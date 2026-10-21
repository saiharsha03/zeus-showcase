package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

class AccountAlertsTest {
    private val zone = ZoneId.of("America/New_York")
    /** 2026-10-20 20:01 ET, when both of the owner's Google alerts came in. */
    private val at = ZonedDateTime.of(2026, 10, 20, 20, 1, 0, 0, zone).toInstant().toEpochMilli()
    private val zeus = "Security alert\nYou allowed Zeus access to some of your Google Account data\nowner@example.com"
    private val claude = "Security alert\nYou shared some Google Account data with Claude\nowner@example.com"

    @Test fun readsTheRealGoogleAlerts() {
        assertEquals(AccountAlert("google", AlertKind.APP_ACCESS, "Zeus"), AccountAlerts.classify("Google", zeus))
        assertEquals(AccountAlert("google", AlertKind.SIGN_IN_WITH, "Claude"), AccountAlerts.classify("Google", claude))
        assertEquals(AccountAlert("google", AlertKind.APP_ACCESS, "Google Home"), AccountAlerts.classify("Google", "You allowed Google Home access to your Google Account"))
    }

    @Test fun readsTheOtherKinds() {
        assertEquals(AlertKind.NEW_DEVICE, AccountAlerts.classify("Google", "Security alert\nA new sign-in on Samsung Galaxy S25")?.kind)
        assertEquals(AlertKind.PASSWORD, AccountAlerts.classify("Google", "Security alert\nThe password for your Google Account was changed")?.kind)
        assertEquals(AlertKind.BLOCKED, AccountAlerts.classify("Google", "Critical security alert\nSuspicious sign-in prevented")?.kind)
        assertEquals(AlertKind.RECOVERY, AccountAlerts.classify("Google", "Security alert\nRecovery email was changed for your Google Account")?.kind)
        assertEquals(AlertKind.TWO_STEP, AccountAlerts.classify("Google", "2-Step Verification turned off")?.kind)
        assertEquals(AccountAlert("github", AlertKind.ACCESS_KEY), AccountAlerts.classify("GitHub", "[GitHub] A new personal access token (classic) was added to your account"))
        assertEquals(AccountAlert("github", AlertKind.APP_ACCESS, "Foo CI"), AccountAlerts.classify("GitHub", "A third-party OAuth application (Foo CI) with read:user scope was recently authorized"))
        assertNull(AccountAlerts.classify("Google", "Security alert"))
    }

    @Test fun zeusOwnConsentExplainsItWithinHalfAnHour() {
        val a = AccountAlerts.classify("Google", zeus)!!
        val c = AccountAlerts.card(a, at, listOf(OwnConsent(Consent.GOOGLE_CALENDAR, at - 60_000L)), zone)
        assertTrue(c.expected)
        assertEquals("This is you: Zeus got access to your Google Calendar at 8:00 PM when you connected birthdays. Nothing to do.", c.body)
        assertEquals("Google security alert: Zeus got Google Calendar access (expected).", c.line)
        assertNotNull(AccountAlerts.match(a, at, listOf(OwnConsent(Consent.GOOGLE_CALENDAR, at - AccountAlerts.WINDOW_MS))))
        assertNull(AccountAlerts.match(a, at, listOf(OwnConsent(Consent.GOOGLE_CALENDAR, at - AccountAlerts.WINDOW_MS - 1))))
        // A Spotify sign-in doesn't explain a Google alert.
        assertNull(AccountAlerts.match(a, at, listOf(OwnConsent(Consent.SPOTIFY, at))))
    }

    @Test fun anythingElseIsLoud() {
        val own = listOf(OwnConsent(Consent.GOOGLE_CALENDAR, at))
        val claudeCard = AccountAlerts.card(AccountAlerts.classify("Google", claude)!!, at, own, zone)
        assertFalse(claudeCard.expected)
        assertEquals("You signed in to Claude with Google. Not from Zeus — check this.", claudeCard.body)
        // Zeus never changes a password or signs in on a device, whatever it did a minute ago.
        assertFalse(AccountAlerts.card(AccountAlerts.classify("Google", "A new sign-in on Windows")!!, at, own, zone).expected)
        assertFalse(AccountAlerts.card(AccountAlerts.classify("Google", "Your password was changed")!!, at, own, zone).expected)
    }

    @Test fun knownAppsAreQuiet() {
        val c = AccountAlerts.card(AccountAlerts.classify("Google", claude)!!, at, emptyList(), zone, KnownApps.DEFAULT)
        assertTrue(c.expected)
        assertEquals("security-ok:google:sign_in_with:claude", c.key)
        assertEquals("You signed in to Claude with Google. Claude is one of your known apps. Nothing to do.", c.body)
        assertEquals("Google security alert: you signed in to Claude with Google (known app).", c.line)
        // Knowing Claude never quiets a password change or a new device.
        assertFalse(AccountAlerts.card(AccountAlerts.classify("Google", "Your password was changed")!!, at, emptyList(), zone, listOf("Claude")).expected)
        assertFalse(AccountAlerts.card(AccountAlerts.classify("Google", "You shared some Google Account data with Notion")!!, at, emptyList(), zone, listOf("Claude")).expected)
    }

