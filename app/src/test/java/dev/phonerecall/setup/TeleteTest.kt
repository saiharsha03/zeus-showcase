package dev.phonerecall.setup

import dev.phonerecall.Dest
import dev.phonerecall.memory.EmbedModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up facts: [TeleteFixtures.ready] is a phone with everything in place. */
object TeleteFixtures {
    const val NOW = 1_792_000_000_000L

    val ready = Facts(
        granted = setOf(Perm.FINE, Perm.BACKGROUND, Perm.ACTIVITY, Perm.CALL_LOG, Perm.CALL, Perm.CONTACTS, Perm.CALENDAR, Perm.MIC, Perm.NOTIFY, Perm.NEARBY),
        listener = true, usage = true, health = HcFact.READY, exactAlarms = true, dnd = true, installs = true, battery = true,
        keys = setOf("anthropic", "gemini", "hetzner", "tomtom", "ny511"),
        paired = true, spotify = true, home = HomeFact.CONNECTED, googleBirthdays = true,
        backupSetUp = true, backupAt = NOW - 3_600_000L, backupOk = true,
        indexedAt = NOW - 3_600_000L, passages = 463, embedded = 463, model = EmbedModel.READY,
        odysseyOn = true, homePlace = true, officePlace = true,
        theogonyDone = true, mindSetUp = true, lastSync = NOW - 600_000L, heartbeat = true,
        iris = "connected", agora = true,
    )

    /** A phone partway through: what the shots draw. */
    val partway = ready.copy(
        granted = ready.granted - Perm.BACKGROUND - Perm.CALL_LOG,
        battery = false, samsung = true, keys = ready.keys - "tomtom" - "ny511", spotify = false,
        backupAt = null, embedded = 120, officePlace = false, theogonyDone = false, asked = 9, total = 14,
        iris = null,
    )
}

class TeleteTest {
    private fun checks(f: Facts) = Telete.checks(f, TeleteFixtures.NOW)
    private fun status(f: Facts, id: String) = checks(f).single { it.id == id }.status

    @Test fun aReadyPhoneHasNothingLeft() {
        val all = checks(TeleteFixtures.ready)
        assertEquals(0, Telete.left(all))
        assertTrue(all.all { it.status == Status.OK })
        assertEquals("Setup status: everything is ready.", Telete.context(all))
    }

