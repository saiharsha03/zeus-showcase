package dev.phonerecall.moments

import kotlin.math.roundToInt

/*
 * Moments: Zeus notices an everyday moment and offers the obvious helper, always as a proposal.
 * This file reads cooking instructions (a box photo's text, a recipe on screen, what he says) with
 * plain rules. Pure: no Android, no model.
 */

/** How it cooks. `rank` orders the default pick: the oven first, the microwave late. */
enum class Method(val label: String, val preheatMin: Int, val rank: Int) {
    OVEN("Oven", 10, 0), CONVECTION("Convection oven", 8, 1), TOASTER("Toaster oven", 6, 2),
    AIR_FRYER("Air fryer", 3, 3), STOVETOP("Stovetop", 0, 4), MICROWAVE("Microwave", 0, 5), GRILL("Grill", 10, 6),
}

/** A temperature as the box printed it. */
data class Temp(val degrees: Int, val celsius: Boolean) {
    val f: Int get() = if (celsius) (degrees * 9.0 / 5 + 32).roundToInt() else degrees
    val text: String get() = if (celsius) "$degrees°C" else "$degrees°F"
}

/** A length in seconds; a range when the box gives one ("18-20 min"). */
data class Span(val low: Int, val high: Int = low) {
    val range: Boolean get() = high > low
    val text: String get() = if (range) "${words(low, unit = false)}–${words(high)}" else words(low)

    companion object {
        /** "18 min", "1 h 5 min", "45 s". */
        fun words(s: Int, unit: Boolean = true): String = when {
            s < 60 -> if (unit) "$s s" else "$s"
            s % 60 != 0 -> "${s / 60} min ${s % 60} s"
            s >= 3600 && s % 3600 == 0 -> "${s / 3600} h"
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            else -> if (unit) "${s / 60} min" else "${s / 60}"
        }
    }
}

/** Something to do partway: "Stir" at 3 minutes, or the box's own words ("Remove film") when it is no stir or flip. */
data class Turn(val at: Int, val word: String, val phrase: Boolean = false)

/** One way to cook it (the oven, the microwave...). `others` are the box's alternatives (pan sizes, wattages). */
data class CookVariant(
    val method: Method,
    val temp: Temp?,
    val preheat: Boolean,
    val cook: Span,
    val turn: Turn? = null,
    val stand: Int? = null,
    val others: List<Span> = emptyList(),
)

/** What the parser read: every variant with a time, and the food's name when the text says it. */
data class CookRead(val variants: List<CookVariant>, val name: String?) {
    val best: CookVariant? get() = variants.minByOrNull { it.method.rank }
    val found: Boolean get() = variants.isNotEmpty()
}

object CookParser {
    private const val HEAD_WORDS = "(?:conventional |convection |toaster )?ovens?|micro[\\s-]?wave|air[\\s-]?fr(?:y|yer|ier)|stove[\\s-]?top|skillet|grill"
    /** A heading that owns its line: "CONVENTIONAL OVEN", "Microwave (1100 watts)", "AIR FRYER INSTRUCTIONS". */
    private val LINE_HEAD = Regex("(?i)^[\\s•*\\-\\d.)]*($HEAD_WORDS)(?:\\s*\\([^)]*\\))?(?:\\s+(?:instructions|directions|method|cooking|heating|preparation|prep))?\\s*[:\\-]?\\s*$")
    /** A heading inside running text, always with a colon: "MICROWAVE: Cook 3 min." */
    private val INLINE_HEAD = Regex("(?i)(?:^|(?<=[.!;])\\s*|\\n)\\s*[•*\\-]?\\s*($HEAD_WORDS)(?:\\s*\\([^)]*\\))?(?:\\s+(?:instructions|directions|method))?\\s*:")
    private val SENTINEL = Regex("§([A-Z_]+)§")

    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private val TIME = Regex("(?i)(?<![\\d.x])$NUM(?:\\s*(?:-|to|or)\\s*$NUM)?\\s*(hours?|hrs?\\.?|minutes?|mins?\\.?|min\\b|seconds?|secs?\\.?|sec\\b)")
    private val TEMP = Regex("(?i)(\\d{2,3})\\s*(?:°|degrees?|deg\\.?)\\s*([FC](?![a-z]))?|(\\d{3})\\s?([FC])\\b")

