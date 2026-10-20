package dev.phonerecall.brain

import java.security.MessageDigest

/**
 * What the owner saw when he tapped Confirm: the exact payload (as a hash), its targets, the account it
 * acts in, and when he saw it. Confirm refuses unless all of it still holds, so an approval can't be
 * reused for something that changed after he looked, or long after he looked.
 */
data class Binding(val proposalId: Long, val payloadHash: String, val target: String, val account: String, val boundAt: Long)

object Bindings {
    const val CHANGED = "This changed since you saw it. Review it again."
    const val TIMED_OUT = "You saw this a while ago. Review it again."
    val REASONS = setOf(CHANGED, TIMED_OUT)

    /** The payload keys that name who or what a step acts on. */
    private val TARGET_KEYS = listOf("task_id", "calendar_id", "section", "person", "device_id", "device", "uri", "query", "kind")

    /** How long a look stays good: 10 minutes for outward kinds (tier 3 and 4), a day for tier 2. */
    fun ttl(tier: Int): Long = if (tier >= 3) 10 * 60_000L else 24 * 3_600_000L

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Every step in order with its selection; the payload of each selected step, keys sorted. */
    fun payloadHash(steps: List<Step>): String = sha(steps.joinToString("\n") { s ->
        "${s.kind.wire}:${s.selected}:" + if (!s.selected) "" else s.payload.toSortedMap().entries.joinToString(";") { (k, v) -> "$k=$v" }
    })

    fun target(steps: List<Step>): String = steps.filter { it.selected }.joinToString("|") { s ->
        (TARGET_KEYS.mapNotNull { k -> s.payload[k]?.let { "$k=$it" } } + listOfNotNull(schedulePrompt(s)?.let { "prompt=${sha(it)}" })).joinToString(",")
    }

    /** A schedule's prompt: what deploys and runs every time, shown in full on the card and bound here as shown. */
    fun schedulePrompt(s: Step): String? = s.payload["prompt"]?.takeIf { s.kind == ProposalKind.SET_SCHEDULE }

    /** The Google account of each calendar a step writes to; "phone" for everything that stays on the phone. */
    fun account(steps: List<Step>, calendars: List<CalendarChoice>): String = steps.filter { it.selected }.joinToString("|") { s ->
        s.payload["calendar_id"]?.let { id -> calendars.firstOrNull { "${it.id}" == id }?.account ?: "unknown" } ?: "phone"
    }

    fun of(p: Proposal, calendars: List<CalendarChoice>, boundAt: Long): Binding =
        Binding(p.id, payloadHash(p.steps), target(p.steps), account(p.steps, calendars), boundAt)

    /** Null when `seen` still matches `now`'s proposal; otherwise the reason to show him. */
    fun check(seen: Binding, current: Binding, tier: Int, now: Long): String? = when {
        seen.proposalId != current.proposalId -> CHANGED
        seen.boundAt > now || now - seen.boundAt >= ttl(tier) -> TIMED_OUT
        seen.payloadHash != current.payloadHash || seen.target != current.target || seen.account != current.account -> CHANGED
        else -> null
    }
}
