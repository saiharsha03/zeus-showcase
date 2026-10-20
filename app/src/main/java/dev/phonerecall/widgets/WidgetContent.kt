package dev.phonerecall.widgets

import dev.phonerecall.brain.Topic
import dev.phonerecall.brain.Topics
import dev.phonerecall.data.Availability
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.CardKind
import dev.phonerecall.home.CardSource
import dev.phonerecall.home.Fmt
import dev.phonerecall.home.ScoredCard
import dev.phonerecall.lemnos.AgentStatus
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.IrisTask
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.lemnos.workerStatus
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Workers
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** The home-screen widgets, one per section. `dest` is the MainActivity destination a tap opens. */
enum class WKind(val place: Place?, val dest: String) {
    TODAY(Place.HOME, "HOME"),
    CHRONOS(Place.CALENDAR, "CALENDAR"),
    AGORA(Place.TASKS, "TASKS"),
    DELPHI(Place.ORACLE, "DELPHI"),
    VENUS(Place.CLOSET, "CLOSET"),
    MUSIC(null, "HOME"),
    LEMNOS(Place.VPS, "VPS"),
    CERBERUS(Place.ALERTS, "ALERTS"),
    PANTHEON(Place.AGENTS, "AGENTS"),
    METRON(Place.USAGE, "USAGE"),
    /** Ask Zeus: Hermes's command sheet. No section of its own. */
    HERMES(null, "DELPHI"),
    TRIPS(Place.TRIPS, "TRIPS"),
    DRIVE(Place.PLACES, "ODYSSEY"),
    MONEY(Place.MONEY, "MONEY"),
    HEARTH(Place.DEVICES, "DEVICES"),
    HEALTH(Place.HEALTH, "HEALTH"),
    BRIEF(Place.BRIEF, "HOME"),
    ROOM(Place.AGORA, "AGENTS");

    /** Built from what Lemnos (or Google Home) sent, kept on the phone so the widget survives the app being closed. */
    val remote: Boolean get() = this == LEMNOS || this == CERBERUS || this == PANTHEON || this == MUSIC || this == METRON || this == HEARTH || this == ROOM

    /** Built from its own section's store (Argo, Odyssey, Moneta, Eos) or, for Iaso, its running hub; never kept by the widgets. */
    val section: Boolean get() = this == TRIPS || this == DRIVE || this == MONEY || this == BRIEF || this == HEALTH

    /**
     * Moneta and Iaso: off-limits everywhere else, shown only on their own widget, which he places himself.
     * Their lines skip the money and health word rules but never carry an amount ([WidgetSafety.clean]).
     */
    val own: Boolean get() = this == MONEY || this == HEALTH

    /** The section's name, honouring Themis's "Plain names". Music and Ask Zeus have no mythic section. */
    fun label(plain: Boolean): String = place?.let { if (plain) it.plain else it.myth } ?: if (this == HERMES) "Zeus" else "Music"
}

enum class WTone { QUIET, OK, ACTIVE, ALERT }

/** What sits in front of a row: nothing, a status dot, or a tick box (his own local tasks only). */
enum class Mark { NONE, DOT, CHECK }

/** One line. `taskId` is set only on his own local tasks, the one thing a widget tap changes directly. */
data class WRow(
    val text: String,
    val meta: String = "",
    val tone: WTone = WTone.QUIET,
    val mark: Mark = Mark.NONE,
    val taskId: Long? = null,
    val dest: String? = null,
    /** Hestia only: the device a tap switches, as "id|on" or "id|off" (his own tap, so it acts directly). */
    val act: String? = null,
)

data class WGroup(val label: String, val rows: List<WRow>, val empty: String = "")

/** A closet picture: a render is fitted onto its own background; a photo is cropped. */
data class WPic(val path: String, val bg: Int?, val fit: Boolean, val label: String)

/** Everything one widget shows. The renderer draws it; the kicker comes from [kicker] at draw time. */
data class WView(
    val kind: WKind,
    val title: String,
    val sub: String = "",
    val groups: List<WGroup> = emptyList(),
    val foot: String = "",
    val tone: WTone = WTone.QUIET,
    val pics: List<WPic> = emptyList(),
    /** Music only: whether the player is playing (the middle button is pause then). */
    val playing: Boolean = false,
    /** A section standing in for the widget's own (Argo's trip on Today): its name is the kicker. */
    val place: Place? = null,
    /** A percent drawn as a bar under the title (Moneta's week against usual, Odyssey's miles against the lease). */
    val bar: Int? = null,
) {
    fun kicker(plain: Boolean): String = place?.let { if (plain) it.plain else it.myth } ?: kind.label(plain)
}

fun Tone.w(): WTone = when (this) { Tone.OK -> WTone.OK; Tone.ACTIVE -> WTone.ACTIVE; Tone.ALERT -> WTone.ALERT; else -> WTone.QUIET }

/**
 * The widget rule: a home screen is seen by anyone holding the phone, so nothing from Iris's
 * OFF-LIMITS list reaches one. Health, immigration, dating and money lines are dropped whole;
 * Calypso, Aegis, Moneta and Iaso never appear (outside their own widgets, which carry no amounts);
 * credentials, addresses, hosts and numbers that identify things are masked or dropped. If unsure, the line goes.
 */
