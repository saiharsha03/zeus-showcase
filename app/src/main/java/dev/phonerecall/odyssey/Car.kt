package dev.phonerecall.odyssey

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * The car (a leased car, 10,000 miles a year): fill-ups, odometer readings and the lease. Iris finds what
 * she can in his email and owner.db; he is asked only for what is still missing, and nothing is logged
 * without his tap. Sealed with the rest of Odyssey. Amounts show on his own screens only, never on a
 * widget, the lock screen or to the brain (which gets the pace in words).
 */

/** The lease. Each field is null until Iris or he fills it; `fromIris` names the fields she supplied. */
data class Lease(
    val start: String? = null,
    val termMonths: Int? = null,
    /** Miles a year; null reads as his 10,000. */
    val allowance: Int? = null,
    val startOdometer: Int? = null,
    /** Overage in cents a mile; null shows miles only. */
    val overageCents: Int? = null,
    val lessor: String? = null,
    val car: String? = null,
    val signed: String? = null,
    /** The day the car came home: the earliest solid date Iris reported (the carmaker app account, a lease email, the dealer). */
    val delivered: String? = null,
    val fromIris: Set<String> = emptySet(),
    /** Where Iris found each field ("carmaker app welcome email, Jan 15"). */
    val sources: Map<String, String> = emptyMap(),
) {
    val miles: Int get() = allowance ?: 10_000
    fun missing(): List<String> = buildList {
        if (start == null) add("start date"); if (termMonths == null) add("term"); if (startOdometer == null) add("odometer at delivery")
        if (overageCents == null) add("overage per mile")
    }
}

/** One fill-up. Fields fill in as Iris answers; it is logged only when he confirms. */
data class FillUp(
    val id: String = "",
    val at: Long = 0,
    val place: String = "",
    val cents: Long? = null,
    val gallons: Double? = null,
    val pricePerGal: Double? = null,
    /** "Example Bank ••1234": a card name and at most four digits. */
    val card: String? = null,
    val odometer: Int? = null,
    /** open (waiting on him), logged, skipped. */
    val state: String = "open",
    val askId: String? = null,
    val answered: Boolean = false,
)

data class Reading(val at: Long = 0, val miles: Int = 0, val source: String = "typed")

/** Iris asks in flight: request id to what it is about ("fuel:<fill-up id>", "car_lease"). */
data class CarData(
    val lease: Lease = Lease(),
    val fillups: List<FillUp> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val asks: Map<String, String> = emptyMap(),
    /** When the car_lease ask went out, and when Iris answered it (0 = not yet). */
    val leaseAsked: Long = 0,
    val leaseAnswered: Long = 0,
    /** Cards he answered "Got it" or "Not now" to: "lease-setup", "lease:2026-11". */
    val dismissed: Set<String> = emptySet(),
) {
    fun open(): FillUp? = fillups.lastOrNull { it.state == "open" }
    fun lastReading(): Reading? = readings.maxByOrNull { it.at }
}

/** The ask.request envelopes (Argo's shared format) and what comes back. */
object IrisEnvelope {
    private val TIME = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

    fun fuelQuestion(place: String, at: Long, zone: ZoneId): String =
        "Search the owner's email (gas receipts, card alerts) and owner.db (fillups, transactions) for the fill-up at $place around " +
            "${TIME.format(Instant.ofEpochMilli(at).atZone(zone))}: amount, gallons, price/gal, card last-4? " +
            "Reply as JSON {amount, gallons, price_per_gal, card, last4}; null for anything you can't find."

    const val LEASE_QUESTION = "Search the owner's email (lease contract, dealer paperwork, DMV, the lessor's lease emails, " +
        "the carmaker's connected-car app welcome or account-created email) and owner.db (facts, documents, transactions) for his car lease: " +
        "lease start date, term in months, annual mileage allowance, odometer at delivery, overage cost per mile, dealer and lessor, " +
        "car make/model/year, and the lease signing date. The day his connected-car app account was created is about the day " +
        "the car was delivered. Report every date you find with its source. Reply as JSON {start_date, term_months, annual_miles, " +
        "start_odometer, overage_per_mile, dealer, lessor, car, signed_date, delivery_dates: [{date, source}], sources: {field: source}}; " +
        "null for anything you can't find."

    fun request(id: String, topic: String, question: String): String = dev.phonerecall.mind.stateJson.writeValueAsString(
        linkedMapOf("type" to "ask.request", "id" to id, "from" to "athena", "authority" to "read", "topic" to topic, "question" to question, "want_format" to "json"),
    )

