package dev.phonerecall.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

/** Same paths as the phone's WatchPaths (app module). */
object WearPaths {
    const val TILE = "/zeus/tile"
    const val WATER = "/zeus/water"
    const val WEIGHT = "/zeus/weight"
}

/** What the phone last sent: the next event and the top task. Never health data. */
data class TileData(val event: String, val eventAt: String, val task: String) {
    companion object {
        private const val FILE = "tile"

        fun load(c: Context): TileData {
            val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return TileData(sp.getString("event", "").orEmpty(), sp.getString("eventAt", "").orEmpty(), sp.getString("task", "").orEmpty())
        }

        fun save(c: Context, d: TileData) {
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("event", d.event).putString("eventAt", d.eventAt).putString("task", d.task).apply()
        }
    }
}

/** A tap to the phone, over the Data Layer, to every connected node (the phone is the only one). */
object Phone {
    fun send(c: Context, path: String, payload: String = "") {
        val app = c.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            for (n in nodes) Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
        }
    }
}
