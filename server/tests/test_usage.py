"""Metron: Iris's status line pass-through, Atlas's Codex log, and GET /api/usage."""
import json
import os
import shutil
import subprocess
import sys

from fastapi.testclient import TestClient

from conftest import GOOD, SERVER, TMP

from gateway import app as gw, usage

HERE = os.path.dirname(__file__)
STATUS = open(os.path.join(HERE, "fixtures", "statusline.json"), encoding="utf-8").read()
NOW = 1792880000          # before every reset time in the fixtures
client = TestClient(gw.app)


def run_hook(tmp, raw=STATUS, agent="iris"):
    nxt = tmp / "next.py"
    nxt.write_text("#!" + sys.executable + "\nimport sys, json\nd = json.load(sys.stdin)\nprint('LINE', d['model']['display_name'])\n")
    nxt.chmod(0o755)
    env = {**os.environ, "ZEUS_USAGE_DIR": str(tmp), "ZEUS_STATUSLINE_NEXT": str(nxt), "ZEUS_USAGE_AGENT": agent}
    return subprocess.run([sys.executable, str(SERVER / "hooks" / "usage_statusline.py")], input=raw, capture_output=True, text=True, env=env, timeout=20)


def test_status_line_parses_every_window_and_its_reset():
    u = usage.claude(json.loads(STATUS), now=NOW)
    assert u["model"] == "Opus 5.5" and u["cost_usd"] == 3.42
    assert u["windows"] == [
        {"label": "5h", "used_pct": 83.5, "resets_at": 1792900800},
        {"label": "7d", "used_pct": 46.0, "resets_at": 1793368800},          # ISO 8601 -> epoch seconds
        {"label": "7d Opus", "used_pct": 12.2, "resets_at": 1792900800},     # milliseconds -> seconds
    ]


def test_a_window_past_its_reset_has_no_number():
    u = usage.claude(json.loads(STATUS), now=1793000000)
    assert [w["used_pct"] for w in u["windows"]] == [None, 46.0, None]
    assert usage.claude(None)["windows"] == [] and usage.claude({"rate_limits": "junk"})["windows"] == []


def test_hook_keeps_limits_only_and_still_prints_her_status_line(tmp_path):
    r = run_hook(tmp_path)
    assert r.returncode == 0 and r.stdout.strip() == "LINE Opus 5.5"     # her line, from the same stdin
    f = tmp_path / "iris.json"
    assert oct(f.stat().st_mode & 0o777) == "0o640"
    saved = json.loads(f.read_text())
    assert set(saved) == {"provider", "rate_limits", "model", "cost_usd", "updated"}
    assert set(saved["rate_limits"]) == {"five_hour", "seven_day", "seven_day_opus"}
    assert "PRIVATE" not in f.read_text() and "transcript" not in f.read_text() and "/home/agent" not in f.read_text()
    assert usage.claude(saved, now=NOW)["windows"][0]["used_pct"] == 83.5
    assert not list(tmp_path.glob(".*.tmp"))


def test_hook_outside_her_session_writes_nothing_and_bad_input_never_breaks_the_line(tmp_path):
    r = run_hook(tmp_path, agent="")
    assert r.stdout.strip() == "LINE Opus 5.5" and not (tmp_path / "iris.json").exists()
    r = run_hook(tmp_path, raw="not json")
    assert r.returncode != 0 or "LINE" not in r.stdout   # her line fails on junk itself; ours wrote nothing
    assert not (tmp_path / "iris.json").exists()


def codex_dir():
    d = TMP / "codex" / "2026" / "10" / "19"
    d.mkdir(parents=True, exist_ok=True)
    return d


def test_codex_rollout_newest_limits_and_model_only():
    d = codex_dir()
    old = d / "rollout-2026-10-19T01-00-00-old.jsonl"
    old.write_text('{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":1.0,"window_minutes":300,"resets_at":1}}}}\n')
    os.utime(old, (1_700_000_000, 1_700_000_000))
    shutil.copy(os.path.join(HERE, "fixtures", "codex_rollout.jsonl"), d / "rollout-2026-10-19T22-00-00-new.jsonl")
    r = subprocess.run([sys.executable, str(SERVER / "bin" / "zeus-openclaw"), "codex-usage"], capture_output=True, env=os.environ, timeout=20)
    assert r.returncode == 0, r.stderr
    raw = r.stdout.decode()
    assert "PRIVATE" not in raw and "plexbot" not in raw and "/home/agent" not in raw and "balance" not in raw
    rec = json.loads(raw)
    assert rec["model"] == "gpt-6.1-sol"
    u = usage.codex(rec, now=NOW)
    assert u["windows"] == [{"label": "5h", "used_pct": 91.0, "resets_at": 1792890000}, {"label": "7d", "used_pct": None, "resets_at": 1792874633}]
    assert u["plan"] == "prolite" and u["limit_reached"] is False


def test_codex_without_limits_says_so():
    assert usage.codex({"rate_limits": None, "model": None, "mtime": None})["windows"] == []
    assert usage.codex({"rate_limits": {"primary": {"used_percent": 30, "window_minutes": 10080, "resets_at": NOW + 99}, "secondary": None}}, now=NOW)["windows"] == [
        {"label": "7d", "used_pct": 30.0, "resets_at": NOW + 99}]


def test_api_usage_answers_for_both_and_needs_the_device():
    assert client.get("/api/usage").status_code == 403
    (TMP / "usage" / "iris.json").write_text(json.dumps({"provider": "claude", "rate_limits": {"five_hour": {"used_percentage": 20, "resets_at": 4102444800}}, "model": "Opus 5.5", "updated": 1792879000}))
    shutil.copy(os.path.join(HERE, "fixtures", "codex_rollout.jsonl"), codex_dir() / "rollout-2026-10-19T22-00-00-new.jsonl")
    b = client.get("/api/usage", headers=GOOD).json()
    by = {a["id"]: a for a in b["agents"]}
    assert set(by) == {"iris", "atlas"} and by["iris"]["name"] == "Iris"
    assert by["iris"]["windows"] == [{"label": "5h", "used_pct": 20.0, "resets_at": 4102444800}] and by["iris"]["updated"] == 1792879000
    assert by["atlas"]["model"] == "gpt-6.1-sol" and all({"label", "used_pct", "resets_at"} == set(w) for w in by["atlas"]["windows"])
    assert isinstance(b["now"], int)
