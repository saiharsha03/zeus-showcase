package dev.phonerecall.moments

import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.QuietHours
import dev.phonerecall.brain.RawStep
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.odyssey.Commute
import dev.phonerecall.odyssey.Geo
import dev.phonerecall.odyssey.Kind
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.ui.Tone
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The moments. `context` ones come up on their own as an Olympus card; the rest only when he asks. */
enum class MomentKind(val label: String, val context: Boolean) {
    COOKING("Dinner from the freezer", true),
    PARKING("Parking meter", true),
    LEAVING("Leaving home", true),
    BATTERY("Charge before you leave", true),
    BEDTIME("Charge before bed", true),
    HAZARD("Snow and ice before the commute", true),
    TRASH("Trash night", true),
    STAPLE("Staples running low", true),
    CALL_HOME("The usual call home", true),
    // Small touches (qol/): their rules live in QolRules.
    CAR_SPOT("Where you parked", true),
    /** No card: the switch for muting media at the office (qol/WorkMute). */
    WORK_MUTE("Mute media at work", true),
    /** No card: the switch for starting music when the car connects (qol/CarMusic). */
    CAR_MUSIC("Music when the car connects", true),
    NAVIGATE("Navigate at leave-by", true),
    PACKAGE("Package at the door", true),
    RENEWAL("Renewing tomorrow", true),
    FLIGHT_KIT("Flight-day kit", true),
    RIDE_HOME("Ride home after a late night", true),
    STREET_PARKING("Street parking", true),
    SKIP_ALARM("Skip the alarm on a day off", true),
    CALL_CLASH("India clock before a call home", true),
    SPOILER_SAFE("Spoiler-safe results", true),
    BILL_DUE("Bill due", true),
    REFUND_WATCH("Refund not in yet", true),
    RETURN_BY("Return window", true),
    PICKUP("Pickup reminder", true),
    MEMBER_CARD("Member card at the store", true),
    /** No card: the switch for the gas nudge when a drive starts (qol/Gas). */
    GAS("Gas on the way", true),
    STEPS("Walk nudge on a quiet day", true),
    SPLIT("Split this charge", true),
    /** No card: the switch for the ride and delivery Live Update chip (qol/LiveTrip). */
    LIVE_TRIP("Ride and delivery chip", true),
    /** A message card's "Later" chip (qol/Later); no switch. */
    MESSAGE_LATER("Message later", false),
    /** No card: the switch for the "Home in N min" reply chip while driving (qol/HomeEta). */
    HOME_ETA("Home in N min reply", true),
    FAMILY_DIGEST("Family groups: quiet nights, morning digest", true),
    /** Iris's street parking rule, offered once (no switch). */
    STREET_SEED("Street parking from Iris", false),
    LAUNDRY("Laundry", false), STEEP("Tea", false), WORKOUT("Workout", false), RECIPE("Recipe timers", false),
    SIGN("Parking sign", false), FLIGHT("Flight", false), EVENT("Event", false),
}

/** A frozen or bake item he bought, from a receipt or order notification. The name only, never an amount. */
data class Purchase(val item: String = "", val at: Long = 0)

/**
 * What he taught Moments. "Not now" snoozes a kind for the day; three in a row rest it for a week
 * (Iris: three asks is the ceiling). "Never for this" turns it off until he turns it on in Themis.
 */
