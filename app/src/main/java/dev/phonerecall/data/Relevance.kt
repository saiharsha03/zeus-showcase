package dev.phonerecall.data

import android.content.Context
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** What a calendar is, guessed from its account and name. Sets its default mode. */
enum class CalKind(val label: String) {
    OWN("Yours"), WORK("Work"), BIRTHDAYS("Birthdays"), SPORTS("Sports"), HOLIDAY("Holidays"), SUBSCRIBED("Subscribed"), HIDDEN("Hidden"),
    /** Olympia's fixtures for the teams he follows: already his choice, so shown by default. */
    FOLLOWED("Your teams"),
}

enum class CalMode(val label: String) { SHOW("Show"), HIGHLIGHTS("Highlights only"), HIDE("Hide") }

@Immutable
data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountType: String,
    val kind: CalKind,
    val color: Int,
) {
    val defaultMode: CalMode get() = when (kind) {
        CalKind.OWN, CalKind.WORK, CalKind.BIRTHDAYS, CalKind.FOLLOWED -> CalMode.SHOW
        CalKind.SPORTS, CalKind.HOLIDAY, CalKind.SUBSCRIBED -> CalMode.HIGHLIGHTS
        CalKind.HIDDEN -> CalMode.HIDE
    }

    companion object {
        const val OUTLOOK = "com.microsoft.office.outlook.USER_ACCOUNT"

        /** Classify from the provider's columns. Outlook (and Exchange) is work. */
        fun classify(name: String, accountType: String, owner: String, visible: Boolean, primary: Boolean): CalKind {
            val n = name.lowercase()
            val o = owner.lowercase()
            return when {
                accountType == OUTLOOK || accountType.contains("exchange") -> CalKind.WORK
                n.contains("birthday") || o.startsWith("addressbook#") || o.contains("#contacts@") -> CalKind.BIRTHDAYS
                !visible -> CalKind.HIDDEN
                o.contains("#holiday@") || n.contains("holiday") -> CalKind.HOLIDAY
                o.contains("#sports@") || SPORT_WORDS.any { n.contains(it) } -> CalKind.SPORTS
                o.endsWith("@group.v.calendar.google.com") || o.endsWith("@import.calendar.google.com") -> if (primary) CalKind.OWN else CalKind.SUBSCRIBED
                else -> CalKind.OWN
            }
        }

        private val SPORT_WORDS = listOf("f1", "formula", "sport", "football", "soccer", "cricket", "ipl", "premier", "la liga", "laliga", "barcelona", "inter miami", "mls", "nba", "nfl", "nhl", "wwe", "match")
    }
}

/** KEEP shows a match even in a Highlights calendar; HIDE removes it. */
enum class RuleAction { KEEP, HIDE }

/**
 * A title rule. `match` is words separated by "|" (any of them, case-insensitive). A "2of:" prefix
 * means at least two of the words (a big Premier League match). `calendarId` limits it to one
 * calendar; `kinds` limits a seeded rule to calendar kinds. His own rules (seeded = false) win.
 */
@Immutable
data class Rule(
    val id: String,
    val match: String,
    val action: RuleAction,
    val calendarId: Long? = null,
    val kinds: Set<CalKind> = emptySet(),
    val seeded: Boolean = false,
    val label: String = "",
) {
    fun matches(title: String, cal: CalendarInfo?): Boolean {
        if (calendarId != null && cal?.id != calendarId) return false
        if (kinds.isNotEmpty() && (cal == null || cal.kind !in kinds)) return false
        val t = title.lowercase()
        val twoOf = match.startsWith("2of:")
        val words = match.removePrefix("2of:").split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return if (twoOf) words.count { t.contains(it) } >= 2 else words.any { t.contains(it) }
    }
}

@Immutable
data class RelevanceConfig(val modes: Map<Long, CalMode> = emptyMap(), val rules: List<Rule> = emptyList()) {
    fun mode(cal: CalendarInfo?): CalMode = cal?.let { modes[it.id] ?: it.defaultMode } ?: CalMode.SHOW
}

/**
 * Phase 2 hook: the brain scores events no rule decided (in a Highlights calendar). Rules always win;
 * the judge is asked only when no rule matched. Null means "no opinion" (hidden).
 */
fun interface RelevanceJudge {
    fun relevant(title: String, cal: CalendarInfo?): Boolean?
}

val NoJudge = RelevanceJudge { _, _ -> null }

/** The festival allowlist (common spellings). Editable in Settings as the "Big Telugu festivals" rule. */
const val FESTIVAL_WORDS = "ugadi|sankranti|makar sankranti|pongal|holi|raksha bandhan|rakhi|ganesh chaturthi|vinayaka chavithi|vinayaka chaturthi|bathukamma|dasara|dussehra|vijayadashami|diwali|deepavali"

object Relevance {
    /** Is this a festival from the (edited) allowlist? */
    fun isFestival(title: String, config: RelevanceConfig): Boolean {
        val words = config.rules.firstOrNull { it.id == "seed-festivals" }?.match ?: return false
        return Rule("festival", words, RuleAction.KEEP).matches(title, null)
    }

    private val BIRTHDAY_WORDS = listOf("birthday", "bday", "b-day")

    fun isBirthday(title: String, cal: CalendarInfo?): Boolean =
        cal?.kind == CalKind.BIRTHDAYS || BIRTHDAY_WORDS.any { title.lowercase().contains(it) }

