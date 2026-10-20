package dev.phonerecall.iaso

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Sealer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** What Iaso keeps on the phone itself: meals, his own weights (kilograms), water, one setting. */
data class IasoSaved(
    val meals: List<Meal> = emptyList(),
    val weights: List<WeightPoint> = emptyList(),
    val water: Map<LocalDate, Int> = emptyMap(),
    val share: Boolean = false,
    val nextId: Long = 1L,
)

interface IasoStorage {
    fun load(): IasoSaved
    fun save(s: IasoSaved)
}

/** SharedPreferences, sealed with an Aegis key (no fingerprint needed; never in the clear, excluded from backup). */
class SealedPrefsStorage(context: Context, private val sealer: Sealer = KeystoreSealer("zeus.aegis.health")) : IasoStorage {
    private val sp = context.getSharedPreferences("iaso", Context.MODE_PRIVATE)

    override fun load(): IasoSaved = sp.getString("s", null)?.let { sealer.open(it) }?.let { runCatching { IasoCodec.decode(it) }.getOrNull() } ?: IasoSaved()

    override fun save(s: IasoSaved) { sp.edit().putString("s", sealer.seal(IasoCodec.encode(s))).apply() }
}

internal object IasoCodec {
    private val m = ObjectMapper()

    fun encode(s: IasoSaved): String {
        val o = m.createObjectNode()
        o.putArray("meals").apply {
            for (x in s.meals) addObject().put("id", x.id).put("at", x.at).put("ty", x.type.name).put("d", x.dish).put("p", x.portion)
                .put("k", x.kcal).put("pr", x.proteinG).put("c", x.carbsG).put("f", x.fatG).put("hc", x.inHc).put("ph", x.fromPhoto)
        }
        o.putArray("w").apply { for (w in s.weights) addObject().put("at", w.at).put("kg", w.kg) }
        val water: ObjectNode = o.putObject("wa")
        // Water older than two weeks is of no use to anyone.
        val cutoff = LocalDate.now().minusDays(14)
        for ((d, n) in s.water) if (!d.isBefore(cutoff)) water.put(d.toString(), n)
        o.put("sh", s.share).put("n", s.nextId)
        return m.writeValueAsString(o)
    }

    fun decode(json: String): IasoSaved {
        val o = m.readTree(json)
        val meals = o.path("meals").mapNotNull { x ->
            runCatching {
                Meal(
                    x.path("id").asLong(), x.path("at").asLong(), MealType.valueOf(x.path("ty").asText()), x.path("d").asText(), x.path("p").asText(),
                    x.path("k").asInt(), x.path("pr").asDouble(), x.path("c").asDouble(), x.path("f").asDouble(), x.path("hc").asBoolean(), x.path("ph").asBoolean(),
                )
            }.getOrNull()
        }
        val weights = o.path("w").map { WeightPoint(it.path("at").asLong(), it.path("kg").asDouble(), mine = true) }
        val water = o.path("wa").fields().asSequence().mapNotNull { e -> runCatching { LocalDate.parse(e.key) to e.value.asInt() }.getOrNull() }.toMap()
        return IasoSaved(meals, weights, water, o.path("sh").asBoolean(false), o.path("n").asLong(1L).coerceAtLeast(1L))
    }
}

/**
 * Iaso's logic, apart from Android: Health Connect reads, the food and weight logs, water, and what the
 * brain may hear. Every write to Health Connect happens only after his tap (Save, Log), never from a model.
 */
