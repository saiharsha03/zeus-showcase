package dev.phonerecall.voice

import com.anthropic.core.jsonMapper
import java.util.Locale

/*
 * Voice, hybrid (docs/VOICE.md). Push-to-talk only: the side key or the sheet's
 * Speak opens the recognizer; nothing listens on its own. A short command stays on the phone (the
 * on-device recognizer, a fixed parser, Nano when the parser can't tell, TextToSpeech). "Let's talk" or a
 * long question goes to Gemini Live. Everything else goes to Delphi's brain as typed text would.
 */

/** A quick command the phone handles itself. Writes still go through Delphi's tools, so they become proposals. */
sealed interface QuickCommand {
    /** One of Delphi's write tools with its input. */
    data class Propose(val tool: String, val input: Map<String, Any?>) : QuickCommand
    /** "What's next": the next event from read_calendar, spoken. */
    data object WhatsNext : QuickCommand
}

object QuickCommands {
    /** Tools a quick command (or Nano's guess at one) may name. All propose; none acts. */
    val TOOLS = setOf("set_timer", "set_alarm", "add_to_list", "music_control", "play_music")

    private val NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "twenty-five" to 25, "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )
    private val NOT_LISTS = setOf("calendar", "my calendar", "tasks", "task list", "agenda", "reminders", "inbox", "cart", "playlist", "queue")

