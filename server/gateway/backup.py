"""Mnemosyne: the phone's encrypted backups (docs/MNEMOSYNE.md). The gateway only ever holds ciphertext.

The phone encrypts every backup under a key derived from the owner's recovery phrase, which never leaves the phone. Here a
backup is an opaque file that starts with the format magic. Layout under the backup root (the storage box subfolder
mounted for user zeus at /mnt/zeus-backups, 0700):  <id>.zbk  the ciphertext,  <id>.json  {id, kind, size, sha256,
created}. Uploads stream to a dot-file created O_EXCL|O_NOFOLLOW and are renamed into place only when complete, so
a half-written backup is never listed. An id is never overwritten. After every upload the retention runs: the 14
newest backups stay, and of the older ones the newest in each ISO week, for 4 weeks.
"""
import asyncio
import datetime as dt
import hashlib
import json
import os
import re
import shutil
from pathlib import Path

ID = re.compile(r"^\d{8}T\d{6}Z-[0-9a-f]{8}$")
KINDS = ("nightly", "manual")
MAGIC = b"ZBK1"
KEEP_RECENT = 14
KEEP_WEEKS = 4
RESERVE = 2 << 30          # never fill the box past its last 2 GB


class Rejected(Exception):
    def __init__(self, code: int, msg: str):
        super().__init__(msg)
        self.code = code


def retention(rows: list[dict], recent: int = KEEP_RECENT, weeks: int = KEEP_WEEKS) -> set[str]:
    """The ids to keep: the `recent` newest, plus the newest of each older ISO week, for `weeks` weeks."""
    rows = sorted(rows, key=lambda r: (r["created"], r["id"]), reverse=True)
    keep = {r["id"] for r in rows[:recent]}
    seen: list[tuple[int, int]] = []
    for r in rows[recent:]:
        wk = dt.datetime.fromtimestamp(r["created"], dt.timezone.utc).isocalendar()[:2]
        if wk in seen:
            continue
        if len(seen) >= weeks:
            break
        seen.append(wk)
        keep.add(r["id"])
    return keep


class Backups:
    def __init__(self, root: Path, max_bytes: int, require_mount: bool):
        self.root = Path(root)
        self.max_bytes = max_bytes
        self.require_mount = require_mount

    def ready(self) -> bool:
        """Writes go to the storage box only. When the mount is missing, refuse instead of filling the local disk."""
        r = self.root
        return r.is_dir() and not r.is_symlink() and (os.path.ismount(r) or not self.require_mount)

    def _id(self, bid: str) -> str:
        if not ID.fullmatch(bid or ""):
            raise Rejected(400, "bad backup id")
        return bid

    def _meta(self, bid: str) -> dict | None:
        p = self.root / f"{bid}.json"
        try:
            if p.is_symlink():
                return None
            m = json.loads(p.read_text(encoding="utf-8"))
            return m if m.get("id") == bid and (self.root / f"{bid}.zbk").is_file() else None
        except (OSError, ValueError):
            return None

    def all(self) -> list[dict]:
        if not self.root.is_dir():
            return []
        out = [m for p in self.root.iterdir() if p.suffix == ".json" and ID.fullmatch(p.stem) and (m := self._meta(p.stem))]
        out.sort(key=lambda m: (m["created"], m["id"]), reverse=True)
        return out

    def status(self) -> dict:
        free = None
        if self.root.is_dir():
            free = shutil.disk_usage(self.root).free
        return {"ready": self.ready(), "free": free, "max_bytes": self.max_bytes, "keep_recent": KEEP_RECENT, "keep_weeks": KEEP_WEEKS}

    def path(self, bid: str) -> Path:
        bid = self._id(bid)
        p = self.root / f"{bid}.zbk"
        if self._meta(bid) is None or p.is_symlink() or not p.is_file():
            raise Rejected(404, "no such backup")
        return p

    async def put(self, bid: str, kind: str, chunks, length: int | None, now: float) -> dict:
        """Streams the upload to the box. `chunks` is an async iterator of bytes (the request body)."""
        bid = self._id(bid)
        if kind not in KINDS:
            raise Rejected(400, "bad kind")
        if not self.ready():
            raise Rejected(503, "the backup store isn't mounted")
        if length is not None and length > self.max_bytes:
            raise Rejected(413, "backup too large")
        final, meta = self.root / f"{bid}.zbk", self.root / f"{bid}.json"
        if final.exists() or meta.exists() or final.is_symlink():
            raise Rejected(409, "that backup exists")
        if shutil.disk_usage(self.root).free - (length or 0) < RESERVE:
            raise Rejected(507, "the storage box is nearly full")
        tmp = self.root / f".{bid}.part"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        h, n, head = hashlib.sha256(), 0, b""
        try:
            with os.fdopen(fd, "wb") as f:
                async for c in chunks:
                    if not c:
                        continue
                    n += len(c)
                    if n > self.max_bytes:
                        raise Rejected(413, "backup too large")
                    if len(head) < len(MAGIC):
                        head += c[: len(MAGIC) - len(head)]
                        if not MAGIC.startswith(head[: len(MAGIC)]):
                            raise Rejected(400, "not a Mnemosyne backup")
                    h.update(c)
                    await asyncio.to_thread(f.write, c)   # the box is a network mount; keep the loop free
                await asyncio.to_thread(lambda: (f.flush(), os.fsync(f.fileno())))
            if head != MAGIC or (length is not None and n != length):
                raise Rejected(400, "incomplete upload")
            os.replace(tmp, final)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        m = {"id": bid, "kind": kind, "size": n, "sha256": h.hexdigest(), "created": int(now)}
        mtmp = self.root / f".{bid}.json.part"
        fd = os.open(mtmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(m, f)
        os.replace(mtmp, meta)
        m["pruned"] = self.prune(now)
        return m

    def prune(self, now: float | None = None) -> list[str]:
        for p in self.root.glob(".*.part"):      # an upload that died half way
            try:
                if p.lstat().st_mtime < (now or p.lstat().st_mtime + 1) - 6 * 3600:
                    p.unlink()
            except OSError:
                pass
        rows = self.all()
        keep = retention(rows)
        gone = []
        for r in rows:
            if r["id"] not in keep:
                for suffix in (".zbk", ".json"):
                    try:
                        os.unlink(self.root / f"{r['id']}{suffix}")
                    except FileNotFoundError:
                        pass
                gone.append(r["id"])
        return gone
