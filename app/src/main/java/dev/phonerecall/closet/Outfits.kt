package dev.phonerecall.closet

import androidx.compose.runtime.Immutable
import dev.phonerecall.data.Availability
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.ClosetGroup
import dev.phonerecall.data.ClosetItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.DayType
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.FESTIVAL_WORDS
import dev.phonerecall.home.Festivals
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import kotlin.math.roundToInt

/** Today's weather as Venus needs it. Every field is optional; Aeolia fills what it knows. */
@Immutable
data class DayWeather(
    val highC: Double? = null,
    val lowC: Double? = null,
    val feelsLikeC: Double? = null,
    val rainChance: Int? = null,
    val snow: Boolean = false,
    val windKph: Double? = null,
)

/** Aeolia's forecast for a day, or null when there is none (Venus then uses the month). */
fun interface WeatherSource {
    fun today(date: LocalDate): DayWeather?
}

val NoWeather = WeatherSource { null }

/** Where Venus reads the forecast. Aeolia sets [source] once it has a WeatherState; until then the month decides. */
object VenusWeather {
    @Volatile var source: WeatherSource = NoWeather

    /** Aeolia's forecast as Venus's day weather. WMO codes 71-77 and 85-86 are snow. */
    fun from(w: dev.phonerecall.aeolia.WeatherState): DayWeather =
        DayWeather(highC = w.highC, lowC = w.lowC, feelsLikeC = w.feelsC, rainChance = w.rainChance, snow = w.code in 71..77 || w.code in 85..86)

    /** Reads Aeolia's stored forecast for today only; other days fall back to the month. */
    val aeolia = WeatherSource { d ->
        dev.phonerecall.aeolia.WeatherStore.state.value.takeIf { it.hasData && d == java.time.LocalDate.now() }?.let { from(it) }
    }
}

/** How warm to dress. From the forecast when there is one, else a Buffalo month. */
enum class Warmth { HOT, MILD, COOL, COLD, FREEZING }

/** What the day asks for. Festival and temple days get traditional wear. */
enum class Occasion { OFFICE, REMOTE, WEEKEND, OFF, DINNER, PARTY, TEMPLE, FESTIVAL }

/** The day an outfit is for. `events` are titles only, for the dinner/temple/party words. */
@Immutable
data class DayContext(
    val date: LocalDate,
    val dayType: DayType,
    val events: List<String> = emptyList(),
    /** A festival on the calendar today (from the big festivals rule), if any. */
    val festival: String? = null,
    val weather: DayWeather? = null,
)

/** His wear log, favorites and today's "not today" skips. Saved by [VenusStore]. */
@Immutable
data class VenusPrefs(
    /** Item id to the epoch days it was worn, oldest first, last 120 days. */
    val worn: Map<String, List<Long>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    /** Items skipped with "Not today", for [skipDay] only. */
    val skipped: Set<String> = emptySet(),
    val skipDay: Long = 0,
    /** Claude's one-line polish of a pick's reason, by [OutfitPick.key] for [polishDay]. */
    val polish: Map<String, String> = emptyMap(),
    val polishDay: Long = 0,
) {
    fun lastWorn(id: String): Long? = worn[id]?.lastOrNull()
    fun skippedOn(day: Long): Set<String> = if (skipDay == day) skipped else emptySet()
    fun polished(key: String, day: Long): String? = if (polishDay == day) polish[key] else null
}

enum class PieceRole(val label: String) { LAYER("Layer"), TOP("Top"), BOTTOM("Bottom"), SHOES("Shoes"), ACCESSORY("Accessory") }

@Immutable
data class OutfitPick(
    val top: ClosetItem?,
    val bottom: ClosetItem?,
    val shoes: ClosetItem?,
    val layer: ClosetItem? = null,
    val accessory: ClosetItem? = null,
    /** A traditional piece not in the catalogue yet ("Blue festive kurta"). Never drawn as a picture. */
    val traditional: String? = null,
    val reason: String,
) {
    val pieces: List<Pair<PieceRole, ClosetItem>> get() = listOfNotNull(
        layer?.let { PieceRole.LAYER to it }, top?.let { PieceRole.TOP to it }, bottom?.let { PieceRole.BOTTOM to it },
        shoes?.let { PieceRole.SHOES to it }, accessory?.let { PieceRole.ACCESSORY to it },
    )
    val ids: List<String> get() = pieces.map { it.second.id }
    /** Stable id of the combination: what "Wore it" logs and what a polish is cached under. */
    val key: String get() = (listOfNotNull(traditional?.let { "t:$it" }) + ids).joinToString("+")
    /** "Navy polo, stone chinos": the card title. */
    val title: String get() = traditional ?: listOfNotNull(top?.name, bottom?.name?.lowercase()).joinToString(", ")
}

