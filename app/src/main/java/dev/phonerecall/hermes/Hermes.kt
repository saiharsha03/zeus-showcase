package dev.phonerecall.hermes

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Validate
import dev.phonerecall.calls.CallRouting
import dev.phonerecall.calls.Calls
import dev.phonerecall.clio.Clio
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A button on a reply. Runs off the main thread and returns the next reply. */
class Choice(val label: String, val detail: String = "", val run: (Context) -> Outcome)

/**
 * What Hermes shows after a command: one line, and at most one of Undo, a confirm, a few choices or a
 * permission to ask for. [needs] is a runtime permission; granting it runs [retry]. [body] is the agenda.
 */
data class Outcome(
    val title: String,
    val detail: String = "",
    val ok: Boolean = true,
    val undo: ((Context) -> Unit)? = null,
    val confirm: Choice? = null,
    val choices: List<Choice> = emptyList(),
    val needs: String? = null,
    val retry: Cmd? = null,
    val body: String? = null,
)

/** A settings page he can open by name. [panel] pages open as the quick panel over the app. */
data class Page(val action: String, val title: String, val words: String, val panel: Boolean = false)

/** The settings he opens by name (ported from PhoneRecall's SETTINGS_PAGES). Pure, so it is tested. */
object Pages {
    val ALL = listOf(
        Page(Settings.Panel.ACTION_WIFI, "Wi-Fi", "wifi wi fi wireless network", panel = true),
        Page(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Internet", "internet mobile data data connection", panel = true),
        Page(Settings.Panel.ACTION_NFC, "NFC", "nfc tap to pay contactless", panel = true),
        Page(Settings.Panel.ACTION_VOLUME, "Volume", "volume sound levels", panel = true),
        Page(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings", "wifi wi fi wireless network"),
        Page(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", "bluetooth headphones earbuds pair speaker car"),
        Page(Settings.ACTION_DISPLAY_SETTINGS, "Display", "display screen brightness dark mode font size"),
        Page(Settings.ACTION_SOUND_SETTINGS, "Sound", "sound ringtone vibrate silent"),
        Page(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery", "battery battery saver power saving"),
        Page(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location", "location gps"),
        Page("android.settings.TETHER_SETTINGS", "Hotspot", "hotspot tethering tether"),
        Page(Settings.ACTION_DATA_USAGE_SETTINGS, "Mobile data", "mobile data usage cellular roaming sim"),
        Page(Settings.ACTION_NFC_SETTINGS, "NFC settings", "nfc tap to pay contactless"),
        Page(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode", "airplane flight mode"),
        Page(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "Do not disturb", "do not disturb dnd quiet"),
        Page(Settings.ACTION_APP_NOTIFICATION_SETTINGS, "Notifications", "notifications"),
        Page(Settings.ACTION_APPLICATION_SETTINGS, "Apps", "apps installed apps"),
        Page(Settings.ACTION_SETTINGS, "Settings", "settings all settings"),
    )

    /** "wifi", "turn on wifi": the quick panel when Android has one, else the page. "wifi settings": the page. */
    fun of(target: String): Page? {
        val wantsPage = Regex("\\bsettings?\\b").containsMatchIn(target.lowercase())
        val q = Commands.norm(target.lowercase().replace(Regex("\\bsettings?\\b"), "")).ifEmpty { "settings" }
        val hits = ALL.filter { p -> Commands.norm(p.title.removeSuffix(" settings")) == q || p.words.split(' ').any { it == q } || " $q " in " ${p.words} " }
        return hits.firstOrNull { it.panel != wantsPage } ?: hits.firstOrNull()
    }
}

/** What Hermes keeps: corrections he taught, and whether it already asked for Do not disturb access. */
class HermesStore(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    private val gson = Gson()
    fun book(): Map<String, String> = sp.getString("book", null)?.let { runCatching { gson.fromJson<Map<String, String>>(it, object : TypeToken<Map<String, String>>() {}.type) }.getOrNull() }.orEmpty()
    fun learn(kind: String, phrase: String, meant: String) { sp.edit().putString("book", gson.toJson(Corrections.learn(book(), kind, phrase, meant))).apply() }
    var askedDnd: Boolean
        get() = sp.getBoolean("asked_dnd", false)
        set(v) { sp.edit().putBoolean("asked_dnd", v).apply() }
}

/**
 * Runs his commands. Blocking: call off the main thread. Every command is logged to Clio as its kind and
 * how it went, never his words or who it was for.
 */
object Hermes {
    /** The last command, for "no, I meant ...". */
    @Volatile var last: Cmd? = null

    private val WHEN = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** Null when [text] is not a command: the caller hands it to Athena. */
    fun handle(c: Context, text: String, now: LocalDateTime = LocalDateTime.now()): Outcome? {
        val cmd = Commands.parse(text, now) ?: return null
        return run(c, cmd)
    }

    fun run(c: Context, cmd: Cmd): Outcome {
        val o = try { exec(c, cmd) } catch (e: Exception) { Outcome("That didn't work", e.message ?: e.javaClass.simpleName, ok = false) }
        if (cmd !is CorrectionCmd) last = cmd
        log(cmd, when { !o.ok -> "failed"; o.confirm != null -> "confirm"; o.needs != null -> "needs permission"; o.choices.isNotEmpty() -> "asked"; else -> "ran" })
        return o
    }

    fun log(cmd: Cmd, outcome: String) = Clio.info("hermes", cmd.kind, outcome)

    /** Undo from the snackbar, logged like the command. */
    fun undo(c: Context, o: Outcome, cmd: Cmd?) {
        runCatching { o.undo?.invoke(c) }.onFailure { Clio.warn("hermes", cmd?.kind ?: "undo", "undo failed") }
        cmd?.let { log(it, "undone") }
    }

    private fun exec(c: Context, cmd: Cmd): Outcome = when (cmd) {
        is OpenCmd -> open(c, cmd)
        is AlarmCmd -> own(c, ProposalKind.SET_ALARM, mapOf("hour" to "${cmd.hour}", "minute" to "${cmd.minute}"))
        is TimerCmd -> own(c, ProposalKind.SET_TIMER, mapOf("seconds" to "${cmd.seconds}"))
        is RemindCmd -> remind(c, cmd)
        is PlayCmd -> own(c, ProposalKind.PLAY_MUSIC, mapOf("query" to cmd.query, "name" to cmd.query))
        is MusicCmd -> own(c, ProposalKind.MUSIC_CONTROL, mapOf("action" to cmd.action))
        is NavigateCmd -> navigate(c, cmd.place)
        is TorchCmd -> torch(c, cmd.on)
        is VolumeCmd -> volume(c, cmd)
        is DndCmd -> dnd(c, cmd.on)
        is CallCmd -> call(c, cmd)
        is MessageCmd -> message(c, cmd)
        is AgendaCmd -> agenda(c, cmd)
        is CorrectionCmd -> correct(c, cmd.target)
    }

    private fun launch(c: Context, i: Intent): Boolean = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---- through Fates, as his own ----

    /** Validated and run at once as his; kept in Fates so Undo (where the kind has one) works there too. */
    private fun own(c: Context, kind: ProposalKind, args: Map<String, String>): Outcome {
        val fates = BrainCore.get(c).fates
        return when (val r = fates.own(listOf(RawStep(kind, args)), null)) {
            is ProposeResult.Invalid -> Outcome("Couldn't do that", r.reason.substringAfter(": "), ok = false)
            is ProposeResult.Created -> when (val d = r.decision) {
                is Decision.Done -> Outcome(done(d.proposal.title), undo = if (d.proposal.undoable) { ctx -> BrainCore.get(ctx).fates.undo(d.proposal.id); BrainCore.get(ctx).changed() } else null)
                is Decision.Failed -> Outcome("Couldn't do that", d.reason, ok = false)
                else -> Outcome("Couldn't do that", (d as? Decision.Refused)?.reason.orEmpty(), ok = false)
            }.also { BrainCore.get(c).changed() }
        }
    }

    /** "Alarm at 6:30 AM" to "Alarm set for 6:30 AM": the line says it happened. */
    private fun done(summary: String): String = when {
        summary.startsWith("Alarm at ") -> "Alarm set for " + summary.removePrefix("Alarm at ")
        summary.startsWith("Timer for ") -> "Timer started for " + summary.removePrefix("Timer for ")
        summary.startsWith("Remind you ") -> "I'll remind you " + summary.removePrefix("Remind you ")
        summary.startsWith("Play ") -> "Playing " + summary.removePrefix("Play ")
        else -> summary
    }

    private fun remind(c: Context, cmd: RemindCmd): Outcome {
        val at = cmd.at ?: run {
            val now = LocalDateTime.now()
            val options = listOf(
                "In 1 hour" to now.plusHours(1).withSecond(0).withNano(0),
                "This evening, 6 PM" to now.toLocalDate().atTime(18, 0).let { if (it.isBefore(now)) it.plusDays(1) else it },
                "Tomorrow, 9 AM" to now.toLocalDate().plusDays(1).atTime(9, 0),
            )
            return Outcome("When should I remind you?", cmd.what, choices = options.map { (label, time) -> Choice(label) { run(it, cmd.copy(at = time)) } })
        }
        return own(c, ProposalKind.ADD_REMINDER, mapOf("at" to ISO.format(at), "text" to cmd.what))
    }

    // ---- open apps and settings ----

    private fun launcherApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != c.packageName }
            .distinctBy { it.first }
    }

    private fun open(c: Context, cmd: OpenCmd): Outcome {
        // A correction he taught ("no, I meant ...") wins over fuzzy matching.
        val target = Corrections.lookup(HermesStore(c).book(), "app", cmd.target) ?: cmd.target.trim()
        val t = target.lowercase()
        if (cmd.toggle != null) {
            if ("flash" in t || "torch" in t) return torch(c, cmd.toggle)
            if ("disturb" in t || t == "dnd") return dnd(c, cmd.toggle)
        }
        // Wi-Fi, Bluetooth, NFC, hotspot and the rest: apps can't switch them, so the panel or page opens.
        Pages.of(target)?.let { p ->
            val opened = launch(c, Intent(p.action)) || (p.title == "Hotspot" && launch(c, Intent(Settings.ACTION_WIRELESS_SETTINGS)))
            if (!opened) return Outcome("${p.title} could not be opened", ok = false)
            return if (cmd.toggle != null) Outcome("Turn ${if (cmd.toggle) "on" else "off"} ${p.title.removeSuffix(" settings")} here", "Android doesn't let apps switch it directly.")
            else Outcome("Opening ${p.title}")
        }
        val apps = launcherApps(c)
        val scored = apps.map { (pkg, label) -> Commands.Target(label, "App", Commands.matchScore(target, label), pkg) }
        return when (val pick = Commands.pick(scored)) {
            is Commands.Pick.One -> openApp(c, pick.target)
            is Commands.Pick.Several -> Outcome("Which one?", choices = pick.targets.map { tg ->
                // Picking one teaches it: next time the same words open that app.
                Choice(tg.label, tg.detail) { ctx -> HermesStore(ctx).learn("app", cmd.target, tg.label); openApp(ctx, tg) }
            })
            Commands.Pick.None -> Outcome("No app called \"$target\"", ok = false)
        }
    }

    private fun openApp(c: Context, t: Commands.Target): Outcome {
        val i = c.packageManager.getLaunchIntentForPackage(t.key) ?: return Outcome("${t.label} could not be opened", ok = false)
        return if (launch(c, i)) Outcome("Opening ${t.label}") else Outcome("${t.label} could not be opened", ok = false)
    }

    // ---- places ----

    private fun navigate(c: Context, place: String): Outcome {
        // A place he named in Odyssey goes by its saved spot; anything else is a Maps search.
        val saved = runCatching { dev.phonerecall.places.PlaceStore(c).all() }.getOrDefault(emptyList())
            .maxByOrNull { Commands.matchScore(place, it.name) }?.takeIf { Commands.matchScore(place, it.name) >= 0.9f }
        val q = saved?.let { "${it.lat},${it.lng}" } ?: place
        val ok = launch(c, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))) ||
            launch(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        return if (ok) Outcome("Directions to ${saved?.name ?: place}") else Outcome("No maps app found", ok = false)
    }

    // ---- device ----

    private fun torch(c: Context, on: Boolean): Outcome {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Outcome("This phone has no flashlight", ok = false)
        return runCatching { cm.setTorchMode(id, on) }.fold(
            { Outcome("Flashlight ${if (on) "on" else "off"}", undo = { ctx -> runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, !on) } }) },
            { Outcome("The flashlight is busy", "Another app is using the camera.", ok = false) },
        )
    }

    fun volume(c: Context, cmd: VolumeCmd): Outcome {
        val am = c.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val before = am.getStreamVolume(stream)
        when (cmd.action) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> am.setStreamVolume(stream, Math.round(am.getStreamMaxVolume(stream) * (cmd.percent ?: 50) / 100f), AudioManager.FLAG_SHOW_UI)
        }
        val pct = am.getStreamVolume(stream) * 100 / am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return Outcome(if (cmd.action == "mute") "Media muted" else "Media volume $pct%",
            undo = { ctx -> ctx.getSystemService(AudioManager::class.java).setStreamVolume(stream, before, AudioManager.FLAG_SHOW_UI) })
    }

