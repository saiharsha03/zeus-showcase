package dev.phonerecall.iaso

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.ModelGateway
import dev.phonerecall.brain.SpendGuard
import dev.phonerecall.brain.TokenUse
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * JPEG metadata removal, byte by byte. Re-encoding a Bitmap already writes no EXIF, but this runs on every
 * photo that leaves the phone anyway: APP1 to APP15 (EXIF, XMP, GPS, maker notes, ICC) and comments are
 * dropped; APP0 (JFIF), the tables and the image data are kept as they are.
 */
object Exif {
    fun strip(jpeg: ByteArray): ByteArray {
        require(jpeg.size >= 4 && u(jpeg, 0) == 0xFF && u(jpeg, 1) == 0xD8) { "Not a JPEG" }
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (u(jpeg, i) != 0xFF) throw IllegalArgumentException("Bad JPEG marker at $i")
            val marker = u(jpeg, i + 1)
            // Fill bytes before a marker.
            if (marker == 0xFF) { i++; continue }
            // Start of scan: everything after it is image data through the end of the file.
            if (marker == 0xDA) { out.write(jpeg, i, jpeg.size - i); return out.toByteArray() }
            if (marker == 0xD9) { out.write(jpeg, i, 2); return out.toByteArray() }
            val len = (u(jpeg, i + 2) shl 8) or u(jpeg, i + 3)
            require(len >= 2 && i + 2 + len <= jpeg.size) { "Bad JPEG segment length at $i" }
            val drop = marker in 0xE1..0xEF || marker == 0xFE
            if (!drop) out.write(jpeg, i, 2 + len)
            i += 2 + len
        }
        return out.toByteArray()
    }

    /** True when any APP1..APP15 or COM segment is present before the image data. */
    fun hasMetadata(jpeg: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= jpeg.size && u(jpeg, i) == 0xFF) {
            val m = u(jpeg, i + 1)
            if (m == 0xDA || m == 0xD9) return false
            if (m in 0xE1..0xEF || m == 0xFE) return true
            i += 2 + ((u(jpeg, i + 2) shl 8) or u(jpeg, i + 3))
        }
        return false
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}

/** The photo as it leaves the phone: upright, at most 1024 px on the long side, JPEG, no metadata. */
object MealPhoto {
    const val MAX_EDGE = 1024

    fun fromUri(cr: ContentResolver, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val deg = cr.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val upright = if (deg == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true)
        encode(upright)
    }.getOrNull()

    fun encode(b: Bitmap): ByteArray {
        val scale = MAX_EDGE.toFloat() / maxOf(b.width, b.height)
        val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true) else b
        val bytes = ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        return Exif.strip(bytes)
    }
}

/** One item Claude saw on the plate. */
data class FoodItem(val name: String, val portion: String, val kcal: Int)

/** Claude's guess. Only ever a suggestion: it fills the confirm sheet, and nothing saves until he taps Save. */
data class FoodEstimate(
    val dish: String,
    val portion: String,
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val confidence: String,
    val items: List<FoodItem> = emptyList(),
    val note: String? = null,
) {
    companion object {
        private val mapper = ObjectMapper()

        /**
         * The model's reply into an estimate. Tolerates a code fence or words around the JSON, clamps every
         * number to something a meal could be, and returns null when it isn't food at all ("not_food").
         */
        fun parse(text: String): FoodEstimate? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o: JsonNode = runCatching { mapper.readTree(text.substring(start, end + 1)) }.getOrNull() ?: return null
            if (o.path("not_food").asBoolean(false)) return null
            fun num(k: String, max: Double): Double {
                val n = o.path(k)
                val v = if (n.isNumber) n.asDouble() else n.asText("").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                return v.coerceIn(0.0, max)
            }
            val dish = o.path("dish").asText("").trim().take(80)
            if (dish.isEmpty()) return null
            val items = o.path("items").takeIf { it.isArray }?.mapNotNull { i ->
                val n = i.path("name").asText("").trim()
                if (n.isEmpty()) null else FoodItem(n.take(60), i.path("portion").asText("").trim().take(40), i.path("kcal").asDouble(0.0).coerceIn(0.0, 4000.0).roundToInt())
            }.orEmpty().take(8)
            return FoodEstimate(
                dish = dish,
                portion = o.path("portion").asText("").trim().take(60),
                kcal = num("kcal", 4000.0).roundToInt(),
                proteinG = round1(num("protein_g", 300.0)),
                carbsG = round1(num("carbs_g", 600.0)),
                fatG = round1(num("fat_g", 300.0)),
                confidence = o.path("confidence").asText("medium").lowercase().takeIf { it in setOf("low", "medium", "high") } ?: "medium",
                items = items,
                note = o.path("note").asText("").trim().take(140).ifEmpty { null },
            )
        }

        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}

