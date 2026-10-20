package dev.phonerecall.odyssey

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalKind
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.EventCategory
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.home.Fmt
import dev.phonerecall.home.Stat
import dev.phonerecall.ui.TileForm
import dev.phonerecall.ui.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Where Odyssey meets the rest of Zeus: Olympus cards, Chronos entries, and the brain's derived facts.
 */

/** Odyssey's three Olympus cards; each is null when it has nothing to say. */
data class OdysseyHome(
    val commute: CardContent? = null, val roads: CardContent? = null, val offer: CardContent? = null, val commuteFit: Float = 0f,
    val snow: CardContent? = null, val wfh: CardContent? = null, val fuel: CardContent? = null, val lease: CardContent? = null,
)

object OdysseyCards {
    private val OPEN = CardAction("Open Odyssey", ActionRole.OPEN_ODYSSEY)

    fun home(d: OdysseyData, sections: List<Pair<Long, String>>, now: ZonedDateTime): OdysseyHome? {
        if (!d.settings.on) return null
        val ms = now.toInstant().toEpochMilli()
        return OdysseyHome(
            commute(d, ms, now.zone), roads(d, ms), offer(d, sections), commuteFit = 22f,
            snow = note(d, "snow", ms), wfh = wfh(d, ms), fuel = fuel(d, ms), lease = lease(d, now),
        )
    }

    /** The newest live note of a kind (a snow band on the route). */
    fun note(d: OdysseyData, kind: String, now: Long): CardContent? {
        val n = d.notes.filter { it.kind == kind && it.expiresAt > now }.maxByOrNull { it.at } ?: return null
        return CardContent(
            title = n.title, body = n.body,
            facts = n.facts.map { Fact(it.label, it.value) },
            tone = if (kind == "snow") Tone.ALERT else Tone.ACTIVE,
            actions = listOf(CardAction("Got it", ActionRole.ROAD_DONE, n.key)),
        )
    }

    /**
     * The open fill-up: "Fill-up at Costco Gas", "$42.10 · 11.2 gal · Example Bank ••1234. Odometer?". Shows as soon
     * as the stop is seen; Iris's answer fills it in when it comes. Gone after three days.
     */
    fun fuel(d: OdysseyData, now: Long): CardContent? {
        val f = d.car.open()?.takeIf { now - it.at < 3 * 86_400_000L } ?: return null
        val known = FuelStop.line(f)
        val waiting = !f.answered && f.askId != null && d.car.asks.containsKey(f.askId)
        return CardContent(
            title = "Fill-up at ${f.place}",
            body = listOfNotNull(known, if (waiting) "Iris is finding the receipt" else null, "Odometer?").joinToString(". "),
            actions = listOf(CardAction("Odometer", ActionRole.FUEL_ODOMETER, f.id), CardAction("Skip", ActionRole.FUEL_SKIP, f.id)),
        )
    }

    /**
     * The lease: missing details as one card once Iris has looked (or a day after asking), then the pace,
     * monthly (the first three days) or whenever it runs more than 5% over the allowance. Hidden for the
     * month after "Got it".
     */
    fun lease(d: OdysseyData, now: ZonedDateTime): CardContent? {
        val car = d.car
        val ms = now.toInstant().toEpochMilli()
        val looked = car.leaseAnswered > 0 || (car.leaseAsked > 0 && ms - car.leaseAsked > 86_400_000L)
        val missing = car.lease.missing()
        if (looked && missing.isNotEmpty() && "lease-setup" !in car.dismissed) {
            val found = car.lease.fromIris.size
            return CardContent(
                title = "Your car lease: a few details",
                body = (if (found > 0) "Iris found $found ${if (found == 1) "detail" else "details"} in your email. " else "Iris couldn't find the lease in your email. ") +
                    "Still missing: ${missing.joinToString(", ")}.",
                actions = listOf(CardAction("Fill in", ActionRole.CAR_SETUP), CardAction("Not now", ActionRole.ROAD_DONE, "lease-setup")),
            )
        }
        val p = LeasePace.pace(car.lease, car.readings, d.drives, now.toLocalDate(), now.zone) ?: return null
        val month = "lease:${now.year}-${now.monthValue}"
        if (month in car.dismissed || (!p.ahead && now.dayOfMonth > 3)) return null
        val (title, body) = LeasePace.words(p)
        return CardContent(
            title = title, body = body,
            facts = buildList {
                LeasePace.mpg(car.fillups)?.let { (last, avg) -> add(Fact("Fuel", "%.1f mpg".format(java.util.Locale.US, last) + (avg?.let { " (usual %.1f)".format(java.util.Locale.US, it) } ?: ""))) }
                LeasePace.centsPerMile(car.fillups)?.let { add(Fact("Cost", "%.1f¢ a mile in fuel".format(java.util.Locale.US, it))) }
                add(Fact("Allowance", "%,d mi a year".format(java.util.Locale.US, p.allowance)))
            },
            status = if (p.ahead) "Over pace" else "On pace",
            tone = if (p.ahead) Tone.ACTIVE else Tone.OK,
            actions = listOf(CardAction("Got it", ActionRole.ROAD_DONE, month)),
        )
    }

