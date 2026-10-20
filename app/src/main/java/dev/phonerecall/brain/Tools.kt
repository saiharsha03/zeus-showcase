package dev.phonerecall.brain

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Tool
import dev.phonerecall.data.Availability
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.ScoredCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the read tools can see. The ViewModel supplies it from the state it already holds. */
interface BrainReads {
    fun now(): ZonedDateTime
    /** Events in [from, to) after the relevance layer (what Chronos shows). */
    fun relevantEvents(from: Long, to: Long): List<CalItem>
    /** Every event in [from, to), hidden calendars included. */
    fun allEvents(from: Long, to: Long): List<CalItem>
    fun tasks(): TaskSnapshot
    fun closet(): ClosetSnapshot
    fun homeCards(): List<ScoredCard>
    fun availability(): Map<LocalDate, Availability>
    fun writableCalendars(): List<CalendarChoice>
}

/** Phase 2c: what Delphi's tier 0-1 tools reach beyond the phone's own state. */
interface DelphiExtras {
    /** Alexandria, cited snippets as JSON. */
    fun searchMemory(query: String, k: Int): String
    /** Starts a long job for Athena's circle (tier 1: no card to confirm). Returns one line for the model. */
    fun handOff(title: String, brief: String, protege: String?): String
    /** Metron in words: Iris's and Atlas's plan windows, Athena's cap, the 90% rule. */
    fun usage(): String = "Plan usage isn't available on this phone yet."
}

/** One tool call's answer. `proposal` is set when a write tool parked one. */
data class ToolOutcome(val text: String, val isError: Boolean = false, val proposal: Proposal? = null, val decision: Decision? = null)

/**
 * Athena's tools. Read tools (tier 0) answer at once. Write tools never execute: they hand the
 * arguments to Fates.propose, which validates them against local data and parks a proposal.
 */
