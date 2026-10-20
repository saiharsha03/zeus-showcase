package dev.phonerecall.clio

import dev.phonerecall.brief.ScreenTime
import dev.phonerecall.brief.UsageEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Clio's "device" area as plain functions: what a broadcast or a reading becomes as a line. The Android side
 * (DeviceWatch) only reads values and hands them here. Places are names only, never coordinates.
 */
object DeviceLog {
    const val AREA = "device"
    const val PLACES = "places"

    const val SCREEN_ON = "screen on"
    const val SCREEN_OFF = "screen off"
    const val UNLOCKED = "unlocked"
    const val PLUGGED = "charging started"
    const val UNPLUGGED = "charging stopped"
    const val FULL = "fully charged"
    const val OVERNIGHT = "overnight charge"
    const val BATTERY = "battery"
    const val HOT = "battery hot"
    const val SAVER_ON = "battery saver on"
    const val SAVER_OFF = "battery saver off"
    const val SCREEN_TIME = "screen time"
    const val APP_TIME = "app time"
    const val FIRST_UNLOCK = "first unlock"
    const val LAST_OFF = "last screen-off"
    const val CAR_ON = "car connected"
    const val CAR_OFF = "car disconnected"
    const val ALARM_DISMISSED = "alarm dismissed"
    const val ALARM_SNOOZED = "alarm snoozed"
    const val ARRIVED = "arrived"
    const val LEFT = "left"
    const val DRIVE_START = "drive started"
    const val DRIVE_END = "drive ended"

    /** Network lines: one of these three, logged when it changes. */
    val NETWORK = setOf("on Wi-Fi", "on mobile data", "offline")

    /** The battery reading rides the 30-minute sync; a little early still counts (WorkManager jitters). */
    const val SNAPSHOT_MS = 25 * 60_000L
    /** Hot at 42 °C, cool again under 40 °C (tenths, as BatteryManager reports it). */
    const val HOT_TENTHS = 420
    const val COOL_TENTHS = 400

    // UsageEvents.Event constants (API 28+), as in ScreenTime.
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16

    private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    fun clock(ms: Long, zone: ZoneId): String = clockFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun duration(ms: Long): String = ScreenTime.duration((ms / 60_000L).toInt().coerceAtLeast(0))

    /** BatteryManager.EXTRA_PLUGGED. */
    fun plug(plugged: Int): String = when (plugged) { 1 -> "AC"; 2 -> "USB"; 4 -> "wireless"; 8 -> "dock"; else -> "power" }

    fun plugged(pct: Int, plug: String, late: Boolean = false) = "$pct%, $plug" + if (late) " (noticed when Zeus started)" else ""

    fun unplugged(start: Long?, startPct: Int?, end: Long, pct: Int): String =
        listOfNotNull(if (startPct != null) "$startPct% to $pct%" else "$pct%", start?.let { duration(end - it) }).joinToString(", ")

    /** A charge that ran through 3 AM and lasted at least three hours. */
    fun overnight(start: Long, end: Long, zone: ZoneId): Boolean {
        if (end - start < 3 * 3_600_000L) return false
        val d = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        val three = d.atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        return three in start..end || (three - 86_400_000L) in start..end
    }

    fun overnightDetail(start: Long, end: Long, startPct: Int?, pct: Int, zone: ZoneId) =
        "${clock(start, zone)} to ${clock(end, zone)}, ${duration(end - start)}" + (startPct?.let { ", $it% to $pct%" } ?: "")

    fun battery(pct: Int, plug: String?, tempTenths: Int, saver: Boolean): String =
        listOfNotNull("$pct%", plug?.let { "charging ($it)" }, "${tempTenths / 10}°C", if (saver) "saver on" else null).joinToString(", ")

    /** The hot flag after this reading, and whether it just turned hot (log once per spike). */
    fun heat(tempTenths: Int, wasHot: Boolean): Pair<Boolean, Boolean> = when {
        !wasHot && tempTenths >= HOT_TENTHS -> true to true
        wasHot && tempTenths < COOL_TENTHS -> false to false
        else -> wasHot to false
    }

