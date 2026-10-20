"""POST /hooks/anthropic: Anthropic Managed Agents webhooks, so the phone wakes the moment a cloud session pauses.

The only route without a device certificate: it's served on Caddy's own hooks.example.com host (exactly
POST /hooks/anthropic, 64 KB, with the proxy key; the main site stays strictly mTLS). The caller is proven by the
delivery's Standard Webhooks signature instead: HMAC-SHA256 over "<webhook-id>.<webhook-timestamp>.<raw
body>" with the whsec_ signing secret (/etc/zeus/anthropic-webhook.secret, root:zeus 0640), at most 5 minutes old.

Payloads are thin (event type + resource ids). Only data.type and data.id are read; nothing is stored or logged.
session.status_idled doesn't say why the session idled (stop_reason lives on the session's events, which only the
phone fetches), so every idle wakes the phone with the usual content-free {"t": "sync"}; the phone checks for a
pending phone tool call itself. Should a payload ever carry a stop_reason other than requires_action, it's skipped.
"""
import base64
import binascii
import hashlib
import hmac
import json
import re
import time

from fastapi import APIRouter, HTTPException, Request, Response

from . import config

TOLERANCE_S = 300          # the SDK's unwrap() window, both directions
DEBOUNCE_S = 10            # one wake per session per 10 s
MAX_BODY = 64 * 1024       # Caddy enforces it too
MAX_SESSIONS = 1024
SESSION_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

_last: dict[str, float] = {}   # session id -> monotonic time of its last wake (insertion order = age)


def _key() -> bytes:
    """The decoded signing secret; 503 when it isn't there. The value never reaches a log or an error message."""
    try:
        s = config.ANTHROPIC_WEBHOOK_SECRET.read_text().strip()
    except (OSError, UnicodeDecodeError):
        s = ""
    s = s.removeprefix("whsec_")
    try:
        key = base64.b64decode(s, validate=True) if s else b""
    except (binascii.Error, ValueError):
        key = b""
    if not key:
        raise HTTPException(503, "webhook not configured")
    return key


def verify(key: bytes, headers, body: bytes, now: float) -> bool:
    """Standard Webhooks: webhook-signature is a space-separated list of "v1,<base64>"; any one matching is enough."""
    mid, ts, sigs = headers.get("webhook-id") or "", headers.get("webhook-timestamp") or "", headers.get("webhook-signature") or ""
    if not mid or not sigs or not re.fullmatch(r"\d{1,12}", ts) or abs(now - int(ts)) > TOLERANCE_S:
        return False
    want = base64.b64encode(hmac.new(key, f"{mid}.{ts}.".encode() + body, hashlib.sha256).digest())
    good = False
    for s in sigs.split():
        version, _, sig = s.partition(",")
        if version == "v1" and hmac.compare_digest(sig.encode(), want):
            good = True
    return good


def _due(sid: str, now: float) -> bool:
    """True (and remembered) when this session hasn't woken the phone in the last DEBOUNCE_S."""
    if now - _last.get(sid, -DEBOUNCE_S) < DEBOUNCE_S:
        return False
    _last.pop(sid, None)
    _last[sid] = now
    while len(_last) > MAX_SESSIONS:
        del _last[next(iter(_last))]
    return True


def router(wake, proxied) -> APIRouter:
    """wake(): the gateway's FCM sync push (non-blocking; it sends from its own thread). proxied(headers): the proxy key matches."""
    r = APIRouter()

    @r.post("/hooks/anthropic")
    async def anthropic(request: Request):
        if not proxied(request.headers):
            raise HTTPException(403, "not through the proxy")
        key = _key()
        body = await request.body()
        if len(body) > MAX_BODY:
            raise HTTPException(413, "too large")
        if not verify(key, request.headers, body, time.time()):
            raise HTTPException(401, "bad signature")
        try:
            data = json.loads(body).get("data") or {}
            kind, sid, stop = data.get("type"), data.get("id"), data.get("stop_reason")
        except (ValueError, AttributeError):
            raise HTTPException(400, "bad payload")
        if kind != "session.status_idled":
            return Response(status_code=204)
        if not isinstance(sid, str) or not SESSION_ID.match(sid):
            raise HTTPException(400, "bad session id")
        if stop is not None and (stop.get("type") if isinstance(stop, dict) else stop) != "requires_action":
            return Response(status_code=204)
        if _due(sid, time.monotonic()):
            wake()
        return {"ok": True}

    return r
