package dev.phonerecall.hestia


/** What propose_routine's input comes to after the phone checked it. */
sealed class SpecCheck {
    /** A new routine, or a change to [before]. */
    data class Ok(val routine: Routine, val sentence: String, val before: Routine?) : SpecCheck()
    data class Delete(val routine: Routine, val sentence: String) : SpecCheck()
    /** Refused, in plain words. [assess]: something Zeus can't do yet, worth asking Prometheus about. */
    data class Bad(val reason: String, val assess: String? = null) : SpecCheck()
}

/**
 * Athena's draft of a routine (propose_routine), checked against the home as it is. She turns his words into
 * WHEN / IF / THEN; this decides what may be. Never a lock or any forbidden kind, never a message, a call or a
 * payment, never a "Never automate" device unless the routine names it on purpose. Unknown triggers, conditions
 * and actions are not refused outright: the answer tells her to ask Prometheus whether they can be built.
 */
object RoutineSpec {
    const val TOOL = "propose_routine"
    const val READ = "read_routines"

    const val NO_MESSAGES = "Routines never send messages, call anyone or pay. Those stay one tap for you."
    private val OUTWARD = setOf("message", "text", "sms", "whatsapp", "telegram", "email", "mail", "call", "dial", "pay", "payment", "buy", "order", "purchase", "transfer", "send", "venmo", "zelle", "tip", "post", "tweet")
    private val LOCKISH = setOf("lock", "unlock", "door", "garage", "gate", "camera", "doorbell", "security", "alarm_system", "arm", "disarm")

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z_]+")).filter { it.isNotEmpty() }.toSet()

    private fun str(m: Map<*, *>, k: String, max: Int = 120): String? = m[k]?.toString()?.trim()?.take(max)?.takeIf { it.isNotEmpty() }
    private fun bool(m: Map<*, *>, k: String): Boolean = m[k] == true || m[k]?.toString()?.lowercase() == "true"
    private fun list(m: Map<*, *>, k: String): List<String> = when (val v = m[k]) {
        is List<*> -> v.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun hhmm(v: String?): Int? = v?.trim()?.let { Regex("""(\d{1,2}):(\d{2})""").matchEntire(it) }?.destructured?.let { (h, m) -> if (h.toInt() < 24 && m.toInt() < 60) h.toInt() * 60 + m.toInt() else null }

    private fun assess(what: String, kind: String) = SpecCheck.Bad(
        "Zeus can't $kind \"$what\" in a routine yet. Ask Prometheus whether it can be built (ask_prometheus, kind assess, title \"Routine: $what\"), " +
            "and tell the owner plainly: you asked Prometheus to check, and he gets a Build card if it can be done. Offer the rest of the routine without it if that still makes sense.",
        "Routine: $what",
    )

    private class No(val bad: SpecCheck.Bad) : Exception(bad.reason)
    private fun no(reason: String): Nothing = throw No(SpecCheck.Bad(reason))

    /** One device by name or id, checked: there, one of them, not forbidden, not Never automate unless [override]. */
    private fun device(ref: String, s: HomeSnapshot, never: Set<String>, override: Boolean): HomeDevice {
        val found = HomeRules.find(ref, s)
        val d = when (found.size) {
            0 -> no("No device called \"$ref\". Call home_status for the names.")
            1 -> found[0]
            else -> no("\"$ref\" matches ${found.size} devices; use the device_id from home_status")
        }
        if (d.kind.forbidden) no(HomeRules.FORBIDDEN)
        if (!d.controllable) throw No(assess("control ${d.kind.plural} like ${d.name}", "yet"))
        if (d.id in never && !override) no("${d.name} is marked Never automate. Only if the owner says this routine may touch it, send it again with override_never.")
        return d
    }

    fun check(input: Map<String, Any?>, s: HomeSnapshot, d: RoutinesData, newId: () -> String): SpecCheck = try {
        if (s.link != Link.Connected) no("Hestia isn't connected to Google Home yet")
        val before = str(input, "id", 40)?.let { id -> d.routine(id) ?: no("No routine $id. Call read_routines for the ids.") }
        if (bool(input, "delete")) {
            val r = before ?: no("Say which routine to delete (its id from read_routines)")
            SpecCheck.Delete(r, "Delete \"${r.name}\": ${RoutineRules.sentence(r, s, never = d.never)}")
        } else parse(input, s, d, before, newId)
    } catch (e: No) { e.bad }

    @Suppress("UNCHECKED_CAST")
    private fun parse(input: Map<String, Any?>, s: HomeSnapshot, d: RoutinesData, before: Routine?, newId: () -> String): SpecCheck {
        // Anything outward in what it does is refused first (a card titled "call Amma" is still just a card).
        for (a in (input["then"] as? List<Map<String, Any?>>).orEmpty()) if (words("${a["type"]} ${a["action"]}").any { it in OUTWARD }) no(NO_MESSAGES)

        val w = input["when"] as? Map<String, Any?> ?: no("when is missing: what starts the routine")
        val type = str(w, "type", 40)?.lowercase() ?: no("when.type is missing")
        val moment = Moment.wire(type) ?: return assess(type, "start on")
        if (!moment.wired) return assess(moment.triggerLabel, "start on")
        val place = str(w, "place", 60)?.takeIf { moment == Moment.ARRIVE || moment == Moment.LEAVE }?.takeIf { !it.equals("home", true) }
        val atMin = if (moment == Moment.TIME) hhmm(str(w, "at", 5)) ?: no("A time routine needs when.at as HH:MM, like 21:00") else null
        val offset = (w["offset_min"] as? Number)?.toInt() ?: str(w, "offset_min", 5)?.toIntOrNull() ?: 0
        if (offset !in -180..180) no("offset_min goes from -180 to 180")
        val afterMin = if (moment == Moment.ASLEEP) str(w, "after", 5)?.let { hhmm(it) ?: no("when.after is HH:MM") } else null
        val idle = (w["idle_min"] as? Number)?.toInt()?.coerceIn(5, 120)

        val ifs = (input["if"] as? List<Map<String, Any?>>).orEmpty().map { c ->
            val t = str(c, "type", 30)?.lowercase() ?: no("Each if needs a type")
            val v = str(c, "value", 60)
            when (t) {
                "home", "at_home" -> Cond("home", if (v == "no" || v == "false") "no" else "yes")
                "away", "not_home" -> Cond("home", "no")
                "dark", "after_dark" -> Cond("dark", if (v == "no" || v == "false") "no" else "yes")
                "light", "daylight" -> Cond("dark", "no")
                "weekdays", "weekday" -> Cond("days", "weekdays")
                "weekend", "weekends" -> Cond("days", "weekend")
                "days" -> v?.takeIf { RoutineRules.days(it).isNotEmpty() }?.let { Cond("days", it.lowercase()) } ?: no("days needs weekdays, weekend or names like mon,wed")
                "after", "before" -> v?.takeIf { hhmm(it) != null }?.let { Cond(t, it) } ?: no("$t needs a time as HH:MM, like 21:00")
                "device" -> {
                    val dv = device(str(c, "device", 80) ?: v?.substringBeforeLast(':') ?: no("A device condition needs device and state"), s, emptySet(), true)
                    val st = (str(c, "state", 5) ?: v?.substringAfterLast(':'))?.lowercase()?.takeIf { it == "on" || it == "off" } ?: no("A device condition's state is on or off")
                    Cond("device", "${dv.id}:$st")
                }
                else -> return assess(t, "check")
            }
        }

        var lights: Routine? = null
        val then = ArrayList<Act>()
        val overrides = ArrayList<String>()
        for (a in (input["then"] as? List<Map<String, Any?>>).orEmpty()) {
            val t = str(a, "type", 30)?.lowercase() ?: no("Each then needs a type")
            if (words(t).any { it in LOCKISH }) no(HomeRules.FORBIDDEN)
            when (t) {
                "lights", "switch", "switches", "plugs" -> {
                    if (lights != null) no("One lights step per routine; name the rest as device steps")
                    val action = str(a, "action", 5)?.lowercase()?.takeIf { it == "on" || it == "off" } ?: no("A lights step's action is on or off")
                    val rooms = list(a, "rooms").map { r ->
                        if (r == "*" || r.equals("all", true) || r.equals("every room", true) || r.equals("everywhere", true)) "*"
                        else s.rooms.firstOrNull { it.name.equals(r, true) }?.name ?: s.rooms.firstOrNull { RoutineRules.roomMatch(it.name, listOf(r)) }?.name
                            ?: no("No room called \"$r\" in Google Home. Rooms: ${s.rooms.joinToString(", ") { it.name }}")
                    }
                    val override = bool(a, "override_never")
                    val ds = list(a, "devices").map { device(it, s, d.never, override).also { dv -> if (dv.id in d.never) overrides += dv.id } }
                    val plugs = bool(a, "every_plug")
                    if (plugs && action == "on") no("A routine turns every plug off, never all of them on. Name the plugs to turn on.")
                    val except = list(a, "except").map { e -> HomeRules.find(e, s).singleOrNull()?.id ?: no("No single device called \"$e\" to leave out") }
                    val usual = bool(a, "usual")
                    if (rooms.isEmpty() && ds.isEmpty() && !plugs && !usual) no("Say which lights: rooms, devices, every_plug or usual")
                    lights = Routine(turnOn = action == "on", rooms = rooms, devices = ds.map { it.id }, plugs = plugs, except = except, learn = usual)
                }
                "device" -> {
                    val override = bool(a, "override_never")
                    val dv = device(str(a, "device", 80) ?: no("A device step needs device"), s, d.never, override)
                    if (dv.id in d.never) overrides += dv.id
                    val raw = buildMap { put("device_id", dv.id); str(a, "action", 20)?.let { put("action", it) }; str(a, "value", 20)?.let { put("value", it) } }
                    when (val c = HomeRules.check(raw, s)) {
                        is ControlCheck.Ok -> then += Act("device", c.payload.filterKeys { it == "device_id" || it == "action" || it == "value" })
                        is ControlCheck.Bad -> if (c.reason.endsWith("is offline")) then += Act("device", raw) else no(c.reason)
                    }
                }
                "music" -> {
                    val q = str(a, "query", 120); val uri = str(a, "uri", 80)
                    if (q == null && uri == null) no("A music step needs a query or a Spotify uri")
                    then += Act("music", buildMap { q?.let { put("query", it) }; uri?.let { put("uri", it) }; put("name", str(a, "name", 80) ?: q ?: "music") })
                }
                "alarm" -> {
                    val h = (a["hour"] as? Number)?.toInt() ?: str(a, "hour", 2)?.toIntOrNull() ?: no("An alarm step needs hour 0-23")
                    val m = (a["minute"] as? Number)?.toInt() ?: str(a, "minute", 2)?.toIntOrNull() ?: 0
                    if (h !in 0..23 || m !in 0..59) no("An alarm is hour 0-23 and minute 0-59")
                    then += Act("alarm", buildMap { put("hour", "$h"); put("minute", "$m"); str(a, "label", 60)?.let { put("label", it) } })
                }
                "card" -> then += Act("card", buildMap { put("title", str(a, "title", 60) ?: no("A card step needs a title")); str(a, "body", 280)?.let { put("body", it) } })
                "scene" -> no("Say the lights and levels instead: a scene is a few device steps (on, brightness, white, color).")
                else -> return assess(t, "do")
            }
        }
        val base = lights ?: Routine()
        if (!base.switches && then.isEmpty()) no("A routine needs at least one thing to do")
        val r = base.copy(
            id = before?.id ?: newId(), name = str(input, "name", 60) ?: before?.name ?: "${moment.triggerLabel.replaceFirstChar { it.uppercase() }}",
            moment = moment, place = place, atMin = atMin, offsetMin = offset, ifs = ifs, then = then, overrides = overrides.distinct(),
            afterMin = afterMin ?: before?.afterMin ?: 23 * 60 + 30, idleMin = idle ?: before?.idleMin ?: 20,
            source = before?.source ?: "chat", on = before?.on ?: true,
        )
        return SpecCheck.Ok(r, RoutineRules.sentence(r, s, never = d.never), before)
    }

    /** At confirm: the routine still fits the home (its devices are there and allowed). Null when it does, else why not. */
    fun recheck(r: Routine, s: HomeSnapshot, never: Set<String>): String? {
        val ids = r.devices + r.then.filter { it.type == "device" }.mapNotNull { it.args["device_id"] }
        for (id in ids) {
            val dv = s.device(id) ?: return "A device in this routine is gone from Google Home"
            if (dv.kind.forbidden) return HomeRules.FORBIDDEN
            if (dv.id in never && dv.id !in r.overrides) return "${dv.name} is marked Never automate"
        }
        if (r.then.any { a -> a.type !in setOf("device", "music", "alarm", "card") }) return NO_MESSAGES
        if (!r.moment.wired) return "Zeus can't start a routine on ${r.moment.triggerLabel} yet"
        return null
    }
}