    /** "WFH today?", Yes or No; gone at noon or after either tap. */
    fun wfh(d: OdysseyData, now: Long): CardContent? {
        val n = d.notes.firstOrNull { it.kind == "wfh" && it.expiresAt > now } ?: return null
        return CardContent(
            title = n.title, body = n.body,
            actions = listOf(CardAction("Yes, remote today", ActionRole.WFH_YES, n.key), CardAction("No", ActionRole.WFH_NO, n.key)),
        )
    }

    /** The latest traffic check that is still ahead of him: a commute heads-up or a leave-by. */
    fun commute(d: OdysseyData, now: Long, zone: ZoneId): CardContent? {
        val c = d.checks.filter { it.expiresAt > now && now > it.at - 60_000L }.minByOrNull { it.leaveBy } ?: return null
        val late = c.leaveBy < now
        return CardContent(
            title = c.title,
            body = RoadWords.advice(c, zone),
            facts = buildList {
                add(Fact("Leave by", Fmt.time(Instant.ofEpochMilli(c.leaveBy).atZone(zone)), if (late) "Late" else null, if (late) Tone.ALERT else Tone.QUIET))
                add(Fact(if (c.reason == "event") "To" else "Office", "${c.destination}, ${c.minutes} min with traffic"))
                for (i in c.incidents.take(2)) add(Fact("On the way", i))
            },
            status = if (c.delayMinutes >= 5) "+${c.delayMinutes} min" else "Clear",
            tone = if (c.delayMinutes >= 10) Tone.ALERT else if (c.delayMinutes >= 5) Tone.ACTIVE else Tone.OK,
            actions = listOf(CardAction("Open in Maps", ActionRole.MAPS, "${c.lat},${c.lng}"), CardAction("Got it", ActionRole.ACK)),
        )
    }

    /** A place Odyssey worked out, or wants to: "Is this your office? (Pine St)" Yes / No / Something else, or Keep / Undo. */
    fun guess(d: OdysseyData): CardContent? {
        val g = d.guesses.firstOrNull { it.state == "auto" } ?: d.guesses.firstOrNull { it.state == "ask" && it.kind != Kind.OTHER } ?: return null
        return CardContent(
            title = PlaceLearner.text(g),
            body = if (g.state == "auto") "Arrivals and leaving there are noted from now on, and drives to it count as the commute." else "From ${g.visits} ${if (g.visits == 1) "stay" else "stays"} of half an hour or more. Asked once.",
            actions = if (g.state == "auto") listOf(CardAction("Keep it", ActionRole.ODYSSEY_YES, g.id), CardAction("Undo", ActionRole.ODYSSEY_NO, g.id))
            else listOf(CardAction("Yes", ActionRole.ODYSSEY_YES, g.id), CardAction("No", ActionRole.ODYSSEY_NO, g.id), CardAction("Something else", ActionRole.OPEN_ODYSSEY)),
        )
    }

    /** This week's driving score and the commute insight. */
    fun roads(d: OdysseyData, now: Long): CardContent? {
        val w = DrivingReport.week(d.drives, now)
        val insight = Commute.insight(d, now)
        if (w.trips == 0 && insight == null) return null
        return CardContent(
            title = w.score?.let { s -> "Driving score $s" + (w.lastWeekScore?.let { if (s > it) ", up from $it" else if (s < it) ", down from $it" else "" } ?: "") } ?: "Your roads this week",
            stat = w.score?.let { Stat("$it", "score", TileForm.CIRCLE) },
            facts = buildList {
                if (w.trips > 0) add(Fact("Week", "${w.trips} ${if (w.trips == 1) "drive" else "drives"}, ${Fmt.minutes(w.minutes)}, ${w.miles} mi"))
                w.counts[DriveEventKind.HARSH_BRAKE]?.let { add(Fact("Braking", "$it hard")) }
                if (w.screenMinutes >= 0.5) add(Fact("Screen", "${w.screenMinutes.toInt().coerceAtLeast(1)} min while moving", null, Tone.ACTIVE))
                w.spots.firstOrNull()?.let { add(Fact("Spot", it)) }
                insight?.let { add(Fact("Insight", it)) }
            },
            actions = listOf(OPEN),
        )
    }

