"""FCM HTTP v1 wake-ups. The message carries exactly {"t": "sync"}: no title, no text, nothing about why.
The phone wakes, fetches /api/inbox over mTLS and builds the notification itself, so Google never sees content.
High severity goes as a high-priority message; everything else as normal. Bursts collapse into one."""
import base64
import json
import threading
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from . import config

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
COALESCE_S = 15


def payload(token: str, high: bool) -> dict:
    """The whole message body. Tests pin it: data is exactly {"t": "sync"}."""
    return {"message": {"token": token, "data": {"t": "sync"},
                        "android": {"priority": "high" if high else "normal", "collapse_key": "zeus-sync", "ttl": "3600s"}}}


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class Fcm:
    def __init__(self, tokens):
        self.tokens = tokens          # () -> list of registered FCM tokens
        self._access = (None, 0.0)
        self._lock = threading.Lock()
        self._pending_high = None     # None: nothing pending; else whether any pending wake is high
        self._last = 0.0

    def available(self) -> bool:
        return config.FCM_ACCOUNT.exists()

    def _account(self) -> dict:
        return json.loads(config.FCM_ACCOUNT.read_text())

    def _token(self) -> str:
        tok, exp = self._access
        if tok and time.time() < exp - 60:
            return tok
        sa = self._account()
        now = int(time.time())
        head = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = _b64(json.dumps({"iss": sa["client_email"], "scope": SCOPE, "aud": sa["token_uri"], "iat": now, "exp": now + 3600}).encode())
        key = serialization.load_pem_private_key(sa["private_key"].encode(), None)
        sig = _b64(key.sign(f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256()))
        body = f"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion={head}.{claims}.{sig}".encode()
        with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], body, {"Content-Type": "application/x-www-form-urlencoded"}), timeout=15) as r:
            d = json.loads(r.read())
        self._access = (d["access_token"], time.time() + int(d.get("expires_in", 3600)))
        return self._access[0]

    def send_now(self, high: bool) -> list[int]:
        """Blocking. HTTP status per registered token."""
        if not self.available():
            return []
        project = self._account()["project_id"]
        url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"
        out = []
        for t in self.tokens():
            req = urllib.request.Request(url, json.dumps(payload(t, high)).encode(), {"Authorization": f"Bearer {self._token()}", "Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=15) as r:
                    out.append(r.status)
            except urllib.error.HTTPError as e:
                out.append(e.code)
            except OSError:
                out.append(0)
        return out

    def wake(self, high: bool) -> None:
        """Ask for a wake-up. At most one every COALESCE_S; a high one in the window makes the next one high."""
        with self._lock:
            first = self._pending_high is None
            self._pending_high = high or bool(self._pending_high)
        if first:
            threading.Thread(target=self._fire, daemon=True).start()

    def _fire(self) -> None:
        time.sleep(max(0.0, self._last + COALESCE_S - time.time()))
        with self._lock:
            high, self._pending_high = bool(self._pending_high), None
        self._last = time.time()
        try:
            self.send_now(high)
        except Exception:
            pass
