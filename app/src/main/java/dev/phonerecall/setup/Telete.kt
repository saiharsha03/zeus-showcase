package dev.phonerecall.setup

import dev.phonerecall.memory.EmbedModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Where a check stands. Only [MISSING] keeps Telete in the sidebar: optional and not-known-yet don't. */
enum class Status { OK, MISSING, OPTIONAL, UNKNOWN }

/** The system screens a fix can open (TeleteAndroid maps each to its intent). */
enum class Screen { NOTIFICATION_LISTENER, USAGE_ACCESS, BATTERY, EXACT_ALARMS, DND, INSTALL_PACKAGES, APP_DETAILS }

/** What a check's fix button does. */
sealed interface Fix {
    /** Android's own permission sheet (the app's settings page when it won't show again). */
    data class Ask(val permissions: List<String>) : Fix
    data class System(val screen: Screen) : Fix
    /** A Zeus destination, by [dev.phonerecall.Dest] name. */
    data class Open(val dest: String) : Fix
    /** A page inside Themis ("mnemosyne", "music"). */
    data class Themis(val page: String) : Fix
    data object GoogleCalendar : Fix
    data object IndexNow : Fix
    data object HeartbeatOn : Fix
    data object SyncMind : Fix
}

data class Check(val id: String, val group: String, val label: String, val why: String, val status: Status, val detail: String = "", val fix: Fix? = null, val fixLabel: String = "Fix", val test: String? = null)

enum class HcFact { NOT_INSTALLED, NONE, PARTIAL, READY }
enum class HomeFact { CONNECTED, NEEDS_PERMISSION, STARTING, NOT_CONNECTED }

/** Runtime permissions Telete checks, by their Manifest names (kept here so the pure part needs no Android). */
object Perm {
    const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
    const val ACTIVITY = "android.permission.ACTIVITY_RECOGNITION"
    const val CALL_LOG = "android.permission.READ_CALL_LOG"
    const val CONTACTS = "android.permission.READ_CONTACTS"
    const val CALENDAR = "android.permission.READ_CALENDAR"
    const val MIC = "android.permission.RECORD_AUDIO"
    const val NOTIFY = "android.permission.POST_NOTIFICATIONS"
}

/**
 * Everything the checks read, gathered at once (TeleteAndroid on the phone, plain values in tests). A null is
 * "not known yet" (the gateway didn't answer, Google hasn't said), never "missing".
 */
data class Facts(
    val granted: Set<String> = emptySet(),
    val listener: Boolean = false,
    val usage: Boolean = false,
    val health: HcFact = HcFact.NONE,
    val exactAlarms: Boolean = false,
    val dnd: Boolean = false,
    val installs: Boolean = false,
    val battery: Boolean = false,
    val samsung: Boolean = false,
    /** Calypso slots that hold something, by name only: anthropic, gemini, hetzner, tomtom, ny511. */
    val keys: Set<String> = emptySet(),
    val paired: Boolean = false,
    val spotify: Boolean = false,
    val home: HomeFact = HomeFact.STARTING,
    val homeReason: String? = null,
    val googleBirthdays: Boolean? = null,
    val backupSetUp: Boolean = false,
    val backupAt: Long? = null,
    val backupOk: Boolean? = null,
    val indexedAt: Long? = null,
    val passages: Int = 0,
    val embedded: Int = 0,
    val model: EmbedModel = EmbedModel.MISSING,
    val odysseyOn: Boolean = false,
    val odysseyPaused: Boolean = false,
    val homePlace: Boolean = false,
    val officePlace: Boolean = false,
    val theogonyDone: Boolean = false,
    val asked: Int = 0,
    val total: Int = 0,
    val mindSetUp: Boolean = false,
    val lastSync: Long? = null,
    val heartbeat: Boolean = false,
    /** Iris's channel as the gateway's /api/agents says it: connected, offline, not-enabled, tmux. */
    val iris: String? = null,
    val agora: Boolean? = null,
)

/**
 * Telete (Setup): what Zeus still needs, one check per thing, each with a one-line why and the fix that opens
 * the exact screen. Pure: [checks] reads [Facts] only.
 */
object Telete {
    const val PHONE = "Phone"
    const val KEYS = "Keys in Calypso"
    const val LINKS = "Connections"
    const val DATA = "Memory and backup"
    const val PLACES = "Places"
    const val ATHENA = "Athena"
    const val AGENTS = "Agents"

    private const val DAY = 86_400_000L

