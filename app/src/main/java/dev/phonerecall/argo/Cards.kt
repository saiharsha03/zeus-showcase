package dev.phonerecall.argo

import dev.phonerecall.aeolia.Sky
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Workers
import dev.phonerecall.widgets.Mark
import dev.phonerecall.widgets.WGroup
import dev.phonerecall.widgets.WKind
import dev.phonerecall.widgets.WRow
import dev.phonerecall.widgets.WTone
import dev.phonerecall.widgets.WView
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Argo on Olympus and the Today widget. `card` is the one trip card (countdown, travel day, the trip, or
 * back home); `offer` is a Moneta booking asking "a trip coming up?". `widget`, when set, replaces the
 * Today widget for the travel day and the trip itself.
 */
data class TripHome(
    val card: CardContent? = null,
    val fit: Float = 0f,
    val kicker: String = "Trip",
    val today: Boolean = false,
    val alert: Float = 0f,
    val offer: CardContent? = null,
    val widget: WView? = null,
)

object ArgoCards {
    /** The only Calypso wording a widget may carry: it says where, never what. */
    const val CONFIRMATION = "Confirmation in Calypso"
    private val OPEN = CardAction("Open Argo", ActionRole.OPEN_ARGO)
    private val VAULT = CardAction("Confirmation", ActionRole.ARGO, "vault")

    private fun f(c: Double) = "${(c * 9 / 5 + 32).roundToInt()}°"

    fun home(d: ArgoData, now: Long, zone: ZoneId, alarms: AlarmWatch? = null): TripHome? {
        // Athena's one open question (an investigation she couldn't settle), never more than one at a time.
        val offer = d.inquiries.filter { it.status == InqStatus.QUESTION }.maxByOrNull { it.updatedAt }?.let { Inquiries.card(it) }
            ?: d.conflicts.firstNotNullOfOrNull { k -> d.trips.firstOrNull { it.id == k.tripId }?.let { conflict(k, it) } }
            ?: d.change?.takeIf { !it.ack && now - it.at in 0 until 86_400_000L }?.let(::changed)
        val back = d.trips.filter { it.status == TripStatus.ARCHIVED && it.recap != null }.firstOrNull { t -> t.window(zone)?.second?.let { now - it in 0 until 86_400_000L } == true }
        val cur = Travel.current(d.trips, now, zone)
        val (card, rest) = when {
            cur == null -> back?.let { backHome(it) } ?: (null to TripHome())
            cur.second.phase == Phase.TRAVEL -> travel(cur.first, cur.second.leg!!, now, zone)
            cur.second.phase == Phase.AWAY -> away(cur.first, d.weather[cur.first.id], now, zone)
            else -> countdown(cur.first, d.weather[cur.first.id], now, zone, alarms)
        }
        if (card == null && offer == null) return null
        return rest.copy(card = card, offer = offer)
    }

    /** New evidence against a trip he confirmed: one card, rebuild or keep. Never a silent split. */
    fun conflict(k: Conflict, t: Trip): CardContent = CardContent(
        title = "${t.place} trip: something new doesn't fit".take(60),
        body = k.text,
        actions = listOf(CardAction("Rebuild it", ActionRole.ARGO, "unlock:${t.id}"), CardAction("Keep as is", ActionRole.ARGO, "keep:${k.key}"), OPEN),
    )

    /** What Athena changed on her own: a quiet line with Undo. */
    fun changed(c: TripChange): CardContent = CardContent(
        title = "Athena updated your trips",
        body = c.text.removePrefix("Athena updated your trips: "),
        tone = Tone.QUIET,
        actions = listOf(CardAction("Undo", ActionRole.ARGO, "undo"), CardAction("Got it", ActionRole.ARGO, "change"), OPEN),
    )

    // ---- before ----

