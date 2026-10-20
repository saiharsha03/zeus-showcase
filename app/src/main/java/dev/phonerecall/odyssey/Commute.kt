package dev.phonerecall.odyssey

import com.fasterxml.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** His usual commute, learned from trips between Home and the office. */
data class CommuteStats(
    /** Median departure from home, minutes of day, per weekday with at least 2 trips in six weeks. */
    val departures: Map<DayOfWeek, Int> = emptyMap(),
    /** Mean minutes per weekday. */
    val minutes: Map<DayOfWeek, Int> = emptyMap(),
    val average: Int? = null,
    val samples: Int = 0,
)

object Commute {
    private const val DAY = 86_400_000L

    fun trips(d: OdysseyData): List<Trip> {
        val home = d.home()?.id ?: return emptyList()
        val work = d.work()?.id ?: return emptyList()
        return d.trips.filter { it.from == home && it.to == work && it.minutes in 3..150 }
    }

    fun stats(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault()): CommuteStats {
        val all = trips(d)
        val recent = all.filter { now - it.start < 42 * DAY }
        val byDay = recent.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }
        val deps = byDay.filter { it.value.size >= 2 }.mapValues { (_, ts) ->
            ts.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime().toSecondOfDay() / 60 }.sorted().let { it[it.size / 2] }
        }
        val mins = all.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }.mapValues { (_, ts) -> ts.map { it.minutes }.average().roundToInt() }
        return CommuteStats(deps, mins, all.takeIf { it.isNotEmpty() }?.map { it.minutes }?.average()?.roundToInt(), all.size)
    }

    /** "Commute to office: avg 22 min, Tue slowest (28)". */
    fun summary(s: CommuteStats): String? {
        val avg = s.average ?: return null
        if (s.samples < 3) return null
        val slow = s.minutes.maxByOrNull { it.value }?.takeIf { s.minutes.size >= 2 && it.value - avg >= 3 }
        return "Commute to office: avg $avg min" + (slow?.let { ", ${Clock.short(it.key)} slowest (${it.value})" } ?: "")
    }

    /** "You leave for the office ~8:20 on Mon–Wed". */
    fun usual(s: CommuteStats): String? {
        if (s.departures.isEmpty()) return null
        val typical = s.departures.values.sorted().let { it[it.size / 2] }
        return "You leave for the office ~${Clock.hm(Patterns.roundTo(typical, 5))} on ${Clock.days(s.departures.keys)}" + (s.average?.let { ", $it min usual" } ?: "")
    }

    /**
     * The pre-departure check: due between 30 and 10 minutes before his usual departure on a weekday he
     * usually commutes, once a day, and only while he is still at home (or Odyssey doesn't know where he is).
     */
    fun departureDue(s: CommuteStats, now: ZonedDateTime, ran: Map<String, Long>, atHome: Boolean?, workday: Boolean = true): Boolean {
        if (!workday || atHome == false) return false
        val dep = s.departures[now.dayOfWeek] ?: return false
        if ("commute:${now.toLocalDate()}" in ran) return false
        val m = now.toLocalTime().toSecondOfDay() / 60
        return m in (dep - 30)..(dep - 10)
    }

    fun departureKey(day: LocalDate) = "commute:$day"

    /** The next moment a pre-departure check could be due, for the alarm. */
    fun nextDeparture(s: CommuteStats, now: ZonedDateTime): ZonedDateTime? = (0..7).asSequence().mapNotNull { add ->
        val day = now.toLocalDate().plusDays(add.toLong())
        s.departures[day.dayOfWeek]?.let { day.atTime(LocalTime.ofSecondOfDay(((it - 30).coerceAtLeast(0) * 60).toLong())).atZone(now.zone) }
    }.firstOrNull { it.isAfter(now) }

    /** Leave-by: the event's start minus the trip and a five minute margin. */
    fun leaveBy(start: Long, travelSeconds: Int, marginMin: Int = 5): Long = start - travelSeconds * 1000L - marginMin * 60_000L

    /** An event check runs once, in the 2.5 hours before the event, never after it began. */
    fun eventDue(start: Long, now: Long, key: String, ran: Map<String, Long>): Boolean =
        key !in ran && now < start && start - now <= 150 * 60_000L

    /**
     * The weekly insight: on a weekday with enough trips, compare leaving earlier than usual with leaving
     * later. "Leaving 10 min earlier on Tuesdays saves 9 min." `day` keeps to that weekday (a drive's report).
     */
    fun insight(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault(), day: DayOfWeek? = null): String? {
        val trips = trips(d).filter { now - it.start < 56 * DAY && (day == null || Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek == day) }
        var best: Triple<DayOfWeek, Int, Int>? = null
        for ((dow, ts) in trips.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }) {
            if (ts.size < 4) continue
            val dep = ts.map { it to Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime().toSecondOfDay() / 60 }.sortedBy { it.second }
            val half = dep.size / 2
            val early = dep.take(half); val late = dep.drop(dep.size - half)
            val gap = late.map { it.second }.average() - early.map { it.second }.average()
            val saves = late.map { it.first.minutes }.average() - early.map { it.first.minutes }.average()
            if (gap >= 5 && saves >= 4) {
                val g = Patterns.roundTo(gap.roundToInt(), 5).coerceAtLeast(5)
                val s = saves.roundToInt()
                if (best == null || s > best.third) best = Triple(dow, g, s)
            }
        }
        val (dow, g, s) = best ?: return null
        return "Leaving $g min earlier on ${dow.getDisplayName(TextStyle.FULL, Locale.US)}s saves $s min."
    }
}

