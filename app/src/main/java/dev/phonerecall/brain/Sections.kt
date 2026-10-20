package dev.phonerecall.brain

/*
 * Dynamic sections. A registry of lists the owner or Athena made,
 * like Groceries. Athena may create, rename, hide, show and reorder them on her own (tier 1: automatic,
 * logged, undoable). His choices win: a section he pinned, renamed or hid is out of her reach. Nothing
 * is ever deleted here; empty sections hide themselves after 14 days.
 */

enum class Actor(val word: String) { OWNER("The owner"), ATHENA("Athena") }

/** How an item's relevance ends (task hygiene). */
enum class RelevanceKind { SEASONAL, EVENT, DEADLINE }

data class Section(
    val id: Long = 0,
    val name: String,
    /** A built-in home it belongs to ("venus" for closet notes), or null for a plain list. */
    val place: String? = null,
    val createdBy: Actor,
    val createdAt: Long,
    val hidden: Boolean = false,
    val hiddenBy: Actor? = null,
    val pinned: Boolean = false,
    val renamedByHim: Boolean = false,
    val order: Int = 0,
    /** Last time an item was added or checked; empty sections hide 14 days after it. */
    val lastActiveAt: Long = createdAt,
)

data class SectionItem(
    val id: Long = 0,
    val sectionId: Long,
    val text: String,
    val note: String = "",
    val done: Boolean = false,
    val createdAt: Long,
    val doneAt: Long? = null,
    /** Relevant until (task hygiene). Past it the item goes to Lethe, unless it is protected. */
    val until: Long? = null,
    val relevance: RelevanceKind? = null,
    val reason: String? = null,
    val pinned: Boolean = false,
    /** In Lethe (closed by hygiene, restorable). */
    val lethe: Boolean = false,
    val lastChance: Boolean = false,
)

/** One logged registry change. `before` is empty for a create. Undo puts `before` back. */
data class SectionChange(
    val id: Long = 0,
    val at: Long,
    val actor: Actor,
    val op: String,
    val before: List<Section>,
    val after: List<Section>,
    val undone: Boolean = false,
) {
    val summary: String get() {
        val a = after.firstOrNull()?.name ?: before.firstOrNull()?.name ?: "a section"
        return when (op) {
            "create" -> "Made $a"
            "rename" -> "Renamed ${before.firstOrNull()?.name} to $a"
            "hide" -> "Hid $a"
            "auto-hide" -> "Hid $a (empty for 14 days)"
            "show" -> "Brought back $a"
            "reorder" -> "Reordered sections"
            "pin" -> if (after.firstOrNull()?.pinned == true) "Pinned $a" else "Unpinned $a"
            else -> "$op $a"
        }
    }
}

interface SectionPersistence {
    fun sections(): List<Section>
    fun insertSection(s: Section): Long
    fun updateSection(s: Section)
    fun openItemCount(sectionId: Long): Int
    fun logChange(c: SectionChange): Long
    fun changes(limit: Int = 50): List<SectionChange>
    fun change(id: Long): SectionChange?
    fun markUndone(id: Long)
}

sealed class SectionResult {
    data class Done(val section: Section, val changeId: Long?) : SectionResult()
    data class Refused(val reason: String) : SectionResult()
}