object WidgetSafety {
    private val sanitizer = Sanitizer()
    private val SEALED = Regex(
        "(?i)\\b(calypso|aegis|moneta|iaso|vault|balance|salary|income|loan|weight|calories|diagnos\\w*)\\b",
    )
    /** Never on any widget, his own Moneta and Iaso ones included. */
    private val SECRET = Regex(
        "(?i)\\b(password|passcode|passphrase|passwd|token|api[ -]?key|secret|credential|" +
            "private key|ssh key|keypair|otp|2fa|one-time code|verification code|security code|pin code|ssn|social security|" +
            "account (no|number)|routing|iban|swift|card ending|cvv|" +
            "vin|licen[cs]e plate|plate number|sudoers|firewall|ufw|iptables|fail2ban)\\b",
    )
    /** An amount in any form: a currency sign or word, or a figure with cents. */
    private val AMOUNT = Regex("(?i)[$₹€£¢]|\\b\\d[\\d,]*\\.\\d{2}\\b|\\b(usd|dollars?|bucks|cents?|rupees?|inr|rs\\.?|eur|gbp)\\b")
    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b")
    private val IPV6 = Regex("\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b")
    private val HOST = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|dev|app|cloud|xyz|me|ai|sh|lan|local|internal)\\b")
    private val PORT = Regex("(?i)\\bport\\s*\\d+\\b")
    private val SIGN_IN = Regex("(?i)\\b(ssh|login|logged in|sign-?in|signed in|sudo)\\b")
    private val USER = Regex("(?i)\\b(user|as|by)\\s+[a-z_][a-z0-9_.-]{1,31}\\b")
    private val LONG_ID = Regex("\\b[A-Za-z0-9+/_-]{24,}={0,2}\\b")
    private val VIN = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")

    /**
     * One line made safe for a widget, or null when it must not be shown at all. On his own Moneta and Iaso
     * widgets ([WKind.own]) the money and health words may stay, but an amount never does.
     */
    fun clean(raw: String?, kind: WKind? = null): String? {
        if (raw.isNullOrBlank()) return null
        val one = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        // Argo's pointer says where a confirmation is, never what it says; it is the one Calypso line allowed.
        if (one == dev.phonerecall.argo.ArgoCards.CONFIRMATION) return one
        if (SECRET.containsMatchIn(one) || VIN.containsMatchIn(one)) return null
        var s = if (kind?.own == true) {
            if (AMOUNT.containsMatchIn(one)) return null
            one
        } else {
            if (SEALED.containsMatchIn(one)) return null
            if (Topic.MONEY in Topics.of(one)) return null
            sanitizer.line(one) ?: return null
        }
        if (s.contains("[amount]")) return null
        s = IPV4.replace(s, "an address")
        s = IPV6.replace(s, "an address")
        s = HOST.replace(s, "a host")
        s = PORT.replace(s, "a port")
        if (SIGN_IN.containsMatchIn(s)) s = USER.replace(s) { "${it.groupValues[1]} someone" }
        s = LONG_ID.replace(s, "")
        return s.replace(Regex("\\s{2,}"), " ").trim().take(120).ifBlank { null }
    }

    /** True when every string in the view passes [clean] unchanged: the check WidgetBus runs before drawing. */
    fun isSafe(v: WView): Boolean = texts(v).all { it.isEmpty() || clean(it, v.kind) == it }

    /** The last gate: a view with any line that does not pass is redrawn with those lines removed. */
    fun seal(v: WView): WView {
        fun c(s: String) = if (s.isEmpty()) s else clean(s, v.kind).orEmpty()
        return v.copy(
            title = c(v.title).ifEmpty { "Open Zeus" },
            sub = c(v.sub), foot = c(v.foot),
            groups = v.groups.map { g -> g.copy(label = c(g.label), empty = c(g.empty), rows = g.rows.mapNotNull { r -> clean(r.text, v.kind)?.let { r.copy(text = it, meta = c(r.meta)) } }) },
            pics = v.pics.map { it.copy(label = c(it.label)) },
        )
    }

    fun texts(v: WView): List<String> = listOf(v.title, v.sub, v.foot) +
        v.groups.flatMap { g -> listOf(g.label, g.empty) + g.rows.flatMap { listOf(it.text, it.meta) } } + v.pics.map { it.label }

    /** Card kinds that may appear on Today. Bank cards, Calypso and anything money or health never do. */
    val TODAY_KINDS = setOf(CardKind.BRIEF, CardKind.COMMUTE, CardKind.OOO, CardKind.MATCH, CardKind.WARDROBE, CardKind.AGENTS, CardKind.SHOPPING, CardKind.TRIP)
    val TODAY_SOURCES = setOf(CardSource.ATHENA, CardSource.CALENDAR, CardSource.IRIS, CardSource.ATLAS)
}

/**
 * Size buckets. The widget host picks the largest one that fits, so each must fit at its minimum height:
 * [bodyDp] is what is left for rows under the kicker and a one-line title (widget_card.xml).
 */
enum class Bucket(val widthDp: Float, val heightDp: Float, val wide: Boolean = false) {
    SMALL(110f, 40f),
    /** About two cells high: one row (cards), or Delphi's kicker above the field. */
    SHORT(160f, 96f),
    MEDIUM(180f, 150f),
    LARGE(180f, 280f),
    FULL(180f, 460f),
    WIDE_LARGE(400f, 280f, wide = true),
    WIDE_FULL(400f, 460f, wide = true);

    val bodyDp: Float get() = heightDp - HEADER_DP

    companion object {
        /** Padding, kicker and a one-line title. */
        const val HEADER_DP = 69f
        const val ROW_DP = 27f
        const val LABEL_DP = 23f
        /** The line under the title. */
        const val SUB_DP = 16f
        /** The bar under the title (Moneta, Odyssey). */
        const val BAR_DP = 12f
    }
}

/** Which groups and rows fit a bucket. Pure, so the sizes are tested without a launcher. */
object WidgetPlan {
    private fun labelDp(g: WGroup) = if (g.label.isEmpty()) 0f else Bucket.LABEL_DP

    /**
     * Groups in order, each taking what height is left. A label needs room for at least one line under it;
     * [reservedDp] is the line under the title when it shows. Empty groups keep their "empty" line only
     * when there is room. The smallest bucket has no rows: the title carries it.
     */
    fun fit(groups: List<WGroup>, bucket: Bucket, reservedDp: Float = 0f): List<WGroup> {
        var left = bucket.bodyDp - reservedDp
        val out = ArrayList<WGroup>()
        for (g in groups) {
            val head = labelDp(g)
            if (left - head < Bucket.ROW_DP) break
            left -= head
            if (g.rows.isEmpty()) {
                if (g.empty.isNotEmpty()) { out += g; left -= Bucket.ROW_DP } else left += head
                continue
            }
            val take = minOf(g.rows.size, (left / Bucket.ROW_DP).toInt())
            out += g.copy(rows = g.rows.take(take))
            left -= take * Bucket.ROW_DP
        }
        return out
    }