data class MomentsPrefs(
    val never: Set<MomentKind> = emptySet(),
    val snoozed: Map<MomentKind, Long> = emptyMap(),
    val misses: Map<MomentKind, Int> = emptyMap(),
    val restUntil: Map<MomentKind, Long> = emptyMap(),
    /** The key of the moment he last took or waved off, so the same one never comes back. */
    val handled: Map<MomentKind, String> = emptyMap(),
    val purchase: Purchase? = null,
    /** Trash pickup weekday: Thursday, as the owner said (2026-10-20); null after he clears it in Themis, then asked at most 3 evenings, one a day. */
    val trashDay: java.time.DayOfWeek? = java.time.DayOfWeek.THURSDAY,
    val trashAsks: Int = 0,
    val trashAskedDay: Long = 0,
    /** When the evening-before reminder starts, minutes after midnight (Themis, qol/Bins). */
    val trashMinute: Int = 19 * 60,
    /** The pickup day was preset once (qol/Bins); clearing it in Themis then sticks. */
    val trashPreset: Boolean = false,
    /** Grocery aliases from his corrections (alias to his name for it). */
    val aliases: Map<String, String> = emptyMap(),
    /** When family called (from call notifications he got): times only, the last 8 weeks. */
    val calls: List<Long> = emptyList(),
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val names: Aliases get() = Aliases(aliases)
    fun later(kind: MomentKind, key: String, day: Long): MomentsPrefs {
        val n = (misses[kind] ?: 0) + 1
        return if (n >= 3) copy(misses = misses - kind, restUntil = restUntil + (kind to day + 7), snoozed = snoozed + (kind to day), handled = handled + (kind to key))
        else copy(misses = misses + (kind to n), snoozed = snoozed + (kind to day), handled = handled + (kind to key))
    }
    fun took(kind: MomentKind, key: String): MomentsPrefs = copy(misses = misses - kind, handled = handled + (kind to key))
    fun off(kind: MomentKind): MomentsPrefs = copy(never = never + kind)
    fun on(kind: MomentKind): MomentsPrefs = copy(never = never - kind, restUntil = restUntil - kind, misses = misses - kind)
}

/** Where he parked: a stay opened right after a drive that he walked away from. */
data class Parked(val since: Long, val near: String?)

data class LightOn(val id: String, val name: String)

/** Everything the rules look at, already derived (no coordinates past this point). */
data class MomentContext(
    val now: ZonedDateTime,
    val odysseyOn: Boolean = false,
    /** null: unknown (Odyssey off, no home, or between stays). */
    val atHome: Boolean? = null,
    val driving: Boolean = false,
    val parked: Parked? = null,
    /** His learned departure today, when it is still ahead. */
    val departure: ZonedDateTime? = null,
    /** A drive started from home in the last 10 minutes. */
    val leftHome: Boolean = false,
    val battery: Int? = null,
    val charging: Boolean = false,
    val purchase: Purchase? = null,
    val lightsOn: List<LightOn> = emptyList(),
    /** "Rain from 4 PM", from Aeolia. */
    val rainLater: String? = null,
    /** A Moments proposal is already waiting on a card. */
    val pending: Boolean = false,
    val hazard: MorningHazard? = null,
    /** The clock app's next alarm. */
    val nextAlarm: ZonedDateTime? = null,
    val staples: List<StapleDue> = emptyList(),
    val callSlot: CallSlot? = null,
    /** The small touches' signals (qol/QolCore). */
    val qol: dev.phonerecall.qol.QolSignals = dev.phonerecall.qol.QolSignals(),
)

/**
 * The one Moments card on Olympus: its content and, per action option, the plan Confirm-able from it.
 * A null plan means the option only acknowledges ("Got it").
 */
data class MomentCard(
    val kind: MomentKind, val key: String, val content: CardContent, val fit: Float, val plans: Map<String, MomentPlan?>,
    /** What an option teaches Kairos ("Yes, weekly" sets trash night). */
    val teach: Map<String, (MomentsPrefs) -> MomentsPrefs> = emptyMap(),
    /** An option that opens another app on his tap: "app:<package>" or a web address for the browser. */
    val opens: Map<String, String> = emptyMap(),
    /** Options that leave the card up ("Navigate to car" until he drives off). */
    val keep: Set<String> = emptySet(),
)

object MomentRules {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Late at night these still come up: the way back to the car, an early leave-by. */
    private val NIGHT = setOf(MomentKind.CAR_SPOT, MomentKind.NAVIGATE, MomentKind.FLIGHT_KIT, MomentKind.RIDE_HOME, MomentKind.STREET_PARKING)

    /** The quiet rules. Not when off, snoozed today, resting, in quiet hours (NIGHT excepted), or driving (leaving and parking excepted). */
    fun open(kind: MomentKind, prefs: MomentsPrefs, now: ZonedDateTime, driving: Boolean): Boolean {
        val day = now.toLocalDate().toEpochDay()
        return kind !in prefs.never && prefs.snoozed[kind] != day && (prefs.restUntil[kind] ?: Long.MIN_VALUE) <= day &&
            (!QuietHours.quiet(now) || kind in NIGHT) && (!driving || kind == MomentKind.LEAVING || kind == MomentKind.PARKING)
    }

