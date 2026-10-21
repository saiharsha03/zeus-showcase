package dev.phonerecall.odyssey

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The nearest point of interest TomTom knows at a stop: its name, street and what kind of place it is. */
data class PoiHit(val name: String? = null, val street: String? = null, val kind: Kind = Kind.OTHER, val meters: Double = 0.0)

/**
 * Naming every stop: TomTom's nearby search (and its reverse geocode for the street) at the stop's fix. Only
 * the latitude and longitude go out, with his key from Calypso; nothing about him. Offline, or without the
 * key, the Geocoder's street names it ("Near Maple Ave") until a receipt or he names it better.
 */
object Poi {
    /** A POI farther than this from the fix is someone else's door. */
    const val NEAR_M = 80.0

    private fun p(v: Double) = "%.5f".format(Locale.US, v)
    private fun k(key: String) = java.net.URLEncoder.encode(key, "UTF-8")

    /** Names that only say what the place is, not which one. */
    private val GENERIC = setOf("car wash", "gas station", "petrol station", "gas", "parking", "parking lot", "restaurant", "cafe", "grocery", "supermarket", "pharmacy", "gym", "atm")

    fun nearbyUrl(lat: Double, lng: Double, key: String) =
        "https://${TomTom.HOST}/search/2/nearbySearch/.json?lat=${p(lat)}&lon=${p(lng)}&radius=${NEAR_M.toInt()}&limit=5&key=${k(key)}"

    fun reverseUrl(lat: Double, lng: Double, key: String) = "https://${TomTom.HOST}/search/2/reverseGeocode/${p(lat)},${p(lng)}.json?key=${k(key)}"

    /** Nearby results, nearest first, within [NEAR_M]. */
    fun parseNearby(json: String): List<PoiHit> = runCatching {
        dev.phonerecall.mind.stateJson.readTree(json).path("results").mapNotNull { r ->
            val poi = r.path("poi")
            val name = poi.path("brands").firstOrNull()?.path("name")?.asText()?.takeIf { it.isNotBlank() } ?: poi.path("name").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val words = poi.path("categories").map { it.asText() } + poi.path("classifications").map { it.path("code").asText() }
            PoiHit(name, r.path("address").path("streetName").asText().takeIf { it.isNotBlank() }, category(words, name), r.path("dist").asDouble(0.0))
        }.filter { it.meters <= NEAR_M }.sortedBy { it.meters }
    }.getOrDefault(emptyList())

    fun parseStreet(json: String): String? = runCatching {
        dev.phonerecall.mind.stateJson.readTree(json).path("addresses").firstOrNull()?.path("address")?.path("streetName")?.asText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** TomTom's category words and codes onto Odyssey's kinds: grocery, fuel, car wash, food, fitness, shopping, health, other. */
    fun category(words: List<String>, name: String): Kind {
        val w = words.joinToString(" ").lowercase().replace('_', ' ')
        return when {
            "car wash" in w -> Kind.CAR_WASH
            "petrol" in w || "gas station" in w || "fuel" in w -> Kind.FUEL
            "grocer" in w || "supermarket" in w || "hypermarket" in w || "market" in w -> Kind.GROCERY
            "fitness" in w || "gym" in w || "sports center" in w -> Kind.GYM
            "cafe" in w || "coffee" in w -> Kind.CAFE
            "restaurant" in w || "fast food" in w || "food" in w -> Kind.RESTAURANT
            "hospital" in w || "doctor" in w || "dentist" in w || "pharmacy" in w || "clinic" in w || "health" in w -> Kind.HEALTH
            "place of worship" in w || "temple" in w || "church" in w -> Kind.TEMPLE
            "shop" in w || "store" in w || "mall" in w || "retail" in w -> Kind.SHOP
            else -> Naming.kind(name)
        }
    }

    /**
     * "Wegmans (Cedar St)"; a place with no name of its own takes its kind: "Car wash on Maple Ave".
     * Null when TomTom had nothing near.
     */
    fun label(hit: PoiHit?, street: String?): String? {
        val h = hit ?: return null
        val name = h.name?.trim()?.takeIf { it.isNotEmpty() }
        val at = h.street ?: street
        val generic = name == null || name.equals(h.kind.label, true) || name.lowercase() in GENERIC
        return when {
            !generic -> name + (at?.let { " ($it)" } ?: "")
            h.kind != Kind.OTHER -> h.kind.label + (at?.let { " on $it" } ?: "")
            else -> name
        }
    }
}

/** Every stop of a few minutes as a visit, in words for Clio, Athena, Moneta and the drive report. */
object Stops {
    /** The name for words outside Odyssey's own screens: no street ("Wegmans", "a car wash"); null for unnamed spots. */
    fun plain(p: KnownPlace): String? = when {
        p.kind == Kind.HOME -> "home"
        p.kind == Kind.WORK -> "the office"
        p.source == NameSource.NONE || p.source == NameSource.GEOCODER -> null
        p.name.contains(" on ") && p.source == NameSource.POI -> "a ${p.kind.label.lowercase()}"
        else -> p.name.substringBefore(" (").trim().takeIf { it.isNotEmpty() }
    }

    /** "Wegmans 18 min". */
    fun line(v: Visit, p: KnownPlace): String = "${p.name} ${v.minutes.coerceAtLeast(1)} min"

    /** Today's stops for Athena ("Stopped at Wegmans 18 min today"), home and the office left out; the last three. */
    fun facts(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return d.visits.filter { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == day }.sortedBy { it.start }.mapNotNull { v ->
            val p = d.place(v.placeId)?.takeIf { it.kind != Kind.HOME && it.kind != Kind.WORK } ?: return@mapNotNull null
            plain(p)?.let { "Stopped at $it ${v.minutes.coerceAtLeast(1)} min today" }
        }.takeLast(3)
    }

    /** Where he was at `at` (a visit covering it, give or take 5 minutes, or the open stay), for Moneta's charges. */
    fun placeAt(d: OdysseyData, at: Long): String? {
        val slack = 5 * 60_000L
        val v = d.visits.lastOrNull { at in (it.start - slack)..(it.end + slack) }
        if (v != null) return d.place(v.placeId)?.name
        val s = d.stay ?: return null
        if (at < s.start - slack) return null
        return d.places.filter { Geo.meters(it.lat, it.lng, s.lat, s.lng) < Visits.SAME_PLACE_M }.minByOrNull { Geo.meters(it.lat, it.lng, s.lat, s.lng) }?.name ?: s.poiName
    }

    /** The stop a drive ended at (a visit starting within 15 minutes of its end): "Wegmans, 18 min". */
    fun after(d: OdysseyData, r: DriveReport): String? {
        val v = d.visits.firstOrNull { it.start in (r.end - 5 * 60_000L)..(r.end + 15 * 60_000L) } ?: return null
        val p = d.place(v.placeId) ?: return null
        return "${p.name}, ${v.minutes.coerceAtLeast(1)} min"
    }
}