/**
 * One TomTom route: minutes with traffic, the delay, the incidents on it, and its polyline and road
 * numbers (kept on the phone, for the snow check).
 */
data class Route(val seconds: Int, val delaySeconds: Int, val meters: Int, val incidents: List<String>, val points: List<Pt> = emptyList(), val roads: List<RoadSpan> = emptyList())

/**
 * TomTom Routing (calculateRoute) with live traffic. One GET per check, at check time, with only the two
 * ends of the trip being checked. The key is the owner's, from Calypso, and goes only to api.tomtom.com.
 */
object TomTom {
    const val HOST = "api.tomtom.com"

    /** Five decimals (~1 m) for the two ends; nothing else about where he is or has been. */
    fun routeUrl(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, key: String): String {
        fun p(v: Double) = "%.5f".format(Locale.US, v)
        return "https://$HOST/routing/1/calculateRoute/${p(fromLat)},${p(fromLng)}:${p(toLat)},${p(toLng)}/json" +
            "?key=${java.net.URLEncoder.encode(key, "UTF-8")}&traffic=true&travelMode=car&routeType=fastest" +
            "&maxAlternatives=1&routeRepresentation=polyline&instructionsType=coded&sectionType=traffic&computeTravelTimeFor=all"
    }

    /** TEC main cause codes (TomTom traffic sections) worth naming. */
    private val CAUSE = mapOf(1 to "Heavy traffic", 2 to "Crash", 3 to "Road work", 4 to "Lane closure", 5 to "Road closed", 6 to "Slippery road",
        9 to "Hazard", 10 to "Object on the road", 13 to "Broken-down vehicle", 19 to "Weather")
    private val CATEGORY = mapOf("JAM" to "Heavy traffic", "ROAD_WORK" to "Road work", "ROAD_CLOSURE" to "Road closed")

    /** The main route first, then the alternative. Throws on a response without routes. */
    fun parse(json: String): List<Route> {
        val root = dev.phonerecall.mind.stateJson.readTree(json)
        val routes = root.path("routes")
        require(routes.isArray && routes.size() > 0) { root.path("error").path("description").asText("No route") }
        return routes.map { r ->
            val s = r.path("summary")
            val incidents = r.path("sections").filter { it.path("sectionType").asText() == "TRAFFIC" }.mapNotNull { sec -> incident(sec) }
            val points = r.path("legs").flatMap { leg -> leg.path("points").map { Pt(it.path("latitude").asDouble(), it.path("longitude").asDouble()) } }
            // Coded instructions name the road from each manoeuvre's point on: "I-190", else the street.
            val roads = r.path("guidance").path("instructions").mapNotNull { ins ->
                val name = ins.path("roadNumbers").firstOrNull()?.asText()?.takeIf { it.isNotBlank() } ?: ins.path("street").asText().takeIf { it.isNotBlank() }
                name?.let { RoadSpan(ins.path("pointIndex").asInt(), it) }
            }.distinctBy { it.from }
            Route(s.path("travelTimeInSeconds").asInt(), s.path("trafficDelayInSeconds").asInt(), s.path("lengthInMeters").asInt(), incidents.distinct(), points, roads)
        }
    }

    /** The road at route point `i`, from the spans. */
    fun roadAt(roads: List<RoadSpan>, i: Int): String? = roads.lastOrNull { it.from <= i }?.name

    /** A route thinned to a point every ~`stepM` metres (ends kept), for the sealed cache and the checks. */
    fun thin(points: List<Pt>, roads: List<RoadSpan>, stepM: Double = 400.0): Pair<List<Pt>, List<RoadSpan>> {
        if (points.size < 3) return points to roads
        val keep = ArrayList<Int>().apply { add(0) }
        for (i in 1 until points.lastIndex) {
            val a = points[keep.last()]; val b = points[i]
            if (Geo.meters(a.lat, a.lng, b.lat, b.lng) >= stepM || roads.any { it.from == i }) keep += i
        }
        keep += points.lastIndex
        val spans = roads.map { s -> RoadSpan(keep.indexOfLast { it <= s.from }.coerceAtLeast(0), s.name) }.distinctBy { it.from }
        return keep.map { points[it] } to spans
    }

