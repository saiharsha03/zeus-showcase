package dev.phonerecall.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.BuildConfig
import dev.phonerecall.HomeMode
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.topRule
import dev.phonerecall.ui.APP_NAME
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.LocalPlainNames
import dev.phonerecall.ui.emblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/**
 * A Themis page asked for from another screen (Telete's "Connect" for Spotify opens the music page), or a row to
 * scroll to and light (a settings match in the sidebar's search).
 */
object ThemisNav {
    @Volatile var page: String? = null
    @Volatile var row: String? = null
    fun take(): String? = page.also { page = null }
    fun takeRow(): String? = row.also { row = null }
}

@Composable
fun SettingsScreen(vm: AthenaViewModel, gutter: Dp, go: (dev.phonerecall.Dest) -> Unit) {
    val openHome = { go(dev.phonerecall.Dest.HOME) }
    var page by remember { mutableStateOf<String?>(ThemisNav.take()) }
    val pageCtx = LocalContext.current
    LaunchedEffect(Unit) { if (dev.phonerecall.mnemosyne.Mnemosyne.takeShowRestored(pageCtx)) page = "mnemosyne" }
    if (page == "mnemosyne") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.mnemosyne.MnemosyneScreen(gutter, gutter >= Space.gutterWide) { page = null }
        return
    }
    if (page == "audit") {
        androidx.activity.compose.BackHandler { page = null }
        AuditPage(vm, gutter) { page = null }
        return
    }
    if (page == "places") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.mind.PlacesScreen(vm.brain, gutter) { page = null }
        return
    }
    if (page == "music") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.music.MusicPage(gutter) { page = null }
        return
    }
    if (page == "olympia") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.olympia.OlympiaSettingsPage(gutter, Place.SETTINGS.title()) { page = null }
        return
    }
    if (page == "rhythms") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.rhythms.RhythmsPage(gutter) { page = null }
        return
    }
    if (page == "auto") {
        androidx.activity.compose.BackHandler { page = null }
        val s by vm.brain.settings.collectAsStateWithLifecycle()
        val stats by vm.brain.fatigue.collectAsStateWithLifecycle()
        val budget by vm.brain.budget.collectAsStateWithLifecycle()
        val att by vm.brain.attention.collectAsStateWithLifecycle()
        AutoApproveContent(gutter, AutoApproveView(s.autoApprove, stats, budget, att.first, att.second), AutoApproveCalls(toggle = { vm.brain.toggleAuto(it) }, budget = { vm.brain.setBudget(it) }, back = { page = null }))
        return
    }
    if (page == "outbound") {
        androidx.activity.compose.BackHandler { page = null }
        val rows by vm.brain.outbound.collectAsStateWithLifecycle()
        OutboundContent(gutter, rows, back = { page = null })
        return
    }
    if (page == "oneiroi") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.oneiroi.MemoryCarePage(vm.brain, gutter) { page = null }
        return
    }
    if (page == "clio") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.clio.ClioPage(gutter) { page = null }
        return
    }
    if (page == "remembered") {
        androidx.activity.compose.BackHandler { page = null }
        dev.phonerecall.mind.RememberedPage(vm.brain, gutter) { page = null }
        return
    }
    val mind by vm.brain.mind.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val brain by vm.brain.settings.collectAsStateWithLifecycle()
    var writeCalOk by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    var calendarOk by remember { mutableStateOf(false) }
    var notifyOk by remember { mutableStateOf(false) }
    var contactsOk by remember { mutableStateOf(false) }
    var micOk by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(false) }
    val senses by vm.brain.senses.collectAsStateWithLifecycle()
    val nano by dev.phonerecall.brain.Nano.status.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val relevance by vm.relevance.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<dev.phonerecall.data.Rule?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        calendarOk = granted(ctx, Manifest.permission.READ_CALENDAR)
        notifyOk = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        contactsOk = granted(ctx, Manifest.permission.READ_CONTACTS)
        writeCalOk = granted(ctx, Manifest.permission.WRITE_CALENDAR)
        micOk = granted(ctx, Manifest.permission.RECORD_AUDIO)
        batteryOk = ctx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    val openListenerSettings = { dev.phonerecall.setup.TeleteHub.open(ctx, dev.phonerecall.setup.Screen.NOTIFICATION_LISTENER) }
    val askWriteCal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { writeCalOk = it }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarOk = it; vm.refreshCalendar() }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifyOk = it }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it; vm.refreshBirthdays() }
    val placesName = Place.PLACES.title()
    val vaultName = Place.VAULT.title()
    var confirmReset by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(4000); confirmReset = false } }
    LaunchedEffect(resetDone) { if (resetDone) { delay(2500); resetDone = false } }

    editing?.let { r ->
        dev.phonerecall.calendar.RuleDialog(
            eventTitle = null, calendar = r.calendarId?.let { calendars[it] }, existing = r,
            onSave = { vm.saveRule(it) }, onDelete = { vm.deleteRule(r.id) }, onDismiss = { editing = null },
        )
    }

    // One row of Themis, by its registry key (ThemisRegistry.rows has the order and the sections).
    val row: @Composable (String) -> Unit = { key ->
        when (key) {
            "theme" -> ChoiceRow("Theme", listOf("Dark", "Light"), if (settings.dark) 0 else 1) { vm.setDark(it == 0) }
            // Easter egg: a long press swaps the mottos for a hidden set of epithets (and back).
            "plain-names" -> SettingRow(
                "Plain names", "Home, Calendar, Tasks instead of Olympus, Chronos, Praxis", onClick = { vm.setPlainNames(!settings.plainNames) },
                onLongClick = { dev.phonerecall.ui.Eggs.toggleEpithets() },
            ) {
                StatusWord(if (settings.plainNames) "On" else "Off", if (settings.plainNames) Tone.ACTIVE else Tone.QUIET)
            }
            "second-clock" -> {
                // The second clock Athena reads next to his own: now lines, calls, dates in that zone.
                val zones = dev.phonerecall.brain.TwoClocks.CHOICES
                ChoiceRow(if (LocalPlainNames.current) "Second clock" else "Janus · second clock", zones.map { it.second }, zones.indexOfFirst { it.first == settings.secondClock }.coerceAtLeast(0)) { vm.setSecondClock(zones[it].first) }
            }
            // Auto: Haiku 5.5 for quick turns, Sonnet 5.5 when a turn needs planning or several tools. A model here overrides.
            "delphi-model" -> ChoiceRow("Delphi model", listOf("Auto") + dev.phonerecall.brain.BrainModel.entries.map { it.label.substringBefore(' ') }, if (brain.auto) 0 else brain.model.ordinal + 1) {
                if (it == 0) vm.brain.setAuto() else vm.brain.setModel(dev.phonerecall.brain.BrainModel.entries[it - 1])
            }
            "spend" -> Column { dev.phonerecall.mind.SpendRows(brain.spend, mind); dev.phonerecall.voice.LiveSpendRows() }
            "usage" -> {
                val usage by remember { dev.phonerecall.metron.MetronCore.get(ctx) }.usage.collectAsStateWithLifecycle()
                val b = remember(brain.spend, mind.circle) { dev.phonerecall.metron.MetronBudget.of(brain.spend, mind.circle, null, java.time.ZonedDateTime.now()) }
                SettingRow(Place.USAGE.title(), dev.phonerecall.metron.metronLine(usage, b), onClick = { go(dev.phonerecall.Dest.USAGE) }, emblem = Place.USAGE) {
                    val worst = maxOf(usage?.agents.orEmpty().maxOfOrNull { it.worst?.usedPct ?: 0.0 } ?: 0.0, b.pct)
                    StatusWord(if (worst >= 95) "Limit" else if (worst >= 80) "Near" else "Room", if (worst >= 95) Tone.ALERT else if (worst >= 80) Tone.ACTIVE else Tone.QUIET)
                }
            }
            "cap" -> {
                val caps = listOf(25, 50, 100, 150)
                ChoiceRow("Monthly cap", caps.map { "$$it" }, caps.indexOf(brain.spend.capUsd).coerceAtLeast(-1)) { vm.brain.setCap(caps[it]) }
            }
            "pantheon" -> SettingRow(Place.AGENTS.title(), "Iris, Atlas and Agora: who is running and what they are doing", onClick = { go(dev.phonerecall.Dest.AGENTS) }, emblem = Place.AGENTS)
            "lemnos" -> SettingRow(Place.VPS.title(), "The server: terminal, files, services and the desktop", onClick = { go(dev.phonerecall.Dest.VPS) }, emblem = Place.VPS)
            "fates" -> SettingRow(Place.PROPOSALS.title(), "Every proposal: waiting, done and rejected", onClick = { go(dev.phonerecall.Dest.FATES) }, emblem = Place.PROPOSALS)
            "mind" -> dev.phonerecall.mind.MindRows(
                mind, brain.keySet, openRemembered = { page = "remembered" }, openPlaces = { page = "places" }, openMemoryCare = { page = "oneiroi" },
                toggleHeartbeat = { vm.brain.setHeartbeat(!mind.heartbeat.on) },
                openHorologion = { go(dev.phonerecall.Dest.HOROLOGION) },
                openAssistant = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } },
            )
            "refresh-known" -> SettingRow("Refresh what you know", "${Place.SEED.title()}: Athena asks Iris again, re-reads the phone and asks only what's new. Your answers stay.", onClick = {
                dev.phonerecall.theogony.TheogonyHub.get(ctx).refresh(); go(dev.phonerecall.Dest.THEOGONY)
            })
            "vault" -> SettingRow(vaultName, "Sealed and Shielded, each behind your fingerprint", onClick = { go(dev.phonerecall.Dest.VAULT) }, emblem = Place.VAULT) {
                StatusWord(if (brain.keySet) "Key set" else "Add key", if (brain.keySet) Tone.OK else Tone.ACTIVE)
            }
            "logs" -> SettingRow(Place.LOGS.title(), "Everything the phone did for the last 14 days, newest first. Copy the last 24 hours, or export for the laptop.", onClick = { page = "clio" })
            "outbound" -> SettingRow("What left the phone", "Every request in the last 7 days: host, purpose, and whether names or coordinates were in it. Never the content.", onClick = { vm.brain.loadOutbound(); page = "outbound" })
            "backup" -> {
                val mn by dev.phonerecall.mnemosyne.Mnemosyne.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { dev.phonerecall.mnemosyne.Mnemosyne.refresh(ctx) } }
                SettingRow(Place.BACKUP.title(), "Nightly, locked with a recovery phrase only you hold, on the storage box behind ${Place.VPS.title()}", onClick = { page = "mnemosyne" }) {
                    when {
                        mn.restored != null -> StatusWord("Finish", Tone.ALERT)
                        !mn.setUp -> StatusWord("Set up", Tone.ACTIVE)
                        mn.last?.ok == false -> StatusWord("Failed", Tone.ALERT)
                        else -> StatusWord("On", Tone.OK)
                    }
                }
            }
            "memory" -> SettingRow(Place.MEMORY.title(), mind.memoryStatus.ifBlank { "Search over your notes, chats and documents, by words and meaning" }, onClick = { go(dev.phonerecall.Dest.MEMORY) }) {
                StatusWord("${mind.memoryChunks}", Tone.QUIET)
            }
            "netgate" -> SettingRow("NetGate", "Only api.anthropic.com, and accounts.spotify.com and api.spotify.com for music. Every other host is blocked and logged." + if (brain.blocked > 0) " ${brain.blocked} blocked so far." else "") {
                StatusWord("On", Tone.OK)
            }
            "setup" -> {
                val tele by remember { dev.phonerecall.setup.TeleteHub.get(ctx) }.state.collectAsStateWithLifecycle()
                SettingRow(Place.SETUP.title(), "Everything Zeus needs, and a test for each", onClick = { go(dev.phonerecall.Dest.SETUP) }) {
                    StatusWord(if (tele.left > 0) "${tele.left} left" else if (tele.left == 0) "Ready" else "Check", if (tele.left > 0) Tone.ALERT else if (tele.left == 0) Tone.OK else Tone.QUIET)
                }
            }
            "battery" -> SettingRow(
                "Unrestricted background",
                "Battery set to Unrestricted, so the heartbeat, syncs and Odyssey keep running with the screen off. On Samsung, also add Zeus to Never sleeping apps.",
                onClick = { dev.phonerecall.setup.TeleteHub.open(ctx, dev.phonerecall.setup.Screen.BATTERY) },
            ) { StatusWord(if (batteryOk) "Unrestricted" else "Allow", if (batteryOk) Tone.OK else Tone.ACTIVE) }
            "senses" -> SettingRow(
                "Notification senses",
                "Reads notifications on the phone to make cards, catch birthdays and learn who to answer first. Codes, password managers and bank messages are never kept.",
                onClick = openListenerSettings,
            ) { StatusWord(if (senses.listening) "Allowed" else "Allow", if (senses.listening) Tone.OK else Tone.ACTIVE) }
            "people" -> SettingRow(Place.PEOPLE.title(), "Who matters, and how fast you answer them. On the phone only.", onClick = { go(dev.phonerecall.Dest.PEOPLE) }) {
                StatusWord("${senses.people.size}", Tone.QUIET)
            }
            "odyssey" -> SettingRow(placesName, "Learned places, commutes and the driving report. On the phone only.", onClick = { go(dev.phonerecall.Dest.ODYSSEY) }) {
                val on = dev.phonerecall.odyssey.OdysseyStore.state.collectAsStateWithLifecycle().value.settings
                StatusWord(if (!on.on) "Off" else if (on.paused) "Paused" else "On", if (on.on && !on.paused) Tone.OK else Tone.QUIET)
            }
            "trips" -> SettingRow(Place.TRIPS.title(), "Trips: flights, stays and plans", onClick = { go(dev.phonerecall.Dest.TRIPS) })
            "weather" -> SettingRow(Place.WEATHER.title(), "Weather where you are, and its alerts", onClick = { go(dev.phonerecall.Dest.WEATHER) }, emblem = Place.WEATHER)
            "money" -> SettingRow(Place.MONEY.title(), "Spending from bank and card notifications, on the phone only", onClick = { go(dev.phonerecall.Dest.MONEY) })
            "health" -> SettingRow(Place.HEALTH.title(), "Steps, sleep, heart rate and weight from Health Connect", onClick = { go(dev.phonerecall.Dest.HEALTH) })
            "hestia" -> SettingRow(Place.DEVICES.title(), "Lights and plugs through Google Home", onClick = { go(dev.phonerecall.Dest.DEVICES) }, emblem = Place.DEVICES)
            "rhythms" -> SettingRow(Place.RHYTHMS.title(), "When you usually open apps and call home. On the phone only.", onClick = { page = "rhythms" }) {
                val r = dev.phonerecall.rhythms.RhythmsStore.state.collectAsStateWithLifecycle().value.settings
                StatusWord(if (r.on) "On" else "Off", if (r.on) Tone.OK else Tone.QUIET)
            }
            "nano" -> SettingRow("Gemini Nano", "On-device sorting while Zeus is open. In the background, Haiku gets derived lines only.") {
                StatusWord(nano.substringBefore(" ("), if (nano.startsWith("ready")) Tone.OK else Tone.QUIET)
            }
            "music" -> {
                val music by dev.phonerecall.music.MusicCore.get(ctx).state.collectAsStateWithLifecycle()
                SettingRow("Music", "Spotify by default, Apple Music as the fallback. Context cards and music while driving.", onClick = { page = "music" }) {
                    StatusWord(if (music.connected) "Spotify" else "Connect", if (music.connected) Tone.OK else Tone.ACTIVE)
                }
            }
            "brief" -> {
                val b by dev.phonerecall.brief.BriefStore.settingsFlow.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { dev.phonerecall.brief.BriefStore.settings(ctx) } }
                fun set(n: dev.phonerecall.brief.BriefSettings) = dev.phonerecall.brief.BriefStore.setSettings(ctx, n)
                Column {
                    SettingRow(Place.BRIEF.title(), "Once a day after your alarm (or your first unlock after 5 AM). Silent in quiet hours, held while you drive.", onClick = { set(b.copy(on = !b.on)) }) {
                        StatusWord(if (b.on) "On" else "Off", if (b.on) Tone.OK else Tone.QUIET)
                    }
                    SettingRow("Ask ${dev.phonerecall.ui.Workers.IRIS} for her part", "Mail, tasks, bills due and flags, in words. She gets the date only, nothing from the phone.", onClick = { set(b.copy(iris = !b.iris)) }) {
                        StatusWord(if (b.iris) "On" else "Off", if (b.iris) Tone.OK else Tone.QUIET)
                    }
                    // Iris's own Telegram briefing: Eos keeps 30 minutes clear of it. Unknown until he sets it or she says.
                    val told = remember(b) { dev.phonerecall.brief.BriefStore.irisTold(ctx) }
                    val slot = (b.irisSlot ?: told)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
                    SettingRow(
                        "${dev.phonerecall.ui.Workers.IRIS}'s briefing",
                        "Eos stays 30 minutes clear of it. Only you can move hers, on Telegram. Long-press to go back to asking her.",
                        onClick = {
                            val t = slot ?: java.time.LocalTime.of(7, 0)
                            android.app.TimePickerDialog(ctx, { _, h, m -> set(b.copy(irisSlot = java.time.LocalTime.of(h, m).toString())) }, t.hour, t.minute, false).show()
                        },
                        onLongClick = { set(b.copy(irisSlot = null)) },
                    ) {
                        StatusWord(slot?.let { java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(it) } ?: "Ask ${dev.phonerecall.ui.Workers.IRIS}", if (slot != null) Tone.QUIET else Tone.ACTIVE)
                    }
                    SettingRow("Read it aloud", "After the alarm, outside quiet hours. Today only; names and Iris's part stay on screen.", onClick = { set(b.copy(speak = !b.speak)) }) {
                        StatusWord(if (b.speak) "On" else "Off", if (b.speak) Tone.ACTIVE else Tone.QUIET)
                    }
                }
            }
            "screen-time" -> {
                var usage by remember { mutableStateOf(dev.phonerecall.odyssey.Odyssey.canUsage(ctx)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { usage = dev.phonerecall.odyssey.Odyssey.canUsage(ctx) }
                SettingRow("Screen time", "Yesterday's total, top apps and late nights, from Usage access. Banking, health, dating and immigration apps are never named.", onClick = {
                    dev.phonerecall.setup.TeleteHub.open(ctx, dev.phonerecall.setup.Screen.USAGE_ACCESS)
                }) { StatusWord(if (usage) "Allowed" else "Allow", if (usage) Tone.OK else Tone.ACTIVE) }
            }
            "auto-approve" -> Column {
                Line(
                    "Proposals of these kinds run without asking, then show as done automatically with Undo. Empty unless you add something.",
                    Type.small, Athena.colors.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
                val budget by vm.brain.budget.collectAsStateWithLifecycle()
                SettingRow(
                    "Auto-approve and attention",
                    "What you confirm and turn down per kind, the list, and the daily budget (${budget.cards} cards, ${budget.notifications} notifications)",
                    onClick = { vm.brain.loadAttention(); page = "auto" },
                ) { StatusWord(if (brain.autoApprove.isEmpty()) "Ask" else "${brain.autoApprove.size} automatic", if (brain.autoApprove.isEmpty()) Tone.QUIET else Tone.ACTIVE) }
            }
            "always-ask" -> Column {
                SettingRow("Calendar events", "Writes to your Google calendar always ask", enabled = false) { StatusWord("Always ask", Tone.QUIET) }
                SettingRow("Plugs and switches", "${Place.DEVICES.title()} always asks. Locks, garage doors and security systems are never controlled.", enabled = false) { StatusWord("Always ask", Tone.QUIET) }
            }
            "audit" -> SettingRow("Audit log", "Every proposal, decision, key use and blocked request", onClick = { vm.brain.loadAudit(); page = "audit" })
            "bedtime" -> {
                val alarmWatch = remember { dev.phonerecall.brain.AlarmWatch.get(ctx) }
                var nudges by remember { mutableStateOf(alarmWatch.nudgesOn()) }
                SettingRow(
                    "${Place.BEDTIME.title()} nudges",
                    "One quiet notification when you are still up past bedtime before an early alarm, flight or busy day. Never on nights with no early start.",
                    onClick = { nudges = !nudges; alarmWatch.setNudges(nudges); Thread { dev.phonerecall.alarms.AlarmPlanner.arm(ctx.applicationContext) }.start() },
                ) { StatusWord(if (nudges) "On" else "Off", if (nudges) Tone.ACTIVE else Tone.QUIET) }
            }
            "moments" -> Column {
                Line(
                    "${Place.MOMENTS.title()}: Zeus offers the obvious helper for the moment, one card at a time, never in quiet hours. Not now snoozes it for the day; Never turns it off here.",
                    Type.small, Athena.colors.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
                val mp by dev.phonerecall.moments.MomentsCore.prefs.collectAsStateWithLifecycle()
                for (k in dev.phonerecall.moments.MomentKind.entries.filter { it.context }) {
                    val on = k !in mp.never
                    SettingRow(k.label, null, onClick = { kotlin.concurrent.thread { dev.phonerecall.moments.MomentsCore.setOn(ctx.applicationContext, k, !on) } }) {
                        StatusWord(if (on) "On" else "Off", if (on) Tone.ACTIVE else Tone.QUIET)
                    }
                }
            }
            "snap" -> SettingRow("Snap the box", "Timers from a box's cooking instructions, read on the phone", onClick = { runCatching { ctx.startActivity(dev.phonerecall.moments.SnapActivity.intent(ctx)) } })
            "sample-cards" -> SettingRow("Show sample cards", "Fills the placeholder cards with realistic examples", onClick = { vm.setSample(!settings.sample) }) {
                StatusWord(if (settings.sample) "On" else "Off", if (settings.sample) Tone.ACTIVE else Tone.QUIET)
            }
            "now-bar" -> SettingRow(
                "Show in Now Bar",
                "The top card on the lock screen and status bar, while it is time-sensitive",
                onClick = {
                    if (settings.nowBar && !notifyOk) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setNowBar(!settings.nowBar)
                },
            ) {
                when {
                    settings.nowBar && !notifyOk -> StatusWord("Allow", Tone.ACTIVE)
                    settings.nowBar -> StatusWord("On", Tone.ACTIVE)
                    else -> StatusWord("Off", Tone.QUIET)
                }
            }
            "edit-home" -> SettingRow("Edit Home", "Add, hide and restore cards", onClick = { vm.setMode(HomeMode.EDIT); openHome() })
            "add-card" -> SettingRow("Add card", "Put another card on Home", onClick = { vm.setMode(HomeMode.BOARD); vm.openAdd(); openHome() })
            "reset-learning" -> SettingRow(
                "Reset learning", "Forget taps and dismissals. Pins and order stay.",
                onClick = {
                    if (confirmReset) { vm.resetLearning(); confirmReset = false; resetDone = true } else confirmReset = true
                },
            ) {
                when {
                    confirmReset -> StatusWord("Tap again", Tone.ALERT)
                    resetDone -> StatusWord("Reset", Tone.OK)
                }
            }
            "sports" -> {
                val o by dev.phonerecall.olympia.OlympiaStore.state.collectAsStateWithLifecycle()
                SettingRow(
                    "${Place.SPORTS.title()}: teams and alerts",
                    "${o.settings.followed.size} followed. ${o.settings.alerts.label}.",
                    onClick = { page = "olympia" },
                ) { Kicker("Edit") }
            }
            "cal-relevance" -> Column {
                Kicker("Calendar relevance", Modifier.padding(top = Space.s, bottom = Space.xs))
                Line(
                    "Athena shows only what matters. Your phone's calendar keeps everything. Birthdays always show.",
                    Type.small, Athena.colors.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
                for (cal in calendars.values.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))) {
                    val mode = relevance.mode(cal)
                    SettingRow(cal.name.ifEmpty { "Calendar" }, cal.kind.label) {
                        for (m in dev.phonerecall.data.CalMode.entries) {
                            val on = m == mode
                            Line(
                                when (m) { dev.phonerecall.data.CalMode.SHOW -> "Show"; dev.phonerecall.data.CalMode.HIGHLIGHTS -> "Highlights"; dev.phonerecall.data.CalMode.HIDE -> "Hide" },
                                Type.small, if (on) Athena.colors.accentInk else Athena.colors.ink3,
                                Modifier.clickable(role = Role.RadioButton) { vm.setCalendarMode(cal.id, m) }.padding(horizontal = Space.xs, vertical = Space.m),
                            )
                        }
                    }
                }
                Kicker("Rules", Modifier.padding(top = Space.l, bottom = Space.xs))
                for (r in relevance.rules) {
                    val scope = r.calendarId?.let { id -> "Only ${calendars[id]?.name ?: "one calendar"}" }
                        ?: if (r.kinds.isNotEmpty()) r.kinds.joinToString(", ") { it.label } else "Any calendar"
                    SettingRow(r.label.ifEmpty { r.match.replace("|", ", ") }, scope + if (r.seeded) ", default" else "", onClick = { editing = r }) {
                        StatusWord(if (r.action == dev.phonerecall.data.RuleAction.KEEP) "Show" else "Hide", if (r.action == dev.phonerecall.data.RuleAction.KEEP) Tone.OK else Tone.QUIET)
                    }
                }
                SettingRow("Restore default rules", "Brings back Formula 1, your teams, festivals and US holidays", onClick = { vm.restoreSeedRules() })
            }
            "perm-calendar" -> SettingRow("Calendar", "Your next event on Home. Read live, never copied.", onClick = { if (!calendarOk) askCalendar.launch(Manifest.permission.READ_CALENDAR) }) {
                StatusWord(if (calendarOk) "Allowed" else "Allow", if (calendarOk) Tone.OK else Tone.ACTIVE)
            }
            "perm-notify" -> SettingRow("Notifications", "For the Now Bar, and nudges from phase 2", onClick = { if (!notifyOk) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                StatusWord(if (notifyOk) "Allowed" else "Allow", if (notifyOk) Tone.OK else Tone.ACTIVE)
            }
            "perm-contacts" -> SettingRow("Contacts", "First names, birthdays and relation fields (Mother, Manager) only, for birthday cards and People", onClick = { if (!contactsOk) askContacts.launch(Manifest.permission.READ_CONTACTS) }) {
                StatusWord(if (contactsOk) "Allowed" else "Allow", if (contactsOk) Tone.OK else Tone.ACTIVE)
            }
            "perm-calwrite" -> SettingRow("Calendar writes", "Lets confirmed proposals add events to your Google calendar", onClick = { if (!writeCalOk) askWriteCal.launch(Manifest.permission.WRITE_CALENDAR) }) {
                StatusWord(if (writeCalOk) "Allowed" else "Allow", if (writeCalOk) Tone.OK else Tone.ACTIVE)
            }
            "perm-mic" -> SettingRow("Microphone", "For Speak in ${Place.RAMBLER.title()}. Recognized on the phone.", onClick = { if (!micOk) askMic.launch(Manifest.permission.RECORD_AUDIO) }) {
                StatusWord(if (micOk) "Allowed" else "Allow", if (micOk) Tone.OK else Tone.ACTIVE)
            }
            "about" -> Column {
                ZeusCoin()
                // Secrets: listed only after he has found three of them.
                if (dev.phonerecall.ui.Eggs.secretsOpen) {
                    Kicker("Secrets", Modifier.padding(top = Space.xl, bottom = Space.xs))
                    for (e in dev.phonerecall.ui.Eggs.Egg.entries.filter { it.name in dev.phonerecall.ui.Eggs.found }) SettingRow(e.label, e.hint)
                    val left = dev.phonerecall.ui.Eggs.Egg.entries.size - dev.phonerecall.ui.Eggs.found.size
                    if (left > 0) Line(if (left == 1) "One more is hidden." else "$left more are hidden.", Type.small, Athena.colors.ink3, Modifier.padding(top = Space.s))
                }
                SettingRow("Version", BuildConfig.VERSION_NAME)
                SettingRow("Build", "Phase 2c")
                SettingRow("Network", "api.anthropic.com, accounts.spotify.com and api.spotify.com, plus ESPN and Jolpica for ${Place.SPORTS.title()}'s scores, api.tomtom.com and 511ny.org for ${Place.PLACES.title()}'s traffic, through NetGate. ML Kit's telemetry is stripped. Cleartext is off.")
            }
            else -> {}
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    ThemisList(gutter, query, { query = it }, row, ThemisIndex.vectors) { q -> withContext(Dispatchers.IO) { ThemisIndex.embed(ctx, q) } }
}

/**
 * Themis's body: the search box, then either the matches or every row by section. A match scrolls to its row
 * and lights it for a moment. [embed] gives the query's meaning vector, or null (words only).
 */
@Composable
fun ThemisList(
    gutter: Dp,
    query: String,
    onQuery: (String) -> Unit,
    row: @Composable (String) -> Unit,
    vectors: Map<String, FloatArray>,
    embed: suspend (String) -> FloatArray?,
) {
    val c = Athena.colors
    val list = rememberLazyListState()
    var target by remember { mutableStateOf(ThemisNav.takeRow()) }
    var lit by remember { mutableStateOf<String?>(null) }
    val (found, meaning) = rememberSearch(query, ThemisRegistry.entries, vectors, embed)
    // Lazy indexes: each section's header, then its rows.
    val index = remember {
        var i = 0
        buildMap { for ((_, rows) in ThemisRegistry.rows.groupBy({ it.first }, { it.second })) { i++; for (r in rows) put(r, i++) } }
    }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        index[t]?.let { list.scrollToItem((it - 1).coerceAtLeast(0)) }
        lit = t; target = null
        delay(1600); lit = null
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.SETTINGS.title(), gutter, emblem = Place.SETTINGS, subtitle = Place.SETTINGS.subtitle())
        UnderlineField(query, { onQuery(it.take(60)) }, "Search settings: battery, where I park, backup", Modifier.fillMaxWidth().padding(horizontal = gutter), style = Type.small)
        if (query.isNotBlank()) {
            SearchHits(found, meaning, gutter) { e -> onQuery(""); target = e.row }
            return@Column
        }
        LazyColumn(state = list, contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            for ((g, rows) in ThemisRegistry.rows.groupBy({ it.first }, { it.second })) {
                item(key = "h-${g.name}") { Section(g.title) }
                for (r in rows) item(key = r) {
                    val glow by animateColorAsState(if (lit == r) c.accent.copy(alpha = 0.16f) else Color.Transparent, label = "lit")
                    Box(Modifier.fillMaxWidth().background(glow)) { row(r) }
                }
            }
        }
    }
}

/** Ranked matches for [query]: words at once, words and meaning a moment later when the vectors are there. */
@Composable
fun rememberSearch(query: String, entries: List<SettingEntry>, vectors: Map<String, FloatArray>, embed: suspend (String) -> FloatArray?): Pair<List<SettingEntry>, Boolean> {
    var queryVector by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(query) {
        queryVector = null
        if (query.isNotBlank() && vectors.isNotEmpty()) { delay(250); queryVector = runCatching { embed(query) }.getOrNull() }
    }
    val found = remember(query, queryVector, entries) { ThemisSearch.rank(query, entries, vectors, queryVector) }
    return found to (queryVector != null)
}

/** Search results: both names ("Hypnos · Bedtime") whatever the plain-names toggle says, what it is, and where it lives. */
@Composable
fun SearchHits(found: List<SettingEntry>, meaning: Boolean, gutter: Dp, onPick: (SettingEntry) -> Unit) {
    val c = Athena.colors
    LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Space.xxxl)) {
        item(key = "count") {
            Line(
                if (found.isEmpty()) "Nothing matches." else "${found.size} found" + if (meaning) ", by words and meaning" else "",
                Type.caption, c.ink3, Modifier.padding(vertical = Space.s),
            )
        }
        for (e in found) item(key = "f-${e.key}") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).clickable(role = Role.Button) { onPick(e) }.padding(vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pl = e.place
                if (pl?.emblem != null) { dev.phonerecall.ui.Emblem(pl, 32.dp); Spacer(Modifier.width(Space.m)) }
                Column(Modifier.weight(1f)) {
                    Line(e.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                    val what = if (pl != null && e.label != pl.myth && e.label != pl.plain) "${e.label}: ${e.about}" else e.about
                    Line(what, Type.small, c.ink3, Modifier.widthIn(max = 520.dp), maxLines = 2)
                }
                Spacer(Modifier.width(Space.m))
                Kicker(if (e.dest != null) "Place" else e.group.title)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Kicker(title, Modifier.padding(top = Space.xxl, bottom = Space.s))
}

/** A settings row: title, one line of detail, a trailing word. Flat, under a hairline. */
@Composable
internal fun SettingRow(
    title: String,
    about: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    emblem: Place? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = Athena.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .topRule(c.rule)
            .then(
                if (onClick != null && enabled && onLongClick != null) Modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick()
                })
                else if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            )
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emblem != null) { dev.phonerecall.ui.Emblem(emblem, 36.dp, alpha = if (enabled) 1f else 0.6f); Spacer(Modifier.width(Space.m)) }
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), if (enabled) c.ink else c.ink3, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}

