"""Iris's Moneta ops on their way to the phone. Only files openclaw (Iris's user) wrote count, and each is checked
against lib/moneta_ops.py before it is queued. Provenance is set here, not by the sender: every op is from Iris."""
import json
import os
import pwd
import sys
from pathlib import Path

from . import config
from .spool import read_drop

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import moneta_ops as mo  # noqa: E402


class Moneta:
    def __init__(self, store, wake=lambda: None):
        self.s = store
        self.wake = wake
        self.dir = Path(config.STATE) / "moneta-ops"
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)
        try:
            self.iris_uid = pwd.getpwnam(config.IRIS_USER).pw_uid
        except KeyError:
            self.iris_uid = None

    def intake(self) -> int:
        n = 0
        for f in sorted(Path(config.MONETA_SPOOL).glob("*.json")):
            try:
                st, raw = read_drop(f, mo.MAX_OP * 4)     # never follows a symlink; owner and bytes from one fd
                if self.iris_uid is None or st.st_uid != self.iris_uid:
                    self.s.audit("moneta.foreign", f.name[:40])
                elif mo.envelope_ok(env := json.loads(raw.decode("utf-8"))):
                    env["from"] = "iris"
                    env["args"] = mo.check(env["op"], env["args"])
                    name = f"{int(env['at']):015d}-{env['op_id']}.json"
                    tmp = self.dir / f".{name}"
                    tmp.write_text(json.dumps(env), encoding="utf-8")
                    os.replace(tmp, self.dir / name)
                    self.s.audit("moneta.op", env["op"])
                    n += 1
                else:
                    self.s.audit("moneta.bad", f.name[:40])
            except (OSError, ValueError) as e:
                self.s.audit("moneta.bad", f"{f.name[:40]}: {type(e).__name__}")
            f.unlink(missing_ok=True)
        if n:
            self.wake()
        return n

    def ops(self, limit: int = 200) -> list[dict]:
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
