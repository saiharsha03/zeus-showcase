package dev.phonerecall.fresh

import android.content.Context
import android.location.Geocoder
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.clio.Clio
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.mind.MindNote
import dev.phonerecall.mind.MindWork
import dev.phonerecall.mind.PrefsKv
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.SdkMindApi
import dev.phonerecall.mind.SealedHandoffs
import dev.phonerecall.mind.TopTierCaps
import dev.phonerecall.models.ModelChoice
import dev.phonerecall.odyssey.Geo
import dev.phonerecall.odyssey.Kind
import dev.phonerecall.odyssey.KnownPlace
import dev.phonerecall.odyssey.NameSource
import dev.phonerecall.odyssey.Odyssey
import dev.phonerecall.odyssey.OdysseyStore
import dev.phonerecall.odyssey.Visits
import dev.phonerecall.setup.Check
import dev.phonerecall.theogony.Gap
import dev.phonerecall.theogony.Phase
import dev.phonerecall.theogony.TheogonyHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** What Genesis's screen shows. */
data class GenesisState(
    val active: Boolean = false,
    val stage: Stage = Stage.SETUP,
    val rows: List<SetupRow> = emptyList(),
    val left: Int = 0,
    /** Annabeth is writing the questions. */
    val planning: Boolean = false,
    /** Who wrote the questions, once known: "Annabeth" or the built-in set and why. */
    val planNote: String? = null,
    /** Iris was asked (Theogony's seed is out). */
    val irisAsked: Boolean = false,
)

/**
 * Genesis on the phone: the engine over its own prefs, the setup rows from Telete, Iris asked as soon as the mind is
 * reachable, Annabeth's plan, the profile for Athena once the interview is done, and the shakedown. One per process.
 */
class GenesisHub private constructor(private val app: Context) {
    private val kv = PrefsKv(app, Genesis.PREFS)
    val engine = Genesis(kv)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(GenesisState(engine.active, engine.stage))
    val state: StateFlow<GenesisState> = _state.asStateFlow()
    @Volatile private var checks: List<Check> = emptyList()
    @Volatile private var gapsCache: List<Gap>? = null
    @Volatile private var mattersCache: Set<dev.phonerecall.brain.AppKind>? = null
    private val planning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val theo get() = TheogonyHub.get(app)

    fun gaps(): List<Gap> = gapsCache ?: engine.gaps().also { gapsCache = it }
    fun matters(): Set<dev.phonerecall.brain.AppKind> = mattersCache ?: engine.matters().also { mattersCache = it }
    fun saveMatters(k: Set<dev.phonerecall.brain.AppKind>) { engine.saveMatters(k); mattersCache = k }

    private fun publish() {
        val mn = dev.phonerecall.mnemosyne.Mnemosyne
        val rows = engine.rows(checks, runCatching { mn.setUpAt(app) }.getOrNull(), runCatching { mn.isSetUp(app) }.getOrDefault(false))
        _state.value = GenesisState(
            engine.active, engine.stage, rows, engine.left(rows), planning.get(), kv.get(K_NOTE),
            theo.engine.phase != Phase.NONE,
        )
    }

    /** Telete's checks changed (the screen passes them): rows again, and Iris asked once the mind is reachable. */
    fun update(checks: List<Check>) {
        this.checks = checks
        watch()
        askIris()
        publish()
    }

    fun skip(id: String) { engine.skip(id); publish() }
    fun unskip(id: String) { engine.unskip(id); publish() }

    /** As soon as Lemnos is paired and Athena has her key, Theogony's seed goes out: Iris answers while he sets up. */
    private fun askIris() {
        if (!engine.active || engine.stage > Stage.THEOGONY || theo.engine.phase != Phase.NONE) return
        val core = BrainCore.get(app)
        scope.launch {
            if (DeviceKeys.paired(app) && core.vault.has() && theo.engine.phase == Phase.NONE) {
                Clio.info("setup", "genesis asked Iris", "Theogony's seed started during setup")
                theo.start()
                publish()
            }
        }
    }

    /** The next step, with what each one starts. */
    fun next() = scope.launch {
        watch()
        val book = ModelChoice.book
        val from = engine.stage
        val now = engine.advance(book)
        Clio.info("setup", "genesis step", "${from.name.lowercase()} → ${now.name.lowercase()}")
        when (now) {
            Stage.THEOGONY -> { askIris(); if (theo.engine.phase == Phase.INTERVIEW || theo.engine.phase == Phase.DONE) plan() }
            Stage.SHAKEDOWN -> { populate(); dev.phonerecall.setup.ShakedownHub.get(app).start() }
            Stage.DONE -> MindWork.syncNow(app)
            else -> Unit
        }
        if (from == Stage.THEOGONY) MindWork.syncNow(app)
        publish()
    }

