package dev.phonerecall.home

import dev.phonerecall.ui.Workers

import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSeed
import dev.phonerecall.ui.Tone
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Sample content for the preview build (Settings > Show sample cards): office days, a weekly
 * team lunch, match days, a refund on day two. No amounts, addresses or account details.
 */
object SampleData {

    /** Seeds for the real task store in sample mode (flagged as samples, removed when sample mode goes off). */
    fun seedTasks(now: ZonedDateTime): List<TaskSeed> {
        val ms = { t: ZonedDateTime -> t.toInstant().toEpochMilli() }
        val today = now.toLocalDate().atStartOfDay(now.zone)
        val firstOfNext = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(now.zone)
        return listOf(
            TaskSeed("Drop off the package return", TaskList.NEXT, due = ms(today.withHour(18)), dueHasTime = true, context = dev.phonerecall.data.TaskContext.ERRANDS),
            TaskSeed("Reply to Taylor about the weekly report", TaskList.NEXT, due = ms(today), context = dev.phonerecall.data.TaskContext.COMPUTER),
            TaskSeed("Text Jordan about the grocery run", TaskList.NEXT, quick = true, context = dev.phonerecall.data.TaskContext.ANYWHERE),
            TaskSeed("Look at the Inter Miami away dates", TaskList.INBOX, project = "Inter Miami away game"),
            TaskSeed("Check the drive time to Foxborough", TaskList.NEXT, project = "Inter Miami away game", context = dev.phonerecall.data.TaskContext.COMPUTER),
            TaskSeed("Fall foliage drive, Allegheny?", TaskList.INBOX),
            TaskSeed("Refund from the store", TaskList.WAITING, waitingOn = "The store", followUp = ms(today.plusDays(3))),
            TaskSeed("Car wash membership renews", TaskList.SCHEDULED, due = ms(firstOfNext)),
            TaskSeed("Summer trip ideas", TaskList.SOMEDAY),
        )
    }

    fun weather() = CardContent(
        title = "Light rain from 3 PM",
        stat = Stat("9°", "now", dev.phonerecall.ui.TileForm.CIRCLE),
        facts = listOf(Fact("High", "12°"), Fact("Low", "6°"), Fact("Next hour", "Dry")),
        sample = true,
    )

    fun music(now: ZonedDateTime): CardContent {
        val playlist = when (now.hour) {
            in 5..10 -> "Morning Commute"
            in 11..16 -> "Deep Work"
            in 17..21 -> "Late Night Drive"
            else -> "Night Shift"
        }
        return CardContent(
            title = "You usually play $playlist around now.",
            actions = listOf(CardAction("Play", ActionRole.OPEN)),
            sample = true,
        )
    }

    /** The Pantheon's external workers (phase 3). Athena's own circle lives in her Managed Agent (see mind/Circle.kt). */
    data class Worker(val name: String, val role: String, val active: Boolean)

    val workers = listOf(
        Worker(Workers.IRIS, "mail and calendar", true),
        Worker(Workers.ATLAS, "server and code", true),
        Worker(Workers.ECHO, "phone calls", false),
    )

    fun agents(now: ZonedDateTime): CardContent {
        val irisRan = if (now.hour >= 8) "Morning brief sent at 7:15, nothing new since" else "Ran overnight, nothing new"
        val waiting = workers.filter { !it.active }.joinToString(", ") { it.name }
        return CardContent(
            title = "One job running",
            facts = listOf(
                Fact(Workers.IRIS, irisRan, "Idle", Tone.QUIET),
                Fact(Workers.ATLAS, "Updating the Claw server", "Working", Tone.ACTIVE),
                Fact("Later", "$waiting are not set up yet"),
            ),
            sample = true,
        )
    }

    fun vps() = CardContent(
        title = "All services up",
        status = "Online",
        tone = Tone.OK,
        facts = listOf(Fact("Uptime", "12 days"), Fact("Load", "Light"), Fact("Agents", "2 running")),
        sample = true,
    )

    fun security(now: ZonedDateTime) = CardContent(
        title = "No new sign-ins or alerts",
        status = "All clear",
        tone = Tone.OK,
        facts = listOf(Fact("Checked", if (now.hour >= 7) "7:02 AM" else "Last night")),
        sample = true,
    )

