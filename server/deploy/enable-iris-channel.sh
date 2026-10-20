#!/bin/bash
# PREPARED, NOT RUN. The owner's call: adds the zeus channel to Iris's launch command, next to Telegram's.
#   bash /opt/zeus/deploy/enable-iris-channel.sh        (as root)
# Patches the CMD= line in ~/bin/restart-iris.sh and ~/bin/claude-watchdog.sh (backed up first). Takes effect at her
# next restart (Plexbot /restart, or the watchdog). Telegram's --channels flag is kept exactly as it is.
# Note: a development channel may ask for a one-time confirmation on first start; check her screen in Lemnos then.
set -euo pipefail
FLAG="--dangerously-load-development-channels plugin:zeus@zeus-local"
BK=/opt/zeus-backups/iris-channel-$(date -u +%Y%m%dT%H%M%SZ)
install -d -m 700 "$BK"
for f in /home/agent/bin/restart-iris.sh /home/agent/bin/claude-watchdog.sh; do
  [ -f "$f" ] || { echo "skip (missing): $f"; continue; }
  cp -a "$f" "$BK/"
  if grep -q "plugin:zeus@zeus-local" "$f"; then echo "already on: $f"; continue; fi
  sed -i "/claude --channels plugin:telegram@claude-plugins-official/ s|--channels plugin:telegram@claude-plugins-official|& $FLAG|" "$f"
  grep -q "plugin:zeus@zeus-local" "$f" && echo "patched: $f" || echo "!! no launch line found in $f (left unchanged)"
done
echo "backups in $BK"