class SectionRegistry(private val p: SectionPersistence, private val audit: Audit, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** What the nav lists: visible sections, his pins first, then the registry order. */
    fun visible(): List<Section> = p.sections().filter { !it.hidden }.sortedWith(compareBy({ !it.pinned }, { it.order }, { it.id }))

    fun all(): List<Section> = p.sections().sortedWith(compareBy({ !it.pinned }, { it.order }, { it.id }))

    fun get(id: Long): Section? = p.sections().firstOrNull { it.id == id }

    fun byName(name: String): Section? = clean(name)?.let { n -> p.sections().firstOrNull { it.name.equals(n, ignoreCase = true) } }

    /** "groceries " becomes "Groceries". Letters, digits, spaces and & ' - only, 1 to 32 characters. */
    fun clean(name: String): String? {
        val n = name.trim().replace(Regex("\\s+"), " ").replace(Regex("[^\\p{L}\\p{N} &'-]"), "").trim()
        if (n.isEmpty() || n.length > 32) return null
        return n.replaceFirstChar { it.uppercase() }
    }

    private fun log(actor: Actor, op: String, before: List<Section>, after: List<Section>): Long {
        val c = SectionChange(at = clock(), actor = actor, op = op, before = before, after = after)
        val id = p.logChange(c)
        audit.log(actor.word, "section", c.summary)
        return id
    }

    fun create(name: String, by: Actor, place: String? = null): SectionResult = synchronized(lock) {
        val n = clean(name) ?: return SectionResult.Refused("A section name needs 1 to 32 letters")
        byName(n)?.let { s ->
            if (!s.hidden) return SectionResult.Done(s, null)
            if (by == Actor.ATHENA && s.hiddenBy == Actor.OWNER) return SectionResult.Refused("The owner hid $n")
            return show(s.id, by)
        }
        val now = clock()
        val s0 = Section(name = n, place = place, createdBy = by, createdAt = now, order = (p.sections().maxOfOrNull { it.order } ?: -1) + 1)
        val s = s0.copy(id = p.insertSection(s0))
        SectionResult.Done(s, log(by, "create", emptyList(), listOf(s)))
    }

    fun rename(id: Long, name: String, by: Actor): SectionResult = synchronized(lock) {
        val s = get(id) ?: return SectionResult.Refused("No such section")
        val n = clean(name) ?: return SectionResult.Refused("A section name needs 1 to 32 letters")
        if (by == Actor.ATHENA && (s.renamedByHim || s.pinned)) return SectionResult.Refused("The owner named or pinned ${s.name}")
        if (byName(n)?.let { it.id != id } == true) return SectionResult.Refused("$n already exists")
        val a = s.copy(name = n, renamedByHim = s.renamedByHim || by == Actor.OWNER)
        p.updateSection(a)
        SectionResult.Done(a, log(by, "rename", listOf(s), listOf(a)))
    }

    fun hide(id: Long, by: Actor, op: String = "hide"): SectionResult = synchronized(lock) {
        val s = get(id) ?: return SectionResult.Refused("No such section")
        if (by == Actor.ATHENA && s.pinned) return SectionResult.Refused("The owner pinned ${s.name}")
        if (s.hidden) return SectionResult.Done(s, null)
        val a = s.copy(hidden = true, hiddenBy = by, pinned = if (by == Actor.OWNER) false else s.pinned)
        p.updateSection(a)
        SectionResult.Done(a, log(by, op, listOf(s), listOf(a)))
    }

    fun show(id: Long, by: Actor): SectionResult = synchronized(lock) {
        val s = get(id) ?: return SectionResult.Refused("No such section")
        if (!s.hidden) return SectionResult.Done(s, null)
        if (by == Actor.ATHENA && s.hiddenBy == Actor.OWNER) return SectionResult.Refused("The owner hid ${s.name}")
        val a = s.copy(hidden = false, hiddenBy = null, lastActiveAt = clock())
        p.updateSection(a)
        SectionResult.Done(a, log(by, "show", listOf(s), listOf(a)))
    }

    /** The owner only. A pinned section is his: Athena can't move, rename or hide it. */
    fun pin(id: Long, on: Boolean): SectionResult = synchronized(lock) {
        val s = get(id) ?: return SectionResult.Refused("No such section")
        val a = s.copy(pinned = on, hidden = if (on) false else s.hidden, hiddenBy = if (on) null else s.hiddenBy)
        p.updateSection(a)
        SectionResult.Done(a, log(Actor.OWNER, "pin", listOf(s), listOf(a)))
    }

    /**
     * New order for the listed ids. Athena's reorder leaves pinned sections where they are and only
     * moves the others among themselves.
     */
    fun reorder(ids: List<Long>, by: Actor): SectionResult = synchronized(lock) {
        val all = p.sections().associateBy { it.id }
        val movable = ids.mapNotNull { all[it] }.filter { by == Actor.OWNER || !it.pinned }
        if (movable.isEmpty()) return SectionResult.Refused("Nothing to reorder")
        val slots = movable.map { it.order }.sorted()
        val after = movable.mapIndexed { i, s -> s.copy(order = slots[i]) }.filter { a -> all[a.id]?.order != a.order }
        if (after.isEmpty()) return SectionResult.Done(movable.first(), null)
        after.forEach { p.updateSection(it) }
        SectionResult.Done(after.first(), log(by, "reorder", after.map { all.getValue(it.id) }, after))
    }

    /** Puts `before` back. Undoing a create hides the section as if he hid it, so Athena won't bring it back. */
    fun undo(changeId: Long): SectionResult = synchronized(lock) {
        val c = p.change(changeId) ?: return SectionResult.Refused("No such change")
        if (c.undone) return SectionResult.Refused("Already undone")
        val now = p.sections().associateBy { it.id }
        val restored = if (c.before.isEmpty()) {
            c.after.mapNotNull { now[it.id] }.map { it.copy(hidden = true, hiddenBy = Actor.OWNER, pinned = false) }
        } else {
            c.before.mapNotNull { b -> now[b.id]?.let { cur -> b.copy(lastActiveAt = cur.lastActiveAt) } }
        }
        restored.forEach { p.updateSection(it) }
        p.markUndone(changeId)
        audit.log(Actor.OWNER.word, "undid", c.summary)
        SectionResult.Done(restored.firstOrNull() ?: c.after.first(), changeId)
    }

    /** Empty, not pinned, quiet for 14 days: hidden (never deleted), logged as Athena's. */
    fun autoHide(now: Long = clock()): List<Section> = p.sections()
        .filter { !it.hidden && !it.pinned && p.openItemCount(it.id) == 0 && now - it.lastActiveAt >= EMPTY_HIDE_MS }
        .mapNotNull { (hide(it.id, Actor.ATHENA, "auto-hide") as? SectionResult.Done)?.section }

    /** An item was added or checked: the section is active again (not logged). */
    fun touch(id: Long, now: Long = clock()) {
        synchronized(lock) { get(id)?.let { p.updateSection(it.copy(lastActiveAt = now)) } }
    }

    fun changes(limit: Int = 30): List<SectionChange> = p.changes(limit)

    companion object {
        const val EMPTY_HIDE_MS = 14 * 86_400_000L
        /** The cover screen's bottom bar: four fixed destinations and More. Sections never take a slot. */
        const val COVER_BAR_SLOTS = 4
    }
}
