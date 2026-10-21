package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.TimeZone

/** Raw location never reaches the brain: only OdysseyBrain.facts(), and those carry no coordinates or addresses. */
class PrivacyTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private fun rich(): OdysseyData {
        var d = Fx.officeWeeks(6)
        var day = LocalDate.of(2026, 9, 6)
        repeat(6) {
            d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day, 10, 5), Fx.at(day, 11, 30), street = "Ridge Rd", poi = "Riverside Cultural Center", wifi = "RCC-Guest")
            d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 12), Fx.at(day, 12, 45), hints = listOf("You spent $82.14 at COSTCO WHSE #1234"), street = "Maple Ave")
            d = Fx.stay(d, 42.9312, -85.6771, Fx.at(day, 14), Fx.at(day, 14, 40), street = "Oak Ave")
            day = day.plusDays(7)
        }
        val drive = DriveReport("d1", Fx.at(day, 9), Fx.at(day, 9, 25), 13_000, 80, listOf(DriveEvent(DriveEventKind.HARSH_BRAKE, Fx.at(day, 9, 10), 42.91234, -85.67654, 0.42, 40, "Main St")), score = 91)
        return d.copy(drives = listOf(drive))
    }

    @Test fun theBrainGetsDerivedFactsOnly() {
        val d = rich()
        val facts = OdysseyBrain.facts(d, Fx.at(LocalDate.of(2026, 10, 18), 12), Fx.zone)
        assertTrue(facts.toString(), facts.any { it.startsWith("You leave for the office ~8:20 on Mon–Wed") })
        assertTrue(facts.toString(), facts.any { it.contains("Temple every Sunday") })
        assertTrue(facts.toString(), facts.any { it.startsWith("Driving score this week 91") })
        val all = facts.joinToString("\n")
        assertFalse("no coordinates", Regex("""-?\d{1,3}\.\d{3,}""").containsMatchIn(all))
        for (secret in listOf("42.9", "85.6", "Oak Ave", "Ridge Rd", "Maple Ave", "Main St", "RCC-Guest", "$", "82.14", "Near ")) {
            assertFalse("leaked \"$secret\": $all", all.contains(secret))
        }
        assertTrue("nothing at all when Odyssey is off", OdysseyBrain.facts(d.copy(settings = OdysseySettings()), 0L).isEmpty())
    }

    @Test fun scrubCutsAnythingThatLooksLikeACoordinateOrAnAmount() {
        assertEquals("at ,", OdysseyBrain.scrub("at 42.95501,-85.69000"))
        assertEquals("spent", OdysseyBrain.scrub("spent $82.14"))
    }

    /** The source itself: the brain is reached in one place, with facts(); no model is called from Odyssey. */
    @Test fun odysseyReachesTheBrainOnlyThroughFacts() {
        val dir = File(System.getProperty("user.dir"), "src/main/java/dev/phonerecall/odyssey")
        val src = dir.listFiles()!!.filter { it.extension == "kt" }.associate { it.name to it.readText() }
        val brainLines = src.flatMap { (f, s) -> s.lines().filter { it.contains("mindHost") || it.contains("MindNote(") }.map { "$f: ${it.trim()}" } }
        assertEquals(brainLines.toString(), 1, brainLines.count { it.contains("mindHost.offer") })
        assertTrue(brainLines.toString(), brainLines.single { it.contains("mindHost.offer") }.contains("facts"))
        for ((f, s) in src) for (bad in listOf("haiku()", "CloudModel", ".complete(", "Anthropic", "upload")) assertFalse("$f mentions $bad", s.contains(bad))
        // And the brain's own code never reads Odyssey's store.
        val brainSide = File(System.getProperty("user.dir"), "src/main/java/dev/phonerecall").walk().filter { it.extension == "kt" && (it.path.contains("${File.separator}brain${File.separator}") || it.path.contains("${File.separator}mind${File.separator}")) }
        // Except read_zeus's reader: the owner chose (2026-10-20) that Athena reads all of Zeus but Calypso, places and coordinates included.
        for (f in brainSide.filter { it.name != "ZeusAreas.kt" }) assertFalse("${f.name} reads OdysseyStore", f.readText().contains("OdysseyStore"))
    }

    /** Routes stay in Odyssey's screens: nothing outside odyssey/ (Athena, Delphi, tools, backups) names them, and the brain's facts never read them. */
    @Test fun routesNeverLeaveOdyssey() {
        val root = File(System.getProperty("user.dir"), "src/main/java/dev/phonerecall")
        val outside = root.walk().filter { it.extension == "kt" && !it.path.contains("${File.separator}odyssey${File.separator}") }
        for (f in outside) for (name in listOf("RouteStore", "RouteFiles", "DriveRoute", "TrackPoint", "TrackRecorder", "odyssey-routes")) assertFalse("${f.name} mentions $name", f.readText().contains(name))
        val odyssey = File(root, "odyssey").listFiles()!!.filter { it.extension == "kt" }
        val users = odyssey.filter { f -> f.readText().contains("RouteStore") }.map { it.name }.toSet()
        assertEquals(setOf("Track.kt", "DriveService.kt", "Odyssey.kt", "Screens.kt"), users)
        val brain = odyssey.first { it.name == "Bridges.kt" }.readText().substringAfter("object OdysseyBrain")
        assertFalse(brain.contains("RouteStore") || brain.contains("DriveRoute") || brain.contains("TrackPoint"))
    }
}
