package dev.phonerecall.brain

import android.content.Context
import java.time.LocalDate
import kotlin.random.Random

/** How much a card or notification is about right now. Only NICE waits when the day's budget is spent. */
enum class Urgency(val word: String) {
    DEADLINE_TODAY("Due today"),
    SAFETY("Safety"),
    TIME_CRITICAL("Now"),
    NICE("Nice to have");

    val breaksBudget: Boolean get() = this != NICE
}

/** The daily attention budget, Themis → Attention. A ceiling, never a target: nothing is made to fill it. */
data class AttentionBudget(val cards: Int = DEFAULT_CARDS, val notifications: Int = DEFAULT_NOTIFICATIONS) {
    companion object {
        const val DEFAULT_CARDS = 5
        const val DEFAULT_NOTIFICATIONS = 3
        val CARD_STEPS = listOf(3, 5, 8, 12)
        val NOTIFICATION_STEPS = listOf(1, 3, 5, 8)
        fun next(steps: List<Int>, now: Int) = steps.firstOrNull { it > now } ?: steps.first()
    }
}

/** Something that waited: it goes into the next morning brief instead of showing now. */
data class DigestItem(val key: String = "", val title: String = "", val forDay: String = "")

/** Where waiting things go (the morning brief on Olympus, and Eos once it exists). */
fun interface DigestSink {
    fun add(item: DigestItem)
}

/** What today has used. `cards` are the ids that showed, so a card that showed stays. */
data class AttentionDay(
    val day: String = "",
    val cards: Set<String> = emptySet(),
    val notified: Int = 0,
    /** The one "Was this useful?" of the day, once picked. */
    val ask: String? = null,
    val asked: Boolean = false,
)

object Attention {
    data class Split<T>(val show: List<T>, val batch: List<T>)

    /**
     * Proactive cards, in order. Urgent ones always show and use budget; ones that already showed today
     * stay; a nice-to-have shows only while the day's budget lasts, otherwise it waits for the brief.
     */
    fun <T> cards(items: List<T>, id: (T) -> String, urgency: (T) -> Urgency, shownToday: Set<String>, budget: Int): Split<T> {
        val show = ArrayList<T>()
        val batch = ArrayList<T>()
        var used = shownToday.size
        for (it in items) when {
            id(it) in shownToday -> show += it
            urgency(it).breaksBudget -> { show += it; used++ }
            used < budget -> { show += it; used++ }
            else -> batch += it
        }
        return Split(show, batch)
    }

    /** May a notification of this urgency go out now, with `notifiedToday` already sent? */
    fun mayNotify(urgency: Urgency, notifiedToday: Int, budget: Int): Boolean = urgency.breaksBudget || notifiedToday < budget

    /** Where a waiting item goes: the next morning brief. Before 4 AM that is still this morning's. */
    fun briefDay(now: java.time.ZonedDateTime): LocalDate = if (now.hour < 4) now.toLocalDate() else now.toLocalDate().plusDays(1)

    /**
     * "Was this useful?": at most once a day, on one kind he hasn't rated yet, at random. Null when today's
     * is used or there is nothing new to ask about.
     */
    fun pickAsk(shown: Collection<ProposalKind>, unrated: Set<ProposalKind>, day: AttentionDay, today: String, rnd: Random = Random.Default): ProposalKind? {
        if (day.day == today && (day.asked || day.ask != null)) return null
        val pool = shown.filter { it in unrated && Fatigue.tracked(it) }.distinct()
        return if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]
    }

    /** The brief's line for what waited: "Delivery update, Worth a look and 2 more". Null on a quiet day. */
    fun briefLine(items: List<DigestItem>): String? {
        if (items.isEmpty()) return null
        val t = items.map { it.title }.distinct()
        return if (t.size <= 3) t.joinToString(", ") else t.take(2).joinToString(", ") + " and ${t.size - 2} more"
    }
}

/**
 * The budget, today's tally and the digest, in SharedPreferences. Shared by the screens and the
 * background (notifications), so every method is synchronized.
 */
class AttentionStore(context: Context, private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() }) : DigestSink {
    private val sp = context.getSharedPreferences("attention", Context.MODE_PRIVATE)
    private val json = dev.phonerecall.mind.stateJson

    @Synchronized fun budget(): AttentionBudget = AttentionBudget(sp.getInt("cards", AttentionBudget.DEFAULT_CARDS), sp.getInt("notifications", AttentionBudget.DEFAULT_NOTIFICATIONS))

    @Synchronized fun setBudget(b: AttentionBudget) { sp.edit().putInt("cards", b.cards.coerceIn(1, 20)).putInt("notifications", b.notifications.coerceIn(0, 20)).apply() }

    @Synchronized fun day(): AttentionDay {
        val today = clock().toLocalDate().toString()
        val d = sp.getString("day", null)?.let { runCatching { json.readValue(it, AttentionDay::class.java) }.getOrNull() }
        return if (d?.day == today) d else AttentionDay(today)
    }

    @Synchronized private fun saveDay(d: AttentionDay) { sp.edit().putString("day", json.writeValueAsString(d)).apply() }

    /** Splits proactive cards by today's budget; the ones that wait go to the digest once. */
    @Synchronized fun <T> cards(items: List<T>, id: (T) -> String, title: (T) -> String, urgency: (T) -> Urgency): List<T> {
        val d = day()
        val s = Attention.cards(items, id, urgency, d.cards, budget().cards)
        val shown = s.show.map(id).toSet()
        if (!d.cards.containsAll(shown)) saveDay(d.copy(cards = d.cards + shown))
        for (b in s.batch) add(DigestItem(id(b), title(b), Attention.briefDay(clock()).toString()))
        return s.show
    }

    /** Runs `post` when the budget allows; otherwise the notification waits for the brief. True when it posted. */
    @Synchronized fun notify(urgency: Urgency, key: String, title: String, post: () -> Unit): Boolean {
        val d = day()
        if (!Attention.mayNotify(urgency, d.notified, budget().notifications)) {
            add(DigestItem("n:$key", title, Attention.briefDay(clock()).toString()))
            return false
        }
        post()
        saveDay(d.copy(notified = d.notified + 1))
        return true
    }

    @Synchronized fun digest(): List<DigestItem> = sp.getString("digest", null)
        ?.let { runCatching { json.readValue(it, object : com.fasterxml.jackson.core.type.TypeReference<List<DigestItem>>() {}) }.getOrNull() }.orEmpty()

    /** Keeps one item per key, and nothing older than yesterday's brief. */
    @Synchronized override fun add(item: DigestItem) {
        val keep = clock().toLocalDate().minusDays(1).toString()
        val all = digest().filter { it.key != item.key && it.forDay >= keep } + item
        sp.edit().putString("digest", json.writeValueAsString(all.takeLast(40))).apply()
    }

    /** What this morning's brief carries. */
    @Synchronized fun forBrief(): List<DigestItem> = clock().toLocalDate().toString().let { today -> digest().filter { it.forDay == today } }

    /** Picks today's "Was this useful?" kind (once), or returns the one already picked and not yet answered. */
    @Synchronized fun ask(shown: Collection<ProposalKind>, unrated: Set<ProposalKind>): ProposalKind? {
        val d = day()
        if (d.asked) return null
        d.ask?.let { return ProposalKind.of(it) }
        val k = Attention.pickAsk(shown, unrated, d, d.day) ?: return null
        saveDay(d.copy(ask = k.wire))
        return k
    }

    @Synchronized fun answered() { saveDay(day().copy(asked = true)) }
}
