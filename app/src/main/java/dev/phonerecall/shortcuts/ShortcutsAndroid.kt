package dev.phonerecall.shortcuts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Step
import dev.phonerecall.clio.Clio
import dev.phonerecall.mind.PrefsKv
import dev.phonerecall.mind.stateJson
import java.time.ZoneId

/** Learned shortcuts and ideas on the phone: triggers in, rules fired, the evidence Athena reads, her offers as grouped cards. */
object ShortcutsCore {
    @Volatile private var book: ShortcutBook? = null

    fun book(c: Context): ShortcutBook = book ?: synchronized(this) {
        book ?: ShortcutBook(PrefsKv(c.applicationContext, "shortcuts"), KeystoreSealer("zeus.aegis.shortcuts")).also { book = it }
    }

    /** Something happened (an arrival, a kickoff): kept for the daily pass, and every rule it fires runs. Call off the main thread. */
    fun trigger(c: Context, key: String, label: String) {
        runCatching {
            val b = book(c)
            // One arrival or kickoff is one trigger, however many times the system reports it.
            if (b.load().triggers.any { it.key == key && System.currentTimeMillis() - it.at < 3_600_000L }) return
            b.seenTrigger(Seen(System.currentTimeMillis(), key, label))
            for (r in b.fire(key)) fire(c, r)
        }.onFailure { Clio.error("shortcuts", it) }
    }

    /** Set while Zeus acts on its own (a home routine): what it does then isn't something he did. */
    private val quiet = ThreadLocal<Boolean>()

    fun <T> quietly(block: () -> T): T { quiet.set(true); try { return block() } finally { quiet.remove() } }

    /** What he did through Zeus: a candidate action for a pattern (music, an alarm, a Hestia device, a tap on a tile). */
    fun acted(c: Context, step: Step) {
        if (quiet.get() == true) return
        val s = Learn.seen(step) ?: return
        runCatching { book(c).acted(s.copy(at = System.currentTimeMillis())) }
    }

    /** A rule fires: an app becomes a tap on a notification; the rest a Fates card, or done with Undo when he chose that and it may be. */
    private fun fire(c: Context, r: Rule) {
        Clio.info("shortcuts", "fired", "${r.id} · ${r.mode.name.lowercase()}")
        val core = BrainCore.get(c)
        when (r.action.substringBefore(':')) {
            "open" -> notify(c, r)
            "alarm", "music", "home" -> {
                val kind = when (r.action.substringBefore(':')) { "alarm" -> ProposalKind.SET_ALARM; "music" -> ProposalKind.PLAY_MUSIC; else -> ProposalKind.HOME_CONTROL }
                val res = core.fates.propose(listOf(RawStep(kind, r.args - "light")), r.actionLabel.replaceFirstChar { it.uppercase() }, null, "Learned shortcut")
                if (res is ProposeResult.Created && r.mode == Mode.AUTO && Learn.autoAllowed(r)) core.fates.confirm(res.proposal.id, core.fates.bind(res.proposal))
                if (res is ProposeResult.Invalid) Clio.warn("shortcuts", "not proposed", res.reason)
                core.changed()
            }
        }
    }

