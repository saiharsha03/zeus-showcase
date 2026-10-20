"""Pantheon's rooms: "iris", "atlas", "group" and "agora".

The owner's message is stored first, then one delivery per worker:
- Iris: a frame on the zeus channel (her Claude Code session's `zeus` plugin is connected to CHANNEL_SOCK).
  Until her session loads the channel the delivery waits as "queued" and goes out when the plugin says hello.
- Atlas: typed into his Codex session as one tagged line (long text goes to a file he is told to read).
Replies come back by message id: the plugin's reply tool, Iris's Stop hook (fallback), Atlas's notify hook.
A reply is filed in the room its message came from; text from a worker is never treated as the owner's.

Agora is where the agents meet: Iris, Atlas and Athena (on the phone) coordinate there; the owner reads it and can
steer. A post from one member is relayed to the others (Athena fetches hers on the phone's sync), never echoed to its
sender. PASS is never posted. An agent's exact repeat within 24 h is dropped. Runaway guard: after 8 agent posts in a
row (reset when the owner posts in any room, or after 30 minutes of quiet in Agora) posts are still shown but no longer
relayed, and one line says so.
"""
import asyncio
import json
import re
import time
from pathlib import Path

from . import calls, config
from . import envelope as env
from .store import Store

ROOMS = ("iris", "atlas", "group", "agora")
AGORA_AGENTS = ("iris", "atlas", "athena")
GUARD = 8               # agent posts in a row in Agora before relaying pauses
GUARD_QUIET_S = 1800    # this much quiet in Agora resets the run
DUPE_S = 86_400
PAUSED = "Paused agent-to-agent until the owner is back or 30 minutes pass."


class Refused(ValueError):
    pass
OPEN = ("queued", "sent", "uncertain")
TAG = re.compile(r"\[zeus #(\d+)\]")