    /** Should Athena show this event? Birthdays always; then his rules, seeded rules, the calendar's mode. */
    fun relevant(title: String, cal: CalendarInfo?, config: RelevanceConfig, judge: RelevanceJudge = NoJudge): Boolean {
        if (isBirthday(title, cal)) return true
        val mode = config.mode(cal)
        config.rules.firstOrNull { !it.seeded && it.matches(title, cal) }?.let { return it.action == RuleAction.KEEP }
        if (mode == CalMode.HIDE) return false
        if (mode == CalMode.SHOW) return true
        // Highlights only: seeded hide words first (F1 practice), then seeded keeps.
        if (config.rules.any { it.seeded && it.action == RuleAction.HIDE && it.matches(title, cal) }) return false
        if (config.rules.any { it.seeded && it.action == RuleAction.KEEP && it.matches(title, cal) }) return true
        return judge.relevant(title, cal) ?: false
    }

    /** A rule suggestion for "Hide events like this": the part after the last dash or colon, without numbers. */
    fun suggestKeyword(title: String): String {
        val tail = title.split(" - ", " – ", ": ", " | ").last().trim()
        val clean = tail.replace(Regex("\\d+"), "").trim().ifEmpty { title.trim() }
        return clean.take(40)
    }

    /**
     * Seeded defaults (editable and deletable in Settings). Example teams and F1. Holiday
     * calendars keep US federal holidays and the big Telugu festivals; other observances stay hidden.
     */
    val seeds: List<Rule> = listOf(
        Rule("seed-f1-practice", "practice|fp1|fp2|fp3", RuleAction.HIDE, kinds = setOf(CalKind.SPORTS, CalKind.SUBSCRIBED), seeded = true, label = "Formula 1 practice"),
        Rule("seed-f1-keep", "sprint|qualifying|shootout|race|grand prix", RuleAction.KEEP, kinds = setOf(CalKind.SPORTS, CalKind.SUBSCRIBED), seeded = true, label = "Formula 1 sprint, qualifying, race"),
        Rule("seed-teams", "barcelona|barça|barca|inter miami|messi|punjab kings|pbks", RuleAction.KEEP, kinds = setOf(CalKind.SPORTS, CalKind.SUBSCRIBED), seeded = true, label = "His teams"),
        Rule(
            "seed-big-six", "2of:arsenal|liverpool|man city|manchester city|man utd|manchester united|man united|chelsea|tottenham|spurs",
            RuleAction.KEEP, kinds = setOf(CalKind.SPORTS, CalKind.SUBSCRIBED), seeded = true, label = "Premier League big matches",
        ),
        Rule("seed-festivals", FESTIVAL_WORDS, RuleAction.KEEP, kinds = setOf(CalKind.HOLIDAY), seeded = true, label = "Big Telugu festivals"),
        Rule(
            "seed-us-federal",
            "new year's day|martin luther king|washington's birthday|presidents' day|presidents day|memorial day|juneteenth|independence day|labor day|columbus day|indigenous peoples|veterans day|thanksgiving|christmas day",
            RuleAction.KEEP, kinds = setOf(CalKind.HOLIDAY), seeded = true, label = "US federal holidays",
        ),
    )
}

/** Modes and rules, kept on the phone in SharedPreferences. Call on IO. */
class RelevanceStore(context: Context) {
    private val sp = context.getSharedPreferences("relevance", Context.MODE_PRIVATE)

    fun load(): RelevanceConfig {
        if (!sp.getBoolean("seeded", false)) {
            save(RelevanceConfig(rules = Relevance.seeds))
            sp.edit().putBoolean("seeded", true).apply()
        }
        val modes = sp.getString("modes", "").orEmpty().split(',').mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(0, i).toLongOrNull()?.let { id -> runCatching { CalMode.valueOf(e.substring(i + 1)) }.getOrNull()?.let { id to it } }
        }.toMap()
        val rules = runCatching {
            val arr = JSONArray(sp.getString("rules", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                Rule(
                    id = o.getString("id"), match = o.getString("match"), action = RuleAction.valueOf(o.getString("action")),
                    calendarId = if (o.has("cal")) o.getLong("cal") else null,
                    kinds = o.optString("kinds").split(',').filter { it.isNotEmpty() }.mapNotNull { k -> runCatching { CalKind.valueOf(k) }.getOrNull() }.toSet(),
                    seeded = o.optBoolean("seeded"), label = o.optString("label"),
                )
            }
        }.getOrDefault(emptyList())
        return RelevanceConfig(modes, rules)
    }

    fun save(c: RelevanceConfig) {
        val arr = JSONArray()
        for (r in c.rules) arr.put(JSONObject().apply {
            put("id", r.id); put("match", r.match); put("action", r.action.name)
            r.calendarId?.let { put("cal", it) }
            put("kinds", r.kinds.joinToString(",") { it.name }); put("seeded", r.seeded); put("label", r.label)
        })
        sp.edit()
            .putString("modes", c.modes.entries.joinToString(",") { "${it.key}=${it.value.name}" })
            .putString("rules", arr.toString())
            .apply()
    }

    fun restoreSeeds(c: RelevanceConfig): RelevanceConfig =
        c.copy(rules = c.rules.filter { !it.seeded } + Relevance.seeds).also { save(it) }
}
