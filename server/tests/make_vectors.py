"""Regenerates tests/vectors.json (the Kotlin side checks its canonical bytes, hash and token against it)."""
import base64, hashlib, json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519
import zeus_approval as za

seed = hashlib.sha256(b"zeus-approval-test-vector-v1").digest()   # a TEST key, never registered anywhere
key = ed25519.Ed25519PrivateKey.from_private_bytes(seed)
spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
payload = {"task_id": 42, "text": "Café \"quoted\" \\ back\nnew\ttab \u0001 ✓ \U0001F600", "ok": True, "none": None,
           "list": [3, "x", {"z": 1, "a": -2}], "Zed": "", "a": {}}
claims = {"v": 1, "aud": "zeus-gateway", "alg": "ed25519", "kid": za.kid_of(spki), "job_id": "job-7f3a", "kind": "iris.task_note",
          "executor": "zeus-gateway", "payload_sha256": za.payload_hash(payload), "rev": 1, "nonce": "0123456789abcdef0123456789abcdef",
          "iat": 1792000000, "exp": 1792000600}
out = {
    "payload": payload, "payload_canonical": za.canonical(payload).decode(), "payload_sha256": za.payload_hash(payload),
    "claims": claims, "claims_canonical": za.canonical(claims).decode(),
    "ed25519_seed_hex": seed.hex(), "spki_b64": base64.b64encode(spki).decode(), "kid": za.kid_of(spki),
    "token": za.sign_for_test(key, "ed25519", claims),
}
Path(__file__).with_name("vectors.json").write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("ok", out["payload_sha256"][:12])
