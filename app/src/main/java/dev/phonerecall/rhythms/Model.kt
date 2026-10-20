package dev.phonerecall.rhythms

/*
 * Rhythms' data: when he opens which apps, and who he calls when. On the phone only, sealed with an Aegis
 * Keystore key (RhythmsStore). The brain gets RhythmsBrain.facts() words only ("calls Amma daily ~8 AM IST"),
 * never a number, a JID or a raw session. Every class has defaults so Jackson can read it back.
 */

/** One stretch of an app on screen. */
data class Session(val pkg: String = "", val start: Long = 0, val end: Long = 0)

/**
 * A learned app habit: on [days] (DayOfWeek values, the evening's day for a session past midnight), from
 * [startMin] to [endMin] local minutes (end below start means past midnight). [strength] is the share of
 * those days it actually happened in the last four weeks.
 */
data class AppHabit(
    val pkg: String = "",
    val label: String = "",
    val game: Boolean = false,
    val days: List<Int> = emptyList(),
    val startMin: Int = 0,
    val endMin: Int = 0,
    val strength: Double = 0.0,
    val perWeek: Double = 0.0,
    val learnedAt: Long = 0,
) {
    val strong: Boolean get() = strength >= Habits.STRONG
}

/** A usual session that didn't happen. Stored quietly, never notified. */
data class Skip(val pkg: String = "", val date: String = "")

enum class CallSource { CALL_LOG, WHATSAPP }

/** One call. [key] is People.keyOf(name or number); no number is stored. */
data class CallRecord(
    val key: String = "",
    val name: String = "",
    val at: Long = 0,
    val seconds: Int = 0,
    val source: CallSource = CallSource.CALL_LOG,
    /** Talked (a connected call), not just rang. */
    val connected: Boolean = true,
    /** He placed it; null when the source can't tell (a WhatsApp ongoing call). */
    val outgoing: Boolean? = null,
    val video: Boolean = false,
    /** "IN", "US" or "OTHER" when the number told; it decides the person's zone. */
    val country: String? = null,
)

/**
 * A recurring call: [minute] of the day in [zone] (the person's zone, "Asia/Kolkata" for India), every day
 * or on [weekday] (DayOfWeek value) only.
 */
data class CallHabit(
    val key: String = "",
    val name: String = "",
    val zone: String = "",
    val minute: Int = 0,
    val weekday: Int = 0,
    val strength: Double = 0.0,
    val contactId: Long? = null,
    val learnedAt: Long = 0,
) {
    val daily: Boolean get() = weekday == 0
}

enum class NudgeStage { NONE, POSTED, DONE }

/** One call habit's nudge on one day (the person's date). At most the first post, one reminder and two snoozes. */
data class NudgeDay(
    val key: String = "",
    val date: String = "",
    val stage: NudgeStage = NudgeStage.NONE,
    val reminded: Boolean = false,
    val snoozes: Int = 0,
    /** A Later is waiting: the next post is that one. */
    val snoozed: Boolean = false,
    val nextAt: Long = 0,
    /** Why it ended: called, not today, no answer, driving, quiet hours. */
    val outcome: String = "",
)

data class RhythmsSettings(
    val on: Boolean = true,
    val nudges: Boolean = true,
    /** Packages he excluded himself. */
    val excluded: List<String> = emptyList(),
    /** "Check on home": only a heatwave or heavy rain where home is. */
    val homeWeather: Boolean = true,
    val homeCity: String = "Pune",
    val homeLat: Double = 18.5204,
    val homeLon: Double = 73.8567,
    /** Packages the default categories excluded that he let in anyway. */
    val included: List<String> = emptyList(),
)

data class RhythmsData(
    val settings: RhythmsSettings = RhythmsSettings(),
    val sessions: List<Session> = emptyList(),
    val habits: List<AppHabit> = emptyList(),
    val skips: List<Skip> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
    val callHabits: List<CallHabit> = emptyList(),
    val nudges: List<NudgeDay> = emptyList(),
    /** Usage events were read up to here. */
    val scannedTo: Long = 0,
    /** The first day anything was seen, for "four weeks" before there are four weeks. */
    val since: Long = 0,
    /** "Forget": a package or person key, and when. Nothing older is learned from again (the call log keeps it). */
    val forgot: Map<String, Long> = emptyMap(),
    val callVia: Map<String, String> = emptyMap(),
    val callNumber: Map<String, String> = emptyMap(),
    val ran: Map<String, Long> = emptyMap(),
    /** When each tracked person (India, someone he calls) had a WhatsApp notification. Times only, never text. */
    val seen: Map<String, List<Long>> = emptyMap(),
    val bookmarks: List<Bookmark> = emptyList(),
    val homeAlerts: List<HomeAlert> = emptyList(),
    val nextId: Long = 1,
)

/** Something he wants to tell a person next time they talk. */
data class Bookmark(val id: Long = 0, val key: String = "", val person: String = "", val note: String = "", val at: Long = 0, val done: Boolean = false)

/** A weather extreme where home is: "heat" (max °C) or "rain" (mm in the day), on [date] there. */
data class HomeAlert(val date: String = "", val kind: String = "", val value: Double = 0.0)
