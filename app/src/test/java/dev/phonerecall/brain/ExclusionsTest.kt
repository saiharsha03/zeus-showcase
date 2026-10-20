package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Codes, password managers, banks, Calypso and Zeus itself never reach storage. */
class ExclusionsTest {
    private fun n(pkg: String, title: String, text: String, app: String = "App") = Incoming("k", pkg, app, title, text, 0L)

    @Test fun oneTimeCodesAreDroppedInAnyApp() {
        val codes = listOf(
            n("com.google.android.apps.messaging", "57826", "Your Chase verification code is 482913. Don't share it."),
            n("com.google.android.apps.messaging", "Google", "G-482913 is your Google verification code."),
            n("com.google.android.apps.messaging", "Instagram", "123 456 is your Instagram code"),
            n("com.google.android.apps.messaging", "AX-HDFCBK", "OTP for txn of INR 2,500 at AMAZON is 739201. Valid for 5 mins."),
            n("com.whatsapp", "Jordan", "my otp is 4521 can you read it"),
            n("com.google.android.gm", "Uber", "Your sign-in code: 8842. It expires in 10 minutes."),
            n("com.microsoft.office.outlook", "Microsoft account", "Security code 61-3302? Use 613302 to verify your identity"),
            n("com.google.android.gm", "Venmo", "Use this one-time passcode 220981 to log in"),
        )
        for (c in codes) assertEquals(c.text, Intake.Drop("code"), Exclusions.check(c))
    }

    @Test fun ordinaryNumbersAreNotCodes() {
        assertEquals(Intake.Keep, Exclusions.check(n("com.whatsapp", "Taylor", "Dress code is formal for the 2026 gala")))
        assertEquals(Intake.Keep, Exclusions.check(n("com.whatsapp", "Alex", "Flight lands at 7:40, gate 12")))
        assertFalse(Exclusions.isOtp("The score was 2 1, Barca won"))
    }

    @Test fun passwordManagersAuthenticatorsAndSignInLinksAreDropped() {
        assertEquals(Intake.Drop("password manager"), Exclusions.check(n("com.x8bit.bitwarden", "Bitwarden", "Autofill used for chase.com")))
        assertEquals(Intake.Drop("password manager"), Exclusions.check(n("com.samsung.android.samsungpass", "Samsung Pass", "Signed in")))
        assertEquals(Intake.Drop("authenticator"), Exclusions.check(n("com.azure.authenticator", "Approve sign-in?", "Microsoft account")))
        assertEquals(Intake.Drop("sign-in link"), Exclusions.check(n("com.google.android.gm", "Slack", "Here is your magic link to sign in")))
    }

    @Test fun banksKeepOnlyTheFact() {
        assertEquals(Intake.BankFact("Chase"), Exclusions.check(n("com.chase.sig.android", "Chase", "You sent $42.10 to Jordan", app = "Chase")))
        assertEquals(Intake.BankFact("KeyBank"), Exclusions.check(n("com.key.android", "KeyBank", "Low balance alert", app = "KeyBank")))
        // An unlisted bank still says "bank" in its name.
        assertEquals(Intake.BankFact("First Niagara Bank"), Exclusions.check(n("com.example.fnb", "Alert", "Deposit posted", app = "First Niagara Bank")))
        assertEquals(Intake.BankFact("a bank text"), Exclusions.check(n("com.google.android.apps.messaging", "AD-SBIBNK", "Your A/c XX1234 is debited by Rs.1,200 on 19-10")))
    }

    @Test fun calypsoAndHisOwnAppAreDropped() {
        assertEquals(Intake.Drop("own app"), Exclusions.check(n(Exclusions.OWN_PACKAGE, "Zeus", "Amma called twice")))
        assertEquals(Intake.Drop("Calypso"), Exclusions.check(n("com.whatsapp", "Alex", "hi"), calypsoApps = setOf("com.whatsapp")))
        assertEquals(Intake.Drop("Calypso"), Exclusions.check(n("com.whatsapp", "Riley", "hi"), calypsoPeople = setOf(People.keyOf("Riley"))))
        assertEquals(Intake.Keep, Exclusions.check(n("com.whatsapp", "Alex", "hi"), calypsoPeople = setOf(People.keyOf("Riley"))))
    }

    @Test fun cloudLinesNeverCarryNamesOrPeoplesWords() {
        val msg = Incoming("k", "com.whatsapp", "WhatsApp", "Amma", "Call me when you are free, about the visa papers", 0L)
        val line = Derive.cloudLine(1, msg, AppKind.CHAT, "family (mother)", Tier.REPLY_NOW, CueReader.read(msg.text), Topics.of(msg.text))
        assertFalse(line, line.contains("Amma"))
        assertFalse(line, line.contains("visa papers"))
        assertFalse(line, line.contains("Call me"))
        assertTrue(line, line.contains("from family (mother)"))
        assertTrue(line, line.contains("immigration"))
        // An ordinary app may carry a short, digit-masked snippet.
        val app = Incoming("k", "com.amazon.mShop.android.shopping", "Amazon", "Out for delivery", "Order 112-334 arrives by 8 PM", 0L)
        val l2 = Derive.cloudLine(2, app, AppKind.OTHER, null, null, CueReader.read(app.text), Topics.of(app.text))
        assertTrue(l2, l2.contains("Order ###-### arrives by # PM"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensesNeverStoreTest {
    @Test fun excludedNotificationsLeaveNoRow() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note("com.google.android.apps.messaging", "57826", "Your verification code is 482913"))
        rig.engine.ingest(rig.note("com.x8bit.bitwarden", "Bitwarden", "Vault unlocked"))
        rig.engine.ingest(rig.note(Exclusions.OWN_PACKAGE, "Zeus", "Amma called twice"))
        rig.store.setCalypsoApp("com.whatsapp", true)
        rig.engine.ingest(rig.note("com.whatsapp", "Riley", "the thing we talked about"))
        assertTrue(rig.store.notes().isEmpty())
        assertEquals("1", rig.store.meta("dropped.code"))
    }

    @Test fun bankAlertKeepsTheFactNotTheBody() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note("com.chase.sig.android", "Chase", "You sent $42.10 to Jordan").copy(app = "Chase"))
        val row = rig.store.notes().single()
        assertNull(row.raw)
        assertEquals("Bank alert from Chase", row.summary)
        assertFalse(rig.store.cards(rig.now).single().body.contains("42"))
    }
}