    fun countdown(t: Trip, w: DestWeather?, now: Long, zone: ZoneId, alarms: AlarmWatch? = null): Pair<CardContent?, TripHome> {
        val start = t.days(zone)?.first
        val days = start?.let { ChronoUnit.DAYS.between(Trip.day(now, zone), it) }
        if (days != null && days > 120) return null to TripHome()
        val gaps = Gaps.of(t, w, now, zone, alarms)
        val ask = gaps.firstOrNull { it.gap.ask != null && it.gap.name !in t.askedIris }?.takeIf { days != null && days <= 60 }
        val nudge = ask?.let { g -> "Ask ${Workers.IRIS} to look for ${g.gap.ask}?" }
        val content = CardContent(
            title = "${t.place} ${Travel.countdown(t, now, zone)}",
            body = listOfNotNull(Travel.dates(t, zone), nudge).joinToString(". "),
            facts = gaps.map { g ->
                val label = when (g.gap) { Gap.WEATHER -> "Weather"; Gap.ALARM -> "Alarm"; else -> "Missing" }
                Fact(label, g.text, if (g.gap.name in t.askedIris) "${Workers.IRIS} is looking" else null, if (g.gap == Gap.WEATHER || g.gap == Gap.ALARM) Tone.ACTIVE else Tone.QUIET)
            },
            status = if (gaps.isEmpty()) "All set" else "${gaps.size} missing",
            tone = if (gaps.any { it.gap == Gap.WEATHER || it.gap == Gap.ALARM }) Tone.ACTIVE else if (gaps.isEmpty()) Tone.OK else Tone.QUIET,
            actions = listOfNotNull(ask?.let { CardAction("Ask ${Workers.IRIS}", ActionRole.ARGO, "ask:${t.id}:${it.gap.name}") }, OPEN),
        )
        val fit = when { days == null -> 1f; days <= 1 -> 22f; days <= 7 -> 14f; days <= 21 -> 7f; else -> 2f }
        return content to TripHome(fit = fit, kicker = if (t.status == TripStatus.IDEA) "Trip idea" else "Trip", today = days == 0L)
    }

    // ---- travel day ----

    fun travel(t: Trip, l: Leg, now: Long, zone: ZoneId): Pair<CardContent, TripHome> {
        val live = LiveWords.of(t, l, now, zone)
        val stay = t.stays.firstOrNull()
        val depZ = if (l.returning) t.destZone(zone) else zone
        val content = CardContent(
            title = live.title,
            body = live.text,
            facts = buildList {
                add(Fact(l.mode.word, listOf(l.from, l.to).filter { it.isNotBlank() }.joinToString(" to ").ifEmpty { "To ${t.place}" }))
                if (l.departAt > 0 && live.stage == Stage.LEAVE) add(Fact("Departs", Clock.hm(l.departAt, depZ), l.platform.ifBlank { null }))
                if (l.doc != null) add(Fact("Ticket", CONFIRMATION))
                if (!l.returning && stay != null) add(Fact("Stay", listOf(stay.name, stay.address).filter { it.isNotBlank() }.joinToString(", ")))
                if (l.returning) t.firstBack?.let { add(Fact("Next", it.removePrefix("First thing back: "))) }
            },
            status = if (live.stage == Stage.DEPART) "Go" else null,
            tone = if (live.stage == Stage.DEPART) Tone.ALERT else Tone.ACTIVE,
            actions = listOfNotNull(
                (if (live.stage == Stage.LEAVE || live.stage == Stage.DEPART) l.from else if (!l.returning) stay?.let { it.address.ifBlank { it.name } } else null)
                    ?.takeIf { it.isNotBlank() }?.let { CardAction("Directions", ActionRole.ARGO, "maps:$it") },
                VAULT.takeIf { l.doc != null }, OPEN,
            ),
        )
        return content to TripHome(fit = 40f, kicker = if (l.returning) "Going home" else "Travel day", today = true, alert = 15f, widget = travelWidget(t, l, live, zone))
    }

    // ---- at the destination ----

    fun away(t: Trip, w: DestWeather?, now: Long, zone: ZoneId): Pair<CardContent, TripHome> {
        val dz = t.destZone(zone)
        val today = Trip.day(now, dz)
        val (s, e) = t.days(zone)!!
        val dayN = ChronoUnit.DAYS.between(s, today) + 1
        val total = ChronoUnit.DAYS.between(s, e) + 1
        val stay = t.stays.firstOrNull()
        val plans = todayPlans(t, now, zone)
        val content = CardContent(
            title = "${t.place}, day $dayN of $total",
            lead = weatherLead(w, now, dz),
            facts = buildList {
                stay?.let { add(Fact("Stay", listOf(it.name, it.address).filter { x -> x.isNotBlank() }.joinToString(", "))) }
                weatherLine(w, today)?.let { add(Fact("Weather", it)) }
                for (p in plans) add(Fact(Clock.hm(p.at, dz), p.title + (p.place.takeIf { it.isNotBlank() && !p.title.contains(it, true) }?.let { ", $it" } ?: "") + (p.people.filter { !p.title.contains(it, true) }.takeIf { it.isNotEmpty() }?.let { " with " + it.joinToString(", ") } ?: ""),
                    if (p.at - now in 0..6 * 3_600_000L) "Leave ${if (stay != null) "the hotel " else ""}by ${Clock.hm(p.at - LEAVE_BEFORE, dz)}" else null))
                t.back?.takeIf { it.departAt > 0 }?.let { b -> add(Fact("Back", "${b.mode.word}, ${Clock.day(Trip.day(b.departAt, dz))} ${Clock.hm(b.departAt, dz)}")) }
                if (dayN == total) t.firstBack?.let { add(Fact("Then", it.removePrefix("First thing back: "))) }
            },
            tone = Tone.OK,
            actions = listOfNotNull(stay?.let { s0 -> CardAction("To the ${if (s0.name.contains("airbnb", true)) "stay" else "hotel"}", ActionRole.ARGO, "maps:" + s0.address.ifBlank { s0.name }) }, OPEN),
        )
        return content to TripHome(fit = 40f, kicker = "Trip mode", today = true, widget = awayWidget(t, w, now, zone))
    }

