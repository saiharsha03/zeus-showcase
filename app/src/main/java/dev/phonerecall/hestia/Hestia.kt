package dev.phonerecall.hestia

import android.content.Context
import androidx.activity.ComponentActivity
import dev.phonerecall.brain.ExecResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/** What Delphi's read tool and the proposal validator see. */
interface HomeReads {
    val snapshot: HomeSnapshot
    fun status(): Map<String, Any?>
}

/** What a confirmed home_control (or Lights) step does. Fates calls it after it revalidated the step. */
interface HomeHands {
    fun control(payload: Map<String, String>): ExecResult
    fun restore(undo: Map<String, String>): Boolean
}

/**
 * Hestia for the whole process: one backend, one live snapshot. The owner's taps on the Hestia screen go
 * straight to [tap]; anything a model asks for arrives only as a confirmed proposal through [control].
 * Both refuse forbidden kinds, so nothing in Zeus can open a lock or a garage door.
 */
class HestiaCore(val backend: HomeBackend) : HomeReads, HomeHands {
    val state: StateFlow<HomeSnapshot> get() = backend.state
    override val snapshot: HomeSnapshot get() = backend.state.value

    override fun status(): Map<String, Any?> = HomeRules.status(snapshot, whereabouts.away())

    /** A tap on the screen. Returns null when done, else the words to show. */
    suspend fun tap(deviceId: String, command: HomeCommand): String? {
        val d = snapshot.device(deviceId) ?: return "That device is gone"
        if (d.kind.forbidden) return HomeRules.FORBIDDEN
        if (!d.controllable) return "Hestia doesn't control ${d.kind.plural} yet"
        return try {
            backend.command(d.id, command)
            runCatching { tapped(d, command, snapshot) }
            null
        } catch (e: HomeError) { e.message } catch (e: Exception) { e.message ?: "It didn't work" }
    }

    override fun control(payload: Map<String, String>): ExecResult {
        val d = snapshot.device(payload.getValue("device_id")) ?: throw HomeError("${payload["device"] ?: "That device"} isn't in Google Home any more")
        if (d.kind.forbidden) throw HomeError(HomeRules.FORBIDDEN)
        val cmd = HomeRules.command(payload)
        val undo = HomeRules.undo(d, payload)
        runBlocking { backend.command(d.id, cmd) }
        return ExecResult(when (cmd) {
            is HomeCommand.Power -> "${d.name} ${if (cmd.on) "on" else "off"}"
            is HomeCommand.Brightness -> "${d.name} at ${cmd.percent}%"
            is HomeCommand.ColorTemp -> "${d.name} at ${cmd.kelvin} K"
            is HomeCommand.Color -> "${d.name} ${payload["value"]}"
        }, undo)
    }

    override fun restore(undo: Map<String, String>): Boolean = runCatching { control(undo) }.isSuccess

    companion object {
        /** Odyssey's "is he home?". Set by the app; Hestia never imports Odyssey. Null answers mean not known. */
        @Volatile var whereabouts: Whereabouts = Whereabouts { null }

        /** Each tap that worked (device as it was, the command, the home): the routines feed it to the learned-shortcuts evidence. */
        @Volatile var tapped: (HomeDevice, HomeCommand, HomeSnapshot) -> Unit = { _, _, _ -> }

        @Volatile private var instance: HestiaCore? = null
        private var google: GoogleHomeBackend? = null

        fun get(context: Context): HestiaCore = instance ?: synchronized(this) {
            instance ?: GoogleHomeBackend(context.applicationContext).let { g -> google = g; HestiaCore(g).also { instance = it } }
        }

        /** Tests and shots: a backend of their own. */
        fun install(core: HestiaCore?) = synchronized(this) { instance = core; google = null }

        /** From MainActivity.onCreate: the Home APIs' consent screen needs the activity before it starts. */
        fun attach(activity: ComponentActivity) {
            val core = get(activity)
            runCatching { google?.register(activity) }
            core.backend.start()
        }
    }
}

/** Made-up devices for tests and shots. Commands change the state like a real home would. */
class FakeHomeBackend(initial: HomeSnapshot = sample()) : HomeBackend {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<HomeSnapshot> = _state.asStateFlow()
    val sent = mutableListOf<Pair<String, HomeCommand>>()
    var permissionResult: String? = null