    /** A low-authority write Iris applies to her fillups table (she checks it first). No full card number. */
    fun fillupWrite(id: String, f: FillUp, zone: ZoneId): String = dev.phonerecall.mind.stateJson.writeValueAsString(
        linkedMapOf(
            "type" to "write.request", "id" to id, "from" to "athena", "authority" to "low", "topic" to "fuel", "table" to "fillups",
            "row" to linkedMapOf(
                "date" to Instant.ofEpochMilli(f.at).atZone(zone).toLocalDateTime().toString(), "station" to f.place,
                "amount" to f.cents?.let { it / 100.0 }, "gallons" to f.gallons, "price_per_gal" to f.pricePerGal,
                "last4" to f.card?.let { Regex("""\d{4}""").find(it)?.value }, "odometer" to f.odometer,
            ),
        ),
    )

    /** An ask.reply: its id and its answer object (the answer may come as a JSON string). Null for anything else. */
    fun reply(text: String): Pair<String, JsonNode>? {
        val root = runCatching { dev.phonerecall.mind.stateJson.readTree(text.trim()) }.getOrNull() ?: return null
        if (root.path("type").asText() != "ask.reply") return null
        val id = root.path("id").asText().takeIf { it.isNotBlank() } ?: return null
        var a = sequenceOf("answer", "data", "result").map { root.path(it) }.firstOrNull { !it.isMissingNode && !it.isNull } ?: root
        if (a.isTextual) a = runCatching { dev.phonerecall.mind.stateJson.readTree(a.asText()) }.getOrNull() ?: return id to a
        return id to a
    }

    private fun JsonNode.num(vararg k: String): Double? = k.asSequence().map { path(it) }.firstOrNull { it.isNumber || (it.isTextual && it.asText().replace(Regex("[$,]"), "").toDoubleOrNull() != null) }
        ?.let { if (it.isNumber) it.asDouble() else it.asText().replace(Regex("[$,]"), "").toDouble() }
    private fun JsonNode.str(vararg k: String): String? = k.asSequence().map { path(it) }.firstOrNull { it.isTextual && it.asText().isNotBlank() && it.asText() != "null" }?.asText()?.trim()

    /** Fills a fill-up from Iris's answer; what she couldn't find stays as it was. */
    fun fuel(f: FillUp, a: JsonNode): FillUp {
        val last4 = a.str("last4", "card_last4")?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }
        val name = a.str("card", "card_name")?.replace(Regex("""\d{5,}"""), "")?.replace(Regex("""\s*[•*x.]*\d{4}\s*$"""), "")?.trim()?.takeIf { it.isNotBlank() }
        val card = when { name != null && last4 != null -> "$name ••$last4"; last4 != null -> "Card ••$last4"; else -> name }
        return f.copy(
            cents = a.num("amount", "total")?.let { (it * 100).roundToLong() } ?: f.cents,
            gallons = a.num("gallons") ?: f.gallons,
            pricePerGal = a.num("price_per_gal", "ppg", "price") ?: f.pricePerGal,
            card = card ?: f.card,
            answered = true,
        )
    }

    private fun date(s: String?): String? = s?.let { v -> runCatching { LocalDate.parse(v.take(10)).toString() }.getOrNull() }

    /** Fills the lease from Iris's answer, marking each field she gave; a field he typed himself is kept. */
    fun lease(l: Lease, a: JsonNode): Lease {
        val deliveries = a.path("delivery_dates").mapNotNull { n -> date(n.path("date").asText())?.let { it to n.path("source").asText() } }.sortedBy { it.first }
        val mine = l.fromIris.toMutableSet()
        fun <T> take(k: String, cur: T?, iris: T?): T? = if (iris != null && (cur == null || k in l.fromIris)) { mine += k; iris } else cur
        var out = l.copy(
            start = take("start", l.start, date(a.str("start_date"))),
            termMonths = take("termMonths", l.termMonths, a.num("term_months")?.roundToInt()),
            allowance = take("allowance", l.allowance, a.num("annual_miles", "annual_allowance")?.roundToInt()),
            startOdometer = take("startOdometer", l.startOdometer, a.num("start_odometer")?.roundToInt()),
            overageCents = take("overageCents", l.overageCents, a.num("overage_per_mile")?.let { (it * 100).roundToInt() }),
            lessor = take("lessor", l.lessor, listOfNotNull(a.str("lessor"), a.str("dealer")).distinct().joinToString(" / ").ifBlank { null }),
            car = take("car", l.car, a.str("car")),
            signed = take("signed", l.signed, date(a.str("signed_date"))),
            // The earliest solid date: the carmaker app account, a lease email or the dealer paperwork.
            delivered = take("delivered", l.delivered, deliveries.firstOrNull()?.first),
        )
        // No start date in the contract: the lease runs from delivery.
        if (out.start == null && out.delivered != null) { out = out.copy(start = out.delivered); mine += "start" }
        val sources = a.path("sources").fields().asSequence().associate { it.key to it.value.asText() }
        return out.copy(fromIris = mine, sources = l.sources + sources + (deliveries.firstOrNull()?.let { mapOf("delivered" to it.second) } ?: emptyMap()))
    }
}