    /** Two columns on the inner screen: whole groups fill the left column, the rest go right. */
    fun columns(groups: List<WGroup>, bucket: Bucket, reservedDp: Float = 0f): Pair<List<WGroup>, List<WGroup>> {
        fun height(g: WGroup) = labelDp(g) + maxOf(g.rows.size, if (g.empty.isNotEmpty()) 1 else 0) * Bucket.ROW_DP
        var left = bucket.bodyDp - reservedDp
        var i = 0
        while (i < groups.size) {
            val h = height(groups[i])
            if (h > left && i > 0) break
            left -= h; i++
        }
        return fit(groups.take(i), bucket, reservedDp) to fit(groups.drop(i), bucket, reservedDp)
    }
}

/** State to display model, one function per widget. Pure: the same inputs give the same widget. */
object WidgetContent {
    private const val PRIVATE = "Busy"

    // ---- calendar helpers ----

    private fun dayStart(d: LocalDate, now: ZonedDateTime) = d.atStartOfDay(now.zone).toInstant().toEpochMilli()

    private fun on(d: LocalDate, events: List<CalItem>, now: ZonedDateTime): List<CalItem> {
        val s = dayStart(d, now); val e = dayStart(d.plusDays(1), now)
        return events.filter { it.begin < e && it.end > s }.sortedWith(compareBy({ !it.allDay }, { it.begin }))
    }

    private fun birthdayName(t: String) = t.removeSuffix("'s birthday").removeSuffix("’s birthday").removeSuffix(" birthday").trim()

    /** An event as a row. A title that may not be shown becomes "Busy", so the time still counts. */
    fun eventRow(e: CalItem, now: ZonedDateTime, withDay: Boolean = false): WRow {
        val title = if (e.category == EventCategory.BIRTHDAY) WidgetSafety.clean(birthdayName(e.title))?.let { "$it's birthday" } ?: "A birthday"
        else WidgetSafety.clean(e.title) ?: PRIVATE
        val t = Instant.ofEpochMilli(e.begin).atZone(now.zone)
        val day = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val prefix = if (!withDay || day <= 0) "" else if (day == 1L) "Tomorrow " else Fmt.shortDate(t).substringBefore(',') + " "
        val meta = when {
            e.category == EventCategory.BIRTHDAY -> "Birthday"
            e.allDay -> (prefix + "all day").trim().replaceFirstChar { it.uppercase() }
            else -> prefix + Fmt.time(t)
        }
        val live = !e.allDay && e.begin <= now.toInstant().toEpochMilli()
        val tone = when { e.category == EventCategory.OOO -> WTone.ACTIVE; live -> WTone.OK; else -> WTone.QUIET }
        return WRow(title, meta, tone, Mark.DOT, dest = WKind.CHRONOS.dest)
    }

    private fun dayLabel(d: LocalDate, now: ZonedDateTime, a: Availability?): String {
        val diff = ChronoUnit.DAYS.between(now.toLocalDate(), d)
        val name = when (diff) { 0L -> "Today"; 1L -> "Tomorrow"; else -> Fmt.shortDate(d.atStartOfDay(now.zone)) }
        return listOfNotNull(name, a?.type?.label).joinToString(" · ")
    }

    // ---- tasks helpers ----

    private fun ownOrder(now: ZonedDateTime): Comparator<Task> {
        val ms = now.toInstant().toEpochMilli()
        val end = dayStart(now.toLocalDate().plusDays(1), now)
        return compareBy<Task>({ !(it.due != null && it.due < ms && (it.dueHasTime || it.due < dayStart(now.toLocalDate(), now))) }, { !(it.due != null && it.due < end) }, { !it.quick }, { it.due ?: Long.MAX_VALUE }, { it.sort })
    }

    /** His open tasks for a widget: no Someday, nothing snoozed off Home, most pressing first. */
    fun ownTasks(t: TaskSnapshot, now: ZonedDateTime, snoozed: Map<String, Long> = emptyMap()): List<WRow> {
        val ms = now.toInstant().toEpochMilli()
        return t.open().filter { it.list != TaskList.SOMEDAY && (snoozed[it.id.toString()] ?: 0L) <= ms }.sortedWith(ownOrder(now)).mapNotNull { task ->
            val title = WidgetSafety.clean(task.title) ?: return@mapNotNull null
            val due = task.due?.let { Fmt.due(it, task.dueHasTime, now) }.orEmpty()
            WRow(title, if (due.isEmpty() && task.quick) "2 min" else due, if (due == "Overdue") WTone.ALERT else if (due.startsWith("Today")) WTone.ACTIVE else WTone.QUIET, Mark.CHECK, taskId = task.id, dest = WKind.AGORA.dest)
        }
    }

    /** The agents' tasks (Iris's list on Lemnos), when paired. Never sensitive ones; a tap opens Pantheon. */
    fun agentTasks(tasks: List<IrisTask>, now: ZonedDateTime): List<WRow> {
        val today = now.toLocalDate().toString()
        return tasks.filter { !it.sensitive }
            .sortedWith(compareBy({ !(it.due != null && it.due < today) }, { it.priority != "high" }, { it.due != today }, { !it.waitingOnYou }, { it.due ?: "9999" }))
            .mapNotNull { t ->
                val title = WidgetSafety.clean(t.title) ?: return@mapNotNull null
                val owner = when (t.owner?.lowercase()) { null, "", "iris" -> Workers.IRIS; "atlas" -> Workers.ATLAS; "owner" -> "You"; else -> t.owner.replaceFirstChar { it.uppercase() } }
                val overdue = t.due != null && t.due < today
                // Short, so the task keeps its room: overdue beats waiting; due today shows in the dot's colour.
                val meta = listOfNotNull(owner, if (overdue) "overdue" else if (t.waitingOnYou) "waiting" else null).joinToString(" · ")
                WRow(title, meta, if (overdue || t.priority == "high") WTone.ALERT else if (t.waitingOnYou || t.due == today) WTone.ACTIVE else WTone.QUIET, Mark.DOT, dest = WKind.PANTHEON.dest)
            }
    }

