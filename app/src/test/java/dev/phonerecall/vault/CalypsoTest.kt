package dev.phonerecall.vault

import com.anthropic.core.jsonMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalypsoTest {
    private val secret = "hcloud-SECRET-7f3a9c"

    private fun ByteArray.has(needle: ByteArray) = (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Test fun `a depositor gets an id and nothing that reads`() {
        val r = VaultRig()
        val id = r.calypso.drop.deposit(Agent.ATLAS, ItemKind.SECRET, "Hetzner token", secret.toByteArray(), null)
        assertTrue(id.matches(Regex("[0-9a-f]{24}")))
        // The drop is a bare lambda: there is no Calypso behind it to cast to, and its only method is deposit.
        assertFalse(r.calypso.drop is Calypso)
        assertEquals(listOf("deposit"), CalypsoDrop::class.java.declaredMethods.map { it.name }.filter { !it.contains('$') })
        // Nothing on disk is plaintext: not the secret, not even the label.
        val raw = r.db.rawCalypso()
        assertTrue(raw.none { it.has(secret.toByteArray()) || it.has("Hetzner token".toByteArray()) })
        assertEquals("deposited", r.calypso.log().single().action)
        assertEquals("Atlas", r.calypso.log().single().who)
    }

    @Test fun `the public key a depositor holds can't open its own deposit`() {
        val r = VaultRig()
        val id = r.calypso.drop.deposit(Agent.IRIS, ItemKind.NOTE, "Passport", "renew by March".toByteArray(), null)
        val e = r.db.envelope(id)!!
        try {
            Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, r.keys.publicKey(), Box.OAEP) }.doFinal(e.wk)
            fail("the public key unwrapped it")
        } catch (_: Exception) { }
        // And the unwrapper itself refuses until a prompt authorised it.
        try { Box.open(e, r.keys.unwrapper()); fail("opened without a fingerprint") } catch (_: IllegalStateException) { }
    }

    @Test fun `opening needs his fingerprint, bound to that item`() {
        val r = VaultRig()
        val id = r.calypso.drop.deposit(Agent.ATHENA, ItemKind.SECRET, "Spotify refresh", secret.toByteArray(), null)
        r.bio.succeed = false
        var opened: Opened? = null
        var failed: String? = null
        r.calypso.open(id, r.bio, { _, o -> opened = o }, { failed = it })
        assertNull(opened)
        assertNotNull(failed)
        assertFalse(r.keys.made.single().authorized)
        assertTrue(r.calypso.log().none { it.action == "opened" })

        r.bio.succeed = true
        r.calypso.open(id, r.bio, { _, o -> opened = o }, { failed = it })
        assertEquals(secret, opened!!.text)
        assertEquals("The owner opened", r.calypso.log().first().let { "${it.who} ${it.action}" })
        assertEquals(2, r.bio.prompts.size)
    }

    @Test fun `files keep their name inside the seal`() {
        val r = VaultRig()
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1)
        val id = r.calypso.drop.deposit(Agent.OWNER, ItemKind.FILE, "ID scan", bytes, "license.png")
        var o: Opened? = null
        r.calypso.open(id, r.bio, { _, x -> o = x }, { fail(it) })
        assertEquals("license.png", o!!.name)
        assertArrayEquals(bytes, o!!.data)
    }

    @Test fun `a swapped label or sender breaks the seal`() {
        val r = VaultRig()
        val e = Box.seal(r.keys.publicKey(), ItemKind.NOTE, "Passport", "Iris", "renew".toByteArray())
        val forged = Envelope(e.id, e.kind, "Something else", e.from, e.at, e.wk, e.iv, e.ct)
        val u = r.keys.unwrapper() as FakeUnwrapper
        u.authorized = true
        try { Box.open(forged, u); fail("a swapped label opened") } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun `drops sealed on the VPS open on the phone`() {
        val v = jsonMapper().readTree(File(System.getProperty("user.dir"), "../server/tests/calypso_vector.json").readText())
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(v["pkcs8_b64"].asText())))
        val r = VaultRig()
        val u = FakeUnwrapper(priv).apply { authorized = true }
        v["envelopes"].forEachIndexed { i, n ->
            val e = Envelope.parse(n.toString())!!
            assertTrue(r.calypso.accept(e))
            assertFalse("duplicates are ignored", r.calypso.accept(e))
            val o = Box.open(r.db.envelope(e.id)!!, u)
            val want = if (i == 0) v["plain"][0].asText().toByteArray() else Base64.getDecoder().decode(v["plain"][1].asText())
            assertArrayEquals(want, o.data)
            assertEquals(v["names"][i].takeIf { !it.isNull }?.asText(), o.name)
        }
        assertEquals(listOf("Iris", "Atlas"), r.calypso.log().map { it.who })
        assertTrue(r.calypso.log().all { it.why == "through Lemnos" })
        assertNull(Envelope.parse("""{"v":1,"id":"../x","kind":"secret","label":"a","from":"x","at":1,"wk":"","iv":"","ct":""}"""))
    }

    @Test fun `move to Aegis and delete need his fingerprint`() {
        val r = VaultRig()
        val id = r.calypso.drop.deposit(Agent.IRIS, ItemKind.SECRET, "Delivery address", "12 Elm St".toByteArray(), null)
        r.bio.succeed = false
        r.calypso.moveToAegis(id, r.aegis, "delivery_address", AegisScope.AGENTS, r.bio, { fail("moved without a fingerprint") }, {})
        assertEquals(1, r.calypso.items().size)
        assertTrue(r.aegis.items().isEmpty())

        r.bio.succeed = true
        var done = false
        r.calypso.moveToAegis(id, r.aegis, "delivery_address", AegisScope.AGENTS, r.bio, { done = true }, { fail(it) })
        assertTrue(done)
        assertTrue(r.calypso.items().isEmpty())
        assertEquals(AegisScope.AGENTS, r.aegis.items().single().scope)
        assertTrue(r.calypso.log().any { it.action == "moved to Aegis" })

        val other = r.calypso.drop.deposit(Agent.ZEUS, ItemKind.NOTE, "Old note", "x".toByteArray(), null)
        r.bio.succeed = false
        r.calypso.delete(other, r.bio, { fail("deleted without a fingerprint") }, {})
        assertEquals(1, r.calypso.items().size)
        r.bio.succeed = true
        r.calypso.delete(other, r.bio, {}, { fail(it) })
        assertTrue(r.calypso.items().isEmpty())
    }
}
