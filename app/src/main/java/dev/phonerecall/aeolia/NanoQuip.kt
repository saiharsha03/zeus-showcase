package dev.phonerecall.aeolia

import dev.phonerecall.brain.Nano
import org.json.JSONObject

/**
 * Nimbus, BetweenWeather's weather robot, on Gemini Nano (ported from its NanoWriter.java onto Zeus's
 * shared [Nano] client). On the phone only; display only. A line is rejected if it holds a number that
 * is not in the facts, or runs past 22 words.
 */
object NanoQuip {
    data class Result(val text: String?, val status: String, val reason: String?)

    fun normalize(l: String?): String = when (l) {
        "professional", "mild", "hot", "nuclear" -> l
        "homicidal", "overkill", "savage" -> "hot"
        else -> "mild"
    }

    private const val CHARACTER = "You are Nimbus, a sarcastic, overdramatic weather robot who treats the weather as a personal vendetta. You mock the sky and the reader with comic exaggeration, rhetorical questions, mock pity and absurd comparisons. You are never cruel about real people or real groups, and you never mention real violence."
    private val VOICE = mapOf(
        "professional" to "Voice: calm, clear and factual, like a good forecaster. No jokes.",
        "mild" to "Voice: sharp, sarcastic, eye-rolling and deadpan.",
        "hot" to "Voice: a maximal, unhinged roast of the reader with cartoonish villain menace, like a theatrical robot villain. Mild profanity such as damn, hell and crap is allowed. Threats are absurd and comic (unplugging their toaster, hiding their umbrella), never real violence. No slurs, nothing about real people or groups.",
        "nuclear" to "Voice: completely unhinged, no-holds-barred roast of the reader with absurd escalating chaos. Heavy profanity such as fuck and shit is allowed and encouraged. Hard limits: no slurs, nothing about real people or real groups or identities, no real violence, no self-harm, nothing sexual.",
    )
    private val EXAMPLES = mapOf(
        "professional" to listOf("Clear and cool this afternoon. A light jacket will do.", "Rain is likely later today. Keep an umbrella close.", "A cold start this morning. Watch for icy patches.", "Warm and dry, with plenty of sun.", "Cloudy and calm. No rain expected."),
        "mild" to listOf("Sunny and cold. The sun is technically out. Emotionally, it is not.", "Rain this afternoon. Yes, right when you leave. Of course.", "Freezing again. Your car will start eventually. Probably.", "Cloudy all day. The sky is phoning it in.", "Hot and humid. Congratulations on becoming soup."),
        "hot" to listOf("Go outside in that hoodie. I will wait. I enjoy watching you shiver.", "Rain later, because the universe saw your plans and said hell no.", "Snow tonight. I hope your shovel is sharp and your will is weak.", "The sun is not shining, it is personally roasting you. Crap.", "Thunderstorms. I have been practicing my evil laugh for this, damn it."),
        "nuclear" to listOf("Your hoodie is not a personality and it is not fucking enough, you walking popsicle.", "Rain later. The sky is taking a shit on your plans and I am here for it.", "It is freezing as fuck. Your car battery just filed for divorce and took the dog.", "Sunny, and you will still find a way to fuck it up. Impressive.", "Wind gusts all day. Say goodbye to your hair, your hat and your shitty dignity."),
    )

    fun prompt(level: String, facts: String, situation: String, n: Int, timeRule: String): String = buildString {
        append(CHARACTER).append('\n').append(VOICE[level]).append('\n')
        append("Write ").append(n).append(" different one-line remarks about the weather, one per line, no numbering. Each line at most 22 words. ")
        append("Use only the facts below. Numbers are optional, and any number you use must appear in the facts. No emoji, no hashtags, no quotation marks, no dashes.\n")
        if (timeRule.isNotEmpty()) append(timeRule).append('\n')
        if (situation.isNotEmpty()) append("React to this situation as well: ").append(situation).append('\n')
        append("Examples of the voice (other days, do not copy):\n")
        for (e in EXAMPLES.getValue(level)) append("- ").append(e).append('\n')
        append("Facts: ").append(facts).append("\nLines:\n")
    }

