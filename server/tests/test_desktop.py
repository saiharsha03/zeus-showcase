import os
import socket
import threading
import time

import pytest
from fastapi.testclient import TestClient

from conftest import GOOD, TMP

from gateway import app as gw, desktop

client = TestClient(gw.app)


@pytest.fixture
def vnc(monkeypatch):
    """A stand-in Xvnc on a unix socket: greets like RFB 3.8, then echoes."""
    path = str(TMP / "run" / "vnc.sock")
    srv = socket.socket(socket.AF_UNIX)
    srv.bind(path)
    srv.listen(4)

    def serve():
        while True:
            try:
                c, _ = srv.accept()
            except OSError:
                return
            c.sendall(b"RFB 003.008\n")
            while data := c.recv(65536):
                c.sendall(data)
            c.close()

    threading.Thread(target=serve, daemon=True).start()
    monkeypatch.setattr(desktop, "SOCK", path)
    yield path
    srv.close()
    os.unlink(path)


def test_desktop_routes_need_a_device():
    for method, path in [("GET", "/api/desktop"), ("POST", "/api/desktop/ticket")]:
        assert client.request(method, path).status_code == 403
    assert client.post("/api/desktop", json={"action": "start"}).status_code == 403


def test_status_reads_the_unit(monkeypatch):
    async def show():
        return {"ActiveState": "active", "SubState": "running", "MemoryCurrent": "314572800", "ActiveEnterTimestamp": "@1792000000"}
    monkeypatch.setattr(desktop, "show", show)
    monkeypatch.setattr(desktop, "SOCK", str(TMP / "run" / "none.sock"))
    r = client.get("/api/desktop", headers=GOOD).json()
    assert r["state"] == "active" and r["memory"] == 314572800 and r["since"] == 1792000000 and r["memory_max"] == 1 << 30
    assert r["ready"] is False and r["idle_minutes"] == 15      # no socket: not ready to view


def test_only_start_and_stop(monkeypatch):
    assert client.post("/api/desktop", headers=GOOD, json={"action": "restart"}).status_code == 422
    assert client.post("/api/desktop", headers=GOOD, json={"action": "rm -rf"}).status_code == 422
    calls = []

    async def root(*a, **k):
        calls.append(a)
        return b"{}"

    async def show():
        return {"ActiveState": "inactive"}
    monkeypatch.setattr(desktop.calls, "root", root)
    monkeypatch.setattr(desktop, "show", show)
    assert client.post("/api/desktop", headers=GOOD, json={"action": "stop"}).status_code == 200
    assert calls == [("unit", "stop", "zeus-desktop.service")]


def test_start_goes_through_zeus_root_and_its_refusal_comes_back(monkeypatch):
    async def show():
        return {"ActiveState": "inactive"}
    monkeypatch.setattr(desktop, "show", show)
    r = client.post("/api/desktop", headers=GOOD, json={"action": "start"})
    assert r.status_code == 403          # conftest's zeus-root refuses everything


def test_no_ticket_while_stopped(monkeypatch):
    monkeypatch.setattr(desktop, "SOCK", str(TMP / "run" / "none.sock"))
    assert client.post("/api/desktop/ticket", headers=GOOD).status_code == 409


def test_viewer_needs_ticket_and_caddys_headers(vnc):
    tk = client.post("/api/desktop/ticket", headers=GOOD).json()["ticket"]
    with pytest.raises(Exception):
        with client.websocket_connect(f"/ws/desktop?ticket={tk}") as ws:      # right ticket, no headers
            ws.receive_bytes()
    with pytest.raises(Exception):
        with client.websocket_connect("/ws/desktop?ticket=made-up", headers=GOOD) as ws:
            ws.receive_bytes()
    term = client.post("/api/events/ticket", headers=GOOD).json()["ticket"]  # another kind of ticket
    with pytest.raises(Exception):
        with client.websocket_connect(f"/ws/desktop?ticket={term}", headers=GOOD) as ws:
            ws.receive_bytes()


def test_viewer_bridges_bytes_both_ways_and_ticket_is_single_use(vnc):
    tk = client.post("/api/desktop/ticket", headers=GOOD).json()["ticket"]
    with client.websocket_connect(f"/ws/desktop?ticket={tk}", headers=GOOD) as ws:
        assert ws.receive_bytes() == b"RFB 003.008\n"
        ws.send_bytes(b"RFB 003.008\n")
        assert ws.receive_bytes() == b"RFB 003.008\n"
        ws.send_bytes(bytes(range(256)) * 64)
        got = b""
        while len(got) < 256 * 64:
            got += ws.receive_bytes()
        assert got == bytes(range(256)) * 64
    with pytest.raises(Exception):
        with client.websocket_connect(f"/ws/desktop?ticket={tk}", headers=GOOD) as ws:
            ws.receive_bytes()
    end = time.time() + 3
    while desktop.viewers and time.time() < end:
        time.sleep(0.05)
    assert desktop.viewers == 0
