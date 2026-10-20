package dev.phonerecall.vault

import com.anthropic.core.jsonMapper

/**
 * The vault tools Delphi and Athena's mind get. Calypso: deposit only, answered with "stored" and an
 * opaque id. Aegis: list names and read one value with a reason, both logged. There is no tool that
 * lists, opens or moves anything in Calypso, and none that edits Aegis.
 */
class VaultTools(private val drop: CalypsoDrop, private val aegis: AegisReads) {
    private val json = jsonMapper()

    /** Text for the model, and whether it is an error. Never Calypso content. */
    fun run(name: String, input: Map<String, Any?>, who: Agent): Pair<String, Boolean> = try {
        when (name) {
            DEPOSIT -> {
                val value = input["value"]?.toString().orEmpty()
                val kind = ItemKind.of(input["kind"]?.toString()) ?: ItemKind.SECRET
                if (value.isEmpty() || kind == ItemKind.FILE) "value is missing (files come through Lemnos or the app)" to true
                else {
                    val id = drop.deposit(who, kind, input["label"]?.toString().orEmpty(), value.toByteArray(), null)
                    json.writeValueAsString(mapOf("stored" to true, "id" to id, "note" to "Sealed in Calypso. Nobody but the owner can open it, you included. Keep no copy of the value.")) to false
                }
            }
            AEGIS_LIST -> json.writeValueAsString(mapOf("items" to aegis.agentList(who).map { mapOf("name" to it.name, "about" to it.note) })) to false
            AEGIS_READ -> when (val r = aegis.agentRead(input["name"]?.toString().orEmpty(), who, input["why"]?.toString().orEmpty())) {
                is AegisResult.Value -> json.writeValueAsString(mapOf("name" to r.name, "value" to r.value, "note" to "Logged for the owner. Use it for this task only; don't store it.")) to false
                is AegisResult.Refused -> r.reason to true
            }
            else -> "Unknown tool $name" to true
        }
    } catch (e: Exception) {
        "Tool $name failed: ${e.javaClass.simpleName}" to true
    }

    companion object {
        const val DEPOSIT = "calypso_deposit"
        const val AEGIS_LIST = "aegis_list"
        const val AEGIS_READ = "aegis_read"
        val NAMES = setOf(DEPOSIT, AEGIS_LIST, AEGIS_READ)

        const val DEPOSIT_TEXT = "Seal something sensitive in Calypso, the owner's fingerprint vault: a key, token, ID number or private note you came across. " +
            "It is write-only: once stored, nobody but the owner can open it, you included. You get back only an id. Prefer this to repeating a secret in chat."
        const val LIST_TEXT = "The names of Aegis items you may use (his delivery address, loyalty numbers, Wi-Fi details, sizes and so on), with a short note each. No values."
        const val READ_TEXT = "Read one Aegis item's value to use it for the task at hand. Every read is logged for the owner with who, when and your reason. Agents can't change Aegis items."
    }
}
