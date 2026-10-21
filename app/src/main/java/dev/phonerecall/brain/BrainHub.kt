package dev.phonerecall.brain

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.CalendarContract
import androidx.compose.runtime.Immutable
import dev.phonerecall.home.CardSource
import dev.phonerecall.home.GeneratedCard
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.ActionRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What Delphi draws. `working` is the single state word while Athena runs; `error` is one plain line. */
@Immutable
data class ChatState(
    val loaded: Boolean = false,
    val chatId: Long? = null,
    val lines: List<ChatLine> = emptyList(),
    val streaming: String = "",
    val working: String? = null,
    val error: String? = null,
    /** Older lines exist above the ones loaded. */
    val earlier: Boolean = false,
)

/** What phase 2b's screens draw: Parnassus, the sections, People, Lethe and the waiting worker jobs. */
@Immutable
data class SensesState(
    val loaded: Boolean = false,
    val sections: List<Section> = emptyList(),
    val items: Map<Long, List<SectionItem>> = emptyMap(),
    val captures: List<Capture> = emptyList(),
    val changes: List<SectionChange> = emptyList(),
    val people: List<Person> = emptyList(),
    val lethe: List<LetheRow> = emptyList(),
    val jobs: List<WorkerJob> = emptyList(),
    /** Notification access is granted. */
    val listening: Boolean = false,
)

/** What phase 2c's screens draw: the mind's state, long jobs, Places and what Athena remembers. */
@Immutable
data class MindState(
    val ready: Boolean = false,
    val setUp: Boolean = false,
    val session: String? = null,
    val jobs: List<dev.phonerecall.mind.LongJob> = emptyList(),
    val log: List<String> = emptyList(),
    val wakeupsToday: List<dev.phonerecall.mind.Wakeup> = emptyList(),
    val lastSync: Long? = null,
    /** Cents this month per circle member (agent name, or anthropic.advisor for Metis). */
    val circle: Map<String, Long> = emptyMap(),
    val metisNote: String? = null,
    val places: List<dev.phonerecall.places.Place> = emptyList(),
    val here: String? = null,
    val remembered: List<dev.phonerecall.mind.MemoryFile>? = null,
    val rememberedError: String? = null,
    val memoryCounts: Map<dev.phonerecall.memory.Source, Int> = emptyMap(),
    val memoryChunks: Int = 0,
    val memoryVectors: Int = 0,
    val embedder: String = "not loaded",
    /** Alexandria's one line: what is indexed and what it waits for (IndexStatus). */
    val memoryStatus: String = "",
    val indexing: Boolean = false,
    val documents: List<dev.phonerecall.memory.PickedDoc> = emptyList(),
    val heartbeat: dev.phonerecall.mind.HeartbeatView = dev.phonerecall.mind.HeartbeatView(),
)

/**
 * The brain's front desk: Delphi's chat, the Fates inbox, Calypso's key and Themis's brain settings,
 * plus phase 2b's Parnassus, sections, People and Lethe. The databases, Fates and the senses engine
 * live in BrainCore, shared with the notification listener and the workers. All IO runs off the main thread.
 */
