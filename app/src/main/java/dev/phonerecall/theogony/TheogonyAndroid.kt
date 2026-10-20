package dev.phonerecall.theogony

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.clio.Clio
import dev.phonerecall.clio.Level
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import dev.phonerecall.mind.MindWork
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.oneiroi.GatewayModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Iris over the existing Pantheon chat route: her reply is the gateway message whose reply_to is ours. */
class GatewayIris(private val app: Context) : IrisLink {
    override fun available(): Boolean = DeviceKeys.paired(app)
    override fun send(text: String, clientId: String): Long = Gateway.send("iris", text, clientId, via = "athena").id
    override fun reply(messageId: Long): String? =
        Gateway.history("iris", after = messageId, limit = 200).firstOrNull { it.replyTo == messageId && it.sender == "iris" }?.text
}

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private fun day(ms: Long, zone: ZoneId) = MONTH_DAY.format(Instant.ofEpochMilli(ms).atZone(zone))
private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun join(words: List<String>) = when (words.size) { 0 -> ""; 1 -> words[0]; else -> words.dropLast(1).joinToString(", ") + " and " + words.last() }
private fun clock(h: Int) = when { h == 0 -> "midnight"; h < 12 -> "$h AM"; h == 12 -> "noon"; else -> "${h - 12} PM" }

/**
 * The phone's readers. Each one is words only and skips itself (null) without its permission. Names become
 * labels (People); a contact without a label is never named.
 */
object PhoneSources {
    private const val DAY = 86_400_000L

    fun all(app: Context, core: BrainCore): List<PhoneSource> {
        val zone = ZoneId.systemDefault()
        val labels: () -> Map<String, String> = { core.store.people().filter { !it.calypso && it.relation != null }.associate { it.name to it.label } }
        return listOf(
            source("Reading your calendar") { calendar(app, zone) },
            source("Your app habits") { usage(app, zone) },
            source("Your calls") { calls(app, labels()) },
            source("Contacts and birthdays") { birthdays(app, labels()) },
            source("Music") { music(app) },
            source("Health, if shared") { dev.phonerecall.iaso.IasoBrain.current()?.let { listOf(RawFact(SeedPath.SNAPSHOT, "Lately: $it.", 0.6, "health")) } },
            source("Spending, in words") { money(app) },
            source("Your closet") { closet(app) },
            source("Places you go") { places(app) },
        )
    }

    private fun source(label: String, reader: () -> List<RawFact>?) = object : PhoneSource {
        override val label = label
        override fun read() = reader()
    }

