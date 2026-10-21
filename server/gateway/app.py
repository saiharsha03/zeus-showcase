"""zeus-gateway: Lemnos, Pantheon and Cerberus for the Zeus phone app (docs/ZEUS-GATEWAY.md).

Runs as user `zeus` on a unix socket that only Caddy's group can open. Caddy has already checked the phone's client
certificate; it passes the fingerprint and a shared key, and both are checked here on every request. The agents
run as openclaw, which can't open the socket, so none of these routes is reachable from their tools.
"""
import asyncio
import hmac
import json
import os
import re
import time

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import calls, config, usage, desktop, term
from .backup import Backups, Rejected as BackupRejected
from .calypso import Calypso
from .moneta import VIEW_MAX as MONETA_VIEW_MAX, Moneta
from .argo import Argo
from .cerberus import Cerberus
from .chat import ROOMS, Chat, Refused
from .fcm import Fcm
from .store import Store

import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import zeus_approval as za  # noqa: E402
import prometheus_queue as pq  # noqa: E402

config.STATE.mkdir(parents=True, exist_ok=True)
store = Store(config.DB)
fcm = Fcm(lambda: list((store.get("fcm_tokens") or {}).values()))


def notify(kind: str, title: str, body: str, room: str | None, severity: str) -> None:
    """A notification the phone fetches from /api/inbox. FCM only says "sync"."""
    with store.tx() as t:
        t.emit("notify", {"kind": kind, "title": title, "body": body, "room": room, "severity": severity})
    if severity in ("high", "medium") or kind == "reply":
        fcm.wake(high=severity == "high")


chat = Chat(store, notify)
cerberus = Cerberus(store, notify)
calypso = Calypso(store)
moneta = Moneta(store, wake=lambda: fcm.wake(high=False))
argo = Argo(store, wake=lambda: fcm.wake(high=False))
chat.trip_info = argo.put
chat.moneta_view = moneta.view
chat.wake = lambda: fcm.wake(high=False)
verifier = za.Verifier(keys_file=config.APPROVAL_KEYS, nonce_db=config.APPROVAL_DB)
prom = pq.Queue(config.PROMETHEUS)
backups = Backups(config.MNEMOSYNE, config.MNEMOSYNE_MAX, config.MNEMOSYNE_REQUIRE_MOUNT)
_prom_seen: dict[str, str] = {}
tickets = term.Tickets()
app = FastAPI(title="zeus-gateway", docs_url=None, redoc_url=None, openapi_url=None)

# ---- live events -----------------------------------------------------------------------------------
_listeners: set[tuple[asyncio.AbstractEventLoop, asyncio.Queue]] = set()


def _broadcast(ev: dict) -> None:
    for loop, q in list(_listeners):
        loop.call_soon_threadsafe(lambda q=q: q.full() or q.put_nowait(ev))


store.listeners.append(_broadcast)


async def _loop():
    n = 0
    while True:
        try:
            chat.read_spool()
            cerberus.tick(slow=n % 60 == 0)     # files every minute, the spools every second
            calypso.intake()
            moneta.intake()
            if n % 5 == 0:
                _prom_tick()
            if n % 60 == 0:
                chat.expire()
        except Exception as e:
            store.audit("loop.error", repr(e))
        n += 1
        await asyncio.sleep(1)


# The event loop keeps only weak references to tasks: without these, the channel server can be collected mid-wait
# and Iris's plugin gets "connection refused" until the next restart.
_tasks: set = set()


def _keep(make) -> None:
    t = asyncio.create_task(make())
    _tasks.add(t)

    def done(t):
        _tasks.discard(t)
        if not t.cancelled():
            print(f"zeus-gateway: {make.__name__} stopped ({t.exception()!r}); restarting in 5 s", flush=True)
            asyncio.get_running_loop().call_later(5, _keep, make)
    t.add_done_callback(done)


@app.on_event("startup")
async def _start():
    _keep(_loop)
    _keep(chat.serve_channel)


