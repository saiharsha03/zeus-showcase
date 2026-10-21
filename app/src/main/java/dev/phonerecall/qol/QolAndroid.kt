package dev.phonerecall.qol

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import dev.phonerecall.R
import dev.phonerecall.brain.AndroidLocalState
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Sealer
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import dev.phonerecall.odyssey.DriveService
import dev.phonerecall.odyssey.Geo
import dev.phonerecall.odyssey.Odyssey
import dev.phonerecall.odyssey.OdysseyData
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The small touches on the phone: the sealed state (where he parked), the signals for Kairos's rules, the
 * hooks from the car's Bluetooth and the drive detector, and the parked chip. Everything is logged to Clio
 * under "qol" (names and kinds only, never a place's coordinates).
 */
object QolCore {
    const val AREA = "qol"
    private const val FILE = "qol.sealed"
    private const val CHANNEL = "qol"
    private const val CHIP_ID = 61_500
    private val lock = Any()
    @Volatile private var state: QolState? = null
    private val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.qol") }

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): QolState = synchronized(lock) {
        state?.let { return it }
        val f = file(c)
        val s = if (f.exists()) runCatching { sealer.openBytes(f.readBytes())?.let { dev.phonerecall.mind.stateJson.readValue(it, QolState::class.java) } }.getOrNull() else null
        (s ?: QolState()).also { state = it }
    }

    fun update(c: Context, change: (QolState) -> QolState): QolState = synchronized(lock) {
        val next = change(load(c))
        state = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(sealer.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(next)))
        tmp.renameTo(f)
        next
    }

    private fun on(c: Context, k: MomentKind) = k !in MomentsCore.load(c).never

    /** What the rules read, from the phone's stores. Called with the board, off the main thread. */
    fun signals(c: Context, now: ZonedDateTime, d: OdysseyData): QolSignals {
        val ms = now.toInstant().toEpochMilli()
        val st = load(c)
        val stay = d.stay?.takeIf { !d.driving }?.let { s -> s.poiName ?: s.poi ?: d.places.firstOrNull { p -> Geo.meters(p.lat, p.lng, s.lat, s.lng) < 150 }?.name }
        return QolSignals(
            busy = Quiet.busy(c),
            car = st.car?.takeIf { ms - it.at in 0..QolRules.CAR_KEEP_MS }?.let { CarSpot(it.at, it.near, it.bigLot, QolRules.walkTo(it.lat, it.lng)) },
            leave = QolRules.nextLeave(events(c, ms), d.checks, ms),
            delivery = st.delivery?.takeIf { ms - it.at in 0..Deliveries.KEEP_MS },
            renewals = renewals(c, now),
            late = late(c, d, st, ms, now),
            street = StreetParking.now(c, now),
            streetOffer = st.streetOffer?.takeIf { st.street == null },
            offAlarm = if (now.hour in 19..22) offAlarm(c, now) else null,
            spoiler = spoiler(c, d, st, ms, now),
            bills = bills(c, st, now),
            refunds = refunds(c, st, ms),
            returns = st.returns,
            pickups = st.pickups,
            stayName = stay,
            steps = steps(c, d, now),
            family = family(c, st, ms),
            split = if (Opens.installed(c, Split.SPLITWISE)) Split.offer(dev.phonerecall.moneta.MonetaCore.get(c).state.value.txns, events(c, ms), ms, now.zone) else null,
            member = MemberCard.at(stay ?: runCatching { dev.phonerecall.places.PlaceStore(c).let { ps -> ps.current()?.let { id -> ps.all().firstOrNull { it.id == id }?.name } } }.getOrNull()) { Opens.installed(c, it) },
            clash = IndiaClock.clash(dev.phonerecall.moments.CallHabit.slot(MomentsCore.load(c).calls, ms, now.zone), events(c, ms), now),
            rides = d.home().let { h -> val home = h?.let { it.lat to it.lng }
                Rides(RideHome.uber(home, Opens.installed(c, "com.ubercab")), if (Opens.installed(c, "me.lyft.android")) RideHome.lyft(home) else null) },
            flight = runCatching { FlightKit.today(dev.phonerecall.argo.ArgoStore.load(c).trips, ms, now.zone) { Opens.installed(c, it) } }.getOrNull(),
        )
    }

    /** A late event or visit just ended, he is not driving, no drive since, and his car is not parked there. */
    private fun late(c: Context, d: OdysseyData, st: QolState, ms: Long, now: ZonedDateTime): LateOut? {
        if (d.driving) return null
        val s = d.stay
        val away = s != null && d.home()?.let { Geo.meters(it.lat, it.lng, s.lat, s.lng) > 300 } != false
        val out = RideHome.lateEvent(events(c, ms), ms, now.zone) ?: if (away) RideHome.lateVisit(s?.start, d.walkStart, ms, now.zone) else null
        out ?: return null
        if (d.drives.any { it.start >= out.endedAt }) return null
        val car = st.car
        val droveHere = car != null && (if (s != null) Geo.meters(car.lat, car.lng, s.lat, s.lng) < 800 else car.at >= out.endedAt - 6 * 3_600_000L)
        return out.takeIf { !droveHere }
    }

    /** The latest followed match that ended unseen in the last 12 hours; also which scores Olympia hides. */
    private fun spoiler(c: Context, d: OdysseyData, st: QolState, ms: Long, now: ZonedDateTime): SpoilerResult? {
        if (!on(c, MomentKind.SPOILER_SAFE)) { Spoilers.hiddenIds = emptySet(); return null }
        val s = dev.phonerecall.olympia.OlympiaStore.state.value
        val drives = d.drives.map { it.start..it.end }
        val meetings = events(c, ms).map { it.begin..it.end }
        val done = s.followed.filter { it.phase == dev.phonerecall.olympia.Phase.DONE }
            .map { it to it.start + dev.phonerecall.olympia.ResultCheck.typical(it) }
            .filter { (_, end) -> ms - end in 0..12 * 3_600_000L && Spoilers.unseen(end, now.zone, drives, meetings) }
        Spoilers.hiddenIds = done.map { it.first.id }.filter { it !in st.revealed }.toSet()
        val (m, end) = done.maxByOrNull { it.second } ?: return null
        return SpoilerResult(m.id, m.title, dev.phonerecall.olympia.OlympiaCards.resultLine(m, s), m.leagueName, end, m.id in st.revealed)
    }

    /** Office day (he arrived at work today), the afternoon, and Iaso says a quiet day on foot: the weather in words. */
    private fun steps(c: Context, d: OdysseyData, now: ZonedDateTime): StepsNudge? {
        if (now.hour !in 15..16) return null
        val office = d.arrivals.any { it.kind == dev.phonerecall.odyssey.Kind.WORK && java.time.Instant.ofEpochMilli(it.at).atZone(now.zone).toLocalDate() == now.toLocalDate() }
        if (!Steps.due(runCatching { dev.phonerecall.iaso.IasoGlance.quietOnFoot(now.toLocalDate()) }.getOrNull(), office, now.hour)) return null
        val w = dev.phonerecall.aeolia.WeatherStore.load(c).takeIf { it.hasData && it.tempC != null }
        return StepsNudge(w?.let { "${dev.phonerecall.aeolia.WeatherState.f(it.tempC!!)}°F, ${dev.phonerecall.aeolia.Sky.words(it.code)}" })
    }

    /** Last night's family-group messages as the morning card; Nano writes its line once, while Zeus is on screen. */
    private fun family(c: Context, st: QolState, ms: Long): FamilyDigest? {
        val notes = st.familyNight.filter { ms - it.at in 0..FamilyNight.KEEP_MS }
        if (notes.isEmpty()) return null
        if (st.familyLine == null && !nanoBusy && dev.phonerecall.brain.Nano.foreground()) {
            nanoBusy = true
            thread(name = "qol-family") {
                try {
                    val line = runCatching { kotlinx.coroutines.runBlocking { dev.phonerecall.brain.Nano.generate("qol-family", FamilyNight.prompt(notes), 120) } }.getOrNull()?.trim()?.take(240)
                    if (!line.isNullOrEmpty()) { update(c) { it.copy(familyLine = line) }; dev.phonerecall.brain.BrainCore.get(c).changed() }
                } finally { nanoBusy = false }
            }
        }
        return FamilyDigest(FamilyNight.summary(notes), st.familyLine)
    }

    @Volatile private var nanoBusy = false

    @Volatile private var offAt = 0L
    @Volatile private var offCache: OffAlarm? = null

    /** Tomorrow's alarm against tomorrow's day type and its morning (read at most every 30 minutes, evenings only). */
    private fun offAlarm(c: Context, now: ZonedDateTime): OffAlarm? {
        val ms = now.toInstant().toEpochMilli()
        if (ms - offAt < 30 * 60_000L) return offCache
        offAt = ms
        offCache = runCatching {
            val next = dev.phonerecall.brain.AlarmWatch.get(c).next()
            val day = now.toLocalDate().plusDays(1)
            val avail = dev.phonerecall.mind.BackgroundReads(c, dev.phonerecall.brain.BrainCore.get(c)).availability()[day]
            val from = day.atStartOfDay(now.zone).toInstant().toEpochMilli()
            SkipAlarm.check(next?.at, next?.app, avail?.type, avail?.reason, query(c, from, from + 12 * 3_600_000L, needPlace = false).size, now)
        }.onFailure { Clio.error(AREA, it) }.getOrNull()
        return offCache
    }

    @Volatile private var subsAt = 0L
    @Volatile private var subsCache: List<String> = emptyList()

    /** Moneta's subscriptions renewing tomorrow, worked out at most every 30 minutes (it scans the card history). */
    private fun renewals(c: Context, now: ZonedDateTime): List<String> {
        val ms = now.toInstant().toEpochMilli()
        if (ms - subsAt < 30 * 60_000L) return subsCache
        val core = dev.phonerecall.moneta.MonetaCore.get(c)
        val s = core.state.value
        if (!s.loaded) { core.load(); return subsCache }
        subsAt = ms
        subsCache = runCatching { Renewals.tomorrow(core.subscriptions(s), now.toLocalDate()) }.getOrDefault(emptyList())
        return subsCache
    }

    /** A notification (senses): a package delivered while Odyssey says he is out is kept for his way home. */
    fun notification(c: Context, n: dev.phonerecall.brain.Incoming) {
        delivered(c, n)
        if (on(c, MomentKind.FAMILY_DIGEST) && FamilyNight.hold(n.isGroup, n.conversation, ZonedDateTime.now())) {
            update(c) { s -> s.copy(familyNight = (s.familyNight.filter { n.at - it.at < FamilyNight.KEEP_MS } + FamilyNote(n.conversation.orEmpty(), n.sender, n.text.take(200), n.at)).takeLast(60), familyLine = null) }
        }
        LiveTrips.post(c, n)
        if (on(c, MomentKind.BILL_DUE)) Bills.parse(n.pkg, n.app, n.title, n.text, n.at, java.time.LocalDate.now())?.let { b ->
            update(c) { it.copy(bills = Bills.add(it.bills, b)) }
            Clio.info(AREA, "bill due pinned", b.bank)
        }
        if (on(c, MomentKind.REFUND_WATCH)) Refunds.parse(n.pkg, n.title, n.text, n.at)?.let { r ->
            update(c) { s -> s.copy(refunds = (s.refunds.filter { it.shop != r.shop || n.at - it.at > 86_400_000L } + r).takeLast(20)) }
            Clio.info(AREA, "refund promised", r.shop)
        }
        if (on(c, MomentKind.RETURN_BY)) Returns.parse(n.pkg, n.title, n.text, n.at, java.time.LocalDate.now())?.let { r ->
            val today = java.time.LocalDate.now().toString()
            update(c) { s -> s.copy(returns = (s.returns.filter { it.by >= today && !(it.shop == r.shop && it.by == r.by) } + r).takeLast(20)) }
            Clio.info(AREA, "return window noted", r.shop)
        }
        if (Gas.lowText(n.title + " " + n.text)) { update(c) { it.copy(lowFuelAt = n.at) }; Clio.info(AREA, "low fuel noticed", n.app) }
        if (on(c, MomentKind.PICKUP)) Pickups.parse(n.pkg, n.app, n.title, n.text, n.at)?.let { p ->
            update(c) { s -> s.copy(pickups = (s.pickups.filter { it.store != p.store && n.at - it.at < Pickups.KEEP_MS } + p).takeLast(10)) }
            Clio.info(AREA, "pickup ready", p.store)
        }
    }

    /** Refunds still not in Moneta (its refund rows by merchant); any that came in drop off. */
    private fun refunds(c: Context, st: QolState, ms: Long): List<Refund> {
        if (st.refunds.isEmpty()) return emptyList()
        val since = st.refunds.minOf { it.at } - 86_400_000L
        val got = dev.phonerecall.moneta.MonetaCore.get(c).state.value.txns.filter { it.kind == dev.phonerecall.moneta.TxnKind.REFUND && it.at >= since }.map { it.merchant to it.at }
        val open = Refunds.waiting(st.refunds, got, ms)
        if (open.size != st.refunds.size) { update(c) { it.copy(refunds = open) }; Clio.info(AREA, "refund cleared", "arrived or given up") }
        return open
    }

    /** Moneta's card payments since the oldest open bill: (bank, when). Bills it pays drop off. */
    private fun bills(c: Context, st: QolState, now: ZonedDateTime): List<Bill> {
        if (st.bills.isEmpty()) return emptyList()
        val since = st.bills.minOf { it.at }
        val pays = dev.phonerecall.moneta.MonetaCore.get(c).state.value.txns.filter { it.kind == dev.phonerecall.moneta.TxnKind.PAYMENT && it.at > since }.map { it.bank to it.at }
        val open = Bills.open(st.bills, pays, now.toLocalDate(), now.toInstant().toEpochMilli())
        if (open.size != st.bills.size) { update(c) { it.copy(bills = open) }; Clio.info(AREA, "bill cleared", "paid or past due") }
        return open
    }

    private fun delivered(c: Context, n: dev.phonerecall.brain.Incoming) {
        val carrier = Deliveries.carrier(n.pkg, n.app, n.title, n.text) ?: return
        if (!on(c, MomentKind.PACKAGE)) return
        if (dev.phonerecall.moments.Signals.atHome(Odyssey.state(c)) != false) return
        update(c) { it.copy(delivery = Delivery(carrier, n.at)) }
        Clio.info(AREA, "package delivered while out", carrier)
    }

    @Volatile private var eventsAt = 0L
    @Volatile private var eventsCache: List<CalEvent> = emptyList()

    /** His own calendars, timed events six hours either side. Read at most every 5 minutes. */
    fun events(c: Context, now: Long): List<CalEvent> {
        if (now - eventsAt < 5 * 60_000L) return eventsCache
        eventsAt = now
        eventsCache = query(c, now - 6 * 3_600_000L, now + 6 * 3_600_000L, needPlace = false)
        return eventsCache
    }

    /** Timed events on his own calendars between [from] and [to]; with [needPlace], only those with a location. */
    private fun query(c: Context, from: Long, to: Long, needPlace: Boolean): List<CalEvent> {
        if (!AndroidLocalState.granted(c, Manifest.permission.READ_CALENDAR)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val out = ArrayList<CalEvent>()
        runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                dev.phonerecall.data.MyAccounts.instancesWhere(c), null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(4) == 1) continue
                    val loc = cur.getString(3)?.trim().orEmpty()
                    if (needPlace && loc.isEmpty()) continue
                    out += CalEvent(cur.getString(0).orEmpty(), cur.getLong(1), cur.getLong(2), loc)
                }
            }
        }.onFailure { Clio.error(AREA, it) }
        return out
    }

    @Volatile private var lastShown: String? = null

    /** The card Kairos picked: one Clio line the first time each small touch shows. */
    fun shown(card: MomentCard?) {
        if (card == null || card.kind !in KINDS) return
        val id = "${card.kind.name}|${card.key}"
        if (id == lastShown) return
        lastShown = id
        Clio.info(AREA, "card", card.kind.label)
    }

    /** A tap on one of these cards, after Kairos recorded it. */
    fun tapped(c: Context, kind: MomentKind, key: String, option: String) {
        if (kind == MomentKind.MESSAGE_LATER) Later.snooze(c, key, option)
        if (kind == MomentKind.FAMILY_DIGEST) update(c) { it.copy(familyNight = emptyList(), familyLine = null) }
        if (kind == MomentKind.SPLIT && option == "split") Split.offer(dev.phonerecall.moneta.MonetaCore.get(c).state.value.txns, events(c, System.currentTimeMillis()), System.currentTimeMillis(), java.time.ZoneId.systemDefault())?.takeIf { it.ref == key }?.let { Split.copy(c, it) }
        if (kind == MomentKind.PICKUP && option == "done") update(c) { s -> s.copy(pickups = s.pickups.filter { "${it.store}:${it.at}" != key }) }
        if (kind == MomentKind.RETURN_BY && option == "done") update(c) { s -> s.copy(returns = s.returns.filter { "${it.shop}:${it.by}" != key }) }
        if (kind == MomentKind.REFUND_WATCH && option == "done") update(c) { s -> s.copy(refunds = s.refunds.filter { "${it.shop}:${it.at}" != key }) }
        if (kind == MomentKind.BILL_DUE && option == "paid") { update(c) { s -> s.copy(bills = s.bills.filter { "${it.bank}:${it.at}" != key }) }; dev.phonerecall.brain.BrainCore.get(c).changed() }
        if (kind == MomentKind.SPOILER_SAFE && option == "show") { update(c) { it.copy(revealed = (it.revealed + key).takeLast(20)) }; dev.phonerecall.brain.BrainCore.get(c).changed() }
        if (kind == MomentKind.CAR_SPOT && option.startsWith("m")) option.drop(1).toIntOrNull()?.let { Meter.start(c, it) }
        StreetParking.tapped(c, kind, option)
        if (kind == MomentKind.TRASH) { c.getSystemService(NotificationManager::class.java)?.cancel(61_560); Bins.arm(c) }
    }

    /** The Kairos kinds that live here. */
    val KINDS = setOf(MomentKind.CAR_SPOT, MomentKind.NAVIGATE, MomentKind.PACKAGE, MomentKind.RENEWAL, MomentKind.FLIGHT_KIT, MomentKind.RIDE_HOME, MomentKind.STREET_PARKING, MomentKind.STREET_SEED, MomentKind.SKIP_ALARM, MomentKind.CALL_CLASH, MomentKind.SPOILER_SAFE, MomentKind.BILL_DUE, MomentKind.REFUND_WATCH, MomentKind.RETURN_BY, MomentKind.PICKUP, MomentKind.MEMBER_CARD, MomentKind.STEPS, MomentKind.SPLIT, MomentKind.MESSAGE_LATER, MomentKind.FAMILY_DIGEST)

    // ---- the car ----

    /** The car's Bluetooth came or went (clio.CarLinkReceiver). */
    fun carLink(c: Context, connected: Boolean) {
        val app = c.applicationContext
        thread(name = "qol-car") {
            runCatching {
                if (connected) { update(app) { it.copy(carLink = System.currentTimeMillis(), carMoved = System.currentTimeMillis(), carHome = false) }; StreetParking.moved(app); droveOff(app, "car connected"); CarMusic.connected(app); Gas.driveStarted(app) }
                else parked(app, "car disconnected")
            }.onFailure { Clio.error(AREA, it) }
        }
    }

    /** Odyssey's drive detector: a drive ended or started. Blocking; Odyssey calls it from its own thread. */
    fun drive(c: Context, started: Boolean) = runCatching {
        val st = load(c)
        if (started) Gas.driveStarted(c)
        if (started) { if (st.carLink == 0L) { update(c) { it.copy(carMoved = System.currentTimeMillis(), carHome = false) }; StreetParking.moved(c); droveOff(c, "drive started") } }
        else if (QolRules.driveWasCar(st.carLink, System.currentTimeMillis())) parked(c, "drive ended")
    }.onFailure { Clio.error(AREA, it) }

    /** Saves where the car is: the drive's last GPS point when fresh, else one fix. Blocking. */
    @SuppressLint("MissingPermission")
    fun parked(c: Context, why: String) {
        if (!(on(c, MomentKind.CAR_SPOT) || load(c).street != null) || !AndroidLocalState.granted(c, Manifest.permission.ACCESS_FINE_LOCATION)) return
        val now = System.currentTimeMillis()
        val f = DriveService.lastFix?.takeIf { now - it.at < 3 * 60_000L }?.let { Triple(it.lat, it.lng, it.at) } ?: runCatching {
            Tasks.await(LocationServices.getFusedLocationProviderClient(c).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token), 20, TimeUnit.SECONDS)
        }.getOrNull()?.let { Triple(it.latitude, it.longitude, now) } ?: run { Clio.warn(AREA, "parked, no fix", why); return }
        val d = Odyssey.state(c)
        val near = listOfNotNull(d.home(), d.work()).any { Geo.meters(it.lat, it.lng, f.first, f.second) < 300 }
        // Street parking: where the car sits now, and that it moved (answers a reminder).
        val home = d.home()?.let { Geo.meters(it.lat, it.lng, f.first, f.second) < 300 } == true
        update(c) { it.copy(carHome = home, carMoved = now) }
        StreetParking.moved(c)
        if (!on(c, MomentKind.CAR_SPOT)) return
        if (!QolRules.keepPark(load(c).car, now, near)) { if (near) drop(c); return }
        val (poi, street) = Odyssey.geocode(c, f.first, f.second)
        val car = ParkedCar(now, f.first, f.second, poi ?: street, QolRules.bigLot(poi, street))
        update(c) { it.copy(car = car) }
        Clio.info(AREA, "parked", if (car.bigLot) "big lot, $why" else why)
        if (car.bigLot) chip(c, car)
        dev.phonerecall.brain.BrainCore.get(c).changed()
    }

    /** He is in the car again: the spot and its chip go. */
    fun droveOff(c: Context, why: String) {
        if (load(c).car == null) return
        drop(c)
        Clio.info(AREA, "drove off", why)
    }

    private fun drop(c: Context) {
        if (load(c).meterUntil > 0) Meter.stop(c)
        update(c) { it.copy(car = null) }
        c.getSystemService(NotificationManager::class.java)?.cancel(CHIP_ID)
        runCatching { dev.phonerecall.brain.BrainCore.get(c).changed() }
    }

    fun channel(c: Context): NotificationManager? {
        if (!AndroidLocalState.granted(c, Manifest.permission.POST_NOTIFICATIONS)) return null
        val nm = c.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Small touches", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Where you parked, and Undo for the car's music"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        return nm
    }

    /** For the few that should be heard (the meter's nudge): default importance. */
    fun alertChannel(c: Context): NotificationManager? {
        val nm = channel(c) ?: return null
        nm.createNotificationChannel(NotificationChannel("qol.alert", "Small touches that ring", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "The parking meter's 10-minute nudge" })
        return nm
    }

    /** The Now Bar chip "Parked 7:12" with Navigate to car, as a promoted ongoing notification (NowBar's extras). */
    private fun chip(c: Context, car: ParkedCar) {
        if (Quiet.onCall(c)) return
        val nm = channel(c) ?: return
        val now = ZonedDateTime.now()
        val walk = Opens.intent(c, QolRules.walkTo(car.lat, car.lng)) ?: return
        val nav = PendingIntent.getActivity(c, CHIP_ID, walk, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hide = PendingIntent.getBroadcast(c, CHIP_ID + 1, Intent(c, QolReceiver::class.java).setAction(QolReceiver.HIDE_CHIP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Parked · ${QolRules.clock(car.at, now)}")
            .setContentText(car.near?.let { "Near $it" } ?: "Tap for walking directions back")
            .setContentIntent(nav)
            .addAction(0, "Navigate to car", nav)
            .addAction(0, "Hide", hide)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_owl).setContentTitle("Parked").build())
            .setTimeoutAfter(12 * 3_600_000L)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", QolRules.chip(car.at, now)) })
        nm.notify(CHIP_ID, b.build())
    }

    fun hideChip(c: Context) { c.getSystemService(NotificationManager::class.java)?.cancel(CHIP_ID); Clio.info(AREA, "parked chip hidden") }
}