    // ---- 1. Today (Olympus) ----

    fun today(
        now: ZonedDateTime,
        cards: List<ScoredCard>,
        events: List<CalItem>,
        availability: Map<LocalDate, Availability>,
        tasks: TaskSnapshot,
        snoozed: Map<String, Long> = emptyMap(),
        weather: dev.phonerecall.aeolia.WeatherState? = null,
        /** Birthdays whose day is on for her (brain/BirthdayBook); calendar birthdays already covered by it are not repeated. */
        book: dev.phonerecall.brain.BirthdayBoard = dev.phonerecall.brain.BirthdayBoard(),
    ): WView {
        val ms = now.toInstant().toEpochMilli()
        val today = now.toLocalDate()
        val upcoming = (on(today, events, now) + on(today.plusDays(1), events, now))
            .distinctBy { it.key }
            .filter { it.category != EventCategory.BIRTHDAY && it.end > ms }
            .take(6).map { eventRow(it, now, withDay = true) }
        val k = dev.phonerecall.brain.BirthdayBook
        val birthdays = book.active.mapNotNull { WidgetSafety.clean(it.name) } + on(today, events, now)
            .filter { it.category == EventCategory.BIRTHDAY && "${k.keyOf(k.nameOf(it.title))}@$today" !in book.covered }
            .mapNotNull { WidgetSafety.clean(birthdayName(it.title)) }
        val homeCards = cards.filter { c ->
            !c.content.placeholder && (c.kind in WidgetSafety.TODAY_KINDS || (c.kind == CardKind.GENERATED && c.neverHideReason == null && sourceOf(c) in WidgetSafety.TODAY_SOURCES))
        }.mapNotNull { c -> WidgetSafety.clean(c.content.title)?.let { WRow(it, WidgetSafety.clean(c.kicker).orEmpty(), c.content.tone.w(), Mark.DOT, dest = WKind.TODAY.dest) } }
        val alert = cards.firstOrNull { it.kind == CardKind.SECURITY && it.content.tone == Tone.ALERT }?.let { WRow("Cerberus needs you", "Open", WTone.ALERT, Mark.DOT, dest = WKind.CERBERUS.dest) }
        val a = availability[today]
        val sub = listOfNotNull(a?.type?.label, birthdays.firstOrNull()?.let { "$it's birthday" }).joinToString(" · ")
        return WView(
            kind = WKind.TODAY,
            title = Fmt.date(now),
            sub = sub,
            groups = listOfNotNull(
                WGroup("Next", upcoming, "Nothing else on today"),
                WGroup("Praxis", ownTasks(tasks, now, snoozed).take(5), "No tasks due"),
                WGroup("Olympus", listOfNotNull(alert) + homeCards.take(4)).takeIf { it.rows.isNotEmpty() },
                WGroup("Aeolia", weatherRows(weather, ms), "Weather not checked yet"),
            ),
            tone = if (alert != null) WTone.ALERT else WTone.QUIET,
        )
    }

    /** Current temperature and the next change, plus one row per Severe or Extreme NWS alert. Stale (6 h+) shows nothing. */
    internal fun weatherRows(w: dev.phonerecall.aeolia.WeatherState?, ms: Long): List<WRow> {
        if (w == null || !w.hasData || ms - w.updatedAt > 6 * 3_600_000L) return emptyList()
        val f = { c: Double -> "${Math.round(c * 9 / 5 + 32)}°" }
        val now = listOfNotNull(w.tempC?.let(f), w.next).joinToString(" · ")
        val hl = listOfNotNull(w.highC?.let { "H ${f(it)}" }, w.lowC?.let { "L ${f(it)}" }).joinToString(" ")
        val alerts = w.alerts.filter { it.severity in setOf("Severe", "Extreme") }.take(2)
            .map { WRow(it.event, "Alert", WTone.ALERT, Mark.DOT, dest = "WEATHER") }
        return alerts + WRow(now.ifEmpty { "Weather" }, hl, WTone.QUIET, Mark.NONE, dest = "WEATHER")
    }

    private fun sourceOf(c: ScoredCard): CardSource? = CardSource.entries.firstOrNull { c.learnKey == "GENERATED:${it.name}" }

    // ---- 2. Chronos ----

    fun chronos(now: ZonedDateTime, events: List<CalItem>, availability: Map<LocalDate, Availability>): WView {
        val ms = now.toInstant().toEpochMilli()
        val days = (0..2).map { now.toLocalDate().plusDays(it.toLong()) }
        val groups = days.map { d ->
            val list = on(d, events, now).filter { d != now.toLocalDate() || it.end > ms || it.allDay }
            val (bdays, rest) = list.partition { it.category == EventCategory.BIRTHDAY }
            WGroup(dayLabel(d, now, availability[d]), (bdays + rest).map { eventRow(it, now) }, "Nothing on")
        }
        val next = on(now.toLocalDate(), events, now).firstOrNull { !it.allDay && it.end > ms && it.category != EventCategory.BIRTHDAY }
        val title = next?.let { n -> val r = eventRow(n, now); if (n.begin <= ms) "${r.text}, now" else "${r.text} at ${r.meta}" } ?: "Nothing else today"
        return WView(WKind.CHRONOS, title, availability[now.toLocalDate()]?.type?.label.orEmpty(), groups)
    }

    // ---- 3. Praxis ----

    fun agora(now: ZonedDateTime, tasks: TaskSnapshot, agent: List<WRow>, snoozed: Map<String, Long> = emptyMap()): WView {
        val own = ownTasks(tasks, now, snoozed)
        val overdue = own.count { it.meta == "Overdue" } + agent.count { it.meta.endsWith("overdue") }
        val total = own.size + agent.size
        return WView(
            WKind.AGORA,
            title = if (total == 0) "Nothing open" else "$total open",
            sub = if (overdue > 0) "$overdue overdue" else "",
            groups = listOfNotNull(
                WGroup("Yours", own.take(10), "Nothing of yours open"),
                WGroup("${Workers.IRIS} and ${Workers.ATLAS}", agent.take(6)).takeIf { agent.isNotEmpty() },
            ),
            tone = if (overdue > 0) WTone.ALERT else WTone.QUIET,
        )
    }

