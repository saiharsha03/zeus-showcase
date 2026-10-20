"""Calling the helpers. Each call is a fresh `sudo -n -u openclaw zeus-openclaw ...` (or zeus-root): nothing
long-lived runs as openclaw on the gateway's behalf, so an agent has no process of ours to hijack."""
import asyncio
import json

from fastapi import HTTPException

from . import config


class HelperError(HTTPException):
    pass


def _error(code: int, stderr: bytes) -> HelperError:
    try:
        e = json.loads(stderr.decode(errors="replace"))
        return HelperError(int(e.get("code", 500)), str(e.get("error", "helper failed")))
    except (ValueError, AttributeError):
        return HelperError(502, (stderr.decode(errors="replace").strip() or f"helper exit {code}")[:200])


async def run(prefix: list[str], args: list[str], stdin: bytes | None = None, timeout: float = 20) -> bytes:
    p = await asyncio.create_subprocess_exec(*prefix, *[str(a) for a in args], stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
                                             stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        out, err = await asyncio.wait_for(p.communicate(stdin), timeout)
    except asyncio.TimeoutError:
        p.kill()
        raise HelperError(504, "helper timed out")
    if p.returncode != 0:
        raise _error(p.returncode, err)
    return out


async def openclaw(*args, stdin: bytes | None = None, timeout: float = 20) -> bytes:
    return await run(config.AS_OPENCLAW, list(args), stdin, timeout)


async def openclaw_json(*args, stdin: bytes | None = None, timeout: float = 20):
    return json.loads(await openclaw(*args, stdin=stdin, timeout=timeout) or b"null")


async def root(*args, timeout: float = 90) -> bytes:
    return await run(config.AS_ROOT, list(args), None, timeout)


async def stream_read(root_name: str, path: str, maxbytes: int):
    """Starts `read`, returns (process, first chunk). The caller streams the rest of stdout."""
    p = await asyncio.create_subprocess_exec(*config.AS_OPENCLAW, "read", root_name, path, str(maxbytes),
                                             stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    first = await p.stdout.read(1 << 16)
    if not first:
        await p.wait()
        if p.returncode != 0:
            raise _error(p.returncode, await p.stderr.read())
    return p, first


async def stream_write(root_name: str, path: str, chunks, limit: int) -> bytes:
    p = await asyncio.create_subprocess_exec(*config.AS_OPENCLAW, "write", root_name, path,
                                             stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    n = 0
    try:
        async for c in chunks:
            n += len(c)
            if n > limit:
                p.kill()
                raise HelperError(413, "file too large")
            p.stdin.write(c)
            await p.stdin.drain()
        p.stdin.close()
    except (BrokenPipeError, ConnectionResetError):
        pass
    out, err = await p.communicate()
    if p.returncode != 0:
        raise _error(p.returncode, err)
    return out