@Immutable
data class OutfitPlan(
    val date: LocalDate,
    val occasion: Occasion,
    val warmth: Warmth,
    val main: OutfitPick,
    val alternates: List<OutfitPick>,
    /** True when today's pieces were already logged with "Wore it". */
    val wearing: Boolean,
)

/**
 * Venus's outfit rules: on the phone, deterministic, explainable. Same closet, day and log give the same
 * outfit. The network is never needed; Claude may only reword [OutfitPick.reason] afterwards.
 */
object Outfits {
    /** A top is not suggested again within this many days of being worn; bottoms within [BOTTOM_GAP]. */
    const val TOP_GAP = 6
    const val BOTTOM_GAP = 2

    private val TEMPLE = Regex("(?i)\\b(temple|puja|pooja|mandir|satyanarayana|vratham)\\b")
    private val PARTY = Regex("(?i)\\b(party|wedding|reception|gala|sangeet|engagement)\\b")
    private val DINNER = Regex("(?i)\\b(dinner|date|drinks|interview|presentation|birthday dinner)\\b")
    private val FESTIVAL = Regex("(?i)\\b($FESTIVAL_WORDS)\\b")

    private val LIGHT = setOf("tee", "polo", "jersey", "campshirt")
    private val MID = setOf("henley", "longsleeve", "dressshirt")
    private val WARM = setOf("hoodie", "sweatshirt", "ziptop")

    /** The day for [date] from the curated calendar and availability. Titles only; nothing else leaves. */
    fun context(date: LocalDate, availability: Availability?, events: List<CalItem>, zone: ZoneId, weather: DayWeather?): DayContext {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = events.filter { it.begin < end && it.end > start }
        val festival = Festivals.on(date)?.name
            ?: today.firstOrNull { it.category == EventCategory.HOLIDAY && FESTIVAL.containsMatchIn(it.title) }?.title
        val type = availability?.type ?: when (date.dayOfWeek.value) { 6, 7 -> DayType.WEEKEND; 2, 3, 4 -> DayType.WORKDAY; else -> DayType.REMOTE }
        return DayContext(date, type, today.map { it.title }, festival, weather)
    }

    fun warmth(date: LocalDate, w: DayWeather?): Warmth {
        val t = w?.feelsLikeC ?: w?.let { if (it.highC != null && it.lowC != null) (it.highC + it.lowC) / 2 else it.highC ?: it.lowC }
        val base = if (t == null) when (date.month) {
            Month.JUNE, Month.JULY, Month.AUGUST -> Warmth.HOT
            Month.MAY, Month.SEPTEMBER -> Warmth.MILD
            Month.APRIL, Month.OCTOBER -> Warmth.COOL
            Month.NOVEMBER, Month.MARCH -> Warmth.COLD
            else -> Warmth.FREEZING
        } else when {
            t >= 24 -> Warmth.HOT
            t >= 16 -> Warmth.MILD
            t >= 8 -> Warmth.COOL
            t >= -3 -> Warmth.COLD
            else -> Warmth.FREEZING
        }
        // Snow always means at least cold: the boot and the puffer.
        return if (w?.snow == true && base < Warmth.COLD) Warmth.COLD else base
    }

    fun occasion(ctx: DayContext): Pair<Occasion, String?> {
        if (ctx.festival != null) return Occasion.FESTIVAL to ctx.festival
        ctx.events.firstOrNull { TEMPLE.containsMatchIn(it) }?.let { return Occasion.TEMPLE to it }
        ctx.events.firstOrNull { FESTIVAL.containsMatchIn(it) }?.let { return Occasion.FESTIVAL to it }
        ctx.events.firstOrNull { PARTY.containsMatchIn(it) }?.let { return Occasion.PARTY to it }
        ctx.events.firstOrNull { DINNER.containsMatchIn(it) }?.let { return Occasion.DINNER to it }
        return when (ctx.dayType) {
            DayType.WORKDAY -> Occasion.OFFICE
            DayType.REMOTE -> Occasion.REMOTE
            DayType.WEEKEND -> Occasion.WEEKEND
            DayType.OOO, DayType.HOLIDAY -> Occasion.OFF
        } to null
    }

