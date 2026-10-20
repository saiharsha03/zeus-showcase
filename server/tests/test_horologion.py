import asyncio
import json
import os

from fastapi.testclient import TestClient

from conftest import GOOD, TMP

from gateway import app as gw, envelope

client = TestClient(gw.app)

HOME = TMP / "openclaw-home"
SECRETS = ["SECRET-PROMPT-TEXT", "sk-live-0123456789", "supersecret", "hunter2", "BODY-OF-THE-SKILL", "xoxb-123", "MEMORY-CONTENT", "ghp_abcdef"]


def setup_module():
    c = HOME / ".claude"
    (c / "skills" / "morning-brief").mkdir(parents=True)
    (c / "skills" / "morning-brief" / "SKILL.md").write_text(
        "---\nname: morning-brief\ndescription: Builds the morning briefing for Telegram\n---\nBODY-OF-THE-SKILL use sk-live-0123456789\n")
    (c / "skills" / "leaky").mkdir()
    (c / "skills" / "leaky" / "SKILL.md").write_text("---\nname: leaky\ndescription: uses token sk-live-0123456789\n---\nbody\n")
    (c / "plugins" / "cache" / "zeus" / "skills" / "agora-etiquette").mkdir(parents=True)
    (c / "plugins" / "cache" / "zeus" / "skills" / "agora-etiquette" / "SKILL.md").write_text("---\ndescription: How to talk in Agora\n---\nBODY-OF-THE-SKILL\n")
    (c / "settings.json").write_text(json.dumps({
        "model": "claude-opus-5-5",
        "env": {"API_KEY": "hunter2"},
        "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "API_TOKEN=supersecret python3 -I /opt/zeus/plugin/hooks/iris_stop.py --key hunter2"}]}],
                  "PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "/home/agent/bin/guard.sh"}]}]},
        "enabledPlugins": {"zeus@zeus-local": True, "telegram@claude-plugins": True, "old@x": False},
        "mcpServers": {"gmail": {"command": "npx", "env": {"TOKEN": "xoxb-123"}}},
    }))
    (c / "settings.local.json").write_text(json.dumps({"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "bash ~/bin/wake.sh ghp_abcdef"}]}]}}))
    (HOME / ".claude.json").write_text(json.dumps({"mcpServers": {"calendar": {"env": {"SECRET": "hunter2"}}}, "oauthAccount": {"token": "sk-live-0123456789"}}))
    (c / "crons.json").write_text(json.dumps({"jobs": [
        {"id": "morning-brief", "cron": "30 6 * * *", "prompt": "SECRET-PROMPT-TEXT do the brief", "enabled": True, "last_run": 1792000000},
        {"id": "weekly-bills", "schedule": "0 9 * * 0", "prompt": "SECRET-PROMPT-TEXT bills", "enabled": False, "token": "hunter2"},
    ]}))
    (c / "projects" / "home" / "memory").mkdir(parents=True)
    (c / "projects" / "home" / "memory" / "MEMORY.md").write_text("MEMORY-CONTENT\nline two\n")
    (HOME / "SOUL.md").write_text("who I am")
    (HOME / "opt-docs").mkdir()
    (HOME / "opt-docs" / "IRIS-BRIEF.md").write_text("x")
    (HOME / ".codex" / "prompts").mkdir(parents=True)
    (HOME / ".codex" / "prompts" / "review.md").write_text("SECRET-PROMPT-TEXT")
    (HOME / ".codex" / "config.toml").write_text(
        'model = "gpt-6.1-sol"\napproval_policy = "on-request"\nsandbox_mode = "workspace-write"\n'
        'notify = ["/usr/bin/python3", "-I", "/opt/zeus/hooks/atlas_notify.py"]\n'
        '[mcp_servers.github]\ncommand = "gh-mcp"\nenv = { GITHUB_TOKEN = "ghp_abcdef" }\n')
    os.environ["ZEUS_TEST_HOME"] = str(HOME)
    (TMP / "Caddyfile").write_text("{\n  email x@y.z\n}\n(common) {\n  encode\n}\nzeus.example.net:443 {\n  reverse_proxy unix//run/x\n}\n"
                                   "https://media.example.org, jelly.example.org {\n  import common\n}\n")
    gw.config.CADDYFILE = TMP / "Caddyfile"


def no_secrets(obj):
    text = json.dumps(obj)
    for s in SECRETS:
        assert s not in text, s


def test_iris_jobs_never_carry_prompts():
    r = client.get("/api/iris/jobs", headers=GOOD).json()
    assert r == [{"id": "morning-brief", "cron": "30 6 * * *", "status": "active", "last_run": 1792000000},
                 {"id": "weekly-bills", "cron": "0 9 * * 0", "status": "paused", "last_run": None}]
    no_secrets(r)
    assert client.get("/api/iris/jobs").status_code == 403


def test_iris_setup_is_names_and_one_liners_only():
    r = client.get("/api/iris/setup", headers=GOOD).json()
    assert set(r) == {"skills", "hooks", "plugins", "mcp_servers", "memory", "briefs", "channels", "model"}
    assert {"name": "morning-brief", "description": "Builds the morning briefing for Telegram", "from": "own"} in r["skills"]
    assert {"name": "leaky", "description": None, "from": "own"} in r["skills"]                 # a secret-looking line is dropped
    assert {"name": "agora-etiquette", "description": "How to talk in Agora", "from": "plugin"} in r["skills"]
    assert all(set(s) == {"name", "description", "from"} for s in r["skills"])
    assert {"event": "Stop", "matcher": "*", "script": "iris_stop.py", "file": "settings.json"} in r["hooks"]
    assert {"event": "PreToolUse", "matcher": "Bash", "script": "guard.sh", "file": "settings.json"} in r["hooks"]
    assert {"event": "SessionStart", "matcher": "*", "script": "wake.sh", "file": "settings.local.json"} in r["hooks"]
    assert all(set(h) == {"event", "matcher", "script", "file"} for h in r["hooks"])
    assert r["plugins"] == [{"name": "zeus@zeus-local", "enabled": True}, {"name": "telegram@claude-plugins", "enabled": True}, {"name": "old@x", "enabled": False}]
    assert r["mcp_servers"] == ["calendar", "gmail"] and r["model"] == "claude-opus-5-5"
    assert r["memory"] == [{"file": "MEMORY.md", "lines": 2}]
    assert r["briefs"] == ["SOUL.md", "IRIS-BRIEF.md"] and r["channels"] == ["telegram", "zeus"]
    no_secrets(r)


def test_atlas_setup_is_names_only():
    r = client.get("/api/atlas/setup", headers=GOOD).json()
    assert set(r) == {"version", "model", "notify", "approval", "sandbox", "mcp_servers", "prompts", "skills"}
    assert (r["model"], r["notify"], r["approval"], r["sandbox"]) == ("gpt-6.1-sol", "atlas_notify.py", "on-request", "workspace-write")
    assert r["mcp_servers"] == ["github"] and r["prompts"] == ["review.md"] and r["skills"] == []
    no_secrets(r)


def test_server_setup_lists_site_names():
    r = client.get("/api/server/setup", headers=GOOD).json()
    assert r["caddy_sites"] == ["jelly.example.org", "media.example.org", "zeus.example.net"]
    assert set(r) == {"caddy_sites", "mnemosyne_last", "server_backup", "controllable_units"}


def test_a_request_from_owner_needs_his_approval_and_says_who():
    body = {"job_id": "cron-1", "to": "iris", "kind": "iris.cron_change", "title": "Change your cron morning-brief to 0 7 * * *",
            "payload": {"id": "morning-brief", "action": "change", "cron": "0 7 * * *"}, "by": "owner"}
    assert client.post("/api/jobs", headers=GOOD, json=body).status_code == 400
    assert client.post("/api/jobs", headers=GOOD, json={**body, "approval": "not-a-token"}).status_code == 403
    text = envelope.job(5, "cron-1", "iris.cron_change", "Change your cron", {"id": "x"}, True, "tok", 1, by="owner")
    assert text.startswith("[zeus #5] FROM OWNER, from Horologion") and "APPROVED BY OWNER" in text and "--token tok" in text
    assert "reply with what changed" in text
    # Unapproved, it can never claim the owner.
    assert envelope.job(5, "j", "k", "t", {}, False, None, None, by="owner").startswith("[zeus #5] FROM ATHENA")
    m = asyncio.run(gw.chat.submit_job("cron-2", "iris", "iris.cron_change", "Pause weekly-bills", {"id": "weekly-bills", "action": "pause"}, True, "tok", 1, by="owner"))
    msg = gw.store.one("SELECT * FROM messages WHERE job_id='cron-2'")
    assert msg["sender"] == "owner" and gw.chat.stamp(msg["id"]) == ("owner", "signed") and m["approved"]
