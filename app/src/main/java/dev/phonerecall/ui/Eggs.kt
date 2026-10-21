package dev.phonerecall.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import java.time.LocalDate
import java.time.ZoneId

/**
 * Easter eggs: small, discoverable, never in the way (docs/EASTER-EGGS.md has the spoilers). State is
 * shared across screens; what was found is kept so Themis can list the Secrets after three.
 */
object Eggs {
    enum class Egg(val label: String, val hint: String) {
        THUNDER("Thunder", "Seven taps on the Olympus title"),
        ZEUS_COIN("The other side of the coin", "A long press on the Zeus coin in Themis"),
        OWLS("The owls", "Opening Zeus between 3 and 4 AM"),
        HERACLES("Heracles rested", "Praxis with nothing open"),
        LETHE("The river", "Opening Lethe"),
        ATHENA_OWL("Athena's owl", "A long press on Athena in an empty chat"),
        METIS("Swallowed whole", "Three taps on Metis's coin in Pantheon"),
        ANNABETH("Wise Girl", "Asking Annabeth in the chat"),
        ORACLE("The oracle", "Asking Athena who she is"),
        ATTIC("Attic numerals", "Up, up, down, down on the Chronos title"),
        LAUREL("Laurel", "Your birthday, in Chronos"),
        EPITHETS("Epithets", "A long press on Plain names in Themis"),
    }

