import base64
import hashlib
import json
import time

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519
from fastapi.testclient import TestClient

from conftest import GOOD, TMP

import prometheus_queue as pq
import zeus_approval as za
from gateway import app as gw

client = TestClient(gw.app)


@pytest.fixture
def key(tmp_path, monkeypatch):
    k = ed25519.Ed25519PrivateKey.generate()
    spki = k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    p = tmp_path / "keys.json"
    p.write_text(json.dumps({"keys": [{"kid": za.kid_of(spki), "alg": "ed25519", "spki": base64.b64encode(spki).decode()}]}))
    monkeypatch.setattr(gw, "verifier", za.Verifier(keys_file=p, nonce_db=tmp_path / "n.db"))
    return k, za.kid_of(spki)


def token(key, rid, problem, kind="prometheus.request", nonce="1" * 32):
    k, kid = key
    now = int(time.time())
    c = {"v": 1, "aud": "zeus-gateway", "alg": "ed25519", "kid": kid, "job_id": rid, "kind": kind, "executor": "zeus-gateway",
         "payload_sha256": za.payload_hash({"id": rid, "problem": problem}), "rev": 1, "nonce": nonce, "iat": now, "exp": now + 600}
    return za.sign_for_test(k, "ed25519", c)


def post(rid, problem, approval):
    return client.post("/api/prometheus/requests", headers=GOOD, json={"id": rid, "problem": problem, "approval": approval})


def test_only_a_signed_request_is_queued(key):
    assert post("p000000000001", "x", "zt1.e30.AA").status_code == 403
    assert post("p000000000001", "x", token(key, "p000000000001", "something else")).status_code == 403
    assert post("p000000000001", "x", token(key, "p000000000001", "x", kind="iris.task_done")).status_code == 403
    assert post("../etc", "x", token(key, "../etc", "x")).status_code == 422
    assert client.post("/api/prometheus/requests", json={"id": "p000000000001", "problem": "x", "approval": "a"}).status_code == 403
    t = token(key, "p000000000001", "Sort quick jobs first")
    r = post("p000000000001", "Sort quick jobs first", t)
    assert r.status_code == 200 and r.json()["status"] == "queued"
    # The same token for another request is a replay.
    assert post("p000000000002", "Sort quick jobs first", t).status_code == 403
    assert post("p000000000001", "Sort quick jobs first", token(key, "p000000000001", "Sort quick jobs first", nonce="2" * 32)).status_code == 409


def test_build_flow_and_download(key, tmp_path):
    rid = "p0000000000aa"
    assert post(rid, "Widget for Fates", token(key, rid, "Widget for Fates", nonce="3" * 32)).status_code == 200
    assert client.get(f"/api/prometheus/requests/{rid}/apk", headers=GOOD).status_code == 404
    gw.prom.claim(rid)
    up = tmp_path / "up"; up.mkdir()
    apk = b"PK\x03\x04 fake apk" * 100
    (up / "app.apk").write_bytes(apk)
    (up / "patch.diff").write_text("diff --git a b")
    (up / "build.json").write_text(json.dumps({"summary": "Adds the widget.", "diff": {"files": 3, "insertions": 80, "deletions": 2},
                                               "tests": {"passed": 420, "failed": 0, "line": "420 passed"}, "version_name": "1.5.1", "version_code": 131}))
    assert gw.prom.publish(rid, pq.read_upload(str(up)))["status"] == "ready"
    row = next(r for r in client.get("/api/prometheus/requests", headers=GOOD).json() if r["id"] == rid)
    assert row["build"]["apk_sha256"] == hashlib.sha256(apk).hexdigest() and row["build"]["diff"]["files"] == 3
    got = client.get(f"/api/prometheus/requests/{rid}/apk", headers=GOOD)
    assert got.status_code == 200 and got.content == apk
    assert client.get(f"/api/prometheus/requests/{rid}/apk").status_code == 403
    assert client.post(f"/api/prometheus/requests/{rid}/cancel", headers=GOOD).status_code == 409


def test_failing_tests_never_serve_an_apk(key, tmp_path):
    rid = "p0000000000bb"
    post(rid, "Break it", token(key, rid, "Break it", nonce="4" * 32))
    gw.prom.claim(rid)
    up = tmp_path / "up"; up.mkdir()
    (up / "app.apk").write_bytes(b"PK")
    (up / "build.json").write_text(json.dumps({"tests": {"passed": 1, "failed": 2}}))
    assert gw.prom.publish(rid, pq.read_upload(str(up)))["status"] == "failed"
    assert client.get(f"/api/prometheus/requests/{rid}/apk", headers=GOOD).status_code == 404


def test_cancel_while_queued(key):
    rid = "p0000000000cc"
    post(rid, "Never mind", token(key, rid, "Never mind", nonce="5" * 32))
    assert client.post(f"/api/prometheus/requests/{rid}/cancel", headers=GOOD).json()["status"] == "cancelled"
    with pytest.raises(ValueError):
        gw.prom.claim(rid)
    assert client.post("/api/prometheus/requests/p0000000000ff/cancel", headers=GOOD).status_code == 404


def test_planted_symlinks_are_never_followed(key, tmp_path):
    rid = "p0000000000dd"
    post(rid, "Links", token(key, rid, "Links", nonce="6" * 32))
    gw.prom.claim(rid)
    d = gw.prom.root / rid
    target = tmp_path / "victim"; target.write_bytes(b"untouched")
    # Links where the temp files go, and where the final apk goes.
    for name in (".request.json.tmp", ".build.json.tmp", ".app.apk.tmp", ".patch.diff.tmp", "app.apk"):
        (d / name).symlink_to(target)
    files = {"build.json": json.dumps({"tests": {"passed": 1, "failed": 0}}).encode(), "app.apk": b"PK new", "patch.diff": b"d"}
    assert gw.prom.publish(rid, files)["status"] == "ready"
    assert target.read_bytes() == b"untouched"
    assert not (d / "app.apk").is_symlink() and (d / "app.apk").read_bytes() == b"PK new"
    # The upload reader refuses a linked file or folder too.
    up = tmp_path / "up"; up.mkdir()
    (up / "build.json").symlink_to(target)
    with pytest.raises(OSError):
        pq.read_upload(str(up))
    (tmp_path / "uplink").symlink_to(up, target_is_directory=True)
    with pytest.raises(OSError):
        pq.read_upload(str(tmp_path / "uplink"))
