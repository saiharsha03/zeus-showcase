package dev.phonerecall.setup

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.clio.Clio
import dev.phonerecall.clio.Level
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What Telete's page and the sidebar read. [left] is kept across launches so the sidebar doesn't flicker. */
data class TeleteState(
    val checks: List<Check> = emptyList(),
    val left: Int = -1,
    val checking: Boolean = false,
    val results: Map<String, TestResult> = emptyMap(),
    val running: Set<String> = emptySet(),
)

/**
 * Telete on the phone: gathers [Facts] (the gateway and Google only every 10 minutes, or on Refresh), and runs
 * the self-tests one at a time. Every test result goes to Clio under "setup".
 */
class TeleteHub private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sp = app.getSharedPreferences("telete", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(TeleteState(left = sp.getInt("left", -1)))
    val state: StateFlow<TeleteState> = _state.asStateFlow()

    @Volatile private var remoteAt = 0L
    @Volatile private var remote = Remote()
    private data class Remote(val iris: String? = null, val agora: Boolean? = null, val googleBirthdays: Boolean? = null)

    /** Local facts now; the network ones when they are older than 10 minutes, or when `force`. */
    fun refresh(force: Boolean = false) = scope.launch {
        publish(facts())
        if (force || System.currentTimeMillis() - remoteAt > 10 * 60_000L) {
            _state.update { it.copy(checking = true) }
            remote = remote()
            remoteAt = System.currentTimeMillis()
            publish(facts())
            _state.update { it.copy(checking = false) }
        }
    }

    private fun publish(f: Facts) {
        val checks = Telete.checks(f, System.currentTimeMillis())
        val left = Telete.left(checks)
        if (left != _state.value.left) {
            sp.edit().putInt("left", left).apply()
            Clio.info("setup", if (left == 0) "all ready" else "$left left")
        }
        _state.update { it.copy(checks = checks, left = left) }
    }

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun facts(): Facts {
        val core = BrainCore.get(app)
        val host = core.mindHost
        val od = runCatching { dev.phonerecall.odyssey.OdysseyStore.load(app) }.getOrNull()
        runCatching { dev.phonerecall.mnemosyne.Mnemosyne.refresh(app) }
        val mn = dev.phonerecall.mnemosyne.Mnemosyne.state.value
        val th = dev.phonerecall.theogony.TheogonyHub.get(app).state.value
        val hc = dev.phonerecall.iaso.RealHc.sdk(app)?.let { HcFact.NOT_INSTALLED } ?: runCatching {
            kotlinx.coroutines.runBlocking { dev.phonerecall.iaso.RealHc.of(app)?.granted() }
        }.getOrNull()?.let {
            when (dev.phonerecall.iaso.HcPermissions.status(it)) {
                dev.phonerecall.iaso.HcStatus.READY -> HcFact.READY
                dev.phonerecall.iaso.HcStatus.PARTIAL -> HcFact.PARTIAL
                else -> HcFact.NONE
            }
        } ?: HcFact.NONE
        val link = runCatching { dev.phonerecall.hestia.HestiaCore.get(app).snapshot.link }.getOrNull()
        val r = remote
        return Facts(
            granted = listOf(Perm.FINE, Perm.BACKGROUND, Perm.ACTIVITY, Perm.CALL_LOG, Perm.CONTACTS, Perm.CALENDAR, Perm.MIC, Perm.NOTIFY).filter(::granted).toSet() +
                // Before Android 13 notifications need no permission.
                (if (Build.VERSION.SDK_INT < 33) setOf(Perm.NOTIFY) else emptySet()),
            listener = app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app),
            usage = dev.phonerecall.odyssey.Odyssey.canUsage(app),
            health = hc,
            exactAlarms = app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
            dnd = app.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true,
            installs = app.packageManager.canRequestPackageInstalls(),
            battery = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            keys = buildSet {
                if (core.vault.has()) add("anthropic")
                if (dev.phonerecall.voice.geminiKey(app).has()) add("gemini")
                if (dev.phonerecall.lemnos.hetznerToken(app).has()) add("hetzner")
                if (dev.phonerecall.odyssey.tomTomKey(app).has()) add("tomtom")
                if (dev.phonerecall.odyssey.ny511Key(app).has()) add("ny511")
            },
            paired = DeviceKeys.paired(app),
            spotify = runCatching { dev.phonerecall.music.MusicCore.get(app).state.value.connected }.getOrDefault(false),
            home = when (link) {
                dev.phonerecall.hestia.Link.Connected -> HomeFact.CONNECTED
                dev.phonerecall.hestia.Link.NeedsPermission -> HomeFact.NEEDS_PERMISSION
                is dev.phonerecall.hestia.Link.NotConnected -> HomeFact.NOT_CONNECTED
                else -> HomeFact.STARTING
            },
            homeReason = (link as? dev.phonerecall.hestia.Link.NotConnected)?.reason,
            googleBirthdays = r.googleBirthdays,
            backupSetUp = mn.setUp,
            backupAt = mn.last?.at,
            backupOk = mn.last?.ok,
            indexedAt = host.kv.get("alexandria.last")?.toLongOrNull(),
            passages = runCatching { host.alexandria.chunkCount() }.getOrDefault(0),
            embedded = runCatching { host.alexandria.vectorCount() }.getOrDefault(0),
            model = dev.phonerecall.memory.GemmaEmbedder.model(app),
            odysseyOn = od?.settings?.on == true,
            odysseyPaused = od?.settings?.paused == true,
            homePlace = od?.home() != null,
            officePlace = od?.work() != null,
            theogonyDone = th.phase == dev.phonerecall.theogony.Phase.DONE,
            asked = th.asked,
            total = th.total,
            mindSetUp = runCatching { host.mind.ids.agent(dev.phonerecall.mind.MindPrompts.ATHENA_NAME) != null }.getOrDefault(false),
            lastSync = host.kv.get("sync.last")?.toLongOrNull(),
            heartbeat = dev.phonerecall.mind.HeartbeatStore(core.mindKv).on,
            iris = r.iris,
            agora = r.agora,
        )
    }

    /** The gateway and Google. A failure to reach either is "not known", never "missing". */
    private fun remote(): Remote {
        val paired = DeviceKeys.paired(app)
        val iris = if (!paired) null else runCatching { Gateway.agents().firstOrNull { it.id == "iris" }?.channel }.getOrNull()
        val agora = if (!paired) null else runCatching { Gateway.history("agora", limit = 1); true }.getOrNull()
        val google = runCatching { dev.phonerecall.data.GoogleCalendarAuth(app).token() != null }.getOrNull()
        return Remote(iris, agora, google)
    }

    // ---- self-tests ----

    fun run(id: String) {
        val t = tests().firstOrNull { it.id == id } ?: return
        if (id in _state.value.running) return
        _state.update { it.copy(running = it.running + id) }
        scope.launch {
            val r = Rite.run(t) { test, res -> Clio.log("setup", if (res.pass) Level.INFO else Level.WARN, "test ${test.id} ${if (res.pass) "passed" else "failed"}", "${res.reason.take(120)} · ${res.ms} ms") }
            _state.update { it.copy(results = it.results + (id to r), running = it.running - id) }
        }
    }

    fun runAll() = scope.launch { for (t in tests()) { run(t.id); while (t.id in _state.value.running) delay(200) } }

    fun tests(): List<SelfTest> = listOf(
        SelfTest("athena", "Athena", "Sends Delphi's model a one-word ping and shows the reply") {
            val m = BrainCore.get(app).haiku() ?: throw TestFailed("No Anthropic key in Calypso")
            val reply = m.complete("selftest", "You are Athena, the owner's assistant in Zeus. This is a setup self-test.", "Ping. Reply with one short friendly word.", 20)
            if (reply.isBlank()) throw TestFailed("She answered with nothing") else "She said: ${reply.take(80)}"
        },
        SelfTest("iris", "Iris", "Asks Iris one harmless read-only question and waits for her answer") {
            if (!DeviceKeys.paired(app)) throw TestFailed("Lemnos isn't paired")
            dev.phonerecall.mind.AskHub.held(app, "iris")?.let { throw TestFailed(it) }
            // A no-op owner, so the hub's own delivery leaves this answer alone.
            dev.phonerecall.mind.AskHub.on("setup") { _, _ -> }
            val p = dev.phonerecall.mind.AskHub.get(app).ask("iris", "selftest", "Setup self-test from Zeus: what day is it where you are? One line.", owner = "setup:${UUID.randomUUID()}").getOrElse { throw TestFailed(it.message ?: "Not asked") }
            val text = withTimeoutOrNull(85_000L) {
                var t: String? = null
                while (t == null) { delay(5_000); t = runCatching { dev.phonerecall.mind.GatewayAskTransport.replyTo("iris", p.messageId) }.getOrNull() }
                t
            } ?: throw TestFailed("Asked; no answer in 85 s. It still arrives in Agora.")
            "Iris: " + dev.phonerecall.mind.AskEnvelope.parse(text, p.id).lines().joinToString(" ").take(120)
        },
        SelfTest("alexandria", "Alexandria", "Searches the index for \"flight\"") {
            val hits = BrainCore.get(app).mindHost.alexandria.search("flight", 3, forAgent = false)
            if (BrainCore.get(app).mindHost.alexandria.chunkCount() == 0) throw TestFailed("Nothing is indexed yet")
            if (hits.isEmpty()) "Searched; nothing mentions a flight" else "${hits.size} found, first from ${hits.first().source.label}"
        },
        SelfTest("spotify", "Spotify", "Reads what is playing now") {
            val music = dev.phonerecall.music.MusicCore.get(app)
            if (!music.state.value.connected) throw TestFailed("Spotify isn't connected")
            music.refresh()
            music.now.value?.let { "Now playing: ${it.title}" + if (it.playing) "" else " (paused)" } ?: "Read fine; nothing is playing"
        },
        SelfTest("hestia", "Hestia", "Lists the devices Google Home shares") {
            val s = dev.phonerecall.hestia.HestiaCore.get(app).snapshot
            when (val l = s.link) {
                dev.phonerecall.hestia.Link.Connected -> "${s.devices.size} devices in ${s.rooms.size} rooms"
                dev.phonerecall.hestia.Link.NeedsPermission -> throw TestFailed("Zeus isn't allowed in Google Home yet")
                is dev.phonerecall.hestia.Link.NotConnected -> throw TestFailed(l.reason)
                else -> throw TestFailed("Still connecting to Google Home")
            }
        },
        SelfTest("odyssey-fix", "Odyssey", "Gets one location fix (the accuracy only is shown)") { fix() },
        SelfTest("weather", "Aeolia", "Fetches the forecast for home") {
            val (lat, lng) = homeOrDefault()
            val f = dev.phonerecall.aeolia.WeatherParse.forecast(dev.phonerecall.aeolia.WeatherNet.text(dev.phonerecall.aeolia.WeatherNet.forecastUrl(lat, lng)))
                ?: throw TestFailed("The forecast didn't parse")
            "Now ${f.tempC?.let { dev.phonerecall.aeolia.WeatherCards.pair(it) } ?: "no temperature"}, ${f.days.size} days ahead"
        },
        SelfTest("tomtom", "TomTom", "Times the drive from home to the office") {
            val d = dev.phonerecall.odyssey.OdysseyStore.load(app)
            val home = d.home() ?: throw TestFailed("Home isn't marked in Odyssey")
            val work = d.work() ?: throw TestFailed("The office isn't marked in Odyssey")
            val key = dev.phonerecall.odyssey.tomTomKey(app).read() ?: throw TestFailed("No TomTom key in Calypso")
            val r = dev.phonerecall.odyssey.TomTom.parse(dev.phonerecall.odyssey.Odyssey.get(dev.phonerecall.odyssey.TomTom.routeUrl(home.lat, home.lng, work.lat, work.lng, key))).firstOrNull()
                ?: throw TestFailed("TomTom returned no route")
            "${(r.seconds + 59) / 60} min now" + if (r.delaySeconds >= 60) ", ${r.delaySeconds / 60} min of it traffic" else ", no traffic delay"
        },
        SelfTest("moneta", "Moneta", "Parses a sample bank notification, offline") {
            val t = dev.phonerecall.moneta.BankParser.parse("com.chase.sig.android", "", "Chase Sapphire", "You made a $47.82 transaction with WHOLEFDS SPR 10234 on Oct 19, 2026 at 6:12 PM ET.", System.currentTimeMillis())
                ?: throw TestFailed("The sample didn't parse")
            if (t.cents != 4782L || t.merchant != "Whole Foods") throw TestFailed("Read ${t.cents} cents at ${t.merchant}")
            "$47.82 at Whole Foods, ${t.category.name.lowercase()}"
        },
        SelfTest("hermes", "Hermes", "Parses \"set alarm 6:30\" without setting it") {
            val c = dev.phonerecall.hermes.Commands.parse("set alarm 6:30") ?: throw TestFailed("Not understood")
            if (c !is dev.phonerecall.hermes.AlarmCmd || c.hour != 6 || c.minute != 30) throw TestFailed("Read it as $c")
            "An alarm at 6:30 (not set)"
        },
        SelfTest("praxis", "Praxis", "Adds a test task and removes it") {
            val tasks = BrainCore.get(app).tasks
            val id = tasks.add("Zeus setup self-test")
            val there = tasks.load().tasks.any { it.id == id }
            tasks.delete(id)
            if (!there) throw TestFailed("The task didn't save")
            if (tasks.load().tasks.any { it.id == id }) throw TestFailed("The task wasn't removed")
            "Added and removed"
        },
        SelfTest("clio", "Clio", "Writes a line to the log and reads it back") {
            val log = Clio.get() ?: throw TestFailed("Clio isn't running")
            val mark = "self-test ${UUID.randomUUID().toString().take(8)}"
            val since = System.currentTimeMillis() - 1
            log.append("setup", Level.INFO, mark)
            if (log.lines(since).none { it.event == mark }) throw TestFailed("Wrote it but couldn't read it back")
            "Wrote and read one line"
        },
        SelfTest("widgets", "Widgets", "Counts the widgets on your home screens") {
            val placed = dev.phonerecall.widgets.WidgetBus.placed(app).filter { it.second > 0 }
            if (placed.isEmpty()) "None placed yet" else "${placed.sumOf { it.second }} placed: " + placed.joinToString(", ") { "${it.second} ${it.first.name.lowercase()}" }
        },
    )

    private fun homeOrDefault(): Pair<Double, Double> =
        dev.phonerecall.odyssey.OdysseyStore.load(app).home()?.let { it.lat to it.lng } ?: (39.100 to -94.578)   // a central US default until home is marked

    @SuppressLint("MissingPermission")
    private fun fix(): String {
        if (!granted(Perm.FINE)) throw TestFailed("Location isn't allowed")
        val l = com.google.android.gms.tasks.Tasks.await(
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(app)
                .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, com.google.android.gms.tasks.CancellationTokenSource().token),
            30, TimeUnit.SECONDS,
        ) ?: throw TestFailed("No fix right now")
        return "A fix within ${l.accuracy.toInt()} m"
    }

    companion object {
        @Volatile private var instance: TeleteHub? = null
        fun get(c: Context): TeleteHub = instance ?: synchronized(this) { instance ?: TeleteHub(c.applicationContext).also { instance = it } }

        /** The intent for a system screen, the app's own page where there is one. */
        @SuppressLint("BatteryLife")
        fun intent(c: Context, s: Screen): Intent {
            val pkg = Uri.parse("package:${c.packageName}")
            return when (s) {
                Screen.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, android.content.ComponentName(c, dev.phonerecall.brain.SensesListener::class.java).flattenToString())
                Screen.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                Screen.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                Screen.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                Screen.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                Screen.INSTALL_PACKAGES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
                Screen.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Opens a system screen; when that one isn't there, the app's settings page. */
        fun open(c: Context, s: Screen) {
            runCatching { c.startActivity(intent(c, s)) }.recoverCatching {
                c.startActivity(if (s == Screen.NOTIFICATION_LISTENER) Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent(c, Screen.APP_DETAILS))
            }
        }
    }
}
