"""End-to-end check on the VPS (root):  /opt/zeus/venv/bin/python /opt/zeus/deploy/check.py

Through Caddy with a throwaway client certificate (signed by the Zeus CA, registered only while this runs), plus
the socket boundary from the openclaw side. Restores devices.json and deletes the throwaway key at the end.
Prints PASS/FAIL lines, never a key or token; exit 0 only when everything passed.
"""
import datetime as dt
import hashlib
import http.client
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from websockets.sync.client import connect

HOST = "zeus.example.com"
HOOKS = "hooks.example.com"
DEVICES = "/etc/zeus/devices.json"
results = []


def ok(name, cond, detail=""):
    results.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + (f"  ({detail})" if detail and not cond else ""))


def make_cert(tmp):
    ca_cert = x509.load_pem_x509_certificate(open("/etc/zeus/ca.crt", "rb").read())
    ca_key = serialization.load_pem_private_key(open("/etc/zeus/ca.key", "rb").read(), None)
    key = ec.generate_private_key(ec.SECP256R1())
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "zeus-check")]))
            .issuer_name(ca_cert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(minutes=1)).not_valid_after(now + dt.timedelta(hours=1))
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False).sign(ca_key, hashes.SHA256()))
    kp, cp = os.path.join(tmp, "k.pem"), os.path.join(tmp, "c.pem")
    open(kp, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    open(cp, "wb").write(cert.public_bytes(serialization.Encoding.PEM))
    return kp, cp, hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


def call(ctx, method, path, body=None, headers=None, host=HOST):
    c = http.client.HTTPSConnection(host, 443, context=ctx, timeout=20)
    c.request(method, path, body=json.dumps(body) if body is not None else None, headers={"Content-Type": "application/json", **(headers or {})})
    r = c.getresponse()
    data = r.read()
    c.close()
    try:
        return r.status, json.loads(data or b"null")
    except ValueError:
        return r.status, data


def main():
    saved = open(DEVICES).read()
    tmp = tempfile.mkdtemp(prefix="zeus-check-")
    os.chmod(tmp, 0o700)
    try:
        # 1. no certificate: the main host refuses at the TLS handshake
        try:
            ssl.create_default_context().wrap_socket(socket.create_connection((HOST, 443), 10), server_hostname=HOST).recv(1)
            ok("no client certificate is refused", False, "handshake succeeded")
        except (ssl.SSLError, ConnectionError, OSError):
            ok("no client certificate is refused", True)
        # 1b. the hooks host: no certificate needed, 404 on everything but POST /hooks/anthropic
        bare = ssl.create_default_context()
        for m, p in [("GET", "/api/health"), ("GET", "/api/ping"), ("POST", "/api/chat/zeus"), ("GET", "/ws/events"), ("GET", "/"),
                     ("GET", "/hooks/anthropic"), ("PUT", "/hooks/anthropic"), ("POST", "/hooks/anthropic/"), ("POST", "/hooks/other"),
                     ("POST", "/hooks/anthropic/../../api/health")]:
            s, _ = call(bare, m, p, {}, host=HOOKS)
            ok(f"hooks host: {m} {p} 404", s == 404, f"got {s}")
        s, _ = call(bare, "POST", "/hooks/anthropic", {"type": "event"}, host=HOOKS)
        ok("hooks host: unsigned POST /hooks/anthropic refused (401, or 503 before the secret is placed)", s in (401, 503), f"got {s}")
        s, _ = call(bare, "POST", "/hooks/anthropic", {"x": "y" * 70_000}, host=HOOKS)
        ok("hooks host: a body over 64 KB is refused (413)", s == 413, f"got {s}")

        kp, cp, fp = make_cert(tmp)
        ctx = ssl.create_default_context()
        ctx.load_cert_chain(cp, kp)
        decoy = secrets.token_hex(32)

        # 2. a CA-signed but unregistered certificate can't borrow a registered fingerprint by sending headers
        d = json.loads(saved)
        d["devices"] = d.get("devices", []) + [{"sha256": decoy, "name": "zeus-check-decoy"}]
        open(DEVICES, "w").write(json.dumps(d))
        time.sleep(1.1)
        s, _ = call(ctx, "GET", "/api/health", headers={"X-Zeus-Client-SHA256": decoy, "X-Zeus-Proxy-Key": "x" * 64})
        ok("spoofed X-Zeus-* headers are dropped by Caddy (403)", s == 403, f"got {s}")

        # 3. registered: the routes answer
        d["devices"].append({"sha256": fp, "name": "zeus-check"})
        open(DEVICES, "w").write(json.dumps(d))
        time.sleep(1.1)
        for p in ["/api/health", "/api/sessions", "/api/agents", "/api/alerts", "/api/units", "/api/inbox", "/api/slash", "/api/files", "/api/files/home", "/api/jobs", "/api/iris/tasks", "/api/iris/ledger?limit=1", "/api/moneta/ops", "/api/calypso/drops", "/api/usage"]:
            s, b = call(ctx, "GET", p)
            ok(f"GET {p} 200", s == 200, f"got {s} {str(b)[:120]}")
        s, b = call(ctx, "GET", "/api/files/home?path=.ssh")
        ok("secrets folder refused (403)", s == 403, f"got {s}")
        s, b = call(ctx, "GET", "/api/files/home?path=../../etc")
        ok("path escape refused (403)", s == 403, f"got {s}")

        # 3b. Mnemosyne: a throwaway ciphertext-shaped backup goes in, comes back byte for byte, then is removed by hand
        s, b = call(ctx, "GET", "/api/backup")
        ok("GET /api/backup 200 and the box is mounted", s == 200 and b.get("ready"), f"got {s} {str(b)[:120]}")
        bid = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(0)) + "-" + secrets.token_hex(4)
        blob = b"ZBK1" + secrets.token_bytes(4096)
        c = http.client.HTTPSConnection(HOST, 443, context=ctx, timeout=20)
        c.request("PUT", f"/api/backup/{bid}?kind=manual", body=blob, headers={"Content-Type": "application/octet-stream"})
        r = c.getresponse(); r.read(); c.close()
        ok("PUT /api/backup stores a backup", r.status == 200, f"got {r.status}")
        c = http.client.HTTPSConnection(HOST, 443, context=ctx, timeout=20)
        c.request("GET", f"/api/backup/{bid}")
        r = c.getresponse(); back = r.read(); c.close()
        ok("GET /api/backup/<id> returns the same bytes", r.status == 200 and back == blob, f"got {r.status}")
        st = os.stat(f"/mnt/zeus-backups/{bid}.zbk") if os.path.exists(f"/mnt/zeus-backups/{bid}.zbk") else None
        ok("the backup file is zeus-owned 0600", st is not None and st.st_uid == __import__("pwd").getpwnam("zeus").pw_uid and st.st_mode & 0o077 == 0)
        for suffix in (".zbk", ".json"):
            try:
                os.unlink(f"/mnt/zeus-backups/{bid}{suffix}")
            except OSError:
                pass
        r = subprocess.run(["runuser", "-u", "openclaw", "--", "/usr/bin/ls", "/mnt/zeus-backups"], capture_output=True, text=True, timeout=10)
        ok("openclaw cannot list /mnt/zeus-backups", r.returncode != 0)

        # 4. a live terminal on a fresh shell
        s, b = call(ctx, "POST", "/api/shell", {})
        shell = b.get("session") if s == 200 else None
        ok("new shell", bool(shell), f"got {s} {b}")
        if shell:
            s, t = call(ctx, "POST", "/api/term/ticket", {"session": shell})
            seen = False
            try:
                with connect(f"wss://{HOST}/ws/term?ticket={t['ticket']}&cols=100&rows=30", ssl=ctx, open_timeout=10) as ws:
                    first = ws.recv(timeout=10)
                    ok("terminal sends history first", isinstance(first, str) and json.loads(first).get("t") == "history")
                    ws.send(json.dumps({"t": "resize", "cols": 120, "rows": 32}))
                    ws.send(json.dumps({"t": "in", "d": "echo zeus-check-$((40+2))\r"}))
                    end = time.time() + 10
                    buf = b""
                    while time.time() < end and not seen:
                        m = ws.recv(timeout=5)
                        buf += m if isinstance(m, bytes) else m.encode()
                        seen = b"zeus-check-42" in buf
            except Exception as e:
                ok("terminal websocket", False, repr(e)[:160])
            ok("typed into the shell and saw the output", seen)
            try:   # the same ticket a second time
                with connect(f"wss://{HOST}/ws/term?ticket={t['ticket']}&cols=100&rows=30", ssl=ctx, open_timeout=10) as ws:
                    ws.recv(timeout=5)
                ok("a used ticket is refused", False, "second attach worked")
            except Exception:
                ok("a used ticket is refused", True)
            s, _ = call(ctx, "DELETE", f"/api/shell/{shell}")
            ok("shell closed", s == 200, f"got {s}")

        # 5. events socket opens
        s, t = call(ctx, "POST", "/api/events/ticket", {})
        try:
            with connect(f"wss://{HOST}/ws/events?ticket={t['ticket']}&since=0", ssl=ctx, open_timeout=10):
                ok("events websocket opens", True)
        except Exception as e:
            ok("events websocket opens", False, repr(e)[:160])

        # 6. the boundary from openclaw's side
        probe = ("import socket,sys\ns=socket.socket(socket.AF_UNIX)\n"
                 "try:\n s.connect('/run/zeus-gateway/gw.sock'); print('connected')\nexcept OSError as e:\n print(type(e).__name__)\n")
        r = subprocess.run(["runuser", "-u", "openclaw", "--", "/usr/bin/python3", "-I", "-c", probe], capture_output=True, text=True, timeout=10)
        ok("openclaw cannot connect to the gateway socket", r.stdout.strip() in ("PermissionError",), r.stdout.strip() or r.stderr.strip()[:120])
        # and even root, at the socket, gets nothing without Caddy's key
        probe2 = ("import socket\ns=socket.socket(socket.AF_UNIX);s.connect('/run/zeus-gateway/gw.sock')\n"
                  f"s.sendall(b'GET /api/health HTTP/1.1\\r\\nHost: x\\r\\nX-Zeus-Client-SHA256: {fp}\\r\\nConnection: close\\r\\n\\r\\n')\n"
                  "print(s.recv(64).split(b' ')[1].decode())\n")
        r = subprocess.run(["/usr/bin/python3", "-I", "-c", probe2], capture_output=True, text=True, timeout=10)
        ok("a registered fingerprint without the proxy key gets 403", r.stdout.strip() == "403", r.stdout.strip() or r.stderr.strip()[:120])
    finally:
        open(DEVICES, "w").write(saved)
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"{sum(results)}/{len(results)} passed; throwaway certificate removed, devices.json restored")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
