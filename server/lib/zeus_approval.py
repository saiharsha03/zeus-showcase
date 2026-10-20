"""Zeus approval tokens (zt1): the owner's biometric-gated signature over one exact job.

A job that changes or sends something runs only with a token that verifies here. Unsigned = suggestion.
Format (docs/ZEUS-GATEWAY.md):

    token  = "zt1." + b64url(claims) + "." + b64url(sig)
    claims = canonical JSON {v, aud, alg, kid, job_id, kind, executor, payload_sha256, rev, nonce, iat, exp}
    sig    = Ed25519 over the claims bytes, or SHA256withECDSA (DER) for es256

Keys: /etc/zeus/approval-keys.json, written only by pairing (root-owned):
    {"keys": [{"kid": "...", "alg": "ed25519"|"es256", "spki": "<base64 DER>", "added": 1790000000}]}
Nonces: a nonce is bound to the first job_id that used it. The same job may verify again (the executor
re-checking), any other job with that nonce is a replay. Stored in sqlite (ZEUS_APPROVAL_DB).

CLI:  zeus-approval verify --token T --job J --kind K --executor E --payload-file F   (exit 0 = approved)
"""
import base64
import hashlib
import json
import os
import sqlite3
import sys
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519

AUD = "zeus-gateway"
MAX_LIFETIME_S = 900
SKEW_S = 30
FUTURE_S = 60
KEYS_FILE = Path(os.environ.get("ZEUS_APPROVAL_KEYS", "/etc/zeus/approval-keys.json"))
NONCE_DB = Path(os.environ.get("ZEUS_APPROVAL_DB", os.path.expanduser("~/.zeus/approvals.db")))
CLAIM_KEYS = {"v", "aud", "alg", "kid", "job_id", "kind", "executor", "payload_sha256", "rev", "nonce", "iat", "exp"}


class Rejected(Exception):
    pass


def canonical(obj) -> bytes:
    """sort_keys, no spaces, UTF-8 as is. Floats are refused: their text form differs between languages."""
    def check(x):
        if isinstance(x, float):
            raise ValueError("floats are not allowed in signed payloads")
        if isinstance(x, dict):
            for k, v in x.items():
                if not isinstance(k, str):
                    raise ValueError("object keys must be strings")
                check(v)
        elif isinstance(x, list):
            for v in x:
                check(v)
        elif not (x is None or isinstance(x, (str, int, bool))):
            raise ValueError(f"unsupported type {type(x).__name__}")
    check(obj)
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def payload_hash(payload) -> str:
    return hashlib.sha256(canonical(payload)).hexdigest()


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def unb64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def kid_of(spki_der: bytes) -> str:
    return hashlib.sha256(spki_der).hexdigest()[:16]


def load_keys(path: Path = None) -> dict[str, dict]:
    path = path or KEYS_FILE
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {}
    return {k["kid"]: k for k in data.get("keys", []) if k.get("kid") and k.get("spki")}


def _check_sig(alg: str, spki_der: bytes, sig: bytes, msg: bytes) -> None:
    pub = serialization.load_der_public_key(spki_der)
    try:
        if alg == "ed25519" and isinstance(pub, ed25519.Ed25519PublicKey):
            pub.verify(sig, msg)
        elif alg == "es256" and isinstance(pub, ec.EllipticCurvePublicKey) and pub.curve.name == "secp256r1":
            pub.verify(sig, msg, ec.ECDSA(hashes.SHA256()))
        else:
            raise Rejected("key and algorithm don't match")
    except InvalidSignature:
        raise Rejected("bad signature")


def parse(token: str) -> tuple[dict, bytes, bytes]:
    parts = token.strip().split(".")
    if len(parts) != 3 or parts[0] != "zt1":
        raise Rejected("not a zt1 token")
    try:
        raw, sig = unb64u(parts[1]), unb64u(parts[2])
        claims = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise Rejected("malformed token")
    if not isinstance(claims, dict) or set(claims) != CLAIM_KEYS:
        raise Rejected("claims have the wrong fields")
    if canonical(claims) != raw:
        raise Rejected("claims are not canonical")
    return claims, raw, sig


