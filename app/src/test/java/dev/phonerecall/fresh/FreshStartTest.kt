package dev.phonerecall.fresh

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.phonerecall.brain.Sealer
import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.DriveRoute
import dev.phonerecall.odyssey.KnownPlace
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.OdysseyStore
import dev.phonerecall.odyssey.RouteFiles
import dev.phonerecall.odyssey.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FreshStartTest {
    private val c: Context = ApplicationProvider.getApplicationContext()

    /** Robolectric has no Keystore: Odyssey's document in the clear stands in for the sealed one. */
    private object Plain : Sealer {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun sealBytes(plain: ByteArray) = plain
        override fun openBytes(sealed: ByteArray) = sealed
    }

    private fun prefs(name: String) = c.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun db(name: String) = SQLiteDatabase.openOrCreateDatabase(c.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { it.execSQL("CREATE TABLE t(x)"); it.execSQL("INSERT INTO t VALUES(1)") }
    private fun file(root: File, path: String) = File(root, path).apply { parentFile?.mkdirs(); writeText("x") }
    private val now = System.currentTimeMillis()
    private val drive = DriveReport("d7", start = now - 3_600_000, end = now - 3_000_000, meters = 12_000, tag = "COMMUTE")

    @Before fun phoneFullOfOldZeus() {
        prefs("calypso").edit().putString("anthropic.ct", "sealed").commit()
        prefs("vault").edit().putString("pem.sent", "1").commit()
        prefs("lemnos").edit().putBoolean("paired", true).putString("fcm", "tok").putLong("agora.after", 900).putLong("agora.seen", 900).commit()
        prefs("mnemosyne").edit().putBoolean("on", true).commit()
        prefs("metron").edit().putString("x", "1").commit()
        prefs("brain").edit().putLong("spend.micros", 4_200_000).putInt("cap", 100).putStringSet("auto", setOf("add_task")).putString("model", "x").commit()
        prefs("mind").edit().putString("id.agent.Zeus · Athena", "agent_1@3").putString("id.store", "memstore_1").putString("session.id", "sesn_9")
            .putString("theo.phase", "DONE").putString("handoff.latest", "2026-10-19").putString("circle.2026-10", "{}").commit()
        for (p in listOf("athena", "models", "oneiroi", "places", "olympia", "music", "athena_dismissed", "widgets")) prefs(p).edit().putString("k", "v").commit()
        prefs("com.google.firebase.messaging").edit().putString("token", "keep").commit()
        for (d in listOf("brain.db", "senses.db", "moneta.db", "tasks.db", "alexandria.db", "vault.db", "androidx.work.workdb")) db(d)
        file(c.filesDir, "zeus-pair/ca.pem"); file(c.filesDir, "clio/2026-10-20.jsonl"); file(c.filesDir, "parnassus/a.txt"); file(c.filesDir, "PersistedInstallation.x.json")
        file(c.noBackupFilesDir, "seal/zeus.aegis.odyssey.keys"); file(c.noBackupFilesDir, "argo.sealed"); file(c.noBackupFilesDir, "rhythms.sealed")
        file(c.cacheDir, "box/box.jpg")
        val doc = OdysseyData(places = listOf(KnownPlace("k1", "Home")), drives = listOf(drive, DriveReport("", 1, 2)))
        File(c.noBackupFilesDir, "odyssey.sealed").writeBytes(OdysseyStore.encode(Plain, doc))
        val routes = RouteFiles(File(c.noBackupFilesDir, "odyssey-routes"), Plain)
        routes.save(DriveRoute("d7", drive.end, listOf(TrackPoint(1, 42.9, -78.8, 40))))
        routes.save(DriveRoute("gone", drive.end, listOf(TrackPoint(1, 42.9, -78.8, 40))))
    }

    private fun gate(restored: Boolean = false) = FreshStart.gate(c, restored) { Plain }

    @Test fun wipesEverythingButKeysPairingAndDrives() {
        gate()
        // Kept.
        assertEquals("sealed", prefs("calypso").getString("anthropic.ct", null))
        assertEquals("1", prefs("vault").getString("pem.sent", null))
        assertTrue(prefs("lemnos").getBoolean("paired", false)); assertEquals("tok", prefs("lemnos").getString("fcm", null))
        assertTrue(prefs("mnemosyne").getBoolean("on", false))
        assertEquals("keep", prefs("com.google.firebase.messaging").getString("token", null))
        assertTrue(c.getDatabasePath("vault.db").exists()); assertTrue(c.getDatabasePath("androidx.work.workdb").exists())
        assertTrue(File(c.filesDir, "zeus-pair/ca.pem").exists()); assertTrue(File(c.filesDir, "PersistedInstallation.x.json").exists())
        assertTrue(File(c.noBackupFilesDir, "seal/zeus.aegis.odyssey.keys").exists())
        // Kept in part.
        assertEquals(4_200_000L, prefs("brain").getLong("spend.micros", 0)); assertEquals(100, prefs("brain").getInt("cap", 0))
        assertNull(prefs("brain").getStringSet("auto", null)); assertNull(prefs("brain").getString("model", null))
        assertEquals("agent_1@3", prefs("mind").getString("id.agent.Zeus · Athena", null)); assertEquals("memstore_1", prefs("mind").getString("id.store", null))
        assertNull(prefs("mind").getString("session.id", null)); assertNull(prefs("mind").getString("theo.phase", null)); assertNull(prefs("mind").getString("handoff.latest", null))
        assertEquals(-1L, prefs("lemnos").getLong("agora.after", -1)); assertEquals(0L, prefs("lemnos").getLong("agora.seen", 0))
        // Wiped.
        for (p in listOf("athena", "models", "oneiroi", "places", "olympia", "music", "athena_dismissed", "widgets")) assertTrue(p, prefs(p).all.isEmpty())
        for (d in listOf("brain.db", "senses.db", "moneta.db", "tasks.db", "alexandria.db")) assertFalse(d, c.getDatabasePath(d).exists())
        assertFalse(File(c.filesDir, "clio").exists()); assertFalse(File(c.filesDir, "parnassus").exists())
        assertFalse(File(c.noBackupFilesDir, "argo.sealed").exists()); assertFalse(File(c.noBackupFilesDir, "rhythms.sealed").exists())
        assertFalse(File(c.cacheDir, "box").exists())
        // Drives: the whole one and its route; the broken one, the orphan route and the places are gone.
        val left = OdysseyStore.decode(Plain, File(c.noBackupFilesDir, "odyssey.sealed").readBytes())!!
        assertEquals(listOf(drive), left.drives)
        assertTrue(left.places.isEmpty())
        assertEquals(listOf("d7.sealed"), File(c.noBackupFilesDir, "odyssey-routes").list()!!.toList())
        // What the clean slate starts with: the cloud reset due, Genesis on, one line for Clio.
        assertEquals(FreshCloud.PENDING, prefs("mind").getString(FreshCloud.KEY, null))
        assertTrue(Genesis(dev.phonerecall.mind.PrefsKv(c, Genesis.PREFS)).active)
        assertTrue(FreshStart.pendingLine!!.startsWith("fresh start: cleared "))
        assertTrue(FreshStart.pendingLine!!.endsWith(" stores, kept keys/pairing/drives"))
    }

    @Test fun runsOnceOnly() {
        gate()
        assertTrue(FreshStart.done(c))
        prefs("athena").edit().putString("dark", "1").commit()
        db("brain.db")
        gate()
        assertEquals("1", prefs("athena").getString("dark", null))
        assertTrue(c.getDatabasePath("brain.db").exists())
    }

    @Test fun aRestoreIsNeverWiped() {
        gate(restored = true)
        assertTrue(FreshStart.done(c))
        assertEquals("v", prefs("athena").getString("k", null))
        assertTrue(c.getDatabasePath("brain.db").exists())
        assertFalse(Genesis(dev.phonerecall.mind.PrefsKv(c, Genesis.PREFS)).active)
    }

    @Test fun drivesThatDoNotOpenAreWipedToo() {
        File(c.noBackupFilesDir, "odyssey.sealed").writeText("not a document")
        gate()
        assertFalse(File(c.noBackupFilesDir, "odyssey.sealed").exists())
        assertFalse(File(c.noBackupFilesDir, "odyssey-routes").exists())
    }

    /** The gate is Mnemosyne's provider, the first to start: everything else in the process starts after the wipe. */
    @Test fun theWipeRunsInTheFirstProviderBeforeAnyStoreOrWorker() {
        org.robolectric.Robolectric.buildContentProvider(dev.phonerecall.mnemosyne.RestoreGate::class.java).create()
        assertTrue(FreshStart.done(c))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val gateBlock = Regex("""<provider\s+android:name="\.mnemosyne\.RestoreGate"[^>]*>""").find(manifest)!!.value
        val order = Regex("""initOrder="(\d+)"""").find(gateBlock)!!.groupValues[1].toInt()
        val others = Regex("""initOrder="(\d+)"""").findAll(manifest).map { it.groupValues[1].toInt() }.toList()
        assertEquals(others.max(), order)
        assertTrue(order >= 1000)
    }
}
