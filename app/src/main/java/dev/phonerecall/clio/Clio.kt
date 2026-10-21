package dev.phonerecall.clio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.stateJson
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

enum class Level { INFO, WARN, ERROR }

/** One line of Clio: when, which part of the app, how bad, what happened, a short sanitized detail. */
data class ClioLine(val ts: Long = 0, val area: String = "", val level: Level = Level.INFO, val event: String = "", val detail: String = "")

/**
 * The log on disk: one JSON line per event in app-private files/clio/, a file per day
 * (clio-2026-10-20-00.jsonl, then -01 past 2 MB). Days older than [KEEP_DAYS] go first, then the
 * oldest files until the folder is under 20 MB. Every event and detail passes [clean] on the way in.
 */
class ClioLog(
    val dir: File,
    private val clean: () -> Sanitizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val fileBytes: Long = FILE_BYTES,
    private val totalBytes: Long = TOTAL_BYTES,
) {
    private var written = 0

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    fun files(): List<File> = dir.listFiles { f -> NAME.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun current(now: Long): File {
        val prefix = "clio-${day(now)}-"
        val last = files().lastOrNull { it.name.startsWith(prefix) }
        if (last != null && last.length() < fileBytes) return last
        val n = last?.name?.removePrefix(prefix)?.removeSuffix(".jsonl")?.toIntOrNull()?.plus(1) ?: 0
        return File(dir, prefix + "%02d".format(n) + ".jsonl")
    }

    /** Writes one line. `max` is the detail's length after cleaning (crash stack traces get more). */
    @Synchronized fun append(area: String, level: Level, event: String, detail: String = "", max: Int = DETAIL) {
        val s = clean()
        val line = ClioLine(clock(), area, level, (s.line(event) ?: "[withheld]").take(EVENT), s.clean(detail).take(max).let { if (detail.isNotBlank() && it.isBlank()) "[withheld]" else it })
        dir.mkdirs()
        current(line.ts).appendText(stateJson.writeValueAsString(line) + "\n")
        if (written++ % 200 == 0) prune()
    }

    /** Retention: 14 days, then 20 MB, oldest first. */
    @Synchronized fun prune() {
        val oldest = day(clock()).minusDays(KEEP_DAYS - 1L).toString()
        files().filter { it.name.substring(5, 15) < oldest }.forEach { it.delete() }
        val left = files().toMutableList()
        var total = left.sumOf { it.length() }
        while (total > totalBytes && left.size > 1) { val f = left.removeAt(0); total -= f.length(); f.delete() }
    }

    /** Newest first, at or after `since`. */
    fun lines(since: Long = 0): List<ClioLine> {
        val out = ArrayList<ClioLine>()
        for (f in files().asReversed()) {
            if (f.name.substring(5, 15) < day(since).toString()) break
            val inFile = f.readLines().mapNotNull { runCatching { stateJson.readValue(it, ClioLine::class.java) }.getOrNull() }
            out += inFile.filter { it.ts >= since }.asReversed()
        }
        return out
    }

    /** files/clio/export.jsonl, oldest first, for `adb exec-out run-as dev.phonerecall cat files/clio/export.jsonl`. */
    @Synchronized fun export(): File {
        val out = File(dir, EXPORT)
        out.bufferedWriter().use { w -> for (f in files()) f.forEachLine { if (it.isNotBlank()) { w.write(it); w.newLine() } } }
        return out
    }

    companion object {
        const val KEEP_DAYS = 14
        const val FILE_BYTES = 2L * 1024 * 1024
        const val TOTAL_BYTES = 20L * 1024 * 1024
        const val DETAIL = 240
        const val EVENT = 60
        const val EXPORT = "export.jsonl"
        private val NAME = Regex("clio-\\d{4}-\\d{2}-\\d{2}-\\d{2,}\\.jsonl")

        /** One line as plain text, for Copy. */
        fun plain(l: ClioLine, zone: ZoneId): String =
            "${Instant.ofEpochMilli(l.ts).atZone(zone).toLocalDateTime().withNano(0)} ${l.area} ${l.level.name.lowercase()} ${l.event}" + if (l.detail.isEmpty()) "" else ": ${l.detail}"
    }
}

/** Writes the crash to Clio first, then hands it to whoever was there before (the system's handler). */
class ClioCrashHandler(private val previous: Thread.UncaughtExceptionHandler?, private val write: (Thread, Throwable) -> Unit) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { write(t, e) }
        previous?.uncaughtException(t, e)
    }
}