    /** Lowercase, no wake words, no "please", no trailing punctuation. */
    fun normalize(text: String): String = text.lowercase(Locale.US).trim()
        .replace(Regex("^(hey |ok |okay )?(athena|zeus)[,!.]?\\s+"), "")
        .replace(Regex("^(please|can you|could you|would you)\\s+"), "")
        .replace(Regex("[?.!]+$"), "")
        .replace(Regex("\\s+please$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun number(s: String): Int? = s.toIntOrNull() ?: NUMBERS[s]

    /** Null when it isn't one of the quick commands. */
    fun parse(text: String): QuickCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        return timer(t) ?: alarm(t) ?: music(t) ?: list(t) ?: whatsNext(t)
    }

    private val UNIT = "(seconds?|secs?|minutes?|mins?|hours?|hrs?)"
    private val AMOUNT = Regex("(\\d+|[a-z-]+) $UNIT")

    private fun seconds(n: Int, unit: String) = when {
        unit.startsWith("h") -> n * 3600
        unit.startsWith("m") -> n * 60
        else -> n
    }

    private fun timer(t: String): QuickCommand? {
        if ("timer" !in t) return null
        val total = if ("half an hour" in t) 1800 else AMOUNT.findAll(t).sumOf { m -> number(m.groupValues[1])?.let { seconds(it, m.groupValues[2]) } ?: 0 }
        if (total !in 1..86_400) return null
        return QuickCommand.Propose("set_timer", mapOf("seconds" to total))
    }

    private val ALARM = Regex("^(?:set (?:an |the |my )?alarm|alarm|wake me(?: up)?)(?: for| at)? (\\d{1,2})(?:[: ](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?)?(?: tomorrow| today)?$")

    private fun alarm(t: String): QuickCommand? {
        val m = ALARM.find(t) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3].replace(".", "")
        if (half.isNotEmpty() && hour !in 1..12) return null
        if (half == "pm" && hour < 12) hour += 12
        if (half == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return QuickCommand.Propose("set_alarm", mapOf("hour" to hour, "minute" to minute))
    }

    private fun music(t: String): QuickCommand? {
        val control = when {
            Regex("^(pause|stop)( the)?( music| song| spotify| playback)?$").matches(t) -> "pause"
            Regex("^(resume|continue|unpause|play)( the)?( music| song| spotify| playback)?$").matches(t) -> "resume"
            Regex("^(next|skip)( song| track| this| this song| it)?$").matches(t) -> "next"
            Regex("^(previous|go back|last song|previous song|previous track|back)$").matches(t) -> "previous"
            else -> null
        }
        if (control != null) return QuickCommand.Propose("music_control", mapOf("action" to control))
        val play = Regex("^play (.+?)(?: on spotify)?$").find(t)?.groupValues?.get(1)?.trim() ?: return null
        if (play.isEmpty()) return null
        return QuickCommand.Propose("play_music", mapOf("query" to play, "name" to play))
    }

    private fun list(t: String): QuickCommand? {
        val m = Regex("^add (.+) to (?:my |the )?(.+?)(?: list)?$").find(t) ?: return null
        val section = m.groupValues[2].trim()
        if (section in NOT_LISTS || section.isEmpty()) return null
        val items = m.groupValues[1].split(Regex(",\\s*|\\s+and\\s+|\\s*&\\s*")).map { it.trim().removePrefix("some ") }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        val title = section.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
        return QuickCommand.Propose("add_to_list", mapOf("section" to title, "items" to items.joinToString(", ")))
    }

    private val NEXT = Regex("^(what'?s|what is|whats) (next|up next|my next (thing|event|meeting)|on my calendar( today)?|on today|coming up)$")
    private fun whatsNext(t: String): QuickCommand? = if (NEXT.matches(t)) QuickCommand.WhatsNext else null

    /** The prompt Nano gets when the parser can't tell. On the phone; no network. */
    fun nanoPrompt(text: String): String = """
        Classify one spoken command for a phone assistant. Reply with one line of JSON and nothing else.
        If it is a timer, alarm, music control, play request or adding items to a list, reply like one of:
        {"tool":"set_timer","input":{"seconds":600}}
        {"tool":"set_alarm","input":{"hour":6,"minute":30}}
        {"tool":"music_control","input":{"action":"pause"}}   (pause, resume, next or previous)
        {"tool":"play_music","input":{"query":"lofi","name":"lofi"}}
        {"tool":"add_to_list","input":{"section":"Groceries","items":"milk, eggs"}}
        Otherwise reply {"tool":"none"}.
        Command: ${text.take(300)}
    """.trimIndent()

    /** Nano's answer, if it names one of [TOOLS] with an object input. Anything else is null. */
    fun fromNano(reply: String?): QuickCommand? {
        val raw = reply?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim() ?: return null
        val start = raw.indexOf('{').takeIf { it >= 0 } ?: return null
        return try {
            val node = jsonMapper().readTree(raw.substring(start, raw.lastIndexOf('}') + 1))
            val tool = node.path("tool").asText()
            if (tool !in TOOLS || !node.path("input").isObject) return null
            @Suppress("UNCHECKED_CAST")
            QuickCommand.Propose(tool, jsonMapper().convertValue(node.path("input"), Map::class.java) as Map<String, Any?>)
        } catch (_: Exception) { null }
    }
}

/** Where Live stands for the router. */
data class LiveGate(val keySet: Boolean, val confirmedToday: Boolean, val leftSeconds: Long)

sealed interface Route {
    data class Quick(val command: QuickCommand) : Route
    /** Not a known command: Nano may still recognize one, else it goes to Delphi's brain. */
    data object Brain : Route
    data object Live : Route
    /** First Live of the day: it costs money, so ask once. */
    data object ConfirmLive : Route
    data class LiveBlocked(val why: String) : Route
}

object VoiceRouter {
    private val LIVE = Regex("^(let'?s talk|lets talk|let us talk|talk to me|can we talk|i want to talk|go live|start live|live mode|let'?s chat|lets chat)( for a (bit|minute|while))?( about .*)?$")
    /** A question this long is a conversation, not a command. */
    const val LONG_WORDS = 14

    fun wantsLive(text: String): Boolean = LIVE.matches(QuickCommands.normalize(text))

    fun route(text: String, gate: LiveGate): Route {
        val t = QuickCommands.normalize(text)
        if (wantsLive(t)) return live(gate)
        QuickCommands.parse(t)?.let { return Route.Quick(it) }
        val long = t.split(' ').count { it.isNotBlank() } >= LONG_WORDS
        if (long) return live(gate).let { if (it is Route.LiveBlocked) Route.Brain else it }
        return Route.Brain
    }

    /** Also what the sheet's Live button asks. */
    fun live(gate: LiveGate): Route = when {
        !gate.keySet -> Route.LiveBlocked("Live needs a Gemini key. Add it in Calypso.")
        gate.leftSeconds <= 0 -> Route.LiveBlocked("Today's Live minutes are used. Raise the cap in Themis to keep going.")
        !gate.confirmedToday -> Route.ConfirmLive
        else -> Route.Live
    }
}
