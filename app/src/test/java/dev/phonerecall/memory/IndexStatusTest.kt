package dev.phonerecall.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexStatusTest {
    private fun line(lastRun: Long? = 1L, running: Boolean = false, error: String? = null, passages: Int = 463, embedded: Int = 0, model: EmbedModel = EmbedModel.READY, charging: Boolean = false) =
        IndexStatus.line(lastRun, running, error, passages, embedded, model, charging) { "Oct 20, 7:09 AM" }

    @Test fun runningWinsOverEverything() = assertEquals("Indexing now.", line(lastRun = null, running = true, error = "boom"))

    @Test fun neverRunAsksForIndexNow() = assertEquals("Not indexed yet. Tap Index now.", line(lastRun = null))

    @Test fun aFailedRunSaysWhy() =
        assertEquals("The last run at Oct 20, 7:09 AM stopped: disk full. Tap Index now to try again.", line(error = "disk full"))

    @Test fun emptyPhoneIsNotAnError() = assertEquals("Indexed Oct 20, 7:09 AM. Nothing on the phone to keep yet.", line(passages = 0))

    @Test fun missingModelNamesTheFileAndFolder() = assertEquals(
        "Indexed by words Oct 20, 7:09 AM, 463 passages. Meaning search waits for embeddinggemma-2-740m.litertlm in Android/data/dev.phonerecall/files.",
        line(model = EmbedModel.MISSING),
    )

    @Test fun modelThatFailedToLoadSaysSo() = assertEquals(
        "Indexed by words Oct 20, 7:09 AM, 463 passages. EmbeddingGemma is on the phone but failed to load.",
        line(model = EmbedModel.FAILED),
    )

    @Test fun partlyEmbeddedWaitsForTheCharger() {
        assertEquals("100 of 463 passages embedded. The rest wait for the charger, or tap Index now.", line(embedded = 100))
        assertEquals("100 of 463 passages embedded. The rest embed on the charger within 30 minutes.", line(embedded = 100, charging = true))
    }

    @Test fun allEmbedded() = assertEquals("Indexed Oct 20, 7:09 AM, 463 passages, all embedded.", line(embedded = 463))
}
