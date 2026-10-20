package dev.phonerecall.brain

import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** Kinds of data a request can carry. The outbound log keeps which ones a request had, never the content. */
enum class FieldCat(val word: String) {
    NAMES("names"),
    COORDINATES("coordinates"),
    PLACE_NAMES("place names"),
    MESSAGE_TEXT("your words"),
    VOICE("your voice"),
    LIST_ITEMS("list items"),
    NOTIFICATION_LINES("notification labels"),
    SUMMARIES("derived summaries"),
    KEY("an API key"),
}

/** Why a request is made and what kinds of data it holds. */
data class Outgoing(val purpose: String, val fields: Set<FieldCat>)

/** One line of "What left the phone": `count` requests to `host` for the same purpose, the last at `at`. */
data class OutboundRow(val at: Long, val host: String, val purpose: String, val fields: Set<FieldCat>, val count: Int = 1)

/**
 * The outbound log. NetGate tells it about every request it lets through; the caller may say what the
 * request is for and what it carries ([tagged]), otherwise the host's default applies. Content never
 * reaches this object: only the host, a purpose and field categories.
 */
object Outbound {
    const val KEEP_MS = 7 * 86_400_000L
    /** The same host, purpose and fields within this window add to one row instead of a new one. */
    const val MERGE_MS = 10 * 60_000L

    private val tag = ThreadLocal<Outgoing?>()
    private val listeners = CopyOnWriteArrayList<(OutboundRow) -> Unit>()

    /** Every request `block` makes on this thread is logged with this purpose and these fields. */
    fun <T> tagged(purpose: String, fields: Set<FieldCat>, block: () -> T): T {
        val before = tag.get()
        tag.set(Outgoing(purpose, fields))
        try { return block() } finally { tag.set(before) }
    }

    fun onSent(listener: (OutboundRow) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Called by NetGate for each allowed request. */
    fun sent(url: String, at: Long = System.currentTimeMillis()) {
        if (listeners.isEmpty()) return
        val r = row(url, tag.get(), at) ?: return
        for (l in listeners) runCatching { l(r) }
    }

    fun row(url: String, tagged: Outgoing?, at: Long): OutboundRow? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        val o = tagged ?: default(host, u.rawPath.orEmpty())
        return OutboundRow(at, host, o.purpose, o.fields)
    }

    /** What each host gets when the caller didn't say. */
    fun default(host: String, path: String): Outgoing = when {
        host == "api.anthropic.com" && path.startsWith("/v1/messages") -> Outgoing("Athena (Claude)", setOf(FieldCat.MESSAGE_TEXT))
        host == "api.anthropic.com" -> Outgoing("Athena's mind (Managed Agents)", setOf(FieldCat.SUMMARIES))
        host == NetGate.LEMNOS_HOST -> Outgoing("Lemnos, your server", setOf(FieldCat.MESSAGE_TEXT))
        host == NetGate.HETZNER_HOST -> Outgoing("Hetzner, server power", setOf(FieldCat.KEY))
        host == "accounts.spotify.com" -> Outgoing("Spotify sign-in", setOf(FieldCat.KEY))
        host == "api.spotify.com" -> Outgoing("Music (Spotify)", emptySet())
        host in NetGate.SPORTS_HOSTS -> Outgoing("Olympia scores", emptySet())
        host == "geocoding-api.open-meteo.com" -> Outgoing("Aeolia place search", setOf(FieldCat.PLACE_NAMES))
        host in setOf("api.open-meteo.com", "air-quality-api.open-meteo.com", "api.weather.gov") -> Outgoing("Aeolia weather", setOf(FieldCat.COORDINATES))
        host in NetGate.WEATHER_HOSTS -> Outgoing("Aeolia radar and map", emptySet())
        host == "api.tomtom.com" -> Outgoing("Odyssey traffic", setOf(FieldCat.COORDINATES, FieldCat.KEY))
        host == "511ny.org" -> Outgoing("Odyssey road events", setOf(FieldCat.KEY))
        host == "api.github.com" -> Outgoing("Prometheus (GitHub)", setOf(FieldCat.KEY))
        host in NetGate.GITHUB_HOSTS -> Outgoing("Prometheus update download", emptySet())
        host == NetGate.CALENDAR_HOST -> Outgoing("Google Calendar birthdays", setOf(FieldCat.NAMES))
        host == NetGate.LIVE_HOST -> Outgoing("Gemini Live voice", setOf(FieldCat.VOICE, FieldCat.KEY))
        else -> Outgoing("Unlisted", emptySet())
    }

    /** "names: yes, coordinates: no" first, then the other kinds the request held. */
    fun describe(fields: Set<FieldCat>): String =
        (listOf(FieldCat.NAMES, FieldCat.COORDINATES).map { "${it.word}: ${if (it in fields) "yes" else "no"}" } +
            fields.filter { it != FieldCat.NAMES && it != FieldCat.COORDINATES }.map { it.word }).joinToString(", ")

    private val LATLNG = Regex("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}")

    /** The declared fields, plus names and coordinates when the text itself holds them. */
    fun fieldsOf(text: String, declared: Set<FieldCat>): Set<FieldCat> = declared +
        (if (NameGuard.contains(text)) setOf(FieldCat.NAMES) else emptySet()) +
        (if (LATLNG.containsMatchIn(text)) setOf(FieldCat.COORDINATES) else emptySet())
}

/**
 * Knows the names of the people in his graph (and the words he calls them), so wording-only cloud calls
 * can tell when a text holds one and use on-device words instead. Loaded from People; nothing leaves.
 */
object NameGuard {
    @Volatile private var known: Set<String> = emptySet()

    /** Full names and terms ("Taylor Reed", "Amma"): each word of three letters or more counts. */
    fun update(names: Collection<String>) { known = tokens(names) }

    fun tokens(names: Collection<String>): Set<String> =
        names.flatMap { it.lowercase().split(Regex("[^\\p{L}]+")) }.filter { it.length >= 3 }.toSet()

    fun contains(text: String, names: Set<String> = known): Boolean =
        names.isNotEmpty() && text.lowercase().split(Regex("[^\\p{L}]+")).any { it in names }
}
