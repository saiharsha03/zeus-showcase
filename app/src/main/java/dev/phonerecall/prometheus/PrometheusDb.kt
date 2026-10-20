package dev.phonerecall.prometheus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * prometheus.db: runs, suggestions, the log, the applied card weights and Athena's notes, and Delphi's
 * thumbs. Local only, excluded from backup like brain.db. Every method does IO.
 */
class PrometheusDb(context: Context) : SQLiteOpenHelper(context, "prometheus.db", null, 1), PromStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, found INTEGER NOT NULL DEFAULT 0, refused INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')")
        db.execSQL(
            "CREATE TABLE tweaks (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL, value TEXT NOT NULL, " +
                "before TEXT NOT NULL, why TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, decided_at INTEGER, source TEXT NOT NULL)",
        )
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)")
        db.execSQL("CREATE TABLE weights (key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE notes (pos INTEGER PRIMARY KEY, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE feedback (line_id INTEGER PRIMARY KEY, up INTEGER NOT NULL, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun addRun(at: Long, kind: String): Long = writableDatabase.insert("runs", null, ContentValues().apply { put("at", at); put("kind", kind) })

    override fun finishRun(id: Long, found: Int, refused: Int, note: String) {
        writableDatabase.update("runs", ContentValues().apply { put("found", found); put("refused", refused); put("note", note) }, "id = ?", arrayOf("$id"))
    }

    override fun runs(limit: Int): List<PromRun> = readableDatabase.rawQuery("SELECT id, at, kind, found, refused, note FROM runs ORDER BY id DESC LIMIT $limit", null).use { c ->
        buildList { while (c.moveToNext()) add(PromRun(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4), c.getString(5))) }
    }

    private fun values(t: Tweak) = ContentValues().apply {
        put("run_id", t.runId); put("kind", t.kind.wire); put("target", t.target); put("value", t.value); put("before", t.before); put("why", t.why)
        put("status", t.status.name); put("created_at", t.createdAt); put("source", t.source)
        if (t.decidedAt != null) put("decided_at", t.decidedAt) else putNull("decided_at")
    }

    override fun insert(t: Tweak): Long = writableDatabase.insert("tweaks", null, values(t))
    override fun save(t: Tweak) { writableDatabase.update("tweaks", values(t), "id = ?", arrayOf("${t.id}")) }

    private fun tweaks(where: String?, args: Array<String>?, limit: Int): List<Tweak> =
        readableDatabase.rawQuery(
            "SELECT id, run_id, kind, target, value, before, why, status, created_at, decided_at, source FROM tweaks ${where?.let { "WHERE $it" } ?: ""} ORDER BY id DESC LIMIT $limit", args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = TweakKind.of(c.getString(2)) ?: continue
                    add(Tweak(
                        c.getLong(0), c.getLong(1), kind, c.getString(3), c.getString(4), c.getString(5), c.getString(6),
                        runCatching { TweakStatus.valueOf(c.getString(7)) }.getOrDefault(TweakStatus.REJECTED), c.getLong(8),
                        if (c.isNull(9)) null else c.getLong(9), c.getString(10),
                    ))
                }
            }
        }

    override fun get(id: Long): Tweak? = tweaks("id = ?", arrayOf("$id"), 1).firstOrNull()
    override fun tweaks(limit: Int): List<Tweak> = tweaks(null, null, limit)

    override fun log(at: Long, action: String, detail: String) {
        writableDatabase.insert("log", null, ContentValues().apply { put("at", at); put("action", action); put("detail", detail) })
    }

    override fun logs(limit: Int): List<PromLog> = readableDatabase.rawQuery("SELECT at, action, detail FROM log ORDER BY id DESC LIMIT $limit", null).use { c ->
        buildList { while (c.moveToNext()) add(PromLog(c.getLong(0), c.getString(1), c.getString(2))) }
    }

    fun weights(): Map<String, Int> = readableDatabase.rawQuery("SELECT key, value FROM weights", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
    }

    fun setWeight(key: String, value: Int) {
        if (value == 0) writableDatabase.delete("weights", "key = ?", arrayOf(key))
        else writableDatabase.insertWithOnConflict("weights", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun notes(): List<String> = readableDatabase.rawQuery("SELECT text FROM notes ORDER BY pos", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun setNotes(notes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("notes", null, null)
            notes.forEachIndexed { i, t -> db.insert("notes", null, ContentValues().apply { put("pos", i); put("text", t) }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun feedback(lineId: Long, up: Boolean, at: Long) {
        writableDatabase.insertWithOnConflict("feedback", null, ContentValues().apply { put("line_id", lineId); put("up", if (up) 1 else 0); put("at", at) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun thumbs(): Map<Long, Boolean> = readableDatabase.rawQuery("SELECT line_id, up FROM feedback", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1) == 1) }
    }
}
