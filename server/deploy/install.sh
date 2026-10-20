#!/bin/bash
# zeus-gateway install / update. Run as root from the unpacked bundle:  bash deploy/install.sh
# What it owns: user zeus, /opt/zeus, /var/lib/zeus*, /var/spool/zeus-alerts, /etc/zeus, /etc/sudoers.d/zeus, the
# zeus-gateway unit and Caddy's zeus.example.com block. Everything it changes is backed up first.
# It never touches Telegram, the Iris/Atlas sessions, their launchers, SSH or the firewall.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
TS=$(date -u +%Y%m%dT%H%M%SZ)
BK=/opt/zeus-backups/$TS
install -d -m 700 "$BK"

echo "== backup -> $BK"
cp -a /etc/caddy/Caddyfile "$BK/Caddyfile"
cp -a /etc/sudoers.d/zeus* "$BK/" 2>/dev/null || true
cp -a /etc/systemd/system/zeus-gateway.service "$BK/" 2>/dev/null || true
cp -a /home/agent/.codex/config.toml "$BK/codex-config.toml" 2>/dev/null || true
ls -la /etc/zeus > "$BK/etc-zeus.ls" 2>/dev/null || true

echo "== user zeus (no login, no home of its own beyond its state dir)"
id zeus >/dev/null 2>&1 || useradd --system --home-dir /var/lib/zeus --no-create-home --shell /usr/sbin/nologin zeus

systemctl stop zeus-gateway 2>/dev/null || true

echo "== code (root-owned: neither zeus nor openclaw can change it)"
install -d -o root -g root -m 755 /opt/zeus
for d in gateway lib bin hooks plugin deploy docs; do
  rm -rf "/opt/zeus/$d.new"; cp -r "$SRC/$d" "/opt/zeus/$d.new"
  rm -rf "/opt/zeus/$d"; mv "/opt/zeus/$d.new" "/opt/zeus/$d"
