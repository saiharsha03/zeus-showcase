package dev.phonerecall.clio

import org.junit.Assert.assertTrue
import org.junit.Test

class MainWatchTest {
    private fun f(cls: String, m: String, line: Int) = StackTraceElement(cls, m, "$m.kt", line)
    private val looper = f("android.os.Looper", "loop", 1)
    private val people = listOf(f("android.os.BinderProxy", "transact", 9), f("dev.phonerecall.brain.SenseStore", "people", 186), f("dev.phonerecall.Shell", "content", 191), looper)
    private val draw = listOf(f("androidx.compose.ui.Layout", "measure", 4), f("dev.phonerecall.Shell", "content", 191), looper)

    @Test fun theAppFramesAreRankedBySharedSamplesAndTheCommonStackIsShown() {
        val s = MainWatch.summary(listOf(people, people, people, draw))
        assertTrue(s, s.startsWith("4 samples. Ours: Shell.content:191 100%; SenseStore.people:186 75%"))
        assertTrue(s, s.contains("Most common (3x):\nBinderProxy.transact:9\nSenseStore.people:186"))
    }

    @Test fun aStallOutsideTheAppSaysSo() {
        assertTrue(MainWatch.summary(listOf(listOf(looper))).contains("Ours: none"))
    }
}
