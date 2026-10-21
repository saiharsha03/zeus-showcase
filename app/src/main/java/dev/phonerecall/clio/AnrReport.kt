package dev.phonerecall.clio

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * Android keeps the thread dump of each ANR ("isn't responding") with the process's exit record. On the next start
 * the ones Clio hasn't seen go in under "crash", with the main thread's stack, so a freeze that ended in an ANR
 * says where it was stuck. The trace holds stacks only, no app data.
 */
object AnrReport {
    private const val PREFS = "clio"
    private const val SEEN = "anr.seen"

    fun logNew(c: Context) {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = sp.getLong(SEEN, 0)
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        val anrs = am.getHistoricalProcessExitReasons(c.packageName, 0, 16)
            .filter { it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (e in anrs) {
            val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            Clio.log("crash", Level.ERROR, "ANR", (e.description.orEmpty().take(200) + "\n" + (trace?.let(::mainThread) ?: "no trace")), max = 6000)
        }
        anrs.lastOrNull()?.let { sp.edit().putLong(SEEN, it.timestamp).apply() }
    }

    /** The "main" thread's block of a thread dump: its header, state and frames, up to the blank line after it. */
    fun mainThread(trace: String, frames: Int = 60): String {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return "no main thread in the trace"
        val block = lines.drop(start).takeWhile { it.isNotBlank() }
        val head = block.take(4).filter { !it.trimStart().startsWith("at ") && !it.trimStart().startsWith("native:") && !it.trimStart().startsWith("-") }
        val stack = block.filter { val t = it.trimStart(); t.startsWith("at ") || t.startsWith("- ") }.take(frames)
        return (head + stack).joinToString("\n") { it.trim() }
    }
}