    fun checks(f: Facts, now: Long): List<Check> {
        fun has(p: String) = p in f.granted
        fun ok(b: Boolean) = if (b) Status.OK else Status.MISSING
        fun perm(id: String, label: String, why: String, p: String, test: String? = null) =
            Check(id, PHONE, label, why, ok(has(p)), if (has(p)) "" else "Not allowed yet", Fix.Ask(listOf(p)), "Allow", test)
        fun key(slot: String, label: String, why: String, optional: Boolean = false) = Check(
            "key-$slot", KEYS, label, why,
            if (slot in f.keys) Status.OK else if (optional) Status.OPTIONAL else Status.MISSING,
            if (slot in f.keys) "In Calypso" else "Not in Calypso", Fix.Open("VAULT"), "Add key",
        )
        val out = ArrayList<Check>()
        out += Check(
            "location", PHONE, "Location", "Odyssey's places and drives, and the weather where you are",
            ok(has(Perm.FINE)), if (has(Perm.FINE)) "" else "Not allowed yet", Fix.Ask(listOf(Perm.FINE)), "Allow", "odyssey-fix",
        )
        out += Check(
            "background-location", PHONE, "Location all the time", "So a stay is noted when Zeus is closed",
            if (!has(Perm.FINE)) Status.MISSING else ok(has(Perm.BACKGROUND)),
            if (has(Perm.BACKGROUND)) "" else if (!has(Perm.FINE)) "Allow Location first" else "Set Location to Allow all the time",
            Fix.Ask(listOf(if (has(Perm.FINE)) Perm.BACKGROUND else Perm.FINE)), "Allow",
        )
        out += perm("activity", "Physical activity", "Tells walking from driving, for the driving report", Perm.ACTIVITY)
        out += Check(
            "listener", PHONE, "Notification senses", "Cards, birthdays, Moneta's spending and who to answer first",
            ok(f.listener), if (f.listener) "" else "Notification access is off", Fix.System(Screen.NOTIFICATION_LISTENER), "Open",
        )
        out += Check("usage", PHONE, "Usage access", "Screen time in Eos and the rhythms of your day", ok(f.usage), if (f.usage) "" else "Usage access is off", Fix.System(Screen.USAGE_ACCESS), "Open")
        out += perm("call-log", "Call log", "Who you call home and when, for Horae", Perm.CALL_LOG)
        out += perm("contacts", "Contacts", "First names, birthdays and relations for People", Perm.CONTACTS)
        out += perm("calendar", "Calendar", "Your next event on Home and in Athena's day", Perm.CALENDAR)
        out += perm("mic", "Microphone", "Speak in Parnassus and talk to Athena", Perm.MIC)
        out += perm("notify", "Notifications", "The Now Bar, nudges and Athena's notes", Perm.NOTIFY)
        out += Check(
            "health", PHONE, "Health Connect", "Iaso's steps, sleep, heart rate and weight",
            when (f.health) { HcFact.READY -> Status.OK; else -> Status.MISSING },
            when (f.health) { HcFact.READY -> ""; HcFact.PARTIAL -> "Some kinds are still off"; HcFact.NONE -> "Not allowed yet"; HcFact.NOT_INSTALLED -> "Health Connect isn't on the phone" },
            Fix.Open("HEALTH"), "Open Iaso",
        )
        out += Check("exact-alarms", PHONE, "Exact alarms", "Alarms, the brief and bedtime nudges on the minute", ok(f.exactAlarms), if (f.exactAlarms) "" else "Alarms may run late", Fix.System(Screen.EXACT_ALARMS), "Open")
        out += Check("dnd", PHONE, "Do Not Disturb access", "So Hermes can turn Do Not Disturb on and off", ok(f.dnd), if (f.dnd) "" else "Not allowed yet", Fix.System(Screen.DND), "Open")
        out += Check("installs", PHONE, "Install updates", "Prometheus installs the builds it makes", ok(f.installs), if (f.installs) "" else "Not allowed yet", Fix.System(Screen.INSTALL_PACKAGES), "Open")
        out += Check(
            "battery", PHONE, "Unrestricted background", "Heartbeat, syncs and Odyssey keep running when the screen is off",
            ok(f.battery),
            if (f.battery) "" else if (f.samsung) "Set Battery to Unrestricted. On Samsung also add Zeus to Never sleeping apps (Battery, Background usage limits)." else "Set Battery to Unrestricted",
            Fix.System(Screen.BATTERY), "Open",
        )
        out += key("anthropic", "Anthropic", "Athena's mind and Delphi").copy(test = "athena")
        out += key("gemini", "Gemini", "Live voice")
        out += key("hetzner", "Hetzner", "Lemnos's server page and its storage box")
        out += key("tomtom", "TomTom", "Traffic and leave-by times").copy(test = "tomtom")
        out += key("ny511", "511NY", "Road closures on your routes (optional)", optional = true)
        out += Check("lemnos", LINKS, "Lemnos paired", "The server: Iris, Atlas, Agora, backups", ok(f.paired), if (f.paired) "" else "Not paired", Fix.Open("VPS"), "Pair")
        out += Check("spotify", LINKS, "Spotify", "Music by context and while driving", ok(f.spotify), if (f.spotify) "" else "Not connected", Fix.Themis("music"), "Connect", "spotify")
        out += Check(
            "hestia", LINKS, "Google Home", "Hestia's lights and plugs",
            when (f.home) { HomeFact.CONNECTED -> Status.OK; HomeFact.STARTING -> Status.UNKNOWN; else -> Status.MISSING },
            when (f.home) { HomeFact.CONNECTED -> ""; HomeFact.STARTING -> "Still asking Google"; HomeFact.NEEDS_PERMISSION -> "Allow Zeus in Google Home"; HomeFact.NOT_CONNECTED -> f.homeReason ?: "Not connected" },
            Fix.Open("DEVICES"), "Connect", "hestia",
        )
        out += Check(
            "google-birthdays", LINKS, "Google Calendar birthdays", "Birthdays saved once per person, in your calendar",
            when (f.googleBirthdays) { true -> Status.OK; false -> Status.MISSING; null -> Status.UNKNOWN },
            when (f.googleBirthdays) { true -> ""; false -> "Not allowed yet"; null -> "Not checked yet" },
            Fix.GoogleCalendar, "Allow",
        )
        val backupStale = f.backupAt == null || now - f.backupAt > 2 * DAY
        out += Check(
            "backup", DATA, "Mnemosyne backup", "A nightly backup locked with your recovery phrase",
            when { !f.backupSetUp -> Status.MISSING; f.backupOk == false || backupStale -> Status.MISSING; else -> Status.OK },
            when {
                !f.backupSetUp -> "Recovery phrase not set"
                f.backupAt == null -> "Phrase set. No backup has run yet."
                f.backupOk == false -> "The last backup failed"
                backupStale -> "The last backup is more than two days old"
                else -> ""
            },
            Fix.Themis("mnemosyne"), "Open",
        )
        val allEmbedded = f.passages > 0 && f.embedded >= f.passages
        out += Check(
            "alexandria", DATA, "Alexandria", "Search over your notes, chats and documents, by words and meaning",
            when { f.indexedAt == null || f.passages == 0 -> Status.MISSING; f.model == EmbedModel.READY && !allEmbedded -> Status.MISSING; else -> Status.OK },
            when {
                f.indexedAt == null -> "Not indexed yet"
                f.passages == 0 -> "Nothing indexed yet"
                f.model == EmbedModel.MISSING -> "${f.passages} passages, by words only (EmbeddingGemma isn't on the phone)"
                f.model == EmbedModel.FAILED -> "${f.passages} passages, by words only (EmbeddingGemma failed to load)"
                !allEmbedded -> "${f.embedded} of ${f.passages} passages embedded"
                else -> ""
            },
            Fix.IndexNow, "Index now", "alexandria",
        )
        out += Check(
            "odyssey", PLACES, "Odyssey tracking", "Learned places, commutes and the driving report",
            ok(f.odysseyOn && !f.odysseyPaused), if (!f.odysseyOn) "Off" else if (f.odysseyPaused) "Paused" else "", Fix.Open("ODYSSEY"), "Open",
        )
        out += Check(
            "home-office", PLACES, "Home and office", "Commute checks and leave-by times need both",
            ok(f.homePlace && f.officePlace),
            when { f.homePlace && f.officePlace -> ""; !f.homePlace && !f.officePlace -> "Neither is marked yet"; !f.homePlace -> "Home isn't marked yet"; else -> "Office isn't marked yet" },
            Fix.Open("ODYSSEY"), "Mark",
        )
        out += Check(
            "theogony", ATHENA, "Theogony", "What Athena knows about you from the first run",
            ok(f.theogonyDone), if (f.theogonyDone) "" else if (f.total > 0) "${f.asked} of ${f.total} asked" else "Not started", Fix.Open("THEOGONY"), "Continue",
        )
        val syncStale = f.lastSync == null || now - f.lastSync > DAY
        out += Check(
            "mind", ATHENA, "Athena's mind synced", "Her agents on Managed Agents, and her memory",
            when { "anthropic" !in f.keys -> Status.MISSING; !f.mindSetUp || syncStale -> Status.MISSING; else -> Status.OK },
            when { "anthropic" !in f.keys -> "Needs the Anthropic key"; !f.mindSetUp -> "Her agents aren't made yet"; syncStale -> "No sync in the last day"; else -> "" },
            if ("anthropic" !in f.keys) Fix.Open("VAULT") else Fix.SyncMind, if ("anthropic" !in f.keys) "Add key" else "Sync now",
        )
        out += Check("heartbeat", ATHENA, "Heartbeat", "Her hourly look at what changed", ok(f.heartbeat), if (f.heartbeat) "" else "Off", Fix.HeartbeatOn, "Turn on")
        out += Check(
            "iris", AGENTS, "Iris's channel", "Athena's questions reach Iris over it",
            when { !f.paired -> Status.MISSING; f.iris == null -> Status.UNKNOWN; f.iris == "connected" || f.iris == "tmux" -> Status.OK; else -> Status.MISSING },
            when { !f.paired -> "Pair Lemnos first"; f.iris == null -> "Lemnos didn't answer just now"; f.iris == "offline" -> "Enabled but not connected"; f.iris == "not-enabled" -> "Not enabled on the server"; else -> "" },
            Fix.Open(if (f.paired) "AGENTS" else "VPS"), "Open", "iris",
        )
        out += Check(
            "agora", AGENTS, "Agora", "The room where the agents meet",
            when { !f.paired -> Status.MISSING; f.agora == null -> Status.UNKNOWN; f.agora -> Status.OK; else -> Status.MISSING },
            when { !f.paired -> "Pair Lemnos first"; f.agora == null -> "Lemnos didn't answer just now"; f.agora -> ""; else -> "Agora didn't load" },
            Fix.Open(if (f.paired) "AGENTS" else "VPS"), "Open",
        )
        return out
    }

