package dev.phonerecall.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * Zeus's tile: the next event, the top task, and two buttons. "Water" sends one glass to the phone and
 * redraws; "Weight" opens [WeightActivity]. Colours are Zeus's: saffron on near-black.
 */
class ZeusTileService : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val sentWater = req.currentState.lastClickableId == WATER
        if (sentWater) Phone.send(this, WearPaths.WATER)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES)
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(TileData.load(this), sentWater)))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion(RES).build()); "res" }

    private fun text(s: String, size: Float, color: Int, lines: Int = 1, bold: Boolean = false) = LayoutElementBuilders.Text.Builder()
        .setText(s).setMaxLines(lines)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
        )
        .build()

    private fun button(label: String, click: ModifiersBuilders.Clickable) = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(68f)).setHeight(dp(40f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(click)
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(SAFFRON)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build()).build())
                .build(),
        )
        .addContent(text(label, 13f, INK_ON_SAFFRON, bold = true))
        .build()

    private fun layout(d: TileData, sentWater: Boolean): LayoutElementBuilders.LayoutElement {
        val water = ModifiersBuilders.Clickable.Builder().setId(WATER).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
        val weight = ModifiersBuilders.Clickable.Builder().setId("weight").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WeightActivity::class.java.name).build(),
            ).build(),
        ).build()
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(if (d.event.isEmpty()) "NOTHING NEXT" else d.eventAt.uppercase().ifEmpty { "NEXT" }, 11f, SAFFRON))
            .addContent(text(d.event.ifEmpty { "Open Zeus on the phone" }, 15f, INK, lines = 2, bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(if (d.task.isEmpty()) "No task up next" else "Task: ${d.task}", 12f, INK2))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(button(if (sentWater) "+1 sent" else "Water", water))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(button("Weight", weight))
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col).build()
    }

    companion object {
        private const val RES = "1"
        private const val WATER = "water"
        private const val SAFFRON = 0xFFE8A33D.toInt()
        private const val INK_ON_SAFFRON = 0xFF1B1A17.toInt()
        private const val INK = 0xFFF2EEE6.toInt()
        private const val INK2 = 0xFFB9B3A8.toInt()
    }
}