    private val STAND = Regex("(?i)\\b(stand|rest|cool|sit|set aside)\\w*")
    private val SKIP = Regex("(?i)\\b(preheat\\w*|prep|total|ready in|thaw\\w*|defrost\\w*|refrigerat\\w*|keep frozen|within|up to|ago|marinate)\\b")
    private val CONTINUE_BEFORE = Regex("(?i)\\b(another|additional|then|continue\\w*|again)\\b[^.]{0,30}$")
    private val CONTINUE_AFTER = Regex("(?i)^\\s*(more|longer|additional|again)\\b")
    private val PER_SIDE = Regex("(?i)^\\s*(per|each|on each)\\s+side")
    private val TURN = Regex("(?i)\\b(stir|flip|turn(?!\\s+(?:off|on|the oven))|rotat|shak|peel back)\\w*")
    private val HALF = Regex("(?i)\\b(halfway|half way|half-way|midway|once)\\b")
    private val LOOSE = Regex("(?i)\\b(occasionally|frequently|constantly|often|stir in)\\b")
    private val INTERNAL = Regex("(?i)internal|cook(ed)? to|reach|thermometer|temperature of|heated? to (?=1[3-8]\\d)")
    private val COOK_VERB = Regex("(?i)\\b(bake|cook|heat|microwave|roast|fry|boil|simmer|place|put)\\w*")
    private val NEGATIVE = Regex("(?i)\\b(not recommended|do not|don't|never|not for)\\b")

    fun normalize(raw: String): String = raw
        .replace('–', '-').replace('—', '-').replace('º', '°').replace('˚', '°').replace(' ', ' ')
        .replace(Regex("(\\d)\\s*½"), "$1.5").replace("½", "0.5")
        .replace(Regex("(\\d+)\\s+1/2"), "$1.5")
        .replace(Regex("(?i)\\bone(?=\\s+(hour|minute))"), "1")

    fun parse(raw: String): CookRead {
        val text = normalize(raw)
        val segments = segments(text)
        val variants = segments.mapNotNull { (m, body) -> variant(m, body) }
            .distinctBy { it.method }
        return CookRead(variants, Foods.name(text))
    }

