package dev.phonerecall.argo

import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Airports and flight numbers: where a code is, which zone its clocks run in, and how long a flight between two of
 * them can plausibly take. Time repair (Evidence.kt) and journey assembly (Assemble.kt) lean on this. Pure.
 */

data class Airport(val code: String, val city: String, val zone: String, val lat: Double, val lng: Double)

object Airports {
    private fun a(code: String, city: String, zone: String, lat: Double, lng: Double) = Airport(code, city, zone, lat, lng)
    private const val ET = "America/New_York"; private const val CT = "America/Chicago"; private const val MT = "America/Denver"; private const val PT = "America/Los_Angeles"

    val ALL: List<Airport> = listOf(
        a("BUF", Cities.HOME, ET, 42.94, -78.73), a("ROC", "Rochester", ET, 43.12, -77.67), a("SYR", "Syracuse", ET, 43.11, -76.11),
        a("ALB", "Albany", ET, 42.75, -73.80), a("JFK", "New York", ET, 40.64, -73.78), a("LGA", "New York", ET, 40.78, -73.87),
        a("EWR", "New York", ET, 40.69, -74.17), a("BOS", "Boston", ET, 42.36, -71.01), a("PHL", "Philadelphia", ET, 39.87, -75.24),
        a("BWI", "Baltimore", ET, 39.18, -76.67), a("DCA", "Washington", ET, 38.85, -77.04), a("IAD", "Washington", ET, 38.95, -77.46),
        a("CLT", "Charlotte", ET, 35.21, -80.94), a("RDU", "Raleigh", ET, 35.88, -78.79), a("ATL", "Atlanta", ET, 33.64, -84.43),
        a("MIA", "Miami", ET, 25.79, -80.29), a("FLL", "Fort Lauderdale", ET, 26.07, -80.15), a("MCO", "Orlando", ET, 28.43, -81.31),
        a("TPA", "Tampa", ET, 27.98, -82.53), a("DTW", "Detroit", "America/Detroit", 42.21, -83.35), a("CLE", "Cleveland", ET, 41.41, -81.85),
        a("PIT", "Pittsburgh", ET, 40.49, -80.23), a("CMH", "Columbus", ET, 39.998, -82.89), a("CVG", "Cincinnati", ET, 39.05, -84.67),
        a("IND", "Indianapolis", "America/Indiana/Indianapolis", 39.72, -86.29), a("BNA", "Nashville", CT, 36.12, -86.68),
        a("ORD", "Chicago", CT, 41.98, -87.90), a("MDW", "Chicago", CT, 41.79, -87.75), a("MSP", "Minneapolis", CT, 44.88, -93.22),
        a("STL", "St. Louis", CT, 38.75, -90.37), a("MCI", "Kansas City", CT, 39.30, -94.71), a("MSY", "New Orleans", CT, 29.99, -90.26),
        a("DFW", "Dallas", CT, 32.90, -97.04), a("IAH", "Houston", CT, 29.99, -95.34), a("AUS", "Austin", CT, 30.19, -97.67),
        a("DEN", "Denver", MT, 39.86, -104.67), a("SLC", "Salt Lake City", MT, 40.79, -111.98), a("BOI", "Boise", "America/Boise", 43.56, -116.22),
        a("PHX", "Phoenix", "America/Phoenix", 33.43, -112.01), a("LAS", "Las Vegas", PT, 36.08, -115.15), a("SEA", "Seattle", PT, 47.45, -122.31),
        a("PDX", "Portland", PT, 45.59, -122.60), a("GEG", "Spokane", PT, 47.62, -117.53), a("SFO", "San Francisco", PT, 37.62, -122.38),
        a("LAX", "Los Angeles", PT, 33.94, -118.41), a("SAN", "San Diego", PT, 32.73, -117.19), a("HNL", "Honolulu", "Pacific/Honolulu", 21.32, -157.92),
        a("ANC", "Anchorage", "America/Anchorage", 61.17, -149.99), a("YYZ", "Toronto", "America/Toronto", 43.68, -79.63),
        a("YUL", "Montreal", "America/Toronto", 45.47, -73.74), a("YVR", "Vancouver", "America/Vancouver", 49.19, -123.18),
        a("MEX", "Mexico City", "America/Mexico_City", 19.44, -99.07), a("CUN", "Cancun", "America/Cancun", 21.04, -86.87),
        a("LHR", "London", "Europe/London", 51.47, -0.45), a("CDG", "Paris", "Europe/Paris", 49.01, 2.55), a("FRA", "Frankfurt", "Europe/Berlin", 50.03, 8.56),
        a("AMS", "Amsterdam", "Europe/Amsterdam", 52.31, 4.76), a("DXB", "Dubai", "Asia/Dubai", 25.25, 55.36), a("DOH", "Doha", "Asia/Qatar", 25.27, 51.61),
        a("HYD", "Hyderabad", "Asia/Kolkata", 17.24, 78.43), a("BLR", "Bengaluru", "Asia/Kolkata", 13.20, 77.71), a("MAA", "Chennai", "Asia/Kolkata", 12.99, 80.17),
        a("BOM", "Mumbai", "Asia/Kolkata", 19.09, 72.87), a("DEL", "Delhi", "Asia/Kolkata", 28.56, 77.10), a("SIN", "Singapore", "Asia/Singapore", 1.36, 103.99),
        a("NRT", "Tokyo", "Asia/Tokyo", 35.77, 140.39), a("HND", "Tokyo", "Asia/Tokyo", 35.55, 139.78),
    )
    private val BY_CODE = ALL.associateBy { it.code }
    /** A city's main airport, for its zone and distances ("Chicago" is ORD). */
    private val BY_CITY = ALL.groupBy { it.city.lowercase() }.mapValues { it.value.first() }
    private val CODE = Regex("\\b([A-Z]{3})\\b")

