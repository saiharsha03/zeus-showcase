package dev.phonerecall.brain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * This month's spend, shared by Delphi and the background triage so neither overwrites the other.
 * Persisted in BrainPrefs on every change.
 */
class SpendLedger(private val prefs: BrainPrefs) : SpendGuard {
    private val _state = MutableStateFlow(prefs.load().spend.current(SpendState.thisMonth()))
    val state: StateFlow<SpendState> = _state.asStateFlow()

    @Synchronized override fun check() {
        val s = _state.value
        if (!s.allows(SpendState.thisMonth())) throw SpendCapReached(s.capUsd)
    }

    @Synchronized override fun record(model: BrainModel, use: TokenUse) {
        val next = _state.value.plus(Pricing.costMicros(model, use), SpendState.thisMonth())
        _state.value = next
        prefs.saveSpend(next)
    }

    /** Managed Agents spend (cents of list cost) and active time, into the same monthly total. */
    @Synchronized fun recordMind(cents: Long, seconds: Long) {
        val next = _state.value.plusMind(cents * 10_000L, seconds, SpendState.thisMonth())
        _state.value = next
        prefs.saveSpend(next)
    }

    /** Delphi's advisor consultations (Metis). */
    @Synchronized override fun recordAdvisor(micros: Long) {
        val next = _state.value.plusAdvisor(micros, SpendState.thisMonth())
        _state.value = next
        prefs.saveSpend(next)
    }

    @Synchronized fun setCap(usd: Int): SpendState {
        val next = _state.value.copy(capUsd = usd.coerceIn(1, 500))
        _state.value = next
        prefs.saveSpend(next)
        return next
    }
}

