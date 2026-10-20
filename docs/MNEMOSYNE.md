# Mnemosyne: Zeus's encrypted backup ("Backup" with plain names)

Design: a nightly encrypted backup to the storage box through the gateway, locked with a recovery key only the owner
holds; restore on a new phone with that key; Calypso excluded unless he opts in.

Code: `app/.../mnemosyne/` (Crypto.kt, Archive.kt, Escrow.kt, Mnemosyne.kt, MnemosyneScreens.kt), `server/gateway/backup.py`,
`server/deploy/mnemosyne-mount.sh`. Page: Themis → Mnemosyne.

## The recovery phrase and the keys
- Set-up makes 256 random bits on the phone and shows them once as 24 BIP39 English words (bundled list,
  `resources/mnemosyne/bip39-english.txt`, sha256 `2f5eed53…b24dbda`) and the same words as a QR, under FLAG_SECURE.
  "I wrote it down" asks for three random words back. The last word carries an 8-bit checksum, so a typo or a swap is
  caught before any download. The first four letters of each word are enough when typing.
- Keys (HKDF-SHA256, salt `zeus/mnemosyne/v1`): `backup key` (locks every backup), `calypso escrow` (opens Calypso's
  escrow key), and a public 8-byte `key check` in each file header so a wrong phrase reads as "wrong phrase", not "damaged".
- On the phone: the backup key sealed with Keystore AES key `zeus.mnemosyne.key` (no prompt, so the nightly job runs
  alone); the phrase wrapped (RSA-OAEP) to Keystore key `zeus.mnemosyne.phrase`, which needs his fingerprint for every
  use. "Show recovery phrase" is that fingerprint. Neither the phrase nor any key is ever sent anywhere.
- Losing the phrase means the backups can't be opened by anyone. The page says so.

## The backup
- WorkManager `mnemosyne.nightly`: every 24 h from the next 3 AM, only while charging, on unmetered network and idle.
  "Back up now" runs the same thing. One at a time.
- Each store's logical export, every sealed value opened with its own Keystore key on the phone:
  - every SharedPreferences file and every database, unless excluded (below); new stores are covered without edits;
  - sealed values are found by trial-opening with each `zeus.aegis.*` key (AES-GCM never opens under the wrong key);
    per database column, the first few long values decide whether it is sealed;
  - databases go out as schema + typed rows + user_version (FTS5 tables by rowid, their shadow tables rebuilt);
  - files are an allowlist: `files/parnassus`, `files/closet`, `files/alexandria/docs`, `nobackup/odyssey.sealed`.
