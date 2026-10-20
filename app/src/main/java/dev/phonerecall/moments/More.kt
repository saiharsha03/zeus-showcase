package dev.phonerecall.moments

import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Kairos's second set of moments: text read from a photo or a screen (a parking sign, a flight, an
 * event), copied text (an address, a tracking number), receipts, Buffalo mornings, trash night,
 * staples running low, charging before bed, the usual call home, and grocery aliases. All pure.
 */

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** Dates and times as printed on tickets, confirmations and flyers. */
object When {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val WORD_DATE = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b")
    private val DAY_FIRST = Regex("(?i)\\b(\\d{1,2})\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?(?:\\s+(\\d{4}))?\\b")
    private val SLASH = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
    private val CLOCK = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?\\s?m\\b\\.?|\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")

    /** The first date in the text; a date without a year is the next one on or after today. */
    fun date(text: String, today: LocalDate): LocalDate? {
        fun fix(m: Int, d: Int, y: String?): LocalDate? {
            val year = y?.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val base = runCatching { LocalDate.of(year ?: today.year, m, d) }.getOrNull() ?: return null
            return if (year == null && base.isBefore(today)) base.plusYears(1) else base
        }
        val hits = listOfNotNull(
            WORD_DATE.find(text)?.let { it.range.first to fix(MONTHS.indexOf(it.groupValues[1].lowercase().take(3)) + 1, it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { null }) },
            DAY_FIRST.find(text)?.let { it.range.first to fix(MONTHS.indexOf(it.groupValues[2].lowercase().take(3)) + 1, it.groupValues[1].toInt(), it.groupValues[3].ifEmpty { null }) },
            SLASH.find(text)?.let { it.range.first to fix(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { null }) },
        )
        return hits.filter { it.second != null }.minByOrNull { it.first }?.second
    }

    fun time(text: String): LocalTime? {
        val m = CLOCK.find(text) ?: return null
        return if (m.groupValues[3].isNotEmpty()) {
            val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].lowercase() == "p") 12 else 0
            runCatching { LocalTime.of(h, m.groupValues[2].ifEmpty { "0" }.toInt()) }.getOrNull()
        } else LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt())
    }
}

/** A photo or a screen read as an errand: a parking sign, a flight confirmation, an event. */
object ScreenIntents {
    private val SIGN = Regex("(?i)\\b(\\d)\\s*(?:hr|hour|h)\\.?\\s*(?:parking|limit)|parking\\s*(?:limit(?:ed)?(?:\\s+to)?)?\\s*:?\\s*(\\d)\\s*(?:hr|hour)s?\\b")
    private val FLIGHT = Regex("(?i)\\bflight\\s*(?:no\\.?|number|#)?\\s*:?\\s*\\b([A-Z]{2}|[A-Z]\\d|\\d[A-Z])\\s?(\\d{1,4})\\b")
    private val ROUTE = Regex("\\b([A-Z]{3})\\s*(?:→|->|-|to|–)\\s*([A-Z]{3})\\b")
    private val EVENT = Regex("(?i)\\b(tickets?|admission|doors open|join us|you'?re invited|rsvp|concert|show|festival|party|celebration|gala|event|registration)\\b")

    fun read(raw: String, now: ZonedDateTime, airportMinutes: Int? = null): MomentPlan? {
        val text = CookParser.normalize(raw)
        sign(text)?.let { return MomentPlans.meter(it * 60).copy(kind = MomentKind.SIGN, title = "Parking sign: $it h limit") }
        flight(text, now, airportMinutes)?.let { return it }
        return event(text, now)
    }

