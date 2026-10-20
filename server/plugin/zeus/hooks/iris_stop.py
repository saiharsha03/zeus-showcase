"""Stop hook (fallback): if Iris's turn started from a Zeus message and she didn't use the reply tool, her final
text goes back to Zeus anyway, so the owner isn't left waiting. Returns at once (the work happens in a forked child),
reads nothing for turns that didn't come from Zeus, and never runs outside Iris's tmux session."""
import json
import os
import re
import subprocess
import sys
import uuid

SPOOL = os.environ.get("ZEUS_SPOOL", "/var/lib/zeus-spool")
SESSION = os.environ.get("ZEUS_CHANNEL_SESSION", "claude-auto")
ZEUS_TAG = re.compile(r'<channel[^>]*source="plugin:zeus:zeus"[^>]*zeus_id="(\d+)"')


def last_turn(transcript: str) -> tuple[str, list[str], bool]:
    """(the prompt that started the last turn, assistant texts in it, whether the zeus reply tool was used)."""
    with open(transcript, encoding="utf-8") as f:
        rows = [json.loads(ln) for ln in f if ln.strip()]
    texts, replied = [], False
    for r in reversed(rows):
        content = r.get("message", {}).get("content")
        if r.get("type") == "assistant" and isinstance(content, list):
            for c in reversed(content):
                if c.get("type") == "text" and c.get("text", "").strip():
                    texts.append(c["text"])
                if c.get("type") == "tool_use" and "zeus" in c.get("name", "") and c.get("name", "").endswith("reply"):
                    replied = True
        elif r.get("type") == "user" and not r.get("isMeta"):
            prompt = content if isinstance(content, str) else "\n".join(c.get("text", "") for c in content or [] if c.get("type") == "text")
            if prompt:      # tool results are user rows too; a real prompt starts the turn
                return prompt, texts[::-1], replied
    return "", [], replied


def main() -> None:
    ev = json.load(sys.stdin)
    pane = os.environ.get("TMUX_PANE")
    if ev.get("stop_hook_active") or not pane:
        return
    name = subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#S"], capture_output=True, text=True, timeout=5).stdout.strip()
    if name != SESSION:
        return
    if os.fork():
        return          # the parent hands control back to Claude straight away
    os.setsid()
    prompt, texts, replied = last_turn(ev["transcript_path"])
    ids = ZEUS_TAG.findall(prompt)
    if not ids or replied or not texts:
        return
    out = {"agent": "iris", "message_id": int(ids[-1]), "texts": texts[-6:]}
    tmp = os.path.join(SPOOL, f".{uuid.uuid4().hex}.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f)
    os.chmod(tmp, 0o640)    # the spool is openclaw:zeus 2770: the gateway reads by group
    os.rename(tmp, os.path.join(SPOOL, f"{uuid.uuid4().hex}.json"))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    os._exit(0)
