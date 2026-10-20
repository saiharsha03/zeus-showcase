import datetime as dt
import os

import pytest
from fastapi.testclient import TestClient

from conftest import GOOD, TMP

from gateway import app as gw
from gateway import backup as bk

client = TestClient(gw.app)
DAY = 86400


def bid(t: float, n: int = 0) -> str:
    return dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{n:08x}"


@pytest.fixture(autouse=True)
def empty():
    for p in (TMP / "backups").iterdir():
        p.unlink()
    yield


def put(b, body, kind="nightly", headers=GOOD):
    return client.put(f"/api/backup/{b}?kind={kind}", headers=headers, content=body)


def test_device_check_and_round_trip():
    body = b"ZBK1" + os.urandom(5000)
    b = bid(1_792_000_000)
    assert client.get("/api/backup").status_code == 403
    assert put(b, body, headers={}).status_code == 403
    r = put(b, body)
    assert r.status_code == 200 and r.json()["size"] == len(body)
    assert put(b, body).status_code == 409                       # never overwritten
    lst = client.get("/api/backup", headers=GOOD).json()
    assert [x["id"] for x in lst["backups"]] == [b] and lst["ready"]
    got = client.get(f"/api/backup/{b}", headers=GOOD)
    assert got.status_code == 200 and got.content == body
    assert client.get(f"/api/backup/{bid(1_792_000_001)}", headers=GOOD).status_code == 404


def test_only_ciphertext_with_the_magic_and_under_the_cap():
    assert put(bid(1), b"PK\x03\x04plain zip").status_code == 400
    assert put(bid(2), b"ZB").status_code == 400
    assert put(bid(3), b"ZBK1" + b"x" * (1 << 20)).status_code == 413
    assert put("../../etc/passwd", b"ZBK1").status_code in (400, 404)
    assert put(bid(4), b"ZBK1x", kind="weekly").status_code == 400
    assert client.get("/api/backup/..%2Fzeus.db", headers=GOOD).status_code in (400, 404)
    assert [p.name for p in (TMP / "backups").iterdir()] == []    # nothing half written stays


def test_refuses_when_the_box_is_not_mounted(monkeypatch):
    monkeypatch.setattr(gw.backups, "require_mount", True)
    assert put(bid(5), b"ZBK1abc").status_code == 503


def test_retention_keeps_14_recent_and_4_weekly():
    now = 1_792_000_000
    rows = [{"id": bid(now - i * DAY, i), "created": now - i * DAY} for i in range(60)]
    keep = bk.retention(rows)
    newest = {r["id"] for r in rows[:14]}
    assert newest <= keep
    older = keep - newest
    assert len(older) == 4
    weeks = {dt.datetime.fromtimestamp(r["created"], dt.timezone.utc).isocalendar()[:2] for r in rows if r["id"] in older}
    assert len(weeks) == 4
    # Each kept weekly is the newest of its week among the older backups.
    for r in rows[14:]:
        if r["id"] in older:
            wk = dt.datetime.fromtimestamp(r["created"], dt.timezone.utc).isocalendar()[:2]
            same = [x for x in rows[14:] if dt.datetime.fromtimestamp(x["created"], dt.timezone.utc).isocalendar()[:2] == wk]
            assert r["created"] == max(x["created"] for x in same)
    assert bk.retention(rows[:10]) == {r["id"] for r in rows[:10]}


def test_upload_prunes_old_backups(monkeypatch):
    now = 1_792_000_000
    t = [now - i * DAY for i in range(20, 0, -1)]
    for i, c in enumerate(t):
        monkeypatch.setattr(gw.time, "time", lambda c=c: c)
        assert put(bid(c, i), b"ZBK1" + bytes([i])).status_code == 200
    ids = [x["id"] for x in client.get("/api/backup", headers=GOOD).json()["backups"]]
    assert len(ids) <= 14 + 4 and ids[0] == bid(t[-1], 19)
    assert len(list((TMP / "backups").glob("*.zbk"))) == len(ids)
