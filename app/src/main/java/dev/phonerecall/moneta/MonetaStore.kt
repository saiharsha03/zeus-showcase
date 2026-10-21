package dev.phonerecall.moneta

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.brain.Sealer
import dev.phonerecall.brain.reseal
import java.time.LocalDate

/** One line in Moneta's change log. `undo` is null for changes that can't be taken back (a ledger sync). */
@Immutable data class MonetaChange(val id: Long, val at: Long, val who: String, val summary: String, val undoable: Boolean, val undone: Boolean)

/**
 * moneta.db: every row is one sealed JSON blob (the Aegis-level Keystore key, so the notification listener and the
 * Iris sync can write in the background), next to only its time and, for outside rows, its key ("iris:812").
 * Budgets, subscriptions, charts, dismissed alerts and the change log are sealed the same way. Local only, excluded
 * from backup. Pass a null name for an in-memory database (tests). Call on IO.
 */
class MonetaStore(context: Context, private val sealer: Sealer, name: String? = "moneta.db") : SQLiteOpenHelper(context, name, null, 2) {
    private val json = jsonMapper()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE txns (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, blob TEXT NOT NULL, k TEXT)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, blob TEXT NOT NULL)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            if (db.rawQuery("PRAGMA table_info(txns)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.none { it == "k" } }) {
                db.execSQL("ALTER TABLE txns ADD COLUMN k TEXT")
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS txns_k ON txns(k)")
            db.execSQL("CREATE TABLE IF NOT EXISTS changes (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, blob TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0)")
        }
    }

    /** Every sealed blob, rewritten once into the sealer's current format (envelopes). */
    fun reseal(): Int = writableDatabase.let { db -> listOf("txns", "kv", "changes").sumOf { db.reseal(sealer, it, listOf("blob")) } }

    // ---- transactions ----

    private fun txnMap(t: Txn) = linkedMapOf(
        "at" to t.at, "cents" to t.cents, "kind" to t.kind.name, "merchant" to t.merchant, "category" to t.category.name,
        "bank" to t.bank, "last4" to t.last4?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }, "source" to t.source.name, "note" to t.note,
        "currency" to t.currency, "merged" to t.mergedInto, "edited" to t.edited, "via" to t.via?.name, "pending" to t.pending,
    )

    private fun values(t: Txn) = ContentValues().apply { put("at", t.at); put("blob", sealer.seal(json.writeValueAsString(txnMap(t)))); put("k", t.key) }

    fun add(t: Txn): Long = writableDatabase.insert("txns", null, values(t))

    /** Insert, or replace the row with the same id or key. Returns the row id. */
    fun put(t: Txn): Long {
        val existing = if (t.id > 0) t.id else t.key?.let { byRef(it)?.id }
        if (existing == null) return add(t)
        writableDatabase.update("txns", values(t), "id = ?", arrayOf(existing.toString()))
        return existing
    }

    fun delete(id: Long) = writableDatabase.delete("txns", "id = ?", arrayOf(id.toString()))

    /** Puts a row back exactly as it was, same id (undo). */
    fun restore(t: Txn) {
        writableDatabase.insertWithOnConflict("txns", null, values(t).apply { put("id", t.id) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun byRef(ref: String): Txn? = if (ref.startsWith("local:")) ref.removePrefix("local:").toLongOrNull()?.let { one("id = ?", it.toString()) } else one("k = ?", ref)

    private fun one(where: String, arg: String): Txn? = readableDatabase.rawQuery("SELECT id, blob, k FROM txns WHERE $where", arrayOf(arg)).use { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: android.database.Cursor): Txn? {
        val m = sealer.open(c.getString(1))?.let { runCatching { json.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.getOrNull() } ?: return null
        return Txn(
            c.getLong(0), (m["at"] as Number).toLong(), (m["cents"] as Number).toLong(), TxnKind.valueOf(m["kind"] as String), m["merchant"] as String,
            Category.of(m["category"] as String?), m["bank"] as String?, m["last4"] as String?, Source.valueOf(m["source"] as String), m["note"] as String?,
            c.getString(2), (m["currency"] as String?) ?: "USD", m["merged"] as String?, m["edited"] == true,
            (m["via"] as String?)?.let { runCatching { Via.valueOf(it) }.getOrNull() }, m["pending"] == true,
        )
    }

    fun txns(since: Long = 0): List<Txn> = readableDatabase.rawQuery("SELECT id, blob, k FROM txns WHERE at >= ? ORDER BY at", arrayOf(since.toString())).use { c ->
        buildList { while (c.moveToNext()) row(c)?.let { add(it) } }
    }

    /** Raw rows, for the test that no card number is stored. */
    internal fun raw(): List<String> = readableDatabase.rawQuery("SELECT blob FROM txns", null).use { c -> buildList { while (c.moveToNext()) add(sealer.open(c.getString(0)).orEmpty()) } }

    // ---- the change log ----

    fun logChange(at: Long, who: String, summary: String, undo: Map<String, Any?>?): Long =
        writableDatabase.insert("changes", null, ContentValues().apply {
            put("at", at); put("blob", sealer.seal(json.writeValueAsString(mapOf("who" to who, "summary" to summary, "undo" to undo))))
        })

    fun changes(limit: Int = 100): List<MonetaChange> = readableDatabase.rawQuery("SELECT id, at, blob, undone FROM changes ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList {
            while (c.moveToNext()) {
                val m = sealer.open(c.getString(2))?.let { runCatching { json.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.getOrNull() } ?: continue
                add(MonetaChange(c.getLong(0), c.getLong(1), m["who"].toString(), m["summary"].toString(), m["undo"] != null, c.getInt(3) != 0))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun undoOf(id: Long): Map<String, Any?>? = readableDatabase.rawQuery("SELECT blob, undone FROM changes WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst() || c.getInt(1) != 0) null
        else sealer.open(c.getString(0))?.let { runCatching { json.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.getOrNull() }?.get("undo") as? Map<String, Any?>
    }

    fun markUndone(id: Long) { writableDatabase.update("changes", ContentValues().apply { put("undone", 1) }, "id = ?", arrayOf(id.toString())) }

    // ---- small sealed values ----

    private fun put(k: String, v: Any) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("blob", sealer.seal(json.writeValueAsString(v))) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun <T> get(k: String, type: TypeReference<T>): T? = readableDatabase.rawQuery("SELECT blob FROM kv WHERE k = ?", arrayOf(k)).use { c ->
        if (!c.moveToFirst()) null else sealer.open(c.getString(0))?.let { runCatching { json.readValue(it, type) }.getOrNull() }
    }

    fun budgets(): Map<Category, Long> = get("budgets", object : TypeReference<Map<String, Long>>() {}).orEmpty().mapKeys { Category.of(it.key) }
    fun saveBudgets(b: Map<Category, Long>) = put("budgets", b.mapKeys { it.key.name })

    fun dismissed(): Set<String> = get("dismissed", object : TypeReference<Set<String>>() {}).orEmpty()
    fun dismiss(key: String) = put("dismissed", (dismissed() + key).toList().takeLast(500))

    /** Merchants he said were his ("Yes, that's me"), normalised. */
    fun known(): Set<String> = get("known", object : TypeReference<Set<String>>() {}).orEmpty()
    fun addKnown(merchant: String) = put("known", (known() + Reconcile.norm(merchant)).toList().takeLast(500))

    /** Rows he unmerged: never folded again automatically. */
    fun apart(): Set<String> = get("apart", object : TypeReference<Set<String>>() {}).orEmpty()
    fun keepApart(ref: String) = put("apart", (apart() + ref).toList().takeLast(500))

    /** Flags he answered "No" to, waiting for him to open the bank or tell Iris. */
    fun disowned(): List<String> = get("disowned", object : TypeReference<List<String>>() {}).orEmpty()
    fun saveDisowned(keys: List<String>) = put("disowned", keys.takeLast(20))

    /** Flags already rung, so one charge asks once. */
    fun asked(): Set<String> = get("asked", object : TypeReference<Set<String>>() {}).orEmpty()
    fun addAsked(key: String) = put("asked", (asked() + key).toList().takeLast(500))

    fun manualSubs(): List<Subscription> = get("subs", object : TypeReference<List<Map<String, Any?>>>() {}).orEmpty().map { subOf(it, SubSource.MANUAL) }
    fun saveManualSubs(subs: List<Subscription>) = put("subs", subs.map(::subMap))

    /** Iris's subscriptions table, as of the last ledger sync. */
    fun irisSubs(): List<Subscription> = get("iris.subs", object : TypeReference<List<Map<String, Any?>>>() {}).orEmpty().map { subOf(it, SubSource.IRIS) }
    fun saveIrisSubs(subs: List<Subscription>) = put("iris.subs", subs.map(::subMap))

    /** Charts Iris pinned, as their validated spec JSON. */
    fun charts(): List<String> = get("charts", object : TypeReference<List<String>>() {}).orEmpty()
    fun saveCharts(specs: List<String>) = put("charts", specs)

    fun long(k: String): Long = get(k, object : TypeReference<Long>() {}) ?: 0L
    fun setLong(k: String, v: Long) = put(k, v)

    fun appliedOps(): Set<String> = get("ops.applied", object : TypeReference<Set<String>>() {}).orEmpty()
    fun addApplied(ids: Collection<String>) = put("ops.applied", (appliedOps().toList() + ids).takeLast(2000))

    private fun subMap(s: Subscription) = mapOf("name" to s.name, "cents" to s.cents, "cycle" to s.cycle.name, "next" to s.next?.toString(), "bank" to s.bank, "source" to s.source.name)
    internal fun subOf(m: Map<String, Any?>, src: SubSource) = Subscription(
        m["name"].toString(), (m["cents"] as? Number)?.toLong(), runCatching { Cycle.valueOf(m["cycle"].toString()) }.getOrDefault(Cycle.MONTHLY),
        (m["next"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, (m["source"] as? String)?.let { runCatching { SubSource.valueOf(it) }.getOrNull() } ?: src, m["bank"] as String?,
    )
    internal fun subMapOf(s: Subscription) = subMap(s)
}
