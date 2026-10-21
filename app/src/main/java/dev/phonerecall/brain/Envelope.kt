package dev.phonerecall.brain

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope sealing: a random AES-256-GCM data key does the work in software, and only the data key is sealed by
 * the Keystore ([wrap], the alias's Keystore key, StrongBox where the phone has it). One Keystore decrypt per
 * process and key version, instead of one per value: a cold people() was ~1,400 StrongBox calls.
 *
 * Value format: [MAGIC][key version][12-byte IV][ciphertext + tag]. The wrapped data keys live in [keys], one line
 * per version ("v:base64"), in no-backup storage; the plain data key is only ever in memory and is zeroed on [forget].
 * Old values (the Keystore-direct format, IV first) don't start with a known header and are opened by the caller.
 */
class Envelope(private val wrap: Sealer, private val keys: File, private val random: SecureRandom = SecureRandom()) {
    private val open = HashMap<Int, ByteArray>()

    /** Seals with the newest data key, making the first one if there is none. */
    fun seal(plain: ByteArray): ByteArray = synchronized(this) {
        val v = latest() ?: rotate()
        val iv = ByteArray(IV).also(random::nextBytes)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(v), "AES"), GCMParameterSpec(TAG_BITS, iv)) }
        byteArrayOf(MAGIC, v.toByte()) + iv + c.doFinal(plain)
    }

    /** Null when it isn't an envelope value, or its key or tag doesn't check out. */
    fun open(sealed: ByteArray): ByteArray? {
        if (!looksLike(sealed)) return null
        val key = synchronized(this) { runCatching { key(sealed[1].toInt() and 0xff) }.getOrNull() } ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 2, IV)) }
            c.doFinal(sealed, 2 + IV, sealed.size - 2 - IV)
        } catch (_: Exception) { null }
    }

    /** Sealed with the newest key: nothing to rewrite. */
    fun current(sealed: ByteArray): Boolean = looksLike(sealed) && (sealed[1].toInt() and 0xff) == synchronized(this) { latest() }

    /**
     * A new data key for new seals; the old ones stay so old values still open (rewrite them to retire a key).
     * Returns the new version.
     */
    fun rotate(): Int = synchronized(this) {
        val next = (versions().keys.maxOrNull() ?: 0) + 1
        require(next <= 255) { "too many key versions" }
        val k = ByteArray(32).also(random::nextBytes)
        val sealedKey = wrap.sealBytes(k)
        keys.parentFile?.mkdirs()
        val tmp = File(keys.parentFile, keys.name + ".tmp")
        tmp.writeText((versions() + (next to sealedKey)).entries.joinToString("\n") { (v, w) -> "$v:" + java.util.Base64.getEncoder().encodeToString(w) })
        if (!tmp.renameTo(keys)) { keys.delete(); tmp.renameTo(keys) }
        wrapped = versions() + (next to sealedKey)
        open[next] = k
        next
    }

    /** Zeroes and drops the plain data keys; the next use unwraps them again. */
    fun forget() = synchronized(this) { open.values.forEach { it.fill(0) }; open.clear() }

    private fun latest(): Int? = versions().keys.maxOrNull()

    /** The wrapped keys, read from disk once (only [rotate] changes them, in this process). */
    private var wrapped: Map<Int, ByteArray>? = null

    private fun versions(): Map<Int, ByteArray> = wrapped ?: (if (!keys.exists()) emptyMap() else keys.readLines().mapNotNull { l ->
        val v = l.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        v to java.util.Base64.getDecoder().decode(l.substringAfter(':'))
    }.toMap()).also { wrapped = it }

    private fun key(v: Int): ByteArray = open.getOrPut(v) {
        val w = versions()[v] ?: throw IllegalStateException("no data key $v")
        wrap.openBytes(w)?.takeIf { it.size == 32 } ?: throw IllegalStateException("data key $v can't be opened")
    }

    private fun looksLike(b: ByteArray) = b.size >= 2 + IV + TAG_BITS / 8 && b[0] == MAGIC && b[1] != 0.toByte()

    companion object {
        const val MAGIC: Byte = 0x5A
        private const val IV = 12
        private const val TAG_BITS = 128
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
