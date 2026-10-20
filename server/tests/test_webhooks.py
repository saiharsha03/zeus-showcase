import base64
import hashlib
import hmac
import importlib.util
import json
import re
import time

import pytest
from fastapi.testclient import TestClient

from conftest import PROXY_KEY, SERVER, TMP

from gateway import app as gw, webhooks

client = TestClient(gw.app)
KEY = bytes(range(32))
SECRET = TMP / "etc" / "anthropic-webhook.secret"


@pytest.fixture
def pushes(monkeypatch):
    SECRET.write_text("whsec_" + base64.b64encode(KEY).decode() + "\n")
    monkeypatch.setattr(webhooks.config, "ANTHROPIC_WEBHOOK_SECRET", SECRET)
    webhooks._last.clear()
    sent = []
    monkeypatch.setattr(gw.fcm, "wake", lambda high: sent.append(high))
    return sent


def event(kind="session.status_idled", sid="session_01ABC", **data):
    return json.dumps({"type": "event", "id": "whe_1", "created_at": "2026-10-20T12:00:00Z",
                       "data": {"type": kind, "id": sid, "organization_id": "o", "workspace_id": "w", **data}}).encode()


def post(body, ts=None, key=KEY, sig=None, proxy=PROXY_KEY):
    ts = str(int(time.time()) if ts is None else ts)
    mac = base64.b64encode(hmac.new(key, f"whe_1.{ts}.".encode() + body, hashlib.sha256).digest()).decode()
    return client.post("/hooks/anthropic", content=body, headers={"webhook-id": "whe_1", "webhook-timestamp": ts, "x-zeus-proxy-key": proxy,
                                                                   "webhook-signature": sig or f"v1,{mac}", "content-type": "application/json"})


def test_without_the_proxy_key_is_403(pushes):
    assert post(event(), proxy="").status_code == 403
    assert post(event(), proxy="x" * 64).status_code == 403
    assert pushes == []


def test_bad_signature_is_401(pushes):
    assert post(event(), key=b"x" * 32).status_code == 401
    assert post(event(), sig="v1,AAAA").status_code == 401
    assert client.post("/hooks/anthropic", content=event(), headers={"x-zeus-proxy-key": PROXY_KEY}).status_code == 401
    assert pushes == []


def test_stale_or_future_timestamp_is_401(pushes):
    assert post(event(), ts=int(time.time()) - 301).status_code == 401
    assert post(event(), ts=int(time.time()) + 301).status_code == 401
    assert pushes == []


def test_missing_or_empty_secret_is_503(pushes, monkeypatch):
    monkeypatch.setattr(webhooks.config, "ANTHROPIC_WEBHOOK_SECRET", TMP / "etc" / "nope.secret")
    assert post(event()).status_code == 503
    SECRET.write_text("\n")
    monkeypatch.setattr(webhooks.config, "ANTHROPIC_WEBHOOK_SECRET", SECRET)
    r = post(event())
    assert r.status_code == 503 and "whsec" not in r.text
    assert pushes == []


def test_idle_pushes_once_then_debounces_per_session(pushes, monkeypatch):
    r = post(event())
    assert r.status_code == 200 and pushes == [True]
    assert post(event()).status_code == 200 and pushes == [True]           # a retry within 10 s: no second push
    assert post(event(sid="session_02XYZ")).status_code == 200 and pushes == [True, True]   # another session does
    later = time.monotonic() + 11
    monkeypatch.setattr(webhooks.time, "monotonic", lambda: later)
    assert post(event()).status_code == 200 and len(pushes) == 3


def test_a_second_signature_in_the_list_counts(pushes):
    ts = str(int(time.time()))
    mac = base64.b64encode(hmac.new(KEY, f"whe_1.{ts}.".encode() + event(), hashlib.sha256).digest()).decode()
    assert post(event(), ts=ts, sig=f"v1,AAAA v1,{mac}").status_code == 200 and pushes == [True]


def test_other_events_are_204(pushes):
    for kind in ("session.status_run_started", "session.status_terminated", "session.thread_idled", "vault.created"):
        assert post(event(kind=kind)).status_code == 204
    assert post(event(stop_reason={"type": "end_turn"})).status_code == 204
    assert pushes == []
    assert post(event(sid="../etc")).status_code == 400
    assert post(b"not json").status_code == 400
    assert pushes == []


def load_caddy():
    spec = importlib.util.spec_from_file_location("caddy_block", SERVER / "deploy" / "caddy_block.py")
    cb = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cb)
    return cb


def test_main_site_stays_strictly_mtls():
    site = load_caddy().BLOCK
    assert re.search(r"^zeus\.example\.com \{$", site, re.M)
    assert "mode require_and_verify" in site and "verify_if_given" not in site and "hooks" not in site
    assert re.search(r"@api path /api/\* /ws/\*\n", site) and "certificate_der" not in site and "abort" not in site
    assert re.findall(r"^\s+handle(?: (@\w+))? \{", site, re.M) == ["@api", ""]


def test_hooks_host_serves_only_the_webhook():
    hooks = load_caddy().HOOKS
    assert re.search(r"^hooks\.example\.com \{$", hooks, re.M) and "client_auth" not in hooks
    assert re.search(r"@hook \{\n\s+method POST\n\s+path /hooks/anthropic\n\s+\}", hooks)
    assert "/hooks/*" not in hooks and "/hooks/anthropic*" not in hooks
    hook = hooks[hooks.index("handle @hook {"):hooks.index("    handle {")]
    assert "max_size 64KB" in hook and "rewrite * /hooks/anthropic?" in hook
    assert "X-Zeus-Proxy-Key {file./etc/caddy/zeus-proxy.key}" in hook and "Client-SHA256" not in hooks
    assert "request_header -X-Zeus-*" in hooks
    # one route to the gateway; everything else 404s
    assert re.findall(r"^\s+handle(?: (@\w+))? \{", hooks, re.M) == ["@hook", ""]
    assert hooks.count("reverse_proxy") == 1 and hooks.rstrip().endswith("respond 404\n    }\n}")


def test_caddy_block_replaces_both_sites_and_keeps_the_rest(tmp_path):
    cb = load_caddy()
    f = tmp_path / "Caddyfile"
    f.write_text("other.example {\n    respond 200\n}\n\n# old\nzeus.example.com {\n    respond 1\n}\n")
    cb.main(str(f))
    once = f.read_text()
    cb.main(str(f))
    assert f.read_text() == once
    assert once.startswith("other.example {\n    respond 200\n}\n") and "respond 1" not in once and "# old" not in once
    assert once.count("zeus.example.com {") == 1 and once.count("hooks.example.com {") == 1
