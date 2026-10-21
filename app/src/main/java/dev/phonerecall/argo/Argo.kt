package dev.phonerecall.argo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.aeolia.WeatherNet
import dev.phonerecall.aeolia.WeatherParse
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Sealer
import dev.phonerecall.data.AvailabilityModel
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalendarPipeline
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.RelevanceStore
import dev.phonerecall.hestia.HomeDevice
import dev.phonerecall.mind.MindNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** argo.sealed in no-backup storage, under its own Keystore key. Same shape as Odyssey's store. */
object ArgoStore {
    private const val FILE = "argo.sealed"
    private val _state = MutableStateFlow(ArgoData())
    val state: StateFlow<ArgoData> = _state.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    private val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.argo") }

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): ArgoData = synchronized(lock) {
        if (loaded) return _state.value
        val f = file(c)
        val d = if (f.exists()) runCatching { decode(sealer, f.readBytes()) }.getOrNull() ?: ArgoData() else ArgoData()
        _state.value = d
        loaded = true
        d
    }

    fun update(c: Context, change: (ArgoData) -> ArgoData): ArgoData = synchronized(lock) {
        val before = load(c)
        val next = change(before)
        if (next == before) return before
        _state.value = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(encode(sealer, next))
        tmp.renameTo(f)
        next
    }

    fun encode(s: Sealer, d: ArgoData): ByteArray = s.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(d))
    fun decode(s: Sealer, bytes: ByteArray): ArgoData? = s.openBytes(bytes)?.let { dev.phonerecall.mind.stateJson.readValue(it, ArgoData::class.java) }
}

/** Hestia's side: the lights-off plan for leaving, one step per light that is on. A proposal; nothing switches here. */
object ArgoAway {
    fun steps(lightsOn: List<HomeDevice>): List<RawStep> =
        lightsOn.filter { it.controllable }.take(8).map { RawStep(ProposalKind.HOME_CONTROL, mapOf("device_id" to it.id, "action" to "off")) }

    fun title(t: Trip) = "Off to ${t.place}: lights off at home?"
}

/** The first run after the redesign: Iris's old bookings back into evidence, his words marked, then trips rebuilt. */
object Migrate {
    fun run(d: ArgoData, now: Long, zone: ZoneId): ArgoData {
        if (d.schema >= 2) return d
        val trips = d.trips.map { t ->
            if (t.open && t.saidStart == null && t.sources.any { it in Evidence.WORDS } && t.startDate != null) t.copy(saidStart = t.startDate, saidEnd = t.endDate) else t
        }
        val ev = (d.evidence + Evidence.migrate(d.trips)).distinctBy { it.id }
        return Rebuild.run(d.copy(trips = trips, evidence = ev, schema = 2), now, zone)
    }
}

/**
 * Argo for the process: capture, the phase clock, the Live Update, the destination's weather, and the
 * hooks into Availability, Odyssey, Moneta, Janus and Hestia. Everything runs on one background thread
 * at a time and only on the phone; the only network calls are Open-Meteo (through Aeolia's door) and
 * Lemnos (Iris's trip.info, and a lookup request to Iris when he taps "Ask Iris").
 */
object Argo {
    private const val WORK = "zeus-argo"
    private const val EDGE = "zeus-argo-edge"
    private val lock = Any()

    fun start(c: Context) {
        val app = c.applicationContext
        val zone = { ZoneId.systemDefault() }
        AvailabilityModel.travel = dev.phonerecall.data.TravelDays { date, z -> Travel.awayOn(ArgoStore.state.value.trips, date, z) }
        dev.phonerecall.moneta.MonetaHooks.trips = dev.phonerecall.moneta.TripLink { at -> ArgoMoney.tripAt(ArgoStore.state.value.trips, at, zone()) }
        dev.phonerecall.mind.AskHub.on("argo") { p, r -> answered(app, p, r) }
        thread(name = "argo-start") {
            ArgoStore.load(app)
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ArgoWorker>(15, TimeUnit.MINUTES).build(),
            )
            runCatching { tick(app) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        }
    }

    /** Olympus and the widgets: the trip card from what is stored (no IO beyond the first load). */
    fun home(c: Context, now: Long, zone: ZoneId = ZoneId.systemDefault()): TripHome? =
        ArgoCards.home(ArgoStore.load(c), now, zone, ArgoLinks.alarms)