/** Options as plain words; the chosen one is full ink with a saffron underline. No pills, no switch. */
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
        options.forEachIndexed { i, label ->
            val on = i == selected
            val underline = c.accent
            Column(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = Space.m),
                verticalArrangement = Arrangement.Center,
            ) {
                Line(
                    label, Type.action, if (on) c.ink else c.ink3,
                    Modifier.drawBehind {
                        if (on) {
                            val w = 2.dp.toPx()
                            drawLine(underline, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w)
                        }
                    },
                )
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}

/** Themis's audit log: newest first, one labelled row per event. */
@Composable
private fun AuditPage(vm: AthenaViewModel, gutter: Dp, back: () -> Unit) {
    val c = Athena.colors
    val rows by vm.brain.audit.collectAsStateWithLifecycle()
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Audit log", gutter, kicker = Place.SETTINGS.title()) { dev.phonerecall.ui.TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            if (rows.isEmpty()) item { Line("Nothing yet.", Type.body, c.ink2, Modifier.padding(top = Space.l)) }
            for ((i, r) in rows.withIndex()) item(key = i) {
                Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Line(r.actor + " " + r.action, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                        Line(fmt.format(java.time.Instant.ofEpochMilli(r.at).atZone(java.time.ZoneId.systemDefault())), Type.small, c.ink3, maxLines = 1)
                    }
                    Line(r.detail, Type.small, c.ink2)
                }
            }
        }
    }
}

