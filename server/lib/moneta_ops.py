"""Iris -> Moneta operations: the one schema, checked by zeus-moneta (Iris's CLI) and again by the gateway; the phone
checks a third time before applying. Ops apply directly on the phone (no proposal card), each one logged in Moneta's
change log with an undo. Nothing here can move money, and no field may carry a full card number."""
import json
import re

OPS = ("add", "edit", "recategorize", "merge", "delete", "budget", "sub_add", "sub_remove", "chart_put", "chart_remove")
KINDS = ("spend", "refund", "payment", "transfer", "income", "declined")
CYCLES = ("weekly", "monthly", "yearly")
FIELDS = ("date", "amount", "merchant", "category", "kind", "last4", "currency", "note")
KEY = re.compile(r"^[A-Za-z0-9:_.-]{1,64}$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2})?)?$")
CHART_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
LONG_DIGITS = re.compile(r"\d(?:[ -]?\d){6,}")
MAX_OP = 8 * 1024
MAX_SPEC = 4 * 1024
CHART_TYPES = ("line", "bar", "stacked", "donut", "table")
BY = ("month", "week", "day", "category", "merchant", "card")
SPLIT = ("month", "category", "merchant", "card")
QUERY_KEYS = {"metric", "by", "split", "months", "categories", "merchants", "cards", "kinds", "top", "cumulative", "reference"}


class Invalid(ValueError):
    pass


def _text(v, name, cap, required=False):
    if v is None:
        if required:
            raise Invalid(f"{name} is required")
        return None
    if not isinstance(v, str) or len(v) > cap:
        raise Invalid(f"{name} must be text, at most {cap} characters")
    if LONG_DIGITS.search(v):
        raise Invalid(f"{name} looks like it holds a card or account number; never send those")
    return v.strip()


def _amount(v, name="amount"):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not (0 < v <= 10_000_000):
        raise Invalid(f"{name} must be a positive number of dollars")
    return round(float(v), 2)


def _key(v, name="key"):
    if not isinstance(v, str) or not KEY.match(v):
        raise Invalid(f"{name} must match {KEY.pattern}")
    return v


def _fields(d, require=()):
    if not isinstance(d, dict):
        raise Invalid("fields must be an object")
    extra = set(d) - set(FIELDS)
    if extra:
        raise Invalid(f"unknown fields: {sorted(extra)}")
    out = {}
    for k in require:
        if d.get(k) is None:
            raise Invalid(f"{k} is required")
    if "date" in d:
        if not isinstance(d["date"], str) or not DATE.match(d["date"]):
            raise Invalid("date must be YYYY-MM-DD or YYYY-MM-DDTHH:MM")
        out["date"] = d["date"]
    if "amount" in d:
        out["amount"] = _amount(d["amount"])
    for k, cap in (("merchant", 60), ("category", 40), ("note", 200)):
        if k in d:
            out[k] = _text(d[k], k, cap)
    if "kind" in d:
        if d["kind"] not in KINDS:
            raise Invalid(f"kind must be one of {KINDS}")
        out["kind"] = d["kind"]
    if "last4" in d:
        if d["last4"] is not None and not (isinstance(d["last4"], str) and re.fullmatch(r"\d{4}", d["last4"])):
            raise Invalid("last4 must be exactly four digits (never the full number)")
        out["last4"] = d["last4"]
    if "currency" in d:
        if not (isinstance(d["currency"], str) and re.fullmatch(r"[A-Z]{3}", d["currency"])):
            raise Invalid("currency must be a 3-letter code like USD")
        out["currency"] = d["currency"]
    return out


def _list(v, name, cap=20, item=40, pattern=None):
    if v is None:
        return []
    if not isinstance(v, list) or len(v) > cap:
        raise Invalid(f"{name} must be a list of at most {cap}")
    out = []
    for x in v:
        if not isinstance(x, str) or not x.strip() or len(x) > item or (pattern and not re.fullmatch(pattern, x)):
            raise Invalid(f"{name} entries must be short text")
        out.append(x.strip())
    return out


