package dev.phonerecall.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What an event is about. Decides its tint everywhere (Athena's tints, not the calendar's colours). */
enum class EventCategory(val label: String) {
    WORK("Work"), PERSONAL("Personal"), SPORT("Sport"), BIRTHDAY("Birthday"), TRAVEL("Travel"), OOO("Out of office"), HOLIDAY("Holiday"),
}

/** One event instance, as Athena sees it. Contact birthdays use calendarId = -1. */
@Immutable
data class CalItem(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val category: EventCategory,
    val calendarName: String,
    /** A place name only: the part before the first comma, dropped if it looks like a street address. */
    val place: String?,
    val notes: String?,
    val recurring: Boolean,
    /** The event's own IANA zone, or India's for an India person's birthday. Null when it has none. */
    val timeZone: String? = null,
) {
    val key: String get() = "$eventId:$begin"
}

/** Events read for Home (today to a week ahead). `loaded` is false until the first read finishes. */
@Immutable
data class CalendarSnapshot(val allowed: Boolean, val events: List<CalItem>, val loaded: Boolean) {
    companion object { val Unloaded = CalendarSnapshot(false, emptyList(), false) }
}

/** Reads the system calendar provider live. Nothing is copied or stored. Every method does IO. */
class CalendarSource(private val context: Context) {
    fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every calendar on the phone, visible or not, classified. */
    fun calendars(): List<CalendarInfo> {
        if (!allowed()) return emptyList()
        val out = ArrayList<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val type = c.getString(2).orEmpty()
                    val owner = c.getString(3).orEmpty()
                    if (MyAccounts.notMine(c.getString(7))) continue
                    val kind = CalendarInfo.classify(name, type, owner, visible = c.getInt(4) == 1, primary = !c.isNull(5) && c.getInt(5) == 1)
                    out += CalendarInfo(c.getLong(0), name, type, kind, c.getInt(6))
                }
            }
        } catch (_: SecurityException) { return emptyList() }
        return out
    }

    /** Home's window: the start of today to eight days ahead. */
    fun read(calendars: Map<Long, CalendarInfo>, zone: ZoneId = ZoneId.systemDefault()): CalendarSnapshot {
        if (!allowed()) return CalendarSnapshot(false, emptyList(), true)
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(9).atStartOfDay(zone).toInstant().toEpochMilli()
        return CalendarSnapshot(true, readRange(from, to, calendars, zone), true)
    }

    /** All instances overlapping [from, to), every calendar (relevance decides what shows). */
    fun readRange(from: Long, to: Long, calendars: Map<Long, CalendarInfo>, zone: ZoneId = ZoneId.systemDefault()): List<CalItem> {
        if (!allowed()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            // All-day instances are stored in UTC; widen the window so they are not cut off, then trim below.
            ContentUris.appendId(it, from - DAY)
            ContentUris.appendId(it, to + DAY)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.EVENT_TIMEZONE,
        )
        val out = ArrayList<CalItem>()
        try {
            context.contentResolver.query(uri, projection, dev.phonerecall.data.MyAccounts.instancesWhere(context), null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val allDay = c.getInt(4) == 1
                    var begin = c.getLong(2)
                    var end = c.getLong(3)
                    if (allDay) { begin = utcDateToLocal(begin, zone); end = utcDateToLocal(end, zone) }
                    if (end <= from || begin >= to) continue
                    val title = c.getString(1)?.trim().orEmpty().ifEmpty { "Busy" }
                    val calId = c.getLong(6)
                    val cal = calendars[calId]
                    out += CalItem(
                        eventId = c.getLong(0), calendarId = calId, title = title, begin = begin, end = end, allDay = allDay,
                        category = EventKinds.categorize(title, cal, if (c.isNull(10)) null else c.getInt(10), allDay),
                        calendarName = c.getString(7).orEmpty(),
                        place = placeName(c.getString(8)),
                        notes = c.getString(9)?.trim()?.ifEmpty { null },
                        recurring = !c.getString(11).isNullOrEmpty(),
                        timeZone = c.getString(12)?.ifEmpty { null },
                    )
                }
            }
        } catch (_: SecurityException) { return emptyList() }
        return out
    }

    private fun placeName(raw: String?): String? {
        val first = raw?.substringBefore(',')?.substringBefore('\n')?.trim().orEmpty()
        if (first.isEmpty() || first.first().isDigit()) return null
        return first
    }

    private fun utcDateToLocal(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object { const val DAY = 86_400_000L }
}

/** Sorts events into categories. Titles and the calendar's kind decide; nothing leaves the phone. */
object EventKinds {
    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), "")

    /**
     * One event per normalized title and day across calendars (two holiday calendars both carry
     * Dussehra). Keeps his own calendar's copy first, then work, then anything else.
     */
    fun dedupe(items: List<CalItem>, calendars: Map<Long, CalendarInfo>, zone: ZoneId = ZoneId.systemDefault()): List<CalItem> {
        fun rank(e: CalItem) = when (calendars[e.calendarId]?.kind) {
            CalKind.OWN -> 0; CalKind.WORK -> 1; CalKind.BIRTHDAYS -> 2; null -> 5; else -> 3
        }
        return items.groupBy { Triple(norm(it.title), Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate(), it.allDay) }
            .values.map { group -> group.minBy { rank(it) } }
            .sortedBy { it.begin }
    }

    private val OOO = listOf("ooo", "out of office", "out-of-office", "pto", "vacation", "annual leave", "on leave", "leave day", "holiday off", "day off", "off work")
    private val TRAVEL = listOf("flight", "fly to", "airport", "train to", "amtrak", "bus to", "hotel", "check-in", "check in", "trip to", "drive to", "road trip", "boarding")
    private val SPORT = listOf("grand prix", "qualifying", "sprint", "formula 1", "f1 ", " vs ", " vs. ", " v ", "match", "barcelona", "inter miami", "punjab kings", "ipl", "premier league", "la liga", "wwe")

    fun isOoo(title: String): Boolean {
        val t = " ${title.lowercase()} "
        return OOO.any { w -> if (w == "ooo" || w == "pto") Regex("\\b$w\\b").containsMatchIn(t) else t.contains(w) }
    }

    /**
     * `availability` is Events.AVAILABILITY. Android defines busy (0), free (1) and tentative (2); a sync
     * adapter that writes anything else (Outlook's out-of-office) is read as OOO on a work calendar.
     */
    fun categorize(title: String, cal: CalendarInfo?, availability: Int?, allDay: Boolean): EventCategory {
        val t = " ${title.lowercase()} "
        return when {
            Relevance.isBirthday(title, cal) -> EventCategory.BIRTHDAY
            isOoo(title) -> EventCategory.OOO
            cal?.kind == CalKind.WORK && availability != null && availability > 2 -> EventCategory.OOO
            cal?.kind == CalKind.HOLIDAY -> EventCategory.HOLIDAY
            TRAVEL.any { t.contains(it) } -> EventCategory.TRAVEL
            cal?.kind == CalKind.SPORTS || SPORT.any { t.contains(it) } -> EventCategory.SPORT
            cal?.kind == CalKind.WORK -> EventCategory.WORK
            else -> EventCategory.PERSONAL
        }
    }
}
