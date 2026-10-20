package dev.phonerecall.hermes

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/*
 * Hermes: the owner's instant commands, ported from PhoneRecall (Commands.kt, Actions.kt). He types or says
 * "open spotify", "wake me at 6", "torch on"; a fixed parser reads it on the phone, no model. Reversible
 * things run at once with Undo; calls and texts wait for one tap. Anything that is not a command goes to
 * Athena as before. Pure: the parser, the times and the fuzzy matching are tested without a phone.
 */

/** Something he asked the phone to do. */
sealed interface Cmd {
    /** What Clio logs: the kind only, never his words. */
    val kind: String get() = javaClass.simpleName.removeSuffix("Cmd").lowercase()
}
data class OpenCmd(val target: String, val toggle: Boolean? = null) : Cmd
data class AlarmCmd(val hour: Int, val minute: Int) : Cmd
data class TimerCmd(val seconds: Int) : Cmd
/** [at] null: he didn't say when, so the reply asks. */
data class RemindCmd(val what: String, val at: LocalDateTime?) : Cmd
data class NavigateCmd(val place: String) : Cmd
data class PlayCmd(val query: String) : Cmd
/** pause, resume, next, previous. */
data class MusicCmd(val action: String) : Cmd
data class TorchCmd(val on: Boolean) : Cmd
/** [action]: up, down, mute, unmute, set (with [percent]). */
data class VolumeCmd(val action: String, val percent: Int? = null) : Cmd
data class DndCmd(val on: Boolean) : Cmd
/** [via]: auto (the number's country decides), phone, whatsapp, whatsapp_video. */
data class CallCmd(val who: String, val via: String) : Cmd
/** [via]: sms or whatsapp. */
data class MessageCmd(val who: String, val via: String, val body: String?) : Cmd
data class AgendaCmd(val day: LocalDate) : Cmd
/** "No, I meant WhatsApp": redo the last command with this target and learn it for that phrase. */
data class CorrectionCmd(val target: String) : Cmd

/** What happens when a command runs: at once (with Undo where it can), after one tap, or a read. */
enum class Policy { RUN, CONFIRM, READ }

object Commands {
    private val I = RegexOption.IGNORE_CASE
    private fun rx(p: String) = Regex(p, I)

    /** Calls and texts reach another person, so they wait for his tap. Everything else is his phone. */
    fun policy(cmd: Cmd): Policy = when (cmd) {
        is CallCmd, is MessageCmd -> Policy.CONFIRM
        is AgendaCmd -> Policy.READ
        else -> Policy.RUN
    }

