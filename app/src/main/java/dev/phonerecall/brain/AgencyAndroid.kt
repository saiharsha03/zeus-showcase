package dev.phonerecall.brain

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.service.notification.StatusBarNotification

/**
 * The Android half of one-tap agency: reading a notification's actions into [LiveActions], and firing one when
 * The owner taps it on a card. Only a tap reaches [fire] or [send]; Athena never calls them.
 */
object Agency {
    private const val CLIP_LABEL = "Zeus code"
    private const val CLEAR_MS = 60_000L

    private fun prefs(c: Context) = c.getSharedPreferences("agency", Context.MODE_PRIVATE)

    /** Themis: "Call back" places the call (on by default, the owner's choice); off opens the dial screen. Needs CALL_PHONE, asked on first tap. */
    fun callDirectly(c: Context): Boolean = prefs(c).getBoolean("call_directly", true)

    fun setCallDirectly(c: Context, on: Boolean) = prefs(c).edit().putBoolean("call_directly", on).apply()

    /** Themis: what a message asks for and its reply chips may go to Haiku (sanitized, never off-limits topics). On by default. */
    fun haikuAsks(c: Context): Boolean = prefs(c).getBoolean("haiku_asks", true)

    fun setHaikuAsks(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haiku_asks", on).apply()

    /** The tap needs the phone-call permission first: Call back, set to dial directly, without CALL_PHONE yet. */
    fun wantsCallPermission(c: Context, payload: String): Boolean {
        val (note, i) = parse(payload) ?: return false
        return LiveActions.get(note, i)?.smart is Smart.Dial && callDirectly(c) && !canCallDirectly(c)
    }

    fun canCallDirectly(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Called by the listener for a notification that passed its filters (his accounts, Telegram, Temu). Never stores anything. */
    fun posted(c: Context, sbn: StatusBarNotification, n: Incoming) {
        val no = sbn.notification ?: return
        val all = no.actions.orEmpty().toList()
        val specs = all.map { a ->
            ActionSpec(
                a.title?.toString().orEmpty(), a.actionIntent != null,
                freeForm = a.remoteInputs.orEmpty().any { it.allowFreeFormInput },
                contextual = a.isContextual,
            )
        }
        // The system's smart replies come as contextual actions titled with the reply itself.
        val suggested = all.filter { it.isContextual && it.remoteInputs.orEmpty().isNotEmpty() }.mapNotNull { it.title?.toString() }
        val app = AppActions.pick(specs).map { i ->
            val a = all[i]
            val input = a.remoteInputs.orEmpty().firstOrNull { it.allowFreeFormInput }
            LiveAction(specs[i].label.trim().take(20), a.actionIntent, input, choices = input?.choices.orEmpty().map { it.toString() } + suggested)
        }
        val kind = AppKinds.of(n.pkg, n.app)
        val who = Senders.of(n, kind)
        val number = if (kind == AppKind.DIALER && who?.missedCall == true) number(c, no, who) else null
        number?.let { LiveActions.caller(n.key, it) }
        val smart = SmartActions.of(n, kind, who, number, app.map { it.label }) { installed(c, it) }
            .map { (label, s) -> LiveAction(label, smart = s) }
        // What the message asks for (rules here; Nano or Haiku later in BrainCore.ask): a button first, and its chips on the reply.
        val asked = if (chatty(kind)) asked(c, n, who, Asks.detect(n.text)) else Asked()
        LiveActions.put(n.key, n.app, (asked.buttons + merge(app, asked.chips) + smart).distinctBy { it.label.lowercase() }, n.at)
    }

    /** People's chats, where a message can ask him for something. */
    fun chatty(kind: AppKind) = kind == AppKind.CHAT || kind == AppKind.SMS || kind == AppKind.WORK

    /** An ask's buttons and the chips it adds to the reply. */
    data class Asked(val buttons: List<LiveAction> = emptyList(), val chips: List<String> = emptyList())

    private fun merge(actions: List<LiveAction>, chips: List<String>): List<LiveAction> =
        if (chips.isEmpty()) actions else actions.map { if (it.reply) it.withChoices((chips + it.choices).distinctBy { c -> c.lowercase() }) else it }

    /** Nano or Haiku named what an earlier message asks for: its buttons and chips join what the card has. */
    fun answered(c: Context, n: Incoming, ask: Ask?, replies: List<String>) {
        val kind = AppKinds.of(n.pkg, n.app)
        val asked = asked(c, n, Senders.of(n, kind), ask)
        val have = LiveActions.forNote(n.key)
        if (have.isEmpty() && asked.buttons.isEmpty()) return
        LiveActions.put(n.key, n.app, (asked.buttons + merge(have, asked.chips + replies)).distinctBy { it.label.lowercase() }, n.at)
    }

    /** The button for an ask: only installed apps, the one he taps (for this friend) or uses most. */
    private fun asked(c: Context, n: Incoming, who: Who?, ask: Ask?): Asked {
        ask ?: return Asked()
        val friend = who?.name?.let(People::keyOf)
        fun open(o: AppChoice) = LiveAction("Open ${o.name}", smart = Smart.Launch(o.pkg), ask = ask, who = friend)
        val has: (String) -> Boolean = { installed(c, it) }
        return when (ask) {
            Ask.GAME -> {
                val game = Asks.game(n.text, has) ?: return Asked()
                val busy = busy(c, game.pkg)
                Asked(if (busy == Asks.Busy.IN_GAME) emptyList() else listOf(open(game)), Asks.gameReplies(busy))
            }
            Ask.PHOTOS -> Asked(listOf(LiveAction("Share photos", smart = Smart.SharePhotos(n.pkg), ask = ask, who = friend)))
            Ask.LOCATION -> Asked(listOf(LiveAction("Share location", smart = Smart.ShareLocation(n.pkg), ask = ask, who = friend)))
            Ask.SONG -> Asked(listOf(LiveAction("Share song", smart = Smart.ShareSong(n.pkg), ask = ask, who = friend)))
            Ask.ADDRESS -> Asked(chips = runCatching { dev.phonerecall.odyssey.Odyssey.upcomingEvents(c, System.currentTimeMillis()) }.getOrDefault(emptyList())
                .mapNotNull { it.third.trim().takeIf { l -> l.isNotEmpty() && l.length <= 80 } }.take(1))
            Ask.CALL -> who?.name?.let { name -> (SmartActions.number(name) ?: dev.phonerecall.alarms.Phones.of(c, name).firstOrNull()) }
                ?.let { Asked(listOf(LiveAction("Call back", smart = Smart.Dial(it), ask = ask, who = friend))) } ?: Asked()
            else -> Asks.prefer(Asks.APPS[ask].orEmpty(), has, usage(c), Learn.taps(c, ask, friend))?.let { Asked(listOf(open(it))) } ?: Asked()
        }
    }

    /** In that game right now, or busy (a call, driving, late at night), for a game invite. */
    private fun busy(c: Context, game: String): Asks.Busy {
        if (dev.phonerecall.alarms.ForegroundApp.usage(c).current() == game) return Asks.Busy.IN_GAME
        val audio = c.getSystemService(android.media.AudioManager::class.java)
        val onCall = audio?.mode == android.media.AudioManager.MODE_IN_CALL || audio?.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val late = QuietHours.quiet(java.time.ZonedDateTime.now())
        return if (onCall || late || dev.phonerecall.odyssey.Odyssey.driving()) Asks.Busy.BUSY else Asks.Busy.FREE
    }

    /** Foreground time per app over two weeks (Usage access), to pick the app he actually uses. */
    private fun usage(c: Context): Map<String, Long> = runCatching {
        val now = System.currentTimeMillis()
        c.getSystemService(android.app.usage.UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 14 * 86_400_000L, now).mapValues { it.value.totalTimeInForeground }
    }.getOrDefault(emptyMap())

    /** Which app he opens for each ask, overall and per friend (counts, on the phone only). */
    object Learn {
        private fun sp(c: Context) = c.getSharedPreferences("agency_learn", Context.MODE_PRIVATE)
        private fun friend(who: String) = "f" + who.hashCode().toUInt().toString(36)

        fun tapped(c: Context, ask: Ask, who: String?, pkg: String) {
            val keys = listOfNotNull("${ask.wire}|$pkg", who?.let { "${ask.wire}|${friend(it)}|$pkg" })
            val e = sp(c).edit()
            for (k in keys) e.putInt(k, sp(c).getInt(k, 0) + 1)
            e.apply()
        }

        /** Package to weight: a friend's own taps count three times. */
        fun taps(c: Context, ask: Ask, who: String?): Map<String, Int> {
            val out = HashMap<String, Int>()
            val f = who?.let(::friend)
            for ((k, v) in sp(c).all) {
                val n = v as? Int ?: continue
                val parts = k.split('|')
                if (parts.firstOrNull() != ask.wire) continue
                when {
                    parts.size == 2 -> out.merge(parts[1], n, Int::plus)
                    parts.size == 3 && parts[1] == f -> out.merge(parts[2], 3 * n, Int::plus)
                }
            }
            return out
        }
    }

    /** The caller's number: the notification's own person, then a number in its words, then Contacts by name. */
    private fun number(c: Context, no: Notification, who: Who): String? {
        val people = runCatching {
            if (Build.VERSION.SDK_INT >= 33) no.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java)
            else @Suppress("DEPRECATION") no.extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
        }.getOrNull().orEmpty().mapNotNull { it.uri }
        people.firstOrNull { it.startsWith("tel:") }?.let { return Uri.decode(it.removePrefix("tel:")) }
        SmartActions.number(who.name)?.let { return it }
        return dev.phonerecall.alarms.Phones.of(c, who.name).firstOrNull()
    }

    private fun installed(c: Context, pkg: String): Boolean = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** What a tap did. [Reply] asks the card for its inline field, prefilled with Athena's draft when there is one. */
    sealed class Outcome {
        data object Done : Outcome()
        data class Reply(val draft: String?) : Outcome()
        /** The notification went away (or the app cancelled the action) before the tap. */
        data object Gone : Outcome()
        /** The screen opens the photo picker, then shares to [pkg]. */
        data class PickPhotos(val pkg: String) : Outcome()
    }

    /** "<note key>#<index>", the payload a card's button carries. */
    fun payload(note: String, index: Int) = "$note#$index"

    private fun parse(payload: String): Pair<String, Int>? =
        payload.substringAfterLast('#').toIntOrNull()?.let { payload.substringBeforeLast('#') to it }

    /** His tap on a button. A reply only opens the field; everything else runs now, from his tap. */
    fun fire(c: Context, payload: String): Outcome {
        val (note, i) = parse(payload) ?: return Outcome.Gone
        val a = LiveActions.get(note, i) ?: return Outcome.Gone
        if (a.reply) return Outcome.Reply(LiveActions.draftForNote(note)?.let { dev.phonerecall.oneiroi.Humanizer.fix(it).text })
        (a.smart as? Smart.Launch)?.let { l -> a.ask?.let { Learn.tapped(c, it, a.who, l.pkg) } }
        (a.smart as? Smart.SharePhotos)?.let { log(note, a, true); return Outcome.PickPhotos(it.pkg) }
        val ok = a.intent?.let { send(c, it, null) } ?: a.smart?.let { run(c, it) } ?: false
        log(note, a, ok)
        return if (ok) Outcome.Done else Outcome.Gone
    }

    private const val SEP = '\u001f'

    /** A chip's payload: the button and the exact text it sends. */
    fun sayPayload(note: String, index: Int, text: String) = payload(note, index) + SEP + text

    /** His tap on a reply chip: that exact text goes now (the app's RemoteInput), or into the composer for a Text. */
    fun say(c: Context, chip: String): Boolean {
        val text = chip.substringAfter(SEP, "")
        val button = chip.substringBefore(SEP)
        val (note, i) = parse(button) ?: return false
        val a = LiveActions.get(note, i) ?: return false
        return when {
            a.reply -> send(c, button, text)
            a.smart is Smart.Text -> runCatching {
                // Without SEND_SMS the composer opens with the text; he presses send there.
                start(c, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.smart.number)).putExtra("sms_body", text)); log(note, a, true); true
            }.getOrDefault(false)
            else -> false
        }
    }

    /** His Send on the inline field (or a chip): the text goes into the app's own RemoteInput, and the app sends it. */
    fun send(c: Context, payload: String, text: String): Boolean {
        val (note, i) = parse(payload) ?: return false
        val a = LiveActions.get(note, i) ?: return false
        val input = a.input ?: return false
        val pi = a.intent ?: return false
        val t = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, t) })
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val ok = send(c, pi, fill)
        log(note, a, ok)
        if (ok) GroupWish.sent(c, note, t)
        return ok
    }

    /**
     * A group is wishing [name]: his wish, worded his way for that group, becomes the card's draft chip (one tap sends
     * it through the group's reply). Without a reply on the notification, the card copies it and opens WhatsApp.
     */
    fun offerWish(c: Context, thread: String, name: String, note: String, person: Person?) {
        val text = GroupWish.wording(GroupWish.templates(c, thread), GroupWish.address(name, person), GroupWish.emoji(c))
        GroupWish.offer(thread, name, text)
        LiveActions.setDraft("wish:$thread", text)
        val have = LiveActions.forNote(note)
        if (have.none { it.reply }) {
            val pkg = note.split('|').getOrNull(1) ?: "com.whatsapp"
            LiveActions.put(note, LiveActions.app(note) ?: "WhatsApp", listOf(LiveAction("Copy wish, open chat", smart = Smart.CopyOpen(text, pkg))) + have, System.currentTimeMillis())
        }
    }

    // ---- plans from threads ----

    /** The thread a message belongs to: the same key the senses use (a group, or one person in one app). */
    fun threadOf(n: Incoming): String? {
        val who = Senders.of(n)
        return when {
            n.isGroup && n.conversation != null -> People.keyOf("${n.pkg}|group|${n.conversation}")
            who != null -> People.keyOf("${n.pkg}|${who.name}")
            else -> null
        }
    }

    /** A message in a chat: the thread's plan may start, move or end. */
    fun heard(c: Context, n: Incoming) {
        val thread = threadOf(n) ?: return
        val from = (n.sender ?: Senders.of(n)?.name)?.let(Senders::clean)
        val plan = PlanBook.heard(thread, ThreadLine(n.at, n.text, from = from), n.key, java.time.ZonedDateTime.now()) { installed(c, it) }
        if (plan == null && PlanBook.lines(thread).any { it.text.any(Char::isDigit) } && Plans.infer(PlanBook.lines(thread), java.time.ZonedDateTime.now()) != null) {
            BrainCore.get(c).plan(thread)
        }
        PlanWork.schedule(c)
        BrainCore.get(c).changed()
    }

    /** His own line in a chat: "can't" ends the plan; a time he says moves it. */
    fun heardHis(c: Context, sbn: StatusBarNotification) {
        val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull() ?: return
        val last = style.messages.lastOrNull() ?: return
        val title = (style.conversationTitle ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.let(dev.phonerecall.data.MyAccounts::untag) ?: return
        val group = style.isGroupConversation
        val other = style.messages.lastOrNull { it.person != null }?.person?.name?.toString()
        val thread = if (group) People.keyOf("${sbn.packageName}|group|$title") else People.keyOf("${sbn.packageName}|${Senders.clean(other ?: title)}")
        PlanBook.heard(thread, ThreadLine(last.timestamp, last.text?.toString().orEmpty(), his = true), null, java.time.ZonedDateTime.now()) { installed(c, it) }
        BrainCore.get(c).changed()
    }

    /** He picked one reading of an unclear time. */
    fun pickPlan(c: Context, thread: String, at: Long) {
        PlanBook.pick(thread, at)
        PlanWork.schedule(c)
        BrainCore.get(c).changed()
    }

    /** A due plan, ready for Home: the card's words and the note whose buttons it wears. */
    data class PlanCard(val thread: String, val plan: ChatPlan, val note: String, val title: String, val body: String, val until: Long, val picks: List<Pair<String, Long>>)

    /**
     * Plans due now, unless he's already in that game. Each wears its thread's reply (with In / Can't today) and
     * the game's Open button, in memory under "plan#<thread>".
     */
    fun planCards(c: Context, now: Long): List<PlanCard> {
        val zone = java.time.ZoneId.systemDefault()
        val playing = runCatching { dev.phonerecall.alarms.ForegroundApp.usage(c).current() }.getOrNull()
        return PlanBook.due(now).mapNotNull { (thread, plan, src) ->
            val game = plan.game.firstOrNull { installed(c, it.pkg) }
            if (game != null && game.pkg == playing) return@mapNotNull null
            val note = "plan#$thread"
            val reply = LiveActions.forNote(src).firstOrNull { it.reply }
            val buttons = listOfNotNull(game?.let { LiveAction("Open ${it.name}", smart = Smart.Launch(it.pkg), ask = Ask.GAME) }) +
                listOfNotNull(reply?.withChoices(Plans.REPLIES))
            LiveActions.put(note, LiveActions.app(src) ?: "Chat", buttons, now)
            val picks = plan.choices.mapIndexed { i, at ->
                (if (i == plan.choices.lastIndex && plan.choices.size > 1) "In ${((at - (PlanBook.lines(thread).maxOfOrNull { it.at } ?: now)) / 60_000L).coerceAtLeast(1)} min" else Plans.clockWord(at, zone)) to at
            }
            PlanCard(
                thread, plan, note, Plans.title(plan, zone),
                if (plan.choices.isNotEmpty()) "Which time did they mean?" else "From the chat",
                (plan.time ?: now) + 30 * 60_000L, picks,
            )
        }
    }

    /** His own wish in a group (the chat shows his reply): a wording to learn for that group. */
    fun learnHisWish(c: Context, sbn: StatusBarNotification) {
        val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull() ?: return
        val text = style.messages.lastOrNull()?.text?.toString() ?: return
        val wish = BirthdayCatcher.detect(text) ?: return
        val group = style.conversationTitle?.toString()?.let(dev.phonerecall.data.MyAccounts::untag) ?: return
        val thread = People.keyOf("${sbn.packageName}|group|$group")
        GroupWish.learn(c, thread, text, wish.name ?: GroupWish.offered(thread)?.first, his = true)
    }

    private fun send(c: Context, pi: PendingIntent, fill: Intent?): Boolean = runCatching {
        // Zeus is on screen when he taps, so the app may open its own screen (a call, a chat).
        val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        pi.send(c, 0, fill, null, null, null, options)
        true
    }.getOrDefault(false)

    private fun run(c: Context, s: Smart): Boolean = runCatching {
        when (s) {
            is Smart.Dial -> start(c, Intent(if (callDirectly(c) && canCallDirectly(c)) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + s.number)))
            is Smart.Text -> start(c, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + s.number)))
            is Smart.Directions -> start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(s.place))))
            is Smart.Open -> {
                // The airline's app when it handles its own links, else the browser.
                val inApp = s.pkg?.takeIf { installed(c, it) }?.let { Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).setPackage(it) }
                    ?.takeIf { it.resolveActivity(c.packageManager) != null }
                start(c, inApp ?: Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
            }
            is Smart.Launch -> start(c, c.packageManager.getLaunchIntentForPackage(s.pkg) ?: return@runCatching false)
            is Smart.Copy -> copy(c, s.code)
            is Smart.SharePhotos -> return@runCatching false
            is Smart.CopyOpen -> {
                c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Zeus wish", s.text))
                start(c, c.packageManager.getLaunchIntentForPackage(s.pkg) ?: return@runCatching false)
            }
            is Smart.ShareLocation -> {
                val loc = lastLocation(c)
                if (loc == null) start(c, c.packageManager.getLaunchIntentForPackage(s.pkg) ?: return@runCatching false)
                else share(c, s.pkg, "https://maps.google.com/?q=${loc.first},${loc.second}")
            }
            is Smart.ShareSong -> {
                val now = dev.phonerecall.music.MediaFallback(c).now() ?: return@runCatching false
                share(c, s.pkg, listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" \u00b7 "))
            }
        }
        true
    }.getOrDefault(false)

    private fun start(c: Context, i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Text to the chat's app; he picks the chat and presses send there. */
    private fun share(c: Context, pkg: String, text: String) =
        start(c, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg.takeIf { installed(c, it) }))

    /** The photos he picked, to the chat's app. */
    fun sharePhotos(c: Context, pkg: String, uris: List<Uri>): Boolean = uris.isNotEmpty() && runCatching {
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        start(c, i.setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setPackage(pkg.takeIf { installed(c, it) }))
        true
    }.getOrDefault(false)

    /** The phone's last fix, read at his tap and handed only to the app he shares with. */
    private fun lastLocation(c: Context): Pair<Double, Double>? {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(android.location.LocationManager::class.java) ?: return null
        return listOf(android.location.LocationManager.FUSED_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { "%.5f".format(java.util.Locale.US, it.latitude).toDouble() to "%.5f".format(java.util.Locale.US, it.longitude).toDouble() }
    }

    /** The code goes to the clipboard marked sensitive, and is cleared after a minute unless he copied something else. */
    private fun copy(c: Context, code: String) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(CLIP_LABEL, code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val d = cm.primaryClipDescription
                // In the background Android hides the description; clearing then may drop his own copy, which beats leaving a code.
                if (d == null || d.label == CLIP_LABEL) cm.clearPrimaryClip()
            }
        }, CLEAR_MS)
    }

    /**
     * The newest line in a chat notification is his own: he replied (from the shade, a watch, or the app), so
     * its cards are done. Android shows the sender as null, or as the style's user, for his own messages.
     */
    fun hisReply(sbn: StatusBarNotification): Boolean {
        val style = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull() ?: return false
        val last = style.messages.lastOrNull() ?: return false
        val p = last.person ?: return true
        if (p.key != null && p.key == style.user.key) return true
        val name = p.name?.toString()
        return name != null && name == style.user.name?.toString()
    }

    /**
     * Finishes sense cards (and Athena's cards wearing their buttons) through Dismissals, exactly as a swipe would,
     * so they can be brought back. [athena] gives an Athena card's expiry by id. Clio gets the reason and a count.
     */
    fun finish(c: Context, store: SenseStore, athena: (Long) -> Long?, cards: List<String>, why: String, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        for (k in cards.distinct()) {
            val card = store.card(k) ?: continue
            dev.phonerecall.data.Dismissals.add(c, SenseCards.instance(card), card.until, now); n++
            for (id in LiveActions.borrowers(k)) athena(id)?.let { dev.phonerecall.data.Dismissals.add(c, SenseCards.athenaInstance(id), it, now); n++ }
        }
        if (n > 0) clio("finished", "$why: $n card(s)")
        return n
    }

    /** Missed-call cards whose number he has since called back, or answered when it rang again. Needs READ_CALL_LOG. */
    fun calledBack(c: Context, store: SenseStore, people: (String) -> String?, now: Long = System.currentTimeMillis()): List<String> {
        val calls = store.cards(now).filter { it.key.startsWith("call:") }
        if (calls.isEmpty() || c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val log = callLog(c, calls.minOf { it.at })
        return calls.filter { card ->
            val person = card.key.removePrefix("call:")
            val number = LiveActions.callerOf(card.key) ?: person.takeIf { it.length >= 7 && it.all(Char::isDigit) }
                ?: people(person)?.let { dev.phonerecall.alarms.Phones.of(c, it).firstOrNull() }
            number != null && Finish.calledBack(number, card.at, log)
        }.map { it.key }
    }

    private fun callLog(c: Context, since: Long): List<Finish.Call> = runCatching {
        c.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION),
            "${android.provider.CallLog.Calls.DATE} > ?", arrayOf("$since"), null,
        )?.use { cur -> buildList { while (cur.moveToNext()) add(Finish.Call(cur.getString(0).orEmpty(), cur.getInt(1), cur.getLong(2), cur.getLong(3))) } }.orEmpty()
    }.getOrDefault(emptyList())

    /** Where tap lines go: Clio's "agency" area. Tests swap it to read the lines. */
    internal var clio: (event: String, detail: String) -> Unit = { event, detail ->
        if (event == "tap failed") dev.phonerecall.clio.Clio.warn("agency", event, detail) else dev.phonerecall.clio.Clio.info("agency", event, detail)
    }

    /** Clio gets the app and the button's label, never the words, the number or the code. */
    private fun log(note: String, a: LiveAction, ok: Boolean) = clio(if (ok) "tapped" else "tap failed", "${LiveActions.app(note) ?: "an app"}: ${a.label}")
}
