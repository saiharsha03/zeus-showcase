package dev.phonerecall.mind

import android.content.Context
import dev.phonerecall.aeolia.Sky
import dev.phonerecall.aeolia.WeatherState
import dev.phonerecall.argo.ArgoData
import dev.phonerecall.argo.TripStatus
import dev.phonerecall.brain.BirthdayBook
import dev.phonerecall.brain.BirthdayFrom
import dev.phonerecall.brain.BirthdaySeen
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.NudgeState
import dev.phonerecall.brain.Person
import dev.phonerecall.brain.Plan
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.SpendState
import dev.phonerecall.brief.MorningBrief
import dev.phonerecall.brief.ScreenDay
import dev.phonerecall.clio.ClioLine
import dev.phonerecall.closet.VenusPrefs
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.ContactBirthday
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.hestia.HomeRules
import dev.phonerecall.hestia.HomeSnapshot
import dev.phonerecall.iaso.IasoState
import dev.phonerecall.iaso.SleepNight
import dev.phonerecall.iaso.WeightTrend
import dev.phonerecall.lemnos.ChatMsg
import dev.phonerecall.moneta.Money
import dev.phonerecall.moneta.MonetaState
import dev.phonerecall.moneta.Subscription
import dev.phonerecall.moneta.TxnKind
import dev.phonerecall.music.MusicSettings
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.music.TrackStat
import dev.phonerecall.odyssey.DrivingReport
import dev.phonerecall.odyssey.Fix
import dev.phonerecall.odyssey.LeasePace
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.Units
import dev.phonerecall.odyssey.Visits
import dev.phonerecall.olympia.OlympiaSnapshot
import dev.phonerecall.theogony.SeedFact
import dev.phonerecall.widgets.WKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * read_zeus's readers, one per area (ZeusRead has the index, filters and cap). Each mapper is pure: the feature's own
 * state in, a small map out, newest first and within the ask's days. [phone] loads each from the feature's store.
 * Raw values on purpose (everything but Calypso). Calypso itself is never read here.
 */
object ZeusAreas {
    private const val DAY = 86_400_000L
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private val ZeusRead.Ask.ms: Long get() = now.toInstant().toEpochMilli()
    private val ZeusRead.Ask.from: Long get() = ms - days * DAY
    private val ZeusRead.Ask.zone: ZoneId get() = now.zone
    private val ZeusRead.Ask.today: Long get() = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private fun ZeusRead.Ask.t(ms: Long?): String? = ms?.takeIf { it > 0 }?.let { ISO.format(Instant.ofEpochMilli(it).atZone(zone)) }
    private fun usd(cents: Long?): Double? = cents?.let { it / 100.0 }
    private fun m(vararg kv: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*kv).filterValues { it != null && it != "" && !(it is Collection<*> && it.isEmpty()) }

    fun places(d: OdysseyData, a: ZeusRead.Ask): Map<String, Any?> {
        val byId = d.places.associateBy { it.id }
        fun name(id: String?) = id?.let { byId[it] }?.let { p -> p.name.ifBlank { p.kind.label } }
        val stay = d.stay?.let { s ->
            val known = Visits.known(d, Fix(s.start, s.lat, s.lng))
            m("place" to (known?.name ?: s.poiName ?: s.poi ?: s.street), "kind" to (known?.kind ?: s.poiKind)?.label, "since" to a.t(s.start), "lat" to s.lat, "lng" to s.lng, "street" to s.street)
        }
        return m(
            "tracking" to Visits.tracking(d), "driving" to d.driving, "now" to (stay ?: "not at a stay"),
            "today_stays" to d.visits.filter { it.end >= a.today }.sortedByDescending { it.start }.map { v -> m("place" to name(v.placeId), "start" to a.t(v.start), "end" to a.t(v.end), "minutes" to v.minutes) },
            "today_drives" to d.trips.filter { it.end >= a.today }.sortedByDescending { it.start }.map { t -> m("from" to name(t.from), "to" to name(t.to), "start" to a.t(t.start), "end" to a.t(t.end), "minutes" to t.minutes) },
            "earlier_stays" to d.visits.filter { it.start >= a.from && it.end < a.today }.sortedByDescending { it.start }.map { v -> m("place" to name(v.placeId), "start" to a.t(v.start), "minutes" to v.minutes) },
            "places" to d.places.sortedByDescending { it.visits }.map { p ->
                m("name" to p.name.ifBlank { null }, "role" to p.kind.label, "named_by" to p.source.name.lowercase(), "visits" to p.visits, "lat" to p.lat, "lng" to p.lng, "street" to p.street, "learned" to p.learned.takeIf { it })
            },
        )
    }

