package dev.phonerecall.data

import android.Manifest
import android.accounts.Account
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import com.anthropic.core.jsonMapper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.SavedBirthday
import dev.phonerecall.brain.Validate
import dev.phonerecall.clio.Clio
import dev.phonerecall.music.Http
import java.net.URLEncoder
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** A contact's birthday: first name and the day. No year, no number, no other contact detail is kept. */
@Immutable data class ContactBirthday(val name: String, val month: Int, val day: Int, val india: Boolean = false)

/**
 * Birthdays from Contacts (Event rows of type birthday). Needs READ_CONTACTS, asked in Settings only.
 * Reads the first name and the date, nothing else. Call on IO.
 */
class BirthdaySource(private val context: Context) {
    fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun read(): List<ContactBirthday> {
        if (!allowed()) return emptyList()
        val out = ArrayList<ContactBirthday>()
        val india = indiaNames()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val first = c.getString(0)?.trim()?.split(' ')?.firstOrNull().orEmpty()
                    val (m, d) = parse(c.getString(1)) ?: continue
                    if (first.isNotEmpty()) out += ContactBirthday(first, m, d, india = c.getString(0)?.trim()?.lowercase() in india)
                }
            }
        } catch (_: SecurityException) { return emptyList() }
        return out.distinct()
    }

    /** Lower-cased display names of contacts with a +91 number: their birthdays are dated in IST. */
    private fun indiaNames(): Set<String> {
        val out = HashSet<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) if (dev.phonerecall.brain.TwoClocks.isIndia(c.getString(1))) c.getString(0)?.trim()?.lowercase()?.let { out += it }
            }
        } catch (_: SecurityException) { }
        return out
    }

    /** "1990-03-14", "--03-14" or "03-14". */
    private fun parse(s: String?): Pair<Int, Int>? {
        val parts = s?.trim()?.trimStart('-')?.split('-')?.filter { it.isNotEmpty() } ?: return null
        val (m, d) = when (parts.size) { 3 -> parts[1] to parts[2]; 2 -> parts[0] to parts[1]; else -> return null }
        val mi = m.toIntOrNull() ?: return null
        val di = d.take(2).toIntOrNull() ?: return null
        return if (mi in 1..12 && di in 1..31) mi to di else null
    }

    companion object {
        /** All-day items for each birthday falling in [from, from + days). Calendar id -1 marks them. */
        fun asItems(list: List<ContactBirthday>, from: LocalDate, days: Int, zone: ZoneId): List<CalItem> {
            val out = ArrayList<CalItem>()
            for (i in 0 until days) {
                val d = from.plusDays(i.toLong())
                for (b in list) if (b.month == d.monthValue && b.day == d.dayOfMonth) {
                    val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
                    out += CalItem(
                        eventId = -(b.name.hashCode().toLong() and 0xffffffL) - 1, calendarId = -1, title = "${b.name}'s birthday",
                        begin = start, end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true,
                        category = EventCategory.BIRTHDAY, calendarName = "Contacts", place = null, notes = null, recurring = true,
                        timeZone = if (b.india) "Asia/Kolkata" else null,
                    )
                }
            }
            return out
        }
    }
}

/** Who may call Google Calendar for him: a fresh access token, or null while he has not allowed it. */
interface CalendarAuth {
    fun token(): String?
    /** Drops a token Google refused, so the next [token] is a fresh one. */
    fun clear(token: String) {}
}

class CalendarError(msg: String) : Exception(msg)

/**
 * Google Calendar birthdays, made the way Google Calendar's own "Birthday" option makes them: an event of type
 * birthday on his primary calendar with only a name and a day. No contact is made or changed.
 * What the API requires of a birthday event (developers.google.com/workspace/calendar/api/guides/event-types):
 * all-day and exactly one day long, recurrence RRULE:FREQ=YEARLY (Feb 29: BYMONTH=2;BYMONTHDAY=-1), visibility
 * private, transparency transparent, and birthdayProperties, if given, type birthday with no contact; only summary,
 * colorId and reminders may be added. Every call passes NetGate (www.googleapis.com, this calendar's events path
 * only) and so lands in the Outbound log; Clio gets the name only. Blocking; call on IO.
 */
