package dev.phonerecall.fresh

import android.content.Context
import android.content.SharedPreferences
import dev.phonerecall.brain.Sealer
import dev.phonerecall.odyssey.OdysseyFresh
import dev.phonerecall.odyssey.OdysseyStore
import java.io.File

/** One fresh start's outcome: how many stores went, which stayed, which names Zeus didn't recognise (left alone). */
data class FreshReport(val cleared: List<String>, val kept: List<String>, val unknown: List<String>, val drives: Int, val routes: Int, val error: String? = null)

/**
 * The fresh start, once per phone for [FreshPlan.VERSION]. Runs from [dev.phonerecall.mnemosyne.RestoreGate], the
 * first content provider, so it is done before WorkManager, Firebase, the receivers, the workers or any screen open a
 * store: everything after it starts on the clean slate. A marker in no-backup storage keeps it to once (a restore
 * from Mnemosyne writes the marker instead: restored data is never wiped). Deletes files, SharedPreferences and
 * databases with Android's own calls; nothing has opened them yet, so each store re-creates itself empty on first use.
 */
object FreshStart {
    /** The Clio line, written once Clio is up ([dev.phonerecall.ZeusApp]). */
    @Volatile var pendingLine: String? = null
        private set

    fun marker(c: Context) = File(c.noBackupFilesDir, FreshPlan.VERSION)

    fun done(c: Context) = marker(c).exists()

    /** Blocking. `restored`: a Mnemosyne restore was just moved in. */
    fun gate(c: Context, restored: Boolean, odysseySealer: () -> Sealer = { OdysseyStore.sealer }) {
        val mark = marker(c)
        if (mark.exists()) return
        if (restored) { mark.writeText("restored ${System.currentTimeMillis()}"); return }
        // Before anything seals: the envelope keys' folder (ZeusApp sets the same one later).
        if (dev.phonerecall.brain.SealKeys.dir == null) dev.phonerecall.brain.SealKeys.dir = File(c.noBackupFilesDir, "seal")
        val r = runCatching { wipe(c, odysseySealer) }.getOrElse { FreshReport(emptyList(), emptyList(), emptyList(), 0, 0, it.javaClass.simpleName + ": " + it.message) }
        // Written even after an error: a second run would wipe what he set up since.
        mark.writeText("wiped ${System.currentTimeMillis()} cleared=${r.cleared.size} kept=${r.kept.size}" + (r.error?.let { " error=$it" } ?: ""))
        pendingLine = FreshPlan.line(r.cleared.size) + (r.error?.let { " (stopped early: $it)" } ?: "")
        android.util.Log.i("Zeus", pendingLine!!)
    }

    fun logPending() {
        val l = pendingLine ?: return
        pendingLine = null
        dev.phonerecall.clio.Clio.info("setup", l)
    }

    internal fun wipe(c: Context, odysseySealer: () -> Sealer): FreshReport {
        val cleared = ArrayList<String>()
        val kept = ArrayList<String>()
        val unknown = ArrayList<String>()
        fun sort(kind: String, name: String, fate: Fate?) = when (fate) {
            Fate.WIPE -> { cleared += "$kind:$name"; true }
            Fate.KEEP, Fate.SOME -> { kept += "$kind:$name"; false }
            null -> { unknown += "$kind:$name"; false }
        }

        // SharedPreferences.
        val prefsDir = File(c.dataDir, "shared_prefs")
        val prefs = prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }.orEmpty().sorted()
        for (name in prefs) {
            val fate = FreshPlan.fate(FreshPlan.PREFS, name)
            if (sort("prefs", name, fate)) c.deleteSharedPreferences(name)
            else if (fate == Fate.SOME) trim(c.getSharedPreferences(name, Context.MODE_PRIVATE)) { FreshPlan.keepKey(name, it) }
        }

        // Databases (deleteDatabase takes the journal and WAL files with it).
        val dbDir = c.getDatabasePath("x").parentFile
        val dbs = dbDir?.listFiles()?.filter { f -> f.isFile && listOf("-journal", "-wal", "-shm").none { f.name.endsWith(it) } }?.map { it.name }.orEmpty().sorted()
        for (name in dbs) if (sort("db", name, FreshPlan.fate(FreshPlan.DBS, name))) c.deleteDatabase(name)

        // Files: filesDir, no-backup, external; the cache goes whole.
        fun dir(kind: String, root: File?, plan: Map<String, Fate>) {
            for (f in root?.listFiles().orEmpty().sortedBy { it.name }) if (sort(kind, f.name, FreshPlan.fate(plan, f.name))) f.deleteRecursively()
        }
        dir("files", c.filesDir, FreshPlan.FILES)
        dir("nobackup", c.noBackupFilesDir, FreshPlan.NOBACKUP)
        dir("external", c.getExternalFilesDir(null), FreshPlan.EXTERNAL)
        c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        // Drives: kept in the current format, else wiped.
        val (drives, routes) = OdysseyFresh.migrate(c.noBackupFilesDir, runCatching { odysseySealer() }.getOrNull())

        // What the clean slate starts with: Athena's cloud side to reset on her next sync, and the onboarding.
        c.getSharedPreferences("mind", Context.MODE_PRIVATE).edit().putString(FreshCloud.KEY, FreshCloud.PENDING).commit()
        c.getSharedPreferences(Genesis.PREFS, Context.MODE_PRIVATE).edit().putString(Genesis.K_START, "${System.currentTimeMillis()}").commit()
        return FreshReport(cleared, kept, unknown, drives, routes)
    }

    /** Keeps only the keys [keep] says, with their types. */
    private fun trim(sp: SharedPreferences, keep: (String) -> Boolean) {
        val all = sp.all
        val e = sp.edit().clear()
        for ((k, v) in all) {
            if (!keep(k)) continue
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> e.putString(k, v)
                is Long -> e.putLong(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v as Set<String>)
            }
        }
        e.commit()
    }
}
