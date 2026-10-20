package dev.phonerecall.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable

/** GTD lists. Projects are separate: any open task can belong to one. */
enum class TaskList(val label: String) {
    INBOX("Inbox"), NEXT("Next"), WAITING("Waiting for"), SCHEDULED("Scheduled"), SOMEDAY("Someday"), DONE("Done"),
}

/** GTD contexts: where, or with what, a next action can be done. */
enum class TaskContext(val label: String) {
    OFFICE("@office"), HOME("@home"), ERRANDS("@errands"), CALLS("@calls"), COMPUTER("@computer"), ANYWHERE("@anywhere");

    companion object {
        /** "office", "@office" or "OFFICE". */
        fun of(s: String?): TaskContext? = s?.trim()?.removePrefix("@")?.uppercase()?.let { w -> entries.firstOrNull { it.name == w } }
    }
}

@Immutable
data class Task(
    val id: Long,
    val title: String,
    val list: TaskList,
    val projectId: Long?,
    /** Epoch ms. With no time, it is the start of the day. */
    val due: Long?,
    val dueHasTime: Boolean,
    val sort: Double,
    /** The two-minute rule: do it now. */
    val quick: Boolean,
    val doneAt: Long?,
    val sample: Boolean,
    val context: TaskContext? = null,
    /** Waiting for: who (a people label or a name). */
    val waitingOn: String? = null,
    /** Waiting for: the day to check back, epoch ms at the start of that day. */
    val followUp: Long? = null,
)

@Immutable data class Project(val id: Long, val name: String, val sample: Boolean)

@Immutable
data class TaskSnapshot(
    val tasks: List<Task> = emptyList(), val projects: List<Project> = emptyList(), val loaded: Boolean = false,
    /** When he last finished the weekly review, epoch ms. */
    val lastReview: Long? = null,
) {
    fun open() = tasks.filter { it.list != TaskList.DONE }
    fun project(id: Long?) = projects.firstOrNull { it.id == id }
}

/** One seed for sample mode. */
data class TaskSeed(
    val title: String, val list: TaskList, val due: Long? = null, val dueHasTime: Boolean = false,
    val quick: Boolean = false, val project: String? = null,
    val context: TaskContext? = null, val waitingOn: String? = null, val followUp: Long? = null,
)

