package dev.phonerecall.lemnos

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Canonical JSON, byte for byte what the gateway's verifier makes with Python's
 * `json.dumps(x, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`. Ints, strings, booleans,
 * null, lists and maps only: a float has no single spelling, so it is refused.
 */
object Canonical {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()
    fun bytes(v: Any?): ByteArray = encode(v).toByteArray(Charsets.UTF_8)

    /** Python sorts keys by code point; String.compareTo sorts by UTF-16 unit, which differs past U+FFFF. */
    private val byCodePoint = Comparator<String> { a, b ->
        val x = a.codePoints().toArray(); val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
        x.size.compareTo(y.size)
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Map<*, *> -> {
                val keys = v.keys.map { it as? String ?: throw IllegalArgumentException("map keys must be strings") }.sortedWith(byCodePoint)
                sb.append('{')
                keys.forEachIndexed { i, k -> if (i > 0) sb.append(','); string(sb, k); sb.append(':'); write(sb, v[k]) }
                sb.append('}')
            }
            is List<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']') }
            else -> throw IllegalArgumentException("not canonical JSON: ${v::class.java.simpleName}")
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        sb.append('"')
    }
}

/** What the owner approves: one exact job for one executor. Changing any of it needs a new signature. */
data class ApprovalRequest(
    val jobId: String,
    val kind: String,
    val executor: String,
    val payload: Map<String, Any?>,
    val rev: Int = 1,
)

/**
 * The zt1 approval token (docs/ZEUS-GATEWAY.md). Pure: the signature comes from outside, so the format is
 * tested on the JVM and the Keystore only signs. Without one, a job is a suggestion.
 */
object ApprovalToken {
    const val AUD = "zeus-gateway"
    /** Ten minutes: long enough to reach the server, short enough that a stolen token goes stale. */
    const val TTL_S = 600L
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun kid(spki: ByteArray): String = sha256Hex(spki).take(16)
    fun payloadHash(payload: Map<String, Any?>): String = sha256Hex(Canonical.bytes(payload))
    fun nonce(random: SecureRandom = SecureRandom()): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun claims(req: ApprovalRequest, alg: String, kid: String, nonce: String, iat: Long, ttl: Long = TTL_S): Map<String, Any?> {
        require(alg == "ed25519" || alg == "es256") { "unknown alg $alg" }
        require(ttl in 1..900) { "ttl too long" }
        return mapOf(
            "v" to 1, "aud" to AUD, "alg" to alg, "kid" to kid,
            "job_id" to req.jobId, "kind" to req.kind, "executor" to req.executor,
            "payload_sha256" to payloadHash(req.payload), "rev" to req.rev,
            "nonce" to nonce, "iat" to iat, "exp" to iat + ttl,
        )
    }

    fun claimsBytes(claims: Map<String, Any?>): ByteArray = Canonical.bytes(claims)

    fun assemble(claimsBytes: ByteArray, signature: ByteArray): String = "zt1." + b64.encodeToString(claimsBytes) + "." + b64.encodeToString(signature)

    /** Builds the token with [sign] (the Keystore, after his fingerprint). */
    fun make(req: ApprovalRequest, alg: String, spki: ByteArray, now: Long = System.currentTimeMillis() / 1000, sign: (ByteArray) -> ByteArray): String {
        val bytes = claimsBytes(claims(req, alg, kid(spki), nonce(), now))
        return assemble(bytes, sign(bytes))
    }
}

/** Who started a change. Only the owner, in Zeus's own UI and after his fingerprint, can attach a signature. */
enum class Origin { OWNER, ATHENA, AGENT }

/**
 * Proposal-only rule for jobs to the workers. A job that Athena or another agent started goes out with no
 * approval, so the worker treats it as a suggestion; anything that changes things becomes a Fates card first,
 * and only his confirm (which signs) sends the approved version.
 */
object JobPolicy {
    data class Outgoing(val req: ApprovalRequest, val title: String, val approval: String?) {
        val suggestion: Boolean get() = approval == null
    }

    fun outgoing(req: ApprovalRequest, title: String, origin: Origin, signedByOwner: String?): Outgoing =
        Outgoing(req, title, if (origin == Origin.OWNER) signedByOwner else null)

    /** Kinds that only read, look up or draft, so Athena may send them herself (tier 1 delegation). */
    private val READS = setOf("read", "lookup", "research", "draft", "summarise", "summarize")

    /** True when the job may go without a card: reads only. Everything else waits for him on a Fates card. */
    fun automatic(kind: String): Boolean = kind.substringAfterLast('.') in READS
}

/** FCM wake-ups carry exactly {"t":"sync"}. Anything else is ignored and never shown. */
object SyncPush {
    fun isSync(data: Map<String, String>): Boolean = data.size == 1 && data["t"] == "sync"
}