class BrainHub(
    private val app: Application,
    private val scope: CoroutineScope,
    private val reads: BrainReads,
    /** Tasks or Olympus cards changed (a confirmed proposal touched them). */
    private val onData: () -> Unit,
) {
    val core = BrainCore.get(app)
    private val db = core.db
    private val vault = core.vault
    private val prefs = core.prefs

    private val _settings = MutableStateFlow(BrainSettings())
    val settings: StateFlow<BrainSettings> = _settings.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _proposals = MutableStateFlow<List<Proposal>>(emptyList())
    val proposals: StateFlow<List<Proposal>> = _proposals.asStateFlow()

    private val _cards = MutableStateFlow<List<GeneratedCard>>(emptyList())
    val cards: StateFlow<List<GeneratedCard>> = _cards.asStateFlow()

    private val _audit = MutableStateFlow<List<AuditRow>>(emptyList())
    val audit: StateFlow<List<AuditRow>> = _audit.asStateFlow()

    private val _calendars = MutableStateFlow<List<CalendarChoice>>(emptyList())
    /** Writable Google calendars, for the picker on calendar proposals. */
    val calendars: StateFlow<List<CalendarChoice>> = _calendars.asStateFlow()

    private val _digest = MutableStateFlow<List<DigestItem>>(emptyList())
    /** What waited for this morning's brief (over the day's attention budget). Empty on a quiet day. */
    val digest: StateFlow<List<DigestItem>> = _digest.asStateFlow()

    private val _fatigue = MutableStateFlow<Map<ProposalKind, Pair<KindStats, Long?>>>(emptyMap())
    /** Per kind: the last 30 days, and when a quiet kind wakes up. Themis → Auto-approve. */
    val fatigue: StateFlow<Map<ProposalKind, Pair<KindStats, Long?>>> = _fatigue.asStateFlow()

    private val _ask = MutableStateFlow<ProposalKind?>(null)
    /** Today's one "Was this useful?", on cards of this kind, until he answers. */
    val ask: StateFlow<ProposalKind?> = _ask.asStateFlow()

    private val _outbound = MutableStateFlow<List<OutboundRow>>(emptyList())
    /** The last 7 days of requests: host, purpose and field kinds. Themis → What left the phone. */
    val outbound: StateFlow<List<OutboundRow>> = _outbound.asStateFlow()

    private val _budget = MutableStateFlow(AttentionBudget())
    val budget: StateFlow<AttentionBudget> = _budget.asStateFlow()

    private val _attention = MutableStateFlow(AttentionDay() to emptyList<DigestItem>())
    /** Today's tally and what waits for the next brief, for Themis → Auto-approve. */
    val attention: StateFlow<Pair<AttentionDay, List<DigestItem>>> = _attention.asStateFlow()

    private val _senses = MutableStateFlow(SensesState())
    val senses: StateFlow<SensesState> = _senses.asStateFlow()

    private val fates = core.fates

    private val _mind = MutableStateFlow(MindState())
    val mind: StateFlow<MindState> = _mind.asStateFlow()

    val oneiroi = dev.phonerecall.oneiroi.Oneiroi.get(app)

    private val stopListening: () -> Unit

    init {
        stopListening = NetGate.onBlocked { b ->
            db.log("NetGate", "blocked", b.url)
            _settings.update { it.copy(blocked = it.blocked + 1) }
            prefs.saveBlocked(_settings.value.blocked)
        }
        scope.launch { core.spend.state.collect { s -> _settings.update { it.copy(spend = s) } } }
        // The listener and the workers change things while the screens are open: reload on each bump.
        scope.launch(Dispatchers.IO) { core.changes.collect { reloadSenses(); reloadProposals(); reloadCards(); reloadMind(); reloadAuto(); reloadChat() } }
        scope.launch(Dispatchers.IO) { core.mindHost.chatStatus.collect { w -> _chat.update { it.copy(working = w) } } }
        io {
            val s = prefs.load()
            _settings.value = s.copy(spend = core.spend.state.value, keySet = vault.has())
            fates.autoApprove = s.autoApprove
            _budget.value = core.attention.budget()
            val chat = db.latestChat()
            _chat.value = ChatState(loaded = true, chatId = chat)
            reloadChat()
            fates.sweep()
            reloadProposals()
            reloadCards()
        }
    }

    fun close() = stopListening()

    private fun io(block: suspend CoroutineScope.() -> Unit) = scope.launch(Dispatchers.IO, block = block)

    private fun writable(): List<CalendarChoice> = AndroidLocalState.writable(app)

    fun writableCalendars(): List<CalendarChoice> = writable()

    private fun reloadProposals() {
        val all = db.all()
        _proposals.value = all
        _fatigue.value = fates.stats()
        // The day's one "Was this useful?", among kinds on cards he can see now and hasn't rated.
        _ask.value = core.attention.ask(all.filter { it.status == ProposalStatus.PENDING }.flatMap { p -> p.steps.map { it.kind } }, fates.unrated())
    }

    /** The auto-approve list can change from a confirmed meta-proposal on another thread. */
    private fun reloadAuto() {
        val auto = prefs.load().autoApprove
        if (auto != _settings.value.autoApprove) { _settings.update { it.copy(autoApprove = auto) }; fates.autoApprove = auto }
    }

    /** Olympus's proactive cards, through the day's attention budget. What waits goes to the morning brief. */
    private fun reloadCards() {
        val now = System.currentTimeMillis()
        val all = LiveActions.codeCards(now).map { codeCard(it) } + Agency.planCards(app, now).map { planCard(it) } + db.cards(now).map {
            // "Call back Mom?": her card wears the real buttons of the card she pointed at, while its notification lasts.
            GeneratedCard(it.title, it.body, listOf("Got it"), it.expiresAt, CardSource.ATHENA, id = "athena-${it.id}", real = true,
                live = LiveActions.borrowedFrom(it.id)?.let(::live).orEmpty(), replies = LiveActions.borrowedFrom(it.id)?.let(::replies).orEmpty())
        } + core.store.cards(now).map { senseCard(it) }
        _cards.value = core.attention.cards(all, { it.id }, { it.title }, { it.urgency })
        _digest.value = core.attention.forBrief()
    }

    private fun reloadSenses() {
        val st = core.store
        val all = core.sections.all()
        _senses.value = SensesState(
            loaded = true,
            sections = all,
            items = all.associate { it.id to st.items(it.id) },
            captures = st.captures(100),
            changes = core.sections.changes(30),
            people = st.people().sortedWith(compareBy({ it.tier.ordinal }, { it.name.lowercase() })),
            lethe = st.lethe(200),
            jobs = st.jobs(),
            listening = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
        )
        NameGuard.update(_senses.value.people.flatMap { listOfNotNull(it.name, it.term) })
    }

    /** On resume: expire stale proposals, refresh the calendar list for pickers, run hygiene once, sync the mind. */
    fun onResume() = io {
        // Not at process start: an alarm or geofence wake-up must not wait behind a minute of old-format decrypts.
        dev.phonerecall.brain.SealMigration.start(app)
        fates.sweep()
        // Missed calls he returned while Zeus was closed.
        core.sweepCalls()
        // The fatigue meter's one meta-proposal a week, when a kind has earned it.
        fates.offerAutoApprove()
        reloadProposals()
        _calendars.value = writable()
        reloadCards()
        reloadSenses()
        SensesWork.schedule(app)
        core.run { hygiene() }
        dev.phonerecall.mind.MindWork.schedule(app)
        dev.phonerecall.mind.HeartbeatWork.schedule(app)
        if (vault.has()) dev.phonerecall.mind.MindWork.syncNow(app)
        memoryCareOnResume()
        reloadMind()
        pregenerateArrivals()
        // Context music: at most one card per context per day (festival morning, office focus, evening).
        runCatching { dev.phonerecall.music.MusicCore.get(app).offerContext(reads); reloadProposals() }
    }

    // ---- phase 2c: the mind, long jobs, Alexandria, Places ----

    private val places = dev.phonerecall.places.PlaceStore(app)

    private fun reloadMind() {
        val h = core.mindHost
        val m = h.mind
        val a = h.alexandria
        _mind.update {
            it.copy(
                ready = true, setUp = m.ids.agent(dev.phonerecall.mind.MindPrompts.ATHENA_NAME) != null, session = m.currentSession(),
                jobs = m.jobs().sortedByDescending { j -> j.updatedAt }, log = m.log(), wakeupsToday = dev.phonerecall.mind.WakeLog.today(h.kv, ZoneId.systemDefault()),
                lastSync = h.kv.get("sync.last")?.toLongOrNull(), circle = m.circleSpend(), metisNote = h.kv.get("metis.unavailable"),
                places = places.all(), here = places.current(),
                memoryCounts = a.counts(), memoryChunks = a.chunkCount(), memoryVectors = a.vectorCount(),
                embedder = dev.phonerecall.memory.GemmaEmbedder.status, documents = h.indexer.picked(),
                memoryStatus = dev.phonerecall.memory.IndexStatus.line(
                    h.kv.get("alexandria.last")?.toLongOrNull(), h.indexingNow, h.kv.get("alexandria.error"), a.chunkCount(), a.vectorCount(),
                    dev.phonerecall.memory.GemmaEmbedder.model(app), app.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true,
                ) { t -> java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US).format(java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())) },
                indexing = h.indexingNow,
                heartbeat = dev.phonerecall.mind.HeartbeatStore(h.kv).view(ZoneId.systemDefault()),
            )
        }
    }

    /** Themis → Heartbeat: on by default; off leaves the worker running but it asks nothing. */
    fun setHeartbeat(on: Boolean) {
        dev.phonerecall.mind.HeartbeatStore(core.mindKv).on = on
        dev.phonerecall.clio.Clio.info("heartbeat", if (on) "turned on" else "turned off")
        reloadMind()
    }

    fun refreshMind() = io { reloadMind() }

    fun cancelJob(id: String) = io { core.mindHost.locked { cancelJob(id) }; dev.phonerecall.mind.MindWork.flushSoon(app, 0); reloadMind() }

    /** Themis → What Athena remembers: the memory store, read-only here. */
    fun loadRemembered() = io {
        _mind.update { it.copy(remembered = null, rememberedError = null) }
        val r = runCatching { core.mindHost.locked { remembered() } }
        _mind.update { it.copy(remembered = r.getOrNull() ?: emptyList(), rememberedError = r.exceptionOrNull()?.let { e -> AthenaEngine.plainError(e) }) }
    }

    /** "Forget this": deletes the file through the API. His action, never the agent's. */
    fun forget(file: dev.phonerecall.mind.MemoryFile) = io {
        val r = runCatching { core.mindHost.locked { forget(file) } }
        _mind.update { s -> s.copy(remembered = s.remembered?.filter { it.id != file.id }, rememberedError = r.exceptionOrNull()?.let { e -> AthenaEngine.plainError(e) }) }
    }

    // ---- Oneiroi (Memory care) ----

    /** Schedules the dream and the sweep; a chat left for 30 minutes counts as ended. */
    private fun memoryCareOnResume() {
        dev.phonerecall.oneiroi.OneiroiWork.schedule(app)
        val chat = _chat.value.chatId
        val last = _chat.value.lines.lastOrNull()?.at
        if (chat != null && last != null && System.currentTimeMillis() - last > 30 * 60_000L && core.mindHost.chatStatus.value == null) dev.phonerecall.oneiroi.OneiroiWork.reflect(app, chat)
        runCatching { oneiroi.sweepIfDue() }
        oneiroi.reload()
    }

    fun loadMemoryCare() = io { oneiroi.reload() }
    fun forgetFact(id: Int) = io { oneiroi.retract(id) }
    fun confirmFact(id: Int) = io { oneiroi.confirm(id) }
    fun decideNudge(id: String, accept: Boolean) = io { oneiroi.decide(id, accept); reloadProposals() }
    fun removeRef(term: String) = io { oneiroi.removeRef(term) }
    fun dreamNow() = io { oneiroi.dream() }

    // Alexandria
    private val _hits = MutableStateFlow<List<dev.phonerecall.memory.MemoryHit>>(emptyList())
    val hits: StateFlow<List<dev.phonerecall.memory.MemoryHit>> = _hits.asStateFlow()

    fun searchMemory(q: String) = io {
        val h = core.mindHost
        _hits.value = h.alexandria.search(q, 10, if (Nano.foreground()) dev.phonerecall.memory.NanoReranker(NanoModel, Nano::foreground) else dev.phonerecall.memory.LexicalReranker, forAgent = false, unlocked = false)
    }

    fun reindexMemory() = io { core.mindHost.index(100); reloadMind() }

    /** Index now: a one-off background run of every source and passage, without the sync's network or charging conditions. */
    fun indexMemoryNow() = io { dev.phonerecall.mind.MindWork.indexNow(app); reloadMind() }

    fun addDocument(uri: android.net.Uri, onDone: (String?) -> Unit) = io {
        val d = runCatching { core.mindHost.indexer.add(uri) }.getOrNull()
        reloadMind()
        launch(Dispatchers.Main) { onDone(if (d == null) "Zeus reads PDF, text, Markdown and CSV files." else null) }
    }

    fun removeDocument(id: String) = io { core.mindHost.indexer.removeDoc(id); reloadMind() }

    // Places
    fun savePlace(p: dev.phonerecall.places.Place) = io {
        places.upsert(p)
        db.log("The owner", "saved place", p.name)
        dev.phonerecall.places.Places.register(app)
        reloadMind(); pregenerateArrivals()
    }

    fun removePlace(id: String) = io { places.remove(id); dev.phonerecall.places.Places.register(app); reloadMind() }

    fun registerGeofences() = io { dev.phonerecall.places.Places.register(app) }

    /** Nano writes each place's offline arrival text while Zeus is on screen (it can't in the background). */
    private suspend fun pregenerateArrivals() {
        if (!Nano.foreground()) return
        for (p in places.all()) {
            val lists = dev.phonerecall.places.Places.lists(core, p)
            val key = dev.phonerecall.places.ArrivalText.itemsKey(lists)
            if (p.pregenFor == key) continue
            val text = dev.phonerecall.places.ArrivalText.pregenerate(NanoModel, p.name, lists) ?: continue
            places.upsert(p.copy(pregen = text, pregenFor = key))
        }
    }

    // ---- Parnassus, sections, People, Lethe (phase 2b) ----

    private fun senses(block: SenseEngine.() -> Unit) { core.run(block) }

    /** Praxis's clarify: Reference files the words in his Reference list. Returns the Undo, which runs after it in order. */
    fun fileReference(text: String): () -> Unit {
        val ids = java.util.concurrent.atomic.AtomicReference<List<Long>>(emptyList())
        senses { ids.set(runCatching { core.addToList("Reference", listOf(text)) }.getOrDefault(emptyList())) }
        return { senses { core.removeItems(ids.get()) } }
    }

    /** Keeps a capture in Parnassus and sorts it (rules, then Nano on screen, or Haiku). */
    fun capture(text: String, kind: CaptureKind = CaptureKind.TEXT) {
        val t = text.trim()
        if (t.isEmpty()) return
        val k = if (kind == CaptureKind.TEXT && Regex("(?i)^https?://\\S+$").matches(t)) CaptureKind.LINK else kind
        senses { capture(k, t) }
    }

    /** A photo: stored sealed in app-private files; only Nano (on the phone) ever looks at it. */
    fun capturePhoto(image: android.graphics.Bitmap, caption: String = "") = io {
        val dir = java.io.File(app.filesDir, "parnassus").apply { mkdirs() }
        val f = java.io.File(dir, "${System.currentTimeMillis()}.bin")
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        f.writeBytes(KeystoreSealer("zeus.aegis.photos").sealBytes(bytes))
        senses { capture(CaptureKind.PHOTO, caption, f.name, image) }
    }

    /** Text, a link or an image shared to Zeus from another app. */
    fun captureShared(intent: android.content.Intent) {
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        if (uri != null && intent.type?.startsWith("image/") == true) {
            io {
                val bmp = runCatching {
                    app.contentResolver.openInputStream(uri)?.use { s ->
                        val b = android.graphics.BitmapFactory.decodeStream(s) ?: return@use null
                        val scale = 1024f / maxOf(b.width, b.height)
                        if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
                    }
                }.getOrNull()
                if (bmp != null) capturePhoto(bmp, text)
            }
            return
        }
        val combined = listOf(subject, text).filter { it.isNotEmpty() }.distinct().joinToString(": ")
        if (combined.isNotEmpty()) capture(combined, if (Regex("(?i)https?://").containsMatchIn(combined)) CaptureKind.LINK else CaptureKind.TEXT)
    }

    fun undoCapture(id: Long) = senses { undoCapture(id) }

    fun newSection(name: String) = senses { sections.create(name, Actor.OWNER); core.changed() }
    fun renameSection(id: Long, name: String) = senses { sections.rename(id, name, Actor.OWNER); core.changed() }
    fun hideSection(id: Long) = senses { sections.hide(id, Actor.OWNER); core.changed() }
    fun showSection(id: Long) = senses { sections.show(id, Actor.OWNER); core.changed() }
    fun pinSection(id: Long, on: Boolean) = senses { sections.pin(id, on); core.changed() }
    fun undoChange(changeId: Long) = senses { sections.undo(changeId); core.changed() }

    fun addItem(sectionId: Long, text: String) {
        val t = text.trim().takeIf { it.isNotEmpty() } ?: return
        senses {
            store.addItem(SectionItem(sectionId = sectionId, text = t, createdAt = System.currentTimeMillis()))
            sections.touch(sectionId)
            core.changed()
        }
    }

    fun toggleItem(item: SectionItem) = senses {
        val now = System.currentTimeMillis()
        store.updateItem(item.copy(done = !item.done, doneAt = if (item.done) null else now))
        sections.touch(item.sectionId, now)
        core.changed()
    }

    fun setItemNote(item: SectionItem, note: String) = senses { store.updateItem(item.copy(note = note.trim().take(300))); core.changed() }

    /** His choice on the People screen; null goes back to learning. */
    fun setTier(key: String, tier: Tier?) = senses {
        store.person(key)?.let { store.savePerson(it.copy(pinned = tier)); core.audit.log("The owner", "tier", "${tier?.label ?: "Learn"} for one person") }
        core.changed()
    }

    fun setRelation(key: String, relation: Relation?) = senses {
        store.person(key)?.let { store.savePerson(it.copy(relation = relation, confirmed = relation != null, asked = true)) }
        core.changed()
    }

    /** His own choice of where a person lives: a zone id, or null for his own. */
    fun setZone(key: String, zone: String?) = senses {
        store.person(key)?.let { store.savePerson(it.copy(zone = zone)) }
        core.changed()
    }

    /** Sealed in Calypso: this person's notifications are never stored again. */
    fun setCalypso(key: String, on: Boolean) = senses {
        store.person(key)?.let { store.savePerson(it.copy(calypso = on)) }
        core.audit.log("The owner", if (on) "sealed in Calypso" else "unsealed", "one person")
        core.changed()
    }

    /** Relation fields from Contacts, when he has allowed Contacts. */
    fun refreshPeople() = senses { contactRelations(core.readContactRelations()); contactZones(core.readIndiaContacts()) }

    fun restoreFromLethe(id: Long) = senses { restore(id) }

    fun notBirthday(id: Long) = senses { db.get(id)?.let { notBirthday(it) } }

    private fun senseCard(c: SenseCard): GeneratedCard {
        val (actions, link) = AccountAlerts.actions(c.key, c.source)
        return GeneratedCard(
            c.title, c.body, actions, c.until,
            when (c.source) {
                "BANK" -> CardSource.BANK; "SECURITY" -> CardSource.SECURITY; "METRON" -> CardSource.METRON
                AccountAlerts.QUIET_SOURCE -> CardSource.ACCOUNT; else -> CardSource.ATHENA
            },
            // The update time is part of the id, so "Amma called twice" shows again after he cleared "Amma called".
            id = SenseCards.id(c), real = true,
            urgency = senseUrgency(c),
            live = live(c.key),
            replies = replies(c.key, c.body) + laterChip(c),
            link = link,
        )
    }

    /** A sense card's buttons from its notification (one-tap agency); empty once the notification is gone. A reply becomes Edit next to its chips. */
    private fun live(card: String): List<CardAction> {
        val note = LiveActions.noteOf(card) ?: return emptyList()
        return LiveActions.forNote(note).mapIndexed { i, a -> CardAction(if (a.reply) "Edit" else a.label, ActionRole.LIVE, Agency.payload(note, i)) }
    }

    /** The chips for the first button that takes a text: Athena's draft (humanized), then two short ones. */
    private fun replies(card: String, text: String? = null): List<CardAction> {
        val note = LiveActions.noteOf(card) ?: return emptyList()
        val actions = LiveActions.forNote(note)
        val i = actions.indexOfFirst { it.says }.takeIf { it >= 0 } ?: return emptyList()
        val draft = LiveActions.draftForNote(note)?.let { dev.phonerecall.oneiroi.Humanizer.fix(it).text }
        // Asked where he is while he drives: "Home in N min" first among the short ones (qol/HomeEta).
        val eta = runCatching { dev.phonerecall.qol.HomeEta.chip(app, text) }.getOrNull()
        return Replies.chips(draft, listOfNotNull(eta) + actions[i].choices, card.startsWith("call:"))
            .mapIndexed { n, t -> CardAction(Replies.label(t, draft != null && n == 0), ActionRole.LIVE_SEND, Agency.sayPayload(note, i, t)) }
    }

    /** "Later" on a message card (qol/Later): the fitting time named on the chip. */
    private fun laterChip(c: SenseCard): List<CardAction> {
        if (!c.key.startsWith("msg:")) return emptyList()
        return runCatching { listOf(dev.phonerecall.qol.Later.chip(SenseCards.instance(c), dev.phonerecall.qol.Later.now(app).first)) }.getOrDefault(emptyList())
    }

    /** A plan from a chat thread: "PUBG at 10:00 PM with Alex", its app and one-tap replies, or the two readings of an unclear time. */
    private fun planCard(p: Agency.PlanCard) = GeneratedCard(
        p.title, p.body, emptyList(), p.until, CardSource.ATHENA,
        id = "plan-${p.thread.hashCode()}-${p.plan.time ?: 0}", real = true, urgency = Urgency.TIME_CRITICAL, travel = true,
        live = LiveActions.forNote(p.note).mapIndexed { i, a -> CardAction(if (a.reply) "Edit" else a.label, ActionRole.LIVE, Agency.payload(p.note, i)) }
            .filter { it.label != "Edit" || p.plan.choices.isEmpty() },
        replies = if (p.plan.choices.isNotEmpty()) p.picks.map { (label, at) -> CardAction(label, ActionRole.PLAN_PICK, "${p.thread}|$at") }
        else LiveActions.forNote(p.note).indexOfFirst { it.reply }.takeIf { it >= 0 }?.let { i ->
            Plans.REPLIES.map { CardAction(it, ActionRole.LIVE_SEND, Agency.sayPayload(p.note, i, it)) }
        }.orEmpty(),
    )

    /** A one-time code, in memory only: the card never shows the code, and goes with the notification (ten minutes at most). */
    private fun codeCard(c: LiveActions.CodeCard) = GeneratedCard(
        "Code from ${c.app}", "Copy it from here. Zeus clears the clipboard after a minute.", emptyList(), c.until, CardSource.ATHENA,
        id = "code-${c.note.hashCode()}-${c.at}", real = true, urgency = Urgency.TIME_CRITICAL,
        live = LiveActions.forNote(c.note).mapIndexed { i, a -> CardAction(a.label, ActionRole.LIVE, Agency.payload(c.note, i)) },
    )

    /** Calls and messages from people, and last chances, never wait; deliveries and notes may. */
    private fun senseUrgency(c: SenseCard): Urgency = when {
        c.source == "BANK" || c.source == "SECURITY" -> Urgency.SAFETY
        c.key.startsWith("last:") -> Urgency.DEADLINE_TODAY
        c.key.startsWith("call:") || c.key.startsWith("msg:") -> Urgency.TIME_CRITICAL
        else -> Urgency.NICE
    }

    // ---- Delphi ----

    /** Any time, even while she is answering: Oneiroi and the eggs answer on the phone, everything else goes to her. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        _chat.update { it.copy(error = null) }
        io {
            try {
                val chat = _chat.value.chatId ?: db.newChat().also { id -> _chat.update { it.copy(chatId = id) } }
                // Oneiroi: "what do you know about ..." and "#12 is wrong, it's ..." are answered here, no model.
                oneiroi.ask(t)?.let { answer ->
                    val u = db.addLine(chat, LineKind.USER, t)
                    val l = db.addLine(chat, LineKind.MEMORY, dev.phonerecall.oneiroi.MemoryView.encode(answer))
                    _chat.update { it.copy(lines = it.lines + u + l) }
                    return@io
                }
                val notes = db.takeNotes(chat)
                // Names and nicknames he uses resolve through the lookup table before Athena answers.
                val pre = notes.map { "[Zeus note] $it" } + listOfNotNull(oneiroi.refsNote(t))
                val line = db.addLine(chat, LineKind.USER, t)
                _chat.update { it.copy(lines = it.lines + line) }
                // Easter eggs: the oracle answers "Who are you?" once, on the phone; asking Annabeth gets a wink.
                dev.phonerecall.ui.Eggs.oracle(t, dev.phonerecall.ui.Eggs.oracleUsed())?.let { riddle ->
                    dev.phonerecall.ui.Eggs.useOracle()
                    val l = db.addLine(chat, LineKind.ATHENA, riddle)
                    _chat.update { it.copy(lines = it.lines + l) }
                    return@io
                }
                dev.phonerecall.ui.Eggs.annabethWink(t)?.let { wink ->
                    dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.ANNABETH)
                    val l = db.addLine(chat, LineKind.NOTE, wink, delivered = true)
                    _chat.update { it.copy(lines = it.lines + l) }
                }
                if (!vault.has()) throw NoKey()
                // Everything else goes to Athena herself (her one session), queued on the phone so he never waits to send.
                core.mindHost.ask(chat, t, pre)
            } catch (e: Exception) {
                _chat.update { it.copy(working = null, streaming = "", error = AthenaEngine.plainError(e)) }
            }
        }
    }

    /** Hermes ran his command on the phone: his words and the result go in the transcript, and nothing goes to Athena. */
    fun hermesRan(text: String, result: String) {
        io {
            val chat = _chat.value.chatId ?: db.newChat().also { id -> _chat.update { it.copy(chatId = id) } }
            val u = db.addLine(chat, LineKind.USER, text)
            val n = db.addLine(chat, LineKind.NOTE, result)
            _chat.update { it.copy(lines = it.lines + u + n) }
        }
    }

    /** Sends whatever is still queued for her (a message held while she was busy or offline). */
    fun retry() {
        _chat.update { it.copy(error = null) }
        core.mindHost.deliverSoon()
    }

    /** How many of the newest lines the screen holds; "Show earlier" adds a page. Opening sealed lines costs, so not all at once. */
    @Volatile private var shown = PAGE

    /**
     * One continuous history: every chat and every one of her sessions in one scroll, a thin divider where a chat began
     * (her sessions add their own). Her replies and proposals land in the lines (MindHost writes them); re-read on each change.
     */
    private fun reloadChat() {
        val rows = db.history(shown + 1)
        val more = rows.size > shown
        val lines = ArrayList<ChatLine>()
        var prev: Long? = null
        for ((chat, l) in if (more) rows.drop(1) else rows) {
            if (prev != null && chat != prev && l.kind != LineKind.DIVIDER) lines += ChatLine(-l.id, LineKind.DIVIDER, DIVIDER_DAY.format(java.time.Instant.ofEpochMilli(l.at).atZone(ZoneId.systemDefault())), at = l.at)
            lines += l
            prev = chat
        }
        _chat.update { it.copy(lines = lines, earlier = more) }
    }

    fun earlier() = io { shown += PAGE; reloadChat() }

    fun newChat() {
        io {
            // The chat that just ended: Oneiroi reflects on it once.
            _chat.value.chatId?.let { dev.phonerecall.oneiroi.OneiroiWork.reflect(app, it) }
            val id = db.newChat()
            _chat.update { it.copy(chatId = id, error = null) }
            reloadChat()
        }
    }

    fun dismissError() = _chat.update { it.copy(error = null) }

    private companion object {
        const val PAGE = 300
        val DIVIDER_DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", java.util.Locale.US)
    }

    // ---- Fates ----

    private fun decided(d: Decision) {
        val p = d.proposal ?: return
        val note = when (d) {
            is Decision.Done -> "The owner confirmed “${p.title}”. It was done."
            is Decision.Failed -> "“${p.title}” was confirmed but not done: ${d.reason}."
            is Decision.Rejected -> "The owner rejected “${p.title}”."
            is Decision.Expired -> "“${p.title}” expired before he confirmed it."
            is Decision.Undone -> "The owner undid “${p.title}”."
            else -> null
        }
        val chat = p.conversationId
        if (note != null && chat != null) {
            // The note goes back to Athena with his next message, and shows in that chat now.
            val line = db.addLine(chat, LineKind.NOTE, note, p.id, delivered = false)
            if (_chat.value.chatId == chat) _chat.update { it.copy(lines = it.lines + line) }
        }
        reloadProposals()
        if (d is Decision.Done || d is Decision.Undone || d is Decision.Failed) { reloadCards(); onData() }
    }

    /** A card's binding for what it shows now (the calendars it may name come from the phone). */
    fun bind(p: Proposal): Binding = Bindings.of(p, _calendars.value, System.currentTimeMillis())

    /** Plain code, no model: Fates.confirm checks pending and the binding, revalidates, runs and records. */
    fun confirm(id: Long, seen: Binding, onRefused: (String) -> Unit = {}) = io {
        val d = fates.confirm(id, seen)
        if (d is Decision.Refused) { reloadProposals(); launch(Dispatchers.Main) { onRefused(d.reason) } } else decided(d)
    }

    /** "Was this useful?" Yes or No: feeds the fatigue meter, and today's ask is spent. */
    fun useful(kind: ProposalKind, yes: Boolean) = io { fates.useful(kind, yes); core.attention.answered(); reloadProposals() }

    fun loadAttention() = io {
        val next = Attention.briefDay(java.time.ZonedDateTime.now()).toString()
        _attention.value = core.attention.day() to core.attention.digest().filter { it.forDay >= next }
        _fatigue.value = fates.stats()
    }

    fun loadOutbound() = io { _outbound.value = db.outbound(System.currentTimeMillis() - Outbound.KEEP_MS) }

    fun setBudget(b: AttentionBudget) = io {
        core.attention.setBudget(b); _budget.value = core.attention.budget()
        db.log("The owner", "set attention budget", "${b.cards} cards, ${b.notifications} notifications a day")
        reloadCards()
        loadAttention()
    }

    fun reject(id: Long) = io { decided(fates.reject(id)) }
    fun undo(id: Long) = io { decided(fates.undo(id)) }
    fun toggleStep(id: Long, index: Int) = io { fates.toggle(id, index); reloadProposals() }

    fun edit(id: Long, index: Int, payload: Map<String, String>, onResult: (String?) -> Unit) = io {
        val before = db.get(id)?.steps?.getOrNull(index)?.takeIf { it.kind == ProposalKind.ADD_TO_LIST }?.payload?.get("items")
        val d = fates.edit(id, index, payload)
        // Kairos: his correction of a list ("kandi pappu" to "toor dal") teaches a grocery alias.
        if (before != null && d is Decision.Edited) {
            fun names(s: String?) = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            dev.phonerecall.moments.MomentsCore.corrected(app, names(before), names(payload["items"]))
        }
        reloadProposals()
        launch(Dispatchers.Main) { onResult((d as? Decision.Refused)?.reason) }
    }

    fun pickCalendar(id: Long, index: Int, calendar: CalendarChoice) = io {
        val p = db.get(id) ?: return@io
        val step = p.steps.getOrNull(index) ?: return@io
        fates.edit(id, index, step.payload + ("calendar_id" to "${calendar.id}"))
        reloadProposals()
    }

    /** After he grants calendar writes or Google Calendar: re-check the missing choices. */
    fun refreshNeeds(id: Long) = io { _calendars.value = writable(); fates.refresh(id); reloadProposals() }

    // ---- Themis ----

    fun setModel(m: BrainModel) { _settings.update { it.copy(model = m, auto = false) }; prefs.saveModel(m) }
    fun setAuto() { _settings.update { it.copy(auto = true) }; prefs.saveAuto() }

    fun setCap(usd: Int) = io {
        val s = core.spend.setCap(usd)
        db.log("The owner", "set cap", "$${s.capUsd} a month")
    }

    fun toggleAuto(kind: ProposalKind) {
        if (kind !in ProposalKind.autoCandidates) return
        val next = _settings.value.autoApprove.let { if (kind in it) it - kind else it + kind }
        _settings.update { it.copy(autoApprove = next) }
        fates.autoApprove = next
        prefs.saveAuto(next)
        io { db.log("The owner", if (kind in next) "auto-approve on" else "auto-approve off", kind.label) }
    }

    fun loadAudit() = io { _audit.value = db.audit() }

    // ---- Calypso (the screen asks for the fingerprint before any of these) ----

    fun saveKey(key: String) = io {
        val rotate = vault.has()
        vault.save(key)
        _settings.update { it.copy(keySet = true) }
        db.log("The owner", if (rotate) "rotated key" else "added key", "Anthropic")
    }

    fun deleteKey() = io {
        vault.delete()
        _settings.update { it.copy(keySet = false) }
        db.log("The owner", "deleted key", "Anthropic")
    }

    /** The masked form only; never the key in full. */
    fun maskedKey(reveal: Boolean, then: (String?) -> Unit) = io {
        val m = vault.read()?.let { KeyVault.mask(it, reveal) }
        if (reveal) db.log("The owner", "revealed key (masked)", "Anthropic")
        launch(Dispatchers.Main) { then(m) }
    }

    fun keySavedAt(then: (Long?) -> Unit) = io { val t = vault.savedAt(); launch(Dispatchers.Main) { then(t) } }

    fun calypsoOpened() = io { db.log("The owner", "opened Calypso", "fingerprint") }

    /** Copies the key, marked sensitive (no clipboard preview), and clears the clipboard after 30 s. */
    fun copyKey() = io {
        val k = vault.read() ?: return@io
        db.log("The owner", "copied key", "Anthropic, cleared after 30 s")
        launch(Dispatchers.Main) {
            val cm = app.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Anthropic key", k).apply {
                description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            cm.setPrimaryClip(clip)
            Handler(Looper.getMainLooper()).postDelayed({
                val now = cm.primaryClip
                if (now != null && now.description?.label == "Anthropic key") cm.clearPrimaryClip()
            }, 30_000L)
        }
    }
}
