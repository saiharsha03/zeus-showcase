package dev.phonerecall.iaso

import androidx.health.connect.client.records.NutritionRecord
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.ModelGateway
import dev.phonerecall.brain.SpendGuard
import dev.phonerecall.brain.TokenUse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The photo leaves without metadata, Claude's reply parses into a suggestion, and only his Save keeps a meal. */
class FoodTest {
    // ---- EXIF ----

    private fun seg(marker: Int, body: ByteArray): ByteArray {
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + body
    }

    /** A JPEG's shape: JFIF, an EXIF block with GPS, XMP, a comment, a table, then the scan. */
    private val camera: ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        write(seg(0xE0, "JFIF\u0000\u0001\u0001".toByteArray()))
        write(seg(0xE1, "Exif\u0000\u0000GPSLatitude 42.8864 N GPSLongitude 78.8784 W Model SM-F966".toByteArray()))
        write(seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>Buffalo</x:xmpmeta>".toByteArray()))
        write(seg(0xFE, "Taken at home".toByteArray()))
        write(seg(0xDB, ByteArray(65) { it.toByte() }))
        write(seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)))
        write(byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56))
        write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()

    @Test fun stripsExifXmpAndCommentsButKeepsTheImage() {
        assertTrue(Exif.hasMetadata(camera))
        val clean = Exif.strip(camera)
        assertFalse(Exif.hasMetadata(clean))
        val s = String(clean, Charsets.ISO_8859_1)
        for (gone in listOf("GPS", "Exif", "xmpmeta", "Buffalo", "SM-F966", "Taken at home")) assertFalse(gone, s.contains(gone))
        assertTrue(s.contains("JFIF"))
        // Everything from the quantisation table to the end is byte-identical.
        val tail = camera.copyOfRange(String(camera, Charsets.ISO_8859_1).indexOf("ÿÛ"), camera.size)
        assertArrayEquals(tail, clean.copyOfRange(clean.size - tail.size, clean.size))
        // Stripping twice changes nothing.
        assertArrayEquals(clean, Exif.strip(clean))
    }

    @Test fun refusesWhatIsNotAJpeg() {
        try { Exif.strip("hello".toByteArray()); fail() } catch (_: IllegalArgumentException) { }
    }

    // ---- parsing ----

    @Test fun parsesAFencedReplyAndClampsNumbers() {
        val e = FoodEstimate.parse(
            """Here you go:
```json
{"dish":"Chicken rice bowl with yogurt","portion":"1 plate, about 400 g","kcal":"720 kcal","protein_g":34,"carbs_g":88.44,"fat_g":-3,
 "confidence":"Medium","items":[{"name":"Rice bowl","portion":"350 g","kcal":640},{"name":"Yogurt","portion":"50 g","kcal":80},{"name":"","kcal":5}],"note":"Oil not visible"}
```""",
        )!!
        assertEquals("Chicken rice bowl with yogurt", e.dish)
        assertEquals(720, e.kcal)
        assertEquals(34.0, e.proteinG, 0.0)
        assertEquals(88.4, e.carbsG, 0.0)
        assertEquals(0.0, e.fatG, 0.0)
        assertEquals("medium", e.confidence)
        assertEquals(listOf("Rice bowl", "Yogurt"), e.items.map { it.name })
        assertEquals("Oil not visible", e.note)
    }

    @Test fun notFoodOrBrokenRepliesGiveNothing() {
        assertNull(FoodEstimate.parse("""{"not_food": true}"""))
        assertNull(FoodEstimate.parse("I can't tell what that is."))
        assertNull(FoodEstimate.parse("""{"dish":"","kcal":300}"""))
        assertNull(FoodEstimate.parse("""{"dish": "Omelette", "kcal": """))
        assertEquals(4000, FoodEstimate.parse("""{"dish":"Feast","kcal":99999}""")!!.kcal)
    }

    // ---- the confirm flow ----

    private val omelette = FoodEstimate("Veggie omelette", "2 eggs with salsa", 420, 9.0, 58.5, 16.0, "high")

    @Test fun anEstimateIsOnlyASuggestionUntilSave() = runBlocking {
        val storage = MemoryStorage()
        val hc = FakeHc()
        var seen: ByteArray? = null
        val h = hub(storage, hc, estimator = { jpeg, _ -> seen = jpeg; omelette })
        val draft = h.estimate(camera).getOrThrow()
        // The estimator got the stripped photo; nothing was saved or written.
        assertFalse(Exif.hasMetadata(seen!!))
        assertEquals(0, storage.saves)
        assertTrue(h.state.value.meals.isEmpty())
        assertTrue(hc.inserted.isEmpty())
        assertEquals("420", draft.kcal)
        assertEquals(MealType.LUNCH, draft.type)
        assertTrue(draft.fromPhoto)

        // He corrects the calories, then saves.
        val m = h.confirm(draft.copy(kcal = "380", dish = "Veggie omelette, half salsa"))!!
        assertEquals(380, m.kcal)
        assertTrue(m.inHc)
        assertEquals(listOf(380), h.state.value.meals.map { it.kcal })
        val n = hc.inserted.single() as NutritionRecord
        assertEquals(380.0, n.energy!!.inKilocalories, 0.01)
        assertEquals("Veggie omelette, half salsa", n.name)
        assertEquals(androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH, n.mealType)
        assertEquals(9.0, n.protein!!.inGrams, 0.01)
    }

    @Test fun discardingKeepsNothing() {
        val storage = MemoryStorage()
        val hc = FakeHc()
        val h = hub(storage, hc, estimator = { _, _ -> omelette })
        assertTrue(h.estimate(camera).isSuccess)
        // Discard is simply not calling confirm.
        assertEquals(0, storage.saves)
        assertTrue(hc.inserted.isEmpty())
    }

    @Test fun anInvalidDraftCannotBeSaved() = runBlocking {
        val h = hub()
        assertEquals("Calories must be a number.", MealDraft(dish = "Tea", kcal = "lots").problem)
        assertEquals("Name the dish.", MealDraft(kcal = "100").problem)
        assertEquals("Macros must be numbers.", MealDraft(dish = "Tea", kcal = "40", protein = "a bit").problem)
        assertNull(h.confirm(MealDraft(dish = "Tea", kcal = "lots")))
        assertTrue(h.state.value.meals.isEmpty())
    }

    @Test fun withoutHealthConnectTheMealStaysLocalAndSyncsLater() = runBlocking {
        val storage = MemoryStorage()
        val hc = FakeHc(granted = HcPermissions.READ)
        val h = hub(storage, hc)
        val m = h.confirm(MealDraft(dish = "Tea", kcal = "90", at = NOW.toInstant().toEpochMilli()))!!
        assertFalse(m.inHc)
        assertTrue(hc.inserted.isEmpty())
        hc.granted = HcPermissions.ALL
        h.refresh()
        assertEquals(1, hc.inserted.size)
        assertTrue(h.state.value.meals.single().inHc)
        assertTrue(storage.saved.meals.single().inHc)
    }

    @Test fun estimatorFailuresAreOneLine() {
        assertEquals("Claude couldn't see a meal in that photo.", hub(estimator = { _, _ -> null }).estimate(camera).exceptionOrNull()?.message)
        assertEquals("Add your Anthropic key in Calypso first.", hub(estimator = null).estimate(camera).exceptionOrNull()?.message)
    }

    // ---- Claude ----

    private val json = jsonMapper()
    private fun reply(text: String): Message = json.readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":${json.writeValueAsString(text)}}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1500,"output_tokens":120}}""",
        Message::class.java,
    )

    @Test fun claudeGetsTheCleanPhotoOnSonnetAndIsCharged() {
        val sent = ArrayList<MessageCreateParams>()
        val charged = ArrayList<Pair<BrainModel, TokenUse>>()
        val guard = object : SpendGuard {
            override fun check() = Unit
            override fun record(model: BrainModel, use: TokenUse) { charged += model to use }
        }
        val gateway = object : ModelGateway {
            override fun send(params: MessageCreateParams, onText: (String) -> Unit): Message = error("not streamed")
            override fun create(params: MessageCreateParams): Message { sent += params; return reply("""{"dish":"Lentil soup and rice","portion":"1 bowl","kcal":510,"protein_g":18,"carbs_g":80,"fat_g":12,"confidence":"high"}""") }
        }
        val clean = Exif.strip(camera)
        val e = ClaudeFoodEstimator(gateway, guard).estimate(clean, "lunch at the office")
        assertNotNull(e)
        assertEquals("Lentil soup and rice", e!!.dish)
        val p = sent.single()
        assertEquals("claude-sonnet-5-5", p.model().asString())
        val body = json.writeValueAsString(p.messages())
        val b64 = Regex("\"data\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertArrayEquals(clean, java.util.Base64.getDecoder().decode(b64))
        // Nothing about him rides along: no name, no other health numbers.
        assertFalse(body.contains("The owner") || body.contains("weight") || body.contains("sleep"))
        assertEquals(BrainModel.SONNET to TokenUse(1500, 120), charged.single())
    }

    @Test fun claudeRefusesAPhotoThatStillHasMetadata() {
        val gateway = ModelGateway { _, _ -> error("must not be called") }
        val guard = object : SpendGuard { override fun check() = Unit; override fun record(model: BrainModel, use: TokenUse) = Unit }
        try { ClaudeFoodEstimator(gateway, guard).estimate(camera, ""); fail() } catch (_: IllegalArgumentException) { }
    }
}