/**
 * Clio: what happened, in order. One per process; a no-op until [init] (unit tests and previews).
 * Writes go to one background thread so the main thread never touches the disk; a crash writes inline.
 * Areas: mind, heartbeat, senses, fates, odyssey, alarms, music, argo, praxis, lemnos, netgate, widgets,
 * workers, alexandria, audit, ui, crash, chronos (Google Calendar birthdays), setup (Telete's checks and self-tests), device, places.
 */
object Clio {
    /** Areas whose entries carry a main-thread stack: ANRs and crashes, and the watchdog's stalls. */
    val STACK_AREAS = setOf("crash", "ui")
    @Volatile private var log: ClioLog? = null
    /** Refreshed with his people's labels and Calypso's names by the mind's workers. */
    @Volatile var clean: Sanitizer = Sanitizer()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zeus-clio").apply { isDaemon = true } }

    fun init(c: Context) {
        if (log != null) return
        log = ClioLog(File(c.applicationContext.filesDir, "clio"), { clean }).also { l -> io.execute { runCatching { l.prune() } } }
        Thread.setDefaultUncaughtExceptionHandler(ClioCrashHandler(Thread.getDefaultUncaughtExceptionHandler()) { t, e ->
            log?.append("crash", Level.ERROR, "crash on ${t.name}", e.stackTraceToString(), max = 6000)
        })
    }

    fun get(): ClioLog? = log

    fun log(area: String, level: Level, event: String, detail: String = "", max: Int = ClioLog.DETAIL) {
        val l = log ?: return
        runCatching { io.execute { runCatching { l.append(area, level, event, detail, max) } } }
    }

    fun info(area: String, event: String, detail: String = "") = log(area, Level.INFO, event, detail)
    fun warn(area: String, event: String, detail: String = "") = log(area, Level.WARN, event, detail)

    /** A swallowed exception: its type and message (no query strings), and where it was thrown. */
    fun error(area: String, t: Throwable) = log(area, Level.ERROR, t.javaClass.simpleName.ifEmpty { "error" }, describe(t))

    fun describe(t: Throwable): String {
        val at = t.stackTrace.firstOrNull { it.className.startsWith("dev.phonerecall") }?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return t.message.orEmpty().replace(QUERY, "").take(160) + at
    }

    /** The audit log, line for line: proposals under fates, Athena and her circle under mind. */
    fun audit(actor: String, action: String, detail: String) = info(auditArea(actor, action), "$actor: $action", detail)

    fun auditArea(actor: String, action: String): String = when {
        action in FATES -> "fates"
        actor in MIND -> "mind"
        else -> "audit"
    }

    /** A worker's start, finish and failure, with how long it ran. */
    suspend fun work(w: CoroutineWorker, block: suspend () -> ListenableWorker.Result): ListenableWorker.Result {
        val name = w.javaClass.simpleName
        val started = System.currentTimeMillis()
        info("workers", "$name started", if (w.runAttemptCount > 0) "attempt ${w.runAttemptCount + 1}" else "")
        return try {
            block().also { r ->
                val word = r.javaClass.simpleName.lowercase()
                log("workers", if (word == "failure") Level.WARN else Level.INFO, "$name $word", "${System.currentTimeMillis() - started} ms")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            info("workers", "$name stopped", "${System.currentTimeMillis() - started} ms"); throw e
        } catch (e: Throwable) {
            error("workers", e); throw e
        }
    }

    private val QUERY = Regex("\\?\\S*")
    private val FATES = setOf("proposed", "confirmed", "rejected", "expired", "undid", "edited", "failed", "confirm refused", "held back")
    private val MIND = setOf("Athena", "Metis", "Odysseus", "Perseus", "Atalanta", "Diomedes", "Arachne", "Annabeth", "Iris", "Oneiroi")
}
