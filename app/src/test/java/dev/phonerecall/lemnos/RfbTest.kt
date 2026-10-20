package dev.phonerecall.lemnos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.zip.Deflater

class RfbTest {
    private class Fb : Framebuffer {
        var w = 0; var h = 0; var px = IntArray(0); var frames = 0
        override fun resize(w: Int, h: Int) { this.w = w; this.h = h; px = IntArray(w * h) }
        override fun put(x: Int, y: Int, w: Int, h: Int, p: IntArray) { for (r in 0 until h) System.arraycopy(p, r * w, px, (y + r) * this.w + x, w) }
        override fun fill(x: Int, y: Int, w: Int, h: Int, argb: Int) { for (r in 0 until h) for (c in 0 until w) px[(y + r) * this.w + x + c] = argb }
        override fun copy(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
            val t = IntArray(w * h); for (r in 0 until h) System.arraycopy(px, (sy + r) * this.w + sx, t, r * w, w); put(dx, dy, w, h, t)
        }
        override fun frame() { frames++ }
    }

    private fun u16(v: Int) = RfbClient.u16b(v)
    private fun u32(v: Int) = RfbClient.u32b(v)
    private fun rect(x: Int, y: Int, w: Int, h: Int, enc: Int) = u16(x) + u16(y) + u16(w) + u16(h) + u32(enc)

    private val serverInit = u16(4) + u16(2) + ByteArray(16) + u32(6) + "Lemnos".toByteArray()

    private fun run(server: ByteArray, jpeg: (ByteArray) -> IntArray? = { null }): Pair<Fb, ByteArray> {
        val fb = Fb(); val sent = ByteArrayOutputStream()
        try { RfbClient(ByteArrayInputStream(server), { sent.write(it) }, fb, jpeg).run() } catch (_: EOFException) {}
        return fb to sent.toByteArray()
    }

    private val hello = "RFB 003.008\n".toByteArray() + byteArrayOf(1, 1) + u32(0) + serverInit

    @Test fun handshakeSpeaks38NoneSharedAndAsksForTight() {
        val (fb, sent) = run(hello)
        assertEquals(4, fb.w); assertEquals(2, fb.h)
        val expect = "RFB 003.008\n".toByteArray() + byteArrayOf(1) + byteArrayOf(1) + RfbClient.pixelFormat()
        assertArrayEquals(expect, sent.copyOf(expect.size))
        val enc = sent.copyOfRange(expect.size, expect.size + 4)
        assertArrayEquals(byteArrayOf(2, 0, 0, 8), enc)
        assertEquals(RfbClient.TIGHT, java.nio.ByteBuffer.wrap(sent, expect.size + 4, 4).int)
        assertEquals(3, sent[sent.size - 10].toInt())        // then a full FramebufferUpdateRequest
    }

    @Test fun refusesServersThatWantAPassword() {
        val fb = Fb()
        val e = runCatching { RfbClient(ByteArrayInputStream("RFB 003.008\n".toByteArray() + byteArrayOf(1, 2)), {}, fb, { null }).run() }.exceptionOrNull()
        assertTrue(e is RfbException)
    }

    @Test fun decodesRawFillPaletteCopyRectAndZlibTight() {
        val o = 0xFF shl 24
        val zlib = Deflater().run {
            val raw = ByteArray(4 * 3) { i -> if (i % 3 == 0) 0x10 else 0x20 }    // 4 pixels, RGB 10 20 20
            setInput(raw); finish(); val out = ByteArray(64); val n = deflate(out, 0, out.size, Deflater.SYNC_FLUSH); out.copyOf(n)
        }
        val update = byteArrayOf(0, 0) + u16(5) +
            rect(0, 0, 1, 1, RfbClient.RAW) + byteArrayOf(0x33, 0x22, 0x11, 0) +                          // B G R X
            rect(1, 0, 3, 1, RfbClient.TIGHT) + byteArrayOf(0x80.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()) +  // fill
            rect(0, 1, 4, 1, RfbClient.TIGHT) + byteArrayOf(0x40, 1, 1, 1, 2, 3, 4, 5, 6) + byteArrayOf(0b1010_0000.toByte()) +  // 2-colour palette
            rect(3, 1, 1, 1, RfbClient.COPYRECT) + u16(0) + u16(0) +
            rect(0, 0, 4, 1, RfbClient.TIGHT) + byteArrayOf(0x01) + byteArrayOf(zlib.size.toByte()) + zlib  // stream 0, copy filter, zlib
        val (fb, _) = run(hello + update)
        assertEquals(1, fb.frames)
        assertEquals(o or 0x102020, fb.px[0])
        assertEquals(o or 0x102020, fb.px[3])                     // the zlib row overwrote row 0
        assertEquals(o or 0x040506, fb.px[4])                     // palette bit 1
        assertEquals(o or 0x010203, fb.px[5])                     // palette bit 0
        assertEquals(o or 0x040506, fb.px[6])
        assertEquals(o or 0x112233, fb.px[7])                     // copied from (0,0) before the zlib rect
    }