    fun drives(d: OdysseyData, a: ZeusRead.Ask): Map<String, Any?> {
        val w = DrivingReport.week(d.drives, a.ms)
        val pace = LeasePace.pace(d.car.lease, d.car.readings, d.drives, a.now.toLocalDate(), a.zone)
        val l = d.car.lease
        val last = d.car.lastReading()
        return m(
            "week" to m("trips" to w.trips, "minutes" to w.minutes, "miles" to w.miles, "score" to w.score, "last_week_score" to w.lastWeekScore,
                "events" to w.counts.mapKeys { it.key.label }, "screen_minutes_moving" to w.screenMinutes.roundToInt(), "spots" to w.spots),
            "lease" to pace?.let { p -> m("line" to LeasePace.brainLine(p), "month" to p.month, "term" to p.term, "miles" to p.miles, "per_year" to p.perYear, "allowance" to p.allowance, "projected_over" to p.over, "over_usd" to usd(p.overCents), "ahead" to p.ahead) },
            "car" to m("car" to l.car, "lessor" to l.lessor, "lease_start" to l.start, "term_months" to l.termMonths, "allowance" to l.miles, "start_odometer" to l.startOdometer,
                "odometer" to last?.miles, "odometer_at" to a.t(last?.at),
                "fillups" to d.car.fillups.filter { it.at >= a.from }.sortedByDescending { it.at }.map { f -> m("at" to a.t(f.at), "place" to f.place, "usd" to usd(f.cents), "gallons" to f.gallons, "price_per_gal" to f.pricePerGal, "odometer" to f.odometer, "state" to f.state) }),
            "drives" to d.drives.filter { it.end >= a.from }.sortedByDescending { it.start }.map { r ->
                m("start" to a.t(r.start), "minutes" to r.minutes, "miles" to (Units.miles(r.meters) * 10).roundToInt() / 10.0, "max_mph" to (r.maxKmh / Units.KMH_PER_MPH).roundToInt(), "score" to r.score,
                    "from" to r.from, "to" to r.to, "weather" to r.weather, "tag" to r.tag, "events" to r.events.groupingBy { it.kind.label }.eachCount(), "screen_minutes_moving" to r.phone.screenMinutesMoving)
            },
        )
    }

    fun money(s: MonetaState, subs: List<Subscription>, a: ZeusRead.Ask): Map<String, Any?> {
        // The widget's week: the last 7 days' net spend against the four weeks before (Widgets.money), with the amounts.
        fun net(from: Long, to: Long) = s.txns.filter { it.counted && it.at in from until to }.sumOf { when (it.kind) { TxnKind.SPEND -> it.cents; TxnKind.REFUND -> -it.cents; else -> 0L } }.coerceAtLeast(0)
        val week = net(a.ms - 7 * DAY, a.ms + 1)
        val usual = (1..4).map { net(a.ms - (it + 1) * 7 * DAY, a.ms - it * 7 * DAY) }.filter { it > 0 }.takeIf { it.size >= 2 }?.average()
        return m(
            "week" to m("spent_usd" to usd(week), "usual_usd" to usual?.let { it.roundToInt() / 100.0 }, "percent_of_usual" to usual?.let { (week * 100 / it).roundToInt() }),
            "flags" to Money.unusual(s.txns, a.ms, a.zone, s.dismissed, known = s.known).map { u -> m("merchant" to u.txn.merchant, "usd" to usd(u.txn.cents), "at" to a.t(u.txn.at), "why" to u.reason, "fraud" to u.fraud, "bank" to u.txn.bank) },
            "subscriptions" to subs.sortedBy { it.next ?: LocalDate.MAX }.map { u -> m("name" to u.name, "usd" to usd(u.cents), "cycle" to u.cycle.word, "next" to u.next?.toString(), "source" to u.source.word, "bank" to u.bank) },
            "transactions" to s.txns.filter { it.mergedInto == null && it.at >= a.from }.sortedByDescending { it.at }.map { t ->
                m("at" to a.t(t.at), "usd" to usd(t.cents), "currency" to t.currency.takeIf { it != "USD" }, "kind" to t.kind.word, "merchant" to t.merchant, "category" to t.category.word,
                    "bank" to t.bank, "last4" to t.last4, "pending" to t.pending.takeIf { it }, "note" to t.note)
            },
        )
    }