    /** Theogony's seed finished (Iris answered or the wait ran out): Annabeth plans the rest, once. */
    fun onTheogony(phase: Phase) {
        if (engine.active && engine.stage == Stage.THEOGONY && (phase == Phase.INTERVIEW || phase == Phase.DONE) && !engine.planned && !planning.get()) scope.launch { plan() }
    }

    private fun plan() {
        if (engine.planned || !planning.compareAndSet(false, true)) return
        publish()
        val r = runCatching { annabeth() }
        engine.savePlan(r.getOrNull())
        gapsCache = null
        val note = r.fold({ "Annabeth wrote ${it.size} questions" }, { "Built-in questions: ${it.message ?: it.javaClass.simpleName}" })
        kv.put(K_NOTE, note)
        Clio.info("setup", "genesis plan", note)
        planning.set(false)
        // Restore her model at once if Fable wasn't there: the rest of the month runs on her own.
        if (r.exceptionOrNull() is dev.phonerecall.mind.ModelUnavailable) engine.restoreAnnabeth(ModelChoice.book)
        theo.rereadPhone()
        theo.replan()
        publish()
    }

    /** Annabeth's questions, from her own session on Fable. Throws when she can't (no key, her cap, no answer). */
    private fun annabeth(): List<PlannedQuestion> {
        val core = BrainCore.get(app)
        check(core.vault.has()) { "Athena has no key yet" }
        val host = core.mindHost
        val (agent, env, store) = host.locked {
            check(TopTierCaps.allowed(circleCents(dev.phonerecall.mind.Metis.THREAD), circleCents(Protege.ANNABETH.agentName)).annabeth) { "Annabeth is at her monthly limit" }
            ensure()
            Triple(ids.agent(Protege.ANNABETH.agentName) ?: error("Annabeth isn't set up"), ids.environment ?: error("no environment"), ids.store ?: error("no memory store"))
        }
        val prompt = GenesisGaps.prompt(theo.engine.facts(), host.mind.sanitizer())
        val reply = AnnabethPlan(SdkMindApi({ core.vault.read() })).ask(agent, env, store, prompt)
        runCatching { host.locked { addCircle(Protege.ANNABETH.agentName, account(reply.sessionId)) } }
        return GenesisGaps.parse(reply.text) ?: error(reply.why ?: "her answer didn't read as questions")
    }

    /** Athena's start: the profile from his confirmed answers into her memory, her first session's handoff, a note now. */
    private fun populate() {
        if (engine.profiled) return
        val host = BrainCore.get(app).mindHost
        val profile = GenesisGaps.profile(theo.engine.facts(), host.mind.sanitizer())
        kv.put(K_PROFILE, profile)
        engine.profiled = true
        runCatching { SealedHandoffs(host.kv).save(host.mind.day(), "Fresh start: Zeus was set up again from nothing; there is no handoff from before.\n$profile") }
        if (host.mind.currentSession() != null) host.offer(MindNote(text = "The owner finished the fresh start's setup and interview. His profile is in $PROFILE_PATH; read it before anything else.", high = true, key = "genesis"))
        Clio.info("setup", "genesis profile", "${profile.lines().count { it.startsWith("- ") }} lines for Athena")
    }

    /** The profile as a memory file for her (the mind's sync uploads it). */
    fun files(): Map<String, String> = kv.get(K_PROFILE)?.let { mapOf(PROFILE_PATH to it) }.orEmpty()

    /** The splash is done: Home from here on. */
    fun finish() = next()

    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Theogony's phase, followed once Genesis is on screen (not at creation: Senses reads this hub too). */
    private fun watch() { if (watching.compareAndSet(false, true)) scope.launch { theo.state.collect { onTheogony(it.phase) } } }

    companion object {
        const val PROFILE_PATH = "/profile/fresh-start.md"
        private const val K_NOTE = "plan.note"
        private const val K_PROFILE = "profile"
        @Volatile private var instance: GenesisHub? = null
        fun get(c: Context): GenesisHub = instance ?: synchronized(this) { instance ?: GenesisHub(c.applicationContext).also { instance = it } }

        /** Cheap: whether Home is Genesis now. Reads the prefs only. */
        fun active(c: Context): Boolean = Genesis(PrefsKv(c.applicationContext, Genesis.PREFS)).active
    }
}

