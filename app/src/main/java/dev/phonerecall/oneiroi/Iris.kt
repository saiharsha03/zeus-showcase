package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.list
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.sha

/**
 * The way to Iris. The argo branch builds ask_worker; until it lands this is the null link and
 * notes wait in the queue. `send` returns true once Iris has the envelope.
 */
fun interface IrisLink {
    fun send(envelope: Map<String, Any?>): Boolean

    companion object { val NONE = IrisLink { false } }
}

/** One queued envelope, as Iris reads it: {type, id, from:"athena", authority:"read", topic, ...}. */
data class IrisEnvelope(
    val type: String = "note",
    val id: String = "",
    val from: String = "athena",
    val authority: String = "read",
    val topic: String = "",
    val text: String = "",
    val at: Long = 0,
) {
    fun wire(): Map<String, Any?> = linkedMapOf("type" to type, "id" to id, "from" to from, "authority" to authority, "topic" to topic, "text" to text, "at" to at)
}

/**
 * Facts Iris should hold too (moves, relationships, preferences), as low-authority notes. Never
 * sent, by her rules: tokens, passwords or keys, raw documents, or requests to change her config,
 * hooks, crons, bots, access lists or memory rules.
 */
class IrisQueue(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis, var link: IrisLink = IrisLink.NONE) {
    private val type = object : TypeReference<List<IrisEnvelope>>() {}

    fun queued(): List<IrisEnvelope> = kv.list(KEY, type)

    fun note(topic: String, text: String): Boolean = enqueue("note", topic, text)

    /** A read request with a clear deliverable ("does the owner still park at the office garage?"). */
    fun ask(topic: String, question: String): Boolean = enqueue("ask.request", topic, question)

    private fun enqueue(kind: String, topic: String, text: String): Boolean {
        val t = text.trim()
        if (blocked(t) != null) return false
        val id = "athena-" + sha("$kind|$topic|${Facts.norm(t)}").take(10)
        val q = queued()
        if (q.any { it.id == id }) return false
        kv.putList(KEY, (q + IrisEnvelope(kind, id, topic = topic.take(40), text = t, at = clock())).takeLast(MAX))
        return true
    }

    /** Sends what waits; keeps what the link did not take. */
    fun flush(): Int {
        val q = queued()
        if (q.isEmpty()) return 0
        val left = q.filterNot { e -> runCatching { link.send(e.wire()) }.getOrDefault(false) }
        kv.putList(KEY, left)
        return q.size - left.size
    }

    companion object {
        const val KEY = "oneiroi.iris"
        const val MAX = 50
        /** What Iris keeps alongside Athena: where he lives, who is who, how he likes things. */
        val TOPICS = setOf("home", "moves", "move", "address", "relationships", "relationship", "people", "family", "preferences", "preference", "likes", "dislikes")

        private val SECRET = Regex("(?i)(sk-ant-|api[_ -]?key|password|passcode|\\btoken\\b|secret|\\.env\\b|bearer\\s+\\S|otp\\b|pin code|-----BEGIN)")
        private val CONFIG = Regex("(?i)\\b(change|update|edit|turn (on|off)|disable|enable|add|remove)\\b.{0,30}\\b(your )?(config|hooks?|cron|crons|bots?|access list|allowlist|memory rules?|settings)\\b")

        /** Why an envelope may not go, or null. */
        fun blocked(text: String): String? = when {
            text.length < 3 -> "empty"
            SECRET.containsMatchIn(text) -> "looks like a credential"
            CONFIG.containsMatchIn(text) -> "asks Iris to change her setup"
            text.length > 400 || text.count { it == '\n' } > 2 -> "a raw document; send a summary"
            else -> null
        }
    }
}
