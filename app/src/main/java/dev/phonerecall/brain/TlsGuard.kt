package dev.phonerecall.brain

import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Refuses certificate chains issued by eMudhra (emSign). Decision 2026-10-20: eight emSign intermediate CA keys
 * were compromised and none of Zeus's hosts legitimately use eMudhra, but Android's root store may keep emSign
 * until Google updates it. After the platform's normal validation, any certificate whose subject or issuer
 * Organization or CN names eMudhra or emSign fails the handshake. Hostname verification is untouched.
 * Every HTTPS client Zeus builds goes through here: [trustManager] / [sslSocketFactory] for OkHttp and the
 * Anthropic SDK, [open] for the HttpURLConnection callers.
 */
object TlsGuard {
    private val BAD = listOf("emudhra", "emsign")

    /** Last refusal host, for tests. */
    @Volatile var lastRefusedHost: String? = null

    /** True if any certificate in [chain] names eMudhra or emSign in its subject or issuer. */
    fun tainted(chain: Array<out X509Certificate>?): Boolean = chain.orEmpty().any {
        val names = (it.subjectX500Principal.name + " " + it.issuerX500Principal.name).lowercase()
        BAD.any { b -> b in names }
    }

    private fun refuse(chain: Array<out X509Certificate>?, host: String?) {
        if (!tainted(chain)) return
        val h = host ?: "?"
        lastRefusedHost = h
        runCatching { dev.phonerecall.clio.Clio.log("netgate", dev.phonerecall.clio.Level.ERROR, "refused", "refused eMudhra-issued certificate for $h") }
        throw CertificateException("Refused eMudhra-issued certificate for $h")
    }

    /** Wraps [delegate]: its validation first, then the eMudhra check. */
    class Guarded(private val delegate: X509TrustManager) : X509ExtendedTrustManager() {
        private fun ext() = delegate as? X509ExtendedTrustManager

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val e = ext()
            if (e != null) e.checkServerTrusted(chain, authType, socket) else delegate.checkServerTrusted(chain, authType)
            refuse(chain, socket?.inetAddress?.hostName)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            val e = ext()
            if (e != null) e.checkServerTrusted(chain, authType, engine) else delegate.checkServerTrusted(chain, authType)
            refuse(chain, engine?.peerHost)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            refuse(chain, null)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val e = ext()
            if (e != null) e.checkClientTrusted(chain, authType, socket) else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            val e = ext()
            if (e != null) e.checkClientTrusted(chain, authType, engine) else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = delegate.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    /** The platform trust manager wrapped with the guard. */
    val trustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        Guarded(tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager)
    }

    /** Socket factory using [trustManager]; [keys] carries a client certificate (Lemnos mTLS). */
    fun sslContext(keys: Array<KeyManager>? = null, tm: X509TrustManager = trustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keys, arrayOf(tm), null) }

    val sslSocketFactory: SSLSocketFactory by lazy { sslContext().socketFactory }

    /** `URL.openConnection()` for the HttpURLConnection callers, with the guard on https. */
    fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = sslSocketFactory
        return c
    }
}
