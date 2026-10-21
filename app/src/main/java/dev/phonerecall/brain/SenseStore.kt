package dev.phonerecall.brain

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.core.type.TypeReference

/** A notification as stored. `raw` and `summary` are plain here; on disk they are sealed. */
data class NoteRow(
    val id: Long = 0,
    val at: Long,
    val notifKey: String,
    val pkg: String,
    val app: String,
    val kind: NoteKind,
    val person: String?,
    val thread: String?,
    val raw: String?,
    val summary: String,
    val importance: Importance,
    val needsModel: Boolean = false,
    val looksImportant: Boolean = false,
    /** "rules", "nano" or "haiku". */
    val by: String = "rules",
    /** The only form that may go to Haiku (Derive.cloudLine). */
    val cloudLine: String? = null,
    val sent: Boolean = false,
)

/** A card the senses put on Olympus, keyed so a second call updates "Amma called twice" in place. */
@Immutable data class SenseCard(val key: String, val title: String, val body: String, val source: String, val at: Long, val until: Long)

enum class CaptureKind { TEXT, VOICE, PHOTO, LINK }
enum class CaptureStatus { SORTING, SORTED, UNSORTED }

/** One thing kept in Parnassus and where it went. */
@Immutable
data class Capture(
    val id: Long = 0,
    val at: Long,
    val kind: CaptureKind,
    val text: String,
    val photo: String? = null,
    val status: CaptureStatus = CaptureStatus.SORTING,
    /** Where it went, as he reads it: "Praxis", "Groceries", "Iris". */
    val place: String? = null,
    val outcome: String? = null,
    /** What Undo needs: {"task_id": "4"} or {"item_ids": "3,4"} or {"job_id": "2"}. */
    val undo: Map<String, String>? = null,
    val by: String? = null,
)

@Immutable data class WorkerJob(val id: Long, val at: Long, val owner: WorkerOwner, val job: String, val captureId: Long?, val status: String)

@Immutable data class LetheRow(val id: Long, val at: Long, val refType: String, val refId: Long, val title: String, val reason: String, val restored: Boolean)

data class TaskRelevance(val taskId: Long, val until: Long, val kind: RelevanceKind, val reason: String?, val pinned: Boolean, val lastChance: Boolean)

/**
 * senses.db: phase 2b's local state. Notification text, summaries and people's names are sealed with
 * the Aegis key; raw text is cleared 48 hours after it arrived. Excluded from backup like everything
 * else. Every method does IO; call off the main thread.
 */
