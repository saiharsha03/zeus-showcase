package dev.phonerecall.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import dev.phonerecall.brain.Sealer

/** A Calypso item as the list shows it: never its content. */
@Immutable data class SealedItem(val id: String, val kind: ItemKind, val label: String, val from: String, val at: Long, val size: Int)

@Immutable data class AegisItem(val name: String, val note: String, val scope: AegisScope, val revoked: Boolean, val updated: Long, val uses: Int)

/** One line in a vault's log: who did what to which item, and why when an agent says. */
@Immutable data class VaultLogRow(val at: Long, val vault: String, val who: String, val action: String, val item: String, val why: String)

/**
 * vault.db: Calypso's sealed items, Aegis's items and both logs. Local only, excluded from backup.
 * Calypso rows are ciphertext to the box key (labels sealed again with the Aegis-level key, so the
 * file shows nothing); Aegis values are sealed with the Aegis-level key. Pass a null name for an
 * in-memory database (tests). Call on IO.
 */
class VaultDb(context: Context, private val sealer: Sealer, name: String? = "vault.db") : SQLiteOpenHelper(context, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE calypso (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, sender TEXT NOT NULL, at INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, wk BLOB NOT NULL, iv BLOB NOT NULL, ct BLOB NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE aegis (name TEXT PRIMARY KEY, value TEXT NOT NULL, note TEXT NOT NULL, scope TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, " +
                "updated INTEGER NOT NULL, uses INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, vault TEXT NOT NULL, who TEXT NOT NULL, action TEXT NOT NULL, item TEXT NOT NULL, why TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- Calypso ----

    /** Append-only for depositors: an id that is already there is never overwritten. */
    fun putSealed(e: Envelope): Boolean = writableDatabase.insertWithOnConflict(
        "calypso", null,
        ContentValues().apply {
            put("id", e.id); put("kind", e.kind.wire); put("label", sealer.seal(e.label)); put("sender", e.from); put("at", e.at)
            put("size", e.ct.size); put("wk", e.wk); put("iv", e.iv); put("ct", e.ct)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    ) != -1L

    fun sealedItems(): List<SealedItem> = readableDatabase.rawQuery("SELECT id, kind, label, sender, at, size FROM calypso ORDER BY at DESC", null).use { c ->
        buildList {
            while (c.moveToNext()) add(SealedItem(c.getString(0), ItemKind.of(c.getString(1)) ?: ItemKind.NOTE, sealer.open(c.getString(2)) ?: "Sealed item", c.getString(3), c.getLong(4), c.getInt(5)))
        }
    }

    fun envelope(id: String): Envelope? = readableDatabase.rawQuery("SELECT id, kind, label, sender, at, wk, iv, ct FROM calypso WHERE id = ?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null
        else Envelope(c.getString(0), ItemKind.of(c.getString(1)) ?: return null, sealer.open(c.getString(2)) ?: return null, c.getString(3), c.getLong(4), c.getBlob(5), c.getBlob(6), c.getBlob(7))
    }

    fun deleteSealed(id: String) = writableDatabase.delete("calypso", "id = ?", arrayOf(id)) > 0

    /** Raw rows, for the test that nothing in the file is plaintext. */
    internal fun rawCalypso(): List<ByteArray> = readableDatabase.rawQuery("SELECT label, sender, wk, iv, ct FROM calypso", null).use { c ->
        buildList { while (c.moveToNext()) { add(c.getString(0).toByteArray()); add(c.getString(1).toByteArray()); add(c.getBlob(2)); add(c.getBlob(3)); add(c.getBlob(4)) } }
    }

    // ---- Aegis ----

    fun putAegis(name: String, value: String, note: String, scope: AegisScope, now: Long) {
        val old = aegis(name)
        writableDatabase.insertWithOnConflict(
            "aegis", null,
            ContentValues().apply {
                put("name", name); put("value", sealer.seal(value)); put("note", note); put("scope", scope.wire)
                put("revoked", if (old?.revoked == true) 1 else 0); put("updated", now); put("uses", old?.uses ?: 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun aegisItems(): List<AegisItem> = readableDatabase.rawQuery("SELECT name, note, scope, revoked, updated, uses FROM aegis ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(aegisRow(c)) }
    }

    fun aegis(name: String): AegisItem? = readableDatabase.rawQuery("SELECT name, note, scope, revoked, updated, uses FROM aegis WHERE name = ?", arrayOf(name)).use { c ->
        if (c.moveToFirst()) aegisRow(c) else null
    }

    private fun aegisRow(c: android.database.Cursor) = AegisItem(c.getString(0), c.getString(1), AegisScope.of(c.getString(2)), c.getInt(3) != 0, c.getLong(4), c.getInt(5))

    fun aegisValue(name: String): String? = readableDatabase.rawQuery("SELECT value FROM aegis WHERE name = ?", arrayOf(name)).use { c ->
        if (c.moveToFirst()) sealer.open(c.getString(0)) else null
    }

    fun setAegis(name: String, revoked: Boolean? = null, scope: AegisScope? = null) {
        val v = ContentValues().apply { revoked?.let { put("revoked", if (it) 1 else 0) }; scope?.let { put("scope", it.wire) } }
        if (v.size() > 0) writableDatabase.update("aegis", v, "name = ?", arrayOf(name))
    }

    fun used(name: String) = writableDatabase.execSQL("UPDATE aegis SET uses = uses + 1 WHERE name = ?", arrayOf(name))

    fun deleteAegis(name: String) = writableDatabase.delete("aegis", "name = ?", arrayOf(name)) > 0

    // ---- logs ----

    fun log(at: Long, vault: String, who: String, action: String, item: String, why: String = "") {
        writableDatabase.insert("log", null, ContentValues().apply {
            put("at", at); put("vault", vault); put("who", who.take(40)); put("action", action); put("item", item.take(80)); put("why", why.take(200))
        })
    }

    fun logRows(vault: String, limit: Int = 200): List<VaultLogRow> =
        readableDatabase.rawQuery("SELECT at, vault, who, action, item, why FROM log WHERE vault = ? ORDER BY id DESC LIMIT ?", arrayOf(vault, limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(VaultLogRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
        }
}