    fun commute(now: ZonedDateTime): CardContent {
        // Leave by 8:15, or a few minutes from now if that has passed.
        val fixed = now.withHour(8).withMinute(15)
        val soon = now.plusMinutes(10).let { it.withMinute(it.minute / 5 * 5) }
        val leave = if (soon.isAfter(fixed)) soon else fixed
        return CardContent(
            title = "Leave by ${Fmt.time(leave)}",
            status = "On time",
            tone = Tone.OK,
            facts = listOf(
                Fact("Train", "Central to downtown, about 12 min"),
                Fact("Arrive", Fmt.time(leave.plusMinutes(25))),
            ),
            sample = true,
        )
    }

    /** Morning brief extras that real sources will fill later. */
    fun morningFacts() = listOf(
        Fact("Weather", "Rain after 3 PM, take a jacket", "Sample", Tone.QUIET),
        Fact("Inbox", "One item from Taylor for today", "Sample", Tone.QUIET),
        Fact("Security", "All clear", "Sample", Tone.QUIET),
    )

    fun eveningFacts() = listOf(Fact("Open", "Refund check, day two", "Sample", Tone.QUIET))

    /** Cards in the format the brain will create. */
    fun generated(now: ZonedDateTime): List<GeneratedCard> {
        val ms = { t: ZonedDateTime -> t.toInstant().toEpochMilli() }
        val tomorrow = now.plusDays(1)
        val out = ArrayList<GeneratedCard>()
        out += GeneratedCard(
            title = "Flight tomorrow 8:00 AM",
            body = "Alarm 5:15 AM · Leave 6:00 AM",
            actions = listOf("Set alarm", "Not now"),
            expiresAt = ms(tomorrow.withHour(9).withMinute(0)),
            source = CardSource.ATHENA,
            id = "flight-${tomorrow.toLocalDate()}",
            travel = true,
        )
        out += GeneratedCard(
            title = "Refund, day two",
            body = "No update from the store yet. I'll check again tomorrow morning.",
            actions = listOf("Ask ${Workers.IRIS}", "Got it"),
            expiresAt = ms(tomorrow.withHour(12)),
            source = CardSource.IRIS,
            id = "refund-${now.toLocalDate()}",
        )
        when (now.dayOfWeek) {
            DayOfWeek.MONDAY -> out += GeneratedCard(
                title = "Team lunch tomorrow",
                body = "Lunch is provided on Tuesdays, so no need to pack one.",
                actions = listOf("Got it"),
                expiresAt = ms(tomorrow.withHour(14)),
                source = CardSource.CALENDAR,
                id = "lunch-${tomorrow.toLocalDate()}",
            )
            DayOfWeek.TUESDAY -> if (now.hour < 14) out += GeneratedCard(
                title = "Team lunch today",
                body = "Lunch is provided, so skip the lobby cafe.",
                actions = listOf("Got it"),
                expiresAt = ms(now.withHour(14)),
                source = CardSource.CALENDAR,
                id = "lunch-${now.toLocalDate()}",
            )
            else -> Unit
        }
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        out += GeneratedCard(
            title = if (weekend) "Premier League match day" else "Match day Saturday",
            body = "Scoreline pool picks close at the first kickoff.\nKickoff 7:30 AM",
            actions = listOf("Remind me", "Skip"),
            expiresAt = ms(now.plusDays(((DayOfWeek.SUNDAY.value - now.dayOfWeek.value) + 1).toLong()).withHour(0)),
            source = CardSource.ATHENA,
            id = "matchday-${now.toLocalDate().with(DayOfWeek.SATURDAY)}",
            dueToday = weekend,
        )
        out += GeneratedCard(
            title = "Card statement ready",
            body = "${Workers.IRIS} has the details. Ask her in your private chat.",
            actions = listOf("Ask ${Workers.IRIS}"),
            expiresAt = ms(now.plusDays(2)),
            source = CardSource.BANK,
            id = "statement-${now.toLocalDate()}",
        )
        return out
    }
}