    // ---- 4. Delphi ----

    fun delphi(): WView = WView(WKind.DELPHI, "Ask Athena")

    /** Ask Zeus: no data, only the way in to Hermes's command sheet. */
    fun hermes(): WView = WView(WKind.HERMES, "Open, call, set an alarm")

    // ---- 5. Venus ----

    /** Today's outfit: the same pick as Home's wardrobe card (worn and "Another" included). */
    fun venus(card: CardContent?, imported: Boolean): WView {
        if (card == null) return WView(WKind.VENUS, if (imported) "Nothing picked today" else "Closet not imported yet")
        val labels = card.facts.filter { it.label != "Wear" }.map { it.value }
        return WView(
            WKind.VENUS,
            title = card.title,
            sub = if (card.status == "Wearing") "Wearing today" else card.body.orEmpty(),
            pics = card.photos.mapIndexed { i, p -> WPic(p.path, p.bg, p.fit, labels.getOrNull(i).orEmpty()) },
            tone = WTone.OK,
        )
    }

    // ---- 6. Music ----

    fun music(n: NowPlaying?): WView =
        if (n == null) WView(WKind.MUSIC, "Nothing playing", "Play resumes your last track")
        else WView(WKind.MUSIC, n.title, listOf(n.artist, n.source.label).filter { it.isNotBlank() }.joinToString(" · "), playing = n.playing, tone = if (n.playing) WTone.OK else WTone.QUIET)

    // ---- 7. Lemnos ----

    private fun checked(at: Long?, now: ZonedDateTime): String {
        if (at == null) return ""
        val t = Instant.ofEpochMilli(at).atZone(now.zone)
        return "Checked " + if (t.toLocalDate() == now.toLocalDate()) Fmt.time(t) else Fmt.shortDate(t) + ", " + Fmt.time(t)
    }

    private fun pct(used: Double, total: Double) = if (total <= 0) null else (used / total * 100).toInt().coerceIn(0, 100)

    fun lemnos(s: LemnosState, now: ZonedDateTime): WView {
        if (s.pairing != DeviceKeys.State.PAIRED) return WView(WKind.LEMNOS, "Not paired", "Pair the phone in Lemnos")
        val h = s.health
        val down = s.online == false
        if (h == null) return WView(WKind.LEMNOS, if (down) "Not answering" else "No reading yet", foot = checked(s.syncedAt, now), tone = if (down) WTone.ALERT else WTone.QUIET)
        val cpu = pct(h.load.firstOrNull() ?: 0.0, h.cpus.toDouble())
        val ram = pct((h.memTotal - h.memAvailable).toDouble(), h.memTotal.toDouble())
        val disk = (h.disks.firstOrNull { it.root == "home" } ?: h.disks.firstOrNull())?.let { d -> if (d.used != null && d.total != null) pct(d.used.toDouble(), d.total.toDouble()) else null }
        fun row(label: String, v: Int?, warn: Int) = v?.let { WRow(label, "$it%", if (it >= warn) WTone.ALERT else if (it >= warn - 20) WTone.ACTIVE else WTone.OK, Mark.DOT) }
        val rows = listOfNotNull(row("CPU", cpu, 90), row("Memory", ram, 90), row("Disk", disk, 90))
        val hot = rows.any { it.tone == WTone.ALERT }
        return WView(
            WKind.LEMNOS,
            title = if (down) "Not answering" else "Up ${dev.phonerecall.lemnos.Fmt.uptime(h.uptime)}",
            sub = if (down) "Last reading below" else "",
            groups = listOf(WGroup("", rows)),
            foot = checked(s.syncedAt, now),
            tone = if (down || hot) WTone.ALERT else WTone.OK,
        )
    }

    // ---- 8. Cerberus ----

    /** An alert title for the widget: masked, and replaced by its source when it cannot be shown. */
    fun alertTitle(a: dev.phonerecall.lemnos.Alert): String =
        WidgetSafety.clean(a.title) ?: "New ${a.severity} alert from ${WidgetSafety.clean(a.source.replaceFirstChar { it.uppercase() }) ?: "Cerberus"}"

    fun cerberus(s: LemnosState, now: ZonedDateTime): WView {
        if (s.pairing != DeviceKeys.State.PAIRED) return WView(WKind.CERBERUS, "Not paired", "Pair the phone in Lemnos")
        val unseen = s.alerts.filter { !it.ack }
        val by = listOf("high", "medium", "low").associateWith { sev -> unseen.count { it.severity == sev } }
        val latestHigh = unseen.filter { it.severity == "high" }.maxByOrNull { it.ts }
        val title = if (unseen.isEmpty()) "All clear" else by.filterValues { it > 0 }.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val counts = listOf(
            WRow("High", by.getValue("high").toString(), if (by.getValue("high") > 0) WTone.ALERT else WTone.QUIET, Mark.DOT),
            WRow("Medium", by.getValue("medium").toString(), if (by.getValue("medium") > 0) WTone.ACTIVE else WTone.QUIET, Mark.DOT),
            WRow("Low", by.getValue("low").toString(), WTone.QUIET, Mark.DOT),
        )
        val latest = latestHigh?.let { a ->
            val t = Instant.ofEpochMilli((a.ts * 1000).toLong()).atZone(now.zone)
            WGroup("Latest high", listOf(WRow(alertTitle(a), Fmt.time(t), WTone.ALERT, Mark.NONE)))
        }
        return WView(
            WKind.CERBERUS, title, if (unseen.isEmpty()) "Nothing unseen" else "Unseen",
            groups = listOfNotNull(latest, WGroup("Unseen", counts)),
            foot = checked(s.syncedAt, now),
            tone = if (by.getValue("high") > 0) WTone.ALERT else if (unseen.isNotEmpty()) WTone.ACTIVE else WTone.OK,
        )
    }

