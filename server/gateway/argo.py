"""Iris's trip.info for Argo, on its way to the phone. It arrives only over the zeus channel (Iris's plugin, on a socket
only openclaw's group can open), is checked against lib/argo_info.py, stamped with an id and kept until the phone acks."""
import json
import os
import secrets
import sys
import time
from pathlib import Path

from . import config

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import argo_info as ai  # noqa: E402


class Argo:
    def __init__(self, store, wake=lambda: None):
        self.s = store
        self.wake = wake
        self.dir = Path(config.STATE) / "argo-info"
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)

    def put(self, info) -> str:
        """Checks and queues one trip.info. Returns its id ("iris:<24 hex>"); raises argo_info.Invalid."""
        clean = ai.check(info)
        raw = secrets.token_hex(12)
        env = clean | {"id": raw, "from_agent": "iris", "received": int(time.time() * 1000)}
        name = f"{env['received']:015d}-{raw}.json"
        tmp = self.dir / f".{name}"
        tmp.write_text(json.dumps(env), encoding="utf-8")
        os.replace(tmp, self.dir / name)
        self.s.audit("argo.info", f"{clean['kind']} {clean['place'][:40]}")
        self.wake()
        return f"iris:{raw}"

    def items(self, limit: int = 200) -> list[dict]:
        out = []
        for f in sorted(self.dir.glob("*.json"))[:limit]:
            try:
                out.append(json.loads(f.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                f.unlink(missing_ok=True)
        return out

    def ack(self, ids: list[str]) -> int:
        want = {i for i in ids[:500] if isinstance(i, str) and len(i) == 24}
        n = 0
        for f in self.dir.glob("*.json"):
            if f.stem.split("-", 1)[-1] in want:
                f.unlink(); n += 1
        return n
