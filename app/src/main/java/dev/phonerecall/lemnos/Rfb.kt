package dev.phonerecall.lemnos

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.Inflater

/** Where decoded pixels go. ARGB ints, opaque. The Android one is a Bitmap ([DesktopView]); tests use an IntArray. */
interface Framebuffer {
    fun resize(w: Int, h: Int)
    fun put(x: Int, y: Int, w: Int, h: Int, px: IntArray)
    fun fill(x: Int, y: Int, w: Int, h: Int, argb: Int)
    fun copy(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int)
    /** One FramebufferUpdate is complete: redraw. */
    fun frame() {}
}

/** Bytes from the WebSocket, read by the protocol thread as a stream. [close] ends it (EOF). */
class ByteQueue : InputStream() {
    private val q = LinkedBlockingQueue<ByteArray>()
    private var cur = ByteArray(0)
    private var pos = 0
    @Volatile private var closed = false
    private val end = ByteArray(0)

    fun offer(b: ByteArray) { if (b.isNotEmpty() && !closed) q.put(b) }
    override fun close() { closed = true; q.put(end) }

    private fun next(): Boolean {
        while (pos >= cur.size) {
            val b = q.take()
            if (b === end) { q.put(end); return false }
            cur = b; pos = 0
        }
        return true
    }

    override fun read(): Int = if (next()) cur[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!next()) return -1
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }
}

/**
 * A small RFB 3.8 client (RFC 6143), enough for TigerVNC's Xvnc on Lemnos: security None (the socket is reachable
 * only through the gateway's mTLS + device checks), 32-bit true colour, Tight (with JPEG), CopyRect and Raw, and the
 * DesktopSize / ExtendedDesktopSize pseudo-encodings. [run] blocks on its own thread until the stream ends.
 * [jpeg] decodes one JPEG into ARGB (BitmapFactory on the phone).
 */