# ---- auth: Caddy's word, twice ---------------------------------------------------------------------
_cache: dict = {"devices": (None, set()), "key": (None, b"")}


def _devices() -> set[str]:
    try:
        m = os.stat(config.DEVICES).st_mtime
    except OSError:
        return set()
    if _cache["devices"][0] != m:
        try:
            d = json.loads(config.DEVICES.read_text())
            fps = {str(x.get("sha256", "")).lower().replace(":", "") for x in d.get("devices", [])}
        except (OSError, ValueError):
            fps = set()
        _cache["devices"] = (m, fps)
    return _cache["devices"][1]


def _proxy_key() -> bytes:
    try:
        m = os.stat(config.PROXY_KEY).st_mtime
    except OSError:
        return b""
    if _cache["key"][0] != m:
        _cache["key"] = (m, config.PROXY_KEY.read_bytes().strip())
    return _cache["key"][1]


def proxied(headers) -> bool:
    """True when the request came through Caddy: it carries the shared proxy key."""
    key = _proxy_key()
    return len(key) >= 32 and hmac.compare_digest((headers.get("x-zeus-proxy-key") or "").encode(), key)


def check(headers) -> str | None:
    """The device fingerprint when both the proxy key and a registered fingerprint are present; else None."""
    if not proxied(headers):
        return None
    fp = (headers.get("x-zeus-client-sha256") or "").strip().lower().replace(":", "")
    return fp if re.fullmatch(r"[0-9a-f]{64}", fp) and fp in _devices() else None


def device(request: Request) -> str:
    fp = check(request.headers)
    if fp is None:
        raise HTTPException(403, "unknown device")
    return fp


api = [Depends(device)]


def audit(action, detail):
    store.audit(action, detail)


# ---- health ----------------------------------------------------------------------------------------
@app.get("/api/ping")
def ping():
    return {"ok": True}


def _meminfo() -> dict[str, int]:
    out = {}
    for line in open("/proc/meminfo"):
        k, v = line.split(":", 1)
        out[k] = int(v.split()[0]) * 1024
    return out


@app.get("/api/health", dependencies=api)
async def health():
    m = _meminfo()
    try:
        disks = await calls.openclaw_json("disk", timeout=8)
    except HTTPException:
        disks = []
    return {"host": os.uname().nodename, "uptime": int(float(open("/proc/uptime").read().split()[0])), "load": list(os.getloadavg()),
            "cpus": os.cpu_count(), "mem": {"total": m["MemTotal"], "available": m["MemAvailable"]},
            "swap": {"total": m.get("SwapTotal", 0), "free": m.get("SwapFree", 0)}, "disks": disks}


# ---- sessions + terminal (Lemnos) -------------------------------------------------------------------
def _kind(name: str) -> tuple[str | None, str]:
    for k, a in config.AGENTS.items():
        if a["session"] == name:
            return k, a["kind"]
    return None, "shell" if re.fullmatch(r"shell-\d+", name) else "other"


@app.get("/api/sessions", dependencies=api)
async def sessions():
    out = []
    for s in await chat.sessions():
        agent, kind = _kind(s["name"])
        out.append({k: s[k] for k in ("name", "created", "attached", "activity")} | {"agent": agent, "kind": kind})
    return out


@app.post("/api/shell", dependencies=api)
async def new_shell():
    r = await calls.openclaw_json("new-shell")
    audit("shell.new", r["session"])
    return r


@app.delete("/api/shell/{name}", dependencies=api)
async def close_shell(name: str):
    await calls.openclaw_json("kill-shell", name)
    audit("shell.close", name)
    return {"ok": True}


@app.get("/api/sessions/{name}/screen", dependencies=api)
async def screen(name: str, lines: int = 2000):
    return {"text": (await calls.openclaw("capture", name, str(max(10, min(lines, 10000))))).decode(errors="replace")}