done
rm -rf /opt/zeus/app /opt/zeus/app.old /opt/zeus/deploy.old /opt/zeus/hooks.old /opt/zeus/lib.old /opt/zeus/plugin.old /opt/zeus/helper
find /opt/zeus/gateway /opt/zeus/lib /opt/zeus/bin /opt/zeus/hooks /opt/zeus/plugin /opt/zeus/deploy /opt/zeus/docs -name __pycache__ -prune -exec rm -rf {} +
find /opt/zeus/gateway /opt/zeus/lib /opt/zeus/bin /opt/zeus/hooks /opt/zeus/plugin /opt/zeus/deploy /opt/zeus/docs -type f -exec sed -i 's/\r$//' {} +
chown -R root:root /opt/zeus/gateway /opt/zeus/lib /opt/zeus/bin /opt/zeus/hooks /opt/zeus/plugin /opt/zeus/deploy /opt/zeus/docs
chmod -R u=rwX,go=rX /opt/zeus/gateway /opt/zeus/lib /opt/zeus/bin /opt/zeus/hooks /opt/zeus/plugin /opt/zeus/deploy /opt/zeus/docs
chmod 755 /opt/zeus/bin/*
[ -x /opt/zeus/venv/bin/python ] || python3 -m venv /opt/zeus/venv
/opt/zeus/venv/bin/pip install -q --disable-pip-version-check -r "$SRC/requirements.txt"

echo "== state dirs"
install -d -o zeus -g zeus -m 750 /var/lib/zeus
install -d -o openclaw -g zeus -m 2770 /var/lib/zeus-spool       # hooks (openclaw) drop, the gateway reads by group
install -d -o zeus -g openclaw -m 2750 /var/lib/zeus-outbox      # long messages Atlas reads by group
install -d -o zeus -g zeus -m 1733 /var/spool/zeus-alerts        # anyone may drop an alert; only zeus lists or removes
chown zeus:zeus /var/spool/zeus-alerts; chmod 1733 /var/spool/zeus-alerts
install -d -o zeus -g zeus -m 1733 /var/spool/zeus-calypso       # sealed Calypso drops: anyone may drop; only zeus lists or reads
chown zeus:zeus /var/spool/zeus-calypso; chmod 1733 /var/spool/zeus-calypso
install -d -o openclaw -g zeus -m 2770 /var/spool/zeus-moneta    # Iris's Moneta ops: only openclaw drops; the gateway reads and removes by group
chown openclaw:zeus /var/spool/zeus-moneta; chmod 2770 /var/spool/zeus-moneta
find /var/spool/zeus-moneta -mindepth 1 ! -user openclaw -delete      # leftovers from the old 1733 spool that aren't Iris's
install -d -o openclaw -g zeus -m 2750 /var/lib/zeus-usage      # Metron: Iris's status line pass-through writes iris.json (0640); the gateway reads by group
install -d -o zeus -g zeus -m 750 /var/lib/zeus-prometheus     # Prometheus requests; only root (the laptop over SSH) publishes builds
bash "$SRC/deploy/mnemosyne-mount.sh" || echo "!! Mnemosyne's mount failed; backups answer 503 until it is fixed"   # phone backups (ciphertext)

echo "== /etc/zeus"
install -d -o root -g root -m 755 /etc/zeus
[ -f /etc/zeus/devices.json ] || echo '{"devices": []}' > /etc/zeus/devices.json
chown root:zeus /etc/zeus/devices.json; chmod 640 /etc/zeus/devices.json
[ -f /etc/zeus/approval-keys.json ] || echo '{"keys": []}' > /etc/zeus/approval-keys.json
chown root:root /etc/zeus/approval-keys.json; chmod 644 /etc/zeus/approval-keys.json   # public keys; zeus-approval needs them
if [ -f /etc/zeus/fcm-service-account.json ]; then chown root:zeus /etc/zeus/fcm-service-account.json; chmod 640 /etc/zeus/fcm-service-account.json; fi
if [ ! -s /etc/zeus/proxy.key ]; then (umask 077; openssl rand -hex 32 > /etc/zeus/proxy.key); fi
chown root:zeus /etc/zeus/proxy.key; chmod 640 /etc/zeus/proxy.key
install -o root -g caddy -m 640 /etc/zeus/proxy.key /etc/caddy/zeus-proxy.key
if [ ! -f /etc/zeus/ca.crt ]; then
  (umask 077; openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj "/CN=Zeus device CA" \
     -keyout /etc/zeus/ca.key -out /etc/zeus/ca.crt 2>/dev/null)
fi
chmod 600 /etc/zeus/ca.key; chmod 644 /etc/zeus/ca.crt
install -m 644 /etc/zeus/ca.crt /etc/caddy/zeus-device-ca.pem
[ -f /etc/zeus/units.json ] || cat > /etc/zeus/units.json <<'EOF'
{"control": ["filebrowser.service", "security-scan.service", "cve-watch.service", "iris-borg-backup.service"]}
EOF
chown root:root /etc/zeus/units.json; chmod 644 /etc/zeus/units.json
rm -f /etc/zeus/helper.json

echo "== sudoers: zeus may run exactly two helpers"
cat > "$BK/sudoers.zeus" <<'EOF'
# zeus-gateway (user zeus) reaches openclaw's tmux, files and owner.db only through zeus-openclaw, and root only
# through zeus-root. Both check every argument. Allowed calls aren't logged (the gateway audits them itself), so
# the 4-hourly scan's sudo count isn't flooded by the phone's file browsing.
Defaults:zeus !lecture, !log_allowed
zeus ALL=(openclaw) NOPASSWD: /opt/zeus/bin/zeus-openclaw
zeus ALL=(root) NOPASSWD: /opt/zeus/bin/zeus-root
EOF
visudo -cf "$BK/sudoers.zeus" >/dev/null
install -o root -g root -m 440 "$BK/sudoers.zeus" /etc/sudoers.d/zeus
rm -f /etc/sudoers.d/zeus-helper /usr/local/sbin/zeus-helper
visudo -c >/dev/null

echo "== links: alert mirror (security-alerter and tg-media-dl call claw-mirror) and the approval checker"
ln -sfn /opt/zeus/bin/zeus-mirror /usr/local/bin/claw-mirror
cat > /usr/local/bin/zeus-approval <<'EOF'
#!/bin/sh
exec /opt/zeus/venv/bin/python -I /opt/zeus/lib/zeus_approval.py "$@"
EOF
chmod 755 /usr/local/bin/zeus-approval
cat > /usr/local/bin/zeus-calypso-drop <<'EOF'
#!/bin/sh
exec /opt/zeus/venv/bin/python -I /opt/zeus/bin/zeus-calypso-drop "$@"
EOF
chmod 755 /usr/local/bin/zeus-calypso-drop
cat > /usr/local/bin/zeus-moneta <<'EOF'
#!/bin/sh
exec /opt/zeus/venv/bin/python -I /opt/zeus/bin/zeus-moneta "$@"
EOF
chmod 755 /usr/local/bin/zeus-moneta
cat > /usr/local/sbin/zeus-prometheus <<'EOF'
#!/bin/sh
exec /opt/zeus/venv/bin/python -I /opt/zeus/bin/zeus-prometheus "$@"
EOF
chmod 700 /usr/local/sbin/zeus-prometheus

echo "== Atlas: Codex notify -> /opt/zeus/hooks/atlas_notify.py (takes effect when Codex next starts)"
CFG=/home/agent/.codex/config.toml
if [ -f "$CFG" ]; then
  python3 - "$CFG" <<'EOF'
import re, sys
p = sys.argv[1]; s = open(p).read()
line = 'notify = ["/usr/bin/python3", "-I", "/opt/zeus/hooks/atlas_notify.py"]'
s = re.sub(r'^notify\s*=.*$', line, s, count=1, flags=re.M) if re.search(r'^notify\s*=', s, re.M) else line + "\n" + s
open(p, "w").write(s)
EOF
fi

echo "== service"
cat > /etc/systemd/system/zeus-gateway.service <<'EOF'
[Unit]
Description=zeus-gateway: Zeus phone app backend (Lemnos, Pantheon, Cerberus). Unix socket only, behind Caddy mTLS.
After=network.target

[Service]
User=zeus
Group=zeus
WorkingDirectory=/opt/zeus
# Caddy's socket: only group caddy may enter. The channel socket: only group openclaw (Iris's plugin).
ExecStartPre=+/usr/bin/install -d -o zeus -g caddy -m 2750 /run/zeus-gateway
ExecStartPre=+/usr/bin/install -d -o zeus -g openclaw -m 2750 /run/zeus-channel
ExecStartPre=+/bin/rm -f /run/zeus-gateway/gw.sock /run/zeus-channel/ch.sock
ExecStart=/opt/zeus/venv/bin/uvicorn gateway.app:app --uds /run/zeus-gateway/gw.sock --no-access-log --timeout-graceful-shutdown 3
# uvicorn makes its socket 0666; the 2750 directory already keeps everyone but zeus and caddy out, and this narrows the file too.
ExecStartPost=+/bin/sh -c 'for i in 1 2 3 4 5 6 7 8 9 10; do [ -S /run/zeus-gateway/gw.sock ] && exec chmod 660 /run/zeus-gateway/gw.sock; sleep 0.5; done'
UMask=0117
Restart=on-failure
RestartSec=2
TimeoutStopSec=8
MemoryMax=300M

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable -q zeus-gateway
systemctl restart zeus-gateway
sleep 2
systemctl is-active zeus-gateway

echo "== caddy: the zeus site (mTLS) and the hooks site (POST /hooks/anthropic only), shared key, client-supplied X-Zeus-* headers removed"
python3 "$SRC/deploy/caddy_block.py" /etc/caddy/Caddyfile
if ! caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile > "$BK/caddy-validate.log" 2>&1; then
  echo "!! caddy validate failed, restoring"; tail -5 "$BK/caddy-validate.log"; cp -a "$BK/Caddyfile" /etc/caddy/Caddyfile; exit 1
fi
systemctl reload caddy

echo "== Iris's channel plugin (installed for openclaw; her launch flags are unchanged)"
su - openclaw -c "claude plugin marketplace update zeus-local >/dev/null 2>&1; claude plugin update zeus@zeus-local 2>&1 | tail -1" || true
echo "== done ($BK)"