    private fun calendar(app: Context, zone: ZoneId): List<RawFact>? {
        val src = CalendarSource(app)
        if (!src.allowed()) return null
        val now = System.currentTimeMillis()
        val cals = src.calendars().associateBy { it.id }
        val events = src.readRange(now - 56 * DAY, now + 56 * DAY, cals)
        val out = ArrayList<RawFact>()
        val trip = Regex("(?i)\\b(trip|flight|vacation|holiday|travel|hotel|airbnb|pto|out of office|ooo)\\b")
        events.filter { it.begin > now && (trip.containsMatchIn(it.title) || (it.allDay && it.end - it.begin > DAY)) }
            .distinctBy { it.title }.take(8)
            .forEach { out += RawFact(SeedPath.PLANS, "${it.title}: ${day(it.begin, zone)}" + if (it.end - it.begin > DAY) " to ${day(it.end - 1, zone)}." else ".", 0.8, "calendar") }
        events.filter { it.recurring && !it.allDay }.groupBy { it.title }.filter { it.value.size >= 3 }.entries.take(8).forEach { (title, list) ->
            val days = list.map { Instant.ofEpochMilli(it.begin).atZone(zone).dayOfWeek }.distinct().sorted()
            val h = Instant.ofEpochMilli(list.first().begin).atZone(zone).hour
            out += RawFact(SeedPath.ROUTINES, "Recurring: $title on ${join(days.map { it.getDisplayName(TextStyle.FULL, Locale.US) })} around ${clock(h)}.", 0.8, "work_week")
        }
        val busy = events.filter { !it.allDay && it.begin < now }.groupBy { Instant.ofEpochMilli(it.begin).atZone(zone).dayOfWeek }
        busy.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 4 }?.let { out += RawFact(SeedPath.ROUTINES, "Busiest calendar day lately: ${it.key.getDisplayName(TextStyle.FULL, Locale.US)}.", 0.6, "calendar") }
        val upcoming = events.count { it.begin > now && it.begin < now + 14 * DAY && !it.allDay }
        out += RawFact(SeedPath.SNAPSHOT, "Next two weeks look " + (if (upcoming > 25) "busy" else if (upcoming > 8) "normal" else "light") + " on the calendar.", 0.7, "calendar")
        return out
    }

    /** The apps he uses most and when he first picks the phone up, from 4 weeks of Usage access. */
    private fun usage(app: Context, zone: ZoneId): List<RawFact>? {
        if (!dev.phonerecall.odyssey.Odyssey.canUsage(app)) return null
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val pm = app.packageManager
        val top = usm.queryAndAggregateUsageStats(now - 28 * DAY, now).values
            .filter { it.totalTimeInForeground > 30 * 60_000L && it.packageName != app.packageName && !it.packageName.contains("launcher") && !it.packageName.contains("systemui") }
            .sortedByDescending { it.totalTimeInForeground }.take(6)
            .mapNotNull { s -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() }.getOrNull() }
        val out = ArrayList<RawFact>()
        if (top.isNotEmpty()) out += RawFact(SeedPath.PREFERENCES, "Uses ${join(top)} the most.", 0.8, "apps")
        // First unlock-ish use per weekday morning: a wake hint, not a fact, so the interview confirms it.
        val first = HashMap<java.time.LocalDate, Int>()
        val ev = usm.queryEvents(now - 28 * DAY, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val t = Instant.ofEpochMilli(e.timeStamp).atZone(zone)
            if (t.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) || t.hour < 4) continue
            first.merge(t.toLocalDate(), t.hour * 60 + t.minute) { a, b -> minOf(a, b) }
        }
        if (first.size >= 5) {
            val m = first.values.sorted()[first.size / 2]
            val h = m / 60
            val half = if (m % 60 >= 30) ":30" else ""
            out += RawFact(SeedPath.ROUTINES, "Usually picks up the phone around ${if (h > 12) h - 12 else h}$half ${if (h >= 12) "PM" else "AM"} on weekdays, so likely wakes then.", 0.5, "wake")
        }
        return out
    }

    /** Who he calls most, as labels only (a number or an unlabeled name never leaves), and when. */
    private fun calls(app: Context, labels: Map<String, String>): List<RawFact>? {
        if (!granted(app, Manifest.permission.READ_CALL_LOG)) return null
        val since = System.currentTimeMillis() - 28 * DAY
        val byLabel = HashMap<String, Int>()
        val hours = IntArray(24)
        app.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE), "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty()
                hours[Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.systemDefault()).hour]++
                labels.entries.firstOrNull { it.key.equals(name, true) }?.let { byLabel.merge(it.value, 1, Int::plus) }
            }
        }
        val out = ArrayList<RawFact>()
        val top = byLabel.entries.sortedByDescending { it.value }.take(4).map { it.key }
        if (top.isNotEmpty()) out += RawFact(SeedPath.PEOPLE, "Calls most with ${join(top)}.", 0.7, "people")
        val evening = (17..23).sumOf { hours[it] }
        val total = hours.sum()
        if (total >= 8) out += RawFact(SeedPath.ROUTINES, if (evening * 2 > total) "Most calls happen in the evening." else "Calls are spread through the day.", 0.6, "calls")
        return out
    }

    private fun birthdays(app: Context, labels: Map<String, String>): List<RawFact>? {
        if (!granted(app, Manifest.permission.READ_CONTACTS)) return null
        val all = BirthdaySource(app).read()
        val out = ArrayList<RawFact>()
        for (b in all) {
            val label = labels.entries.firstOrNull { it.key.equals(b.name, true) }?.value ?: continue
            out += RawFact(SeedPath.PEOPLE, "Birthday of $label: ${java.time.Month.of(b.month).getDisplayName(TextStyle.SHORT, Locale.US)} ${b.day}.", 0.9, "people")
        }
        if (all.isNotEmpty()) out += RawFact(SeedPath.PEOPLE, "Birthdays are saved in contacts for ${if (all.size > 20) "many" else "a few"} people.", 0.9, "people")
        return out.take(12)
    }

    @Suppress("UNCHECKED_CAST")
    private fun music(app: Context): List<RawFact>? {
        val s = dev.phonerecall.music.MusicCore.get(app).summary()
        if (s["spotify_connected"] != true) return null
        val out = ArrayList<RawFact>()
        val artists = (s["recently_played"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["by"]?.toString() }.distinct().take(5)
        if (artists.isNotEmpty()) out += RawFact(SeedPath.PREFERENCES, "Recently played on Spotify: ${join(artists)}.", 0.8, "music")
        val lists = (s["playlists"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { it["name"]?.toString() }.take(5)
        if (lists.isNotEmpty()) out += RawFact(SeedPath.PREFERENCES, "Spotify playlists include ${join(lists)}.", 0.7, "music")
        s["drive_playlist"]?.let { out += RawFact(SeedPath.PREFERENCES, "Drive playlist: $it.", 0.9, "music") }
        return out
    }

    /** Category words and usual stores from Moneta's ledger. Never an amount, a card or a single charge. */
    private suspend fun moneyLoaded(app: Context) = dev.phonerecall.moneta.MonetaCore.get(app).let { m ->
        if (!m.state.value.loaded) { m.load(); repeat(30) { if (!m.state.value.loaded) delay(100) } }
        m.state.value
    }

    private fun money(app: Context): List<RawFact>? {
        val st = kotlinx.coroutines.runBlocking { moneyLoaded(app) }
        val since = System.currentTimeMillis() - 60 * DAY
        val spend = st.txns.filter { it.at > since && it.kind.spend }
        if (spend.isEmpty()) return null
        val out = ArrayList<RawFact>()
        val cats = spend.groupBy { it.category }.filterKeys { it != dev.phonerecall.moneta.Category.OTHER && it != dev.phonerecall.moneta.Category.HEALTH }
            .entries.sortedByDescending { it.value.size }.take(4).map { it.key.word.lowercase() }
        if (cats.isNotEmpty()) out += RawFact(SeedPath.PROFILE, "Spends most often on ${join(cats)}.", 0.8, "spending")
        val stores = spend.groupBy { it.merchant.trim() }.filter { it.key.isNotBlank() && it.value.size >= 2 }.entries.sortedByDescending { it.value.size }.take(5).map { it.key }
        if (stores.isNotEmpty()) out += RawFact(SeedPath.PROFILE, "Usual stores: ${join(stores)}.", 0.8, "spending")
        val subs = (st.irisSubs + st.manual).map { it.name }.distinct().take(8)
        if (subs.isNotEmpty()) out += RawFact(SeedPath.PLANS, "Subscriptions: ${join(subs)}.", 0.8, "deadlines")
        if (st.budgets.isNotEmpty()) out += RawFact(SeedPath.PREFERENCES, "Keeps monthly budgets for ${join(st.budgets.keys.map { it.word.lowercase() })}.", 0.9, "budget")
        return out
    }

    private fun closet(app: Context): List<RawFact>? {
        val items = ClosetSource(app).load().items.filter { !it.archived }
        if (items.isEmpty()) return null
        val colours = items.map { it.colour.lowercase().trim() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        val kinds = items.groupingBy { it.group.label.lowercase() }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        return listOfNotNull(
            RawFact(SeedPath.PROFILE, "Closet is catalogued in Venus, mostly ${join(kinds)}.", 0.8, "closet"),
            colours.takeIf { it.isNotEmpty() }?.let { RawFact(SeedPath.PREFERENCES, "Favourite colours to wear: ${join(it)}.", 0.6, "closet") },
        )
    }

    private fun places(app: Context): List<RawFact>? {
        val d = dev.phonerecall.odyssey.OdysseyStore.load(app)
        val facts = dev.phonerecall.odyssey.OdysseyBrain.facts(d, System.currentTimeMillis())
        return facts.takeIf { it.isNotEmpty() }?.take(8)?.map { RawFact(SeedPath.ROUTINES, it.trimEnd('.') + ".", 0.7, "routines") }
    }
}

/**
 * Theogony on the phone: one engine, one IO scope, a state flow for the screen. Starts by itself after
 * pairing, or on first launch when the phone is already paired; Themis can re-run it.
 */
class TheogonyHub private constructor(private val app: Context) {
    private val core = BrainCore.get(app)

    /** Athena's one memory: a fact he confirmed goes into Oneiroi's rows once; one he forgets comes out. */
    private val rows = object : SeedRows {
        private val oneiroi get() = dev.phonerecall.oneiroi.Oneiroi.get(app)
        override fun confirmed(f: SeedFact) {
            oneiroi.facts.put(f.path.dir, f.text, dev.phonerecall.oneiroi.FactStatus.CONFIRMED, SOURCE, key = f.key)
            oneiroi.reload()
        }
        override fun forgot(f: SeedFact) {
            oneiroi.facts.live().filter { it.source == SOURCE && it.key.endsWith(":" + dev.phonerecall.oneiroi.Facts.norm(f.key)) }.forEach { oneiroi.facts.retract(it.id) }
            oneiroi.reload()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kv = core.mindKv
    private val lock = Any()
    private var poller: Job? = null
    @Volatile private var engineRef: TheogonyEngine? = null

    val engine = TheogonyEngine(
        kv, GatewayIris(app), PhoneSources.all(app, core),
        memory = { MindWork.syncNow(app) },
        settings = { wire, on ->
            val k = ProposalKind.of(wire)?.takeIf { it in ProposalKind.autoCandidates }
            if (k != null) {
                val now = core.prefs.load().autoApprove
                val next = if (on) now + k else now - k
                core.prefs.saveAuto(next)
                core.fates.autoApprove = next
                core.audit.log("The owner", if (on) "auto-approve on" else "auto-approve off", "${k.label} (Theogony)")
                core.changed()
            }
        },
        sanitizer = {
            // Every name the phone knows maps to its label, the roster's included, so no name reaches memory.
            val roster = runCatching { engineRef?.roster() }.getOrNull().orEmpty().associate { it.name to it.label }
            val people = core.store.people()
            Sanitizer(roster + people.associate { it.name to it.label }, people.filter { it.calypso }.map { it.name }.toSet())
        },
        people = { PeopleReader.read(app, core) },
        rows = rows,
    ).also { engineRef = it; runCatching { it.migrate() } }

    /**
     * Once: rows the nightly fold made from Theogony's files before it skipped them (guesses filed as observed,
     * the status tags in the text) are retracted, and his confirmed facts are written the right way.
     */
    private fun cleanRows() = scope.launch {
        if (kv.get(K_ROWS_V) != null) return@launch
        runCatching {
            val o = dev.phonerecall.oneiroi.Oneiroi.get(app)
            o.facts.live().filter { it.source.startsWith(FOLDED) && dev.phonerecall.oneiroi.MemoryFold.theogony(it.source.removePrefix(FOLDED)) }.forEach { o.facts.retract(it.id) }
            synchronized(lock) { engine.facts() }.filter { it.status == Status.CONFIRMED }.forEach { rows.confirmed(it) }
            kv.put(K_ROWS_V, "1")
        }.onFailure { e -> Clio.log("theogony", Level.ERROR, "row cleanup failed", e.javaClass.simpleName) }
    }

    private val _state = MutableStateFlow(engine.state())
    val state: StateFlow<TheogonyState> = _state.asStateFlow()

    /** His answers still being saved, in order: the screen shows them and moves on at once. */
    private val pending = LinkedHashMap<String, String>()

    init { cleanRows() }

    private fun publish() { _state.value = engine.state(synchronized(pending) { LinkedHashMap(pending) }) }

    private fun run(block: TheogonyEngine.() -> Unit) = scope.launch {
        synchronized(lock) {
            runCatching { engine.block() }.onFailure { e -> Clio.log("theogony", Level.ERROR, "step failed", e.javaClass.simpleName + ": " + (e.message ?: "").take(120)) }
            publish()
        }
        watch()
        phrase()
    }

    /** An answer: shown right away, saved behind. */
    private fun answering(id: String, words: String, block: TheogonyEngine.() -> Unit) {
        synchronized(pending) { pending[id] = words }
        publish()
        run { try { block() } finally { synchronized(pending) { pending.remove(id) } } }
    }

    /** While Iris's answer is out: look every 20 seconds, for as long as the app runs. */
    private fun watch() {
        if (!_state.value.irisPending || poller?.isActive == true) return
        poller = scope.launch {
            while (isActive && _state.value.irisPending) {
                delay(20_000)
                synchronized(lock) { runCatching { engine.poll() }; publish() }
            }
        }
    }

    @Volatile private var phrasing = false

    /** Athena phrases her opening once, through her model, outside the engine's lock; the plain line stays if she can't. */
    private fun phrase() {
        if (phrasing || !core.vault.has()) return
        val lines = synchronized(lock) { runCatching { engine.openingDraft() }.getOrNull() } ?: return
        phrasing = true
        scope.launch {
            val text = runCatching {
                GatewayModel(core.gateway, core.spend, BrainModel.HAIKU).complete(OPENING_SYSTEM, lines.joinToString("\n") { "- $it" }, 200).text
            }.getOrNull()
            synchronized(lock) { runCatching { engine.setOpening(text) }; publish() }
            phrasing = false
        }
    }

    fun onPaired() { if (engine.phase == Phase.NONE) start() }

    /** On open: start the first seed when paired, else pick up a late Iris answer. True when the screen should show. */
    fun onLaunch(): Boolean {
        if (engine.phase == Phase.NONE && DeviceKeys.paired(app)) { start(); return true }
        if (engine.phase == Phase.ASKING || engine.state().irisPending) run { poll() }
        return engine.phase == Phase.SEEDING || engine.phase == Phase.ASKING || engine.dueForInterview()
    }

    fun start() = run { seed(::publishNow) }
    fun refresh() = run { resetForRefresh(); seed(::publishNow) }
    fun skipWaiting() = run { skipWaiting() }
    fun answer(id: String, text: String) = answering(id, text) { answer(id, text) }
    fun skip(id: String) = answering(id, Interview.SKIP) { skip(id) }
    fun later() = run { say(_state.value.question?.id, "later") }

    /** Free text in the chat; "skip" and "later" count as the buttons. */
    fun say(id: String?, text: String) {
        val intent = Interview.intent(text)
        if (id != null && intent != Interview.Intent.LATER) answering(id, if (intent == Interview.Intent.SKIP) Interview.SKIP else text.trim()) { say(id, text) }
        else run { say(id, text) }
    }

    fun confirmBundle(id: String, keep: Set<String>, words: String) = answering(id, words) { confirmBundle(id, keep) }
    fun askIris(id: String) = answering(id, Interview.ASK_IRIS) { askIris(id) }

    fun forget(key: String) = run { forget(key) }
    fun confirm(key: String) = run { confirm(key) }
    fun setMilestoneNotify(on: Boolean) = run { setMilestoneNotify(on) }

    /** For DND bypass suggestions, Kairos, Horae, Argo: confirmed and observed people only (inferred in suggestions()). */
    fun peoplePriority(): PeoplePriority = synchronized(lock) { engine.peoplePriority() }

    /** For Chronos (a quiet all-day line on the day) and Eos: confirmed milestones only. */
    fun milestones(): Milestones = synchronized(lock) { engine.milestones() }
    fun decide(id: String, confirm: Boolean) = run { decide(id, confirm) }

    private fun publishNow() = publish()

    /** Step 3's gate for the mind's sync. */
    fun proactiveReady(): Boolean = engine.proactiveReady(DeviceKeys.paired(app))

    companion object {
        private const val SOURCE = "theogony"
        /** The fold's source for a row from a memory file: "memory /<path>". */
        private const val FOLDED = "memory "
        private const val K_ROWS_V = "theo.rows.v"
        private val OPENING_SYSTEM = """You are Athena, the owner's new assistant, meeting him for the first time. Below is what you believe about him from Iris and his phone.
In one or two short, plain sentences, speaking to him as "you", say what you think you know, then end with "Right?". Use only those lines; add nothing, guess nothing.
No names: people stay as the labels given. No numbers beyond times and days. ${dev.phonerecall.mind.MindPrompts.OFF_LIMITS}
Reply with the sentences only."""

        @Volatile private var instance: TheogonyHub? = null
        fun get(c: Context): TheogonyHub = instance ?: synchronized(this) { instance ?: TheogonyHub(c.applicationContext).also { instance = it } }
    }
}

/**
 * The People reader: Contacts (names, starred, groups, nicknames; numbers only for the India yes/no), the
 * relation fields, and counts from the senses (WhatsApp messages per person and busy groups, never content)
 * and the call log. Horae's call habits join here when that branch lands.
 */
object PeopleReader {
    private const val DAY = 86_400_000L
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun read(app: Context, core: BrainCore): PeopleInput? {
        if (!granted(app, Manifest.permission.READ_CONTACTS)) return null
        val cr = app.contentResolver
        val groups = HashMap<Long, String>()
        cr.query(android.provider.ContactsContract.Groups.CONTENT_URI, arrayOf(android.provider.ContactsContract.Groups._ID, android.provider.ContactsContract.Groups.TITLE), null, null, null)?.use { c ->
            while (c.moveToNext()) groups[c.getLong(0)] = c.getString(1).orEmpty()
        }
        data class Acc(val name: String, var starred: Boolean = false, val groups: MutableList<String> = ArrayList(), val nicks: MutableList<String> = ArrayList(), val phones: MutableList<String> = ArrayList())
        val byId = HashMap<Long, Acc>()
        cr.query(android.provider.ContactsContract.Contacts.CONTENT_URI, arrayOf(android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME, android.provider.ContactsContract.Contacts.STARRED), null, null, null)?.use { c ->
            while (c.moveToNext()) { val n = c.getString(1)?.trim().orEmpty(); if (n.isNotEmpty()) byId[c.getLong(0)] = Acc(n, c.getInt(2) == 1) }
        }
        val nick = android.provider.ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        val member = android.provider.ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        val phone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        cr.query(
            android.provider.ContactsContract.Data.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Data.CONTACT_ID, android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.Data.DATA1),
            "${android.provider.ContactsContract.Data.MIMETYPE} IN (?,?,?)", arrayOf(nick, member, phone), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val a = byId[c.getLong(0)] ?: continue
                val v = c.getString(2) ?: continue
                when (c.getString(1)) {
                    nick -> a.nicks += v
                    member -> v.toLongOrNull()?.let { groups[it] }?.let { a.groups += it }
                    phone -> a.phones += v
                }
            }
        }
        // Calypso people never enter the roster.
        val sealed = core.store.calypsoPeople()
        fun open(name: String) = dev.phonerecall.brain.People.keyOf(name) !in sealed
        val contacts = byId.values.filter { open(it.name) }.map { ContactInfo(it.name, it.starred, it.groups, it.nicks, it.phones) }

        // Counts from the senses: messages per person (1:1) and how many group chats are busy. Never text.
        val since = System.currentTimeMillis() - 28 * DAY
        val people = core.store.people()
        val names = people.associate { it.key to it.name }
        val notes = runCatching { core.store.notes(5000) }.getOrDefault(emptyList()).filter { it.pkg in WHATSAPP && it.at > since }
        val perPerson = notes.mapNotNull { n -> n.person?.let { names[it] } }.groupingBy { it }.eachCount()
        val busyGroups = notes.filter { it.person == null && it.thread != null }.groupingBy { it.thread!! }.eachCount().count { it.value >= 20 }
        val calls = HashMap<String, Int>()
        if (granted(app, Manifest.permission.READ_CALL_LOG)) runCatching {
            cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME), "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { calls.merge(it, 1, Int::plus) }
            }
        }
        val reply = people.associate { it.name to it.stats.ewmaMinutes }
        val signals = (perPerson.keys + calls.keys).filter(::open).map { n -> ChatSignal(n, perPerson[n] ?: 0, calls[n] ?: 0, reply[n]) }
        return PeopleInput(
            contacts, core.readContactRelations().filter { open(it.first) }, signals, busyGroups,
            people.filter { it.confirmed && it.relation != null && !it.calypso }.associate { it.name to it.relation!! },
        )
    }
}