/** The chat side of routines: what read_routines shows her, the card's step, and the two tools' specs. */
object RoutineTool {
    /** Her view: each routine in propose_routine's own shape (to change one, send it back with its id), plus what can be. */
    fun read(d: RoutinesData, s: HomeSnapshot): Map<String, Any?> = linkedMapOf(
        "routines" to d.routines.map { r ->
            linkedMapOf(
                "id" to r.id, "name" to r.name, "on" to r.on, "from" to r.source, "sentence" to RoutineRules.sentence(r, s, never = d.never),
                "when" to linkedMapOf<String, Any?>("type" to r.moment.wire, "place" to r.place, "at" to r.atMin?.let { "%02d:%02d".format(it / 60, it % 60) },
                    "offset_min" to r.offsetMin.takeIf { it != 0 }).filterValues { it != null },
                "if" to r.ifs.map { mapOf("type" to it.type, "value" to it.value) },
                "then" to listOfNotNull(
                    if (r.switches) linkedMapOf<String, Any?>("type" to "lights", "action" to if (r.turnOn) "on" else "off", "rooms" to r.rooms.takeIf { it.isNotEmpty() },
                        "devices" to r.devices.mapNotNull { s.device(it)?.name }.takeIf { it.isNotEmpty() }, "every_plug" to r.plugs.takeIf { it },
                        "except" to r.except.mapNotNull { s.device(it)?.name }.takeIf { it.isNotEmpty() }, "usual" to r.learn.takeIf { it }).filterValues { it != null } else null,
                ) + r.then.map { a -> mapOf("type" to a.type) + a.args.mapValues { (k, v) -> if (k == "device_id") s.device(v)?.name ?: v else v }.mapKeys { (k, _) -> if (k == "device_id") "device" else k } },
                "runs" to r.runs, "last_run" to r.lastRun?.let { java.time.Instant.ofEpochMilli(it).toString() },
            )
        },
        "never_automate" to d.never.mapNotNull { s.device(it)?.name },
        "rooms" to s.rooms.map { it.name },
        "when_types" to Moment.entries.filter { it.wired }.map { it.wire },
        "not_yet" to Moment.entries.filter { !it.wired }.map { it.wire },
        "if_types" to listOf("home", "away", "dark", "light", "weekdays", "weekend", "days", "after", "before", "device"),
        "then_types" to listOf("lights", "device", "music", "alarm", "card"),
        "rule" to RoutineSpec.NO_MESSAGES + " Locks, garage doors, cameras and security systems: never.",
    )

