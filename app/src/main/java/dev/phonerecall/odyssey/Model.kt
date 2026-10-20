package dev.phonerecall.odyssey

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Odyssey's data. Everything here stays on the phone, sealed with an Aegis Keystore key (OdysseyStore).
 * Raw coordinates never leave it: the brain gets OdysseyBrain.facts() only, TomTom gets the two ends of
 * the trip being checked, at check time. Every class has defaults so Jackson can read it back.
 */

/** One location fix. */
data class Fix(val at: Long = 0, val lat: Double = 0.0, val lng: Double = 0.0, val accuracy: Float = 50f)

/** What a place is, from its name. `autoLog` kinds get a line in Logs and a past entry in Chronos. */
enum class Kind(val label: String, val autoLog: Boolean, val list: String? = null) {
    HOME("Home", false), WORK("Office", false),
    GROCERY("Groceries", true, "Groceries"), CAR_WASH("Car wash", true), TEMPLE("Temple", true), GYM("Gym", true),
    FUEL("Fuel stop", true), RESTAURANT("Restaurant", false), CAFE("Cafe", false), SHOP("Shop", false, "Shopping"),
    HEALTH("Health", false), OTHER("Place", false),
}

/** Where a place's name came from, best first. */
enum class NameSource { HIM, MAPS_LINK, HINT, POI, WIFI, GEOCODER, NONE }

enum class OfferState { NONE, OFFERED, ACCEPTED, DECLINED }

/** A place Odyssey learned from his stays (a cluster of visits). */
data class KnownPlace(
    val id: String = "",
    val name: String = "",
    val source: NameSource = NameSource.NONE,
    val kind: Kind = Kind.OTHER,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** The street from the Geocoder ("Maple Ave"), for "Costco on Maple Ave". */
    val street: String? = null,
    val wifi: String? = null,
    val visits: Int = 0,
    val offer: OfferState = OfferState.NONE,
    /** The store-arrival geofence (places.Place id) once he said yes. */
    val geofence: String? = null,
    /** Odyssey worked out it was Home or the office on its own (PlaceLearner); Roles leaves it alone. Undo clears it. */
    val learned: Boolean = false,
)

/**
 * What Odyssey thinks a spot he stays at is: asked once ("Is this your office?"), or applied on its own with
 * Undo when it is sure. `state`: ask, auto (applied, Undo still offered), yes, no, ignored.
 */
data class PlaceGuess(
    val id: String = "",
    val kind: Kind = Kind.OTHER,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val street: String? = null,
    val visits: Int = 0,
    val state: String = "ask",
    val at: Long = 0,
    /** The place it was applied to, and what that place was before (for Undo). */
    val placeId: String? = null,
    val prevKind: Kind? = null,
    val prevName: String? = null,
    val prevSource: NameSource? = null,
) {
    val open: Boolean get() = state == "ask" || state == "auto"
}

/** A geofence arrival at Home or the office, and when he left. Kept two weeks. */
data class Arrival(val placeId: String = "", val kind: Kind = Kind.OTHER, val at: Long = 0, val left: Long? = null)

/** A stay of 5 minutes or more. `spent` is from a payment notification during it (on the phone only, for Moneta). */
data class Visit(
    val id: String = "",
    val placeId: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val spent: Double? = null,
) {
    val minutes: Long get() = (end - start) / 60_000L
}

/** A trip between two visits. `driveId` links the drive report when the drive detector ran. */
data class Trip(
    val id: String = "",
    val from: String? = null,
    val to: String? = null,
    val start: Long = 0,
    val end: Long = 0,
    val driveId: String? = null,
) {
    val minutes: Long get() = (end - start) / 60_000L
}

/** The stay he is in now: opened by one fix after a drive or a long stop. Hints are gathered while it lasts. */
data class OpenStay(
    val start: Long = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 50f,
    val hints: List<String> = emptyList(),
    val spent: Double? = null,
    val wifi: String? = null,
    /** Geocoder at the fix: a point-of-interest name if it has one, and the street. */
    val poi: String? = null,
    val street: String? = null,
    /** TomTom's nearest point of interest at the fix ("Wegmans (Cedar St)") and its kind, when the key is set and it answered. */
    val poiName: String? = null,
    val poiKind: Kind? = null,
)

/** Where the last visit ended, so the next one can be joined to it as a trip. */
data class Departure(val placeId: String = "", val at: Long = 0)

/** A spot he asked Odyssey to forget: stays here are not noted again. */
data class Zone(val lat: Double = 0.0, val lng: Double = 0.0, val radius: Double = 200.0)