    @Test fun jpegRectsGoThroughTheDecoder() {
        val update = byteArrayOf(0, 0) + u16(1) + rect(0, 0, 2, 1, RfbClient.TIGHT) + byteArrayOf(0x90.toByte(), 3, 9, 9, 9)
        var seen = 0
        val (fb, _) = run(hello + update) { b -> seen = b.size; intArrayOf(7, 8) }
        assertEquals(3, seen)
        assertEquals(7, fb.px[0]); assertEquals(8, fb.px[1])
    }

    @Test fun desktopSizeResizesAndClipboardArrives() {
        var clip = ""
        val msgs = byteArrayOf(0, 0) + u16(1) + rect(0, 0, 8, 6, RfbClient.DESKTOP_SIZE) + byteArrayOf(3, 0, 0, 0) + u32(5) + "hello".toByteArray()
        val fb = Fb()
        try { RfbClient(ByteArrayInputStream(hello + msgs), {}, fb, { null }, { clip = it }).run() } catch (_: EOFException) {}
        assertEquals(8, fb.w); assertEquals(6, fb.h); assertEquals("hello", clip)
    }

    @Test fun pointerAndKeyMessages() {
        val sent = ByteArrayOutputStream()
        val c = RfbClient(ByteArrayInputStream(hello), { sent.write(it) }, Fb(), { null })
        runCatching { c.run() }
        sent.reset()
        c.pointer(9, 9, 1)                                        // clamped to 3,1
        assertArrayEquals(byteArrayOf(5, 1, 0, 3, 0, 1), sent.toByteArray())
        sent.reset()
        c.key(Keysym.RETURN, true)
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0x0d), sent.toByteArray())
    }

    @Test fun keysyms() {
        assertEquals('a'.code, Keysym.ofChar('a'.code))
        assertEquals(0xe9, Keysym.ofChar('é'.code))
        assertEquals(0x01000000 or 0x20AC, Keysym.ofChar('€'.code))
        assertEquals(Keysym.RETURN, Keysym.ofChar('\n'.code))
        assertEquals(0xffbe, Keysym.f(1)); assertEquals(0xffc9, Keysym.f(12))
    }

    @Test fun byteQueueReadsAcrossChunksThenEnds() {
        val q = ByteQueue()
        q.offer(byteArrayOf(1, 2)); q.offer(byteArrayOf(3)); q.close()
        val b = ByteArray(3)
        java.io.DataInputStream(q).readFully(b)
        assertArrayEquals(byteArrayOf(1, 2, 3), b)
        assertEquals(-1, q.read())
    }

    /** A stream recorded from Lemnos's Xvnc (set ZEUS_RFB_REPLAY to its path): decodes to the end, saved as a PNG to look at. */
    @Test fun replaysARecordedXvncStream() {
        val path = System.getenv("ZEUS_RFB_REPLAY") ?: return
        val fb = Fb()
        val jpeg: (ByteArray) -> IntArray? = { b ->
            javax.imageio.ImageIO.read(ByteArrayInputStream(b))?.let { img -> img.getRGB(0, 0, img.width, img.height, null, 0, img.width).map { it or (0xFF shl 24) }.toIntArray() }
        }
        val e = runCatching { RfbClient(java.io.File(path).inputStream().buffered(), {}, fb, jpeg).run() }.exceptionOrNull()
        assertTrue("ended with $e", e is EOFException && e !is RfbException)
        assertTrue(fb.frames > 0)
        val img = java.awt.image.BufferedImage(fb.w, fb.h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, fb.w, fb.h, fb.px, 0, fb.w)
        java.io.File("build/shots").mkdirs()
        javax.imageio.ImageIO.write(img, "png", java.io.File("build/shots/rfb-replay.png"))
        println("replayed ${fb.frames} updates into ${fb.w}x${fb.h}")
    }
}