/** The task store: local SQLite, on the phone only. Every method does IO; call on Dispatchers.IO. */
class TaskStore(context: Context) : SQLiteOpenHelper(context, "tasks.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, list TEXT NOT NULL, " +
                "project_id INTEGER, due INTEGER, due_has_time INTEGER NOT NULL DEFAULT 0, sort REAL NOT NULL DEFAULT 0, " +
                "quick INTEGER NOT NULL DEFAULT 0, prev_list TEXT, done_at INTEGER, sample INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sample INTEGER NOT NULL DEFAULT 0)")
        onUpgrade(db, 1, 2)
    }

    /** v2: GTD contexts, Waiting for (who, and the day to check back), and a small meta table (the weekly review). */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN context TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN waiting_on TEXT")
            db.execSQL("ALTER TABLE tasks ADD COLUMN follow_up INTEGER")
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        }
    }

    fun load(): TaskSnapshot {
        val tasks = ArrayList<Task>()
        readableDatabase.rawQuery(
            "SELECT id, title, list, project_id, due, due_has_time, sort, quick, done_at, sample, context, waiting_on, follow_up FROM tasks ORDER BY sort ASC, id ASC", null,
        ).use { c -> while (c.moveToNext()) tasks += c.task() }
        val projects = ArrayList<Project>()
        readableDatabase.rawQuery("SELECT id, name, sample FROM projects ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) projects += Project(c.getLong(0), c.getString(1), c.getInt(2) == 1)
        }
        return TaskSnapshot(tasks, projects, loaded = true, lastReview = meta(LAST_REVIEW)?.toLongOrNull())
    }

    private fun Cursor.task() = Task(
        id = getLong(0), title = getString(1),
        list = runCatching { TaskList.valueOf(getString(2)) }.getOrDefault(TaskList.INBOX),
        projectId = if (isNull(3)) null else getLong(3),
        due = if (isNull(4)) null else getLong(4),
        dueHasTime = getInt(5) == 1, sort = getDouble(6), quick = getInt(7) == 1,
        doneAt = if (isNull(8)) null else getLong(8), sample = getInt(9) == 1,
        context = TaskContext.of(getString(10)), waitingOn = getString(11), followUp = if (isNull(12)) null else getLong(12),
    )

    private fun ContentValues.gtd(context: TaskContext?, waitingOn: String?, followUp: Long?) {
        if (context != null) put("context", context.name) else putNull("context")
        val who = waitingOn?.trim()?.takeIf { it.isNotEmpty() }
        if (who != null) put("waiting_on", who) else putNull("waiting_on")
        if (followUp != null) put("follow_up", followUp) else putNull("follow_up")
    }

    private fun topSort(db: SQLiteDatabase, list: TaskList): Double =
        db.rawQuery("SELECT MIN(sort) FROM tasks WHERE list = ?", arrayOf(list.name)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) - 1.0 else 0.0
        }

    /** New tasks go to the top of their list. */
    fun add(
        title: String, list: TaskList = TaskList.INBOX, sample: Boolean = false, due: Long? = null, dueHasTime: Boolean = false, quick: Boolean = false, projectId: Long? = null,
        context: TaskContext? = null, waitingOn: String? = null, followUp: Long? = null,
    ): Long {
        val db = writableDatabase
        return db.insert("tasks", null, ContentValues().apply {
            put("title", title.trim()); put("list", list.name); put("sort", topSort(db, list))
            put("sample", if (sample) 1 else 0); put("quick", if (quick) 1 else 0); put("due_has_time", if (dueHasTime) 1 else 0)
            if (due != null) put("due", due) else putNull("due")
            if (projectId != null) put("project_id", projectId) else putNull("project_id")
            gtd(context, waitingOn, followUp)
        })
    }

    fun update(t: Task) {
        val db = writableDatabase
        val old = db.rawQuery("SELECT list FROM tasks WHERE id = ?", arrayOf(t.id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        db.update("tasks", ContentValues().apply {
            put("title", t.title.trim()); put("list", t.list.name); put("quick", if (t.quick) 1 else 0)
            put("due_has_time", if (t.dueHasTime) 1 else 0)
            if (t.due != null) put("due", t.due) else putNull("due")
            if (t.projectId != null) put("project_id", t.projectId) else putNull("project_id")
            gtd(t.context, t.waitingOn, t.followUp)
            // Moving to another list puts the task on top of it.
            if (old != null && old != t.list.name) put("sort", topSort(db, t.list))
        }, "id = ?", arrayOf(t.id.toString()))
    }

    fun complete(id: Long, now: Long) {
        writableDatabase.execSQL(
            "UPDATE tasks SET prev_list = list, list = 'DONE', done_at = ?, quick = 0 WHERE id = ? AND list != 'DONE'",
            arrayOf<Any>(now, id),
        )
    }

    fun uncomplete(id: Long) {
        writableDatabase.execSQL(
            "UPDATE tasks SET list = COALESCE(prev_list, 'INBOX'), prev_list = NULL, done_at = NULL WHERE id = ?",
            arrayOf<Any>(id),
        )
    }

    fun delete(id: Long) { writableDatabase.delete("tasks", "id = ?", arrayOf(id.toString())) }

    /** Saves a list's order after a drag. */
    fun setOrder(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id -> db.execSQL("UPDATE tasks SET sort = ? WHERE id = ?", arrayOf<Any>(i.toDouble(), id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addProject(name: String, sample: Boolean = false): Long =
        writableDatabase.insert("projects", null, ContentValues().apply { put("name", name.trim()); put("sample", if (sample) 1 else 0) })

    /** Undo of a project made while clarifying, only when nothing is linked to it any more. */
    fun deleteProjectIfEmpty(id: Long) {
        writableDatabase.execSQL("DELETE FROM projects WHERE id = ? AND NOT EXISTS (SELECT 1 FROM tasks WHERE project_id = ?)", arrayOf<Any>(id, id))
    }

    fun hasSamples(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE sample = 1 LIMIT 1", null).use { it.moveToFirst() }

    fun seed(seeds: List<TaskSeed>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val projects = HashMap<String, Long>()
            for (s in seeds.asReversed()) {
                val pid = s.project?.let { name -> projects.getOrPut(name) { addProject(name, sample = true) } }
                add(s.title, s.list, sample = true, due = s.due, dueHasTime = s.dueHasTime, quick = s.quick, projectId = pid, context = s.context, waitingOn = s.waitingOn, followUp = s.followUp)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun removeSamples() {
        writableDatabase.delete("tasks", "sample = 1", null)
        writableDatabase.delete("projects", "sample = 1", null)
    }

    fun meta(k: String): String? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(k: String, v: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markReviewed(now: Long) = setMeta(LAST_REVIEW, "$now")

    companion object {
        const val LAST_REVIEW = "review.last"
        /** The review cycle Eos already mentioned (that Sunday's date). */
        const val REVIEW_MENTIONED = "review.mentioned"
    }
}
