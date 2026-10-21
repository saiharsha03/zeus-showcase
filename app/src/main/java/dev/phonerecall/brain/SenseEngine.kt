package dev.phonerecall.brain

import android.graphics.Bitmap
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** Praxis, as the senses and hygiene use it. */
interface TaskHands {
    fun add(title: String, due: Long?, dueHasTime: Boolean): Long
    fun delete(id: Long)
    fun complete(id: Long, now: Long)
    fun uncomplete(id: Long)
    fun open(): List<Task>
}

/** What the engine asks of Android. Faked in tests. */
interface SenseEffects {
    /** Zeus is on screen (Nano only answers then). */
    fun foreground(): Boolean
    /** A Zeus notification that may break through quiet hours. */
    fun nudge(key: String, title: String, body: String)
    /** One batched Haiku triage, no sooner than `delayMs` from now. */
    fun scheduleTriage(delayMs: Long)
    /** A birthday for this first name is already in Contacts or the calendar. */
    fun knownBirthday(name: String, month: Int, day: Int): Boolean
    /** Wishes on [thread] say it is [name]'s birthday today (brain/BirthdayBook counts it like any other source). */
    fun birthdaySeen(name: String, thread: String) {}
    /** He answered on [thread] (WhatsApp cleared it after a reply or an open), with [name] when the thread is one person. */
    fun engaged(thread: String, name: String?, at: Long) {}
    /** Something the screens show changed. */
    fun changed()
    /** Phase 2c: one derived line for Athena's next batch (labels, never names or raw text). `high` sends it now. */
    fun derived(key: String, line: String, high: Boolean) {}
    /** Phase 2c: a Parnassus item for Athena's circle starts a long job (tier 1). Null when the circle isn't set up. */
    fun longJob(title: String, brief: String): String? = null
    /** Zeus's own sign-ins and consents of the last 7 days, to explain security alerts they cause. */
    fun consents(): List<OwnConsent> = emptyList()
    /** Apps he connects himself (Themis's Known apps). */
    fun knownApps(): List<String> = emptyList()
    /** Metron: why [worker] ("iris", "atlas") shouldn't take a job now (above 90% of a plan window), or null. */
    fun workerHold(worker: String): String? = null
    /** One-tap agency: card [card] shows notification [note]'s buttons (held in memory by brain/Agency.kt, never stored). */
    fun showsActions(card: String, note: String) {}
    /** A one-time code was dropped unstored: the phone may offer "Copy code" for it, in memory only, while it lasts. */
    fun code(n: Incoming) {}
    /** A game invite while he is already in that game: no card. */
    fun inGame(text: String): Boolean = false
    /** A group is wishing [name]: the phone words his wish for the card on notification [note]. */
    fun wish(thread: String, name: String, note: String) {}
    /** A relative wished [name] in [thread]: a wording to learn. */
    fun wishLearned(thread: String, text: String, name: String) {}
}

/**
 * Phase 2b's reflexes: notifications in, cards and proposals out; Parnassus sorting; task hygiene.
 * Every call is serialized by BrainCore on one background thread. Models are optional: with neither,
 * the rules still run and nothing leaves the phone.
 */
