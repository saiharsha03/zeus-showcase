package dev.phonerecall.brain

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The rules: which of an app's actions become buttons, and what the phone derives where the app gives none. */
class AgencyRulesTest {
    private fun n(pkg: String, title: String, text: String, app: String = "App") = Incoming("k", pkg, app, title, text, 0L)
    private fun smart(n: Incoming, number: String? = null, app: List<String> = emptyList(), installed: (String) -> Boolean = { true }): List<Pair<String, Smart>> {
        val kind = AppKinds.of(n.pkg, n.app)
        return SmartActions.of(n, kind, Senders.of(n, kind), number, app, installed)
    }

    @Test fun appActionsAreLiveLabelledNotSuggestionsAndAtMostThree() {
        val picked = AppActions.pick(
            listOf(
                ActionSpec("Reply", live = true, freeForm = true), ActionSpec("", live = true), ActionSpec("Mark as read", live = true),
                ActionSpec("Smart reply", live = true, contextual = true), ActionSpec("Dead", live = false), ActionSpec("reply", live = true),
                ActionSpec("Archive", live = true), ActionSpec("Mute", live = true),
            ),
        )
        assertEquals(listOf(0, 2, 6), picked)
    }

    @Test fun missedCallWithoutButtonsGetsCallBackAndText() {
        val call = n("com.google.android.dialer", "Missed call", "+1 (716) 555-0134", "Phone")
        assertEquals(listOf("Call back" to Smart.Dial("+17165550134"), "Text" to Smart.Text("+17165550134")), smart(call))
        // A saved contact: the listener's number from the notification's person or Contacts.
        assertEquals(Smart.Dial("7165550134"), smart(n("com.google.android.dialer", "Missed call", "Amma", "Phone"), number = "7165550134").first().second)
        // No number anywhere: nothing to dial.
        assertTrue(smart(n("com.google.android.dialer", "Missed call", "Amma", "Phone")).isEmpty())
        // The dialer's own Call back and Message win.
        assertTrue(smart(call, app = listOf("Call back", "Message")).isEmpty())
    }

    @Test fun addressGetsDirections() {
        val s = smart(n("com.whatsapp", "Alex", "Party at 1250 Maple Ave, Tonawanda, NY 14150 at 7", "WhatsApp"))
        assertEquals("Directions" to Smart.Directions("1250 Maple Ave, Tonawanda, NY 14150"), s.single())
        assertTrue(smart(n("com.whatsapp", "Alex", "I have 2 tickets for the game", "WhatsApp")).isEmpty())
    }

    @Test fun meetingLinkGetsJoin() {
        val s = smart(n("com.google.android.calendar", "Standup in 10 minutes", "Join: https://meet.google.com/abc-defg-hij", "Calendar"))
        assertEquals("Join" to Smart.Open("https://meet.google.com/abc-defg-hij"), s.single())
        assertEquals(Smart.Open("https://us02web.zoom.us/j/8123456789?pwd=x1"), smart(n("com.google.android.gm", "1:1", "us02web.zoom.us/j/8123456789?pwd=x1")).single().second)
    }

    @Test fun flightGetsCheckInOrTheBoardingPass() {
        val checkIn = smart(n("com.google.android.gm", "Delta", "Check in now for DL 1234 BUF to JFK"))
        assertEquals("Check in" to Smart.Open("https://www.delta.com/mytrips/", "com.delta.mobile.android"), checkIn.single())
        val pass = n("com.google.android.gm", "United", "Your boarding pass for UA 512 is ready")
        assertEquals("Boarding pass" to Smart.Launch("com.united.mobile.android"), smart(pass).single())
        assertEquals("Boarding pass" to Smart.Open("https://www.united.com/en/us/checkin"), smart(pass, installed = { false }).single())
        // A code like "DL 1234" with no travel words is not a flight.
        assertTrue(smart(n("com.whatsapp", "Alex", "AA 100 batteries please", "WhatsApp")).isEmpty())
    }

    @Test fun trackingNumberGetsTrack() {
        assertEquals("Track" to Smart.Open("https://www.ups.com/track?tracknum=1Z999AA10123456784"), smart(n("com.google.android.gm", "UPS", "Shipped: 1Z999AA10123456784")).single())
        assertEquals(Smart.Open("https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899223197428490"), smart(n("com.google.android.gm", "USPS", "Tracking 9400111899223197428490")).single().second)
        assertEquals(Smart.Open("https://www.fedex.com/fedextrack/?trknbr=123456789012"), smart(n("com.google.android.gm", "FedEx", "FedEx 123456789012 is on its way")).single().second)
        // The app's own Track wins.
        assertTrue(smart(n("com.google.android.gm", "UPS", "Shipped: 1Z999AA10123456784"), app = listOf("Track package")).isEmpty())
    }

