package dev.phonerecall.clio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A watchdog for the main thread (debug builds start it): a token is posted to the front of the main queue,
 * and while it waits the main thread's stack is sampled. A stall of [STALL_MS] or more goes to Clio under "ui"
 * with where the main thread was, so the next freeze carries its own evidence.
 */
object MainWatch {
    const val STALL_MS = 700L
    private const val TICK_MS = 100L
    private const val REST_MS = 400L
    private const val SAMPLES = 150
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val main = Looper.getMainLooper()
        val handler = Handler(main)
        thread(name = "zeus-main-watch", isDaemon = true) {
            while (true) {
                val ran = AtomicBoolean(false)
                val posted = SystemClock.uptimeMillis()
                handler.postAtFrontOfQueue { ran.set(true) }
                val samples = ArrayList<List<StackTraceElement>>()
                while (!ran.get()) {
                    Thread.sleep(TICK_MS)
                    if (SystemClock.uptimeMillis() - posted >= STALL_MS && samples.size < SAMPLES) samples += main.thread.stackTrace.toList()
                }
                val ms = SystemClock.uptimeMillis() - posted
                if (ms >= STALL_MS && samples.isNotEmpty()) Clio.log("ui", Level.WARN, "main stalled $ms ms", summary(samples), max = 6000)
                Thread.sleep(REST_MS)
            }
        }
    }

    /**
     * The app's frames that showed up most across the samples (share of samples each), then the most common
     * full stack, innermost first. Pure, for the unit test.
     */
    fun summary(samples: List<List<StackTraceElement>>, frames: Int = 40): String {
        fun name(e: StackTraceElement) = "${e.className.substringAfterLast('.')}.${e.methodName}:${e.lineNumber}"
        val ours = samples.flatMap { s -> s.filter { it.className.startsWith("dev.phonerecall") }.map(::name).distinct() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
            .joinToString("; ") { "${it.key} ${it.value * 100 / samples.size}%" }
        val common = samples.groupingBy { s -> s.take(frames).map(::name) }.eachCount().maxByOrNull { it.value }
        val stack = common?.key.orEmpty().joinToString("\n")
        return "${samples.size} samples. Ours: ${ours.ifEmpty { "none" }}\nMost common (${common?.value ?: 0}x):\n$stack"
    }
}
