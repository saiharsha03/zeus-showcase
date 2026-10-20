#!/usr/bin/python3 -I
"""Iris's status line pass-through for Metron: keeps her plan limits, then shows her normal status line.

~openclaw/.claude/settings.json:  "statusLine": {"type": "command", "command": "python3 -I /opt/zeus/hooks/usage_statusline.py"}

Claude Code sends the status line one JSON object on stdin at every refresh. From it this keeps only the plan
windows (rate_limits), the model and the session cost, never any prompt or transcript text, and writes them
atomically to /var/lib/zeus-usage/iris.json (dir openclaw:zeus 2750, file 0640: the gateway reads by group).
Then it execs /home/agent/bin/iris-statusline with the same stdin, so her status line looks exactly as before.
Only her own session (tmux claude-auto) writes the file. It never fails: any error still runs her status line.
"""
import json
import os
import subprocess
import sys
import time

OUT = os.environ.get("ZEUS_USAGE_DIR", "/var/lib/zeus-usage")
NEXT = os.environ.get("ZEUS_STATUSLINE_NEXT", "/home/agent/bin/iris-statusline")
SESSION = "claude-auto"
WINDOWS = ("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")


def agent() -> str | None:
    """'iris' when this runs inside her tmux session. ZEUS_USAGE_AGENT overrides it (tests)."""
    if os.environ.get("ZEUS_USAGE_AGENT"):
        return os.environ["ZEUS_USAGE_AGENT"]
    pane = os.environ.get("TMUX_PANE")
    if not pane:
        return None
    r = subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#S"], capture_output=True, text=True, timeout=3)
    return "iris" if r.stdout.strip() == SESSION else None


def keep(data: dict) -> dict:
    """The parts Metron needs. Each window is copied field by field, so nothing else can ride along."""
    rl = data.get("rate_limits") if isinstance(data.get("rate_limits"), dict) else {}
    windows = {}
    for k in WINDOWS:
        w = rl.get(k)
        if isinstance(w, dict):
            windows[k] = {"used_percentage": w.get("used_percentage", w.get("utilization")), "resets_at": w.get("resets_at")}
    model = data.get("model") if isinstance(data.get("model"), dict) else {}
    cost = data.get("cost") if isinstance(data.get("cost"), dict) else {}
    return {"provider": "claude", "rate_limits": windows, "model": model.get("display_name") or model.get("id"), "cost_usd": cost.get("total_cost_usd")}


def save(raw: str) -> None:
    who = agent()
    if who is None:
        return
    rec = keep(json.loads(raw))
    path = os.path.join(OUT, f"{who}.json")
    try:   # the status line refreshes often: rewrite only on a change, or once a minute so "updated" stays fresh
        old = json.load(open(path, encoding="utf-8"))
        if {k: v for k, v in old.items() if k != "updated"} == rec and time.time() - old.get("updated", 0) < 60:
            return
    except (OSError, ValueError):
        pass
    rec["updated"] = int(time.time())
    tmp = os.path.join(OUT, f".{who}.{os.getpid()}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o640)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(rec, f)
    os.chmod(tmp, 0o640)
    os.replace(tmp, path)


def main() -> None:
    raw = sys.stdin.read()
    try:
        save(raw)
    except Exception:
        pass
    # Her status line reads the same JSON on stdin: hand it over in a memory file, then become it.
    try:
        fd = os.memfd_create("statusline")
        os.write(fd, raw.encode())
        os.lseek(fd, 0, os.SEEK_SET)
        os.dup2(fd, 0)
        os.execv(NEXT, [NEXT])
    except OSError:
        pass


if __name__ == "__main__":
    main()