    /** Splits the text into (method, body) pieces at its headings; a body before any heading guesses its method. */
    private fun segments(text: String): List<Pair<Method?, String>> {
        val b = StringBuilder()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val head = LINE_HEAD.matchEntire(t)
            if (head != null) b.append("\n§").append(method(head.groupValues[1])?.name).append("§ ")
            else b.append(t).append(' ')
        }
        val marked = INLINE_HEAD.replace(b.toString()) { m -> "\n§${method(m.groupValues[1])?.name}§ " }
        val out = ArrayList<Pair<Method?, String>>()
        for (chunk in marked.split('\n')) {
            val s = SENTINEL.find(chunk)?.takeIf { chunk.trimStart().startsWith("§") }
            val m = s?.let { runCatching { Method.valueOf(it.groupValues[1]) }.getOrNull() }
            val body = (if (s != null) chunk.substring(s.range.last + 1) else chunk).trim()
            val last = out.lastOrNull()
            // "OVEN" then "Preheat oven to..." on the next line is still the oven.
            if (last != null && m != null && last.first == m) out[out.size - 1] = m to (last.second + " " + body)
            else if (body.isNotEmpty() || m != null) out += m to body
        }
        return out
    }

    private fun method(word: String): Method? {
        val w = word.lowercase()
        return when {
            "convection" in w -> Method.CONVECTION
            "toaster" in w -> Method.TOASTER
            "oven" in w -> Method.OVEN
            w.startsWith("micro") -> Method.MICROWAVE
            w.startsWith("air") -> Method.AIR_FRYER
            "stove" in w || "skillet" in w -> Method.STOVETOP
            "grill" in w -> Method.GRILL
            else -> null
        }
    }

    /** The method a headless body describes, from its verbs. */
    private fun infer(body: String): Method? {
        val t = body.lowercase()
        return when {
            Regex("air[\\s-]?fr").containsMatchIn(t) -> Method.AIR_FRYER
            Regex("\\b(preheat|bake|oven|roast)").containsMatchIn(t) || TEMP.containsMatchIn(t) -> Method.OVEN
            Regex("micro[\\s-]?wave").containsMatchIn(t) -> Method.MICROWAVE
            Regex("\\b(boil|simmer|skillet|saucepan|pot|stove|saut|fry|pan)").containsMatchIn(t) -> Method.STOVETOP
            Regex("\\bgrill").containsMatchIn(t) -> Method.GRILL
            else -> null
        }
    }

    fun sentences(body: String): List<String> =
        body.split(Regex("(?<=[.!;])\\s+|(?<=[.!;])$|\\s+(?=\\d+\\.\\s+[A-Z])")).map { it.trim() }.filter { it.isNotEmpty() }

    private data class Time(val low: Int, val high: Int, val start: Int, val end: Int, val unit: Int)

    /** Every time in a sentence, "1 hour 15 minutes" joined into one. */
    private fun times(s: String): List<Time> {
        val out = ArrayList<Time>()
        for (m in TIME.findAll(s)) {
            val unit = m.groupValues[3].lowercase()
            val k = when { unit.startsWith("h") -> 3600; unit.startsWith("s") -> 1; else -> 60 }
            val lo = (m.groupValues[1].toDouble() * k).roundToInt()
            val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { (it.toDouble() * k).roundToInt() } ?: lo
            if (lo <= 0 || hi < lo || hi > 6 * 3600) continue
            val prev = out.lastOrNull()
            // "1 hour 15 minutes", "1 minute 30 seconds": a smaller unit right after a bigger one adds to it.
            if (prev != null && k < prev.unit && s.substring(prev.end, m.range.first).trim().lowercase() in setOf("", "and")) {
                out[out.size - 1] = Time(prev.low + lo, prev.high + hi, prev.start, m.range.last + 1, k)
            } else out += Time(lo, hi, m.range.first, m.range.last + 1, k)
        }
        return out
    }

    private fun temp(s: String): Temp? {
        for (m in TEMP.findAll(s)) {
            // "until it reaches 165°F" is the food, not the oven.
            if (INTERNAL.containsMatchIn(s.substring(maxOf(0, m.range.first - 32), m.range.first))) continue
            val n = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: continue
            val unit = m.groupValues[2].ifEmpty { m.groupValues[4] }.uppercase()
            val t = when (unit) {
                "F" -> Temp(n, false).takeIf { n in 250..550 }
                "C" -> Temp(n, true).takeIf { n in 120..290 }
                else -> if (n in 250..550) Temp(n, false) else if (n in 120..249) Temp(n, true) else null
            }
            if (t != null) return t
        }
        return null
    }

    /** "Preheat oven for 10 minutes" is not the cooking; "Preheat oven and bake 20 minutes" is. */
    private fun skip(before: String): Boolean {
        val m = SKIP.findAll(before).lastOrNull() ?: return false
        return !COOK_VERB.containsMatchIn(before.substring(m.range.last + 1))
    }

    private fun turnWord(s: String): String? {
        val m = TURN.find(s) ?: return null
        val w = m.value.lowercase()
        return when {
            w.startsWith("stir") -> "Stir"
            w.startsWith("rotat") -> "Rotate"
            w.startsWith("shak") -> "Shake"
            w.startsWith("peel") -> "Stir"
            else -> "Flip"
        }
    }

    private fun variant(heading: Method?, body: String): CookVariant? {
        val method = heading ?: infer(body) ?: return null
        var temp: Temp? = null
        var main: Span? = null
        var turn: Turn? = null
        var stand: Int? = null
        var pending: String? = null
        var between: String? = null
        val others = ArrayList<Span>()
        for (s in sentences(body)) {
            if (NEGATIVE.containsMatchIn(s) && (infer(s) != null || method(s) != null)) continue
            if (temp == null) temp = temp(s)
            val ts = times(s)
            if (ts.isEmpty()) {
                // "Stir." between two cooking times is the step in the middle; "Flip halfway." is half of it.
                val m0 = main
                if (m0 != null && turn == null) {
                    turnWord(s)?.takeIf { !LOOSE.containsMatchIn(s) || HALF.containsMatchIn(s) }?.let {
                        if (HALF.containsMatchIn(s)) turn = Turn(m0.low / 2, it) else pending = it
                    }
                    if (between == null) between = s.trimEnd('.', '!', ';').split(',').first().trim().take(28)
                }
                continue
            }
            var from = 0
            for (t in ts) {
                val before = s.substring(from, t.start)
                val after = s.substring(t.end, minOf(s.length, t.end + 24))
                from = t.end
                when {
                    STAND.containsMatchIn(before) -> { if (stand == null) stand = t.low }
                    skip(before) -> {}
                    main != null && (CONTINUE_BEFORE.containsMatchIn(before) || CONTINUE_AFTER.containsMatchIn(after)) -> {
                        val m0 = main!!
                        if (turn == null) turn = (pending ?: turnWord(before))?.let { Turn(m0.low, it) } ?: between?.let { Turn(m0.low, it, phrase = true) }
                        main = Span(m0.low + t.low, m0.high + t.high)
                        pending = null
                    }
                    main == null -> {
                        if (PER_SIDE.containsMatchIn(after)) {
                            main = Span(t.low * 2, t.high * 2); turn = Turn(t.low, "Flip")
                        } else {
                            main = Span(t.low, t.high)
                            val w = turnWord(s.substring(t.end))
                            if (w != null && HALF.containsMatchIn(s)) turn = Turn(t.low / 2, w)
                        }
                    }
                    else -> others += Span(t.low, t.high)
                }
            }
        }
        val cook = main ?: return null
        val preheat = Regex("(?i)preheat").containsMatchIn(body) || (temp != null && method in setOf(Method.OVEN, Method.CONVECTION, Method.TOASTER))
        return CookVariant(method, temp, preheat && method.preheatMin > 0, cook, turn, stand, others.distinct().take(4))
    }
}