    /** What is still left (only MISSING counts). Zero moves Telete from the sidebar to Themis. */
    fun left(checks: List<Check>): Int = checks.count { it.status == Status.MISSING }

    /** "Setup status: …" for Delphi: one line per check that isn't ready. Names only, never a key or a value. */
    fun context(checks: List<Check>): String {
        val notReady = checks.filter { it.status != Status.OK }
        if (notReady.isEmpty()) return "Setup status: everything is ready."
        return "Setup status: ${left(checks)} left of ${checks.size}.\n" + notReady.joinToString("\n") { c ->
            "- ${c.label} (${c.group}): ${c.status.name.lowercase()}" + (if (c.detail.isNotBlank()) ", ${c.detail}" else "")
        }
    }

    const val ASK = "Help me finish setting up Zeus. Walk me through what's left, one thing at a time, and tell me exactly where to tap. If a fact is missing that Iris may know, ask her."
}

/** One self-test's outcome. */
data class TestResult(val pass: Boolean, val reason: String, val ms: Long, val at: Long)

/** The test failed for this reason (shown as is). */
class TestFailed(reason: String) : Exception(reason)

/**
 * A safe self-test: reads, or writes something it removes again. [run] returns the pass reason, or throws
 * (a [TestFailed] for a plain reason).
 */
class SelfTest(val id: String, val label: String, val what: String, val run: suspend () -> String)

object Rite {
    const val TIMEOUT = 90_000L

    /** Runs one test with a timeout. Never throws; every outcome goes to [log]. */
    suspend fun run(t: SelfTest, clock: () -> Long = System::currentTimeMillis, timeoutMs: Long = TIMEOUT, log: (SelfTest, TestResult) -> Unit = { _, _ -> }): TestResult {
        val start = clock()
        val r = try {
            val why = withTimeout(timeoutMs) { t.run() }
            TestResult(true, why, clock() - start, start)
        } catch (e: TimeoutCancellationException) {
            TestResult(false, "No answer in ${timeoutMs / 1000} s", clock() - start, start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TestFailed) {
            TestResult(false, e.message.orEmpty(), clock() - start, start)
        } catch (e: Throwable) {
            TestResult(false, e.message?.take(160) ?: e.javaClass.simpleName, clock() - start, start)
        }
        runCatching { log(t, r) }
        return r
    }
}