    /** Olympus title tapped seven times: the accent turns lightning white for 10 s. */
    var thunder by mutableStateOf(false)
    /** The hidden alternate subtitles (Homeric epithets) instead of the mottos. */
    var epithets by mutableStateOf(false)
        private set
    /** The epoch day Chronos shows Attic numerals for. */
    var atticDay by mutableStateOf(-1L)
        private set
    var found by mutableStateOf<Set<String>>(emptySet())
        private set

    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp != null) return
        val p = context.getSharedPreferences("eggs", Context.MODE_PRIVATE)
        sp = p
        found = p.getStringSet("found", null).orEmpty().toSet()
        epithets = p.getBoolean("epithets", false)
        atticDay = p.getLong("attic", -1L)
    }

    fun find(e: Egg) {
        if (e.name in found) return
        found = found + e.name
        sp?.edit()?.putStringSet("found", found)?.apply()
    }

    /** Themis lists the Secrets only once three are found. */
    val secretsOpen: Boolean get() = found.size >= 3

    fun toggleEpithets() {
        epithets = !epithets
        sp?.edit()?.putBoolean("epithets", epithets)?.apply()
        find(Egg.EPITHETS)
    }

    fun attic(today: LocalDate) {
        atticDay = if (atticDay == today.toEpochDay()) -1L else today.toEpochDay()
        sp?.edit()?.putLong("attic", atticDay)?.apply()
        find(Egg.ATTIC)
    }

    /** Delphi's oracle reply is given once ever. */
    fun oracleUsed(): Boolean = sp?.getBoolean("oracle", false) ?: false
    fun useOracle() { sp?.edit()?.putBoolean("oracle", true)?.apply(); find(Egg.ORACLE) }

    // ---- pure parts (tested) ----

    /** Athena's lines for a long press on her in Delphi. Picked by the press count, so each press is new. */
    val ATHENA_LINES = listOf(
        "The owl of Minerva flies only at dusk. Ask me in the evening, I'll be wiser.",
        "I was born fully armed. You can at least finish your coffee.",
        "Some things you will think of yourself; others a god will put in your mind.",
        "Strategy beats strength. Ask Ares how that went.",
        "I gave Athens an olive tree. Poseidon gave them salt water. They chose well.",
    )

    fun athenaLine(n: Int): String = ATHENA_LINES[Math.floorMod(n, ATHENA_LINES.size)]

    const val METIS_LINE = "Zeus swallowed her whole. She has advised him from the inside ever since."

    /** Taps on the Metis coin: three within 1.5 s of each other. Returns the new count; 3 means found. */
    fun metisTaps(count: Int, lastAt: Long, now: Long): Int = if (now - lastAt <= 1_500L) count + 1 else 1

    private val ANNABETH = Regex("(?i)\\bask\\s+annabeth\\b")
    private val WHO = Regex("(?i)^\\s*(who\\s+are\\s+you|who\\s+r\\s+u|what\\s+are\\s+you)\\s*\\??\\s*$")

    /** A quiet Zeus note when he asks Annabeth (the request still goes to her). */
    fun annabethWink(text: String): String? = if (ANNABETH.containsMatchIn(text)) "Wise Girl has a plan. She always has a plan." else null

    /** The oracle answers "Who are you?" in a riddle, once, on the phone (no model call). */
    fun oracle(text: String, used: Boolean): String? = if (used || !WHO.matches(text)) null else
        "Born from a headache, armed before I could walk; I keep an owl, a shield and a city that took my name. " +
            "You asked the oracle who she is. The oracle asks what you came to know."

    /** Swipes on the Chronos title: up, up, down, down (true = up). */
    val KONAMI = listOf(true, true, false, false)

    /** Keeps the last four swipes; true once they spell [KONAMI]. */
    fun swipe(history: List<Boolean>, up: Boolean): Pair<List<Boolean>, Boolean> {
        val h = (history + up).takeLast(KONAMI.size)
        return if (h == KONAMI) emptyList<Boolean>() to true else h to false
    }

    /**
     * Attic (acrophonic) numerals: Χ 1000, Η 100, Δ 10, Π 5, Ι 1, written additively (the combined 50 and 500
     * signs are left out: a date never needs 50, and most fonts lack them). 2026 is ΧΧΔΔΠΙ.
     */
    fun attic(n: Int): String {
        if (n <= 0) return n.toString()
        var r = n
        val sb = StringBuilder()
        for ((v, s) in listOf(1000 to "Χ", 100 to "Η", 10 to "Δ", 5 to "Π", 1 to "Ι")) while (r >= v) { sb.append(s); r -= v }
        return sb.toString()
    }

    /** "ΔΠΙΙΙΙ October ΧΧΔΔΠΙ": the day and year in Attic numerals, the month by name. */
    fun atticDate(d: LocalDate): String =
        "${attic(d.dayOfMonth)} ${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)} ${attic(d.year)}"

    private val OWN_BIRTHDAY = Regex("(?i)\\b(my birthday|owner'?s? (birthday|bday))\\b")

    /** The owner's own birthday today: an all-day birthday event naming him or "my birthday". */
    fun birthdayToday(events: List<CalItem>, date: LocalDate, zone: ZoneId): Boolean {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return events.any { it.begin < end && it.end > start && (it.category == EventCategory.BIRTHDAY || it.allDay) && OWN_BIRTHDAY.containsMatchIn(it.title) }
    }

    /** The hidden alternate subtitles: Homeric-style epithets. */
    val EPITHETS = mapOf(
        Place.HOME to "Many-ridged, snowy Olympus",
        Place.ORACLE to "Grey-eyed, and listening",
        Place.CALENDAR to "Who devours his children, the hours",
        Place.TASKS to "Deed after deed, the work made real",
        Place.CLOSET to "Laughter-loving, golden Venus",
        Place.AGENTS to "Deathless ones at their work",
        Place.VPS to "Where the lame god hammers",
        Place.WEATHER to "Where Aeolus keeps the winds in a bag",
        Place.DEVICES to "First-born, last-served",
        Place.SETTINGS to "Of the good counsel",
        Place.SPORTS to "Crowned with wild olive",
        Place.ALERTS to "The hound that never sleeps",
        Place.RAMBLER to "Twin-peaked home of the Muses",
        Place.MEMORY to "Every scroll, unburnt",
        Place.DREAMS to "Children of the night",
        Place.EXPIRED to "Drink, and be at peace",
        Place.PROPOSALS to "Spinner, measurer, cutter",
        Place.IMPROVE to "Forethought, chained to a rock",
        Place.PLACES to "Ten years, and home at last",
        Place.HEALTH to "Daughter of Asclepius",
        Place.MONEY to "She who warns",
        Place.VAULT to "Of the lovely braids",
        Place.PEOPLE to "Bride of the war god, keeper of concord",
        Place.BRIEF to "Rosy-fingered, early-born",
        Place.BEDTIME to "Brother of death, gentle in his coming",
        Place.RHYTHMS to "Daughters of Themis, keepers of the seasons",
        Place.SEED to "Sung first, by the Muses of Helicon",
        Place.BACKUP to "Titaness, mother of the nine Muses",
        Place.MOMENTS to "The god of the fleeting chance, bald behind",
        Place.TRIPS to "Fifty oars and a golden fleece",
        Place.USAGE to "Who weighs and counts",
        Place.LOGS to "The proclaimer, muse of what was",
        Place.AGORA to "Where the many-voiced gather to speak",
        Place.HOROLOGION to "Book of the hours, kept by the watchful",
        Place.SETUP to "Rite of the mysteries, where the initiate begins",
        Place.START to "First of all things, out of Chaos",
    )
}
