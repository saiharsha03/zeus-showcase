package dev.phonerecall.odyssey

import dev.phonerecall.data.Availability
import dev.phonerecall.data.DayType
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * "WFH today?": the calendar says office day, but well past his usual leaving time he is still at home.
 * Asked once a day at most, only in the morning; Yes makes today a remote day for everyone who reads
 * availability, No does nothing.
 */
object OfficeDay {
    /** 8:40, or 20 minutes after he usually leaves on this weekday when Odyssey has learned that. */
    fun askAt(stats: CommuteStats, now: ZonedDateTime): Int = stats.departures[now.dayOfWeek]?.plus(20) ?: (8 * 60 + 40)

    fun key(day: LocalDate) = "wfh:$day"

    /** The real arrival: a geofence entry at the office today, or today's open stay at it. */
    fun arrived(d: OdysseyData, now: ZonedDateTime): Boolean {
        val work = d.work() ?: return false
        val day = now.toLocalDate()
        fun today(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(now.zone).toLocalDate() == day
        return d.arrivals.any { it.kind == Kind.WORK && today(it.at) } ||
            d.stay?.let { s -> Geo.meters(work.lat, work.lng, s.lat, s.lng) < PlaceLearner.CLUSTER_M && today(s.start) } == true
    }

    /** `atHome` is Odyssey's open stay at Home; null (unknown) never asks. `arrived`: he is (or was) at the office today. */
    fun due(today: Availability?, stats: CommuteStats, now: ZonedDateTime, atHome: Boolean?, driving: Boolean, ran: Map<String, Long>, arrived: Boolean = false): Boolean {
        if (arrived) return false
        if (today?.type != DayType.WORKDAY || today.date != now.toLocalDate()) return false
        if (atHome != true || driving || key(now.toLocalDate()) in ran) return false
        val m = now.toLocalTime().toSecondOfDay() / 60
        return m >= askAt(stats, now) && m < 12 * 60
    }

    fun note(now: ZonedDateTime): RoadNote {
        val m = now.toLocalTime().let { it.hour * 60 + it.minute }
        return RoadNote(
            key(now.toLocalDate()), "wfh", now.toInstant().toEpochMilli(), "WFH today?",
            "It's an office day and you're still home at ${Clock.hm(m)}. Yes makes today a remote day for Athena and the agents.",
            expiresAt = now.toLocalDate().atTime(12, 0).atZone(now.zone).toInstant().toEpochMilli(),
        )
    }
}