    fun health(s: IasoState, a: ZeusRead.Ask): Map<String, Any?> {
        val since = a.now.toLocalDate().minusDays(a.days.toLong())
        val r = s.report
        return m(
            "health_connect" to s.status.name.lowercase(), "read_at" to a.t(r.readAt),
            "days" to r.days.filter { !it.date.isBefore(since) }.sortedByDescending { it.date }.map { d -> m("date" to d.date.toString(), "steps" to d.steps, "active_kcal" to d.activeKcal?.roundToInt(), "exercise_min" to d.exerciseMin, "resting_hr" to d.restingHr, "avg_hr" to d.avgHr) },
            "sleep" to sleepRows(r.sleep, since, a),
            "weight_latest_kg" to s.weights.lastOrNull()?.kg,
            "weight_trend" to WeightTrend.words(WeightTrend.weeklySlope(s.weights, a.ms)),
            "weights_kg" to s.weights.filter { it.at >= a.from }.sortedByDescending { it.at }.map { w -> m("at" to a.t(w.at), "kg" to (w.kg * 10).roundToInt() / 10.0) },
            "meals" to s.meals.filter { it.at >= a.from }.sortedByDescending { it.at }.map { f -> m("at" to a.t(f.at), "meal" to f.type.label, "dish" to f.dish, "portion" to f.portion, "kcal" to f.kcal, "protein_g" to f.proteinG, "carbs_g" to f.carbsG, "fat_g" to f.fatG) },
            "water_glasses" to s.water.filterKeys { !it.isBefore(since) }.toSortedMap(reverseOrder()).mapKeys { it.key.toString() },
            "workouts_today" to r.workoutsToday.map { w -> m("name" to w.name, "start" to a.t(w.start), "minutes" to w.minutes) },
        )
    }

    private fun sleepRows(sleep: List<SleepNight>, since: LocalDate, a: ZeusRead.Ask) = sleep.filter { !it.date.isBefore(since) }.sortedByDescending { it.date }.map { n ->
        m("woke_on" to n.date.toString(), "asleep_min" to n.asleepMin, "deep_min" to n.deepMin, "rem_min" to n.remMin, "in_bed" to a.t(n.start), "up" to a.t(n.end))
    }

    /** Hestia's own status for the tools: homes, rooms, devices with their state, automations. */
    fun home(s: HomeSnapshot): Map<String, Any?> = HomeRules.status(s, null)

    fun alarms(summary: Map<String, Any?>, rings: List<Long>, a: ZeusRead.Ask): Map<String, Any?> = LinkedHashMap(summary).apply {
        remove("opened")
        put("recent_rings", rings.filter { it >= a.from }.sortedDescending().map { a.t(it) })
    }

    fun sleep(plan: Plan?, nudge: NudgeState, nudgesOn: Boolean, nights: List<SleepNight>, a: ZeusRead.Ask): Map<String, Any?> = m(
        "next_wake" to plan?.let { p ->
            m("date" to p.date.toString(), "up_by" to a.t(p.early.at), "because" to p.early.reason, "from" to p.early.source.name.lowercase(), "alarm" to a.t(p.alarmAt),
                "bedtime" to a.t(p.bedtime), "reasons" to p.reasons, "why" to p.why)
        },
        "bedtime_nudges" to if (nudgesOn) "on" else "off",
        "nudges_sent" to nudge.night?.let { m("night" to it.toString(), "sent" to nudge.sent) },
        "nudges_off_for" to nudge.offFor?.toString(),
        "nights" to sleepRows(nights, a.now.toLocalDate().minusDays(a.days.toLong()), a),
    )