    /** Null means "not a command": it goes to Athena. */
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Cmd? {
        val t = raw.trim().trimEnd('.', '!')
            .replace(rx("^(?:hey |ok |okay )?(?:zeus|athena)[,!.]?\\s+"), "")
            .replace(rx("^(?:(?:hey|ok|okay|please|can you|could you|would you)[ ,]+)+"), "").trim()
        val l = t.lowercase()
        if (l.isEmpty()) return null
        music(l)?.let { return it }

        rx("^(?:no[,.]?\\s*(?:i meant\\s+)?|i meant\\s+)(.+)$").find(t)?.let { return CorrectionCmd(it.groupValues[1].trim()) }

        // "what's on today", "am I free friday", "show my calendar".
        val agenda = rx(
            "^(?:what'?s|what is) on\\b|^what do i have\\b|^do i have anything\\b|^anything on\\b|^am i free\\b|" +
                "^(?:show|read|check) (?:me )?my (?:calendar|agenda|schedule)\\b|^what'?s (?:my|in my) (?:calendar|agenda|schedule)\\b",
        ).containsMatchIn(l)
        if (agenda) {
            val word = rx("\\b(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(l)?.value ?: "today"
            return AgendaCmd(dayWord(word, now.toLocalDate()) ?: now.toLocalDate())
        }

        if (rx("\\btimer\\b").containsMatchIn(l)) parseDuration(l)?.let { return TimerCmd(it) }
        if (rx("^(?:set (?:an |the |my )?alarm|wake me(?: up)?|alarm)\\b").containsMatchIn(l)) {
            // "alarm in 20 minutes" is a time from now; "wake me at 6" is a clock time.
            rx("\\bin\\s+\\S+\\s*(?:minutes?|mins?|hours?|hrs?)\\b").find(l)?.let { parseWhen(it.value, now)?.first?.let { at -> return AlarmCmd(at.hour, at.minute) } }
            parseClock(l)?.let { return AlarmCmd(it.hour, it.minute) }
        }

        rx("^remind me (?:to |that |about )?(.+)$").find(t)?.let { m ->
            val (at, rest) = parseWhen(m.groupValues[1], now) ?: (null to m.groupValues[1])
            val what = rest.trim().trimEnd(',').replace(rx("^(?:to|that|about)\\s+"), "").ifEmpty { m.groupValues[1] }
            return RemindCmd(what, at)
        }

        // "call Mom" lets the number's country pick the way; "phone call", "dial", "ring" mean the phone.
        val wa = rx("\\bwhats ?app\\b").containsMatchIn(l)
        val explicitPhone = rx("^(?:make a )?(?:phone|regular|normal|cell|mobile) call\\b|^dial\\b|^ring\\b|\\b(?:on|via|by) (?:the )?phone$").containsMatchIn(l)
        val callText = l.replace(rx("^(?:make a )?(?:phone|regular|normal|cell|mobile) call\\s+"), "call ").replace(rx("\\s+(?:on|via|by) (?:the )?phone$"), "")
        rx("^(?:make a |start a )?(?:whats ?app |wa )?(video )?(?:call|ring|phone|dial|facetime)\\s+(.+?)(?:\\s+(?:on|via|using|with|over|in)\\s+whats ?app)?$").find(callText)?.let { m ->
            val video = m.groupValues[1].isNotEmpty()
            return CallCmd(m.groupValues[2].trim(), when { video -> "whatsapp_video"; wa -> "whatsapp"; explicitPhone -> "phone"; else -> "auto" })
        }
        rx("^(?:send (?:a )?)?(?:message|msg|text|sms|whats ?app)\\s+(?:to\\s+)?(.+?)(?:\\s+(?:on|via|using|in)\\s+whats ?app)?(?:\\s*[,:]?\\s+(?:saying|that says|that|to say)\\s+(.+))?$").find(t)?.let { m ->
            var who = m.groupValues[1].trim()
            var body = m.groupValues[2].trim().ifEmpty { null }
            // "text mom running late": the first word is who, the rest is the message.
            if (body == null && ' ' in who && !rx("\\b(?:on|via) whats ?app$").containsMatchIn(who)) {
                val w = who.split(Regex("\\s+"), limit = 2)
                if (w[0].length >= 2 && w[0] !in setOf("my", "the")) { who = w[0]; body = w[1] }
            }
            return MessageCmd(who, if (wa) "whatsapp" else "sms", body)
        }

        rx("^(?:navigate|directions|get directions|drive|take me|how do i get|route)\\s+(?:me\\s+)?(?:to\\s+)?(.+)$").find(t)?.let { return NavigateCmd(it.groupValues[1].trim()) }
        rx("^play\\s+(.+?)(?:\\s+on\\s+spotify)?$").find(t)?.let { return PlayCmd(it.groupValues[1].trim()) }

        val off = rx("\\boff\\b|\\bdisable\\b|\\bstop\\b").containsMatchIn(l)
        if (rx("\\b(?:flash ?light|torch)\\b").containsMatchIn(l)) return TorchCmd(!off)
        if (rx("\\b(?:do not disturb|don'?t disturb|dnd)\\b").containsMatchIn(l) && !rx("^(?:open|show)\\b|settings?$").containsMatchIn(l)) return DndCmd(!off)
        if (rx("\\b(?:volume|louder|quieter|unmute|mute)\\b").containsMatchIn(l) && !rx("^(?:open|show)\\b|settings?$").containsMatchIn(l)) {
            rx("(\\d{1,3})\\s*(?:%|percent)?$").find(l)?.let { return VolumeCmd("set", it.groupValues[1].toInt().coerceIn(0, 100)) }
            return VolumeCmd(when {
                "unmute" in l -> "unmute"
                "mute" in l -> "mute"
                rx("\\b(?:down|lower|quieter|decrease|reduce)\\b").containsMatchIn(l) -> "down"
                else -> "up"
            })
        }
        rx("^(?:turn|switch|toggle)\\s+(on|off)\\s+(?:the\\s+)?(.+)$|^(?:turn|switch|toggle)\\s+(?:the\\s+)?(.+?)\\s+(on|off)$").find(l)?.let { m ->
            val target = m.groupValues[2].ifEmpty { m.groupValues[3] }
            return OpenCmd(target, (m.groupValues[1].ifEmpty { m.groupValues[4] }) == "on")
        }
        rx("^(?:open|launch|start|run|go to|show)\\s+(?:the |my )?(.+?)(?:\\s+app)?$").find(t)?.let { return OpenCmd(it.groupValues[1].trim()) }
        rx("^(.+?)\\s+settings?$").find(l)?.let { return OpenCmd(it.groupValues[1] + " settings") }
        return null
    }

    private fun music(l: String): Cmd? = when {
        Regex("^(?:pause|stop)(?: the)?(?: music| song| spotify| playback)?$").matches(l) -> MusicCmd("pause")
        Regex("^(?:resume|continue|unpause|keep playing)(?: the)?(?: music| song| spotify)?$|^play(?: the)? music$").matches(l) -> MusicCmd("resume")
        Regex("^(?:next|skip)(?: song| track| this| this song| it)?$").matches(l) -> MusicCmd("next")
        Regex("^(?:previous|go back|last song|previous song|previous track)$").matches(l) -> MusicCmd("previous")
        else -> null
    }

    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "half an" to 0, "half a" to 0)

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUMBER_WORDS[s.lowercase().trim()]

