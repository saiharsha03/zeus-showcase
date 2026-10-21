package dev.phonerecall.fresh

import dev.phonerecall.brain.AppKind
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.putStrings
import dev.phonerecall.mind.strings
import dev.phonerecall.models.Features
import dev.phonerecall.models.ModelBook
import dev.phonerecall.setup.Check
import dev.phonerecall.setup.Fix
import dev.phonerecall.setup.Status
import dev.phonerecall.setup.Telete

/** Genesis's steps, in order. */
enum class Stage { SETUP, THEOGONY, SHAKEDOWN, SPLASH, DONE }

/** One setup row: Telete's check, and whether he skipped it for now. */
data class SetupRow(val check: Check, val skipped: Boolean) {
    val left: Boolean get() = check.status == Status.MISSING && !skipped
}

/**
 * Genesis (Getting started): the fresh start's onboarding, in Home's place until it's done. Setup (Telete's checks as
 * a wizard, skippable, "N left"), then Theogony led by Annabeth on Fable, then the shakedown, then the splash. Every
 * step is kept in [kv], so it resumes where he left it. Pure: GenesisHub does the Android and the network.
 */
class Genesis(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    /** When the fresh start wiped the phone; null on a phone that never had one (Genesis never shows there). */
    val started: Long? get() = kv.get(K_START)?.toLongOrNull()

    val stage: Stage get() = kv.get(K_STAGE)?.let { runCatching { Stage.valueOf(it) }.getOrNull() } ?: Stage.SETUP

    val active: Boolean get() = started != null && stage != Stage.DONE

    fun skipped(): Set<String> = kv.strings(K_SKIPPED).toSet()
    fun skip(id: String) = kv.putStrings(K_SKIPPED, (skipped() + id).toList())
    fun unskip(id: String) = kv.putStrings(K_SKIPPED, (skipped() - id).toList())

    /**
     * Setup's rows: every Telete check but Theogony's (it is the next step), plus a new recovery phrase (the old one was
     * exposed), ok once Mnemosyne was set up again after the fresh start. Missing first within each group.
     */
    fun rows(checks: List<Check>, phraseSetAt: Long?, backupSetUp: Boolean): List<SetupRow> {
        val since = started ?: 0L
        val phrase = Check(
            PHRASE, Telete.DATA, "A new recovery phrase", "The old phrase was exposed. A new one locks every backup from now on",
            when { !backupSetUp -> Status.OPTIONAL; (phraseSetAt ?: 0L) >= since -> Status.OK; else -> Status.MISSING },
            when { !backupSetUp -> "Set up the backup first"; (phraseSetAt ?: 0L) >= since -> ""; else -> "Still the old phrase" },
            Fix.Themis("mnemosyne"), "Change",
        )
        val all = checks.filter { it.id !in OUT } + phrase
        val order = all.map { it.group }.distinct()
        val rank = mapOf(Status.MISSING to 0, Status.UNKNOWN to 1, Status.OPTIONAL to 2, Status.OK to 3)
        val skip = skipped()
        return all.sortedWith(compareBy({ order.indexOf(it.group) }, { rank.getValue(it.status) })).map { SetupRow(it, it.id in skip) }
    }

    fun left(rows: List<SetupRow>): Int = rows.count { it.left }

    /**
     * The next step. Into Theogony, Annabeth's model becomes Fable for the run (the model book, so her agent gets a new
     * version on the next sync); out of it, her own model comes back. Returns the step it is on now.
     */
    fun advance(book: ModelBook): Stage {
        val next = when (stage) {
            Stage.SETUP -> Stage.THEOGONY
            Stage.THEOGONY -> Stage.SHAKEDOWN
            Stage.SHAKEDOWN -> Stage.SPLASH
            Stage.SPLASH, Stage.DONE -> Stage.DONE
        }
        if (next == Stage.THEOGONY) leadWithFable(book) else restoreAnnabeth(book)
        kv.put(K_STAGE, next.name)
        kv.put("$K_AT${next.name}", "${clock()}")
        return next
    }

    fun leadWithFable(book: ModelBook) {
        if (kv.get(K_ANNABETH) != null) return
        kv.put(K_ANNABETH, book.of(ANNABETH))
        book.set(ANNABETH, FABLE)
    }

    /** Her own model again. Only when Genesis set Fable, and only if nobody changed it since. */
    fun restoreAnnabeth(book: ModelBook) {
        val before = kv.get(K_ANNABETH) ?: return
        if (book.of(ANNABETH) == FABLE) book.set(ANNABETH, before)
        kv.put(K_ANNABETH, null)
    }

    /** Annabeth's questions once she wrote them; null while she hasn't (or couldn't: the built-in ones ask then). */
    fun plan(): List<PlannedQuestion>? = GenesisGaps.decode(kv.get(K_PLAN))
    fun savePlan(p: List<PlannedQuestion>?) { kv.put(K_PLAN, p?.let(GenesisGaps::encode)); kv.put(K_PLAN_DONE, "${clock()}") }
    val planned: Boolean get() = kv.get(K_PLAN_DONE) != null

    /** The gaps the interview asks: hers, or the built-in ones until (and unless) she wrote them. None outside a fresh start. */
    fun gaps() = if (started == null) emptyList() else GenesisGaps.gaps(plan())

    /** The kinds of app he said always matter (Senses' floor). */
    fun matters(): Set<AppKind> = kv.strings(K_MATTERS).mapNotNull { n -> AppKind.entries.firstOrNull { it.name == n } }.toSet()
    fun saveMatters(k: Set<AppKind>) = kv.putStrings(K_MATTERS, k.map { it.name })

    var profiled: Boolean
        get() = kv.get(K_PROFILE) != null
        set(v) = kv.put(K_PROFILE, if (v) "${clock()}" else null)

    companion object {
        const val PREFS = "genesis"
        const val K_START = "start"
        const val K_STAGE = "stage"
        private const val K_AT = "at."
        private const val K_SKIPPED = "skipped"
        private const val K_ANNABETH = "annabeth.before"
        private const val K_PLAN = "plan"
        private const val K_PLAN_DONE = "plan.at"
        private const val K_MATTERS = "matters"
        private const val K_PROFILE = "profile.at"
        const val PHRASE = "phrase"
        const val FABLE = "claude-fable-5-1"
        /** Telete's own Theogony row: Genesis's next step. */
        private val OUT = setOf("theogony")
        val ANNABETH = Features.protege(Protege.ANNABETH)
    }
}

/** His "always matter" kinds, for the triage rules (brain/Triage.kt). Set from the phone's store at startup. */
object SenseRules {
    /** The chip words to the kinds of app they mean. */
    val WORDS: Map<String, AppKind> = mapOf("Family chats" to AppKind.CHAT, "Work chat" to AppKind.WORK, "Work email" to AppKind.MAIL, "Bank alerts" to AppKind.BANK, "Texts" to AppKind.SMS)

    @Volatile var kinds: () -> Set<AppKind> = { emptySet() }

    fun matters(kind: AppKind): Boolean = runCatching { kind in kinds() }.getOrDefault(false)

    fun parse(words: String): Set<AppKind> = dev.phonerecall.theogony.Interview.parts(words).mapNotNull { WORDS[it] }.toSet()
}