    fun trips(d: ArgoData, a: ZeusRead.Ask): Map<String, Any?> = m(
        "trips" to d.trips.filter { it.status != TripStatus.DISMISSED }.sortedWith(compareByDescending<dev.phonerecall.argo.Trip> { it.open }.thenByDescending { it.startDate ?: it.month ?: "" }).map { t ->
            m(
                "place" to t.place, "start" to t.startDate, "end" to t.endDate, "month" to t.month, "vague" to t.vague, "status" to t.status.name.lowercase(),
                "draft" to t.draft.takeIf { it }, "confidence" to t.confidence, "his" to t.locked.takeIf { it }, "lat" to t.lat, "lng" to t.lng, "zone" to t.zone,
                "stops" to t.stops.map { s -> m("place" to s.place, "start" to s.start, "end" to s.end) }, "layovers" to t.layovers,
                "legs" to t.legs.sortedBy { it.departAt }.map { l ->
                    m("mode" to l.mode.word, "from" to l.from, "to" to l.to, "departs" to a.t(l.departAt), "arrives" to a.t(l.arriveAt), "carrier" to l.carrier, "number" to l.number,
                        "platform" to l.platform, "return" to l.returning.takeIf { it }, "usd" to usd(l.cents), "paid" to l.paid)
                },
                "stays" to t.stays.map { s -> m("name" to s.name, "address" to s.address, "check_in" to a.t(s.checkIn), "check_out" to a.t(s.checkOut), "usd" to usd(s.cents), "paid" to s.paid) },
                "plans" to t.plans.sortedBy { it.at }.map { p -> m("title" to p.title, "at" to a.t(p.at), "end" to a.t(p.end), "place" to p.place, "people" to p.people, "kind" to p.kind) },
                "people" to t.people, "notes" to t.notes, "budget_usd" to usd(t.budgetCents), "recap" to t.recap,
            )
        },
    )

    fun sports(s: OlympiaSnapshot, a: ZeusRead.Ask): Map<String, Any?> = m(
        "fixtures" to s.followed.filter { abs(it.start - a.ms) <= a.days * DAY }.sortedByDescending { it.start }.map { x ->
            m("title" to x.title, "league" to x.leagueName, "start" to a.t(x.start), "phase" to x.phase.name.lowercase(), "home" to x.home?.name, "away" to x.away?.name,
                "score" to x.score, "clock" to x.clock, "detail" to x.detail, "session" to x.session, "result" to x.result)
        },
        "teams" to s.teams.values.mapNotNull { t -> m("team" to t.key, "standing" to t.standing, "record" to t.record).takeIf { it.size > 1 } },
        "refreshed_at" to a.t(s.refreshedAt),
    )

    fun music(set: MusicSettings, now: NowPlaying?, skipped: List<TrackStat>, a: ZeusRead.Ask): Map<String, Any?> = m(
        "connected" to set.connected, "player" to set.player.name.lowercase(), "spotify_installed" to set.spotifyInstalled, "drive_playlist" to set.drivePlaylist?.name, "learns_skips" to set.learnSkips,
        "now_playing" to now?.let { m("title" to it.title, "artist" to it.artist, "playing" to it.playing) },
        "skips" to skipped.filter { it.skips > 0 }.sortedByDescending { it.lastSeen }.map { s -> m("song" to s.name, "artist" to s.artist, "plays" to s.plays, "skips" to s.skips, "playlist" to s.context, "last" to a.t(s.lastSeen)) },
    )

    fun closet(c: ClosetSnapshot, v: VenusPrefs, a: ZeusRead.Ask): Map<String, Any?> {
        val since = a.now.toLocalDate().minusDays(a.days.toLong()).toEpochDay()
        val live = c.items.filter { !it.archived }
        return m(
            "items" to live.sortedByDescending { v.worn[it.id]?.lastOrNull() ?: -1L }.map { i ->
                m("name" to i.name, "group" to i.group.label, "category" to i.category, "colour" to i.colour, "brand" to i.brandGuess, "formality" to i.formality, "warmth" to i.temp,
                    "occasions" to i.occasions.sorted(), "size" to i.size, "favorite" to (i.id in v.favorites).takeIf { it },
                    "last_worn" to v.worn[i.id]?.lastOrNull()?.let { LocalDate.ofEpochDay(it).toString() }, "worn_lately" to v.worn[i.id]?.count { it >= since }?.takeIf { it > 0 })
            },
            "archived" to (c.items.size - live.size).takeIf { it > 0 },
        )
    }