    @Test fun gotItTeachesTheAppAndClearingTheNotificationNeverFinishesALoudCard() {
        assertEquals("Claude", KnownApps.appFromCard("gen:sense-security:google:sign_in_with:claude-1729000000000"))
        assertEquals("Google Home", KnownApps.appFromCard("gen:sense-security:google:app_access:google-home-1729000000000"))
        assertNull(KnownApps.appFromCard("gen:sense-security:google:password:-1729000000000"))
        assertNull(KnownApps.appFromCard("gen:sense-call:amma-1729000000000"))
        assertEquals(listOf("security-ok:google:app_access:zeus", "msg:amma"), AccountAlerts.clearable(listOf("security:google:new_device:", "security-ok:google:app_access:zeus", "msg:amma")))
    }

    @Test fun buttonsOpenHisGoogleAccount() {
        assertEquals(listOf("Review connections", "Got it") to "https://myaccount.google.com/connections", AccountAlerts.actions("security-ok:google:app_access:zeus", AccountAlerts.QUIET_SOURCE))
        assertEquals(listOf("Check it", "Got it") to "https://myaccount.google.com/security", AccountAlerts.actions("security:google:new_device:", "SECURITY"))
        assertEquals(listOf("Got it") to null, AccountAlerts.actions("security:com.google.android.gm", "SECURITY"))
        assertEquals(listOf("Got it") to null, AccountAlerts.actions("call:amma", "ATHENA"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountAlertCardsTest {
    private val gmail = "com.google.android.gm"
    private val zeus = "Security alert\nYou allowed Zeus access to some of your Google Account data"

    @Test fun expectedAlertIsAQuietCardForADay() {
        val rig = EngineRig()
        rig.effects.own = listOf(OwnConsent(Consent.GOOGLE_CALENDAR, rig.now - 60_000L))
        rig.engine.ingest(rig.note(gmail, "Google", zeus))
        val card = rig.store.cards(rig.now).single()
        assertEquals(AccountAlerts.QUIET_SOURCE, card.source)
        assertEquals(rig.now + AccountAlerts.QUIET_MS, card.until)
        assertTrue(card.body.startsWith("This is you: Zeus got access to your Google Calendar"))
        assertEquals(listOf("Google security alert: Zeus got Google Calendar access (expected)."), rig.effects.derivedLines.filter { "security alert" in it })
    }

    @Test fun unexplainedAlertStaysLoudAndIsNeverCleared() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note(gmail, "Google", zeus, key = "a"))
        rig.engine.ingest(rig.note(gmail, "Google", "Security alert\nYou shared some Google Account data with Claude", key = "b"))
        // Then Zeus asks, and Google mails again: the quiet card joins, the loud ones stay.
        rig.now += 60_000L
        rig.effects.own = listOf(OwnConsent(Consent.GOOGLE_CALENDAR, rig.now))
        rig.engine.ingest(rig.note(gmail, "Google", zeus, key = "c"))
        val cards = rig.store.cards(rig.now).associateBy { it.key }
        assertEquals(setOf("security:google:app_access:zeus", "security:google:sign_in_with:claude", "security-ok:google:app_access:zeus"), cards.keys)
        assertEquals("SECURITY", cards.getValue("security:google:sign_in_with:claude").source)
        assertEquals(rig.now - 60_000L + AccountAlerts.LOUD_MS, cards.getValue("security:google:app_access:zeus").until)
        assertTrue(rig.effects.derivedLines.contains("!Google security alert: you signed in to Claude with Google (not from Zeus)."))
    }

    @Test fun knownAppStaysQuietInTheEngineAndThemisEditsTheList() {
        val rig = EngineRig()
        rig.effects.apps = KnownApps.DEFAULT
        rig.engine.ingest(rig.note(gmail, "Google", "Security alert\nYou shared some Google Account data with Claude"))
        assertEquals(AccountAlerts.QUIET_SOURCE, rig.store.cards(rig.now).single().source)
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("Claude"), KnownApps.get(app))
        KnownApps.learn(app, "gen:sense-security:google:sign_in_with:notion-1729")
        KnownApps.learn(app, "gen:sense-security:google:sign_in_with:claude-1730")
        assertEquals(listOf("Claude", "Notion"), KnownApps.get(app))
        KnownApps.set(app, " Figma , ,figma, Claude".split(','))
        assertEquals(listOf("Figma", "Claude"), KnownApps.get(app))
    }

    @Test fun consentLogIsSealedAndKeptAWeek() {
        var now = 1_000_000_000_000L
        val log = ConsentLog(RuntimeEnvironment.getApplication(), FakeSealer()) { now }
        log.record(Consent.GOOGLE_CALENDAR)
        now += ConsentLog.KEEP_MS - 1
        log.record(Consent.GOOGLE_HOME)
        assertEquals(listOf(Consent.GOOGLE_CALENDAR, Consent.GOOGLE_HOME), log.recent().map { it.consent })
        now += 2
        assertEquals(listOf(Consent.GOOGLE_HOME), log.recent().map { it.consent })
        val raw = RuntimeEnvironment.getApplication().getSharedPreferences("consents", 0).all.values.joinToString()
        assertFalse(raw.contains("GOOGLE"))
    }
}