    override fun start() = Unit
    override suspend fun requestPermission(): String? = permissionResult.also { if (it == null) _state.value = _state.value.copy(link = Link.Connected) }

    override suspend fun command(deviceId: String, command: HomeCommand) {
        val d = _state.value.device(deviceId) ?: throw HomeError("No such device")
        if (d.kind.forbidden) throw HomeError(HomeRules.FORBIDDEN)
        sent += deviceId to command
        val next = when (command) {
            is HomeCommand.Power -> d.copy(on = command.on)
            is HomeCommand.Brightness -> d.copy(on = true, level = command.percent)
            is HomeCommand.ColorTemp -> d.copy(kelvin = command.kelvin)
            is HomeCommand.Color -> d.copy(hue = command.hue, saturation = command.saturation)
        }
        _state.value = _state.value.let { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) next else it }) }
    }

    fun set(s: HomeSnapshot) { _state.value = s }

    companion object {
        fun sample(link: Link = Link.Connected): HomeSnapshot {
            val home = Structure("s1", "Maple Street apartment")
            val rooms = listOf(Room("r1", "Living room", "s1"), Room("r2", "Bedroom", "s1"), Room("r3", "Kitchen", "s1"), Room("r4", "Entry", "s1"))
            val light = setOf(Trait.ON_OFF, Trait.LEVEL, Trait.COLOR_TEMP)
            val devices = listOf(
                HomeDevice("d1", "Floor lamp", DeviceKind.LIGHT, "r1", "s1", traits = light + Trait.COLOR, on = true, level = 70, kelvin = 2700, hue = 30, saturation = 40),
                HomeDevice("d2", "TV backlight", DeviceKind.LIGHT, "r1", "s1", traits = setOf(Trait.ON_OFF, Trait.LEVEL, Trait.COLOR), on = true, level = 35, hue = 225, saturation = 100),
                HomeDevice("d3", "Air purifier plug", DeviceKind.PLUG, "r1", "s1", traits = setOf(Trait.ON_OFF), on = false),
                HomeDevice("d4", "Bedside lamp", DeviceKind.LIGHT, "r2", "s1", traits = light, on = true, level = 20, kelvin = 2200, minKelvin = 2200),
                HomeDevice("d5", "Ceiling light", DeviceKind.LIGHT, "r2", "s1", traits = setOf(Trait.ON_OFF, Trait.LEVEL), on = false, level = 100),
                HomeDevice("d6", "Under-cabinet strip", DeviceKind.LIGHT, "r3", "s1", traits = light, on = false, level = 80, kelvin = 4000),
                HomeDevice("d7", "Kettle plug", DeviceKind.PLUG, "r3", "s1", traits = setOf(Trait.ON_OFF), online = false),
                HomeDevice("d8", "Front door", DeviceKind.LOCK, "r4", "s1"),
                HomeDevice("d9", "Porch light", DeviceKind.LIGHT, "r4", "s1", traits = setOf(Trait.ON_OFF), on = false),
            )
            val autos = listOf(
                Automation("a1", "Good morning", "Bedside lamp to 40% at 7:00 on weekdays", true, "s1"),
                Automation("a2", "Sunset", "Porch light on at sunset", true, "s1"),
                Automation("a3", "Away", "Everything off when the last person leaves", false, "s1"),
            )
            return HomeSnapshot(link, listOf(home), rooms, devices, autos, System.currentTimeMillis())
        }
    }
}

/** The switches he put on the Hestia widget (hold a tile in Hestia), in his order, at most four. On the phone only. */
object HestiaPicks {
    private const val KEY = "widget.picks"
    const val MAX = 4

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("hestia", Context.MODE_PRIVATE)

    fun load(c: Context): List<String> = sp(c).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Adds or removes one; a fifth pick drops the oldest. */
    fun toggle(picks: List<String>, id: String): List<String> = if (id in picks) picks - id else (picks + id).takeLast(MAX)

    fun toggle(c: Context, id: String): List<String> = toggle(load(c), id).also { sp(c).edit().putString(KEY, it.joinToString("\n")).apply() }
}