    fun weather(w: WeatherState, a: ZeusRead.Ask): Map<String, Any?>? = if (!w.hasData) null else m(
        "place" to w.place, "country" to w.country, "lat" to w.lat, "lng" to w.lon, "sky" to Sky.words(w.code),
        "temp_f" to w.tempC?.let { WeatherState.f(it) }, "feels_f" to w.feelsC?.let { WeatherState.f(it) }, "high_f" to w.highC?.let { WeatherState.f(it) }, "low_f" to w.lowC?.let { WeatherState.f(it) },
        "rain_chance" to w.rainChance, "next" to w.next, "next_at" to a.t(w.nextAt), "aqi" to w.aqi,
        "alerts" to w.activeAlerts(a.ms).map { m("event" to it.event, "severity" to it.severity, "headline" to it.headline, "ends" to a.t(it.ends)) },
        "updated" to a.t(w.updatedAt),
    )

    fun brief(b: MorningBrief?, screen: ScreenDay?, a: ZeusRead.Ask): Map<String, Any?>? = if (b == null && screen == null) null else m(
        "brief" to b?.let { x ->
            fun lines(l: List<dev.phonerecall.brief.BriefLine>) = l.map { "${it.label}: ${it.text}" }
            m("date" to x.date, "posted" to a.t(x.postedAt), "title" to x.title, "lede" to x.lede, "today" to lines(x.today), "yesterday" to lines(x.yesterday), "iris" to lines(x.iris), "proposals" to x.proposals.map { it.title })
        },
        "screen_time" to screen?.let { s -> m("date" to s.date, "total_min" to s.totalMin, "pickups" to s.pickups, "top" to s.top.map { "${it.label}: ${it.minutes} min" }, "late_app" to s.lateApp, "late_at" to a.t(s.lateAt)) },
    )

    fun praxis(t: TaskSnapshot, a: ZeusRead.Ask): Map<String, Any?> {
        val open = t.open().filter { !it.sample }
        fun of(l: TaskList) = open.filter { it.list == l }
        return m(
            "projects" to t.projects.filter { !it.sample }.map { p ->
                val mine = open.filter { it.projectId == p.id }
                m("name" to p.name, "open" to mine.size, "next_actions" to mine.filter { it.list == TaskList.NEXT }.map { it.title }, "stalled" to (mine.none { it.list == TaskList.NEXT }).takeIf { it })
            },
            "next_actions" to of(TaskList.NEXT).map { x -> m("title" to x.title, "project" to t.project(x.projectId)?.name, "context" to x.context?.label, "due" to a.t(x.due), "two_minutes" to x.quick.takeIf { it }) },
            "waiting_for" to of(TaskList.WAITING).map { x -> m("title" to x.title, "on" to x.waitingOn, "check_back" to a.t(x.followUp)) },
            "scheduled" to of(TaskList.SCHEDULED).sortedBy { it.due ?: Long.MAX_VALUE }.map { x -> m("title" to x.title, "due" to a.t(x.due)) },
            "inbox" to of(TaskList.INBOX).map { it.title },
            "someday" to of(TaskList.SOMEDAY).map { it.title },
            "done_lately" to t.tasks.filter { !it.sample && (it.doneAt ?: 0) >= a.from }.sortedByDescending { it.doneAt }.map { x -> m("title" to x.title, "done" to a.t(x.doneAt)) },
            "last_review" to a.t(t.lastReview),
        )
    }

    fun fates(all: List<Proposal>, a: ZeusRead.Ask): Map<String, Any?> {
        fun row(p: Proposal) = m(
            "id" to p.id, "title" to p.title, "status" to p.status.name.lowercase(), "from" to p.proposer, "auto" to p.auto.takeIf { it },
            "steps" to p.steps.filter { it.selected }.map { "${it.kind.label}: ${it.summary}" }, "outcome" to p.outcome, "made" to a.t(p.createdAt), "decided" to a.t(p.decidedAt),
        )
        return m(
            "open" to all.filter { it.status == ProposalStatus.PENDING }.sortedByDescending { it.createdAt }.map(::row),
            "recent" to all.filter { it.status != ProposalStatus.PENDING && (it.decidedAt ?: it.createdAt) >= a.from }.sortedByDescending { it.decidedAt ?: it.createdAt }.map(::row),
        )
    }

    /** Lemnos's live room when it has been fetched; otherwise the notes the phone kept (cut to 200 characters). */
    fun agora(msgs: List<ChatMsg>, notes: List<MindNote>, a: ZeusRead.Ask): Map<String, Any?> =
        if (msgs.isNotEmpty()) m("posts" to msgs.filter { it.kind == "text" && it.ts * 1000 >= a.from }.sortedByDescending { it.ts }.map { p -> m("at" to a.t((p.ts * 1000).toLong()), "from" to p.sender, "text" to p.text) })
        else m("posts" to notes.filter { it.key.startsWith("agora-") && it.at >= a.from }.sortedByDescending { it.at }.map { n -> m("at" to a.t(n.at), "text" to n.text) }, "kept" to "the phone's copies, each cut to 200 characters")