    /** Runs whatever is due: capture, the phase edges, the notice, weather, hooks. Blocking; call off the main thread. */
    fun tick(c: Context, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val before = ArgoStore.load(c)
        runCatching { dev.phonerecall.mind.AskHub.deliver(c) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        runCatching { sweep(c, now) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        if (before.schema < 2) ArgoStore.update(c) { Migrate.run(it, now, zone) }
        val (items, events) = captureCalendar(c, now, zone)
        runCatching { assemble(c, now, zone) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        investigate(c, Hints.fromCalendar(items, ArgoStore.load(c).trips, now, zone) + chargeHints(c, now, zone), now, zone)
        dayAfter(c, events, now, zone)
        closeFinished(c, now, zone)
        val d = ArgoStore.load(c)
        val cur = Travel.current(d.trips, now, zone)
        refreshWeather(c, cur?.first, cur?.second, now, zone)
        notice(c, cur, now, zone)
        if (cur?.second?.phase == Phase.TRAVEL && cur.second.leg?.returning == false && !cur.first.awayAsked) away(c, cur.first)
        val dz = cur?.takeIf { it.second.phase == Phase.TRAVEL || it.second.phase == Phase.AWAY }?.first?.destZone(zone)
        ArgoLinks.clocks?.destination(dz?.takeIf { it.rules.getOffset(java.time.Instant.ofEpochMilli(now)) != zone.rules.getOffset(java.time.Instant.ofEpochMilli(now)) }, cur?.first?.place)
        scheduleEdge(c, ArgoStore.load(c), now, zone)
        val after = ArgoStore.load(c)
        if (after != before) changed(c, after, now, zone)
    }

    private fun changed(c: Context, d: ArgoData, now: Long, zone: ZoneId) {
        runCatching { dev.phonerecall.widgets.WidgetBus.refreshFromDisk(c, force = true) }
        // The mind keeps /plans/ (words only) up to date on its next memory sync.
        runCatching { dev.phonerecall.brain.BrainCore.get(c).changed() }
    }

    // ---- capture ----

    private fun captureCalendar(c: Context, now: Long, zone: ZoneId): Pair<List<TripItem>, List<dev.phonerecall.data.CalItem>> {
        val src = CalendarSource(c)
        if (!src.allowed()) return emptyList<TripItem>() to emptyList()
        val from = now - 14 * 86_400_000L
        val to = now + 180 * 86_400_000L
        val cals = src.calendars().associateBy { it.id }
        val events = CalendarPipeline.curate(CalendarSnapshot(true, src.readRange(from, to, cals), true), cals, RelevanceStore(c).load(), BirthdaySource(c).read()).snapshot.events
        val kinds = cals.mapValues { it.value.kind }
        val items = CalendarCapture.items(events, zone, kinds)
        // Every calendar of his is evidence, read fresh; calendar evidence from before the window stays for trips under way.
        val fresh = Evidence.calendar(events, zone, kinds)
        ArgoStore.update(c) { d ->
            val kept = d.evidence.filter { e -> !e.feed.startsWith("cal:") || (Evidence.time(e, zone) ?: 0) < from }
            Rebuild.run(d.copy(evidence = kept + fresh), now, zone).let { it.copy(trips = keepStamps(d.trips, it.trips)) }
        }
        return items to events
    }

    /** A recapture that changed nothing keeps the old updatedAt, so the store is not rewritten every tick. */
    private fun keepStamps(old: List<Trip>, new: List<Trip>): List<Trip> = new.map { t ->
        old.firstOrNull { it.id == t.id }?.let { o -> if (o.copy(updatedAt = 0) == t.copy(updatedAt = 0)) o else t } ?: t
    }

    /** Moneta's booking-like charges, merchant and time only (amounts never leave Moneta for this). */
    private fun chargeHints(c: Context, now: Long, zone: ZoneId): List<Inquiry> {
        val s = dev.phonerecall.moneta.MonetaCore.get(c).state.value
        if (!s.loaded) return emptyList()
        return Hints.fromCharges(s.txns.filter { it.mergedInto == null && it.kind == dev.phonerecall.moneta.TxnKind.SPEND }.map { it.at to it.merchant }, now, zone)
    }

    /** Booking charges with amounts, for the trip page's committed total (shown only behind Moneta's fingerprint). */
    fun charges(c: Context): List<Triple<Long, String, Long>> = dev.phonerecall.moneta.MonetaCore.get(c).state.value.let { s ->
        if (!s.loaded) emptyList() else s.txns.filter { it.mergedInto == null && it.kind == dev.phonerecall.moneta.TxnKind.SPEND && ArgoMoney.isBooking(it.merchant) }.map { Triple(it.at, it.merchant, it.cents) }
    }

    /** From the notification listener: only a city and a date are kept. Cheap; the investigation runs on its own thread. */
    fun notification(c: Context, n: dev.phonerecall.brain.Incoming) {
        if (n.pkg == c.packageName) return
        val h = Hints.fromNotification("${n.title} ${n.text}", n.at, ZoneId.systemDefault()) ?: return
        thread(name = "argo-hint") { runCatching { synchronized(lock) { investigate(c, listOf(h), System.currentTimeMillis(), ZoneId.systemDefault()) } } }
    }

    // ---- investigations: Athena looks into hints herself (mind/Investigate.kt) ----

    private const val HANDED_FOR = 6 * 3_600_000L

    /**
     * New hints become investigations. With the mind set up, Athena's circle (Perseus) runs it inside the budget caps
     * and reports with record_trip_finding; without it, or if the circle is quiet for six hours, the phone asks Iris
     * itself and concludes from her answer and Alexandria.
     */
    private fun investigate(c: Context, hints: List<Inquiry>, now: Long, zone: ZoneId) {
        val fresh = Hints.fresh(hints, ArgoStore.load(c), zone)
        if (fresh.isNotEmpty()) ArgoStore.update(c) { it.copy(inquiries = (it.inquiries + fresh).takeLast(80)) }
        val core = dev.phonerecall.brain.BrainCore.get(c)
        for (q in ArgoStore.load(c).inquiries) {
            when {
                q.status == InqStatus.OPEN && core.vault.has() -> {
                    val r = core.mindHost.handOff("Look into a possible trip" + (q.place?.let { " to $it" } ?: ""), Inquiries.brief(q), "Perseus")
                    if (r.startsWith("Started")) setInq(c, q.id) { it.copy(status = InqStatus.HANDED, updatedAt = now) } else askIris(c, q, now)
                    core.audit.log("Argo", "investigating", q.hint)
                }
                q.status == InqStatus.OPEN -> { askIris(c, q, now); core.audit.log("Argo", "investigating", q.hint) }
                q.status == InqStatus.HANDED && now - q.updatedAt > HANDED_FOR -> askIris(c, q, now)
            }
        }
    }

    private fun setInq(c: Context, id: String, f: (Inquiry) -> Inquiry) = ArgoStore.update(c) { d -> d.copy(inquiries = d.inquiries.map { if (it.id == id) f(it) else it }) }

    private fun askIris(c: Context, q: Inquiry, now: Long) {
        if (dev.phonerecall.mind.AskHub.held(c, "iris") != null) return    // Metron: asked on a later tick
        val p = if (dev.phonerecall.lemnos.DeviceKeys.paired(c)) dev.phonerecall.mind.AskHub.get(c).ask("iris", "trip", Inquiries.question(q), owner = "argo:${q.id}").getOrNull() else null
        if (p != null) setInq(c, q.id) { it.copy(status = InqStatus.ASKED, askId = p.id, updatedAt = now) }
        else conclude(c, q, null, now)          // no Lemnos: conclude from memory alone
    }

    /** Once a week, Iris is asked for every trip in his mail (Sweep): bookings made before Argo never reached it. */
    private fun sweep(c: Context, now: Long) {
        if (!Sweep.due(ArgoStore.load(c), now) || !dev.phonerecall.lemnos.DeviceKeys.paired(c) || dev.phonerecall.mind.AskHub.held(c, "iris") != null) return
        dev.phonerecall.mind.AskHub.get(c).ask("iris", "trips", Sweep.question(), owner = Sweep.OWNER).onSuccess { ArgoStore.update(c) { d -> d.copy(sweptAt = now) } }
    }

    /** Iris answered (or 30 minutes passed): search Alexandria, then conclude. */
    private fun answered(c: Context, p: dev.phonerecall.mind.PendingAsk, r: dev.phonerecall.mind.AskReply?) {
        if (p.owner == Sweep.OWNER) {
            synchronized(lock) { ArgoStore.update(c) { d -> Sweep.apply(d, r, System.currentTimeMillis(), ZoneId.systemDefault()) } }
            return
        }
        val id = p.owner.removePrefix("argo:")
        val q = ArgoStore.load(c).inquiries.firstOrNull { it.id == id && it.status == InqStatus.ASKED } ?: return
        synchronized(lock) { conclude(c, q, r, System.currentTimeMillis()) }
    }

    private fun conclude(c: Context, q: Inquiry, r: dev.phonerecall.mind.AskReply?, now: Long) {
        val zone = ZoneId.systemDefault()
        val query = listOfNotNull("trip", q.place, q.start ?: q.month).joinToString(" ")
        val memory = runCatching { memoryLines(dev.phonerecall.brain.BrainCore.get(c).mindHost.searchMemory(query, 6)) }.getOrDefault(emptyList())
        val f = Inquiries.conclude(q, r, memory, Trip.day(now, zone))
        ArgoStore.update(c) { d -> Rebuild.run(Inquiries.apply(d, q, f, now, zone), now, zone) }
        dev.phonerecall.brain.BrainCore.get(c).audit.log("Argo", "concluded", "${f.place ?: "a trip"}: ${f.confidence.name.lowercase()}")
    }

    /** Alexandria's cited snippets as plain lines. */
    private fun memoryLines(json: String): List<String> = runCatching {
        val o = JSONObject(json)
        val a = o.optJSONArray("snippets") ?: o.optJSONArray("results") ?: return emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("text").ifEmpty { it.optString("snippet") } }?.takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())

    /** Athena's circle concluding (record_trip_finding). Returns one line for the model. */
    fun finding(c: Context, input: Map<String, Any?>): String = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val conf = when (input["confidence"]?.toString()?.lowercase()) { "high" -> dev.phonerecall.mind.Confidence.HIGH; "medium" -> dev.phonerecall.mind.Confidence.MEDIUM; else -> dev.phonerecall.mind.Confidence.LOW }
        val place = input["place"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(Cities::canonical)
        val w = WhenParse.parse(input["when"]?.toString(), Trip.day(now, zone))
        val evidence = input["evidence"]?.toString()?.take(300).orEmpty()
        val d = ArgoStore.load(c)
        val q = d.inquiries.firstOrNull { it.id == input["investigation_id"]?.toString() }
            ?: Inquiry("q" + java.lang.Long.toString(now, 36), "mind:$now", "athena", place, hint = evidence.ifEmpty { "Athena noticed it" }, createdAt = now, updatedAt = now).also { n -> ArgoStore.update(c) { it.copy(inquiries = it.inquiries + n) } }
        val f = Finding(place ?: q.place, w.start ?: q.start?.let(java.time.LocalDate::parse), w.end ?: q.end?.let(java.time.LocalDate::parse), if (w.start != null) null else w.month, conf, listOf(q.hint, "Athena: $evidence").distinct())
        val next = ArgoStore.update(c) { Rebuild.run(Inquiries.apply(it, q, f, now, zone), now, zone) }
        dev.phonerecall.brain.BrainCore.get(c).audit.log("Athena", "trip finding", "${f.place ?: "a trip"}: ${conf.name.lowercase()}")
        when (next.inquiries.first { it.id == q.id }.status) {
            InqStatus.DONE -> "Recorded in Argo as a trip. Prep shows on his home screen; nothing was booked."
            InqStatus.QUESTION -> "Not sure enough to record it, so he gets one question card. Don't ask him again about it."
            else -> "Closed without a card (already asked, or nothing to go on)."
        }
    }

    // ---- the day after ----

    /** Once the real return is known: the first commitment back, and one proposal for a light recovery block. */
    private fun dayAfter(c: Context, events: List<dev.phonerecall.data.CalItem>, now: Long, zone: ZoneId) {
        for (t in ArgoStore.load(c).trips.filter { it.open }) {
            val back = DayAfter.returnAt(t) ?: continue
            if (back - now > 7 * 86_400_000L) continue
            val first = DayAfter.first(events, back)
            val words = first?.let { DayAfter.words(it, zone) }
            if (words != t.firstBack) ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == t.id) it.copy(firstBack = words) else it }) }
            if (t.recoveryAsked || now > back) continue
            val block = DayAfter.recovery(back, first, zone) ?: continue
            val step = RawStep(ProposalKind.ADD_CALENDAR_EVENT, DayAfter.eventArgs(block, zone))
            runCatching { dev.phonerecall.brain.BrainCore.get(c).fates.propose(listOf(step), "Back from ${t.place} late: keep the next morning light?", null, proposer = "Argo") }
            ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == t.id) it.copy(recoveryAsked = true) else it }) }
        }
    }

    /** Iris's trip.info messages, from Lemnos. Applied once each, then acked. Blocking. */
    fun syncIris(c: Context) {
        if (!dev.phonerecall.lemnos.DeviceKeys.paired(c)) return
        val envs = dev.phonerecall.lemnos.Gateway.argoInfo()
        if (envs.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val ids = envs.mapNotNull { IrisInfo.id(it) }
        ArgoStore.update(c) { d ->
            val fresh = envs.filter { e -> IrisInfo.id(e)?.let { it !in d.seenInfo } == true }
            Rebuild.run(d.copy(evidence = Evidence.iris(d.evidence, fresh.mapNotNull { IrisInfo.parse(it, zone) }), seenInfo = (d.seenInfo + ids).distinct().takeLast(500)), now, zone)
        }
        dev.phonerecall.lemnos.Gateway.argoAck(ids)
        dev.phonerecall.brain.BrainCore.get(c).audit.log(dev.phonerecall.ui.Workers.IRIS, "trip info", "${ids.size} for Argo")
        tick(c, now)
    }

    // ---- Delphi (tier 0 read, tier 1 own-space write) ----

    fun note(c: Context, place: String, whenText: String?, details: String?, who: String = "Athena", budgetCents: Long? = null): Trip {
        val zone = ZoneId.systemDefault()
        var touched: Trip? = null
        val now = System.currentTimeMillis()
        ArgoStore.update(c) { d -> Trips.note(d.trips, place, whenText, details, now, zone, budgetCents).let { (all, t) -> touched = t; Rebuild.run(d.copy(trips = all), now, zone) } }
        val t = ArgoStore.load(c).trips.firstOrNull { it.id == touched!!.id } ?: touched!!
        dev.phonerecall.brain.BrainCore.get(c).audit.log(who, "noted trip", "${t.place}, ${Travel.dates(t, zone)}")
        thread(name = "argo-note") { runCatching { tick(c) } }
        return t
    }

    fun read(c: Context): List<String> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        return ArgoStore.load(c).trips.filter { it.open || it.status == TripStatus.ARCHIVED }.sortedBy { it.window(zone)?.first ?: Long.MAX_VALUE }.take(8).map { TripWords.line(it, now, zone) }
    }

    /** Maps is another app: an intent with the place's words, nothing sent from Zeus. */
    fun mapsIntent(query: String): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + java.net.URLEncoder.encode(query, "UTF-8") + "&travelmode=transit"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The mind's /plans/trips.md (words only). */
    fun plans(c: Context): String = TripWords.plans(ArgoStore.load(c).trips, System.currentTimeMillis(), ZoneId.systemDefault())

    // ---- his taps (card actions, the Argo screen) ----

    /**
     * "ask:<trip>:<GAP>", "q:<inquiry>:yes|no", "archive:<trip>", "dismiss:<trip>", and his trip edits: "merge:<trip>:<other>",
     * "layover:<trip>:<city>", "split:<trip>:<city>", "unlock:<trip>", "keep:<conflict>". Edits lock the trip and teach a rule. Blocking.
     */
    fun act(c: Context, payload: String) {
        val parts = payload.split(':', limit = 3)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        when (parts[0]) {
            "ask" -> if (parts.size == 3) askIris(c, parts[1], parts[2])
            "q" -> if (parts.size == 3) ArgoStore.update(c) { d -> Rebuild.run(Inquiries.answer(d, parts[1], parts[2] == "yes", now, zone), now, zone) }
            "merge" -> if (parts.size == 3) edited(c, ArgoStore.update(c) { Reconcile.merge(it, parts[1], parts[2], now, zone) }, "merged trips")
            "layover" -> if (parts.size == 3) edited(c, ArgoStore.update(c) { Reconcile.layover(it, parts[1], parts[2], now, zone) }, "${parts[2]} is a layover")
            "split" -> if (parts.size == 3) edited(c, ArgoStore.update(c) { Reconcile.split(it, parts[1], parts[2], now, zone) }, "split a trip at ${parts[2]}")
            "unlock" -> ArgoStore.update(c) { d -> Rebuild.run(d.copy(trips = d.trips.map { if (it.id == parts.getOrNull(1)) it.copy(locked = false) else it }), now, zone) }
            "undo" -> ArgoStore.update(c) { d -> Rebuild.run(Reconcile.undo(d), now, zone) }.also { dev.phonerecall.brain.BrainCore.get(c).audit.log("The owner", "undid Athena's trip change", "") }
            "change" -> ArgoStore.update(c) { d -> d.copy(change = d.change?.copy(ack = true)) }
            "keep" -> payload.removePrefix("keep:").let { k -> ArgoStore.update(c) { d -> d.copy(kept = (d.kept + k).distinct().takeLast(100), conflicts = d.conflicts.filter { it.key != k }) } }
            "archive" -> ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == parts.getOrNull(1)) it.copy(status = TripStatus.ARCHIVED, recap = it.recap ?: TripWords.recap(it, ZoneId.systemDefault())) else it }) }
            "dismiss" -> ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == parts.getOrNull(1)) it.copy(status = TripStatus.DISMISSED) else it }) }
        }
        runCatching { tick(c) }
    }

    /**
     * His tap on "Ask Iris": a lookup job to Iris with no approval token, so she reads and suggests only.
     * She never books, holds or pays; the request says so, and the gateway marks it SUGGESTION ONLY.
     */
    private fun askIris(c: Context, tripId: String, gapName: String) {
        val zone = ZoneId.systemDefault()
        val t = ArgoStore.load(c).trips.firstOrNull { it.id == tripId } ?: return
        val gap = runCatching { Gap.valueOf(gapName) }.getOrNull()?.takeIf { it.ask != null } ?: return
        val req = dev.phonerecall.lemnos.ApprovalRequest(
            jobId = "argo-${t.id}-${gap.name.lowercase()}", kind = "argo.lookup", executor = "iris",
            payload = mapOf("trip" to t.place, "dates" to Travel.dates(t, zone), "need" to gap.ask, "rule" to "Look up and suggest only. Do not book, hold, pay or sign up for anything."),
        )
        val title = "Look up ${gap.ask} for ${t.place} (${Travel.dates(t, zone)})"
        runCatching { dev.phonerecall.lemnos.Gateway.submitJob(dev.phonerecall.lemnos.JobPolicy.outgoing(req, title, dev.phonerecall.lemnos.Origin.ATHENA, null)) }
            .onSuccess { ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == t.id) it.copy(askedIris = (it.askedIris + gap.name).distinct()) else it }) } }
        dev.phonerecall.brain.BrainCore.get(c).audit.log("Argo", "asked Iris to look", title)
    }

    // ---- Athena assembles (Reconcile.kt) ----

    /**
     * When the evidence changed (at most once per 30 minutes, batched): all of it goes to Athena in one note and she
     * answers with propose_trips. Without her mind on this phone, the offline draft stands.
     */
    private fun assemble(c: Context, now: Long, zone: ZoneId) {
        val d = ArgoStore.load(c)
        val v = Rebuild.view(d, zone)
        val flags = Sanity.of(v.drafts, v.disagree, d.rules, d.sweepAnswered > 0, Trip.day(now, zone))
        val (text, refs, key) = Reconcile.note(d, flags, zone)
        if (!Reconcile.due(d, key, now)) return
        val core = dev.phonerecall.brain.BrainCore.get(c)
        if (!core.vault.has()) return
        core.mindHost.offer(MindNote(now, text, key = Reconcile.NOTE_KEY))
        ArgoStore.update(c) { it.copy(asked = ReconcileAsk(key, now, refs, Reconcile.raw(it).flatMap { e -> e.ids }.distinct())) }
        core.audit.log("Argo", "asked Athena to assemble trips", "${refs.size} pieces of evidence")
        dev.phonerecall.clio.Clio.info("argo", "assemble asked", "${refs.size} pieces")
    }

    /** Athena's propose_trips: applied on its own when she is sure and his trips are untouched, else one card. */
    fun proposeTrips(c: Context, input: Map<String, Any?>): String = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val got = Reconcile.receive(ArgoStore.load(c), input, now, zone)
        if (got.outcome == Reconcile.Outcome.REFUSED) return got.text
        val next = ArgoStore.update(c) { got.d }
        val r0 = got.r!!
        val title = Reconcile.title(r0.trips)
        val core = dev.phonerecall.brain.BrainCore.get(c)
        if (got.outcome == Reconcile.Outcome.APPLIED) {
            facts(c, next)
            core.audit.log("Athena", "updated trips", title)
            dev.phonerecall.clio.Clio.info("argo", "trips updated by Athena", title)
            return got.text
        }
        val args = mapOf("plan" to r0.id, "trips" to title.take(240)) + (r0.why.takeIf { it.isNotEmpty() }?.let { mapOf("why" to it) } ?: emptyMap())
        return when (val r = core.fates.propose(listOf(RawStep(dev.phonerecall.brain.ProposalKind.SET_TRIPS, args)), title.take(80), null, proposer = "Athena")) {
            is dev.phonerecall.brain.ProposeResult.Created -> got.text
            is dev.phonerecall.brain.ProposeResult.Invalid -> "Not proposed: ${r.reason}"
        }
    }

    /** His confirm (Fates): her list checked again against the evidence as it is now, then locked as his. */
    fun applyPlan(c: Context, p: Map<String, String>): String = synchronized(lock) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val d = ArgoStore.load(c)
        val plan = d.plan?.takeIf { it.id == p["plan"] } ?: error("That trip list is out of date; Athena will look again")
        val again = Reconcile.Answer(plan.trips, plan.ignored, plan.repairs, plan.why)
        Reconcile.check(again, d, emptyMap(), zone, now).second?.let { error("The bookings changed since ($it); Athena will look again") }
        val next = ArgoStore.update(c) { Reconcile.apply(it, plan, now, zone, byHim = true) }
        facts(c, next)
        dev.phonerecall.brain.BrainCore.get(c).audit.log("Argo", "trips set", Reconcile.title(plan.trips))
        thread(name = "argo-plan") { runCatching { tick(c) } }
        return "Trips set: " + Reconcile.title(plan.trips).removePrefix("Trips: ")
    }

    private fun edited(c: Context, d: ArgoData, what: String) {
        facts(c, d)
        dev.phonerecall.brain.BrainCore.get(c).audit.log("The owner", "trip edit", what)
    }

    /** Oneiroi keeps his trips and what his edits taught, in words: "The owner's trips: ...", "Charlotte was only a layover ...". */
    private fun facts(c: Context, d: ArgoData) = runCatching {
        val o = dev.phonerecall.oneiroi.Oneiroi.get(c)
        Reconcile.summary(d.trips, ZoneId.systemDefault())?.let { o.facts.put("trips", it, dev.phonerecall.oneiroi.FactStatus.CONFIRMED, "argo", key = "trips:summary") }
        for (r in d.rules.takeLast(8)) if (r.why.isNotBlank()) o.facts.put("trips", r.why, dev.phonerecall.oneiroi.FactStatus.CONFIRMED, "argo", key = "trips:${r.kind.name.lowercase()}:${r.place.lowercase()}")
        o.reload()
    }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }

    // ---- phases ----

    /** A trip whose time is over is archived with a recap in words; Kairos and the mind hear one line. */
    private fun closeFinished(c: Context, now: Long, zone: ZoneId) {
        val done = ArgoStore.load(c).trips.filter { it.open && Travel.moment(it, now, zone).phase == Phase.AFTER }
        if (done.isEmpty()) return
        ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { t -> if (done.any { it.id == t.id }) t.copy(status = TripStatus.ARCHIVED, recap = TripWords.recap(t, zone), updatedAt = now) else t }) }
        for (t in done) {
            val recap = TripWords.recap(t, zone)
            ArgoLinks.moments?.moment(now, "Back from ${t.place}", recap)
            runCatching { dev.phonerecall.brain.BrainCore.get(c).mindHost.offer(MindNote(now, "Trip over (Argo): $recap", key = "argo-${t.id}")) }
        }
    }

    private fun notice(c: Context, cur: Pair<Trip, Moment>?, now: Long, zone: ZoneId) {
        val last = ArgoStore.load(c).noticeKey
        val leg = cur?.second?.leg?.takeIf { cur.second.phase == Phase.TRAVEL }
        if (leg == null) {
            if (last != null) { ArgoNotice.clear(c); ArgoStore.update(c) { it.copy(noticeKey = null) } }
            return
        }
        val live = LiveWords.of(cur.first, leg, now, zone)
        ArgoNotice.show(c, live, last)
        if (live.key != last) ArgoStore.update(c) { it.copy(noticeKey = live.key) }
    }

    private fun away(c: Context, t: Trip) {
        ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == t.id) it.copy(awayAsked = true) else it }) }
        val steps = ArgoAway.steps(dev.phonerecall.hestia.HestiaCore.get(c).snapshot.lightsOn)
        if (steps.isEmpty()) return
        runCatching { dev.phonerecall.brain.BrainCore.get(c).fates.propose(steps, ArgoAway.title(t), null, proposer = "Argo") }
    }

    /** The next moment the phase or the notice's stage changes, as one inexact wake-up. */
    private fun scheduleEdge(c: Context, d: ArgoData, now: Long, zone: ZoneId) {
        val edges = d.trips.filter { it.open }.flatMap { t ->
            t.legs.filter { it.departAt > 0 }.flatMap { l -> listOfNotNull(Travel.window(l)?.first, Travel.leaveBy(l), l.departAt, l.arriveAt.takeIf { it > 0 }, Travel.window(l)?.last) } +
                listOfNotNull(t.window(zone)?.first, t.window(zone)?.second)
        }.filter { it > now + 30_000L }
        val next = edges.minOrNull() ?: return
        if (next - now > 6 * 3_600_000L) return
        WorkManager.getInstance(c).enqueueUniqueWork(
            EDGE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ArgoWorker>().setInitialDelay(next - now, TimeUnit.MILLISECONDS).build(),
        )
    }

    // ---- the destination's weather (Open-Meteo, through Aeolia's allowlisted door) ----

    private fun refreshWeather(c: Context, t: Trip?, m: Moment?, now: Long, zone: ZoneId) {
        if (t == null || m == null || m.phase == Phase.IDEA || t.place == Cities.HOME) return
        val start = t.window(zone)?.first ?: return
        if (m.phase == Phase.BEFORE && start - now > 10 * 86_400_000L) return
        val have = ArgoStore.load(c).weather[t.id]
        if (have != null && now - have.at < 3 * 3_600_000L) return
        runCatching {
            var trip = t
            if (trip.lat == null || trip.lng == null) {
                val (lat, lng, tz) = geocode(trip.place) ?: return
                trip = trip.copy(lat = lat, lng = lng, zone = tz ?: trip.zone)
                ArgoStore.update(c) { d -> d.copy(trips = d.trips.map { if (it.id == t.id) it.copy(lat = lat, lng = lng, zone = tz ?: it.zone) else it }) }
            }
            val fc = WeatherParse.forecast(WeatherNet.text(WeatherNet.forecastUrl(trip.lat!!, trip.lng!!))) ?: return
            val w = DestWeather(now, fc.tempC, fc.code, fc.days.map { DestDay(it.date.toString(), it.highC, it.lowC, it.code, it.rainChance) })
            ArgoStore.update(c) { d -> d.copy(weather = d.weather.filterKeys { k -> d.trips.any { it.id == k && it.open } } + (t.id to w)) }
        }
    }

    /** The city's centre and time zone from Open-Meteo's geocoder. Only the city name is sent. */
    private fun geocode(place: String): Triple<Double, Double, String?>? {
        val r = JSONObject(WeatherNet.text(WeatherNet.searchUrl(place))).optJSONArray("results") ?: return null
        val o = (0 until r.length()).map { r.getJSONObject(it) }.firstOrNull { it.optString("country_code") == "US" } ?: r.optJSONObject(0) ?: return null
        return Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("timezone").takeIf { it.isNotEmpty() })
    }
}

class ArgoWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        runCatching { Argo.tick(applicationContext) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        return Result.success()
    }
}