    fun sign(text: String): Int? = SIGN.find(text)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }?.takeIf { it in 1..4 }

    private fun at(text: String, now: ZonedDateTime): ZonedDateTime? {
        val d = When.date(text, now.toLocalDate()) ?: return null
        val t = When.time(text) ?: return null
        return d.atTime(t).atZone(now.zone).takeIf { it.isAfter(now) }
    }

    private fun reminder(at: ZonedDateTime, now: ZonedDateTime, text: String): RawStep? =
        if (at.isAfter(now.plusMinutes(1))) RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to ISO.format(at), "text" to text)) else null

    /** The event, a check-in reminder 24 h before, and leave-by 2.5 h before (minus the drive to the airport when Odyssey knows it). */
    fun flight(text: String, now: ZonedDateTime, airportMinutes: Int?): MomentPlan? {
        val f = FLIGHT.find(text) ?: return null
        val departs = at(text, now) ?: return null
        val code = "${f.groupValues[1].uppercase()} ${f.groupValues[2]}"
        val route = ROUTE.find(text)?.let { " ${it.groupValues[1]}→${it.groupValues[2]}" }.orEmpty()
        val leave = departs.minusMinutes(150L + (airportMinutes ?: 0))
        val steps = listOfNotNull(
            RawStep(ProposalKind.ADD_CALENDAR_EVENT, mapOf("title" to "Flight $code$route", "start" to ISO.format(departs), "end" to ISO.format(departs.plusHours(2)))),
            reminder(departs.minusHours(24), now, "Check in for $code"),
            reminder(leave, now, "Leave for the airport ($code at ${TIME.format(departs)})"),
        )
        return MomentPlan(MomentKind.FLIGHT, "Flight $code, ${DAY_TIME.format(departs)}", steps)
    }

    /** A flyer, ticket or invite: the event, and a leave-by 30 minutes before. */
    fun event(text: String, now: ZonedDateTime): MomentPlan? {
        if (!EVENT.containsMatchIn(text)) return null
        val start = at(text, now) ?: return null
        val title = text.lines().map { it.trim() }.firstOrNull { l ->
            l.count { it.isLetter() } >= 4 && When.date(l, now.toLocalDate()) == null && When.time(l) == null && !Regex("(?i)^(tickets?|admission|rsvp|doors)").containsMatchIn(l)
        }?.take(60) ?: "Event"
        val steps = listOfNotNull(
            RawStep(ProposalKind.ADD_CALENDAR_EVENT, mapOf("title" to title, "start" to ISO.format(start))),
            reminder(start.minusMinutes(30), now, "Leave for $title"),
        )
        return MomentPlan(MomentKind.EVENT, "$title, ${DAY_TIME.format(start)}", steps)
    }
}

/** Copied text, read only while Zeus is on screen (the assist sheet). */
sealed class Clip {
    data class Address(val text: String, val leaveAt: ZonedDateTime? = null) : Clip() {
        val mapsUri: String get() = "geo:0,0?q=" + java.net.URLEncoder.encode(text, "UTF-8")
    }
    data class Package(val carrier: String, val number: String) : Clip() {
        /** Opens in the browser (another app); Zeus fetches nothing. */
        val url: String get() = when (carrier) {
            "UPS" -> "https://www.ups.com/track?tracknum=$number"
            "USPS" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$number"
            "FedEx" -> "https://www.fedex.com/fedextrack/?trknbr=$number"
            else -> "https://track.amazon.com/tracking/$number"
        }
    }
}

object Clips {
    private val UPS = Regex("\\b1Z[0-9A-Z]{16}\\b")
    private val AMAZON = Regex("\\bTBA\\d{12}\\b")
    private val USPS = Regex("\\b(9[2-5]\\d{20}|9[2-5]\\d{18})\\b")
    private val FEDEX = Regex("\\b(\\d{12}|\\d{15})\\b")
    private val ADDRESS = Regex(
        "(?i)\\b\\d{1,6}\\s+(?:[NSEW]\\.?\\s+)?[A-Za-z0-9.' ]{2,40}?\\s(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Parkway|Hwy|Highway|Ter|Terrace|Cir|Circle)\\b\\.?" +
            "(?:,?\\s+[A-Za-z .]{2,30})?(?:,\\s*[A-Z]{2})?(?:\\s+\\d{5})?",
    )