    fun clio(lines: List<ClioLine>, a: ZeusRead.Ask): Map<String, Any?> {
        val recent = lines.filter { it.ts >= a.from }.sortedByDescending { it.ts }
        fun row(l: ClioLine) = m("at" to a.t(l.ts), "area" to l.area, "level" to l.level.name.lowercase(), "event" to l.event, "detail" to l.detail)
        return m(
            "counts" to recent.groupingBy { it.level.name.lowercase() }.eachCount(),
            "problems" to recent.filter { it.level != dev.phonerecall.clio.Level.INFO }.take(40).map(::row),
            "device" to recent.filter { it.area == "device" || it.area == "places" }.take(40).map(::row),
            "lines" to recent.map(::row),
        )
    }

    fun theogony(facts: List<SeedFact>): Map<String, Any?> = m(
        "confirmed" to facts.filter { it.status == dev.phonerecall.theogony.Status.CONFIRMED }.map { f -> m("about" to f.path.title, "fact" to f.text, "topic" to f.topic, "date" to f.date) },
    )

    /** Labels and relationships, the way Harmonia groups them. Calypso people are left out before anything else. */
    fun people(all: List<Person>): Map<String, Any?> = m(
        "people" to all.filter { !it.calypso }.groupBy { listOf(it.label, it.tier.label, it.zone) }.map { (k, v) ->
            m("label" to k[0], "reply" to k[1], "zone" to k[2], "count" to v.size, "work" to v.any { it.work }.takeIf { it }, "confirmed" to v.count { it.confirmed }.takeIf { it > 0 })
        },
    )

    fun birthdays(events: List<CalItem>, contacts: List<ContactBirthday>, done: Set<String>, a: ZeusRead.Ask): Map<String, Any?> {
        val today = a.now.toLocalDate()
        val window = (0..a.days).map { today.plusDays(it.toLong()) }.toSet()
        val seen = ArrayList<BirthdaySeen>()
        for (e in events) {
            if (e.category != EventCategory.BIRTHDAY || !e.allDay) continue
            val d = Instant.ofEpochMilli(e.begin).atZone(a.zone).toLocalDate()
            if (d in window) seen += BirthdaySeen(BirthdayBook.nameOf(e.title), d, if (e.calendarId == -1L) BirthdayFrom.CONTACTS else BirthdayFrom.CALENDAR, e.timeZone == BirthdayBook.IST.id)
        }
        for (b in contacts) for (d in window) if (b.month == d.monthValue && b.day == d.dayOfMonth) seen += BirthdaySeen(b.name, d, BirthdayFrom.CONTACTS, b.india)
        return m(
            "upcoming" to BirthdayBook.merge(seen, a.zone).sortedBy { it.date }.map { b ->
                m("name" to b.name, "date" to b.date.toString(), "india" to b.india.takeIf { it }, "from" to b.from.map { it.name.lowercase() }, "wished" to (b.id in done).takeIf { it })
            },
        )
    }

    fun widgets(placed: List<Pair<WKind, Int>>): Map<String, Any?> {
        val on = placed.filter { it.second > 0 }.map { (k, n) -> m("widget" to k.name.lowercase(), "count" to n) }
        return m("placed" to if (on.isEmpty()) "none on his home screen" else on)
    }

    fun usage(words: String, spend: SpendState): Map<String, Any?> = m(
        "plans" to words.lines().filter { it.isNotBlank() },
        "zeus_spend" to m("month" to spend.month, "spent_usd" to (spend.spentUsd * 100).roundToInt() / 100.0, "cap_usd" to spend.capUsd,
            "athena_usd" to spend.mindMicros / 10_000 / 100.0, "advisor_usd" to spend.advisorMicros / 10_000 / 100.0),
    )

    /** Calypso's names on the phone: sealed people, sealed apps and the vault's labels. Anything that names one is dropped. */
    /** Fails closed: if any sealed list can't be read this throws, and read_zeus answers nothing rather than unfiltered data. */
    fun sealed(app: Context, core: BrainCore): Set<String> =
        core.mindHost.sealed() + core.store.calypsoApps() + dev.phonerecall.vault.VaultCore.get(app).calypso.items().map { it.label }

