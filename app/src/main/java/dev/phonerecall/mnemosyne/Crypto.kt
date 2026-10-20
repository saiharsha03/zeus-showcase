package dev.phonerecall.mnemosyne

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The recovery phrase: 256 random bits shown as 24 BIP39 English words (the last word carries an 8-bit
 * checksum), so a mistyped or swapped word is caught before any download. The wordlist is bundled
 * (resources/mnemosyne/bip39-english.txt, sha256 2f5eed53...b24dbda, the list from github.com/bitcoin/bips).
 * Only the entropy is used as key material; there is no BIP39 seed/passphrase step.
 */
object Phrase {
    val words: List<String> by lazy {
        Phrase::class.java.getResourceAsStream("/mnemosyne/bip39-english.txt")!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .also { check(it.size == 2048) { "wordlist" } }
    }
    private val index: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }
    /** BIP39 words are unique in their first four letters, so "abst" is enough for "abstract". */
    private val prefix: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w.take(4) to i } }

    fun newEntropy(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == 32)
        val bits = BooleanArray(264)
        for (i in 0 until 256) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        val check = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt()
        for (i in 0 until 8) bits[256 + i] = (check shr (7 - i)) and 1 == 1
        return (0 until 24).map { w -> words[(0 until 11).fold(0) { acc, b -> (acc shl 1) or if (bits[w * 11 + b]) 1 else 0 }] }
    }

    /** Why a phrase doesn't parse, in words he can act on. */
    class Invalid(message: String) : Exception(message)

    fun normalize(text: String): List<String> = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    fun decode(text: String): ByteArray = decode(normalize(text))

    fun decode(given: List<String>): ByteArray {
        if (given.size != 24) throw Invalid("The phrase has 24 words; this has ${given.size}.")
        val idx = given.mapIndexed { i, w -> index[w] ?: (if (w.length >= 4) prefix[w.take(4)] else null) ?: throw Invalid("Word ${i + 1} (\"$w\") isn't on the list.") }
        val bits = BooleanArray(264)
        idx.forEachIndexed { w, v -> for (b in 0 until 11) bits[w * 11 + b] = (v shr (10 - b)) and 1 == 1 }
        val entropy = ByteArray(32)
        for (i in 0 until 256) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        val check = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
        val got = (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[256 + b]) 1 else 0 }
        if (check != got) throw Invalid("Those words don't check out. One is wrong or two are swapped.")
        return entropy
    }

    /** Three distinct positions (1-based, sorted) for the "I wrote it down" check. */
    fun checkPositions(random: SecureRandom = SecureRandom()): List<Int> = (1..24).shuffled(random).take(3).sorted()
}

/** Keys from the phrase (HKDF-SHA256, RFC 5869). Everything Mnemosyne locks derives from the 32 bytes of entropy. */
object Keys {
    private val SALT = "zeus/mnemosyne/v1".toByteArray()

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray = hkdf(ikm, salt, info.toByteArray(), length)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var n = 1
        while (out.size() < length) {
            mac.update(t); mac.update(info); mac.update(n.toByte())
            t = mac.doFinal(); out.write(t); n++
        }
        return out.toByteArray().copyOf(length)
    }

    /** The key every backup is locked under. A Keystore-wrapped copy lets the nightly job run without him. */
    fun backupKey(entropy: ByteArray) = hkdf(entropy, SALT, "backup key")

    /** Opens Calypso's escrow private key, which only the phrase can reach (never kept on the phone in the clear). */
    fun escrowKey(entropy: ByteArray) = hkdf(entropy, SALT, "calypso escrow")

    /** A public 8-byte tag in each backup's header: tells "wrong phrase" apart from "damaged file". Reveals nothing usable. */
    fun check(backupKey: ByteArray): String = hkdf(backupKey, SALT, "key check", 8).joinToString("") { "%02x".format(it) }
}

/**
 * The backup file (.zbk), v1:
 *   "ZBK1" | u32 header length | header JSON | chunks
 *   header = {"v":1,"id","created","salt":base64(32 random),"check":Keys.check,"chunk":1048576}
 *   each chunk = u32 length | AES-256-GCM(fileKey, nonce = u64 index | u32 last?1:0, aad = sha256(header bytes))
 *   fileKey = HKDF(backupKey, salt, "file key")
 * A fresh salt gives every backup its own key, so counter nonces never repeat. The header (and through it the id and
 * time) is bound into every chunk, the last chunk is marked inside its nonce, and nothing may follow it: a changed
 * byte, a swapped, dropped or appended chunk, or a cut-off file all fail to open.
 */
object Zbk {
    val MAGIC = "ZBK1".toByteArray()
    const val CHUNK = 1 shl 20
    class Tampered(message: String) : Exception(message)
    class WrongKey : Exception("That phrase doesn't open this backup.")

