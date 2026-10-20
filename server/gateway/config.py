"""Where things live. Every path can be moved with an environment variable (the tests do)."""
import json
import os
from pathlib import Path


def _p(name, default):
    return Path(os.environ.get(name, default))


STATE = _p("ZEUS_STATE", "/var/lib/zeus")            # zeus:zeus 0750, the gateway's own
DB = STATE / "zeus.db"
APPROVAL_DB = STATE / "approvals.db"
SPOOL = _p("ZEUS_SPOOL", "/var/lib/zeus-spool")      # openclaw:zeus 2770: the agents' hooks drop replies, the gateway reads them by group
OUTBOX = _p("ZEUS_OUTBOX", "/var/lib/zeus-outbox")   # zeus:openclaw 2750: long messages for Atlas to read
ALERTS = _p("ZEUS_ALERTS", "/var/spool/zeus-alerts")  # zeus:zeus 1733: zeus-mirror drops service alerts here
MONETA_SPOOL = _p("ZEUS_MONETA_SPOOL", "/var/spool/zeus-moneta")    # openclaw:zeus 2770: Iris's Moneta ops (zeus-moneta); only openclaw's regular files count
IRIS_USER = os.environ.get("ZEUS_IRIS_USER", "openclaw")
PROMETHEUS = _p("ZEUS_PROMETHEUS", "/var/lib/zeus-prometheus")   # zeus:zeus 0750: app-change requests; root (the laptop over SSH) publishes builds
USAGE_DIR = _p("ZEUS_USAGE_DIR", "/var/lib/zeus-usage")    # openclaw:zeus 2750: Iris's status line pass-through writes iris.json (0640)
CALYPSO_SPOOL = _p("ZEUS_CALYPSO_SPOOL", "/var/spool/zeus-calypso")  # zeus:zeus 1733: sealed Calypso drops (zeus-calypso-drop)
CHANNEL_SOCK = _p("ZEUS_CHANNEL_SOCK", "/run/zeus-channel/ch.sock")   # dir zeus:openclaw 2750, the Iris plugin connects
DEVICES = _p("ZEUS_DEVICES", "/etc/zeus/devices.json")                # root:zeus 0640
PROXY_KEY = _p("ZEUS_PROXY_KEY", "/etc/zeus/proxy.key")              # root:zeus 0640, same value Caddy sends
APPROVAL_KEYS = _p("ZEUS_APPROVAL_KEYS", "/etc/zeus/approval-keys.json")  # root 0644 (public keys)
FCM_ACCOUNT = _p("ZEUS_FCM_ACCOUNT", "/etc/zeus/fcm-service-account.json")  # root:zeus 0640
ANTHROPIC_WEBHOOK_SECRET = _p("ZEUS_ANTHROPIC_WEBHOOK_SECRET", "/etc/zeus/anthropic-webhook.secret")  # root:zeus 0640, whsec_...
SCAN_FILE = _p("ZEUS_SCAN_FILE", "/var/log/security-check/latest.json")
CVE_LOG = _p("ZEUS_CVE_LOG", "/var/log/cve-watch.log")
BACKUP_UNIT = os.environ.get("ZEUS_BACKUP_UNIT", "iris-borg-backup.service")
CADDYFILE = _p("ZEUS_CADDYFILE", "/etc/caddy/Caddyfile")   # site names for Horologion; unreadable means none
# Mnemosyne: the phone's encrypted backups, on a storage box subfolder mounted for zeus (uid zeus, 0700). Writes are
# refused while it isn't a mount point, so a missing mount never fills the local disk.
MNEMOSYNE = _p("ZEUS_MNEMOSYNE", "/mnt/zeus-backups")
MNEMOSYNE_MAX = int(os.environ.get("ZEUS_MNEMOSYNE_MAX", str(1 << 30)))
MNEMOSYNE_REQUIRE_MOUNT = os.environ.get("ZEUS_MNEMOSYNE_REQUIRE_MOUNT", "1") == "1"

# The two helpers, through sudo. Tests point these at the scripts directly.
AS_OPENCLAW = json.loads(os.environ.get("ZEUS_AS_OPENCLAW", '["/usr/bin/sudo", "-n", "-u", "openclaw", "/opt/zeus/bin/zeus-openclaw"]'))
AS_ROOT = json.loads(os.environ.get("ZEUS_AS_ROOT", '["/usr/bin/sudo", "-n", "/opt/zeus/bin/zeus-root"]'))

# The workers on this box. Display names are Zeus's; session names are what tmux calls them.
AGENTS = {
    "iris": {"name": "Iris", "kind": "claude", "session": "claude-auto", "proc": "claude", "via": "channel"},
    "atlas": {"name": "Atlas", "kind": "codex", "session": "codex", "proc": "codex", "via": "tmux"},
}
CHANNEL_FLAG = "zeus@zeus-local"   # in Iris's command line when her session loads the zeus channel

SLASH = {
    "claude": [("/compact", "Summarise to free context"), ("/clear", "Start fresh"), ("/model", "Pick the model"), ("/cost", "Spend so far"),
               ("/status", "Account and model"), ("/usage", "Plan limits"), ("/context", "What fills the context"),
               ("/resume", "Resume a conversation"), ("/memory", "Memory files"), ("/mcp", "MCP servers and channels"), ("/help", "All commands")],
    "codex": [("/compact", "Summarise to free context"), ("/new", "New conversation"), ("/model", "Model and effort"),
              ("/status", "Session and limits"), ("/diff", "Git diff"), ("/review", "Review changes"), ("/approvals", "What it may do alone")],
}