class Verifier:
    def __init__(self, keys_file: Path = None, nonce_db: Path = None, now=time.time):
        self.keys_file, self.now = keys_file or KEYS_FILE, now
        self.db_path = nonce_db or NONCE_DB
        self.db_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(self.db_path, isolation_level=None, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, job_id TEXT NOT NULL, kind TEXT NOT NULL, "
                        "exp INTEGER NOT NULL, first_ts REAL NOT NULL)")

    def verify(self, token: str, job_id: str, kind: str, executor: str, payload) -> dict:
        """Returns the claims when the token approves exactly this job. Raises Rejected otherwise."""
        claims, raw, sig = parse(token)
        key = load_keys(self.keys_file).get(claims["kid"])
        if key is None:
            raise Rejected("unknown signing key")
        if key.get("alg") != claims["alg"]:
            raise Rejected("algorithm differs from the registered key")
        _check_sig(claims["alg"], base64.b64decode(key["spki"]), sig, raw)
        if claims["v"] != 1 or claims["aud"] != AUD:
            raise Rejected("wrong audience or version")
        now = self.now()
        if not isinstance(claims["iat"], int) or not isinstance(claims["exp"], int):
            raise Rejected("bad times")
        if claims["exp"] - claims["iat"] > MAX_LIFETIME_S or claims["exp"] < claims["iat"]:
            raise Rejected("lifetime too long")
        if now > claims["exp"] + SKEW_S:
            raise Rejected("expired")
        if claims["iat"] > now + FUTURE_S:
            raise Rejected("issued in the future")
        if claims["job_id"] != job_id or claims["kind"] != kind or claims["executor"] != executor:
            raise Rejected("token is for a different job")
        if claims["payload_sha256"] != payload_hash(payload):
            raise Rejected("payload changed after approval")
        if not (isinstance(claims["nonce"], str) and len(claims["nonce"]) == 32):
            raise Rejected("bad nonce")
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self.db.execute("SELECT job_id FROM nonces WHERE nonce=?", (claims["nonce"],)).fetchone()
            if row is None:
                self.db.execute("INSERT INTO nonces VALUES (?,?,?,?,?)", (claims["nonce"], job_id, kind, claims["exp"], now))
            elif row[0] != job_id:
                raise Rejected("replayed approval")
            self.db.execute("DELETE FROM nonces WHERE exp < ?", (int(now) - 86400 * 30,))
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise
        return claims


def sign_for_test(private_key, alg: str, claims: dict) -> str:
    """Test helper only: the phone signs in Android Keystore."""
    raw = canonical(claims)
    sig = private_key.sign(raw) if alg == "ed25519" else private_key.sign(raw, ec.ECDSA(hashes.SHA256()))
    return f"zt1.{b64u(raw)}.{b64u(sig)}"


def main(argv=None) -> int:
    import argparse
    p = argparse.ArgumentParser(prog="zeus-approval")
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify", help="exit 0 when the token approves exactly this job")
    v.add_argument("--token", required=True)
    v.add_argument("--job", required=True)
    v.add_argument("--kind", required=True)
    v.add_argument("--executor", required=True)
    v.add_argument("--payload-file", required=True, help="JSON file with the exact payload ('-' for stdin)")
    sub.add_parser("hash", help="payload_sha256 of the JSON on stdin")
    a = p.parse_args(argv)
    if a.cmd == "hash":
        print(payload_hash(json.load(sys.stdin)))
        return 0
    payload = json.load(sys.stdin) if a.payload_file == "-" else json.loads(Path(a.payload_file).read_text())
    try:
        c = Verifier().verify(a.token, a.job, a.kind, a.executor, payload)
    except (Rejected, ValueError) as e:
        print(f"rejected: {e}")
        return 1
    print(f"approved: {c['job_id']} {c['kind']} (expires {c['exp']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