    /** At most one card: the most time-critical moment that is open and not handled. */
    fun pick(ctx: MomentContext, prefs: MomentsPrefs): MomentCard? {
        if (ctx.pending) return null
        return sequenceOf<(MomentContext) -> MomentCard?>(::parking, dev.phonerecall.qol.StreetParking::card, dev.phonerecall.qol.FamilyNight::card, dev.phonerecall.qol.RideHome::card, dev.phonerecall.qol.QolRules::navigate, dev.phonerecall.qol.FlightKit::card, ::leaving, ::battery, ::bedtime, ::hazard, dev.phonerecall.qol.QolRules::carSpot, dev.phonerecall.qol.Pickups::card, dev.phonerecall.qol.MemberCard::card, dev.phonerecall.qol.Deliveries::card, { trash(it, prefs) }, dev.phonerecall.qol.Bills::card, dev.phonerecall.qol.Split::card, dev.phonerecall.qol.Refunds::card, dev.phonerecall.qol.Returns::card, ::cooking, dev.phonerecall.qol.Renewals::card, dev.phonerecall.qol.SkipAlarm::card, dev.phonerecall.qol.Spoilers::card, ::callHome, dev.phonerecall.qol.Steps::card, dev.phonerecall.qol.IndiaClock::clashCard, ::staple, dev.phonerecall.qol.StreetParking::offer)
            .mapNotNull { it(ctx) }
            .firstOrNull { open(it.kind, prefs, ctx.now, ctx.driving) && prefs.handled[it.kind] != it.key }
    }

    private fun payload(kind: MomentKind, key: String, option: String) = "${kind.name}|$key|$option"
    private fun later(kind: MomentKind, key: String) = CardAction("Not now", ActionRole.MOMENT_LATER, payload(kind, key, ""))
    private fun never(kind: MomentKind, key: String) = CardAction("Never", ActionRole.MOMENT_NEVER, payload(kind, key, ""))

    fun parking(ctx: MomentContext): MomentCard? {
        val p = ctx.parked ?: return null
        if (!ctx.odysseyOn || ctx.driving) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        if (ms - p.since > 20 * 60_000L) return null
        val key = "${p.since}"
        val k = MomentKind.PARKING
        return MomentCard(
            k, key,
            CardContent(
                title = "Meter timer?",
                body = (p.near?.let { "Parked near $it. " } ?: "") + "Zeus warns you 5 minutes before it runs out.",
                actions = listOf(CardAction("1 h", ActionRole.MOMENT_GO, payload(k, key, "60")), CardAction("2 h", ActionRole.MOMENT_GO, payload(k, key, "120")), later(k, key), never(k, key)),
            ),
            fit = 30f, plans = mapOf("60" to MomentPlans.meter(60), "120" to MomentPlans.meter(120)),
        )
    }

    fun leaving(ctx: MomentContext): MomentCard? {
        if (!ctx.odysseyOn) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        val soon = ctx.atHome == true && ctx.departure != null && ctx.departure.toInstant().toEpochMilli() - ms in 0..30 * 60_000L
        if (!soon && !ctx.leftHome) return null
        if (ctx.lightsOn.isEmpty() && ctx.rainLater == null) return null
        val k = MomentKind.LEAVING
        val key = ctx.now.toLocalDate().toString()
        val n = ctx.lightsOn.size
        val lights = if (n == 1) "1 light" else "$n lights"
        val title = when {
            n > 0 && ctx.leftHome -> "$lights still on at home"
            n > 0 -> "Heading out? $lights on"
            else -> "Heading out? ${ctx.rainLater}"
        }
        val facts = buildList {
            if (n > 0) add(Fact("Lights", ctx.lightsOn.take(4).joinToString(", ") { it.name } + if (n > 4) " and ${n - 4} more" else ""))
            ctx.rainLater?.let { add(Fact("Weather", "$it. Take an umbrella", null, Tone.ACTIVE)) }
            if (soon) ctx.departure?.let { add(Fact("You leave", "Usually ${TIME.format(it)}")) }
        }
        val plan = if (n > 0) MomentPlan(k, "Lights off before you go", ctx.lightsOn.take(8).map { RawStep(ProposalKind.HOME_CONTROL, mapOf("device_id" to it.id, "action" to "off")) }) else null
        val go = if (n > 0) CardAction("Lights off", ActionRole.MOMENT_GO, payload(k, key, "go")) else CardAction("Got it", ActionRole.MOMENT_GO, payload(k, key, "go"))
        return MomentCard(k, key, CardContent(title = title, facts = facts, actions = listOf(go, later(k, key), never(k, key))), fit = 26f, plans = mapOf("go" to plan))
    }