    @Test fun billOpensTheBankAppOnlyWhenItIsInstalled() {
        val bill = n("com.google.android.gm", "Chase", "Your Chase statement is ready. Payment due Nov 3")
        assertEquals("Open Chase" to Smart.Launch("com.chase.sig.android"), smart(bill).single())
        assertTrue(smart(bill, installed = { false }).isEmpty())
        assertEquals("Open Capital One" to Smart.Launch("com.konylabs.capitalone"), smart(n("com.google.android.gm", "Capital One", "Your bill is ready")).single())
    }

    @Test fun chipsAreTheDraftThenTwoShortOnes() {
        assertEquals(listOf("Sure, 7 works for me", "Yes", "Running late"), Replies.chips("Sure, 7 works for me", listOf("Yes", "Running late", "No"), missedCall = false))
        assertEquals(Replies.GENERIC, Replies.chips(null, emptyList(), missedCall = false))
        assertEquals(Replies.MISSED_CALL, Replies.chips(" ", emptyList(), missedCall = true))
        // The draft isn't offered twice, and long suggestions aren't chips.
        assertEquals(listOf("on my way", "Call you in 10"), Replies.chips("on my way", listOf("x".repeat(60)), missedCall = false))
        assertEquals("Send \u201cSure, 7 works for me\u201d", Replies.label("Sure, 7 works for me", draft = true))
        assertEquals("Yes", Replies.label("Yes", draft = false))
    }

    @Test fun otpCodeIsReadOutOfTheMessage() {
        assertEquals("482913", SmartActions.code("Your verification code is 482913"))
        assertEquals("123456", SmartActions.code("G-123456 is your Google verification code"))
        assertEquals("123456", SmartActions.code("Your code: 123-456"))
    }

    @Test fun removedNotificationTakesItsButtonsAlong() {
        LiveActions.clear()
        LiveActions.put("n1", "WhatsApp", listOf(LiveAction("Reply"), LiveAction("Mark as read")), 0L)
        LiveActions.link("msg:amma", "n1")
        assertEquals(listOf("Reply", "Mark as read"), LiveActions.forCard("msg:amma").map { it.label })
        assertEquals(LiveActions.Gone(listOf("msg:amma"), redraw = true), LiveActions.removed("n1"))
        assertTrue(LiveActions.forCard("msg:amma").isEmpty())
        assertNull(LiveActions.noteOf("msg:amma"))
        // A notification no card showed: nothing to redraw.
        LiveActions.put("n2", "Gmail", listOf(LiveAction("Archive")), 0L)
        assertFalse(LiveActions.removed("n2").redraw)
        LiveActions.clear()
    }

