package dev.phonerecall.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.MessageParam
import com.fasterxml.jackson.core.type.TypeReference

/** One line in his transcript with Athena (one continuous history across chats and sessions). A PROPOSAL line points at a proposal and draws its Fates card. */
/** MEMORY: a /memory answer card (Oneiroi), its text the encoded answer. */
/** DIVIDER: a thin rule where one of her daily sessions began, its text the day. */
enum class LineKind { USER, ATHENA, NOTE, PROPOSAL, ERROR, MEMORY, DIVIDER }

@Immutable
data class ChatLine(val id: Long, val kind: LineKind, val text: String, val proposalId: Long? = null, val at: Long = 0)

@Immutable data class AuditRow(val at: Long, val actor: String, val action: String, val detail: String)

/** A card Athena put on Olympus (after he confirmed create_card). */
@Immutable data class BrainCard(val id: Long, val title: String, val body: String, val createdAt: Long, val expiresAt: Long)

/** A reminder Zeus will post at `at`. */
data class Reminder(val id: Long, val at: Long, val text: String)

/**
 * brain.db: proposals, the audit log, his transcripts with Athena and the things confirmed proposals made
 * (reminders, cards). Local only, excluded from Android's backup (Mnemosyne carries it, opening and resealing the
 * transcript under "zeus.aegis.chat"). Transcript text and the old API history are sealed with a Keystore key: a
 * copied database shows no words. Every method does IO; call on Dispatchers.IO.
 */
