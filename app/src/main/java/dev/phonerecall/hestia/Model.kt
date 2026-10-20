package dev.phonerecall.hestia

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/*
 * Hestia: his lights and plugs. AiDot (Linkind and similar) has no public API, so the devices are linked
 * into the Google Home app and Zeus reaches them through the Google Home APIs for Android (docs/HESTIA.md).
 * Everything above HomeBackend is plain data, so the screen, the tools and the tests never see the SDK.
 */

/**
 * What a device is, as far as Hestia cares. `forbidden` kinds (locks, garage doors, security systems,
 * cameras, doorbells) are never controlled by Zeus at all: not from a tap, not by a model, never on
 * auto-approve. They show as read-only rows that point to the Google Home app.
 */
enum class DeviceKind(val word: String, val plural: String, val forbidden: Boolean = false) {
    LIGHT("Light", "lights"),
    PLUG("Plug", "plugs"),
    SWITCH("Switch", "switches"),
    FAN("Fan", "fans"),
    THERMOSTAT("Thermostat", "thermostats"),
    SPEAKER("Speaker", "speakers"),
    TV("TV", "TVs"),
    SENSOR("Sensor", "sensors"),
    LOCK("Lock", "locks", forbidden = true),
    GARAGE("Garage door", "garage doors", forbidden = true),
    SECURITY("Security system", "security systems", forbidden = true),
    CAMERA("Camera", "cameras", forbidden = true),
    DOORBELL("Doorbell", "doorbells", forbidden = true),
    OTHER("Device", "devices");

    companion object {
        /** Kinds Hestia sends commands to. Thermostats, speakers and TVs stay with the Google Home app for now. */
        val controllable = setOf(LIGHT, PLUG, SWITCH, FAN)
    }
}

/** The traits Hestia reads and drives. Each maps to one Matter cluster (OnOff, LevelControl, ColorControl). */
enum class Trait { ON_OFF, LEVEL, COLOR_TEMP, COLOR }

@Immutable
data class Structure(val id: String, val name: String)

@Immutable
data class Room(val id: String, val name: String, val structureId: String)

/**
 * One device as Google Home reports it. `level` is a percent (1 to 100); `kelvin` the white temperature;
 * `hue` 0 to 359 and `saturation` 0 to 100 for color lights. Null means the device doesn't report it.
 */
@Immutable
data class HomeDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val roomId: String?,
    val structureId: String,
    val online: Boolean = true,
    val traits: Set<Trait> = emptySet(),
    val on: Boolean? = null,
    val level: Int? = null,
    val kelvin: Int? = null,
    val minKelvin: Int = 2000,
    val maxKelvin: Int = 6500,
    val hue: Int? = null,
    val saturation: Int? = null,
) {
    /** Hestia acts on it: an allowed kind with at least on/off. */
    val controllable: Boolean get() = !kind.forbidden && kind in DeviceKind.controllable && Trait.ON_OFF in traits
}

/** A Google Home automation or household routine. Read only in Hestia: he edits them in the Google Home app. */
@Immutable
data class Automation(val id: String, val name: String, val description: String, val active: Boolean, val structureId: String)

/** Where the connection stands. `reason` is the exact words for the not-connected screen. */
sealed class Link {
    /** Waiting for the first answer from Google Play services. */
    data object Starting : Link()
    /** The SDK is there but he hasn't granted Zeus access to his home yet. */
    data object NeedsPermission : Link()
    data object Connected : Link()
    data class NotConnected(val reason: String) : Link()
}

@Immutable
data class HomeSnapshot(
    val link: Link = Link.Starting,
    val structures: List<Structure> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val devices: List<HomeDevice> = emptyList(),
    val automations: List<Automation> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun device(id: String): HomeDevice? = devices.firstOrNull { it.id == id }
    fun room(id: String?): Room? = id?.let { r -> rooms.firstOrNull { it.id == r } }
    val lightsOn: List<HomeDevice> get() = devices.filter { it.kind == DeviceKind.LIGHT && it.on == true && it.online }
}

/** One command. Percent and kelvin, never raw Matter units; the backend converts. */
sealed class HomeCommand {
    data class Power(val on: Boolean) : HomeCommand()
    data class Brightness(val percent: Int) : HomeCommand()
    data class ColorTemp(val kelvin: Int) : HomeCommand()
    data class Color(val hue: Int, val saturation: Int) : HomeCommand()
}

/** A command the backend refused or that failed on the way. The message is shown as is. */
class HomeError(message: String) : Exception(message)

/**
 * The one seam to the smart home. GoogleHomeBackend talks to the Home APIs; FakeHomeBackend is for tests
 * and shots. `state` is live: the implementation subscribes and keeps it current.
 */
interface HomeBackend {
    val state: StateFlow<HomeSnapshot>
    /** Starts (or restarts) the subscriptions. Safe to call more than once. */
    fun start()
    /** Opens Google's consent screen. Needs the activity registered first. */
    suspend fun requestPermission(): String?
    /** Sends one command; throws [HomeError] when it can't. Never called for forbidden kinds. */
    suspend fun command(deviceId: String, command: HomeCommand)
}

/** Odyssey's answer to "is he home?", without Hestia depending on Odyssey. Null: not known. */
fun interface Whereabouts {
    fun away(): Boolean?
}
