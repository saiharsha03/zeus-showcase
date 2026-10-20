package dev.phonerecall.moments

import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One bundled proposal a moment offers: Fates validates it, shows it as one card, and runs it on Confirm. */
data class MomentPlan(val kind: MomentKind, val title: String, val steps: List<RawStep>)

/** What he said or typed, read as a moment: a plan, or a question back when the length is unknown. */
sealed class Said {
    data class Plan(val plan: MomentPlan) : Said()
    data class Ask(val question: String) : Said()
}

object MomentPlans {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** Every Moments timer starts at Confirm. Under an hour it is a short timer (the auto-approve kind). */
    fun timer(seconds: Int, label: String): RawStep =
        RawStep(if (seconds < 3600) ProposalKind.SHORT_TIMER else ProposalKind.SET_TIMER, mapOf("seconds" to "$seconds", "label" to label.take(60)))

    /**
     * The cooking plan. All timers start at Confirm, so everything after the preheat is offset by it:
     * preheat, a check 2 minutes before the low end of a range, done at the low end, the step in the
     * middle, and the rest. `preheatFirst` is false when the oven is already hot ("putting it in").
     */
    fun cook(v: CookVariant, name: String?, preheatFirst: Boolean = true, guess: Boolean = false): MomentPlan {
        val food = name ?: "Dinner"
        val it = food.lowercase().let { if (name == null) "food" else it }
        val pre = if (v.preheat && preheatFirst && v.method.preheatMin > 0) v.method.preheatMin * 60 else 0
        val low = v.cook.low
        val items = ArrayList<Pair<Int, String>>()
        if (pre > 0) items += pre to (v.temp?.let { t -> "${v.method.label} at ${t.text}. Put the $it in" } ?: "${v.method.label} is hot. Put the $it in")
        if (v.cook.range && low >= 6 * 60) items += (pre + low - 120) to "Check the $it"
        items += (pre + low) to (if (v.cook.range) "$food should be done (up to ${Span.words(v.cook.high)})" else "$food: done")
        v.turn?.takeIf { t -> t.at in 30 until low }?.let { t -> items += (pre + t.at) to (if (t.phrase) t.word else "${t.word} the $it") }
        v.stand?.takeIf { s -> s > 0 }?.let { s -> items += (pre + low + s) to "Ready to eat: $it" }
        val steps = items.sortedBy { p -> p.first }.distinctBy { p -> p.first }.take(8).map { (s, l) -> timer(s, l) }
        val head = listOfNotNull(v.temp?.text, v.cook.text).joinToString(" · ")
        return MomentPlan(MomentKind.COOKING, "$food: $head" + if (guess) " (a guess)" else "", steps)
    }

    /** A recipe's timed steps, back to back from Confirm: step 2 rings when step 1's time plus its own has passed. */
    fun recipe(steps: List<RecipeStep>): MomentPlan? {
        if (steps.isEmpty()) return null
        var t = 0
        val n = steps.size.coerceAtMost(8)
        val raw = steps.take(8).mapIndexed { i, s ->
            t += s.seconds
            timer(t, if (n == 1) "${s.label}: done" else "${s.label}: done (step ${i + 1} of $n)")
        }
        return MomentPlan(MomentKind.RECIPE, if (n == 1) "${steps[0].label}, ${Span.words(steps[0].seconds)}" else "Recipe timers, back to back ($n steps)", raw)
    }

    fun laundry(washerSeconds: Int, now: ZonedDateTime): MomentPlan = MomentPlan(
        MomentKind.LAUNDRY, "Laundry: washer ${Span.words(washerSeconds)}",
        listOf(
            timer(washerSeconds, "Washer is done. Move it to the dryer"),
            RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to ISO.format(now.plusSeconds(washerSeconds + 15 * 60L)), "text" to "Did the laundry go in the dryer?")),
        ),
    )

    fun dryer(seconds: Int): MomentPlan = MomentPlan(MomentKind.LAUNDRY, "Dryer: ${Span.words(seconds)}", listOf(timer(seconds, "Dryer is done")))

    /** The meter: warns 5 minutes before it runs out. */
    fun meter(minutes: Int): MomentPlan = MomentPlan(
        MomentKind.PARKING, "Meter: ${Span.words(minutes * 60)}",
        listOf(timer((minutes - 5) * 60, "Meter runs out in 5 min")),
    )
}