    /** "10 minutes", "1 hour 30 minutes", "90 seconds", "half an hour", "10 min". */
    fun parseDuration(text: String): Int? {
        val l = text.lowercase()
        if (rx("\\bhalf an? hour\\b").containsMatchIn(l)) return 1800
        var total = 0
        rx("(\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|forty five|forty)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").findAll(l).forEach { m ->
            val n = num(m.groupValues[1]) ?: return@forEach
            total += n * when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
        }
        return total.takeIf { it in 1..86_400 }
    }

    /** "7", "7:30", "7.30 am", "6 pm", "18:45", "noon", "midnight". A bare hour is read as on a 24-hour clock. */
    fun parseClock(text: String): LocalTime? {
        val l = text.lowercase()
        if (rx("\\bnoon\\b").containsMatchIn(l)) return LocalTime.NOON
        if (rx("\\bmidnight\\b").containsMatchIn(l)) return LocalTime.MIDNIGHT
        val m = rx("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?=\\W|$)").findAll(l).firstOrNull { it.groupValues[1].toInt() <= 23 } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].replace(".", "")
        if (ampm.isNotEmpty() && h !in 1..12) return null
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return runCatching { LocalTime.of(h, min) }.getOrNull()
    }

    private fun dayWord(w: String, today: LocalDate): LocalDate? = when (w.lowercase()) {
        "today", "tonight", "this evening", "this afternoon", "this morning" -> today
        "tomorrow" -> today.plusDays(1)
        else -> runCatching { DayOfWeek.valueOf(w.uppercase()) }.getOrNull()?.let { today.with(TemporalAdjusters.next(it)) }
    }

    /**
     * A time phrase ("in 20 minutes", "at 5pm", "tomorrow", "on friday at 9", "tonight") and [text] without it.
     * Only a day means 9 AM. "At 5" at 3 PM means 5 PM today; any other time that has passed means tomorrow.
     * Null when there is no time phrase; the caller asks rather than guessing.
     */
    fun parseWhen(text: String, now: LocalDateTime = LocalDateTime.now()): Pair<LocalDateTime, String>? {
        rx("\\bin\\s+(\\d+|an?|one|two|three|five|ten|fifteen|twenty|thirty|half an)\\s*(minutes?|mins?|hours?|hrs?|days?)\\b").find(text)?.let { m ->
            val n = num(m.groupValues[1]) ?: 0
            val at = when (m.groupValues[2].lowercase().first()) {
                'm' -> now.plusMinutes(n.toLong())
                'h' -> if (m.groupValues[1].startsWith("half")) now.plusMinutes(30) else now.plusHours(n.toLong())
                else -> now.plusDays(n.toLong())
            }
            return at.withSecond(0).withNano(0) to text.removeRange(m.range).replace(Regex("\\s+"), " ").trim()
        }
        var rest = text
        var day: LocalDate? = null
        var time: LocalTime? = null
        var bareHour = false
        rx("\\b(?:on\\s+)?(today|tonight|tomorrow|this evening|this afternoon|this morning|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(rest)?.let { m ->
            day = dayWord(m.groupValues[1], now.toLocalDate())
            time = when (m.groupValues[1].lowercase()) { "tonight", "this evening" -> LocalTime.of(20, 0); "this afternoon" -> LocalTime.of(15, 0); "this morning" -> LocalTime.of(9, 0); else -> null }
            rest = rest.removeRange(m.range)
        }
        rx("\\b(?:at|by|around)\\s+(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?)?|noon|midnight)|\\b(\\d{1,2}(?:[:.]\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?))").find(rest)?.let { m ->
            parseClock(m.value)?.let { c ->
                time = c
                bareHour = !rx("[ap]\\.?m\\.?|noon|midnight").containsMatchIn(m.value)
                rest = rest.removeRange(m.range)
            }
        }
        if (day == null && time == null) return null
        var at = LocalDateTime.of(day ?: now.toLocalDate(), time ?: LocalTime.of(9, 0))
        if (day == null && at.isBefore(now)) {
            // "At 5" said in the afternoon is this evening, not 5 AM tomorrow.
            at = if (bareHour && at.hour in 1..11 && at.plusHours(12).isAfter(now)) at.plusHours(12) else at.plusDays(1)
        }
        return at to rest.replace(Regex("\\s+"), " ").trim()
    }

    // ---- fuzzy names ----

    private val NUMBER_DIGITS = mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")

    /** Lowercase, punctuation to spaces, number words to digits. */
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        .split(' ').joinToString(" ") { NUMBER_DIGITS[it] ?: it }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diag = prev[0]; prev[0] = i
            for (j in 1..b.length) {
                val tmp = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diag + if (a[i - 1] == b[j - 1]) 0 else 1)
                diag = tmp
            }
        }
        return prev[b.length]
    }

    /**
     * How well a typed or spoken name matches a label, 0..1: "chess" to "Chess Arena" 0.93, "teams" to
     * "Microsoft Teams" 0.86, "whatsap" to "WhatsApp" 0.9, "spotfy" to "Spotify" by edit distance.
     */
    fun matchScore(query: String, label: String): Float {
        val a = norm(query); val b = norm(label)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val ca = a.replace(" ", ""); val cb = b.replace(" ", "")
        if (ca == cb) return 0.98f
        val words = b.split(' ')
        if (words.first() == a || b.startsWith("$a ")) return 0.93f
        if (ca.length >= 3 && cb.startsWith(ca)) return 0.9f
        if (a.split(' ').all { it in words }) return 0.88f
        if (words.any { it == a }) return 0.86f
        val whole = 1f - levenshtein(ca, cb).toFloat() / maxOf(ca.length, cb.length)
        val perWord = words.maxOf { w -> 1f - levenshtein(ca, w).toFloat() / maxOf(ca.length, w.length) } * 0.95f
        return maxOf(whole, perWord) * 0.9f
    }

    /** One thing he could open: an app or a settings page. */
    data class Target(val label: String, val detail: String, val score: Float, val key: String)

    sealed interface Pick {
        data class One(val target: Target) : Pick
        data class Several(val targets: List<Target>) : Pick
        data object None : Pick
    }

    /**
     * Which target "open <name>" means. A clear winner opens; a typo opens when nothing else is close;
     * a name that is the start of another ("WhatsApp", "WhatsApp Business") wins over the longer one.
     */
    fun pick(scored: List<Target>): Pick {
        val list = scored.sortedByDescending { it.score }
        val best = list.firstOrNull() ?: return Pick.None
        val near = list.drop(1).filter { it.score >= best.score - 0.04f && !it.label.equals(best.label, true) }
        if (best.score >= 0.85f) {
            if (near.isEmpty()) return Pick.One(best)
            val group = near + best
            val shortest = group.minBy { norm(it.label).length }
            if (group.all { it === shortest || norm(it.label).startsWith(norm(shortest.label) + " ") }) return Pick.One(shortest)
        }
        val second = list.getOrNull(1)
        if (best.score >= 0.7f && (second == null || second.score < best.score - 0.15f)) return Pick.One(best)
        val close = list.filter { it.score >= 0.55f }.distinctBy { it.label.lowercase() }.take(3)
        return if (close.isEmpty()) Pick.None else Pick.Several(close)
    }
}