    /** About half an hour to anywhere in a city he doesn't drive in; the card says "Leave by", not a route. */
    const val LEAVE_BEFORE = 30 * 60_000L

    fun todayPlans(t: Trip, now: Long, zone: ZoneId): List<TripPlan> {
        val dz = t.destZone(zone)
        val today = Trip.day(now, dz)
        return t.plans.filter { it.at > 0 && Trip.day(it.at, dz) == today && it.at > now - 60 * 60_000L }.sortedBy { it.at }.take(4)
    }

    private fun weatherLead(w: DestWeather?, now: Long, dz: ZoneId): String? = w?.takeIf { now - it.at < 6 * 3_600_000L }?.tempC?.let(::f)

    fun weatherLine(w: DestWeather?, date: java.time.LocalDate): String? {
        val d = w?.days?.firstOrNull { it.date == date.toString() } ?: return null
        return listOfNotNull(Sky.words(d.code), d.highC?.let { "H ${f(it)}" }, d.lowC?.let { "L ${f(it)}" }, d.rainChance?.takeIf { it >= 30 }?.let { "$it% rain" }).joinToString(" · ")
    }

    // ---- back ----

    private fun backHome(t: Trip): Pair<CardContent, TripHome> = CardContent(
        title = "Back from ${t.place}",
        body = listOfNotNull(t.firstBack, t.recap).joinToString(". ").ifEmpty { null },
        actions = listOf(CardAction("Got it", ActionRole.ACK)),
    ) to TripHome(fit = 6f, kicker = "Trip recap")

    // ---- the trip widget (Today, replaced) ----

    fun travelWidget(t: Trip, l: Leg, live: LiveText, zone: ZoneId): WView {
        val depZ = if (l.returning) t.destZone(zone) else zone
        val arrZ = if (l.returning) zone else t.destZone(zone)
        val stay = t.stays.firstOrNull()
        val what = listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { l.mode.word }
        return WView(
            kind = WKind.TODAY,
            place = dev.phonerecall.ui.Place.TRIPS,
            title = live.title,
            sub = if (l.returning) "Going home" else "To ${t.place}",
            groups = listOfNotNull(
                WGroup("Next leg", listOfNotNull(
                    WRow("$what to ${l.to.ifBlank { t.place }}", if (l.departAt > 0) Clock.hm(l.departAt, depZ) else "", WTone.ACTIVE, Mark.DOT, dest = DEST),
                    l.platform.takeIf { it.isNotBlank() }?.let { WRow(it, "", WTone.ALERT, Mark.DOT, dest = DEST) },
                    l.arriveAt.takeIf { it > 0 }?.let { WRow("Arrive ${Clock.hm(it, arrZ)}", l.to, WTone.QUIET, Mark.NONE, dest = DEST) },
                )),
                WGroup("Argo", listOfNotNull(
                    WRow(CONFIRMATION, "", WTone.QUIET, Mark.NONE, dest = "VAULT").takeIf { l.doc != null },
                    stay?.takeIf { !l.returning }?.let { WRow(it.name.ifBlank { "Your stay" }, it.address, WTone.QUIET, Mark.NONE, dest = DEST) },
                )).takeIf { it.rows.isNotEmpty() },
            ),
            tone = if (live.stage == Stage.DEPART) WTone.ALERT else WTone.ACTIVE,
        )
    }