    fun step(ok: SpecCheck.Ok): dev.phonerecall.brain.RawStep =
        dev.phonerecall.brain.RawStep(dev.phonerecall.brain.ProposalKind.SET_ROUTINE, mapOf("routine" to dev.phonerecall.mind.stateJson.writeValueAsString(ok.routine), "sentence" to ok.sentence))

    private fun s(d: String) = mapOf("type" to "string", "description" to d)
    private fun o(props: Map<String, Any?>, req: List<String> = emptyList()) = mapOf("type" to "object", "properties" to props, "required" to req)
    private fun arr(d: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to d)
    private fun b(d: String) = mapOf("type" to "boolean", "description" to d)
    private fun i(d: String) = mapOf("type" to "integer", "description" to d)

    val SPECS = listOf(
        dev.phonerecall.mind.ToolSpec(
            RoutineSpec.READ,
            "His home routines (WHEN, IF, THEN), each with its id, its sentence and runs; the devices he marked Never automate; the rooms; and what WHEN, IF and THEN can be. Call it before drafting or changing one.",
            o(emptyMap()),
        ),
        dev.phonerecall.mind.ToolSpec(
            RoutineSpec.TOOL,
            "Make, change or delete one home routine from what the owner said, in any words or Hinglish (\"when I get home after 9 turn on the hall light\", \"jab main ghar se niklun, bedroom ki lights band\"). " +
                "The phone checks it and parks ONE card with the routine in a sentence; it is live only after he confirms. To change one, send it whole again with its id (from read_routines); to delete, id and delete true. " +
                "Never locks or anything forbidden, never a message, a call or a payment. A device marked Never automate only if he said so for this routine (override_never). " +
                "If the answer says Zeus can't do part of it yet, call ask_prometheus as it says and tell him plainly.",
            o(
                mapOf(
                    "id" to s("To change or delete: the routine's id from read_routines"),
                    "delete" to b("true to delete the routine with this id"),
                    "name" to s("A short name he will see, like Hall light at night"),
                    "when" to o(
                        mapOf(
                            "type" to s("leave, arrive, sunset, sunrise, time, asleep, wake (alarm dismissed), car_on, car_off; also charging, unplugged, event_start, event_end (not built yet)"),
                            "place" to s("arrive and leave: home (default), work, or a place's name"),
                            "at" to s("time: HH:MM, 24 hour"),
                            "offset_min" to i("sunset and sunrise: minutes after, negative before"),
                            "after" to s("asleep: not before this HH:MM when Hypnos has no bedtime"),
                            "idle_min" to i("asleep: screen off this long"),
                        ),
                        listOf("type"),
                    ),
                    "if" to mapOf("type" to "array", "items" to o(mapOf("type" to s("home, away, dark, light, weekdays, weekend, days, after, before, device"), "value" to s("days: mon,wed; after and before: HH:MM"), "device" to s("device: its name"), "state" to s("device: on or off")), listOf("type"))),
                    "then" to mapOf(
                        "type" to "array",
                        "items" to o(
                            mapOf(
                                "type" to s("lights (rooms, devices, every_plug, except, usual), device (one device: on, off, brightness, color_temp, color), music, alarm, card"),
                                "action" to s("lights: on or off. device: on, off, brightness, color_temp or color"),
                                "rooms" to arr("lights: room names, or * for every room"),
                                "devices" to arr("lights: device names"),
                                "every_plug" to b("lights off: every plug and switch too"),
                                "except" to arr("lights: devices to leave alone"),
                                "usual" to b("lights: the ones he usually switches at this moment, learned"),
                                "override_never" to b("Only when he said this routine may touch a device marked Never automate"),
                                "device" to s("device: its name"), "value" to s("device: 40, 2700, warm, blue"),
                                "query" to s("music: what to play"), "uri" to s("music: a Spotify uri"), "name" to s("music: its name"),
                                "hour" to i("alarm: 0-23"), "minute" to i("alarm: 0-59"), "label" to s("alarm: label"),
                                "title" to s("card: title"), "body" to s("card: text"),
                            ),
                            listOf("type"),
                        ),
                    ),
                ),
            ),
        ),
    )
}
