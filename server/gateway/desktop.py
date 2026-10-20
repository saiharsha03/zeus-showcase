"""The remote desktop: XFCE on TigerVNC's Xvnc, run as user `desk` by zeus-desktop.service (deploy/desktop-install.sh).

Xvnc listens on a unix socket only (/run/zeus-desktop/vnc.sock, dir desk:zeus 2750, socket 0660): no TCP port, and only
the gateway's group can open it. The phone speaks RFB itself; this module only starts and stops the unit (through
zeus-root, the unit is on its control list) and copies bytes between the phone's WebSocket and the socket.
Idle stop is Xvnc's own: MaxDisconnectionTime ends the session after N minutes with no viewer, even if the gateway is down.
"""
import asyncio
import os
import time

from fastapi import APIRouter, Depends, HTTPException, WebSocket
from fastapi.websockets import WebSocketDisconnect
from pydantic import BaseModel, Field

from . import calls

UNIT = "zeus-desktop.service"
SOCK = os.environ.get("ZEUS_DESKTOP_SOCK", "/run/zeus-desktop/vnc.sock")
IDLE_MIN = int(os.environ.get("ZEUS_DESKTOP_IDLE_MIN", "15"))
MEM_MAX = 1 << 30            # the unit's MemoryMax
CHUNK = 256 * 1024

viewers = 0


async def show() -> dict:
    """The unit's state from systemd (no sudo needed to read it)."""
    p = await asyncio.create_subprocess_exec("systemctl", "show", UNIT, "--no-pager", "--timestamp=unix",
                                             "-p", "ActiveState,SubState,MemoryCurrent,ActiveEnterTimestamp,Result",
                                             stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    out, _ = await asyncio.wait_for(p.communicate(), 15)
    return dict(ln.split("=", 1) for ln in out.decode(errors="replace").splitlines() if "=" in ln)


def _int(v):
    try:
        return int(str(v).lstrip("@"))
    except (TypeError, ValueError):
        return None      # "[not set]", "infinity", empty


def ready() -> bool:
    return os.path.exists(SOCK)


async def status() -> dict:
    st = await show()
    active = st.get("ActiveState", "unknown")
    mem = _int(st.get("MemoryCurrent"))
    return {"state": active, "sub": st.get("SubState", ""), "ready": active == "active" and ready(),
            "memory": mem if active == "active" else None, "memory_max": MEM_MAX,
            "since": _int(st.get("ActiveEnterTimestamp")) if active == "active" else None,
            "viewers": viewers, "idle_minutes": IDLE_MIN}


async def _wait_ready(seconds: float) -> bool:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if ready():
            try:
                _, w = await asyncio.open_unix_connection(SOCK)
                w.close()
                return True
            except OSError:
                pass
        await asyncio.sleep(0.3)
    return False


async def bridge(ws: WebSocket) -> None:
    """Bytes both ways, unchanged. The phone's RFB client does the protocol."""
    global viewers
    try:
        reader, writer = await asyncio.open_unix_connection(SOCK)
    except OSError:
        await ws.close(code=4404)
        return
    await ws.accept()
    viewers += 1

    async def to_phone():
        while data := await reader.read(CHUNK):
            await ws.send_bytes(data)

    async def from_phone():
        while True:
            m = await ws.receive()
            if m["type"] == "websocket.disconnect":
                return
            data = m.get("bytes")
            if data:
                writer.write(data)
                await writer.drain()

    tasks = [asyncio.create_task(to_phone()), asyncio.create_task(from_phone())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        viewers -= 1
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        writer.close()
        try:
            await ws.close()
        except (RuntimeError, WebSocketDisconnect):
            pass


class Action(BaseModel):
    action: str = Field(pattern=r"^(start|stop)$")


def router(device, check, tickets, audit) -> APIRouter:
    """The routes, given app.py's auth pieces (same checks as the terminal)."""
    r = APIRouter()
    api = [Depends(device)]

    @r.get("/api/desktop", dependencies=api)
    async def desktop_status():
        return await status()

    @r.post("/api/desktop", dependencies=api)
    async def desktop_action(b: Action):
        audit("desktop", b.action)
        if b.action == "start":
            if not (await show()).get("ActiveState") == "active":
                await calls.root("unit", "start", UNIT)
            if not await _wait_ready(20):
                raise HTTPException(504, "the desktop didn't come up")
        else:
            await calls.root("unit", "stop", UNIT)
        return await status()

    @r.post("/api/desktop/ticket", dependencies=api)
    def desktop_ticket():
        if not ready():
            raise HTTPException(409, "the desktop isn't running")
        audit("desktop", "view")
        return {"ticket": tickets.issue(kind="desktop")}

    @r.websocket("/ws/desktop")
    async def ws_desktop(ws: WebSocket, ticket: str = ""):
        scope = tickets.redeem(ticket)
        if check(ws.headers) is None or not scope or scope["kind"] != "desktop":
            await ws.close(code=4401)
            return
        await bridge(ws)

    return r