class CalendarBirthdays(private val http: Http, private val auth: CalendarAuth, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val json = jsonMapper()

    /** His birthday events for [name] (the title is the name, or the name's birthday), with their days. */
    fun find(name: String): List<SavedBirthday> {
        val out = ArrayList<SavedBirthday>()
        var page: String? = null
        do {
            val m = req("GET", "$EVENTS?eventTypes=birthday&singleEvents=false&maxResults=2500&q=${enc(name)}" + (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            for (e in (m?.get("items") as? List<*>).orEmpty()) {
                val ev = e as? Map<*, *> ?: continue
                val summary = (ev["summary"] as? String)?.trim() ?: continue
                if (ev["status"] == "cancelled" || !(summary.equals(name, true) || Validate.birthdayOf(summary).equals(name, true))) continue
                val day = ((ev["start"] as? Map<*, *>)?.get("date") as? String)?.takeIf { it.length == 10 }?.substring(5) ?: continue
                out += SavedBirthday(ev["id"] as? String ?: continue, summary, day)
            }
            page = m?.get("nextPageToken") as? String
        } while (page != null)
        Clio.info("chronos", "birthdays checked", name)
        return out
    }

    /** A new birthday event for [name] on [date] ("MM-DD"). Returns its event id. */
    fun insert(name: String, date: String): String {
        val (m, d) = date.split('-').map { it.toInt() }
        val leap = m == 2 && d == 29
        var year = LocalDate.now(zone()).year
        while (leap && !Year.isLeap(year.toLong())) year--
        val start = LocalDate.of(year, m, d)
        val body = mapOf(
            "eventType" to "birthday",
            "summary" to "$name's birthday",
            "start" to mapOf("date" to "$start"),
            "end" to mapOf("date" to "${start.plusDays(1)}"),
            "recurrence" to listOf(if (leap) "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1" else "RRULE:FREQ=YEARLY"),
            "visibility" to "private",
            "transparency" to "transparent",
            "birthdayProperties" to mapOf("type" to "birthday"),
        )
        val id = req("POST", EVENTS, body)?.get("id") as? String ?: throw CalendarError("Google Calendar did not return the birthday")
        Clio.info("chronos", "birthday added", name)
        return id
    }

    /** Deletes one birthday event. Already gone counts as done. */
    fun delete(eventId: String, name: String) {
        req("DELETE", "$EVENTS/${enc(eventId)}", gone = true)
        Clio.info("chronos", "birthday removed", name)
    }

    /**
     * Saves a confirmed ADD_BIRTHDAY payload ("name", "date" MM-DD, and "old_event_id" and "old_date" when it replaces
     * another day). The new event goes in first; if the old one can't be deleted, the new one is taken back. Returns
     * what undo needs.
     */
    fun save(p: Map<String, String>): Map<String, String> {
        val name = p.getValue("name")
        val id = insert(name, p.getValue("date"))
        val old = p["old_event_id"] ?: return mapOf("event_id" to id, "name" to name)
        try { delete(old, name) } catch (e: Exception) { runCatching { delete(id, name) }; throw e }
        return mapOf("event_id" to id, "name" to name, "old_date" to p.getValue("old_date"))
    }

    /** Takes a saved birthday back: deletes the event Zeus made and, after a replace, puts the old day back. */
    fun undo(u: Map<String, String>): Boolean {
        val name = u.getValue("name")
        delete(u.getValue("event_id"), name)
        u["old_date"]?.let { insert(name, it) }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun req(method: String, url: String, body: Map<String, Any?>? = null, gone: Boolean = false, again: Boolean = true): Map<String, Any?>? {
        val t = auth.token() ?: throw CalendarError("Allow Google Calendar first")
        val (code, text) = http.call(method, url, mapOf("Authorization" to "Bearer $t", "Content-Type" to "application/json"), body?.let { json.writeValueAsString(it) })
        if (code == 401 && again) { auth.clear(t); return req(method, url, body, gone, again = false) }
        when {
            gone && (code == 404 || code == 410) -> return null
            code == 401 -> throw CalendarError("Google Calendar needs you to allow it again")
            code !in 200..299 -> throw CalendarError("Google Calendar refused that ($code)" + (apiMessage(text)?.let { ": $it" } ?: ""))
        }
        return if (text.isBlank()) null else runCatching { json.readValue(text, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    /** Google's own words for a refusal (error.message), so a rule it enforces reads as itself. */
    private fun apiMessage(text: String): String? = runCatching { ((json.readValue(text, Map::class.java)["error"] as? Map<*, *>)?.get("message") as? String)?.take(160) }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val EVENTS = "https://${NetGate.CALENDAR_HOST}${NetGate.CALENDAR_PATH}"
    }
}

/**
 * Google sign-in for Calendar birthdays on his account ([MyAccounts.PRIMARY]) through Google Play services'
 * AuthorizationClient, with the one narrow scope [SCOPE]. Play services holds and refreshes the token (its own
 * process does that network); Zeus asks for one only when it calls the API. Matched to the Android OAuth client of
 * Cloud project your-gcp-project by package and SHA-1 (docs/HESTIA.md), so no client id is in the code.
 */
class GoogleCalendarAuth(private val context: Context) : CalendarAuth {
    /** Silent: the token once he has allowed it, null while the card still has to ask. Blocking; call on IO. */
    override fun token(): String? = try {
        Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 20, TimeUnit.SECONDS).takeIf { !it.hasResolution() }?.accessToken
    } catch (e: Exception) { Clio.warn("chronos", "google calendar sign-in failed", e.javaClass.simpleName); null }

    override fun clear(token: String) {
        runCatching { Tasks.await(Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()), 10, TimeUnit.SECONDS) }
    }

    companion object {
        /** Events only: never the broader calendar scope. */
        const val SCOPE = "https://www.googleapis.com/auth/calendar.events"

        fun request(): AuthorizationRequest = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(SCOPE))).setAccount(Account(MyAccounts.PRIMARY, "com.google")).build()

        /** The card's "Allow Google Calendar": Google's consent sheet when it is needed ([resolve]), then [done]. Main thread. */
        fun ask(context: Context, resolve: (IntentSender) -> Unit, done: () -> Unit) {
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { r -> val s = r.pendingIntent?.intentSender; if (r.hasResolution() && s != null) resolve(s) else done() }
                .addOnFailureListener { e -> Clio.warn("chronos", "google calendar consent failed", e.javaClass.simpleName); done() }
        }
    }
}

/**
 * Phase 2 hook: the brain proposes a "Send a wish" or "Remind me to call" card for a birthday. The
 * stub proposes nothing.
 */
fun interface BirthdayProposer {
    fun proposals(name: String, date: LocalDate): List<String>
}

val NoProposals = BirthdayProposer { _, _ -> emptyList() }
