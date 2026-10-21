package dev.phonerecall.models

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.list
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.strings
import dev.phonerecall.mind.putStrings
import dev.phonerecall.prometheus.PrometheusPrompts
import java.time.OffsetDateTime

/*
 * Model choice as data. Every feature that calls a model reads its model here; the defaults are
 * the choices that used to be written into the code. Anthropic's live list (GET /v1/models) is read on each mind
 * sync and cached. A change is his: Horologion's Models picker with his fingerprint, or the one card Athena posts
 * when a newer or cheaper model is out. Managed agents pick a change up on the next sync (a new agent version);
 * Messages API features on their next call.
 */

/** What a feature needs from its model. */
enum class Need {
    /** Runs as a Managed Agent: any model the account lists. */
    AGENT,
    /** The advisor slot (Metis): only models the price table marks advisor-capable. */
    ADVISOR,
    /** A Messages API call the spend ledger meters on the phone: only models with a [BrainModel] price. */
    METERED,
}

data class Feature(val key: String, val label: String, val default: String, val need: Need)

object Features {
    val ATHENA = Feature("athena", "Athena's mind", "claude-sonnet-5-5", Need.AGENT)
    val METIS = Feature("metis", "Metis (advisor)", Metis.MODEL, Need.ADVISOR)
    /** The old phone-side chat's bigger turns. The chat is Athena herself now, so it is no longer offered in a picker. */
    val DELPHI = Feature("delphi", "Phone chat, bigger turns", BrainModel.SONNET.id, Need.METERED)
    val TRIAGE = Feature("triage", "Background triage", BrainModel.HAIKU.id, Need.METERED)
    val REFLECT = Feature("reflect", "Reflect", BrainModel.HAIKU.id, Need.METERED)
    val HUMANIZER = Feature("humanizer", "Humanizer polish", BrainModel.HAIKU.id, Need.METERED)
    val PROMETHEUS = Feature("prometheus", "Prometheus", PrometheusPrompts.DEFAULT_MODEL, Need.AGENT)
    /** Assess mode: a read-only look, so a cheaper model is fine. */
    val PROMETHEUS_ASSESS = Feature("prometheus.assess", "Prometheus, assessments", "claude-sonnet-5-5", Need.AGENT)

    fun protege(p: Protege) = Feature("protege.${p.name.lowercase()}", p.display, p.model, Need.AGENT)

    val ALL: List<Feature> = listOf(ATHENA) + Protege.entries.map { protege(it) } + listOf(METIS, TRIAGE, REFLECT, HUMANIZER, PROMETHEUS, PROMETHEUS_ASSESS)

    fun of(key: String): Feature? = ALL.firstOrNull { it.key == key }
}

/** One model from Anthropic's list. `created` is epoch ms. */
data class ModelEntry(val id: String = "", val name: String = "", val created: Long = 0, val deprecated: Boolean = false)

/**
 * List prices in dollars per million tokens, and whether the model can be an advisor. The Messages API ones come
 * from [BrainModel] (the ledger's own prices); add a model here and to BrainModel when one is out
 * (docs/claude-api/models.md). Prometheus may update this table in a PR.
 */
data class Price(val id: String, val input: Double, val output: Double, val advisor: Boolean = false) {
    val blended: Double get() = input + output
}

object Prices {
    val TABLE: List<Price> = BrainModel.entries.map { Price(it.id, it.input, it.output) } + Price(Metis.MODEL, Metis.INPUT, Metis.OUTPUT, advisor = true)

    fun of(id: String): Price? = TABLE.firstOrNull { it.id == id }
}

object ModelList {
    /** GET /v1/models's body: {"data": [{"id", "display_name", "created_at", "deprecated_at"?}], ...}. */
    fun parse(n: JsonNode): List<ModelEntry> = n.path("data").mapNotNull { m ->
        val id = m.path("id").asText().takeIf { it.startsWith("claude-") } ?: return@mapNotNull null
        ModelEntry(
            id, m.path("display_name").asText().ifEmpty { id },
            runCatching { OffsetDateTime.parse(m.path("created_at").asText()).toInstant().toEpochMilli() }.getOrDefault(0L),
            m.path("deprecated_at").let { it.isTextual && it.asText().isNotEmpty() },
        )
    }

    /** A model's line: "claude-sonnet-5-5" -> "sonnet". */
    fun family(id: String): String = id.removePrefix("claude-").substringBefore('-')
}