/** Timed steps in a recipe on screen: "simmer 10 minutes", "bake 25 min", "rest 5 min". */
data class RecipeStep(val label: String, val seconds: Int)

object Recipe {
    private val VERB = Regex(
        "(?i)\\b(simmer|bake|boil|roast|rest|cook|fry|saut[eé]|steam|braise|broil|toast|grill|steep|chill|rise|proof|knead|marinate|blanch|poach|reduce|whisk|beat|mix|microwave|heat|cool|let (?:it )?(?:stand|rest|cool|sit))\\w*",
    )
    private val NOT_A_STEP = Regex("(?i)(prep time|total time|cook time|cooking time|ready in|active time|\\bago\\b|min read|servings|yield|calories|reviews?|rated)")
    private val INGREDIENTS = Regex("(?i)\\b(ingredients|tbsp|tsp|tablespoons?|teaspoons?|cups?|grams|ounces?|oz)\\b")
    private val TIME = Regex("(?i)(\\d+(?:\\.\\d+)?)(?:\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*(hours?|hrs?|minutes?|mins?|min\\b|seconds?|secs?)")

    fun steps(raw: String): List<RecipeStep> {
        val out = ArrayList<RecipeStep>()
        val text = CookParser.normalize(raw).replace('\n', ' ')
        for (s in CookParser.sentences(text)) {
            if (NOT_A_STEP.containsMatchIn(s)) continue
            val v = VERB.find(s) ?: continue
            val t = TIME.findAll(s).firstOrNull { it.range.first > v.range.first } ?: continue
            val unit = t.groupValues[3].lowercase()
            val k = when { unit.startsWith("h") -> 3600; unit.startsWith("s") -> 1; else -> 60 }
            val sec = (t.groupValues[1].toDouble() * k).roundToInt()
            if (sec !in 30..12 * 3600) continue
            val words = s.substring(v.range.first, t.range.first)
                .replace(Regex("(?i)\\b(for|about|around|approximately|until|another|or)\\s*$"), "").trim().trimEnd(',', '-', ' ')
            val label = words.split(Regex("\\s+")).take(4).joinToString(" ").replaceFirstChar { it.uppercase() }.take(28)
            if (out.none { it.label == label && it.seconds == sec }) out += RecipeStep(label, sec)
            if (out.size == 8) break
        }
        return out
    }

    /** Enough to offer "Timers for these steps": two timed steps, or one with an ingredient list. */
    fun looksLikeRecipe(text: String): Boolean {
        val n = steps(text).size
        return n >= 2 || (n == 1 && INGREDIENTS.containsMatchIn(text))
    }
}

/** A built-in guess for common items, clearly labelled as one. */
data class Guess(val name: String, val variant: CookVariant, val note: String? = null)