    private class Profile(val tags: Set<String>, val formality: IntRange, val reason: String)

    private fun profile(o: Occasion, event: String?): Profile = when (o) {
        Occasion.OFFICE -> Profile(setOf("office"), 1..3, "Office day, so smart casual.")
        Occasion.REMOTE -> Profile(setOf("home"), 0..1, "Remote day, so easy and comfortable.")
        Occasion.WEEKEND -> Profile(setOf("outing", "home"), 0..2, "Weekend, so relaxed but ready to head out.")
        Occasion.OFF -> Profile(setOf("outing"), 0..2, "Day off, so relaxed.")
        Occasion.DINNER -> Profile(setOf("date", "impress", "party"), 2..4, "${quote(event)} today, so a step up.")
        Occasion.PARTY -> Profile(setOf("party", "impress"), 2..4, "${quote(event)} today, so dressed up.")
        Occasion.TEMPLE, Occasion.FESTIVAL -> Profile(setOf("date", "office", "party"), 2..4, "")
    }

    private fun quote(s: String?) = s?.let { "\"${it.take(40)}\"" } ?: "An evening plan"

    private fun kind(i: ClosetItem) = i.kind.lowercase()

    /** How a top suits the warmth: higher is better, null means no. */
    private fun topWarmthFit(i: ClosetItem, w: Warmth): Double? {
        val k = kind(i)
        return when (w) {
            Warmth.HOT -> when (k) { in LIGHT -> 2.0; in MID -> 0.0; else -> null }
            Warmth.MILD -> when (k) { in LIGHT -> 1.5; in MID -> 1.5; else -> 0.0 }
            Warmth.COOL -> when (k) { in LIGHT -> 0.5; in MID -> 1.5; else -> 1.5 }
            Warmth.COLD -> when (k) { in LIGHT -> 0.0; in MID -> 1.5; else -> 2.0 }
            Warmth.FREEZING -> when (k) { in LIGHT -> -1.0; in MID -> 1.0; else -> 2.5 }
        }
    }

    /** Small stable noise per item and day, so ties break the same way all day and differently tomorrow. */
    private fun jitter(id: String, day: Long, salt: Int): Double {
        var h = 1125899906842597L xor (day * 31 + salt)
        for (ch in id) h = 31 * h + ch.code
        h = h xor (h ushr 33); h *= -0xae502812aa7333L; h = h xor (h ushr 33)
        return ((h ushr 11) % 1000) / 1000.0 * 0.6
    }

    private fun freshness(prefs: VenusPrefs, id: String, day: Long): Double {
        val last = prefs.lastWorn(id) ?: return 1.0
        return ((day - last).coerceIn(0, 14)) * 0.07
    }

    private fun favorite(prefs: VenusPrefs, id: String, weight: Double) = if (id in prefs.favorites) weight else 0.0

    private fun colourWord(i: ClosetItem) = i.colour.lowercase().split(' ', '-').lastOrNull { it.isNotBlank() }.orEmpty()

    /** Top and bottom together: shade contrast, no same-colour pair, no warm-on-cool clash. */
    private fun harmony(top: ClosetItem?, b: ClosetItem): Double {
        if (top == null) return if (b.shade == "light" || b.temp == "neutral") 1.0 else 0.5
        var s = 0.0
        if (top.shade != b.shade) s += 1.2
        if (colourWord(top) == colourWord(b)) s -= 1.5
        if (top.temp != "neutral" && b.temp != "neutral" && top.temp != b.temp) s -= 0.4
        if (b.temp == "neutral") s += 0.3
        return s
    }

