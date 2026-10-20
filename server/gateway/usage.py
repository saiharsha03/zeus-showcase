"""Metron: how much of Iris's and Atlas's plan windows is used, for Zeus (GET /api/usage).

- Iris (Claude Code): hooks/usage_statusline.py copies the status line's rate_limits to /var/lib/zeus-usage/iris.json.
- Atlas (Codex): `zeus-openclaw codex-usage` reads the newest Codex session log's rate limits, read-only.
Each window comes back as {label, used_pct, resets_at (epoch seconds)}. A window whose reset time has passed since
the last reading comes back with used_pct null: the old number no longer holds and the next reading brings the new one.
"""
import datetime as dt
import json
import time
from pathlib import Path

CLAUDE = (("five_hour", "5h"), ("seven_day", "7d"), ("seven_day_opus", "7d Opus"), ("seven_day_sonnet", "7d Sonnet"))
CODEX = {300: "5h", 1440: "24h", 10080: "7d"}


def epoch(v) -> int | None:
    """Seconds since the epoch from seconds, milliseconds or an ISO 8601 string."""
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return int(v / 1000) if v > 1e12 else int(v)
    if isinstance(v, str):
        try:
            t = dt.datetime.fromisoformat(v.replace("Z", "+00:00"))
            return int((t if t.tzinfo else t.replace(tzinfo=dt.timezone.utc)).timestamp())
        except ValueError:
            return epoch(float(v)) if v.replace(".", "", 1).isdigit() else None
    return None


def pct(v) -> float | None:
    try:
        return None if v is None or isinstance(v, bool) else round(max(0.0, min(float(v), 100.0)), 1)
    except (TypeError, ValueError):
        return None


def window(label: str, used, resets, now: float) -> dict:
    r = epoch(resets)
    return {"label": label, "used_pct": None if r is not None and r <= now else pct(used), "resets_at": r}


def claude(rec: dict | None, now: float | None = None) -> dict:
    """Iris's saved status line record (or the raw status line JSON) to Metron's shape."""
    now = time.time() if now is None else now
    if not isinstance(rec, dict):
        return {"provider": "claude", "windows": [], "model": None, "updated": None, "note": "no status line reading yet"}
    rl = rec.get("rate_limits") if isinstance(rec.get("rate_limits"), dict) else {}
    windows = [window(label, w.get("used_percentage", w.get("utilization")), w.get("resets_at"), now)
               for key, label in CLAUDE if isinstance(w := rl.get(key), dict)]
    model = rec.get("model")
    if isinstance(model, dict):
        model = model.get("display_name") or model.get("id")
    cost = rec.get("cost_usd", (rec.get("cost") or {}).get("total_cost_usd") if isinstance(rec.get("cost"), dict) else None)
    return {"provider": "claude", "windows": windows, "model": model if isinstance(model, str) else None,
            "cost_usd": cost if isinstance(cost, (int, float)) and not isinstance(cost, bool) else None, "updated": epoch(rec.get("updated"))}


def codex(rec: dict | None, now: float | None = None) -> dict:
    """What `zeus-openclaw codex-usage` printed to Metron's shape."""
    now = time.time() if now is None else now
    rl = (rec or {}).get("rate_limits")
    if not isinstance(rl, dict):
        return {"provider": "codex", "windows": [], "model": (rec or {}).get("model"), "updated": (rec or {}).get("mtime"), "note": "no Codex rate limits logged yet"}
    windows = []
    for key in ("primary", "secondary"):
        w = rl.get(key)
        if isinstance(w, dict):
            mins = w.get("window_minutes")
            label = CODEX.get(mins) or (f"{mins // 60}h" if isinstance(mins, int) and mins >= 60 else key)
            windows.append(window(label, w.get("used_percent"), w.get("resets_at"), now))
    return {"provider": "codex", "windows": windows, "model": rec.get("model"), "plan": rl.get("plan_type"),
            "limit_reached": bool(rl.get("rate_limit_reached_type")), "updated": rec.get("mtime")}


def read_file(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
