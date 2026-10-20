package dev.phonerecall.places

import dev.phonerecall.brain.CloudModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrival notification's words: Haiku live, else Nano's pregenerated text, else the template. */
class ArrivalTextTest {
    private val lists = mapOf("Groceries" to listOf("Tomatoes", "Garlic", "Parmesan"))
    private val key = ArrivalText.itemsKey(lists)

    @Test fun haikuWritesItLive() {
        var prompt = ""
        val cloud = CloudModel { _, _, user, _ -> prompt = user; "You're at Walmart. Tomatoes, garlic and parmesan for the pasta." }
        val (text, by) = ArrivalText.compose("Walmart", lists, cloud, "pregen", key)
        assertEquals("haiku", by)
        assertTrue(text.startsWith("You're at Walmart"))
        // Derived list items only: the store's name and the items. No coordinates, no history.
        assertEquals("Store: Walmart\nGroceries: Tomatoes, Garlic, Parmesan", prompt)
    }

    @Test fun offlineFallsBackToNanosPregeneratedText() {
        val offline = CloudModel { _, _, _, _ -> throw java.io.IOException("no network") }
        val (text, by) = ArrivalText.compose("Walmart", lists, offline, "Walmart: tomatoes, garlic, parmesan.", key)
        assertEquals("nano", by)
        assertEquals("Walmart: tomatoes, garlic, parmesan.", text)
    }

    @Test fun aSlowCallDoesNotHoldTheNotification() {
        val slow = CloudModel { _, _, _, _ -> Thread.sleep(5_000); "late" }
        val started = System.currentTimeMillis()
        val (_, by) = ArrivalText.compose("Walmart", lists, slow, "pregen", key, timeoutMs = 300)
        assertEquals("nano", by)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test fun stalePregenOrNoneUsesTheTemplate() {
        val (stale, by) = ArrivalText.compose("Walmart", lists, null, "Walmart: milk.", "Groceries:Milk")
        assertEquals("template", by)
        assertEquals("You're at Walmart. Tomatoes, Garlic, Parmesan are on your list.", stale)
        assertEquals("You're at Costco. Nothing on your list right now.", ArrivalText.compose("Costco", emptyMap(), null, null, null).first)
        assertEquals("You're at Costco. Milk is on your list.", ArrivalText.template("Costco", listOf("Milk")))
    }
}