- Archive: a zip of `manifest.json` (format, v1, app version, Calypso flag, what was excluded), `calypso/escrow.json`
  (only when included), `prefs/*.json`, `db/*.json`, `files/...`, `index.json` (each entry's sha256 and counts).
- File (`.zbk` v1): `ZBK1 | u32 header length | header JSON {v, id, created, salt, check, chunk} | chunks`. Each 1 MiB
  chunk is AES-256-GCM under `HKDF(backup key, salt, "file key")` (a fresh 32-byte salt per backup), nonce =
  u64 index | u32 last-flag, AAD = sha256(header). A changed byte, a swapped, dropped or appended chunk, a cut-off file
  or an edited header all fail. Built to a ciphertext-only temp file in no-backup storage, uploaded, deleted. No
  plaintext touches disk.
- Upload: `PUT /api/backup/<id>?kind=nightly|manual` over the existing mTLS client. Ids are `YYYYMMDDTHHMMSSZ-<8 hex>`.

## What never goes into a backup
- `calypso` prefs: the Anthropic key, the Hetzner token, Spotify tokens. Re-enter / sign in on the new phone.
- `lemnos` prefs and the Keystore device keys (mTLS + approval): not exportable; the new phone pairs from scratch.
- `mnemosyne` prefs (its own Keystore wraps), `vault` `pem.sent` (so the new phone publishes its own Calypso key).
- WorkManager, Google and Firebase files; Alexandria's model file; caches.
- Android-held grants: Health Connect, Google Home, notification access, calendar/contacts. The restore lists them.

## Calypso: the trade-off
Calypso items are sealed to a Keystore RSA key that needs his fingerprint for **every single use** and can't leave the
phone. Keystore keys don't migrate, so including the sealed envelopes as-is would carry nothing anyone could open.
Re-encrypting every item at backup time would need one fingerprint per item per night. So, simply:
- An escrow RSA-3072 key pair is made at set-up. Its public half stays on the phone; its private half is stored only
  encrypted under the phrase's `calypso escrow` key. The phone can add to the escrow without a prompt but can't open it.
- An item's own AES key is in hand at two moments anyway: when it is sealed in the app, and when he opens it with his
  fingerprint. With "Include Calypso" on (one fingerprint to turn it on), `Box.keyTap` wraps the key to the escrow then.
  Drops from Iris and Atlas join the first time he opens them; "Add older items" opens the rest, one fingerprint each.
- A backup carries each escrowed item's row (label sealed, content unchanged) plus its escrowed key. On a new phone the
  phrase opens the escrow key and each item key is wrapped again to the new phone's Calypso key. It still needs his
  fingerprint to open there.
- Cost: with inclusion on, anyone with the phrase and a backup can open those items. Off (the default), Calypso stays
  on this phone only; the restore says how many items stayed behind.

## Restore (new phone)
1. Pair Lemnos (`tools/pair_zeus.py`), then Themis → Mnemosyne → Restore from a backup.
2. Pick a backup, type the 24 words or scan the QR (Google's code scanner, Play services UI: no camera permission).
3. Zeus downloads it, checks the phrase against the header, reads the whole file once to check every chunk and every
   entry's sha256, then stages each store sealed under this phone's keys in `nobackup/mnemosyne-stage/`.
   Nothing live changes until then; a wrong phrase or a damaged file changes nothing.
4. "Restart Zeus": `RestoreGate` (a ContentProvider, the first thing in every Zeus process) moves the stores in before
   any store opens a file. Each store is replaced whole; nothing is merged. Mnemosyne is set up with the same phrase,
   so the next nightly backup continues.
5. Mnemosyne opens with the list: Spotify, Anthropic key + Hetzner token, Health Connect, Google Home, permissions,
   the watch, Calypso items that stayed behind.

## Lemnos side
- Routes (mTLS + device fingerprint, like the rest): `GET /api/backup` -> `{backups: [{id, kind, size, sha256,
  created}], ready, free, max_bytes, keep_recent, keep_weeks}`; `PUT /api/backup/<id>?kind=` raw body;
  `GET /api/backup/<id>` bytes. Only bodies starting with `ZBK1`, at most 1 GiB (`ZEUS_MNEMOSYNE_MAX`), never
  overwriting an id, refused (507) when the box would drop under 2 GB free. Streams to a dot-file (O_EXCL|O_NOFOLLOW),
  fsync, rename. The gateway can't read a backup: it never holds the phrase or any key.
- Retention after every upload: the 14 newest, plus the newest of each older ISO week for 4 weeks.
- Where: `/mnt/zeus-backups`, the storage box subfolder `zeus-backups` mounted a second time for zeus
  (`uid=zeus,gid=zeus,forceuid,forcegid,file_mode=0600,dir_mode=0700,nosuid,nodev,noexec,_netdev,nofail`, same
  credentials file as `/mnt/storagebox`). Why: the main mount forces uid 1000 (openclaw) and 0644, so zeus can't write
  there. The second mount gives the gateway its own 0700 tree and keeps openclaw from listing it through that path.
  Through `/mnt/storagebox/zeus-backups` the same files still show as openclaw's 0644: they're ciphertext only, so that
  costs nothing but the known limit (openclaw can become root and could delete them). Writes are refused (503) whenever
  `/mnt/zeus-backups` isn't a mount point, so a missing mount never fills the local disk.
- `deploy/mnemosyne-mount.sh` (idempotent, called by install.sh) adds the fstab line (fstab backed up first) and mounts.
  `deploy/check.py` stores a throwaway backup, reads it back, checks zeus 0600 ownership and that openclaw can't list.