    fun network(wifi: Boolean, cellular: Boolean): String = when { wifi -> "on Wi-Fi"; cellular -> "on mobile data"; else -> "offline" }

    fun airplane(on: Boolean) = if (on) "airplane mode on" else "airplane mode off"

    /** AudioManager.RINGER_MODE_*. */
    fun ringer(mode: Int) = "ringer " + when (mode) { 0 -> "silent"; 1 -> "vibrate"; else -> "normal" }

    /** NotificationManager.INTERRUPTION_FILTER_*. */
    fun dnd(filter: Int) = when (filter) {
        1 -> "do not disturb off"
        2 -> "do not disturb on (priority)"
        3 -> "do not disturb on (silence)"
        4 -> "do not disturb on (alarms only)"
        else -> "do not disturb unknown"
    }

    /** BluetoothClass.Device: car audio (0x0420) or a hands-free kit (0x0408). */
    fun isCar(deviceClass: Int) = deviceClass == 0x0420 || deviceClass == 0x0408

    /**
     * NEXT_ALARM_CLOCK_CHANGED moved the alarm that was due about now: snoozed when the next one is a few minutes
     * after it, else dismissed. Null when the change wasn't about an alarm going off (set, edited, or far away).
     */
    fun alarm(before: Long?, after: Long?, now: Long): String? {
        if (before == null || before == after || before !in now - 90 * 60_000L..now + 5 * 60_000L) return null
        return if (after != null && after > now && after - before in 60_000L..30 * 60_000L) ALARM_SNOOZED else ALARM_DISMISSED
    }

    /**
     * Yesterday's screen, as lines (event to detail, each detail starting with the date): screen time and pickups,
     * minutes for the top 15 apps that may be named plus "other", the first unlock (from 4 AM) and the last
     * screen-off (before 4 AM next day). Counted by Eos's [ScreenTime.aggregate]; sensitive apps fall into "other".
     */
    fun screenLines(events: List<UsageEvent>, today: LocalDate, zone: ZoneId, self: String, label: (String) -> String = { it }): List<Pair<String, String>> {
        val d = ScreenTime.aggregate(events, today, zone, self, top = Int.MAX_VALUE, label = label)
        val day = today.minusDays(1)
        val from = day.atTime(LocalTime.of(4, 0)).atZone(zone).toInstant().toEpochMilli()
        val to = today.atTime(LocalTime.of(4, 0)).atZone(zone).toInstant().toEpochMilli()
        val sorted = events.filter { it.at in from until to }.sortedBy { it.at }
        val out = ArrayList<Pair<String, String>>()
        out += SCREEN_TIME to "$day · ${ScreenTime.duration(d.totalMin)}" + (d.pickups?.let { " · $it pickups" } ?: "")
        // A label the sanitizer would read as money or health (it would blank the digits or drop the line) counts as other.
        val named = d.top.filter { dev.phonerecall.brain.Topics.of(it.label).isEmpty() }.take(15)
        val other = d.totalMin - named.sumOf { it.minutes }
        if (d.totalMin > 0) out += APP_TIME to (listOf("$day") + named.map { "${it.label} ${it.minutes}" } + (if (other > 0) listOf("other $other") else emptyList())).joinToString(" · ")
        sorted.firstOrNull { it.type == ScreenTime.KEYGUARD_HIDDEN }?.let { out += FIRST_UNLOCK to "$day · ${clock(it.at, zone)}" }
        sorted.lastOrNull { it.type == SCREEN_NON_INTERACTIVE }?.let { out += LAST_OFF to "$day · ${clock(it.at, zone)}" }
        return out
    }
}

enum class Lane(val title: String) { SCREEN("Screen"), CHARGING("Charging"), PLACES("Places"), DRIVES("Drives") }

data class Span(val lane: Lane, val start: Long, val end: Long, val label: String = "")

/** One day for Clio's Day view: the timeline's spans, summary rows (label to text), and app minutes. */
data class DeviceDayView(val date: LocalDate, val from: Long, val to: Long, val spans: List<Span>, val rows: List<Pair<String, String>>, val apps: List<Pair<String, Int>>)

