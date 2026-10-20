package dev.phonerecall.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stand-in for EmbeddingGemma: a few "meaning" axes, so a query with no shared words still lands. */
object StubMeaning {
    private val axes = listOf(
        setOf("battery", "awake", "asleep", "dying", "drain", "unrestricted", "background"),
        setOf("park", "parked", "car", "places", "where", "commute", "office"),
        setOf("backup", "lose", "lost", "phrase", "safe"),
        setOf("song", "spotify", "music", "playlist", "tune"),
    )

    fun embed(text: String): FloatArray {
        val words = text.lowercase().split(Regex("[^a-z]+")).toSet()
        val v = FloatArray(axes.size + 1) { i -> if (i < axes.size) axes[i].count { it in words }.toFloat() else 0.3f }
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    val vectors: Map<String, FloatArray> = ThemisRegistry.entries.associate { it.key to embed(it.text) }
}

class ThemisSearchTest {
    private fun top(q: String, meaning: Boolean = false) =
        ThemisSearch.rank(q, ThemisRegistry.entries, if (meaning) StubMeaning.vectors else emptyMap(), if (meaning) StubMeaning.embed(q) else null).map { it.key }

    @Test fun wordsFindByLabelSynonymAndDescription() {
        assertEquals("battery", top("battery").first())
        assertEquals("odyssey", top("where I park").first())
        assertEquals("backup", top("recovery phrase").first())
        assertEquals("music", top("spotify").first())
        assertEquals("theme", top("dark mode").first())
        assertTrue(top("zzzz").isEmpty())
        assertTrue(top("  ").isEmpty())
    }

    @Test fun greekNamesFindTheirSections() {
        assertEquals("money", top("Moneta").first())
        assertEquals("bedtime", top("Hypnos").first())
        assertEquals("vault", top("Calypso").first())
        assertEquals("odyssey", top("Odyssey").first())
        assertEquals("backup", top("mnemosyne").first())
    }

    @Test fun plainNamesAndEverydayWordsFindThemToo() {
        for ((q, key) in listOf("money" to "money", "vault" to "vault", "bank" to "money", "alarm" to "bedtime", "car" to "odyssey", "keys" to "vault", "drives" to "odyssey")) {
            assertEquals(q, key, top(q).first())
        }
        assertTrue(top("sleep").take(2).containsAll(listOf("bedtime", "health")))
    }

    @Test fun resultsCarryBothNames() {
        assertEquals("Hypnos · Bedtime", ThemisRegistry.entries.single { it.key == "bedtime" }.name)
        assertEquals("Moneta · Money", ThemisRegistry.entries.single { it.key == "money" }.name)
        assertEquals("Theme", ThemisRegistry.entries.single { it.key == "theme" }.name)
        assertTrue(ThemisRegistry.entries.single { it.key == "bedtime" }.text.contains("Brother of death"))
    }

    @Test fun theSidebarFindsPlacesAndSettingsInOneList() {
        fun all(q: String) = ThemisSearch.rank(q, ThemisRegistry.all).map { it.key }
        assertTrue(all("Moneta").take(2).containsAll(listOf("place:MONEY", "money")))
        assertEquals("place:AGENTS", all("Pantheon").first())
        assertEquals("place:CALENDAR", all("Chronos").first())
        assertEquals("place:TASKS", all("todo").first())
        assertEquals("battery", all("battery").first())
        // Every place the sidebar can show is searchable, once.
        assertEquals(ThemisRegistry.places.map { it.dest }.toSet().size, ThemisRegistry.places.size)
        assertTrue(ThemisRegistry.places.any { it.dest == "SETUP" })
    }

    @Test fun aLabelBeatsASynonymBeatsADescription() {
        val e = SettingEntry("x", Group.OTHER, "Heartbeat", "Looks at what changed", listOf("pulse"))
        val label = ThemisSearch.keyword("heartbeat", e)
        val syn = ThemisSearch.keyword("pulse", e)
        val about = ThemisSearch.keyword("changed", e)
        assertTrue(label > syn && syn > about && about > 0)
    }

    @Test fun aHalfMatchedQueryRanksBelowAFullOne() {
        val full = SettingEntry("a", Group.OTHER, "Calendar writes", "")
        val half = SettingEntry("b", Group.OTHER, "Calendar", "")
        assertEquals(listOf("a", "b"), ThemisSearch.rank("calendar writes", listOf(half, full)).map { it.key })
    }

    @Test fun meaningFindsWhatTheWordsMiss() {
        // No word of "my phone keeps dying" is in any label or synonym.
        assertTrue("battery" !in top("my phone keeps dying"))
        assertEquals("battery", top("my phone keeps dying", meaning = true).first())
        assertEquals("backup", top("dont want to lose stuff", meaning = true).first())
    }

    @Test fun wordsStillLeadWhenMeaningAgrees() {
        assertEquals("odyssey", top("where I park", meaning = true).first())
        assertEquals("battery", top("battery", meaning = true).first())
    }

    @Test fun theRegistryIsWholeAndUnique() {
        val keys = ThemisRegistry.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(Group.entries.toSet(), ThemisRegistry.rows.map { it.first }.toSet())
        // A row belongs to one section only.
        assertEquals(ThemisRegistry.rows.size, ThemisRegistry.rows.map { it.second }.toSet().size)
        assertTrue(ThemisRegistry.entries.all { it.label.isNotBlank() && it.about.isNotBlank() })
        val rowKeys = ThemisRegistry.rows.map { it.second }.toSet()
        assertTrue(ThemisRegistry.entries.all { it.row in rowKeys })
    }

    @Test fun theCacheKeyFollowsTheText() {
        val a = ThemisSearch.hash(ThemisRegistry.entries)
        assertEquals(a, ThemisSearch.hash(ThemisRegistry.entries.toList()))
        assertNotEquals(a, ThemisSearch.hash(ThemisRegistry.entries.mapIndexed { i, e -> if (i == 0) e.copy(about = e.about + ".") else e }))
    }
}
