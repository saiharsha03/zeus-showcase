"""Every path the gateway uses points into a temp folder; the helper runs directly (no sudo) with test roots."""
import json
import os
import sqlite3
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER = HERE.parent
TMP = Path(tempfile.mkdtemp(prefix="zeus-test-"))
ROOTS = {"home": str(TMP / "home"), "volume": str(TMP / "volume")}
for d in ["state", "spool", "outbox", "alerts", "run", "etc", "home", "volume", "backups", "usage", "codex"]:
    (TMP / d).mkdir()

PROXY_KEY = "k" * 64
FP = "ab" * 32
(TMP / "etc" / "proxy.key").write_text(PROXY_KEY + "\n")
(TMP / "etc" / "devices.json").write_text(json.dumps({"devices": [{"sha256": FP}]}))
(TMP / "etc" / "approval-keys.json").write_text(json.dumps({"keys": []}))

db = sqlite3.connect(TMP / "owner.db")
db.executescript("""CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT, status TEXT, priority TEXT, due_date TEXT, owner TEXT,
  blocked_on_owner INTEGER, project TEXT, notes TEXT, context TEXT);
INSERT INTO tasks VALUES (1, 'Renew the registration', 'pending', 'high', '2026-10-12', 'owner', 1, 'car', 'form on file', '');
INSERT INTO tasks VALUES (2, 'Pay the card', 'pending', 'medium', NULL, 'iris', 0, NULL, 'secret detail', 'money');
CREATE TABLE fillups (id INTEGER PRIMARY KEY, date TEXT, odometer INTEGER, gallons REAL, mpg_calc REAL, card_last4 TEXT);
INSERT INTO fillups VALUES (1, '2026-10-01', 12000, 10.2, 31.0, '4242');""")
db.commit(); db.close()

os.environ.update({
    "ZEUS_STATE": str(TMP / "state"), "ZEUS_SPOOL": str(TMP / "spool"), "ZEUS_OUTBOX": str(TMP / "outbox"), "ZEUS_ALERTS": str(TMP / "alerts"),
    "ZEUS_CHANNEL_SOCK": str(TMP / "run" / "ch.sock"), "ZEUS_DEVICES": str(TMP / "etc" / "devices.json"),
    "ZEUS_PROXY_KEY": str(TMP / "etc" / "proxy.key"), "ZEUS_APPROVAL_KEYS": str(TMP / "etc" / "approval-keys.json"),
    "ZEUS_FCM_ACCOUNT": str(TMP / "etc" / "none.json"), "ZEUS_SCAN_FILE": str(TMP / "scan.json"), "ZEUS_CVE_LOG": str(TMP / "cve.log"),
    "ZEUS_AS_OPENCLAW": json.dumps([sys.executable, str(SERVER / "bin" / "zeus-openclaw")]),
    "ZEUS_AS_ROOT": json.dumps([sys.executable, "-c", "import sys; sys.stderr.write('{\"code\": 403, \"error\": \"test\"}'); sys.exit(2)"]),
    "ZEUS_PROMETHEUS": str(TMP / "prometheus"),
    "ZEUS_MNEMOSYNE": str(TMP / "backups"), "ZEUS_MNEMOSYNE_REQUIRE_MOUNT": "0", "ZEUS_MNEMOSYNE_MAX": str(1 << 20),
    "ZEUS_USAGE_DIR": str(TMP / "usage"), "ZEUS_TEST_CODEX": str(TMP / "codex"),
    "ZEUS_TEST_ROOTS": json.dumps(ROOTS), "ZEUS_TEST_IRIS_DB": str(TMP / "owner.db"),
})
sys.path.insert(0, str(SERVER))
sys.path.insert(0, str(SERVER / "lib"))

GOOD = {"x-zeus-client-sha256": FP, "x-zeus-proxy-key": PROXY_KEY}