/** The odometer from a dashboard photo's text (ML Kit on the phone). */
object Odometer {
    private val NUM = Regex("""(?<![\d.:])(\d{1,3}(?:,\d{3})+|\d{4,6})(?![\d.:])""")
    private val NEAR = Regex("""(?i)\b(odo|odometer|mi|miles|total)\b""")
    private val TRIP = Regex("""(?i)\b(trip|range|dte|km|mpg)\b|°""")

    /**
     * The 4-6 digit number next to "mi" or "ODO" (the same line, or the line after the word); else the only
     * plain 4-6 digit number. Trip meters (decimals), times, ranges and temperatures are skipped. With
     * `last`, a number below the last reading or more than 5,000 above it is not believed.
     */
    fun parse(text: String, last: Int? = null): Int? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        data class C(val v: Int, val score: Int)
        val cands = ArrayList<C>()
        for ((i, line) in lines.withIndex()) {
            if (TRIP.containsMatchIn(line)) continue
            for (m in NUM.findAll(line)) {
                val v = m.value.replace(",", "").toIntOrNull() ?: continue
                if (v < 1_000 && !m.value.contains(',')) continue
                val near = NEAR.containsMatchIn(line) || (i > 0 && NEAR.containsMatchIn(lines[i - 1]) && !Regex("""\d""").containsMatchIn(lines[i - 1]))
                cands += C(v, if (near) 2 else 1)
            }
        }
        val ok = cands.filter { c -> last == null || (c.v >= last && c.v - last <= 5_000) }
        val best = ok.maxOfOrNull { it.score } ?: return null
        val top = ok.filter { it.score == best }.map { it.v }.distinct()
        return top.singleOrNull() ?: if (best == 2) top.first() else null
    }
}

/** Lease pace, mpg and cost per mile. Pure. */
object LeasePace {
    data class Pace(
        val month: Int, val term: Int, val miles: Int, val perYear: Int, val allowance: Int,
        /** Projected miles over the allowance at the end, or 0. */
        val over: Int, val overCents: Long?,
    ) {
        val ahead: Boolean get() = perYear > allowance * 1.05
    }

    /**
     * Miles from the odometer readings (ground truth), plus GPS miles from Odyssey's drives after the last
     * reading. Null until the start date, term and starting odometer are known and a reading exists.
     */
    fun pace(l: Lease, readings: List<Reading>, drives: List<DriveReport>, today: LocalDate, zone: ZoneId): Pace? {
        val start = l.start?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val term = l.termMonths ?: return null
        val startOdo = l.startOdometer ?: return null
        val last = readings.maxByOrNull { it.at } ?: return null
        val gps = drives.filter { it.start > last.at }.sumOf { it.meters } / 1609.344
        val miles = (last.miles - startOdo + gps).roundToInt().coerceAtLeast(0)
        val days = ChronoUnit.DAYS.between(start, today).coerceAtLeast(1)
        val perYear = (miles / (days / 365.25)).roundToInt()
        val month = (ChronoUnit.MONTHS.between(start, today) + 1).toInt().coerceIn(1, term)
        val over = (perYear * term / 12.0 - l.miles * term / 12.0).roundToInt().coerceAtLeast(0)
        return Pace(month, term, miles, perYear, l.miles, over, l.overageCents?.let { over.toLong() * it })
    }

    /** Miles per gallon between fill-ups with odometers (full tanks): the latest, and the average before it. */
    fun mpg(fills: List<FillUp>): Pair<Double, Double?>? {
        val f = fills.filter { it.state == "logged" && it.odometer != null }.sortedBy { it.at }
        val each = f.zipWithNext().mapNotNull { (a, b) -> b.gallons?.takeIf { it > 0 }?.let { g -> (b.odometer!! - a.odometer!!) / g }?.takeIf { it in 5.0..80.0 } }
        val latest = each.lastOrNull() ?: return null
        return latest to each.dropLast(1).takeIf { it.isNotEmpty() }?.average()
    }

    /** Fuel cents a mile over the logged fill-ups with odometers. */
    fun centsPerMile(fills: List<FillUp>): Double? {
        val f = fills.filter { it.state == "logged" && it.odometer != null }.sortedBy { it.at }
        if (f.size < 2) return null
        val miles = f.last().odometer!! - f.first().odometer!!
        val cents = f.drop(1).mapNotNull { it.cents }.sum()
        return if (miles > 0 && cents > 0) cents.toDouble() / miles else null
    }

    private fun n(v: Int) = "%,d".format(Locale.US, v)