    /**
     * Today's outfit and two alternates. `shift` (Home's "Another") moves the main pick along the ranking.
     * Pieces already logged today with "Wore it" stay today's outfit.
     */
    fun plan(closet: ClosetSnapshot, ctx: DayContext, prefs: VenusPrefs = VenusPrefs(), shift: Int = 0): OutfitPlan? {
        val all = closet.items.filter { !it.archived }
        if (all.none { it.group == ClosetGroup.TOPS }) return null
        val day = ctx.date.toEpochDay()
        val warmth = warmth(ctx.date, ctx.weather)
        val (occasion, event) = occasion(ctx)
        val prof = profile(occasion, event)
        val skipped = prefs.skippedOn(day)
        val items = all.filter { it.id !in skipped }.ifEmpty { all }
        val traditional = occasion == Occasion.FESTIVAL || occasion == Occasion.TEMPLE
        fun fits(i: ClosetItem) = i.occasions.any { it in prof.tags }

        // Already dressed today: that outfit stays, alternates still shown.
        val wornToday = all.filter { prefs.lastWorn(it.id) == day }
        val todayTop = wornToday.firstOrNull { it.group == ClosetGroup.TOPS }

        // ---- tops, ranked ----
        val kurta = items.firstOrNull { it.group == ClosetGroup.TOPS && it.kind.contains("kurta", true) }
        val topPool = items.filter { it.group == ClosetGroup.TOPS && !it.kind.contains("kurta", true) }
        fun rankTops(fresh: Boolean, tagged: Boolean): List<ClosetItem> = topPool.mapNotNull { t ->
            val wf = topWarmthFit(t, warmth) ?: return@mapNotNull null
            val last = prefs.lastWorn(t.id)
            if (fresh && last != null && day - last in 1..TOP_GAP.toLong()) return@mapNotNull null
            if (tagged && !fits(t)) return@mapNotNull null
            if (occasion == Occasion.OFFICE && kind(t) == "jersey") return@mapNotNull null
            val form = if (t.formality in prof.formality) 2.0 else -1.0 * minOf(kotlin.math.abs(t.formality - prof.formality.first), kotlin.math.abs(t.formality - prof.formality.last))
            t to (wf + form + favorite(prefs, t.id, 2.5) + freshness(prefs, t.id, day) + jitter(t.id, day, 1))
        }.sortedByDescending { it.second }.map { it.first }
        // Relax the occasion tag before the no-repeat rule, and that before anything else.
        val ranked = rankTops(true, true).ifEmpty { rankTops(true, false) }.ifEmpty { rankTops(false, false) }.ifEmpty { topPool }
        val rotated = if (ranked.isEmpty()) ranked else ranked.indices.map { ranked[Math.floorMod(it + shift, ranked.size)] }

        // ---- the rest, given a top ----
        val bottomsOk = items.filter { b ->
            b.group == ClosetGroup.BOTTOMS &&
                (b.category != "shorts" || (warmth == Warmth.HOT && occasion != Occasion.OFFICE && !traditional && occasion != Occasion.DINNER && occasion != Occasion.PARTY))
        }
        fun bottomFor(top: ClosetItem?, avoid: Set<String>): ClosetItem? {
            fun score(b: ClosetItem): Double {
                val form = if (b.formality in prof.formality) 1.5 else -0.5 * kotlin.math.abs(b.formality - prof.formality.first)
                val recent = prefs.lastWorn(b.id)?.let { if (day - it in 1..BOTTOM_GAP.toLong()) -2.0 else 0.0 } ?: 0.0
                // Shorts on a hot day beat jeans at home.
                val season = if (warmth == Warmth.HOT && b.category == "shorts") 1.2 else 0.0
                val trad = if (traditional && (b.kind.equals("Chinos", true) || b.shade == "light")) 1.0 else 0.0
                return form + recent + season + trad + harmony(top, b) + (if (fits(b)) 0.6 else 0.0) + favorite(prefs, b.id, 1.0) +
                    freshness(prefs, b.id, day) * 0.5 + jitter(b.id, day, 2) - (if (b.id in avoid) 1.5 else 0.0)
            }
            return bottomsOk.maxByOrNull(::score)
        }
        fun shoesFor(top: ClosetItem?, bottom: ClosetItem?): ClosetItem? {
            val shoes = items.filter { it.group == ClosetGroup.SHOES }
            val boot = shoes.firstOrNull { it.kind.equals("Boot", true) }
            val wet = (ctx.weather?.rainChance ?: 0) >= 60
            if (boot != null && (warmth == Warmth.FREEZING || ctx.weather?.snow == true || (wet && warmth >= Warmth.COLD))) return boot
            val dressy = occasion == Occasion.DINNER || occasion == Occasion.PARTY || traditional || (top?.formality ?: 0) >= 3
            return shoes.filter { !it.kind.equals("Boot", true) }.maxByOrNull { s ->
                val d = if (dressy && s.kind.equals("Derby", true)) 2.5 else if (!dressy && s.kind.equals("Derby", true)) -1.5 else 0.0
                val shade = if (bottom != null && s.shade != bottom.shade) 0.5 else 0.0
                d + shade + (if (fits(s)) 0.6 else 0.0) + favorite(prefs, s.id, 1.0) + jitter(s.id, day, 3)
            }
        }
        fun layerFor(top: ClosetItem?): ClosetItem? {
            val outer = items.filter { it.group == ClosetGroup.OUTERWEAR }
            return when (warmth) {
                Warmth.HOT, Warmth.MILD -> null
                // A warm top (hoodie, sweatshirt) is its own layer on a cool day.
                Warmth.COOL -> if (top != null && kind(top) in WARM) null
                    else outer.filter { !it.kind.equals("Puffer", true) }.maxByOrNull { (if (fits(it)) 1.0 else 0.0) + jitter(it.id, day, 4) }
                // The lighter puffer for cold, the heavier one when it is freezing.
                Warmth.COLD, Warmth.FREEZING -> outer.filter { it.kind.equals("Puffer", true) }.maxByOrNull {
                    val light = it.description.contains("light", true)
                    (if ((warmth == Warmth.COLD) == light) 1.0 else 0.0) + (if (fits(it)) 0.5 else 0.0) + jitter(it.id, day, 4)
                }
            }
        }
        fun accessoryFor(): ClosetItem? =
            if (warmth == Warmth.HOT && occasion in setOf(Occasion.WEEKEND, Occasion.OFF, Occasion.REMOTE))
                items.firstOrNull { it.group == ClosetGroup.ACCESSORIES && it.kind.equals("Cap", true) } else null

        val festivalName = if (occasion == Occasion.FESTIVAL) event else null
        val tradName = if (!traditional) null else kurta?.name?.replaceFirstChar { it.uppercase() }
            ?: festivalName?.let { f -> Festivals.all.firstOrNull { f.contains(it.name, true) }?.outfit } ?: Festivals.on(ctx.date)?.outfit ?: "A kurta"

        fun reasonFor(top: ClosetItem?, layer: ClosetItem?, shoes: ClosetItem?, everyday: Boolean = false): String {
            val head = when {
                everyday -> "An everyday option."
                occasion == Occasion.FESTIVAL -> "${festivalName ?: "A festival"} today, so traditional wear."
                occasion == Occasion.TEMPLE -> "Temple today, so traditional wear."
                else -> prof.reason
            }
            val w = ctx.weather
            val temp = (w?.feelsLikeC ?: w?.highC)?.roundToInt()
            val tail = when {
                shoes?.kind.equals("Boot", true) && w?.snow == true -> "Snow, so the winter boot."
                layer != null && temp != null -> "Feels like $temp°, so the ${layer.name.lowercase()}."
                layer != null -> "Cold ${ctx.date.month.name.lowercase().replaceFirstChar { it.uppercase() }}, so a layer."
                temp != null && warmth == Warmth.HOT -> "$temp° out, so something light."
                (w?.rainChance ?: 0) >= 60 -> "Rain likely, take the umbrella."
                top != null && kind(top) in WARM && warmth >= Warmth.COOL -> "Cool out, so something warm."
                else -> ""
            }
            return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ")
        }

        fun build(top: ClosetItem?, avoidBottoms: Set<String> = emptySet()): OutfitPick {
            val t = if (traditional) kurta else top
            val bottom = bottomFor(t, avoidBottoms)
            val shoes = shoesFor(t, bottom)
            val layer = layerFor(t)
            return OutfitPick(
                top = t, bottom = bottom, shoes = shoes, layer = layer, accessory = accessoryFor(),
                traditional = if (traditional && kurta == null) tradName else null,
                reason = reasonFor(t, layer, shoes),
            )
        }

        val main = if (todayTop != null || wornToday.isNotEmpty()) {
            val pick = build(todayTop)
            pick.copy(
                top = todayTop ?: pick.top,
                bottom = wornToday.firstOrNull { it.group == ClosetGroup.BOTTOMS } ?: pick.bottom,
                shoes = wornToday.firstOrNull { it.group == ClosetGroup.SHOES } ?: pick.shoes,
                layer = wornToday.firstOrNull { it.group == ClosetGroup.OUTERWEAR } ?: pick.layer,
            )
        } else build(rotated.firstOrNull())

        // Alternates: the next two tops in the ranking, everyday wear even on a festival day.
        val altOccasionTops = rotated.filter { it.id != main.top?.id }
        val usedBottoms = setOfNotNull(main.bottom?.id)
        val alternates = altOccasionTops.take(2).mapIndexed { n, t ->
            val bottom = bottomFor(t, usedBottoms + (if (n == 1) setOfNotNull(bottomFor(altOccasionTops[0], usedBottoms)?.id) else emptySet()))
            val shoes = shoesFor(t, bottom)
            val layer = layerFor(t)
            OutfitPick(t, bottom, shoes, layer, accessoryFor(), reason = reasonFor(t, layer, shoes, everyday = traditional))
        }
        val wearing = main.ids.isNotEmpty() && main.ids.any { prefs.lastWorn(it) == day }
        return OutfitPlan(ctx.date, occasion, warmth, main, alternates, wearing)
    }
}