    @Test fun athenaBorrowsByAnOpaqueRefOnly() {
        LiveActions.clear()
        LiveActions.put("0|com.google.android.dialer|7|null|10123", "Phone", listOf(LiveAction("Call back", smart = Smart.Dial("7165550134"))), 0L)
        LiveActions.link("call:amma", "0|com.google.android.dialer|7|null|10123")
        val ref = LiveActions.ref("call:amma")
        assertEquals("c1", ref)
        assertFalse(LiveActions.borrow(9L, "c99", null))
        assertTrue(LiveActions.borrow(9L, ref, "Calling you back in 5"))
        assertEquals("call:amma", LiveActions.borrowedFrom(9L))
        assertEquals("Calling you back in 5", LiveActions.draftForNote("0|com.google.android.dialer|7|null|10123"))
        LiveActions.clear()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgencyAndroidTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val lines = ArrayList<String>()

    @Before fun setUp() { LiveActions.clear(); Agency.clio = { e, d -> lines += "$e $d" } }
    @After fun tearDown() = LiveActions.clear()

    private fun broadcast(action: String) = PendingIntent.getBroadcast(app, action.hashCode(), Intent(action).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)

    private fun posted(pkg: String, title: String, text: String, sub: String? = null, category: String? = null, vararg actions: Notification.Action): StatusBarNotification {
        val no = Notification.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .apply { sub?.let { setSubText(it) }; category?.let { setCategory(it) }; actions.forEach { addAction(it) } }.build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(pkg, pkg, 7, null, 10123, 0, 0, no, Process.myUserHandle(), 1_000L)
    }

    private val replyInput = RemoteInput.Builder("reply_text").setLabel("Reply").build()
    private fun reply() = Notification.Action.Builder(null, "Reply", broadcast("reply")).addRemoteInput(replyInput).build()
    private fun read() = Notification.Action.Builder(null, "Mark as read", broadcast("read")).build()

    private fun take(sbn: StatusBarNotification): Incoming = SensesListener.incoming(app, sbn)!!.also { Agency.posted(app, sbn, it) }

    @Test fun theAppsOwnActionsBecomeButtonsAndLeaveWithTheNotification() {
        val sbn = posted("com.whatsapp", "Amma", "Call me when free", null, null, reply(), read())
        val n = take(sbn)
        LiveActions.link("msg:amma", n.key)
        assertEquals(listOf("Reply", "Mark as read"), LiveActions.forCard("msg:amma").map { it.label })
        assertTrue(LiveActions.forCard("msg:amma")[0].reply)
        assertTrue(LiveActions.removed(n.key).redraw)
        assertTrue(LiveActions.forCard("msg:amma").isEmpty())
        assertEquals(Agency.Outcome.Gone, Agency.fire(app, Agency.payload(n.key, 0)))
    }

    @Test fun aChipSendsItsExactTextInOneTap() {
        val n = take(posted("com.whatsapp", "Alex", "Coming tonight?", null, null, reply()))
        val sent = shadowOf(app as android.app.Application)
        assertTrue(sent.broadcastIntents.none { it.action == "reply" })
        assertTrue(Agency.say(app, Agency.sayPayload(n.key, 0, "Sure, 7 works for me")))
        val out = sent.broadcastIntents.single { it.action == "reply" }
        assertEquals("Sure, 7 works for me", RemoteInput.getResultsFromIntent(out)!!.getCharSequence("reply_text").toString())
        assertEquals(listOf("tapped WhatsApp: Reply"), lines)
        // Gone with its notification: nothing is sent.
        LiveActions.removed(n.key)
        assertFalse(Agency.say(app, Agency.sayPayload(n.key, 0, "On my way")))
    }

    @Test fun theAppsSuggestedRepliesBecomeChips() {
        val input = RemoteInput.Builder("reply_text").setLabel("Reply").setChoices(arrayOf("Yes", "Running late")).build()
        val n = take(posted("com.whatsapp", "Alex", "Coming tonight?", null, null, Notification.Action.Builder(null, "Reply", broadcast("reply")).addRemoteInput(input).build()))
        assertEquals(listOf("Yes", "Running late"), LiveActions.forNote(n.key).single().choices)
    }

    @Test fun aMissedCallsTextChipOpensTheComposerWithThatText() {
        val n = take(posted("com.google.android.dialer", "Missed call", "+1 716 555 0134", category = Notification.CATEGORY_MISSED_CALL))
        assertTrue(Agency.say(app, Agency.sayPayload(n.key, 1, "Can't talk, call you back soon")))
        val started = shadowOf(app as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto:+17165550134", started.data.toString())
        assertEquals("Can't talk, call you back soon", started.getStringExtra("sms_body"))
    }

    @Test fun replyOpensTheFieldAndSendsOnlyOnSend() {
        val n = take(posted("com.whatsapp", "Amma", "Call me when free", null, null, reply(), read()))
        LiveActions.link("msg:amma", n.key)
        val sent = shadowOf(app as android.app.Application)
        LiveActions.ref("msg:amma").let { LiveActions.borrow(1L, it, "On my way, call you in 10") }
        // Tapping Reply only opens the field, with Athena's draft; nothing reaches the app.
        assertEquals(Agency.Outcome.Reply("On my way, call you in 10"), Agency.fire(app, Agency.payload(n.key, 0)))
        assertTrue(sent.broadcastIntents.none { it.action == "reply" })
        assertFalse(Agency.send(app, Agency.payload(n.key, 0), "   "))
        assertTrue(Agency.send(app, Agency.payload(n.key, 0), "Calling you in 10"))
        val out = sent.broadcastIntents.single { it.action == "reply" }
        assertEquals("Calling you in 10", RemoteInput.getResultsFromIntent(out)!!.getCharSequence("reply_text").toString())
        // A button without a reply field has nothing to send into.
        assertFalse(Agency.send(app, Agency.payload(n.key, 1), "hi"))
    }

    @Test fun aTapFiresTheAppsActionAndClioGetsTheLabelOnly() {
        val n = take(posted("com.whatsapp", "Amma", "Call me at +1 716 555 0134", null, null, read()))
        assertEquals(Agency.Outcome.Done, Agency.fire(app, Agency.payload(n.key, 0)))
        assertTrue(shadowOf(app as android.app.Application).broadcastIntents.any { it.action == "read" })
        assertEquals(listOf("tapped WhatsApp: Mark as read"), lines)
    }

    @Test fun callBackOpensTheDialScreenByDefault() {
        val n = take(posted("com.google.android.dialer", "Missed call", "+1 716 555 0134", category = Notification.CATEGORY_MISSED_CALL))
        assertEquals(listOf("Call back", "Text"), LiveActions.forNote(n.key).map { it.label })
        assertEquals(Agency.Outcome.Done, Agency.fire(app, Agency.payload(n.key, 0)))
        val started = shadowOf(app as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, started.action)
        assertEquals("tel:+17165550134", started.data.toString())
        // Clio: app and label, never the number.
        assertEquals(listOf("tapped Phone: Call back"), lines)
        assertFalse(lines.any { it.contains("716") })
    }

    @Test fun copyCodePutsItOnTheClipboardMarkedSensitive() {
        LiveActions.code("otp1", "Messages", "482913", 0L)
        assertEquals(Agency.Outcome.Done, Agency.fire(app, Agency.payload("otp1", 0)))
        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("482913", cm.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(cm.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals(listOf("tapped Messages: Copy code"), lines)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        assertFalse(cm.hasPrimaryClip())
    }

    @Test fun filteredNotificationsGetNoButtons() {
        val service = Robolectric.buildService(SensesListener::class.java).get()
        val telegram = posted("org.telegram.messenger", "Iris", "Done", null, null, reply())
        val temu = posted("com.einnovation.temu", "Temu", "Flash sale", null, null, read())
        val dads = posted("com.google.android.gm", "Bank", "Statement ready", sub = "parent@example.com", category = null, read())
        for (s in listOf(telegram, temu, dads)) service.onNotificationPosted(s)
        for (s in listOf(telegram, temu, dads)) assertTrue(LiveActions.forNote(s.key).isEmpty())
    }
}

/** Cards finish themselves: after his tap, or when the thing was done elsewhere. */
class FinishRulesTest {
    @Test fun removalsThatMeanHeDealtWithIt() {
        for (r in listOf(Reasons.CLICK, Reasons.CANCEL, Reasons.CANCEL_ALL, Reasons.APP_CANCEL, Reasons.APP_CANCEL_ALL)) assertTrue(Finish.byRemoval(r))
        // Timed out, snoozed, another listener, the app blocked: not his doing.
        for (r in listOf(Reasons.TIMEOUT, Reasons.SNOOZED, Reasons.LISTENER_CANCEL, Reasons.PACKAGE_BANNED)) assertFalse(Finish.byRemoval(r))
    }

    @Test fun aTapFinishesExceptOpeningTheReplyField() {
        assertTrue(Finish.byTap(Agency.Outcome.Done))
        assertFalse(Finish.byTap(Agency.Outcome.Reply("draft")))
        assertFalse(Finish.byTap(Agency.Outcome.Gone))
    }

    @Test fun calledBackByAnyRouteOrAnsweredWhenItRangAgain() {
        val since = 1_000L
        assertTrue(Finish.calledBack("+17165550134", since, listOf(Finish.Call("716-555-0134", Finish.OUTGOING, 2_000L, 0))))
        assertTrue(Finish.calledBack("7165550134", since, listOf(Finish.Call("+1 716 555 0134", Finish.INCOMING, 2_000L, 95))))
        // Before the missed call, missed again, or someone else: still open.
        assertFalse(Finish.calledBack("7165550134", since, listOf(Finish.Call("7165550134", Finish.OUTGOING, 500L, 30))))
        assertFalse(Finish.calledBack("7165550134", since, listOf(Finish.Call("7165550134", Finish.INCOMING, 2_000L, 0))))
        assertFalse(Finish.calledBack("7165550134", since, listOf(Finish.Call("7165550199", Finish.OUTGOING, 2_000L, 30))))
    }

    @Test fun theInstanceKeyIsWhatHomeDismisses() {
        val c = SenseCard("call:amma", "Amma called", "Last call 9:41 PM", "ATHENA", 5L, 99L)
        val g = dev.phonerecall.home.GeneratedCard(c.title, c.body, listOf("Got it"), c.until, dev.phonerecall.home.CardSource.ATHENA, id = SenseCards.id(c), real = true)
        assertEquals(dev.phonerecall.home.CardEngine.idOf(g), SenseCards.instance(c))
        assertEquals(dev.phonerecall.home.CardEngine.idOf(g.copy(id = "athena-9")), SenseCards.athenaInstance(9))
    }
}

private object Reasons {
    const val CLICK = android.service.notification.NotificationListenerService.REASON_CLICK
    const val CANCEL = android.service.notification.NotificationListenerService.REASON_CANCEL
    const val CANCEL_ALL = android.service.notification.NotificationListenerService.REASON_CANCEL_ALL
    const val APP_CANCEL = android.service.notification.NotificationListenerService.REASON_APP_CANCEL
    const val APP_CANCEL_ALL = android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL
    const val TIMEOUT = android.service.notification.NotificationListenerService.REASON_TIMEOUT
    const val SNOOZED = android.service.notification.NotificationListenerService.REASON_SNOOZED
    const val LISTENER_CANCEL = android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL
    const val PACKAGE_BANNED = android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishAndroidTest {
    private val app: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() { LiveActions.clear(); Agency.clio = { _, _ -> } }
    @After fun tearDown() = LiveActions.clear()

    private fun dismissed() = dev.phonerecall.data.Dismissals.load(app).keys
    private fun gone(instance: String) = dev.phonerecall.data.dismissKey(instance) in dismissed()

    private fun missedCall(rig: EngineRig, key: String) {
        rig.store.savePerson(Person(People.keyOf("Amma"), "Amma", Relation.MOTHER, confirmed = true, asked = true))
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = key))
    }

    @Test fun aClearedNotificationFinishesItsCardAndAthenasCopyWithUndo() {
        val rig = EngineRig()
        missedCall(rig, "c1")
        val key = "call:${People.keyOf("Amma")}"
        LiveActions.link(key, rig.effects.links.getValue(key))
        LiveActions.put("c1", "Phone", listOf(LiveAction("Call back")), rig.now)
        LiveActions.borrow(9L, LiveActions.ref(key), null)
        val removed = LiveActions.removed("c1")
        assertEquals(listOf(key), removed.cards)
        assertEquals(2, Agency.finish(app, rig.store, { id -> if (id == 9L) rig.now + 3_600_000L else null }, removed.cards, "notification cleared", rig.now))
        val card = rig.store.card(key)!!
        assertTrue(gone(SenseCards.instance(card)))
        assertTrue(gone(SenseCards.athenaInstance(9)))
        // Undo is the swipe's: removing the dismissal brings it back.
        dev.phonerecall.data.Dismissals.remove(app, SenseCards.instance(card))
        assertFalse(gone(SenseCards.instance(card)))
    }

    @Test fun aNewCallAfterFinishingShowsAgain() {
        val rig = EngineRig()
        missedCall(rig, "c1")
        val key = "call:${People.keyOf("Amma")}"
        val first = rig.store.card(key)!!
        Agency.finish(app, rig.store, { null }, listOf(key), "called back", rig.now)
        rig.now += 60 * 60_000L
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = "c2"))
        // The card's time is part of its instance key: the new call is a new card.
        assertFalse(gone(SenseCards.instance(rig.store.card(key)!!)))
        assertTrue(gone(SenseCards.instance(first)))
    }

    @Test fun calledBackNeedsTheCallLogPermission() {
        val rig = EngineRig()
        missedCall(rig, "c1")
        assertTrue(Agency.calledBack(app, rig.store, { "Amma" }, rig.now).isEmpty())
    }

    private fun chat(lastFromHim: Boolean): StatusBarNotification {
        val me = androidx.core.app.Person.Builder().setName("You").build()
        val amma = androidx.core.app.Person.Builder().setName("Amma").build()
        val style = androidx.core.app.NotificationCompat.MessagingStyle(me)
            .addMessage("Call me when free", 1_000L, amma)
            .addMessage("Calling in 10", 2_000L, if (lastFromHim) null else amma)
        val no = androidx.core.app.NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
        @Suppress("DEPRECATION")
        return StatusBarNotification("com.whatsapp", "com.whatsapp", 3, null, 10123, 0, 0, no, Process.myUserHandle(), 2_000L)
    }

    @Test fun hisOwnReplyInTheChatFinishesIt() {
        assertTrue(Agency.hisReply(chat(lastFromHim = true)))
        assertFalse(Agency.hisReply(chat(lastFromHim = false)))
    }

    @Test fun hisReplyIsNotIngestedAsTheirMessage() {
        val service = Robolectric.buildService(SensesListener::class.java).get()
        val sbn = chat(lastFromHim = true)
        LiveActions.put(sbn.key, "WhatsApp", listOf(LiveAction("Reply")), 0L)
        service.onNotificationPosted(sbn)
        // Returned before the senses: the buttons it had are untouched and nothing new was read.
        assertEquals(listOf("Reply"), LiveActions.forNote(sbn.key).map { it.label })
    }
}

/** Buttons live in memory: the sealed stores, their files and the prefs never hold an intent, an input or a label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgencyNeverStoredTest {
    private val main = File("src/main/java/dev/phonerecall")

    @After fun tearDown() = LiveActions.clear()

    @Test fun theStoresNeverSeeTheButtons() {
        val rig = EngineRig()
        rig.store.savePerson(Person(People.keyOf("Amma"), "Amma", Relation.MOTHER, confirmed = true, asked = true))
        LiveActions.put("c1", "Phone", listOf(LiveAction("Call back", smart = Smart.Dial("7165550134")), LiveAction("Text", smart = Smart.Text("7165550134"))), rig.now)
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = "c1"))
        rig.engine.ingest(rig.note("com.google.android.apps.messaging", "57826", "Your verification code is 482913", key = "otp"))
        // The engine only says which card shows which notification, and that a code came; it never holds the buttons.
        assertEquals("c1", rig.effects.links["call:${People.keyOf("Amma")}"])
        assertEquals(listOf("otp"), rig.effects.codes)
        val db = rig.store.readableDatabase
        val dump = StringBuilder()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { t ->
            while (t.moveToNext()) db.rawQuery("SELECT * FROM ${t.getString(0)}", null).use { r ->
                while (r.moveToNext()) for (i in 0 until r.columnCount) if (r.getType(i) == android.database.Cursor.FIELD_TYPE_STRING) dump.append(r.getString(i)).append('\n')
            }
        }
        // Sealed columns are opened too, so this reads what the store means, not only what it wrote.
        val opened = dump.lineSequence().map { FakeSealer().open(it) ?: it }.joinToString("\n")
        for (s in listOf("Call back", "Copy code", "7165550134", "482913", "PendingIntent", "RemoteInput")) assertFalse("stored $s", opened.contains(s))
    }

    @Test fun onlyTheseFilesReachTheLiveButtons() {
        val reach = Regex("""\b(LiveActions|LiveAction|RemoteInput|actionIntent|remoteInputs)\b""")
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" && reach.containsMatchIn(it.readText()) }.map { it.relativeTo(main).path.replace('\\', '/') }.toSet()
        assertEquals(
            setOf(
                "brain/Agency.kt", "brain/AgencyAndroid.kt",
                "brain/BrainCore.kt",       // links a card to a notification, and a code to its card
                "brain/BrainHub.kt",        // labels and payloads onto cards
                "brain/GroupWish.kt",       // a sent wish marks its card's birthday; wordings only are kept
                "brain/SensesListener.kt",  // removal takes the buttons away
                "mind/MindAndroid.kt",      // labels and an opaque ref to Athena; her card borrows by ref
            ),
            users,
        )
        // None of the persistence code mentions them.
        for (f in listOf("brain/SenseStore.kt", "brain/BrainDb.kt", "brain/Seal.kt", "clio/Clio.kt")) assertFalse(f, reach.containsMatchIn(File(main, f).readText()))
        // Agency's own prefs hold one boolean, never a button.
        val prefs = Regex("""prefs\(c\)\.edit\(\)\.(\w+)\("(\w+)"""").findAll(File(main, "brain/AgencyAndroid.kt").readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("putBoolean" to "call_directly", "putBoolean" to "haiku_asks"), prefs)
    }
}
