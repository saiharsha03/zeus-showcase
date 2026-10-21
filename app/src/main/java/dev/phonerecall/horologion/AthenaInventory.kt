package dev.phonerecall.horologion

import android.content.Context
import android.content.pm.PackageManager
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.BrainSettings
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.models.Features
import dev.phonerecall.models.ModelChoice
import dev.phonerecall.mind.MindPrompts
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.TopTierCaps

/** Athena's side of the inventory, read on the phone. Blocking (it asks WorkManager and the package manager). */
object AthenaInventory {
    /** His pick from Horologion's Models row: only a model the feature can use. Returns false when it can't. */
    fun setModel(key: String, id: String): Boolean {
        val f = Features.of(key) ?: return false
        if (id !in ModelChoice.book.options(f)) return false
        ModelChoice.book.set(f, id)
        return true
    }

    fun undoModel(key: String): Boolean = Features.of(key)?.let { ModelChoice.book.undo(it) } ?: false

    private fun usd(cents: Long) = "$" + if (cents % 100 == 0L) "${cents / 100}" else "%.2f".format(cents / 100.0)

    fun facts(c: Context, settings: BrainSettings, heartbeatOn: Boolean): AthenaFacts {
        val core = BrainCore.get(c)
        val tools = runCatching { core.mindHost.locked { athenaSpec(emptyList(), false).tools.map { it.name to it.description } } }.getOrDefault(emptyList())
        val pkg = runCatching { c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        val perms = pkg?.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.map { it.removePrefix("android.permission.") to (c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) }
        val brief = dev.phonerecall.brief.BriefStore.settings(c)
        val moments = dev.phonerecall.moments.MomentsCore.prefs.value
        val touches = dev.phonerecall.qol2.Touches.load(c)
        val rhythms = dev.phonerecall.rhythms.RhythmsStore.state.value.settings
        val odyssey = dev.phonerecall.odyssey.OdysseyStore.state.value.settings
        return AthenaFacts(
            tools = tools,
            circle = Protege.entries.map { Triple(it.display, ModelChoice.of(Features.protege(it)), it.role) } + Triple("Metis", ModelChoice.of(Features.METIS), "Her advisor, before hard decisions"),
            models = listOf(
                "Food photos (Iaso)" to BrainModel.SONNET.label,
            ),
            session = runCatching {
                core.mindHost.locked {
                    currentSession()?.let {
                        "context: ${contextPercent()}% · new session at 4 AM (once he's quiet)" +
                            " · last memory flush: " + (lastFlush()?.let { (at, how) -> "${Horologion.at(at, java.time.ZoneId.systemDefault())} ($how)" } ?: "none yet")
                    }
                }
            }.getOrNull(),
            tasks = runCatching { core.mindHost.mind.tasks().takeLast(10) }.getOrDefault(emptyList()),
            picks = Features.ALL.map { f ->
                val book = ModelChoice.book
                ModelPick(f.key, f.label, book.of(f), book.options(f), book.undoable(f), f.default)
            },
            caps = listOf(
                "Monthly cap" to "$${settings.spend.capUsd}",
                "Metis and Annabeth" to "${usd(TopTierCaps.TOGETHER_CENTS)} together, Metis ${usd(TopTierCaps.METIS_CENTS)}",
                "A scheduled run" to "at most $1.50",
                "Dream" to "at most ${usd(dev.phonerecall.oneiroi.Dreamer.CAP_MICROS / 10_000)} a night",
            ),
            auto = ProposalKind.autoCandidates.map { Switch("auto.${it.wire}", it.label, "Runs without asking, with Undo", it in settings.autoApprove) },
            hosts = NetGate.HOSTS.sorted(),
            permissions = perms,
            switches = listOf(
                Switch("brief", dev.phonerecall.ui.Place.BRIEF.myth, "The morning brief after your alarm", brief.on),
                Switch("heartbeat", "Heartbeat", "Athena's hourly look at what changed", heartbeatOn),
            ) + dev.phonerecall.moments.MomentKind.entries.filter { it.context }.map { Switch("moment.${it.name}", it.label, "A Kairos helper card", it !in moments.never) } +
                dev.phonerecall.qol2.Touch.entries.map { Switch("touch.${it.name}", it.label, "A small touch: ${it.about}", it !in touches.off) } + listOf(
                Switch("info.rhythms", dev.phonerecall.ui.Place.RHYTHMS.myth, "Learns when you open apps and call home (change it in Themis)", rhythms.on),
                Switch("info.odyssey", dev.phonerecall.ui.Place.PLACES.myth, "Places, commutes and the driving report (change it in Themis)", odyssey.on && !odyssey.paused),
                Switch("info.agency", "Card buttons", "Each card shows its notification's own buttons (Call back, Reply) plus Directions, Join, Track and Copy code; only your tap fires them. Call back directly: change it in Themis", dev.phonerecall.brain.Agency.callDirectly(c)),
            ),
            widgets = dev.phonerecall.widgets.WidgetBus.placed(c).filter { it.second > 0 }.map { (k, n) -> k.label(false) to n },
            store = MindPrompts.STORE_NAME,
            oneiroi = listOf(
                "Reflect" to "After a long job ends: what it learned, as notes",
                "Refs" to "Keeps names Athena wouldn't recognise (a nickname for the car, say)",
                "Dream" to "3 AM, Athena's Zeus · dream run: tidies the facts through two tools",
                "Subconscious" to "Every few days: sweeps stale or doubled memory",
                "Humanizer" to "Strips AI-isms from drafts he sends",
            ),
            fresh = runCatching {
                val g = dev.phonerecall.fresh.GenesisHub.get(c)
                if (g.engine.started == null) emptyList() else listOf(
                    Triple("Cloud reset", "Once, on her first sync after the fresh start: archives her old sessions and her own schedules, empties her memory store", if (dev.phonerecall.fresh.FreshCloud.due(core.mindKv)) "due" else "done"),
                    Triple("Interview plan", "Annabeth, on Fable for this run only, writes Genesis's getting-to-know-you questions; her own model comes back after", g.state.value.planNote ?: g.engine.stage.name.lowercase()),
                )
            }.getOrDefault(emptyList()),
        )
    }

    /** Flips one of the switches above. Only existing switches; the info ones are changed in their own screens. */
    fun toggle(c: Context, key: String, on: Boolean, toggleAuto: (ProposalKind) -> Unit, setHeartbeat: (Boolean) -> Unit): Boolean {
        when {
            key == "heartbeat" -> setHeartbeat(!on)
            key.startsWith("auto.") -> { ProposalKind.of(key.removePrefix("auto."))?.takeIf { it in ProposalKind.autoCandidates }?.let(toggleAuto) ?: return false }
            key == "brief" -> dev.phonerecall.brief.BriefStore.setSettings(c, dev.phonerecall.brief.BriefStore.settings(c).copy(on = !on))
            key.startsWith("moment.") -> dev.phonerecall.moments.MomentKind.entries.firstOrNull { it.name == key.removePrefix("moment.") }?.let { dev.phonerecall.moments.MomentsCore.setOn(c, it, !on) } ?: return false
            key.startsWith("touch.") -> dev.phonerecall.qol2.Touch.entries.firstOrNull { it.name == key.removePrefix("touch.") }?.let { dev.phonerecall.qol2.Touches.setOn(c, it, !on) } ?: return false
            else -> return false
        }
        BrainCore.get(c).audit.log("The owner", "Horologion", "${if (on) "off" else "on"}: $key")
        return true
    }
}