/** A day put back together from Clio's own lines (any order, any days; only [date] is kept). */
object DeviceDay {
    /** An open span (no end line: the process died, or it's still going) is cut here. */
    val CAP = mapOf(Lane.SCREEN to 4 * 3_600_000L, Lane.CHARGING to 16 * 3_600_000L, Lane.PLACES to 16 * 3_600_000L, Lane.DRIVES to 4 * 3_600_000L)

    private fun lane(l: ClioLine): Pair<Lane, Boolean>? = when {
        l.area == DeviceLog.AREA && (l.event == DeviceLog.SCREEN_ON || l.event == DeviceLog.UNLOCKED) -> Lane.SCREEN to true
        l.area == DeviceLog.AREA && l.event == DeviceLog.SCREEN_OFF -> Lane.SCREEN to false
        l.area == DeviceLog.AREA && l.event == DeviceLog.PLUGGED -> Lane.CHARGING to true
        l.area == DeviceLog.AREA && l.event == DeviceLog.UNPLUGGED -> Lane.CHARGING to false
        l.area == DeviceLog.PLACES && l.event == DeviceLog.ARRIVED -> Lane.PLACES to true
        l.area == DeviceLog.PLACES && l.event == DeviceLog.LEFT -> Lane.PLACES to false
        l.area == "odyssey" && l.event == DeviceLog.DRIVE_START -> Lane.DRIVES to true
        l.area == "odyssey" && l.event == DeviceLog.DRIVE_END -> Lane.DRIVES to false
        else -> null
    }

    /** Start and end lines paired per lane. A second start closes the open one (an unlock while on doesn't); places pair by name. */
    fun spans(lines: List<ClioLine>, now: Long): List<Span> {
        val out = ArrayList<Span>()
        val open = HashMap<Lane, ClioLine>()
        fun close(lane: Lane, at: Long) {
            val s = open.remove(lane) ?: return
            val end = minOf(at, s.ts + CAP.getValue(lane))
            if (end > s.ts) out += Span(lane, s.ts, end, if (lane == Lane.PLACES) s.detail else "")
        }
        for (l in lines.sortedBy { it.ts }) {
            val (lane, start) = lane(l) ?: continue
            val cur = open[lane]
            when {
                start && cur != null && lane == Lane.SCREEN -> Unit
                start && cur != null && lane == Lane.PLACES && cur.detail == l.detail -> Unit
                start -> { close(lane, l.ts); open[lane] = l }
                lane == Lane.PLACES && cur != null && cur.detail != l.detail -> Unit
                else -> close(lane, l.ts)
            }
        }
        for (lane in open.keys.toList()) close(lane, now)
        return out.sortedBy { it.start }
    }

    private fun minutes(spans: List<Span>) = spans.sumOf { it.end - it.start }

    private fun forDay(lines: List<ClioLine>, event: String, date: LocalDate): String? =
        lines.filter { it.area == DeviceLog.AREA && it.event == event && it.detail.startsWith("$date · ") }.maxByOrNull { it.ts }?.detail?.removePrefix("$date · ")

    /** "YouTube 52 · WhatsApp 31 · other 12" back into (label, minutes). */
    fun apps(detail: String): List<Pair<String, Int>> = detail.split(" · ").mapNotNull { p ->
        val m = p.trim().substringAfterLast(' ').toIntOrNull() ?: return@mapNotNull null
        p.trim().substringBeforeLast(' ').takeIf { it.isNotBlank() }?.let { it to m }
    }