/** The real data a proposal is checked against. Shared by Delphi and the senses. */
class AndroidLocalState(
    private val app: Context, private val tasks: TaskStore, private val zoneOf: (String) -> ZoneId? = { null },
    private val schedules: () -> List<dev.phonerecall.mind.SchedJob>? = { null }, private val lists: (String) -> List<String>,
) : LocalState {
    override fun schedules() = schedules.invoke()
    override val zone: ZoneId get() = ZoneId.systemDefault()
    override fun openTaskTitle(id: Long): String? = tasks.load().tasks.firstOrNull { it.id == id && it.list != TaskList.DONE }?.title
    override fun writableCalendars(): List<CalendarChoice> = writable(app)
    override fun canWriteCalendar(): Boolean = granted(app, Manifest.permission.WRITE_CALENDAR)
    override fun canNotify(): Boolean = granted(app, Manifest.permission.POST_NOTIFICATIONS)
    override fun canSaveBirthdays(): Boolean = dev.phonerecall.data.GoogleCalendarAuth(app).token() != null
    override fun savedBirthdays(name: String): List<SavedBirthday> =
        dev.phonerecall.data.CalendarBirthdays(dev.phonerecall.music.GatedHttp, dev.phonerecall.data.GoogleCalendarAuth(app)).find(name)
    override fun listItems(section: String): List<String> = lists(section)
    override fun personZone(name: String): ZoneId? = zoneOf(name)
    override fun driving(): Boolean = dev.phonerecall.music.MusicCore.get(app).driving
    override fun home(): dev.phonerecall.hestia.HomeSnapshot = dev.phonerecall.hestia.HestiaCore.get(app).snapshot
    override fun callTarget(who: String, contactId: Long?) = dev.phonerecall.calls.Calls.target(app, who, contactId)

    companion object {
        fun granted(app: Context, p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        /** Writable Google calendars, for the picker on calendar proposals. */
        fun writable(app: Context): List<CalendarChoice> {
            if (!granted(app, Manifest.permission.READ_CALENDAR)) return emptyList()
            val out = ArrayList<CalendarChoice>()
            val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME)
            val where = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
            try {
                app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, where, arrayOf("com.google", CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
                    while (c.moveToNext()) if (!dev.phonerecall.data.MyAccounts.notMine(c.getString(2))) out += CalendarChoice(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
                }
            } catch (_: SecurityException) { }
            return out
        }
    }
}

/**
 * One brain per process: the databases, Fates, the spend ledger and the senses engine. The activity
 * (through BrainHub), the notification listener and the WorkManager workers all use this instance,
 * so there is one connection per database and one place proposals are made. Engine work runs on one
 * background thread, in order.
 */
class BrainCore private constructor(private val app: Context) : SenseHands {
    val db = BrainDb(app)
    init {
        dev.phonerecall.data.Prefs(app)
        Different.provider = { now -> dev.phonerecall.mind.DifferentSource.lines(app, this, now) }
        // Which model each feature uses (models/Models.kt): read by every call site, set from Horologion or a Switch card.
        dev.phonerecall.models.ModelChoice.book = dev.phonerecall.models.ModelBook(dev.phonerecall.mind.PrefsKv(app, "models"))
    }
    val tasks = TaskStore(app)
    val vault = KeyVault(app)
    val prefs = BrainPrefs(app)
    val audit = Audit { actor, action, detail -> db.log(actor, action, detail) }
    val store = SenseStore(app, KeystoreSealer())
    val sections = SectionRegistry(store, audit)
    val local = AndroidLocalState(app, tasks, zoneOf = { who ->
        store.person(People.keyOf(who))?.zoneId
            ?: store.people().filter { it.name.substringBefore(' ').equals(who.trim().substringBefore(' '), true) }.singleOrNull()?.zoneId
    }, schedules = { dev.phonerecall.mind.ScheduleBook(mindKv).jobs() }) { name -> sections.byName(name)?.let { s -> store.items(s.id).filter { !it.done }.map { it.text } }.orEmpty() }
    val fates = Fates(db, local, AndroidExecutor(app, db, tasks, hands = this, music = dev.phonerecall.music.MusicCore.get(app), home = dev.phonerecall.hestia.HestiaCore.get(app), schedules = ::applySchedule, trips = { p -> dev.phonerecall.argo.Argo.applyPlan(app, p) }, taskBudget = { p -> mindHost.locked { raiseTaskBudget(p.getValue("task"), p.getValue("cents").toLong()) } }, builds = { p -> dev.phonerecall.prometheus.PrometheusCore.get(app).build(p) }), audit, meter = db)
    /** The daily attention budget and the digest for the morning brief. */
    val attention = AttentionStore(app)
    /** Every request NetGate lets through: host, purpose and field kinds, for Themis → What left the phone. */
    private val stopOutbound = Outbound.onSent { r -> runCatching { db.logOutbound(r) } }
    val spend = SpendLedger(prefs)
    val gateway = SdkGateway { vault.read() }

    /** Phase 2c: Athena's mind (Managed Agents) and Alexandria. Built on first use, off the main thread. */
    val mindKv: dev.phonerecall.mind.Kv = dev.phonerecall.mind.PrefsKv(app)
    val mindHost: dev.phonerecall.mind.MindHost by lazy { dev.phonerecall.mind.MindHost(app, this) }

    /** A confirmed propose_schedule: the book changes now, her deployments on the sync it starts. */
    private fun applySchedule(p: Map<String, String>): String {
        val c = dev.phonerecall.mind.SchedChange(p.getValue("action"), p.getValue("name"), p["schedule"], p["prompt"], p["why"], trial = p["test_run"] == "true")
        val j = dev.phonerecall.mind.ScheduleBook(mindKv).apply(c)
        audit.log("The owner", "schedule ${c.action}", j.full + (p["schedule"]?.let { " (${dev.phonerecall.mind.Cron.words(it)})" } ?: ""))
        dev.phonerecall.mind.MindWork.syncNow(app)
        return when (c.action) { "create" -> "Created ${j.full}"; "update" -> "Changed ${j.full}"; "pause" -> "Paused ${j.full}"; "resume" -> "Resumed ${j.full}"; else -> "Deleted ${j.full}" } +
            if (c.trial) "; a test run starts on the next sync and reports in Agora" else ""
    }

    /** Haiku through the same gateway and cap, or null without a key. */
    fun haiku(): CloudModel? = if (vault.has()) HaikuModel(gateway, spend) else null

    /** Delphi's view of the cap: the same ledger, plus Metis's share (advisor spend counts toward her $15). */
    val delphiSpend: SpendGuard = object : SpendGuard by spend {
        override fun recordAdvisor(micros: Long) { spend.recordAdvisor(micros); mindHost.addAdvisorSpend(micros) }
        override fun advisorAllowed(): Boolean = mindHost.metisAllowed()
    }

    /** Bumped whenever the senses, Parnassus or hygiene changed something on screen. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "zeus-senses").apply { isDaemon = true } }

    private val effects = object : SenseEffects {
        override fun foreground() = Nano.foreground()
        override fun nudge(key: String, title: String, body: String) = Nudges.post(app, key, title, body)
        override fun scheduleTriage(delayMs: Long) = SensesWork.triageSoon(app, delayMs)
        override fun knownBirthday(name: String, month: Int, day: Int): Boolean = birthdayKnown(name, month, day)
        override fun birthdaySeen(name: String, thread: String) = BirthdayDay.seen(app, name, thread)
        override fun engaged(thread: String, name: String?, at: Long) = BirthdayDay.engaged(app, thread, name, at)
        override fun changed() {
            _changes.update { it + 1 }
            runCatching { dev.phonerecall.places.ListWidget.update(app) }
            // Tasks added or closed from the senses thread: Praxis and Today redraw (throttled) even with Zeus closed.
            runCatching { dev.phonerecall.widgets.WidgetBus.tasksChanged(app) }
        }
        override fun workerHold(worker: String): String? =
            dev.phonerecall.metron.MetronWords.held(dev.phonerecall.metron.MetronCore.get(app).pick(worker), java.time.ZonedDateTime.now())

        override fun longJob(title: String, brief: String): String? =
            if (!vault.has()) null else mindHost.handOff(title, brief, null)

        override fun consents(): List<OwnConsent> = ConsentLog.get(app).recent()
        override fun knownApps(): List<String> = KnownApps.get(app)

        override fun derived(key: String, line: String, high: Boolean) {
            mindHost.offer(dev.phonerecall.mind.MindNote(System.currentTimeMillis(), line, high, key))
        }

        override fun showsActions(card: String, note: String) = LiveActions.link(card, note)

        override fun wish(thread: String, name: String, note: String) = Agency.offerWish(app, thread, name, note, store.person(People.keyOf(name)))

        override fun wishLearned(thread: String, text: String, name: String) = GroupWish.learn(app, thread, text, name, his = false)

        override fun inGame(text: String): Boolean = runCatching {
            val g = Asks.game(text) { p -> runCatching { app.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }
            g != null && dev.phonerecall.alarms.ForegroundApp.usage(app).current() == g.pkg
        }.getOrDefault(false)

        override fun code(n: Incoming) {
            SmartActions.code(n.title + "\n" + n.text)?.let { LiveActions.code(n.key, n.app, it, n.at) }
        }
    }

    private val taskHands = object : TaskHands {
        override fun add(title: String, due: Long?, dueHasTime: Boolean) = tasks.add(title, TaskList.INBOX, due = due, dueHasTime = dueHasTime)
        override fun delete(id: Long) = tasks.delete(id)
        override fun complete(id: Long, now: Long) = tasks.complete(id, now)
        override fun uncomplete(id: Long) = tasks.uncomplete(id)
        override fun open(): List<Task> = tasks.load().open()
    }

    val engine = SenseEngine(
        store, sections, taskHands, fates, audit, effects, NanoModel,
        cloud = { if (vault.has()) HaikuModel(gateway, spend) else null },
    )

    /** Runs on the senses thread, in order with everything else the engine does. */
    fun <T> run(block: SenseEngine.() -> T): Future<T> = serial.submit<T> { block(engine) }

    fun changed() = effects.changed()

    /**
     * What a message asks for when the rules couldn't tell: Nano on the phone while Zeus is open, else one Haiku call
     * (only with the Themis switch, sanitized words, never off-limits topics or Calypso). Only for a message that made
     * a card. Runs after its ingest on the senses thread.
     */
    fun ask(n: Incoming) = run {
        if (LiveActions.cardsOf(n.key).isEmpty() || Asks.detect(n.text) != null) return@run
        val cues = CueReader.read(n.text)
        if (!cues.question && !cues.request) return@run
        val answer = if (Nano.foreground()) {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(8_000L) { NanoModel.generate("asks", AsksPrompt.SYSTEM + "\n\n" + AsksPrompt.user(n.text), 120, null) } }
        } else {
            val words = AsksPrompt.cloudWords(n.text, dev.phonerecall.clio.Clio.clean)
            if (words == null || !Agency.haikuAsks(app)) null
            else haiku()?.let { m -> runCatching { m.complete("asks", AsksPrompt.SYSTEM, AsksPrompt.user(words), 150) }.getOrNull() }
        }
        val a = answer?.let(AsksPrompt::parse) ?: return@run
        if (a.ask == null && a.replies.isEmpty()) return@run
        Agency.answered(app, n, a.ask, a.replies)
        dev.phonerecall.clio.Clio.info("agency", "asked", "${n.app}: ${a.ask?.wire ?: "none"}, ${a.replies.size} replies (by ${if (Nano.foreground()) "nano" else "haiku"})")
        changed()
    }

    /** A thread plans something but the rules can't place the time: Nano on screen, else Haiku (same switch and limits as asks). */
    fun plan(thread: String) = run {
        val lines = PlanBook.lines(thread).takeLast(8)
        if (lines.isEmpty()) return@run
        val now = java.time.ZonedDateTime.now()
        val answer = if (Nano.foreground()) {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(8_000L) { NanoModel.generate("plans", PlanPrompt.SYSTEM + "\n\n" + PlanPrompt.user(lines), 80, null) } }
        } else if (Agency.haikuAsks(app)) {
            val clean = lines.mapNotNull { l -> AsksPrompt.cloudWords(l.text, dev.phonerecall.clio.Clio.clean)?.let { l.copy(text = it) } }
            if (clean.size < lines.size) null else haiku()?.let { m -> runCatching { m.complete("plans", PlanPrompt.SYSTEM, PlanPrompt.user(clean), 80) }.getOrNull() }
        } else null
        val at = answer?.let { PlanPrompt.time(it, lines.last().at, now) } ?: return@run
        PlanBook.placed(thread, at)
        PlanWork.schedule(app)
        dev.phonerecall.clio.Clio.info("agency", "plan placed", "by ${if (Nano.foreground()) "nano" else "haiku"}")
        changed()
    }

    /** One-tap agency: these sense cards are done elsewhere; they finish through Dismissals like a swipe. Senses thread. */
    fun finish(cards: List<String>, why: String) { if (cards.isNotEmpty()) run { Agency.finish(app, store, ::athenaUntil, cards, why) } }

    /** Missed-call cards he has since called back (any app that writes the call log). */
    fun sweepCalls() = run { Agency.finish(app, store, ::athenaUntil, Agency.calledBack(app, store, { store.person(it)?.name }), "called back") }

    private fun athenaUntil(id: Long): Long? = db.cards(System.currentTimeMillis()).firstOrNull { it.id == id }?.expiresAt

    init { loadNames() }

    // ---- SenseHands: what confirmed proposals change ----

    override fun setRelation(personKey: String, relation: Relation): String? {
        val p = store.person(personKey) ?: return null
        store.savePerson(p.copy(relation = relation, confirmed = true, asked = true))
        changed()
        return p.relation?.name
    }

    override fun restoreRelation(personKey: String, previous: Relation?) {
        store.person(personKey)?.let { store.savePerson(it.copy(relation = previous, confirmed = false)) }
        changed()
    }

    override fun addToList(section: String, items: List<String>): List<Long> =
        (engine.addItems(section, items, Actor.ATHENA) ?: error("You hid $section")).also { changed() }

    override fun removeItems(ids: List<Long>) { ids.forEach { store.removeItem(it) }; changed() }

    override fun birthdayAdded(name: String) = store.setBirthdayStatus(People.keyOf(name), LocalDate.now().year, "added")

    override fun setAuto(kind: ProposalKind, on: Boolean): Boolean {
        val before = prefs.load().autoApprove
        val next = (if (on) before + kind else before - kind).filter { it in ProposalKind.autoCandidates }.toSet()
        prefs.saveAuto(next); fates.autoApprove = next
        changed()
        return next != before
    }

    /** NameGuard's names, from the people graph (on the senses thread: the names are sealed). */
    fun loadNames() { run { NameGuard.update(store.people().flatMap { listOfNotNull(it.name, it.term) }) } }

    // ---- Contacts and calendar, read on demand ----

    private fun birthdayKnown(name: String, month: Int, day: Int): Boolean {
        if (BirthdaySource(app).read().any { it.name.equals(name, ignoreCase = true) }) return true
        if (!AndroidLocalState.granted(app, Manifest.permission.READ_CALENDAR)) return false
        val zone = ZoneId.systemDefault()
        val d = LocalDate.now(zone).withMonth(month).withDayOfMonth(day)
        val from = d.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = d.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to) }.build()
            app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE), dev.phonerecall.data.MyAccounts.instancesWhere(app), null, null)?.use { c ->
                var found = false
                while (c.moveToNext() && !found) {
                    val t = c.getString(0).orEmpty().lowercase()
                    found = t.contains(name.lowercase()) && (t.contains("birthday") || t.contains("bday"))
                }
                found
            } ?: false
        } catch (_: SecurityException) { false }
    }

    /** Relation fields from Contacts (Mother: Amma, Manager: Taylor). Needs READ_CONTACTS; only names and the relation type are read. */
    fun readContactRelations(): List<Pair<String, Relation>> {
        if (!AndroidLocalState.granted(app, Manifest.permission.READ_CONTACTS)) return emptyList()
        val map = mapOf(
            ContactsContract.CommonDataKinds.Relation.TYPE_MOTHER to Relation.MOTHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_FATHER to Relation.FATHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_SISTER to Relation.SISTER,
            ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER to Relation.BROTHER,
            ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE to Relation.PARTNER,
            ContactsContract.CommonDataKinds.Relation.TYPE_PARTNER to Relation.PARTNER,
            ContactsContract.CommonDataKinds.Relation.TYPE_DOMESTIC_PARTNER to Relation.PARTNER,
            ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER to Relation.MANAGER,
            ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND to Relation.FRIEND,
            ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE to Relation.RELATIVE,
            ContactsContract.CommonDataKinds.Relation.TYPE_CHILD to Relation.RELATIVE,
        )
        val out = ArrayList<Pair<String, Relation>>()
        try {
            app.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Relation.NAME, ContactsContract.CommonDataKinds.Relation.TYPE),
                "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val r = map[c.getInt(1)] ?: continue
                    if (name.isNotEmpty()) out += name to r
                }
            }
        } catch (_: SecurityException) { }
        return out
    }

    /** Display names of contacts with a +91 number. Needs READ_CONTACTS; the numbers themselves are not kept. */
    fun readIndiaContacts(): List<String> {
        if (!AndroidLocalState.granted(app, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashSet<String>()
        try {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    if (name.isNotEmpty() && TwoClocks.isIndia(c.getString(1))) out += name
                }
            }
        } catch (_: SecurityException) { }
        return out.toList()
    }

    companion object {
        @Volatile private var instance: BrainCore? = null
        fun get(context: Context): BrainCore = instance ?: synchronized(this) {
            instance ?: BrainCore(context.applicationContext).also { instance = it }
        }
    }
}

/** Zeus's own notifications from the senses: only reply-now people, only in quiet hours. */
object Nudges {
    const val CHANNEL = "reply_now"

    fun post(context: Context, key: String, title: String, body: String) {
        if (!AndroidLocalState.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reply now people", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Family or your manager reaching you between 11 PM and 7 AM. Allow it to override Do Not Disturb if you want it to ring."
            setBypassDnd(true)
        })
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // A reply-now person is time-critical: it counts against the day's budget but never waits.
        BrainCore.get(context).attention.notify(Urgency.TIME_CRITICAL, key, title) { nm.notify(
            key, 0,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_owl)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        ) }
    }
}
