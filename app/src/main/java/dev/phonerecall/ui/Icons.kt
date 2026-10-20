package dev.phonerecall.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Athena's line icons: one 24 dp grid, one 1.6 stroke, round caps. Tinted by the caller. */
object AthenaIcons {
    private fun line(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Home = line("home", "M4 10.5 L12 4 L20 10.5", "M6 9 V20 H18 V9", "M10 20 V14.5 H14 V20")
    val Agents = line("agents", "M4 5 H20 V16 H11.5 L7.5 19.5 V16 H4 Z", "M8 9.5 H16", "M8 12.5 H13")
    /** Cerberus: a shield with a mark. */
    val Alerts = line("alerts", "M12 3.5 L19 6.5 V11.5 C19 15.5 16 18.8 12 20.5 C8 18.8 5 15.5 5 11.5 V6.5 Z", "M12 8.5 V12.5", "M12 15.5 V16")
    val Server = line("server", "M4 4.5 H20 V10.5 H4 Z", "M4 13.5 H20 V19.5 H4 Z", "M7.5 7.5 H10", "M7.5 16.5 H10")
    val Weather = line("weather", "M3 17 H21", "M6.5 17 A5.5 5.5 0 0 1 17.5 17", "M12 5 V7.5", "M5.3 8.3 L7 10", "M18.7 8.3 L17 10", "M8 20.5 H16")
    val Devices = line(
        "devices",
        "M9.5 17.5 V15.6 C9.5 14.8 9.1 14.1 8.5 13.5 A6 6 0 1 1 15.5 13.5 C14.9 14.1 14.5 14.8 14.5 15.6 V17.5 Z",
        "M10 20.5 H14",
    )
    val Settings = line("settings", "M4 7 H20", "M4 17 H20", "M9 4.5 V9.5", "M15 14.5 V19.5")
    val Calendar = line("calendar", "M4 6 H20 V20 H4 Z", "M4 10.5 H20", "M8.5 3.5 V7.5", "M15.5 3.5 V7.5")
    val Tasks = line("tasks", "M4 7.5 L6 9.5 L9.5 6", "M12.5 8 H20", "M4 15.5 L6 17.5 L9.5 14", "M12.5 16 H20")
    val Closet = line("closet", "M10.2 6 A1.8 1.8 0 1 1 12 7.8 V9", "M12 9 L3.5 16 H20.5 Z")
    val More = line("more", "M4.5 4.5 H10 V10 H4.5 Z", "M14 4.5 H19.5 V10 H14 Z", "M4.5 14 H10 V19.5 H4.5 Z", "M14 14 H19.5 V19.5 H14 Z")
    val Back = line("back", "M14.5 5.5 L8 12 L14.5 18.5")
    /** Birthday: a small cake, drawn as a line icon (never an emoji). */
    val Cake = line("cake", "M5 20 H19 V13 H5 Z", "M5 16.2 C7 17.6 9 14.8 12 16.2 C15 17.6 17 14.8 19 16.2", "M12 13 V10.2", "M12 6.5 C11 7.6 11.3 9.2 12 9.2 C12.7 9.2 13 7.6 12 6.5")
    val Plane = line("plane", "M3 13 L21 7.5", "M10.5 10.8 L7.5 4.5 L9.2 4 L14.2 9.2", "M12.5 12.3 L13.5 19.5 L11.8 20 L9.5 13.2", "M3 13 L5.5 15.5 L7.5 14.8")
    /** Out of office: the sun sitting on the horizon. */
    val Off = line("off", "M3.5 16.5 H20.5", "M7 16.5 A5 5 0 0 1 17 16.5", "M12 6.5 V8.5", "M5.6 9.6 L7 11", "M18.4 9.6 L17 11")
    /** One event, as a short stroke. */
    val Mark = line("mark", "M7 12 H17")
    /** A garment outline, for closet items with no picture yet (never an invented image). */
    val Garment = line("garment", "M8.5 4 L4 7 L6 10.5 L7.5 9.5 V20 H16.5 V9.5 L18 10.5 L20 7 L15.5 4 C14.8 5.4 13.5 6.2 12 6.2 C10.5 6.2 9.2 5.4 8.5 4 Z")
    /** Delphi: Athena's owl as a line icon. */
    val Oracle = line(
        "oracle",
        "M6 20 C6 12.5 7 7.5 9 5 L10.4 7.4 C11.4 7 12.6 7 13.6 7.4 L15 5 C17 7.5 18 12.5 18 20 Z",
        "M7.8 12.2 A1.7 1.7 0 1 0 11.2 12.2 A1.7 1.7 0 1 0 7.8 12.2 Z",
        "M12.8 12.2 A1.7 1.7 0 1 0 16.2 12.2 A1.7 1.7 0 1 0 12.8 12.2 Z",
    )
    /** Fates: a card with a tick, for proposals. */
    val Fates = line("fates", "M4 5 H20 V19 H4 Z", "M8.5 12 L11 14.5 L15.5 9.5")
    /** Calypso: a lock. */
    val Vault = line("vault", "M6 11 H18 V20 H6 Z", "M8.5 11 V8 A3.5 3.5 0 0 1 15.5 8 V11", "M12 14.5 V16.5")
    /** Moneta: a coin. */
    val Coin = line("coin", "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z", "M14.5 9 C14 8 13 7.5 12 7.5 C10.5 7.5 9.5 8.3 9.5 9.5 C9.5 12 14.5 11 14.5 14 C14.5 15.3 13.4 16.5 12 16.5 C10.8 16.5 9.8 15.9 9.3 15", "M12 6 V7.5", "M12 16.5 V18")
    /** Aegis: a round shield with a boss. */
    val Shield = line("shield", "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z", "M12 9.5 A2.5 2.5 0 1 1 11.99 9.5 Z")
    val Check = line("check", "M5 12.5 L10 17 L19 7.5")
    val Plus = line("plus", "M12 5.5 V18.5", "M5.5 12 H18.5")
    val Drag = line("drag", "M6 9.5 H18", "M6 14.5 H18")
    /** Parnassus: a quill. */
    val Quill = line("quill", "M19.5 4 C12.5 5 7.5 10.5 6 18", "M6 18 L4.5 20.5", "M19.5 4 C19 9.5 14.5 13.5 8.5 14.5", "M11 11.5 L16 9")
    /** Harmonia (People): two heads. */
    val People = line(
        "people",
        "M6 8 A3 3 0 1 0 12 8 A3 3 0 1 0 6 8 Z", "M3.5 19.5 C3.5 15.5 6 13.5 9 13.5 C12 13.5 14.5 15.5 14.5 19.5",
        "M15.5 5.6 A2.6 2.6 0 1 1 15.5 10.6", "M16.5 13.6 C19 14 20.5 16 20.5 19.5",
    )
    /** Lethe: the river. */
    val River = line("river", "M3 9 C5 7 7 7 9 9 C11 11 13 11 15 9 C17 7 19 7 21 9", "M3 15 C5 13 7 13 9 15 C11 17 13 17 15 15 C17 13 19 13 21 15")
    /** A dynamic section: a short list. */
    val Laurel = line(
        "laurel",
        "M8 19.5 C4.2 16.5 3.8 9.5 7.5 5", "M16 19.5 C19.8 16.5 20.2 9.5 16.5 5",
        "M5.4 14.8 L3.8 13.6", "M5 11 L3.6 9.6", "M6.2 7.5 L5.2 5.8",
        "M18.6 14.8 L20.2 13.6", "M19 11 L20.4 9.6", "M17.8 7.5 L18.8 5.8", "M9.5 20 H14.5",
    )
    val Search = line("search", "M10.5 4 A6.5 6.5 0 1 1 10.49 4 Z", "M15.3 15.3 L20 20")
    val List = line("list", "M8.5 7 H20", "M8.5 12 H20", "M8.5 17 H20", "M4 7 H5.5", "M4 12 H5.5", "M4 17 H5.5")
}