/**
 * "No, I meant X": what he said last time, per phrase, to what he meant. "open teams" then "no, I meant
 * Microsoft Teams" makes "open teams" go there next time. Pure over a map; [HermesStore] keeps it.
 */
object Corrections {
    fun key(kind: String, phrase: String) = kind + ":" + Commands.norm(phrase)

    fun lookup(book: Map<String, String>, kind: String, phrase: String): String? = book[key(kind, phrase)]

    fun learn(book: Map<String, String>, kind: String, phrase: String, meant: String): Map<String, String> =
        if (Commands.norm(phrase) == Commands.norm(meant)) book else book + (key(kind, phrase) to meant.trim())

    /** The command to run again, and the (kind, phrase) it teaches. Null when the last one has nothing to correct. */
    fun redo(last: Cmd?, meant: String): Pair<Cmd, Pair<String, String>?>? {
        val m = meant.trim().removePrefix("on ").removePrefix("via ").trim()
        return when (last) {
            is OpenCmd -> OpenCmd(m, last.toggle) to ("app" to last.target)
            is CallCmd -> when (Commands.norm(m)) {
                "whatsapp", "whats app" -> last.copy(via = "whatsapp") to null
                "video", "video call", "whatsapp video" -> last.copy(via = "whatsapp_video") to null
                "phone", "phone call", "a phone call", "the phone" -> last.copy(via = "phone") to null
                else -> last.copy(who = m) to ("contact" to last.who)
            }
            is MessageCmd -> when (Commands.norm(m)) {
                "whatsapp", "whats app" -> last.copy(via = "whatsapp") to null
                "sms", "text", "a text" -> last.copy(via = "sms") to null
                else -> last.copy(who = m) to ("contact" to last.who)
            }
            is NavigateCmd -> NavigateCmd(m) to null
            is PlayCmd -> PlayCmd(m) to null
            else -> null
        }
    }
}