    // ---- 9. Pantheon ----

    /** The latest moment a worker did something Zeus can see: a job update, its session, a chat line. */
    fun lastActivity(id: String, s: LemnosState): Long? {
        val jobs = s.jobs.filter { it.to == id }.maxOfOrNull { (it.updated * 1000).toLong() }
        val session = s.sessions.filter { it.agent == id }.maxOfOrNull { it.activity * 1000 }
        val chat = s.chat.values.flatten().filter { it.sender == id }.maxOfOrNull { (it.ts * 1000).toLong() }
        return listOfNotNull(jobs, session, chat).maxOrNull()
    }

    fun pantheon(s: LemnosState, now: ZonedDateTime): WView {
        if (s.pairing != DeviceKeys.State.PAIRED) return WView(WKind.PANTHEON, "Not paired", "Pair the phone in Lemnos")
        val workers = listOf("iris" to Workers.IRIS, "atlas" to Workers.ATLAS, "echo" to Workers.ECHO)
        val rows = workers.map { (id, name) ->
            val a: AgentStatus? = s.agents.firstOrNull { it.id == id }
            val (word, tone) = workerStatus(a)
            val at = lastActivity(id, s)?.let { ms -> Instant.ofEpochMilli(ms).atZone(now.zone).let { t -> if (t.toLocalDate() == now.toLocalDate()) Fmt.time(t) else Fmt.shortDate(t).substringAfter(", ") } }
            WRow(name, listOfNotNull(word, at).joinToString(" · "), tone.w(), Mark.DOT, dest = WKind.PANTHEON.dest)
        }
        val down = rows.filter { it.tone == WTone.ALERT }
        val busy = rows.count { it.meta.startsWith("Thinking") }
        val title = when {
            down.isNotEmpty() -> down.joinToString(" and ") { it.text } + " down"
            busy > 0 -> "$busy thinking"
            rows.all { it.tone == WTone.OK } -> "All ready"
            else -> "Workers"
        }
        return WView(WKind.PANTHEON, title, groups = listOf(WGroup("", rows)), foot = checked(s.syncedAt, now), tone = if (down.isNotEmpty()) WTone.ALERT else WTone.OK)
    }

    // ---- 10. Metron ----

    private fun level(pct: Double?) = when (dev.phonerecall.metron.Level.of(pct)) {
        dev.phonerecall.metron.Level.HOT -> WTone.ALERT
        dev.phonerecall.metron.Level.WARN -> WTone.ACTIVE
        dev.phonerecall.metron.Level.OK -> if (pct == null) WTone.QUIET else WTone.OK
    }

    /** The workers' plan windows and Athena's cap, as percentages only: no amounts on a home screen. */
    fun metron(u: dev.phonerecall.metron.UsageSnapshot?, b: dev.phonerecall.metron.Budget?, now: ZonedDateTime): WView {
        val groups = listOf("iris" to Workers.IRIS, "atlas" to Workers.ATLAS).map { (id, name) ->
            val a = u?.agent(id)
            WGroup(name, a?.windows.orEmpty().filter { it.label == "5h" || it.label == "7d" }.map { w ->
                val reset = w.resetsAt?.let { Instant.ofEpochSecond(it).atZone(now.zone) }?.let { t -> if (t.toLocalDate() == now.toLocalDate()) Fmt.time(t) else Fmt.shortDate(t).substringBefore(',') }
                WRow(w.words.replaceFirstChar { it.uppercase() }, (w.usedPct?.let { "${it.toInt()}%" } ?: "reset") + (reset?.let { ", resets $it" }.orEmpty()), level(w.usedPct), Mark.DOT, dest = WKind.METRON.dest)
            }, "No reading yet")
        } + listOfNotNull(b?.let {
            WGroup("Athena", listOf(
                WRow("This month", "${it.pct.toInt()}% of cap", level(it.pct), Mark.DOT, dest = WKind.METRON.dest),
                WRow("Month end", "${it.projectedPct.toInt()}% at this pace", level(it.projectedPct), Mark.DOT, dest = WKind.METRON.dest),
            ))
        })
        val worst = u?.agents.orEmpty().mapNotNull { a -> a.worst?.let { a to it } }.maxByOrNull { it.second.usedPct ?: 0.0 }
        val title = when {
            worst != null && (worst.second.usedPct ?: 0.0) >= dev.phonerecall.metron.Level.WARN_PCT -> "${worst.first.name} at ${worst.second.usedPct!!.toInt()}%"
            b != null && b.pct >= dev.phonerecall.metron.Level.WARN_PCT -> "Athena at ${b.pct.toInt()}%"
            u == null -> "No reading yet"
            else -> "All under 80%"
        }
        val tones = groups.flatMap { g -> g.rows.map { it.tone } }
        return WView(
            WKind.METRON, title, groups = groups,
            foot = u?.fetchedAt?.takeIf { it > 0 }?.let { checked(it, now) }.orEmpty(),
            tone = if (WTone.ALERT in tones) WTone.ALERT else if (WTone.ACTIVE in tones) WTone.ACTIVE else if (u == null) WTone.QUIET else WTone.OK,
        )
    }

    // ---- 11. Odyssey: the last drive ----

    private const val DAY = 86_400_000L
    private const val MI = 1609.344

    private fun at(ms: Long, now: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(ms).atZone(now.zone)
        return if (t.toLocalDate() == now.toLocalDate()) Fmt.time(t) else Fmt.shortDate(t).substringBefore(',') + " " + Fmt.time(t)
    }

