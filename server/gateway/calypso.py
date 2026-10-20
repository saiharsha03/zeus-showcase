"""Calypso drops: sealed envelopes on their way to the phone. The gateway never sees plaintext and holds no key
that opens them; it only checks the envelope's shape, keeps it until the phone takes it, then forgets it."""
import base64
import json
import os
import re
from pathlib import Path

from . import config
from .spool import read_drop

ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
KINDS = {"secret", "note", "file"}
MAX_CT = 2 * 1024 * 1024 + 512


def valid(env: dict) -> bool:
    try:
        if env.get("v") != 1 or not ID.match(str(env.get("id", ""))) or env.get("kind") not in KINDS:
            return False
        if not isinstance(env.get("label"), str) or len(env["label"]) > 80 or not isinstance(env.get("from"), str) or len(env["from"]) > 40:
            return False
        int(env["at"])
        iv, ct, wk = (base64.b64decode(env[k], validate=True) for k in ("iv", "ct", "wk"))
        return len(iv) == 12 and 18 <= len(ct) <= MAX_CT and 128 <= len(wk) <= 1024
    except (KeyError, TypeError, ValueError):
        return False


class Calypso:
    def __init__(self, store):
        self.s = store
        self.dir = Path(config.STATE) / "calypso"
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)   # the unit's UMask=0117 would leave a folder nobody can enter, zeus included

    def intake(self) -> int:
        """Moves valid drops from the 1733 spool to the gateway's own folder. Bad ones are deleted and audited."""
        n = 0
        for f in sorted(Path(config.CALYPSO_SPOOL).glob("*.json")):
            try:
                _, raw = read_drop(f, MAX_CT * 4)            # anyone may drop here; no symlinks, regular files only
                env = json.loads(raw.decode("utf-8"))
                if valid(env):
                    tmp = self.dir / f".{env['id']}"
                    tmp.write_text(json.dumps(env), encoding="utf-8")
                    os.replace(tmp, self.dir / f"{env['id']}.json")
                    self.s.audit("calypso.drop", f"{env['from']}: {env['kind']}")
                    n += 1
                else:
                    self.s.audit("calypso.bad", f.name[:40])
            except (OSError, ValueError) as e:
                self.s.audit("calypso.bad", f"{f.name[:40]}: {type(e).__name__}")
            f.unlink(missing_ok=True)
        return n

    def drops(self, limit: int = 50) -> list[dict]:
        out = []
        for f in sorted(self.dir.glob("*.json"))[:limit]:
            try:
                out.append(json.loads(f.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                f.unlink(missing_ok=True)
        return out

    def ack(self, ids: list[str]) -> int:
        n = 0
        for i in ids[:200]:
            if ID.match(i) and (self.dir / f"{i}.json").exists():
                (self.dir / f"{i}.json").unlink()
                n += 1
        if n:
            self.s.audit("calypso.taken", str(n))
        return n

    @staticmethod
    def publish_key(pem: str) -> None:
        """Calypso's public key, readable by the agents (openclaw) so they can seal to it."""
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        key = serialization.load_pem_public_key(pem.encode())
        if not isinstance(key, rsa.RSAPublicKey) or key.key_size < 2048:
            raise ValueError("expected an RSA public key, 2048 bits or more")
        out = Path(config.OUTBOX) / "calypso.pub.pem"
        tmp = out.with_suffix(".tmp")
        tmp.write_text(pem, encoding="utf-8")
        os.chmod(tmp, 0o640)
        os.replace(tmp, out)
