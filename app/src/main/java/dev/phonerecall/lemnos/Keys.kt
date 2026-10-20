package dev.phonerecall.lemnos

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.X509KeyManager

/**
 * A PKCS#10 certificate request, DER by hand (no BouncyCastle): CN only, no attributes, ecdsa-with-SHA256.
 * Pure: [sign] is the Keystore key, so the encoding is tested on the JVM with an ordinary key.
 */
object Csr {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply { write(tag); write(len(body.size)); write(body) }.toByteArray()
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private val CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)

    /** The signed part: version 0, subject CN, the public key (SubjectPublicKeyInfo as is), empty attributes. */
    fun info(commonName: String, spki: ByteArray): ByteArray {
        val name = seq(tlv(0x31, seq(CN, tlv(0x0c, commonName.toByteArray(Charsets.UTF_8)))))
        return seq(byteArrayOf(0x02, 0x01, 0x00), name, spki, byteArrayOf(0xa0.toByte(), 0x00))
    }

    fun der(commonName: String, spki: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        val info = info(commonName, spki)
        return seq(info, seq(ECDSA_SHA256), tlv(0x03, byteArrayOf(0) + sign(info)))
    }

    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE REQUEST-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE REQUEST-----\n"
}

/**
 * The phone's two Keystore keys for Lemnos (docs/ZEUS-GATEWAY.md). Neither ever leaves the Keystore.
 * - `zeus.mtls`: the TLS client key Caddy asks for. No user auth, so a background wake-up can fetch.
 * - `zeus.approval`: signs approval tokens, one fingerprint per signature. Ed25519 where the Keystore has
 *   it, otherwise P-256.
 */
object DeviceKeys {
    private const val STORE = "AndroidKeyStore"
    const val MTLS = "zeus.mtls"
    const val APPROVAL = "zeus.approval"
    private const val CN = "owner-fold"

    private fun ks() = KeyStore.getInstance(STORE).apply { load(null) }
    private fun prefs(c: Context) = c.getSharedPreferences("lemnos", Context.MODE_PRIVATE)
    fun pairDir(c: Context) = File(c.filesDir, "zeus-pair")

    enum class State { NONE, WAITING, CERT_ARRIVED, PAIRED }

    fun state(c: Context): State = when {
        paired(c) -> State.PAIRED
        File(pairDir(c), "zeus.crt").exists() -> State.CERT_ARRIVED
        runCatching { ks().containsAlias(MTLS) }.getOrDefault(false) -> State.WAITING
        else -> State.NONE
    }

    /** Paired once the client key carries a chain from the Zeus device CA (leaf + CA), not its own self-signed one. */
    fun paired(c: Context): Boolean = prefs(c).getBoolean("paired", false) && runCatching { (ks().getCertificateChain(MTLS)?.size ?: 0) >= 2 }.getOrDefault(false)

    fun approvalAlg(c: Context): String? = prefs(c).getString("approval.alg", null)

    fun approvalPublic(): PublicKey? = runCatching { ks().getCertificate(APPROVAL)?.publicKey }.getOrNull()