object Foods {
    private fun oven(f: Int, lo: Int, hi: Int = lo, turn: String? = null, stand: Int? = null) =
        CookVariant(Method.OVEN, Temp(f, false), true, Span(lo * 60, hi * 60), turn?.let { Turn(lo * 30, it) }, stand?.let { it * 60 })
    private fun stove(lo: Int, hi: Int = lo, stand: Int? = null) = CookVariant(Method.STOVETOP, null, false, Span(lo * 60, hi * 60), stand = stand?.let { it * 60 })

    /** Most specific first. */
    private val TABLE: List<Pair<Regex, Guess>> = listOf(
        Regex("(?i)lasagn") to Guess("Lasagna", oven(375, 60, stand = 5)),
        Regex("(?i)pot ?pie") to Guess("Pot pie", oven(400, 50, 55, stand = 5)),
        Regex("(?i)(pizza|digiorno|red baron|tombstone|totino|freschetta|jack'?s)") to Guess("Frozen pizza", oven(425, 16, 20)),
        Regex("(?i)(tater ?tots?)") to Guess("Tater tots", oven(425, 20, 25, turn = "Flip")),
        Regex("(?i)(fries|ore-?ida|french fried)") to Guess("Fries", oven(425, 18, 22, turn = "Flip")),
        Regex("(?i)nuggets?") to Guess("Chicken nuggets", oven(400, 15, 18, turn = "Flip")),
        Regex("(?i)fish ?sticks?") to Guess("Fish sticks", oven(425, 16, 18, turn = "Flip")),
        Regex("(?i)garlic bread") to Guess("Garlic bread", oven(400, 10, 12)),
        Regex("(?i)brownies?") to Guess("Brownies", oven(350, 25, 30)),
        Regex("(?i)cookies?|cookie dough") to Guess("Cookies", oven(350, 9, 11)),
        Regex("(?i)baked potato|potatoes") to Guess("Baked potatoes", oven(400, 50, 60)),
        Regex("(?i)mac(aroni)? (and|&|n) cheese|annie'?s|kraft dinner") to Guess("Mac and cheese", stove(7, 8)),
        Regex("(?i)quinoa") to Guess("Quinoa", stove(15, stand = 5)),
        Regex("(?i)\\brice\\b") to Guess("Rice", stove(18, stand = 5)),
        Regex("(?i)(hard[- ]boiled|boiled) eggs?|\\beggs\\b") to Guess("Boiled eggs", stove(10, 12)),
        Regex("(?i)pasta|spaghetti|penne|rigatoni|fusilli|linguine|noodles") to Guess("Pasta", stove(9, 11), note = "Most dried pasta. Your box has the exact time."),
    )

    fun guess(text: String): Guess? = TABLE.firstOrNull { it.first.containsMatchIn(text) }?.second

    private val NAMES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)lasagn") to "Lasagna", Regex("(?i)pot ?pie") to "Pot pie", Regex("(?i)pizza") to "Pizza",
        Regex("(?i)tater ?tots?") to "Tater tots", Regex("(?i)fries|french fried") to "Fries", Regex("(?i)nuggets?") to "Nuggets",
        Regex("(?i)fish ?sticks?") to "Fish sticks", Regex("(?i)brownie") to "Brownies", Regex("(?i)cookie") to "Cookies",
        Regex("(?i)mac(aroni)? (and|&|n) cheese|shells (and|&) (white )?ched|shells (and|&) cheese|annie'?s") to "Mac and cheese", Regex("(?i)garlic bread") to "Garlic bread",
        Regex("(?i)burrito") to "Burrito", Regex("(?i)enchilada") to "Enchiladas", Regex("(?i)egg rolls?") to "Egg rolls",
        Regex("(?i)potstickers?|dumplings?") to "Dumplings", Regex("(?i)wings") to "Wings", Regex("(?i)waffles?") to "Waffles",
        Regex("(?i)\\brice\\b") to "Rice", Regex("(?i)pasta|spaghetti|penne") to "Pasta", Regex("(?i)cake") to "Cake", Regex("(?i)muffins?") to "Muffins",
    )

    /** The food's plain name, when the text says it. */
    fun name(text: String): String? = NAMES.firstOrNull { it.first.containsMatchIn(text) }?.second
}
