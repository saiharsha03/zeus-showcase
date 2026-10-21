package dev.phonerecall.hestia

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Step
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.stateJson
import dev.phonerecall.shortcuts.Evidence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * Home routines: WHEN something happens, IF the conditions hold, THEN switch lights, plugs and switches (and maybe
 * play music, set an alarm or put up a card). He describes one to Athena in his own words; she drafts it with
 * propose_routine, the phone checks it here (RoutineSpec) and he confirms one card with the routine in one sentence.
 * Four presets ship (leaving home, home after dark, getting dark, looking asleep). Steps run through Fates as his own
 * standing commands, so the same validator and HestiaCore guard refuse locks and every forbidden kind; a device he
 * marks "Never automate" (his computer) is never touched unless a routine names it on purpose. Never a message, a
 * call or a payment. Everything here is pure; the phone side is RoutinesAndroid.kt.
 */

/** WHEN. [wired]: the phone can start a routine on it today; the others go to Prometheus to assess. */
enum class Moment(val wire: String, val trigger: String, val triggerLabel: String, val wired: Boolean = true, val clock: Boolean = false) {
    LEAVE("leave", "leave:home", "you leave home"),
    ARRIVE("arrive", "arrive:home", "you get home"),
    DUSK("sunset", "sun:set", "the sun sets", clock = true),
    SUNRISE("sunrise", "sun:rise", "the sun rises", clock = true),
    TIME("time", "time", "it's the time", clock = true),
    ASLEEP("asleep", "bedtime", "it's your bedtime"),
    WAKE("wake", "alarm:dismissed", "you dismiss your alarm"),
    CAR_ON("car_on", "car:on", "the phone connects to the car"),
    CAR_OFF("car_off", "car:off", "the phone disconnects from the car"),
    CHARGING("charging", "charge:on", "the phone starts charging", wired = false),
    UNPLUGGED("unplugged", "charge:off", "the phone is unplugged", wired = false),
    EVENT_START("event_start", "event:start", "a calendar event starts", wired = false),
    EVENT_END("event_end", "event:end", "a calendar event ends", wired = false);

    companion object {
        fun of(trigger: String): Moment? = entries.firstOrNull { it.trigger == trigger }
        fun wire(w: String): Moment? = entries.firstOrNull { it.wire == w }
    }
}

/** IF: home (yes or no), dark (yes or no), days (weekdays, weekend or mon,tue...), after or before (HH:MM), device ("<id>:on" or off). */
data class Cond(val type: String = "", val value: String = "")

/** THEN, beyond the lights block: device (device_id, action, value), music (query or uri, name), alarm (hour, minute, label), card (title, body). */
data class Act(val type: String = "", val args: Map<String, String> = emptyMap())

data class RunLine(val at: Long = 0, val words: String = "", val ran: Boolean = true)

/**
 * One routine. The lights block: [rooms] picks lights by room ("*" every room), [plugs] adds every plug and switch,
 * [devices] the ones named, [except] keeps some out; with [learn], the lights he usually switches at this moment
 * replace the rooms once there are any. [then] holds everything else it does.
 */
data class Routine(
    val id: String = "",
    val name: String = "",
    val moment: Moment = Moment.LEAVE,
    /** ARRIVE and LEAVE: a place other than home ("work", or a place's name). */
    val place: String? = null,
    /** TIME: the minute of the day. */
    val atMin: Int? = null,
    /** DUSK and SUNRISE: minutes after (negative is before). */
    val offsetMin: Int = 0,
    val ifs: List<Cond> = emptyList(),
    val on: Boolean = true,
    val turnOn: Boolean = false,
    val rooms: List<String> = emptyList(),
    val plugs: Boolean = false,
    val devices: List<String> = emptyList(),
    val except: List<String> = emptyList(),
    /** Devices marked Never automate that he named on purpose for this routine. */
    val overrides: List<String> = emptyList(),
    val learn: Boolean = false,
    val then: List<Act> = emptyList(),
    /** ASLEEP: not before this minute of the evening when Hypnos has no bedtime tonight. */
    val afterMin: Int = 23 * 60 + 30,
    /** ASLEEP: the screen off at least this long. */
    val idleMin: Int = 20,
    /** preset, chat (he described it to Athena) or suggestion (his Yes on one of hers). */
    val source: String = "preset",
    val runs: Int = 0,
    val lastRun: Long? = null,
    val lastWords: String? = null,
    /** The Fates proposals of the last run, for Undo. */
    val lastIds: List<Long> = emptyList(),
    val history: List<RunLine> = emptyList(),
) {
    /** The trigger key in the learned-shortcuts evidence. */
    val triggerKey: String get() = when {
        place != null && moment == Moment.ARRIVE -> "arrive:${place.lowercase()}"
        place != null && moment == Moment.LEAVE -> "leave:${place.lowercase()}"
        else -> moment.trigger
    }
    val placeKey: String get() = place?.lowercase() ?: "home"
    val switches: Boolean get() = rooms.isNotEmpty() || plugs || devices.isNotEmpty() || learn
    /** Lights on, music: what would wake him. */
    val wakes: Boolean get() = (turnOn && switches) || then.any { it.type == "music" || (it.type == "device" && it.args["action"] != "off") }
}

