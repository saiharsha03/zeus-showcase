package dev.phonerecall.brain

import dev.phonerecall.odyssey.JceSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The Keystore stands in as a JVM AES key that counts its calls: one unwrap per key, not one call per value. */
class CountingWrap(private val inner: Sealer = JceSealer()) : Sealer by inner {
    var opens = 0
    override fun openBytes(sealed: ByteArray): ByteArray? { opens++; return inner.openBytes(sealed) }
}

class EnvelopeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val wrap = CountingWrap()
    private fun keys() = File(tmp.root, "zeus.aegis.senses.keys")

    @Test fun roundTripsWithOneUnwrapForManyValues() {
        val e = Envelope(wrap, keys())
        val sealed = (1..500).map { e.seal("Person $it".toByteArray()) }
        val fresh = Envelope(wrap, keys())
        sealed.forEachIndexed { i, s -> assertEquals("Person ${i + 1}", String(fresh.open(s)!!)) }
        assertEquals(1, wrap.opens)
        assertEquals(Envelope.MAGIC, sealed[0][0])
    }

    @Test fun nothingPlainOnDiskAndAnotherPhonesKeyOpensNothing() {
        val e = Envelope(wrap, keys())
        val s = e.seal("Amma".toByteArray())
        assertFalse(String(s, Charsets.ISO_8859_1).contains("Amma"))
        // The key file holds only the wrapped key: without this Keystore key it opens nothing.
        val other = Envelope(JceSealer(), keys())
        assertNull(other.open(s))
        assertFalse(keys().readText().contains("Amma"))
    }

    @Test fun oldKeystoreDirectValuesAreLeftToTheKeystore() {
        val e = Envelope(wrap, keys())
        val old = wrap.sealBytes("old".toByteArray())
        if (old[0] != Envelope.MAGIC) assertNull(e.open(old))
        // An old value whose random IV happens to start like a header still isn't taken for an envelope.
        val lookalike = byteArrayOf(Envelope.MAGIC, 1) + old.copyOfRange(2, old.size)
        e.seal("make key 1".toByteArray())
        assertNull(e.open(lookalike))
    }

    @Test fun rotationKeepsOldValuesOpenAndMarksThemForRewrite() {
        val e = Envelope(wrap, keys())
        val v1 = e.seal("before".toByteArray())
        assertTrue(e.current(v1))
        assertEquals(2, e.rotate())
        val v2 = e.seal("after".toByteArray())
        assertEquals(2, v2[1].toInt())
        assertFalse(e.current(v1))
        assertTrue(e.current(v2))
        val reopened = Envelope(wrap, keys())
        assertEquals("before", String(reopened.open(v1)!!))
        assertEquals("after", String(reopened.open(v2)!!))
    }

    @Test fun forgetZeroesTheKeysAndTheNextUseUnwrapsAgain() {
        val e = Envelope(wrap, keys())
        val s = e.seal("x".toByteArray())
        e.forget()
        val before = wrap.opens
        assertArrayEquals("x".toByteArray(), e.open(s))
        assertEquals(before + 1, wrap.opens)
    }
}

/** Old Keystore-direct values in a store are rewritten once; a row changed meanwhile keeps its newer value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResealTest {
    /** "O:" is the old format, "N:" the current one; both open. */
    private class TwoFormats : Sealer {
        override fun seal(plain: String) = "N:$plain"
        override fun open(sealed: String) = sealed.substringAfter(':')
        override fun sealBytes(plain: ByteArray) = plain
        override fun openBytes(sealed: ByteArray) = sealed
        override fun current(sealed: String) = sealed.startsWith("N:")
    }

    @Test fun oldPeopleAndCardsAreResealedOnce() {
        val store = SenseStore(RuntimeEnvironment.getApplication(), TwoFormats(), name = null)
        store.savePerson(Person(People.keyOf("Amma"), "Amma", Relation.MOTHER))
        store.upsertCard(SenseCard("c1", "Title", "Body", "SENSES", 1L, Long.MAX_VALUE))
        val db = store.writableDatabase
        db.execSQL("UPDATE people SET name = 'O:Amma'")
        db.execSQL("UPDATE cards SET title = 'O:Title'")
        assertEquals(2, store.reseal())
        assertEquals("N:Amma", db.rawQuery("SELECT name FROM people", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals("Amma", store.people().single().name)
        assertEquals(0, store.reseal())
    }
}