class ToolBox(
    private val reads: BrainReads, private val fates: Fates, private val extras: DelphiExtras? = null, private val music: dev.phonerecall.music.MusicReads? = null,
    /** Calypso deposit and Aegis reads, logged as Delphi. Nothing here opens Calypso. */
    private val vault: dev.phonerecall.vault.VaultTools? = null,
    /** Hestia's devices (read only here; home_control only proposes). */
    private val home: dev.phonerecall.hestia.HomeReads? = null,
    /** The next system alarm, the ones Zeus set and his habit. */
    private val alarms: AlarmReads? = null,
    /** Argo: note a trip (tier 1, his own trip list, logged) and read trips (city names only). */
    private val trips: dev.phonerecall.argo.TripTools? = null,
) {
    private val json = jsonMapper()
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val day = DateTimeFormatter.ofPattern("EEEE", Locale.US)

    /** Fixed order and wording, so the tools prefix stays byte-identical and cacheable. */
    val definitions: List<Tool> by lazy { READ_TOOLS + WRITE_TOOLS + VAULT_TOOLS + TRIP_TOOLS }

    fun isWrite(name: String) = name == "propose_plan" || name == "suggest_timers" || ProposalKind.tool(name) != null

    fun run(name: String, input: Map<String, Any?>, conversationId: Long?): ToolOutcome = try {
        when (name) {
            "now" -> data(now())
            "read_calendar" -> data(calendar(input))
            "read_tasks" -> data(tasks(input))
            "read_closet" -> data(closet(input))
            "read_home_cards" -> data(cards())
            "read_availability" -> data(availability(input))
            "search_memory" -> extras?.let { ToolOutcome(it.searchMemory(input["query"]?.toString().orEmpty(), int(input, "k", 1..12, 6))) }
                ?: ToolOutcome("Alexandria isn't ready on this phone yet.", isError = true)
            "hand_off" -> extras?.let { e ->
                val title = input["title"]?.toString()?.trim().orEmpty()
                if (title.isEmpty()) ToolOutcome("title is missing", isError = true)
                else ToolOutcome(e.handOff(title.take(80), input["brief"]?.toString().orEmpty().take(1200), input["protege"]?.toString()))
            } ?: ToolOutcome("Athena's circle isn't set up yet. Add the key in Calypso first.", isError = true)
            "read_usage" -> extras?.let { ToolOutcome(it.usage()) } ?: ToolOutcome("Plan usage isn't available on this phone yet.", isError = true)
            "read_music" -> music?.let { data(it.summary()) } ?: ToolOutcome("Music isn't set up on this phone.", isError = true)
            "home_status" -> home?.let { data(it.status()) } ?: ToolOutcome("Hestia isn't set up on this phone.", isError = true)
            "read_alarms" -> alarms?.let { data(it.summary(input["open"]?.toString() == "true")) } ?: ToolOutcome("Alarms aren't available on this phone.", isError = true)
            "note_trip" -> trips?.let { t ->
                val place = input["place"]?.toString()?.trim().orEmpty()
                if (place.isEmpty()) ToolOutcome("place is missing", isError = true)
                else ToolOutcome(t.note(place.take(60), input["when"]?.toString()?.take(80), input["details"]?.toString()?.take(300), input["budget"]?.toString()?.toDoubleOrNull()))
            } ?: ToolOutcome("Argo isn't set up on this phone.", isError = true)
            "read_trips" -> trips?.let { data(mapOf("trips" to it.read())) } ?: ToolOutcome("Argo isn't set up on this phone.", isError = true)
            "propose_plan" -> plan(input, conversationId)
            "suggest_timers" -> timers(input["text"]?.toString().orEmpty(), conversationId)
            in dev.phonerecall.vault.VaultTools.NAMES -> vault?.run(name, input, dev.phonerecall.vault.Agent.DELPHI)?.let { (t, err) -> ToolOutcome(t, isError = err) }
                ?: ToolOutcome("The vaults aren't set up on this phone.", isError = true)
            else -> ProposalKind.tool(name)?.let { kind ->
                propose(listOf(RawStep(kind, strings(input))), null, conversationId)
            } ?: ToolOutcome("Unknown tool $name", isError = true)
        }
    } catch (e: Exception) {
        ToolOutcome("Tool $name failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private fun data(m: Any): ToolOutcome = ToolOutcome(json.writeValueAsString(m))

    /** One step of a kind no model calls directly (Athena's propose_schedule), through the same Fates path. */
    fun proposeStep(kind: ProposalKind, input: Map<String, Any?>, title: String? = null): ToolOutcome = try {
        propose(listOf(RawStep(kind, strings(input))), title, null)
    } catch (e: Exception) { ToolOutcome("Tool ${kind.wire} failed: ${e.message ?: e.javaClass.simpleName}", isError = true) }

    private fun strings(m: Map<String, Any?>): Map<String, String> =
        m.mapNotNull { (k, v) -> v?.let { k to (if (it is Double && it == Math.floor(it)) it.toLong().toString() else it.toString()) } }.toMap()

    private fun int(m: Map<String, Any?>, key: String, range: IntRange, default: Int): Int =
        (m[key]?.toString()?.toDoubleOrNull()?.toInt() ?: default).coerceIn(range)

    // ---- reads ----

    private fun now(): Map<String, Any?> {
        val n = reads.now()
        val av = reads.availability()
        return linkedMapOf(
            "now" to iso.format(n), "weekday" to day.format(n), "zone" to n.zone.id,
            "clocks" to TwoClocks(n.zone).contextLine(n.toInstant()),
            "different" to Different.block(Different.current(n)),
            "today" to av[n.toLocalDate()]?.type?.label, "tomorrow" to av[n.toLocalDate().plusDays(1)]?.type?.label,
            "place" to "Location is not available to Zeus yet.",
        ).apply {
            // Iaso: one derived line ("slept 5h, short"), only when he turned sharing on. Never raw health data.
            dev.phonerecall.iaso.IasoBrain.current(n)?.let { put("health", it) }
        }
    }

    private fun calendar(m: Map<String, Any?>): Map<String, Any?> {
        val everything = m["everything"]?.toString() == "true"
        val days = int(m, "days", 1..14, 2)
        val n = reads.now()
        val clocks = TwoClocks(n.zone)
        val from = n.toLocalDate().atStartOfDay(n.zone).toInstant().toEpochMilli()
        val to = n.toLocalDate().plusDays(days.toLong()).atStartOfDay(n.zone).toInstant().toEpochMilli()
        val events = (if (everything) reads.allEvents(from, to) else reads.relevantEvents(from, to)).take(120)
        return linkedMapOf(
            "view" to if (everything) "everything" else "relevant",
            "from" to iso.format(Instant.ofEpochMilli(from).atZone(n.zone)), "days" to days,
            // Titles, times and place names only: no notes or descriptions leave the phone.
            "events" to events.map { e ->
                linkedMapOf(
                    "title" to e.title, "start" to time(e.begin, e.allDay, n), "end" to time(e.end, e.allDay, n), "all_day" to e.allDay,
                    "calendar" to e.calendarName, "place" to e.place, "category" to e.category.label,
                    "both_clocks" to (clocks.bothFor(e) ?: clocks.dayStart(e)),
                ).filterValues { it != null }
            },
            "writable_calendars" to reads.writableCalendars().map { mapOf("id" to it.id, "name" to it.name) },
        )
    }

    private fun time(ms: Long, allDay: Boolean, n: ZonedDateTime): String = Instant.ofEpochMilli(ms).atZone(n.zone).let { if (allDay) it.toLocalDate().toString() else iso.format(it) }

    private fun tasks(m: Map<String, Any?>): Map<String, Any?> {
        val done = m["include_done"]?.toString() == "true"
        val t = reads.tasks()
        val n = reads.now()
        val rows = t.tasks.filter { done || it.list != TaskList.DONE }.take(150)
        val words = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
        return linkedMapOf("tasks" to rows.map { r ->
            linkedMapOf(
                "id" to r.id, "title" to r.title, "list" to r.list.label,
                "due" to r.due?.let { time(it, !r.dueHasTime, n) }, "project" to t.project(r.projectId)?.name,
                "two_minute" to r.quick.takeIf { it },
                "context" to r.context?.label, "waiting_on" to r.waitingOn,
                "follow_up" to r.followUp?.let { "check back " + words.format(Instant.ofEpochMilli(it).atZone(n.zone)) },
            ).filterValues { it != null }
        }, "stuck_projects" to dev.phonerecall.tasks.Gtd.stuck(t).map { it.name }.takeIf { it.isNotEmpty() }).filterValues { it != null }
    }

    private fun closet(m: Map<String, Any?>): Map<String, Any?> {
        val c = reads.closet()
        if (!c.imported) return mapOf("closet" to "Not imported on this phone yet.")
        val group = m["group"]?.toString()?.lowercase()
        val items = c.items.filter { !it.archived && (group == null || it.group.label.lowercase() == group) }.take(200)
        return mapOf("items" to items.map { i ->
            linkedMapOf("name" to i.name, "group" to i.group.label, "occasions" to i.occasions.sorted(), "formality" to i.formality, "temp" to i.temp)
        })
    }

    private fun cards(): Map<String, Any?> = mapOf("cards" to reads.homeCards().filter { !it.content.sample && !it.content.placeholder }.take(20).map { c ->
        linkedMapOf(
            "kind" to c.kicker, "title" to c.content.title, "lead" to c.content.lead, "body" to c.content.body,
            "facts" to c.content.facts.associate { it.label to it.value }.takeIf { it.isNotEmpty() },
            "tasks" to c.content.tasks.map { it.title }.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    })

    private fun availability(m: Map<String, Any?>): Map<String, Any?> {
        val days = int(m, "days", 1..9, 7)
        val today = reads.now().toLocalDate()
        val av = reads.availability()
        return mapOf("days" to (0 until days).mapNotNull { av[today.plusDays(it.toLong())] }.map {
            linkedMapOf("date" to it.date.toString(), "weekday" to day.format(it.date), "type" to it.type.label, "reason" to it.reason).filterValues { v -> v != null }
        })
    }

    // ---- writes: proposals only ----

    @Suppress("UNCHECKED_CAST")
    private fun plan(m: Map<String, Any?>, conversationId: Long?): ToolOutcome {
        val raw = (m["steps"] as? List<*>)?.map { s ->
            val step = s as? Map<String, Any?> ?: return ToolOutcome("Each step must be an object with tool and input", isError = true)
            val kind = ProposalKind.tool(step["tool"]?.toString().orEmpty()) ?: return ToolOutcome("Unknown step tool ${step["tool"]}", isError = true)
            RawStep(kind, strings((step["input"] as? Map<String, Any?>).orEmpty()))
        } ?: return ToolOutcome("steps is missing", isError = true)
        return propose(raw, m["title"]?.toString(), conversationId)
    }

    /** Moments reads what he said ("putting lasagna in", "washer 45 min") into one bundled timer plan, or a question. */
    private fun timers(text: String, conversationId: Long?): ToolOutcome =
        when (val s = dev.phonerecall.moments.MomentText.read(text, reads.now())) {
            is dev.phonerecall.moments.Said.Plan -> propose(s.plan.steps, s.plan.title, conversationId).let { o ->
                if (s.plan.title.endsWith("(a guess)") && !o.isError) o.copy(text = o.text + " The times are a guess from the usual box; say so, and offer to read his box.") else o
            }
            is dev.phonerecall.moments.Said.Ask -> ToolOutcome("Not proposed: the length is unknown. Ask the owner: ${s.question}")
            null -> ToolOutcome("Nothing to time in that. Use set_timer if he gave a length.", isError = true)
        }

    private fun propose(raw: List<RawStep>, title: String?, conversationId: Long?): ToolOutcome =
        when (val r = fates.propose(raw, title, conversationId)) {
            is ProposeResult.Invalid -> ToolOutcome("Not proposed: ${r.reason}. Fix the arguments, or ask the owner.", isError = true)
            is ProposeResult.Created -> {
                val p = r.proposal
                val text = when (val d = r.decision) {
                    is Decision.Done -> "Proposal #${p.id} matched the owner's auto-approve list and was done: ${p.title}. He can undo it from the card."
                    is Decision.Failed -> "Proposal #${p.id} was auto-approved but failed: ${d.reason}."
                    else -> when (p.needs) {
                        "calendar" -> "Proposal #${p.id} is on a card for the owner: ${p.title}. He has to pick the calendar on the card, then confirm. It has not happened."
                        "calendar_permission" -> "Proposal #${p.id} is on a card for the owner: ${p.title}. Zeus needs his permission to write to the calendar first. It has not happened."
                        "google_calendar_auth" -> "Proposal #${p.id} is on a card for the owner: ${p.title}. Zeus needs his one-time Google permission to add birthdays to Google Calendar first. It has not happened."
                        else -> "Proposal #${p.id} is on a card for the owner: ${p.title}. Status: awaiting his confirmation. It has not happened yet, so do not say it is done."
                    }
                }
                ToolOutcome(text, proposal = p, decision = r.decision)
            }
        }

    companion object {
        private fun schema(props: Map<String, Map<String, Any>>, required: List<String>) = Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder().apply { for ((k, v) in props) putAdditionalProperty(k, JsonValue.from(v)) }.build())
            .required(required)
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

        // Streaming + client tools: inputs stream eagerly; Fates.propose validates every argument before anything is parked.
        private fun tool(name: String, description: String, props: Map<String, Map<String, Any>> = emptyMap(), required: List<String> = emptyList()): Tool =
            Tool.builder().name(name).description(description).inputSchema(schema(props, required)).eagerInputStreaming(true).build()

        private fun str(d: String) = mapOf("type" to "string", "description" to d)
        private fun int(d: String) = mapOf("type" to "integer", "description" to d)
        private fun bool(d: String) = mapOf("type" to "boolean", "description" to d)

        val READ_TOOLS = listOf(
            tool("now", "The current local date, time, weekday and time zone, and whether today and tomorrow are office, remote, out-of-office, weekend or holiday days. Call this before anything that depends on the time. It also gives both clocks (Buffalo and India)."),
            tool(
                "read_calendar",
                "The owner's calendar events from the start of today. By default only the relevant view (what his Calendar screen shows). Set everything to true to include hidden and highlight-only calendars. Also lists the Google calendars Zeus can write to, with ids.",
                mapOf("days" to int("How many days from today, 1 to 14. Default 2."), "everything" to bool("Include every calendar, not just the relevant view.")),
            ),
            tool("read_tasks", "His tasks from Praxis (his task app, set up as GTD), with ids, list, due date, project, context (@office, @home, @errands, @calls, @computer, @anywhere), who a Waiting for is waiting on and the day to check back, and projects with no next action.", mapOf("include_done" to bool("Also include finished tasks."))),
            tool("read_closet", "The clothes in Venus, his closet, by name, group, occasions, formality and temperature.", mapOf("group" to str("Optional: tops, bottoms, shoes, outerwear, accessories or care."))),
            tool("read_home_cards", "The cards currently on Olympus, his home screen, in the order he sees them."),
            tool("read_availability", "Day types for the coming days: office day, remote day, out of office, weekend or holiday.", mapOf("days" to int("1 to 9. Default 7."))),
            tool(
                "search_memory",
                "Search Alexandria, his memory on the phone: Parnassus items, lists and notes, tasks, calendar, closet, notification summaries and documents he added. Returns cited snippets; cite them as [n].",
                mapOf("query" to str("What to look for, in plain words"), "k" to int("How many snippets, 1 to 12. Default 6.")), listOf("query"),
            ),
            tool("read_usage", "How much of Iris's and Atlas's plan windows (5-hour, 7-day) is used and when each resets, and how much of Athena's monthly cap is spent and the month-end pace. Check it before handing a worker a job: never give one to a worker above 90% of a window; use another worker who can do it, or wait for the reset."),
            tool("read_music", "What is playing now, his default player, and when Spotify is connected his playlists (with URIs), recently played tracks and devices."),
            tool("home_status", "His smart home from Hestia (Google Home): rooms, lights and plugs with device ids, on or off, brightness and white temperature, whether he is away, and his automations (read only). Locks, garage doors, cameras and security systems are listed as read only."),
            tool(
                "read_alarms",
                "His next alarm (time and which app set it), the alarms Zeus itself set, and the habit Zeus learned (like weekdays ~6:45 AM, weekends none). Android only reveals the next alarm; the others in his Clock app can't be read. Set open to true to open the Clock app's alarm list for him.",
                mapOf("open" to bool("Also open the Clock app's alarm list for him.")),
            ),
            tool(
                "hand_off",
                "Hand a long job to Athena's circle (research, plans over days, watching something, drafting). It runs in the background and comes back as a card. Starting it needs no confirmation; anything it wants to do at the end comes back as a proposal. Use it when he asks for something that takes longer than this chat, like research winter tires and come back tomorrow.",
                mapOf(
                    "title" to str("A short name for the job"),
                    "brief" to str("Everything the circle needs: the goal, what he said, deadlines, what a good result looks like"),
                    "protege" to str("Optional: Odysseus (long plans), Perseus (research), Atalanta (watching), Diomedes (errands), Arachne (drafting) or Annabeth (hard reasoning). Name one only if he asked for it or it is obvious."),
                ),
                listOf("title", "brief"),
            ),
        )

        val VAULT_TOOLS = listOf(
            tool(
                dev.phonerecall.vault.VaultTools.DEPOSIT, dev.phonerecall.vault.VaultTools.DEPOSIT_TEXT,
                mapOf(
                    "label" to str("A plain name for it, like Hetzner token (rotated 10-19). Not the value."),
                    "kind" to mapOf("type" to "string", "enum" to listOf("secret", "note"), "description" to "secret (default) or note"),
                    "value" to str("The secret or note itself"),
                ),
                listOf("label", "value"),
            ),
            tool(dev.phonerecall.vault.VaultTools.AEGIS_LIST, dev.phonerecall.vault.VaultTools.LIST_TEXT),
            tool(
                dev.phonerecall.vault.VaultTools.AEGIS_READ, dev.phonerecall.vault.VaultTools.READ_TEXT,
                mapOf("name" to str("The item name from aegis_list"), "why" to str("One line: what you need it for. The owner sees this.")),
                listOf("name", "why"),
            ),
        )

        val TRIP_TOOLS = listOf(
            tool(
                "note_trip",
                "Note a trip he mentions, like I'm going to Denver in March, in Argo (his trips). It is his own trip list, so it is saved at once and logged, no card. A vague when is fine; dates, bookings and plans join it later from his calendar and from Iris. Never books anything.",
                mapOf(
                    "place" to str("The city, like Denver"),
                    "when" to str("When, in his words or as dates: March, next month, Mar 3-7, 2027-03-03 to 2027-03-07"),
                    "details" to str("Optional: what he said about it, in a few words (who with, why)"),
                    "budget" to mapOf("type" to "number", "description" to "Optional: his budget for the whole trip in dollars, only if he said one"),
                ),
                listOf("place"),
            ),
            tool("read_trips", "His trips from Argo: city, dates, how he travels, plans, who with, and what is still missing. City names only; bookings and confirmations stay on the phone."),
        )

        private val ALARM = mapOf("hour" to int("0 to 23, local time"), "minute" to int("0 to 59"), "label" to str("Optional short label"))
        private val TIMER = mapOf("seconds" to int("Length in seconds, 1 to 86400"), "label" to str("Optional short label"))
        private val WITH = str("Optional: who this is for or with (a call, message or meeting), by the name the owner used. Zeus adds their local time and warns if it is the middle of the night there.")
        private val REMINDER = mapOf("at" to str("Local date and time, like 2026-10-20T08:00"), "text" to str("What the notification says"), "with" to WITH)
        private val TASK = mapOf(
            "title" to str("The task"), "list" to str("Optional: inbox (default), next, waiting, scheduled or someday"),
            "due" to str("Optional due date (2026-10-20) or date and time (2026-10-20T17:00)"),
            "context" to mapOf("type" to "string", "enum" to listOf("office", "home", "errands", "calls", "computer", "anywhere"), "description" to "Optional GTD context: where or with what it can be done"),
            "waiting_on" to str("Optional: who he is waiting on, by the name he used. Makes it a Waiting for."),
            "follow_up" to str("Optional: the day to check back on a Waiting for (2026-10-23)"),
        )
        private val COMPLETE = mapOf("task_id" to int("The id from read_tasks"))
        private val EVENT = mapOf(
            "title" to str("Event title"), "start" to str("Local start, like 2026-10-20T15:00, or a date for all-day"),
            "end" to str("Optional local end. Default one hour after start, or the next day for all-day"),
            "all_day" to bool("All-day event"), "with" to WITH, "calendar_id" to str("Optional id from read_calendar writable_calendars. Leave out to let the owner pick."),
        )
        private val LIST = mapOf("section" to str("List name in title case, like Groceries"), "items" to str("Comma-separated items, like tomatoes, onions"))
        private val PLAY = mapOf(
            "uri" to str("A Spotify URI from read_music or search, like spotify:playlist:37i9dQZF1DXcBWIGoYBM5M. Preferred when known."),
            "query" to str("Otherwise words to search for: an artist, playlist, song or mood, like Telugu devotional"),
            "name" to str("What the card calls it, like Telugu devotional mix"),
            "shuffle" to bool("Shuffle"),
            "reason" to str("Optional short reason shown on the card, like Dasara morning"),
        )
        private val CONTROL = mapOf("action" to mapOf("type" to "string", "enum" to listOf("pause", "resume", "next", "previous"), "description" to "What to do"))
        private val HOME = mapOf(
            "device_id" to str("The device_id from home_status (preferred), or the exact device name"),
            "action" to mapOf("type" to "string", "enum" to listOf("on", "off", "brightness", "color_temp", "color"), "description" to "What to do"),
            "value" to str("brightness: 1 to 100. color_temp: kelvin like 2700, or warm, soft, neutral, cool, daylight. color: red, orange, yellow, green, teal, blue, purple or pink."),
        )
        private val CARD = mapOf("title" to str("Short title, at most 60 characters"), "body" to str("One or two short sentences"), "hours" to int("How long it stays, 1 to 72. Default 12."))

        private val CALL = mapOf(
            "who" to str("The contact as he says it, like Amma or Alex"),
            "via" to mapOf("type" to "string", "enum" to listOf("auto", "whatsapp_video", "whatsapp_voice", "phone"), "description" to "auto: WhatsApp video for Indian numbers, a phone call otherwise"),
        )
        private val BOOKMARK = mapOf("person" to str("Who to tell, as he says it, like Amma"), "note" to str("What he wants to tell them, short, like the new job offer"))
        private const val PROPOSE = " This only proposes: the owner sees a card and confirms or rejects it. Nothing happens until he does."

        val WRITE_TOOLS = listOf(
            tool("set_alarm", "Propose an alarm in his clock app.$PROPOSE", ALARM, listOf("hour")),
            tool("set_timer", "Propose a countdown timer.$PROPOSE", TIMER, listOf("seconds")),
            tool("add_reminder", "Propose a reminder notification from Zeus at an exact time.$PROPOSE", REMINDER, listOf("at", "text")),
            tool("add_task", "Propose a new task in Praxis.$PROPOSE", TASK, listOf("title")),
            tool("complete_task", "Propose marking one of his open tasks done.$PROPOSE", COMPLETE, listOf("task_id")),
            tool("add_calendar_event", "Propose an event on one of his Google calendars. A birthday (title like Sam's birthday, start on the day) is saved as a Google Calendar birthday instead (Google Calendar's own Birthday type, every year); never make it a yearly event.$PROPOSE", EVENT, listOf("title", "start")),
            tool("create_card", "Propose a card on Olympus, his home screen.$PROPOSE", CARD, listOf("title")),
            tool("add_to_list", "Propose adding items to one of his lists (a section like Groceries). The list is created if it does not exist yet; items already on it are skipped. Use this, not add_task, for shopping and other lists.$PROPOSE", LIST, listOf("section", "items")),
            tool("play_music", "Propose playing music on Spotify (his default player; Apple Music is the fallback). Use a uri from read_music when you have one.$PROPOSE", PLAY),
            tool("music_control", "Propose pausing, resuming or skipping the music.$PROPOSE", CONTROL, listOf("action")),
            tool("home_control", "Propose switching one of his lights or plugs on or off, or setting a light's brightness, white or color. One device per call; use propose_plan for several. Locks, garage doors, cameras, doorbells and security systems are refused.$PROPOSE", HOME, listOf("device_id", "action")),
            tool("place_call", "Propose calling one of his contacts. Numbers never reach you; Zeus finds the contact on the phone. If the card says they are usually asleep, say so and don't press the call.$PROPOSE", CALL, listOf("who")),
            tool("bookmark_for", "Propose saving something he wants to tell a person next time he calls them. It shows on the call-home nudge right before the call.$PROPOSE", BOOKMARK, listOf("person", "note")),
            tool(
                "suggest_timers",
                "Turn an everyday moment he describes into one card of timers: cooking (\"putting lasagna in\", \"bake 25 min then rest 5\", box instructions), laundry (\"washer 45 min\"), tea, a workout. Uses the instructions when given, otherwise a small built-in table of common items, labelled as a guess. If the length is unknown it tells you what to ask him.$PROPOSE",
                mapOf("text" to str("What he said or the instructions, in his words")),
                listOf("text"),
            ),
            tool(
                "propose_plan",
                "Propose several of the actions above as one card he confirms once, with a checkbox per step. Use it for plans like an alarm plus a reminder plus a task.$PROPOSE",
                mapOf(
                    "title" to str("Short name for the plan"),
                    "steps" to mapOf(
                        "type" to "array", "minItems" to 1, "maxItems" to 8,
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "tool" to mapOf("type" to "string", "enum" to ProposalKind.entries.filter { it.modelTool }.map { it.wire }),
                                "input" to mapOf("type" to "object", "description" to "The same input that tool takes"),
                            ),
                            "required" to listOf("tool", "input"),
                        ),
                    ),
                ),
                listOf("title", "steps"),
            ),
        )
    }
}
