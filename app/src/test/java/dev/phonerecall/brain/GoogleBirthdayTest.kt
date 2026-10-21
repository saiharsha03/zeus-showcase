package dev.phonerecall.brain

import com.anthropic.core.jsonMapper
import dev.phonerecall.data.CalendarAuth
import dev.phonerecall.data.CalendarBirthdays
import dev.phonerecall.data.CalendarError
import dev.phonerecall.data.GoogleCalendarAuth
import dev.phonerecall.music.Http
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleBirthdayTest {
    private val now = java.time.ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val store = MemoryStore()
    private val state = FakeState()
    private val exec = FakeExecutor()
    private val fates = Fates(store, state, exec, { _, _, _ -> }, { now })

    private fun check(kind: ProposalKind, vararg args: Pair<String, String>) = Validate.step(kind, mapOf(*args), state, now)
    private fun ok(kind: ProposalKind, vararg args: Pair<String, String>) = (check(kind, *args) as Check.Ok).step
    private fun sam(vararg args: Pair<String, String>) = ok(ProposalKind.ADD_BIRTHDAY, "name" to "Sam", "date" to "10-20", *args)
    private fun propose(kind: ProposalKind, vararg args: Pair<String, String>) =
        (fates.propose(listOf(RawStep(kind, mapOf(*args))), null, 1L) as ProposeResult.Created).proposal

    @Test fun aNewBirthdayIsOnlyANameAndADay() {
        val s = sam()
        assertNull(s.needs)
        assertEquals(setOf("name", "date"), s.payload.keys)               // no contact, no calendar id
        assertEquals("Save Sam's birthday (Oct 20) in Google Calendar. It repeats every year.", s.summary)
        val facts = Validate.facts(s, state.zone).toMap()
        assertEquals("Google Calendar, Birthdays", facts["Calendar"])
        assertNull(facts["Contact"])
    }

    @Test fun alreadySavedOnThatDayIsNothingToDo() {
        state.birthdays = mapOf("Sam" to listOf(SavedBirthday("ev1", "Sam's birthday", "10-20")))
        assertEquals("Already saved: Sam's birthday (Oct 20) is in Google Calendar", (check(ProposalKind.ADD_BIRTHDAY, "name" to "Sam", "date" to "10-20") as Check.Bad).reason)
    }

    @Test fun anotherDayAsksToReplace() {
        state.birthdays = mapOf("Sam" to listOf(SavedBirthday("ev1", "Sam's birthday", "10-03")))
        val s = sam()
        assertNull(s.needs)
        assertEquals("ev1", s.payload["old_event_id"])
        assertEquals("10-03", s.payload["old_date"])
        assertEquals("Sam's birthday is saved as Oct 3 in Google Calendar. Replace it with Oct 20? It repeats every year.", s.summary)
        assertEquals("Oct 3", Validate.facts(s, state.zone).toMap()["Saved now"])
    }

    @Test fun googleCalendarIsAllowedOnceOnTheCardThenRechecked() {
        state.googleCalendar = false
        val p = propose(ProposalKind.ADD_BIRTHDAY, "name" to "Sam", "date" to "10-20")
        assertEquals("google_calendar_auth", p.needs)
        assertEquals("Allow Google Calendar first", (fates.confirmNow(p.id, store) as Decision.Refused).reason)
        assertTrue(exec.ran.isEmpty())
        // He allows it on the card: the hub re-checks the needs, and the duplicate check runs then.
        state.googleCalendar = true
        state.birthdays = mapOf("Sam" to listOf(SavedBirthday("ev1", "Sam", "10-03")))
        assertNull(fates.refresh(p.id)!!.needs)
        assertEquals("ev1", store.get(p.id)!!.steps[0].payload["old_event_id"])
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
    }

    @Test fun undoTakesTheBirthdayBack() {
        val p = propose(ProposalKind.ADD_BIRTHDAY, "name" to "Sam", "date" to "10-20")
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
        assertTrue(store.get(p.id)!!.undoable)
        assertTrue(fates.undo(p.id) is Decision.Undone)
        assertEquals(ProposalKind.ADD_BIRTHDAY, exec.undone.single().kind)
    }

    @Test fun birthdayEventsBecomeBirthdays() {
        val s = ok(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Sam's birthday", "start" to "2026-10-20", "all_day" to "true")
        assertEquals(ProposalKind.ADD_BIRTHDAY, s.kind)
        assertEquals("Sam", s.payload["name"])
        assertEquals("10-20", s.payload["date"])
        assertEquals(ProposalKind.ADD_BIRTHDAY, ok(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Mom bday 🎂", "start" to "2026-11-02T09:00").kind)
        // A party is an event, at its time.
        assertEquals(ProposalKind.ADD_CALENDAR_EVENT, ok(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Sam's birthday party", "start" to "2026-10-24T18:00").kind)
        // A proposal from the model's add_calendar_event lands as a birthday card.
        assertEquals(ProposalKind.ADD_BIRTHDAY, propose(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Birthday of Alex", "start" to "2026-12-01").steps.single().kind)
    }

    @Test fun birthdayTasksBecomeBirthdaysButTasksStayTasks() {
        assertEquals(ProposalKind.ADD_BIRTHDAY, ok(ProposalKind.ADD_TASK, "title" to "Sam’s bday", "due" to "2026-10-20").kind)
        assertEquals(ProposalKind.ADD_TASK, ok(ProposalKind.ADD_TASK, "title" to "Buy Sam's birthday gift", "due" to "2026-10-19").kind)
        assertEquals(ProposalKind.ADD_TASK, ok(ProposalKind.ADD_TASK, "title" to "Sam's birthday").kind)   // no day: nothing to save
    }

    @Test fun whatCountsAsABirthdayTitle() {
        assertEquals("Sam", Validate.birthdayOf("Sam's birthday"))
        assertEquals("James", Validate.birthdayOf("James' birthday!"))
        assertEquals("Alex Kumar", Validate.birthdayOf("Alex Kumar bday"))
        assertEquals("Maya", Validate.birthdayOf("Birthday: Maya"))
        assertNull(Validate.birthdayOf("Happy birthday Alex"))
        assertNull(Validate.birthdayOf("Plan birthday"))
        assertNull(Validate.birthdayOf("Birthday party for Alex"))
        assertNull(Validate.birthdayOf("Dinner with Sam"))
    }
}

/** Google Calendar as a queue of answers; every request is kept. */
class FakeCalendarHttp : Http {
    data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
    val calls = ArrayList<Call>()
    val answers = ArrayDeque<Pair<Int, String>>()
    override fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        calls += Call(method, url, headers, body)
        return answers.removeFirstOrNull() ?: (200 to "{}")
    }
}

class FakeCalendarAuth(var tokens: ArrayDeque<String?> = ArrayDeque(listOf("t1"))) : CalendarAuth {
    val cleared = ArrayList<String>()
    override fun token(): String? = if (tokens.size > 1) tokens.removeFirst() else tokens.firstOrNull()
    override fun clear(token: String) { cleared += token }
}

class CalendarBirthdaysTest {
    private val http = FakeCalendarHttp()
    private val auth = FakeCalendarAuth()
    private val cal = CalendarBirthdays(http, auth) { ZoneId.of("America/New_York") }
    private val json = jsonMapper()

    @Suppress("UNCHECKED_CAST")
    private fun body(c: FakeCalendarHttp.Call) = json.readValue(c.body, Map::class.java) as Map<String, Any?>

    @Test fun insertIsGoogleCalendarsOwnBirthdayWithOnlyANameAndADay() {
        http.answers += 200 to """{"id":"bday1","eventType":"birthday"}"""
        assertEquals("bday1", cal.insert("Sam", "10-20"))
        val c = http.calls.single()
        assertEquals("POST", c.method)
        assertEquals("https://www.googleapis.com/calendar/v3/calendars/primary/events", c.url)
        assertEquals("Bearer t1", c.headers["Authorization"])
        assertEquals(
            mapOf(
                "eventType" to "birthday",
                "summary" to "Sam's birthday",
                "start" to mapOf("date" to "${java.time.LocalDate.now(ZoneId.of("America/New_York")).year}-10-20"),
                "end" to mapOf("date" to "${java.time.LocalDate.now(ZoneId.of("America/New_York")).year}-10-21"),
                "recurrence" to listOf("RRULE:FREQ=YEARLY"),
                "visibility" to "private",
                "transparency" to "transparent",
                "birthdayProperties" to mapOf("type" to "birthday"),     // no contact, no customTypeName
            ),
            body(c),
        )
        assertTrue(NetGate.allows(c.url))
    }

    @Test fun february29thUsesGooglesLeapRuleOnALeapYear() {
        http.answers += 200 to """{"id":"leap"}"""
        cal.insert("Alex", "02-29")
        val b = body(http.calls.single())
        assertEquals(listOf("RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1"), b["recurrence"])
        val start = java.time.LocalDate.parse((b["start"] as Map<*, *>)["date"] as String)
        assertTrue(start.isLeapYear && start.monthValue == 2 && start.dayOfMonth == 29)
        assertEquals("${start.plusDays(1)}", (b["end"] as Map<*, *>)["date"])
    }

    @Test fun findListsOnlyBirthdayEventsForThatName() {
        http.answers += 200 to """{"items":[
            {"id":"a","summary":"Sam's birthday","eventType":"birthday","start":{"date":"1990-10-03"}},
            {"id":"b","summary":"Nina's birthday","eventType":"birthday","start":{"date":"1990-01-05"}},
            {"id":"c","summary":"Sam's birthday","status":"cancelled","start":{"date":"1990-10-04"}}
        ],"nextPageToken":"p2"}"""
        http.answers += 200 to """{"items":[{"id":"d","summary":"sam","eventType":"birthday","start":{"date":"1985-10-20"}}]}"""
        assertEquals(listOf(SavedBirthday("a", "Sam's birthday", "10-03"), SavedBirthday("d", "sam", "10-20")), cal.find("Sam"))
        val (first, second) = http.calls
        assertEquals("GET", first.method)
        assertTrue(first.url, first.url.contains("eventTypes=birthday") && first.url.contains("q=Sam"))
        assertTrue(second.url.contains("pageToken=p2"))
        assertTrue(http.calls.all { NetGate.allows(it.url) })
    }

    @Test fun replacingPutsTheNewDayInAndTheOldOneOutAndUndoReversesIt() {
        http.answers += 200 to """{"id":"new1"}"""
        http.answers += 204 to ""
        val undo = cal.save(mapOf("name" to "Sam", "date" to "10-20", "old_event_id" to "old1", "old_date" to "10-03"))
        assertEquals(mapOf("event_id" to "new1", "name" to "Sam", "old_date" to "10-03"), undo)
        assertEquals(listOf("POST", "DELETE"), http.calls.map { it.method })
        assertTrue(http.calls[1].url.endsWith("/events/old1"))
        http.calls.clear()
        http.answers += 204 to ""
        http.answers += 200 to """{"id":"back1"}"""
        assertTrue(cal.undo(undo))
        assertEquals("DELETE", http.calls[0].method)
        assertTrue(http.calls[0].url.endsWith("/events/new1"))
        assertEquals("POST", http.calls[1].method)
        assertTrue(http.calls[1].body!!.contains("-10-03"))
    }

    @Test fun aNewBirthdayIsUndoneByDeletingTheEventItMade() {
        http.answers += 200 to """{"id":"new1"}"""
        val undo = cal.save(mapOf("name" to "Sam", "date" to "10-20"))
        assertEquals(mapOf("event_id" to "new1", "name" to "Sam"), undo)
        http.calls.clear()
        http.answers += 204 to ""
        assertTrue(cal.undo(undo))
        val c = http.calls.single()
        assertEquals("DELETE", c.method)
        assertEquals("https://www.googleapis.com/calendar/v3/calendars/primary/events/new1", c.url)
        assertTrue(NetGate.allows(c.url))
    }

    @Test fun ifTheOldDayCantBeRemovedTheNewOneIsTakenBack() {
        http.answers += 200 to """{"id":"new1"}"""
        http.answers += 500 to ""
        try { cal.save(mapOf("name" to "Sam", "date" to "10-20", "old_event_id" to "old1", "old_date" to "10-03")); fail() } catch (_: CalendarError) { }
        assertEquals(listOf("POST", "DELETE", "DELETE"), http.calls.map { it.method })
        assertTrue(http.calls[2].url.endsWith("/events/new1"))
    }

    @Test fun anEventAlreadyGoneCountsAsDeleted() {
        http.answers += 410 to ""
        cal.delete("gone", "Sam")
        assertEquals(1, http.calls.size)
    }

    @Test fun noPermissionMeansNoRequest() {
        auth.tokens = ArrayDeque(listOf(null))
        try { cal.insert("Sam", "10-20"); fail() } catch (e: CalendarError) { assertEquals("Allow Google Calendar first", e.message) }
        assertTrue(http.calls.isEmpty())
    }

    @Test fun aRefusedTokenIsClearedAndTriedOnceMore() {
        auth.tokens = ArrayDeque(listOf("old", "fresh"))
        http.answers += 401 to ""
        http.answers += 200 to """{"id":"ok"}"""
        assertEquals("ok", cal.insert("Sam", "10-20"))
        assertEquals(listOf("old"), auth.cleared)
        assertEquals(listOf("Bearer old", "Bearer fresh"), http.calls.map { it.headers["Authorization"] })
    }

    @Test fun googlesOwnRefusalIsReportedAsItIs() {
        http.answers += 400 to """{"error":{"code":400,"message":"Invalid event type."}}"""
        try { cal.insert("Sam", "10-20"); fail() } catch (e: CalendarError) { assertEquals("Google Calendar refused that (400): Invalid event type.", e.message) }
    }

    @Test fun theScopeIsCalendarEventsOnly() {
        assertEquals("https://www.googleapis.com/auth/calendar.events", GoogleCalendarAuth.SCOPE)
    }
}

class CalendarNetGateTest {
    @Test fun opensOnlyHisPrimaryCalendarsEvents() {
        assertTrue(NetGate.allows("https://www.googleapis.com/calendar/v3/calendars/primary/events"))
        assertTrue(NetGate.allows("https://www.googleapis.com/calendar/v3/calendars/primary/events?eventTypes=birthday&q=Sam"))
        assertTrue(NetGate.allows("https://www.googleapis.com/calendar/v3/calendars/primary/events/abc123"))
        for (u in listOf(
            "https://www.googleapis.com/",
            "https://www.googleapis.com/calendar/v3/users/me/calendarList",
            "https://www.googleapis.com/calendar/v3/calendars/other@example.org/events",
            "https://www.googleapis.com/calendar/v3/calendars/primary/events/abc/instances",
            "https://www.googleapis.com/calendar/v3/calendars/primary/events/../acl",
            "https://www.googleapis.com/drive/v3/files",
            "https://www.googleapis.com/oauth2/v4/token",
            "http://www.googleapis.com/calendar/v3/calendars/primary/events",           // cleartext
            "https://www.googleapis.com:8443/calendar/v3/calendars/primary/events",     // other port
            "https://x@www.googleapis.com/calendar/v3/calendars/primary/events",        // user info
            "https://www.googleapis.com.evil.com/calendar/v3/calendars/primary/events", // suffix trick
            "https://oauth2.googleapis.com/token",                                      // Play services does the sign-in
        )) assertFalse(u, NetGate.allows(u))
        assertFalse(NetGate.CALENDAR_HOST in NetGate.HOSTS)
    }

    @Test fun theOutboundLogSaysWhatItIsFor() {
        val o = Outbound.default(NetGate.CALENDAR_HOST, NetGate.CALENDAR_PATH)
        assertEquals("Google Calendar birthdays", o.purpose)
        assertEquals(setOf(FieldCat.NAMES), o.fields)
    }

    @Test fun nothingWritesContactsAnymore() {
        val main = File(System.getProperty("user.dir"), "src/main")
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("WRITE_CONTACTS"))
        for (f in main.walkTopDown().filter { it.extension == "kt" }) {
            val t = f.readText()
            for (bad in listOf("WRITE_CONTACTS", "GoogleBirthdays", "ContentProviderOperation", "RawContacts.CONTENT_URI, values", "applyBatch(ContactsContract"))
                assertFalse("$bad in ${f.name}", t.contains(bad))
        }
    }
}