/** Spoken or typed moments, for Delphi's suggest_timers and the voice router. */
object MomentText {
    private val TIME = Regex("(?i)(\\d+(?:\\.\\d+)?)(?:\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*(hours?|hrs?|minutes?|mins?|min\\b|seconds?|secs?|sec\\b)")
    private val WASHER = Regex("(?i)\\b(washer|washing machine|wash cycle|laundry)\\b")
    private val DRYER = Regex("(?i)\\bdryer\\b")
    private val TEA = Regex("(?i)\\b(tea|steep\\w*|chai)\\b")
    private val WORKOUT = Regex("(?i)\\b(workout|work out|plank|sets?|rounds?|interval\\w*|hiit|stretch\\w*|exercise|reps)\\b")
    private val ROUNDS = Regex("(?i)(\\d+)\\s*(?:sets|rounds|x)\\b")
    private val REST = Regex("(?i)\\brest\\w*\\b[^\\d]{0,12}$")
    private val REST_AFTER = Regex("(?i)^\\s*(of\\s+)?rest")
    private val COOK_CUE = Regex("(?i)\\b(oven|bake|baking|cook\\w*|roast\\w*|boil\\w*|putting|put (it|the)|frozen|dinner|lasagna|pizza|in the oven)\\b")
    private val ALREADY_HOT = Regex("(?i)\\b(putting|put (it|the \\w+) in|it's in|is in|already (hot|preheated)|oven is hot)\\b")

    private fun seconds(m: MatchResult): Int {
        val u = m.groupValues[3].lowercase()
        val k = when { u.startsWith("h") -> 3600; u.startsWith("s") -> 1; else -> 60 }
        return (m.groupValues[1].toDouble() * k).roundToInt()
    }

    private fun first(text: String): Int? = TIME.find(text)?.let { seconds(it) }?.takeIf { it in 1..86_400 }

    fun read(raw: String, now: ZonedDateTime): Said? {
        val text = CookParser.normalize(raw.trim())
        if (text.isEmpty()) return null
        when {
            DRYER.containsMatchIn(text) && !WASHER.containsMatchIn(text) -> return Said.Plan(MomentPlans.dryer(first(text) ?: 60 * 60))
            WASHER.containsMatchIn(text) -> return Said.Plan(MomentPlans.laundry(first(text) ?: 45 * 60, now))
            TEA.containsMatchIn(text) -> {
                val t = text.lowercase()
                val (kind, min) = when {
                    "green" in t -> "Green tea" to 3; "white" in t -> "White tea" to 4; "oolong" in t -> "Oolong" to 5
                    Regex("herbal|chamomile|mint|rooibos|hibiscus").containsMatchIn(t) -> "Herbal tea" to 6
                    "chai" in t -> "Chai" to 5; else -> "Tea" to 4
                }
                val s = first(text) ?: (min * 60)
                return Said.Plan(MomentPlan(MomentKind.STEEP, "$kind: steep ${Span.words(s)}", listOf(MomentPlans.timer(s, "$kind is ready"))))
            }
            WORKOUT.containsMatchIn(text) && TIME.containsMatchIn(text) -> return Said.Plan(workout(text))
        }
        val read = CookParser.parse(text)
        read.best?.let { return Said.Plan(MomentPlans.cook(it, read.name ?: Foods.name(text), preheatFirst = !ALREADY_HOT.containsMatchIn(text))) }
        Foods.guess(text)?.let { g -> return Said.Plan(MomentPlans.cook(g.variant, g.name, preheatFirst = !ALREADY_HOT.containsMatchIn(text), guess = true)) }
        first(text)?.let { s -> return Said.Plan(MomentPlan(MomentKind.COOKING, "Timer: ${Span.words(s)}", listOf(MomentPlans.timer(s, "Time's up")))) }
        if (COOK_CUE.containsMatchIn(text)) return Said.Ask("How long does it need? Tell me the minutes, or snap the box in Zeus and I'll read it.")
        return null
    }

    /** "3 sets of 45 seconds, 15 seconds rest": back-to-back timers, at most 8. Otherwise one timer. */
    private fun workout(text: String): MomentPlan {
        val times = TIME.findAll(text).toList()
        fun isRest(m: MatchResult) = REST.containsMatchIn(text.substring(0, m.range.first)) || REST_AFTER.containsMatchIn(text.substring(m.range.last + 1))
        val rest = times.firstOrNull { isRest(it) }?.let { seconds(it) }
        val work = times.firstOrNull { !isRest(it) }?.let { seconds(it) } ?: seconds(times.first())
        val rounds = ROUNDS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 8) ?: 1
        if (rounds == 1) return MomentPlan(MomentKind.WORKOUT, "Workout: ${Span.words(work)}", listOf(MomentPlans.timer(work, "Workout: time's up")))
        val steps = ArrayList<RawStep>()
        var t = 0
        for (r in 1..rounds) {
            t += work
            steps += MomentPlans.timer(t, if (r == rounds) "Last set done" else if (rest != null) "Set $r done. Rest" else "Set $r done")
            if (rest != null && r < rounds) { t += rest; steps += MomentPlans.timer(t, "Rest over. Set ${r + 1}") }
            if (steps.size >= 8) break
        }
        return MomentPlan(MomentKind.WORKOUT, "Workout: $rounds × ${Span.words(work)}" + (rest?.let { ", ${Span.words(it)} rest" } ?: ""), steps.take(8))
    }
}
