package dev.phonerecall.mind

import com.fasterxml.jackson.core.type.TypeReference

/** The mind's small persistent state: ids, cursors, queues. SharedPreferences on the phone, a map in tests. */
interface Kv {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MapKv : Kv {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class PrefsKv(context: android.content.Context, name: String = "mind") : Kv {
    private val sp = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit() }
}

/**
 * A plain Jackson mapper for the mind's own small records (Kotlin data classes with defaults). The SDK's
 * mapper is tuned for its own model classes and does not fill final fields; this one does.
 */
val stateJson: com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper()
    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(com.fasterxml.jackson.databind.MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, true)

private val json = stateJson

fun Kv.long(key: String): Long? = get(key)?.toLongOrNull()
fun Kv.putLong(key: String, v: Long) = put(key, v.toString())

fun <T> Kv.list(key: String, type: TypeReference<List<T>>): List<T> = get(key)?.let { runCatching { json.readValue(it, type) }.getOrNull() }.orEmpty()
fun <T> Kv.putList(key: String, items: List<T>) = put(key, json.writeValueAsString(items))

fun Kv.strings(key: String): List<String> = list(key, object : TypeReference<List<String>>() {})
fun Kv.putStrings(key: String, items: List<String>) = putList(key, items)

fun Kv.map(key: String): Map<String, Long> = get(key)?.let { runCatching { json.readValue(it, object : TypeReference<Map<String, Long>>() {}) }.getOrNull() }.orEmpty()
fun Kv.putMap(key: String, m: Map<String, Long>) = put(key, json.writeValueAsString(m))

/** One battery-relevant wakeup: when, what ran, how long it took. Shown in Themis so the cost is measurable. */
data class Wakeup(val at: Long = 0, val what: String = "", val ms: Long = 0, val calls: Int = 0)

object WakeLog {
    private const val KEY = "wake.log"
    private val type = object : TypeReference<List<Wakeup>>() {}

    fun add(kv: Kv, w: Wakeup) {
        kv.putList(KEY, (listOf(w) + kv.list(KEY, type)).take(60))
        dev.phonerecall.clio.Clio.info("mind", "wakeup", "${w.what} · ${w.ms} ms · ${w.calls} calls")
    }
    fun all(kv: Kv): List<Wakeup> = kv.list(KEY, type)
    fun today(kv: Kv, zone: java.time.ZoneId, now: Long = System.currentTimeMillis()): List<Wakeup> {
        val start = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return all(kv).filter { it.at >= start }
    }
}