data class RoutinesData(
    val routines: List<Routine> = RoutineRules.defaults(),
    /** Devices no routine touches unless it names them on purpose ("Never automate" in Hestia). */
    val never: Set<String> = emptySet(),
    /** The computer pre-tag ran once on his real devices. */
    val seeded: Boolean = false,
    /** Last arrival and departure per place ("home", "work", a place's name). */
    val arrived: Map<String, Long> = emptyMap(),
    val left: Map<String, Long> = emptyMap(),
    /** When he last looked asleep at home; cleared by the morning or the screen. */
    val asleepAt: Long? = null,
    /** Dates the sunset and bedtime triggers went to the learned-shortcuts evidence. */
    val sunsetSeen: String? = null,
    val bedtimeSeen: String? = null,
    /** Fallback idle clock when UsageStats can't say: the first tick that saw the screen off. */
    val screenOffSeen: Long? = null,
) {
    fun routine(id: String): Routine? = routines.firstOrNull { it.id == id }
}

/** The phone as a routine sees it at one moment. [offSince] null: the screen is on, or nobody knows. */
data class RoutineNow(
    val at: Long,
    val zone: ZoneId,
    val home: Boolean? = null,
    val dark: Boolean = false,
    val offSince: Long? = null,
    val media: Boolean = false,
    /** Hypnos's bedtime tonight, when it has one. */
    val bedtime: Long? = null,
    /** Today's sunrise and sunset at home. */
    val sunrise: Long? = null,
    val sunset: Long? = null,
)

sealed class RoutinePlan {
    data class Go(val routine: Routine, val devices: List<HomeDevice>, val extra: List<RawStep>) : RoutinePlan()
    data class Skip(val routine: Routine, val why: String) : RoutinePlan()
}

/** What one routine did: the proposals it ran (for Undo) and its words, or why it didn't. */
data class RoutineOutcome(val routine: Routine, val ids: List<Long> = emptyList(), val words: String? = null, val skipped: String? = null)

/** Sunrise and sunset from the NOAA sunrise equation, within a minute or two. No network: the home's own coordinates. */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)

    /** (sunrise, sunset) on [date] in ms, or null in polar day or night. */
    fun times(date: LocalDate, lat: Double, lng: Double): Pair<Long, Long>? {
        val n = date.toEpochDay() + 2440588.0 - 2451545.0 + 0.0008
        val jStar = n - lng / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val c = 1.9148 * sin(rad(m)) + 0.0200 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = rad((m + c + 180 + 102.9372).mod(360.0))
        val transit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(rad(23.4397)))
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w = Math.toDegrees(acos(cosW)) / 360.0
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000L).roundToLong()
        return ms(transit - w) to ms(transit + w)
    }

    /** Dark now: before sunrise or after sunset. Without the home's coordinates, 7 PM to 7 AM. */
    fun dark(at: Long, zone: ZoneId, lat: Double?, lng: Double?): Boolean {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val s = if (lat != null && lng != null) times(t.toLocalDate(), lat, lng) else null
        return if (s == null) t.hour >= 19 || t.hour < 7 else at < s.first || at > s.second
    }
}