    private fun r(f: (ZeusRead.Ask) -> Any?) = f

    /** Each area from its own store on the phone. No network: an area that hasn't loaded says so. */
    fun phone(app: Context, core: BrainCore): Map<String, (ZeusRead.Ask) -> Any?> = mapOf(
        "places" to r { a -> dev.phonerecall.odyssey.OdysseyStore.load(app).takeIf { it.settings.on || it.places.isNotEmpty() }?.let { places(it, a) } },
        "drives" to r { a -> dev.phonerecall.odyssey.OdysseyStore.load(app).takeIf { it.drives.isNotEmpty() || it.car.readings.isNotEmpty() }?.let { drives(it, a) } },
        "money" to r { a ->
            val mc = dev.phonerecall.moneta.MonetaCore.get(app)
            val st = mc.store
            val s = mc.state.value.takeIf { it.loaded } ?: MonetaState(loaded = true, txns = st.txns(), budgets = st.budgets(), manual = st.manualSubs(), irisSubs = st.irisSubs(), dismissed = st.dismissed(), known = st.known())
            money(s, mc.subscriptions(s), a)
        },
        "health" to r { a -> dev.phonerecall.iaso.Iaso.get(app).let { h -> h.load(); health(h.state.value, a) } },
        "home" to r { _ -> home(dev.phonerecall.hestia.HestiaCore.get(app).snapshot) },
        "alarms" to r { a -> dev.phonerecall.brain.AlarmWatch.get(app).let { w -> alarms(w.summary(false), w.seenRings(), a) } },
        "sleep" to r { a ->
            val w = dev.phonerecall.brain.AlarmWatch.get(app)
            val nights = runCatching { dev.phonerecall.iaso.Iaso.get(app).let { h -> h.load(); h.state.value.report.sleep } }.getOrDefault(emptyList())
            sleep(dev.phonerecall.alarms.AlarmPlanner.view(app, a.now, w.next()).plan, w.nudgeState(), w.nudgesOn(), nights, a)
        },
        "trips" to r { a -> trips(dev.phonerecall.argo.ArgoStore.load(app), a) },
        "sports" to r { a -> dev.phonerecall.olympia.OlympiaStore.load(app).takeIf { it.loaded }?.let { sports(it, a) } },
        "music" to r { a -> dev.phonerecall.music.MusicCore.get(app).let { mc -> music(mc.state.value, mc.now.value, mc.skipped.value, a) } },
        "closet" to r { a -> dev.phonerecall.data.ClosetSource(app).load().takeIf { it.items.isNotEmpty() }?.let { closet(it, dev.phonerecall.closet.VenusStore(app).load(), a) } },
        "weather" to r { a -> weather(dev.phonerecall.aeolia.WeatherStore.load(app), a) },
        "brief" to r { a -> brief(dev.phonerecall.brief.BriefStore.load(app), dev.phonerecall.brief.BriefStore.screen(app), a) },
        "praxis" to r { a -> praxis(core.tasks.load(), a) },
        "fates" to r { a -> fates(core.db.all(), a) },
        "agora" to r { a -> agora(dev.phonerecall.lemnos.LemnosHub.get(app).state.value.chat[dev.phonerecall.lemnos.Agora.ROOM].orEmpty(), HeartbeatNotes.since(core.mindKv, 0), a) },
        "clio" to r { a -> dev.phonerecall.clio.Clio.init(app); dev.phonerecall.clio.Clio.get()?.lines(a.from)?.let { clio(it, a) } },
        "theogony" to r { _ -> theogony(dev.phonerecall.theogony.TheogonyHub.get(app).engine.facts()) },
        "people" to r { _ -> people(core.store.people()) },
        "birthdays" to r { a ->
            val events = runCatching { BackgroundReads(app, core).relevantEvents(a.today, a.ms + (a.days + 1) * DAY) }.getOrDefault(emptyList())
            birthdays(events, runCatching { dev.phonerecall.data.BirthdaySource(app).read() }.getOrDefault(emptyList()), dev.phonerecall.brain.BirthdayDay.done(app), a)
        },
        "widgets" to r { _ -> widgets(dev.phonerecall.widgets.WidgetBus.placed(app)) },
        "usage" to r { _ -> usage(dev.phonerecall.metron.MetronCore.get(app).words(), core.spend.state.value) },
    )
}
