package dev.phonerecall.hestia

import kotlin.math.roundToInt

/** A home_control request checked against the devices as they are now. */
sealed class ControlCheck {
    /** `light` true: the step is a Lights step (the one Hestia kind he may put on auto-approve). */
    data class Ok(val payload: Map<String, String>, val summary: String, val light: Boolean) : ControlCheck()
    data class Bad(val reason: String) : ControlCheck()
}

/**
 * Pure rules for Hestia: what a command may do, how it reads on a card, and the unit conversions.
 * The proposal validator, the executor and the screen all use these, so they can't disagree.
 */
object HomeRules {
    const val FORBIDDEN = "Zeus never controls locks, garage doors, cameras, doorbells or security systems. Use the Google Home app for those."
    val ACTIONS = listOf("on", "off", "brightness", "color_temp", "color")

    /** Named colors the model (and the screen's swatches) can ask for: hue 0-359, saturation 0-100. */
    val COLORS = linkedMapOf(
        "red" to (0 to 100), "orange" to (30 to 100), "yellow" to (55 to 100), "green" to (120 to 100),
        "teal" to (175 to 90), "blue" to (225 to 100), "purple" to (275 to 90), "pink" to (320 to 70),
    )
    /** White temperature words, in kelvin. */
    val WHITES = linkedMapOf("warm" to 2700, "soft" to 3000, "neutral" to 4000, "cool" to 5000, "daylight" to 6500)