data class OdysseySettings(
    /** Turned on in Odyssey once the permissions are in. */
    val on: Boolean = false,
    /** "Pause tracking": nothing is noted, no drive is sampled, until he resumes. */
    val paused: Boolean = false,
    /** Accelerometer, gyroscope and GPS during detected drives only. */
    val driveReport: Boolean = true,
    /** Pre-departure checks and leave-by times through TomTom (needs the TomTom key in Calypso). */
    val commuteChecks: Boolean = true,
    /** Keep each drive's path (sealed, its own file, 90 days) for the map on its report. */
    val keepRoutes: Boolean = true,
)

/** The result of one traffic check, shown as a card. Only names and minutes; no coordinates. */
data class RoadCheck(
    val key: String = "",
    val at: Long = 0,
    val title: String = "",
    val destination: String = "",
    /** Epoch ms he should leave by. */
    val leaveBy: Long = 0,
    val minutes: Int = 0,
    val delayMinutes: Int = 0,
    /** "Crash on I-190 S near exit 7". */
    val incidents: List<String> = emptyList(),
    /** Minutes more (or less) than the main route, when TomTom has an alternative. */
    val alternative: Int? = null,
    val expiresAt: Long = 0,
    /** For Open in Maps (an intent on the phone; Maps is another app). */
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** "commute" or "event". */
    val reason: String = "commute",
)

/** A point of a route, on the phone only. */
data class Pt(val lat: Double = 0.0, val lng: Double = 0.0)

/** From route point `from` on, the road is `name` ("I-190"), from TomTom's coded instructions. */
data class RoadSpan(val from: Int = 0, val name: String = "")

/**
 * A learned trip's route (TomTom's polyline, thinned), kept so the snow check knows which
 * roads it uses without asking again. Sealed with the rest; never sent anywhere.
 */
data class RouteShape(val key: String = "", val at: Long = 0, val seconds: Int = 0, val points: List<Pt> = emptyList(), val roads: List<RoadSpan> = emptyList())

data class NoteFact(val label: String = "", val value: String = "")

/** A heads-up that is not a traffic check: "snow" (a band on the route), "wfh". Words only. */
data class RoadNote(
    val key: String = "",
    val kind: String = "",
    val at: Long = 0,
    val title: String = "",
    val body: String = "",
    val facts: List<NoteFact> = emptyList(),
    val expiresAt: Long = 0,
)

/** Everything Odyssey keeps. */
data class OdysseyData(
    val settings: OdysseySettings = OdysseySettings(),
    val places: List<KnownPlace> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val drives: List<DriveReport> = emptyList(),
    val stay: OpenStay? = null,
    val departure: Departure? = null,
    val driving: Boolean = false,
    /** When he last started walking during a stay (the stay may have ended then). */
    val walkStart: Long = 0,
    val zones: List<Zone> = emptyList(),
    val checks: List<RoadCheck> = emptyList(),
    /** Check keys already run ("commute:2026-10-20"), so each runs once. */
    val ran: Map<String, Long> = emptyMap(),
    val nextId: Long = 1,
    val notes: List<RoadNote> = emptyList(),
    /** Learned trips' routes by "fromPlaceId>toPlaceId". */
    val shapes: List<RouteShape> = emptyList(),
    /** The leased car: fill-ups, odometer readings, the lease (Car.kt). */
    val car: CarData = CarData(),
    val guesses: List<PlaceGuess> = emptyList(),
    val arrivals: List<Arrival> = emptyList(),
) {
    fun shape(from: String?, to: String?): RouteShape? = shapes.firstOrNull { it.key == "$from>$to" }

    /** Adds or replaces a note; ones a day past their end drop off. */
    fun note(n: RoadNote, now: Long): OdysseyData = copy(notes = (notes.filter { it.key != n.key && it.expiresAt > now - 86_400_000L } + n).takeLast(10))

    fun place(id: String?): KnownPlace? = places.firstOrNull { it.id == id }
    fun home(): KnownPlace? = places.firstOrNull { it.kind == Kind.HOME }
    fun work(): KnownPlace? = places.firstOrNull { it.kind == Kind.WORK }
}

object Geo {
    private const val R = 6_371_000.0

    /** Metres between two points (haversine). */
    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    /** Metres from a point to the segment a-b (flat-earth, fine for a city). */
    fun toSegment(lat: Double, lng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val k = cos(Math.toRadians((aLat + bLat) / 2))
        val px = (lng - aLng) * k; val py = lat - aLat
        val bx = (bLng - aLng) * k; val by = bLat - aLat
        val len = bx * bx + by * by
        val t = if (len == 0.0) 0.0 else ((px * bx + py * by) / len).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy) * 111_320.0
    }
}