    fun read(raw: String, now: ZonedDateTime): Clip? {
        val text = raw.trim().take(500)
        if (text.isEmpty()) return null
        UPS.find(text)?.let { return Clip.Package("UPS", it.value) }
        AMAZON.find(text)?.let { return Clip.Package("Amazon", it.value) }
        USPS.find(text)?.let { return Clip.Package("USPS", it.value) }
        if (text.length <= 20) FEDEX.find(text)?.let { return Clip.Package("FedEx", it.value) }
        ADDRESS.find(text)?.let { a ->
            // "Dinner at 7 PM, 123 Main St": a leave-by 30 minutes before.
            val t = When.time(text)?.let { now.toLocalDate().atTime(it).atZone(now.zone) }?.takeIf { it.isAfter(now.plusMinutes(30)) }
            return Clip.Address(a.value.trim().trimEnd(',', '.'), t?.minusMinutes(30))
        }
        return null
    }

    fun leavePlan(a: Clip.Address): MomentPlan? = a.leaveAt?.let {
        MomentPlan(MomentKind.EVENT, "Leave by ${TIME.format(it)} for ${a.text}", listOf(RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to ISO.format(it), "text" to "Leave for ${a.text}"))))
    }
}

/** A receipt photo read into Moneta's manual entry. He confirms (and can fix) the merchant and amount. */
data class ReceiptDraft(val merchant: String, val cents: Long, val date: LocalDate?)

object Receipts {
    private val TOTAL = Regex("(?im)^\\s*(?:grand\\s+|order\\s+)?total\\b(?!\\s*(?:savings|items?|tax|saved))[^\\d\\n]{0,12}\\$?\\s*(\\d{1,5}[.,]\\d{2})")
    private val CUE = Regex("(?i)\\b(subtotal|tax|visa|mastercard|debit|change due|cash|receipt|amex|approved)\\b")
    private val SKIP = Regex("(?i)receipt|welcome|thank|store\\s*#|^\\d|www\\.|tel|phone")

    fun read(text: String, today: LocalDate): ReceiptDraft? {
        if (!CUE.containsMatchIn(text)) return null
        val total = TOTAL.findAll(text).lastOrNull() ?: return null
        val cents = (total.groupValues[1].replace(',', '.').toDouble() * 100).roundToInt().toLong()
        if (cents <= 0) return null
        val merchant = text.lines().map { it.trim() }.firstOrNull { it.count(Char::isLetter) >= 3 && !SKIP.containsMatchIn(it) }?.take(40) ?: return null
        val date = When.date(text, today.minusYears(1))?.takeIf { !it.isAfter(today) }
        return ReceiptDraft(merchant.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }, cents, date)
    }
}

/** For the bedtime and alarm code: snow or ice before tomorrow's first commute. */
data class MorningHazard(val words: String, val extraMinutes: Int, val departure: ZonedDateTime) {
    val advice: String get() = "Leave $extraMinutes min early, scrape the car"
}

fun interface MorningHazardSource { fun tomorrow(now: ZonedDateTime): MorningHazard? }

object MorningHazards {
    private val SNOW = setOf(71, 73, 75, 77, 85, 86)
    private val ICE = setOf(56, 57, 66, 67)

    /** Hours from three hours before the departure up to it. Snow, freezing rain or drizzle, or rain at or below 0°C. */
    fun at(hours: List<HourCast>, departure: ZonedDateTime): MorningHazard? {
        val to = departure.toInstant().toEpochMilli()
        val window = hours.filter { it.at in (to - 3 * 3_600_000L)..to }
        val ice = window.any { it.code in ICE || (it.precipMm > 0 && (it.tempC ?: 9.0) <= 0.0 && it.code !in SNOW) }
        val snow = window.any { it.code in SNOW || it.snowCm > 0 }
        val words = when { ice -> "Freezing rain"; snow -> "Snow"; else -> return null }
        return MorningHazard("$words before your ${TIME.format(departure)} commute", 15, departure)
    }
}