object RoutineRules {
    /** Kinds the lights block may switch. A named device may also be a fan; never anything forbidden. */
    val KINDS = setOf(DeviceKind.LIGHT, DeviceKind.PLUG, DeviceKind.SWITCH)
    /** A device named like his computer is never automated until he says otherwise. */
    private val COMPUTER = Regex("""\b(computer|pc|desktop|workstation)\b""", RegexOption.IGNORE_CASE)
    /** Lights switched at a moment on this many days count as his usual set. */
    const val LEARN_DAYS = 2
    /** The last hour a night lasts. */
    const val MORNING = 5
    /** Looking asleep never starts before this hour, whatever Hypnos says. */
    const val EARLIEST = 22
    /** A clock routine (sunset, sunrise, a time) may still run this long after its moment, once a day. */
    const val CLOCK_WINDOW_MS = 3 * 3_600_000L

    fun defaults(): List<Routine> = listOf(
        Routine("leave", "Leaving home", Moment.LEAVE, turnOn = false, rooms = listOf("Bedroom"), plugs = true),
        Routine("arrive", "Home after dark", Moment.ARRIVE, ifs = listOf(Cond("dark", "yes")), turnOn = true, rooms = listOf("Living room"), learn = true),
        Routine("dusk", "Getting dark", Moment.DUSK, ifs = listOf(Cond("home", "yes")), turnOn = true, rooms = listOf("Living room"), learn = true),
        Routine("asleep", "Looks asleep", Moment.ASLEEP, turnOn = false, rooms = listOf("*")),
    )

    fun roomMatch(room: String?, picks: List<String>): Boolean =
        "*" in picks || (room != null && picks.any { p -> room.equals(p, true) || room.contains(p, true) })

    /** The devices named like a computer, to pre-tag "Never automate" once. */
    fun computers(s: HomeSnapshot): List<HomeDevice> = s.devices.filter { !it.kind.forbidden && COMPUTER.containsMatchIn(it.name) }

    /** The first real device list: his computer pre-tagged. Runs once; afterwards only he changes the list. */
    fun seed(d: RoutinesData, s: HomeSnapshot): RoutinesData =
        if (d.seeded || s.link != Link.Connected || s.devices.isEmpty()) d else d.copy(seeded = true, never = d.never + computers(s).map { it.id })

    /** Lights he switched this way on [LEARN_DAYS] or more days right after the moment, from the evidence. */
    fun learned(r: Routine, evidence: List<Evidence>): List<String> {
        val verb = if (r.turnOn) "on" else "off"
        return evidence.filter { it.trigger == r.triggerKey && it.days >= LEARN_DAYS && it.action.startsWith("home:") && it.action.endsWith(":$verb") }
            .map { it.action.removePrefix("home:").removeSuffix(":$verb") }.distinct()
    }

    /** The lights block's devices now: only ones it may touch, only ones not already that way. */
    fun targets(r: Routine, s: HomeSnapshot, never: Set<String>, learned: List<String>): List<HomeDevice> {
        val usual = learned.takeIf { r.learn && it.isNotEmpty() }
        return s.devices.filter { d ->
            val named = d.id in r.devices
            val picked = named || (usual?.contains(d.id) ?: (d.kind == DeviceKind.LIGHT && roomMatch(s.room(d.roomId)?.name, r.rooms))) ||
                (r.plugs && !r.turnOn && d.kind != DeviceKind.LIGHT)
            val allowed = (d.kind in KINDS || (named && d.kind == DeviceKind.FAN)) && d.controllable && (d.id !in never || d.id in r.overrides)
            picked && allowed && d.id !in r.except && d.online && (if (r.turnOn) d.on != true else d.on == true)
        }
    }

    /** When tonight's night starts for [r]: Hypnos's bedtime when it has one, else his setting; never before 10 PM. */
    fun nightStart(r: Routine, now: RoutineNow): Long {
        val t = Instant.ofEpochMilli(now.at).atZone(now.zone)
        val evening = if (t.hour < 12) t.toLocalDate().minusDays(1) else t.toLocalDate()
        val floor = evening.atTime(EARLIEST, 0).atZone(now.zone).toInstant().toEpochMilli()
        val mine = evening.atStartOfDay(now.zone).plusMinutes(r.afterMin.toLong().let { if (it < 12 * 60) it + 1440 else it }).toInstant().toEpochMilli()
        val end = evening.plusDays(1).atTime(MORNING, 0).atZone(now.zone).toInstant().toEpochMilli()
        return now.bedtime?.takeIf { it in floor - 12 * 3_600_000L until end }?.let { maxOf(floor, it) } ?: maxOf(floor, mine)
    }

