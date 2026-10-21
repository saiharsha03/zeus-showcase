package dev.phonerecall.brain

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Seals again every value in `cols` of `table` that isn't in the sealer's current format (the Keystore-direct
 * values from before envelopes). A row changed in between keeps its newer value: the update only applies while
 * the column still holds what was read. Returns how many values were rewritten. Call on IO.
 */
fun SQLiteDatabase.reseal(sealer: Sealer, table: String, cols: List<String>): Int {
    var n = 0
    for (col in cols) {
        val old = rawQuery("SELECT rowid, $col FROM $table WHERE $col IS NOT NULL", null).use { c ->
            buildList { while (c.moveToNext()) c.getString(1).takeIf { !sealer.current(it) }?.let { add(c.getLong(0) to it) } }
        }
        // Opened first (old values are a Keystore call each), then written in one short transaction.
        val fresh = old.mapNotNull { (row, sealed) -> sealer.open(sealed)?.let { Triple(row, sealed, sealer.seal(it)) } }
        if (fresh.isEmpty()) continue
        beginTransaction()
        try {
            for ((row, sealed, next) in fresh) n += update(table, ContentValues().apply { put(col, next) }, "rowid = ? AND $col = ?", arrayOf("$row", sealed))
            setTransactionSuccessful()
        } finally { endTransaction() }
    }
    return n
}

/**
 * Once per phone: the stores read row by row (people, cards, notes, Moneta's ledger) are rewritten from the
 * Keystore-direct format to envelopes, so later reads cost no Keystore call per value. Single-blob stores (Odyssey's
 * file, prefs) move over on their next save. Started when Zeus comes to the front; logged to Clio.
 */
object SealMigration {
    private const val DONE = "envelope.v1"
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** On its own low-priority thread, once at a time. */
    fun start(app: android.content.Context) {
        if (running.compareAndSet(false, true)) kotlin.concurrent.thread(name = "zeus-reseal", priority = Thread.MIN_PRIORITY) { try { runOnce(app) } finally { running.set(false) } }
    }

    fun runOnce(app: android.content.Context) {
        val sp = app.getSharedPreferences("seal", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean(DONE, false) || SealKeys.dir == null) return
        val started = android.os.SystemClock.elapsedRealtime()
        val r = runCatching {
            var n = BrainCore.get(app).store.reseal()
            n += dev.phonerecall.moneta.MonetaCore.get(app).store.reseal()
            n
        }
        r.onSuccess { n ->
            sp.edit().putBoolean(DONE, true).apply()
            dev.phonerecall.clio.Clio.info("audit", "sealed stores moved to envelopes", "$n values · ${android.os.SystemClock.elapsedRealtime() - started} ms")
        }.onFailure { dev.phonerecall.clio.Clio.error("audit", it) }
    }
}
