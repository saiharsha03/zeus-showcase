package dev.phonerecall.lemnos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The zt1 format matches the gateway's verifier byte for byte (server/tests/vectors.json, made by the server's tests). */
class ApprovalTest {
    @Suppress("UNCHECKED_CAST")
    private val v = MiniJson.parse(File(System.getProperty("user.dir"), "../server/tests/vectors.json").readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun obj(k: String) = v[k] as Map<String, Any?>

    @Test fun canonicalJsonMatchesPython() {
        assertEquals(v["payload_canonical"], Canonical.encode(obj("payload")))
        assertEquals(v["claims_canonical"], Canonical.encode(obj("claims")))
        assertEquals(v["payload_sha256"], ApprovalToken.payloadHash(obj("payload")))
    }

    @Test fun refusesFloatsAndOddKeys() {
        try { Canonical.encode(mapOf("x" to 1.5)); fail() } catch (_: IllegalArgumentException) { }
        try { Canonical.encode(mapOf(1 to "x")); fail() } catch (_: IllegalArgumentException) { }
        // Raw string: the \u001f below is six literal characters, as Python writes a control character.
        assertEquals("""{"a":"\u001f","b":"é😀"}""", Canonical.encode(mapOf("b" to "é😀", "a" to "\u001f")))
    }

    @Test fun claimsAndTokenMatchTheVector() {
        val c = obj("claims")
        val spki = Base64.getDecoder().decode(v["spki_b64"] as String)
        assertEquals(v["kid"], ApprovalToken.kid(spki))
        @Suppress("UNCHECKED_CAST")
        val req = ApprovalRequest(c["job_id"] as String, c["kind"] as String, c["executor"] as String, obj("payload"), (c["rev"] as Long).toInt())
        val claims = ApprovalToken.claims(req, "ed25519", ApprovalToken.kid(spki), c["nonce"] as String, c["iat"] as Long, (c["exp"] as Long) - (c["iat"] as Long))
        val bytes = ApprovalToken.claimsBytes(claims)
        assertEquals(v["claims_canonical"], String(bytes, Charsets.UTF_8))

        // Ed25519 is deterministic: the same seed and bytes give the server's exact token.
        val seed = (v["ed25519_seed_hex"] as String).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed))
        val sig = Signature.getInstance("Ed25519").apply { initSign(key); update(bytes) }.sign()
        assertEquals(v["token"], ApprovalToken.assemble(bytes, sig))

        // And the public key in the vector verifies it.
        val pub = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        assertTrue(Signature.getInstance("Ed25519").apply { initVerify(pub); update(bytes) }.verify(sig))
    }

    @Test fun es256FallbackSignsTheSameBytes() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val req = ApprovalRequest("job-1", "iris.task_done", "zeus-gateway", mapOf("task_id" to 7))
        val token = ApprovalToken.make(req, "es256", kp.public.encoded, now = 1_792_000_000) { b ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(b) }.sign()
        }
        val (prefix, body, sig) = token.split(".")
        assertEquals("zt1", prefix)
        val claims = Base64.getUrlDecoder().decode(body)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(claims) }.verify(Base64.getUrlDecoder().decode(sig)))
        val text = String(claims, Charsets.UTF_8)
        assertTrue(text, text.contains("\"alg\":\"es256\"") && text.contains("\"exp\":1792000600") && text.contains("\"executor\":\"zeus-gateway\""))
        assertFalse(token.contains("="))   // unpadded base64url
    }

    @Test fun agentStartedJobsNeverCarryASignature() {
        val req = ApprovalRequest("job-2", "iris.email_send", "iris", mapOf("to" to "landlord"))
        // Even if a signature were somehow at hand, Athena and the agents can't attach it.
        assertNull(JobPolicy.outgoing(req, "Email the landlord", Origin.ATHENA, "zt1.x.y").approval)
        assertNull(JobPolicy.outgoing(req, "Email the landlord", Origin.AGENT, "zt1.x.y").approval)
        assertTrue(JobPolicy.outgoing(req, "Email the landlord", Origin.AGENT, null).suggestion)
        assertEquals("zt1.x.y", JobPolicy.outgoing(req, "Email the landlord", Origin.OWNER, "zt1.x.y").approval)
        // Only reads, research and drafts go without a card.
        assertTrue(JobPolicy.automatic("iris.research"))
        assertTrue(JobPolicy.automatic("draft"))
        assertFalse(JobPolicy.automatic("iris.email_send"))
        assertFalse(JobPolicy.automatic("atlas.restart"))
        assertFalse(JobPolicy.automatic("iris.task_done"))
    }

    @Test fun fcmPayloadCarriesNoContent() {
        assertTrue(SyncPush.isSync(mapOf("t" to "sync")))
        assertFalse(SyncPush.isSync(mapOf("t" to "sync", "body" to "Iris: your refund came in")))
        assertFalse(SyncPush.isSync(mapOf("title" to "x")))
        assertFalse(SyncPush.isSync(emptyMap()))
    }
}

/** Just enough JSON to read the vector file in a plain JVM test (Android's org.json is a stub here). */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); require(i == s.length); v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); require(s[i++] == ':'); m[k] = value(); ws(); if (s[i++] == '}') return m } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') return l } }
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i] == '-' || s[i].isDigit())) i++; s.substring(st, i).toLong() }
            }
        }
        fun str(): String {
            require(s[i++] == '"')
            val b = StringBuilder()
            while (true) {
                val ch = s[i++]
                when (ch) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(ch)
                }
            }
        }
    }
}