class InputBody(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    submit: bool = True


@app.post("/api/sessions/{name}/input", dependencies=api)
async def type_input(name: str, b: InputBody):
    agent, _ = _kind(name)
    if agent:
        await chat.sessions()
        if not chat._running(agent):
            raise HTTPException(409, f"{config.AGENTS[agent]['proc']} isn't running in {name}; typing there would go to a bare shell")
    audit("input", f"{name}: {b.text[:120]}")
    await calls.openclaw("type", name, "submit" if b.submit else "hold", stdin=b.text.encode())
    return {"ok": True}


class KeyBody(BaseModel):
    key: str = Field(max_length=16)


@app.post("/api/sessions/{name}/key", dependencies=api)
async def press(name: str, b: KeyBody):
    await calls.openclaw("key", name, b.key)
    return {"ok": True}


@app.get("/api/slash", dependencies=api)
def slash():
    return {k: [{"cmd": c, "hint": h} for c, h in v] for k, v in config.SLASH.items()}


class TicketBody(BaseModel):
    session: str = Field(max_length=48)
    ro: bool = False


@app.post("/api/term/ticket", dependencies=api)
async def term_ticket(b: TicketBody):
    if b.session not in {s["name"] for s in await chat.sessions()}:
        raise HTTPException(404, "no such session")
    audit("terminal", f"{b.session} {'ro' if b.ro else 'rw'}")
    return {"ticket": tickets.issue(kind="term", session=b.session, ro=b.ro)}


@app.websocket("/ws/term")
async def ws_term(ws: WebSocket, ticket: str = "", cols: int = 80, rows: int = 24):
    scope = tickets.redeem(ticket)
    if check(ws.headers) is None or not scope or scope["kind"] != "term":
        await ws.close(code=4401)
        return
    await ws.accept()
    await term.bridge(ws, scope["session"], scope["ro"], cols, rows)


# ---- remote desktop (gateway/desktop.py) ---------------------------------------------------------
app.include_router(desktop.router(device, check, tickets, audit))
from . import webhooks; app.include_router(webhooks.router(lambda: fcm.wake(high=True), proxied))  # noqa: E402,E702  Anthropic webhook: signed + proxy key, no device cert


# ---- files -----------------------------------------------------------------------------------------
@app.get("/api/files", dependencies=api)
async def roots():
    return await calls.openclaw_json("disk", timeout=8)


@app.get("/api/files/{root}", dependencies=api)
async def ls(root: str, path: str = ""):
    return await calls.openclaw_json("ls", root, path)


@app.get("/api/files/{root}/download", dependencies=api)
async def download(root: str, path: str):
    proc, first = await calls.stream_read(root, path, 8 << 30)
    name = re.sub(r'["\\\r\n]', "", os.path.basename(path)) or "file"

    async def body():
        try:
            if first:
                yield first
            while chunk := await proc.stdout.read(1 << 20):
                yield chunk
        finally:
            if proc.returncode is None:
                proc.kill()
    audit("file.download", f"{root}:{path}")
    return StreamingResponse(body(), media_type="application/octet-stream", headers={"Content-Disposition": f'attachment; filename="{name}"'})


@app.get("/api/files/{root}/text", dependencies=api)
async def read_text(root: str, path: str):
    data = await calls.openclaw("read", root, path, str(1 << 20))
    try:
        return {"text": data.decode("utf-8")}
    except UnicodeDecodeError:
        raise HTTPException(415, "not a text file")


@app.put("/api/files/{root}/raw", dependencies=api)
async def upload(root: str, path: str, request: Request):
    audit("file.upload", f"{root}:{path}")
    return json.loads(await calls.stream_write(root, path, request.stream(), 2 << 30))


class PathBody(BaseModel):
    path: str = Field(max_length=1024)
    to: str | None = Field(None, max_length=1024)
    to_root: str | None = Field(None, max_length=16)


@app.post("/api/files/{root}/mkdir", dependencies=api)
async def mkdir(root: str, b: PathBody):
    return await calls.openclaw_json("mkdir", root, b.path)


@app.post("/api/files/{root}/rename", dependencies=api)
async def rename(root: str, b: PathBody):
    if not b.to:
        raise HTTPException(400, "missing 'to'")
    audit("file.rename", f"{root}:{b.path} -> {b.to}")
    return await calls.openclaw_json("rename", root, b.path, b.to)


@app.post("/api/files/{root}/move", dependencies=api)
async def move(root: str, b: PathBody):
    if not b.to or not b.to_root:
        raise HTTPException(400, "missing 'to_root' or 'to'")
    audit("file.move", f"{root}:{b.path} -> {b.to_root}:{b.to}")
    return await calls.openclaw_json("move", root, b.path, b.to_root, b.to, timeout=600)


@app.post("/api/files/{root}/delete", dependencies=api)
async def delete(root: str, b: PathBody):
    audit("file.trash", f"{root}:{b.path}")
    return {"ok": True, **await calls.openclaw_json("trash", root, b.path)}


# ---- services --------------------------------------------------------------------------------------
UNIT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9@._-]{0,120}\.(service|timer)$")


