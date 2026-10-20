package dev.phonerecall.memory

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.CaptureKind
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.TaskList
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A document he picked (SAF), copied into app storage. */
data class PickedDoc(val id: String, val name: String, val file: File, val addedAt: Long, val pages: Int)

/**
 * Gathers what Alexandria indexes from the phone's own stores: Parnassus captures, list items and
 * notes, tasks, calendar titles and times (never notes or places), closet descriptions (never sizes),
 * derived notification lines (the same ones Haiku may see; raw text is never indexed), and documents
 * he added. Calypso is never touched: sealed people are dropped before a notification is stored, and
 * photos are indexed only by the words that describe them.
 */
class Indexer(private val app: Context, private val core: BrainCore, private val alexandria: Alexandria) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val day = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a", Locale.US)
    private val docsDir: File get() = File(app.filesDir, "alexandria/docs").apply { mkdirs() }

    /** Re-reads every source. Cheap when nothing changed (hash per document). Returns how many were (re)indexed. */
    fun reindex(): Int {
        var n = 0
        val st = core.store
        n += alexandria.sync(Source.PARNASSUS, st.captures(500).filter { it.text.isNotBlank() }.map { c ->
            MemDoc("cap:${c.id}", Source.PARNASSUS, c.text.take(60), c.text + (c.outcome?.let { "\n$it" } ?: ""), c.at, ref = "parnassus")
        })
        n += alexandria.sync(Source.NOTE, core.sections.all().filter { !it.hidden }.flatMap { s ->
            st.items(s.id).filter { !it.lethe }.map { i -> MemDoc("item:${i.id}", Source.NOTE, "${s.name}: ${i.text}".take(80), listOf(i.text, i.note).filter { it.isNotBlank() }.joinToString("\n"), i.createdAt, ref = "section:${s.id}") }
        })
        val tasks = core.tasks.load()
        n += alexandria.sync(Source.TASK, tasks.tasks.filter { !it.sample }.map { t ->
            val due = t.due?.let { "Due " + day.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()
            MemDoc("task:${t.id}", Source.TASK, t.title.take(80), listOf(t.title, t.list.label + if (t.list == TaskList.DONE) " (done)" else "", due, tasks.project(t.projectId)?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(". "), t.doneAt ?: t.due ?: 0L, ref = "agora")
        })
        runCatching {
            val src = CalendarSource(app)
            if (src.allowed()) {
                val cals = src.calendars().associateBy { it.id }
                val now = System.currentTimeMillis()
                val events = src.readRange(now - 60 * 86_400_000L, now + 90 * 86_400_000L, cals)
                n += alexandria.sync(Source.CALENDAR, events.map { e ->
                    MemDoc("cal:${e.key}", Source.CALENDAR, e.title.take(80), "${e.title}. ${day.format(Instant.ofEpochMilli(e.begin).atZone(zone))}" + (if (e.allDay) " (all day)" else "") + ". ${e.calendarName}", e.begin, ref = "chronos")
                })
            }
        }
        runCatching {
            val closet = ClosetSource(app).load()
            n += alexandria.sync(Source.CLOSET, closet.items.filter { !it.archived }.map { i ->
                MemDoc("closet:${i.id}", Source.CLOSET, i.name, listOf(i.name, i.description, i.category, i.occasions.joinToString(", "), i.notes).filter { it.isNotBlank() }.joinToString(". "), 0L, ref = "venus")
            })
        }
        n += alexandria.sync(Source.NOTIFICATION, st.notes(400).mapNotNull { note ->
            val line = note.cloudLine?.replace(Regex("^#\\d+ \\| "), "") ?: return@mapNotNull null
            MemDoc("note:${note.id}", Source.NOTIFICATION, "${note.app} notification", line, note.at, shield = Shield.AEGIS, ref = "notification")
        })
        n += alexandria.sync(Source.DOCUMENT, picked().map { d -> MemDoc("doc:${d.id}", Source.DOCUMENT, d.name, text(d.file), d.addedAt, ref = "doc:${d.id}") })
        return n
    }

    // ---- documents he picks (Storage Access Framework), copied into app storage ----

    fun picked(): List<PickedDoc> = docsDir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".name") }.map { f ->
        val name = File(docsDir, f.name + ".name").takeIf { it.isFile }?.readText() ?: f.name
        PickedDoc(f.name, name, f, f.lastModified(), 0)
    }.sortedByDescending { it.addedAt }

    /** Copies the picked file in. The original stays where it was; Zeus keeps no permission to it. */
    fun add(uri: Uri): PickedDoc? {
        val r = app.contentResolver
        val name = r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Document"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("pdf", "txt", "md", "csv") } ?: return null
        val id = "${System.currentTimeMillis()}.$ext"
        val out = File(docsDir, id)
        r.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        File(docsDir, "$id.name").writeText(name.take(120))
        alexandria.put(MemDoc("doc:$id", Source.DOCUMENT, name, text(out), out.lastModified(), ref = "doc:$id"))
        return PickedDoc(id, name, out, out.lastModified(), 0)
    }

    fun removeDoc(id: String) {
        File(docsDir, id).delete(); File(docsDir, "$id.name").delete()
        alexandria.remove("doc:$id")
    }

    /** Plain text from a document. PDFs use the platform's text layer (Android 15+); scans without one are skipped. */
    fun text(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> if (Build.VERSION.SDK_INT >= 35) runCatching {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { pdf ->
                    (0 until minOf(pdf.pageCount, 200)).joinToString("\n\n") { i ->
                        pdf.openPage(i).use { p -> "Page ${i + 1}. " + p.textContents.joinToString(" ") { it.text } }
                    }
                }
            }
        }.getOrDefault("") else ""
        else -> runCatching { f.readText().take(400_000) }.getOrDefault("")
    }

    companion object {
        /** Kinds a capture can be indexed by: a photo only by its words. */
        val TEXT_KINDS = setOf(CaptureKind.TEXT, CaptureKind.VOICE, CaptureKind.LINK, CaptureKind.PHOTO)
    }
}