    /** Under 35% and not charging, within an hour of leaving: his usual departure or the next event's leave-by (qol). */
    fun battery(ctx: MomentContext): MomentCard? {
        val b = ctx.battery ?: return null
        if (b >= 35 || ctx.charging || ctx.qol.busy) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        val event = ctx.qol.leave?.takeIf { it.leaveBy - ms in 0..60 * 60_000L }
        val usual = ctx.departure?.takeIf { it.toInstant().toEpochMilli() - ms in 0..60 * 60_000L }
        if (event == null && usual == null) return null
        val eventFirst = event != null && (usual == null || event.leaveBy <= usual.toInstant().toEpochMilli())
        val body = if (eventFirst) "Leave by ${TIME.format(Instant.ofEpochMilli(event!!.leaveBy).atZone(ctx.now.zone))} for ${event.title}." else "You usually leave at ${TIME.format(usual)}."
        val k = MomentKind.BATTERY
        val key = "${ctx.now.toLocalDate()}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Charge now: $b%", body = body, status = "$b%", tone = Tone.ALERT,
                actions = listOf(CardAction("Got it", ActionRole.MOMENT_GO, payload(k, key, "go")), never(k, key)),
            ),
            fit = 24f, plans = mapOf("go" to null),
        )
    }

    fun bedtime(ctx: MomentContext): MomentCard? {
        val b = ctx.battery ?: return null
        val alarm = ctx.nextAlarm ?: return null
        if (b >= 30 || ctx.charging || ctx.now.hour !in 21..22) return null
        if (alarm.toInstant().toEpochMilli() - ctx.now.toInstant().toEpochMilli() !in 0..12 * 3_600_000L || alarm.hour >= 8) return null
        val k = MomentKind.BEDTIME
        val key = "${ctx.now.toLocalDate()}"
        return MomentCard(
            k, key,
            CardContent(title = "Charge tonight: $b%", body = "Your alarm is at ${TIME.format(alarm)}.", status = "$b%", tone = Tone.ACTIVE,
                actions = listOf(CardAction("Got it", ActionRole.MOMENT_GO, payload(k, key, "go")), never(k, key))),
            fit = 22f, plans = mapOf("go" to null),
        )
    }

    fun hazard(ctx: MomentContext): MomentCard? {
        val h = ctx.hazard ?: return null
        if (ctx.now.hour !in 18..22) return null
        val k = MomentKind.HAZARD
        val key = "${h.departure.toLocalDate()}"
        // His alarm for that morning, moved earlier by the same minutes (a new alarm through Fates; Undo removes it).
        val earlier = ctx.nextAlarm?.takeIf { it.toLocalDate() == h.departure.toLocalDate() && it.isBefore(h.departure) }?.minusMinutes(h.extraMinutes.toLong())
        val alarm = earlier?.let { MomentPlan(k, "Alarm ${TIME.format(it)}, ${h.extraMinutes} min early for the snow", listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "${it.hour}", "minute" to "${it.minute}", "label" to "Snow: leave early")))) }
        return MomentCard(
            k, key,
            CardContent(title = h.words, body = "${h.advice}.", status = "+${h.extraMinutes} min", tone = Tone.ACTIVE,
                actions = listOfNotNull(
                    CardAction("Got it", ActionRole.MOMENT_GO, payload(k, key, "go")),
                    alarm?.let { CardAction("Alarm ${h.extraMinutes} min earlier", ActionRole.MOMENT_GO, payload(k, key, "alarm")) },
                    later(k, key), never(k, key),
                )),
            fit = 24f, plans = mapOf("go" to null, "alarm" to alarm),
        )
    }

    fun trash(ctx: MomentContext, prefs: MomentsPrefs): MomentCard? {
        val k = MomentKind.TRASH
        val today = ctx.now.toLocalDate()
        val tomorrow = today.plusDays(1).dayOfWeek
        val day = prefs.trashDay
        if (day == null) {
            if (prefs.trashAsks >= 3 || prefs.trashAskedDay == today.toEpochDay() || ctx.now.hour !in 19..21 || ctx.atHome == false) return null
            val key = "ask:$today"
            return MomentCard(
                k, key,
                CardContent(title = "Is tomorrow trash pickup?", body = "Tell me once and Kairos reminds you the evening before.",
                    actions = listOf(CardAction("Yes, weekly", ActionRole.MOMENT_GO, payload(k, key, "yes")), CardAction("No", ActionRole.MOMENT_GO, payload(k, key, "no")), never(k, key))),
                fit = 12f, plans = mapOf("yes" to null, "no" to null),
                teach = mapOf(
                    "yes" to { p: MomentsPrefs -> p.copy(trashDay = tomorrow) },
                    "no" to { p: MomentsPrefs -> p.copy(trashAsks = p.trashAsks + 1, trashAskedDay = today.toEpochDay()) },
                ),
            )
        }
        if (day != tomorrow || ctx.now.hour * 60 + ctx.now.minute < prefs.trashMinute || ctx.now.hour > 22) return null
        val key = "$today"
        val nine = today.atTime(21, 0).atZone(ctx.now.zone)
        val remind = if (ctx.now.isBefore(nine.minusMinutes(5))) MomentPlan(k, "Trash reminder at 9 PM", listOf(RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to "${today}T21:00", "text" to "Trash goes out tonight")))) else null
        return MomentCard(
            k, key,
            CardContent(title = "Trash goes out tonight", body = "Pickup is ${dayWord(day)} morning.",
                actions = listOfNotNull(CardAction("Done", ActionRole.MOMENT_GO, payload(k, key, "go")), remind?.let { CardAction("Remind 9 PM", ActionRole.MOMENT_GO, payload(k, key, "nine")) }, never(k, key))),
            fit = 20f, plans = mapOf("go" to null, "nine" to remind),
        )
    }

    fun callHome(ctx: MomentContext): MomentCard? {
        val s = ctx.callSlot ?: return null
        val m = ctx.now.hour * 60 + ctx.now.minute
        if (ctx.now.dayOfWeek != s.day || m !in (s.minute - 15)..(s.minute + 5)) return null
        val k = MomentKind.CALL_HOME
        val key = "${ctx.now.toLocalDate()}"
        val usual = TIME.format(LocalTime.of(s.minute / 60, s.minute % 60))
        return MomentCard(
            k, key,
            CardContent(title = "Call home?", body = "It's ${CallHabit.india(ctx.now)} in India. You usually call around $usual on ${dayWord(s.day)}s.",
                actions = listOf(CardAction("WhatsApp", ActionRole.MOMENT_GO, payload(k, key, "open")), later(k, key), never(k, key))),
            fit = 18f, plans = mapOf("open" to null), opens = mapOf("open" to "app:com.whatsapp"),
        )
    }

    fun staple(ctx: MomentContext): MomentCard? {
        val s = ctx.staples.firstOrNull() ?: return null
        val k = MomentKind.STAPLE
        val key = "${s.name}:${s.last}"
        val name = s.name.replaceFirstChar { it.uppercase() }
        return MomentCard(
            k, key,
            CardContent(title = "$name again?", body = "You usually buy ${s.name} every ~${s.everyDays} days. It's not on Groceries.",
                actions = listOf(CardAction("Add it", ActionRole.MOMENT_GO, payload(k, key, "go")), later(k, key), never(k, key))),
            fit = 10f, plans = mapOf("go" to MomentPlan(k, "Add ${s.name} to Groceries", listOf(RawStep(ProposalKind.ADD_TO_LIST, mapOf("section" to "Groceries", "items" to s.name))))),
        )
    }

    fun cooking(ctx: MomentContext): MomentCard? {
        val p = ctx.purchase ?: return null
        if (ctx.atHome != true || ctx.now.hour !in 17..20) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        if (ms - p.at !in 0..3 * 86_400_000L) return null
        val g = Foods.guess(p.item) ?: return null
        val k = MomentKind.COOKING
        val key = "${p.at}"
        val v = g.variant
        val bought = Instant.ofEpochMilli(p.at).atZone(ctx.now.zone).let {
            if (it.toLocalDate() == ctx.now.toLocalDate()) "today" else it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        }
        return MomentCard(
            k, key,
            CardContent(
                title = "${g.name} tonight?",
                lead = listOfNotNull(v.temp?.text, v.cook.text).joinToString(" · "),
                body = "You bought it $bought. That's the usual box, a guess; snap yours to be exact.",
                actions = listOf(
                    CardAction("Use guess", ActionRole.MOMENT_GO, payload(k, key, "go")),
                    // Four actions must fit the cover screen: the short words.
                    CardAction("Snap", ActionRole.MOMENT_SNAP, payload(k, key, "")),
                    later(k, key).copy(label = "Later"), never(k, key),
                ),
            ),
            fit = 22f, plans = mapOf("go" to MomentPlans.cook(v, g.name, guess = true)),
        )
    }
}

