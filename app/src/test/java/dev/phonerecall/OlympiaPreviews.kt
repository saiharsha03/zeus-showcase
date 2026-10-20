package dev.phonerecall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.olympia.Lineup
import dev.phonerecall.olympia.Live
import dev.phonerecall.olympia.LiveWidget
import dev.phonerecall.olympia.Match
import dev.phonerecall.olympia.Moment
import dev.phonerecall.olympia.MomentKind
import dev.phonerecall.olympia.OlympiaCards
import dev.phonerecall.olympia.OlympiaContent
import dev.phonerecall.olympia.OlympiaSettings
import dev.phonerecall.olympia.OlympiaSettingsContent
import dev.phonerecall.olympia.OlympiaSnapshot
import dev.phonerecall.olympia.Phase
import dev.phonerecall.olympia.Side
import dev.phonerecall.olympia.Sport
import dev.phonerecall.olympia.TeamContent
import dev.phonerecall.olympia.TeamInfo
import dev.phonerecall.olympia.Teams
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import java.io.File
import java.time.ZonedDateTime

/** Fixed Olympia screens for the screenshot test: a Saturday with Barça live, the Bills later, F1 on Sunday. */
object OlympiaPreviews {
    val now: ZonedDateTime = ZonedDateTime.now().withHour(16).withMinute(10).withSecond(0).withNano(0)
    private val ms = now.toInstant().toEpochMilli()
    private const val H = 3_600_000L
    private const val D = 24 * H

    private fun side(id: String, name: String, abbr: String, score: String?, home: Boolean) = Side(id, name, abbr, score, home)

    private val barca = Match("901", Sport.SOCCER, "esp.1", "LALIGA", ms - 69 * 60_000L, Phase.LIVE, "Barcelona v Sevilla",
        side("83", "Barcelona", "BAR", "2", true), side("243", "Sevilla", "SEV", "1", false), setOf("bar"), clock = "67'")
    private val bills = Match("902", Sport.NFL, "nfl", "NFL", ms + 4 * H, Phase.PRE, "Kansas City Chiefs at Buffalo Bills",
        side("2", "Bills", "BUF", null, true), side("12", "Chiefs", "KC", null, false), setOf("buf"))
    private val arsenal = Match("903", Sport.SOCCER, "eng.1", "Premier League", ms + D - 2 * H, Phase.PRE, "Arsenal v Chelsea",
        side("359", "Arsenal", "ARS", null, true), side("363", "Chelsea", "CHE", null, false), setOf("ars", "che"))
    private val quali = Match("f1:2026:18:qualifying", Sport.F1, "f1", "F1", ms + D + 2 * H, Phase.PRE, "United States Grand Prix qualifying", teams = setOf(Teams.F1), session = "Qualifying")
    private val race = Match("f1:2026:18:race", Sport.F1, "f1", "F1", ms + 2 * D + 4 * H, Phase.PRE, "United States Grand Prix", teams = setOf(Teams.F1), session = "Race")
    private val miami = Match("904", Sport.SOCCER, "usa.1", "MLS", ms + 3 * D, Phase.PRE, "Inter Miami v Nashville",
        side("20232", "Inter Miami", "MIA", null, true), side("18986", "Nashville", "NSH", null, false), setOf("mia"))
    private val betis = Match("905", Sport.SOCCER, "esp.1", "LALIGA", ms - 7 * D, Phase.DONE, "Real Betis v Barcelona",
        side("244", "Real Betis", "BET", "2", true), side("83", "Barcelona", "BAR", "5", false), setOf("bar"))
    private val raiders = Match("906", Sport.NFL, "nfl", "NFL", ms - 6 * D, Phase.DONE, "Buffalo Bills at Las Vegas Raiders",
        side("13", "Raiders", "LV", "30", true), side("2", "Bills", "BUF", "33", false), setOf("buf"))
    private val pbks = Match("907", Sport.CRICKET, "8048", "IPL", ms - 140 * D, Phase.DONE, "Punjab Kings v Gujarat Titans",
        side("335973", "PBKS", "PBKS", "201/6", true), side("1298769", "GT", "GT", "198/7", false), setOf("pbks"), result = "PBKS won by 3 wkts (5b rem)")

    private val barcaLive = Live(
        barca,
        listOf(
            Moment("g1", MomentKind.GOAL, "Goal, BAR: Lamine Yamal 12'", "12'"),
            Moment("g2", MomentKind.GOAL, "Goal, SEV: Isaac Romero 38'", "38'"),
            Moment("ht", MomentKind.HALF_TIME, "Half time, BAR 1–1 SEV", "HT"),
            Moment("r1", MomentKind.RED_CARD, "Red card, SEV: Marcão 55'", "55'"),
            Moment("g3", MomentKind.GOAL, "Goal, BAR: Robert Lewandowski 64'", "64'"),
        ),
        lineups = listOf(
            Lineup("BAR", "4-3-3", listOf("Joan García", "Koundé", "Cubarsí", "Araujo", "Balde", "Pedri", "De Jong", "Gavi", "Lamine Yamal", "Lewandowski", "Raphinha"), listOf("Szczęsny", "Olmo", "Fermín López")),
            Lineup("SEV", "4-2-3-1", listOf("Nyland", "Carmona", "Marcão", "Badé", "Pedrosa", "Agoumé", "Sow", "Lukébakio", "Saúl", "Ejuke", "Isaac Romero"), listOf("Flores", "Peque")),
        ),
        updatedAt = ms - 40_000L,
    )

    val snapshot = OlympiaSnapshot(
        matches = listOf(betis, raiders, pbks, barca, bills, arsenal, quali, race, miami),
        teams = mapOf(
            "bar" to TeamInfo("bar", "1st in Spanish LALIGA", "9-0-0"), "buf" to TeamInfo("buf", "1st in AFC East", "5-1"),
            "ars" to TeamInfo("ars", "2nd in English Premier League", "6-2-1"), "che" to TeamInfo("che", "5th in English Premier League", "5-2-2"),
            "pbks" to TeamInfo("pbks", "IPL 2026", "7-6"),
        ),
        live = mapOf(barca.id to barcaLive),
        podium = "Singapore Grand Prix" to listOf("Verstappen", "Leclerc", "Antonelli"),
        refreshedAt = ms - 25 * 60_000L, loaded = true,
    )

    @Composable fun Screen(wide: Boolean = false) = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            OlympiaContent(snapshot, now, if (wide) Space.gutterWide else Space.gutterNarrow, wide, false, {}, {}, {})
        }
    }

    @Composable fun Light() = AthenaTheme(false) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { OlympiaContent(snapshot, now, Space.gutterNarrow, false, false, {}, {}, {}) }
    }

    @Composable fun Team() = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { TeamContent(snapshot, "buf", now, Space.gutterNarrow) {} }
    }

    @Composable fun Settings() = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { OlympiaSettingsContent(OlympiaSettings(), Space.gutterNarrow, "Themis", {}) {} }
    }

    @Composable fun HomeCard() = dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = true, sports = OlympiaCards.card(snapshot, now))

    /** The home-screen widget, inflated from its RemoteViews and drawn in software. */
    fun widget(c: Context, file: File, widthPx: Int, heightPx: Int) {
        val v = LiveWidget.views(c, LiveWidget.pick(snapshot, now))
        val parent = FrameLayout(c)
        val view: View = v.apply(c, parent)
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, widthPx, heightPx)
        val bmp = Bitmap.createBitmap(widthPx + 64, heightPx + 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF6B7F8E.toInt())  // a plain wallpaper behind it
        canvas.translate(32f, 32f)
        view.draw(canvas)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
