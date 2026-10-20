package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import dev.phonerecall.moneta.Categories
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.Cycle
import dev.phonerecall.moneta.MoneyActionKind
import dev.phonerecall.moneta.MoneyActions
import dev.phonerecall.moneta.MonetaCalls
import dev.phonerecall.moneta.MonetaContent
import dev.phonerecall.moneta.MonetaState
import dev.phonerecall.moneta.ReconcileSection
import dev.phonerecall.moneta.Source
import dev.phonerecall.moneta.Via
import dev.phonerecall.moneta.Money
import dev.phonerecall.moneta.SubSource
import dev.phonerecall.moneta.Subscription
import dev.phonerecall.moneta.Txn
import dev.phonerecall.moneta.TxnKind
import dev.phonerecall.moneta.VisitLink
import dev.phonerecall.moneta.monetaUi
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Moneta to PNG (app/build/shots), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonetaShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Page(content: @Composable () -> Unit) = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    // ---- Moneta ----

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private fun at(daysAgo: Long, h: Int = 12) = today.minusDays(daysAgo).atTime(h, 10).atZone(zone).toInstant().toEpochMilli()
    private fun t(daysAgo: Long, cents: Long, m: String, bank: String = "Chase", last4: String = "1234", kind: TxnKind = TxnKind.SPEND, h: Int = 12) =
        Txn(at = at(daysAgo, h), cents = cents, kind = kind, merchant = m, category = Categories.of(m), bank = bank, last4 = last4)

    private fun monetaState(): MonetaState {
        val txns = ArrayList<Txn>()
        // Three earlier months of ordinary life.
        for (back in listOf(95L, 65L, 35L)) {
            txns += t(back, 15_49, "Netflix", "Capital One", "4321"); txns += t(back + 4, 2_500, "Car wash", "Amex", "1009")
            txns += t(back + 1, 8_210, "Costco", "Bank of America", "5678"); txns += t(back + 8, 6_120, "Wegmans", "Bank of America", "5678")
            txns += t(back + 3, 4_480, "Shell"); txns += t(back + 6, 2_890, "Uber Eats", "Amex", "1009"); txns += t(back + 10, 12_000, "Amazon", "Capital One", "4321")
        }
        txns += t(5, 15_49, "Netflix", "Capital One", "4321"); txns += t(9, 2_500, "Car wash", "Amex", "1009")
        txns += t(1, 9_840, "Costco", "Bank of America", "5678"); txns += t(3, 5_760, "Wegmans", "Bank of America", "5678"); txns += t(2, 4_710, "Shell")
        txns += t(0, 2_345, "Starbucks", h = 8); txns += t(0, 2_345, "Starbucks", h = 9)
        txns += t(4, 64_900, "Best Buy", "Capital One", "4321")
        txns += t(6, 1_999, "Amazon", "Capital One", "4321", TxnKind.REFUND)
        txns += t(2, 11_840, "National Grid", "Iris", "").copy(source = dev.phonerecall.moneta.Source.IRIS, key = "iris:812", bank = null, last4 = null)
        val pinned = listOfNotNull(dev.phonerecall.moneta.ChartSpecs.parse("""{"id":"dining-weekly","type":"bar","title":"Eating out by week","subtitle":"From Iris, last 2 months","query":{"by":"week","months":2,"categories":["Eating out"]}}""").getOrNull(),
            dev.phonerecall.moneta.ChartSpecs.parse("""{"id":"cards","type":"donut","title":"Spending by card, 3 months","query":{"by":"card","months":3}}""").getOrNull())
        val changes = listOf(
            dev.phonerecall.moneta.MonetaChange(3, System.currentTimeMillis() - 600_000, "Iris", "pinned the chart \"Eating out by week\"", true, false),
            dev.phonerecall.moneta.MonetaChange(2, System.currentTimeMillis() - 3_600_000, "Iris", "moved Uber Eats to Eating out", true, false),
            dev.phonerecall.moneta.MonetaChange(1, System.currentTimeMillis() - 7_200_000, "Iris", "her log: 14 new, 2 matched to bank alerts", false, false),
        )
        return MonetaState(true, txns, mapOf(Category.GROCERIES to 15_000L, Category.SHOPPING to 40_000L), emptyList(),
            listOf(Subscription("Claude", 2_000, Cycle.MONTHLY, today.plusDays(9), SubSource.IRIS)), charts = pinned, changes = changes, syncedAt = System.currentTimeMillis() - 2 * 3_600_000L)
    }

    @Composable private fun Moneta(gutter: Dp, wide: Boolean, unlocked: Boolean = true) = Page {
        val s = monetaState()
        val subs = Money.subscriptions(Money.recurring(s.txns, System.currentTimeMillis(), zone), s.irisSubs, emptyList())
        val ui = monetaUi(s, subs, YearMonth.now(zone), System.currentTimeMillis(), zone, VisitLink { a -> if (a == at(1)) "Costco Downtown" else null })
        MonetaContent(gutter, wide, unlocked, null, if (unlocked) ui else null, MoneyActions.propose(MoneyActionKind.DISPUTE, "Best Buy", "Capital One").takeIf { wide }, MonetaCalls())
    }

    /** Every chart Moneta draws, built-in and pinned, one under another. */
    @Composable private fun Charts(gutter: Dp) = Page {
        val s = monetaState()
        val ui = monetaUi(s, emptyList(), YearMonth.now(zone), System.currentTimeMillis(), zone, null)
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = gutter)) {
            (ui.charts + ui.pinned).forEach { (spec, data) -> dev.phonerecall.moneta.ChartCard(spec, data) }
        }
    }

    /** Recent rows with where each came from, then "Check these"; Costco is opened to show the alert folded into it. */
    @Composable private fun Reconcile(dark: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            val iris = { id: Int, daysAgo: Long, cents: Long, m: String -> t(daysAgo, cents, m).copy(source = Source.IRIS, key = "iris:$id", bank = null, via = null) }
            val txns = listOf(
                iris(1, 1, 5_421, "Costco").copy(last4 = "5678"),
                t(1, 5_421, "Costco", "Bank of America", "5678").copy(id = 1, via = Via.APP, mergedInto = "iris:1"),
                t(1, 5_421, "Costco", "Google Wallet", "5678").copy(id = 2, via = Via.WALLET, mergedInto = "local:1"),
                t(0, 2_345, "Starbucks", h = 8).copy(id = 3, via = Via.APP),
                t(0, 2_345, "Starbucks", "Google Wallet", h = 8).copy(id = 4, via = Via.WALLET, mergedInto = "local:3"),
                iris(2, 2, 1_549, "Netflix").copy(last4 = "4321"),
                t(4, 3_210, "Target", "Capital One", "4321").copy(id = 5, via = Via.APP),
                t(3, 8_200, "Sunoco", "Capital One", "4321", TxnKind.DECLINED).copy(id = 6, via = Via.SMS),
            )
            val s = MonetaState(true, txns, syncedAt = System.currentTimeMillis() - 3_600_000L)
            val ui = monetaUi(s, emptyList(), YearMonth.now(zone), System.currentTimeMillis(), zone, null)
            androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = Space.gutterNarrow)) { ReconcileSection(ui, MonetaCalls()) }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun monetaReconcileLight() { compose.setContent { Reconcile(dark = false) }; compose.onAllNodesWithText("Costco")[0].performClick(); save("moneta-reconcile-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun monetaReconcileDark() { compose.setContent { Reconcile(dark = true) }; compose.onAllNodesWithText("Costco")[0].performClick(); save("moneta-reconcile-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h3400dp-xhdpi")
    fun monetaCharts() { compose.setContent { Charts(Space.gutterNarrow) }; save("moneta-charts") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2900dp-xhdpi")
    fun monetaCover() { compose.setContent { Moneta(Space.gutterNarrow, wide = false) }; save("moneta-cover") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun monetaInner() { compose.setContent { Moneta(Space.gutterWide, wide = true) }; save("moneta-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h760dp-xhdpi")
    fun monetaLocked() { compose.setContent { Moneta(Space.gutterNarrow, wide = false, unlocked = false) }; save("moneta-locked") }
}
