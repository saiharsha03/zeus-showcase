package dev.phonerecall.brain

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.phonerecall.alarms.AlarmPlanner
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.ui.Tone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Android shows an app only the NEXT alarm (AlarmManager.getNextAlarmClock), never the list in Samsung Clock.
 * So Zeus watches that one value, keeps 60 days of it on the phone to learn his habits, and remembers the
 * alarms it set itself. Nothing here leaves the phone.
 */

/** The system's next alarm. `app` is "Clock", "Zeus", another app's package, or null when Android doesn't say. */
data class NextAlarm(val at: Long, val app: String?) {
    companion object {
        /**
         * Calendar apps register their own date-change and reminder wakes as "alarm clocks" (Samsung Calendar sets one
         * for 00:00 every night), so Android reports them as the next alarm. They are not alarms he set.
         */
        fun notAnAlarm(creator: String?): Boolean = creator != null && "calendar" in creator.lowercase()

        /** `creator` is AlarmClockInfo.showIntent.creatorPackage; `own` is Zeus's package. */
        fun of(triggerAt: Long, creator: String?, own: String): NextAlarm = NextAlarm(
            triggerAt,
            when {
                creator == null -> null
                creator == own -> "Zeus"
                "clock" in creator.lowercase() -> "Clock"
                else -> creator
            },
        )
    }
}

/** An alarm Zeus set. `days` is empty for a one-off. */
data class ZeusAlarm(val hour: Int, val minute: Int, val label: String?, val days: String, val setAt: Long)

/** The learned habit: the usual time (minute of day) for each weekday he usually has an alarm on. */
data class Habit(val usual: Map<DayOfWeek, Int>, val daysSeen: Int) {
    fun forDay(d: DayOfWeek): Int? = usual[d]
}

object AlarmRules {
    const val WINDOW_DAYS = 60L
    private const val MIN_DAYS = 3
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun clock(minuteOfDay: Int): String = TIME.format(LocalDateTime.of(2000, 1, 1, minuteOfDay / 60, minuteOfDay % 60))

    /** Adds one reading and drops what is older than the window. Distinct values only. */
    fun record(seen: List<Long>, at: Long?, now: Long): List<Long> {
        val cutoff = now - WINDOW_DAYS * 86_400_000L
        return (if (at == null) seen else seen + at).filter { it >= cutoff }.distinct().sorted().takeLast(2_000)
    }

    /**
     * A weekday counts as a habit when, among the days we have been watching, at least half of that weekday had
     * an alarm and there were at least three. The usual time is the median of each day's earliest alarm.
     */
    fun habit(seen: List<Long>, now: Long, zone: ZoneId): Habit {
        if (seen.isEmpty()) return Habit(emptyMap(), 0)
        val firstDay = Instant.ofEpochMilli(seen.min()).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val earliest = HashMap<LocalDate, Int>()
        for (at in seen) {
            val t = Instant.ofEpochMilli(at).atZone(zone)
            earliest.merge(t.toLocalDate(), t.hour * 60 + t.minute, ::minOf)
        }
        val usual = HashMap<DayOfWeek, Int>()
        for (d in DayOfWeek.entries) {
            var occasions = 0
            var day = firstDay
            while (!day.isAfter(today)) { if (day.dayOfWeek == d) occasions++; day = day.plusDays(1) }
            val minutes = earliest.filterKeys { it.dayOfWeek == d }.values.sorted()
            if (minutes.size >= MIN_DAYS && minutes.size * 2 >= occasions) usual[d] = minutes[minutes.size / 2]
        }
        return Habit(usual, earliest.size)
    }

    /** "weekdays ~6:45 AM, weekends none". Days that differ are listed one by one. */
    fun habitLine(h: Habit): String {
        if (h.daysSeen < MIN_DAYS) return "Not enough history yet to know his habits (${h.daysSeen} days with an alarm seen)"
        fun group(name: String, days: List<DayOfWeek>): String {
            val times = days.mapNotNull { h.usual[it] }
            return when {
                times.isEmpty() -> "$name none"
                times.size == days.size && times.max() - times.min() <= 20 -> "$name ~${clock(times.sorted()[times.size / 2])}"
                else -> days.joinToString(", ") { d ->
                    val short = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                    "$short " + (h.usual[d]?.let { clock(it) } ?: "none")
                }
            }
        }
        return group("weekdays", WEEKDAYS) + ", " + group("weekends", WEEKEND)
    }