    private fun morningAfter(at: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val day = if (t.hour < MORNING) t.toLocalDate() else t.toLocalDate().plusDays(1)
        return day.atTime(MORNING, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** He looked asleep at home and hasn't touched the phone since, and it is still that night. */
    fun asleep(d: RoutinesData, now: RoutineNow): Boolean {
        val a = d.asleepAt ?: return false
        return now.at < morningAfter(a, now.zone) && now.offSince != null && now.offSince <= a
    }

    /** Null when he looks asleep now, else why not. */
    fun looksAsleep(r: Routine, now: RoutineNow): String? {
        val t = Instant.ofEpochMilli(now.at).atZone(now.zone)
        if (now.home != true) return "not home"
        if (t.hour in MORNING until 12 || now.at < nightStart(r, now)) return "not his bedtime yet"
        if (now.media) return "something is playing"
        val off = now.offSince ?: return "the screen is on"
        val idle = (now.at - off) / 60_000L
        if (idle < r.idleMin) return "the screen was on $idle min ago"
        return null
    }

    /** When a clock routine's moment is today, or null when there is none (no coordinates for the sun). */
    fun clockAt(r: Routine, now: RoutineNow): Long? = when (r.moment) {
        Moment.DUSK -> now.sunset?.plus(r.offsetMin * 60_000L)
        Moment.SUNRISE -> now.sunrise?.plus(r.offsetMin * 60_000L)
        Moment.TIME -> r.atMin?.let { m -> Instant.ofEpochMilli(now.at).atZone(now.zone).toLocalDate().atStartOfDay(now.zone).plusMinutes(m.toLong()).toInstant().toEpochMilli() }
        else -> null
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId) = Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    private fun minute(hhmm: String): Int? = Regex("""(\d{1,2}):(\d{2})""").matchEntire(hhmm.trim())?.destructured?.let { (h, m) -> (h.toInt() * 60 + m.toInt()).takeIf { h.toInt() < 24 && m.toInt() < 60 } }

    /** Null when the condition holds now, else it in words. */
    fun failing(c: Cond, s: HomeSnapshot, now: RoutineNow): String? {
        val t = Instant.ofEpochMilli(now.at).atZone(now.zone)
        val yes = c.value != "no"
        val mod = t.hour * 60 + t.minute
        return when (c.type) {
            "home" -> if ((now.home == true) == yes) null else if (yes) "not home" else "home"
            "dark" -> if (now.dark == yes) null else if (yes) "not dark yet" else "already dark"
            "days" -> if (days(c.value).contains(t.dayOfWeek)) null else "not ${c.value}"
            "after" -> { val m = minute(c.value) ?: return "bad time ${c.value}"; if (mod >= m) null else "before ${c.value}" }
            "before" -> { val m = minute(c.value) ?: return "bad time ${c.value}"; if (mod < m) null else "after ${c.value}" }
            "device" -> {
                val id = c.value.substringBeforeLast(':'); val want = c.value.substringAfterLast(':') == "on"
                val d = s.device(id) ?: return "that device is gone"
                if ((d.on == true) == want) null else "${d.name} is ${if (want) "off" else "on"}"
            }
            else -> "can't check ${c.type}"
        }
    }

    fun days(v: String): Set<DayOfWeek> = when (v.lowercase()) {
        "weekdays" -> DayOfWeek.entries.take(5).toSet()
        "weekend", "weekends" -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> v.lowercase().split(',', ' ').mapNotNull { w -> DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(w.trim().take(3)) && w.trim().length >= 3 } }.toSet()
    }

    /** Whether [r] runs at its moment now, and on what. The quiet rules live here. */
    fun plan(r: Routine, s: HomeSnapshot, d: RoutinesData, now: RoutineNow, learned: List<String>): RoutinePlan {
        fun skip(why: String) = RoutinePlan.Skip(r, why)
        if (!r.on) return skip("turned off")
        if (s.link != Link.Connected) return skip(HomeRules.linkWords(s.link))
        val asleep = asleep(d, now)
        val place = r.placeKey
        when (r.moment) {
            Moment.LEAVE -> {
                if (asleep && place == "home") return skip("you look asleep, so this is a GPS jump, not a departure")
                val left = d.left[place]
                if (left != null && left > (d.arrived[place] ?: 0) && now.at - left < 2 * 3_600_000L) return skip("already left")
            }
            Moment.ARRIVE -> {
                if (asleep && place == "home") return skip("you look asleep; no lights flicker on")
                val arrived = d.arrived[place]
                if (arrived != null && arrived > (d.left[place] ?: 0) && now.at - arrived < 8 * 3_600_000L) return skip("already there")
                d.left[place]?.let { if (now.at - it < 5 * 60_000L) return skip("back within 5 minutes (a geofence bounce)") }
            }
            Moment.ASLEEP -> {
                looksAsleep(r, now)?.let { return skip(it) }
                if (r.lastRun?.let { it >= nightStart(r, now) } == true) return skip("already ran tonight")
            }
            else -> if (r.moment.clock && r.lastRun?.let { sameDay(it, now.at, now.zone) } == true) return skip("already ran today")
        }
        if (asleep && r.wakes && r.moment != Moment.WAKE) return skip("you look asleep")
        for (c in r.ifs) failing(c, s, now)?.let { return skip(it) }
        val ds = if (r.switches) targets(r, s, d.never, learned) else emptyList()
        val extra = r.then.mapNotNull { a -> step(a, s, d.never, r) }
        return if (ds.isEmpty() && extra.isEmpty()) skip("nothing to switch") else RoutinePlan.Go(r, ds, extra)
    }

    /** One THEN item as a Fates step; null when its device is gone, offline or no longer allowed. */
    private fun step(a: Act, s: HomeSnapshot, never: Set<String>, r: Routine): RawStep? = when (a.type) {
        "device" -> s.device(a.args["device_id"].orEmpty())?.takeIf { it.online && it.controllable && !it.kind.forbidden && (it.id !in never || it.id in r.overrides) }
            ?.let { RawStep(ProposalKind.HOME_CONTROL, a.args) }
        "music" -> RawStep(ProposalKind.PLAY_MUSIC, a.args)
        "alarm" -> RawStep(ProposalKind.SET_ALARM, a.args)
        "card" -> RawStep(ProposalKind.CREATE_CARD, a.args)
        else -> null
    }

    /** Fates steps for a plan, at most eight to a proposal. */
    fun steps(p: RoutinePlan.Go): List<List<RawStep>> =
        (p.devices.map { RawStep(ProposalKind.HOME_CONTROL, mapOf("device_id" to it.id, "action" to if (p.routine.turnOn) "on" else "off")) } + p.extra).chunked(8)

    fun words(p: RoutinePlan.Go, s: HomeSnapshot): String {
        val names = p.devices.map { it.name }
        val list = if (names.size <= 3) names.joinToString(", ") else names.take(2).joinToString(", ") + " and ${names.size - 2} more"
        val switched = if (names.isEmpty()) null else "$list ${if (p.routine.turnOn) "on" else "off"}"
        return (listOfNotNull(switched) + p.routine.then.filter { a -> p.extra.any { it.args == a.args } }.map { actWords(it, s) }).joinToString("; ")
    }

    // ---- words ----

    fun clock(minute: Int): String {
        val h = (minute / 60) % 24; val m = minute % 60
        return "${if (h % 12 == 0) 12 else h % 12}:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
    }

    private fun hhmm(v: String) = minute(v)?.let { clock(it) } ?: v

    private fun whenWords(r: Routine): String = when (r.moment) {
        Moment.LEAVE -> "When you leave ${r.place ?: "home"}"
        Moment.ARRIVE -> "When you get ${r.place?.let { "to $it" } ?: "home"}"
        Moment.DUSK, Moment.SUNRISE -> {
            val sun = if (r.moment == Moment.DUSK) "sunset" else "sunrise"
            when { r.offsetMin == 0 -> "At $sun"; r.offsetMin > 0 -> "${r.offsetMin} min after $sun"; else -> "${-r.offsetMin} min before $sun" }
        }
        Moment.TIME -> "At ${r.atMin?.let { clock(it) } ?: "a time"}"
        Moment.ASLEEP -> "When you look asleep (past your bedtime or ${clock(r.afterMin)}, screen off ${r.idleMin} min, nothing playing)"
        else -> "When ${r.moment.triggerLabel}"
    }

    private fun condWords(c: Cond, s: HomeSnapshot): String = when (c.type) {
        "home" -> if (c.value == "no") "you're away" else "you're home"
        "dark" -> if (c.value == "no") "it's light out" else "it's dark"
        "days" -> "on ${c.value}"
        "after" -> "after ${hhmm(c.value)}"
        "before" -> "before ${hhmm(c.value)}"
        "device" -> "${s.device(c.value.substringBeforeLast(':'))?.name ?: "that device"} is ${c.value.substringAfterLast(':')}"
        else -> c.type
    }

    private fun actWords(a: Act, s: HomeSnapshot): String = when (a.type) {
        "device" -> {
            val n = s.device(a.args["device_id"].orEmpty())?.name ?: a.args["device"] ?: "that device"
            when (a.args["action"]) {
                "on", "off" -> "turn ${a.args["action"]} $n"
                "brightness" -> "set $n to ${a.args["value"]}%"
                "color_temp" -> "set $n to ${a.args["value"]} K"
                else -> "make $n ${a.args["value"]}"
            }
        }
        "music" -> "play ${a.args["name"] ?: a.args["query"] ?: "music"}"
        "alarm" -> "set an alarm for ${clock((a.args["hour"]?.toIntOrNull() ?: 0) * 60 + (a.args["minute"]?.toIntOrNull() ?: 0))}"
        "card" -> "put a card on Olympus: ${a.args["title"]}"
        else -> a.type
    }

    /** The routine in one sentence: "When you leave home: turn off the Bedroom lights and every plug and switch, except Computer." */
    fun sentence(r: Routine, s: HomeSnapshot, learned: List<String> = emptyList(), never: Set<String> = emptySet()): String {
        val ifs = r.ifs.map { condWords(it, s) }.takeIf { it.isNotEmpty() }?.let { ", if " + it.joinToString(" and ") }.orEmpty()
        val parts = ArrayList<String>()
        if (r.switches) {
            val usual = learned.takeIf { r.learn && it.isNotEmpty() }?.mapNotNull { s.device(it)?.name }
            val what = listOfNotNull(
                when {
                    usual != null -> "your usual lights (${usual.joinToString(", ")})"
                    "*" in r.rooms -> "every light"
                    r.rooms.isNotEmpty() -> "the ${r.rooms.joinToString(" and ")} lights" + if (r.learn) " (until it learns your usual)" else ""
                    r.learn -> "your usual lights (once it learns them)"
                    else -> null
                },
                r.devices.mapNotNull { s.device(it)?.name }.takeIf { it.isNotEmpty() }?.joinToString(", "),
                "every plug and switch".takeIf { r.plugs && !r.turnOn },
            )
            // A Never automate device is named only where this routine would otherwise have switched it.
            val skipped = never.filter { id -> id !in r.overrides && !r.turnOn && s.device(id)?.let { d -> if (d.kind == DeviceKind.LIGHT) roomMatch(s.room(d.roomId)?.name, r.rooms) else r.plugs } == true }
            val kept = (r.except + skipped).distinct().mapNotNull { s.device(it)?.name }
            parts += "turn ${if (r.turnOn) "on" else "off"} ${joinAnd(what)}" + if (kept.isNotEmpty()) ", except ${kept.joinToString(", ")}" else ""
        }
        parts += r.then.map { actWords(it, s) }
        return "${whenWords(r)}$ifs: ${joinAnd(parts).ifEmpty { "nothing yet" }}."
    }

    private fun joinAnd(l: List<String>) = if (l.size <= 1) l.joinToString("") else l.dropLast(1).joinToString(", ") + " and " + l.last()

    /**
     * His Yes on Athena's suggestion ("home:<device id>:on" after one of the moments): a routine for that one device.
     * Null when it may not be one: a lock or any forbidden kind, a device he marked Never automate.
     */
    fun adopt(trigger: String, action: String, label: String, s: HomeSnapshot, never: Set<String>, id: String): Routine? {
        val m = Moment.of(trigger)?.takeIf { it.wired } ?: return null
        if (!action.startsWith("home:")) return null
        val verb = action.substringAfterLast(':').takeIf { it == "on" || it == "off" } ?: return null
        val device = s.device(action.removePrefix("home:").removeSuffix(":$verb")) ?: return null
        if (device.kind.forbidden || device.kind !in KINDS || device.id in never) return null
        return Routine(id, label.replaceFirstChar { it.uppercase() }.take(60), m, ifs = if (m == Moment.ARRIVE) listOf(Cond("dark", "yes")) else emptyList(),
            turnOn = verb == "on", devices = listOf(device.id), source = "suggestion")
    }

    /** A tap of his on a tile as a Fates-shaped step, so the learned-shortcuts evidence reads it like any other. */
    fun tapStep(d: HomeDevice, command: HomeCommand, s: HomeSnapshot): Step? {
        val on = (command as? HomeCommand.Power)?.on ?: return null
        if (d.kind.forbidden || d.kind !in KINDS) return null
        val p = linkedMapOf("device_id" to d.id, "device" to d.name, "action" to if (on) "on" else "off")
        s.room(d.roomId)?.name?.let { p["room"] = it }
        return Step(if (d.kind == DeviceKind.LIGHT) ProposalKind.LIGHTS else ProposalKind.HOME_CONTROL, p, "Turn ${if (on) "on" else "off"} ${d.name}")
    }
}

/** The routines on the phone, one JSON value. Device ids and room names only. */
class RoutineBook(private val kv: Kv) {
    private val type = object : TypeReference<RoutinesData>() {}

