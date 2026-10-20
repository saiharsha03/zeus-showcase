# zeus-gateway: the contract between Zeus (phone) and Lemnos (VPS), Phase 3

Server: `zeus-gateway` (FastAPI, written new for Zeus in `server/`), runs as its own system user **`zeus`**, on the unix
socket `/run/zeus-gateway/gw.sock` only (dir `zeus:caddy 2750`, socket 0660). There is no TCP port. Only Caddy's group
can open the socket; the agents run as `openclaw`, which can't (checked by `deploy/check.py`).
Public face: Caddy site `zeus.example.com:443`, `client_auth require_and_verify` against
`/etc/caddy/zeus-device-ca.pem` (the Anthropic webhook lives on its own host, below). Caddy drops every client-sent `X-Zeus-*` header, then sets
`X-Zeus-Client-SHA256 = {http.request.tls.client.fingerprint}` and `X-Zeus-Proxy-Key` (from `/etc/caddy/zeus-proxy.key`,
root:caddy 0640). The gateway accepts a request only when the key matches `/etc/zeus/proxy.key` (root:zeus 0640,
`hmac.compare_digest`) and the fingerprint is in `/etc/zeus/devices.json` (root:zeus 0640). No bearer token.

What `zeus` may do, and how:
- openclaw's tmux, files and owner.db: only `sudo -n -u openclaw /opt/zeus/bin/zeus-openclaw CMD ...`, a fresh process
  per call that checks every argument (fixed roots, deny list, no `..`, no symlink escape, fixed SQL).
- root: only `sudo -n /opt/zeus/bin/zeus-root journal|unit ...` (units in `/etc/zeus/units.json`, never the protected list).
- `/etc/sudoers.d/zeus` holds exactly those two rules, with `!log_allowed` so the scan's sudo count isn't flooded.
- Iris's plugin reaches the gateway on a separate socket, `/run/zeus-channel/ch.sock` (dir `zeus:openclaw 2750`), which
  speaks only the channel protocol (hello, message frames down, replies up). Hooks drop replies in `/var/lib/zeus-spool`
  (`openclaw:zeus 2770`); long messages for Atlas go to `/var/lib/zeus-outbox` (`zeus:openclaw 2750`); service alerts
  land in `/var/spool/zeus-alerts` (`zeus:zeus 1733`) through `/usr/local/bin/claw-mirror` -> `zeus-mirror`.
- Known limit: openclaw has `(ALL) NOPASSWD: ALL` in sudoers, so an agent can become root and then reach anything.
  The zeus/openclaw split holds against an agent's normal tools; it becomes a hard boundary only once that grant goes.

