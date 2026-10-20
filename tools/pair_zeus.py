"""Pair the phone with Lemnos (laptop side, over adb). Nothing secret moves: a certificate request, a public key,
and the signed certificate coming back.

    python tools/pair_zeus.py <adb-serial>

1. In Zeus: Lemnos > Make the keys (writes files/zeus-pair/zeus.csr and approval.json; needs a debug build for run-as).
2. This pulls both, has the VPS sign the request (/opt/zeus/deploy/sign_device.py, replacing any earlier pairing),
   and writes the chain back to files/zeus-pair/zeus.crt.
3. In Zeus: Finish pairing.
"""
import json
import os
import subprocess
import sys
import tempfile
import time

ADB = os.environ.get("ADB", "adb")
PKG = "dev.phonerecall"
KEY = os.path.expanduser(os.environ.get("ZEUS_SSH_KEY", "~/.ssh/id_ed25519"))
VPS = os.environ.get("ZEUS_VPS", "root@203.0.113.10")
SSH = ["ssh", "-i", KEY, "-o", "BatchMode=yes", VPS]


def pull(serial: str, name: str) -> bytes:
    r = subprocess.run([ADB, "-s", serial, "exec-out", "run-as", PKG, "cat", f"files/zeus-pair/{name}"], capture_output=True, timeout=30)
    if r.returncode != 0 or not r.stdout:
        sys.exit(f"couldn't read {name} from Zeus. Open Lemnos and tap Make the keys first.")
    return r.stdout.replace(b"\r\n", b"\n")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    serial = sys.argv[1]
    csr, appr = pull(serial, "zeus.csr"), pull(serial, "approval.json")
    if b"BEGIN CERTIFICATE REQUEST" not in csr:
        sys.exit("zeus.csr isn't a certificate request")
    a = json.loads(appr)
    if a.get("alg") not in ("ed25519", "es256") or not a.get("spki"):
        sys.exit("approval.json needs alg (ed25519 or es256) and spki")

    remote = f"/root/zeus-pair-{int(time.time())}"
    with tempfile.TemporaryDirectory() as d:
        for name, data in (("zeus.csr", csr), ("approval.json", appr)):
            open(os.path.join(d, name), "wb").write(data)
        subprocess.run(SSH + [f"install -d -m 700 {remote}"], check=True, timeout=30)
        subprocess.run(["scp", "-q", "-i", KEY, os.path.join(d, "zeus.csr"), os.path.join(d, "approval.json"), f"{VPS}:{remote}/"], check=True, timeout=60)
    try:
        r = subprocess.run(SSH + [f"/opt/zeus/venv/bin/python /opt/zeus/deploy/sign_device.py --replace {remote}/zeus.csr {remote}/approval.json"],
                           capture_output=True, text=True, timeout=60)
    finally:
        subprocess.run(SSH + [f"rm -rf {remote}"], timeout=30)
    if r.returncode != 0 or "BEGIN CERTIFICATE" not in r.stdout:
        sys.exit(f"signing failed: {r.stderr.strip()[:300]}")
    print(r.stderr.strip())

    push = subprocess.run([ADB, "-s", serial, "shell", f"run-as {PKG} sh -c 'cat > files/zeus-pair/zeus.crt'"], input=r.stdout.encode(), capture_output=True, timeout=30)
    if push.returncode != 0:
        sys.exit(f"couldn't write zeus.crt into Zeus: {push.stderr.decode(errors='replace')[:200]}")
    print("Certificate written into Zeus. Tap Finish pairing in Lemnos.")


if __name__ == "__main__":
    main()
