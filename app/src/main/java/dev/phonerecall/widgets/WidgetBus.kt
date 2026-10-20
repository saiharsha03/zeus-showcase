package dev.phonerecall.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.google.gson.Gson
import dev.phonerecall.data.Prefs
import dev.phonerecall.home.Board
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardEngine
import dev.phonerecall.lemnos.LemnosHub
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.music.MusicCore
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.nowbar.NowBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * At most one run per [gapMs]. A request inside the gap is held and runs once at the gap's end (with the
 * newest state, read then); further requests while one is held are dropped. Pure, so it is tested.
 */
class Throttle(private val gapMs: Long) {
    private var last = Long.MIN_VALUE / 2
    private var held = false

    /** 0: run now. A positive number: schedule one run after that many ms. null: one is already held. */
    @Synchronized fun request(now: Long): Long? {
        if (held) return null
        val wait = last + gapMs - now
        if (wait <= 0) { last = now; return 0 }
        held = true
        return wait
    }

    /** The held run is running now. */
    @Synchronized fun fired(now: Long) { held = false; last = now }
}

/**
 * The one way widgets learn anything. Zeus calls it when state changes (the board re-scores, Lemnos
 * syncs, the track changes) and after a restart or an update; nothing here polls.
 *
 * Calendar, tasks and closet are never copied: those widgets are rebuilt from the phone's own stores
 * ([refreshFromDisk], the same reads as the Now Bar). What only Lemnos or the player knows is kept as the
 * finished widget text (already through [WidgetSafety]) in a private, never-backed-up preferences file.
 */
object WidgetBus {
    private val providers = mapOf(
        WKind.TODAY to TodayWidget::class.java, WKind.CHRONOS to ChronosWidget::class.java, WKind.AGORA to AgoraWidget::class.java,
        WKind.DELPHI to DelphiWidget::class.java, WKind.VENUS to VenusWidget::class.java, WKind.MUSIC to MusicWidget::class.java,
        WKind.LEMNOS to LemnosWidget::class.java, WKind.CERBERUS to CerberusWidget::class.java, WKind.PANTHEON to PantheonWidget::class.java,
        WKind.METRON to MetronWidget::class.java, WKind.HERMES to HermesWidget::class.java, WKind.TRIPS to TripsWidget::class.java,
        WKind.DRIVE to DriveWidget::class.java, WKind.MONEY to MoneyWidget::class.java, WKind.HEARTH to HearthWidget::class.java,
        WKind.HEALTH to HealthWidget::class.java, WKind.BRIEF to BriefWidget::class.java, WKind.ROOM to RoomWidget::class.java,
    )
    private const val AGENT_TASKS = "agent.tasks"

