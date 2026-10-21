package dev.phonerecall.brain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what the senses keep: notification text, derived summaries, people's names. Aegis-level:
 * the key does not need his fingerprint, so the listener and the workers can use it in the background,
 * but it never leaves the Keystore and nothing is stored in the clear.
 */
interface Sealer {
    fun seal(plain: String): String
    /** Null when the value can't be opened (key gone, corrupted). */
    fun open(sealed: String): String?
    fun sealBytes(plain: ByteArray): ByteArray
    fun openBytes(sealed: ByteArray): ByteArray?
    /** False when the value is in an older format and worth sealing again (see [KeystoreSealer]). */
    fun current(sealed: String): Boolean = true
}

/**
 * AES-256-GCM. With [envelope] (the default, once [SealKeys.dir] is set): an [Envelope] data key in software,
 * wrapped by the alias's Keystore key (StrongBox when the phone has it). Values sealed before that (the Keystore
 * key directly, IV in front of the ciphertext) still open, and stores rewrite them once (`reseal`).
 * `envelope = false` keeps the Keystore-direct format for every seal (Calypso's vault).
 */
class KeystoreSealer(private val alias: String = "zeus.aegis.senses", private val envelope: Boolean = true) : Sealer {
    @Volatile private var key: SecretKey? = null

    /** The alias's Keystore key used directly: wraps data keys, opens old values. */
    private val direct = object : Sealer {
        override fun seal(plain: String) = throw UnsupportedOperationException()
        override fun open(sealed: String) = throw UnsupportedOperationException()
        override fun sealBytes(plain: ByteArray) = directSeal(plain)
        override fun openBytes(sealed: ByteArray) = directOpen(sealed)
    }
    private val env: Envelope? by lazy { if (envelope) SealKeys.envelope(alias, direct) else null }

    private fun key(): SecretKey {
        key?.let { return it }
        synchronized(this) {
            key?.let { return it }
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            val k = (ks.getKey(alias, null) as? SecretKey) ?: run {
                fun make(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
                    init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setIsStrongBoxBacked(strongBox)
                            .build(),
                    )
                }.generateKey()
                try { make(true) } catch (_: Exception) { make(false) }
            }
            key = k
            return k
        }
    }

    private fun directSeal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    private fun directOpen(sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        c.doFinal(sealed, IV, sealed.size - IV)
    } catch (_: Exception) { null }

    override fun sealBytes(plain: ByteArray): ByteArray = env?.seal(plain) ?: directSeal(plain)

    // An old value's random IV can start like an envelope header (1 in 65,536): when the envelope can't open it, the Keystore tries.
    override fun openBytes(sealed: ByteArray): ByteArray? = env?.open(sealed) ?: directOpen(sealed)

    override fun current(sealed: String): Boolean {
        val e = env ?: return true
        return runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()?.let { e.current(it) } ?: true
    }

    /** A new data key for new seals (old values keep opening with theirs). */
    fun rotate() { env?.rotate() }

    /**
     * Each Keystore decrypt is a binder call (slower still on StrongBox), and lists like people() open every row
     * on every read. A sealed string never changes (a fresh IV per seal), so its plain text is cached by the
     * sealed text itself, in memory only and briefly: an entry lasts 15 minutes (enough to absorb a burst of
     * reads), counted from its last use, so rows the workers read every few minutes stay open while unused ones
     * expire. On StrongBox one decrypt is tens of ms: a cold people() of ~1,400 rows cost a sync about 50 s and
     * held the Keystore up for everything else (taps included). The cache is dropped when the system is short of memory.
     */
    private val opened get() = SealCache.entries

    private fun cached(sealed: String): String? = opened.get(sealed)?.let { (plain, at) ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - at < TTL_MS) plain.also { opened.put(sealed, it to now) } else { opened.remove(sealed); null }
    }

    private fun remember(sealed: String, plain: String) { opened.put(sealed, plain to android.os.SystemClock.elapsedRealtime()) }

    override fun seal(plain: String): String =
        Base64.encodeToString(sealBytes(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP).also { remember(it, plain) }

    override fun open(sealed: String): String? = cached(sealed)
        ?: runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()?.let { openBytes(it) }?.toString(Charsets.UTF_8)?.also { remember(sealed, it) }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
        const val TTL_MS = 15 * 60_000L
    }
}

/** The opened-text cache all KeystoreSealers share (keys are sealed strings, unique per seal), so one call can drop it. */
object SealCache {
    internal val entries = android.util.LruCache<String, Pair<String, Long>>(8192)
    /** Opened text and the plain data keys. */
    fun forget() { entries.evictAll(); SealKeys.forget() }
}

/** Where the wrapped data keys live (no-backup storage, set at process start) and the envelopes in use, one per alias. */
object SealKeys {
    @Volatile var dir: java.io.File? = null
    private val envelopes = HashMap<String, Envelope>()

    fun envelope(alias: String, wrap: Sealer): Envelope? = dir?.let { d -> synchronized(envelopes) { envelopes.getOrPut(alias) { Envelope(wrap, java.io.File(d, "$alias.keys")) } } }

    fun forget() = synchronized(envelopes) { envelopes.values.forEach { it.forget() } }
}
