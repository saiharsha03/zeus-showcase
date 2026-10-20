package dev.phonerecall.moments

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Incoming
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.Sealer
import dev.phonerecall.home.ActionRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Moments on the phone: what he taught it (sealed, no-backup), the one card it shows, its actions, the
 * receipts it notices, and reading a box. Everything it does is a proposal made with proposer "Moments";
 * only Fates.confirm (or a kind he put on auto-approve) runs anything.
 */
object MomentsCore {
    /** Kairos ("the right moment"), plain name Moments. */
    const val PROPOSER = "Kairos"
    private const val FILE = "moments.sealed"
    private val _prefs = MutableStateFlow(MomentsPrefs())
    val prefs: StateFlow<MomentsPrefs> = _prefs.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    private val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.moments") }

    /** The card on Olympus right now, so a tap runs exactly the plan it showed. */
    @Volatile var shown: MomentCard? = null

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): MomentsPrefs = synchronized(lock) {
        if (loaded) return _prefs.value
        val f = file(c)
        _prefs.value = if (f.exists()) runCatching {
            sealer.openBytes(f.readBytes())?.let { dev.phonerecall.mind.stateJson.readValue(it, MomentsPrefs::class.java) }
        }.getOrNull() ?: MomentsPrefs() else MomentsPrefs()
        loaded = true
        _prefs.value
    }

    fun update(c: Context, change: (MomentsPrefs) -> MomentsPrefs): MomentsPrefs = synchronized(lock) {
        val next = change(load(c))
        _prefs.value = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(sealer.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(next)))
        tmp.renameTo(f)
        next
    }

    /** The one Moments card for Olympus, from the state the board already has. Pure apart from the battery read. */
    fun card(
        c: Context, now: ZonedDateTime, prefs: MomentsPrefs, d: dev.phonerecall.odyssey.OdysseyData,
        weather: dev.phonerecall.aeolia.WeatherState?, home: dev.phonerecall.hestia.HomeSnapshot?, proposals: List<Proposal>,
    ): MomentCard? {
        val ms = now.toInstant().toEpochMilli()
        val (battery, charging) = battery(c)
        val ctx = MomentContext(
            now = now, odysseyOn = Signals.on(d), atHome = Signals.atHome(d), driving = d.driving,
            parked = Signals.parked(d, ms), departure = Signals.departure(d, now), leftHome = Signals.leftHome(d, ms),
            battery = battery, charging = charging, purchase = prefs.purchase,
            lightsOn = home?.takeIf { it.link == dev.phonerecall.hestia.Link.Connected }?.lightsOn?.map { LightOn(it.id, it.name) }.orEmpty(),
            rainLater = weather?.takeIf { it.hasData }?.let { Signals.rain(it.next, it.nextAt, it.rainChance, ms) },
            pending = proposals.any { it.proposer == PROPOSER && it.status == ProposalStatus.PENDING },
            hazard = hazards(c, d).tomorrow(now),
            nextAlarm = runCatching { dev.phonerecall.brain.AlarmWatch.get(c).next()?.at }.getOrNull()
                ?.let { java.time.Instant.ofEpochMilli(it).atZone(now.zone) },
            staples = staples(c, prefs, ms),
            callSlot = CallHabit.slot(prefs.calls, ms, now.zone),
        )
        return MomentRules.pick(ctx, prefs).also { shown = it }
    }

    /**
     * Snow or ice before tomorrow's first commute, from Aeolia's stored forecast and Odyssey's usual departure
     * (8:00 when it has none). The bedtime and alarm code can read this too.
     */
    fun hazards(c: Context, d: dev.phonerecall.odyssey.OdysseyData = dev.phonerecall.odyssey.OdysseyStore.state.value) = MorningHazardSource { now ->
        val raw = dev.phonerecall.aeolia.WeatherStore.prefs(c).getString("forecast", null) ?: return@MorningHazardSource null
        val fc = dev.phonerecall.aeolia.WeatherParse.forecast(raw) ?: return@MorningHazardSource null
        val day = now.toLocalDate().plusDays(1)
        if (day.dayOfWeek.value >= 6) return@MorningHazardSource null
        val m = dev.phonerecall.odyssey.Commute.stats(d, now.toInstant().toEpochMilli(), now.zone).departures[day.dayOfWeek] ?: (8 * 60)
        MorningHazards.at(fc.hours.map { HourCast(it.at, it.tempC, it.code, it.precipMm, it.snowCm) }, day.atTime(m / 60, m % 60).atZone(now.zone))
    }

    @Volatile private var staplesAt = 0L
    @Volatile private var staplesCache: List<StapleDue> = emptyList()

    /** Groceries history, read at most every 15 minutes: what he ticked bought, and when. */
    private fun staples(c: Context, prefs: MomentsPrefs, now: Long): List<StapleDue> {
        if (now - staplesAt < 15 * 60_000L) return staplesCache
        staplesAt = now
        staplesCache = runCatching {
            val core = BrainCore.get(c)
            val section = core.sections.byName("Groceries") ?: return@runCatching emptyList()
            val entries = core.store.items(section.id).map { ListEntry(it.text, it.createdAt, it.doneAt, !it.done) }
            Staples.due(entries, prefs.names, now)
        }.getOrDefault(emptyList())
        return staplesCache
    }

    /** A confirmed list edit he made: the changed names become aliases ("kandi pappu" is toor dal). */
    fun corrected(c: Context, before: List<String>, after: List<String>) {
        val pairs = Aliases.corrections(before, after)
        if (pairs.isEmpty()) return
        update(c) { p -> p.copy(aliases = pairs.fold(p.names) { a, (f, t) -> a.learn(f, t) }.map) }
    }

    private fun battery(c: Context): Pair<Int?, Boolean> = runCatching {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        (if (level >= 0 && scale > 0) level * 100 / scale else null) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(null to false)

    /** A tap on the Moments card. Call off the main thread. */
    fun act(c: Context, role: ActionRole, payload: String?) {
        val parts = payload?.split('|', limit = 3)?.takeIf { it.size == 3 } ?: return
        val kind = runCatching { MomentKind.valueOf(parts[0]) }.getOrNull() ?: return
        val key = parts[1]
        val audit = BrainCore.get(c).db
        when (role) {
            ActionRole.MOMENT_GO -> {
                val card = shown?.takeIf { it.kind == kind && it.key == key }
                card?.plans?.get(parts[2])?.let { propose(c, it) }
                card?.opens?.get(parts[2])?.let { open(c, it) }
                val teach = card?.teach?.get(parts[2])
                update(c) { p -> (teach?.invoke(p) ?: p).took(kind, key) }
            }
            ActionRole.MOMENT_SNAP -> update(c) { it.took(kind, key) }
            ActionRole.MOMENT_LATER -> update(c) { it.later(kind, key, LocalDate.now().toEpochDay()) }
            ActionRole.MOMENT_NEVER -> { update(c) { it.off(kind) }; audit.log("The owner", "moments off", kind.label) }
            else -> {}
        }
        BrainCore.get(c).changed()
    }

    /** His tap opens another app: "app:<package>" launches it, anything else opens in the browser. */
    fun open(c: Context, target: String) = runCatching {
        val i = if (target.startsWith("app:")) c.packageManager.getLaunchIntentForPackage(target.removePrefix("app:")) else Intent(Intent.ACTION_VIEW, Uri.parse(target))
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
    }

    /** Themis: turns a context moment back on (or off). */
    fun setOn(c: Context, kind: MomentKind, on: Boolean) {
        update(c) { if (on) it.on(kind) else it.off(kind) }
        BrainCore.get(c).db.log("The owner", if (on) "moments on" else "moments off", kind.label)
        BrainCore.get(c).changed()
    }

    fun propose(c: Context, plan: MomentPlan): ProposeResult {
        val core = BrainCore.get(c)
        return core.fates.propose(plan.steps, plan.title, null, proposer = PROPOSER).also { core.changed() }
    }

    /** A receipt or order notification with a frozen or bake item: kept by name for tonight's card. */
    fun notification(c: Context, n: Incoming) {
        Purchases.item("${n.title}\n${n.text}")?.let { item -> update(c) { it.copy(purchase = Purchase(item, n.at)) } }
        // A call from family (saved as Amma, Nanna...): its time only, for the usual call home.
        if (CALL.containsMatchIn(n.text) && dev.phonerecall.brain.AddressTerms.relationOf(n.title)?.first?.label == "family") {
            update(c) { p -> p.copy(calls = (p.calls + n.at).filter { n.at - it < 56 * 86_400_000L }.takeLast(60)) }
        }
    }

    private val CALL = Regex("(?i)\\b((incoming|missed|ongoing)\\s+((voice|video)\\s+)?call|(voice|video)\\s+call)\\b")

    /** On-device OCR of a box photo. The photo and its text stay on the phone. */
    suspend fun read(c: Context, uri: Uri): String {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(c, uri)
        val client = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { client.process(image).await().text } finally { client.close() }
    }

    private const val REWRITE = "You read text from a food package photo (OCR, may be messy). Rewrite only its cooking instructions as short plain lines, one cooking method per line, " +
        "each starting with the method and a colon, like: OVEN: Preheat to 425°F. Bake 18-20 minutes. Let stand 5 minutes. Keep the package's own temperatures and times; never invent any. " +
        "If the text has no cooking instructions, answer exactly NONE."

    /**
     * Only after he taps "Ask Athena" when the rules found nothing: Haiku rewrites the box text into plain lines
     * (through NetGate and the spend cap), and the same rules read those. Blocking; call on IO.
     */
    fun askAthena(c: Context, boxText: String): CookRead? {
        val model = BrainCore.get(c).haiku() ?: return null
        val reply = model.complete("moments", REWRITE, boxText.take(4_000), 300).trim()
        if (reply.isEmpty() || reply.startsWith("NONE")) return null
        return CookParser.parse(reply).takeIf { it.found }?.let { it.copy(name = it.name ?: Foods.name(boxText)) }
    }
}