    /** When a Zeus-set alarm next rings after `from`: the next time the clock reads hour:minute (on one of its days, if any). */
    fun nextRing(a: ZeusAlarm, from: Long, zone: ZoneId): Long {
        val days = a.days.split(',').mapNotNull { runCatching { DayOfWeek.valueOf(it.trim()) }.getOrNull() }.toSet()
        var day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        repeat(9) {
            val at = day.atTime(a.hour, a.minute).atZone(zone)
            if (at.toInstant().toEpochMilli() >= from && (days.isEmpty() || at.dayOfWeek in days)) return at.toInstant().toEpochMilli()
            day = day.plusDays(1)
        }
        return Long.MAX_VALUE
    }

    /** One-offs drop once their time has passed since they were set; repeating ones stay. */
    fun live(list: List<ZeusAlarm>, now: Long, zone: ZoneId): List<ZeusAlarm> =
        list.filter { it.days.isNotBlank() || nextRing(it, it.setAt, zone) >= now }

    fun encode(list: List<ZeusAlarm>): String =
        list.joinToString("\n") { listOf(it.setAt, it.hour, it.minute, it.days, it.label.orEmpty().replace("|", " ").replace("\n", " ")).joinToString("|") }

    fun decode(s: String): List<ZeusAlarm> = s.lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 5) return@mapNotNull null
        val setAt = p[0].toLongOrNull() ?: return@mapNotNull null
        ZeusAlarm(p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: return@mapNotNull null, p[4].ifBlank { null }, p[3], setAt)
    }
}

/** What read_alarms sees. */
interface AlarmReads {
    fun summary(open: Boolean): Map<String, Any?>
}

/** The cards: "Alarm 6:45 AM" or "No alarm set for tomorrow", in the evening only. */
object AlarmCards {
    data class Card(val content: CardContent, val fit: Float, val updatedAt: Long, val kicker: String = "Calendar")

    val OPEN = CardAction("Open alarms", ActionRole.OPEN_ALARMS)

    /** "Early day tomorrow: alarm 5:00 AM, Flight 8:10 AM - sleep by 9:10 PM". */
    fun earlyDay(plan: Plan, zone: ZoneId, next: NextAlarm?): Card? {
        val (head, body) = BedNudges.card(plan, zone)
        return Card(CardContent(title = head, body = body, tone = Tone.ACTIVE, actions = listOf(OPEN)), fit = 16f, updatedAt = plan.early.at, kicker = "Bedtime")
    }

    /** "Japan GP 1:00 AM": wake, catch up after, or skip, with the bedtime each means. */
    fun sport(t: SportTrade, zone: ZoneId): Card {
        val start = AlarmRules.clock(Days.minuteOfDay(t.fixture.start, zone))
        val wake = Days.at(t.fixture.start - 10 * 60_000L, zone).let { "${it.hour}:${it.minute}" }
        return Card(
            CardContent(
                title = "${t.fixture.title} $start", body = SportSleep.body(t, zone), tone = Tone.ACTIVE,
                actions = listOf(
                    CardAction("Wake for it", ActionRole.ALARM_SPORT, "${t.fixture.id}|wake|$wake|${t.fixture.title}"),
                    CardAction("Catch up after", ActionRole.ALARM_SPORT, "${t.fixture.id}|catchup"),
                    CardAction("Skip", ActionRole.ALARM_SPORT, "${t.fixture.id}|skip"),
                ),
            ),
            fit = 18f, updatedAt = t.fixture.start, kicker = "Bedtime",
        )
    }

    /** "Alex's birthday starts at 2:30 PM your time - set an alarm?" Setting it is a tier-2 proposal. */
    fun indiaOffer(text: String, at: Long, zone: ZoneId): Card {
        val t = Days.at(at, zone)
        return Card(
            CardContent(title = "$text - set an alarm?", tone = Tone.ACTIVE, actions = listOf(CardAction("Set ${AlarmRules.clock(t.hour * 60 + t.minute)}", ActionRole.ALARM_SET, "${t.hour}:${t.minute}|Midnight in India|Alarm for midnight in India"), OPEN)),
            fit = 14f, updatedAt = at,
        )
    }

    /** One question, once, with quick picks. Other opens a text box. */
    fun ask(minute: Int): Card = Card(
        CardContent(
            title = "What's your ${AlarmRules.clock(minute)} alarm for?",
            actions = Alarms.ASK_PICKS.map { CardAction(it, ActionRole.ALARM_ASK, "$minute|$it") } + CardAction("Other", ActionRole.ALARM_OTHER, "$minute"),
        ),
        fit = 12f, updatedAt = System.currentTimeMillis(),
    )

