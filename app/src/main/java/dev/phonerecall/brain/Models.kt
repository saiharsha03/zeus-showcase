package dev.phonerecall.brain

import android.graphics.Bitmap
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam

/** The on-device model (Gemini Nano). Null when it can't answer now (background, not ready, failed). */
fun interface LocalModel {
    suspend fun generate(feature: String, prompt: String, maxTokens: Int, image: Bitmap?): String?
}

/** One plain request to the cloud model (Haiku): a system prompt and one user message, text back. */
fun interface CloudModel {
    fun complete(feature: String, system: String, user: String, maxTokens: Int): String
}

val NanoModel = LocalModel { feature, prompt, max, image -> Nano.generate(feature, prompt, max, image = image) }

/**
 * Haiku 5.5 through the existing client: the same gateway (NetGate in front of OkHttp) and the same
 * monthly cap as Delphi. No tools; small replies.
 */
class HaikuModel(private val gateway: ModelGateway, private val spend: SpendGuard) : CloudModel {
    companion object {
        /** What each feature's request is for and what it declares it holds; names and coordinates are also found in the text. */
        fun outgoing(feature: String, user: String): Outgoing {
            val (purpose, declared) = when (feature) {
                "triage" -> "Triage of notification labels" to setOf(FieldCat.NOTIFICATION_LINES)
                "rambler" -> "Sorting a Parnassus capture" to setOf(FieldCat.MESSAGE_TEXT)
                "arrival" -> "Arrival text for a store" to setOf(FieldCat.PLACE_NAMES, FieldCat.LIST_ITEMS)
                "venus" -> "Outfit note wording" to emptySet()
                "asks" -> "What one message asks, and reply chips" to setOf(FieldCat.MESSAGE_TEXT)
                "plans" -> "When a chat's plan is (activity and time)" to setOf(FieldCat.MESSAGE_TEXT)
                else -> "Quick call: $feature" to setOf(FieldCat.MESSAGE_TEXT)
            }
            return Outgoing(purpose, Outbound.fieldsOf(user, declared))
        }
    }

    override fun complete(feature: String, system: String, user: String, maxTokens: Int): String {
        val o = outgoing(feature, user)
        return Outbound.tagged(o.purpose, o.fields) { send(system, user, maxTokens) }
    }

    private fun send(system: String, user: String, maxTokens: Int): String {
        spend.check()
        val model = dev.phonerecall.models.ModelChoice.brain(dev.phonerecall.models.Features.TRIAGE)
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(maxTokens.toLong())
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system).cacheControl(com.anthropic.models.messages.CacheControlEphemeral.builder().build()).build()))
            .addMessage(MessageParam.builder().role(MessageParam.Role.USER).content(user).build())
            .build()
        val msg = gateway.send(params) {}
        val u = msg.usage()
        spend.record(model, TokenUse(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L)))
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
    }
}