    fun offer(d: OdysseyData, sections: List<Pair<Long, String>>): CardContent? {
        guess(d)?.let { return it }
        val o = Offers.due(d, sections).firstOrNull() ?: return null
        return CardContent(
            title = o.text,
            body = "Seen ${o.place.visits} times. One tap sets up the arrival list; nothing happens without it.",
            actions = listOf(CardAction(if (o.lists.isEmpty()) "Keep it" else "Yes", ActionRole.ODYSSEY_YES, o.place.id), CardAction("Not now", ActionRole.ODYSSEY_NO, o.place.id)),
        )
    }
}

/** Odyssey in Chronos: leave-by times ahead and auto-logged visits behind, as its own calendar. */
object OdysseyCalendar {
    const val ID = -8L
    val INFO = CalendarInfo(ID, "Odyssey", "odyssey", CalKind.OWN, 0)

    fun items(d: OdysseyData, from: Long, to: Long): List<CalItem> {
        if (!d.settings.on) return emptyList()
        val leave = d.checks.filter { it.leaveBy in from until to }.map { c ->
            CalItem(-(c.key.hashCode().toLong() and 0x7fffffffL) - 1_000_000_000L, ID, "Leave for ${c.destination}", c.leaveBy, c.leaveBy + 5 * 60_000L,
                false, EventCategory.TRAVEL, INFO.name, null, "${c.minutes} min with traffic", false)
        }
        val logs = Patterns.logs(d).filter { it.visit.start < to && it.visit.end > from }.map { l ->
            CalItem(-(l.visit.id.hashCode().toLong() and 0x7fffffffL) - 2_000_000_000L, ID, "${l.kind.label}: ${l.place.name}", l.visit.start, l.visit.end,
                false, EventCategory.PERSONAL, INFO.name, null, "${l.visit.minutes} min", false)
        }
        return leave + logs
    }
}

/**
 * The only door from Odyssey to the brain. Derived facts in words: usual times, averages, habits and a
 * weekly score. Never coordinates, addresses, streets, Wi-Fi names, amounts or a single visit's time, except today's office arrival (he asked for that one).
 */
object OdysseyBrain {
    /** The same derived facts, read from the store here so the brain side never touches it. */
    fun factsNow(c: android.content.Context, now: Long): List<String> = facts(OdysseyStore.load(c), now)

    fun facts(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        if (!d.settings.on) return emptyList()
        val s = Commute.stats(d, now, zone)
        val named = d.places.filter { it.source != NameSource.NONE || it.kind == Kind.HOME || it.kind == Kind.WORK }.map { it.id }.toSet()
        val out = ArrayList<String>()
        Commute.usual(s)?.let { out += it }
        Commute.summary(s)?.let { out += it }
        Patterns.homeBy(d, zone)?.let { out += it }
        out += Patterns.habits(d, now, zone).filter { it.placeId in named }.map { it.text }
        Commute.insight(d, now, zone)?.let { out += it }
        val w = DrivingReport.week(d.drives, now)
        w.score?.let { out += "Driving score this week $it over ${w.trips} ${if (w.trips == 1) "drive" else "drives"}" + (w.lastWeekScore?.let { p -> " (last week $p)" } ?: "") }
        LeasePace.pace(d.car.lease, d.car.readings, d.drives, Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone)?.let { out += LeasePace.brainLine(it) }
        arrival(d, now, zone)?.let { out += it }
        out += Stops.facts(d, now, zone)
        return out.map { scrub(it) }
    }

    /** Today's real arrival at the office (its geofence), which he asked Athena to know: "At the office since 9:34 today". */
    fun arrival(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val saved = d.guesses.lastOrNull { it.state == "auto" && it.kind == Kind.WORK && Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
        val work = d.work() ?: return null
        fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { Clock.hm(it.hour * 60 + it.minute) }
        // The geofence's entry, or the open stay at the office (he was already inside when it was saved).
        val a = d.arrivals.lastOrNull { it.placeId == work.id && Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
            ?: d.stay?.takeIf { s -> Geo.meters(work.lat, work.lng, s.lat, s.lng) < PlaceLearner.CLUSTER_M && Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate() == day }?.let { Arrival(work.id, Kind.WORK, it.start) }
            ?: return saved?.let { "Odyssey saved his office from this morning's drive (he can undo it in Odyssey)" }
        return a.left?.let { "Left the office at ${hm(it)} today (there from ${hm(a.at)})" } ?: "At the office since ${hm(a.at)} today"
    }

    /** Belt and braces: anything that looks like a coordinate or an amount is cut. */
    fun scrub(s: String): String = s.replace(Regex("""-?\d{1,3}\.\d{3,}"""), "").replace(Regex("""\$\s?\d[\d,.]*"""), "").trim()
}