def _controllable() -> set[str]:
    try:
        return set(json.loads(open("/etc/zeus/units.json").read()).get("control", []))
    except (OSError, ValueError):
        return set()


async def _sh(*argv) -> str:
    p = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    out, _ = await asyncio.wait_for(p.communicate(), 15)
    return out.decode(errors="replace")


@app.get("/api/units", dependencies=api)
async def units():
    units = json.loads(await _sh("systemctl", "list-units", "--type=service,timer", "--all", "--output=json", "--no-pager") or "[]")
    timers = {t.get("unit"): t for t in json.loads(await _sh("systemctl", "list-timers", "--all", "--output=json", "--no-pager") or "[]")}
    ctl, us = _controllable(), lambda v: int(v) // 1_000_000 if v else None
    rows = [{"unit": u["unit"], "kind": u["unit"].rsplit(".", 1)[1], "active": u.get("active"), "sub": u.get("sub"), "description": u.get("description", ""),
             "controllable": u["unit"] in ctl, "last": us(timers.get(u["unit"], {}).get("last")), "next": us(timers.get(u["unit"], {}).get("next"))}
            for u in units if UNIT.match(u.get("unit", "")) and u.get("load") != "not-found"]
    return sorted(rows, key=lambda r: (r["kind"] != "service", r["unit"]))


@app.get("/api/units/{unit}", dependencies=api)
async def unit(unit: str, lines: int = 200):
    if not UNIT.match(unit):
        raise HTTPException(400, "bad unit name")
    state = dict(ln.split("=", 1) for ln in (await _sh("systemctl", "show", unit, "--no-pager", "-p",
                 "ActiveState,SubState,Result,ExecMainStatus,ActiveEnterTimestamp,Description")).splitlines() if "=" in ln)
    try:
        logs = (await calls.root("journal", unit, str(max(10, min(lines, 2000))), timeout=25)).decode(errors="replace")
    except HTTPException as e:
        logs = f"(journal unavailable: {e.detail})"
    return {"state": state, "logs": logs, "controllable": unit in _controllable()}


class UnitAction(BaseModel):
    action: str = Field(pattern=r"^(restart|start|stop)$")


@app.post("/api/units/{unit}", dependencies=api)
async def unit_action(unit: str, b: UnitAction):
    if not UNIT.match(unit):
        raise HTTPException(400, "bad unit name")
    audit("unit", f"{b.action} {unit}")
    await calls.root("unit", b.action, unit)
    return {"ok": True}


# ---- Pantheon ----------------------------------------------------------------------------------------
@app.get("/api/agents", dependencies=api)
async def agents():
    return await chat.status()


def _room(room: str) -> str:
    if room not in ROOMS:
        raise HTTPException(404, "unknown room")
    return room