    private fun incident(sec: JsonNode): String? {
        val delay = sec.path("delayInSeconds").asInt()
        val cause = sec.path("tec").path("causes").firstOrNull()?.path("mainCauseCode")?.asInt()
        val word = cause?.let { CAUSE[it] } ?: CATEGORY[sec.path("simpleCategory").asText()] ?: return null
        if (delay < 60 && cause != 2 && cause != 5) return null
        return word + if (delay >= 60) ", +${(delay / 60.0).roundToInt()} min" else ""
    }
}

/**
 * 511NY events (crashes, closures, construction on NY roads). Optional, behind a "511NY key" in Calypso.
 * The request carries only the key: the whole state's list comes back and is filtered on the phone to
 * the trip's corridor, so no location is sent at all.
 */
object Ny511 {
    const val HOST = "511ny.org"
    fun eventsUrl(key: String) = "https://$HOST/api/getevents?key=${java.net.URLEncoder.encode(key, "UTF-8")}&format=json"

    data class Event(val type: String, val road: String, val direction: String, val description: String, val lat: Double, val lng: Double, val severity: String)

    fun parse(json: String): List<Event> {
        val root = dev.phonerecall.mind.stateJson.readTree(json)
        val arr = if (root.isArray) root else root.path("events")
        return arr.mapNotNull { e ->
            val lat = e.path("Latitude").asDouble(Double.NaN); val lng = e.path("Longitude").asDouble(Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null
            else Event(e.path("EventType").asText(), e.path("RoadwayName").asText(), e.path("DirectionOfTravel").asText(), e.path("Description").asText(), lat, lng, e.path("Severity").asText())
        }
    }

    /** Events within ~1.5 km of the straight line between the two ends, worst first, as short lines. */
    fun onRoute(events: List<Event>, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, corridorM: Double = 1_500.0): List<String> =
        events.filter { it.type.lowercase().let { t -> t.contains("accident") || t.contains("incident") || t.contains("closure") || t.contains("roadwork") || t.contains("construction") } }
            .filter { Geo.toSegment(it.lat, it.lng, fromLat, fromLng, toLat, toLng) < corridorM }
            .sortedBy { if (it.severity.equals("major", true)) 0 else 1 }
            .map { line(it) }.distinct().take(3)

    fun line(e: Event): String {
        val what = when {
            e.type.contains("accident", true) || e.description.contains("crash", true) || e.description.contains("accident", true) -> "Crash"
            e.type.contains("closure", true) -> "Closure"
            else -> "Road work"
        }
        val dir = e.direction.takeIf { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("all directions", true) }?.first()?.uppercaseChar()?.let { " $it" }.orEmpty()
        val near = Regex("""(?i)\b(?:at|near)\s+(exit\s*\d+\w?|[A-Z][\w.' ]{2,30}?)(?=\s*[;,.(]|$)""").find(e.description)?.groupValues?.get(1)?.let { " near " + it.trim().replaceFirstChar { c -> c.lowercaseChar() }.replace(Regex("(?i)^exit"), "exit") }.orEmpty()
        return "$what on ${e.road}$dir$near".trim()
    }
}

/** Turns a checked route into the card's words. Pure. */
object RoadWords {
    fun check(
        key: String, reason: String, destination: String, arriveBy: Long, routes: List<Route>, extra: List<String>, now: Long,
        lat: Double, lng: Double, usualMinutes: Int?,
    ): RoadCheck {
        val main = routes.first()
        val alt = routes.getOrNull(1)
        val minutes = (main.seconds / 60.0).roundToInt()
        val leave = Commute.leaveBy(arriveBy, main.seconds, marginMin = if (reason == "event") 5 else 0)
        val incidents = (extra + main.incidents).distinct().take(3)
        val delay = usualMinutes?.let { (minutes - it).coerceAtLeast(0) } ?: (main.delaySeconds / 60.0).roundToInt()
        val title = when {
            reason == "event" -> "$destination, $minutes min away" + if (delay >= 5) " (+$delay min traffic)" else ""
            incidents.isNotEmpty() && delay >= 5 -> incidents.first().substringBefore(", +") + ", +$delay min"
            delay >= 5 -> "$minutes min to $destination, +$delay min"
            else -> "$minutes min to $destination"
        }
        return RoadCheck(key, now, title, destination, leave, minutes, delay, incidents, alt?.let { ((it.seconds - main.seconds) / 60.0).roundToInt() }, arriveBy, lat, lng, reason)
    }

    /** "Take the other route (+2 min) or leave by 8:05." */
    fun advice(c: RoadCheck, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(c.leaveBy).atZone(zone).toLocalTime()
        val leave = "leave by ${Clock.hm(at.hour * 60 + at.minute)}"
        val alt = c.alternative?.takeIf { c.delayMinutes >= 5 && it <= c.delayMinutes / 2 }
        return when {
            alt != null -> "Take the other route (${if (alt > 0) "+$alt min" else if (alt < 0) "${-alt} min faster" else "same time"}) or $leave."
            else -> leave.replaceFirstChar { it.uppercase() } + "."
        }
    }

    /** While driving, only alerts about the route he is on reach him. */
    fun whileDriving(driving: Boolean, routeRelevant: Boolean): Boolean = !driving || routeRelevant
}
