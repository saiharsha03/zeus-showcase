package dev.phonerecall

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.Availability
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalMode
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.CalendarPipeline
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ContactBirthday
import dev.phonerecall.data.CuratedCalendar
import dev.phonerecall.data.RelevanceConfig
import dev.phonerecall.data.RelevanceStore
import dev.phonerecall.data.Rule
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.EngagementStore
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.Prefs
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.data.TaskStore
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.Board
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.CardKind
import dev.phonerecall.home.SampleData
import dev.phonerecall.home.ScoredCard
import dev.phonerecall.nowbar.NowBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class Settings(val dark: Boolean = true, val sample: Boolean = false, val nowBar: Boolean = true, val plainNames: Boolean = false, val secondClock: String = "Asia/Kolkata")

enum class HomeMode { BOARD, EDIT, ADD }

/** The one Home state the screen draws. `ready` is false only until prefs and the calendar have loaded. */
data class HomeState(val ready: Boolean = false, val board: Board = Board())

/** Days loaded for the Calendar screen, by local date. */
data class CalendarDays(val allowed: Boolean = false, val days: Map<LocalDate, List<CalItem>> = emptyMap(), val version: Int = 0)

/**
 * All IO happens here, on Dispatchers.IO: prefs, the learning table, tasks, the closet and the calendar.
 * The board is a pure function of those inputs (CardEngine.build), recomputed on Default when any changes.
 */
class AthenaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val store = EngagementStore(app)
    /** One task database per process, shared with the senses and the workers. */
    private val taskStore = dev.phonerecall.brain.BrainCore.get(app).tasks
    private val closetSource = ClosetSource(app)
    private val calendarSource = CalendarSource(app)
    private val relevanceStore = RelevanceStore(app)
    private val birthdaySource = BirthdaySource(app)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val homePrefs = MutableStateFlow<HomePrefs?>(null)
    /** Swiped-away card instances: their own prefs file, also written by the Now Bar delete intent, so watched rather than held in HomePrefs. */
    private val dismissals = MutableStateFlow(dev.phonerecall.data.Dismissals.load(app))
    private val dismissalsWatch = dev.phonerecall.data.Dismissals.watch(app) { dismissals.value = it }
    /** Read-only view of the arrangement (the Closet's shopping list reads `bought` from it). */
    val arrangement: StateFlow<HomePrefs?> = homePrefs.asStateFlow()
    /** Raw events for Home's window, before the relevance layer. */
    private val calendarRaw = MutableStateFlow(CalendarSnapshot.Unloaded)

    private val _calendars = MutableStateFlow<Map<Long, CalendarInfo>>(emptyMap())
    val calendars: StateFlow<Map<Long, CalendarInfo>> = _calendars.asStateFlow()

    private val _relevance = MutableStateFlow(RelevanceConfig())
    val relevance: StateFlow<RelevanceConfig> = _relevance.asStateFlow()

    private val _birthdays = MutableStateFlow<List<ContactBirthday>>(emptyList())
    val birthdays: StateFlow<List<ContactBirthday>> = _birthdays.asStateFlow()

    /** The curated calendar and the availability model, for every reader in the app. */
    private val curated = MutableStateFlow(CuratedCalendar(CalendarSnapshot.Unloaded, emptyMap()))
    val availability: StateFlow<Map<LocalDate, Availability>> get() = _availability
    private val _availability = MutableStateFlow<Map<LocalDate, Availability>>(emptyMap())
    private val learned = MutableStateFlow(Learned())
    private val clock = MutableStateFlow(ZonedDateTime.now())

    private val _tasks = MutableStateFlow(TaskSnapshot())
    val tasks: StateFlow<TaskSnapshot> = _tasks.asStateFlow()

    private val _closet = MutableStateFlow(ClosetSnapshot())
    val closet: StateFlow<ClosetSnapshot> = _closet.asStateFlow()

    /** Venus: the wear log and favorites, today's outfit (also published as OutfitState), trip packing. */
    private val venusStore = dev.phonerecall.closet.VenusStore(app)
    private val _venus = MutableStateFlow(dev.phonerecall.closet.VenusPrefs())
    val venus: StateFlow<dev.phonerecall.closet.VenusPrefs> = _venus.asStateFlow()
    private val _outfit = MutableStateFlow<dev.phonerecall.closet.OutfitPlan?>(null)
    val outfit: StateFlow<dev.phonerecall.closet.OutfitPlan?> = _outfit.asStateFlow()
    private val _packing = MutableStateFlow<List<dev.phonerecall.closet.TripPack>?>(null)
    val packing: StateFlow<List<dev.phonerecall.closet.TripPack>?> = _packing.asStateFlow()
    private var polishing: String? = null

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _mode = MutableStateFlow(HomeMode.BOARD)
    val mode: StateFlow<HomeMode> = _mode.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Bumped when a notification asks for Home. */
    private val _homeRequests = MutableStateFlow(0)
    val homeRequests: StateFlow<Int> = _homeRequests.asStateFlow()

    private val _calendarDays = MutableStateFlow(CalendarDays())
    val calendarDays: StateFlow<CalendarDays> = _calendarDays.asStateFlow()
    private val loadedMonths = HashSet<LocalDate>()

    /**
     * Athena's brain (phase 2a): Delphi, Fates, Calypso's key, the spend cap. Its read tools see the same
     * state this ViewModel holds; a confirmed proposal that touches tasks or cards reloads them here.
     */
    val brain: dev.phonerecall.brain.BrainHub = dev.phonerecall.brain.BrainHub(
        app, viewModelScope,
        object : dev.phonerecall.brain.BrainReads {
            override fun now(): ZonedDateTime = ZonedDateTime.now()
            override fun relevantEvents(from: Long, to: Long): List<CalItem> {
                val cals = _calendars.value
                val raw = CalendarSnapshot(true, calendarSource.readRange(from, to, cals), true)
                return CalendarPipeline.curate(raw, cals, _relevance.value, _birthdays.value).snapshot.events.filter { it.end > from && it.begin < to }
            }
            override fun allEvents(from: Long, to: Long): List<CalItem> = calendarSource.readRange(from, to, _calendars.value)
            override fun tasks(): TaskSnapshot = _tasks.value
            override fun closet(): ClosetSnapshot = _closet.value
            override fun homeCards(): List<ScoredCard> = _home.value.board.cards
            override fun availability(): Map<LocalDate, Availability> = _availability.value
            override fun writableCalendars() = brain.writableCalendars()
        },
        onData = { viewModelScope.launch(Dispatchers.IO) { _tasks.value = taskStore.load() } },
    )

    /** Phase 3: Lemnos (the VPS), Pantheon's workers and Cerberus. One per process; it outlives this ViewModel. */
    val lemnos: dev.phonerecall.lemnos.LemnosHub = dev.phonerecall.lemnos.LemnosHub.get(app)
    private val lemnosHome = combine(lemnos.state, brain.mind, clock) { s, m, now -> dev.phonerecall.lemnos.LemnosHome.of(s, m.jobs, now.toInstant().toEpochMilli()) }.distinctUntilChanged()

    /** Prometheus: loads the card weights and notes he approved. It runs nothing until he taps Run. */
    private val prometheus = dev.phonerecall.prometheus.PrometheusCore.get(app).also { it.hub = brain }

    /** A screen asked for: "PARNASSUS" after a share, say. Counter first so the same request repeats. */
    private val _destRequests = MutableStateFlow(0 to "")
    val destRequests: StateFlow<Pair<Int, String>> = _destRequests.asStateFlow()
    fun requestDest(name: String) = _destRequests.update { (it.first + 1) to name }

    private var calendarJob: Job? = null
    private var observing = false
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshCalendar(debounce = true)
    }

    init {
        dev.phonerecall.closet.VenusWeather.source = dev.phonerecall.closet.VenusWeather.aeolia
        viewModelScope.launch(Dispatchers.IO) {
            val p = prefs.load()
            _settings.value = Settings(p.dark, p.sample, p.nowBar, p.plainNames, dev.phonerecall.brain.TwoClocks.secondZone.id)
            learned.value = store.load()
            if (p.sample) { if (!taskStore.hasSamples()) taskStore.seed(SampleData.seedTasks(ZonedDateTime.now())) } else if (taskStore.hasSamples()) taskStore.removeSamples()
            _tasks.value = taskStore.load()
            homePrefs.value = p.home
        }
        viewModelScope.launch(Dispatchers.IO) { _closet.value = closetSource.load() }
        viewModelScope.launch(Dispatchers.IO) { _venus.value = venusStore.load() }
        // The morning brief: today's (if it went out) for the Brief card, and the daily request to Iris armed.
        viewModelScope.launch(Dispatchers.IO) { runCatching { dev.phonerecall.brief.BriefStore.settings(app); dev.phonerecall.brief.BriefStore.load(app); dev.phonerecall.brief.BriefCore.schedule(app) } }
        dev.phonerecall.ui.Eggs.init(app)
        // Curate whenever the raw calendar, the rules, the birthdays or Olympia's fixtures change.
        // Olympia's matches join as the "Olympia" calendar; one on now is left to its own card.
        viewModelScope.launch(Dispatchers.Default) {
            combine(combine(calendarRaw, dev.phonerecall.odyssey.OdysseyStore.state, dev.phonerecall.data.DayOverrides.state) { a, b, o -> Triple(a, b, o) }, _calendars, _relevance, _birthdays, dev.phonerecall.olympia.OlympiaStore.state) { (raw, roads, overrides), cals, cfg, bdays, sports ->
                if (!raw.loaded) return@combine CuratedCalendar(raw, emptyMap())
                val from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val games = dev.phonerecall.olympia.OlympiaCalendar.items(sports, from, from + 9 * 86_400_000L, exceptLive = true)
                // Odyssey's leave-by times join Chronos as their own calendar.
                val leave = dev.phonerecall.odyssey.OdysseyCalendar.items(roads, from, from + 9 * 86_400_000L)
                // Milestones (the car's delivery day, and whatever else registers) as their own calendar.
                val marks = dev.phonerecall.data.Milestones.items(dev.phonerecall.data.Milestones.all(), from, from + 9 * 86_400_000L, ZoneId.systemDefault())
                CalendarPipeline.curate(raw.copy(events = raw.events + games + leave + marks), cals, cfg, bdays, overrides = overrides)
            }.collect { curated.value = it; _availability.value = it.availability }
        }
        dev.phonerecall.olympia.Olympia.start(app)
        dev.phonerecall.aeolia.Aeolia.start(app)
        dev.phonerecall.argo.Argo.start(app)
        dev.phonerecall.odyssey.Odyssey.hooks(app)
        // Odyssey's sealed state, read once; the board and Chronos follow its flow.
        viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.odyssey.OdysseyStore.load(app); dev.phonerecall.data.DayOverrides.load(app); dev.phonerecall.moments.MomentsCore.load(app) }
        // Alarm awareness: bedtime, the next ring moment and tomorrow evening's planner, re-armed whenever the app opens.
        viewModelScope.launch(Dispatchers.IO) { runCatching { dev.phonerecall.alarms.AlarmPlanner.arm(app) } }
        // Iaso's watch tile: the next event and the top task (never health data), when either changes.
        viewModelScope.launch(Dispatchers.Default) {
            combine(curated, _tasks, clock) { cur, t, now -> dev.phonerecall.iaso.WatchPayload.of(cur.snapshot.events, t, now) }
                .collect { dev.phonerecall.iaso.WatchBridge.push(app, it) }
        }
        // Hestia's "you're away" comes from Odyssey's current stay; unknown (off, driving, no home) stays null.
        dev.phonerecall.hestia.HestiaCore.whereabouts = dev.phonerecall.hestia.Whereabouts {
            val d = dev.phonerecall.odyssey.OdysseyStore.state.value
            val home = d.home(); val stay = d.stay
            if (!d.settings.on || home == null || stay == null) null
            else dev.phonerecall.odyssey.Geo.meters(home.lat, home.lng, stay.lat, stay.lng) >= 200
        }
        // New fixtures: Chronos re-reads its months so the matches show.
        viewModelScope.launch {
            var seen = -1L
            dev.phonerecall.olympia.OlympiaStore.state.collect { s ->
                val v = s.refreshedAt + s.matches.size + s.settings.hashCode()
                if (seen != -1L && v != seen) clearCalendarDays()
                seen = v
            }
        }
        refreshCalendar()
        // Parnassus and hygiene add and close tasks from the senses thread.
        viewModelScope.launch(Dispatchers.IO) { brain.core.changes.collect { if (it > 0) _tasks.value = taskStore.load() } }
        // A clock that ticks on the minute, so "now" and the scores stay current without polling faster.
        viewModelScope.launch {
            while (true) {
                delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
                clock.value = ZonedDateTime.now()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                combine(clock, combine(homePrefs.filterNotNull(), dismissals) { h, d -> h.copy(dismissed = d) }, curated) { a, b, c -> Triple(a, b, c) },
                combine(learned, _tasks, _closet, combine(brain.cards, brain.digest) { c, d -> c to d }, dev.phonerecall.olympia.OlympiaStore.state) { a, b, c, d, e -> Triple(a, b, c) to (d to e) },
                combine(settings, lemnosHome, _venus, dev.phonerecall.brief.BriefStore.state) { a, b, c, d -> Triple(a, b, c to d) },
                combine(dev.phonerecall.aeolia.WeatherStore.state, dev.phonerecall.odyssey.OdysseyStore.state, brain.senses, dev.phonerecall.hestia.HestiaCore.get(app).state, combine(prometheus.weights, brain.proposals, dev.phonerecall.moments.MomentsCore.prefs) { tw, p, m -> Triple(tw, p, m) }) { w, r, sn, h, x -> Triple(w to h, r to sn.sections.filter { !it.hidden }.map { it.id to it.name }, x) },
                dev.phonerecall.argo.ArgoStore.state,
            ) { (now, hp, cur), (ltc, rest), (s, server, vb), (wh, roads, x), trip ->
                val (tuning, liveProposals, momentPrefs) = x
                val pm = liveProposals to momentPrefs
                val (venus, morning) = vb
                val (weather, hearth) = wh
                val (l, t, closet) = ltc
                val (cardsAndDigest, sports) = rest
                val (brainCards, digest) = cardsAndDigest
                val cal = cur.snapshot
                if (!cal.loaded || !t.loaded || !closet.loaded) return@combine null
                val inputs = BoardInputs(
                    now = now, calendar = cal, availability = cur.availability, sample = s.sample, prefs = hp, learned = l, tasks = t, closet = closet,
                    generated = (if (s.sample) SampleData.generated(now) else emptyList()) + brainCards,
                    generatedAreSamples = true,
                    lemnos = server,
                    sports = dev.phonerecall.olympia.OlympiaCards.card(sports, now),
                    weather = weather.takeIf { it.hasData }, venus = venus,
                    odyssey = dev.phonerecall.odyssey.OdysseyCards.home(roads.first, roads.second, now),
                    hearth = dev.phonerecall.hestia.HestiaCards.card(hearth, dev.phonerecall.hestia.HestiaCore.whereabouts.away(), now),
                    trip = trip.let { dev.phonerecall.argo.ArgoCards.home(it, now.toInstant().toEpochMilli(), now.zone, dev.phonerecall.argo.ArgoLinks.alarms) },
                    tuning = tuning,
                    morning = morning,
                    alarm = runCatching { dev.phonerecall.brain.AlarmWatch.get(app).card(now) }.getOrNull(),
                    digest = digest.map { it.title },
                    moment = if (s.sample) null else dev.phonerecall.moments.MomentsCore.card(getApplication(), now, pm.second, roads.first, weather.takeIf { it.hasData }, hearth, pm.first),
                    birthdays = if (s.sample) dev.phonerecall.brain.BirthdayBoard() else runCatching { dev.phonerecall.brain.BirthdayDay.board(getApplication(), cal.events, now) }.getOrDefault(dev.phonerecall.brain.BirthdayBoard()),
                )
                if (!s.sample) dev.phonerecall.brain.BirthdayDay.armSoon(getApplication())
                val board = CardEngine.build(inputs)
                dev.phonerecall.widgets.WidgetBus.local(getApplication(), inputs, board, s.plainNames)
                HomeState(ready = true, board = board) to s.nowBar
            }.collect { pair ->
                if (pair == null) return@collect
                _home.value = pair.first
                NowBar.sync(getApplication(), pair.first.board, pair.second)
            }
        }
        // Home-screen widgets follow Lemnos and the player from here on (throttled; nothing polls).
        dev.phonerecall.widgets.WidgetBus.watch(app)
    }

    init {
        // Today's outfit for Venus and the widgets: the same rules and inputs as the Home card.
        viewModelScope.launch(Dispatchers.Default) {
            combine(clock, _closet, _venus, curated) { now, closet, venus, cur ->
                if (!closet.imported) return@combine null
                val today = now.toLocalDate()
                val ctx = dev.phonerecall.closet.Outfits.context(
                    today, cur.availability[today], cur.snapshot.events, now.zone, dev.phonerecall.closet.VenusWeather.source.today(today),
                )
                dev.phonerecall.closet.Outfits.plan(closet, ctx, venus) to now
            }.collect { r ->
                val plan = r?.first
                _outfit.value = plan
                val day = plan?.date?.toEpochDay() ?: 0
                dev.phonerecall.closet.OutfitState.set(plan, plan?.let { _venus.value.polished(it.main.key, day) })
                if (plan != null && r.second.hour in 5..11) polish(plan)
            }
        }
    }

    /** Optional: Haiku rewords the morning pick's reason once. Never blocks or changes the outfit. */
    private fun polish(plan: dev.phonerecall.closet.OutfitPlan) {
        val day = plan.date.toEpochDay()
        val key = plan.main.key
        if (_venus.value.polished(key, day) != null || polishing == "$day|$key") return
        // Wording polish only: a reason that names someone ("dinner with Jordan") keeps the rule's own line.
        if (dev.phonerecall.brain.NameGuard.contains(dev.phonerecall.closet.OutfitPolish.user(plan))) return
        val model = runCatching { dev.phonerecall.brain.BrainCore.get(getApplication()).haiku() }.getOrNull() ?: return
        polishing = "$day|$key"
        viewModelScope.launch(Dispatchers.IO) {
            val reply = runCatching {
                model.complete("venus", dev.phonerecall.closet.OutfitPolish.SYSTEM, dev.phonerecall.closet.OutfitPolish.user(plan), 60)
            }.getOrNull()
            val line = dev.phonerecall.closet.OutfitPolish.accept(reply) ?: return@launch
            editVenus { v -> v.copy(polish = (if (v.polishDay == day) v.polish else emptyMap()) + (key to line), polishDay = day) }
        }
    }

    private fun editVenus(f: (dev.phonerecall.closet.VenusPrefs) -> dev.phonerecall.closet.VenusPrefs) {
        val next = _venus.updateAndGet(f)
        viewModelScope.launch(Dispatchers.IO) { venusStore.save(next) }
    }

    fun wore(ids: List<String>) = editVenus { dev.phonerecall.closet.VenusStore.wore(it, ids, LocalDate.now().toEpochDay()) }
    fun unwore(ids: List<String>) = editVenus { dev.phonerecall.closet.VenusStore.unwore(it, ids, LocalDate.now().toEpochDay()) }
    fun notToday(ids: List<String>) = editVenus { dev.phonerecall.closet.VenusStore.skip(it, ids, LocalDate.now().toEpochDay()) }
    fun toggleFavorite(id: String) = editVenus { dev.phonerecall.closet.VenusStore.toggleFavorite(it, id) }

    /** Trips in the next three weeks (travel events on the curated calendar) and what to pack for each. */
    fun loadPacking() {
        viewModelScope.launch(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(22).atStartOfDay(zone).toInstant().toEpochMilli()
            val cals = _calendars.value
            val events = runCatching {
                val raw = CalendarSnapshot(true, calendarSource.readRange(from, to, cals), true)
                CalendarPipeline.curate(raw, cals, _relevance.value, _birthdays.value).snapshot.events
            }.getOrDefault(emptyList())
            val closet = _closet.value
            _packing.value = dev.phonerecall.closet.Packing.trips(events, today, zone).map { t ->
                val w = dev.phonerecall.closet.VenusWeather.source.today(t.start)
                dev.phonerecall.closet.Packing.pack(closet, t, dev.phonerecall.closet.Outfits.warmth(t.start, w), _venus.value)
            }
        }
    }

    // ---- lifecycle ----

    fun onResume() {
        clock.value = ZonedDateTime.now()
        refreshCalendar()
        brain.onResume()
        if (lemnos.paired) { lemnos.refresh(); lemnos.enablePush() }
    }

    fun requestHome() { _mode.value = HomeMode.BOARD; _homeRequests.update { it + 1 } }

    /** Pull to refresh: re-read the calendar, tasks and closet, then re-score. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            loadCalendarBasics()
            calendarRaw.value = calendarSource.read(_calendars.value)
            _tasks.value = taskStore.load()
            _closet.value = closetSource.load()
            clearCalendarDays()
            clock.value = ZonedDateTime.now()
            delay((450L - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _refreshing.value = false
        }
    }

    fun refreshCalendar(debounce: Boolean = false) {
        calendarJob?.cancel()
        calendarJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounce) delay(400)
            loadCalendarBasics()
            val snap = calendarSource.read(_calendars.value)
            calendarRaw.value = snap
            if (debounce) clearCalendarDays()
            if (snap.allowed && !observing) {
                observing = true
                try {
                    getApplication<Application>().contentResolver
                        .registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
                } catch (_: SecurityException) { observing = false }
            }
        }
    }

    /** Calendars, relevance rules and contact birthdays. Small; read on IO. */
    private fun loadCalendarBasics() {
        val olympia = dev.phonerecall.olympia.OlympiaCalendar.INFO
        val odyssey = dev.phonerecall.odyssey.OdysseyCalendar.INFO
        _calendars.value = calendarSource.calendars().associateBy { it.id } + (olympia.id to olympia) + (odyssey.id to odyssey)
        _relevance.value = relevanceStore.load()
        _birthdays.value = birthdaySource.read()
    }

    // ---- relevance ----

    private fun editRelevance(change: (RelevanceConfig) -> RelevanceConfig) {
        val next = change(_relevance.value)
        _relevance.value = next
        viewModelScope.launch(Dispatchers.IO) { relevanceStore.save(next) }
    }

    fun setCalendarMode(id: Long, mode: CalMode) = editRelevance { it.copy(modes = it.modes + (id to mode)) }

    /** Saves a rule (new or edited). His own rules go first, so they win. */
    fun saveRule(r: Rule) = editRelevance { c -> c.copy(rules = listOf(r) + c.rules.filter { it.id != r.id }) }

    fun deleteRule(id: String) = editRelevance { c -> c.copy(rules = c.rules.filter { it.id != id }) }

    fun restoreSeedRules() {
        viewModelScope.launch(Dispatchers.IO) { _relevance.value = relevanceStore.restoreSeeds(_relevance.value) }
    }

    /** After Contacts access changes: re-read birthdays. */
    fun refreshBirthdays() {
        viewModelScope.launch(Dispatchers.IO) { _birthdays.value = birthdaySource.read() }
    }

    override fun onCleared() {
        brain.close()
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(calendarObserver)
    }

    // ---- Calendar screen ----

    private fun clearCalendarDays() {
        synchronized(loadedMonths) { loadedMonths.clear() }
        _calendarDays.update { it.copy(days = emptyMap(), version = it.version + 1) }
    }

    /** Makes sure the months covering [from, to] are loaded. Reads on IO, one month at a time. */
    fun ensureCalendar(from: LocalDate, to: LocalDate) {
        var m = from.withDayOfMonth(1)
        val months = ArrayList<LocalDate>()
        while (!m.isAfter(to)) {
            synchronized(loadedMonths) { if (loadedMonths.add(m)) months += m }
            m = m.plusMonths(1)
        }
        if (months.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            for (month in months) {
                val start = month.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (_calendars.value.isEmpty()) loadCalendarBasics()
                val items = calendarSource.readRange(start, end, _calendars.value, zone) +
                    dev.phonerecall.olympia.OlympiaCalendar.items(dev.phonerecall.olympia.OlympiaStore.state.value, start, end) +
                    dev.phonerecall.odyssey.OdysseyCalendar.items(dev.phonerecall.odyssey.OdysseyStore.load(getApplication()), start, end) +
                    dev.phonerecall.moneta.MonetaCalendar.items(getApplication<Application>(), start, end)
                val byDay = HashMap<LocalDate, MutableList<CalItem>>()
                var d = month
                while (d.isBefore(month.plusMonths(1))) { byDay[d] = ArrayList(); d = d.plusDays(1) }
                for (it in items) {
                    var day = java.time.Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate()
                    val last = java.time.Instant.ofEpochMilli((it.end - 1).coerceAtLeast(it.begin)).atZone(zone).toLocalDate()
                    while (!day.isAfter(last)) { byDay[day]?.add(it); day = day.plusDays(1) }
                }
                _calendarDays.update { cd ->
                    cd.copy(allowed = calendarSource.allowed(), days = cd.days + byDay.mapValues { (_, v) -> v.sortedWith(compareBy({ !it.allDay }, { it.begin })) })
                }
            }
        }
    }

    // ---- settings ----

    fun setDark(dark: Boolean) {
        _settings.update { it.copy(dark = dark) }
        viewModelScope.launch(Dispatchers.IO) { prefs.saveTheme(dark) }
    }

    fun setSample(on: Boolean) {
        _settings.update { it.copy(sample = on) }
        viewModelScope.launch(Dispatchers.IO) {
            prefs.saveSample(on)
            if (on) { if (!taskStore.hasSamples()) taskStore.seed(SampleData.seedTasks(ZonedDateTime.now())) }
            else taskStore.removeSamples()
            _tasks.value = taskStore.load()
        }
    }

    fun setNowBar(on: Boolean) {
        _settings.update { it.copy(nowBar = on) }
        viewModelScope.launch(Dispatchers.IO) { prefs.saveNowBar(on) }
    }

    fun setPlainNames(on: Boolean) {
        _settings.update { it.copy(plainNames = on) }
        viewModelScope.launch(Dispatchers.IO) { prefs.savePlainNames(on) }
    }

    fun setSecondClock(id: String) {
        _settings.update { it.copy(secondClock = id) }
        viewModelScope.launch(Dispatchers.IO) { prefs.saveSecondClock(id) }
    }

    fun setMode(m: HomeMode) { _mode.value = m }

    private var addReturn = HomeMode.BOARD
    fun openAdd() { if (_mode.value != HomeMode.ADD) addReturn = _mode.value; _mode.value = HomeMode.ADD }
    fun closeAdd() { _mode.value = addReturn }

    fun resetLearning() {
        learned.value = Learned()
        editHome { it.copy(less = emptyMap()) }
        viewModelScope.launch(Dispatchers.IO) { store.reset() }
    }

    // ---- learning ----

    private fun record(key: String, tap: Boolean) {
        val how = CardEngine.hourOfWeek(ZonedDateTime.now())
        learned.update { it.plus(key, how, tap) }
        viewModelScope.launch(Dispatchers.IO) { store.record(key, how, tap) }
    }

    fun tapped(card: ScoredCard) = record(card.learnKey, tap = true)

    // ---- arrangement ----

    private fun editHome(change: (HomePrefs) -> HomePrefs) {
        val next = change(homePrefs.value ?: return)
        homePrefs.value = next
        viewModelScope.launch(Dispatchers.IO) { prefs.saveHome(next) }
    }

    fun togglePin(id: String) = editHome { it.copy(pins = if (id in it.pins) it.pins - id else it.pins + id) }

    /** Hide a card. A default-off card is simply taken off Home again (back to Add card only). */
    fun hide(id: String, learnKey: String?) {
        learnKey?.let { record(it, tap = false) }
        val kind = CardKind.entries.firstOrNull { CardEngine.idOf(it) == id }
        editHome {
            if (kind != null && !kind.defaultOn) it.copy(added = it.added - id, pins = it.pins - id, positions = it.positions - id)
            else it.copy(hidden = it.hidden + id, pins = it.pins - id, positions = it.positions - id)
        }
    }

    /** Swipe: done with this card only. Lasts until the item stops mattering (24 h when unknown); the next one of its kind shows. No learning signal. */
    fun dismiss(c: ScoredCard) {
        val app = getApplication<Application>()
        // A birthday card stands for "Already wished": swipe marks it handled the same way.
        val wished = c.content.actions.firstOrNull { it.role == ActionRole.BIRTHDAY_DONE }?.payload
        viewModelScope.launch(Dispatchers.IO) {
            dev.phonerecall.data.Dismissals.add(app, c.instanceKey, c.relevantUntil, System.currentTimeMillis())
            wished?.let { dev.phonerecall.brain.BirthdayDay.markDone(app, it) }
        }
    }

    fun undismiss(c: ScoredCard) {
        val app = getApplication<Application>()
        val wished = c.content.actions.firstOrNull { it.role == ActionRole.BIRTHDAY_DONE }?.payload
        viewModelScope.launch(Dispatchers.IO) {
            dev.phonerecall.data.Dismissals.remove(app, c.instanceKey)
            wished?.let { dev.phonerecall.brain.BirthdayDay.unmarkDone(app, it) }
        }
    }

    fun lessLikeThis(card: ScoredCard) {
        record(card.learnKey, tap = false)
        editHome { it.copy(less = it.less + (card.learnKey to ((it.less[card.learnKey] ?: 0) + 1).coerceAtMost(3))) }
    }

    /** Restore a hidden card, or add a default-off kind from Add card. */
    fun show(id: String) {
        val kind = CardKind.entries.firstOrNull { CardEngine.idOf(it) == id }
        editHome {
            if (kind != null && !kind.defaultOn) it.copy(added = it.added + id, hidden = it.hidden - id)
            else it.copy(hidden = it.hidden - id)
        }
    }

    /** Drop on Home: the card keeps that slot. Returns the old positions for Undo. */
    fun moveCard(id: String, slot: Int): Map<String, Int> {
        val before = homePrefs.value?.positions.orEmpty()
        editHome { it.copy(positions = it.positions + (id to slot)) }
        return before
    }

    fun restorePositions(p: Map<String, Int>) = editHome { it.copy(positions = p) }
    fun resetPositions() = editHome { it.copy(positions = emptyMap()) }

    // ---- card actions ----

    fun completeTask(id: Long, card: ScoredCard? = null) {
        card?.let { record(it.learnKey, tap = true) }
        tasksIo { taskStore.complete(id, System.currentTimeMillis()) }
    }

    fun uncompleteTask(id: Long) = tasksIo { taskStore.uncomplete(id) }

    fun snoozeTask(card: ScoredCard, id: Long) {
        val until = System.currentTimeMillis() + 3 * 3_600_000L
        editHome { it.copy(snoozed = it.snoozed.filterValues { v -> v > System.currentTimeMillis() } + (id.toString() to until)) }
        record(card.learnKey, tap = true)
    }

    fun unsnoozeTask(id: Long) = editHome { it.copy(snoozed = it.snoozed - id.toString()) }

    fun toggleBought(itemId: String) = editHome { it.copy(bought = if (itemId in it.bought) it.bought - itemId else it.bought + itemId) }

    /** Returns true when the action was handled; false means "preview only" for a sample card. */
    fun action(card: ScoredCard, action: CardAction): Boolean = when (action.role) {
        ActionRole.ACK -> { record(card.learnKey, tap = true); editHome { it.copy(hidden = it.hidden + card.id) }; true }
        ActionRole.DISMISS -> { hide(card.id, card.learnKey); true }
        ActionRole.WEAR -> {
            record(card.learnKey, tap = true)
            wore(action.payload.orEmpty().split('+').filter { it.isNotBlank() })
            true
        }
        ActionRole.ANOTHER -> {
            record(card.learnKey, tap = true)
            val today = LocalDate.now().toEpochDay().toString()
            editHome { p ->
                val n = p.another.split(':').let { if (it.size == 2 && it[0] == today) it[1].toIntOrNull() ?: 0 else 0 }
                p.copy(another = "$today:${n + 1}")
            }
            true
        }
        ActionRole.ALLOW_CALENDAR, ActionRole.OPEN_CLOSET, ActionRole.OPEN_TASKS, ActionRole.OPEN_OLYMPIA, ActionRole.OPEN_WEATHER, ActionRole.OPEN_ODYSSEY, ActionRole.OPEN_HESTIA, ActionRole.OPEN_ALARMS, ActionRole.ALARM_LINK, ActionRole.ALARM_OTHER, ActionRole.MAPS, ActionRole.OPEN_ARGO, ActionRole.OPEN_REVIEW -> { record(card.learnKey, tap = true); true }
        ActionRole.ALARM_SET -> {
            record(card.learnKey, tap = true)
            // payload: "6:45", or "14:30|label|proposal title"
            val parts = action.payload.orEmpty().split('|')
            val (h, m) = parts[0].split(':').map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
            val label = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val app = getApplication<Application>()
            val title = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Alarm for ${LocalDate.now().plusDays(1).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
            viewModelScope.launch(Dispatchers.IO) {
                val core = dev.phonerecall.brain.BrainCore.get(app)
                val args = mapOf("hour" to "$h", "minute" to "$m") + (label?.let { mapOf("label" to it) } ?: emptyMap())
                core.fates.propose(listOf(dev.phonerecall.brain.RawStep(dev.phonerecall.brain.ProposalKind.SET_ALARM, args)), title, null, "Athena")
                val watch = dev.phonerecall.brain.AlarmWatch.get(app)
                if (label != null) watch.markIndiaOffered(LocalDate.now()) else watch.markOffered(LocalDate.now())
                core.changed()
            }
            true
        }
        ActionRole.BIRTHDAY_REACH -> {
            record(card.learnKey, tap = true)
            // payload: "id|name|via", via a CallRouting way or "chat"
            val p = action.payload.orEmpty().split('|')
            val app = getApplication<Application>()
            if (p.size == 3) viewModelScope.launch(Dispatchers.IO) {
                dev.phonerecall.brain.BirthdayDay.reach(app, p[0], p[1], p[2])?.let { why ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, why, android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            true
        }
        ActionRole.BIRTHDAY_DONE -> {
            record(card.learnKey, tap = true)
            val app = getApplication<Application>()
            action.payload?.let { id -> viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.brain.BirthdayDay.markDone(app, id) } }
            true
        }
        ActionRole.ALARM_ASK -> {
            record(card.learnKey, tap = true)
            val (minute, label) = action.payload.orEmpty().split('|').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to it.getOrElse(1) { "" } }
            viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.brain.AlarmWatch.get(getApplication()).answerRoutine(minute, label); dev.phonerecall.alarms.AlarmPlanner.invalidate() }
            true
        }
        ActionRole.ALARM_DONE -> {
            record(card.learnKey, tap = true)
            viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.brain.AlarmWatch.get(getApplication()).setMoment(null) }
            true
        }
        ActionRole.ALARM_SPORT -> {
            record(card.learnKey, tap = true)
            // payload: "matchId|wake|h:m|title" or "matchId|catchup" or "matchId|skip"
            val p = action.payload.orEmpty().split('|')
            val app = getApplication<Application>()
            viewModelScope.launch(Dispatchers.IO) {
                val watch = dev.phonerecall.brain.AlarmWatch.get(app)
                watch.answerSport(p[0], p.getOrElse(1) { "skip" })
                dev.phonerecall.alarms.AlarmPlanner.invalidate()
                if (p.getOrNull(1) == "wake") {
                    val (h, m) = p.getOrElse(2) { "0:0" }.split(':').map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
                    val core = dev.phonerecall.brain.BrainCore.get(app)
                    val title = p.getOrElse(3) { "the game" }
                    core.fates.propose(
                        listOf(dev.phonerecall.brain.RawStep(dev.phonerecall.brain.ProposalKind.SET_ALARM, mapOf("hour" to "$h", "minute" to "$m", "label" to title.take(40)))),
                        "Alarm for $title", null, "Athena",
                    )
                    core.changed()
                }
            }
            true
        }
        ActionRole.ALARM_MUSIC -> {
            record(card.learnKey, tap = true)
            val query = action.payload ?: "Telugu devotional"
            viewModelScope.launch(Dispatchers.IO) {
                val core = dev.phonerecall.brain.BrainCore.get(getApplication())
                core.fates.propose(
                    listOf(dev.phonerecall.brain.RawStep(dev.phonerecall.brain.ProposalKind.PLAY_MUSIC, mapOf("query" to query, "name" to "$query mix", "reason" to "Festival morning"))),
                    "$query mix", null, proposer = "Music",
                )
                core.changed()
            }
            true
        }
        ActionRole.ARGO -> {
            record(card.learnKey, tap = true)
            val p = action.payload.orEmpty()
            val app = getApplication<Application>()
            if (p.isNotEmpty() && p != "vault" && !p.startsWith("maps:")) viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.argo.Argo.act(app, p) }
            true
        }
        ActionRole.ODYSSEY_YES, ActionRole.ODYSSEY_NO -> {
            record(card.learnKey, tap = action.role == ActionRole.ODYSSEY_YES)
            val id = action.payload
            val app = getApplication<Application>()
            if (id != null) viewModelScope.launch(Dispatchers.IO) { if (action.role == ActionRole.ODYSSEY_YES) dev.phonerecall.odyssey.Odyssey.accept(app, id) else dev.phonerecall.odyssey.Odyssey.decline(app, id) }
            true
        }
        ActionRole.ROAD_DONE -> {
            record(card.learnKey, tap = true)
            val app = getApplication<Application>()
            action.payload?.let { key -> viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.odyssey.Odyssey.noteDone(app, key) } }
            true
        }
        ActionRole.WFH_YES, ActionRole.WFH_NO -> {
            record(card.learnKey, tap = action.role == ActionRole.WFH_YES)
            val app = getApplication<Application>()
            val yes = action.role == ActionRole.WFH_YES
            viewModelScope.launch(Dispatchers.IO) {
                // Yes: today is a remote day for Home and read_availability, and the mind's next batch tells the agents.
                val today = LocalDate.now()
                if (yes) dev.phonerecall.mind.MindAvailability.remote(app, brain.core, today)
                dev.phonerecall.odyssey.Odyssey.noteDone(app, dev.phonerecall.odyssey.OfficeDay.key(today))
            }
            true
        }
        ActionRole.FUEL_SKIP -> {
            record(card.learnKey, tap = false)
            val app = getApplication<Application>()
            action.payload?.let { id -> viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.odyssey.Garage.skip(app, id) } }
            true
        }
        // The odometer dialog and the Car tab open from the screen; here only the tap is learned.
        ActionRole.FUEL_ODOMETER, ActionRole.CAR_SETUP -> { record(card.learnKey, tap = true); true }
        ActionRole.MOMENT_GO, ActionRole.MOMENT_SNAP, ActionRole.MOMENT_LATER, ActionRole.MOMENT_NEVER -> {
            record(card.learnKey, tap = action.role == ActionRole.MOMENT_GO || action.role == ActionRole.MOMENT_SNAP)
            val app = getApplication<Application>()
            viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.moments.MomentsCore.act(app, action.role, action.payload) }
            true
        }
        ActionRole.OPEN -> { record(card.learnKey, tap = true); false }
    }

    fun answerAlarm(minute: Int, label: String) {
        viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.brain.AlarmWatch.get(getApplication()).answerRoutine(minute, label); dev.phonerecall.alarms.AlarmPlanner.invalidate() }
    }

    /** The fill-up card's odometer, typed or read from a dashboard photo. */
    fun confirmFillUp(id: String, odometer: Int, fromPhoto: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { dev.phonerecall.odyssey.Garage.confirm(app, id, odometer, if (fromPhoto) "dash" else "typed") }
    }

    // ---- tasks ----

    private fun tasksIo(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block(); _tasks.value = taskStore.load() }
    }

    fun addTask(title: String) { if (title.isNotBlank()) tasksIo { taskStore.add(title) } }
    fun updateTask(t: Task) = tasksIo { taskStore.update(t) }
    fun deleteTask(id: Long) = tasksIo { taskStore.delete(id) }
    fun reorderTasks(ids: List<Long>) = tasksIo { taskStore.setOrder(ids) }
    fun addProject(name: String, then: (Long) -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = taskStore.addProject(name)
            _tasks.value = taskStore.load()
            launch(Dispatchers.Main) { then(id) }
        }
    }

    /** Restores a deleted task (Undo). */
    fun restoreTask(t: Task) = tasksIo { reAdd(t) }

    private fun reAdd(t: Task) {
        val id = taskStore.add(
            t.title, t.list, sample = t.sample, due = t.due, dueHasTime = t.dueHasTime, quick = t.quick, projectId = t.projectId,
            context = t.context, waitingOn = t.waitingOn, followUp = t.followUp,
        )
        if (t.list == dev.phonerecall.data.TaskList.DONE) taskStore.complete(id, t.doneAt ?: System.currentTimeMillis())
    }

    // ---- GTD (Praxis) ----

    private val _reviewAsk = MutableStateFlow(0)
    /** Bumped when Olympus's review card asks for the walk-through; Praxis opens on it. */
    val reviewAsk: StateFlow<Int> = _reviewAsk.asStateFlow()
    fun openReview() { _reviewAsk.value++ }
    fun reviewSeen() { _reviewAsk.value = 0 }
    fun reviewDone() = tasksIo { taskStore.markReviewed(System.currentTimeMillis()) }

    /** Where Praxis's Next view thinks he is: today's day type, Odyssey's stay and drive mode. */
    val situation: StateFlow<dev.phonerecall.tasks.Situation> = combine(_availability, dev.phonerecall.odyssey.OdysseyStore.state, clock) { av, d, now ->
        val stay = d.stay
        fun near(p: dev.phonerecall.odyssey.KnownPlace?): Boolean? =
            if (p == null || stay == null || d.driving || !dev.phonerecall.moments.Signals.on(d)) null
            else dev.phonerecall.odyssey.Geo.meters(p.lat, p.lng, stay.lat, stay.lng) < 300
        val driving = d.driving || runCatching { dev.phonerecall.music.MusicCore.get(getApplication()).driving }.getOrDefault(false)
        dev.phonerecall.tasks.Situation(
            officeDay = av[now.toLocalDate()]?.type == dev.phonerecall.data.DayType.WORKDAY,
            atOffice = near(d.work()), atHome = near(d.home()), driving = driving, hour = now.hour,
        )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), dev.phonerecall.tasks.Situation())

    /**
     * One Inbox item, clarified. Every move hands [then] an Undo for the toast (on the main thread).
     * Trash deletes, Reference files the words in his Reference list, Do it now marks it done, and
     * Project makes a project named after the item with its first next action.
     */
    fun clarify(t: Task, m: dev.phonerecall.tasks.ClarifyMove, then: (dev.phonerecall.ui.Undo) -> Unit) {
        val c = m.choice; val context = m.context; val who = m.who; val date = m.date; val project = m.project; val firstAction = m.firstAction
        val zone = java.time.ZoneId.systemDefault()
        val day = date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        fun move(to: Task, message: String) { updateTask(to); then(dev.phonerecall.ui.Undo(message) { updateTask(t) }) }
        when (c) {
            dev.phonerecall.tasks.Clarify.TRASH -> { deleteTask(t.id); then(dev.phonerecall.ui.Undo("Deleted.") { restoreTask(t) }) }
            dev.phonerecall.tasks.Clarify.REFERENCE -> {
                val unfile = brain.fileReference(t.title)
                deleteTask(t.id)
                then(dev.phonerecall.ui.Undo("Filed in Reference.") { unfile(); restoreTask(t) })
            }
            dev.phonerecall.tasks.Clarify.DO_NOW -> { completeTask(t.id); then(dev.phonerecall.ui.Undo("Done.") { uncompleteTask(t.id) }) }
            dev.phonerecall.tasks.Clarify.SOMEDAY -> move(t.copy(list = TaskList.SOMEDAY), "Moved to Someday.")
            dev.phonerecall.tasks.Clarify.WAITING -> move(
                t.copy(list = TaskList.WAITING, waitingOn = who?.trim()?.ifEmpty { null }, followUp = day),
                who?.trim()?.takeIf { it.isNotEmpty() }?.let { "Waiting for $it." } ?: "Moved to Waiting for.",
            )
            dev.phonerecall.tasks.Clarify.SCHEDULED -> move(
                t.copy(list = TaskList.SCHEDULED, due = day ?: t.due, dueHasTime = if (day != null) false else t.dueHasTime, context = context ?: t.context),
                "Scheduled.",
            )
            dev.phonerecall.tasks.Clarify.NEXT -> move(t.copy(list = TaskList.NEXT, context = context), "Moved to Next.")
            dev.phonerecall.tasks.Clarify.PROJECT -> viewModelScope.launch(Dispatchers.IO) {
                val pid = taskStore.addProject(project?.trim()?.ifEmpty { null } ?: t.title)
                val first = firstAction?.trim().orEmpty()
                // The item becomes the project. With a first next action it leaves the Inbox; without one it is that action.
                val added = if (first.isNotEmpty()) taskStore.add(first, TaskList.NEXT, projectId = pid, context = context ?: dev.phonerecall.tasks.Gtd.contextOf(first)) else null
                if (added != null) taskStore.delete(t.id) else taskStore.update(t.copy(list = TaskList.NEXT, projectId = pid, context = context))
                _tasks.value = taskStore.load()
                val undo = dev.phonerecall.ui.Undo("Project made.") {
                    tasksIo {
                        if (added != null) { taskStore.delete(added); reAdd(t) } else taskStore.update(t)
                        taskStore.deleteProjectIfEmpty(pid)
                    }
                }
                launch(Dispatchers.Main) { then(undo) }
            }
        }
    }

    /** A stuck project's next action, typed on its row. */
    fun addNextAction(projectId: Long, title: String) {
        if (title.isBlank()) return
        tasksIo { taskStore.add(title, TaskList.NEXT, projectId = projectId, context = dev.phonerecall.tasks.Gtd.contextOf(title)) }
    }
}