class SenseStore(context: Context, private val sealer: Sealer, name: String? = "senses.db") :
    SQLiteOpenHelper(context, name, null, 2), SectionPersistence {

    private val json = jsonMapper()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, notif_key TEXT NOT NULL, pkg TEXT NOT NULL, app TEXT NOT NULL, " +
                "kind TEXT NOT NULL, person TEXT, thread TEXT, raw TEXT, summary TEXT NOT NULL, importance TEXT NOT NULL, needs_model INTEGER NOT NULL, " +
                "looks_important INTEGER NOT NULL, by_whom TEXT NOT NULL, cloud TEXT, sent INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX notes_at ON notes(at)")
        db.execSQL("CREATE INDEX notes_key ON notes(notif_key)")
        db.execSQL(
            "CREATE TABLE people (key TEXT PRIMARY KEY, name TEXT NOT NULL, relation TEXT, term TEXT, confirmed INTEGER NOT NULL DEFAULT 0, asked INTEGER NOT NULL DEFAULT 0, " +
                "pinned TEXT, n INTEGER NOT NULL DEFAULT 0, engaged INTEGER NOT NULL DEFAULT 0, ewma REAL, last_at INTEGER NOT NULL DEFAULT 0, calypso INTEGER NOT NULL DEFAULT 0, work INTEGER NOT NULL DEFAULT 0, zone TEXT)",
        )
        db.execSQL("CREATE TABLE threads (thread TEXT PRIMARY KEY, person TEXT, first_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cards (key TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, source TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE wishes (id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT NOT NULL, phrase TEXT NOT NULL, name TEXT, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE birthdays (name_key TEXT NOT NULL, year INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY (name_key, year))")
        db.execSQL("CREATE TABLE birthday_negatives (id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT NOT NULL, name_key TEXT, phrase TEXT)")
        db.execSQL(
            "CREATE TABLE sections (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, place TEXT, created_by TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "hidden INTEGER NOT NULL DEFAULT 0, hidden_by TEXT, pinned INTEGER NOT NULL DEFAULT 0, renamed INTEGER NOT NULL DEFAULT 0, ord INTEGER NOT NULL, last_active INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE section_changes (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, op TEXT NOT NULL, before TEXT NOT NULL, after TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, section_id INTEGER NOT NULL, text TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', done INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, done_at INTEGER, until INTEGER, rkind TEXT, reason TEXT, pinned INTEGER NOT NULL DEFAULT 0, lethe INTEGER NOT NULL DEFAULT 0, last_chance INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE captures (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, photo TEXT, status TEXT NOT NULL, " +
                "place TEXT, outcome TEXT, undo TEXT, by_whom TEXT)",
        )
        db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, owner TEXT NOT NULL, job TEXT NOT NULL, capture_id INTEGER, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE task_relevance (task_id INTEGER PRIMARY KEY, until INTEGER NOT NULL, rkind TEXT NOT NULL, reason TEXT, pinned INTEGER NOT NULL DEFAULT 0, last_chance INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE lethe (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, ref_type TEXT NOT NULL, ref_id INTEGER NOT NULL, title TEXT NOT NULL, reason TEXT NOT NULL, restored INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE calypso_apps (pkg TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE people ADD COLUMN zone TEXT")
    }

    /** Every sealed column, rewritten once into the sealer's current format (envelopes). People first: the hot path. */
    fun reseal(): Int = writableDatabase.let { db ->
        db.reseal(sealer, "people", listOf("name")) + db.reseal(sealer, "cards", listOf("title", "body")) +
            db.reseal(sealer, "wishes", listOf("name")) + db.reseal(sealer, "notes", listOf("summary", "raw", "cloud"))
    }

    private fun sealOrNull(s: String?) = s?.let { sealer.seal(it) }
    private fun openOrNull(s: String?) = s?.let { sealer.open(it) }
    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)

    // ---- notes ----

    fun insertNote(n: NoteRow): Long = writableDatabase.insert("notes", null, ContentValues().apply {
        put("at", n.at); put("notif_key", n.notifKey); put("pkg", n.pkg); put("app", n.app); put("kind", n.kind.name)
        put("person", n.person); put("thread", n.thread); put("raw", sealOrNull(n.raw)); put("summary", sealer.seal(n.summary))
        put("importance", n.importance.name); put("needs_model", if (n.needsModel) 1 else 0); put("looks_important", if (n.looksImportant) 1 else 0)
        put("by_whom", n.by); put("cloud", sealOrNull(n.cloudLine)); put("sent", if (n.sent) 1 else 0)
    })

    private val noteCols = "id, at, notif_key, pkg, app, kind, person, thread, raw, summary, importance, needs_model, looks_important, by_whom, cloud, sent"

    private fun Cursor.note() = NoteRow(
        id = getLong(0), at = getLong(1), notifKey = getString(2), pkg = getString(3), app = getString(4),
        kind = runCatching { NoteKind.valueOf(getString(5)) }.getOrDefault(NoteKind.OTHER), person = str(6), thread = str(7),
        raw = openOrNull(str(8)), summary = sealer.open(getString(9)).orEmpty(),
        importance = runCatching { Importance.valueOf(getString(10)) }.getOrDefault(Importance.LOW),
        needsModel = getInt(11) == 1, looksImportant = getInt(12) == 1, by = getString(13), cloudLine = openOrNull(str(14)), sent = getInt(15) == 1,
    )

    fun note(id: Long): NoteRow? = readableDatabase.rawQuery("SELECT $noteCols FROM notes WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) it.note() else null }

    fun notes(limit: Int = 200): List<NoteRow> = readableDatabase.rawQuery("SELECT $noteCols FROM notes ORDER BY id DESC LIMIT $limit", null).use { c ->
        val out = ArrayList<NoteRow>(); while (c.moveToNext()) out += c.note(); out
    }

    /** The newest note posted under this notification key (to match a removal to its post). */
    fun noteByKey(key: String): NoteRow? =
        readableDatabase.rawQuery("SELECT $noteCols FROM notes WHERE notif_key = ? ORDER BY id DESC LIMIT 1", arrayOf(key)).use { if (it.moveToFirst()) it.note() else null }

    /** How many raw bodies are still on disk (for the 48 h rule and its test). */
    fun rawCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE raw IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) }

    fun countNotes(kind: NoteKind, person: String, since: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE kind = ? AND person = ? AND at >= ?", arrayOf(kind.name, person, "$since")).use { it.moveToFirst(); it.getInt(0) }

    /**
     * The retention rule: raw text lives 48 hours, then only the derived summary stays (30 days).
     * Old wishes go after two days, expired cards at once. Returns how many raw bodies were cleared.
     */
    fun purge(now: Long): Int {
        val db = writableDatabase
        val cleared = db.compileStatement("UPDATE notes SET raw = NULL WHERE raw IS NOT NULL AND at <= ?").use { it.bindLong(1, now - RAW_TTL_MS); it.executeUpdateDelete() }
        db.delete("notes", "at < ?", arrayOf("${now - SUMMARY_TTL_MS}"))
        db.delete("wishes", "at < ?", arrayOf("${now - 2 * 86_400_000L}"))
        db.delete("cards", "until < ?", arrayOf("$now"))
        db.delete("threads", "first_at < ?", arrayOf("${now - 2 * 86_400_000L}"))
        return cleared
    }

    /** Notes waiting for Haiku: undecided by rules, important-looking, not sent yet. Their cloud lines only. */
    fun pendingForCloud(limit: Int = TriageBatch.MAX_LINES): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT id, cloud FROM notes WHERE needs_model = 1 AND looks_important = 1 AND sent = 0 AND by_whom = 'rules' AND cloud IS NOT NULL ORDER BY id LIMIT $limit", null).use { c ->
            val out = ArrayList<Pair<Long, String>>(); while (c.moveToNext()) openOrNull(c.getString(1))?.let { out += c.getLong(0) to it }; out
        }

    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE needs_model = 1 AND looks_important = 1 AND sent = 0 AND by_whom = 'rules'", null).use { it.moveToFirst(); it.getInt(0) }

    fun setVerdict(id: Long, importance: Importance, by: String) {
        writableDatabase.execSQL("UPDATE notes SET importance = ?, by_whom = ?, sent = 1 WHERE id = ?", arrayOf<Any>(importance.name, by, id))
    }

    fun markSent(ids: List<Long>) { if (ids.isNotEmpty()) writableDatabase.execSQL("UPDATE notes SET sent = 1 WHERE id IN (${ids.joinToString(",")})") }

    // ---- people ----

    private val personCols = "key, name, relation, term, confirmed, asked, pinned, n, engaged, ewma, last_at, calypso, work, zone"

    private fun Cursor.person() = Person(
        key = getString(0), name = sealer.open(getString(1)).orEmpty(), relation = Relation.of(str(2)), term = str(3),
        confirmed = getInt(4) == 1, asked = getInt(5) == 1, pinned = Tier.of(str(6)),
        stats = ResponseStats(getInt(7), getInt(8), if (isNull(9)) null else getDouble(9), getLong(10)),
        calypso = getInt(11) == 1, work = getInt(12) == 1, zone = str(13),
    )

    fun person(key: String): Person? = readableDatabase.rawQuery("SELECT $personCols FROM people WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.person() else null }

    fun people(): List<Person> = readableDatabase.rawQuery("SELECT $personCols FROM people", null).use { c ->
        val out = ArrayList<Person>(); while (c.moveToNext()) out += c.person(); out
    }

    /** Only people with a relation set: a few rows to open, not the whole book (each name is a Keystore decrypt). */
    fun related(): List<Person> = readableDatabase.rawQuery("SELECT $personCols FROM people WHERE relation IS NOT NULL", null).use { c ->
        val out = ArrayList<Person>(); while (c.moveToNext()) out += c.person(); out
    }

    fun savePerson(p: Person) {
        writableDatabase.insertWithOnConflict("people", null, ContentValues().apply {
            put("key", p.key); put("name", sealer.seal(p.name)); put("relation", p.relation?.name); put("term", p.term)
            put("confirmed", if (p.confirmed) 1 else 0); put("asked", if (p.asked) 1 else 0); put("pinned", p.pinned?.name)
            put("n", p.stats.n); put("engaged", p.stats.engaged); put("ewma", p.stats.ewmaMinutes); put("last_at", p.stats.lastAt)
            put("calypso", if (p.calypso) 1 else 0); put("work", if (p.work) 1 else 0); put("zone", p.zone)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun calypsoPeople(): Set<String> = readableDatabase.rawQuery("SELECT key FROM people WHERE calypso = 1", null).use { c ->
        val out = HashSet<String>(); while (c.moveToNext()) out += c.getString(0); out
    }

    fun calypsoApps(): Set<String> = readableDatabase.rawQuery("SELECT pkg FROM calypso_apps", null).use { c ->
        val out = HashSet<String>(); while (c.moveToNext()) out += c.getString(0); out
    }

    fun setCalypsoApp(pkg: String, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("calypso_apps", null, ContentValues().apply { put("pkg", pkg) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("calypso_apps", "pkg = ?", arrayOf(pkg))
    }

    // ---- threads (response latency) ----

    /** The first unanswered notification of a thread; later ones don't reset the clock. */
    fun openThread(thread: String, person: String?, at: Long) {
        writableDatabase.insertWithOnConflict("threads", null, ContentValues().apply { put("thread", thread); put("person", person); put("first_at", at) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun closeThread(thread: String): Pair<String?, Long>? {
        val r = readableDatabase.rawQuery("SELECT person, first_at FROM threads WHERE thread = ?", arrayOf(thread)).use { if (it.moveToFirst()) it.str(0) to it.getLong(1) else null }
        if (r != null) writableDatabase.delete("threads", "thread = ?", arrayOf(thread))
        return r
    }

    // ---- cards ----

    fun upsertCard(c: SenseCard) {
        writableDatabase.insertWithOnConflict("cards", null, ContentValues().apply {
            put("key", c.key); put("title", sealer.seal(c.title)); put("body", sealer.seal(c.body)); put("source", c.source); put("at", c.at); put("until", c.until)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun cards(now: Long): List<SenseCard> = readableDatabase.rawQuery("SELECT key, title, body, source, at, until FROM cards WHERE until > ? ORDER BY at DESC", arrayOf("$now")).use { c ->
        val out = ArrayList<SenseCard>()
        while (c.moveToNext()) out += SenseCard(c.getString(0), sealer.open(c.getString(1)).orEmpty(), sealer.open(c.getString(2)).orEmpty(), c.getString(3), c.getLong(4), c.getLong(5))
        out
    }

    fun card(key: String): SenseCard? = cards(0).firstOrNull { it.key == key }

    fun dismissCard(key: String) { writableDatabase.delete("cards", "key = ?", arrayOf(key)) }

    // ---- birthdays ----

    fun addWish(thread: String, w: Wish, at: Long) {
        writableDatabase.insert("wishes", null, ContentValues().apply { put("thread", thread); put("phrase", w.phrase); put("name", sealOrNull(w.name)); put("at", at) })
    }

    fun wishes(thread: String, since: Long): List<Wish> =
        readableDatabase.rawQuery("SELECT phrase, name FROM wishes WHERE thread = ? AND at >= ?", arrayOf(thread, "$since")).use { c ->
            val out = ArrayList<Wish>(); while (c.moveToNext()) out += Wish(c.getString(0), openOrNull(c.str(1))); out
        }

    fun birthdayStatus(nameKey: String, year: Int): String? =
        readableDatabase.rawQuery("SELECT status FROM birthdays WHERE name_key = ? AND year = ?", arrayOf(nameKey, "$year")).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setBirthdayStatus(nameKey: String, year: Int, status: String) {
        writableDatabase.insertWithOnConflict("birthdays", null, ContentValues().apply { put("name_key", nameKey); put("year", year); put("status", status) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun negatives(): List<BirthdayNegative> = readableDatabase.rawQuery("SELECT thread, name_key, phrase FROM birthday_negatives", null).use { c ->
        val out = ArrayList<BirthdayNegative>(); while (c.moveToNext()) out += BirthdayNegative(c.getString(0), c.str(1), c.str(2)); out
    }

    fun addNegative(n: BirthdayNegative) {
        writableDatabase.insert("birthday_negatives", null, ContentValues().apply { put("thread", n.thread); put("name_key", n.nameKey); put("phrase", n.phrase) })
    }

    // ---- sections (SectionPersistence) ----

    private fun Cursor.section() = Section(
        id = getLong(0), name = getString(1), place = str(2), createdBy = Actor.valueOf(getString(3)), createdAt = getLong(4),
        hidden = getInt(5) == 1, hiddenBy = str(6)?.let { Actor.valueOf(it) }, pinned = getInt(7) == 1, renamedByHim = getInt(8) == 1,
        order = getInt(9), lastActiveAt = getLong(10),
    )

    override fun sections(): List<Section> =
        readableDatabase.rawQuery("SELECT id, name, place, created_by, created_at, hidden, hidden_by, pinned, renamed, ord, last_active FROM sections", null).use { c ->
            val out = ArrayList<Section>(); while (c.moveToNext()) out += c.section(); out
        }

    private fun sectionValues(s: Section) = ContentValues().apply {
        put("name", s.name); put("place", s.place); put("created_by", s.createdBy.name); put("created_at", s.createdAt)
        put("hidden", if (s.hidden) 1 else 0); put("hidden_by", s.hiddenBy?.name); put("pinned", if (s.pinned) 1 else 0)
        put("renamed", if (s.renamedByHim) 1 else 0); put("ord", s.order); put("last_active", s.lastActiveAt)
    }

    override fun insertSection(s: Section): Long = writableDatabase.insert("sections", null, sectionValues(s))
    override fun updateSection(s: Section) { writableDatabase.update("sections", sectionValues(s), "id = ?", arrayOf("${s.id}")) }
    override fun openItemCount(sectionId: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE section_id = ? AND done = 0 AND lethe = 0", arrayOf("$sectionId")).use { it.moveToFirst(); it.getInt(0) }

    private fun sectionsJson(l: List<Section>) = json.writeValueAsString(l.map { s ->
        linkedMapOf("id" to s.id, "name" to s.name, "place" to s.place, "createdBy" to s.createdBy.name, "createdAt" to s.createdAt, "hidden" to s.hidden,
            "hiddenBy" to s.hiddenBy?.name, "pinned" to s.pinned, "renamed" to s.renamedByHim, "order" to s.order, "lastActive" to s.lastActiveAt)
    })

    private fun sectionsOf(text: String): List<Section> = json.readValue(text, object : TypeReference<List<Map<String, Any?>>>() {}).map { m ->
        Section(
            id = (m["id"] as Number).toLong(), name = m["name"] as String, place = m["place"] as String?, createdBy = Actor.valueOf(m["createdBy"] as String),
            createdAt = (m["createdAt"] as Number).toLong(), hidden = m["hidden"] as Boolean, hiddenBy = (m["hiddenBy"] as String?)?.let { Actor.valueOf(it) },
            pinned = m["pinned"] as Boolean, renamedByHim = m["renamed"] as Boolean, order = (m["order"] as Number).toInt(), lastActiveAt = (m["lastActive"] as Number).toLong(),
        )
    }

    override fun logChange(c: SectionChange): Long = writableDatabase.insert("section_changes", null, ContentValues().apply {
        put("at", c.at); put("actor", c.actor.name); put("op", c.op); put("before", sectionsJson(c.before)); put("after", sectionsJson(c.after))
    })

    private fun Cursor.change() = SectionChange(getLong(0), getLong(1), Actor.valueOf(getString(2)), getString(3), sectionsOf(getString(4)), sectionsOf(getString(5)), getInt(6) == 1)

    override fun changes(limit: Int): List<SectionChange> =
        readableDatabase.rawQuery("SELECT id, at, actor, op, before, after, undone FROM section_changes ORDER BY id DESC LIMIT $limit", null).use { c ->
            val out = ArrayList<SectionChange>(); while (c.moveToNext()) out += c.change(); out
        }

    override fun change(id: Long): SectionChange? =
        readableDatabase.rawQuery("SELECT id, at, actor, op, before, after, undone FROM section_changes WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) it.change() else null }

    override fun markUndone(id: Long) { writableDatabase.execSQL("UPDATE section_changes SET undone = 1 WHERE id = ?", arrayOf<Any>(id)) }

    // ---- items ----

    private val itemCols = "id, section_id, text, note, done, created_at, done_at, until, rkind, reason, pinned, lethe, last_chance"

    private fun Cursor.item() = SectionItem(
        id = getLong(0), sectionId = getLong(1), text = getString(2), note = getString(3), done = getInt(4) == 1, createdAt = getLong(5),
        doneAt = long(6), until = long(7), relevance = str(8)?.let { r -> RelevanceKind.entries.firstOrNull { it.name == r } }, reason = str(9),
        pinned = getInt(10) == 1, lethe = getInt(11) == 1, lastChance = getInt(12) == 1,
    )

    private fun itemValues(i: SectionItem) = ContentValues().apply {
        put("section_id", i.sectionId); put("text", i.text); put("note", i.note); put("done", if (i.done) 1 else 0); put("created_at", i.createdAt)
        put("done_at", i.doneAt); put("until", i.until); put("rkind", i.relevance?.name); put("reason", i.reason); put("pinned", if (i.pinned) 1 else 0)
        put("lethe", if (i.lethe) 1 else 0); put("last_chance", if (i.lastChance) 1 else 0)
    }

    fun addItem(i: SectionItem): Long = writableDatabase.insert("items", null, itemValues(i))
    fun updateItem(i: SectionItem) { writableDatabase.update("items", itemValues(i), "id = ?", arrayOf("${i.id}")) }
    /** Only Undo of something he just added (his own capture or a confirmed expansion) removes an item. */
    fun removeItem(id: Long) { writableDatabase.delete("items", "id = ?", arrayOf("$id")) }
    fun item(id: Long): SectionItem? = readableDatabase.rawQuery("SELECT $itemCols FROM items WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) it.item() else null }

    fun items(sectionId: Long): List<SectionItem> =
        readableDatabase.rawQuery("SELECT $itemCols FROM items WHERE section_id = ? AND lethe = 0 ORDER BY done, id DESC", arrayOf("$sectionId")).use { c ->
            val out = ArrayList<SectionItem>(); while (c.moveToNext()) out += c.item(); out
        }

    fun itemsWithWindow(): List<SectionItem> =
        readableDatabase.rawQuery("SELECT $itemCols FROM items WHERE until IS NOT NULL AND done = 0 AND lethe = 0", null).use { c ->
            val out = ArrayList<SectionItem>(); while (c.moveToNext()) out += c.item(); out
        }

    // ---- captures ----

    private fun captureValues(c: Capture) = ContentValues().apply {
        put("at", c.at); put("kind", c.kind.name); put("text", c.text); put("photo", c.photo); put("status", c.status.name)
        put("place", c.place); put("outcome", c.outcome); put("undo", c.undo?.let { json.writeValueAsString(it) }); put("by_whom", c.by)
    }

    private fun Cursor.capture() = Capture(
        id = getLong(0), at = getLong(1), kind = CaptureKind.valueOf(getString(2)), text = getString(3), photo = str(4),
        status = runCatching { CaptureStatus.valueOf(getString(5)) }.getOrDefault(CaptureStatus.UNSORTED), place = str(6), outcome = str(7),
        undo = str(8)?.let { runCatching { json.readValue(it, object : TypeReference<Map<String, String>>() {}) }.getOrNull() }, by = str(9),
    )

    fun addCapture(c: Capture): Long = writableDatabase.insert("captures", null, captureValues(c))
    fun updateCapture(c: Capture) { writableDatabase.update("captures", captureValues(c), "id = ?", arrayOf("${c.id}")) }
    fun capture(id: Long): Capture? =
        readableDatabase.rawQuery("SELECT id, at, kind, text, photo, status, place, outcome, undo, by_whom FROM captures WHERE id = ?", arrayOf("$id")).use { if (it.moveToFirst()) it.capture() else null }

    fun captures(limit: Int = 100): List<Capture> =
        readableDatabase.rawQuery("SELECT id, at, kind, text, photo, status, place, outcome, undo, by_whom FROM captures ORDER BY id DESC LIMIT $limit", null).use { c ->
            val out = ArrayList<Capture>(); while (c.moveToNext()) out += c.capture(); out
        }

    fun unsorted(limit: Int = 10): List<Capture> = captures(200).filter { it.status == CaptureStatus.UNSORTED && it.kind != CaptureKind.PHOTO }.take(limit)

    // ---- worker jobs ----

    fun addJob(owner: WorkerOwner, job: String, captureId: Long?, at: Long): Long = writableDatabase.insert("jobs", null, ContentValues().apply {
        put("at", at); put("owner", owner.name); put("job", job); put("capture_id", captureId); put("status", JOB_WAITING)
    })

    fun jobs(): List<WorkerJob> = readableDatabase.rawQuery("SELECT id, at, owner, job, capture_id, status FROM jobs ORDER BY id DESC", null).use { c ->
        val out = ArrayList<WorkerJob>()
        while (c.moveToNext()) out += WorkerJob(c.getLong(0), c.getLong(1), (WorkerOwner.of(c.getString(2)) ?: WorkerOwner.CIRCLE), c.getString(3), c.long(4), c.getString(5))
        out
    }

    fun removeJob(id: Long) { writableDatabase.delete("jobs", "id = ?", arrayOf("$id")) }

    // ---- task relevance + Lethe ----

    fun setTaskRelevance(r: TaskRelevance) {
        writableDatabase.insertWithOnConflict("task_relevance", null, ContentValues().apply {
            put("task_id", r.taskId); put("until", r.until); put("rkind", r.kind.name); put("reason", r.reason)
            put("pinned", if (r.pinned) 1 else 0); put("last_chance", if (r.lastChance) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun taskRelevance(): List<TaskRelevance> = readableDatabase.rawQuery("SELECT task_id, until, rkind, reason, pinned, last_chance FROM task_relevance", null).use { c ->
        val out = ArrayList<TaskRelevance>()
        while (c.moveToNext()) out += TaskRelevance(c.getLong(0), c.getLong(1), RelevanceKind.valueOf(c.getString(2)), c.str(3), c.getInt(4) == 1, c.getInt(5) == 1)
        out
    }

    fun removeTaskRelevance(taskId: Long) { writableDatabase.delete("task_relevance", "task_id = ?", arrayOf("$taskId")) }

    fun addLethe(refType: String, refId: Long, title: String, reason: String, at: Long): Long = writableDatabase.insert("lethe", null, ContentValues().apply {
        put("at", at); put("ref_type", refType); put("ref_id", refId); put("title", title); put("reason", reason)
    })

    fun lethe(limit: Int = 200): List<LetheRow> = readableDatabase.rawQuery("SELECT id, at, ref_type, ref_id, title, reason, restored FROM lethe ORDER BY id DESC LIMIT $limit", null).use { c ->
        val out = ArrayList<LetheRow>()
        while (c.moveToNext()) out += LetheRow(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getInt(6) == 1)
        out
    }

    fun letheRow(id: Long): LetheRow? = lethe(1000).firstOrNull { it.id == id }
    fun markRestored(id: Long) { writableDatabase.execSQL("UPDATE lethe SET restored = 1 WHERE id = ?", arrayOf<Any>(id)) }

    // ---- meta ----

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val RAW_TTL_MS = 48 * 3_600_000L
        const val SUMMARY_TTL_MS = 30 * 86_400_000L
        const val JOB_WAITING = "WAITING_CHANNEL"
    }
}
