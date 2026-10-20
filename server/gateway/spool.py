"""Reading a drop from a spool other users can write into. One open, no symlinks, regular files only: the owner
and the bytes come from the same file descriptor, so a drop can't be swapped or pointed elsewhere between them."""
import os
import stat


def read_drop(path, limit: int) -> tuple[os.stat_result, bytes]:
    """(fstat, at most `limit` bytes). OSError for a symlink, FIFO, device, directory or anything else not a plain file."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0))
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise OSError("not a regular file")
        out = bytearray()
        while len(out) < limit and (chunk := os.read(fd, min(65536, limit - len(out)))):
            out += chunk
        return st, bytes(out)
    finally:
        os.close(fd)
