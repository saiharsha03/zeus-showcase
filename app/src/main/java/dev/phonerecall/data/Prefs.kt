package dev.phonerecall.data

import android.content.Context
import androidx.compose.runtime.Immutable

/** Home arrangement and small per-card state. Immutable; every change makes a new copy and saves it. */
@Immutable
data class HomePrefs(
    val pins: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    /** Kinds that are off by default and were added from Add card. */
    val added: Set<String> = emptySet(),
    /** "Less like this" count per learning key. */
    val less: Map<String, Int> = emptyMap(),
    /** Cards moved by drag on Home: card id to its slot. The engine keeps them there. */
    val positions: Map<String, Int> = emptyMap(),
    /** Task id to the epoch ms it is snoozed (off the Home card) until. */
    val snoozed: Map<String, Long> = emptyMap(),
    /** Tops worn, as top id to the epoch day (LocalDate.toEpochDay) it was worn. */
    val worn: Map<String, Long> = emptyMap(),
    /** "Another" taps today, as "epochDay:count". Moves the outfit pick along. */
    val another: String = "",
    /** Shopping list items he has bought. */
    val bought: Set<String> = emptySet(),
    /** Card instances swiped away as done (read-only here: the Dismissals file is their one writer). Instance key to the epoch ms the item stops being relevant. */
    val dismissed: Map<String, Long> = emptyMap(),
)

/** Marks one card instance done until `until` (24 h from `now` when unknown); keys are comma/equals-safe and expired entries are dropped. */
fun HomePrefs.dismiss(instanceKey: String, until: Long?, now: Long): HomePrefs =
    copy(dismissed = dismissed.filterValues { it > now } + (dismissKey(instanceKey) to (until ?: (now + 24 * 3_600_000L)).coerceAtLeast(now + 60_000L)))

fun dismissKey(instanceKey: String) = instanceKey.replace(',', ';').replace('=', '~')

data class AppPrefs(val dark: Boolean, val sample: Boolean, val nowBar: Boolean, val plainNames: Boolean, val home: HomePrefs)

/**
 * SharedPreferences, read on IO and written with apply() (async). Small and local; nothing is backed
 * up or transferred (see data_extraction_rules.xml).
 */
class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("athena", Context.MODE_PRIVATE)

    init {
        // The second clock is read everywhere (tools, Mind, Chronos), so it lives in one place from the first load.
        dev.phonerecall.brain.TwoClocks.zoneOrNull(sp.getString("second_clock", null))?.let { dev.phonerecall.brain.TwoClocks.secondZone = it }
    }

    fun saveSecondClock(id: String) { sp.edit().putString("second_clock", id).apply(); dev.phonerecall.brain.TwoClocks.zoneOrNull(id)?.let { dev.phonerecall.brain.TwoClocks.secondZone = it } }

    fun load(): AppPrefs = AppPrefs(
        dark = sp.getString("theme", "dark") != "light",
        sample = sampleOn(),
        nowBar = sp.getBoolean("nowbar", true),
        plainNames = sp.getBoolean("plainnames", false),
        home = HomePrefs(
            pins = sp.getStringSet("pins", null).orEmpty().toSet(),
            hidden = sp.getStringSet("hidden", null).orEmpty().toSet(),
            added = sp.getStringSet("added", null).orEmpty().toSet(),
            less = decodeMap(sp.getString("less", "")) { it.toIntOrNull() },
            positions = decodeMap(sp.getString("positions", "")) { it.toIntOrNull() },
            snoozed = decodeMap(sp.getString("snoozed", "")) { it.toLongOrNull() },
            worn = decodeMap(sp.getString("worn", "")) { it.toLongOrNull() },
            another = sp.getString("another", "").orEmpty(),
            bought = sp.getStringSet("bought", null).orEmpty().toSet(),
            dismissed = Dismissals.load(context),
        ),
    )

    fun saveTheme(dark: Boolean) = sp.edit().putString("theme", if (dark) "dark" else "light").apply()
    /** Samples are off for real use; earlier builds defaulted them on, so that old default is cleared once. */
    private fun sampleOn(): Boolean {
        if (!sp.getBoolean("sample_v2", false)) sp.edit().putBoolean("sample", false).putBoolean("sample_v2", true).apply()
        return sp.getBoolean("sample", false)
    }

    fun saveSample(on: Boolean) = sp.edit().putBoolean("sample", on).apply()
    fun saveNowBar(on: Boolean) = sp.edit().putBoolean("nowbar", on).apply()
    fun savePlainNames(on: Boolean) = sp.edit().putBoolean("plainnames", on).apply()

    fun saveHome(h: HomePrefs) = sp.edit()
        .putStringSet("pins", h.pins)
        .putStringSet("hidden", h.hidden)
        .putStringSet("added", h.added)
        .putString("less", encodeMap(h.less))
        .putString("positions", encodeMap(h.positions))
        .putString("snoozed", encodeMap(h.snoozed))
        .putString("worn", encodeMap(h.worn))
        .putString("another", h.another)
        .putStringSet("bought", h.bought)
        .apply()

    private fun <V> encodeMap(m: Map<String, V>) = m.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun <V> decodeMap(s: String?, parse: (String) -> V?): Map<String, V> =
        s.orEmpty().split(',').mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else parse(e.substring(i + 1))?.let { e.substring(0, i) to it }
        }.toMap()
}
