"""trip.info: what Iris tells Argo (Zeus's trips) about a booking or a plan. Checked here, on the gateway, and again on
the phone. Never a confirmation number, a full card number or an account: the confirmation itself goes to Calypso with
zeus-calypso-drop, and trip.info carries only the id that printed (calypso_id). Docs: /opt/zeus/docs/IRIS-ARGO.md."""
import re
from datetime import date, datetime

# Plans are every other travel booking: tickets and attractions, tours, car rentals, tables, parking.
PLANS = ("plan", "event", "tour", "car", "restaurant", "parking")
KINDS = ("flight", "train", "bus", "drive", "hotel", "dates", "cancel") + PLANS
STATUSES = ("booked", "changed", "cancelled")
MAX_INFO = 4096
FIELDS = {
    # name: max length
    "kind": 10, "place": 60, "tz": 40, "from": 80, "to": 80, "depart": 32, "arrive": 32, "carrier": 40, "number": 16,
    "platform": 24, "name": 80, "address": 120, "check_in": 32, "check_out": 32, "title": 80, "at": 32, "where": 80,
    "start": 10, "end": 10, "ref": 40, "calypso_id": 64, "venue": 80, "until": 32, "status": 10,
}
BOOLS = ("returning", "paid")
NEEDS = {
    "flight": ("depart",), "train": ("depart",), "bus": ("depart",), "drive": ("depart",),
    "hotel": ("name", "check_in"), "dates": ("start",), "cancel": ("ref",),
} | {k: ("title", "at") for k in PLANS}
TIMES = ("depart", "arrive", "check_in", "check_out", "at", "until")
CODEY = re.compile(r"(?i)\b(confirmation|conf\.?|record locator|pnr|booking (ref|reference|code|number)|reservation (number|code)|e-?ticket)\b")
DIGITS7 = re.compile(r"\d{7,}")
CALYPSO_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
REF = re.compile(r"^iris:[0-9a-f]{24}$")


class Invalid(ValueError):
    pass


def _time(v: str) -> None:
    for parse in (datetime.fromisoformat, date.fromisoformat):
        try:
            parse(v)
            return
        except ValueError:
            continue
    raise Invalid(f"not an ISO date or time: {v!r}")


def check(info) -> dict:
    """The cleaned info, or Invalid saying what to fix."""
    if not isinstance(info, dict):
        raise Invalid("trip.info must be an object")
    unknown = set(info) - set(FIELDS) - set(BOOLS) - {"people", "price"}
    if unknown:
        raise Invalid(f"unknown fields: {', '.join(sorted(unknown))} (a confirmation goes to Calypso, not here)")
    kind = info.get("kind")
    if kind not in KINDS:
        raise Invalid(f"kind must be one of {', '.join(KINDS)}")
    out: dict = {"kind": kind}
    for k, cap in FIELDS.items():
        if k == "kind" or k not in info or info[k] is None:
            continue
        v = info[k]
        if not isinstance(v, str):
            raise Invalid(f"{k} must be a string")
        v = v.strip()
        if len(v) > cap:
            raise Invalid(f"{k} is longer than {cap}")
        if k not in ("calypso_id", "ref") and (CODEY.search(v) or DIGITS7.search(v)):
            raise Invalid(f"{k} looks like a confirmation or account number; seal it in Calypso and send calypso_id")
        if k in TIMES or k in ("start", "end"):
            _time(v)
        if v:
            out[k] = v
    if "calypso_id" in out and not CALYPSO_ID.match(out["calypso_id"]):
        raise Invalid("calypso_id is the id zeus-calypso-drop printed")
    if "ref" in out and not REF.match(out["ref"]):
        raise Invalid("ref is an id trip_info returned, like iris:0123456789abcdef01234567")
    if "tz" in out and not re.match(r"^[A-Za-z_]+(/[A-Za-z0-9_+-]+){0,2}$", out["tz"]):
        raise Invalid("tz is an IANA zone like America/New_York")
    for b in BOOLS:
        if b in info:
            if not isinstance(info[b], bool):
                raise Invalid(f"{b} must be true or false")
            out[b] = info[b]
    if "price" in info and info["price"] is not None:
        p = info["price"]
        if isinstance(p, bool) or not isinstance(p, (int, float)) or not 0 <= p <= 100000:
            raise Invalid("price is the booking's cost in dollars, a number")
        out["price"] = round(float(p), 2)
    if "people" in info:
        p = info["people"]
        if not isinstance(p, list) or len(p) > 8 or not all(isinstance(x, str) and 0 < len(x) <= 40 for x in p):
            raise Invalid("people is a list of up to 8 short names")
        out["people"] = [x.strip() for x in p]
    if "status" in out and out["status"] not in STATUSES:
        raise Invalid(f"status is one of {', '.join(STATUSES)}")
    if not out.get("place"):
        raise Invalid("place is the trip's city, like New York (for a way back too)")
    needs = NEEDS[kind]
    if kind in PLANS and out.get("status") == "cancelled":
        needs = ("title",) if "ref" not in out else ()     # a cancelled plan is found by its id, or its title and time
    missing = [k for k in needs if k not in out]
    if missing:
        raise Invalid(f"{kind} needs {', '.join(missing)}")
    return out
