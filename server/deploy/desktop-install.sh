#!/bin/bash
# The Lemnos remote desktop, install / update. Run as root from the unpacked bundle, after install.sh:
#   bash deploy/desktop-install.sh
# What it owns: user desk, /usr/local/lib/zeus-desktop, zeus-desktop.service, and one entry in /etc/zeus/units.json
# "control" (so zeus-root may start/stop it). Kept apart from install.sh, and out of /opt/zeus, so a gateway redeploy
# from another branch can't remove it. Nothing is enabled at boot: the gateway starts it when the phone asks.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
TS=$(date -u +%Y%m%dT%H%M%SZ)
BK=/opt/zeus-backups/desktop-$TS
install -d -m 700 "$BK"
cp -a /etc/zeus/units.json "$BK/" 2>/dev/null || true
cp -a /etc/systemd/system/zeus-desktop.service "$BK/" 2>/dev/null || true

echo "== packages (no recommends: XFCE's core, TigerVNC's Xvnc)"
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l     # list, never restart other services
apt-get install -y -q --no-install-recommends tigervnc-standalone-server xfce4-session xfwm4 xfce4-panel xfdesktop4 \
  xfce4-terminal thunar xfce4-settings dbus-x11 fonts-dejavu-core adwaita-icon-theme >/dev/null

echo "== user desk (no sudo, no password, not in any agent's group)"
id desk >/dev/null 2>&1 || useradd --create-home --shell /bin/bash --user-group desk
passwd -l desk >/dev/null
chmod 700 /home/desk

echo "== session"
install -d -o root -g root -m 755 /usr/local/lib/zeus-desktop
install -o root -g root -m 755 "$SRC/desktop/session" /usr/local/lib/zeus-desktop/session
install -o root -g root -m 644 "$SRC/desktop/xfwm4.xml" "$SRC/desktop/xfce4-desktop.xml" /usr/local/lib/zeus-desktop/
sed -i 's/\r$//' /usr/local/lib/zeus-desktop/*
install -o root -g root -m 644 "$SRC/desktop/zeus-desktop.service" /etc/systemd/system/zeus-desktop.service
sed -i 's/\r$//' /etc/systemd/system/zeus-desktop.service
systemctl daemon-reload

echo "== zeus-root may start/stop it"
python3 - <<'EOF'
import json
p = "/etc/zeus/units.json"
d = json.load(open(p))
c = d.setdefault("control", [])
if "zeus-desktop.service" not in c:
    c.append("zeus-desktop.service")
    json.dump(d, open(p, "w"))
EOF
chown root:root /etc/zeus/units.json; chmod 644 /etc/zeus/units.json
echo "== done ($BK)"
