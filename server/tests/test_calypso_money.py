"""Calypso drops (seal, intake, ack) and Moneta's ledger feed. Run: python -m pytest server/tests -q

Also regenerates tests/calypso_vector.json, which the phone's CalypsoTest opens with the same TEST key."""
import base64
import importlib.machinery
import importlib.util
import json
import os
import sqlite3
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


drop = load("zeus_calypso_drop", ROOT / "bin" / "zeus-calypso-drop")


def open_env(env, priv):
    key = priv.decrypt(base64.b64decode(env["wk"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None))
    framed = AESGCM(key).decrypt(base64.b64decode(env["iv"]), base64.b64decode(env["ct"]), drop.aad(env["id"], env["kind"], env["label"], env["from"]))
    n = int.from_bytes(framed[:2], "big")
    return framed[2:2 + n].decode() or None, framed[2 + n:]


def test_seal_opens_only_with_the_private_key():
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    env = drop.seal(priv.public_key(), "secret", "Server API token", "atlas", b"hcloud-SECRET-123")
    assert b"hcloud-SECRET-123" not in json.dumps(env).encode()
    assert open_env(env, priv) == (None, b"hcloud-SECRET-123")
    other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    try:
        open_env(env, other)
        assert False, "another key opened it"
    except ValueError:
        pass


def test_label_is_bound_to_the_content():
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    env = drop.seal(priv.public_key(), "note", "Passport note", "iris", b"renew by March")
    env["label"] = "Something else"
    try:
        open_env(env, priv)
        assert False, "a swapped label still opened"
    except Exception:
        pass


def test_intake_and_ack(tmp_path, monkeypatch):
    monkeypatch.setenv("ZEUS_STATE", str(tmp_path / "state"))
    monkeypatch.setenv("ZEUS_CALYPSO_SPOOL", str(tmp_path / "spool"))
    monkeypatch.setenv("ZEUS_OUTBOX", str(tmp_path / "outbox"))
    for d in ("spool", "outbox"):
        (tmp_path / d).mkdir()
    sys.path.insert(0, str(ROOT))
    for m in [m for m in sys.modules if m.startswith("gateway")]:
        del sys.modules[m]
    from gateway import calypso as cal

    class Audit:
        rows = []
        def audit(self, a, d): self.rows.append((a, d))

    c = cal.Calypso(Audit())
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    env = drop.seal(priv.public_key(), "file", "Card statement", "iris", b"%PDF-1.7 fake", name="oct.pdf")
    (tmp_path / "spool" / f"{env['id']}.json").write_text(json.dumps(env))
    (tmp_path / "spool" / "bad.json").write_text(json.dumps({"v": 1, "id": "x"}))
    assert c.intake() == 1
    assert list((tmp_path / "spool").iterdir()) == []
    got = c.drops()
    assert [g["id"] for g in got] == [env["id"]] and open_env(got[0], priv) == ("oct.pdf", b"%PDF-1.7 fake")
    assert c.ack([env["id"], "../../etc/passwd"]) == 1 and c.drops() == []
    pem = priv.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    c.publish_key(pem)
    assert (tmp_path / "outbox" / "calypso.pub.pem").read_text() == pem


def test_money_ledger_is_the_full_log_without_card_numbers(tmp_path):
    dbp = tmp_path / "owner.db"
    con = sqlite3.connect(dbp)
    # owner.db's transactions and subscriptions columns.
    con.execute("CREATE TABLE transactions (id INTEGER PRIMARY KEY, date TEXT, amount REAL, currency TEXT, category TEXT, description TEXT, type TEXT, "
                "created_at TIMESTAMP, email_message_id TEXT, card TEXT, status TEXT, estimated_amount REAL, trip_time TEXT, order_id TEXT)")
    con.execute("CREATE TABLE subscriptions (id INTEGER PRIMARY KEY, name TEXT, amount REAL, currency TEXT, billing_cycle TEXT, next_billing_date TEXT, status TEXT, notes TEXT, created_at TIMESTAMP)")
    rows = [("2026-10-02", 54.21, None, "USD", "Groceries", "GROCER MART #0123", "charge", "Visa Gold 31005", "posted"),
            ("2026-10-03", None, 30.0, "USD", "Groceries", "CORNER MARKET", "charge", "4111 1111 1111 5678", "pending"),
            ("2026-10-04", 1234.56, None, "USD", "Balance", "Checking balance", "balance", "9876", ""),
            ("2026-10-05", 5000.0, None, "EUR", "Groceries", "WEBSHOP order 12345678901", "charge", "", "")]
    con.executemany("INSERT INTO transactions (date, amount, estimated_amount, currency, category, description, type, card, status) VALUES (?,?,?,?,?,?,?,?,?)", rows)
    con.execute("INSERT INTO subscriptions (name, amount, currency, billing_cycle, next_billing_date, status, notes) VALUES ('StreamBox', 9.99, 'USD', 'monthly', '2026-11-05', 'active', 'card 4111111111111111')")
    con.commit(); con.close()
    oc = load("zeus_openclaw", ROOT / "bin" / "zeus-openclaw")
    oc.IRIS_DB = str(dbp)
    out = oc.money_ledger(0, 500)
    assert [r["id"] for r in out["rows"]] == [1, 2, 4]          # the balance row never leaves
    a, b, c = out["rows"]
    assert (a["cents"], a["merchant"], a["last4"], a["kind"]) == (5421, "GROCER MART #0123", "1005", "charge")
    assert (b["cents"], b["last4"]) == (3000, "5678")            # the estimate, and only the last four
    assert c["currency"] == "EUR" and "12345678901" not in c["merchant"]
    text = json.dumps(out)
    for leak in ("4111", "31005", "1234.56", "123456", "Checking"):
        assert leak not in text, leak
    assert out["subscriptions"][0] | {"id": 0} == {"id": 0, "name": "StreamBox", "cents": 999, "currency": "USD", "billing_cycle": "monthly",
                                                   "next_billing_date": "2026-11-05", "status": "active"}
    assert out["next"] == 4 and out["more"] is False
    assert oc.money_ledger(2, 500)["rows"][0]["id"] == 4
    page = oc.money_ledger(0, 1)
    assert page["more"] is True and page["next"] == 1


def test_write_vector_for_the_phone():
    """A fixed TEST key (never used anywhere else) and one envelope per kind, for app CalypsoTest."""
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pkcs8 = priv.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    envs = [drop.seal(priv.public_key(), "secret", "Hetzner token", "atlas", "hcloud-test-✓".encode(), at_ms=1792000000000),
            drop.seal(priv.public_key(), "file", "Scan", "iris", b"\x00\x01binary", name="scan.png", at_ms=1792000000001)]
    out = {"pkcs8_b64": base64.b64encode(pkcs8).decode(), "envelopes": envs, "plain": ["hcloud-test-✓", base64.b64encode(b"\x00\x01binary").decode()], "names": [None, "scan.png"]}
    path = Path(__file__).with_name("calypso_vector.json")
    if not path.exists() or os.environ.get("ZEUS_REGEN_VECTORS"):
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    assert json.loads(path.read_text(encoding="utf-8"))["envelopes"]


def test_calypso_intake_refuses_symlinks_and_fifos(tmp_path, monkeypatch):
    monkeypatch.setenv("ZEUS_STATE", str(tmp_path / "state"))
    monkeypatch.setenv("ZEUS_CALYPSO_SPOOL", str(tmp_path / "spool"))
    monkeypatch.setenv("ZEUS_OUTBOX", str(tmp_path / "outbox"))
    for d in ("spool", "outbox"):
        (tmp_path / d).mkdir()
    sys.path.insert(0, str(ROOT))
    for m in [m for m in sys.modules if m.startswith("gateway")]:
        del sys.modules[m]
    from gateway import calypso as cal

    class Audit:
        rows = []
        def audit(self, a, d): self.rows.append((a, d))

    c = cal.Calypso(Audit())
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    env = drop.seal(priv.public_key(), "note", "Passport note", "iris", b"renew by March")
    real = tmp_path / "real.json"
    real.write_text(json.dumps(env))
    (tmp_path / "spool" / f"{env['id']}.json").symlink_to(real)
    os.mkfifo(tmp_path / "spool" / "pipe.json")
    assert c.intake() == 0 and c.drops() == []
    assert len([a for a, _ in Audit.rows if a == "calypso.bad"]) == 2
    assert real.exists() and list((tmp_path / "spool").iterdir()) == []