## Pairing (over adb, run by the parent session)
1. Zeus (Lemnos > Make the keys) generates in Android Keystore:
   - `zeus.mtls`: EC P-256, PURPOSE_SIGN, no user auth (background fetches need it).
   - `zeus.approval`: Ed25519 (falls back to EC P-256 when the phone can't), PURPOSE_SIGN, **strong biometric for
     every use**.
   Then writes `files/zeus-pair/zeus.csr` (PEM PKCS#10, CN=owner-fold) and `files/zeus-pair/approval.json`
   `{"alg":"ed25519"|"es256","spki":"<base64 DER SubjectPublicKeyInfo>"}` in the app's internal storage.
2. `Athena/tools/pair_zeus.py` (laptop): `adb exec-out run-as dev.phonerecall cat ...` pulls both, sends them to
   the VPS, where `/opt/zeus/deploy/sign_device.py` signs the CSR with the Zeus device CA (730 days, EKU
   clientAuth), adds the cert's SHA-256 to `/etc/zeus/devices.json` and the approval key to
   `/etc/zeus/approval-keys.json`. The cert chain (leaf + CA, PEM) is pushed back to `files/zeus-pair/zeus.crt`.
3. Zeus "Finish pairing" imports the chain onto `zeus.mtls` (`KeyStore.setKeyEntry`) and deletes the pair files.
Nothing secret crosses adb: the CSR, cert and public key are public.

## Approval tokens (`zt1`)
Signed by `zeus.approval` after a biometric prompt. Unsigned job = suggestion.
```
token  = "zt1." + b64url(claims) + "." + b64url(sig)          # b64url without padding
claims = canonical JSON of {
  "v":1, "aud":"zeus-gateway", "alg":"ed25519"|"es256", "kid":"<first 16 hex of sha256(spki DER)>",
  "job_id":str, "kind":str, "executor":str, "payload_sha256":"<hex>", "rev":int,
  "nonce":"<32 hex>", "iat":int, "exp":int }
sig    = Ed25519 over the claims bytes, or SHA256withECDSA (DER) for es256
canonical JSON = json.dumps(x, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
                 ints, strings, bools, null, lists, objects only (no floats)
payload_sha256 = sha256(canonical JSON of the payload)
```
Verifier (`/opt/zeus/lib/zeus_approval.py`, CLI `zeus-approval verify`): signature by a key in
`/etc/zeus/approval-keys.json`; aud; exp - iat <= 900; now <= exp + 30; iat <= now + 60; claims bytes are
canonical; job_id, kind, executor and payload hash match what is being executed; nonce single-use (a nonce is
bound to its first job_id; the same job may re-verify, any other job is a replay).

## Routes (all JSON, all behind mTLS + device fingerprint unless noted)
Health and terminal (Lemnos, UI-only: no agent tool or CLI calls these)
- `GET /api/ping` (no device check) -> `{ok:true}`
- `GET /api/health` -> `{host, uptime, load[3], cpus, mem{total,available}, swap{total,free}, disks:[{root,path,total,used,free,ok}]}`
- `GET /api/sessions` -> `[{name, created, attached, activity, agent: "iris"|"atlas"|null, kind: "claude"|"codex"|"shell"|"other"}]`
- `POST /api/shell` -> `{session}` (new `shell-N` running `bash -l`); `DELETE /api/shell/{name}`
- `GET /api/sessions/{name}/screen?lines=2000` -> `{text}` (capture-pane, plain)
- `POST /api/sessions/{name}/input {text, submit=true}` -> send-keys -l text, then Enter. Agent sessions refuse when
  the agent process isn't running in the pane (never types into a bare shell by accident).
- `POST /api/sessions/{name}/key {key}`: Escape Tab BTab Enter Up Down Left Right C-c C-d C-l C-r C-o C-t C-j PageUp PageDown
- `GET /api/slash` -> `{claude:[{cmd, hint}], codex:[{cmd, hint}]}`
- `POST /api/term/ticket {session, ro=false}` -> `{ticket}` (30 s, single use)
- `WS /ws/term?ticket=&cols=&rows=`: first a text frame `{"t":"history","d":"<capture-pane -e, CRLF>"}`, then binary
  PTY output of `tmux attach`. Client sends text `{"t":"in","d":"..."}` / `{"t":"resize","cols":n,"rows":n}`.
Remote desktop (Lemnos > Desktop; `gateway/desktop.py`, installed by `deploy/desktop-install.sh`, checked by `deploy/desktop_check.py`)
- `GET /api/desktop` -> `{state, sub, ready, memory, memory_max, since, viewers, idle_minutes}` (systemd's view of `zeus-desktop.service`)
- `POST /api/desktop {action: start|stop}` -> the same status; start waits until Xvnc's socket answers. Through `zeus-root unit`
  (the unit is on `/etc/zeus/units.json` "control"), never directly.
- `POST /api/desktop/ticket` -> `{ticket}` (30 s, single use; 409 while the desktop is off)
- `WS /ws/desktop?ticket=`: raw RFB 3.8 both ways, in binary frames. The phone is the VNC client (`lemnos/Rfb.kt`); the gateway
  only copies bytes to `/run/zeus-desktop/vnc.sock`.
- Why TigerVNC's Xvnc and not KasmVNC/noVNC: Xvnc is the X server and the VNC server in one process, in Ubuntu's own
  archive (security updates with the rest), listens on a unix socket with TCP off (`-rfbport -1 -nolisten tcp -nolisten local`),
  and has its own idle exit (`MaxDisconnectionTime`). KasmVNC isn't packaged for noble and brings its own web server;
  noVNC/websockify would add a second hop and a WebView. The phone speaks RFB natively instead (Tight + JPEG, CopyRect, Raw).
- The session: user `desk` (no sudo, password locked), `ProtectHome=tmpfs` + its own home only, `/mnt`, `/etc/zeus`, Zeus's
  spools and `/var/log` inaccessible, `NoNewPrivileges`, private /tmp. The socket dir is `desk:zeus 2750`, socket 0660, so
  openclaw can't reach it. `MemoryHigh=900M MemoryMax=1G`, `RuntimeMaxSec=12h`, stops 15 min after the last viewer leaves
  (`ZEUS_DESKTOP_IDLE_MIN` in the unit), not enabled at boot. XFCE core only (no browser installed).
Files (roots: home=/home/agent, volume=/mnt/HC_Volume_0000000, storagebox=/mnt/storagebox, opt=/opt)
- `GET /api/files` roots + disk; `GET /api/files/{root}?path=` ls `[{name, dir, link, size, mtime}]`
- `GET /api/files/{root}/download?path=` bytes; `GET /api/files/{root}/text?path=` `{text}` (<= 1 MB)
- `PUT /api/files/{root}/raw?path=` raw body upload, never overwrites (409)
- `POST /api/files/{root}/mkdir {path}`, `/rename {path, to}`, `/move {path, to_root, to}`, `/delete {path}` (moves to
  `<root>/.zeus-trash/`; a symlink is unlinked, never its target)
- Deny list hides and refuses secrets (.ssh, .claude, *.env, *token*, *secret*, *.pem, *.key, owner.db, ...). No
  `..`, no symlink escape.
Services
- `GET /api/units` -> `[{unit, kind:"service"|"timer", active, sub, description, controllable, last, next}]`
- `GET /api/units/{unit}?lines=200` -> `{state{...}, logs, controllable}` (journal through the root helper)
- `POST /api/units/{unit} {action: restart|start|stop}` -> only units in `/etc/zeus/helper.json` and never the
  protected list, through `sudo -n /usr/local/sbin/zeus-helper` (the one sudoers drop-in).
Pantheon (agents + chat + jobs)
- `GET /api/agents` -> `[{id, name, kind, session, alive, channel: "connected"|"not-enabled"|"offline"|"tmux", busy, waiting}]`
  (iris and atlas only; Echo isn't on the VPS yet). `not-enabled`: Iris's session wasn't started with the zeus channel flag,
  so her messages wait (status queued) until it is; `offline`: enabled but the plugin isn't connected.
  Iris = Claude Code through the `zeus` channel plugin; Atlas = Codex through tmux send-keys + notify hook.
- `GET /api/chat/{room}?after=&before=&limit=` rooms: iris, atlas, group. `[{id, room, sender, text, ts, kind, reply_to, job_id}]`
- `POST /api/chat/{room} {text, client_id, via?: "athena", quoted?: [{source, text}]}` -> message. Provenance rendered
  server-side; quoted text stays fenced data.
- `POST /api/jobs {job_id, to, kind, title, payload, approval?: zt1-token}` -> `{job_id, approved: bool, status}`;
  `GET /api/jobs` -> `[{job_id, to, kind, title, status, approved, created, updated, result}]`
- `POST /api/events/ticket` -> `{ticket}`; `WS /ws/events?ticket=&since=`; `GET /api/events?since=` -> `[{seq, type, ...}]`
Cerberus
- `GET /api/alerts?since=&limit=` -> `[{id, ts, source, severity: high|medium|low, title, body, ack}]`, newest first, ids > since.
  Sources: `ssh`, `security` (Jellyfin, owner lock), `media` (Plexbot) via the existing claw-mirror calls; `scan`, `cve`,
  `backup` from their status files. Not ingested: Uptime Kuma. SSH logins come from the mirror (security-alerter follows the
  journal as root), not from polling the helper: every sudo call is journaled and counted by the security scan.
 
  `POST /api/alerts/{id}/ack`
Push
- `POST /api/push/register {token}`. The gateway sends FCM HTTP v1 data messages to it with data exactly
  `{"t":"sync"}` (high priority only for high-severity alerts). No content ever goes through Google.
- `GET /api/inbox?since=` -> `{seq, items:[{id, kind: alert|reply|job, title, body, severity, room}]}`: what the
  phone shows after a wake-up.
Iris tasks (owner.db, read-only tiers)
- `GET /api/iris/tasks?status=pending&owner=` -> `[{id, title, status, priority, due_date, owner, blocked_on_owner,
  project, notes (one line), sensitive}]`; sensitive tasks (money/immigration/health context) come back with the
  title masked unless `reveal=1`.
- `GET /api/iris/tables` and `GET /api/iris/table/{name}?limit=` for the show-directly tables only, row cap 200.
- `POST /api/iris/tasks/{id}/done {approval}` and `/note {text, approval}`: need a zt1 token (kind
  `iris.task_done` / `iris.task_note`, executor `zeus-gateway`, payload `{"task_id": id}` /
  `{"task_id": id, "text": ...}`).

Calypso drops (write-only; the gateway holds ciphertext only)
- Agents (openclaw) seal with `zeus-calypso-drop --from iris|atlas --label "..." [--kind secret|note|file --file F] < value`.
  It encrypts to Calypso's public key (`/var/lib/zeus-outbox/calypso.pub.pem`: AES-256-GCM item key, RSA-OAEP SHA-256 /
  MGF1 SHA-1 wrap, AAD `calypso/v1\n{id}\n{kind}\n{label}\n{from}`) and drops the envelope in `/var/spool/zeus-calypso`
  (1733). It prints `{"stored": true, "id": ...}` only. Keep no plaintext copy afterwards.
- `PUT /api/calypso/key {pem}`: the phone publishes the public key. `GET /api/calypso/drops` -> envelopes;
  `POST /api/calypso/drops/ack {ids}` deletes them once the phone has them. Only the phone's Keystore key (fingerprint per
  use) opens an envelope.
Prometheus (app-change requests; manual only, built on the laptop, never on this box). This is the fallback: the default is the cloud Prometheus on GitHub (docs/PROMETHEUS.md), which never touches Lemnos.
- `POST /api/prometheus/requests {id: "p"+12 hex, problem, approval}`: needs a zt1 token (kind `prometheus.request`,
  executor `zeus-gateway`, job_id = id, payload `{"id", "problem"}`), else 403. Queued in `/var/lib/zeus-prometheus/<id>/`
  (zeus:zeus 0750). `GET /api/prometheus/requests` -> `[{id, problem, status: queued|building|ready|failed|cancelled,
  created, updated, error, build?: {summary, diff{files,insertions,deletions}, tests{passed,failed,line}, version_name,
  version_code, commit, apk_sha256, apk_bytes}}]`. `POST .../{id}/cancel` (queued only). `GET .../{id}/apk`: bytes, only
  when ready (tests passed). A change to ready or failed wakes the phone ("Update ready").
- Builds: `Athena/tools/prometheus.py list|start|publish|fail` on the laptop, by hand, over root SSH to
  `/opt/zeus/bin/zeus-prometheus` (refuses non-root). The VPS never holds Zeus's source or signing key, so it can't make
  an APK the phone accepts: the phone checks the APK's signing certificate against the installed app before it shows
  Install, then hands it to Android's installer, which asks the owner to confirm. No agent can install.

Mnemosyne (encrypted phone backups, docs/MNEMOSYNE.md; ciphertext only, the key never leaves the phone)
- `GET /api/backup` -> `{backups: [{id, kind, size, sha256, created}], ready, free, max_bytes, keep_recent, keep_weeks}`
- `PUT /api/backup/{id}?kind=nightly|manual` raw body starting `ZBK1`, <= 1 GiB, never overwrites (409); 503 when
  `/mnt/zeus-backups` (storage box subfolder mounted for zeus, 0700) isn't mounted. Keeps 14 newest + 4 weekly.
- `GET /api/backup/{id}` bytes.

Metron (plan usage)
- `GET /api/usage` -> `{agents: [{id, name, provider: claude|codex, model, windows: [{label: "5h"|"7d"|"7d Opus"|..., used_pct,
  resets_at (epoch s)}], updated, cost_usd?, plan?, limit_reached?, note?}], now}`. A window whose reset passed since the
  last reading has `used_pct: null`.
- Iris: her `statusLine` is `python3 -I /opt/zeus/hooks/usage_statusline.py`, which keeps only `rate_limits`, the model and
  the session cost (never prompt text) in `/var/lib/zeus-usage/iris.json` (dir `openclaw:zeus 2750`, file 0640, written
  atomically, only from tmux `claude-auto`), then execs `/home/agent/bin/iris-statusline` with the same stdin.
- Atlas: `zeus-openclaw codex-usage` reads the newest `~/.codex/sessions/*/*/*/rollout-*.jsonl` tail and prints only the
  rate limits (`primary`/`secondary` {used_percent, window_minutes, resets_at}, plan) and the model.
- The phone (Metron in Themis) adds Athena's own spend, alerts once per window at 80% (and the budget at 80%/95%), and
  holds worker jobs while a worker is above 90% of a window.

Moneta (Iris logs every transaction to Moneta and has free control of it)
- `GET /api/iris/ledger?since=&limit=` -> `{rows: [{id, date, cents, currency, category, merchant, kind, last4, status}],
  next, more, subscriptions: [{id, name, cents, currency, billing_cycle, next_billing_date, status}]}`: owner.db
  `transactions` after row `since`, oldest first (limit 1000). Only the last four digits of `card` leave; balance rows
  and 7+ digit runs in descriptions never do. The phone reads from its cursor, and the whole log once a day.
- Iris's ops: `zeus-moneta add|edit|recategorize|merge|delete|budget|sub-add|sub-remove|chart|chart-remove|schema`
  (JSON in/out, `/opt/zeus/docs/IRIS-MONETA.md`) drops a checked op into `/var/spool/zeus-moneta` (1733). The gateway
  keeps only files owned by openclaw, checks them again (`lib/moneta_ops.py`), stamps `from: "iris"` and wakes the
  phone. `GET /api/moneta/ops` -> envelopes; `POST /api/moneta/ops/ack {ids}`. The phone applies them directly (no
  card), logs each in Moneta's change log and lets the owner undo any one. No op moves money.

Argo (trips)
- Iris's plugin tool `trip_info` sends one channel frame up: `{"t":"trip.info","req":N,"info":{kind, place, ...}}`
  (`server/docs/IRIS-ARGO.md`). The gateway checks it (`lib/argo_info.py`: fixed fields, no confirmation-like words or
  7+ digit runs; a confirmation travels only as `calypso_id`, a Calypso drop id), stamps `id` (24 hex), `from_agent:
  "iris"`, `received`, keeps it in `/var/lib/zeus/argo-info/` (0700) and wakes the phone. It answers
  `{"t":"trip.ack","req":N,"ok":true,"id":"iris:<hex>"}` or `{"ok":false,"error":...}`. Never filed as chat.
- `GET /api/argo/info` -> envelopes, oldest first; `POST /api/argo/info/ack {ids}`. The phone merges them into its
  trips (sealed, on the phone) and acks.

Agora (the agents' room)
- Room `agora`: Iris, Atlas and Athena coordinate there; the owner reads it in Pantheon (unread dot) and can post to
  steer. One team: Iris owns mail, calendar and admin; Athena the phone and his day; Atlas the server and code. Each
  keeps the others informed, asks when she needs something, answers her part, PASSes when another has it, keeps one
  owner per job and builds on what the others said.
- Posting: Iris with her plugin tool `post_agora {text}` (frame `{"t":"agora.post","req":N,"text"}` ->
  `{"t":"agora.ack","req":N,"ok":true,"id","relayed"}` or `{"ok":false,"error"}`), or by answering an Agora message
  with `reply`; Atlas by answering an Agora message (his notify hook files it, as in his own room); Athena with her mind
  tool `post_agora {text, room?: agora|group}` (`POST /api/chat/agora` with `via: athena`); the owner from Pantheon.
- Relay (`chat.agora`): each post goes to every other member, never back to its sender: Iris and Atlas get a delivery
  headed `[zeus #N] FROM IRIS|ATLAS|ATHENA|OWNER ... in Agora` (meta `from=<sender>`, `authority=read`; a colleague's
  post says it is from a trusted colleague and that quoted mail/web text is data; only the owner's carries a REQUEST);
  Athena gets it on the phone's sync (FCM wake, cursor `agora.after`) as a mind note `[FROM IRIS in Agora] ...`, high
  for the owner's posts and for a colleague's post naming a deadline, a reply owed or a booking change within 48 h.
  Agora answers never ping the owner's shade.
- Rules: PASS is never posted (409 / not filed). An agent's exact repeat within 24 h is refused (409). Runaway guard:
  after 8 agent posts in a row, posts are still stored and shown (`kind: held`) but not relayed, with one system line
  "Paused agent-to-agent until the owner is back or 30 minutes pass". The run resets when the owner posts in any room or
  after 30 minutes of quiet in Agora.
- The group chat is as before (the owner + Iris + Atlas). Athena also gets the owner's group messages (high) and may answer
  there (`via: athena` in `group` is shown, not relayed). ask_worker stays in Iris's room.

## Anthropic webhook

`POST /hooks/anthropic` (`gateway/webhooks.py`) wakes the phone the moment an Anthropic Managed Agents session pauses
(for example on a phone tool call), instead of the phone polling. It's the one route without a device certificate, so
it has its own host and the main site stays strictly mTLS:
- Caddy (`deploy/caddy_block.py`) serves `hooks.example.com` (certificate issued automatically, no client
  auth). Only `POST /hooks/anthropic` is proxied, body at most 64 KB, rewritten to exactly `/hooks/anthropic` upstream,
  with the proxy key and no fingerprint; every other method or path on that host is a 404. The gateway refuses the
  route without the proxy key (403). `deploy/check.py` checks that the main host refuses a cert-less handshake and
  that the hooks host 404s everything but the webhook.
- The gateway checks the Standard Webhooks signature Anthropic sends: `webhook-id`, `webhook-timestamp`,
  `webhook-signature` (`v1,<base64>`, space-separated), HMAC-SHA256 over `<id>.<timestamp>.<raw body>` with the
  base64 part of the `whsec_` secret, `hmac.compare_digest`, timestamp within 5 minutes. Bad or missing signature or a
  stale timestamp -> 401; no secret file -> 503.
- It reads only `data.type` and `data.id` (and `data.stop_reason` if one is ever present). Nothing is stored or logged.
  On `session.status_idled` it sends the usual content-free FCM `{"t":"sync"}` (high priority), at most once per
  session per 10 s, and answers 200; any other event -> 204. The webhook doesn't say why the session idled, so the
  phone, once awake, checks the session itself for a pending phone tool call.

Two manual steps (the owner):
1. Claude Console -> Managed Agents -> Webhooks: add `https://hooks.example.com/hooks/anthropic`,
   subscribed to the session status events (`session.status_idled` is the one used). The signing secret is shown once.
2. The signing secret is already in `/etc/zeus/anthropic-webhook.secret` (root:zeus 0640). It is never printed,
   pasted into chat logs or committed. Without it the route answers 503.