    private fun notify(c: Context, r: Rule) {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val launch = c.packageManager.getLaunchIntentForPackage(r.action.removePrefix("open:")) ?: return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Learned shortcuts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Your own habits, one tap away" })
        val pi = PendingIntent.getActivity(c, r.id.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(48_000 + (r.id.hashCode() and 0x3ff), NotificationCompat.Builder(c, CHANNEL).setSmallIcon(dev.phonerecall.R.drawable.ic_owl)
            .setContentTitle(r.actionLabel.replaceFirstChar { it.uppercase() }).setContentText("You usually do this when ${r.triggerLabel}. Tap to open.")
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(30 * 60_000L).build())
    }

    /** A Yes row (a shortcut, or an idea that is a rule): the rule, from the row's payload. Saying it twice keeps one. */
    fun accept(c: Context, p: Map<String, String>): Pair<String, String> {
        // A light or switch after leaving, arriving, sunset or bedtime becomes a Hestia routine, editable there.
        dev.phonerecall.hestia.HestiaRoutines.adopt(c, p.getValue("trigger"), p.getValue("action"), p.getValue("action_label"))?.let { return it }
        val args = p.filterKeys { it.startsWith("a.") }.mapKeys { it.key.removePrefix("a.") }
        val r = book(c).accept(Seen(0, p.getValue("trigger"), p.getValue("trigger_label")), Seen(0, p.getValue("action"), p.getValue("action_label"), args))
        Clio.info("shortcuts", "learned", r.id)
        return "Learned: ${r.actionLabel} when ${r.triggerLabel}" to r.id
    }

    fun undo(c: Context, id: String) { if (dev.phonerecall.hestia.HestiaRoutines.owns(id)) dev.phonerecall.hestia.HestiaRoutines.remove(c, id) else book(c).remove(id) }

    /** A Yes on an idea that needs code: her request waits, drafted, in Prometheus; he reads it and taps Send. */
    fun draft(c: Context, p: Map<String, String>): String {
        dev.phonerecall.prometheus.PrometheusDraft.set(c, "${p["title"]}\n\nProblem: ${p["problem"]}\n\nDone when: ${p["acceptance"]}")
        Clio.info("shortcuts", "idea drafted for Prometheus")
        return "Drafted for Prometheus: open it to read and send"
    }

    /**
     * His answer on one row of a grouped card. Not now, Never and No count at once; Yes rows apply when the whole card is
     * answered (Fates confirms them together, with Undo), and a card with no Yes closes as rejected.
     */
    fun answer(c: Context, proposal: Long, row: Int, value: String) {
        val core = BrainCore.get(c)
        val p = core.db.get(proposal) ?: return
        val s = p.steps.getOrNull(row) ?: return
        val b = book(c)
        b.answer("$proposal:$row", value)
        when (value) {
            "notnow" -> b.notNow("${s.payload["trigger"]}>${s.payload["action"]}")
            "never" -> b.never("${s.payload["trigger"]}>${s.payload["action"]}")
            "no" -> b.decline(s.payload["title"].orEmpty())
        }
        val answers = b.answers(proposal)
        if (p.steps.indices.all { it in answers }) {
            p.steps.forEachIndexed { i, st -> if ((answers[i] == "yes") != st.selected) core.fates.toggle(proposal, i) }
            val now = core.db.get(proposal) ?: return
            if (answers.values.any { it == "yes" }) core.fates.confirm(proposal, core.fates.bind(now)) else core.fates.reject(proposal)
            b.clearAnswers(proposal)
            Clio.info("shortcuts", "card answered", "${answers.values.count { it == "yes" }} yes of ${answers.size}")
        }
        core.changed()
    }

    fun answers(c: Context, proposal: Long): Map<Int, String> = book(c).answers(proposal)

    /** App opens from UsageStats: each app brought to the front, a repeat within two minutes counted once. */
    private fun opens(c: Context, from: Long, to: Long): List<Seen> = runCatching {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val pm = c.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        val q = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = ArrayList<Seen>()
        val labels = HashMap<String, String>()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName ?: continue
            if (pkg == c.packageName || pkg in home || pkg.startsWith("com.android.systemui")) continue
            if (out.lastOrNull()?.let { it.key == "open:$pkg" && e.timeStamp - it.at < 120_000L } == true) continue
            val label = labels.getOrPut(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            out += Seen(e.timeStamp, "open:$pkg", "open $label")
        }
        out
    }.getOrDefault(emptyList())

    /** The evidence she judges (read_habits): the pairs, his rules, what he said Never, Not now and No to, and the killed list. */
    fun evidence(c: Context, now: Long = System.currentTimeMillis()): List<Evidence> {
        val d = book(c).load()
        val names = dev.phonerecall.olympia.Teams.all.associate { it.key to it.name }
        val kickoffs = dev.phonerecall.olympia.OlympiaStore.load(c).matches.filter { it.start in (now - Learn.KEEP_MS)..now }
            .flatMap { m -> m.teams.map { t -> Seen(m.start, "match:$t", "a ${names[t] ?: t} match starts") } }
        val triggers = (d.triggers + kickoffs).distinctBy { it.key to it.at / 60_000L }
        return Learn.evidence(triggers, opens(c, now - 7 * 86_400_000L, now) + d.acted, ZoneId.systemDefault())
    }

    fun habitsJson(c: Context): String {
        val d = book(c).load()
        val ev = evidence(c)
        return stateJson.writeValueAsString(
            mapOf(
                "pairs" to ev.map { e ->
                    mapOf("trigger" to e.trigger, "when" to e.triggerLabel, "action" to e.action, "does" to e.actionLabel, "times" to e.times, "days" to e.days,
                        "hours" to e.hours, "weekdays" to e.weekdays, "minutes_after" to e.minutesAfter, "last_seen" to java.time.Instant.ofEpochMilli(e.lastSeen).toString(),
                        "not_now_since" to d.notNow[e.key]?.let { java.time.Instant.ofEpochMilli(it).toString() })
                },
                "rules" to d.rules.map { "${it.actionLabel} when ${it.triggerLabel}" },
                "home_routines" to dev.phonerecall.hestia.HestiaRoutines.summaries(c),
                "never" to d.never.toList(),
                "declined_ideas" to d.declined.toList() + Killed.DECLINED,
                "killed" to Killed.ALL,
                "allowed_actions" to Learn.ACTIONS.toList(),
            ),
        )
    }

    /** propose_shortcuts: her picks, checked by the hard rules, as ONE card with a Yes / Not now / Never row each. */
    @Suppress("UNCHECKED_CAST")
    fun offerShortcuts(c: Context, input: Map<String, Any?>): String {
        val picks = (input["items"] as? List<Map<String, Any?>>).orEmpty().map { Pick(it["trigger"]?.toString().orEmpty(), it["action"]?.toString().orEmpty(), it["why"]?.toString()?.take(140).orEmpty()) }
        if (picks.isEmpty()) return "Nothing offered."
        val (ok, no) = Offer.shortcuts(picks, evidence(c), book(c).load())
        if (ok.isEmpty()) return "Nothing offered. " + no.joinToString("; ")
        val steps = ok.map { (p, e) ->
            RawStep(ProposalKind.LEARN_SHORTCUT, linkedMapOf("trigger" to e.trigger, "trigger_label" to e.triggerLabel, "action" to e.action, "action_label" to e.actionLabel, "why" to p.why) + e.args.mapKeys { "a.${it.key}" })
        }
        val title = if (ok.size == 1) "You usually ${ok[0].second.actionLabel} when ${ok[0].second.triggerLabel}. Want me to do it for you?" else "${ok.size} things you usually do. Want me to do them for you?"
        val r = BrainCore.get(c).fates.propose(steps, title, null, "Athena")
        BrainCore.get(c).changed()
        return when (r) {
            is ProposeResult.Created -> "One card with ${ok.size} row(s)." + if (no.isEmpty()) "" else " Left out: " + no.joinToString("; ")
            is ProposeResult.Invalid -> "Not offered: ${r.reason}"
        }
    }

    /** propose_ideas: her ideas, filtered (killed, declined, already there), as ONE card with Yes / No each. */
    @Suppress("UNCHECKED_CAST")
    fun offerIdeas(c: Context, input: Map<String, Any?>): String {
        fun s(m: Map<String, Any?>, k: String, n: Int) = m[k]?.toString()?.trim()?.take(n)?.takeIf { it.isNotEmpty() }
        val ideas = (input["ideas"] as? List<Map<String, Any?>>).orEmpty().map { m ->
            Idea(s(m, "title", 80).orEmpty(), s(m, "why", 160).orEmpty(), s(m, "trigger", 120), s(m, "trigger_label", 80), s(m, "action", 160), s(m, "action_label", 80), s(m, "problem", 800), s(m, "acceptance", 400))
        }
        if (ideas.isEmpty()) return "Nothing offered."
        val (ok, no) = Offer.ideas(ideas, Killed.ALL, book(c).load())
        if (ok.isEmpty()) return "Nothing offered. " + no.joinToString("; ")
        val steps = ok.map { i ->
            RawStep(ProposalKind.IDEA, buildMap {
                put("title", i.title); put("why", i.why)
                if (i.rule) { put("trigger", i.trigger!!); put("trigger_label", i.triggerLabel ?: i.trigger); put("action", i.action!!); put("action_label", i.actionLabel ?: i.action) }
                else { put("problem", i.problem!!); put("acceptance", i.acceptance!!) }
            })
        }
        val r = BrainCore.get(c).fates.propose(steps, if (ok.size == 1) "An idea: ${ok[0].title}" else "${ok.size} ideas from your week", null, "Athena")
        BrainCore.get(c).changed()
        return when (r) {
            is ProposeResult.Created -> "One card with ${ok.size} idea(s)." + if (no.isEmpty()) "" else " Left out: " + no.joinToString("; ")
            is ProposeResult.Invalid -> "Not offered: ${r.reason}"
        }
    }

    private const val CHANNEL = "learned_shortcuts"
}