    /** How many of each widget are on his home screens (Horologion). */
    fun placed(c: Context): List<Pair<WKind, Int>> = providers.map { (k, cls) ->
        k to runCatching { AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, cls)).size }.getOrDefault(0)
    }

    private val gson = Gson()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zeus-widgets").apply { isDaemon = true } }
    private val localGate = Throttle(15_000L)
    private val lemnosGate = Throttle(5_000L)
    private val musicGate = Throttle(3_000L)
    private val diskGate = Throttle(30_000L)
    private val hestiaGate = Throttle(3_000L)
    private val sectionGates = WKind.entries.filter { it.section }.associateWith { Throttle(5_000L) }
    private val drawn = HashMap<WKind, Pair<WView, Boolean>>()
    /** The section widgets as last built, so a "Plain names" change redraws them without reading their stores. */
    private val sections = HashMap<WKind, WView>()

    @Volatile private var local: LocalSnapshot? = null
    @Volatile private var lemnos: LemnosState? = null
    @Volatile private var playing: NowPlaying? = null
    @Volatile private var watching = false
    @Volatile private var healthWatched = false

    /** What the local widgets are built from: the board and its inputs, as Home computed them. */
    class LocalSnapshot(val inputs: BoardInputs, val board: Board, val plain: Boolean)

    // ---- what Zeus calls ----

    /** Home re-scored (at most once a minute). Cheap when nothing a widget shows changed. */
    fun local(c: Context, inputs: BoardInputs, board: Board, plain: Boolean) {
        local = LocalSnapshot(inputs, board, plain)
        gate(c.applicationContext, localGate) { drawLocal(it) }
    }

    /** Lemnos's state changed (a sync, an event, an ack). */
    fun lemnos(c: Context, s: LemnosState) {
        lemnos = s
        gate(c.applicationContext, lemnosGate) { drawLemnos(it) }
    }

    /** The track changed. A button tap passes force, so the widget answers at once. */
    fun music(c: Context, n: NowPlaying?, force: Boolean = false) {
        val app = c.applicationContext
        playing = n
        val run: (Context) -> Unit = { WidgetContent.music(playing).let { v -> store(it, v); draw(it, v) } }
        if (force) worker.execute { run(app) } else gate(app, musicGate, run)
    }

    /** Metron read Lemnos or checked the cap: the plan windows and the cap, as finished widget text. */
    fun metron(c: Context) {
        val app = c.applicationContext
        if (ids(app, WKind.METRON).isEmpty()) return
        worker.execute {
            runCatching {
                val m = dev.phonerecall.metron.MetronCore.get(app)
                val v = WidgetContent.metron(m.usage.value, runCatching { m.budget() }.getOrNull(), ZonedDateTime.now())
                store(app, v); draw(app, v)
            }
        }
    }

    /**
     * A section's own store changed (Moneta reloaded, Odyssey saved, the brief was filed, Iaso read Health
     * Connect): rebuild that one widget, at most every 5 s. A no-op with none placed.
     */
    fun section(c: Context, k: WKind) {
        val app = c.applicationContext
        val gate = sectionGates[k] ?: return
        if (ids(app, k).isEmpty()) return
        gate(app, gate) { drawSection(it, k) }
    }

    /** Hestia's devices changed (Google Home's subscription, or his tap). Kept, so the widget survives the app closing. */
    fun hestia(c: Context, s: dev.phonerecall.hestia.HomeSnapshot, force: Boolean = false) {
        // Nothing known yet: the widget keeps what it last showed.
        if (s.link == dev.phonerecall.hestia.Link.Starting) return
        val app = c.applicationContext
        if (ids(app, WKind.HEARTH).isEmpty()) return
        val run: (Context) -> Unit = { WidgetContent.hearth(s, dev.phonerecall.hestia.HestiaPicks.load(it)).let { v -> store(it, v); draw(it, v) } }
        if (force) worker.execute { runCatching { run(app) } } else gate(app, hestiaGate, run)
    }

    /**
     * His tap on a Hestia tile: the same [dev.phonerecall.hestia.HestiaCore.tap] as the screen (which refuses
     * forbidden kinds), then the widget redraws from the new state. Blocking; from WidgetActions' thread.
     */
    fun hearthTap(c: Context, id: String, on: Boolean) {
        val app = c.applicationContext
        val core = dev.phonerecall.hestia.HestiaCore.get(app)
        runCatching { core.backend.start() }
        // A cold process: give Google Home a few seconds to report the device.
        val ready = System.currentTimeMillis() + 5_000L
        while (core.snapshot.device(id) == null && System.currentTimeMillis() < ready) Thread.sleep(200)
        runCatching { kotlinx.coroutines.runBlocking { core.tap(id, dev.phonerecall.hestia.HomeCommand.Power(on)) } }
        val settled = System.currentTimeMillis() + 2_000L
        while (core.snapshot.device(id)?.on != on && System.currentTimeMillis() < settled) Thread.sleep(150)
        hestia(app, core.snapshot, force = true)
    }

    /** Tasks changed outside Home (the senses, hygiene, a widget tick): rebuild from disk, at most every 30 s. */
    fun tasksChanged(c: Context) {
        if (providers.keys.none { !it.remote && ids(c.applicationContext, it).isNotEmpty() }) return
        gate(c.applicationContext, diskGate) { refreshFromDisk(it, force = true) }
    }

    /** Once per process, from the app: follow Lemnos and the player for as long as the process lives. */
    fun watch(c: Context) {
        if (watching) return
        watching = true
        val app = c.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { LemnosHub.get(app).state.collect { lemnos(app, it) } }
        scope.launch { MusicCore.get(app).now.collect { music(app, it) } }
        scope.launch { dev.phonerecall.hestia.HestiaCore.get(app).state.collect { hestia(app, it) } }
        watchHealth(app)
    }

    /** Iaso's widget follows the hub's readings, once one is placed. Nothing of Iaso's is kept by the widgets. */
    private fun watchHealth(app: Context) {
        if (healthWatched || ids(app, WKind.HEALTH).isEmpty()) return
        healthWatched = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { dev.phonerecall.iaso.Iaso.get(app).state.collect { section(app, WKind.HEALTH) } }
    }

    /**
     * After a restart, an update, a Now Bar edge or a tick on the widget: rebuild the local widgets from
     * the phone's stores and redraw the kept ones. Blocking; call off the main thread.
     */
    fun refreshFromDisk(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        if (providers.keys.none { ids(app, it).isNotEmpty() }) return
        if (force) synchronized(drawn) { drawn.keys.removeAll { !it.remote } }
        for (k in WKind.entries.filter { it.section }) if (ids(app, k).isNotEmpty()) drawSection(app, k)
        val (inputs, board) = runCatching { NowBar.boardFromDisk(app) }.getOrNull() ?: return
        local = LocalSnapshot(inputs, board, Prefs(app).load().plainNames)
        drawLocal(app)
    }

    /** A provider asked (added, resized past a bucket, the launcher restarted). */
    fun redraw(c: Context, kind: WKind) {
        synchronized(drawn) { drawn.remove(kind) }
        if (kind.remote) { draw(c, stored(c, kind) ?: placeholder(kind)); return }
        if (kind.section) {
            if (kind == WKind.HEALTH) watchHealth(c.applicationContext)
            draw(c, build(c, kind) ?: synchronized(sections) { sections[kind] } ?: placeholder(kind))
            return
        }
        if (local == null) refreshFromDisk(c) else drawLocal(c)
    }

    // ---- building ----

    private fun placeholder(k: WKind) = when (k) {
        WKind.MUSIC -> WidgetContent.music(null)
        WKind.HEALTH -> WView(k, "Open Iaso to refresh", "Steps, weight and sleep show after a read")
        else -> WView(k, "Open Zeus to refresh")
    }

    /** A section widget from its own store. Iaso's only from a hub that has read Health Connect, so a cold start never blanks it. */
    private fun build(c: Context, k: WKind): WView? = runCatching {
        val app = c.applicationContext
        val now = ZonedDateTime.now()
        val ms = now.toInstant().toEpochMilli()
        when (k) {
            WKind.TRIPS -> dev.phonerecall.argo.ArgoCards.widget(dev.phonerecall.argo.ArgoStore.load(app), ms, now.zone)
            WKind.DRIVE -> WidgetContent.drive(dev.phonerecall.odyssey.OdysseyStore.load(app), now)
            WKind.MONEY -> {
                val store = dev.phonerecall.moneta.MonetaCore.get(app).store
                val txns = store.txns(ms - 400 * 86_400_000L)
                val subs = dev.phonerecall.moneta.Money.subscriptions(dev.phonerecall.moneta.Money.recurring(txns, ms, now.zone), store.irisSubs(), store.manualSubs())
                WidgetContent.money(txns, subs, store.dismissed(), now)
            }
            WKind.BRIEF -> WidgetContent.brief(dev.phonerecall.brief.BriefStore.load(app), now)
            WKind.HEALTH -> dev.phonerecall.iaso.Iaso.peek()?.state?.value?.takeIf { it.report.readAt > 0 }?.let { WidgetContent.health(it, now) }
            else -> null
        }
    }.getOrNull()

    private fun drawSection(c: Context, k: WKind) {
        val v = build(c, k) ?: return
        synchronized(sections) { sections[k] = v }
        draw(c, v)
    }

    /** The local widgets from one snapshot. Pure apart from reading the kept agent tasks. */
    fun localViews(s: LocalSnapshot, agent: List<WRow>): List<WView> {
        val i = s.inputs
        val events = i.calendar.events
        return listOf(
            // Travel day and the trip itself: Argo's trip widget takes Today's place.
            i.trip?.widget ?: WidgetContent.today(i.now, s.board.cards, events, i.availability, i.tasks, i.prefs.snoozed, i.weather, i.birthdays),
            WidgetContent.chronos(i.now, events, i.availability),
            WidgetContent.agora(i.now, i.tasks, agent, i.prefs.snoozed),
            WidgetContent.delphi(),
            WidgetContent.hermes(),
            WidgetContent.venus(CardEngine.wardrobe(i, morningOnly = false), i.closet.imported),
        )
    }

    private fun drawLocal(c: Context) {
        val s = local ?: return
        val agent = storedRows(c)
        for (v in localViews(s, agent)) draw(c, v, s.plain)
        // The kept ones too, so a "Plain names" change reaches every widget. Unchanged ones are skipped.
        for (k in WKind.entries.filter { it.remote }) stored(c, k)?.let { draw(c, it, s.plain) }
        for (v in synchronized(sections) { sections.values.toList() }) draw(c, v, s.plain)
    }

    private fun drawLemnos(c: Context) {
        val s = lemnos ?: return
        val now = ZonedDateTime.now()
        val agent = if (s.pairing == dev.phonerecall.lemnos.DeviceKeys.State.PAIRED) WidgetContent.agentTasks(s.tasks, now) else emptyList()
        val before = storedRows(c)
        // Lemnos sends tasks only after a Pantheon visit; an empty list from a fresh process keeps the last ones.
        if (s.tasks.isNotEmpty() || s.pairing != dev.phonerecall.lemnos.DeviceKeys.State.PAIRED) prefs(c).edit().putString(AGENT_TASKS, gson.toJson(agent)).apply()
        for (v in listOf(WidgetContent.lemnos(s, now), WidgetContent.cerberus(s, now), WidgetContent.pantheon(s, now), WidgetContent.room(s, now))) { store(c, v); draw(c, v) }
        if (storedRows(c) != before) drawLocal(c)
    }

    // ---- keeping and drawing ----

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    private fun store(c: Context, v: WView) = prefs(c).edit().putString(v.kind.name, gson.toJson(WidgetSafety.seal(v))).apply()

    private fun stored(c: Context, k: WKind): WView? =
        prefs(c).getString(k.name, null)?.let { runCatching { gson.fromJson(it, WView::class.java) }.getOrNull() }?.takeIf { it.kind == k && it.groups != null }

    private fun storedRows(c: Context): List<WRow> =
        prefs(c).getString(AGENT_TASKS, null)?.let { runCatching { gson.fromJson(it, Array<WRow>::class.java).toList() }.getOrNull() }.orEmpty()

    private fun ids(c: Context, k: WKind): IntArray =
        runCatching { AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, providers.getValue(k))) }.getOrDefault(IntArray(0))

    /** Seals the view, skips it when it is what the widget already shows, and pushes it. */
    private fun draw(c: Context, raw: WView, plain: Boolean = Prefs(c).load().plainNames) {
        val ids = ids(c, raw.kind)
        if (ids.isEmpty()) return
        val v = WidgetSafety.seal(raw)
        synchronized(drawn) { if (drawn[v.kind] == v to plain) return; drawn[v.kind] = v to plain }
        runCatching { AppWidgetManager.getInstance(c).updateAppWidget(ids, WidgetViews.responsive(c, v, plain)) }
            .onFailure { synchronized(drawn) { drawn.remove(v.kind) } }
    }

    private fun gate(app: Context, t: Throttle, run: (Context) -> Unit) {
        when (val wait = t.request(System.currentTimeMillis())) {
            null -> Unit
            0L -> worker.execute { runCatching { run(app) } }
            else -> worker.schedule({ t.fired(System.currentTimeMillis()); runCatching { run(app) } }, wait, TimeUnit.MILLISECONDS)
        }
    }
}