    /** The place's own time of day, so a line never uses the phone's clock for a far city. */
    fun timeRule(f: JSONObject): String {
        val part = f.optString("partOfDay"); val t = f.optString("placeLocalTime"); val city = f.optString("city")
        if (part.isEmpty() || f.optBoolean("tomorrow")) return ""
        val r = "It is currently $part ($t) in $city. Only mention a time of day that matches this, never a different one."
        val ph = f.optString("phonePartOfDay")
        return if (ph.isEmpty()) r else "$r The reader is far away, where it is $ph; that is their time, not this city's."
    }

    fun facts(f: JSONObject): String =
        if (f.optBoolean("tomorrow")) "Tomorrow: ${f.optString("condition")}. High ${f.optInt("highF")}°F (${f.optInt("highC")}°C), low ${f.optInt("lowF")}°F. ${f.optInt("rainChance")}% chance of rain. UV index ${f.optInt("uv")}."
        else "${f.optString("condition")}. Now ${f.optInt("tempF")}°F (${f.optInt("tempC")}°C), feels like ${f.optInt("feelsF")}°F. High ${f.optInt("highF")}°F, low ${f.optInt("lowF")}°F. " +
            "${f.optInt("rainChance")}% chance of rain. Wind ${f.optInt("windMph")} mph. UV index ${f.optInt("uv")}. ${f.optString("versusYesterday")}"

    private val NUMBER = Regex("\\d+(\\.\\d+)?")

    /** Every number in the line must appear in the facts. */
    fun grounded(line: String, facts: String): Boolean {
        if (line.isEmpty() || line.length > 320) return false
        val allowed = NUMBER.findAll(facts).map { it.value }.toSet()
        return NUMBER.findAll(line).all { it.value in allowed }
    }

    /** Contrast or punchline: two sentences, a question, or a comparison. */
    fun jokeShape(s: String) = s.matches(Regex(".*[.!?] +\\S.*")) || s.contains('?') || s.matches(Regex("(?i).*\\b(like|than|as if|apparently|technically)\\b.*"))
    fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Model output to clean candidate lines: no bullets, numbering, quotes or em dashes. */
    fun lines(text: String): List<String> = text.split(Regex("\\r?\\n")).map {
        it.replace(Regex("^\\s*([-*\\d.)]+\\s*)"), "").replace(Regex("\\s+"), " ").replace(Regex("^[\"'“‘ ]+|[\"'”’ ]+$"), "").replace("—", ", ").trim()
    }.filter { it.length > 3 }

    /** Picks the first grounded line, preferring a joke shape. */
    fun pick(candidates: List<String>, facts: String): String? {
        val ok = candidates.filter { grounded(it, facts) && words(it) <= 22 }
        return ok.firstOrNull { jokeShape(it) } ?: ok.firstOrNull()
    }

    suspend fun quip(f: JSONObject, rawLevel: String): Result {
        val level = normalize(rawLevel)
        val facts = facts(f)
        val text = Nano.generate("aeolia-quip", prompt(level, facts, f.optString("situation"), 3, timeRule(f)), maxTokens = 160, temperature = 0.9f)
            ?: return Result(null, status(), "model ${status()}")
        val line = pick(lines(text), "$facts $f") ?: return Result(null, "ready", "rejected: no candidate passed the checks")
        return Result(line, "ready", null)
    }

    /** ready, downloadable, downloading, unavailable or error, as the page's Settings expects. */
    fun status(): String = Nano.status.value.let { s ->
        when {
            s.startsWith("ready") -> "ready"
            s.startsWith("downloading") -> "downloading"
            s.startsWith("downloadable") -> "downloadable"
            s.startsWith("error") || s.contains("failed") -> "error"
            else -> "unavailable"
        }
    }
}