class BrainDb(context: Context, private val sealer: Sealer = KeystoreSealer(CHAT_ALIAS)) : SQLiteOpenHelper(context, "brain.db", null, 3), ProposalStore, FatesMeter {
    private val json = jsonMapper()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE proposals (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, " +
                "status TEXT NOT NULL, title TEXT NOT NULL, steps TEXT NOT NULL, conversation_id INTEGER, proposer TEXT NOT NULL, " +
                "auto INTEGER NOT NULL DEFAULT 0, outcome TEXT, decided_at INTEGER)",
        )
        db.execSQL("CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL)")
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL)")
        // The exact API history, append-only: one MessageParam per row, as the SDK serializes it.
        db.execSQL("CREATE TABLE chat_params (chat_id INTEGER NOT NULL, seq INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY (chat_id, seq))")
        db.execSQL(
            "CREATE TABLE chat_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "text TEXT NOT NULL, proposal_id INTEGER, delivered INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, text TEXT NOT NULL, fired INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE cards (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        v2(db)
        v3(db)
    }

    /** Version 2: the fatigue meter's events and the outbound log (host, purpose, field kinds; never content). */
    private fun v2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fates_events (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, signal TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS fates_events_at ON fates_events (at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbound (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, host TEXT NOT NULL, purpose TEXT NOT NULL, fields TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS outbound_at ON outbound (at)")
    }

    /** Version 3: transcripts sealed. A `sealed` flag per row; the rows written before are sealed once, here. */
    private fun v3(db: SQLiteDatabase) {
        for (t in listOf("chat_lines", "chat_params")) {
            val has = db.rawQuery("PRAGMA table_info($t)", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == "sealed" } }
            if (!has) db.execSQL("ALTER TABLE $t ADD COLUMN sealed INTEGER NOT NULL DEFAULT 0")
        }
        sealPlain(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) v2(db); if (oldVersion < 3) v3(db) }

    /** Seals every transcript row still in plain text (the one-time migration; safe to run again). Returns rows sealed. */
    fun sealPlain(db: SQLiteDatabase = writableDatabase): Int {
        var n = 0
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id, text FROM chat_lines WHERE sealed = 0", null).use { c ->
                while (c.moveToNext()) { db.execSQL("UPDATE chat_lines SET text = ?, sealed = 1 WHERE id = ?", arrayOf<Any>(sealer.seal(c.getString(1)), c.getLong(0))); n++ }
            }
            db.rawQuery("SELECT chat_id, seq, json FROM chat_params WHERE sealed = 0", null).use { c ->
                while (c.moveToNext()) { db.execSQL("UPDATE chat_params SET json = ?, sealed = 1 WHERE chat_id = ? AND seq = ?", arrayOf<Any>(sealer.seal(c.getString(2)), c.getLong(0), c.getLong(1))); n++ }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    /** A transcript value as stored: opened when sealed (a row the key can't open reads as a marker, never as ciphertext). */
    private fun opened(v: String, sealed: Boolean): String = if (!sealed) v else sealer.open(v) ?: UNREADABLE

    // ---- the fatigue meter ----

    override fun record(e: KindEvent) {
        writableDatabase.insert("fates_events", null, ContentValues().apply { put("kind", e.kind.wire); put("signal", e.signal.name); put("at", e.at) })
    }

    override fun events(since: Long): List<KindEvent> =
        readableDatabase.rawQuery("SELECT kind, signal, at FROM fates_events WHERE at >= ? ORDER BY at", arrayOf(since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val k = ProposalKind.of(c.getString(0)) ?: continue
                    val s = runCatching { Signal.valueOf(c.getString(1)) }.getOrNull() ?: continue
                    add(KindEvent(k, s, c.getLong(2)))
                }
            }
        }

    // ---- what left the phone ----

    /** Adds to the last matching row within [Outbound.MERGE_MS], else a new row. Drops rows past a week. */
    @Synchronized fun logOutbound(r: OutboundRow) {
        val fields = r.fields.map { it.name }.sorted().joinToString(",")
        val w = writableDatabase
        val merged = w.compileStatement("UPDATE outbound SET count = count + 1, at = ? WHERE id = (SELECT id FROM outbound WHERE host = ? AND purpose = ? AND fields = ? AND at >= ? ORDER BY id DESC LIMIT 1)").use { st ->
            st.bindLong(1, r.at); st.bindString(2, r.host); st.bindString(3, r.purpose); st.bindString(4, fields); st.bindLong(5, r.at - Outbound.MERGE_MS)
            st.executeUpdateDelete()
        }
        if (merged == 0) {
            w.insert("outbound", null, ContentValues().apply { put("at", r.at); put("host", r.host); put("purpose", r.purpose); put("fields", fields) })
            w.delete("outbound", "at < ?", arrayOf((r.at - Outbound.KEEP_MS).toString()))
        }
    }

    fun outbound(since: Long): List<OutboundRow> =
        readableDatabase.rawQuery("SELECT at, host, purpose, fields, count FROM outbound WHERE at >= ? ORDER BY at DESC LIMIT 500", arrayOf(since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val f = c.getString(3).split(',').mapNotNull { n -> runCatching { FieldCat.valueOf(n) }.getOrNull() }.toSet()
                    add(OutboundRow(c.getLong(0), c.getString(1), c.getString(2), f, c.getInt(4)))
                }
            }
        }

    // ---- proposals ----

    private fun stepsJson(steps: List<Step>): String = json.writeValueAsString(steps.map { s ->
        linkedMapOf<String, Any?>("kind" to s.kind.wire, "payload" to s.payload, "summary" to s.summary, "selected" to s.selected, "needs" to s.needs, "result" to s.result, "undo" to s.undo)
    })

    @Suppress("UNCHECKED_CAST")
    private fun steps(text: String): List<Step> = json.readValue(text, object : TypeReference<List<Map<String, Any?>>>() {}).mapNotNull { m ->
        val kind = ProposalKind.of(m["kind"] as? String ?: "") ?: return@mapNotNull null
        Step(
            kind, (m["payload"] as? Map<String, String>).orEmpty(), m["summary"] as? String ?: "", m["selected"] as? Boolean ?: true,
            m["needs"] as? String, m["result"] as? String, m["undo"] as? Map<String, String>,
        )
    }

    private fun values(p: Proposal) = ContentValues().apply {
        put("created_at", p.createdAt); put("expires_at", p.expiresAt); put("status", p.status.name); put("title", p.title)
        put("steps", stepsJson(p.steps)); put("proposer", p.proposer); put("auto", if (p.auto) 1 else 0)
        if (p.conversationId != null) put("conversation_id", p.conversationId) else putNull("conversation_id")
        if (p.outcome != null) put("outcome", p.outcome) else putNull("outcome")
        if (p.decidedAt != null) put("decided_at", p.decidedAt) else putNull("decided_at")
    }

    override fun insert(p: Proposal): Long = writableDatabase.insert("proposals", null, values(p))

    override fun save(p: Proposal) { writableDatabase.update("proposals", values(p), "id = ?", arrayOf(p.id.toString())) }

    override fun casStatus(id: Long, from: ProposalStatus, to: ProposalStatus): Boolean =
        writableDatabase.compileStatement("UPDATE proposals SET status = ? WHERE id = ? AND status = ?").use {
            it.bindString(1, to.name); it.bindLong(2, id); it.bindString(3, from.name)
            it.executeUpdateDelete() == 1
        }

    private val cols = "id, created_at, expires_at, status, title, steps, conversation_id, proposer, auto, outcome, decided_at"

    private fun query(where: String?, args: Array<String>?, limit: Int = 200): List<Proposal> {
        val out = ArrayList<Proposal>()
        readableDatabase.rawQuery("SELECT $cols FROM proposals ${where?.let { "WHERE $it" } ?: ""} ORDER BY id DESC LIMIT $limit", args).use { c ->
            while (c.moveToNext()) out += Proposal(
                id = c.getLong(0), createdAt = c.getLong(1), expiresAt = c.getLong(2),
                status = runCatching { ProposalStatus.valueOf(c.getString(3)) }.getOrDefault(ProposalStatus.FAILED),
                title = c.getString(4), steps = steps(c.getString(5)),
                conversationId = if (c.isNull(6)) null else c.getLong(6), proposer = c.getString(7), auto = c.getInt(8) == 1,
                outcome = c.getString(9), decidedAt = if (c.isNull(10)) null else c.getLong(10),
            )
        }
        return out
    }

    override fun get(id: Long): Proposal? = query("id = ?", arrayOf(id.toString()), 1).firstOrNull()
    override fun all(): List<Proposal> = query(null, null)

    // ---- audit ----

    fun log(actor: String, action: String, detail: String) {
        writableDatabase.insert("audit", null, ContentValues().apply {
            put("at", System.currentTimeMillis()); put("actor", actor); put("action", action); put("detail", detail.take(400))
        })
        dev.phonerecall.clio.Clio.audit(actor, action, detail)
    }

    fun audit(limit: Int = 300): List<AuditRow> {
        val out = ArrayList<AuditRow>()
        readableDatabase.rawQuery("SELECT at, actor, action, detail FROM audit ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += AuditRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        }
        return out
    }

    // ---- chat ----

    fun newChat(): Long = writableDatabase.insert("chats", null, ContentValues().apply { put("started_at", System.currentTimeMillis()) })

    fun latestChat(): Long? = readableDatabase.rawQuery("SELECT MAX(id) FROM chats", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun appendParam(chat: Long, p: MessageParam) {
        val db = writableDatabase
        val seq = db.rawQuery("SELECT COALESCE(MAX(seq), -1) + 1 FROM chat_params WHERE chat_id = ?", arrayOf(chat.toString())).use { it.moveToFirst(); it.getLong(0) }
        db.insert("chat_params", null, ContentValues().apply { put("chat_id", chat); put("seq", seq); put("json", sealer.seal(json.writeValueAsString(p))); put("sealed", 1) })
    }

    fun params(chat: Long): MutableList<MessageParam> {
        val out = ArrayList<MessageParam>()
        readableDatabase.rawQuery("SELECT json, sealed FROM chat_params WHERE chat_id = ? ORDER BY seq", arrayOf(chat.toString())).use { c ->
            while (c.moveToNext()) runCatching { json.readValue(opened(c.getString(0), c.getInt(1) == 1), MessageParam::class.java) }.getOrNull()?.let { out += it }
        }
        return out
    }

    fun addLine(chat: Long, kind: LineKind, text: String, proposalId: Long? = null, delivered: Boolean = true): ChatLine {
        val at = System.currentTimeMillis()
        val id = writableDatabase.insert("chat_lines", null, ContentValues().apply {
            put("chat_id", chat); put("at", at); put("kind", kind.name); put("text", sealer.seal(text)); put("sealed", 1); put("delivered", if (delivered) 1 else 0)
            if (proposalId != null) put("proposal_id", proposalId) else putNull("proposal_id")
        })
        return ChatLine(id, kind, text, proposalId, at)
    }

    fun lines(chat: Long): List<ChatLine> {
        val out = ArrayList<ChatLine>()
        readableDatabase.rawQuery("SELECT id, kind, text, proposal_id, at, sealed FROM chat_lines WHERE chat_id = ? ORDER BY id", arrayOf(chat.toString())).use { c ->
            while (c.moveToNext()) out += ChatLine(
                c.getLong(0), runCatching { LineKind.valueOf(c.getString(1)) }.getOrDefault(LineKind.NOTE), opened(c.getString(2), c.getInt(5) == 1),
                if (c.isNull(3)) null else c.getLong(3), c.getLong(4),
            )
        }
        return out
    }

    /**
     * The one continuous history: every chat's lines, the newest [limit] before [beforeId] (null: the latest), oldest
     * first, each with its chat id so the screen can draw a thin divider where a chat began.
     */
    fun history(limit: Int, beforeId: Long? = null): List<Pair<Long, ChatLine>> {
        val out = ArrayList<Pair<Long, ChatLine>>()
        readableDatabase.rawQuery(
            "SELECT chat_id, id, kind, text, proposal_id, at, sealed FROM chat_lines WHERE id < ? ORDER BY id DESC LIMIT ?",
            arrayOf((beforeId ?: Long.MAX_VALUE).toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to ChatLine(
                c.getLong(1), runCatching { LineKind.valueOf(c.getString(2)) }.getOrDefault(LineKind.NOTE), opened(c.getString(3), c.getInt(6) == 1),
                if (c.isNull(4)) null else c.getLong(4), c.getLong(5),
            )
        }
        return out.asReversed()
    }

    /** Every chat's lines since `since`, with their chat id (Oneiroi's subconscious sweep). */
    fun linesSince(since: Long): List<Pair<Long, ChatLine>> {
        val out = ArrayList<Pair<Long, ChatLine>>()
        readableDatabase.rawQuery("SELECT chat_id, id, kind, text, proposal_id, at, sealed FROM chat_lines WHERE at >= ? ORDER BY id", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to ChatLine(
                c.getLong(1), runCatching { LineKind.valueOf(c.getString(2)) }.getOrDefault(LineKind.NOTE), opened(c.getString(3), c.getInt(6) == 1),
                if (c.isNull(4)) null else c.getLong(4), c.getLong(5),
            )
        }
        return out
    }

    /** Outcome notes not yet told to Athena; marks them told. */
    fun takeNotes(chat: Long): List<String> {
        val db = writableDatabase
        val out = ArrayList<String>()
        db.rawQuery("SELECT text, sealed FROM chat_lines WHERE chat_id = ? AND kind = 'NOTE' AND delivered = 0 ORDER BY id", arrayOf(chat.toString())).use { c ->
            while (c.moveToNext()) out += opened(c.getString(0), c.getInt(1) == 1)
        }
        db.execSQL("UPDATE chat_lines SET delivered = 1 WHERE chat_id = ? AND kind = 'NOTE'", arrayOf<Any>(chat))
        return out
    }

    // ---- what confirmed proposals made ----

    fun addReminder(at: Long, text: String): Long = writableDatabase.insert("reminders", null, ContentValues().apply { put("at", at); put("text", text) })
    fun reminder(id: Long): Reminder? = readableDatabase.rawQuery("SELECT id, at, text FROM reminders WHERE id = ?", arrayOf(id.toString())).use {
        if (it.moveToFirst()) Reminder(it.getLong(0), it.getLong(1), it.getString(2)) else null
    }
    fun upcomingReminders(now: Long): List<Reminder> {
        val out = ArrayList<Reminder>()
        readableDatabase.rawQuery("SELECT id, at, text FROM reminders WHERE fired = 0 AND at > ?", arrayOf((now - 3_600_000L).toString())).use { c ->
            while (c.moveToNext()) out += Reminder(c.getLong(0), c.getLong(1), c.getString(2))
        }
        return out
    }
    fun markFired(id: Long) { writableDatabase.execSQL("UPDATE reminders SET fired = 1 WHERE id = ?", arrayOf<Any>(id)) }
    fun deleteReminder(id: Long) { writableDatabase.delete("reminders", "id = ?", arrayOf(id.toString())) }

    fun addCard(title: String, body: String, now: Long, until: Long): Long = writableDatabase.insert("cards", null, ContentValues().apply {
        put("title", title); put("body", body); put("created_at", now); put("expires_at", until)
    })
    fun deleteCard(id: Long) { writableDatabase.delete("cards", "id = ?", arrayOf(id.toString())) }
    fun cards(now: Long): List<BrainCard> {
        val out = ArrayList<BrainCard>()
        readableDatabase.rawQuery("SELECT id, title, body, created_at, expires_at FROM cards WHERE expires_at > ? ORDER BY id DESC", arrayOf(now.toString())).use { c ->
            while (c.moveToNext()) out += BrainCard(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
        }
        return out
    }

    companion object {
        /** The transcript's own Keystore key (Mnemosyne opens every "zeus.aegis.*" key for a backup). */
        const val CHAT_ALIAS = "zeus.aegis.chat"
        const val UNREADABLE = "[this line can't be opened on this phone]"
    }
}
