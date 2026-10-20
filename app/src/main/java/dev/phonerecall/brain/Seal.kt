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
}

/** AES-256-GCM with a Keystore key (StrongBox when the phone has it). IV is stored in front of the ciphertext. */
class KeystoreSealer(private val alias: String = "zeus.aegis.senses") : Sealer {
    @Volatile private var key: SecretKey? = null

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

    override fun sealBytes(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun openBytes(sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        c.doFinal(sealed, IV, sealed.size - IV)
    } catch (_: Exception) { null }

    /**
     * Each Keystore decrypt is a binder call (slower still on StrongBox), and lists like people() open every row
     * on every read. A sealed string never changes (a fresh IV per seal), so its plain text is cached by the
     * sealed text itself, in memory only and briefly: an entry lasts 15 minutes (enough to absorb a burst of
     * reads), and the whole cache is dropped when the system asks the app to trim memory.
     */
    private val opened get() = SealCache.entries

    private fun cached(sealed: String): String? = opened.get(sealed)?.let { (plain, at) ->
        if (android.os.SystemClock.elapsedRealtime() - at < TTL_MS) plain else { opened.remove(sealed); null }
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
    fun forget() = entries.evictAll()
}
