package dev.phonerecall.mnemosyne

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

class PhraseTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun bip39Vectors() {
        // Trezor's reference vectors (github.com/trezor/python-mnemonic vectors.json), 256-bit entropy.
        assertEquals((List(23) { "abandon" } + "art").joinToString(" "), Phrase.encode(ByteArray(32)).joinToString(" "))
        assertEquals((List(23) { "zoo" } + "vote").joinToString(" "), Phrase.encode(ByteArray(32) { -1 }).joinToString(" "))
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title",
            Phrase.encode(ByteArray(32) { 0x7f }).joinToString(" "),
        )
        assertEquals(
            "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
            Phrase.encode(hex("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c")).joinToString(" "),
        )
    }

    @Test fun roundTripAndForgivingInput() {
        repeat(50) {
            val e = Phrase.newEntropy()
            val w = Phrase.encode(e)
            assertArrayEquals(e, Phrase.decode(w.joinToString(" ")))
            assertArrayEquals(e, Phrase.decode(w.joinToString("\n") { it.uppercase() }))
            assertArrayEquals(e, Phrase.decode(w.withIndex().joinToString(", ") { (i, x) -> "${i + 1}. ${x.take(4)}" }))   // numbers and 4-letter prefixes
        }
    }

    @Test fun badPhrasesAreCaughtBeforeAnyDownload() {
        val w = Phrase.encode(ByteArray(32) { (it * 7 + 3).toByte() }).toMutableList()
        assertThrows(Phrase.Invalid::class.java) { Phrase.decode(w.dropLast(1)) }
        assertThrows(Phrase.Invalid::class.java) { Phrase.decode(w.take(23) + "zzzz") }
        val swapped = w.toMutableList().also { val t = it[3]; it[3] = it[9]; it[9] = t }
        assertThrows(Phrase.Invalid::class.java) { Phrase.decode(swapped) }
        val wrong = w.toMutableList().also { it[23] = if (it[23] == "zoo") "abandon" else "zoo" }
        assertThrows(Phrase.Invalid::class.java) { Phrase.decode(wrong) }
        assertEquals(3, Phrase.checkPositions().toSet().size)
    }
}

class KeysTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hkdfMatchesRfc5869() {
        val okm = Keys.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun keysAreStableAndSeparate() {
        val e = Phrase.newEntropy()
        assertArrayEquals(Keys.backupKey(e), Keys.backupKey(e.copyOf()))
        assertNotEquals(Keys.backupKey(e).toList(), Keys.escrowKey(e).toList())
        assertNotEquals(Keys.backupKey(e).toList(), Keys.backupKey(Phrase.newEntropy()).toList())
        assertEquals(16, Keys.check(Keys.backupKey(e)).length)
    }
}

class ZbkTest {
    private val key = Keys.backupKey(Phrase.newEntropy())

    private fun seal(plain: ByteArray, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        Zbk.writer(out, k, "20261020T030000Z-0badf00d", 1_792_000_000_000L).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(b: ByteArray, k: ByteArray = key) = Zbk.reader(ByteArrayInputStream(b), k).second.readBytes()

    @Test fun roundTripsAtChunkEdges() {
        val r = SecureRandom()
        for (n in listOf(0, 1, 1000, Zbk.CHUNK - 1, Zbk.CHUNK, Zbk.CHUNK + 1, 3 * Zbk.CHUNK + 17)) {
            val p = ByteArray(n).also { r.nextBytes(it) }
            assertArrayEquals("n=$n", p, open(seal(p)))
        }
        // A fresh salt per backup: the same plaintext never gives the same file.
        val p = "same".toByteArray()
        assertNotEquals(seal(p).toList(), seal(p).toList())
    }

    @Test fun headerCarriesIdAndTime() {
        val h = Zbk.readHeader(DataInputStream(ByteArrayInputStream(seal(byteArrayOf(1)))))
        assertEquals("20261020T030000Z-0badf00d", h.id)
        assertEquals(1_792_000_000_000L, h.created)
    }

    @Test fun anyChangeIsCaught() {
        val p = ByteArray(2 * Zbk.CHUNK + 500) { (it % 251).toByte() }
        val good = seal(p)
        val hlen = 8 + ByteBuffer.wrap(good, 4, 4).int
        // a flipped byte in a chunk
        for (at in listOf(hlen + 10, hlen + Zbk.CHUNK + 30, good.size - 3)) {
            val bad = good.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertThrows(Zbk.Tampered::class.java) { open(bad) }
        }
        // cut short, at a chunk boundary (the last chunk dropped) and mid-chunk
        val firstTwo = hlen + 2 * (4 + Zbk.CHUNK + 16)
        assertThrows(Zbk.Tampered::class.java) { open(good.copyOf(firstTwo)) }
        assertThrows(Zbk.Tampered::class.java) { open(good.copyOf(good.size - 100)) }
        // extra bytes after the last chunk
        assertThrows(Zbk.Tampered::class.java) { open(good + byteArrayOf(0, 0, 0, 0)) }
        // two whole chunks swapped
        val c = 4 + Zbk.CHUNK + 16
        val swapped = good.copyOf().also {
            System.arraycopy(good, hlen + c, it, hlen, c); System.arraycopy(good, hlen, it, hlen + c, c)
        }
        assertThrows(Zbk.Tampered::class.java) { open(swapped) }
        // the header (the time, here) is bound into every chunk
        val s = String(good, 8, hlen - 8)
        val edited = s.replace("1792000000000", "1792000000001").toByteArray()
        assertEquals(edited.size, hlen - 8)
        val moved = good.copyOf().also { System.arraycopy(edited, 0, it, 8, edited.size) }
        assertThrows(Zbk.Tampered::class.java) { open(moved) }
        assertArrayEquals(p, open(good))
    }

    @Test fun wrongPhraseIsToldApartFromDamage() {
        val b = seal("hello".toByteArray())
        assertThrows(Zbk.WrongKey::class.java) { open(b, Keys.backupKey(Phrase.newEntropy())) }
        assertThrows(Zbk.Tampered::class.java) { open("PK\u0003\u0004 not ours".toByteArray()) }
    }

    @Test fun newerFormatIsRefusedPlainly() {
        val raw = """{"v":2,"id":"x","created":1,"salt":"AAAA","check":"00","chunk":1}""".toByteArray()
        val b = Zbk.MAGIC + ByteBuffer.allocate(4).putInt(raw.size).array() + raw
        val e = assertThrows(Zbk.Tampered::class.java) { open(b) }
        assertTrue(e.message!!.contains("newer"))
    }
}