    /** A ring-time moment as a card. */
    fun moment(m: Moment): Card = Card(
        CardContent(
            title = m.title, body = m.body, facts = m.facts.map { Fact(it.first, it.second) }, tone = Tone.ACTIVE,
            actions = m.actions.map { a ->
                CardAction(
                    a.label,
                    when (a.role) { "LINK" -> ActionRole.ALARM_LINK; "MUSIC" -> ActionRole.ALARM_MUSIC; "DONE" -> ActionRole.ALARM_DONE; else -> ActionRole.OPEN_ALARMS },
                    a.payload,
                )
            },
        ),
        fit = 30f, updatedAt = System.currentTimeMillis(),
    )

    /**
     * After 8 PM. An alarm tomorrow gets its time. No alarm tomorrow gets a card only when his habit says he usually
     * has one that weekday, with an offer to set it (a proposal, so he confirms unless alarms are on his auto list).
     * `offered` is true once the offer went out today, so it isn't repeated.
     */
    fun card(next: NextAlarm?, habit: Habit, now: ZonedDateTime, offered: Boolean = false): Card? {
        if (now.hour < 20) return null
        val tomorrow = now.toLocalDate().plusDays(1)
        val nextAt = next?.let { Instant.ofEpochMilli(it.at).atZone(now.zone) }
        if (nextAt != null && nextAt.toLocalDate() == tomorrow) {
            val t = AlarmRules.clock(nextAt.hour * 60 + nextAt.minute)
            return Card(CardContent(title = "Alarm $t", facts = listOfNotNull(next.app?.let { Fact("Set in", it) }), actions = listOf(OPEN)), fit = 8f, updatedAt = next.at)
        }
        if (nextAt != null && nextAt.toLocalDate() == now.toLocalDate()) return null // one still to ring tonight; tomorrow's is not visible yet
        val usual = habit.forDay(tomorrow.dayOfWeek) ?: return null
        val t = AlarmRules.clock(usual)
        val actions = if (offered) listOf(OPEN) else listOf(CardAction("Set $t", ActionRole.ALARM_SET, "${usual / 60}:${usual % 60}"), OPEN)
        return Card(
            CardContent(title = "No alarm set for tomorrow", body = "You usually have one around $t on ${tomorrow.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}s.", tone = Tone.ACTIVE, actions = actions),
            fit = 14f, updatedAt = now.toInstant().toEpochMilli(),
        )
    }
}

