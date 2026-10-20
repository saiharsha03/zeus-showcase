package dev.phonerecall.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** The phone put new tile data: keep it and redraw the tile. */
class WatchListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.dataItem.uri.path != WearPaths.TILE) continue
            val m = DataMapItem.fromDataItem(e.dataItem).dataMap
            TileData.save(this, TileData(m.getString("event").orEmpty(), m.getString("eventAt").orEmpty(), m.getString("task").orEmpty()))
        }
        TileService.getUpdater(this).requestUpdate(ZeusTileService::class.java)
    }
}
