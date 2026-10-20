package dev.phonerecall.iaso

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Galaxy Watch's tile shows: the next event and the top task, nothing else. No health data
 * ever goes to the watch; the watch only sends taps back ("a glass of water", "this weight").
 * The paths are shared with the :wear module (WearPaths there).
 */
object WatchPaths {
    const val TILE = "/zeus/tile"
    const val WATER = "/zeus/water"
    const val WEIGHT = "/zeus/weight"
}

object WatchPayload {
    /** The only keys the phone ever puts on the watch. */
    val KEYS = setOf("event", "eventAt", "task", "at")
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun of(events: List<CalItem>, tasks: TaskSnapshot, now: ZonedDateTime): Map<String, String> {
        val ms = now.toInstant().toEpochMilli()
        val next = events.filter { !it.allDay && it.end > ms }.minByOrNull { it.begin }
        val top = tasks.open().filter { !it.sample && it.list != TaskList.SOMEDAY && it.list != TaskList.WAITING }
            .sortedWith(compareBy<dev.phonerecall.data.Task>({ it.due ?: Long.MAX_VALUE }, { it.sort })).firstOrNull()
        return buildMap {
            put("event", next?.title?.take(40) ?: "")
            put("eventAt", next?.let { if (it.begin <= ms) "Now" else time.format(Instant.ofEpochMilli(it.begin).atZone(now.zone)) } ?: "")
            put("task", top?.title?.take(40) ?: "")
            put("at", ms.toString())
        }
    }
}

object WatchBridge {
    @Volatile private var last: Map<String, String>? = null

    /** Puts the tile's data item. Skipped when nothing but the timestamp changed; no-op without Play services. */
    fun push(c: Context, payload: Map<String, String>) {
        require(payload.keys.all { it in WatchPayload.KEYS })
        if (last?.filterKeys { it != "at" } == payload.filterKeys { it != "at" }) return
        last = payload
        runCatching {
            val req = PutDataMapRequest.create(WatchPaths.TILE).apply { for ((k, v) in payload) dataMap.putString(k, v) }
            Wearable.getDataClient(c.applicationContext).putDataItem(req.asPutDataRequest().setUrgent())
        }
    }
}

/** The watch's taps, delivered by Play services. Water stays on the phone; a weight goes through [IasoHub.logWeight]. */
class PhoneWearListener : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val hub = Iaso.get(this)
        when (e.path) {
            WatchPaths.WATER -> hub.addWater(1)
            WatchPaths.WEIGHT -> e.data.toString(Charsets.UTF_8).toDoubleOrNull()?.let { kg -> runBlocking { hub.logWeight(kg) } }
        }
    }
}