class SenseEngine(
    val store: SenseStore,
    val sections: SectionRegistry,
    private val tasks: TaskHands,
    private val fates: Fates,
    private val audit: Audit,
    private val effects: SenseEffects,
    private val local: LocalModel?,
    private val cloud: () -> CloudModel?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private var lastPurge = 0L

    private fun now() = clock()
    private fun zoned(ms: Long = now()) = Instant.ofEpochMilli(ms).atZone(zone())
    private fun startOfDay(ms: Long) = zoned(ms).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli()

    // ---- notifications ----

    /** One posted notification. OTPs, password managers, Calypso and his own app are dropped before anything is stored. */
    fun ingest(n: Incoming) {
        val now = now()
        if (now - lastPurge > 60_000L) { store.purge(now); lastPurge = now }
        when (val intake = Exclusions.check(n, store.calypsoApps(), store.calypsoPeople())) {
            is Intake.Drop -> {
                store.setMeta("dropped.${intake.reason}", "${(store.meta("dropped.${intake.reason}")?.toLongOrNull() ?: 0) + 1}")
                if (intake.reason == "code") effects.code(n)
                return
            }
            is Intake.BankFact -> { bank(n, intake, now); return }
            Intake.Keep -> Unit
        }
        // Chat apps re-post the same notification when it is read or updated: count it once.
        store.noteByKey(n.key)?.let { if (it.raw == n.text && n.at - it.at < 10 * 60_000L) return }
        val kind = AppKinds.of(n.pkg, n.app)
        val who = Senders.of(n, kind)
        val person = who?.takeIf { !n.isGroup || kind == AppKind.DIALER }?.let { person(it.name, kind) }
        val thread = threadKey(n, who)
        if (thread != null) store.openThread(thread, person?.key, n.at)
        val cues = CueReader.read(n.text)
        val topics = Topics.of(n.title + "\n" + n.text) + if (kind == AppKind.DATING) setOf(Topic.DATING) else emptySet()
        var verdict = Rules.triage(kind, who, person, cues, n.isGroup)
        var by = "rules"
        val cloudLine = Derive.cloudLine(1, n, kind, person?.label, person?.tier, cues, topics)
        if (verdict.needsModel && local != null && effects.foreground()) {
            // On the phone, Nano may read the words too; they never leave.
            val line = cloudLine + " | words \"" + n.text.replace(Regex("\\s+"), " ").take(200) + "\""
            val reply = runBlocking { withTimeoutOrNull(8_000L) { local.generate("triage", TriagePrompt.SYSTEM + "\n\n" + TriagePrompt.user(listOf(line)), 200, null) } }
            TriagePrompt.parse(reply.orEmpty()).firstOrNull()?.let { verdict = verdict.copy(importance = it.importance, needsModel = false); by = "nano" }
        }
        val id = store.insertNote(
            NoteRow(
                at = n.at, notifKey = n.key, pkg = n.pkg, app = n.app, kind = if (who?.missedCall == true) NoteKind.CALL else verdict.kind,
                person = person?.key, thread = thread, raw = n.text, summary = Derive.summary(n, who), importance = verdict.importance,
                needsModel = verdict.needsModel, looksImportant = verdict.looksImportant, by = by, cloudLine = cloudLine,
            ),
        )
        act(n, kind, who, person, verdict, cues, id)
        // App and category only: never the text, never who.
        dev.phonerecall.clio.Clio.info("senses", "triage", "${n.app.ifBlank { n.pkg }}: ${kind.name.lowercase()} ${verdict.kind.name.lowercase()}, ${verdict.importance.name.lowercase()} (by $by)")
        if (by == "rules" && verdict.needsModel && verdict.looksImportant) {
            effects.scheduleTriage(TriageBatch.delay(store.meta(LAST_TRIAGE)?.toLongOrNull(), now))
        }
        if ((kind == AppKind.CHAT || kind == AppKind.SMS) && n.isGroup && thread != null) birthday(n, thread)
        effects.changed()
    }

    private fun threadKey(n: Incoming, who: Who?): String? = when {
        n.isGroup && n.conversation != null -> People.keyOf("${n.pkg}|group|${n.conversation}")
        who != null -> People.keyOf("${n.pkg}|${who.name}")
        else -> null
    }

    private fun bank(n: Incoming, b: Intake.BankFact, now: Long) {
        effects.derived("bank:${n.pkg}", "A bank alert arrived (no details).", high = false)
        val summary = "Bank alert from ${b.app}"
        store.insertNote(NoteRow(at = n.at, notifKey = n.key, pkg = n.pkg, app = n.app, kind = NoteKind.BANK, person = null, thread = null, raw = null, summary = summary, importance = Importance.MEDIUM))
        store.upsertCard(SenseCard("bank:${n.pkg}", "Bank alert", "${b.app}, at ${time.format(zoned(n.at))}", "BANK", now, now + 12 * 3_600_000L))
        effects.showsActions("bank:${n.pkg}", n.key)
        effects.changed()
    }

    /** The person behind a sender name: created on first sight, with a relation guessed from the saved name. */
    fun person(name: String, kind: AppKind): Person {
        val key = People.keyOf(name)
        val existing = store.person(key)
        if (existing != null) {
            if ((kind == AppKind.WORK || kind == AppKind.MAIL) && !existing.work) store.savePerson(existing.copy(work = true))
            return existing
        }
        val guess = AddressTerms.relationOf(name)
        var p = Person(key, name, relation = guess?.first, term = guess?.second, work = kind == AppKind.WORK || kind == AppKind.MAIL,
            zone = if (TwoClocks.isIndia(name)) TwoClocks.INDIA.id else null)
        if (guess != null) {
            // The one-time question. Until he answers, the guess only sets the starting tier.
            val r = fates.propose(
                listOf(RawStep(ProposalKind.SET_RELATION, mapOf("person" to key, "term" to guess.second, "relation" to guess.first.name))),
                "Is “${guess.second}” your ${guess.first.word}?", null, "Athena",
            )
            if (r is ProposeResult.Created) p = p.copy(asked = true)
        }
        store.savePerson(p)
        return p
    }

    private fun act(n: Incoming, kind: AppKind, who: Who?, person: Person?, v: Verdict, cues: Cues, noteId: Long) {
        val now = now()
        val name = who?.name ?: n.app
        val tier = person?.tier
        val endOfTomorrow = startOfDay(now) + 2 * 86_400_000L
        var nudge: Pair<String, String>? = null
        // A friend asking for something he can do in one tap ("Pubg?"): no card at all while he's already in that game.
        val asking = (kind == AppKind.CHAT || kind == AppKind.SMS || kind == AppKind.WORK) && Asks.detect(n.text) != null
        if (asking && effects.inGame(n.text)) return
        // The card this notification lands on wears its buttons (in memory only).
        fun card(c: SenseCard) { store.upsertCard(c); effects.showsActions(c.key, n.key) }
        when {
            n.pkg == Splitwise.PKG -> {
                // Who owes whom, with Open Splitwise and Settle up (both only open the app).
                val line = Splitwise.read(n.title + " " + n.text)
                card(SenseCard("splitwise", Splitwise.title(line), n.text.replace(Regex("\\s+"), " ").take(90), "ATHENA", now, now + 24 * 3_600_000L))
            }
            who?.missedCall == true && person != null -> {
                val count = store.countNotes(NoteKind.CALL, person.key, startOfDay(now))
                val title = SenseWords.calls(name, count)
                card(SenseCard("call:${person.key}", title, "Last call ${time.format(zoned(n.at))}", "ATHENA", now, endOfTomorrow))
                nudge = title to "Missed call"
            }
            v.kind == NoteKind.SECURITY -> security(n, now, ::card)
            v.kind == NoteKind.DELIVERY ->
                card(SenseCard("delivery:${n.pkg}", "Delivery update", "${n.app}: ${n.text.take(90)}", "ATHENA", now, now + 12 * 3_600_000L))
            v.kind == NoteKind.REQUEST && person != null && v.importance <= Importance.MEDIUM -> {
                request(n, name, person, cues)
                nudge = name to "Asked you something"
            }
            v.kind == NoteKind.MESSAGE && person != null && v.importance == Importance.HIGH -> {
                val count = store.countNotes(NoteKind.MESSAGE, person.key, startOfDay(now))
                card(SenseCard("msg:${person.key}", SenseWords.messages(name, count), n.text.replace(Regex("\\s+"), " ").take(90), "ATHENA", now, now + 12 * 3_600_000L))
                nudge = name to "New message"
            }
            // A friend asking for something he can do in one tap ("Pubg?", "pics bhej") gets its card, unless he's already in that game.
            (kind == AppKind.CHAT || kind == AppKind.SMS || kind == AppKind.WORK) && person != null && v.importance != Importance.NOISE &&
                asking -> {
                val count = store.countNotes(NoteKind.MESSAGE, person.key, startOfDay(now))
                card(SenseCard("msg:${person.key}", SenseWords.messages(name, count), n.text.replace(Regex("\\s+"), " ").take(90), "ATHENA", now, now + 6 * 3_600_000L))
            }
        }
        // A family group at night waits for the morning digest (qol/FamilyNight): Zeus's own buzz only.
        if (nudge != null && QuietHours.breaksThrough(tier, v.importance, zoned(now)) && !dev.phonerecall.qol.FamilyNight.hold(n.isGroup, n.conversation, zoned(now))) effects.nudge("sense:${person?.key ?: noteId}", nudge.first, nudge.second)
        // The brain hears about it as a derived line: the person's label and the kind, never the name or the words.
        if (nudge != null || v.importance <= Importance.MEDIUM) {
            val who = person?.label ?: n.app
            val what = when {
                who != null && who.isNotEmpty() && nudge != null -> "${nudge.second} from $who"
                else -> "${v.kind.name.lowercase()} notification from ${n.app}"
            }
            effects.derived("note:${person?.key ?: n.pkg}", "$what (${v.importance.name.lowercase()}, tier ${tier?.word ?: "unknown"}).", high = v.importance == Importance.HIGH && tier == Tier.REPLY_NOW)
        }
    }

    /**
     * A security alert in plain words. One Zeus caused (it names the app Zeus just connected) is a quiet card for a day;
     * anything else is a must-see card for a week, and nothing here ever clears it.
     */
    private fun security(n: Incoming, now: Long, card: (SenseCard) -> Unit) {
        val alert = AccountAlerts.classify(n.title, n.text)
        if (alert == null) {
            card(SenseCard("security:${n.pkg}", "Security alert", "${n.app}, at ${time.format(zoned(n.at))}", "SECURITY", now, now + 24 * 3_600_000L))
            effects.derived("security:${n.pkg}", "Security alert from ${n.app} (details withheld).", high = true)
            return
        }
        val c = AccountAlerts.card(alert, n.at, effects.consents(), zone(), effects.knownApps())
        if (c.expected) card(SenseCard(c.key, c.title, c.body, AccountAlerts.QUIET_SOURCE, now, now + AccountAlerts.QUIET_MS))
        else card(SenseCard(c.key, c.title, c.body, "SECURITY", now, now + AccountAlerts.LOUD_MS))
        effects.derived(c.key, c.line, high = !c.expected)
    }

    /** "Taylor: can you send the report by EOD" becomes a proposal "Add task: Send the report, due 5:00 PM". */
    private fun request(n: Incoming, name: String, person: Person, cues: Cues) {
        val title = Requests.title(n.text) ?: return
        val mark = "req:" + People.keyOf(person.key + "|" + title.lowercase())
        if (store.meta(mark) != null) return
        store.setMeta(mark, "${now()}")
        val args = buildMap {
            put("title", title); put("list", "NEXT")
            Requests.due(cues.deadline, zoned())?.let { put("due", it) }
        }
        fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, args)), "$name: ${n.text.replace(Regex("\\s+"), " ").take(70)}", null, "Athena")
    }

    /** A notification removed: tapped or answered (engaged) or swiped away. Teaches the person's tier. */
    fun removed(key: String, response: Response, at: Long) {
        if (response == Response.IGNORED) return
        val note = store.noteByKey(key) ?: return
        val thread = note.thread ?: return
        val closed = store.closeThread(thread)
        val p = closed?.first?.let { store.person(it) }
        // A reply on her thread on her birthday counts as the wish.
        if (response == Response.ENGAGED) effects.engaged(thread, p?.name, at)
        val firstAt = closed?.second ?: return
        p ?: return
        store.savePerson(p.copy(stats = TierMath.observe(p.stats, response, at - firstAt, at)))
        effects.changed()
    }

    /** Relations from his Contacts' relation fields: confirmed, since he wrote them himself. */
    fun contactRelations(rel: List<Pair<String, Relation>>) {
        for ((name, r) in rel) {
            val key = People.keyOf(name)
            val p = store.person(key) ?: Person(key, name)
            if (p.relation == r && p.confirmed) continue
            store.savePerson(p.copy(relation = r, confirmed = true, asked = true))
        }
        effects.changed()
    }

    /** Names whose Contacts number is +91: they live on India's clock, unless he already chose a zone for them. */
    fun contactZones(indiaNames: List<String>) {
        for (name in indiaNames) {
            val key = People.keyOf(name)
            val p = store.person(key) ?: Person(key, name)
            if (p.zone != null) continue
            store.savePerson(p.copy(zone = TwoClocks.INDIA.id))
        }
    }

    // ---- birthdays ----

    private fun birthday(n: Incoming, thread: String) {
        val wish = BirthdayCatcher.detect(n.text) ?: return
        val now = now()
        store.addWish(thread, wish, now)
        val name = BirthdayCatcher.resolve(store.wishes(thread, now - 86_400_000L), n.conversation) ?: return
        val nameKey = People.keyOf(name)
        if (BirthdayCatcher.blocked(store.negatives(), thread, nameKey, wish.phrase)) return
        effects.birthdaySeen(name, thread)
        // His family's wordings in this group teach how he wishes there.
        val from = n.sender?.let { store.person(People.keyOf(Senders.clean(it))) }
        if (from?.relation?.label == "family") effects.wishLearned(thread, n.text, name)
        // The wish card: his wish to this group in one tap. Made once a day (so a swipe holds), always on the newest notification.
        val wishKey = "wish:$thread"
        if (store.card(wishKey) == null) {
            store.upsertCard(SenseCard(wishKey, "Wish $name happy birthday", n.conversation?.let { "In $it" } ?: n.app, "ATHENA", now, startOfDay(now) + 86_400_000L))
        }
        effects.showsActions(wishKey, n.key)
        effects.wish(thread, name, n.key)
        val today = zoned(now).toLocalDate()
        if (store.birthdayStatus(nameKey, today.year) != null) return
        if (effects.knownBirthday(name, today.monthValue, today.dayOfMonth)) { store.setBirthdayStatus(nameKey, today.year, "known"); return }
        val source = n.conversation?.let { "the “$it” group" } ?: n.app
        val r = fates.propose(
            listOf(
                RawStep(
                    ProposalKind.ADD_BIRTHDAY,
                    mapOf("name" to name, "date" to "%02d-%02d".format(today.monthValue, today.dayOfMonth), "source" to source, "thread" to thread, "phrase" to wish.phrase),
                ),
            ),
            "Save $name's birthday (today) in Google Calendar?", null, "Athena",
        )
        if (r is ProposeResult.Created) store.setBirthdayStatus(nameKey, today.year, "proposed")
    }

    /** "Not a birthday": reject, and never ask again for this name in this group (or this phrase, twice rejected). */
    fun notBirthday(p: Proposal) {
        val s = p.steps.firstOrNull { it.kind == ProposalKind.ADD_BIRTHDAY } ?: return
        val thread = s.payload["thread"] ?: return
        val name = s.payload["name"] ?: return
        store.addNegative(BirthdayNegative(thread, People.keyOf(name), s.payload["phrase"]))
        store.setBirthdayStatus(People.keyOf(name), zoned().year, "not")
        fates.reject(p.id)
        audit.log("The owner", "taught", "Not a birthday")
        effects.changed()
    }

    // ---- Haiku batch ----

    /**
     * The background call: at most once per 15 minutes, only when an important-looking notification
     * waits, with derived lines only. Returns how many it decided. On failure they wait for the next window.
     */
    fun triageBatch(): Int {
        val now = now()
        if (!TriageBatch.due(store.pendingCount(), store.meta(LAST_TRIAGE)?.toLongOrNull(), now)) return 0
        val model = cloud() ?: return 0
        val pending = store.pendingForCloud()
        if (pending.isEmpty()) return 0
        store.setMeta(LAST_TRIAGE, "$now")
        val lines = pending.mapIndexed { i, (_, line) -> line.replaceFirst(Regex("^#\\d+"), "#${i + 1}") }
        val reply = try { model.complete("triage", TriagePrompt.SYSTEM, TriagePrompt.user(lines), 800) } catch (e: Exception) {
            audit.log("Athena", "triage failed", AthenaEngine.plainError(e)); return 0
        }
        val verdicts = TriagePrompt.parse(reply).associateBy { it.index }
        var decided = 0
        pending.forEachIndexed { i, (id, _) ->
            val v = verdicts[i + 1] ?: return@forEachIndexed
            store.setVerdict(id, v.importance, "haiku")
            decided++
            if (v.importance <= Importance.MEDIUM) store.note(id)?.let { note ->
                store.upsertCard(SenseCard("note:$id", v.card?.replaceFirstChar { it.uppercase() } ?: "Worth a look", note.summary.take(120), "ATHENA", now, now + 12 * 3_600_000L))
                effects.showsActions("note:$id", note.notifKey)
            }
        }
        store.markSent(pending.map { it.first })
        audit.log("Athena", "triaged", "$decided of ${pending.size} with Haiku")
        effects.changed()
        return decided
    }

    // ---- Parnassus ----

    /** Keeps a capture and sorts it. Returns the capture id. */
    fun capture(kind: CaptureKind, text: String, photo: String? = null, image: Bitmap? = null): Long {
        val c = Capture(at = now(), kind = kind, text = text.trim(), photo = photo)
        val id = store.addCapture(c)
        effects.changed()
        sort(id, image)
        return id
    }

    private fun sectionNames() = sections.visible().map { it.name }

    /** Rules first; then Nano on screen, or Haiku. A photo with no words is described by Nano only; it is never sent. */
    fun sort(id: Long, image: Bitmap? = null) {
        var c = store.capture(id) ?: return
        var text = c.text
        if (text.isBlank() && image != null && local != null && effects.foreground()) {
            text = runBlocking { withTimeoutOrNull(10_000L) { local.generate("rambler-photo", "In one short line, what is in this photo and what might he want to do with it?", 60, image) } }.orEmpty()
            if (text.isNotBlank()) { c = c.copy(text = text); store.updateCapture(c) }
        }
        if (text.isBlank()) { store.updateCapture(c.copy(status = CaptureStatus.UNSORTED, outcome = "Kept here.")); effects.changed(); return }
        val user = RambleSchema.user(text, sectionNames(), LocalDate.now(zone()).toString())
        val sorted = RambleSchema.rules(text)
            ?: if (local != null && effects.foreground()) runBlocking { withTimeoutOrNull(10_000L) { local.generate("rambler", RambleSchema.SYSTEM + "\n\n" + user, 400, null) } }?.let { RambleSchema.parse(it, "nano") } else null
            ?: cloud()?.let { m -> runCatching { m.complete("rambler", RambleSchema.SYSTEM, user, 500) }.getOrNull()?.let { RambleSchema.parse(it, "haiku") } }
        if (sorted == null) {
            store.updateCapture(c.copy(status = CaptureStatus.UNSORTED, outcome = "Kept here. Not sorted yet."))
        } else apply(c, sorted)
        effects.changed()
    }

    private fun untilMs(s: Sorted): Long? = s.relevantUntil?.let { runCatching { LocalDate.parse(it).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli() }.getOrNull() }

    fun apply(c: Capture, s: Sorted) {
        val now = now()
        var place: String? = null
        var outcome: String
        var undo: Map<String, String>? = null
        val until = untilMs(s)
        when (val r = s.route) {
            is Route.Task -> {
                val hasTime = r.due != null && r.due.length > 10
                val due = r.due?.let { runCatching { Validate.epoch(it, zone()) }.getOrNull() }
                val id = tasks.add(r.title, due, hasTime)
                if (until != null) store.setTaskRelevance(TaskRelevance(id, until, s.relevance ?: RelevanceKind.EVENT, s.reason, pinned = false, lastChance = false))
                place = "Praxis"; outcome = "Added to Praxis: ${r.title}"; undo = mapOf("task_id" to "$id")
                audit.log("Athena", "filed", "Parnassus to Praxis")
            }
            is Route.Event -> {
                val args = buildMap { put("title", r.title); put("start", r.start); r.end?.let { put("end", it) }; put("all_day", "${r.allDay}") }
                outcome = when (val p = fates.propose(listOf(RawStep(ProposalKind.ADD_CALENDAR_EVENT, args)), null, null, "Athena")) {
                    is ProposeResult.Created -> { place = "Chronos"; "Waiting for you in Fates: ${p.proposal.title}" }
                    is ProposeResult.Invalid -> "Kept here. ${p.reason}."
                }
            }
            is Route.ClosetNote -> {
                val ids = addItems("Venus notes", listOf(r.text), Actor.ATHENA, "venus", until, s)
                if (ids == null) outcome = "Kept here. You hid Venus notes."
                else { place = "Venus notes"; outcome = "Noted for Venus"; undo = mapOf("item_ids" to ids.joinToString(",")) }
            }
            is Route.ListItems -> {
                val target = sections.clean(r.section) ?: "Shopping"
                val ids = addItems(target, r.items, Actor.ATHENA, null, until, s)
                if (ids == null) { place = null; outcome = "Kept here. You hid $target." }
                else {
                    place = target
                    outcome = if (ids.isEmpty()) "Already on $target" else "Added to $target: ${r.items.joinToString(", ")}"
                    undo = mapOf("item_ids" to ids.joinToString(","))
                }
            }
            is Route.Worker -> if (r.owner == WorkerOwner.CIRCLE && effects.longJob(r.job.take(80), "From Parnassus: ${r.job}") != null) {
                place = r.owner.display; outcome = "For Athena's circle. Watch it in Praxis under Long jobs."
                audit.log("Athena", "long job", "From Parnassus")
            } else {
                val id = store.addJob(r.owner, r.job, c.id, now)
                val hold = effects.workerHold(r.owner.name.lowercase())
                place = r.owner.display; outcome = "For ${r.owner.display}. " + (hold ?: "Waiting for the phase 3 channel."); undo = mapOf("job_id" to "$id")
                audit.log("Athena", "queued job", "${r.owner.display}, not sent")
            }
            is Route.Keep -> { outcome = "Kept here." }
        }
        s.expand?.let { e ->
            val section = sections.clean(e.section) ?: return@let
            fates.propose(listOf(RawStep(ProposalKind.ADD_TO_LIST, mapOf("section" to section, "items" to e.items.joinToString(", ")))), "Add ${e.items.joinToString(", ")} to $section?", null, "Athena")
        }
        store.updateCapture(c.copy(status = CaptureStatus.SORTED, place = place, outcome = outcome, undo = undo, by = s.by))
        effects.derived("capture:${c.id}", "Parnassus: \"${c.text.take(120)}\" went to ${place ?: "Parnassus (kept)"}.", high = false)
    }

    /** Adds what isn't already on the list. Null when the section is one he hid (Athena won't bring it back). */
    fun addItems(name: String, items: List<String>, by: Actor, place: String? = null, until: Long? = null, s: Sorted? = null): List<Long>? {
        val section = when (val r = sections.create(name, by, place)) {
            is SectionResult.Done -> r.section
            is SectionResult.Refused -> return null
        }
        val have = store.items(section.id).filter { !it.done }.map { it.text.lowercase() }.toMutableSet()
        val now = now()
        val ids = ArrayList<Long>()
        for (i in items) {
            val t = i.trim().takeIf { it.isNotEmpty() } ?: continue
            if (!have.add(t.lowercase())) continue
            ids += store.addItem(SectionItem(sectionId = section.id, text = t, createdAt = now, until = until, relevance = s?.relevance?.takeIf { until != null }, reason = s?.reason))
        }
        if (ids.isNotEmpty()) sections.touch(section.id, now)
        return ids
    }

    fun undoCapture(id: Long) {
        val c = store.capture(id) ?: return
        val u = c.undo ?: return
        u["task_id"]?.toLongOrNull()?.let { tasks.delete(it); store.removeTaskRelevance(it) }
        u["item_ids"]?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { store.removeItem(it) }
        u["job_id"]?.toLongOrNull()?.let { store.removeJob(it) }
        store.updateCapture(c.copy(place = null, outcome = "Undone. Kept here.", undo = null))
        audit.log("The owner", "undid", "Parnassus filing")
        effects.changed()
    }

    /** Captures the cloud couldn't sort yet (no key, offline): retried by the background worker. */
    fun retryUnsorted() { for (c in store.unsorted()) sort(c.id) }

    // ---- task hygiene ----

    fun hygiene() {
        val now = now()
        val z = zone()
        store.purge(now)
        val open = tasks.open().associateBy { it.id }
        for (r in store.taskRelevance()) {
            val t = open[r.taskId] ?: continue
            val item = HygieneItem(t.title, until = r.until, kind = r.kind, reason = r.reason, due = t.due, waitingList = t.list == TaskList.WAITING, pinned = r.pinned, lastChanceShown = r.lastChance)
            when (val call = Hygiene.decide(item, now, z)) {
                is HygieneCall.ToLethe -> {
                    tasks.complete(t.id, now)
                    store.addLethe("task", t.id, t.title, call.reason, now)
                    audit.log("Athena", "closed", "${t.title}: ${call.reason}")
                }
                is HygieneCall.LastChance -> {
                    store.upsertCard(SenseCard("last:task:${t.id}", "Last chance: ${t.title}", call.reason, "ATHENA", now, now + 36 * 3_600_000L))
                    store.setTaskRelevance(r.copy(lastChance = true))
                }
                HygieneCall.Keep -> Unit
            }
        }
        for (i in store.itemsWithWindow()) {
            val item = HygieneItem(i.text, i.note, i.until, i.relevance, i.reason, pinned = i.pinned, lastChanceShown = i.lastChance)
            when (val call = Hygiene.decide(item, now, z)) {
                is HygieneCall.ToLethe -> {
                    store.updateItem(i.copy(lethe = true))
                    store.addLethe("item", i.id, i.text, call.reason, now)
                    audit.log("Athena", "closed", "${i.text}: ${call.reason}")
                }
                is HygieneCall.LastChance -> {
                    store.upsertCard(SenseCard("last:item:${i.id}", "Last chance: ${i.text}", call.reason, "ATHENA", now, now + 36 * 3_600_000L))
                    store.updateItem(i.copy(lastChance = true))
                }
                HygieneCall.Keep -> Unit
            }
        }
        sections.autoHide(now)
        weeklyLine(now)
        followUps(now)
        effects.changed()
    }

    /**
     * Praxis's Waiting for: on the check-back day, one proposal "Follow up with Taylor about the report?"
     * (a Next task in @calls). Only ever a card he confirms; each check-back day is offered once.
     */
    private fun followUps(now: Long) {
        val z = zoned(now)
        for (t in tasks.open()) {
            if (t.sample || !dev.phonerecall.tasks.Gtd.followUpDue(t, z)) continue
            val key = "followup.${t.id}"
            if (store.meta(key) == "${t.followUp}") continue
            val title = dev.phonerecall.tasks.Gtd.followUpTitle(t)
            val r = fates.propose(
                listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to title, "list" to "next", "context" to "calls"))),
                "$title?", null, proposer = "Athena",
            )
            if (r is ProposeResult.Created) store.setMeta(key, "${t.followUp}")
        }
    }

    /** "Closed for you": once a week, when something went to Lethe in the last seven days. */
    private fun weeklyLine(now: Long) {
        val last = store.meta(WEEKLY)?.toLongOrNull()
        if (last != null && now - last < 7 * 86_400_000L) return
        val titles = store.lethe().filter { !it.restored && it.at >= now - 7 * 86_400_000L }.map { it.title }
        val line = Hygiene.closedLine(titles) ?: return
        val week = zoned(now).let { "${it.get(IsoFields.WEEK_BASED_YEAR)}-${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" }
        store.upsertCard(SenseCard("closed:$week", "Closed for you", line, "ATHENA", now, now + 48 * 3_600_000L))
        store.setMeta(WEEKLY, "$now")
    }

    /** Undo from Lethe: the item comes back as it was, and loses its window so it isn't closed again. */
    fun restore(letheId: Long) {
        val row = store.letheRow(letheId) ?: return
        if (row.restored) return
        when (row.refType) {
            "task" -> { tasks.uncomplete(row.refId); store.removeTaskRelevance(row.refId) }
            "item" -> store.item(row.refId)?.let { store.updateItem(it.copy(lethe = false, until = null, lastChance = false)) }
        }
        store.markRestored(letheId)
        audit.log("The owner", "restored", row.title)
        effects.changed()
    }

    companion object {
        const val LAST_TRIAGE = "triage.last"
        const val WEEKLY = "closed.weekly"
    }
}