    @Test fun everyCheckHasAWhyAndAFix() {
        val all = checks(Facts())
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.why.isNotBlank() && it.fix != null })
        assertTrue(all.none { it.why.contains('—') || it.detail.contains('—') })
    }

    @Test fun eachMissingThingIsCountedOnce() {
        val f = TeleteFixtures.ready
        val cases = mapOf(
            "location" to f.copy(granted = f.granted - Perm.FINE - Perm.BACKGROUND),
            "activity" to f.copy(granted = f.granted - Perm.ACTIVITY),
            "listener" to f.copy(listener = false),
            "usage" to f.copy(usage = false),
            "health" to f.copy(health = HcFact.PARTIAL),
            "exact-alarms" to f.copy(exactAlarms = false),
            "dnd" to f.copy(dnd = false),
            "installs" to f.copy(installs = false),
            "battery" to f.copy(battery = false),
            "key-gemini" to f.copy(keys = f.keys - "gemini"),
            "spotify" to f.copy(spotify = false),
            "hestia" to f.copy(home = HomeFact.NEEDS_PERMISSION),
            "google-birthdays" to f.copy(googleBirthdays = false),
            "odyssey" to f.copy(odysseyPaused = true),
            "home-office" to f.copy(officePlace = false),
            "theogony" to f.copy(theogonyDone = false),
            "heartbeat" to f.copy(heartbeat = false),
            "iris" to f.copy(iris = "offline"),
            "agora" to f.copy(agora = false),
        )
        for ((id, facts) in cases) {
            val all = checks(facts)
            val missing = all.filter { it.status == Status.MISSING }.map { it.id }
            // Location off also takes "all the time" with it.
            assertEquals(id, if (id == "location") listOf("location", "background-location") else listOf(id), missing)
        }
    }

    @Test fun backgroundLocationAsksForLocationFirst() {
        val f = TeleteFixtures.ready.copy(granted = TeleteFixtures.ready.granted - Perm.FINE - Perm.BACKGROUND)
        val bg = checks(f).single { it.id == "background-location" }
        assertEquals(Fix.Ask(listOf(Perm.FINE)), bg.fix)
        assertEquals("Allow Location first", bg.detail)
        val onlyBg = checks(TeleteFixtures.ready.copy(granted = TeleteFixtures.ready.granted - Perm.BACKGROUND)).single { it.id == "background-location" }
        assertEquals(Fix.Ask(listOf(Perm.BACKGROUND)), onlyBg.fix)
    }

    @Test fun notKnownYetAndOptionalDontKeepTeleteInTheSidebar() {
        val f = TeleteFixtures.ready.copy(iris = null, agora = null, googleBirthdays = null, home = HomeFact.STARTING, keys = TeleteFixtures.ready.keys - "ny511")
        val all = checks(f)
        assertEquals(0, Telete.left(all))
        assertEquals(Status.UNKNOWN, status(f, "iris"))
        assertEquals(Status.UNKNOWN, status(f, "google-birthdays"))
        assertEquals(Status.OPTIONAL, status(f, "key-ny511"))
    }

    @Test fun unpairedLemnosIsMissingForIrisAndAgora() {
        val f = TeleteFixtures.ready.copy(paired = false, iris = null, agora = null)
        assertEquals(listOf("lemnos", "iris", "agora"), checks(f).filter { it.status == Status.MISSING }.map { it.id })
        assertEquals(Fix.Open("VPS"), checks(f).single { it.id == "iris" }.fix)
    }

    @Test fun backupNeedsAPhraseAndARecentGoodRun() {
        val f = TeleteFixtures.ready
        assertEquals(Status.MISSING, status(f.copy(backupSetUp = false), "backup"))
        assertEquals(Status.MISSING, status(f.copy(backupOk = false), "backup"))
        assertEquals(Status.MISSING, status(f.copy(backupAt = TeleteFixtures.NOW - 3 * 86_400_000L), "backup"))
        assertEquals(Status.OK, status(f.copy(backupAt = TeleteFixtures.NOW - 30 * 3_600_000L), "backup"))
    }

    @Test fun alexandriaWantsEveryPassageEmbeddedOnlyWhenTheModelIsThere() {
        val f = TeleteFixtures.ready
        assertEquals(Status.MISSING, status(f.copy(indexedAt = null), "alexandria"))
        assertEquals(Status.MISSING, status(f.copy(embedded = 100), "alexandria"))
        // Without EmbeddingGemma, words-only is as done as it gets.
        assertEquals(Status.OK, status(f.copy(embedded = 0, model = EmbedModel.MISSING), "alexandria"))
        assertEquals(Fix.IndexNow, checks(f.copy(embedded = 100)).single { it.id == "alexandria" }.fix)
    }

    @Test fun theMindNeedsTheKeyThenADailySync() {
        val f = TeleteFixtures.ready
        val noKey = checks(f.copy(keys = f.keys - "anthropic")).single { it.id == "mind" }
        assertEquals(Fix.Open("VAULT"), noKey.fix)
        assertEquals(Status.MISSING, status(f.copy(lastSync = TeleteFixtures.NOW - 2 * 86_400_000L), "mind"))
        assertEquals(Fix.SyncMind, checks(f.copy(mindSetUp = false)).single { it.id == "mind" }.fix)
    }

    @Test fun samsungGetsTheNeverSleepingHint() {
        val d = checks(TeleteFixtures.ready.copy(battery = false, samsung = true)).single { it.id == "battery" }.detail
        assertTrue(d.contains("Never sleeping apps"))
        assertFalse(checks(TeleteFixtures.ready.copy(battery = false)).single { it.id == "battery" }.detail.contains("Samsung"))
    }

    @Test fun athenaGetsNamesAndStatusesOnly() {
        val all = checks(TeleteFixtures.partway)
        val text = Telete.context(all)
        assertTrue(text.startsWith("Setup status: ${Telete.left(all)} left of ${all.size}."))
        assertTrue(text.contains("- TomTom (Keys in Calypso): missing, Not in Calypso"))
        assertTrue(text.contains("- 511NY (Keys in Calypso): optional"))
        assertFalse(text.contains("Spotify (Connections): ok"))
    }

    @Test fun riteRunsPassFailAndTimeout() = runBlocking {
        var t = 0L
        val clock = { t.also { t += 5 } }
        val logged = ArrayList<String>()
        val log: (SelfTest, TestResult) -> Unit = { s, r -> logged += "${s.id}:${r.pass}" }
        assertEquals(TestResult(true, "fine", 5, 0), Rite.run(SelfTest("a", "A", "") { "fine" }, clock, log = log))
        assertEquals("No key", Rite.run(SelfTest("b", "B", "") { throw TestFailed("No key") }, clock, log = log).reason)
        assertEquals("boom", Rite.run(SelfTest("c", "C", "") { error("boom") }, clock, log = log).reason)
        val slow = Rite.run(SelfTest("d", "D", "") { delay(5_000); "late" }, clock, timeoutMs = 50, log = log)
        assertFalse(slow.pass)
        assertEquals("No answer in 0 s", slow.reason)
        assertEquals(listOf("a:true", "b:false", "c:false", "d:false"), logged)
    }

    @Test fun teleteIsInTheSidebarOnlyWhileSomethingIsLeft() {
        assertTrue(Dest.SETUP in Dest.shown(setupLeft = true))
        assertFalse(Dest.SETUP in Dest.shown(setupLeft = false))
        assertEquals(Dest.shown(true) - Dest.SETUP, Dest.shown(false))
        assertTrue(Dest.shown(false).none { !it.listed })
    }
}
