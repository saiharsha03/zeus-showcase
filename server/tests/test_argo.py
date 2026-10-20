import asyncio
import json
import os

import pytest
from fastapi.testclient import TestClient

from conftest import GOOD

from gateway import app as gw
import argo_info as ai

client = TestClient(gw.app)

FLIGHT = {"kind": "flight", "place": "Boston", "tz": "America/New_York", "from": "Denver (DEN)", "to": "Boston (BOS)",
          "depart": "2027-03-04T08:10", "arrive": "2027-03-04T13:55", "carrier": "United", "number": "UA 1234", "calypso_id": "a1b2c3d4e5f6"}


def test_check_keeps_a_flight_and_refuses_confirmation_numbers():
    assert ai.check(FLIGHT)["number"] == "UA 1234"
    assert ai.check({**FLIGHT, "price": 129.4, "paid": True})["price"] == 129.4
    for bad in ({**FLIGHT, "confirmation": "QX7Z2B"},                 # an unknown field
                {**FLIGHT, "carrier": "United confirmation QX7Z2B"},    # code words in a value
                {**FLIGHT, "number": "12345678"},                       # 7+ digits
                {**FLIGHT, "depart": "next tuesday"},
                {**FLIGHT, "kind": "cruise"},
                {**FLIGHT, "price": "129"}, {**FLIGHT, "paid": "yes"},
                {"kind": "hotel", "place": "Boston", "name": "Harborview Hotel"},   # no check_in
                {"kind": "cancel", "place": "Boston", "ref": "QX7Z2B"},
                {k: v for k, v in FLIGHT.items() if k != "place"}):
        with pytest.raises(ai.Invalid):
            ai.check(bad)
    assert ai.check({"kind": "cancel", "place": "Boston", "ref": "iris:fedcba9876543210fedcba98"})["ref"].startswith("iris:")
    # Every travel booking: tickets, tours, rentals, tables, parking, with a venue, an end and a status.
    ferry = {"kind": "event", "place": "Boston", "title": "Harbor islands ferry", "venue": "Harbor Ferry Co.", "at": "2027-03-06T10:00", "status": "booked"}
    assert ai.check(ferry)["venue"] == "Harbor Ferry Co."
    assert ai.check({"kind": "car", "place": "Portland", "title": "Rental car", "at": "2027-05-01T10:00", "until": "2027-05-08T09:00"})["until"]
    assert ai.check({"kind": "event", "place": "Boston", "title": "Harbor islands ferry", "status": "cancelled"})["status"] == "cancelled"
    for bad in ({**ferry, "status": "maybe"}, {**ferry, "until": "soon"}, {k: v for k, v in ferry.items() if k != "at"}, {**ferry, "venue": "Seat 12345678"}):
        with pytest.raises(ai.Invalid):
            ai.check(bad)
    assert ai.check({"kind": "hotel", "place": "Boston", "name": "Harborview Hotel", "address": "100 Example St, Boston, MA",
                     "check_in": "2027-03-04T15:00", "check_out": "2027-03-08T11:00"})["name"] == "Harborview Hotel"


def test_trip_info_over_the_channel_queues_for_the_phone_and_acks():
    async def run():
        server = asyncio.create_task(gw.chat.serve_channel())
        await asyncio.sleep(0.2)
        r, w = await asyncio.open_unix_connection(os.environ["ZEUS_CHANNEL_SOCK"])
        w.write(b'{"t":"hello","agent":"iris","pid":1}\n'); await w.drain()
        await asyncio.sleep(0.1)
        w.write((json.dumps({"t": "trip.info", "req": 1, "info": FLIGHT}) + "\n").encode()); await w.drain()
        ok = json.loads(await asyncio.wait_for(r.readline(), 5))
        w.write((json.dumps({"t": "trip.info", "req": 2, "info": {**FLIGHT, "number": "PNR QX7Z2B"}}) + "\n").encode()); await w.drain()
        bad = json.loads(await asyncio.wait_for(r.readline(), 5))
        w.close(); server.cancel()
        return ok, bad
    ok, bad = asyncio.run(run())
    assert ok["t"] == "trip.ack" and ok["req"] == 1 and ok["ok"] and ok["id"].startswith("iris:")
    assert bad["ok"] is False and "Calypso" in bad["error"]
    items = client.get("/api/argo/info", headers=GOOD).json()
    mine = [i for i in items if "iris:" + i["id"] == ok["id"]]
    assert mine and mine[0]["number"] == "UA 1234" and mine[0]["from_agent"] == "iris"
    assert client.get("/api/argo/info").status_code == 403
    assert client.post("/api/argo/info/ack", headers=GOOD, json={"ids": [mine[0]["id"]]}).json() == {"ok": True, "removed": 1}
    assert all("iris:" + i["id"] != ok["id"] for i in client.get("/api/argo/info", headers=GOOD).json())


def test_trip_info_is_not_chat():
    before = client.get("/api/chat/iris", headers=GOOD).json()
    gw.argo.put({"kind": "dates", "place": "Boston", "start": "2027-03-04", "end": "2027-03-08"})
    assert client.get("/api/chat/iris", headers=GOOD).json() == before
