package dev.phonerecall.brain

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import dev.phonerecall.ui.Workers

/*
 * Parnassus, the Rambler: one capture box for anything. Each capture is sorted into an existing place
 * (a Praxis task, a Chronos event, a Venus note, a list like Groceries) or a new section, can be
 * expanded into a proposal ("make pasta" -> ingredients), or becomes a worker job that waits for the
 * phase 3 channel. This file is the routing schema: rules, the model prompt and a strict parser.
 */

/** Workers a capture can be handed to. Stored, never sent, until the phase 3 channel exists. */
enum class WorkerOwner(val display: String, val does: String) {
    IRIS(Workers.IRIS, "mail, calendar, refunds, deliveries, personal admin"),
    ATLAS(Workers.ATLAS, "server, code and his self-hosted apps"),
    /** Athena's circle (phase 2c): research, plans over days, watching something. Runs as a long job. */
    CIRCLE("Athena's circle", "research, deep reading, plans over days, monitoring and watching");

    companion object {
        /** Older rows said Heracles or Apollo; both are Athena's circle now. */
        fun of(s: String?): WorkerOwner? = s?.trim()?.let { v ->
            if (v.equals("HERACLES", true) || v.equals("APOLLO", true)) CIRCLE else entries.firstOrNull { it.name.equals(v, true) || it.display.equals(v, true) }
        }
    }
}

sealed class Route {
    data class Task(val title: String, val due: String? = null) : Route()
    data class Event(val title: String, val start: String, val end: String? = null, val allDay: Boolean = false) : Route()
    data class ClosetNote(val text: String) : Route()
    data class ListItems(val section: String, val items: List<String>) : Route()
    data class Worker(val owner: WorkerOwner, val job: String) : Route()
    /** Nothing fits: it stays in Parnassus as a note. */
    data class Keep(val text: String) : Route()
}

/** A proposal to add more than he said: "Add pasta, tomatoes, garlic, parmesan to Groceries?" */
data class Expansion(val section: String, val items: List<String>)

data class Sorted(
    val route: Route,
    val expand: Expansion? = null,
    /** "2026-11-05": past it, task hygiene moves the item to Lethe. */
    val relevantUntil: String? = null,
    val relevance: RelevanceKind? = null,
    val reason: String? = null,
    /** "rules", "nano" or "haiku". */
    val by: String = "rules",
)

object RambleSchema {
    val SYSTEM = """
You file the owner's quick captures for Zeus, his phone app. Each capture is something he typed, said or shared. Decide where it goes and answer with one JSON object only, no other text.

Routes:
- "task": something he has to do. Fields: "title" (short, starts with a verb), optional "due" (2026-10-20 or 2026-10-20T17:00).
- "event": something at a set time. Fields: "title", "start" (2026-10-20T15:00, or a date for all day), optional "end", optional "all_day".
- "closet": a note about his clothes. Field: "text".
- "list": things to buy or collect. Fields: "section" (an existing section name from the list given, or a new short plural name like "Groceries"), "items" (short names, each one item).
- "worker": a question or job for one of his agents. Fields: "worker" (iris: mail, calendar, refunds, deliveries, personal admin; atlas: his server and code; circle: Athena's circle, for research, reading, plans over days, monitoring and watching), "job" (one plain sentence).
- "keep": none of these. Field: "text".

Optional fields:
- "expand": when the capture implies more items than it says (a dish to cook means its ingredients): {"section": "Groceries", "items": [...]}. Only common items. He confirms it first.
- "relevant_until": a date after which it no longer matters (a season ends, an event passes, a deadline). With "relevance": "seasonal", "event" or "deadline", and "reason": a few words.

Rules: use his words. No emoji, no em dashes. Never invent people, amounts or dates he did not imply.
""".trim()

    fun user(text: String, sections: List<String>, today: String): String =
        "Today is $today. Existing sections: ${sections.ifEmpty { listOf("none") }.joinToString(", ")}.\nCapture: $text"

    private fun str(n: JsonNode?, max: Int): String? = n?.takeIf { it.isTextual }?.asText()?.trim()?.replace('—', ',')?.takeIf { it.isNotEmpty() && it.length <= max }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?$")

    private fun items(n: JsonNode?): List<String>? {
        if (n == null || !n.isArray || n.size() == 0 || n.size() > 20) return null
        val out = n.mapNotNull { str(it, 80) }.map { it.replaceFirstChar { c -> c.uppercase() } }.distinctBy { it.lowercase() }
        return out.takeIf { it.size == n.size() || it.isNotEmpty() }
    }

