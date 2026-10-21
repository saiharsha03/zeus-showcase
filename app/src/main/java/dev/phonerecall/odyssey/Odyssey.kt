package dev.phonerecall.odyssey

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Process
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.AndroidLocalState
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Incoming
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.SealedSecret
import dev.phonerecall.mind.MindNote
import dev.phonerecall.music.DriveSense
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.concurrent.thread
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Calypso's two road keys. Read only by the code that calls TomTom and 511NY. */
fun tomTomKey(c: Context) = SealedSecret(c, "tomtom")
fun ny511Key(c: Context) = SealedSecret(c, "ny511")

/**
 * Odyssey on Android: the shared drive detector's transitions, one fix per stay, the Geocoder and Wi-Fi
 * name for naming, payment notifications as hints, store proposals, and the traffic checks. All the
 * deciding is in Visits, Commute and DriveAnalyzer; this file only gathers and stores.
 */
object Odyssey {
    private const val TAG = "Zeus-Odyssey"
    const val CHANNEL = "odyssey_roads"

    fun state(c: Context) = OdysseyStore.load(c)
    fun tracking(c: Context) = Visits.tracking(state(c))
    /** Just the yes/no, for callers outside Odyssey that must not read its store. */
    fun driving() = OdysseyStore.state.value.driving

    fun canFine(c: Context) = AndroidLocalState.granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun canBackground(c: Context) = AndroidLocalState.granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun canActivity(c: Context) = AndroidLocalState.granted(c, Manifest.permission.ACTIVITY_RECOGNITION)

    /** UsageStats (Settings > Usage access): only used if he allowed it there. */
    fun canUsage(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun settings(c: Context, change: (OdysseySettings) -> OdysseySettings) {
        val d = OdysseyStore.update(c) { it.copy(settings = change(it.settings)) }
        if (Visits.tracking(d)) { DriveSense.arm(c); RoadsWork.schedule(c) } else { DriveSense.disarm(c); DriveService.stop(c); RoadsWork.cancel(c) }
    }

    /** The shared drive detector's transitions (music/DriveReceiver). Runs on a worker thread. */
    fun transitions(c: Context, events: List<Pair<Act, Long>>) {
        for ((act, at) in events.sortedBy { it.second }) {
            val before = state(c)
            if (!Visits.tracking(before)) { if (act == Act.VEHICLE_ENTER) dev.phonerecall.clio.Clio.warn("odyssey", "drive ignored", if (before.settings.paused) "tracking paused" else "Odyssey is off"); return }
            val out = Visits.activity(before, act, at)
            OdysseyStore.update(c) { out.data }
            // A drive that starts at home is leaving home, even when the geofence exit never comes.
            if (act == Act.VEHICLE_ENTER) before.home()?.let { h -> before.stay?.takeIf { Geo.meters(h.lat, h.lng, it.lat, it.lng) < 200 } }
                ?.let { dev.phonerecall.hestia.HestiaRoutines.event(c, dev.phonerecall.hestia.Moment.LEAVE) }
            when (act) {
                Act.VEHICLE_ENTER -> if (before.settings.driveReport) DriveService.start(c)
                Act.VEHICLE_EXIT -> DriveService.stop(c)
                else -> Unit
            }
            // Small touches: where the car is (qol/QolCore).
            if (act == Act.VEHICLE_ENTER || act == Act.VEHICLE_EXIT) dev.phonerecall.qol.QolCore.drive(c, act == Act.VEHICLE_ENTER)
            if (out.needFix) fixNow(c, act == Act.VEHICLE_EXIT)
            out.visit?.let { v -> visited(c, v); out.data.place(v.placeId)?.takeIf { it.kind == Kind.FUEL }?.let { p -> Garage.fuelStop(c, v.start, p.name) } }
            out.visit?.let { v -> out.data.place(v.placeId)?.name?.takeIf { it.isNotBlank() }?.let { dev.phonerecall.clio.Clio.info(dev.phonerecall.clio.DeviceLog.PLACES, dev.phonerecall.clio.DeviceLog.LEFT, it) } }
        }
    }

    /** One fix for the stay: the drive's last GPS point when it is fresh, else one balanced-power fix. */
    @SuppressLint("MissingPermission")
    private fun fixNow(c: Context, afterDrive: Boolean) {
        if (!canFine(c)) return
        val f = DriveService.lastFix?.takeIf { afterDrive && System.currentTimeMillis() - it.at < 120_000L } ?: runCatching {
            Tasks.await(LocationServices.getFusedLocationProviderClient(c).getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token), 20, TimeUnit.SECONDS)
        }.getOrNull()?.let { Fix(System.currentTimeMillis(), it.latitude, it.longitude, it.accuracy) } ?: return
        val (poi, street) = geocode(c, f.lat, f.lng)
        val (snapped, at) = Pins.snap(state(c), pins(c), f)
        val out = Visits.fix(snapped, at, poi, street, wifi(c), nearby(c, f.lat, f.lng))
        OdysseyStore.update(c) { out.data }
        out.visit?.let { v -> visited(c, v); out.data.place(v.placeId)?.name?.takeIf { it.isNotBlank() }?.let { dev.phonerecall.clio.Clio.info(dev.phonerecall.clio.DeviceLog.PLACES, dev.phonerecall.clio.DeviceLog.LEFT, it) } }
        // A stay that just opened at a place he already has a name for: Clio gets the name, never the fix.
        if (out.data.stay?.start == f.at) Visits.known(out.data, f)?.let { dev.phonerecall.clio.Clio.info(dev.phonerecall.clio.DeviceLog.PLACES, dev.phonerecall.clio.DeviceLog.ARRIVED, it.name) }
        // A new stay: Athena hears where he is now ("At home since 2:49 PM today").
        if (out.data.stay?.start == f.at) shareFacts(c, out.data, f.at, force = true)
        fuelPlace(c, out.data)
    }