class Chat:
    def __init__(self, store: Store, notify):
        self.s = store
        self.notify = notify            # (kind, title, body, room, severity) -> None
        self.channel: asyncio.StreamWriter | None = None
        self._procs: dict[str, list[str]] = {}   # session -> argv lines, refreshed by status()
        self.trip_info = None           # info -> id, raises ValueError; set by the app (Argo)
        self.wake = lambda: None        # wakes the phone (FCM "sync") so Athena fetches Agora; set by the app
        self._kicks: set = set()

    # ---- status ----
    async def sessions(self) -> list[dict]:
        try:
            rows = await calls.openclaw_json("sessions")
        except calls.HelperError:
            rows = []
        self._procs = {r["name"]: r.get("procs", []) for r in rows}
        return rows

    def _running(self, agent: str) -> bool:
        a = config.AGENTS[agent]
        return any(re.search(rf"(^|/){a['proc']}(\s|$)", p) for p in self._procs.get(a["session"], []))

    def channel_state(self, agent: str) -> str:
        if config.AGENTS[agent]["via"] != "channel":
            return "tmux"
        if self.channel is not None:
            return "connected"
        enabled = any(config.CHANNEL_FLAG in p for p in self._procs.get(config.AGENTS[agent]["session"], []))
        return "offline" if enabled else "not-enabled"

    async def status(self) -> list[dict]:
        await self.sessions()
        waiting = {r["agent"]: r["room"] for r in self.s.q(f"SELECT agent, room FROM deliveries WHERE status IN {OPEN}")}
        return [{"id": k, "name": a["name"], "kind": a["kind"], "session": a["session"], "alive": a["session"] in self._procs,
                 "channel": self.channel_state(k), "busy": k in waiting, "waiting": waiting.get(k)} for k, a in config.AGENTS.items()]

    # ---- storing ----
    def _add(self, t, room, sender, text, kind="text", reply_to=None, job_id=None, client_id=None) -> dict:
        mid = t.x("INSERT INTO messages(room, sender, text, ts, kind, reply_to, job_id, client_id) VALUES (?,?,?,?,?,?,?,?)",
                  (room, sender, text, time.time(), kind, reply_to, job_id, client_id)).lastrowid
        m = t.row("SELECT * FROM messages WHERE id=?", (mid,))
        t.emit("message", m)
        return m

    def history(self, room: str, before: int | None, limit: int, after: int | None) -> list[dict]:
        if after is not None:
            return self.s.q("SELECT * FROM messages WHERE room=? AND id>? ORDER BY id LIMIT ?", (room, after, limit))
        if before:
            return self.s.q("SELECT * FROM messages WHERE room=? AND id<? ORDER BY id DESC LIMIT ?", (room, before, limit))[::-1]
        return self.s.q("SELECT * FROM messages WHERE room=? ORDER BY id DESC LIMIT ?", (room, limit))[::-1]

    # ---- the owner sends ----
    async def send(self, room: str, text: str, client_id: str | None, via=None, channel=None, quoted=None) -> dict:
        if client_id and (prior := self.s.one("SELECT * FROM messages WHERE client_id=?", (client_id,))):
            return prior    # a retry never delivers twice
        if room == "agora":
            m, ids = self.agora("athena" if via == "athena" else "owner", text, client_id=client_id, channel=channel)
            for d in ids:
                await self.deliver(d)
            return m
        if room == "group" and via == "athena":
            # Athena answering the owner in the group: shown to him, not relayed (the agents coordinate in Agora).
            with self.s.tx() as t:
                return self._add(t, room, "athena", text, client_id=client_id)
        targets = list(config.AGENTS) if room == "group" else [room]
        with self.s.tx() as t:
            m = self._add(t, room, "athena" if via == "athena" else "owner", text, client_id=client_id)
            ids = [t.x("INSERT INTO deliveries(message_id, agent, room, text, status, ts) VALUES (?,?,?,?,?,?)",
                       (m["id"], a, room, env.from_owner(m["id"], text, via, channel, quoted, room), "queued", time.time())).lastrowid
                   for a in targets]
        for d in ids:
            await self.deliver(d)
        return m

    async def submit_job(self, job_id, agent, kind, title, payload, approved, token, exp, by=None) -> dict:
        prior = self.s.one("SELECT * FROM jobs WHERE job_id=?", (job_id,))
        if prior:
            if json.loads(prior["payload"]) != payload or prior["agent"] != agent or prior["kind"] != kind:
                raise ValueError("that job id is already used for a different job")
            return self.job_row(prior)
        now = time.time()
        with self.s.tx() as t:
            sender = "owner" if by == "owner" and approved else "athena"
            m = self._add(t, agent, sender, f"{'Request' if sender == 'owner' else 'Job'}: {title}" + (" (approved by you)" if approved else " (a suggestion)"), kind="job", job_id=job_id)
            t.x("INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?)", (job_id, agent, kind, title, json.dumps(payload), int(approved), "queued", now, now, m["id"], None))
            d = t.x("INSERT INTO deliveries(message_id, agent, room, text, status, ts) VALUES (?,?,?,?,?,?)",
                    (m["id"], agent, agent, env.job(m["id"], job_id, kind, title, payload, approved, token, exp, by=sender), "queued", now)).lastrowid
            t.emit("job", {"job_id": job_id, "status": "queued"})
        await self.deliver(d)
        return self.job_row(self.s.one("SELECT * FROM jobs WHERE job_id=?", (job_id,)))

    @staticmethod
    def job_row(j: dict) -> dict:
        return {"job_id": j["job_id"], "to": j["agent"], "kind": j["kind"], "title": j["title"], "status": j["status"],
                "approved": bool(j["approved"]), "created": j["created"], "updated": j["updated"], "result": j["result"]}

    def jobs(self, limit: int) -> list[dict]:
        return [self.job_row(j) for j in self.s.q("SELECT * FROM jobs ORDER BY created DESC LIMIT ?", (limit,))]

    # ---- delivering ----
    def _mark(self, did: int, status: str, detail: str = "") -> None:
        with self.s.tx() as t:
            d = t.row("SELECT * FROM deliveries WHERE id=?", (did,))
            t.x("UPDATE deliveries SET status=?, detail=? WHERE id=?", (status, detail[:300], did))
            t.x("UPDATE jobs SET status=?, updated=? WHERE message_id=? AND status IN ('queued','sent')",
                ("sent" if status in ("sent", "uncertain") else status, time.time(), d["message_id"]))
            t.emit("delivery", {"id": did, "agent": d["agent"], "room": d["room"], "status": status})
            if status == "failed":
                self._add(t, d["room"], "system", f"Couldn't reach {config.AGENTS[d['agent']]['name']}: {detail}", kind="notice")

    async def deliver(self, did: int) -> None:
        d = self.s.one("SELECT * FROM deliveries WHERE id=?", (did,))
        if d is None or d["status"] != "queued":
            return
        a = config.AGENTS[d["agent"]]
        if a["via"] == "channel":
            if self.channel is None:
                return      # stays queued until the plugin connects
            try:
                sender, authority = self.stamp(d["message_id"])
                self.channel.write((json.dumps({"t": "msg", "id": d["message_id"], "room": d["room"], "text": d["text"],
                                                "from": sender, "authority": authority}) + "\n").encode())
                await self.channel.drain()
                self._mark(did, "sent")
            except (ConnectionError, RuntimeError) as e:
                self.channel = None
                self._mark(did, "queued", f"channel dropped: {e!r}")
            return
        await self.sessions()
        if not self._running(d["agent"]):
            return self._mark(did, "failed", f"{a['proc']} isn't running in {a['session']}; nothing was typed")
        text = d["text"]
        if not env.one_line(text):
            f = config.OUTBOX / f"{d['message_id']}-{d['agent']}.md"
            f.write_text(text, encoding="utf-8")
            f.chmod(0o640)
            text = f"{env.tag(d['message_id'])} A message from Zeus is in {f} (it says who it is from). Read it and answer here."
        try:
            await calls.openclaw("type", a["session"], "submit", stdin=text.encode())
            self._mark(did, "sent")
        except calls.HelperError as e:
            self._mark(did, "failed", e.detail)

    def stamp(self, message_id: int) -> tuple[str, str]:
        """Who a delivery is from and what it may authorise, from the gateway's own rows: the message's sender, and
        "signed" only for a job whose approval token the verifier accepted. Never from the text."""
        m = self.s.one("SELECT sender, kind FROM messages WHERE id=?", (message_id,)) or {}
        signed = m.get("kind") == "job" and bool((self.s.one("SELECT approved FROM jobs WHERE message_id=?", (message_id,)) or {}).get("approved"))
        return str(m.get("sender") or "unknown"), "signed" if signed else "read"

    async def flush_channel(self) -> None:
        via_channel = [k for k, a in config.AGENTS.items() if a["via"] == "channel"]
        for r in self.s.q(f"SELECT id FROM deliveries WHERE status='queued' AND agent IN ({','.join('?' * len(via_channel))}) ORDER BY id", tuple(via_channel)):
            await self.deliver(r["id"])

    # ---- workers answer ----
    def reply(self, agent: str, message_id: int, texts: list[str], source: str) -> bool:
        """File a worker's answer to message `message_id`. False when nothing was waiting for it."""
        texts = [t.strip() for t in texts if t and t.strip()]
        with self.s.tx() as t:
            d = t.row(f"SELECT * FROM deliveries WHERE message_id=? AND agent=? AND status IN {OPEN} ORDER BY id DESC LIMIT 1", (message_id, agent))
            if d is None:
                return False
            t.x("UPDATE deliveries SET status='answered', detail=? WHERE id=?", (source, d["id"]))
            joined = "\n\n".join(texts) or "(no text reply)"
            agora = d["room"] == "agora"
            if not agora:
                passed = joined.upper().rstrip(".") == "PASS"
                m = self._add(t, d["room"], agent, "PASS" if passed else joined[:20000], kind="pass" if passed else "text", reply_to=message_id)
                job = t.row("SELECT * FROM jobs WHERE message_id=?", (message_id,))
                if job:
                    t.x("UPDATE jobs SET status='answered', result=?, updated=? WHERE job_id=?", (joined[:4000], time.time(), job["job_id"]))
                    t.emit("job", {"job_id": job["job_id"], "status": "answered"})
        if agora:
            # An answer in Agora is a post there: relayed to the other members, never a ping to the owner.
            try:
                if texts:
                    self._kick(self.agora(agent, joined, reply_to=message_id)[1])
            except Refused:
                pass
            return True
        if not passed:
            self.notify("reply", config.AGENTS[agent]["name"], joined[:300], d["room"], "medium")
        return True

    # ---- Agora: the agents' room ----
    def _agora_run(self, t, now: float) -> int:
        """Agent posts in a row in Agora: since the owner last posted in any room, and with no 30-minute quiet between them."""
        since = (t.row("SELECT MAX(ts) AS ts FROM messages WHERE sender='owner'") or {}).get("ts") or 0
        rows = t.x(f"SELECT ts FROM messages WHERE room='agora' AND sender IN {AGORA_AGENTS} AND kind IN ('text','held') AND ts>? "
                   "ORDER BY id DESC LIMIT ?", (since, GUARD * 4)).fetchall()
        n, prev = 0, now
        for r in rows:
            if prev - r["ts"] > GUARD_QUIET_S:
                break
            n, prev = n + 1, r["ts"]
        return n

    def agora(self, sender: str, text: str, client_id=None, reply_to=None, channel=None) -> tuple[dict, list[int]]:
        """Posts in Agora as `sender` (owner or one of the agents) and queues a delivery for each other channel/tmux
        member. Returns the message and the delivery ids. Raises Refused for PASS and an agent's exact repeat."""
        text = text.strip()[:20000]
        if not text or text.upper().rstrip(".") == "PASS":
            raise Refused("PASS isn't posted in Agora")
        now = time.time()
        ids = []
        with self.s.tx() as t:
            if sender != "owner" and t.row("SELECT id FROM messages WHERE room='agora' AND sender=? AND text=? AND ts>?", (sender, text, now - DUPE_S)):
                raise Refused("the same post is already in Agora from the last 24 h")
            run = 0 if sender == "owner" else self._agora_run(t, now)
            held = run >= GUARD
            m = self._add(t, "agora", sender, text, kind="held" if held else "text", reply_to=reply_to, client_id=client_id)
            if held:
                if run == GUARD:
                    self._add(t, "agora", "system", PAUSED, kind="notice")
            else:
                for a in config.AGENTS:
                    if a != sender:
                        ids.append(t.x("INSERT INTO deliveries(message_id, agent, room, text, status, ts) VALUES (?,?,?,?,?,?)",
                                       (m["id"], a, "agora", env.agora(m["id"], sender, text, channel), "queued", now)).lastrowid)
        if not held and sender != "athena":
            self.wake()     # Athena reads Agora on the phone's sync
        return m, ids

    def _kick(self, ids: list[int]) -> None:
        """Delivers from sync code inside the gateway's loop; outside one (tests) the deliveries stay queued."""
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        for d in ids:
            task = loop.create_task(self.deliver(d))
            self._kicks.add(task)
            task.add_done_callback(self._kicks.discard)

    def read_spool(self) -> None:
        """Hook drops: {"agent": "iris"|"atlas", "message_id": N | "prompt": "...[zeus #N]...", "texts": [...]}"""
        for f in sorted(config.SPOOL.glob("*.json")):
            try:
                ev = json.loads(f.read_text(encoding="utf-8"))
                agent = ev.get("agent")
                ids = [int(ev["message_id"])] if ev.get("message_id") else [int(x) for x in TAG.findall(str(ev.get("prompt", "")))]
                if agent in config.AGENTS:
                    for mid in ids[-3:]:
                        self.reply(agent, mid, [str(x) for x in ev.get("texts", [])][:20], "hook")
            except (OSError, ValueError, KeyError) as e:
                self.s.audit("spool.bad", f"{f.name}: {e!r}")
            f.unlink(missing_ok=True)

    def expire(self, max_age_s: int = 3600) -> None:
        with self.s.tx() as t:
            for r in t.x(f"SELECT id, agent, room FROM deliveries WHERE status IN ('sent','uncertain') AND ts<?", (time.time() - max_age_s,)).fetchall():
                t.x("UPDATE deliveries SET status='stale' WHERE id=?", (r["id"],))
                t.emit("delivery", {"id": r["id"], "agent": r["agent"], "room": r["room"], "status": "stale"})

    # ---- the zeus channel (Iris's plugin) ----
    async def serve_channel(self) -> None:
        path = Path(config.CHANNEL_SOCK)
        path.unlink(missing_ok=True)
        server = await asyncio.start_unix_server(self._on_channel, path=str(path))
        path.chmod(0o660)   # dir is zeus:openclaw 2750, so the socket's group is openclaw
        async with server:
            await server.serve_forever()

    async def _on_channel(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            hello = json.loads((await asyncio.wait_for(reader.readline(), 10)).decode())
            if hello.get("t") != "hello" or hello.get("agent") != "iris":
                writer.close()
                return
            if self.channel is not None:
                self.channel.close()     # a restarted session replaces the old connection
            self.channel = writer
            self.s.audit("channel.hello", f"iris pid={hello.get('pid')}")
            with self.s.tx() as t:
                t.emit("agents", {})
            await self.flush_channel()
            while line := await reader.readline():
                if len(line) > 200_000:
                    continue
                f = json.loads(line.decode())
                if f.get("t") == "reply" and isinstance(f.get("id"), int):
                    ok = self.reply("iris", f["id"], [str(f.get("text", ""))], "channel")
                    writer.write((json.dumps({"t": "ack", "id": f["id"], "ok": ok}) + "\n").encode())
                    await writer.drain()
                elif f.get("t") == "trip.info" and isinstance(f.get("req"), int) and self.trip_info is not None:
                    # Argo: a booking or plan for the owner's trips. Checked, then queued for the phone; never shown as chat.
                    try:
                        ack = {"t": "trip.ack", "req": f["req"], "ok": True, "id": self.trip_info(f.get("info"))}
                    except ValueError as e:
                        ack = {"t": "trip.ack", "req": f["req"], "ok": False, "error": str(e)[:300]}
                    writer.write((json.dumps(ack) + "\n").encode())
                    await writer.drain()
                elif f.get("t") == "agora.post" and isinstance(f.get("req"), int):
                    # Iris posting in Agora on her own (post_agora): relayed to Atlas and Athena.
                    ids = []
                    try:
                        m, ids = self.agora("iris", str(f.get("text", "")))
                        ack = {"t": "agora.ack", "req": f["req"], "ok": True, "id": m["id"], "relayed": m["kind"] == "text"}
                    except Refused as e:
                        ack = {"t": "agora.ack", "req": f["req"], "ok": False, "error": str(e)[:300]}
                    writer.write((json.dumps(ack) + "\n").encode())
                    await writer.drain()
                    for d in ids:
                        await self.deliver(d)
        except (asyncio.TimeoutError, ValueError, ConnectionError):
            pass
        finally:
            if self.channel is writer:
                self.channel = None
                with self.s.tx() as t:
                    t.emit("agents", {})
            writer.close()
