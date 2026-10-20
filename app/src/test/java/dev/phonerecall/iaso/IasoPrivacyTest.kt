package dev.phonerecall.iaso

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Health data is private. The brain gets one derived line, only with sharing on; Iris, Lemnos, the other
 * widgets, the Now Bar and the watch get nothing. Iaso's own widget (placed by him) shows steps, the weight
 * trend and sleep. The last two tests read the source, so a later change that
 * wires health somewhere new fails here until this list is updated on purpose.
 */
class IasoPrivacyTest {
    private val full = IasoState(
        status = HcStatus.READY,
        report = HealthReport(
            days = listOf(DayStat(NOW.toLocalDate(), steps = 1_234, activeKcal = 312.0, restingHr = 58, avgHr = 77)),
            sleep = listOf(SleepNight(NOW.toLocalDate(), 0, 1, asleepMin = 298, deepMin = 41)),
            todayHr = listOf(HrPoint(0, 77), HrPoint(1, 104)),
            weights = listOf(WeightPoint(0, 65.4)),
        ),
        meals = listOf(Meal(1, 0, MealType.LUNCH, "Chicken rice bowl", "1 plate", 720, 34.0, 88.0, 22.0)),
    )

    @Test fun theBrainHearsNothingUntilSharingIsOn() {
        assertNull(IasoBrain.line(full, NOW))
        assertNull(IasoBrain.line(full.copy(shareWithBrain = true, report = HealthReport()), NOW))
    }

    @Test fun withSharingItIsOneCoarseLine() {
        val line = IasoBrain.line(full.copy(shareWithBrain = true), NOW)!!
        assertEquals("slept 5h, short; quiet day on foot", line)
        assertFalse(line.contains('\n'))
        // No raw values: not the steps, heart rate, weight, calories, minutes or the dish.
        for (raw in listOf("1234", "1,234", "298", "58", "77", "104", "65", "312", "720", "rice bowl", "bpm", "kcal", "kg", "lb")) {
            assertFalse("leaked $raw", line.contains(raw, ignoreCase = true))
        }
    }

    @Test fun theWatchGetsTheNextEventAndTopTaskOnly() {
        val ms = NOW.toInstant().toEpochMilli()
        fun ev(id: Long, title: String, inMin: Long, allDay: Boolean = false) =
            CalItem(id, 1, title, ms + inMin * 60_000, ms + (inMin + 30) * 60_000, allDay, EventCategory.WORK, "Work", null, null, false)
        fun task(id: Long, title: String, due: Long?, list: TaskList = TaskList.NEXT) = Task(id, title, list, null, due, false, id.toDouble(), false, null, false)
        val p = WatchPayload.of(
            listOf(ev(1, "Holiday", -60, allDay = true), ev(2, "1:1 with Taylor", 30), ev(3, "Gym", 180)),
            TaskSnapshot(listOf(task(1, "Someday idea", null, TaskList.SOMEDAY), task(2, "Renew car registration", ms + 86_400_000L), task(3, "Call the landlord", null)), loaded = true),
            NOW,
        )
        assertEquals(WatchPayload.KEYS, p.keys)
        assertEquals("1:1 with Taylor", p["event"])
        assertEquals("3:00 PM", p["eventAt"])
        assertEquals("Renew car registration", p["task"])
    }

    private val main = File("src/main/java/dev/phonerecall")

    /** Every file outside iaso/ that names something from it, and what it names. */
    private fun uses(): Map<String, Set<String>> = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.parentFile.name != "iaso" }
        .mapNotNull { f ->
            val names = Regex("dev\\.phonerecall\\.iaso\\.([A-Za-z]+)").findAll(f.readText()).map { it.groupValues[1] }.toSet()
            if (names.isEmpty()) null else f.relativeTo(main).invariantSeparatorsPath to names
        }.toMap()

    @Test fun onlyTheseFilesReachIntoIaso() {
        assertTrue(main.isDirectory)
        assertEquals(
            mapOf(
                "Shell.kt" to setOf("IasoIcon", "IasoScreen"),
                "home/HomeScreen.kt" to setOf("IasoWeekCard"),
                // The brain: the one derived line, nothing else.
                "brain/Tools.kt" to setOf("IasoBrain"),
                // The morning brief: the same derived line, shown on the phone only (never in the Iris request).
                "brief/BriefAndroid.kt" to setOf("IasoBrain"),
                // Theogony's seed: the same derived line, into Athena's memory (sanitized), only with sharing on.
                "theogony/TheogonyAndroid.kt" to setOf("IasoBrain"),
                // Setup: whether Health Connect is there and allowed; the shakedown reads a week of steps and keeps nothing.
                "setup/TeleteAndroid.kt" to setOf("RealHc", "HcPermissions", "HcStatus"),
                "setup/ShakedownAndroid.kt" to setOf("RealHc"),
                // The watch: next event and top task, built from calendar and tasks only.
                "AthenaViewModel.kt" to setOf("WatchPayload", "WatchBridge"),
                // Iaso's own home-screen widget, only when he places it: steps, the weight trend, last night's sleep.
                "widgets/WidgetContent.kt" to setOf("IasoState", "WeightTrend", "Goals"),
                "widgets/WidgetBus.kt" to setOf("Iaso"),
            ),
            uses(),
        )
        val tools = File(main, "brain/Tools.kt").readText()
        assertEquals(listOf("dev.phonerecall.iaso.IasoBrain.current"), Regex("dev\\.phonerecall\\.iaso\\.[A-Za-z.]+").findAll(tools).map { it.value }.toList())
    }

    @Test fun iasoTalksToNoWorkerWidgetOrServer() {
        val src = File(main, "iaso").listFiles()!!.filter { it.extension == "kt" }.associate { it.name to it.readText() }
        for ((name, text) in src) {
            for (banned in listOf("dev.phonerecall.lemnos", "RemoteViews", "AppWidgetManager", "NotificationCompat", "NowBar", "okhttp3", "HttpURLConnection", "Gateway(")) {
                assertFalse("$name uses $banned", text.contains(banned))
            }
        }
        // The watch payload is built from calendar items and tasks; nothing from IasoState goes in.
        val watch = src.getValue("Watch.kt").substringBefore("class PhoneWearListener")
        for (health in listOf("IasoState", "report", "Meal", "WeightPoint", "Iaso.get")) assertFalse("Watch payload touches $health", watch.contains(health))
    }
}