    /** Android's Geocoder (Play services' process, not a Zeus host): a place name if it has one, and the street. */
    @Suppress("DEPRECATION")
    fun geocode(c: Context, lat: Double, lng: Double): Pair<String?, String?> = runCatching {
        if (!Geocoder.isPresent()) return null to null
        val a = Geocoder(c, Locale.US).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null to null
        val poi = a.featureName?.takeIf { it != a.subThoroughfare && it != a.thoroughfare && it.any { ch -> ch.isLetter() } }
        poi to a.thoroughfare
    }.getOrDefault(null to null)

    /** "Main St, Buffalo": street and city only, for a drive's two ends. */
    @Suppress("DEPRECATION")
    fun address(c: Context, lat: Double, lng: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(c, Locale.US).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null
        listOfNotNull(a.thoroughfare, a.locality ?: a.subAdminArea).joinToString(", ").ifBlank { null }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifi(c: Context): String? = runCatching {
        c.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    /** A notification while a stay is open: payment and receipt lines name the place. From SensesListener. */
    fun notification(c: Context, n: Incoming) {
        val d = OdysseyStore.state.value.takeIf { OdysseyStore.state.value.settings.on } ?: return
        if (d.stay == null || d.driving || !Visits.tracking(d)) return
        val text = "${n.title} ${n.text}"
        if (Naming.merchant(text) == null && Naming.amount(text) == null) return
        val fuel = FuelStop.isFuelText(text)
        Thread {
            OdysseyStore.update(c) { Visits.hint(it, text) }
            // A fuel payment during a stay: the fill-up card and the ask to Iris start now, not when he drives off.
            if (fuel) d.stay?.let { s -> Garage.fuelStop(c, s.start, Naming.merchant(text) ?: "a gas station") }
        }.start()
    }

    /** After a new visit: the brain's facts are refreshed at most daily (RoadsWorker); offers show as a card. */
    /** A visit closed: Clio gets "Wegmans 18 min", Athena today's stops in words. */
    private fun visited(c: Context, v: Visit) {
        val d = state(c)
        d.place(v.placeId)?.let { p -> dev.phonerecall.clio.Clio.info("odyssey", "visit", Stops.line(v, p)) }
        shareFacts(c, d, System.currentTimeMillis(), force = true)
        // Small touches: a "remind me when I leave" note made there (qol2/Touches).
        runCatching { dev.phonerecall.qol2.Touches.visited(c, v) }
        BrainCore.get(c).changed()
    }

    /** TomTom's nearest point of interest for a stop's fix (only the latitude and longitude go), or null offline or without the key. */
    private fun nearby(c: Context, lat: Double, lng: Double): PoiHit? {
        val key = tomTomKey(c).read() ?: return null
        val hit = runCatching { Poi.parseNearby(get(Poi.nearbyUrl(lat, lng, key))).firstOrNull() }.onFailure { Log.w(TAG, "TomTom POI: ${it.message}") }.getOrNull()
        if (hit == null || hit.street != null) return hit
        return hit.copy(street = runCatching { Poi.parseStreet(get(Poi.reverseUrl(lat, lng, key))) }.getOrNull())
    }

    /** His pins from Places (Themis), for [Pins]. */
    fun pins(c: Context): List<Pin> = runCatching { dev.phonerecall.places.PlaceStore(c).all().map { Pin(it.name, it.lat, it.lng, it.radius) } }.getOrDefault(emptyList())

    /** Puts an open stay inside one of his pins onto that place (run on each tick and when the screen opens). */
    fun heal(c: Context) {
        val d = state(c)
        val pins = pins(c)
        val clio = dev.phonerecall.clio.Clio.get()?.lines(d.stay?.start ?: Long.MAX_VALUE).orEmpty()
            .filter { it.area == dev.phonerecall.clio.DeviceLog.PLACES && (it.event == dev.phonerecall.clio.DeviceLog.LEFT || it.event == dev.phonerecall.clio.DeviceLog.ARRIVED) }
            .map { it.ts to (it.event == dev.phonerecall.clio.DeviceLog.LEFT) }
        val healed = Pins.backfill(Pins.heal(d, pins), pins, clio)
        if (healed != d) { OdysseyStore.update(c) { Pins.backfill(Pins.heal(it, pins), pins, clio) }; BrainCore.get(c).changed() }
    }

    /** One of his pins entered or left: noted in Odyssey (the timeline shows it), and a leave starts the drive. */
    fun pinEvent(c: Context, pin: Pin, left: Boolean, at: Long = System.currentTimeMillis()) {
        if (!Visits.tracking(state(c))) return
        if (left) geofenceExit(c, at)
        OdysseyStore.update(c) { Pins.event(it, pin, left, at) }
        BrainCore.get(c).changed()
    }

    /** Moneta asks where he was when a charge came in ("Wegmans" next to it). */
    fun hooks(c: Context) {
        val app = c.applicationContext
        // Activity transitions can be dropped by Play services (an update, a cache clear); arming again is harmless.
        thread(name = "odyssey-arm") { runCatching { DriveSense.arm(app) } }
        dev.phonerecall.moneta.MonetaHooks.visits = dev.phonerecall.moneta.VisitLink { at -> Stops.placeAt(OdysseyStore.load(app), at) }
    }

    /** A stay that opened at a place Odyssey knows as a fuel stop: the fill-up starts while he is still at the pump. */
    private fun fuelPlace(c: Context, d: OdysseyData) {
        val s = d.stay ?: return
        val name = d.places.firstOrNull { it.kind == Kind.FUEL && Geo.meters(it.lat, it.lng, s.lat, s.lng) < Visits.SAME_PLACE_M }?.name
            ?: s.poiName?.takeIf { s.poiKind == Kind.FUEL } ?: return
        Garage.fuelStop(c, s.start, name)
    }

    // ---- places Odyssey learns on its own (Learn.kt) ----

    /** Reviews his stays for Home and the office; applies the sure ones (with Undo) and asks about the rest. Idempotent. */
    fun learn(c: Context) {
        val d = state(c)
        if (!d.settings.on) return
        val r = PlaceLearner.review(d, System.currentTimeMillis())
        if (r.applied.isEmpty() && r.asked.isEmpty()) return
        OdysseyStore.update(c) { r.data }
        for (g in r.applied) dev.phonerecall.clio.Clio.info("odyssey", "learned a place", "${g.kind.label} set on its own from ${g.visits} visits (Undo in Odyssey)")
        for (g in r.asked) dev.phonerecall.clio.Clio.info("odyssey", "asking about a place", PlaceLearner.text(g))
        if (r.applied.isNotEmpty()) { placesChanged(c); shareFacts(c, state(c), System.currentTimeMillis(), force = true) } else BrainCore.get(c).changed()
    }

    fun answerGuess(c: Context, id: String, yes: Boolean) { OdysseyStore.update(c) { PlaceLearner.answer(it, id, yes) }; placesChanged(c) }
    fun relabelGuess(c: Context, id: String, kind: Kind, name: String?) { OdysseyStore.update(c) { PlaceLearner.relabel(it, id, kind, name) }; placesChanged(c) }
    fun ignoreGuess(c: Context, id: String) { OdysseyStore.update(c) { PlaceLearner.ignore(it, id) }; BrainCore.get(c).changed() }

    /** Home or the office changed: the arrival geofences follow, and the cards. */
    private fun placesChanged(c: Context) {
        runCatching { dev.phonerecall.places.Places.register(c) }
        BrainCore.get(c).changed()
    }

    const val FENCE = "odyssey:"

    /** Home and the office as geofences (Places registers them with the stores), ids "odyssey:<place id>". */
    fun fences(c: Context): List<dev.phonerecall.places.Place> = state(c).takeIf { Visits.tracking(it) }?.places.orEmpty()
        .filter { it.kind == Kind.HOME || it.kind == Kind.WORK }
        .map { dev.phonerecall.places.Place(FENCE + it.id, it.name, it.lat, it.lng, 150f) }

    /** Geofence enter at Home or the office: logged to Clio and to Athena ("At the office since 9:34"). */
    fun arrivedAt(c: Context, placeId: String, at: Long = System.currentTimeMillis()) {
        val p = state(c).place(placeId) ?: return
        OdysseyStore.update(c) { d -> d.copy(arrivals = (d.arrivals + Arrival(p.id, p.kind, at)).filter { at - it.at < 14 * 86_400_000L }) }
        dev.phonerecall.clio.Clio.info("odyssey", "arrived", "${p.name} ${dev.phonerecall.home.Fmt.time(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}")
        shareFacts(c, state(c), at, force = true)
        if (p.kind == Kind.WORK) dev.phonerecall.qol.WorkMuteCore.arrived(c)
        if (p.kind == Kind.HOME) dev.phonerecall.qol.Later.arrivedHome(c)
        dev.phonerecall.shortcuts.ShortcutsCore.trigger(c, "arrive:${p.kind.name.lowercase()}", if (p.kind == Kind.HOME) "you get home" else if (p.kind == Kind.WORK) "you get to the office" else "you get to ${p.name}")
        dev.phonerecall.hestia.HestiaRoutines.event(c, dev.phonerecall.hestia.Moment.ARRIVE, routinePlace(p))
    }

    /**
     * A geofence exit (his pin or Odyssey's Home and office): when activity recognition hasn't said IN_VEHICLE,
     * the drive starts from here, so a missed transition no longer loses the whole drive.
     */
    fun geofenceExit(c: Context, at: Long = System.currentTimeMillis()) {
        if (!Visits.startOnExit(state(c))) return
        dev.phonerecall.clio.Clio.info("odyssey", "drive from geofence exit")
        transitions(c, listOf(Act.VEHICLE_ENTER to at))
    }

    fun leftPlace(c: Context, placeId: String, at: Long = System.currentTimeMillis()) {
        geofenceExit(c, at)
        val p = state(c).place(placeId) ?: return
        OdysseyStore.update(c) { d ->
            val i = d.arrivals.indexOfLast { it.placeId == placeId && it.left == null }
            if (i < 0) d else d.copy(arrivals = d.arrivals.toMutableList().also { it[i] = it[i].copy(left = at) })
        }
        dev.phonerecall.clio.Clio.info("odyssey", "left", "${p.name} ${dev.phonerecall.home.Fmt.time(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}")
        shareFacts(c, state(c), at, force = true)
        if (p.kind == Kind.WORK) dev.phonerecall.qol.WorkMuteCore.left(c)
        dev.phonerecall.hestia.HestiaRoutines.event(c, dev.phonerecall.hestia.Moment.LEAVE, routinePlace(p))
    }

    /** The place as a home routine names it: home, work, or its name. */
    private fun routinePlace(p: KnownPlace) = when (p.kind) { Kind.HOME -> "home"; Kind.WORK -> "work"; else -> p.name.lowercase() }

    /** His one tap on "Show Groceries when you arrive?": the geofence and the lists. Place questions come here by their "g" id. */
    fun accept(c: Context, placeId: String) {
        if (state(c).guesses.any { it.id == placeId }) { answerGuess(c, placeId, true); return }
        val d = state(c)
        val sections = BrainCore.get(c).sections.visible().map { it.id to it.name }
        val o = Offers.due(d, sections).firstOrNull { it.place.id == placeId } ?: return
        val id = "o${o.place.id}"
        dev.phonerecall.places.PlaceStore(c).upsert(dev.phonerecall.places.Place(id, o.place.name, o.place.lat, o.place.lng, 150f, o.lists))
        dev.phonerecall.places.Places.register(c)
        OdysseyStore.update(c) { Offers.mark(it, placeId, OfferState.ACCEPTED, id) }
        BrainCore.get(c).audit.log("The owner", "store place", "${o.place.name}: ${o.listNames.joinToString().ifEmpty { "no list" }}")
        BrainCore.get(c).changed()
    }

    fun decline(c: Context, placeId: String) {
        if (state(c).guesses.any { it.id == placeId }) { answerGuess(c, placeId, false); return }
        OdysseyStore.update(c) { Offers.mark(it, placeId, OfferState.DECLINED) }
        BrainCore.get(c).changed()
    }

    /** "I'm here now" naming from Odyssey, or renaming a learned place: his name always wins. */
    fun rename(c: Context, placeId: String, name: String) {
        OdysseyStore.update(c) { d -> d.copy(places = d.places.map { if (it.id == placeId) it.copy(name = name.trim(), source = NameSource.HIM, kind = if (it.kind == Kind.HOME || it.kind == Kind.WORK) it.kind else Naming.kind(name)) else it }) }
    }

    fun setKind(c: Context, placeId: String, kind: Kind) {
        OdysseyStore.update(c) { d -> d.copy(places = d.places.map { if (it.id == placeId) it.copy(kind = kind, source = if (it.source == NameSource.NONE) NameSource.HIM else it.source) else it }) }
    }

    /** A Google Maps place shared to Zeus: read from the text on the phone; the address goes to the Geocoder only if there are no coordinates. */
    @Suppress("DEPRECATION")
    fun shared(c: Context, text: String): String {
        val s = MapsLink.parse(text) ?: return "That isn't a Maps place link."
        var lat = s.lat; var lng = s.lng
        if (lat == null || lng == null) {
            val a = s.address?.let { addr -> runCatching { Geocoder(c, Locale.US).getFromLocationName(addr, 1)?.firstOrNull() }.getOrNull() }
            lat = a?.latitude; lng = a?.longitude
        }
        if (lat == null || lng == null) return "That link has no coordinates. Open it at the place and use I'm here now."
        val name = s.name ?: "Shared place"
        val street = geocode(c, lat, lng).second
        OdysseyStore.update(c) { d ->
            val near = d.places.firstOrNull { Geo.meters(it.lat, it.lng, lat, lng) < Visits.SAME_PLACE_M }
            val p = (near ?: KnownPlace("k${d.nextId}", lat = lat, lng = lng)).copy(name = name, source = NameSource.MAPS_LINK, kind = Naming.kind(name), street = street ?: near?.street, offer = OfferState.OFFERED)
            d.copy(places = d.places.filter { it.id != p.id } + p, nextId = d.nextId + 1, settings = d.settings.copy(on = true))
        }
        BrainCore.get(c).changed()
        return "Saved $name. Choose its list in ${dev.phonerecall.ui.Place.PLACES.myth}."
    }

    fun forgetVisit(c: Context, id: String) = OdysseyStore.update(c) { Forget.visit(it, id) }
    fun forgetPlace(c: Context, id: String) = OdysseyStore.update(c) { Forget.place(it, id) }
    /** His one-tap correction of a drive's trip tag; null goes back to the automatic one. */
    fun tagDrive(c: Context, id: String, tag: TripTag?) = OdysseyStore.update(c) { d -> d.copy(drives = d.drives.map { if (it.id == id) it.copy(tag = tag?.name) else it }) }

    fun forgetDay(c: Context, day: LocalDate) {
        val z = ZoneId.systemDefault()
        val before = OdysseyStore.load(c).drives.map { it.id }.toSet()
        val after = OdysseyStore.update(c) { Forget.day(it, day.atStartOfDay(z).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()) }
        for (id in before - after.drives.map { it.id }.toSet()) RouteStore.delete(c, id)
    }

    // ---- traffic checks ----

    /** GET through NetGate. The key is in the query, so nothing here logs the URL. */
    fun get(url: String): String {
        NetGate.check(url)
        val t0 = System.currentTimeMillis()
        val conn = dev.phonerecall.brain.TlsGuard.open(url)
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            NetGate.logRequest("GET", url, code, System.currentTimeMillis() - t0)
            if (code !in 200..299) throw java.io.IOException("HTTP $code from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { conn.disconnect() }
    }

    /** One check: TomTom for the two ends (and 511NY's list, filtered here). Stores the card, notifies when it matters. */
    /** TomTom's routes for the two ends, or null. The main route's shape is cached (sealed) under `shapeKey`. */
    private fun routes(c: Context, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, shapeKey: String?): List<Route>? {
        val tomtom = tomTomKey(c).read() ?: return null
        val routes = runCatching { TomTom.parse(get(TomTom.routeUrl(fromLat, fromLng, toLat, toLng, tomtom))) }.onFailure { Log.w(TAG, "TomTom: ${it.message}") }.getOrNull() ?: return null
        val main = routes.first()
        if (shapeKey != null && main.points.size >= 2) {
            val (pts, roads) = TomTom.thin(main.points, main.roads)
            val now = System.currentTimeMillis()
            OdysseyStore.update(c) { d -> d.copy(shapes = (d.shapes.filter { it.key != shapeKey } + RouteShape(shapeKey, now, main.seconds, pts, roads)).takeLast(6)) }
        }
        return routes
    }

    fun check(c: Context, key: String, reason: String, destination: String, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, arriveBy: Long, usual: Int?, shapeKey: String? = null): RoadCheck? {
        val routes = routes(c, fromLat, fromLng, toLat, toLng, shapeKey) ?: return null
        val extra = ny511Key(c).read()?.let { k -> runCatching { Ny511.onRoute(Ny511.parse(get(Ny511.eventsUrl(k))), fromLat, fromLng, toLat, toLng) }.getOrNull() }.orEmpty()
        val now = System.currentTimeMillis()
        val rc = RoadWords.check(key, reason, destination, arriveBy, routes, extra, now, toLat, toLng, usual)
        OdysseyStore.update(c) { d -> d.copy(checks = (d.checks.filter { it.key != key && it.expiresAt > now - 86_400_000L } + rc).takeLast(10), ran = d.ran.filterValues { now - it < 3 * 86_400_000L } + (key to now)) }
        BrainCore.get(c).changed()
        val d = state(c)
        // While driving only an alert about this trip reaches him; a leave-by is about the next trip.
        if ((rc.delayMinutes >= 5 || rc.incidents.isNotEmpty() || reason == "event") && RoadWords.whileDriving(d.driving, routeRelevant = false)) notify(c, rc)
        // A leave-by trip in lake-effect season: the same route, against the radar.
        if (reason == "event" && SnowBand.season(LocalDate.now())) {
            val main = routes.first()
            val (pts, roads) = TomTom.thin(main.points, main.roads)
            snow(c, "snow:$key", pts, roads, main.seconds, rc.leaveBy, destination)
        }
        return rc
    }

    /** Radar along a route for a departure; a note (and a notification) only when a real band is on it. */
    private fun snow(c: Context, key: String, points: List<Pt>, roads: List<RoadSpan>, seconds: Int, dep: Long, destination: String) {
        val now = System.currentTimeMillis()
        OdysseyStore.update(c) { it.copy(ran = it.ran + (key to now)) }
        if (points.size < 2) return
        val frames = Radar.frames(points, now)
        val a = SnowBand.advise(points, roads, seconds, dep, frames, now) ?: return
        val temp = dev.phonerecall.aeolia.WeatherStore.load(c).tempC
        val source = frames.map { it.source }.distinct().joinToString(" and ")
        val n = SnowBand.note(key, a, snow = temp == null || temp <= 2.0, destination, dep, (seconds / 60.0).roundToInt(), source, now, ZoneId.systemDefault())
        OdysseyStore.update(c) { it.note(n, now) }
        BrainCore.get(c).changed()
        notify(c, n)
    }

    /** "Got it" on a note, or on a lease card (kept as dismissed so it stays away). */
    fun noteDone(c: Context, key: String) {
        OdysseyStore.update(c) { d -> d.copy(notes = d.notes.filter { it.key != key }, car = if (key.startsWith("lease")) d.car.copy(dismissed = (d.car.dismissed + key).toList().takeLast(60).toSet()) else d.car) }
        BrainCore.get(c).changed()
    }

    fun notify(c: Context, n: RoadNote) {
        if (!AndroidLocalState.granted(c, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Roads", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Commute heads-ups and leave-by times." })
        val open = PendingIntent.getActivity(c, 73, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify("odyssey", n.key.hashCode(), NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl).setContentTitle(n.title).setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(n.body) + n.facts.map { "${it.label}: ${it.value}" }).joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter((n.expiresAt - System.currentTimeMillis()).coerceAtLeast(60_000L)).build())
    }

    fun notify(c: Context, rc: RoadCheck) {
        if (!AndroidLocalState.granted(c, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Roads", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Commute heads-ups and leave-by times." })
        val maps = PendingIntent.getActivity(c, 71, mapsIntent(rc.lat, rc.lng), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(c, 72, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "ODYSSEY"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = RoadWords.advice(rc)
        // A delay or an incident on the trip about to start: time-critical, so it never waits for the brief.
        BrainCore.get(c).attention.notify(dev.phonerecall.brain.Urgency.TIME_CRITICAL, "odyssey:${rc.key}", rc.title) { nm.notify("odyssey", rc.key.hashCode(), NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl).setContentTitle(rc.title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + rc.incidents).joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter((rc.expiresAt - System.currentTimeMillis()).coerceAtLeast(60_000L))
            .addAction(0, "Open in Maps", maps).build()) }
    }

    /** Maps is another app: this is an intent, not a request from Zeus. */
    fun mapsIntent(lat: Double, lng: Double): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The next few hours' events that have a location: title, start, location text. Read live, not kept. */
    fun upcomingEvents(c: Context, now: Long): List<Triple<String, Long, String>> {
        if (!AndroidLocalState.granted(c, Manifest.permission.READ_CALENDAR)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, now); android.content.ContentUris.appendId(it, now + 3 * 3_600_000L) }.build()
        val out = ArrayList<Triple<String, Long, String>>()
        runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY), dev.phonerecall.data.MyAccounts.instancesWhere(c), null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val loc = cur.getString(2)?.trim().orEmpty()
                    if (cur.getInt(3) == 1 || loc.length < 4 || loc.contains("teams.microsoft", true) || loc.contains("zoom.us", true) || loc.startsWith("http")) continue
                    out += Triple(cur.getString(0).orEmpty(), cur.getLong(1), loc)
                }
            }
        }
        return out
    }

    /** Out of office or a holiday today (an all-day entry): no commute heads-up. Read live, titles only. */
    private fun dayOff(c: Context, now: ZonedDateTime): Boolean {
        if (!AndroidLocalState.granted(c, Manifest.permission.READ_CALENDAR)) return false
        val from = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, from + 86_400_000L) }.build()
        return runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY), dev.phonerecall.data.MyAccounts.instancesWhere(c), null, null)?.use { cur ->
                var off = false
                while (cur.moveToNext()) {
                    val t = cur.getString(0).orEmpty().lowercase()
                    if (cur.getInt(1) == 1 && listOf("ooo", "out of office", "pto", "vacation", "holiday", "day off").any { t.contains(it) }) off = true
                }
                off
            } ?: false
        }.getOrDefault(false)
    }

    /** Runs whatever is due now: the pre-departure check, leave-by checks, and the daily facts for the brain. */
    @SuppressLint("MissingPermission")
    fun tick(c: Context) {
        val d = state(c)
        if (!Visits.tracking(d)) return
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val ms = now.toInstant().toEpochMilli()
        shareFacts(c, d, ms)
        runCatching { DriveSense.arm(c) }
        runCatching { heal(c) }
        runCatching { learn(c) }
        // The car: Iris looks up the lease once; late answers to any ask are read here too.
        runCatching { Garage.askLease(c); if (d.car.asks.isNotEmpty()) Garage.poll(c) }
        val stats = Commute.stats(d, ms, zone)
        val home = d.home(); val work = d.work()
        val atHome = d.stay?.let { s -> home?.let { Geo.meters(it.lat, it.lng, s.lat, s.lng) < 200 } }
        val today = now.toLocalDate()
        // "WFH today?" needs no network: the day's availability and where he is.
        if (OfficeDay.key(today) !in d.ran && now.hour in 7..11) {
            dev.phonerecall.data.DayOverrides.load(c)
            val avail = runCatching { dev.phonerecall.mind.BackgroundReads(c, BrainCore.get(c)).availability()[today] }.getOrNull()
            if (OfficeDay.due(avail, stats, now, atHome, d.driving, d.ran, OfficeDay.arrived(state(c), now))) {
                OdysseyStore.update(c) { it.note(OfficeDay.note(now), ms).copy(ran = it.ran + (OfficeDay.key(today) to ms)) }
                BrainCore.get(c).changed()
            }
        }
        if (!d.settings.commuteChecks || !tomTomKey(c).has()) return
        // Away on a trip (Argo): no commute heads-up either.
        val workday = !dayOff(c, now) && dev.phonerecall.data.DayOverrides.load(c)[today] != dev.phonerecall.data.DayType.REMOTE &&
            dev.phonerecall.data.AvailabilityModel.travel?.travelling(today, zone) != true
        val shapeKey = if (home != null && work != null) "${home.id}>${work.id}" else null
        if (home != null && work != null && Commute.departureDue(stats, now, d.ran, atHome, workday)) {
            val dep = stats.departures.getValue(now.dayOfWeek)
            val arrive = today.atStartOfDay(zone).plusMinutes((dep + (stats.minutes[now.dayOfWeek] ?: stats.average ?: 20)).toLong()).toInstant().toEpochMilli()
            check(c, Commute.departureKey(today), "commute", work.name, home.lat, home.lng, work.lat, work.lng, arrive, stats.minutes[now.dayOfWeek] ?: stats.average, shapeKey)
        }
        // Lake-effect season: 90 to 40 minutes before he usually leaves, the commute route against the radar.
        val dep = stats.departures[now.dayOfWeek]
        val snowKey = "snow:commute:$today"
        if (home != null && work != null && dep != null && workday && atHome != false && SnowBand.season(today) && snowKey !in d.ran &&
            (now.hour * 60 + now.minute) in (dep - 90)..(dep - 40)) {
            val shape = d.shape(home.id, work.id)?.takeIf { ms - it.at < 7 * 86_400_000L }
                ?: routes(c, home.lat, home.lng, work.lat, work.lng, shapeKey)?.let { state(c).shape(home.id, work.id) }
            if (shape != null) snow(c, snowKey, shape.points, shape.roads, shape.seconds, today.atStartOfDay(zone).plusMinutes(dep.toLong()).toInstant().toEpochMilli(), work.name)
        }
        for ((title, start, loc) in upcomingEvents(c, ms)) {
            val key = "event:${title.hashCode()}:$start"
            if (!Commute.eventDue(start, ms, key, d.ran)) continue
            @Suppress("DEPRECATION")
            val a = runCatching { Geocoder(c, Locale.US).getFromLocationName(loc, 1)?.firstOrNull() }.getOrNull() ?: continue
            val from = d.stay?.let { it.lat to it.lng } ?: home?.let { it.lat to it.lng } ?: continue
            if (Geo.meters(from.first, from.second, a.latitude, a.longitude) < 500) continue
            check(c, key, "event", title.take(40), from.first, from.second, a.latitude, a.longitude, start, null)
        }
    }

    /** Once a day: the derived facts, and only them, go to the brain. */
    private fun shareFacts(c: Context, d: OdysseyData, now: Long, force: Boolean = false) {
        val day = LocalDate.now().toString()
        if (d.ran["facts:$day"] != null && !force) return
        val facts = OdysseyBrain.facts(d, now)
        OdysseyStore.update(c) { it.copy(ran = it.ran + ("facts:$day" to now)) }
        if (facts.isEmpty()) return
        BrainCore.get(c).mindHost.offer(MindNote(now, "Roads and places (derived on the phone): " + facts.joinToString("; ") + ".", key = "odyssey"))
    }
}

/** Every 15 minutes, battery permitting: decides locally whether a check is due; the network only when it is. */
class RoadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        runCatching { Odyssey.tick(applicationContext) }
        return Result.success()
    }
}

object RoadsWork {
    private const val NAME = "zeus-odyssey-roads"
    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RoadsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }
    fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork(NAME) }
}

/** Transitions don't survive a restart or an update: arm the drive detector again. */
class OdysseyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { DriveSense.arm(context); if (Odyssey.tracking(context)) RoadsWork.schedule(context) } finally { pending.finish() }
        }.start()
    }
}