object Picker {
    /**
     * The models a feature may switch to: listed by Anthropic, not deprecated, and able to do what the feature needs.
     * The current choice and the default are always offered so Undo and Reset stay possible. Before the first sync
     * (no list yet) the known table stands in for it.
     */
    fun options(f: Feature, live: List<ModelEntry>, current: String): List<String> {
        val known = live.ifEmpty { Prices.TABLE.map { ModelEntry(it.id, it.id) } }.filter { !it.deprecated }.map { it.id }
        val fits = known.filter { id ->
            when (f.need) {
                Need.AGENT -> true
                Need.ADVISOR -> Prices.of(id)?.advisor == true
                Need.METERED -> BrainModel.of(id) != null
            }
        }
        return (fits + current + f.default).distinct()
    }
}

/** A newer or cheaper model that fits one feature better than its current one. */
data class Upgrade(val model: String, val name: String, val feature: Feature, val why: String) {
    val title: String get() = "$name is out"
    val body: String get() = "$name is out: $why for ${feature.label}. Switch?"
}

object Upgrades {
    /**
     * The first model not seen before that beats what some feature uses: a newer model of the same line, or the
     * same capability for less. Each model is suggested once, whatever the answer (the caller marks it seen).
     */
    fun find(live: List<ModelEntry>, current: (Feature) -> String, seen: Set<String>): Upgrade? {
        val byId = live.associateBy { it.id }
        for (m in live.filter { !it.deprecated && it.id !in seen }.sortedByDescending { it.created }) {
            for (f in Features.ALL) {
                val now = current(f)
                if (m.id == now || m.id !in Picker.options(f, live, now)) continue
                val was = byId[now]
                val newer = was != null && ModelList.family(m.id) == ModelList.family(now) && m.created > was.created
                val pm = Prices.of(m.id)
                val pn = Prices.of(now)
                val cheaper = pm != null && pn != null && ModelList.family(m.id) == ModelList.family(now) && pm.blended < pn.blended
                when {
                    newer && cheaper -> return Upgrade(m.id, m.name, f, "newer than ${was!!.name} and cheaper")
                    newer -> return Upgrade(m.id, m.name, f, "newer than ${was!!.name}, in the same line")
                    cheaper -> return Upgrade(m.id, m.name, f, "the same line for less ($${fmt(pm!!.input)}/$${fmt(pm.output)} per million tokens vs $${fmt(pn!!.input)}/$${fmt(pn.output)})")
                }
            }
        }
        return null
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) "${d.toLong()}" else "$d"
}

/**
 * The choices, on the phone. `set` keeps the one before so Undo puts it back. Reads are cheap (one map in memory),
 * so every call site asks here each time.
 */
class ModelBook(private val kv: Kv) {
    private val entries = object : TypeReference<List<ModelEntry>>() {}

    fun of(f: Feature): String = kv.get("model.${f.key}") ?: f.default

    /** Sets a model and returns the one it replaced. */
    fun set(f: Feature, id: String): String {
        val before = of(f)
        kv.put("model.${f.key}", id.takeIf { it != f.default })
        kv.put("undo.${f.key}", before)
        return before
    }

    fun undoable(f: Feature): String? = kv.get("undo.${f.key}")?.takeIf { it != of(f) }

    fun undo(f: Feature): Boolean {
        val back = undoable(f) ?: return false
        kv.put("model.${f.key}", back.takeIf { it != f.default })
        kv.put("undo.${f.key}", null)
        return true
    }

    fun live(): List<ModelEntry> = kv.list("live", entries)

    fun saveLive(models: List<ModelEntry>) { if (models.isNotEmpty()) kv.putList("live", models) }

    fun seen(): Set<String> = kv.strings("seen").toSet()

    fun markSeen(id: String) = kv.putStrings("seen", (kv.strings("seen") + id).distinct().takeLast(200))

    /** The first sync only learns the list: the models out then are what he already chose from. */
    fun firstLook(models: List<ModelEntry>) { if (kv.get("seen") == null) kv.putStrings("seen", models.map { it.id }) }

    fun options(f: Feature): List<String> = Picker.options(f, live(), of(f))
}

/** The process's book. Installed with SharedPreferences at startup; a map until then (and in tests). */
object ModelChoice {
    @Volatile var book: ModelBook = ModelBook(MapKv())

    fun of(f: Feature): String = book.of(f)

    /** A metered feature's model, as the ledger knows it. */
    fun brain(f: Feature): BrainModel = BrainModel.of(book.of(f)) ?: BrainModel.of(f.default)!!
}
