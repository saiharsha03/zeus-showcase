"""The gateway's SQLite: chat, jobs, alerts, an append-only event log the phone replays, and an audit trail.
One connection behind one lock; events are broadcast only after their transaction commits."""
import json
import sqlite3
import threading
import time
from contextlib import contextmanager

SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, room TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL,
  ts REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'text', reply_to INTEGER, job_id TEXT, client_id TEXT UNIQUE);
CREATE INDEX IF NOT EXISTS messages_room ON messages(room, id);
CREATE TABLE IF NOT EXISTS deliveries (id INTEGER PRIMARY KEY, message_id INTEGER NOT NULL, agent TEXT NOT NULL, room TEXT NOT NULL,
  text TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', ts REAL NOT NULL);
CREATE TABLE IF NOT EXISTS jobs (job_id TEXT PRIMARY KEY, agent TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
  payload TEXT NOT NULL, approved INTEGER NOT NULL, status TEXT NOT NULL, created REAL NOT NULL, updated REAL NOT NULL,
  message_id INTEGER, result TEXT);
CREATE TABLE IF NOT EXISTS alerts (id INTEGER PRIMARY KEY, ts REAL NOT NULL, source TEXT NOT NULL, severity TEXT NOT NULL,
  title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', ack INTEGER NOT NULL DEFAULT 0, dedup TEXT UNIQUE);
CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY, ts REAL NOT NULL, body TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY, ts REAL NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL);
"""


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(SCHEMA)
        self.lock = threading.RLock()
        self.listeners = []     # called with each committed event

    @contextmanager
    def tx(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            pending = []
            try:
                yield Tx(self.db, pending)
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise
        for ev in pending:
            for f in list(self.listeners):
                f(ev)

    def q(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, args)]

    def one(self, sql, args=()):
        r = self.q(sql, args)
        return r[0] if r else None

    def get(self, k, default=None):
        r = self.one("SELECT v FROM kv WHERE k=?", (k,))
        return json.loads(r["v"]) if r else default

    def put(self, k, v):
        with self.tx() as t:
            t.put(k, v)

    def events_since(self, seq, limit=500):
        return [{**json.loads(r["body"]), "seq": r["seq"]} for r in self.q("SELECT seq, body FROM events WHERE seq>? ORDER BY seq LIMIT ?", (seq, limit))]

    def audit(self, action, detail):
        with self.tx() as t:
            t.x("INSERT INTO audit(ts, action, detail) VALUES (?,?,?)", (time.time(), action, str(detail)[:2000]))


class Tx:
    def __init__(self, db, pending):
        self.db, self.pending = db, pending

    def x(self, sql, args=()):
        return self.db.execute(sql, args)

    def row(self, sql, args=()):
        r = self.db.execute(sql, args).fetchone()
        return dict(r) if r else None

    def put(self, k, v):
        self.db.execute("INSERT INTO kv(k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, json.dumps(v)))

    def emit(self, kind, data):
        body = {"type": kind, **data}
        body["seq"] = self.db.execute("INSERT INTO events(ts, body) VALUES (?,?)", (time.time(), json.dumps(body))).lastrowid
        self.pending.append(body)
        return body