    fun of(code: String): Airport? = BY_CODE[code.uppercase()]

    /** The airport code in "Buffalo BUF", "Chicago (ORD)" or "ORD"; capitals only, so "den" in a word is not Denver. */
    fun code(text: String?): String? = text?.let { t -> CODE.findAll(t).map { it.value }.firstOrNull { it in BY_CODE } }

    /** The airport a leg's end names: its code, else the city's main one. */
    fun find(text: String?): Airport? {
        if (text.isNullOrBlank()) return null
        code(text)?.let { return BY_CODE[it] }
        return Cities.city(text)?.let { BY_CITY[it.lowercase()] }
    }

    fun city(city: String): Airport? = BY_CITY[city.lowercase()]

    /** The zone a leg's end keeps its clocks in, or null when the place is unknown. */
    fun zoneOf(text: String?): ZoneId? = find(text)?.let { ZoneId.of(it.zone) }

    /** A city's zone, for a trip's destination. */
    fun zoneOfCity(city: String?): String? = city?.let { BY_CITY[it.lowercase()]?.zone }

    /** "Chicago (ORD)": the city with its code, the way legs are shown. */
    fun label(a: Airport) = "${a.city} (${a.code})"

    fun km(a: Airport, b: Airport): Double {
        val r = Math.PI / 180
        val h = sin((b.lat - a.lat) * r / 2).let { it * it } + cos(a.lat * r) * cos(b.lat * r) * sin((b.lng - a.lng) * r / 2).let { it * it }
        return 2 * 6371 * asin(sqrt(h))
    }

    /** The shortest and longest a flight between them can take, in minutes: 900 km/h plus a quarter hour, to 400 km/h plus three hours. */
    fun flightMinutes(a: Airport, b: Airport): LongRange = km(a, b).let { d -> (d / 900 * 60 + 15).toLong()..(d / 400 * 60 + 180).toLong() }
}

/** A flight number and route from an event title or Iris's words: "Flight AA 101: BUF to ATL", "Delta Flight 606: DEN to BUF". */
object Flights {
    private val NAMES = linkedMapOf(
        "american" to "AA", "delta" to "DL", "united" to "UA", "jetblue" to "B6", "alaska" to "AS", "southwest" to "WN", "frontier" to "F9",
        "spirit" to "NK", "air canada" to "AC", "air india" to "AI", "british airways" to "BA", "lufthansa" to "LH", "emirates" to "EK",
        "qatar" to "QR", "hawaiian" to "HA", "allegiant" to "G4", "sun country" to "SY", "breeze" to "MX",
    )
    private val CODES = NAMES.values.toSet()
    private val NUM = Regex("\\b(" + CODES.joinToString("|") + ")\\s?(\\d{1,4})\\b")
    private val NAMED = Regex("(?i)\\b(" + NAMES.keys.joinToString("|") + ")(?:\\s+air(?:lines|ways)?)?\\s+(?:flight\\s+)?#?(\\d{1,4})\\b")
    private val ROUTE = Regex("\\b([A-Z]{3})\\s*(?:to|TO|→|->|–|-|/)\\s*([A-Z]{3})\\b")

    /** ("AA", "101"), or null. */
    fun number(text: String?): Pair<String, String>? {
        if (text.isNullOrBlank()) return null
        NUM.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        NAMED.find(text)?.let { return NAMES.getValue(it.groupValues[1].lowercase()) to it.groupValues[2] }
        return null
    }

    /** ("BUF", "ORD") when both are known airports. */
    fun route(text: String?): Pair<String, String>? = text?.let { t ->
        ROUTE.findAll(t).firstOrNull { Airports.of(it.groupValues[1]) != null && Airports.of(it.groupValues[2]) != null }?.let { it.groupValues[1] to it.groupValues[2] }
    }

    fun carrierCode(carrier: String): String = carrier.trim().let { c -> if (c.uppercase() in CODES) c.uppercase() else NAMES.entries.firstOrNull { c.lowercase().startsWith(it.key) }?.value ?: c }

    /** The digits of a flight number, for codeshares: "AA 303" and "AS 303" are the same plane on the same route. */
    fun digits(number: String): String? = Regex("(\\d{1,4})\\s*$").find(number.trim())?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }

    fun carrierOf(number: String): String = number.trim().substringBefore(' ').let { if (it.uppercase() in CODES) it.uppercase() else "" }
}
