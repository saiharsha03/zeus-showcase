package dev.phonerecall

import android.app.Application
import android.content.Context
import dev.phonerecall.brain.Days
import dev.phonerecall.brain.NextAlarm
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import dev.phonerecall.brain.AlarmWatch as Hypnos

/** The process start: receivers and workers get the hooks too, not only the activity. */
class ZeusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.phonerecall.clio.Clio.init(this)
        dev.phonerecall.clio.DeviceWatch.start(this)
        Wiring.install(this)
    }

    /** Drop cached plain text when Android asks for memory back (also when the UI goes away for a while). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) dev.phonerecall.brain.SealCache.forget()
    }
}

/** Where the features' hook slots meet. Assignments only: nothing here reads a store until a hook is called. */
object Wiring {
    fun install(app: Context) {
        val c = app.applicationContext
        val zone = { ZoneId.systemDefault() }
        val watch = { Hypnos.get(c) }

        dev.phonerecall.brain.Different.alarm = object : dev.phonerecall.brain.AlarmSignal {
            private fun tomorrow() = LocalDate.now(zone()).plusDays(1)
            override fun tomorrowMinutes() = watch().next()?.at?.takeIf { day(it, zone()) == tomorrow() }?.let { Days.minuteOfDay(it, zone()) }
            override fun usualMinutes() = watch().habit().forDay(tomorrow().dayOfWeek)
        }

        dev.phonerecall.brief.BriefHooks.alarms = dev.phonerecall.brief.AlarmWatch { d ->
            val w = watch()
            val at = firstAlarm(w.seenRings(), w.next()?.at, d, System.currentTimeMillis(), zone()) ?: return@AlarmWatch null
            val odd = unusual(at, w.habit().forDay(d.dayOfWeek), zone())
            val reason = if (odd) runCatching { dev.phonerecall.alarms.AlarmPlanner.classify(c, NextAlarm(at, null), w).phrase }.getOrNull()?.ifEmpty { null } else null
            dev.phonerecall.brief.AlarmInfo(at, reason, odd)
        }
        dev.phonerecall.brief.BriefHooks.wake = dev.phonerecall.brief.WakeTime { d -> watch().habit().forDay(d.dayOfWeek)?.let { LocalTime.of(it / 60, it % 60) } }

        dev.phonerecall.alarms.AlarmHooks.onDismissed = { ctx, reason -> dev.phonerecall.brief.BriefCore.onAlarmDismissed(ctx, reason) }
        dev.phonerecall.alarms.AlarmHooks.habit = { ctx ->
            val r = dev.phonerecall.rhythms.Rhythms.signal(ctx)
            object : dev.phonerecall.alarms.HabitSignal {
                override fun during() = r.during(ZonedDateTime.now()) != null
                override fun shouldHold() = r.shouldHold(ZonedDateTime.now(), urgent = false)
                // The nudge only goes out when tomorrow starts early.
                override fun bedtimeLine() = r.bedtimeLine(dev.phonerecall.rhythms.Habits.eveningOf(System.currentTimeMillis(), zone()), earlyStart = true)
            }
        }
        dev.phonerecall.alarms.AlarmHooks.hazard = { ctx ->
            dev.phonerecall.moments.MomentsCore.hazards(ctx, dev.phonerecall.odyssey.OdysseyStore.load(ctx)).tomorrow(ZonedDateTime.now())
                ?.let { "${it.words}; ${it.advice.replaceFirstChar(Char::lowercase)}" }
        }

        dev.phonerecall.argo.ArgoLinks.alarms = dev.phonerecall.argo.AlarmWatch { watch().next()?.at }
        dev.phonerecall.argo.ArgoLinks.clocks = dev.phonerecall.argo.TwoClocks { z, label -> dev.phonerecall.brain.TwoClocks.trip = z?.let { it to label } }

        dev.phonerecall.data.Milestones.register("car") { dev.phonerecall.odyssey.CarMilestones.of(dev.phonerecall.odyssey.OdysseyStore.load(c)) }
        dev.phonerecall.data.Milestones.register("theogony") {
            dev.phonerecall.theogony.TheogonyHub.get(c).milestones().confirmed().mapNotNull { m -> m.full?.let { dev.phonerecall.data.Milestone(it, m.label, "Theogony") } }
        }
    }

    /** Oneiroi's notes to Iris: into her room as Athena's, once paired. */
    fun irisNotes(app: Context) = dev.phonerecall.oneiroi.IrisLink { e ->
        DeviceKeys.paired(app) && runCatching { Gateway.sendAsAthena("iris", dev.phonerecall.mind.stateJson.writeValueAsString(e), e["id"].toString()) }.isSuccess
    }

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Today's first alarm: one that already rang that day, else the next one when it falls on it. */
    fun firstAlarm(seen: List<Long>, next: Long?, date: LocalDate, now: Long, zone: ZoneId): Long? =
        (seen.filter { it <= now } + listOfNotNull(next)).filter { day(it, zone) == date }.minOrNull()

    /** An hour or more off his usual for that weekday. */
    fun unusual(at: Long, usual: Int?, zone: ZoneId): Boolean = usual != null && kotlin.math.abs(Days.minuteOfDay(at, zone) - usual) >= 60
}
