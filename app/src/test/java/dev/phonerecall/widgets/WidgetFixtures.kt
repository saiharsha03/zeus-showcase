package dev.phonerecall.widgets

import dev.phonerecall.data.Availability
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.DayType
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.lemnos.AgentStatus
import dev.phonerecall.lemnos.Alert
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Disk
import dev.phonerecall.lemnos.Health
import dev.phonerecall.lemnos.IrisTask
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.lemnos.WorkerJob
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One Monday morning in Buffalo, shared by the widget tests and the widget shots. */
object WidgetFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 8, 30, 0, 0, zone)
    val ms = now.toInstant().toEpochMilli()
    private const val H = 3_600_000L

    private fun at(day: Long, h: Int, m: Int = 0) = now.toLocalDate().plusDays(day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun allDay(day: Long) = now.toLocalDate().plusDays(day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun event(id: Long, title: String, begin: Long, end: Long = begin + H, allDay: Boolean = false, cat: EventCategory = EventCategory.WORK) =
        CalItem(id, 1, title, begin, end, allDay, cat, "Work", null, null, false)

    val events = listOf(
        event(1, "Team standup", at(0, 9), at(0, 9, 15)),
        event(2, "Data review with Taylor", at(0, 11), at(0, 12)),
        event(3, "Alex's birthday", allDay(0), allDay(1), allDay = true, cat = EventCategory.BIRTHDAY),
        event(4, "Barça v Sevilla", at(0, 15), at(0, 17), cat = EventCategory.SPORT),
        event(5, "Dentist", at(1, 10), at(1, 11), cat = EventCategory.PERSONAL),
        event(6, "Sprint planning", at(1, 14), at(1, 15)),
        event(7, "Out of office", allDay(2), allDay(3), allDay = true, cat = EventCategory.OOO),
        event(8, "Old meeting", at(0, 7), at(0, 8)),
    )

    val availability: Map<LocalDate, Availability> = mapOf(
        now.toLocalDate() to Availability(now.toLocalDate(), DayType.WORKDAY),
        now.toLocalDate().plusDays(1) to Availability(now.toLocalDate().plusDays(1), DayType.WORKDAY),
        now.toLocalDate().plusDays(2) to Availability(now.toLocalDate().plusDays(2), DayType.OOO, "Out of office"),
    )

    fun task(id: Long, title: String, due: Long? = null, hasTime: Boolean = false, quick: Boolean = false, list: TaskList = TaskList.NEXT) =
        Task(id, title, list, null, due, hasTime, id.toDouble(), quick, null, false)

    val tasks = TaskSnapshot(
        listOf(
            task(1, "Return the Amazon package", due = allDay(0)),
            task(2, "Book the foliage drive", due = allDay(3)),
            task(3, "Reply to Jordan about the lease", quick = true),
            task(4, "Renew car registration", due = allDay(-1)),
            task(5, "Learn the ukulele", list = TaskList.SOMEDAY),
            task(6, "Old done one", list = TaskList.DONE),
        ),
        emptyList(), true,
    )

    val irisTasks = listOf(
        IrisTask(11, "Confirm the WWE tickets", "pending", "high", now.toLocalDate().toString(), "iris", true, null, "", false),
        IrisTask(12, "Patch the media server", "pending", null, null, "atlas", false, null, "", false),
        IrisTask(13, "Passport renewal paperwork", "pending", "high", null, "iris", true, null, "", true),
    )

    private fun s(ms: Long) = ms / 1000.0

    val lemnos = LemnosState(
        pairing = DeviceKeys.State.PAIRED,
        online = true,
        health = Health("box", 12 * 86_400L, listOf(0.42, 0.5, 0.6), 4, 8L shl 30, 5L shl 30, 0, 0,
            listOf(Disk("home", "/home", 160L shl 30, 98L shl 30, 62L shl 30, true))),
        agents = listOf(
            AgentStatus("iris", "Iris", "claude", "iris", true, "connected", false, null),
            AgentStatus("atlas", "Atlas", "codex", "atlas", true, "connected", true, "reply"),
            AgentStatus("echo", "Echo", "claude", "echo", false, "offline", false, null),
        ),
        jobs = listOf(WorkerJob("j1", "atlas", "code", "Fix the backup timer", "working", true, s(ms - 3 * H), s(ms - 20 * 60_000L), null)),
        alerts = listOf(
            Alert(1, s(ms - 2 * H), "ssh", "high", "SSH login for user owner from 203.0.113.7, not a trusted address", "", false),
            Alert(2, s(ms - 5 * H), "scan", "medium", "Pending reboot", "", false),
            Alert(3, s(ms - 6 * H), "scan", "medium", "Unexpected listening port 8443", "", false),
            Alert(4, s(ms - 9 * H), "kuma", "low", "Media server recovered", "", false),
            Alert(5, s(ms - 30 * H), "ssh", "high", "Old login", "", true),
        ),
        tasks = irisTasks,
        syncedAt = ms - 4 * 60_000L,
    )
}