/** A trip from the calendar: travel events grouped by date. */
@Immutable
data class Trip(val start: LocalDate, val end: LocalDate, val title: String) {
    val days: Int get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
}

@Immutable
data class PackLine(val label: String, val count: Int, val items: List<ClosetItem> = emptyList(), val note: String? = null)

@Immutable
data class TripPack(val trip: Trip, val warmth: Warmth, val lines: List<PackLine>)

/** Packing lists for upcoming trips, from his closet. Rules only. */
object Packing {
    private val TO = Regex("(?i)\\b(?:to|for)\\s+([A-Z][\\p{L}.' -]{1,30})")

    /** Travel events in the next [ahead] days, grouped when they touch or are a day apart. */
    fun trips(events: List<CalItem>, today: LocalDate, zone: ZoneId, ahead: Int = 21): List<Trip> {
        val until = today.plusDays(ahead.toLong())
        val spans = events.filter { it.category == EventCategory.TRAVEL }.map { e ->
            val s = java.time.Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
            // All-day ends are exclusive midnight; a timed end belongs to its own day.
            val endMs = if (e.allDay) e.end - 1 else e.end
            val en = java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
            Triple(s, maxOf(s, en), e.title)
        }.filter { it.second >= today && it.first <= until }.sortedBy { it.first }
        val out = ArrayList<Trip>()
        for ((s, e, title) in spans) {
            val last = out.lastOrNull()
            if (last != null && s.toEpochDay() - last.end.toEpochDay() <= 1) {
                out[out.size - 1] = last.copy(end = maxOf(last.end, e))
            } else out += Trip(s, e, place(title) ?: title)
        }
        // A single flight out and one back a few days later become one trip: join gaps up to 10 days
        // when the first leg names a place and the later one does not add a new one.
        val joined = ArrayList<Trip>()
        for (t in out) {
            val last = joined.lastOrNull()
            if (last != null && t.start.toEpochDay() - last.end.toEpochDay() <= 10 && (t.title == last.title || place(t.title) == null)) {
                joined[joined.size - 1] = last.copy(end = t.end)
            } else joined += t
        }
        return joined
    }