class IasoHub(
    private val storage: IasoStorage,
    /** Null when Health Connect isn't usable; then [sdk] says why. */
    private val hc: () -> HcClient?,
    private val sdk: () -> HcStatus?,
    private val estimator: () -> FoodEstimator?,
    private val ownPackage: String,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val _state = MutableStateFlow(IasoState())
    val state: StateFlow<IasoState> = _state.asStateFlow()
    private var saved = IasoSaved()
    private val lock = Any()

    fun load() = synchronized(lock) {
        if (_state.value.loaded) return
        saved = storage.load()
        _state.update { it.copy(meals = saved.meals, localWeights = saved.weights, water = saved.water, shareWithBrain = saved.share, loaded = true) }
    }

    private fun change(f: (IasoSaved) -> IasoSaved) = synchronized(lock) {
        load()
        saved = f(saved)
        storage.save(saved)
        _state.update { it.copy(meals = saved.meals, localWeights = saved.weights, water = saved.water, shareWithBrain = saved.share) }
    }

    private suspend fun granted(c: HcClient): Set<String> = runCatching { c.granted() }.getOrDefault(emptySet())

    /** Reads Health Connect. Not installed, not permitted or failing: the state says so and the logs still work. */
    suspend fun refresh() {
        load()
        val why = sdk()
        val c = if (why == null) hc() else null
        if (c == null) { _state.update { it.copy(status = why ?: HcStatus.NOT_INSTALLED, reading = false) }; return }
        _state.update { it.copy(reading = true, error = null) }
        val g = granted(c)
        val status = HcPermissions.status(g)
        if (status == HcStatus.NOT_PERMITTED) { _state.update { it.copy(status = status, reading = false) }; return }
        val r = runCatching { HcMapping.report(c, g, now(), ownPackage) }
        _state.update { s ->
            r.fold(
                { s.copy(status = status, report = it, reading = false) },
                { s.copy(status = status, reading = false, error = "Couldn't read Health Connect just now.") },
            )
        }
        syncPending(c, g)
    }

    /** Meals and weights saved while Health Connect was unavailable, written once it is. */
    private suspend fun syncPending(c: HcClient, g: Set<String>) {
        val zone = now().zone
        val nutrition = HcPermissions.WRITE_NUTRITION in g
        if (nutrition) {
            val pending = saved.meals.filter { !it.inHc }
            if (pending.isNotEmpty() && runCatching { c.insert(pending.map { HcMapping.nutritionRecord(it, zone) }) }.isSuccess) {
                val ids = pending.map { it.id }.toSet()
                change { s -> s.copy(meals = s.meals.map { if (it.id in ids) it.copy(inHc = true) else it }) }
            }
        }
    }

    /** Photo to a draft for the confirm sheet. Nothing is saved here. Blocking. */
    fun estimate(jpeg: ByteArray, hint: String = ""): Result<MealDraft> {
        val e = estimator() ?: return Result.failure(IllegalStateException("Add your Anthropic key in Calypso first."))
        return runCatching { e.estimate(Exif.strip(jpeg), hint) }.mapCatching { est ->
            est?.let { MealDraft.of(it, now()) } ?: throw IllegalStateException("Claude couldn't see a meal in that photo.")
        }
    }

    /** His Save on the confirm sheet: the only way a meal is kept. Then Health Connect, if allowed. */
    suspend fun confirm(draft: MealDraft): Meal? {
        load()
        var meal: Meal? = null
        change { s ->
            val m = draft.copy(at = if (draft.at == 0L) now().toInstant().toEpochMilli() else draft.at).toMeal(s.nextId) ?: return@change s
            meal = m
            s.copy(meals = (s.meals + m).takeLast(400), nextId = s.nextId + 1)
        }
        val m = meal ?: return null
        val c = if (sdk() == null) hc() else null
        if (c != null && HcPermissions.WRITE_NUTRITION in granted(c)) {
            if (runCatching { c.insert(listOf(HcMapping.nutritionRecord(m, now().zone))) }.isSuccess) {
                change { s -> s.copy(meals = s.meals.map { if (it.id == m.id) it.copy(inHc = true) else it }) }
                return m.copy(inHc = true)
            }
        }
        return m
    }

    fun deleteMeal(id: Long) = change { s -> s.copy(meals = s.meals.filter { it.id != id }) }

    /** A weight he typed (or logged on the watch). Kept locally and written to Health Connect when allowed. */
    suspend fun logWeight(kg: Double, at: Long = now().toInstant().toEpochMilli()): Boolean {
        if (kg !in 25.0..300.0) return false
        val p = WeightPoint(at, kg, mine = true)
        change { s -> s.copy(weights = (s.weights + p).takeLast(1000)) }
        val c = if (sdk() == null) hc() else null
        if (c != null && HcPermissions.WRITE_WEIGHT in granted(c)) {
            if (runCatching { c.insert(listOf(HcMapping.weightRecord(p, now().zone))) }.isSuccess) {
                _state.update { it.copy(report = it.report.copy(weights = (it.report.weights + p).sortedBy { w -> w.at })) }
            }
        }
        return true
    }

    fun addWater(delta: Int = 1) {
        val d = now().toLocalDate()
        change { s -> s.copy(water = s.water + (d to ((s.water[d] ?: 0) + delta).coerceIn(0, 40))) }
    }

    fun setShare(on: Boolean) = change { it.copy(share = on) }
}

/** The process-wide hub: the screen, the Olympus card, the watch listener and the brain's one line share it. */
object Iaso {
    @Volatile private var hub: IasoHub? = null

    fun get(c: Context): IasoHub = hub ?: synchronized(this) {
        hub ?: run {
            val app = c.applicationContext
            val core = dev.phonerecall.brain.BrainCore.get(app)
            IasoHub(
                SealedPrefsStorage(app),
                hc = { RealHc.of(app) },
                sdk = { RealHc.sdk(app) },
                estimator = { if (core.vault.has()) ClaudeFoodEstimator(core.gateway, core.delphiSpend) else null },
                ownPackage = app.packageName,
            ).also { it.load(); hub = it }
        }
    }

    /** Only if Iaso is already running in this process; never starts it. */
    fun peek(): IasoHub? = hub

    /** For previews and tests. */
    fun set(h: IasoHub?) { hub = h }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