@app.get("/api/usage", dependencies=api)
async def plan_usage():
    """Metron: each worker's plan windows {label, used_pct, resets_at}, model and when it was last read."""
    try:
        atlas = usage.codex(await calls.openclaw_json("codex-usage", timeout=10))
    except HTTPException as e:
        atlas = {"provider": "codex", "windows": [], "model": None, "updated": None, "note": f"unavailable: {e.detail}"}
    out = []
    for aid, rec in (("iris", usage.claude(usage.read_file(config.USAGE_DIR / "iris.json"))), ("atlas", atlas)):
        out.append({"id": aid, "name": config.AGENTS[aid]["name"], **rec})
    return {"agents": out, "now": int(time.time())}


@app.get("/api/chat/{room}", dependencies=api)
def history(room: str, before: int | None = None, after: int | None = None, limit: int = 50):
    return chat.history(_room(room), before, max(1, min(limit, 200)), after)


class Quoted(BaseModel):
    source: str = Field(max_length=120)
    text: str = Field(max_length=20000)


class ChatBody(BaseModel):
    text: str = Field(min_length=1, max_length=20000)
    client_id: str | None = Field(None, max_length=64)
    via: str | None = Field(None, pattern=r"^athena$")
    channel: str | None = Field(None, pattern=r"^(typed|voice)$")
    quoted: list[Quoted] | None = Field(None, max_length=10)


@app.post("/api/chat/{room}", dependencies=api)
async def send(room: str, b: ChatBody):
    try:
        return await chat.send(_room(room), b.text, b.client_id, b.via, b.channel, [q.model_dump() for q in b.quoted or []])
    except Refused as e:
        raise HTTPException(409, str(e))


class JobBody(BaseModel):
    job_id: str = Field(pattern=r"^[A-Za-z0-9_.:-]{1,80}$")
    to: str
    kind: str = Field(pattern=r"^[a-z0-9_.-]{1,60}$")
    title: str = Field(min_length=1, max_length=300)
    payload: dict = Field(default_factory=dict)
    approval: str | None = Field(None, max_length=4000)
    # The owner's own request from Horologion ("change your cron", "turn that hook off"): only with his signed approval.
    by: str | None = Field(None, pattern=r"^owner$")


@app.post("/api/jobs", dependencies=api)
async def submit_job(b: JobBody):
    if b.to not in config.AGENTS:
        raise HTTPException(404, "unknown worker")
    if b.by and not b.approval:
        raise HTTPException(400, "a request from the owner carries his approval")
    approved, exp = False, None
    if b.approval:
        try:
            exp = verifier.verify(b.approval, b.job_id, b.kind, b.to, b.payload)["exp"]
            approved = True
        except (za.Rejected, ValueError) as e:
            audit("approval.rejected", f"{b.job_id}: {e}")
            raise HTTPException(403, f"approval rejected: {e}")
    audit("job", f"{b.job_id} {b.kind} -> {b.to} approved={approved}")
    try:
        return await chat.submit_job(b.job_id, b.to, b.kind, b.title, b.payload, approved, b.approval if approved else None, exp, by=b.by)
    except ValueError as e:
        raise HTTPException(409, str(e))


@app.get("/api/jobs", dependencies=api)
def jobs(limit: int = 100):
    return chat.jobs(max(1, min(limit, 500)))


@app.get("/api/events", dependencies=api)
def events(since: int = 0):
    return store.events_since(since)


@app.post("/api/events/ticket", dependencies=api)
def events_ticket():
    return {"ticket": tickets.issue(kind="events")}


@app.websocket("/ws/events")
async def ws_events(ws: WebSocket, ticket: str = "", since: int = 0):
    scope = tickets.redeem(ticket)
    if check(ws.headers) is None or not scope or scope["kind"] != "events":
        await ws.close(code=4401)
        return
    await ws.accept()
    q: asyncio.Queue = asyncio.Queue(maxsize=500)
    entry = (asyncio.get_running_loop(), q)
    _listeners.add(entry)
    try:
        for ev in store.events_since(since):
            await ws.send_json(ev)
        while True:
            await ws.send_json(await q.get())
    except Exception:
        pass
    finally:
        _listeners.discard(entry)