/**
 * About: the Zeus coin. Easter egg: a long press flips it (rotateY) to Athena's profile on the back,
 * with one Greek line. Another long press flips it back.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZeusCoin() {
    val c = Athena.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var flipped by remember { mutableStateOf(false) }
    val line = remember(flipped) {
        listOf("Γνῶθι σεαυτόν. Know thyself.", "Μηδὲν ἄγαν. Nothing in excess.").random()
    }
    val angle by androidx.compose.animation.core.animateFloatAsState(if (flipped) 180f else 0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 120f), label = "coin")
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(56.dp)
                .graphicsLayer { rotationY = angle; cameraDistance = 14f * density }
                .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    flipped = !flipped
                    dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.ZEUS_COIN)
                }),
        ) {
            if (angle <= 90f) dev.phonerecall.ui.ArtImage(dev.phonerecall.R.drawable.ic_launcher_zeus, 56.dp)
            // The back face is mirrored once more so it reads the right way round.
            else dev.phonerecall.ui.ArtImage(dev.phonerecall.R.drawable.athena_profile, 56.dp, Modifier.graphicsLayer { rotationY = 180f })
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Line(APP_NAME, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
            Line(if (angle > 90f) line else "Athena is its mind.", Type.small, c.ink3)
        }
    }
}