class RfbClient(
    input: InputStream,
    private val send: (ByteArray) -> Unit,
    private val fb: Framebuffer,
    private val jpeg: (ByteArray) -> IntArray?,
    private val onClipboard: (String) -> Unit = {},
    private val onReady: (name: String, w: Int, h: Int) -> Unit = { _, _, _ -> },
) {
    private val inp = DataInputStream(input)
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var name = ""; private set
    @Volatile private var extendedSize = false
    private val zs = Array(4) { Inflater() }
    private var screenId = 0L

    fun run() {
        try {
            handshake()
            while (true) message()
        } finally {
            zs.forEach { it.end() }
        }
    }

    private fun u8() = inp.readUnsignedByte()
    private fun u16() = inp.readUnsignedShort()
    private fun u32() = inp.readInt().toLong() and 0xFFFFFFFFL
    private fun s32() = inp.readInt()
    private fun bytes(n: Int) = ByteArray(n).also { inp.readFully(it) }
    private fun skip(n: Int) { var k = n; while (k > 0) { val s = inp.skipBytes(k); if (s <= 0) { inp.readByte(); k-- } else k -= s } }

    private fun handshake() {
        val ver = String(bytes(12), Charsets.US_ASCII)
        if (!ver.startsWith("RFB 003.")) throw RfbException("not a VNC server")
        send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        val n = u8()
        if (n == 0) throw RfbException(String(bytes(u32().toInt()), Charsets.UTF_8))
        val types = bytes(n)
        if (1.toByte() !in types) throw RfbException("the desktop asks for a password type Zeus doesn't speak")
        send(byteArrayOf(1))
        if (u32() != 0L) throw RfbException(runCatching { String(bytes(u32().toInt()), Charsets.UTF_8) }.getOrDefault("refused"))
        send(byteArrayOf(1))                                  // ClientInit: shared
        width = u16(); height = u16()
        skip(16)                                              // the server's pixel format; ours replaces it
        name = String(bytes(u32().toInt()), Charsets.UTF_8)
        fb.resize(width, height)
        send(pixelFormat())
        send(encodings(intArrayOf(TIGHT, COPYRECT, RAW, EXT_DESKTOP_SIZE, DESKTOP_SIZE, LAST_RECT, JPEG_QUALITY_6, COMPRESS_2)))
        onReady(name, width, height)
        request(false)
    }

    private fun message() {
        when (val t = u8()) {
            0 -> update()
            1 -> { skip(1); u16(); skip(u16() * 6) }          // colour map: unused in true colour
            2 -> Unit                                         // bell
            3 -> { skip(3); val n = u32().toInt(); if (n in 0..(1 shl 20)) onClipboard(String(bytes(n), Charsets.ISO_8859_1)) else throw RfbException("clipboard too big") }
            else -> throw RfbException("unknown message $t")
        }
    }

    private fun update() {
        skip(1)
        val n = u16()
        for (i in 0 until n) {
            val x = u16(); val y = u16(); val w = u16(); val h = u16(); val enc = s32()
            when (enc) {
                RAW -> raw(x, y, w, h)
                COPYRECT -> { val sx = u16(); val sy = u16(); fb.copy(sx, sy, x, y, w, h) }
                TIGHT -> tight(x, y, w, h)
                DESKTOP_SIZE -> { width = w; height = h; fb.resize(w, h) }
                EXT_DESKTOP_SIZE -> {
                    extendedSize = true
                    val screens = u8(); skip(3)
                    for (s in 0 until screens) { val id = u32(); skip(12); if (s == 0) screenId = id }
                    if (y == 0 && (w != width || h != height)) { width = w; height = h; fb.resize(w, h) }
                }
                LAST_RECT -> break
                else -> throw RfbException("unexpected encoding $enc")
            }
        }
        fb.frame()
        request(true)
    }

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        val b = bytes(w * h * 4)
        val px = IntArray(w * h)
        for (i in px.indices) {             // little-endian, blue in the low byte: B G R X
            val o = i * 4
            px[i] = OPAQUE or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
        }
        fb.put(x, y, w, h, px)
    }

    private fun compactLength(): Int {
        var b = u8(); var len = b and 0x7F
        if (b and 0x80 != 0) { b = u8(); len = len or ((b and 0x7F) shl 7); if (b and 0x80 != 0) len = len or (u8() shl 14) }
        return len
    }

    private fun tpixel(b: ByteArray, o: Int) = OPAQUE or ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    private fun tight(x: Int, y: Int, w: Int, h: Int) {
        val ctl = u8()
        for (i in 0..3) if (ctl and (1 shl i) != 0) zs[i].reset()
        when (val kind = ctl shr 4) {
            0x8 -> fb.fill(x, y, w, h, tpixel(bytes(3), 0))
            0x9 -> {
                val data = bytes(compactLength())
                val px = jpeg(data) ?: throw RfbException("bad JPEG")
                if (px.size >= w * h) fb.put(x, y, w, h, px)
            }
            else -> {
                if (kind > 0x7) throw RfbException("unknown Tight type $kind")
                val stream = kind and 0x3
                val filter = if (kind and 0x4 != 0) u8() else 0
                var palette = IntArray(0)
                val rowBytes: Int
                when (filter) {
                    0, 2 -> rowBytes = w * 3
                    1 -> {
                        val nc = u8() + 1
                        val p = bytes(nc * 3)
                        palette = IntArray(nc) { tpixel(p, it * 3) }
                        rowBytes = if (nc == 2) (w + 7) / 8 else w
                    }
                    else -> throw RfbException("unknown Tight filter $filter")
                }
                val size = rowBytes * h
                val data = if (size < 12) bytes(size) else inflate(stream, bytes(compactLength()), size)
                val px = IntArray(w * h)
                when (filter) {
                    0 -> for (i in px.indices) px[i] = tpixel(data, i * 3)
                    1 -> if (palette.size == 2) {
                        for (r in 0 until h) for (c in 0 until w) {
                            val bit = (data[r * rowBytes + c / 8].toInt() shr (7 - c % 8)) and 1
                            px[r * w + c] = palette[bit]
                        }
                    } else for (i in px.indices) px[i] = palette[(data[i].toInt() and 0xFF).coerceAtMost(palette.size - 1)]
                    2 -> gradient(data, w, h, px)
                }
                fb.put(x, y, w, h, px)
            }
        }
    }

    private fun gradient(d: ByteArray, w: Int, h: Int, px: IntArray) {
        val prev = IntArray(w * 3); val cur = IntArray(w * 3)
        for (r in 0 until h) {
            for (c in 0 until w) for (k in 0..2) {
                val left = if (c > 0) cur[(c - 1) * 3 + k] else 0
                val up = prev[c * 3 + k]
                val upLeft = if (c > 0) prev[(c - 1) * 3 + k] else 0
                val pred = (left + up - upLeft).coerceIn(0, 255)
                cur[c * 3 + k] = (pred + (d[(r * w + c) * 3 + k].toInt() and 0xFF)) and 0xFF
            }
            for (c in 0 until w) px[r * w + c] = OPAQUE or (cur[c * 3] shl 16) or (cur[c * 3 + 1] shl 8) or cur[c * 3 + 2]
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
    }

    private fun inflate(stream: Int, z: ByteArray, size: Int): ByteArray {
        val inf = zs[stream]
        inf.setInput(z)
        val out = ByteArray(size)
        var n = 0
        while (n < size) {
            val k = inf.inflate(out, n, size - n)
            if (k == 0 && (inf.needsInput() || inf.finished())) throw RfbException("short Tight data")
            n += k
        }
        return out
    }

    // ---- what the phone sends ----

    fun request(incremental: Boolean) = send(byteArrayOf(3, if (incremental) 1 else 0) + u16b(0) + u16b(0) + u16b(width) + u16b(height))

    /** [buttons]: bit 0 left, 1 middle, 2 right, 3/4 wheel up/down, 5/6 wheel left/right. */
    fun pointer(x: Int, y: Int, buttons: Int) =
        send(byteArrayOf(5, buttons.toByte()) + u16b(x.coerceIn(0, maxOf(0, width - 1))) + u16b(y.coerceIn(0, maxOf(0, height - 1))))

    fun key(keysym: Int, down: Boolean) = send(byteArrayOf(4, if (down) 1 else 0, 0, 0) + u32b(keysym))

    /** RFB's clipboard is Latin-1; anything else becomes '?'. */
    fun clipboard(text: String) {
        val b = text.map { if (it.code < 256) it.code.toByte() else '?'.code.toByte() }.toByteArray()
        send(byteArrayOf(6, 0, 0, 0) + u32b(b.size) + b)
    }

    /** Ask Xvnc to change its size (it supports this through RandR). No-op on servers without it. */
    fun resizeDesktop(w: Int, h: Int): Boolean {
        if (!extendedSize) return false
        send(byteArrayOf(251.toByte(), 0) + u16b(w) + u16b(h) + byteArrayOf(1, 0) + u32b(screenId.toInt()) + u16b(0) + u16b(0) + u16b(w) + u16b(h) + u32b(0))
        return true
    }

    companion object {
        const val RAW = 0
        const val COPYRECT = 1
        const val TIGHT = 7
        const val DESKTOP_SIZE = -223
        const val LAST_RECT = -224
        const val EXT_DESKTOP_SIZE = -308
        const val JPEG_QUALITY_6 = -26
        const val COMPRESS_2 = -254
        const val OPAQUE = 0xFF shl 24

        fun u16b(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32b(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        /** 32 bpp, depth 24, little-endian, true colour, red at bit 16: ARGB ints without shuffling. */
        fun pixelFormat() = byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1) + u16b(255) + u16b(255) + u16b(255) + byteArrayOf(16, 8, 0, 0, 0, 0)

        fun encodings(e: IntArray) = byteArrayOf(2, 0) + u16b(e.size) + e.fold(ByteArray(0)) { a, v -> a + u32b(v) }
    }
}

class RfbException(message: String) : EOFException(message)

/** X keysyms for what Android sends. Printable text maps by code point. */
object Keysym {
    const val BACKSPACE = 0xff08; const val TAB = 0xff09; const val RETURN = 0xff0d; const val ESCAPE = 0xff1b; const val DELETE = 0xffff
    const val HOME = 0xff50; const val LEFT = 0xff51; const val UP = 0xff52; const val RIGHT = 0xff53; const val DOWN = 0xff54
    const val PAGE_UP = 0xff55; const val PAGE_DOWN = 0xff56; const val END = 0xff57; const val INSERT = 0xff63
    const val SHIFT_L = 0xffe1; const val SHIFT_R = 0xffe2; const val CONTROL_L = 0xffe3; const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5; const val ALT_L = 0xffe9; const val ALT_R = 0xffea; const val SUPER_L = 0xffeb; const val MENU = 0xff67
    fun f(n: Int) = 0xffbe + (n - 1)

    fun ofChar(c: Int): Int = when {
        c == '\n'.code || c == '\r'.code -> RETURN
        c == '\t'.code -> TAB
        c == 8 -> BACKSPACE
        c in 0x20..0x7e || c in 0xa0..0xff -> c
        else -> 0x01000000 or c
    }

    /** Android key codes that aren't text. Null: use the key's character instead. */
    fun ofKeyCode(k: Int): Int? = when (k) {
        android.view.KeyEvent.KEYCODE_DEL -> BACKSPACE
        android.view.KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        android.view.KeyEvent.KEYCODE_TAB -> TAB
        android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        android.view.KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> HOME
        android.view.KeyEvent.KEYCODE_MOVE_END -> END
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        android.view.KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        android.view.KeyEvent.KEYCODE_INSERT -> INSERT
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_L
        android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        android.view.KeyEvent.KEYCODE_CTRL_LEFT -> CONTROL_L
        android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_R
        android.view.KeyEvent.KEYCODE_ALT_LEFT -> ALT_L
        android.view.KeyEvent.KEYCODE_ALT_RIGHT -> ALT_R
        android.view.KeyEvent.KEYCODE_META_LEFT, android.view.KeyEvent.KEYCODE_META_RIGHT -> SUPER_L
        android.view.KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        android.view.KeyEvent.KEYCODE_MENU -> MENU
        in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 -> f(k - android.view.KeyEvent.KEYCODE_F1 + 1)
        else -> null
    }
}
