package dev.phonerecall.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.anthropic.core.jsonMapper
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/*
 * Calypso's drop box. Anyone may put something in; only the owner, with a fresh fingerprint, can take it out.
 * Each item is sealed to Calypso's public key: a fresh AES-256-GCM key encrypts the item, and RSA-OAEP
 * (SHA-256, MGF1 SHA-1, the padding Android Keystore takes) wraps that key. The private key lives in
 * Keystore, needs user authentication for every use, and never leaves it. A depositor holds only the
 * public key, so it can seal but never open, its own deposits included.
 */

enum class ItemKind(val wire: String, val word: String) {
    SECRET("secret", "Secret"), NOTE("note", "Note"), FILE("file", "File");

    companion object { fun of(wire: String?): ItemKind? = entries.firstOrNull { it.wire == wire } }
}

/** One sealed item, exactly as a depositor made it. `label` is the depositor's plain name for it; the content is in `ct`. */
class Envelope(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val from: String,
    val at: Long,
    /** The AES key, wrapped to the public key. A file's name travels inside `ct` with its bytes. */
    val wk: ByteArray,
    val iv: ByteArray,
    val ct: ByteArray,
) {
    fun toJson(): String = jsonMapper().writeValueAsString(
        linkedMapOf("v" to 1, "id" to id, "kind" to kind.wire, "label" to label, "from" to from, "at" to at, "wk" to b64(wk), "iv" to b64(iv), "ct" to b64(ct)),
    )

    companion object {
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private val ID = Regex("^[A-Za-z0-9_-]{8,64}$")

        /** A depositor's JSON (the gateway's drops). Null when anything is missing or malformed. */
        fun parse(json: String): Envelope? = runCatching {
            val n = jsonMapper().readTree(json)
            if (n.get("v")?.asInt() != 1) return null
            val id = n.get("id").asText()
            if (!ID.matches(id)) return null
            Envelope(
                id, ItemKind.of(n.get("kind")?.asText()) ?: return null, n.get("label").asText().take(Box.MAX_LABEL), n.get("from").asText().take(40),
                n.get("at").asLong(), Base64.getDecoder().decode(n.get("wk").asText()), Base64.getDecoder().decode(n.get("iv").asText()),
                Base64.getDecoder().decode(n.get("ct").asText()),
            )
        }.getOrNull()
    }
}

/** What a sealed item holds once the owner opens it. */
class Opened(val name: String?, val data: ByteArray) {
    val text: String get() = data.toString(Charsets.UTF_8)
}

/**
 * The one way to open a wrapped key. On the phone it is a Keystore cipher that only works after BiometricPrompt
 * authorised it (CryptoObject); in tests, a fake that refuses until the fake prompt says yes.
 */
interface Unwrapper {
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** Calypso's keypair: the public half for sealing, an [Unwrapper] (needing his fingerprint) for opening. */
interface BoxKeys {
    fun publicKey(): PublicKey
    fun unwrapper(): Unwrapper
}

object Box {
    const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    const val MAX_LABEL = 80
    /** In-app files; the gateway takes up to 2 MB. */
    const val MAX_BYTES = 8 * 1024 * 1024
    private val random = SecureRandom()

    /**
     * Mnemosyne's escrow (mnemosyne/Escrow.kt), set only while "Include Calypso" is on: sees an item's AES key at the
     * two moments it is in hand anyway (sealed here, or opened with his fingerprint) and wraps it to the escrow key.
     * Never stores the key itself. Failures are its own business: sealing and opening go on regardless.
     */
    @Volatile var keyTap: ((id: String, key: ByteArray) -> Unit)? = null

    private fun tap(id: String, key: ByteArray) { runCatching { keyTap?.invoke(id, key) } }

    fun newId(): String = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Bound into the seal, so a label, kind or sender can't be swapped onto another item's content. */
    fun aad(id: String, kind: ItemKind, label: String, from: String): ByteArray = "calypso/v1\n$id\n${kind.wire}\n$label\n$from".toByteArray(Charsets.UTF_8)

    /** The plaintext inside the AES layer: a 2-byte name length, the file name, the bytes. */
    private fun frame(name: String?, data: ByteArray): ByteArray {
        val n = (name ?: "").toByteArray(Charsets.UTF_8).take(255).toByteArray()
        return byteArrayOf((n.size shr 8).toByte(), n.size.toByte()) + n + data
    }

    private fun unframe(b: ByteArray): Opened {
        val len = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
        val name = if (len == 0) null else String(b, 2, len, Charsets.UTF_8)
        return Opened(name, b.copyOfRange(2 + len, b.size))
    }

    fun seal(pub: PublicKey, kind: ItemKind, label: String, from: String, data: ByteArray, name: String? = null, at: Long = System.currentTimeMillis(), id: String = newId()): Envelope {
        require(data.size <= MAX_BYTES) { "too big for Calypso" }
        val l = label.trim().take(MAX_LABEL).ifEmpty { kind.word }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
        try {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad(id, kind, l, from))
            val ct = c.doFinal(frame(name, data))
            val r = Cipher.getInstance(RSA)
            r.init(Cipher.ENCRYPT_MODE, pub, OAEP)
            return Envelope(id, kind, l, from, at, r.doFinal(key), iv, ct).also { tap(id, key) }
        } finally { key.fill(0) }
    }

    /** Opens an item. Throws if the unwrapper isn't authorised, or the item was tampered with. */
    fun open(e: Envelope, unwrapper: Unwrapper): Opened {
        val key = unwrapper.unwrap(e.wk)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, e.iv))
            c.updateAAD(aad(e.id, e.kind, e.label, e.from))
            return unframe(c.doFinal(e.ct)).also { tap(e.id, key) }
        } finally { key.fill(0) }
    }

    fun pem(pub: PublicKey): String =
        "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pub.encoded) + "\n-----END PUBLIC KEY-----\n"
}

/** The Keystore cipher behind one unlock. BiometricPrompt authorises exactly this object. */
class CipherUnwrapper(val cipher: Cipher) : Unwrapper {
    override fun unwrap(wrapped: ByteArray): ByteArray = cipher.doFinal(wrapped)
}

/**
 * Calypso's keypair in Android Keystore (StrongBox when the phone has it). The private key needs his
 * fingerprint or PIN for every single use (auth timeout 0, so only a CryptoObject-bound prompt opens it).
 * A new fingerprint enrolment does not wipe it, since the device PIN already opens it anyway.
 */
class KeystoreBoxKeys(private val alias: String = "zeus.calypso.box") : BoxKeys {
    private fun ks() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun ensure(): KeyStore {
        val ks = ks()
        if (ks.containsAlias(alias)) return ks
        fun make(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    // StrongBox only does RSA-2048.
                    .setKeySize(if (strongBox) 2048 else 3072)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setInvalidatedByBiometricEnrollment(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
        }.generateKeyPair()
        try { make(true) } catch (_: Exception) { make(false) }
        return ks
    }

    /** A plain copy of the public key: sealing never touches Keystore. */
    override fun publicKey(): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(ensure().getCertificate(alias).publicKey.encoded))

    override fun unwrapper(): Unwrapper {
        val c = Cipher.getInstance(Box.RSA)
        c.init(Cipher.DECRYPT_MODE, ensure().getKey(alias, null) as PrivateKey, Box.OAEP)
        return CipherUnwrapper(c)
    }

    private companion object { const val STORE = "AndroidKeyStore" }
}
