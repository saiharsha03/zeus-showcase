import base64
import json
import time
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519

import zeus_approval as za

V = json.loads((Path(__file__).parent / "vectors.json").read_text())


def keys_file(tmp_path, pub, alg):
    spki = pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    p = tmp_path / "keys.json"
    p.write_text(json.dumps({"keys": [{"kid": za.kid_of(spki), "alg": alg, "spki": base64.b64encode(spki).decode()}]}))
    return p, za.kid_of(spki)


def claims(kid, alg="ed25519", now=None, **over):
    now = int(now or time.time())
    payload = {"task_id": 1}
    c = {"v": 1, "aud": "zeus-gateway", "alg": alg, "kid": kid, "job_id": "j1", "kind": "iris.task_done", "executor": "zeus-gateway",
         "payload_sha256": za.payload_hash(payload), "rev": 1, "nonce": "0" * 32, "iat": now, "exp": now + 600}
    c.update(over)
    return c


def test_vector_matches_the_phone():
    assert za.canonical(V["payload"]).decode() == V["payload_canonical"]
    assert za.payload_hash(V["payload"]) == V["payload_sha256"]
    assert za.canonical(V["claims"]).decode() == V["claims_canonical"]
    key = ed25519.Ed25519PrivateKey.from_private_bytes(bytes.fromhex(V["ed25519_seed_hex"]))
    assert za.sign_for_test(key, "ed25519", V["claims"]) == V["token"]


@pytest.fixture
def ed(tmp_path):
    k = ed25519.Ed25519PrivateKey.generate()
    path, kid = keys_file(tmp_path, k.public_key(), "ed25519")
    return k, kid, za.Verifier(keys_file=path, nonce_db=tmp_path / "n.db")


def test_good_token_verifies_and_rechecks_for_the_same_job(ed):
    k, kid, v = ed
    t = za.sign_for_test(k, "ed25519", claims(kid))
    assert v.verify(t, "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})["job_id"] == "j1"
    assert v.verify(t, "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})


@pytest.mark.parametrize("job,kind,executor,payload,why", [
    ("j2", "iris.task_done", "zeus-gateway", {"task_id": 1}, "different job"),
    ("j1", "iris.task_note", "zeus-gateway", {"task_id": 1}, "different job"),
    ("j1", "iris.task_done", "iris", {"task_id": 1}, "different job"),
    ("j1", "iris.task_done", "zeus-gateway", {"task_id": 2}, "payload changed"),
])
def test_binding(ed, job, kind, executor, payload, why):
    k, kid, v = ed
    with pytest.raises(za.Rejected, match=why):
        v.verify(za.sign_for_test(k, "ed25519", claims(kid)), job, kind, executor, payload)


def test_replay_tamper_expiry_audience(ed, tmp_path):
    k, kid, v = ed
    first = za.sign_for_test(k, "ed25519", claims(kid))
    v.verify(first, "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
    with pytest.raises(za.Rejected, match="replay"):     # same nonce, another job
        v.verify(za.sign_for_test(k, "ed25519", claims(kid, job_id="j9")), "j9", "iris.task_done", "zeus-gateway", {"task_id": 1})
    head, body, sig = first.split(".")
    raw = za.unb64u(body).replace(b'"rev":1', b'"rev":2')
    with pytest.raises(za.Rejected):
        v.verify(f"{head}.{za.b64u(raw)}.{sig}", "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
    old = int(time.time()) - 2000
    with pytest.raises(za.Rejected, match="expired"):
        v.verify(za.sign_for_test(k, "ed25519", claims(kid, now=old, nonce="1" * 32)), "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
    with pytest.raises(za.Rejected, match="audience"):
        v.verify(za.sign_for_test(k, "ed25519", claims(kid, aud="hub", nonce="2" * 32)), "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
    with pytest.raises(za.Rejected, match="lifetime"):
        v.verify(za.sign_for_test(k, "ed25519", claims(kid, exp=int(time.time()) + 5000, nonce="3" * 32)), "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
    other = ed25519.Ed25519PrivateKey.generate()
    with pytest.raises(za.Rejected, match="bad signature"):
        v.verify(za.sign_for_test(other, "ed25519", claims(kid, nonce="4" * 32)), "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})


def test_es256_fallback(tmp_path):
    k = ec.generate_private_key(ec.SECP256R1())
    path, kid = keys_file(tmp_path, k.public_key(), "es256")
    v = za.Verifier(keys_file=path, nonce_db=tmp_path / "n.db")
    assert v.verify(za.sign_for_test(k, "es256", claims(kid, alg="es256")), "j1", "iris.task_done", "zeus-gateway", {"task_id": 1})