    /**
     * The last drive (score, minutes, miles, whether it was the commute), the week's miles against the lease's
     * weekly share, and today's leave-by: Odyssey's traffic check when there is one, else his usual time.
     * Miles only: the lease's amounts stay on his own screens.
     */
    fun drive(d: dev.phonerecall.odyssey.OdysseyData, now: ZonedDateTime): WView {
        val ms = now.toInstant().toEpochMilli()
        val last = d.drives.maxByOrNull { it.end }
        val commutes = dev.phonerecall.odyssey.Commute.trips(d).mapNotNull { it.driveId }.toSet()
        val weekMi = (d.drives.filter { ms - it.end < 7 * DAY }.sumOf { it.meters } / MI).roundToInt()
        val pace = (d.car.lease.miles * 7 / 365.25).roundToInt().coerceAtLeast(1)
        val check = d.checks.filter { it.reason == "commute" && it.expiresAt > ms && it.leaveBy > ms - 30 * 60_000L }.minByOrNull { it.leaveBy }
        val usual = dev.phonerecall.odyssey.Commute.stats(d, ms, now.zone).departures[now.dayOfWeek]
            ?.let { now.toLocalDate().atStartOfDay(now.zone).plusMinutes(it.toLong()) }?.takeIf { it.isAfter(now) }
        val leave = when {
            check != null -> WRow(
                "Leave by ${Fmt.time(Instant.ofEpochMilli(check.leaveBy).atZone(now.zone))}", if (check.delayMinutes >= 5) "+${check.delayMinutes} min" else "Clear",
                if (check.leaveBy < ms || check.delayMinutes >= 10) WTone.ALERT else if (check.delayMinutes >= 5) WTone.ACTIVE else WTone.OK, Mark.DOT, dest = WKind.DRIVE.dest,
            )
            usual != null -> WRow("Usually leave ${Fmt.time(usual)}", "Commute", WTone.QUIET, Mark.DOT, dest = WKind.DRIVE.dest)
            else -> null
        }
        val week = WRow("$weekMi of ~$pace mi", if (weekMi > pace) "Over pace" else "On pace", if (weekMi > pace * 1.1) WTone.ACTIVE else WTone.OK, Mark.DOT, dest = WKind.DRIVE.dest)
        if (last == null) return WView(WKind.DRIVE, "No drives yet", "Drives show here once Odyssey logs one", groups = listOfNotNull(leave?.let { WGroup("Commute", listOf(it)) }))
        val mi = last.meters / MI
        val miles = if (mi < 10) "%.1f mi".format(java.util.Locale.US, mi) else "${mi.roundToInt()} mi"
        val commute = last.id in commutes
        return WView(
            WKind.DRIVE,
            title = "Score ${last.score} · $miles",
            sub = listOfNotNull("${last.minutes} min", "Commute".takeIf { commute }, at(last.start, now)).joinToString(" · "),
            groups = listOfNotNull(
                WGroup("This week", listOf(week)),
                leave?.let { WGroup("Commute", listOf(it)) },
            ),
            tone = if (last.score >= 85) WTone.OK else if (last.score >= 70) WTone.ACTIVE else WTone.ALERT,
            bar = (weekMi * 100 / pace).coerceIn(0, 100),
        )
    }

    // ---- 12. Moneta: the week, no amounts ----

    private fun net(txns: List<dev.phonerecall.moneta.Txn>, from: Long, to: Long): Long = txns.filter { it.counted && it.at in from until to }.sumOf {
        when (it.kind) { dev.phonerecall.moneta.TxnKind.SPEND -> it.cents; dev.phonerecall.moneta.TxnKind.REFUND -> -it.cents; else -> 0L }
    }.coerceAtLeast(0)

    /**
     * This week's spending against a usual week as a percent (the last seven days against the four weeks
     * before them), how many charges wait on "Did you make this?", and the next renewal by name and day.
     * Never an amount: the percent and the count are the only figures.
     */
    fun money(txns: List<dev.phonerecall.moneta.Txn>, subs: List<dev.phonerecall.moneta.Subscription>, dismissed: Set<String>, now: ZonedDateTime): WView {
        val ms = now.toInstant().toEpochMilli()
        val week = net(txns, ms - 7 * DAY, ms + 1)
        val past = (1..4).map { net(txns, ms - (it + 1) * 7 * DAY, ms - it * 7 * DAY) }.filter { it > 0 }
        val usual = past.takeIf { it.size >= 2 }?.average()
        val pct = usual?.let { (week * 100 / it).roundToInt() }
        val flags = dev.phonerecall.moneta.Money.unusual(txns, ms, now.zone, dismissed).size
        val next = subs.filter { it.next != null && !it.next.isBefore(now.toLocalDate()) }.minByOrNull { it.next!! }
        return WView(
            WKind.MONEY,
            title = when {
                pct == null -> "No usual week yet"
                pct in 90..110 -> "About a usual week"
                else -> "$pct% of a usual week"
            },
            sub = if (pct == null) "Moneta needs a few weeks of alerts" else "The last 7 days",
            groups = listOfNotNull(
                WGroup("To look at", listOf(
                    if (flags == 0) WRow("Nothing to check", "", WTone.OK, Mark.DOT, dest = WKind.MONEY.dest)
                    else WRow("Did you make ${if (flags == 1) "this" else "these"}?", "$flags to check", WTone.ALERT, Mark.DOT, dest = WKind.MONEY.dest),
                )),
                next?.let { s ->
                    val d = ChronoUnit.DAYS.between(now.toLocalDate(), s.next)
                    val day = if (d == 0L) "Today" else if (d == 1L) "Tomorrow" else Fmt.shortDate(s.next!!.atStartOfDay(now.zone)).substringAfter(", ")
                    WGroup("Next due", listOf(WRow(s.name, day, if (d <= 1) WTone.ACTIVE else WTone.QUIET, Mark.DOT, dest = WKind.MONEY.dest)))
                },
            ),
            tone = if (flags > 0) WTone.ALERT else if (pct != null && pct > 125) WTone.ACTIVE else WTone.QUIET,
            bar = pct?.coerceIn(0, 100),
        )
    }

    // ---- 13. Hestia: his favourite switches ----