# ---- Cerberus + push -----------------------------------------------------------------------------------
@app.get("/api/alerts", dependencies=api)
def alerts(since: int = 0, limit: int = 100):
    return cerberus.list(since, max(1, min(limit, 500)))


@app.post("/api/alerts/{aid}/ack", dependencies=api)
def ack(aid: int):
    if not cerberus.ack(aid):
        raise HTTPException(404, "no such alert")
    return {"ok": True}


class PushBody(BaseModel):
    token: str = Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_:.\-]+$")


@app.post("/api/push/register")
def push_register(b: PushBody, fp: str = Depends(device)):
    with store.tx() as t:
        tokens = store.get("fcm_tokens") or {}
        tokens[fp] = b.token
        t.put("fcm_tokens", tokens)
    audit("push.register", fp[:16])
    return {"ok": True, "push": fcm.available()}


@app.get("/api/inbox", dependencies=api)
def inbox(since: int = 0):
    evs = store.events_since(since, 1000)
    items = [{"id": e["seq"], "kind": e["kind"], "title": e["title"], "body": e["body"], "severity": e["severity"], "room": e["room"]}
             for e in evs if e["type"] == "notify"]
    return {"seq": evs[-1]["seq"] if evs else since, "items": items[-50:]}


# ---- Iris's tasks (owner.db through zeus-openclaw) -----------------------------------------------------
@app.get("/api/iris/tasks", dependencies=api)
async def iris_tasks(status: str = "pending", owner: str = "-", reveal: int = 0):
    return await calls.openclaw_json("tasks", status, owner, "1" if reveal else "0")


@app.get("/api/iris/tables", dependencies=api)
def iris_tables():
    return ["closet", "fillups", "subscriptions", "workouts", "nightly_ideas"]


@app.get("/api/iris/table/{name}", dependencies=api)
async def iris_table(name: str, limit: int = 50):
    return await calls.openclaw_json("table", name, str(limit))


class TaskBody(BaseModel):
    approval: str = Field(max_length=4000)
    text: str | None = Field(None, max_length=1000)


def _approved(kind: str, payload: dict, token: str) -> None:
    """Task writes are the owner's own, signed on the phone: kind and payload must match, executor is the gateway."""
    try:
        claims, _, _ = za.parse(token)
        verifier.verify(token, claims["job_id"], kind, "zeus-gateway", payload)
    except (za.Rejected, ValueError) as e:
        audit("approval.rejected", f"{kind}: {e}")
        raise HTTPException(403, f"approval rejected: {e}")


@app.post("/api/iris/tasks/{tid}/done", dependencies=api)
async def iris_done(tid: int, b: TaskBody):
    _approved("iris.task_done", {"task_id": tid}, b.approval)
    audit("iris.task_done", tid)
    return await calls.openclaw_json("task-done", str(tid))


@app.post("/api/iris/tasks/{tid}/note", dependencies=api)
async def iris_note(tid: int, b: TaskBody):
    if not (b.text or "").strip():
        raise HTTPException(400, "missing text")
    _approved("iris.task_note", {"task_id": tid, "text": b.text}, b.approval)
    audit("iris.task_note", tid)
    return await calls.openclaw_json("task-note", str(tid), stdin=b.text.encode())


# ---- Horologion: every job, skill, hook and setting, by name (the helper sends names and schedules, never contents) ----
@app.get("/api/iris/jobs", dependencies=api)
async def iris_jobs():
    return await calls.openclaw_json("iris-jobs", timeout=10)


@app.get("/api/iris/setup", dependencies=api)
async def iris_setup():
    return await calls.openclaw_json("iris-setup", timeout=15)


@app.get("/api/atlas/setup", dependencies=api)
async def atlas_setup():
    return await calls.openclaw_json("atlas-setup", timeout=15)


