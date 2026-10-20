"""A live terminal: `zeus-openclaw attach SESSION` (tmux attach, as openclaw) in a pseudo-terminal, bridged to the
phone's WebSocket. The phone gets the session's earlier lines first, as {"t":"history","d":...}, then raw bytes.
It sends {"t":"in","d":"..."} and {"t":"resize","cols":n,"rows":n}. Closing the socket detaches; tmux keeps going."""
import asyncio
import fcntl
import json
import os
import secrets
import struct
import termios
import time

from fastapi import WebSocket

from . import calls, config

MAX_IN = 64 * 1024


class Tickets:
    """One-time, 30-second tickets, so a WebSocket URL never carries anything reusable."""

    def __init__(self):
        self._t: dict[str, tuple[float, dict]] = {}

    def issue(self, **scope) -> str:
        now = time.time()
        self._t = {k: v for k, v in self._t.items() if v[0] > now}
        t = secrets.token_urlsafe(24)
        self._t[t] = (now + 30, scope)
        return t

    def redeem(self, t: str) -> dict | None:
        e = self._t.pop(t, None)
        return e[1] if e and e[0] > time.time() else None


def _winsize(fd: int, cols: int, rows: int) -> None:
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


def _num(v, lo, hi, default):
    try:
        return max(lo, min(hi, int(v)))
    except (TypeError, ValueError):
        return default


async def bridge(ws: WebSocket, session: str, read_only: bool, cols, rows) -> None:
    cols, rows = _num(cols, 20, 400, 80), _num(rows, 5, 200, 24)
    try:
        hist = (await calls.openclaw("capture", session, "2000", "esc")).decode(errors="replace")
        await ws.send_text(json.dumps({"t": "history", "d": hist.rstrip("\n").replace("\n", "\r\n") + "\r\n"}))
    except calls.HelperError:
        pass
    master, slave = os.openpty()
    _winsize(master, cols, rows)
    # setsid -c: a new session whose controlling terminal is the pty (stdin), so resizes reach sudo and tmux
    proc = await asyncio.create_subprocess_exec("/usr/bin/setsid", "-c", *config.AS_OPENCLAW, "attach", session, "ro" if read_only else "rw",
                                                stdin=slave, stdout=slave, stderr=slave,
                                                env={"PATH": "/usr/bin:/bin", "TERM": "xterm-256color", "LANG": "C.UTF-8"})
    os.close(slave)
    os.set_blocking(master, False)
    loop = asyncio.get_running_loop()
    q: asyncio.Queue = asyncio.Queue(maxsize=512)

    def readable():
        try:
            data = os.read(master, 65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            loop.remove_reader(master)
        try:
            q.put_nowait(data or None)
        except asyncio.QueueFull:
            pass     # a slow phone loses a frame; tmux's next redraw catches up

    loop.add_reader(master, readable)

    async def to_phone():
        while (data := await q.get()) is not None:
            await ws.send_bytes(data)

    async def from_phone():
        while True:
            raw = await ws.receive_text()
            if len(raw) > MAX_IN:
                continue
            try:
                m = json.loads(raw)
            except ValueError:
                continue
            if m.get("t") == "in" and not read_only:
                data = str(m.get("d", "")).encode()
                while data:
                    try:
                        data = data[os.write(master, data):]
                    except BlockingIOError:
                        await asyncio.sleep(0.01)
            elif m.get("t") == "resize":
                # The kernel signals the terminal's foreground group; sudo passes the size on to tmux.
                _winsize(master, _num(m.get("cols"), 20, 400, cols), _num(m.get("rows"), 5, 200, rows))

    tasks = [asyncio.create_task(to_phone()), asyncio.create_task(from_phone())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        try:
            loop.remove_reader(master)
        except (ValueError, OSError):
            pass
        os.close(master)     # hangs up the terminal: sudo and the tmux client exit; the session keeps running
        if proc.returncode is None:
            try:
                await asyncio.wait_for(proc.wait(), 5)
            except asyncio.TimeoutError:
                try:
                    proc.kill()
                except (ProcessLookupError, PermissionError):
                    pass
        try:
            await ws.close()
        except RuntimeError:
            pass
