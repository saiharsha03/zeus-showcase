package dev.phonerecall.clio

import org.junit.Assert.assertEquals
import org.junit.Test

class AnrReportTest {
    private val trace = """
        ----- pid 25637 at 2026-10-20 20:08:00 -----
        Cmd line: dev.phonerecall

        "Signal Catcher" daemon prio=10 tid=7 Runnable
          | group="system" sCount=0
          at java.lang.Object.wait(Native method)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a3d2f0 self=0xb400
          | sysTid=25637 nice=-10 cgrp=top-app sched=0/0 handle=0x7
          native: #00 pc 000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          at dev.phonerecall.odyssey.OdysseyStore.load(Store.kt:27)
          - waiting to lock <0x0f1e2d3c> (a java.lang.Object) held by thread 23
          at dev.phonerecall.moneta.MonetaScreenKt.monetaUi(MonetaScreen.kt:415)

        "zeus-clio" daemon prio=5 tid=23 Native
          at java.io.FileOutputStream.write(Native method)
    """.trimIndent()

    @Test fun theMainThreadsBlockIsKeptWithItsLock() {
        assertEquals(
            listOf(
                "\"main\" prio=5 tid=1 Blocked",
                "| group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a3d2f0 self=0xb400",
                "| sysTid=25637 nice=-10 cgrp=top-app sched=0/0 handle=0x7",
                "at dev.phonerecall.odyssey.OdysseyStore.load(Store.kt:27)",
                "- waiting to lock <0x0f1e2d3c> (a java.lang.Object) held by thread 23",
                "at dev.phonerecall.moneta.MonetaScreenKt.monetaUi(MonetaScreen.kt:415)",
            ).joinToString("\n"),
            AnrReport.mainThread(trace),
        )
    }

    @Test fun aTraceWithoutMainSaysSo() = assertEquals("no main thread in the trace", AnrReport.mainThread("\"Binder:1\" prio=5\n  at x.y(Z.kt:1)"))
}
