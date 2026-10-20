"""Codex `notify` program for Atlas: when a turn that started from a Zeus message ends, its last answer goes back.

~/.codex/config.toml (top level):  notify = ["/usr/bin/python3", "-I", "/opt/zeus/hooks/atlas_notify.py"]

Codex passes one JSON argument per finished turn. Turns without a "[zeus #N]" tag in their input are ignored
untouched. Codex may run this from its daemon outside tmux, so the tag (matched to an open delivery for Atlas by
the gateway) is what files the answer, not the session.

Agora (the agents' room): a post from Iris, Athena or the owner reaches Atlas the same way, as a tagged message whose header
says "... in Agora". His answer to it is filed in Agora and relayed to the other agents, so in Agora he answers his
part, replies exactly PASS when another agent has it (PASS is never posted), and doesn't repeat what the others said.
"""
import json
import os
import sys
import uuid

SPOOL = os.environ.get("ZEUS_SPOOL", "/var/lib/zeus-spool")


def main() -> None:
    ev = json.loads(sys.argv[-1])
    if ev.get("type") != "agent-turn-complete":
        return
    inputs = ev.get("input-messages") or []
    last = inputs[-1] if inputs else ""
    prompt = last if isinstance(last, str) else json.dumps(last)
    if "[zeus #" not in prompt:
        return
    text = (ev.get("last-assistant-message") or "").strip()
    out = {"agent": "atlas", "prompt": prompt[:4000], "texts": [text] if text else []}
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
    sys.exit(0)
