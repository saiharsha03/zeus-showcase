"""Prometheus's queue on Lemnos: app-change requests from the phone, builds from the laptop.

The VPS never builds Zeus and never holds its source or signing key. The phone queues a request (signed with
The owner's fingerprint); the owner's laptop session picks it up over SSH by hand (`zeus-prometheus list|claim|publish`,
root only), builds and tests it there, and publishes the result here. The phone downloads the APK over mTLS and
installs only after its own signing check and Android's confirmation.

Layout: <dir>/<id>/request.json, build.json, app.apk, patch.diff, tests.txt. Writes are atomic (tmp + rename).
All queue I/O runs as zeus (the gateway, and the CLI after it drops root), never as root. Temp files are created
O_EXCL|O_NOFOLLOW, so a planted symlink is never followed.
"""
import hashlib
import json
import os
import re
import time
from pathlib import Path

ID = re.compile(r"p[0-9a-f]{12}")
STATUSES = ("queued", "building", "ready", "failed", "cancelled")
MAX_PROBLEM = 1000
MAX_APK = 200 * 1024 * 1024
MAX_TEXT = 256 * 1024
UPLOADS = ("build.json", "app.apk", "patch.diff", "tests.txt")


def read_upload(folder: str) -> dict[str, bytes]:
    """Reads the laptop's upload folder (root-owned, 0700) without following links. Called before dropping root."""
    out = {}
    dfd = os.open(folder, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0))
    try:
        for name in UPLOADS:
            try:
                fd = os.open(name, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0), dir_fd=dfd)
            except FileNotFoundError:
                continue
            with os.fdopen(fd, "rb") as f:
                out[name] = f.read(MAX_APK + 1)
    finally:
        os.close(dfd)
    if "build.json" not in out:
        raise ValueError("build.json missing")
    return out


def _write(p: Path, data: bytes) -> None:
    tmp = p.with_name("." + p.name + ".tmp")
    try:
        os.unlink(tmp)   # removes a leftover or planted link itself, never its target
    except FileNotFoundError:
        pass
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o640)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, p)   # rename replaces the name, so a symlink at p is replaced, not followed


def _json(p: Path) -> dict | None:
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


class Queue:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _dir(self, rid: str) -> Path:
        if not ID.fullmatch(rid or ""):
            raise ValueError("bad id")
        return self.root / rid

    def _save(self, rid: str, name: str, data: bytes) -> None:
        d = self._dir(rid)
        if d.is_symlink() or not d.is_dir():
            raise ValueError("bad request dir")
        _write(d / name, data)

    def get(self, rid: str) -> dict | None:
        r = _json(self._dir(rid) / "request.json")
        if r is None:
            return None
        b = _json(self._dir(rid) / "build.json")
        if b is not None:
            r["build"] = b
        return r

    def all(self, limit: int = 50) -> list[dict]:
        out = []
        for d in self.root.iterdir():
            if d.is_dir() and ID.fullmatch(d.name):
                r = self.get(d.name)
                if r:
                    out.append(r)
        out.sort(key=lambda r: r.get("created", 0), reverse=True)
        return out[:limit]

    def add(self, rid: str, problem: str, now: float | None = None) -> dict:
        problem = (problem or "").strip()
        if not problem or len(problem) > MAX_PROBLEM:
            raise ValueError("problem must be 1 to 1000 characters")
        d = self._dir(rid)
        if d.exists():
            raise FileExistsError("already queued")
        d.mkdir(mode=0o750)
        now = now or time.time()
        r = {"id": rid, "problem": problem, "status": "queued", "created": now, "updated": now, "error": None}
        self._save(rid, "request.json", json.dumps(r).encode())
        return r

    def _set(self, rid: str, status: str, allowed_from: tuple[str, ...], error: str | None = None) -> dict:
        r = _json(self._dir(rid) / "request.json")
        if r is None:
            raise KeyError("no such request")
        if r["status"] not in allowed_from:
            raise ValueError(f"request is {r['status']}")
        r.update(status=status, updated=time.time(), error=(error or None) and error[:500])
        self._save(rid, "request.json", json.dumps(r).encode())
        return r

    def cancel(self, rid: str) -> dict:
        return self._set(rid, "cancelled", ("queued",))

    def claim(self, rid: str) -> dict:
        return self._set(rid, "building", ("queued", "failed"))

    def fail(self, rid: str, error: str) -> dict:
        return self._set(rid, "failed", ("building",), error or "failed")

    def publish(self, rid: str, files: dict[str, bytes]) -> dict:
        """The laptop's upload, already read into memory: build.json (required), app.apk, patch.diff, tests.txt."""
        info = json.loads(files["build.json"].decode("utf-8"))
        tests = info.get("tests") or {}
        diff = info.get("diff") or {}
        build = {
            "summary": str(info.get("summary", ""))[:2000],
            "diff": {k: int(diff.get(k, 0)) for k in ("files", "insertions", "deletions")},
            "tests": {"passed": int(tests.get("passed", 0)), "failed": int(tests.get("failed", 0)), "line": str(tests.get("line", ""))[:300]},
            "version_name": str(info.get("version_name", ""))[:40] or None,
            "version_code": int(info["version_code"]) if info.get("version_code") is not None else None,
            "commit": str(info.get("commit", ""))[:40],
            "built": time.time(),
        }
        for name in ("patch.diff", "tests.txt"):
            if name in files:
                self._save(rid, name, files[name][:MAX_TEXT])
        apk = files.get("app.apk")
        ok = build["tests"]["failed"] == 0 and apk is not None
        if apk is not None:
            if len(apk) > MAX_APK:
                raise ValueError("apk too large")
            self._save(rid, "app.apk", apk)
            build["apk_sha256"] = hashlib.sha256(apk).hexdigest()
            build["apk_bytes"] = len(apk)
        self._save(rid, "build.json", json.dumps(build).encode())
        return self._set(rid, "ready" if ok else "failed", ("building",), None if ok else (info.get("error") or "tests failed"))

    def apk(self, rid: str) -> Path | None:
        r = self.get(rid)
        p = self._dir(rid) / "app.apk"
        return p if r and r["status"] == "ready" and p.is_file() and not p.is_symlink() else None
