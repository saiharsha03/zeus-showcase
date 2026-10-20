package dev.phonerecall.closet

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Venus's own small state: the wear log, favorites, today's skips and the polished reason. SharedPreferences
 * on this phone only (not backed up). Call on IO.
 */
class VenusStore(context: Context) {
    private val sp = context.getSharedPreferences("venus", Context.MODE_PRIVATE)

    fun load(): VenusPrefs = VenusPrefs(
        worn = decodeLog(sp.getString("worn", "")),
        favorites = sp.getStringSet("fav", null).orEmpty().toSet(),
        skipped = sp.getStringSet("skip", null).orEmpty().toSet(),
        skipDay = sp.getLong("skipDay", 0),
        polish = sp.getString("polish", "").orEmpty().split('\n').mapNotNull { l ->
            val i = l.indexOf('\t'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.toMap(),
        polishDay = sp.getLong("polishDay", 0),
    )

    fun save(p: VenusPrefs) = sp.edit()
        .putString("worn", encodeLog(p.worn))
        .putStringSet("fav", p.favorites)
        .putStringSet("skip", p.skipped)
        .putLong("skipDay", p.skipDay)
        .putString("polish", p.polish.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        .putLong("polishDay", p.polishDay)
        .apply()

    companion object {
        const val KEEP_DAYS = 120

        fun encodeLog(m: Map<String, List<Long>>) = m.entries.joinToString(",") { (id, days) -> "$id=" + days.joinToString(";") }

        fun decodeLog(s: String?): Map<String, List<Long>> = s.orEmpty().split(',').mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else e.substring(0, i) to e.substring(i + 1).split(';').mapNotNull { it.toLongOrNull() }
        }.filter { it.second.isNotEmpty() }.toMap()

        /** Logs `ids` as worn on `day`; one entry per day, older than [KEEP_DAYS] dropped. */
        fun wore(p: VenusPrefs, ids: Collection<String>, day: Long): VenusPrefs {
            val log = p.worn.mapValues { (_, d) -> d.filter { day - it < KEEP_DAYS } }.filterValues { it.isNotEmpty() }.toMutableMap()
            for (id in ids) log[id] = (log[id].orEmpty() + day).distinct().sorted()
            return p.copy(worn = log)
        }

        /** Undoes a day's wear for `ids` (tapping "Wore it" again). */
        fun unwore(p: VenusPrefs, ids: Collection<String>, day: Long): VenusPrefs =
            p.copy(worn = p.worn.mapValues { (id, d) -> if (id in ids) d - day else d }.filterValues { it.isNotEmpty() })

        fun skip(p: VenusPrefs, ids: Collection<String>, day: Long): VenusPrefs =
            p.copy(skipped = p.skippedOn(day) + ids, skipDay = day)

        fun toggleFavorite(p: VenusPrefs, id: String): VenusPrefs =
            p.copy(favorites = if (id in p.favorites) p.favorites - id else p.favorites + id)
    }
}

/** One piece as the widgets draw it. `picture` is a local path; `bg` the render's background, ARGB. */
@Immutable
data class OutfitPiece(val id: String, val name: String, val role: String, val picture: String?, val bg: Int?)

@Immutable
data class OutfitSnapshot(val date: LocalDate, val title: String, val reason: String, val pieces: List<OutfitPiece>, val worn: Boolean)

/** Today's outfit for readers outside Venus (the widgets). Set by AthenaViewModel; read only elsewhere. */
object OutfitState {
    private val _state = MutableStateFlow<OutfitSnapshot?>(null)
    val state: StateFlow<OutfitSnapshot?> = _state.asStateFlow()

    fun set(plan: OutfitPlan?, reason: String?) {
        _state.value = plan?.let { p ->
            OutfitSnapshot(
                p.date, p.main.title, reason ?: p.main.reason,
                p.main.pieces.map { (role, i) -> OutfitPiece(i.id, i.name, role.label, i.picture, if (i.render != null) i.renderBg else null) },
                p.wearing,
            )
        }
    }
}

/**
 * The optional Claude polish: Haiku rewords the rule's reason in one line. The outfit never depends on it;
 * a bad or late reply is dropped and the rule's own line stays.
 */
object OutfitPolish {
    const val SYSTEM = "You reword a one-line outfit note for the owner, in a calm, plain voice. Keep every fact, add none. " +
        "At most 14 words, one sentence, no emoji, no dashes, no exclamation marks, no quotes. Reply with the sentence only."

    fun user(plan: OutfitPlan): String =
        "Outfit: ${plan.main.title}. Pieces: ${plan.main.pieces.joinToString(", ") { it.second.name }}. Note: ${plan.main.reason}"

    /** The reply if it is usable, else null. */
    fun accept(reply: String?): String? {
        val t = reply?.trim()?.removeSurrounding("\"")?.lineSequence()?.firstOrNull()?.trim() ?: return null
        if (t.isEmpty() || t.length > 110 || t.split(' ').size > 16) return null
        if (t.any { it == '—' || it == '–' || it == '!' } || t.contains(" - ")) return null
        return t
    }
}