/** The phone side: reads the next alarm, keeps the history and the alarms Zeus set. */
class AlarmWatch private constructor(private val app: Context) : AlarmReads {
    private val prefs = app.getSharedPreferences("alarm_watch", Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun next(): NextAlarm? = runCatching {
        val info = app.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        if (NextAlarm.notAnAlarm(info.showIntent?.creatorPackage)) {
            // A calendar's wake can hide a real alarm at the same minute (Android shows one). One Zeus set is still known.
            val t = java.time.Instant.ofEpochMilli(info.triggerTime).atZone(zone)
            return if (zeusLabelAt(t.hour * 60 + t.minute) != null || zeusAlarms().any { it.hour == t.hour && it.minute == t.minute }) NextAlarm(info.triggerTime, "Zeus") else null
        }
        NextAlarm.of(info.triggerTime, info.showIntent?.creatorPackage, app.packageName).let { n ->
            // Another app's name for display; the package is only the fallback.
            if (n.app != null && n.app !in setOf("Zeus", "Clock")) n.copy(app = label(n.app)) else n
        }
    }.getOrNull()

    private fun label(pkg: String): String = runCatching {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun seen(): List<Long> = prefs.getString("seen", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** Writes the current next alarm into the rolling history. Cheap; call it whenever. */
    fun record(now: Long = System.currentTimeMillis(), fromBroadcast: Boolean = false): NextAlarm? {
        val n = next()
        // The broadcast is the only moment we know the value was just set, so only it stamps "set at".
        if (n != null && fromBroadcast && setAtOf(n.at) == null) {
            prefs.edit().putString("setat", (setAts() + (n.at to now)).filterKeys { it >= now - AlarmRules.WINDOW_DAYS * 86_400_000L }.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
        }
        val before = seen()
        val after = AlarmRules.record(before, n?.at, now)
        if (after != before) prefs.edit().putString("seen", after.joinToString(",")).apply()
        return n
    }

    private fun setAts(): Map<Long, Long> = prefs.getString("setat", "").orEmpty().split(';').mapNotNull {
        val p = it.split(':')
        if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toLongOrNull() ?: return@mapNotNull null) else null
    }.toMap()

    /** When this ring time first appeared as the next alarm, if the broadcast told us. */
    fun setAtOf(ring: Long): Long? = setAts()[ring]

    fun seenRings(): List<Long> = seen()

    /** The next-alarm value as of the last broadcast, to see when one rings or is dismissed. */
    fun lastNext(): Long? = prefs.getLong("lastnext", 0L).takeIf { it > 0 }
    fun saveLastNext(at: Long?) = prefs.edit().putLong("lastnext", at ?: 0L).apply()

    // ---- bedtime nudges ----

    fun nudgesOn(): Boolean = prefs.getBoolean("nudges", true)
    fun setNudges(on: Boolean) = prefs.edit().putBoolean("nudges", on).apply()

    private fun date(s: String): LocalDate? = s.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun nudgeState(): NudgeState = prefs.getString("nudge", "").orEmpty().split('|').let { p ->
        if (p.size < 5) NudgeState() else NudgeState(date(p[0]), p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3].toLongOrNull() ?: 0, date(p[4]), p.getOrNull(5)?.toLongOrNull() ?: 0)
    }
    fun saveNudgeState(s: NudgeState) = prefs.edit().putString("nudge", "${s.night ?: ""}|${s.sent}|${s.firstAt}|${s.nextAt}|${s.offFor ?: ""}|${s.heldSince}").apply()

    fun lastUnlock(): Long = prefs.getLong("unlock", 0L)
    fun markUnlock(at: Long) = prefs.edit().putLong("unlock", at).apply()

    // ---- naps ----

    /** Zeus holds its non-urgent notifications while a nap alarm is pending. */
    fun napQuiet(now: Long = System.currentTimeMillis()): Boolean = prefs.getLong("napuntil", 0L) > now
    fun setNapUntil(ring: Long) = prefs.edit().putLong("napuntil", ring).apply()

    // ---- moments and routines ----

    fun moment(now: Long = System.currentTimeMillis()): Moment? = runCatching {
        val j = org.json.JSONObject(prefs.getString("moment", null) ?: return null)
        Moment(
            j.getString("id"), j.getString("title"), j.optString("body").ifEmpty { null },
            j.getJSONArray("facts").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { f -> f.getString(0) to f.getString(1) } } },
            j.getJSONArray("actions").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> MomentAction(x.getString("l"), x.getString("r"), x.optString("p").ifEmpty { null }) } } },
            j.getLong("exp"), j.optBoolean("notify"),
        )
    }.getOrNull()?.takeIf { it.expiresAt > now }

    fun setMoment(m: Moment?) {
        if (m == null) { prefs.edit().remove("moment").apply(); return }
        val j = org.json.JSONObject().put("id", m.id).put("title", m.title).put("body", m.body.orEmpty()).put("exp", m.expiresAt).put("notify", m.notify)
            .put("facts", org.json.JSONArray(m.facts.map { org.json.JSONArray(listOf(it.first, it.second)) }))
            .put("actions", org.json.JSONArray(m.actions.map { org.json.JSONObject().put("l", it.label).put("r", it.role).put("p", it.payload.orEmpty()) }))
        prefs.edit().putString("moment", j.toString()).apply()
    }

    private fun minuteMap(key: String): Map<Int, String> = prefs.getString(key, "").orEmpty().split('\n').mapNotNull {
        val i = it.indexOf('=')
        if (i < 1) null else (it.substring(0, i).toIntOrNull() ?: return@mapNotNull null) to it.substring(i + 1)
    }.toMap()
    private fun saveMinuteMap(key: String, m: Map<Int, String>) = prefs.edit().putString(key, m.entries.joinToString("\n") { "${it.key}=${it.value.replace('\n', ' ')}" }).apply()

    /** What he said his recurring alarms are for, by minute of day. */
    fun routineAnswers(): Map<Int, String> = minuteMap("routine")
    fun answerRoutine(minute: Int, label: String) {
        saveMinuteMap("routine", routineAnswers().filterKeys { kotlin.math.abs(it - minute) > Alarms.ROUTINE_WINDOW_MIN } + (minute to label.trim().take(40)))
        markAsked(minute)
    }
    fun askedRoutines(): Map<Int, String> = minuteMap("asked")
    fun markAsked(minute: Int) = saveMinuteMap("asked", askedRoutines() + (minute to "1"))

    fun habit(now: Long = System.currentTimeMillis()): Habit = AlarmRules.habit(seen(), now, zone)

    private fun zeus(): List<ZeusAlarm> = AlarmRules.decode(prefs.getString("zeus", "").orEmpty())
    private fun saveZeus(l: List<ZeusAlarm>) = prefs.edit().putString("zeus", AlarmRules.encode(l)).apply()

    fun zeusSet(hour: Int, minute: Int, label: String?, days: String = "", now: Long = System.currentTimeMillis()) {
        saveZeus(AlarmRules.live(zeus(), now, zone).filterNot { it.hour == hour && it.minute == minute } + ZeusAlarm(hour, minute, label, days, now))
    }

    /** Undo: the alarm was dismissed. */
    fun zeusForget(hour: Int, minute: Int) = saveZeus(zeus().filterNot { it.hour == hour && it.minute == minute })

    /** The label Zeus gave an alarm ringing at this minute of day, without touching what is stored. */
    fun zeusLabelAt(minuteOfDay: Int): String? = zeus().firstOrNull { it.hour * 60 + it.minute == minuteOfDay }?.label

    fun zeusAlarms(now: Long = System.currentTimeMillis()): List<ZeusAlarm> = AlarmRules.live(zeus(), now, zone).also { if (it.size != zeus().size) saveZeus(it) }

    /** The card for Olympus, or null: a ring-time moment first, then tonight's early day, the India offer, the routine question, the evening alarm. Also keeps the history fresh. */
    fun card(now: ZonedDateTime): AlarmCards.Card? {
        val ms = now.toInstant().toEpochMilli()
        val n = record(ms)
        moment(ms)?.let { return AlarmCards.moment(it) }
        val v = AlarmPlanner.view(app, now, n)
        v.sport?.let { return AlarmCards.sport(it, zone) }
        if (now.hour >= 20 && nudgesOn()) v.plan?.let { AlarmCards.earlyDay(it, zone, n)?.let { c -> return c } }
        v.offer?.let { (text, at) -> if (prefs.getLong("offered_india", -1L) != now.toLocalDate().toEpochDay()) return AlarmCards.indiaOffer(text, at, zone) }
        v.ask?.let { return AlarmCards.ask(it) }
        return AlarmCards.card(n, habit(ms), now, offered = prefs.getLong("offered", -1L) == now.toLocalDate().toEpochDay())
    }

    fun answerSport(id: String, choice: String) = prefs.edit().putString("sport_$id", choice).apply()
    fun answeredSports(): Set<String> = prefs.all.keys.filter { it.startsWith("sport_") }.map { it.removePrefix("sport_") }.toSet()

    fun markIndiaOffered(day: LocalDate) = prefs.edit().putLong("offered_india", day.toEpochDay()).apply()

    fun markOffered(day: LocalDate) = prefs.edit().putLong("offered", day.toEpochDay()).apply()

    override fun summary(open: Boolean): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val n = record(now)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val day = DateTimeFormatter.ofPattern("EEEE", Locale.US)
        fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
        val opened = if (open) runCatching { openClock(); "Opened the clock app's alarm list for him." }.getOrElse { "Could not open the clock app: ${it.message}" } else null
        val view = n?.let { AlarmPlanner.view(app, ZonedDateTime.now(), it) }
        val meaning = n?.let { a ->
            val usual = habit(now).forDay(at(a.at).dayOfWeek)
            AlarmWords.line(a.at, AlarmPlanner.classify(app, a, this), usual, view?.plan?.takeIf { it.alarmAt == a.at }?.why, zone)
        }
        return linkedMapOf(
            "next_alarm" to (n?.let { linkedMapOf("time" to fmt.format(at(it.at)), "weekday" to day.format(at(it.at)), "set_in" to it.app, "meaning" to meaning).filterValues { v -> v != null } } ?: "none set"),
            "tomorrow" to view?.plan?.let { p -> BedNudges.card(p, zone).let { (h, b) -> "$h: $b" } },
            "bedtime_nudges" to if (nudgesOn()) "on" else "off",
            "set_by_zeus" to zeusAlarms(now).map { z ->
                linkedMapOf(
                    "time" to AlarmRules.clock(z.hour * 60 + z.minute), "label" to z.label, "repeats" to z.days.ifBlank { null },
                    "set_on" to at(z.setAt).toLocalDate().toString(), "next_rings" to fmt.format(at(AlarmRules.nextRing(z, now, zone))),
                ).filterValues { v -> v != null }
            },
            "habit" to AlarmRules.habitLine(habit(now)),
            "opened" to opened,
            "note" to "Android only tells Zeus his next alarm. The other alarms in his Clock app can't be read, so never say he has no others. open: true shows him the list.",
        ).filterValues { it != null }
    }

    fun openClock() {
        app.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        @Volatile private var instance: AlarmWatch? = null
        fun get(context: Context): AlarmWatch = instance ?: synchronized(this) { instance ?: AlarmWatch(context.applicationContext).also { instance = it } }
    }
}