def chart_spec(spec):
    """A chart Iris pins in Moneta. Data comes from Moneta's own ledger on the phone; the spec only says what to draw."""
    if not isinstance(spec, dict) or len(json.dumps(spec)) > MAX_SPEC:
        raise Invalid(f"spec must be an object under {MAX_SPEC} bytes")
    extra = set(spec) - {"id", "type", "title", "subtitle", "query"}
    if extra:
        raise Invalid(f"unknown spec keys: {sorted(extra)}")
    if not isinstance(spec.get("id"), str) or not CHART_ID.match(spec["id"]):
        raise Invalid("id must be lowercase letters, digits and dashes, at most 40")
    if spec.get("type") not in CHART_TYPES:
        raise Invalid(f"type must be one of {CHART_TYPES}")
    title = _text(spec.get("title"), "title", 60, required=True)
    if not title:
        raise Invalid("title is required")
    q = spec.get("query") or {}
    if not isinstance(q, dict) or set(q) - QUERY_KEYS:
        raise Invalid(f"query keys must be among {sorted(QUERY_KEYS)}")
    if q.get("metric", "spend") not in ("spend", "count"):
        raise Invalid("metric must be spend or count")
    if q.get("by", "month") not in BY:
        raise Invalid(f"by must be one of {BY}")
    if q.get("split") is not None and (q["split"] not in SPLIT or q["split"] == q.get("by", "month")):
        raise Invalid(f"split must be one of {SPLIT}, and differ from by")
    months = q.get("months", 6)
    if isinstance(months, bool) or not isinstance(months, int) or not 1 <= months <= 24:
        raise Invalid("months must be 1 to 24")
    top = q.get("top", 8)
    if isinstance(top, bool) or not isinstance(top, int) or not 1 <= top <= 12:
        raise Invalid("top must be 1 to 12")
    if not isinstance(q.get("cumulative", False), bool):
        raise Invalid("cumulative must be true or false")
    if q.get("reference") not in (None, "budget"):
        raise Invalid('reference must be "budget" or null')
    _list(q.get("categories"), "categories"); _list(q.get("merchants"), "merchants")
    _list(q.get("cards"), "cards", pattern=r"\d{4}")
    kinds = _list(q.get("kinds"), "kinds")
    if any(k not in KINDS for k in kinds):
        raise Invalid(f"kinds must be among {KINDS}")
    _text(spec.get("subtitle"), "subtitle", 100)
    return spec


def check(op, args):
    """Validates one op's arguments. Returns them normalised, or raises Invalid with a reason Iris can act on."""
    if op not in OPS:
        raise Invalid(f"op must be one of {OPS}")
    if not isinstance(args, dict):
        raise Invalid("args must be an object")
    if op == "add":
        f = _fields({k: v for k, v in args.items() if k != "key"}, require=("date", "amount", "merchant"))
        return ({"key": _key(args["key"])} if args.get("key") is not None else {}) | f
    if op == "edit":
        f = _fields(args.get("fields"))
        if not f:
            raise Invalid("edit needs at least one field")
        return {"key": _key(args.get("key")), "fields": f}
    if op == "recategorize":
        return {"key": _key(args.get("key")), "category": _text(args.get("category"), "category", 40, required=True)}
    if op == "merge":
        drop = args.get("drop")
        if not isinstance(drop, list) or not 1 <= len(drop) <= 20:
            raise Invalid("drop must list 1 to 20 keys")
        keep = _key(args.get("keep"), "keep")
        drops = [_key(d, "drop") for d in drop]
        if keep in drops:
            raise Invalid("keep can't also be dropped")
        return {"keep": keep, "drop": drops}
    if op == "delete":
        return {"key": _key(args.get("key"))}
    if op == "budget":
        a = args.get("amount")
        return {"category": _text(args.get("category"), "category", 40, required=True), "amount": None if a is None else _amount(a)}
    if op == "sub_add":
        if args.get("cycle", "monthly") not in CYCLES:
            raise Invalid(f"cycle must be one of {CYCLES}")
        nxt = args.get("next")
        if nxt is not None and not (isinstance(nxt, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", nxt)):
            raise Invalid("next must be YYYY-MM-DD")
        return {"name": _text(args.get("name"), "name", 40, required=True), "amount": None if args.get("amount") is None else _amount(args["amount"]),
                "cycle": args.get("cycle", "monthly"), "next": nxt, "bank": _text(args.get("bank"), "bank", 30)}
    if op == "sub_remove":
        return {"name": _text(args.get("name"), "name", 40, required=True)}
    if op == "chart_put":
        return {"spec": chart_spec(args.get("spec"))}
    if not isinstance(args.get("id"), str) or not CHART_ID.match(args["id"]):
        raise Invalid("id is invalid")
    return {"id": args["id"]}


def envelope_ok(env):
    """The gateway's check of a spooled op: shape, size, and arguments."""
    try:
        if not isinstance(env, dict) or env.get("v") != 1 or not re.fullmatch(r"[0-9a-f]{24}", str(env.get("op_id", ""))):
            return False
        if len(json.dumps(env)) > MAX_OP:
            return False
        int(env["at"])
        check(env.get("op"), env.get("args"))
        return True
    except (Invalid, KeyError, TypeError, ValueError):
        return False