/** Reads Odyssey's state into the derived signals the rules use. Pure. */
object Signals {
    /** Places with their own lot: no meter there. */
    private val LOT = setOf(Kind.HOME, Kind.WORK, Kind.GROCERY, Kind.GYM, Kind.TEMPLE, Kind.FUEL, Kind.CAR_WASH)

    fun on(d: OdysseyData) = d.settings.on && !d.settings.paused

    fun atHome(d: OdysseyData): Boolean? {
        if (!on(d)) return null
        if (d.driving) return false
        val home = d.home() ?: return null
        val s = d.stay ?: return null
        return Geo.meters(home.lat, home.lng, s.lat, s.lng) < 200
    }

    /** Parked away from home and work, at no place with its own lot, after a drive, and he walked off. A guess at a meter. */
    fun parked(d: OdysseyData, now: Long): Parked? {
        if (!on(d) || d.driving) return null
        val s = d.stay ?: return null
        if (d.walkStart <= s.start || now - d.walkStart > 15 * 60_000L) return null
        val drive = d.drives.maxByOrNull { it.end } ?: return null
        if (kotlin.math.abs(drive.end - s.start) > 5 * 60_000L) return null
        if (d.places.any { it.kind in LOT && Geo.meters(it.lat, it.lng, s.lat, s.lng) < if (it.kind == Kind.HOME || it.kind == Kind.WORK) 300.0 else 150.0 }) return null
        return Parked(s.start, s.poi ?: s.street)
    }

