package dev.phonerecall.iaso

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass

val NY: ZoneId = ZoneId.of("America/New_York")
/** 20 October 2026, 2:30 PM in Buffalo. */
val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 20, 14, 30, 0, 0, NY)

fun at(daysAgo: Long, h: Int, m: Int = 0): Instant = NOW.toLocalDate().minusDays(daysAgo).atTime(h, m).atZone(NY).toInstant()
fun off(i: Instant) = NY.rules.getOffset(i)
/** Records as another app wrote them. Only Health Connect sets a data origin, so the fake reaches the (internal) constructor. */
fun from(pkg: String): Metadata = Metadata::class.java.getDeclaredConstructor(
    Int::class.javaPrimitiveType, String::class.java, DataOrigin::class.java, Instant::class.java, String::class.java, Long::class.javaPrimitiveType,
    androidx.health.connect.client.records.metadata.Device::class.java,
).apply { isAccessible = true }.newInstance(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, "id-${System.nanoTime()}", DataOrigin(pkg), Instant.EPOCH, null, 0L, null)

const val SAMSUNG = "com.sec.android.app.shealth"
const val FIT = "com.google.android.apps.fitness"

/** Health Connect in memory: records by type, a granted set, and every insert kept. */
class FakeHc(var granted: Set<String> = HcPermissions.ALL, val records: MutableList<Record> = mutableListOf()) : HcClient {
    val inserted = ArrayList<Record>()
    val reads = ArrayList<KClass<*>>()
    var failInsert = false

    override suspend fun granted() = granted

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T> {
        reads += type
        return records.filter { type.isInstance(it) }.map { it as T }.filter { r ->
            val t: Instant = (runCatching { r.javaClass.getMethod("getStartTime") }.getOrElse { r.javaClass.getMethod("getTime") }.invoke(r) as Instant)
            !t.isBefore(from) && t.isBefore(to)
        }
    }

    override suspend fun insert(records: List<Record>) {
        if (failInsert) throw IllegalStateException("HC down")
        inserted += records
        this.records += records
    }
}

class MemoryStorage(var saved: IasoSaved = IasoSaved()) : IasoStorage {
    var saves = 0
    override fun load() = saved
    override fun save(s: IasoSaved) { saved = s; saves++ }
}

fun hub(storage: IasoStorage = MemoryStorage(), hc: FakeHc? = FakeHc(), sdk: HcStatus? = null, estimator: FoodEstimator? = null) =
    IasoHub(storage, { hc }, { sdk }, { estimator }, "dev.phonerecall", now = { NOW })