    private fun dnd(c: Context, on: Boolean): Outcome {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            // Asked once: the list opens by itself the first time, after that only from the button.
            val store = HermesStore(c)
            val open = Choice("Open the setting") { ctx -> launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); Outcome("Turn Zeus on in the list, then ask again") }
            if (!store.askedDnd) { store.askedDnd = true; launch(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            return Outcome("Allow Zeus to change Do not disturb", "Turn Zeus on in the list, then ask again.", ok = false, choices = listOf(open))
        }
        val before = nm.currentInterruptionFilter
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Outcome("Do not disturb ${if (on) "on" else "off"}", undo = { it.getSystemService(NotificationManager::class.java).setInterruptionFilter(before) })
    }

    // ---- people: one tap first ----

    private sealed interface Who {
        data class Found(val name: String, val id: Long) : Who
        data class Reply(val outcome: Outcome) : Who
    }

    /** The one contact, or a reply: Contacts permission, nobody, or "Which Amma?" (a pick is learned). */
    private fun person(c: Context, who: String, retry: Cmd, next: (Context, String, Long) -> Outcome): Who {
        if (!Calls.canContacts(c)) return Who.Reply(Outcome("Zeus needs your contacts to find $who", needs = Manifest.permission.READ_CONTACTS, retry = retry))
        val name = Corrections.lookup(HermesStore(c).book(), "contact", who) ?: who
        Calls.target(c, name)?.let { return Who.Found(it.name, it.contactId) }
        val found = Calls.matches(c, name)
        if (found.isEmpty()) return Who.Reply(Outcome("No contact called \"$who\"", ok = false))
        return Who.Reply(Outcome("Which ${who.replaceFirstChar(Char::uppercase)}?", choices = found.map { (id, n) ->
            Choice(n) { ctx -> HermesStore(ctx).learn("contact", who, n); next(ctx, n, id) }
        }))
    }