    /** "Flight to Austin" to "Austin". */
    fun place(title: String): String? = TO.find(title)?.groupValues?.get(1)?.trim()?.trimEnd('.')

    fun pack(closet: ClosetSnapshot, trip: Trip, warmth: Warmth, prefs: VenusPrefs = VenusPrefs()): TripPack {
        val items = closet.items.filter { !it.archived }
        val d = trip.days.coerceAtLeast(1)
        val day = trip.start.toEpochDay()
        // Versatile first: more occasions, favorites, then a stable order.
        fun rank(list: List<ClosetItem>) = list.sortedWith(
            compareByDescending<ClosetItem> { (if (it.id in prefs.favorites) 3 else 0) + it.occasions.size + (if (it.temp == "neutral") 1 else 0) }.thenBy { it.id },
        )
        val warmKinds = setOf("hoodie", "sweatshirt", "ziptop")
        val topsWanted = minOf(d + 1, 8)
        val tops = rank(items.filter { t -> t.group == ClosetGroup.TOPS && (warmth != Warmth.HOT || t.kind.lowercase() !in warmKinds) })
        // A mix: mostly everyday tops, one warm piece unless it is hot, one smarter top for a dinner out.
        val warm = if (warmth == Warmth.HOT) emptyList() else tops.filter { it.kind.lowercase() in warmKinds }.take(if (warmth >= Warmth.COLD) 2 else 1)
        val smart = tops.filter { it.formality >= 2 && it !in warm }.take(1)
        val everyday = tops.filter { it !in warm && it !in smart }.take((topsWanted - warm.size - smart.size).coerceAtLeast(1))
        val chosenTops = (smart + everyday + warm).take(topsWanted)
        val bottomsWanted = minOf((d + 2) / 3 + 1, 4)
        val bottoms = rank(items.filter { it.group == ClosetGroup.BOTTOMS && (it.category != "shorts" || warmth <= Warmth.MILD) })
            .distinctBy { it.colour }.take(bottomsWanted)
        val shoes = items.filter { it.group == ClosetGroup.SHOES }
        val shoePair = buildList {
            if (warmth == Warmth.FREEZING) shoes.firstOrNull { it.kind.equals("Boot", true) }?.let(::add)
            rank(shoes.filter { !it.kind.equals("Boot", true) && !it.kind.equals("Derby", true) }).firstOrNull()?.let(::add)
            if (d >= 4) shoes.firstOrNull { it.kind.equals("Derby", true) }?.let(::add)
        }
        val layer = when (warmth) {
            Warmth.HOT, Warmth.MILD -> emptyList()
            Warmth.COOL -> items.filter { it.group == ClosetGroup.OUTERWEAR && !it.kind.equals("Puffer", true) }.take(1)
            else -> items.filter { it.group == ClosetGroup.OUTERWEAR && it.kind.equals("Puffer", true) }.take(1)
        }
        val care = items.filter { it.group == ClosetGroup.CARE }
        val careLine = buildList {
            care.filter { it.category == "skincare" }.sortedBy { it.id }.take(3).let(::addAll)
            rank(care.filter { it.category == "fragrance" }).firstOrNull()?.let(::add)
        }
        val lines = buildList {
            add(PackLine("Tops", chosenTops.size, chosenTops))
            add(PackLine("Bottoms", bottoms.size, bottoms))
            add(PackLine("Shoes", shoePair.size, shoePair, "Wear the bulkiest pair on the way"))
            if (layer.isNotEmpty()) add(PackLine("Layer", layer.size, layer))
            if (careLine.isNotEmpty()) add(PackLine("Care", careLine.size, careLine, "Travel sizes where you have them"))
            add(PackLine("Socks and underwear", d + 1))
            add(PackLine("Sleepwear", 1))
            if (warmth >= Warmth.COLD) add(PackLine("Hat, gloves, scarf", 1, note = "Not in the closet yet"))
        }
        return TripPack(trip, warmth, lines)
    }
}

