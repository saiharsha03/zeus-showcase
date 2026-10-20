package dev.phonerecall.music

import com.anthropic.core.jsonMapper
import kotlin.random.Random

/** One song's history on this phone: names and counts only. `last` is the newest outcomes, true = skipped. */
data class TrackStat(
    val uri: String,
    val name: String,
    val artist: String,
    val plays: Int = 0,
    val skips: Int = 0,
    val last: List<Boolean> = emptyList(),
    val lastSeen: Long = 0L,
    /** The playlist (or album) it was playing in, the last time. */
    val context: String? = null,
    /** When it became "usually skipped", for the 30-day second chance. */
    val outSince: Long? = null,
    /** Its one comeback is spent: it stays out until Bring back. */
    val comebackUsed: Boolean = false,
)

/** What the App Remote reported, reduced to what the skip detector needs. */
data class Seen(val uri: String?, val name: String, val artist: String, val durationMs: Long, val positionMs: Long, val paused: Boolean, val at: Long)

/** A finished song: skipped or played, and in which context. */
data class Outcome(val uri: String, val name: String, val artist: String, val skipped: Boolean, val context: String?)

/**
 * The rules, pure. Usually skipped = skipped in 3 of the last 4 plays (at least 3 plays). Out of a
 * playlist Zeus plays, except for one comeback 30 days after it went out.
 */
object SkipRules {
    const val KEEP = 6
    const val COMEBACK_MS = 30 * 86_400_000L

    fun usuallySkipped(last: List<Boolean>): Boolean = last.size >= 3 && last.takeLast(4).count { it } >= 3

    private fun comebackDue(s: TrackStat, now: Long) = !s.comebackUsed && s.outSince != null && now - s.outSince >= COMEBACK_MS

    /** Left out of playlists (and auto-skipped) right now. */
    fun out(s: TrackStat?, now: Long): Boolean = s != null && usuallySkipped(s.last) && !comebackDue(s, now)

    fun record(prev: TrackStat?, o: Outcome, now: Long): TrackStat {
        val s = (prev ?: TrackStat(o.uri, o.name, o.artist)).copy(
            name = o.name.ifBlank { prev?.name.orEmpty() }, artist = o.artist.ifBlank { prev?.artist.orEmpty() },
            plays = (prev?.plays ?: 0) + if (o.skipped) 0 else 1, skips = (prev?.skips ?: 0) + if (o.skipped) 1 else 0,
            lastSeen = now, context = o.context ?: prev?.context,
        )
        if (usuallySkipped(s.last) && comebackDue(s, now)) {
            // The comeback: played through, it starts over; skipped again, it stays out.
            return if (o.skipped) s.copy(last = (s.last + true).takeLast(KEEP), comebackUsed = true)
            else s.copy(last = listOf(false), outSince = null, comebackUsed = true)
        }
        val last = (s.last + o.skipped).takeLast(KEEP)
        val outSince = if (usuallySkipped(last)) s.outSince ?: now else null
        return s.copy(last = last, outSince = outSince)
    }

    data class Pick(val uris: List<String>, val rest: List<String>, val leftOut: Int)

    /** His playlist without what he usually skips, shuffled; the first `max` start playback, the rest are queued. */
    fun pick(tracks: List<String>, stats: Map<String, TrackStat>, now: Long, max: Int = 100, random: Random = Random): Pick {
        val songs = tracks.filter { it.startsWith("spotify:track:") }.distinct()
        val kept = songs.filterNot { out(stats[it], now) }.shuffled(random)
        return Pick(kept.take(max), kept.drop(max), songs.size - kept.size)
    }
}

/**
 * Watches the App Remote's player state and calls a song skipped when the track changes before it
 * played 30 s (songs of unknown length) or half its length, while playing. Reaching half or the end is
 * a play. A change while paused, a stop, or a change Zeus made itself teaches nothing.
 */
class SkipDetector(private val ourWindowMs: Long = 8_000L) {
    private var cur: Seen? = null
    private var context: String? = null
    private var ourUntil = 0L

    /** Zeus is about to change the track (auto-skip, or starting a playlist): don't learn from it. */
    @Synchronized fun ours(now: Long) { ourUntil = now + ourWindowMs }

    @Synchronized fun context(uri: String?) { context = uri?.ifBlank { null } }

    /** The outcome of the song that just ended, if a new one started and the old one counts. */
    @Synchronized fun see(s: Seen): Outcome? {
        val prev = cur
        val song = s.uri?.takeIf { it.startsWith("spotify:track:") }
        if (song == null) { cur = null; return null }
        cur = s
        if (prev == null || prev.uri == song) return null
        val ours = s.at < ourUntil
        if (ours) ourUntil = 0L
        val played = prev.positionMs + if (prev.paused) 0L else s.at - prev.at
        val reached = if (prev.durationMs > 0) played.coerceAtMost(prev.durationMs) else played
        val skipped = when {
            prev.durationMs > 0 && (reached * 2 >= prev.durationMs || prev.durationMs - reached <= END_MS) -> false
            prev.paused || ours -> return null
            prev.durationMs <= 0 -> reached < 30_000L
            else -> true
        }
        return if (ours) null else Outcome(prev.uri!!, prev.name, prev.artist, skipped, context)
    }

    /** The song playing now, as last seen. */
    val current: Seen? @Synchronized get() = cur

    private companion object { const val END_MS = 3_000L }
}

/** Where the counts live: one JSON blob in app-private prefs. Tests use a string. */
interface SkipStore {
    fun load(): String?
    fun save(json: String)
}

/** The per-song history, keyed by track uri. Thread-safe: the remote's callbacks and playback both use it. */
class SkipBook(private val store: SkipStore) {
    private val json = jsonMapper()
    private val stats: MutableMap<String, TrackStat> = runCatching { decode(store.load()) }.getOrDefault(mutableMapOf())

    @Synchronized fun get(uri: String): TrackStat? = stats[uri]
    @Synchronized fun all(): Map<String, TrackStat> = stats.toMap()
    @Synchronized fun record(o: Outcome, now: Long): TrackStat = SkipRules.record(stats[o.uri], o, now).also { stats[o.uri] = it; persist() }
    @Synchronized fun out(now: Long): List<TrackStat> = stats.values.filter { SkipRules.out(it, now) }.sortedByDescending { it.lastSeen }
    /** Bring back: forget this song's history. */
    @Synchronized fun forget(uri: String) { if (stats.remove(uri) != null) persist() }

    private fun persist() = store.save(json.writeValueAsString(stats.values.map {
        mapOf("uri" to it.uri, "name" to it.name, "artist" to it.artist, "plays" to it.plays, "skips" to it.skips, "last" to it.last,
            "lastSeen" to it.lastSeen, "context" to it.context, "outSince" to it.outSince, "comebackUsed" to it.comebackUsed)
    }))

    private fun decode(text: String?): MutableMap<String, TrackStat> {
        val list = text?.let { json.readValue(it, List::class.java) }.orEmpty()
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            TrackStat(
                m["uri"] as? String ?: return@mapNotNull null, m["name"] as? String ?: "", m["artist"] as? String ?: "",
                (m["plays"] as? Number)?.toInt() ?: 0, (m["skips"] as? Number)?.toInt() ?: 0,
                (m["last"] as? List<*>).orEmpty().mapNotNull { it as? Boolean }, (m["lastSeen"] as? Number)?.toLong() ?: 0L,
                m["context"] as? String, (m["outSince"] as? Number)?.toLong(), m["comebackUsed"] == true,
            )
        }.associateByTo(mutableMapOf()) { it.uri }
    }
}