    fun of(lines: List<ClioLine>, date: LocalDate, zone: ZoneId, now: Long): DeviceDayView {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val spans = spans(lines, now).mapNotNull { s -> Span(s.lane, maxOf(s.start, from), minOf(s.end, to), s.label).takeIf { it.end > it.start } }
        val day = lines.filter { it.ts in from until to }.sortedBy { it.ts }
        fun dev(vararg events: String) = day.filter { it.area == DeviceLog.AREA && it.event in events }
        fun clock(ms: Long) = DeviceLog.clock(ms, zone)
        val rows = ArrayList<Pair<String, String>>()

        forDay(lines, DeviceLog.SCREEN_TIME, date)?.let { rows += "Screen time" to it }
        val screen = spans.filter { it.lane == Lane.SCREEN }
        if (screen.isNotEmpty()) rows += "Screen on" to "${screen.size} ${if (screen.size == 1) "time" else "times"}, ${DeviceLog.duration(minutes(screen))} (while Zeus was running)"
        forDay(lines, DeviceLog.FIRST_UNLOCK, date)?.let { rows += "First unlock" to it }
        forDay(lines, DeviceLog.LAST_OFF, date)?.let { rows += "Last screen-off" to it }

        val charging = spans.filter { it.lane == Lane.CHARGING }
        val full = dev(DeviceLog.FULL).map { clock(it.ts) }
        if (charging.isNotEmpty() || full.isNotEmpty()) rows += "Charging" to listOfNotNull(
            "${charging.size} ${if (charging.size == 1) "session" else "sessions"}, ${DeviceLog.duration(minutes(charging))}",
            full.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "full at "),
        ).joinToString(" · ")
        dev(DeviceLog.OVERNIGHT).lastOrNull()?.let { rows += "Overnight" to it.detail }
        val pct = dev(DeviceLog.BATTERY).mapNotNull { l -> l.detail.substringBefore('%').toIntOrNull()?.let { it to l.ts } }
        val hot = dev(DeviceLog.HOT).size
        if (pct.isNotEmpty() || hot > 0) rows += "Battery" to listOfNotNull(
            pct.minByOrNull { it.first }?.let { (p, at) -> "lowest $p% at ${clock(at)}" },
            if (hot > 0) "hot $hot ${if (hot == 1) "time" else "times"}" else null,
            if (dev(DeviceLog.SAVER_ON).isNotEmpty()) "saver used" else null,
        ).joinToString(" · ")

        val places = spans.filter { it.lane == Lane.PLACES }.map { it.label }.filter { it.isNotBlank() }.distinct()
        if (places.isNotEmpty()) rows += "Places" to places.joinToString(", ")
        val drives = day.filter { it.area == "odyssey" && it.event == DeviceLog.DRIVE_END && "not kept" !in it.detail }
        if (drives.isNotEmpty()) {
            val mins = drives.sumOf { Regex("(\\d+) min").find(it.detail)?.groupValues?.get(1)?.toInt() ?: 0 }
            val best = drives.mapNotNull { Regex("score (\\d+)").find(it.detail)?.groupValues?.get(1)?.toInt() }.maxOrNull()
            rows += "Drives" to "${drives.size} ${if (drives.size == 1) "drive" else "drives"}, ${ScreenTime.duration(mins)}" + (best?.let { " · best score $it" } ?: "")
        }
        val car = dev(DeviceLog.CAR_ON).size
        if (car > 0) rows += "Car Bluetooth" to "connected $car ${if (car == 1) "time" else "times"}"

        val net = day.filter { it.area == DeviceLog.AREA && it.event in DeviceLog.NETWORK }
        val air = dev(DeviceLog.airplane(true)).size
        if (net.isNotEmpty() || air > 0) rows += "Network" to listOfNotNull(
            net.takeIf { it.isNotEmpty() }?.let { "${it.size} ${if (it.size == 1) "switch" else "switches"}, last ${it.last().event.removePrefix("on ")}" },
            if (air > 0) "airplane mode $air ${if (air == 1) "time" else "times"}" else null,
        ).joinToString(" · ")
        val sound = day.filter { it.area == DeviceLog.AREA && (it.event.startsWith("ringer ") || it.event.startsWith("do not disturb ")) }
        if (sound.isNotEmpty()) rows += "Sound" to "${sound.size} ${if (sound.size == 1) "change" else "changes"}, last ${sound.last().event}"
        val dismissed = dev(DeviceLog.ALARM_DISMISSED).size
        val snoozed = dev(DeviceLog.ALARM_SNOOZED).size
        if (dismissed + snoozed > 0) rows += "Alarms" to listOfNotNull(
            if (dismissed > 0) "dismissed $dismissed" else null, if (snoozed > 0) "snoozed $snoozed" else null,
        ).joinToString(", ")

        return DeviceDayView(date, from, to, spans, rows, forDay(lines, DeviceLog.APP_TIME, date)?.let(::apps).orEmpty())
    }
}