    private fun call(c: Context, cmd: CallCmd): Outcome = when (val w = person(c, cmd.who, cmd) { ctx, name, id -> confirmCall(ctx, cmd, name, id) }) {
        is Who.Reply -> w.outcome
        is Who.Found -> confirmCall(c, cmd, w.name, w.id)
    }

    private fun wire(via: String): String? = when (via) {
        "whatsapp_video" -> CallRouting.VIDEO
        "whatsapp" -> CallRouting.VOICE
        "phone" -> CallRouting.PHONE
        else -> null
    }

    /** "WhatsApp video call Amma?" with the other way one tap away. The tap runs it through Fates as his. */
    private fun confirmCall(c: Context, cmd: CallCmd, name: String, id: Long, forced: String? = null): Outcome {
        val core = BrainCore.get(c)
        val raw = mapOf("who" to name, "contact_id" to "$id") + ((forced ?: wire(cmd.via))?.let { mapOf("via" to it) } ?: emptyMap())
        val step = when (val v = Validate.step(ProposalKind.PLACE_CALL, raw, core.local, System.currentTimeMillis())) {
            is dev.phonerecall.brain.Check.Ok -> v.step
            is dev.phonerecall.brain.Check.Bad -> return Outcome("Can't call $name", v.reason, ok = false)
        }
        val via = step.payload.getValue("via")
        val country = Calls.target(c, name, id)?.country
        val how = when (via) { CallRouting.VIDEO -> "WhatsApp video"; CallRouting.VOICE -> "WhatsApp voice"; else -> "Phone call" }
        val where = when (country) { "IN" -> ", India number"; "US" -> ", US number"; else -> "" }
        val detail = listOfNotNull("$how$where", step.payload["there"]?.let { "$it there" }, step.payload["awake"]).joinToString(". ")
        val other = if (via == CallRouting.PHONE) CallRouting.VIDEO else CallRouting.PHONE
        return Outcome(
            "${step.summary.substringBefore(" (")}?", detail,
            confirm = Choice("Call") { ctx ->
                when (val r = BrainCore.get(ctx).fates.own(listOf(RawStep(ProposalKind.PLACE_CALL, step.payload)), null)) {
                    is ProposeResult.Created -> (r.decision as? Decision.Done)?.let { Outcome(it.proposal.steps.first().result ?: "Calling $name") }
                        ?: Outcome("The call didn't start", (r.decision as? Decision.Failed)?.reason.orEmpty(), ok = false)
                    is ProposeResult.Invalid -> Outcome("The call didn't start", r.reason.substringAfter(": "), ok = false)
                }.also { log(cmd, if (it.ok) "confirmed" else "failed") }
            },
            // Picking the other way is remembered for this person.
            choices = listOf(Choice(if (other == CallRouting.PHONE) "Phone call instead" else "WhatsApp instead") { ctx ->
                Calls.rememberVia(ctx, id, other); confirmCall(ctx, cmd, name, id, forced = other)
            }),
        )
    }

    private fun message(c: Context, cmd: MessageCmd): Outcome = when (val w = person(c, cmd.who, cmd) { _, name, id -> confirmMessage(cmd, name, id) }) {
        is Who.Reply -> w.outcome
        is Who.Found -> confirmMessage(cmd, w.name, w.id)
    }

    private fun confirmMessage(cmd: MessageCmd, name: String, id: Long): Outcome {
        val wa = cmd.via == "whatsapp"
        return Outcome(
            "Text $name${if (wa) " on WhatsApp" else ""}?",
            listOfNotNull(if (wa) "WhatsApp" else "SMS", cmd.body?.let { "\"$it\"" }, "You press send there").joinToString(". "),
            confirm = Choice(if (wa) "Open WhatsApp" else "Write it") { ctx ->
                runCatching { Calls.message(ctx, id, name, wa, cmd.body) }.fold({ Outcome(it) }, { Outcome("Couldn't open it", it.message.orEmpty(), ok = false) })
                    .also { log(cmd, if (it.ok) "confirmed" else "failed") }
            },
            choices = listOf(Choice(if (wa) "SMS instead" else "WhatsApp instead") { _ -> confirmMessage(cmd.copy(via = if (wa) "sms" else "whatsapp"), name, id) }),
        )
    }

    // ---- calendar ----

    private fun agenda(c: Context, cmd: AgendaCmd): Outcome {
        if (!granted(c, Manifest.permission.READ_CALENDAR)) return Outcome("Zeus needs your calendar for that", needs = Manifest.permission.READ_CALENDAR, retry = cmd)
        val zone = ZoneId.systemDefault()
        val from = cmd.day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = cmd.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = dev.phonerecall.mind.BackgroundReads(c, BrainCore.get(c)).relevantEvents(from, to).sortedBy { it.begin }
        val today = java.time.LocalDate.now(zone)
        val label = when (cmd.day) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> cmd.day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)) }
        if (events.isEmpty()) return Outcome("Nothing on your calendar $label")
        val body = events.joinToString("\n") { e ->
            (if (e.allDay) "All day" else Instant.ofEpochMilli(e.begin).atZone(zone).format(CLOCK)) + "  " + e.title + (e.place?.let { ", $it" } ?: "")
        }
        return Outcome("${events.size} ${if (events.size == 1) "thing" else "things"} $label", body = body)
    }

    // ---- corrections ----

    private fun correct(c: Context, meant: String): Outcome {
        val (redo, teach) = Corrections.redo(last, meant) ?: return Outcome("Nothing to correct", "Say the whole command again.", ok = false)
        teach?.let { (kind, phrase) -> HermesStore(c).learn(kind, phrase, redo.let { r -> (r as? OpenCmd)?.target ?: (r as? CallCmd)?.who ?: (r as? MessageCmd)?.who ?: meant }) }
        val o = run(c, redo)
        return if (teach != null && o.ok) o.copy(detail = listOf(o.detail, "Learned for next time.").filter { it.isNotBlank() }.joinToString(" ")) else o
    }
}