/** The forecast hour Kairos needs (Aeolia's Hour, without Compose). */
data class HourCast(val at: Long, val tempC: Double?, val code: Int, val precipMm: Double, val snowCm: Double)

/** A Groceries line he added, and when he ticked it bought. */
data class ListEntry(val text: String, val addedAt: Long, val boughtAt: Long?, val open: Boolean)

data class StapleDue(val name: String, val everyDays: Int, val last: Long)

object Staples {
    private const val DAY = 86_400_000L

    /** Items he bought at least 3 times at a steady pace (spread under 35% of the mean), due again, and not on the list now. */
    fun due(entries: List<ListEntry>, aliases: Aliases, now: Long): List<StapleDue> {
        val byName = entries.groupBy { aliases.canonical(it.text) }
        return byName.mapNotNull { (name, es) ->
            if (es.any { it.open }) return@mapNotNull null
            val bought = es.mapNotNull { it.boughtAt }.sorted()
            if (bought.size < 3) return@mapNotNull null
            val gaps = bought.zipWithNext { a, b -> (b - a).toDouble() / DAY }
            val mean = gaps.average()
            if (mean < 2) return@mapNotNull null
            val sd = sqrt(gaps.map { (it - mean) * (it - mean) }.average())
            if (sd / mean > 0.35) return@mapNotNull null
            if (now < bought.last() + ((mean - 1) * DAY).toLong()) return@mapNotNull null
            StapleDue(name, mean.roundToInt(), bought.last())
        }.sortedBy { it.last }
    }
}

/**
 * Grocery aliases, learned only from his own corrections: "kandi pappu" = toor dal = "Laxmi Toor Dal 4lb".
 * `map` is alias (lower case) to the name he chose.
 */
data class Aliases(val map: Map<String, String> = emptyMap()) {
    fun canonical(name: String): String {
        val k = key(name)
        return map[k] ?: k
    }

    /** He changed `from` to `to`: both now mean `to`, and so does anything that meant `from`. */
    fun learn(from: String, to: String): Aliases {
        val f = key(from); val t = key(to)
        if (f.isEmpty() || t.isEmpty() || f == t) return this
        val target = map[t] ?: t
        return Aliases(map.mapValues { (_, v) -> if (v == f) target else v } + (f to target))
    }

    companion object {
        fun key(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

        /** A confirmed list edit: the same number of items, changed ones pair up in order. */
        fun corrections(before: List<String>, after: List<String>): List<Pair<String, String>> =
            if (before.size != after.size) emptyList() else before.zip(after).filter { (a, b) -> key(a) != key(b) }
    }
}

/** The usual call home: a weekday and time he has called family on in at least 3 of the last 6 weeks. */
data class CallSlot(val day: DayOfWeek, val minute: Int)

object CallHabit {
    fun slot(calls: List<Long>, now: Long, zone: ZoneId): CallSlot? {
        val recent = calls.filter { now - it in 0..42 * 86_400_000L }.map { Instant.ofEpochMilli(it).atZone(zone) }
        return recent.groupBy { it.dayOfWeek }.mapNotNull { (day, cs) ->
            val weeks = cs.map { it.toLocalDate().toEpochDay() / 7 }.toSet().size
            if (weeks < 3) return@mapNotNull null
            val mins = cs.map { it.hour * 60 + it.minute }.sorted()
            val median = mins[mins.size / 2]
            if (mins.count { abs(it - median) <= 45 } < 3) null else CallSlot(day, median) to weeks
        }.maxByOrNull { it.second }?.first
    }

    fun india(now: ZonedDateTime): String = TIME.format(now.withZoneSameInstant(ZoneId.of("Asia/Kolkata")))
}

/** Words for days in cards. */
internal fun dayWord(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.US)
