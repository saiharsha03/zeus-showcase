package dev.phonerecall.fresh

import dev.phonerecall.odyssey.CarData
import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.DriveRoute
import dev.phonerecall.odyssey.KnownPlace
import dev.phonerecall.odyssey.Lease
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.TrackPoint
import dev.phonerecall.odyssey.Trip
import dev.phonerecall.odyssey.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FreshPlanTest {
    private val src = File("src/main/java/dev/phonerecall")
    private fun sources() = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every store name the source writes, by kind, found the way a reviewer would: the literal next to the call. */
    private fun found(): Map<String, Set<String>> {
        val prefs = HashSet<String>(); val dbs = HashSet<String>(); val files = HashSet<String>(); val nobackup = HashSet<String>(); val external = HashSet<String>()
        for (f in sources()) {
            val t = f.readText()
            Regex("""getSharedPreferences\("([^"]+)"""").findAll(t).forEach { prefs += it.groupValues[1] }
            Regex("""PrefsKv\([^()"]*,\s*"([^"]+)"\)""").findAll(t).forEach { prefs += it.groupValues[1] }
            if (Regex("""PrefsKv\(\w+(\.applicationContext)?\)""").containsMatchIn(t)) prefs += "mind"
            val const = Regex("""const val (FILE|PREFS) = "([^"]+)"""").findAll(t).associate { it.groupValues[1] to it.groupValues[2] }
            for ((k, v) in const) {
                if (Regex("""getSharedPreferences\($k\b""").containsMatchIn(t)) prefs += v
                if (Regex("""noBackupFilesDir, $k\b""").containsMatchIn(t)) nobackup += v
            }
            Regex("""SQLiteOpenHelper\(\w+, "([^"]+)"""").findAll(t).forEach { dbs += it.groupValues[1] }
            Regex("""name: String\? = "([^"]+\.db)"""").findAll(t).forEach { dbs += it.groupValues[1] }
            Regex("""[^.]filesDir, "([^"]+)"""").findAll(t).forEach { files += it.groupValues[1].substringBefore('/') }
            Regex("""noBackupFilesDir, "([^"]+)"""").findAll(t).forEach { nobackup += it.groupValues[1].substringBefore('/') }
            Regex("""getExternalFilesDir\(null\)\?\.let \{ File\(it, "([^"]+)"""").findAll(t).forEach { external += it.groupValues[1] }
        }
        return mapOf("prefs" to prefs, "db" to dbs, "files" to files, "nobackup" to nobackup, "external" to external)
    }

    @Test fun everyStoreInTheSourceIsKeptOrWipedOnPurpose() {
        val f = found()
        // The scan itself works: a few stores it must see.
        assertTrue(f.getValue("prefs").containsAll(setOf("calypso", "lemnos", "mind", "athena", "brain", "models", "oneiroi", "olympia", "aeolia")))
        assertTrue(f.getValue("db").containsAll(setOf("brain.db", "vault.db", "moneta.db", "senses.db")))
        assertTrue(f.getValue("nobackup").containsAll(setOf("odyssey.sealed", "odyssey-routes", "argo.sealed", "seal")))
        val plans = mapOf("prefs" to FreshPlan.PREFS, "db" to FreshPlan.DBS, "files" to FreshPlan.FILES, "nobackup" to FreshPlan.NOBACKUP, "external" to FreshPlan.EXTERNAL)
        val missing = f.flatMap { (kind, names) -> names.filter { it !in plans.getValue(kind) }.map { "$kind:$it" } }
        assertTrue("Not classified in FreshPlan (add each as KEEP, WIPE or SOME): $missing", missing.isEmpty())
    }

    @Test fun onlyKeysPairingBackupMetersAndDrivesAreKept() {
        val kept = (FreshPlan.PREFS + FreshPlan.DBS + FreshPlan.FILES + FreshPlan.NOBACKUP + FreshPlan.EXTERNAL).filterValues { it != Fate.WIPE }.keys
        assertEquals(
            setOf("calypso", "vault", "lemnos", "mnemosyne", "prometheus-cloud", "metron", "brain", "mind", "vault.db", "zeus-pair", "seal", "odyssey.sealed", "odyssey-routes", dev.phonerecall.memory.GemmaEmbedder.FILE),
            kept,
        )
        // What he named for the wipe.
        for (p in listOf("models", "oneiroi", "athena", "athena_dismissed", "relevance", "places", "music", "hestia", "widgets", "alarm_watch", "shortcuts", "aeolia", "olympia")) assertEquals(p, Fate.WIPE, FreshPlan.PREFS[p])
        for (d in listOf("brain.db", "senses.db", "tasks.db", "alexandria.db", "moneta.db", "learning.db")) assertEquals(d, Fate.WIPE, FreshPlan.DBS[d])
        for (n in listOf("argo.sealed", "moments.sealed", "rhythms.sealed")) assertEquals(n, Fate.WIPE, FreshPlan.NOBACKUP[n])
        for (n in listOf("parnassus", "clio", "alexandria", "oneiroi", "closet")) assertEquals(n, Fate.WIPE, FreshPlan.FILES[n])
    }

    @Test fun insideTheMixedFilesOnlyTheRightKeysStay() {
        // The month's spend and cap stay (the cap holds); auto-approve and the model go.
        assertTrue(FreshPlan.keepKey("brain", "spend.micros")); assertTrue(FreshPlan.keepKey("brain", "cap")); assertTrue(FreshPlan.keepKey("brain", "spend.mind"))
        assertFalse(FreshPlan.keepKey("brain", "auto")); assertFalse(FreshPlan.keepKey("brain", "model"))
        // Her stored agents and store stay; her session, schedules, Theogony, handoffs, tasks go.
        for (k in listOf("id.env", "id.store", "id.agent.Zeus · Athena", "hash.agent.Zeus · Annabeth", "circle.2026-10")) assertTrue(k, FreshPlan.keepKey("mind", k))
        for (k in listOf("session.id", "session.day", "handoff.latest", "sched.user", "id.depl.Zeus · dream", "theo.phase", "theo.facts", "tasks", "batch.queue", "chat.queue", "model.protege.annabeth")) assertFalse(k, FreshPlan.keepKey("mind", k))
        // The pairing stays; Agora's local cursors go.
        assertTrue(FreshPlan.keepKey("lemnos", "paired")); assertTrue(FreshPlan.keepKey("lemnos", "fcm")); assertTrue(FreshPlan.keepKey("lemnos", "events.seq"))
        assertFalse(FreshPlan.keepKey("lemnos", "agora.after")); assertFalse(FreshPlan.keepKey("lemnos", "agora.seen"))
    }

    @Test fun librariesAreLeftAlone() {
        for (n in listOf("com.google.firebase.messaging", "androidx.work.workdb", "FirebaseHeartBeat", "PersistedInstallation.x.json", "WebViewChromiumPrefs")) {
            assertTrue(n, FreshPlan.library(n))
            assertEquals(null, FreshPlan.fate(FreshPlan.PREFS, n))
        }
        assertEquals("fresh start: cleared 37 stores, kept keys/pairing/drives", FreshPlan.line(37))
    }

    @Test fun drivesStayInTheCurrentFormatAndEverythingElseInOdysseyGoes() {
        val good = DriveReport("d1", start = 1_000, end = 600_000, meters = 8_000, tag = "COMMUTE")
        val d = OdysseyData(
            places = listOf(KnownPlace("k1", "Home")), visits = listOf(Visit("v1", "k1", 0, 10)), trips = listOf(Trip("t1", driveId = "d1")),
            drives = listOf(good, DriveReport("", 1, 2), DriveReport("d2", start = 5_000, end = 5_000), good.copy(meters = 9)),
            car = CarData(Lease(car = "CX-5")), nextId = 42,
        )
        val k = dev.phonerecall.odyssey.OdysseyFresh.keep(d)
        assertEquals(listOf(good), k.drives)
        assertEquals("COMMUTE", k.drives.single().tag)
        assertEquals("CX-5", k.car.lease.car)
        assertEquals(42, k.nextId)
        assertTrue(k.places.isEmpty() && k.visits.isEmpty() && k.trips.isEmpty())

        val now = 10L * 86_400_000L
        val pt = listOf(TrackPoint(1, 42.9, -78.8, 30))
        assertTrue(dev.phonerecall.odyssey.OdysseyFresh.route(DriveRoute("d1", now - 1_000, pt), setOf("d1"), now))
        assertFalse("its report is gone", dev.phonerecall.odyssey.OdysseyFresh.route(DriveRoute("dx", now - 1_000, pt), setOf("d1"), now))
        assertFalse("empty", dev.phonerecall.odyssey.OdysseyFresh.route(DriveRoute("d1", now - 1_000, emptyList()), setOf("d1"), now))
        assertFalse("past 90 days", dev.phonerecall.odyssey.OdysseyFresh.route(DriveRoute("d1", now - 91L * 86_400_000L, pt), setOf("d1"), now + 91L * 86_400_000L))
        assertFalse("didn't open", dev.phonerecall.odyssey.OdysseyFresh.route(null, setOf("d1"), now))
    }
}