    fun awayWidget(t: Trip, w: DestWeather?, now: Long, zone: ZoneId): WView {
        val dz = t.destZone(zone)
        val stay = t.stays.firstOrNull()
        val today = Trip.day(now, dz)
        val (s, e) = t.days(zone)!!
        return WView(
            kind = WKind.TODAY,
            place = dev.phonerecall.ui.Place.TRIPS,
            title = "${t.place}, day ${ChronoUnit.DAYS.between(s, today) + 1} of ${ChronoUnit.DAYS.between(s, e) + 1}",
            sub = weatherLine(w, today).orEmpty(),
            groups = listOfNotNull(
                WGroup("Today", todayPlans(t, now, zone).map { p -> WRow(p.title, Clock.hm(p.at, dz), if (p.at - now in 0..2 * 3_600_000L) WTone.ACTIVE else WTone.QUIET, Mark.DOT, dest = DEST) }, "Nothing planned"),
                WGroup("Stay", listOf(WRow(stay?.name?.ifBlank { null } ?: "Your stay", stay?.address.orEmpty(), WTone.QUIET, Mark.NONE, dest = DEST))).takeIf { stay != null },
                t.back?.takeIf { it.departAt > 0 }?.let { b -> WGroup("Back", listOf(WRow("${b.mode.word} home", Clock.day(Trip.day(b.departAt, dz)).substringBefore(',') + " " + Clock.hm(b.departAt, dz), WTone.QUIET, Mark.DOT, dest = DEST))) },
            ),
            tone = WTone.OK,
        )
    }

    // ---- the Trips widget (its own) ----

    /**
     * Argo's own widget: the countdown to the next trip ("Austin in 72 days"); on a travel day the leg and
     * when to leave ("Flight AA 404 at 7:42 AM", "Leave by 5:42 AM"); away, the next plan and its leave-by.
     */
    fun widget(d: ArgoData, now: Long, zone: ZoneId): WView {
        val cur = Travel.current(d.trips, now, zone)
            ?: return WView(WKind.TRIPS, "No trip planned", "Trips in your calendar and mail show here")
        val (t, m) = cur
        return when (m.phase) {
            Phase.TRAVEL -> {
                val l = m.leg!!
                val depZ = if (l.returning) t.destZone(zone) else zone
                val what = listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { l.mode.word }
                val leave = Travel.leaveBy(l)
                travelWidget(t, l, LiveWords.of(t, l, now, zone), zone).copy(
                    kind = WKind.TRIPS, place = null,
                    title = if (l.departAt > 0) "$what at ${Clock.hm(l.departAt, depZ)}" else "$what to ${l.to.ifBlank { t.place }}",
                    sub = if (now < leave) "Leave by ${Clock.hm(leave, depZ)}" else LiveWords.of(t, l, now, zone).title,
                )
            }
            Phase.AWAY -> {
                val dz = t.destZone(zone)
                val next = todayPlans(t, now, zone).firstOrNull { it.at > now - 15 * 60_000L }
                val base = awayWidget(t, d.weather[t.id], now, zone)
                base.copy(
                    kind = WKind.TRIPS, place = null,
                    title = next?.let { "${it.title} at ${Clock.hm(it.at, dz)}" } ?: base.title,
                    sub = next?.takeIf { it.at - now in 0..6 * 3_600_000L }?.let { "Leave by ${Clock.hm(it.at - LEAVE_BEFORE, dz)}" } ?: base.title.takeIf { next != null } ?: base.sub,
                )
            }
            else -> {
                val days = t.days(zone)?.first?.let { ChronoUnit.DAYS.between(Trip.day(now, zone), it) }
                val word = when { days == null -> Travel.countdown(t, now, zone); days <= 0 -> "today"; days == 1L -> "tomorrow"; else -> "in $days days" }
                val out = t.outbound?.takeIf { it.departAt > 0 }
                val later = d.trips.filter { it.open && it.id != t.id }.map { it to Travel.moment(it, now, zone) }.filter { it.second.phase == Phase.BEFORE }
                    .sortedBy { it.first.window(zone)!!.first }.take(3)
                WView(
                    WKind.TRIPS,
                    title = "${t.place} $word",
                    sub = Travel.dates(t, zone),
                    groups = listOfNotNull(
                        out?.let { l ->
                            val what = listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { l.mode.word }
                            WGroup("Getting there", listOf(WRow("$what to ${l.to.ifBlank { t.place }}", Clock.day(Trip.day(l.departAt, zone)).substringBefore(',') + " " + Clock.hm(l.departAt, zone), WTone.QUIET, Mark.DOT, dest = DEST)))
                        },
                        WGroup("After that", later.map { (o, _) -> WRow(o.place, Travel.countdown(o, now, zone), WTone.QUIET, Mark.DOT, dest = DEST) }).takeIf { it.rows.isNotEmpty() },
                    ),
                    tone = if (days != null && days <= 1) WTone.ACTIVE else WTone.QUIET,
                )
            }
        }
    }

    const val DEST = "TRIPS"
}
