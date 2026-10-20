package dev.phonerecall.lemnos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The hand-built PKCS#10 request is well-formed DER and its signature verifies over the request info. */
class CsrTest {
    /** (tag, header length, body length) of the TLV at [at]. */
    private fun tlv(b: ByteArray, at: Int): Triple<Int, Int, Int> {
        val tag = b[at].toInt() and 0xff
        val l0 = b[at + 1].toInt() and 0xff
        return when {
            l0 < 0x80 -> Triple(tag, 2, l0)
            l0 == 0x81 -> Triple(tag, 3, b[at + 2].toInt() and 0xff)
            else -> Triple(tag, 4, ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff))
        }
    }

    @Test fun requestIsWellFormedAndSigned() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val der = Csr.der("owner-fold", kp.public.encoded) { b -> Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(b) }.sign() }

        val (outer, oh, ol) = tlv(der, 0)
        assertEquals(0x30, outer)
        assertEquals(der.size, oh + ol)
        // info, algorithm, signature
        val (infoTag, ih, il) = tlv(der, oh)
        assertEquals(0x30, infoTag)
        val info = der.copyOfRange(oh, oh + ih + il)
        assertArrayEquals(Csr.info("owner-fold", kp.public.encoded), info)
        val algAt = oh + ih + il
        val (algTag, ah, al) = tlv(der, algAt)
        assertEquals(0x30, algTag)
        val sigAt = algAt + ah + al
        val (sigTag, sh, sl) = tlv(der, sigAt)
        assertEquals(0x03, sigTag)
        assertEquals(0, der[sigAt + sh].toInt())   // no unused bits
        val sig = der.copyOfRange(sigAt + sh + 1, sigAt + sh + sl)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(kp.public); update(info) }.verify(sig))

        // The subject and the key are inside the info as given.
        assertTrue(String(info, Charsets.ISO_8859_1).contains("owner-fold"))
        assertTrue(info.toList().windowed(kp.public.encoded.size).any { it == kp.public.encoded.toList() })

        val pem = Csr.pem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n") && pem.trimEnd().endsWith("-----END CERTIFICATE REQUEST-----"))
        val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }
        assertTrue(body.all { it.length <= 64 })
        assertArrayEquals(der, Base64.getDecoder().decode(body.joinToString("")))
    }
}
