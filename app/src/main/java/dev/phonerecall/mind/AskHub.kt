package dev.phonerecall.mind

import android.content.Context
import dev.phonerecall.lemnos.Gateway

/** ask_worker over Lemnos: Athena's question goes to Iris's room (FROM ATHENA); her reply is the message answering it. */
object GatewayAskTransport : AskTransport {
    override fun send(worker: String, text: String, clientId: String): Long = Gateway.ask(worker, text, clientId).id

    override fun replyTo(worker: String, messageId: Long): String? =
        Gateway.history(worker, after = messageId, limit = 100).firstOrNull { it.replyTo == messageId && it.sender == worker }?.text
}

/**
 * The phone's one AskWorker. Owners register what happens when an answer (or the 30-minute timeout) arrives:
 * "mind" resumes the session with a note; a feature ("argo") takes its own. Polled on a Lemnos wake-up and on
 * Argo's tick; nothing waits.
 */
object AskHub {
    @Volatile private var worker: AskWorker? = null
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, (PendingAsk, AskReply?) -> Unit>()

    fun get(c: Context): AskWorker = worker ?: synchronized(this) {
        worker ?: AskWorker(PrefsKv(c.applicationContext, "asks"), GatewayAskTransport).also { worker = it }
    }

    /** Metron's rule: why [worker] takes no new ask now (above 90% of a plan window), or null. */
    fun held(c: Context, worker: String): String? = runCatching {
        dev.phonerecall.metron.MetronWords.held(dev.phonerecall.metron.MetronCore.get(c).pick(worker), java.time.ZonedDateTime.now())
    }.getOrNull()

    /** `prefix` is the owner up to the first colon: "argo" handles "argo:<id>". */
    fun on(prefix: String, handler: (PendingAsk, AskReply?) -> Unit) { handlers[prefix] = handler }

    /** Blocking: resolves answers and timeouts and hands each to its owner. */
    fun deliver(c: Context) {
        if (!dev.phonerecall.lemnos.DeviceKeys.paired(c)) return
        val w = get(c)
        if (w.pending().none { it.status == "waiting" }) return
        for ((p, r) in w.poll()) {
            val prefix = p.owner.substringBefore(':')
            if (prefix == "mind") {
                runCatching { dev.phonerecall.brain.BrainCore.get(c).mindHost.offer(MindNote(System.currentTimeMillis(), AskWorker.asNote(p, r), high = true, key = "ask-${p.id}")) }
            } else handlers[prefix]?.let { h -> runCatching { h(p, r) } }
            runCatching { dev.phonerecall.brain.BrainCore.get(c).audit.log(p.worker.replaceFirstChar { it.uppercase() }, if (r == null) "no answer in 30 min" else "answered", p.topic) }
        }
    }
}