    /** The model's answer, validated. Anything malformed or out of bounds is null, and the capture stays in Parnassus. */
    fun parse(text: String, by: String = "model"): Sorted? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { jsonMapper().readTree(text.substring(start, end + 1)) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val route: Route = when (str(o.get("route"), 20)?.lowercase()) {
            "task" -> Route.Task(str(o.get("title"), 200) ?: return null, str(o.get("due"), 25)?.takeIf { DATE_TIME.matches(it) })
            "event" -> Route.Event(
                str(o.get("title"), 200) ?: return null,
                str(o.get("start"), 25)?.takeIf { DATE_TIME.matches(it) } ?: return null,
                str(o.get("end"), 25)?.takeIf { DATE_TIME.matches(it) },
                o.get("all_day")?.asBoolean(false) ?: false,
            )
            "closet" -> Route.ClosetNote(str(o.get("text"), 300) ?: return null)
            "list" -> Route.ListItems(str(o.get("section"), 32) ?: return null, items(o.get("items")) ?: return null)
            "worker" -> Route.Worker(WorkerOwner.of(str(o.get("worker"), 20)) ?: return null, str(o.get("job"), 200) ?: return null)
            "keep" -> Route.Keep(str(o.get("text"), 500) ?: return null)
            else -> return null
        }
        val expand = o.get("expand")?.takeIf { it.isObject }?.let { e ->
            val s = str(e.get("section"), 32)
            val i = items(e.get("items"))?.take(12)
            if (s != null && i != null) Expansion(s, i) else null
        }
        val until = str(o.get("relevant_until"), 10)?.takeIf { DATE.matches(it) }
        val kind = str(o.get("relevance"), 12)?.let { r -> RelevanceKind.entries.firstOrNull { it.name.equals(r, true) } }
        return Sorted(route, expand, until, if (until != null) kind ?: RelevanceKind.EVENT else null, if (until != null) str(o.get("reason"), 80) else null, by)
    }

    private val BUY = Regex("(?i)^(i )?(need|buy|get|pick up|grab|we need|we're out of|out of|running low on|low on)\\s+(.+)$")
    private val IRIS_JOB = Regex(
        "(?i)(\\b(did|has|have|is)\\b.*\\b(refund|package|order|parcel|payment|reimbursement|delivery|return)\\b|check (if|whether) .*(mail|email|inbox|refund|order)|reply to .*(email|mail)|\\bemail\\b.*\\b(landlord|leasing)\\b)",
    )
    private val ATLAS_JOB = Regex("(?i)\\b(server|vps|jellyfin|plex|bot|uptime|backup|deploy|docker)\\b.*\\?|\\b(is|why is) (the )?(server|vps|jellyfin|plex|site|bot)\\b")
    private val RESEARCH_JOB = Regex("(?i)^(research|look into|find out about|compare|read up on|what are the best)\\b")
    private val WATCH_JOB = Regex("(?i)^(keep an eye on|monitor|watch for|track)\\b")
    private val FOOD = setOf(
        "tomato", "tomatoes", "onion", "onions", "garlic", "ginger", "milk", "eggs", "egg", "bread", "rice", "dal", "atta", "paneer",
        "yogurt", "curd", "chicken", "pasta", "cheese", "parmesan", "butter", "coffee", "tea", "sugar", "salt", "oil", "apples", "bananas",
        "spinach", "cilantro", "coriander", "chillies", "chilli", "lemons", "lemon", "potatoes", "cereal", "juice", "flour", "lentils",
        "basil", "cream", "mushrooms", "peppers", "lettuce", "cucumber", "avocado", "oats", "honey", "ghee", "masala", "tortillas",
    )

    fun splitItems(s: String): List<String> = s.split(Regex("\\s*(,|\\band\\b|&|\\+)\\s*"))
        .map { it.trim().trimEnd('.', '!').removePrefix("some ").removePrefix("a ").trim() }
        .filter { it.isNotEmpty() && it.length <= 60 }
        .map { it.replaceFirstChar { c -> c.uppercase() } }
        .distinctBy { it.lowercase() }

    /** Fast, on-phone sorting for the obvious ones. Null hands it to a model. */
    fun rules(text: String): Sorted? {
        val t = text.trim()
        if (t.isEmpty()) return null
        when {
            RESEARCH_JOB.containsMatchIn(t) -> return Sorted(Route.Worker(WorkerOwner.CIRCLE, t))
            WATCH_JOB.containsMatchIn(t) -> return Sorted(Route.Worker(WorkerOwner.CIRCLE, t))
            ATLAS_JOB.containsMatchIn(t) -> return Sorted(Route.Worker(WorkerOwner.ATLAS, t))
            IRIS_JOB.containsMatchIn(t) -> return Sorted(Route.Worker(WorkerOwner.IRIS, t))
        }
        BUY.find(t)?.let { m ->
            val items = splitItems(m.groupValues[3])
            // "need to call Taylor" is a task, not shopping.
            if (items.isEmpty() || m.groupValues[3].trim().lowercase().startsWith("to ")) return null
            val food = items.any { i -> i.lowercase().split(' ').any { it in FOOD } }
            return Sorted(Route.ListItems(if (food) "Groceries" else "Shopping", items))
        }
        return null
    }
}
