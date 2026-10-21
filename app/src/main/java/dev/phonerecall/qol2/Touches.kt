package dev.phonerecall.qol2

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Incoming
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Sealer
import dev.phonerecall.brain.Urgency
import dev.phonerecall.clio.Clio
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.moments.Aliases
import dev.phonerecall.moments.ListEntry
import dev.phonerecall.moments.Signals
import dev.phonerecall.moments.Staples
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.MonetaCore
import dev.phonerecall.moneta.MonetaHooks
import dev.phonerecall.moneta.MonetaOp
import dev.phonerecall.moneta.TxnKind
import dev.phonerecall.odyssey.Geo
import dev.phonerecall.odyssey.Kind
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.OdysseyStore
import dev.phonerecall.odyssey.Visit
import dev.phonerecall.ui.Undo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * Small touches on the phone: what he keeps (sealed, no-backup), the cards for Home, the taps, and the
 * hooks that fire when the app is closed (a visit ends, a timer runs out, audio drops, a notice arrives).
 * Taps only do reversible things (with Undo) or open another app; a plug goes through Fates.
 */
object Touches {
    const val AREA = "touches"
    const val PROPOSER = "Touches"
    private const val FILE = "touches.sealed"
    private const val CHANNEL = "touches"
    private const val CHANNEL_TIMER = "touch_timers"
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private val _prefs = MutableStateFlow(TouchPrefs())
    val prefs: StateFlow<TouchPrefs> = _prefs.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    private val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.touches") }