    data class Header(val id: String, val created: Long, val salt: ByteArray, val check: String, val chunk: Int, val raw: ByteArray)

    private val json = com.fasterxml.jackson.databind.ObjectMapper()

    private fun headerJson(id: String, created: Long, salt: ByteArray, check: String): ByteArray =
        json.writeValueAsBytes(linkedMapOf("v" to 1, "id" to id, "created" to created, "salt" to java.util.Base64.getEncoder().encodeToString(salt), "check" to check, "chunk" to CHUNK))

    private fun nonce(i: Long, last: Boolean) = ByteBuffer.allocate(12).putLong(i).putInt(if (last) 1 else 0).array()

    /** Wraps [out]: what's written is encrypted chunk by chunk. close() writes the last chunk and closes [out]. */
    fun writer(out: OutputStream, backupKey: ByteArray, id: String, created: Long, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(32).also { random.nextBytes(it) }
        val raw = headerJson(id, created, salt, Keys.check(backupKey))
        out.write(MAGIC); out.write(ByteBuffer.allocate(4).putInt(raw.size).array()); out.write(raw)
        val key = SecretKeySpec(Keys.hkdf(backupKey, salt, "file key"), "AES")
        val aad = MessageDigest.getInstance("SHA-256").digest(raw)
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK)
            private var n = 0
            private var index = 0L
            private var closed = false
            private fun emit(last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(index++, last)))
                c.updateAAD(aad)
                val ct = c.doFinal(buf, 0, n)
                out.write(ByteBuffer.allocate(4).putInt(ct.size).array()); out.write(ct)
                n = 0
            }
            override fun write(b: Int) { if (n == CHUNK) emit(false); buf[n++] = b.toByte() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var left = len
                while (left > 0) {
                    if (n == CHUNK) emit(false)
                    val k = minOf(left, CHUNK - n)
                    System.arraycopy(b, o, buf, n, k); n += k; o += k; left -= k
                }
            }
            override fun close() { if (closed) return; closed = true; emit(true); out.close() }
        }
    }

    fun readHeader(input: DataInputStream): Header {
        val magic = ByteArray(4)
        try { input.readFully(magic) } catch (_: EOFException) { throw Tampered("Not a Mnemosyne backup.") }
        if (!magic.contentEquals(MAGIC)) throw Tampered("Not a Mnemosyne backup.")
        val len = input.readInt()
        if (len !in 2..4096) throw Tampered("The backup's header is damaged.")
        val raw = ByteArray(len).also { input.readFully(it) }
        return try {
            val o = json.readTree(raw)
            if (o.path("v").asInt() != 1) throw Tampered("This backup was made by a newer Zeus (format ${o.path("v").asInt()}).")
            Header(o.get("id").asText(), o.get("created").asLong(), java.util.Base64.getDecoder().decode(o.get("salt").asText()), o.get("check").asText(), o.get("chunk").asInt(), raw)
        } catch (e: Tampered) { throw e } catch (_: Exception) { throw Tampered("The backup's header is damaged.") }
    }

    /** The plaintext, chunk by chunk; any chunk that doesn't authenticate throws [Tampered]. Checks the key first. */
    fun reader(input: InputStream, backupKey: ByteArray): Pair<Header, InputStream> {
        val din = DataInputStream(input)
        val h = readHeader(din)
        if (h.check != Keys.check(backupKey)) throw WrongKey()
        if (h.chunk != CHUNK) throw Tampered("The backup's header is damaged.")
        val key = SecretKeySpec(Keys.hkdf(backupKey, h.salt, "file key"), "AES")
        val aad = MessageDigest.getInstance("SHA-256").digest(h.raw)
        val stream = object : InputStream() {
            private var cur = ByteArray(0)
            private var pos = 0
            private var index = 0L
            private var done = false
            private fun next(): Boolean {
                if (done) return false
                val len = try { din.readInt() } catch (_: EOFException) { throw Tampered("The backup is cut short.") }
                if (len !in 16..CHUNK + 16) throw Tampered("The backup is damaged.")
                val ct = ByteArray(len)
                try { din.readFully(ct) } catch (_: EOFException) { throw Tampered("The backup is cut short.") }
                fun open(last: Boolean): ByteArray? = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(index, last))); updateAAD(aad); doFinal(ct)
                    }
                } catch (_: AEADBadTagException) { null }
                val mid = if (len == CHUNK + 16) open(false) else null
                val plain = mid ?: open(true)?.also { done = true } ?: throw Tampered("The backup was changed or damaged (chunk ${index + 1}).")
                index++
                if (done && din.read() != -1) throw Tampered("The backup has extra data after its end.")
                cur = plain; pos = 0
                return true
            }
            override fun read(): Int {
                while (pos >= cur.size) if (!next()) return -1
                return cur[pos++].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) if (!next()) return -1
                val k = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, k); pos += k
                return k
            }
        }
        return h to stream
    }
}