/** His tap opens another app: "app:<package>" launches it, "camera" the camera, anything else is a view intent. */
object Opens {
    const val CAMERA = "camera"
    /** The clock's alarm list; "dismiss:H:M" its dismiss screen for that time (24-hour). */
    const val ALARMS = "alarms"
    const val DISMISS = "dismiss:"

    fun intent(c: Context, target: String): Intent? = when {
        target.startsWith("app:") -> c.packageManager.getLaunchIntentForPackage(target.removePrefix("app:"))
        target == CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        target == ALARMS -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        target.startsWith(DISMISS) -> target.removePrefix(DISMISS).split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { (h, m) ->
            Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, if (h % 12 == 0) 12 else h % 12).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                .putExtra(android.provider.AlarmClock.EXTRA_IS_PM, h >= 12)
        }
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    fun start(c: Context, target: String) = runCatching { intent(c, target)?.let { c.startActivity(it) } }
}

/** The quiet rules for anything that would interrupt: never on a call or with a game in the foreground. */
object Quiet {
    fun onCall(c: Context): Boolean = runCatching {
        c.getSystemService(AudioManager::class.java)?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
    }.getOrDefault(false)

    @Volatile private var gameAt = 0L
    @Volatile private var game = false

    /** A game is the app in front (Usage access), read at most once a minute. */
    fun gaming(c: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - gameAt < 60_000L) return game
        gameAt = now
        game = runCatching {
            val usm = c.getSystemService(UsageStatsManager::class.java) ?: return@runCatching false
            val ev = usm.queryEvents(now - 10 * 60_000L, now)
            val e = UsageEvents.Event()
            var front: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = e.packageName
                else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && e.packageName == front) front = null
            }
            front != null && front != c.packageName && c.packageManager.getApplicationInfo(front, 0).category == ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)
        return game
    }

    fun busy(c: Context) = onCall(c) || gaming(c)
}

class QolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            HIDE_CHIP -> QolCore.hideChip(context.applicationContext)
            UNDO_MUTE -> context.applicationContext.let { app -> val p = goAsync(); thread { try { WorkMuteCore.undo(app) } finally { p.finish() } } }
            STREET -> context.applicationContext.let { app -> val p = goAsync(); thread { try { StreetParking.fire(app) } finally { p.finish() } } }
            STREET_DONE -> context.applicationContext.let { app -> val p = goAsync(); thread { try { StreetParking.done(app) } finally { p.finish() } } }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> context.applicationContext.let { app -> val p = goAsync(); thread { try { StreetParking.arm(app); Bins.arm(app) } finally { p.finish() } } }
            BINS -> context.applicationContext.let { app -> val p = goAsync(); thread { try { Bins.fire(app) } finally { p.finish() } } }
            BINS_DONE -> context.applicationContext.let { app -> val p = goAsync(); thread { try { Bins.done(app) } finally { p.finish() } } }
            INDIA_REMIND -> context.applicationContext.let { app -> val k = intent.getStringExtra("key"); val v = intent.getStringExtra("via"); if (k != null && v != null) { val p = goAsync(); thread { try { IndiaClock.remind(app, k, v) } finally { p.finish() } } } }
            INDIA_DUE -> context.applicationContext.let { app -> val k = intent.getStringExtra("key"); val v = intent.getStringExtra("via"); if (k != null && v != null) IndiaClock.due(app, k, v) }
            METER -> context.applicationContext.let { app -> val p = goAsync(); thread { try { Meter.nudge(app) } finally { p.finish() } } }
            METER_STOP -> context.applicationContext.let { app -> val p = goAsync(); thread { try { Meter.stop(app) } finally { p.finish() } } }
            UNDO_MUSIC -> context.applicationContext.let { app -> val p = goAsync(); thread { try { CarMusic.undo(app) } finally { p.finish() } } }
        }
    }

    companion object {
        const val HIDE_CHIP = "dev.phonerecall.QOL_HIDE_CHIP"
        const val UNDO_MUTE = "dev.phonerecall.QOL_UNDO_MUTE"
        const val UNDO_MUSIC = "dev.phonerecall.QOL_UNDO_MUSIC"
        const val STREET = "dev.phonerecall.QOL_STREET"
        const val METER = "dev.phonerecall.QOL_METER"
        const val INDIA_REMIND = "dev.phonerecall.QOL_INDIA_REMIND"
        const val INDIA_DUE = "dev.phonerecall.QOL_INDIA_DUE"
        const val BINS = "dev.phonerecall.QOL_BINS"
        const val BINS_DONE = "dev.phonerecall.QOL_BINS_DONE"
        const val METER_STOP = "dev.phonerecall.QOL_METER_STOP"
        const val STREET_DONE = "dev.phonerecall.QOL_STREET_DONE"
    }
}