    /** His usual departure today (median from the commute stats), when it is still ahead. */
    fun departure(d: OdysseyData, now: ZonedDateTime): ZonedDateTime? {
        if (!on(d)) return null
        val m = Commute.stats(d, now.toInstant().toEpochMilli(), now.zone).departures[now.dayOfWeek] ?: return null
        val t = now.toLocalDate().atTime(LocalTime.of(m / 60, m % 60)).atZone(now.zone)
        return t.takeIf { !it.isBefore(now) }
    }

    /** His usual drive from home to a place named like an airport, when Odyssey has seen it. */
    fun airportMinutes(d: OdysseyData): Int? {
        if (!on(d)) return null
        val home = d.home()?.id ?: return null
        val airports = d.places.filter { Regex("(?i)airport|\\bBUF\\b").containsMatchIn(it.name) }.map { it.id }.toSet()
        return d.trips.filter { it.from == home && it.to in airports && it.minutes in 5..180 }.map { it.minutes }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    }

    fun leftHome(d: OdysseyData, now: Long): Boolean {
        val home = d.home() ?: return false
        val dep = d.departure ?: return false
        return on(d) && d.driving && dep.placeId == home.id && now - dep.at in 0..10 * 60_000L
    }

    /** "Rain from 4 PM" when Aeolia's next change is wet, or a high chance today. */
    fun rain(next: String?, nextAt: Long?, chance: Int?, now: Long): String? {
        val wet = Regex("(?i)rain|shower|storm|drizzle|snow|sleet")
        if (next != null && wet.containsMatchIn(next) && (nextAt == null || nextAt - now in 0..12 * 3_600_000L)) return next.trimEnd('.')
        if (chance != null && chance >= 60) return "Rain chance $chance% today"
        return null
    }
}

/** Frozen and bake items in a receipt or order notification. Item names only; amounts are never read. */
object Purchases {
    private val CONTEXT = Regex("(?i)\\b(order|receipt|delivered|delivery|purchase|bought|picked up|pickup|items?|your cart|thanks for shopping)\\b")
    private val ITEMS = Regex("(?i)\\b(frozen pizza|digiorno|red baron|tombstone|totino'?s|freschetta|lasagna|stouffer'?s|lean cuisine|pot pie|tater tots|ore-?ida|frozen fries|french fries|chicken nuggets|fish sticks|garlic bread|brownie mix|cookie dough|pillsbury|frozen \\w+)\\b")

    fun item(text: String): String? = if (CONTEXT.containsMatchIn(text)) ITEMS.find(text)?.value?.lowercase() else null
}
