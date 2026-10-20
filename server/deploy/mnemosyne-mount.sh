#!/bin/bash
# Mnemosyne's store: the storage box subfolder zeus-backups, mounted a second time at /mnt/zeus-backups as user zeus
# (uid/gid forced, files 0600, dirs 0700), so the gateway owns its backups and openclaw can't list them through this
# mount. The share's main mount (/mnt/storagebox) forces uid 1000 (openclaw), so zeus can't write there, and through it
# the same files show as openclaw's: they are ciphertext only, under a key that never leaves the phone.
# Reuses the credentials file of the existing storage box line in /etc/fstab (never printed). Idempotent. Run as root.
set -euo pipefail
MP=/mnt/zeus-backups
LINE=$(awk '$2 == "/mnt/storagebox" && $3 == "cifs" {print; exit}' /etc/fstab)
[ -n "$LINE" ] || { echo "!! no /mnt/storagebox cifs line in /etc/fstab"; exit 1; }
SRC=$(echo "$LINE" | awk '{print $1}')
CRED=$(echo "$LINE" | awk '{print $4}' | tr ',' '\n' | grep '^credentials=' | head -1)
[ -n "$CRED" ] || { echo "!! the storage box line has no credentials= option"; exit 1; }
mountpoint -q /mnt/storagebox || mount /mnt/storagebox
install -d /mnt/storagebox/zeus-backups
install -d -o root -g root -m 755 "$MP"
if ! awk -v mp="$MP" '$2 == mp {f=1} END {exit !f}' /etc/fstab; then
  cp -a /etc/fstab "/etc/fstab.zeus-$(date -u +%Y%m%dT%H%M%SZ)"
  echo "$SRC/zeus-backups $MP cifs $CRED,uid=zeus,gid=zeus,forceuid,forcegid,file_mode=0600,dir_mode=0700,vers=3.0,nosuid,nodev,noexec,_netdev,nofail 0 0" >> /etc/fstab
  systemctl daemon-reload
fi
mountpoint -q "$MP" || mount "$MP"
mountpoint -q "$MP"
runuser -u zeus -- test -w "$MP"
echo "mnemosyne store mounted at $MP (zeus, 0700)"
