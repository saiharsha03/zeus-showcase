"""Iris's Moneta ops on their way to the phone. Only files openclaw (Iris's user) wrote count, and each is checked
against lib/moneta_ops.py before it is queued. Provenance is set here, not by the sender: every op is from Iris.

The other way: the phone's view of Moneta (PUT /api/moneta/view), kept in /var/lib/zeus/moneta-view.json (0600, zeus
only) and read by Iris only through her plugin's moneta_view tool (the channel frame moneta.view)."""
import datetime as dt
import json
import os
import pwd
import re
import sys
import tempfile
import time
from pathlib import Path

from . import config
from .spool import read_drop

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import moneta_ops as mo  # noqa: E402

VIEW_MAX = 512 * 1024
VIEW_ROWS = 2000
VIEW_DAYS = 90
ANSWER_ROWS = 500           # rows one moneta_view answer carries; the rest are counted in "more"
LONG_DIGITS = re.compile(r"\d(?:[ -]?\d){6,}")
BALANCE = re.compile(r"(?i)\b(?:avail(?:able)?\.?\s+)?bal(?:ance)?\b.*")
KINDS = {"spend", "refund", "payment", "transfer", "income", "declined"}
STATUSES = {"posted", "pending", "declined"}
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def _text(v, cap: int) -> str:
    """Merchant or category text: no 7+ digit runs (card or account numbers), nothing from "balance" on."""
    return " ".join(LONG_DIGITS.sub("", BALANCE.sub("", str(v or ""))).split())[:cap]


def _int(v) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) and 0 <= v < 10**11 else None


def clean_view(raw: bytes, now: float | None = None) -> dict:
    """The phone's snapshot, checked again here: fixed fields only, last four digits only, capped. Raises ValueError."""
    if len(raw) > VIEW_MAX:
        raise ValueError("view too big")
    v = json.loads(raw.decode("utf-8"))
    if not isinstance(v, dict) or not isinstance(v.get("rows"), list):
        raise ValueError("no rows")
    rows = []
    for r in v["rows"]:
        if len(rows) >= VIEW_ROWS:
            break
        if not isinstance(r, dict) or not DATE.fullmatch(str(r.get("date", ""))) or _int(r.get("cents")) is None:
            continue
        last4 = str(r.get("last4") or "")
        row = {"id": _text(r.get("id"), 40), "date": r["date"], "cents": r["cents"],
               "currency": c if re.fullmatch(r"[A-Z]{3}", c := str(r.get("currency") or "USD")) else "USD",
               "merchant": _text(r.get("merchant"), 60), "category": _text(r.get("category"), 40),
               "kind": r.get("kind") if r.get("kind") in KINDS else "spend",
               "last4": last4 if re.fullmatch(r"\d{4}", last4) else None,
               "source": "iris" if r.get("source") == "iris" else "phone",
               "status": r.get("status") if r.get("status") in STATUSES else "posted"}
        if r.get("fraud_flag") is True:
            row["fraud_flag"] = True
        rows.append(row)
    rows.sort(key=lambda r: r["date"], reverse=True)
    subs = [{"name": _text(x.get("name"), 40), "cents": _int(x.get("cents")),
             "cycle": x.get("cycle") if x.get("cycle") in ("weekly", "monthly", "yearly") else "monthly",
             "next": x.get("next") if DATE.fullmatch(str(x.get("next", ""))) else None,
             "source": x.get("source") if x.get("source") in ("detected", "iris", "manual") else "detected"}
            for x in (v.get("subscriptions") or [])[:200] if isinstance(x, dict) and x.get("name")]
    b = v.get("budgets") if isinstance(v.get("budgets"), dict) else {}
    budgets = {_text(k, 40): c for k, c in list(b.items())[:40] if _int(c) is not None}
    w = v.get("week") if isinstance(v.get("week"), dict) else {}
    return {"generated": _int(v.get("generated")) or 0, "received": int((now or time.time()) * 1000), "rows": rows,
            "subscriptions": subs, "budgets": budgets,
            "week": {"spent_cents": _int(w.get("spent_cents")), "usual_cents": _int(w.get("usual_cents"))},
            "truncated": bool(v.get("truncated")) or len(rows) < len(v["rows"])}


def filter_view(view: dict, days, query, now: float | None = None) -> dict:
    """What moneta_view answers: rows from the last `days` (1..90, default 30), optionally only those whose merchant or
    category contains `query`, at most ANSWER_ROWS, newest first."""
    days = max(1, min(int(days), VIEW_DAYS)) if isinstance(days, int) and not isinstance(days, bool) else 30
    q = str(query or "").strip().lower()[:80]
    cut = dt.datetime.fromtimestamp((now or time.time()) - days * 86400, dt.timezone.utc).date().isoformat()
    rows = [r for r in view.get("rows", []) if r["date"] >= cut and (not q or q in r["merchant"].lower() or q in r["category"].lower())]
    subs = [s for s in view.get("subscriptions", []) if not q or q in s["name"].lower()]
    return {"generated": view.get("generated"), "days": days, "query": q or None, "rows": rows[:ANSWER_ROWS],
            "more": max(0, len(rows) - ANSWER_ROWS), "subscriptions": subs, "budgets": view.get("budgets", {}),
            "week": view.get("week")}


class Moneta:
    def __init__(self, store, wake=lambda: None):
        self.s = store
        self.wake = wake
        self.dir = Path(config.STATE) / "moneta-ops"
        self.view_file = Path(config.STATE) / "moneta-view.json"
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

    def put_view(self, raw: bytes) -> int:
        """Stores the phone's view atomically (0600, the gateway's own). Returns the row count; raises ValueError."""
        view = clean_view(raw)
        fd, tmp = tempfile.mkstemp(dir=self.view_file.parent, prefix=".moneta-view.")   # 0600
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(view, f)
            os.replace(tmp, self.view_file)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise
        self.s.audit("moneta.view", f"{len(view['rows'])} rows")
        return len(view["rows"])

    def view(self, days=30, query=None) -> dict | None:
        try:
            return filter_view(json.loads(self.view_file.read_text(encoding="utf-8")), days, query)
        except (OSError, ValueError):
            return None