def caddy_sites(text: str) -> list[str]:
    """Site addresses from a Caddyfile: host names only (no ports, paths or directives)."""
    out = []
    for ln in text.splitlines():
        if ln[:1].isspace() or not ln.rstrip().endswith("{") or ln.lstrip().startswith(("(", "#", "{")):
            continue
        for addr in ln.rstrip()[:-1].replace(",", " ").split():
            a = re.sub(r"^[a-z]+://", "", addr).split("/")[0]
            host = re.sub(r":\d+$", "", a)
            if re.fullmatch(r"[A-Za-z0-9*.-]{1,253}", host) and "." in host:
                out.append(host)
    return sorted(set(out))


@app.get("/api/server/setup", dependencies=api)
async def server_setup():
    try:
        sites = caddy_sites(config.CADDYFILE.read_text(errors="replace"))
    except OSError:
        sites = []
    newest = max((b.get("created", 0) for b in backups.all()), default=None)
    borg = {}
    try:
        borg = dict(ln.split("=", 1) for ln in (await _sh("systemctl", "show", config.BACKUP_UNIT, "--no-pager", "-p", "ActiveState,Result,ExecMainExitTimestamp")).splitlines() if "=" in ln)
    except (OSError, asyncio.TimeoutError):
        pass
    return {"caddy_sites": sites, "mnemosyne_last": newest, "server_backup": {"unit": config.BACKUP_UNIT, "result": borg.get("Result"), "finished": borg.get("ExecMainExitTimestamp")},
            "controllable_units": sorted(_controllable())}


# ---- Moneta: Iris's full money log, and her ops on Moneta ----------------------------------------------------
@app.get("/api/iris/ledger", dependencies=api)
async def iris_ledger(since: int = 0, limit: int = 500):
    return await calls.openclaw_json("money-ledger", str(max(0, since)), str(max(1, min(limit, 1000))))


@app.get("/api/moneta/ops", dependencies=api)
def moneta_ops():
    return moneta.ops()


class OpsAck(BaseModel):
    ids: list[str] = Field(max_length=500)


@app.post("/api/moneta/ops/ack", dependencies=api)
def moneta_ack(b: OpsAck):
    return {"ok": True, "removed": moneta.ack(b.ids)}


@app.put("/api/moneta/view", dependencies=api)
async def moneta_view_put(request: Request):
    """The phone's view of Moneta for Iris (her moneta_view tool reads it over the channel). At most 512 KB."""
    raw = b""
    async for chunk in request.stream():
        raw += chunk
        if len(raw) > MONETA_VIEW_MAX:
            raise HTTPException(413, "view too big")
    try:
        return {"ok": True, "rows": moneta.put_view(raw)}
    except ValueError as e:
        raise HTTPException(400, str(e)[:200])


# ---- Argo: Iris's trip.info (over the zeus channel only) ---------------------------------------------
@app.get("/api/argo/info", dependencies=api)
def argo_info():
    return argo.items()


@app.post("/api/argo/info/ack", dependencies=api)
def argo_ack(b: OpsAck):
    return {"ok": True, "removed": argo.ack(b.ids)}


# ---- Calypso drops (sealed by the sender; ciphertext only) -----------------------------------------------
class KeyBody(BaseModel):
    pem: str = Field(min_length=200, max_length=4000)


@app.put("/api/calypso/key", dependencies=api)
def calypso_key(b: KeyBody):
    try:
        calypso.publish_key(b.pem)
    except ValueError as e:
        raise HTTPException(400, str(e))
    audit("calypso.key", "published")
    return {"ok": True}


@app.get("/api/calypso/drops", dependencies=api)
def calypso_drops():
    return calypso.drops()


class AckBody(BaseModel):
    ids: list[str] = Field(max_length=200)


@app.post("/api/calypso/drops/ack", dependencies=api)
def calypso_ack(b: AckBody):
    return {"ok": True, "removed": calypso.ack(b.ids)}