/**
 * His interview answers into the stores that use them. Home and the office become Odyssey's places (a fix now, or
 * the address on the phone's Geocoder), his numbers are sealed on the phone, "always matter" sets Senses' floor, teams
 * become Olympia's follows, the car goes on the lease. Nothing here leaves the phone; Clio gets the topic only.
 */
object GenesisRoutes {
    fun apply(c: Context, g: Gap, words: String) {
        val t = GenesisGaps.topicOf(g.id) ?: return
        val w = words.trim()
        if (w.startsWith("Ask me") || w.isEmpty()) return
        runCatching {
            when (t.id) {
                "home" -> place(c, Kind.HOME, w)
                "office" -> place(c, Kind.WORK, w)
                "phone" -> numbers(c, w)
                "notify_matters" -> GenesisHub.get(c).saveMatters(SenseRules.parse(w))
                "sports" -> teams(c, w)
                "car" -> car(c, w)
                else -> Unit
            }
            Clio.info("setup", "genesis answer filed", t.id)
        }.onFailure { Clio.error("setup", it) }
    }

    private fun place(c: Context, kind: Kind, words: String) {
        if (words.startsWith("I'm")) {
            dev.phonerecall.places.Places.here(c, { lat, lng -> Thread { pin(c, kind, lat, lng) }.start() }) { why -> Clio.warn("setup", "genesis place not marked", "${kind.label}: $why") }
            return
        }
        @Suppress("DEPRECATION")
        val a = runCatching { Geocoder(c, Locale.US).getFromLocationName(words, 1)?.firstOrNull() }.getOrNull()
        if (a == null) { Clio.warn("setup", "genesis place not marked", "${kind.label}: the address wasn't found"); return }
        pin(c, kind, a.latitude, a.longitude)
    }

    /** One Home and one office: a new one replaces the old of its kind. */
    fun pin(c: Context, kind: Kind, lat: Double, lng: Double) {
        val street = runCatching { Odyssey.geocode(c, lat, lng).second }.getOrNull()
        OdysseyStore.update(c) { d ->
            val near = d.places.firstOrNull { Geo.meters(it.lat, it.lng, lat, lng) < Visits.SAME_PLACE_M }
            val p = (near ?: KnownPlace("k${d.nextId}", lat = lat, lng = lng)).copy(name = kind.label, source = NameSource.HIM, kind = kind, street = street ?: near?.street)
            d.copy(places = d.places.filter { it.id != p.id && it.kind != kind } + p, nextId = d.nextId + 1, settings = d.settings.copy(on = true))
        }
        runCatching { dev.phonerecall.places.Places.register(c) }
        BrainCore.get(c).changed()
    }

    private fun numbers(c: Context, words: String) {
        val nums = words.split(';', ',').map { it.filter { ch -> ch.isDigit() || ch == '+' } }.filter { n -> n.count(Char::isDigit) >= 10 }
        if (nums.isEmpty()) return
        c.getSharedPreferences(Genesis.PREFS, Context.MODE_PRIVATE).edit().putString(NUMBERS, KeystoreSealer("zeus.aegis.people").seal(nums.joinToString("\n"))).apply()
    }

    /** His own numbers, opened on the phone (Calypso-level privacy: never sent anywhere). */
    fun myNumbers(c: Context): List<String> = c.getSharedPreferences(Genesis.PREFS, Context.MODE_PRIVATE).getString(NUMBERS, null)
        ?.let { KeystoreSealer("zeus.aegis.people").open(it) }?.lines()?.filter { it.isNotBlank() }.orEmpty()

    private fun teams(c: Context, words: String) {
        val names = dev.phonerecall.theogony.Interview.parts(words).map { it.lowercase() }
        val keys = dev.phonerecall.olympia.Teams.all.filter { t -> names.any { it == t.name.lowercase() || it == t.short.lowercase() } }.map { it.key }.toSet()
        if (keys.isNotEmpty()) dev.phonerecall.olympia.OlympiaStore.settings(c) { it.copy(followed = keys) }
    }

    private fun car(c: Context, words: String) {
        if (words in setOf("Leased", "Owned", "No car")) return
        OdysseyStore.update(c) { d -> d.copy(car = d.car.copy(lease = d.car.lease.copy(car = words.take(60)))) }
    }

    private const val NUMBERS = "me.numbers"
}
