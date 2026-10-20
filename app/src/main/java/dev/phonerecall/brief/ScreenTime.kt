package dev.phonerecall.brief

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One UsageEvents.Event, copied out so the counting is plain Kotlin (and testable with fakes). */
data class UsageEvent(val pkg: String, val type: Int, val at: Long)

data class AppTime(val pkg: String = "", val label: String = "", val minutes: Int = 0)

/** Yesterday on the phone. Sensitive apps count toward the total but are never named. */
data class ScreenDay(
    val date: String = "",
    val totalMin: Int = 0,
    val top: List<AppTime> = emptyList(),
    /** The last app used between 11 PM and 4 AM; null when it was a sensitive one (or none). */
    val lateApp: String? = null,
    val lateAt: Long? = null,
    /** Unlocks (keyguard hidden); null when the phone doesn't report them. */
    val pickups: Int? = null,
)

/**
 * Apps never named in the brief, to the model or anywhere: banking and payments, health, dating, immigration.
 * By package and by label, so a bank Zeus doesn't know by name is still caught by its words.
 */
object SensitiveApps {
    private val packages = listOf(
        "com.chase.", "com.wf.", "com.wellsfargo", "com.infonow.bofa", "com.bankofamerica", "com.citi", "com.americanexpress",
        "com.discoverfinancial", "com.konylabs.capitalone", "com.capitalone", "com.usaa", "com.ally", "com.paypal", "com.venmo",
        "com.squareup.cash", "com.zellepay", "com.robinhood", "com.coinbase", "com.sofi", "com.chime", "com.snapdragon.hdfc",
        "com.csam.icici", "com.sbi.", "com.axis.", "com.msf.kbank", "net.one97.paytm", "com.phonepe", "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa", "com.mint", "com.creditkarma", "com.intuit",
        "com.tinder", "com.bumble", "co.hinge", "com.grindrapp", "com.okcupid", "com.match.", "com.hinge", "com.coffeemeetsbagel", "com.aisle",
        "com.myfitnesspal", "com.epic.mychart", "com.teladoc", "com.google.android.apps.healthdata", "com.samsung.android.shealth",
        "com.fitbit", "com.whoop", "com.ouraring", "com.cvs", "com.walgreens", "com.goodrx", "com.headspace", "com.calm", "com.talkspace", "com.betterhelp",
        "gov.uscis", "gov.dhs", "gov.cbp", "gov.state",
    )
    private val words = Regex(
        "(?i)\\b(bank|banking|credit union|wallet|pay|venmo|zelle|cash app|invest|brokerage|crypto|loan|" +
            "health|clinic|pharmacy|therapy|mychart|fitness|sleep|period|" +
            "dating|tinder|bumble|hinge|grindr|okcupid|" +
            "uscis|immigration|visa|i-94|passport|cbp)\\b",
    )

    fun sensitive(pkg: String, label: String): Boolean = packages.any { pkg.startsWith(it) } || words.containsMatchIn(label)
}

object ScreenTime {
    // UsageEvents.Event constants (stable since API 21/28), kept here so the counting runs on the JVM.
    const val RESUMED = 1
    const val PAUSED = 2
    const val KEYGUARD_HIDDEN = 18
    const val STOPPED = 23

    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** The window to read: yesterday from midnight, plus two hours either side for sessions that cross it. */
    fun window(today: LocalDate, zone: ZoneId): Pair<Long, Long> =
        today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 2 * 3_600_000L to
            today.atTime(LocalTime.of(4, 0)).atZone(zone).toInstant().toEpochMilli()

    /**
     * Foreground minutes per app for yesterday (midnight to midnight), the top [top] that may be named, the last app
     * used late (11 PM to 4 AM) and unlocks. Zeus itself, launchers and System UI don't count.
     */
    fun aggregate(
        events: List<UsageEvent>, today: LocalDate, zone: ZoneId, self: String, top: Int = 5,
        label: (String) -> String = { it },
    ): ScreenDay {
        val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val lateFrom = today.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val lateTo = today.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        fun ignored(p: String) = p == self || p.contains("launcher") || p.contains("systemui") || p == "android"

        val ms = HashMap<String, Long>()
        var open: Pair<String, Long>? = null
        var late: Pair<String, Long>? = null
        var unlocks = 0
        var sawKeyguard = false
        fun close(at: Long) {
            val (p, s) = open ?: return
            open = null
            val a = maxOf(s, from); val b = minOf(at, to)
            if (b > a) ms.merge(p, b - a, Long::plus)
            if (at > lateFrom && s < lateTo) late = p to minOf(at, lateTo)
        }
        for (e in events.sortedBy { it.at }) {
            when (e.type) {
                RESUMED -> { close(e.at); if (!ignored(e.pkg)) open = e.pkg to e.at }
                PAUSED, STOPPED -> if (open?.first == e.pkg) close(e.at)
                KEYGUARD_HIDDEN -> { sawKeyguard = true; if (e.at in from until to) unlocks++ }
            }
        }
        open?.let { (_, s) -> close(maxOf(s, minOf(lateTo, events.maxOfOrNull { it.at } ?: s))) }

        val minutes = ms.mapValues { (it.value / 60_000L).toInt() }.filterValues { it > 0 }
        val named = minutes.entries.sortedByDescending { it.value }
            .map { AppTime(it.key, label(it.key), it.value) }
            .filter { !SensitiveApps.sensitive(it.pkg, it.label) }
            .take(top)
        val lateNamed = late?.let { (p, _) -> label(p).takeIf { !SensitiveApps.sensitive(p, it) } }
        return ScreenDay(today.minusDays(1).toString(), minutes.values.sum(), named, lateNamed, late?.second, if (sawKeyguard) unlocks else null)
    }

    fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    /** One line for the brief: "3 h 10 min · most in YouTube, WhatsApp, Chrome · 54 pickups". */
    fun line(d: ScreenDay): String = listOfNotNull(
        duration(d.totalMin),
        d.top.take(3).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "most in ") { it.label },
        d.pickups?.let { "$it pickups" },
    ).joinToString(" · ")

    /** Late night, when there was any: "YouTube at 12:40 AM", or "on the phone at 1:10 AM" for a private app. */
    fun lateLine(d: ScreenDay, zone: ZoneId): String? = d.lateAt?.let { at ->
        val t = clock.format(Instant.ofEpochMilli(at).atZone(zone))
        if (d.lateApp != null) "${d.lateApp} at $t" else "on the phone at $t"
    }

    /** Words only, for the model: rounded hours, the named apps, late or not. No minutes per app, no times. */
    fun words(d: ScreenDay): String {
        val hours = Math.round(d.totalMin / 30.0) / 2.0
        val h = if (hours == Math.floor(hours)) "${hours.toInt()}" else "$hours"
        val apps = d.top.take(3).joinToString(", ") { it.label }
        return "About $h hours on the phone yesterday" + (if (apps.isNotEmpty()) ", mostly $apps" else "") +
            (if (d.lateAt != null) "; up on the phone after 11 PM" else "") + "."
    }
}
