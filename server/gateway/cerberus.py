"""Cerberus: the server's watchers, read-only.

  mirror  /var/spool/zeus-alerts, from zeus-mirror (security-alerter and tg-media-dl call it as claw-mirror)
  scan    /var/log/security-check/latest.json, the 4-hourly scan's result
  cve     /var/log/cve-watch.log, the daily CVE watch ("alerts: N" per run)
  backup  the borg backup unit's last result (systemctl show, no privilege needed)

Severity: high = an SSH sign-in from an address that isn't trusted (the alerter's own flag); medium = other security
events, a scan that isn't clean, new CVEs, a failed backup; low = the rest. Nothing here sends to Telegram or
changes a watcher: Telegram stays theirs.
"""
import hashlib
import json
import re
import subprocess
import time

from . import config
from .store import Store

UNTRUSTED = re.compile(r"not\s+a\s+trusted|non-?trusted|untrusted|unknown\s+(ip|address)", re.I)


def classify(source: str, text: str) -> str:
    t = text.lower()
    if source == "security":
        if "ssh" in t:
            return "high" if UNTRUSTED.search(text) else "low"
        return "medium"
    if source == "media":
        return "medium" if re.search(r"unauthori[sz]ed|stranger|owner.?lock", t) else "low"
    return "medium"


def title_of(text: str) -> str:
    first = next((ln.strip() for ln in text.splitlines() if ln.strip()), text.strip())
    return re.sub(r"[*_`]", "", first)[:140]


class Cerberus:
    def __init__(self, store: Store, notify):
        self.s, self.notify = store, notify

    def add(self, source: str, severity: str, title: str, body: str, dedup: str, ts: float | None = None) -> bool:
        with self.s.tx() as t:
            if t.row("SELECT 1 AS x FROM alerts WHERE dedup=?", (dedup,)):
                return False
            aid = t.x("INSERT INTO alerts(ts, source, severity, title, body, dedup) VALUES (?,?,?,?,?,?)",
                      (ts or time.time(), source, severity, title[:200], body[:4000], dedup)).lastrowid
            t.emit("alert", {"id": aid, "severity": severity, "source": source})
        if severity in ("high", "medium"):
            self.notify("alert", title[:120], body[:300], None, severity)
        return True

    def list(self, since: int, limit: int) -> list[dict]:
        return [{**r, "ack": bool(r["ack"])} for r in self.s.q("SELECT id, ts, source, severity, title, body, ack FROM alerts WHERE id>? ORDER BY id DESC LIMIT ?", (since, limit))]

    def ack(self, aid: int) -> bool:
        with self.s.tx() as t:
            return t.x("UPDATE alerts SET ack=1 WHERE id=?", (aid,)).rowcount == 1

    # ---- sources ----
    def read_mirror(self) -> None:
        for f in sorted(config.ALERTS.glob("*.json"))[:200]:
            try:
                ev = json.loads(f.read_text(encoding="utf-8"))
                src, text = str(ev.get("source", "alert"))[:24], str(ev.get("text", ""))
                if text.strip():
                    self.add(src, classify(src, text), title_of(text), text, "m:" + f.name, float(ev.get("ts") or time.time()))
            except (OSError, ValueError):
                pass
            f.unlink(missing_ok=True)

    def read_scan(self) -> None:
        try:
            d = json.loads(config.SCAN_FILE.read_text())
        except (OSError, ValueError):
            return
        status = str(d.get("status", ""))
        if status.lower() in ("clean", "ok", ""):
            return
        concerns = [str(c) for c in d.get("concerns", [])][:10]
        self.add("scan", "medium", f"Security scan: {status}", "\n".join(concerns) or status, "scan:" + str(d.get("ts")))

    def read_cve(self) -> None:
        try:
            text = config.CVE_LOG.read_text(errors="replace")[-20000:]
        except OSError:
            return
        runs = text.split("=== ")
        last = next((r for r in reversed(runs) if "cve-watch run" in r), None)
        if not last:
            return
        m = re.search(r"alerts:\s*(\d+)", last)
        if m and int(m.group(1)) > 0:
            body = "\n".join(ln for ln in last.splitlines()[1:] if ln.strip() and not ln.startswith("==="))[:3000]
            self.add("cve", "medium", f"{m.group(1)} new CVE alert{'s' if m.group(1) != '1' else ''}", body,
                     "cve:" + hashlib.sha256(last.encode()).hexdigest()[:16])

    def read_backup(self) -> None:
        r = subprocess.run(["systemctl", "show", config.BACKUP_UNIT, "--no-pager", "-p", "Result,ExecMainExitTimestamp,ExecMainStatus"],
                           capture_output=True, text=True, timeout=10)
        p = dict(ln.split("=", 1) for ln in r.stdout.splitlines() if "=" in ln)
        if p.get("ExecMainExitTimestamp") and p.get("Result") not in (None, "", "success"):
            self.add("backup", "medium", "Backup failed", f"{config.BACKUP_UNIT}: {p.get('Result')} (exit {p.get('ExecMainStatus')}) at {p['ExecMainExitTimestamp']}",
                     "backup:" + p["ExecMainExitTimestamp"])

    def tick(self, slow: bool) -> None:
        self.read_mirror()
        if slow:
            for f in (self.read_scan, self.read_cve, self.read_backup):
                try:
                    f()
                except Exception as e:
                    self.s.audit("cerberus.error", f"{f.__name__}: {e!r}")
