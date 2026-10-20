package dev.phonerecall.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable

/** Taps and dismissals for one card key in one hour of the week. */
@Immutable data class Counts(val taps: Int, val dismissals: Int)

/** In-memory copy of the learning table: key -> hour-of-week (0..167, Monday 00:00 = 0) -> counts. */
@Immutable
data class Learned(val table: Map<String, Map<Int, Counts>> = emptyMap()) {
    fun at(key: String, hourOfWeek: Int): Counts? = table[key]?.get(hourOfWeek)

    fun plus(key: String, hourOfWeek: Int, tap: Boolean): Learned {
        val byHour = table[key].orEmpty()
        val old = byHour[hourOfWeek] ?: Counts(0, 0)
        val new = if (tap) old.copy(taps = old.taps + 1) else old.copy(dismissals = old.dismissals + 1)
        return Learned(table + (key to (byHour + (hourOfWeek to new))))
    }
}

/**
 * The learning scaffold: one small SQLite table on the phone. Nothing here leaves the device.
 * Columns: key (card kind, or GENERATED:<source>), how (hour of week), taps, dismissals.
 */
class EngagementStore(context: Context) : SQLiteOpenHelper(context, "learning.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE engagement (key TEXT NOT NULL, how INTEGER NOT NULL, taps INTEGER NOT NULL DEFAULT 0, " +
                "dismissals INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (key, how))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Call on IO. */
    fun load(): Learned {
        val out = HashMap<String, HashMap<Int, Counts>>()
        readableDatabase.rawQuery("SELECT key, how, taps, dismissals FROM engagement", null).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { HashMap() }[c.getInt(1)] = Counts(c.getInt(2), c.getInt(3))
            }
        }
        return Learned(out)
    }

    /** Call on IO. */
    fun record(key: String, hourOfWeek: Int, tap: Boolean) {
        val db = writableDatabase
        db.insertWithOnConflict(
            "engagement", null,
            ContentValues().apply { put("key", key); put("how", hourOfWeek) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val col = if (tap) "taps" else "dismissals"
        db.execSQL("UPDATE engagement SET $col = $col + 1 WHERE key = ? AND how = ?", arrayOf<Any>(key, hourOfWeek))
    }

    /** Call on IO. */
    fun reset() {
        writableDatabase.delete("engagement", null, null)
    }
}