    /** Step 1: both keys, the CSR and the approval public key in the pair folder for the adb script. */
    fun beginPairing(c: Context) {
        val store = ks()
        if (!store.containsAlias(MTLS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(MTLS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // TLS signs through Conscrypt, which may ask for any of these.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .build(),
                )
            }.generateKeyPair()
        }
        if (!store.containsAlias(APPROVAL)) prefs(c).edit().putString("approval.alg", makeApprovalKey()).commit()
        val key = store.getKey(MTLS, null) as PrivateKey
        val spki = store.getCertificate(MTLS).publicKey.encoded
        val der = Csr.der(CN, spki) { b -> Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(b) }.sign() }
        val dir = pairDir(c).apply { mkdirs() }
        File(dir, "zeus.csr").writeText(Csr.pem(der))
        val pub = approvalPublic()?.encoded ?: error("approval key missing")
        File(dir, "approval.json").writeText("""{"alg":"${approvalAlg(c)}","spki":"${Base64.encodeToString(pub, Base64.NO_WRAP)}"}""")
    }

    /** Ed25519 needs KeyMint 2 (Android 13+); some phones only have it outside StrongBox. Falls back to P-256. */
    private fun makeApprovalKey(): String {
        fun spec(ed: Boolean) = KeyGenParameterSpec.Builder(APPROVAL, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(if (ed) "ed25519" else "secp256r1"))
            .setDigests(if (ed) KeyProperties.DIGEST_NONE else KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec(true)) }.generateKeyPair()
            Signature.getInstance("Ed25519")   // fail now, not at the first approval
            "ed25519"
        } catch (_: Exception) {
            runCatching { ks().deleteEntry(APPROVAL) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec(false)) }.generateKeyPair()
            "es256"
        }
    }

    /** Step 3: the signed chain the adb script pushed back goes onto the client key; the pair folder is cleared. */
    fun finishPairing(c: Context) {
        val f = File(pairDir(c), "zeus.crt")
        val chain = f.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it).map { x -> x as X509Certificate } }
        require(chain.size >= 2) { "expected the certificate and the Zeus CA" }
        val store = ks()
        val key = store.getKey(MTLS, null) as PrivateKey
        require(chain[0].publicKey.encoded.contentEquals(store.getCertificate(MTLS).publicKey.encoded)) { "that certificate is for another key" }
        chain[0].checkValidity()
        store.setKeyEntry(MTLS, key, null, chain.toTypedArray())
        prefs(c).edit().putBoolean("paired", true).commit()
        pairDir(c).deleteRecursively()
    }

    /** Forget this pairing (Themis). The server keeps the old fingerprint until the pair script removes it. */
    fun unpair(c: Context) {
        runCatching { ks().deleteEntry(MTLS) }
        runCatching { ks().deleteEntry(APPROVAL) }
        prefs(c).edit().clear().commit()
        pairDir(c).deleteRecursively()
    }

    /** Offers the Keystore client key to the TLS handshake. Nothing is copied out of the Keystore. */
    val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            MTLS.takeIf { runCatching { ks().containsAlias(MTLS) }.getOrDefault(false) }
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            runCatching { ks().getCertificateChain(MTLS)?.map { it as X509Certificate }?.toTypedArray() }.getOrNull()
        override fun getPrivateKey(alias: String?): PrivateKey? = runCatching { ks().getKey(MTLS, null) as PrivateKey }.getOrNull()
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(MTLS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    /**
     * Signs [bytes] with the approval key behind a strong-biometric prompt (the key is bound to it per use).
     * [onSigned] gets the signature; [onFail] a reason. Main thread.
     */
    fun signWithFingerprint(c: Context, title: String, subtitle: String, bytes: ByteArray, onSigned: (ByteArray) -> Unit, onFail: (String) -> Unit) {
        val alg = approvalAlg(c) ?: return onFail("This phone isn't paired with Lemnos yet")
        val sig = try {
            Signature.getInstance(if (alg == "ed25519") "Ed25519" else "SHA256withECDSA").apply { initSign(ks().getKey(APPROVAL, null) as PrivateKey) }
        } catch (e: Exception) { return onFail("The approval key is unusable (${e.javaClass.simpleName}); pair again") }
        BiometricPrompt.Builder(c)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", c.mainExecutor) { _, _ -> onFail("Cancelled") }
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(sig), CancellationSignal(), c.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val s = result.cryptoObject!!.signature!!
                            s.update(bytes)
                            onSigned(s.sign())
                        } catch (e: Exception) { onFail("Signing failed (${e.javaClass.simpleName})") }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
                },
            )
    }

    /** A full approval token for [req], after his fingerprint. */
    fun approve(c: Context, req: ApprovalRequest, title: String, onToken: (String) -> Unit, onFail: (String) -> Unit) {
        val alg = approvalAlg(c) ?: return onFail("This phone isn't paired with Lemnos yet")
        val spki = approvalPublic()?.encoded ?: return onFail("The approval key is missing; pair again")
        val bytes = ApprovalToken.claimsBytes(ApprovalToken.claims(req, alg, ApprovalToken.kid(spki), ApprovalToken.nonce(), System.currentTimeMillis() / 1000))
        signWithFingerprint(c, "Approve", title, bytes, { onToken(ApprovalToken.assemble(bytes, it)) }, onFail)
    }
}

/** Strong biometrics or the device PIN: Lemnos opens once per app session with this. */
fun unlockPrompt(c: Context, title: String, subtitle: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    BiometricPrompt.Builder(c)
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        .build()
        .authenticate(
            CancellationSignal(), c.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
            },
        )
}