    /** Finds a device by id, or by name (case and spacing ignored), or "Room name" plus name. */
    fun find(ref: String, s: HomeSnapshot): List<HomeDevice> {
        val key = norm(ref)
        s.devices.firstOrNull { it.id == ref }?.let { return listOf(it) }
        val byName = s.devices.filter { norm(it.name) == key }
        if (byName.isNotEmpty()) return byName
        return s.devices.filter { d -> s.room(d.roomId)?.let { norm("${it.name} ${d.name}") == key } == true }
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Validates one request: device, kind, action and value. Never looks at who asked. */
    fun check(raw: Map<String, String>, s: HomeSnapshot): ControlCheck {
        if (s.link != Link.Connected) return ControlCheck.Bad("Hestia isn't connected to Google Home yet")
        val ref = (raw["device_id"] ?: raw["device"])?.trim()?.takeIf { it.isNotEmpty() } ?: return ControlCheck.Bad("device is missing")
        val found = find(ref, s)
        val d = when (found.size) {
            0 -> return ControlCheck.Bad("No device called \"$ref\". Call home_status for the names.")
            1 -> found[0]
            else -> return ControlCheck.Bad("\"$ref\" matches ${found.size} devices; use the device_id from home_status")
        }
        if (d.kind.forbidden) return ControlCheck.Bad(FORBIDDEN)
        if (!d.controllable) return ControlCheck.Bad("Hestia doesn't control ${d.kind.plural} yet")
        if (!d.online) return ControlCheck.Bad("${d.name} is offline")
        val action = raw["action"]?.trim()?.lowercase()?.let { if (it == "brightness_percent" || it == "level") "brightness" else it }
            ?: return ControlCheck.Bad("action is missing")
        val value = raw["value"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val room = s.room(d.roomId)?.name
        val base = linkedMapOf("device_id" to d.id, "device" to d.name, "action" to action).apply { room?.let { put("room", it) } }
        val (v, summary) = when (action) {
            "on" -> null to "Turn on ${d.name}"
            "off" -> null to "Turn off ${d.name}"
            "brightness" -> {
                if (Trait.LEVEL !in d.traits) return ControlCheck.Bad("${d.name} can't dim")
                val pct = value?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.roundToInt() ?: return ControlCheck.Bad("value must be a brightness from 1 to 100")
                if (pct !in 1..100) return ControlCheck.Bad("value must be a brightness from 1 to 100")
                "$pct" to "Set ${d.name} to $pct%"
            }
            "color_temp" -> {
                if (Trait.COLOR_TEMP !in d.traits) return ControlCheck.Bad("${d.name} has no white temperature")
                val k = value?.let { WHITES[it] ?: it.removeSuffix("k").trim().toIntOrNull() } ?: return ControlCheck.Bad("value must be kelvin like 2700, or warm, soft, neutral, cool or daylight")
                if (k !in d.minKelvin..d.maxKelvin) return ControlCheck.Bad("${d.name} goes from ${d.minKelvin} K to ${d.maxKelvin} K")
                "$k" to "Set ${d.name} to $k K${whiteWord(k)?.let { " ($it)" }.orEmpty()}"
            }
            "color" -> {
                if (Trait.COLOR !in d.traits) return ControlCheck.Bad("${d.name} isn't a color light")
                val name = value?.takeIf { it in COLORS } ?: return ControlCheck.Bad("value must be one of ${COLORS.keys.joinToString(", ")}")
                name to "Make ${d.name} $name"
            }
            else -> return ControlCheck.Bad("action must be on, off, brightness, color_temp or color")
        }
        v?.let { base["value"] = it }
        return ControlCheck.Ok(base, summary, light = d.kind == DeviceKind.LIGHT)
    }

    fun command(p: Map<String, String>): HomeCommand = when (p["action"]) {
        "on" -> HomeCommand.Power(true)
        "off" -> HomeCommand.Power(false)
        "brightness" -> HomeCommand.Brightness(p.getValue("value").toInt())
        "color_temp" -> HomeCommand.ColorTemp(p.getValue("value").toInt())
        "color" -> COLORS.getValue(p.getValue("value")).let { (h, sat) -> HomeCommand.Color(h, sat) }
        else -> throw HomeError("Unknown action ${p["action"]}")
    }

    /** The payload that puts the device back as it was before [p], when that is known. */
    fun undo(d: HomeDevice, p: Map<String, String>): Map<String, String>? {
        val back = when (p["action"]) {
            "on", "off" -> d.on?.let { "action" to if (it) "on" else "off" }?.let { mapOf(it) }
            "brightness" -> d.level?.let { mapOf("action" to "brightness", "value" to "$it") }
            "color_temp" -> d.kelvin?.let { mapOf("action" to "color_temp", "value" to "$it") }
            else -> null
        } ?: return null
        return mapOf("device_id" to d.id, "device" to d.name) + back
    }

    fun whiteWord(k: Int): String? = when {
        k <= 2850 -> "warm"
        k <= 3500 -> "soft"
        k <= 4500 -> "neutral"
        k <= 5750 -> "cool"
        else -> "daylight"
    }

    // ---- Matter units ----

    /** Matter LevelControl runs 1 to 254. */
    fun toLevel(percent: Int): Int = (percent.coerceIn(1, 100) * 254 / 100.0).roundToInt().coerceIn(1, 254)
    fun toPercent(level: Int): Int = (level.coerceIn(0, 254) * 100 / 254.0).roundToInt().coerceIn(if (level > 0) 1 else 0, 100)
    fun toMireds(kelvin: Int): Int = (1_000_000.0 / kelvin.coerceIn(1000, 10_000)).roundToInt()
    fun toKelvin(mireds: Int): Int = if (mireds <= 0) 0 else ((1_000_000.0 / mireds) / 10).roundToInt() * 10
    /** Matter hue and saturation are 0 to 254. */
    fun toMatterHue(degrees: Int): Int = (degrees.mod(360) * 254 / 360.0).roundToInt().coerceIn(0, 254)
    fun toMatterSat(percent: Int): Int = (percent.coerceIn(0, 100) * 254 / 100.0).roundToInt()
    fun toDegrees(hue: Int): Int = (hue.coerceIn(0, 254) * 360 / 254.0).roundToInt() % 360
    fun satPercent(sat: Int): Int = (sat.coerceIn(0, 254) * 100 / 254.0).roundToInt()

    // ---- words ----

    /** "On, 60%", "Off", "Offline". */
    fun state(d: HomeDevice): String = when {
        !d.online -> "Offline"
        d.kind.forbidden -> "In Google Home"
        d.on == true -> listOfNotNull("On", d.level?.takeIf { Trait.LEVEL in d.traits }?.let { "$it%" }).joinToString(", ")
        d.on == false -> "Off"
        else -> "No state"
    }

    /** home_status for the model: names, ids, rooms and state. Forbidden kinds are listed as read only. */
    fun status(s: HomeSnapshot, away: Boolean?): Map<String, Any?> {
        if (s.link != Link.Connected) return mapOf("connected" to false, "why" to linkWords(s.link))
        return linkedMapOf(
            "connected" to true,
            "away" to away,
            "homes" to s.structures.map { st ->
                linkedMapOf(
                    "name" to st.name,
                    "rooms" to (s.rooms.filter { it.structureId == st.id }.map { it.name to it.id } + listOf<Pair<String, String?>>("No room" to null)).mapNotNull { (name, id) ->
                        val ds = s.devices.filter { it.structureId == st.id && it.roomId == id }
                        if (ds.isEmpty()) null else linkedMapOf("room" to name, "devices" to ds.map { d -> device(d) })
                    },
                    "automations" to s.automations.filter { it.structureId == st.id }.map { mapOf("name" to it.name, "active" to it.active) },
                )
            },
            "rule" to "home_control only proposes; the owner confirms. Locks, garage doors, cameras, doorbells and security systems can't be controlled.",
        )
    }

    private fun device(d: HomeDevice): Map<String, Any?> = linkedMapOf<String, Any?>(
        "device_id" to d.id, "name" to d.name, "kind" to d.kind.word.lowercase(), "online" to d.online,
        "on" to d.on, "brightness" to d.level?.takeIf { Trait.LEVEL in d.traits }, "kelvin" to d.kelvin?.takeIf { Trait.COLOR_TEMP in d.traits },
        "can" to if (d.controllable) listOfNotNull("on", "off", "brightness".takeIf { Trait.LEVEL in d.traits }, "color_temp".takeIf { Trait.COLOR_TEMP in d.traits }, "color".takeIf { Trait.COLOR in d.traits }) else null,
        "read_only" to (!d.controllable).takeIf { it },
    ).filterValues { it != null }

    /**
     * The debug build's adb dump (DebugCommandReceiver, `hestia_status`): link, homes, rooms, and per device its
     * name, room, type, online, on and level. No ids, no tokens.
     */
    fun debugStatus(s: HomeSnapshot): Map<String, Any?> = linkedMapOf(
        "connected" to (s.link == Link.Connected),
        "reason" to linkWords(s.link),
        "structures" to s.structures.size,
        "structure_names" to s.structures.map { it.name },
        "rooms" to s.rooms.size,
        "devices" to s.devices.map { d ->
            linkedMapOf("name" to d.name, "room" to (s.room(d.roomId)?.name ?: "No room"), "type" to d.kind.word.lowercase(), "online" to d.online, "on" to d.on, "level" to d.level)
        },
    )

    fun linkWords(l: Link): String = when (l) {
        Link.Starting -> "Connecting to Google Home"
        Link.NeedsPermission -> "The owner hasn't given Zeus access to his Google Home yet"
        Link.Connected -> "Connected"
        is Link.NotConnected -> l.reason
    }
}
