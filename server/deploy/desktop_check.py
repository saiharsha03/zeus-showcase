"""End-to-end check of the remote desktop on the VPS (root):  /opt/zeus/venv/bin/python /opt/zeus/deploy/desktop_check.py [shot.png]

Like check.py: through Caddy with a throwaway client certificate registered only while this runs. Starts the desktop
through the API, opens a viewer ticket, speaks RFB over the WebSocket (handshake, a full Raw frame, a click and a key),
saves the frame as a PNG if asked, checks the boundaries (used ticket, openclaw at the socket), then stops it.
Restores devices.json and deletes the throwaway key at the end. Prints PASS/FAIL lines, never a key or token.
"""
import json
import os
import shutil
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import zlib

from websockets.sync.client import connect

sys.path.insert(0, os.path.dirname(__file__))
from check import DEVICES, HOST, call, make_cert, ok, results  # noqa: E402


class Rfb:
    def __init__(self, ws):
        self.ws, self.buf = ws, b""

    def read(self, n):
        while len(self.buf) < n:
            m = self.ws.recv(timeout=20)
            self.buf += m if isinstance(m, bytes) else m.encode()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, b):
        self.ws.send(b)

    def handshake(self):
        ver = self.read(12)
        self.send(b"RFB 003.008\n")
        types = self.read(self.read(1)[0])
        self.send(b"\x01")
        res = struct.unpack(">I", self.read(4))[0]
        self.send(b"\x01")
        w, h = struct.unpack(">HH", self.read(4))
        self.read(16)
        name = self.read(struct.unpack(">I", self.read(4))[0]).decode()
        self.w, self.h = w, h
        self.send(bytes([0, 0, 0, 0, 32, 24, 0, 1]) + struct.pack(">HHHBBB", 255, 255, 255, 16, 8, 0) + b"\0\0\0")
        self.send(struct.pack(">BBHi", 2, 0, 1, 0))                      # Raw only
        return ver, types, res, name

    def frame(self):
        """A full, non-incremental update as raw BGRX rows."""
        self.send(struct.pack(">BBHHHH", 3, 0, 0, 0, self.w, self.h))
        fb = bytearray(self.w * self.h * 4)
        while True:
            t = self.read(1)[0]
            if t == 0:
                break
            if t == 3:
                self.read(3); self.read(struct.unpack(">I", self.read(4))[0])
            elif t != 2:
                raise ValueError(f"message {t}")
        self.read(1)
        for _ in range(struct.unpack(">H", self.read(2))[0]):
            x, y, w, h, enc = struct.unpack(">HHHHi", self.read(12))
            if enc != 0:
                raise ValueError(f"encoding {enc}")
            data = self.read(w * h * 4)
            for r in range(h):
                o = ((y + r) * self.w + x) * 4
                fb[o:o + w * 4] = data[r * w * 4:(r + 1) * w * 4]
        return fb


def png(path, w, h, bgrx):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = bgrx[y * w * 4:(y + 1) * w * 4]
        rgb = bytearray(w * 3)
        rgb[0::3], rgb[1::3], rgb[2::3] = row[2::4], row[1::4], row[0::4]
        raw += rgb
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def main():
    shot = sys.argv[1] if len(sys.argv) > 1 else None
    saved = open(DEVICES).read()
    tmp = tempfile.mkdtemp(prefix="zeus-desk-check-")
    os.chmod(tmp, 0o700)
    try:
        kp, cp, fp = make_cert(tmp)
        ctx = ssl.create_default_context()
        ctx.load_cert_chain(cp, kp)
        s, _ = call(ctx, "GET", "/api/desktop")
        ok("unregistered certificate gets 403 on /api/desktop", s == 403, f"got {s}")
        d = json.loads(saved)
        d["devices"] = d.get("devices", []) + [{"sha256": fp, "name": "zeus-desk-check"}]
        open(DEVICES, "w").write(json.dumps(d))
        time.sleep(1.1)

        s, b = call(ctx, "POST", "/api/desktop", {"action": "stop"})
        ok("stop (clean slate)", s == 200 and b["state"] in ("inactive", "deactivating"), f"got {s} {b}")
        s, _ = call(ctx, "POST", "/api/desktop/ticket", {})
        ok("no viewer ticket while stopped (409)", s == 409, f"got {s}")
        t0 = time.time()
        s, b = call(ctx, "POST", "/api/desktop", {"action": "start"})
        ok("start through zeus-root", s == 200 and b.get("ready"), f"got {s} {b}")
        print(f"     started in {time.time() - t0:.1f}s")
        time.sleep(4)          # let XFCE settle

        s, t = call(ctx, "POST", "/api/desktop/ticket", {})
        ok("viewer ticket", s == 200 and t.get("ticket"), f"got {s}")
        with connect(f"wss://{HOST}/ws/desktop?ticket={t['ticket']}", ssl=ctx, open_timeout=10, max_size=None) as ws:
            r = Rfb(ws)
            ver, types, res, name = r.handshake()
            ok("RFB 3.8 greeting through Caddy and the gateway", ver == b"RFB 003.008\n", repr(ver))
            ok("security None offered, result OK", 1 in types and res == 0, f"{list(types)} {res}")
            print(f"     desktop '{name}' {r.w}x{r.h}")
            fb = r.frame()
            ok("a full frame arrives", len(fb) == r.w * r.h * 4 and any(fb[0:r.w * 4 * 40]))
            r.send(struct.pack(">BBHH", 5, 0, r.w // 2, r.h // 2))         # pointer move
            r.send(struct.pack(">BBHI", 4, 1, 0, 0xffe3) + struct.pack(">BBHI", 4, 0, 0, 0xffe3))   # Ctrl down/up
            fb2 = r.frame()
            ok("still talking after pointer and key events", len(fb2) == len(fb))
            if shot:
                png(shot, r.w, r.h, fb2)
                print(f"     frame saved to {shot}")
            s, st = call(ctx, "GET", "/api/desktop")
            ok("status counts the viewer", s == 200 and st["viewers"] >= 1, f"{st}")
            print(f"     memory while viewing: {st['memory'] / 2**20:.0f} MB of {st['memory_max'] / 2**20:.0f} MB cap")
        try:
            with connect(f"wss://{HOST}/ws/desktop?ticket={t['ticket']}", ssl=ctx, open_timeout=10) as ws:
                ws.recv(timeout=5)
            ok("a used ticket is refused", False, "second viewer worked")
        except Exception:
            ok("a used ticket is refused", True)

        probe = ("import socket\ns=socket.socket(socket.AF_UNIX)\n"
                 "try:\n s.connect('/run/zeus-desktop/vnc.sock'); print('connected')\nexcept OSError as e:\n print(type(e).__name__)\n")
        r2 = subprocess.run(["runuser", "-u", "openclaw", "--", "/usr/bin/python3", "-I", "-c", probe], capture_output=True, text=True, timeout=10)
        ok("openclaw cannot open the VNC socket", r2.stdout.strip() == "PermissionError", r2.stdout.strip() or r2.stderr.strip()[:120])
        ss = subprocess.run(["ss", "-tlnp"], capture_output=True, text=True).stdout
        ok("Xvnc has no TCP listener", "Xvnc" not in ss)

        if os.environ.get("KEEP_RUNNING") != "1":
            s, b = call(ctx, "POST", "/api/desktop", {"action": "stop"})
            ok("stop", s == 200 and b["state"] != "active", f"got {s} {b}")
    finally:
        open(DEVICES, "w").write(saved)
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"{sum(results)}/{len(results)} passed; throwaway certificate removed, devices.json restored")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