@Immutable
data class Laundry(val wornThisWeek: List<ClosetItem>, val cleanOfficeTops: Int, val cleanTops: Int)

/** Small facts from the wear log: the laundry pile and what never comes out. */
object Insights {
    private fun clothes(closet: ClosetSnapshot) = closet.items.filter { !it.archived && it.group != ClosetGroup.CARE }

    fun laundry(closet: ClosetSnapshot, prefs: VenusPrefs, today: LocalDate): Laundry {
        val day = today.toEpochDay()
        val worn = clothes(closet).filter { i -> prefs.worn[i.id].orEmpty().any { day - it in 0..6 } }
        val wornIds = worn.map { it.id }.toSet()
        val tops = clothes(closet).filter { it.group == ClosetGroup.TOPS && it.id !in wornIds }
        return Laundry(worn.sortedByDescending { prefs.lastWorn(it.id) }, tops.count { "office" in it.occasions }, tops.size)
    }

    /** Clothes with no wear in the last [days] days, the ones never logged first. Empty until the log has a week. */
    fun rarelyWorn(closet: ClosetSnapshot, prefs: VenusPrefs, today: LocalDate, days: Int = 30): List<ClosetItem> {
        val day = today.toEpochDay()
        val first = prefs.worn.values.mapNotNull { it.firstOrNull() }.minOrNull() ?: return emptyList()
        if (day - first < 7) return emptyList()
        return clothes(closet).filter { it.group != ClosetGroup.ACCESSORIES }
            .filter { i -> prefs.lastWorn(i.id)?.let { day - it > days } ?: true }
            .sortedWith(compareBy<ClosetItem> { prefs.lastWorn(it.id) ?: Long.MIN_VALUE }.thenBy { it.id })
    }

    /** Days the log covers, for "Since you started logging". */
    fun loggedSince(prefs: VenusPrefs): Long? = prefs.worn.values.mapNotNull { it.firstOrNull() }.minOrNull()
}