/** Whatever turns a photo into a guess. The app uses Claude; tests use a fake. */
fun interface FoodEstimator {
    /** Blocking; call off the main thread. Null: it couldn't see a meal. */
    fun estimate(jpeg: ByteArray, hint: String): FoodEstimate?
}

/**
 * Sonnet 5.5 through Zeus's one Anthropic client (NetGate in front, api.anthropic.com only, telemetry off)
 * and the shared spend cap. The photo is sent once, for this estimate, and nothing about him goes with it:
 * no name, no history, no other health data.
 */
class ClaudeFoodEstimator(private val gateway: ModelGateway, private val spend: SpendGuard) : FoodEstimator {
    override fun estimate(jpeg: ByteArray, hint: String): FoodEstimate? {
        require(!Exif.hasMetadata(jpeg)) { "Photo still has metadata" }
        spend.check()
        val msg = gateway.create(params(jpeg, hint))
        val u = msg.usage()
        spend.record(BrainModel.SONNET, TokenUse(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L)))
        return FoodEstimate.parse(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
    }

    companion object {
        val SYSTEM = """
You estimate a meal from one photo for a private food log. Reply with one JSON object and nothing else:
{"dish": short name, "portion": e.g. "1 plate, about 350 g", "kcal": number, "protein_g": number, "carbs_g": number, "fat_g": number, "confidence": "low" | "medium" | "high", "items": [{"name": text, "portion": text, "kcal": number}], "note": one short line or ""}
Estimate the whole visible portion. Use typical recipes, including home cooking. If the photo does not show food, reply {"not_food": true}.
        """.trimIndent()

        fun params(jpeg: ByteArray, hint: String): MessageCreateParams {
            val image = ImageBlockParam.builder().source(
                Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(java.util.Base64.getEncoder().encodeToString(jpeg)).build(),
            ).build()
            val ask = if (hint.isBlank()) "Estimate this meal." else "Estimate this meal. He says: ${hint.trim().take(120)}"
            return MessageCreateParams.builder()
                .model(BrainModel.SONNET.id)
                .maxTokens(1_500L)
                .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(SYSTEM).cacheControl(com.anthropic.models.messages.CacheControlEphemeral.builder().build()).build()))
                .messages(listOf(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(TextBlockParam.builder().text(ask).build()))).build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .build()
        }
    }
}

/** The confirm sheet's fields, as he edits them. Text, so a half-typed number doesn't fight him. */
data class MealDraft(
    val dish: String = "",
    val portion: String = "",
    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val type: MealType = MealType.SNACK,
    val at: Long = 0L,
    val fromPhoto: Boolean = false,
    /** Claude's confidence and note, shown above the fields. Not saved. */
    val confidence: String? = null,
    val note: String? = null,
    val items: List<FoodItem> = emptyList(),
) {
    /** What Save needs: a name and a calorie number. */
    val problem: String? get() = when {
        dish.isBlank() -> "Name the dish."
        kcal.trim().toIntOrNull() == null -> "Calories must be a number."
        kcal.trim().toInt() !in 0..5000 -> "Calories look off."
        listOf(protein, carbs, fat).any { it.isNotBlank() && it.trim().replace(',', '.').toDoubleOrNull() == null } -> "Macros must be numbers."
        else -> null
    }

    fun toMeal(id: Long): Meal? {
        if (problem != null) return null
        fun g(s: String) = s.trim().replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 600.0) ?: 0.0
        return Meal(id, at, type, dish.trim().take(80), portion.trim().take(60), kcal.trim().toInt(), g(protein), g(carbs), g(fat), fromPhoto = fromPhoto)
    }

    companion object {
        fun typeAt(t: ZonedDateTime): MealType = when (t.hour) {
            in 5..10 -> MealType.BREAKFAST
            in 11..15 -> MealType.LUNCH
            in 17..22 -> MealType.DINNER
            else -> MealType.SNACK
        }

        fun of(e: FoodEstimate, at: ZonedDateTime): MealDraft = MealDraft(
            dish = e.dish, portion = e.portion, kcal = e.kcal.toString(),
            protein = fmt(e.proteinG), carbs = fmt(e.carbsG), fat = fmt(e.fatG),
            type = typeAt(at), at = at.toInstant().toEpochMilli(), fromPhoto = true,
            confidence = e.confidence, note = e.note, items = e.items,
        )

        fun blank(at: ZonedDateTime) = MealDraft(type = typeAt(at), at = at.toInstant().toEpochMilli())

        private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}