# ---- Prometheus: app-change requests (built on the owner's laptop, never here) ---------------------------
def _prom_tick() -> None:
    """Wakes the phone when the laptop publishes a build or a failure. The first pass only remembers."""
    first = not _prom_seen
    for r in prom.all(200):
        old = _prom_seen.get(r["id"])
        _prom_seen[r["id"]] = r["status"]
        if not first and old is not None and old != r["status"] and r["status"] in ("ready", "failed"):
            notify("job", "Update ready" if r["status"] == "ready" else "Build failed", r["problem"][:120], None, "medium")
    _prom_seen.setdefault("", "")   # marks the first pass done even when the queue is empty


class PromBody(BaseModel):
    id: str = Field(pattern=r"^p[0-9a-f]{12}$")
    problem: str = Field(min_length=1, max_length=1000)
    approval: str = Field(max_length=4000)


@app.post("/api/prometheus/requests", dependencies=api)
def prom_request(b: PromBody):
    """Only a request his fingerprint signed (kind prometheus.request, payload {id, problem}) is queued."""
    try:
        verifier.verify(b.approval, b.id, "prometheus.request", "zeus-gateway", {"id": b.id, "problem": b.problem})
    except (za.Rejected, ValueError) as e:
        audit("approval.rejected", f"prometheus {b.id}: {e}")
        raise HTTPException(403, f"approval rejected: {e}")
    try:
        r = prom.add(b.id, b.problem)
    except FileExistsError:
        raise HTTPException(409, "already queued")
    except ValueError as e:
        raise HTTPException(400, str(e))
    audit("prometheus.request", b.id)
    return r


@app.get("/api/prometheus/requests", dependencies=api)
def prom_requests(limit: int = 50):
    return prom.all(max(1, min(limit, 200)))


@app.post("/api/prometheus/requests/{rid}/cancel", dependencies=api)
def prom_cancel(rid: str):
    try:
        r = prom.cancel(rid)
    except KeyError:
        raise HTTPException(404, "no such request")
    except ValueError as e:
        raise HTTPException(409, str(e))
    audit("prometheus.cancel", rid)
    return r


@app.get("/api/prometheus/requests/{rid}/apk", dependencies=api)
def prom_apk(rid: str):
    try:
        p = prom.apk(rid)
    except ValueError:
        p = None
    if p is None:
        raise HTTPException(404, "no build ready")
    audit("prometheus.download", rid)

    def chunks():
        with open(p, "rb") as f:
            while b := f.read(64 * 1024):
                yield b
    return StreamingResponse(chunks(), media_type="application/vnd.android.package-archive", headers={"Content-Length": str(p.stat().st_size)})


# ---- Mnemosyne: encrypted backups (ciphertext only; the key never leaves the phone) ------------------------
@app.get("/api/backup", dependencies=api)
def backup_list():
    return {"backups": backups.all(), **backups.status()}


@app.put("/api/backup/{bid}", dependencies=api)
async def backup_put(bid: str, request: Request, kind: str = "nightly"):
    length = request.headers.get("content-length")
    try:
        m = await backups.put(bid, kind, request.stream(), int(length) if length and length.isdigit() else None, time.time())
    except BackupRejected as e:
        audit("backup.rejected", f"{bid}: {e}")
        raise HTTPException(e.code, str(e))
    audit("backup.put", f"{bid} {m['size']} bytes, pruned {len(m['pruned'])}")
    return m


@app.get("/api/backup/{bid}", dependencies=api)
def backup_get(bid: str):
    try:
        p = backups.path(bid)
    except BackupRejected as e:
        raise HTTPException(e.code, str(e))
    audit("backup.get", bid)

    def chunks():
        with open(p, "rb") as f:
            while b := f.read(256 * 1024):
                yield b
    return StreamingResponse(chunks(), media_type="application/octet-stream", headers={"Content-Length": str(p.stat().st_size)})


@app.get("/api/audit", dependencies=api)
def audit_log(limit: int = 100):
    return store.q("SELECT * FROM audit ORDER BY id DESC LIMIT ?", (max(1, min(limit, 500)),))
