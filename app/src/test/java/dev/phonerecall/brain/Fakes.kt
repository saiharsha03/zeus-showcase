package dev.phonerecall.brain

import java.time.ZoneId

/** An in-memory ProposalStore with the same compare-and-set semantics as the SQLite one. */
class MemoryStore : ProposalStore {
    val rows = LinkedHashMap<Long, Proposal>()
    private var next = 1L
    override fun insert(p: Proposal): Long { val id = next++; rows[id] = p.copy(id = id); return id }
    override fun get(id: Long) = rows[id]
    override fun save(p: Proposal) { rows[p.id] = p }
    override fun casStatus(id: Long, from: ProposalStatus, to: ProposalStatus): Boolean {
        val p = rows[id] ?: return false
        if (p.status != from) return false
        rows[id] = p.copy(status = to); return true
    }
    override fun all() = rows.values.toList().asReversed()
}

class FakeState(
    var open: MutableMap<Long, String> = mutableMapOf(7L to "Return the green scarf"),
    var calendars: List<CalendarChoice> = listOf(CalendarChoice(3, "Personal", "owner@example.com")),
    var calendarWrite: Boolean = true,
    var notify: Boolean = true,
    var drive: Boolean = false,
    var home: dev.phonerecall.hestia.HomeSnapshot? = null,
    var people: Map<String, ZoneId> = emptyMap(),
    var schedulesOf: () -> List<dev.phonerecall.mind.SchedJob>? = { null },
    var googleCalendar: Boolean = true,
    /** Birthday events on his Google Calendar, by name. */
    var birthdays: Map<String, List<SavedBirthday>> = emptyMap(),
) : LocalState {
    override fun schedules() = schedulesOf()
    override fun canSaveBirthdays() = googleCalendar
    override fun savedBirthdays(name: String) = birthdays[name].orEmpty()
    override fun personZone(name: String) = people[name]
    override val zone: ZoneId = ZoneId.of("America/New_York")
    override fun openTaskTitle(id: Long) = open[id]
    override fun writableCalendars() = calendars
    override fun canWriteCalendar() = calendarWrite
    override fun canNotify() = notify
    override fun driving() = drive
    override fun home() = home
}

class FakeExecutor : StepExecutor {
    val ran = ArrayList<Step>()
    val undone = ArrayList<Step>()
    var failWith: String? = null
    override fun run(step: Step): ExecResult {
        failWith?.let { throw IllegalStateException(it) }
        ran += step
        return ExecResult(step.summary, mapOf("id" to "${ran.size}"))
    }
    override fun undo(step: Step): Boolean { undone += step; return true }
}

/** His tap right after the card showed him the proposal as it is in `store` now. */
fun Fates.confirmNow(id: Long, store: ProposalStore): Decision =
    confirm(id, store.get(id)?.let { bind(it) } ?: Binding(id, "", "", "", 0L))