    fun load(): RoutinesData = kv.get(KEY)?.let { runCatching { stateJson.readValue(it, type) }.getOrNull() } ?: RoutinesData()

    @Synchronized
    fun update(change: (RoutinesData) -> RoutinesData): RoutinesData = change(load()).also { kv.put(KEY, stateJson.writeValueAsString(it)) }

    fun edit(id: String, change: (Routine) -> Routine) = update { d -> d.copy(routines = d.routines.map { if (it.id == id) change(it) else it }) }

    /** Never automate on or off for one device. */
    fun never(deviceId: String) = update { d -> d.copy(never = if (deviceId in d.never) d.never - deviceId else d.never + deviceId) }

    fun add(r: Routine) = update { d -> if (d.routines.any { it.id == r.id }) d else d.copy(routines = d.routines + r) }

    /** A new or changed routine, its run history kept. */
    fun put(r: Routine) = update { d ->
        val old = d.routine(r.id)
        val kept = old?.let { r.copy(runs = it.runs, lastRun = it.lastRun, lastWords = it.lastWords, lastIds = it.lastIds, history = it.history) } ?: r
        d.copy(routines = if (old == null) d.routines + kept else d.routines.map { if (it.id == r.id) kept else it })
    }

    fun remove(id: String) = update { d -> d.copy(routines = d.routines.filter { it.id != id }) }