    /** At most four switches: his picks in Hestia, else the first lights and plugs it can control. A tile tap switches it. */
    fun hearth(s: dev.phonerecall.hestia.HomeSnapshot, favourites: List<String>): WView {
        if (s.link != dev.phonerecall.hestia.Link.Connected) return WView(WKind.HEARTH, "Not connected", "Open Hestia to connect Google Home")
        val picked = favourites.mapNotNull { s.device(it) }.filter { it.controllable }.take(4)
            .ifEmpty { s.devices.filter { it.controllable }.sortedBy { !it.online }.take(4) }
        val rows = picked.map { d ->
            val on = d.on == true && d.online
            WRow(
                d.name, when { !d.online -> "Offline"; on -> "On"; else -> "Off" }, when { !d.online -> WTone.ALERT; on -> WTone.OK; else -> WTone.QUIET }, Mark.DOT,
                dest = WKind.HEARTH.dest, act = if (d.online) "${d.id}|${if (on) "off" else "on"}" else null,
            )
        }
        val lit = s.lightsOn.size
        return WView(
            WKind.HEARTH,
            title = if (lit == 0) "Every light is off" else if (lit == 1) "1 light on" else "$lit lights on",
            groups = listOf(WGroup("", rows, "No lights or plugs yet")),
            tone = if (lit > 0) WTone.OK else WTone.QUIET,
        )
    }

    // ---- 14. Iaso: his own widget ----

    /** Steps today, the weight trend in kilograms (one decimal), last night's sleep. Only on the widget he placed. */
    fun health(s: dev.phonerecall.iaso.IasoState, now: ZonedDateTime): WView {
        val today = now.toLocalDate()
        val trend = dev.phonerecall.iaso.WeightTrend
        val goal = dev.phonerecall.iaso.Goals
        val steps = s.report.day(today)?.steps
        val daily = trend.daily(s.weights, now.zone)
        val weight = trend.smooth(daily).lastOrNull()
        val slope = trend.words(trend.weeklySlope(daily, now.toInstant().toEpochMilli()))
        val sleep = s.report.lastNight(today)
        val stepWords = steps?.let { "%,d steps".format(java.util.Locale.US, it) }
        val rows = listOfNotNull(
            steps?.let { WRow(stepWords!!, if (it >= goal.STEPS) "Goal met" else "of %,d".format(java.util.Locale.US, goal.STEPS), if (it >= goal.STEPS) WTone.OK else WTone.QUIET, Mark.DOT, dest = WKind.HEALTH.dest) },
            weight?.let { WRow(trend.format(it.kg), slope ?: "Trend", WTone.QUIET, Mark.DOT, dest = WKind.HEALTH.dest) },
            sleep?.let { n ->
                val short = n.asleepMin < goal.SHORT_SLEEP_MIN
                WRow("Slept ${Fmt.minutes(n.asleepMin)}", if (short) "Short" else "Last night", if (short) WTone.ACTIVE else WTone.OK, Mark.DOT, dest = WKind.HEALTH.dest)
            },
        )
        return WView(
            WKind.HEALTH,
            title = stepWords ?: weight?.let { trend.format(it.kg) } ?: "Nothing read yet",
            sub = if (rows.isEmpty()) "Open Iaso to read Health Connect" else "",
            groups = listOf(WGroup("", rows)),
            foot = s.report.readAt.takeIf { it > 0 }?.let { checked(it, now) }.orEmpty(),
            tone = if (steps != null && steps >= goal.STEPS) WTone.OK else WTone.QUIET,
        )
    }

    // ---- 15. Eos: the morning brief ----

    /** The brief's lede and its first three lines for today. After noon it says it is the morning's: Eos has no evening view yet. */
    fun brief(b: dev.phonerecall.brief.MorningBrief?, now: ZonedDateTime): WView {
        if (b == null || b.date != now.toLocalDate().toString()) return WView(WKind.BRIEF, "No brief yet today", "It comes after your alarm")
        return WView(
            WKind.BRIEF,
            title = b.lede ?: b.title,
            sub = if (now.hour >= 12) "This morning" else b.title.substringBefore(" · "),
            groups = listOf(WGroup("", b.today.take(3).map { WRow(it.text, it.label, WTone.QUIET, Mark.DOT, dest = WKind.BRIEF.dest) }, "Nothing else today")),
            foot = Fmt.time(Instant.ofEpochMilli(b.postedAt).atZone(now.zone)),
        )
    }

    // ---- 16. Agora: the agents' room ----

    private fun sender(id: String) = when (id) { "iris" -> Workers.IRIS; "atlas" -> Workers.ATLAS; "echo" -> Workers.ECHO; "owner" -> "You"; else -> id.replaceFirstChar { it.uppercase() } }

    /** The latest post (who and one line) with the unread dot, then the posts before it. */
    fun room(s: LemnosState, now: ZonedDateTime): WView {
        if (s.pairing != DeviceKeys.State.PAIRED) return WView(WKind.ROOM, "Not paired", "Pair the phone in Lemnos")
        val msgs = s.chat[dev.phonerecall.lemnos.Agora.ROOM].orEmpty().filter { it.kind != "notice" && it.text.isNotBlank() }.sortedBy { it.id }
        val unread = dev.phonerecall.lemnos.Agora.unread(msgs, s.agoraSeen)
        val latest = msgs.lastOrNull { it.sender != "owner" } ?: msgs.lastOrNull()
            ?: return WView(WKind.ROOM, "Quiet in the room", "Nothing posted yet", foot = checked(s.syncedAt, now))
        fun line(m: dev.phonerecall.lemnos.ChatMsg) = WidgetSafety.clean(m.text) ?: "A message to read in Zeus"
        val rows = msgs.takeLast(3).reversed().map { m ->
            WRow(line(m), "${sender(m.sender)} · ${at((m.ts * 1000).toLong(), now)}", if (m.id > s.agoraSeen && m.sender != "owner") WTone.ACTIVE else WTone.QUIET, Mark.DOT, dest = WKind.ROOM.dest)
        }
        return WView(
            WKind.ROOM,
            title = "${sender(latest.sender)}: ${line(latest)}",
            sub = if (unread) "New since you looked" else "",
            groups = listOf(WGroup("Latest", rows)),
            foot = checked(s.syncedAt, now),
            tone = if (unread) WTone.ACTIVE else WTone.QUIET,
        )
    }
}