    /** "Month 8/36 · 7,450 mi" and "Pace 11,200/yr → ~3,600 over (~$540)". */
    fun words(p: Pace): Pair<String, String> {
        val title = "Month ${p.month}/${p.term} · ${n(p.miles)} mi"
        val body = "Pace ${n(round100(p.perYear))}/yr" + when {
            p.over > 0 -> " → ~${n(round100(p.over))} over" + (p.overCents?.let { " (~$${n(((it / 100.0) / 10).roundToInt() * 10)})" } ?: "")
            else -> ", within the ${n(p.allowance)} a year"
        }
        return title to body
    }

    /** For the brain: words only, no amounts. "Car lease: on pace ~11k mi/yr vs 10k allowed". */
    fun brainLine(p: Pace): String = "Car lease: on pace ~${k(p.perYear)} mi/yr vs ${k(p.allowance)} allowed (month ${p.month} of ${p.term})"

    private fun k(v: Int) = if (v >= 1000) "${(v / 100.0).roundToInt() / 10.0}".removeSuffix(".0") + "k" else "$v"
    private fun round100(v: Int) = ((v / 100.0).roundToInt() * 100)
}

/** The car's milestones for Chronos: the delivery day (Iris's earliest solid date) and the lease signing. */
object CarMilestones {
    fun of(d: OdysseyData): List<dev.phonerecall.data.Milestone> {
        val l = d.car.lease
        val car = l.car ?: "Car"
        fun day(s: String?) = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return listOfNotNull(
            day(l.delivered)?.let { dev.phonerecall.data.Milestone(it, "$car delivered", l.sources["delivered"]?.let { s -> "From Iris: $s" } ?: "Odyssey") },
            day(l.signed)?.takeIf { it != day(l.delivered) }?.let { dev.phonerecall.data.Milestone(it, "$car lease signed", if ("signed" in l.fromIris) "From Iris" else "Odyssey") },
        )
    }
}

/** A stop at the pump: from the place's kind or a fuel payment during a stay. Pure. */
object FuelStop {
    private val WORDS = Regex("""(?i)\b(gas station|gasoline|fuel|costco gas|shell|sunoco|speedway|kwik ?fill|mobil|exxon|citgo|bp|wawa|sheetz|circle k|marathon|valero)\b""")
    private const val H = 3_600_000L

    /** A payment or receipt line that is about fuel. */
    fun isFuelText(text: String): Boolean =
        Naming.merchant(text)?.let { Naming.kind(it) == Kind.FUEL } == true || (WORDS.containsMatchIn(text) && Naming.amount(text) != null)

    fun id(stayStart: Long) = "f$stayStart"

    /** A new open fill-up for the stay, or null when this stay already has one. */
    fun open(car: CarData, stayStart: Long, place: String, askId: String): CarData? {
        val id = id(stayStart)
        if (car.fillups.any { it.id == id }) return null
        val f = FillUp(id, stayStart, place, askId = askId)
        return car.copy(fillups = (car.fillups + f).takeLast(200), asks = car.asks + (askId to "fuel:$id"))
    }

    /** Moneta's bank alert for the same stop (a Fuel spend, or a fuel merchant, within two hours) fills the amount and card. */
    fun fromMoneta(f: FillUp, txns: List<dev.phonerecall.moneta.Txn>): FillUp {
        val t = txns.filter { it.kind.spend && kotlin.math.abs(it.at - f.at) <= 2 * H && (it.category == dev.phonerecall.moneta.Category.FUEL || Naming.kind(it.merchant) == Kind.FUEL || WORDS.containsMatchIn(it.merchant)) }
            .minByOrNull { kotlin.math.abs(it.at - f.at) } ?: return f
        val card = t.last4?.let { "${t.bank ?: "Card"} ••$it" }
        return f.copy(cents = f.cents ?: t.cents, card = f.card ?: card)
    }

    /** The Moneta row this fill-up is, when the bank alert already logged it: same amount, within two hours. */
    fun match(f: FillUp, txns: List<dev.phonerecall.moneta.Txn>): dev.phonerecall.moneta.Txn? = f.cents?.let { c ->
        txns.filter { it.kind.spend && it.cents == c && kotlin.math.abs(it.at - f.at) <= 2 * H }.minByOrNull { kotlin.math.abs(it.at - f.at) }
    }

    /** "$42.10 · 11.2 gal · Example Bank ••1234", what is known so far. */
    fun line(f: FillUp): String? = listOfNotNull(
        f.cents?.let { "$" + "%.2f".format(Locale.US, it / 100.0) },
        f.gallons?.let { "%.1f gal".format(Locale.US, it) },
        f.pricePerGal?.takeIf { f.gallons == null }?.let { "$" + "%.3f/gal".format(Locale.US, it) },
        f.card,
    ).joinToString(" · ").ifBlank { null }
}