    private companion object { const val KEY = "routines" }
}

/**
 * Runs the routines. [run] takes one proposal's steps and its title and returns the proposal ids that ran (Fates' own,
 * on the phone); [evidence] is the learned-shortcuts evidence.
 */
class RoutineEngine(
    private val book: RoutineBook,
    private val home: () -> HomeSnapshot,
    private val run: (List<RawStep>, String) -> List<Long>,
    private val evidence: () -> List<Evidence> = { emptyList() },
) {
    /** Something happened: he left or reached a place ([place] "home", "work" or a place's name), the alarm, the car. */
    fun event(m: Moment, now: RoutineNow, place: String = "home"): List<RoutineOutcome> {
        // Asleep at home, a leave or an arrival is the GPS jumping: nothing runs and he still counts as home.
        val asleep = place == "home" && RoutineRules.asleep(book.load(), now)
        val key = place.lowercase()
        val out = moment(m, now) { r -> m !in setOf(Moment.ARRIVE, Moment.LEAVE) || r.placeKey == key || (key == "work" && r.placeKey == "office") }
        if (!asleep) book.update { d ->
            when (m) {
                Moment.LEAVE -> d.copy(left = d.left + (key to now.at), asleepAt = if (key == "home") null else d.asleepAt)
                Moment.ARRIVE -> d.copy(arrived = d.arrived + (key to now.at))
                else -> d
            }
        }
        return out
    }

    /** The clock: sunset, sunrise and set times (once a day, within three hours) and the night's asleep check. */
    fun tick(now: RoutineNow): List<RoutineOutcome> {
        val d = book.load()
        val asleepRule = d.routines.firstOrNull { it.moment == Moment.ASLEEP } ?: Routine(moment = Moment.ASLEEP)
        val looks = RoutineRules.looksAsleep(asleepRule, now) == null
        book.update { it.copy(asleepAt = if (looks) it.asleepAt ?: now.at else if (now.offSince == null) null else it.asleepAt) }
        val out = ArrayList<RoutineOutcome>()
        out += moment(Moment.ASLEEP, now)
        for (m in Moment.entries.filter { it.clock }) out += moment(m, now) { r ->
            RoutineRules.clockAt(r, now)?.let { at -> now.at >= at && now.at < at + RoutineRules.CLOCK_WINDOW_MS } == true
        }
        return out
    }

    private fun moment(m: Moment, now: RoutineNow, due: (Routine) -> Boolean = { true }): List<RoutineOutcome> {
        val s = home()
        val d = book.update { RoutineRules.seed(it, s) }
        val ev = runCatching { evidence() }.getOrDefault(emptyList())
        return d.routines.filter { it.moment == m && due(it) }.map { r ->
            when (val p = RoutineRules.plan(r, s, d, now, RoutineRules.learned(r, ev))) {
                is RoutinePlan.Skip -> {
                    // Event routines keep their skips in the history; the clock's every-15-minute "not yet" doesn't.
                    if (!m.clock && m != Moment.ASLEEP && p.why != "turned off") book.edit(r.id) { it.copy(history = (it.history + RunLine(now.at, p.why, ran = false)).takeLast(10)) }
                    RoutineOutcome(r, skipped = p.why)
                }
                is RoutinePlan.Go -> {
                    val words = RoutineRules.words(p, s)
                    val ids = RoutineRules.steps(p).flatMap { run(it, "${r.name}: $words") }
                    book.edit(r.id) { it.copy(runs = it.runs + 1, lastRun = now.at, lastWords = words, lastIds = ids, history = (it.history + RunLine(now.at, words)).takeLast(10)) }
                    RoutineOutcome(r, ids, words)
                }
            }
        }
    }

    companion object {
        fun now(at: Long, zone: ZoneId, home: Boolean?, lat: Double?, lng: Double?, offSince: Long?, media: Boolean, bedtime: Long?): RoutineNow {
            val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val sun = if (lat != null && lng != null) Sun.times(date, lat, lng) else null
            return RoutineNow(at, zone, home, Sun.dark(at, zone, lat, lng), offSince, media, bedtime, sun?.first, sun?.second)
        }

        /** The next time the clock should look: the next sun or set time, tonight's bedtime, or every 15 minutes at night. */
        fun nextTick(d: RoutinesData, at: Long, zone: ZoneId, lat: Double?, lng: Double?, bedtime: Long?): Long {
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
            val asleep = d.routines.filter { it.moment == Moment.ASLEEP && it.on }
            // A morning asks about tonight, not last night.
            val probe = if (t.hour in RoutineRules.MORNING until 12) t.withHour(12).toInstant().toEpochMilli() else at
            val night = asleep.map { RoutineRules.nightStart(it, RoutineNow(probe, zone, bedtime = bedtime)) }.minOrNull()
            val candidates = ArrayList<Long>()
            if (night != null) candidates += if (probe == at && at >= night) at + 15 * 60_000L else night
            for (day in 0..1) {
                val date = t.toLocalDate().plusDays(day.toLong())
                val sun = if (lat != null && lng != null) Sun.times(date, lat, lng) else null
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val n = RoutineNow(start, zone, sunrise = sun?.first, sunset = sun?.second)
                for (r in d.routines.filter { it.on && it.moment.clock }) RoutineRules.clockAt(r, n)?.takeIf { it > at }?.let { candidates += it }
                // The sunset trigger feeds the evidence even with every routine off.
                sun?.second?.takeIf { it > at }?.let { candidates += it }
            }
            return candidates.minOrNull() ?: (at + 6 * 3_600_000L)
        }
    }
}
