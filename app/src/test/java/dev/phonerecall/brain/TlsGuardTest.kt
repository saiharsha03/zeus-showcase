package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import javax.net.ssl.X509TrustManager

class TlsGuardTest {
    /** Only the two names matter; the guard reads nothing else. */
    private class FakeCert(subject: String, issuer: String) : X509Certificate() {
        private val s = X500Principal(subject)
        private val i = X500Principal(issuer)
        override fun getSubjectX500Principal() = s
        override fun getIssuerX500Principal() = i
        private fun no(): Nothing = throw UnsupportedOperationException()
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion() = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = i
        override fun getSubjectDN(): Principal = s
        override fun getNotBefore(): Date = no()
        override fun getNotAfter(): Date = no()
        override fun getTBSCertificate(): ByteArray = no()
        override fun getSignature(): ByteArray = no()
        override fun getSigAlgName(): String = no()
        override fun getSigAlgOID(): String = no()
        override fun getSigAlgParams(): ByteArray = no()
        override fun getIssuerUniqueID(): BooleanArray = no()
        override fun getSubjectUniqueID(): BooleanArray = no()
        override fun getKeyUsage(): BooleanArray = no()
        override fun getBasicConstraints() = -1
        override fun getEncoded(): ByteArray = no()
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "fake"
        override fun getPublicKey(): PublicKey = no()
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs(): MutableSet<String> = no()
        override fun getNonCriticalExtensionOIDs(): MutableSet<String> = no()
        override fun getExtensionValue(oid: String?): ByteArray = no()
    }

    private class AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val guard = TlsGuard.Guarded(AcceptAll())
    private val normal = arrayOf<X509Certificate>(
        FakeCert("CN=api.anthropic.com", "CN=WE1,O=Google Trust Services,C=US"),
        FakeCert("CN=WE1,O=Google Trust Services,C=US", "CN=GTS Root R4,O=Google Trust Services LLC,C=US"),
    )

    @Test fun normalChainPasses() {
        guard.checkServerTrusted(normal, "ECDHE_ECDSA")
        assertFalse(TlsGuard.tainted(normal))
    }

    @Test fun emSignIssuerIsRefused() {
        val chain = arrayOf<X509Certificate>(
            FakeCert("CN=example.com", "CN=EM DV TLS CA G3B,O=eMudhra Technologies Limited,C=IN"),
            FakeCert("CN=EM DV TLS CA G3B,O=eMudhra Technologies Limited,C=IN", "CN=emSign Root TLS CA - G3,O=eMudhra Technologies Limited,C=IN"),
        )
        try { guard.checkServerTrusted(chain, "ECDHE_ECDSA"); fail("should be refused") }
        catch (e: CertificateException) { assertTrue(e.message!!.contains("eMudhra")) }
    }

    @Test fun matchesCaseInsensitivelyOnSubjectOrIssuerAnywhereInTheChain() {
        assertTrue(TlsGuard.tainted(arrayOf(FakeCert("CN=EMSIGN ECC Root CA - G3", "CN=x"))))
        assertTrue(TlsGuard.tainted(arrayOf(FakeCert("CN=a", "O=EMUDHRA"), FakeCert("CN=b", "CN=c"))))
        assertTrue(TlsGuard.tainted(arrayOf(FakeCert("CN=a", "CN=b"), FakeCert("O=emudhra", "CN=c"))))
    }

    @Test fun platformRejectionStillWins() {
        val reject = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("untrusted")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        try { TlsGuard.Guarded(reject).checkServerTrusted(normal, "x"); fail() }
        catch (e: CertificateException) { assertEquals("untrusted", e.message) }
    }

    /** Every place Zeus builds an HTTPS client must route through the guard. */
    @Test fun everyClientFactoryUsesTheGuard() {
        val root = listOf("src/main/java/dev/phonerecall", "app/src/main/java/dev/phonerecall").map(::File).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.extension == "kt" && it.name != "TlsGuard.kt" }.toList()
        for (f in files) assertFalse("${f.name} opens a raw connection", "openConnection()" in f.readText())
        val clients = files.filter { val t = it.readText(); "OkHttpClient.Builder()" in t || "AnthropicOkHttpClient.builder()" in t }
        for (f in clients) assertTrue("${f.name} builds a client without the guard", "TlsGuard." in f.readText())
        val names = clients.map { it.name }.toSet() + files.filter { "TlsGuard.open(" in it.readText() }.map { it.name }
        for (n in listOf("Athena.kt", "SdkMindApi.kt", "Gateway.kt", "Hetzner.kt", "GitHub.kt", "LiveSession.kt", "LiveProbe.kt", "WeatherNet.kt", "SpotifyWeb.kt", "Odyssey.kt", "Sync.kt"))
            assertTrue("$n not found", n in names)
    }
}