    /** The cards on Home right now, so a tap runs exactly what it showed. */
    @Volatile private var shown: Map<String, TouchCard> = emptyMap()
    @Volatile private var logged: Set<String> = emptySet()
    /** The last audio drop while music played (this process only). */
    @Volatile var audioDropAt: Long? = null

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): TouchPrefs = synchronized(lock) {
        if (loaded) return _prefs.value
        val f = file(c)
        _prefs.value = if (f.exists()) runCatching {
            sealer.openBytes(f.readBytes())?.let { dev.phonerecall.mind.stateJson.readValue(it, TouchPrefs::class.java) }
        }.getOrNull() ?: TouchPrefs() else TouchPrefs()
        loaded = true
        _prefs.value
    }

    fun update(c: Context, change: (TouchPrefs) -> TouchPrefs): TouchPrefs = synchronized(lock) {
        val next = change(load(c))
        _prefs.value = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(sealer.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(next)))
        tmp.renameTo(f)
        next
    }

    /** Themis: one switch per touch. */
    fun setOn(c: Context, t: Touch, on: Boolean) {
        update(c) { if (on) it.copy(off = it.off - t) else it.copy(off = it.off + t) }
        BrainCore.get(c).db.log("The owner", if (on) "touch on" else "touch off", t.label)
        Clio.info(AREA, if (on) "on" else "off", t.label)
    }

    // ---- Home ----

    /** Up to three cards for Home, from the phone's own stores. Never throws. Call off the main thread. */
    fun cards(c: Context, now: ZonedDateTime, events: List<CalItem>, officeDay: Boolean): List<TouchCard> = runCatching {
        val app = c.applicationContext
        val p = load(app)
        val ctx = context(app, now, events, officeDay, p)
        val focus = TouchRules.focus(ctx, p)
        if (focus != p.focus) setFocus(app, p.focus, focus)
        val top = TouchRules.cards(ctx, load(app)).take(3)
        shown = top.associateBy { "${it.touch.name}|${it.key}" }
        val fresh = top.map { "${it.touch.name}|${it.key}" }.filter { it !in logged }
        if (fresh.isNotEmpty()) { logged = (logged + fresh).toList().takeLast(200).toSet(); top.filter { "${it.touch.name}|${it.key}" in fresh }.forEach { Clio.info(AREA, "card", it.touch.label) } }
        if (top.any { it.touch == Touch.CAR_WASH } && now.toInstant().toEpochMilli() - (p.shownAt[Touch.CAR_WASH] ?: 0L) >= 7 * DAY) {
            update(app) { it.copy(shownAt = it.shownAt + (Touch.CAR_WASH to now.toInstant().toEpochMilli())) }
        }
        top
    }.onFailure { Clio.error(AREA, it) }.getOrDefault(emptyList())

    /** Match mode and office focus: Zeus's own alerts only. The phone's Do not disturb only for a match, and only if he opted in. */
    private fun setFocus(c: Context, before: Focus?, after: Focus?) {
        update(c) { it.copy(focus = after) }
        if (after != null) Clio.info(AREA, "focus on", after.why) else Clio.info(AREA, "focus off", before?.why.orEmpty())
        val p = load(c)
        if (!p.matchDnd) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        if (after?.why == "match" && before?.why != "match") runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
        if (before?.why == "match" && after?.why != "match") runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
    }

    /** Attention asks before posting: during a focus, nice-to-have alerts wait for the brief. */
    fun holds(urgency: Urgency, now: Long = System.currentTimeMillis()): Boolean {
        val f = _prefs.value.focus ?: return false
        return urgency == Urgency.NICE && f.until > now
    }

    // ---- the signals ----

    @Volatile private var gamingAt = 0L
    @Volatile private var gaming = false
    @Volatile private var staplesAt = 0L
    @Volatile private var staplesCache: List<StoreStaple> = emptyList()
    @Volatile private var forecastRaw: String? = null
    @Volatile private var forecastHours: List<Hour> = emptyList()

    fun onCall(c: Context): Boolean = runCatching {
        c.getSystemService(AudioManager::class.java).mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
    }.getOrDefault(false)

    private fun gaming(c: Context, now: Long): Boolean {
        if (now - gamingAt < MIN) return gaming
        gamingAt = now
        gaming = runCatching { dev.phonerecall.alarms.ForegroundApp.usage(c).current() in dev.phonerecall.alarms.ForegroundApp.GAMES }.getOrDefault(false)
        return gaming
    }

    private fun near(d: OdysseyData, kind: Kind): Boolean? {
        if (!Signals.on(d) || d.driving) return null
        val p = d.places.firstOrNull { it.kind == kind } ?: return null
        val s = d.stay ?: return null
        return Geo.meters(p.lat, p.lng, s.lat, s.lng) < if (kind == Kind.HOME) 200 else 300
    }

    private fun context(c: Context, now: ZonedDateTime, events: List<CalItem>, officeDay: Boolean, p: TouchPrefs): TouchContext {
        val ms = now.toInstant().toEpochMilli()
        val d = OdysseyStore.state.value
        val music = runCatching { dev.phonerecall.music.MusicCore.get(c) }.getOrNull()
        val driving = d.driving || music?.driving == true
        val (battery, charging) = battery(c)
        val stay = d.stay?.takeIf { Signals.on(d) && !d.driving }
        val stayName = stay?.let { it.poiName ?: it.poi ?: it.street }
        val core = runCatching { BrainCore.get(c) }.getOrNull()
        return TouchContext(
            now = now,
            quiet = onCall(c) || driving || gaming(c, ms),
            driving = driving,
            atHome = near(d, Kind.HOME), atOffice = near(d, Kind.WORK), officeDay = officeDay,
            staySince = stay?.start, stayName = stayName, garage = TouchParse.garage(stay?.poiName) || TouchParse.garage(stay?.poi),
            departure = Signals.departure(d, now),
            battery = battery, charging = charging,
            hours = hours(c),
            events = events.filter { it.end > ms - 12 * HOUR && it.begin < ms + 2 * DAY }.map {
                DayEvent(it.key, it.title, it.begin, it.end, it.allDay, TouchParse.ticketLinks(listOfNotNull(it.notes, it.place).joinToString("\n")))
            },
            charges = charges(c, ms),
            visits = if (core != null) storeVisits(core, d, ms) else emptyList(),
            staples = if (core != null) staples(c, core, ms) else emptyList(),
            legs = legs(),
            ended = ended(events, ms, now),
            matches = runCatching {
                dev.phonerecall.olympia.OlympiaStore.state.value.followed.map {
                    Followed(it.id, it.title, it.start, it.phase == dev.phonerecall.olympia.Phase.LIVE, it.phase == dev.phonerecall.olympia.Phase.DONE)
                }
            }.getOrDefault(emptyList()),
            nextAlarm = runCatching { dev.phonerecall.brain.AlarmWatch.get(c).next()?.at }.getOrNull()?.let { Instant.ofEpochMilli(it).atZone(now.zone) },
            plugs = runCatching {
                dev.phonerecall.hestia.HestiaCore.get(c).state.value.devices.filter { it.kind == dev.phonerecall.hestia.DeviceKind.PLUG }.map { Plug(it.id, it.name, it.on == true) }
            }.getOrDefault(emptyList()),
            musicNow = music?.now?.value?.playing == true,
            playlist = music?.state?.value?.drivePlaylist?.name ?: dev.phonerecall.music.MusicCore.DEFAULT_PLAYLIST,
            innerScreen = c.resources.configuration.smallestScreenWidthDp >= 600,
            shortNight = runCatching { dev.phonerecall.iaso.IasoGlance.shortNight(now.toLocalDate()) }.getOrNull() == true,
            stepsSyncedAt = if (now.hour in 10..21) runCatching { dev.phonerecall.iaso.IasoGlance.stepsSyncedAt(c, ms) }.getOrNull() else null,
            lunchAt = lunchAt(d, now),
            carWash = d.places.firstOrNull { it.kind == Kind.CAR_WASH }?.name,
            renewals = renewals(c, now),
            audioDropAt = audioDropAt,
        )
    }

    private fun battery(c: Context): Pair<Int?, Boolean> = runCatching {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        (if (level >= 0 && scale > 0) level * 100 / scale else null) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(null to false)

    private fun hours(c: Context): List<Hour> {
        val raw = dev.phonerecall.aeolia.WeatherStore.prefs(c).getString("forecast", null) ?: return emptyList()
        if (raw == forecastRaw) return forecastHours
        forecastHours = dev.phonerecall.aeolia.WeatherParse.forecast(raw)?.hours?.map { Hour(it.at, it.tempC, it.code, it.precipMm) }.orEmpty()
        forecastRaw = raw
        return forecastHours
    }

    private fun charges(c: Context, now: Long): List<Charge> = runCatching {
        MonetaCore.get(c).state.value.txns.filter { it.counted && it.kind == TxnKind.SPEND && !it.pending && now - it.at in 0..40 * DAY }.map {
            Charge(it.ref, it.merchant, it.category.name, it.at, it.cents, if (it.category == Category.OTHER) runCatching { MonetaHooks.visits?.placeAt(it.at) }.getOrNull() else null)
        }
    }.getOrDefault(emptyList())

    /** Visits that ended in the last three hours at a place with a list (its own name, or its kind's: Groceries, Shopping). */
    private fun storeVisits(core: BrainCore, d: OdysseyData, now: Long): List<StoreVisit> = d.visits.filter { now - it.end in 0..3 * HOUR }.mapNotNull { v ->
        val place = d.place(v.placeId) ?: return@mapNotNull null
        val section = (core.sections.byName(place.name) ?: place.kind.list?.let { core.sections.byName(it) })?.takeIf { !it.hidden } ?: return@mapNotNull null
        StoreVisit(place.name, section.name, v.start, v.end, core.store.items(section.id).count { !it.done && !it.lethe })
    }

    /** Store lists from Places (Costco, Wegmans...), Groceries left to Kairos. Read at most every 15 minutes. */
    private fun staples(c: Context, core: BrainCore, now: Long): List<StoreStaple> {
        if (now - staplesAt < 15 * MIN) return staplesCache
        staplesAt = now
        staplesCache = runCatching {
            dev.phonerecall.places.PlaceStore(c).all().flatMap { it.sections }.distinct().mapNotNull { core.sections.get(it) }
                .filter { !it.hidden && !it.name.equals("Groceries", true) }
                .flatMap { s ->
                    val entries = core.store.items(s.id).map { ListEntry(it.text, it.createdAt, it.doneAt, !it.done) }
                    Staples.due(entries, Aliases(), now).map { StoreStaple(s.name, it.name, it.everyDays, it.last) }
                }
        }.getOrDefault(emptyList())
        return staplesCache
    }

    private fun legs(): List<FlightLeg> = runCatching {
        dev.phonerecall.argo.ArgoStore.state.value.trips.filter { it.status == dev.phonerecall.argo.TripStatus.PLANNED }
            .flatMap { it.legs }.filter { it.mode == dev.phonerecall.argo.LegMode.FLIGHT }
            .map { FlightLeg(it.from, it.to, it.departAt, it.arriveAt, it.number, it.platform) }
    }.getOrDefault(emptyList())

    /** Trips that ended in the last three days, and long personal events that ended in the last twelve hours. */
    private fun ended(events: List<CalItem>, now: Long, at: ZonedDateTime): List<Ended> {
        val trips = runCatching {
            dev.phonerecall.argo.ArgoStore.state.value.trips.filter { it.status == dev.phonerecall.argo.TripStatus.PLANNED || it.status == dev.phonerecall.argo.TripStatus.DONE || it.status == dev.phonerecall.argo.TripStatus.ARCHIVED }
                .mapNotNull { t -> t.window(at.zone)?.takeIf { (_, e) -> now - e in 0..3 * DAY }?.let { (s, e) -> Ended("trip:${t.id}", t.place.ifBlank { "trip" }, s, e) } }
        }.getOrDefault(emptyList())
        val long = events.filter {
            !it.allDay && !it.recurring && it.end <= now && now - it.end <= 12 * HOUR && it.end - it.begin >= 3 * HOUR &&
                it.category in setOf(EventCategory.PERSONAL, EventCategory.TRAVEL, EventCategory.SPORT, EventCategory.HOLIDAY)
        }.map { Ended("event:${it.key}", it.title, it.begin, it.end) }
        return trips + long
    }

    /** His usual lunch outing on office days: the median start of a 10 to 120 minute stop away from work, 11 AM to 2 PM, on 3+ of the last 30 days. */
    private fun lunchAt(d: OdysseyData, now: ZonedDateTime): Int? {
        val work = d.work() ?: return null
        val home = d.home()?.id
        val since = now.toInstant().toEpochMilli() - 30 * DAY
        val days = d.visits.filter { it.start > since }.groupBy { Instant.ofEpochMilli(it.start).atZone(now.zone).toLocalDate() }
        val starts = days.values.mapNotNull { vs ->
            if (vs.none { it.placeId == work.id }) return@mapNotNull null
            vs.filter { it.placeId != work.id && it.placeId != home && it.minutes in 10..120 }
                .map { Instant.ofEpochMilli(it.start).atZone(now.zone).let { t -> t.hour * 60 + t.minute } }
                .firstOrNull { it in 660..840 }
        }
        return TouchLearn.median(starts, 3)
    }

    private fun renewals(c: Context, now: ZonedDateTime): Map<String, java.time.LocalDate> = runCatching {
        MonetaCore.get(c).subscriptions().mapNotNull { s ->
            val kind = when {
                Regex("(?i)inspection").containsMatchIn(s.name) -> "inspection"
                Regex("(?i)registration|\\bdmv\\b").containsMatchIn(s.name) -> "registration"
                else -> null
            }
            kind?.let { k -> s.next?.let { k to it } }
        }.filter { !it.second.isBefore(now.toLocalDate().minusDays(14)) }.toMap()
    }.getOrDefault(emptyMap())

    // ---- taps ----

    private fun io(block: () -> Unit) = thread(name = "touches") { runCatching(block).onFailure { Clio.error(AREA, it) } }

    private fun unhandle(c: Context, t: Touch) = io { update(c) { it.copy(handled = it.handled - t) } }

    /**
     * A tap on a touch card, on the main thread. [undo] shows the Undo toast, [go] opens a Zeus screen,
     * [say] shows a short notice.
     */
    fun tap(c: Context, payload: String?, undo: (Undo) -> Unit, go: (String) -> Unit, say: (String) -> Unit) {
        val parts = payload?.split('|', limit = 3)?.takeIf { it.size == 3 } ?: return
        val t = runCatching { Touch.valueOf(parts[0]) }.getOrNull() ?: return
        val key = parts[1]
        val card = shown["${t.name}|$key"] ?: return
        val does = card.does[parts[2]] ?: return
        val app = c.applicationContext
        Clio.info(AREA, "tap", "${t.label}: ${parts[2]}")
        fun handled() = io { update(app) { it.took(t, key) } }
        when (does) {
            TouchDo.Ack -> handled()
            TouchDo.Later -> io { update(app) { it.later(t, key, java.time.LocalDate.now().toEpochDay()) } }
            TouchDo.Never -> { io { setOn(app, t, false) }; undo(Undo("${t.label} off.") { io { setOn(app, t, true) } }) }
            is TouchDo.Open -> if (!open(c, does.target)) say("That app isn't installed.")
            is TouchDo.Go -> if (does.where == "touches") c.startActivity(TouchesActivity.intent(c)) else go(does.where)
            is TouchDo.Categorize -> {
                val cat = Category.valueOf(does.category)
                MonetaCore.get(app).edit(MonetaOp.Recategorize(does.ref, cat))
                handled()
                undo(Undo("Filed as ${cat.word}.") { MonetaCore.get(app).edit(MonetaOp.Recategorize(does.ref, Category.OTHER)); unhandle(app, t) })
            }
            is TouchDo.AddToList -> {
                val ids = java.util.concurrent.atomic.AtomicReference<List<Long>>(emptyList())
                io { ids.set(BrainCore.get(app).addToList(does.list, listOf(does.item))); update(app) { it.took(t, key) } }
                undo(Undo("Added to ${does.list}.") { io { BrainCore.get(app).removeItems(ids.get()) }; unhandle(app, t) })
            }
            is TouchDo.ClearList -> {
                val cleared = java.util.concurrent.atomic.AtomicReference<List<Long>>(emptyList())
                io {
                    val core = BrainCore.get(app)
                    val s = core.sections.byName(does.list) ?: return@io
                    val now = System.currentTimeMillis()
                    val open = core.store.items(s.id).filter { !it.done && !it.lethe }
                    open.forEach { core.store.updateItem(it.copy(done = true, doneAt = now)) }
                    cleared.set(open.map { it.id })
                    core.changed()
                    update(app) { it.took(t, key) }
                    Clio.info(AREA, "list cleared", "${does.list}: ${open.size}")
                }
                undo(Undo("${does.list} cleared.") {
                    io {
                        val core = BrainCore.get(app)
                        val s = core.sections.byName(does.list) ?: return@io
                        val ids = cleared.get().toSet()
                        core.store.items(s.id).filter { it.id in ids }.forEach { core.store.updateItem(it.copy(done = false, doneAt = null)) }
                        core.changed()
                    }
                    unhandle(app, t)
                })
            }
            is TouchDo.Copy -> {
                c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tip", does.text))
                say("Copied ${does.text}")
            }
            is TouchDo.Timer -> {
                val id = startTimer(app, does.minutes, does.label)
                undo(Undo("Timer set.") { io { stopTimer(app, id) } })
            }
            is TouchDo.StopTimer -> {
                val was = load(app).running.firstOrNull { it.id == does.id }
                io { stopTimer(app, does.id) }
                if (was != null && was.endsAt > System.currentTimeMillis()) undo(Undo("Timer stopped.") { io { update(app) { it.copy(running = it.running + was) }; arm(app, was) } })
            }
            is TouchDo.Extend -> io {
                val r = load(app).running.firstOrNull { it.id == does.id } ?: return@io
                val next = r.copy(endsAt = r.endsAt + does.minutes * MIN)
                update(app) { p -> p.copy(running = p.running.map { if (it.id == r.id) next else it }) }
                arm(app, next)
            }
            TouchDo.Bedtime -> {
                val nm = c.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    val before = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    Clio.info(AREA, "bedtime", "do not disturb on")
                    handled()
                    undo(Undo("Do not disturb on.") { nm.setInterruptionFilter(before); unhandle(app, t) })
                } else {
                    // No access to Do not disturb: Samsung's Modes (Sleep), else the system's settings.
                    if (!open(c, "app:com.samsung.android.app.routines")) open(c, "settings:zen")
                    handled()
                }
            }
            TouchDo.NightLight -> open(c, "settings:night")
            TouchDo.FocusOff -> {
                val before = load(app)
                io {
                    update(app) { p ->
                        val f = p.focus
                        p.copy(focus = null, focusOffDay = if (f?.why == "office" || t == Touch.WORK_FOCUS) java.time.LocalDate.now().toEpochDay() else p.focusOffDay)
                            .let { if (t == Touch.MATCH_MODE) it.copy(focus = Focus("off", 0, "off:$key")) else it }
                    }
                    if (before.focus?.why == "match" && before.matchDnd) runCatching { c.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                    Clio.info(AREA, "focus off", "by hand")
                }
                handled()
                undo(Undo("Alerts back to normal.") { io { update(app) { it.copy(focus = before.focus, focusOffDay = before.focusOffDay, handled = it.handled - t) } } })
            }
            is TouchDo.Plug -> {
                io {
                    val core = BrainCore.get(app)
                    core.fates.propose(listOf(RawStep(ProposalKind.HOME_CONTROL, mapOf("device_id" to does.deviceId, "action" to "on"))), "Turn on ${does.name}", null, proposer = PROPOSER)
                    core.changed()
                    update(app) { it.took(t, key) }
                }
                say("Confirm it in Fates.")
            }
            TouchDo.Resume -> {
                io { runCatching { dev.phonerecall.music.MusicCore.get(app).control("resume") }.onFailure { Clio.error(AREA, it) }; update(app) { it.took(t, key) } }
                undo(Undo("Playing.") { io { runCatching { dev.phonerecall.music.MusicCore.get(app).control("pause") } } })
            }
            is TouchDo.Returned -> {
                io { update(app) { p -> p.copy(borrowed = p.borrowed.map { if (it.id == does.id) it.copy(returned = true) else it }) } }
                undo(Undo("Marked returned.") { io { update(app) { p -> p.copy(borrowed = p.borrowed.map { if (it.id == does.id) it.copy(returned = false) else it }) } } })
            }
            is TouchDo.ReadLink -> {
                val link = load(app).links.firstOrNull { it.id == does.id } ?: return
                open(c, link.url)
                io { readLink(app, link.id) }
            }
            is TouchDo.OpenPin -> if (!open(c, does.uri)) say("That file is gone.")
            is TouchDo.PickPhotos -> { handled(); c.startActivity(TouchesActivity.photos(c, does.from, does.to)) }
            is TouchDo.SaveReceipt -> { handled(); c.startActivity(TouchesActivity.receipt(c, does.name)) }
            is TouchDo.DropNote -> {
                val note = load(app).leave.firstOrNull { it.id == does.id } ?: return
                io { update(app) { p -> p.copy(leave = p.leave.filter { it.id != does.id }) } }
                undo(Undo("Note dropped.") { io { update(app) { p -> p.copy(leave = p.leave + note) } } })
            }
        }
    }

    fun readLink(c: Context, id: String, now: ZonedDateTime = ZonedDateTime.now()) = update(c) { p ->
        p.copy(links = p.links.map { if (it.id == id) it.copy(openedAt = now.toInstant().toEpochMilli()) else it }, readHours = (p.readHours + dev.phonerecall.home.CardEngine.hourOfWeek(now)).takeLast(40))
    }

    /** Opens a web address, a content uri, "geo:", "settings:<page>" or "app:<package>[,app:<package>...]" (the first installed). */
    fun open(c: Context, target: String): Boolean {
        val i = when {
            target.startsWith("app:") -> target.split(',').map { it.trim().removePrefix("app:") }.firstNotNullOfOrNull { c.packageManager.getLaunchIntentForPackage(it) }
            target == BLUETOOTH_SETTINGS -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            target == "settings:night" -> Intent("android.settings.NIGHT_DISPLAY_SETTINGS")
            target == "settings:zen" -> Intent("android.settings.ZEN_MODE_SETTINGS")
            target.startsWith("content:") -> Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
        } ?: return false
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            (target == "settings:night" && runCatching { c.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess)
    }

    private const val BLUETOOTH_SETTINGS = TouchRules.BLUETOOTH

    // ---- quick timers ----

    fun startTimer(c: Context, minutes: Int, label: String, now: Long = System.currentTimeMillis()): String {
        val r = RunningTimer("t$now", label, now + minutes * MIN)
        update(c) { p -> p.copy(running = p.running.filter { it.endsAt > now } + r) }
        arm(c, r)
        Clio.info(AREA, "timer", "$label, $minutes min")
        return r.id
    }

    fun stopTimer(c: Context, id: String) {
        update(c) { p -> p.copy(running = p.running.filter { it.id != id }) }
        c.getSystemService(AlarmManager::class.java).cancel(timerIntent(c, id))
        Clio.info(AREA, "timer stopped")
        refreshNowBar(c)
    }

    private fun timerIntent(c: Context, id: String) = PendingIntent.getBroadcast(
        c, id.hashCode(), Intent(c, TouchReceiver::class.java).setAction(TouchReceiver.ACTION_TIMER).putExtra(TouchReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun arm(c: Context, r: RunningTimer) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.endsAt, timerIntent(c, r.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.endsAt, timerIntent(c, r.id))
        refreshNowBar(c)
    }

    /** After a restart: re-arm the timers still running. */
    fun rearm(c: Context) {
        val now = System.currentTimeMillis()
        load(c).running.filter { it.endsAt > now }.forEach { arm(c, it) }
    }

    fun timerDone(c: Context, id: String) {
        val r = load(c).running.firstOrNull { it.id == id } ?: return
        update(c) { p -> p.copy(running = p.running.filter { it.id != id }) }
        post(c, CHANNEL_TIMER, "Timers", id.hashCode(), "${r.label} is done", "Your ${r.label.lowercase()} timer ran out.", loud = true)
        Clio.info(AREA, "timer done", r.label)
        refreshNowBar(c)
    }

    private fun refreshNowBar(c: Context) = thread(name = "touches-nowbar") { runCatching { dev.phonerecall.nowbar.NowBar.refreshFromDisk(c) } }

    // ---- hooks ----

    /** Odyssey closed a visit: any "remind me when I leave" note made there fires now. */
    fun visited(c: Context, v: Visit) {
        val p = load(c)
        if (p.leave.isEmpty() || Touch.LEAVE_NOTE in p.off) return
        val name = OdysseyStore.state.value.place(v.placeId)?.name
        val due = p.leave.filter { kotlin.math.abs(it.stayStart - v.start) <= 10 * MIN || (name != null && it.place.isNotEmpty() && it.place.equals(name, true)) }
        if (due.isEmpty()) return
        update(c) { q -> q.copy(leave = q.leave.filter { n -> due.none { it.id == n.id } }) }
        for (n in due) post(c, CHANNEL, "Small touches", n.id.hashCode(), "Leaving ${name ?: n.place.ifEmpty { "here" }}", n.text, loud = !onCall(c))
        Clio.info(AREA, "leave note fired", "${due.size}")
    }

    /** Music's drive mode saw the drive end: remember whether music was on, for the Fold. */
    fun driveEnded(c: Context, playing: Boolean) {
        update(c) { it.copy(driveEndedAt = System.currentTimeMillis(), musicAtDriveEnd = playing) }
    }

    /** A notification arrived: a subscription's cancellation notice is kept as proof. Name and first line only. */
    fun notification(c: Context, n: Incoming) {
        val service = TouchParse.cancellation(n.pkg, n.app, n.title, n.text) ?: return
        if (Touch.CANCEL_PROOF in load(c).off) return
        val line = n.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
        update(c) { p -> p.copy(proofs = (p.proofs.filter { !it.service.equals(service, true) } + Proof(service, n.at, line, n.app)).takeLast(30)) }
        Clio.info(AREA, "cancel proof", service)
    }

    /** A Bluetooth audio device dropped. While music played: one quiet notice with Bluetooth and the music app. */
    fun audioDropped(c: Context, musicActive: Boolean) {
        val playing = musicActive || runCatching { dev.phonerecall.music.MusicCore.get(c).now.value?.playing == true }.getOrDefault(false)
        if (!playing || Touch.AUDIO_DROP in load(c).off) return
        val now = System.currentTimeMillis()
        audioDropAt = now
        Clio.info(AREA, "audio dropped")
        val d = OdysseyStore.state.value
        if (onCall(c) || d.driving || gaming(c, now)) return
        val bt = PendingIntent.getActivity(c, 61_201, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), PendingIntent.FLAG_IMMUTABLE)
        val musicApp = c.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { PendingIntent.getActivity(c, 61_202, it, PendingIntent.FLAG_IMMUTABLE) }
        post(c, CHANNEL, "Small touches", 61_200, "Audio disconnected", "Music was playing.", loud = false) { b ->
            b.addAction(0, "Bluetooth", bt)
            musicApp?.let { b.addAction(0, "Music", it) }
            b.setTimeoutAfter(10 * MIN)
        }
    }

    private fun post(c: Context, channel: String, name: String, id: Int, title: String, text: String, loud: Boolean, more: (NotificationCompat.Builder) -> Unit = {}) {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, name, if (channel == CHANNEL_TIMER) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, id, Intent(c, dev.phonerecall.MainActivity::class.java).setAction(dev.phonerecall.nowbar.ACTION_OPEN_HOME).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_owl).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setSilent(!loud).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (channel == CHANNEL_TIMER) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
        more(b)
        nm.notify(id, b.build())
    }

    // ---- what he keeps ----

    /** A shared link for Read later, or a "Jamie has my controller" line for Borrowed. What it became, for the toast. */
    fun share(c: Context, text: String, subject: String?, now: Long = System.currentTimeMillis()): String? {
        val url = TouchParse.firstUrl(text)
        if (url != null) {
            val title = subject?.takeIf { it.isNotBlank() } ?: text.replace(url, "").trim().take(120)
            update(c) { p -> p.copy(links = (p.links.filter { it.url != url } + SavedLink("l$now", url, title, now)).takeLast(100)) }
            Clio.info(AREA, "read later saved")
            return "Saved to Read later"
        }
        val b = TouchRules.parseBorrowed(text, java.time.LocalDate.now(), now) ?: return null
        update(c) { p -> p.copy(borrowed = p.borrowed + b) }
        Clio.info(AREA, "borrowed saved")
        return "Noted: ${b.who} has your ${b.what}"
    }

    /** "Remind me when I leave": the stay he is at now, from Odyssey. Null when Odyssey doesn't know where he is. */
    fun leaveNote(c: Context, text: String, now: Long = System.currentTimeMillis()): LeaveNote? {
        val d = OdysseyStore.state.value
        val s = d.stay?.takeIf { Signals.on(d) && !d.driving } ?: return null
        val known = d.places.firstOrNull { Geo.meters(it.lat, it.lng, s.lat, s.lng) < 150 }?.name
        val n = LeaveNote("n$now", text.trim(), s.start, known ?: s.poiName ?: s.poi ?: s.street.orEmpty(), now)
        update(c) { p -> p.copy(leave = p.leave + n) }
        Clio.info(AREA, "leave note")
        return n
    }
}

/** Learned habits from times. Pure. */
object TouchLearn {
    /** The median of [mins] once there are at least [atLeast] of them. */
    fun median(mins: List<Int>, atLeast: Int): Int? = mins.sorted().takeIf { it.size >= atLeast }?.let { it[it.size / 2] }
}
