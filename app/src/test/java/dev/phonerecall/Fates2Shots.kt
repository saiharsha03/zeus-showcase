package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.phonerecall.brain.AttentionBudget
import dev.phonerecall.brain.AttentionDay
import dev.phonerecall.brain.Bindings
import dev.phonerecall.brain.DigestItem
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.FatesCard
import dev.phonerecall.brain.FieldCat
import dev.phonerecall.brain.Fatigue
import dev.phonerecall.brain.KindStats
import dev.phonerecall.brain.OutboundRow
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Step
import dev.phonerecall.lemnos.AgentStatus
import dev.phonerecall.lemnos.ChatMsg
import dev.phonerecall.lemnos.ChatPane
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.lemnos.TaskActions
import dev.phonerecall.lemnos.WorkerJob
import dev.phonerecall.lemnos.liveItems
import dev.phonerecall.mind.LongJob
import dev.phonerecall.settings.AutoApproveCalls
import dev.phonerecall.settings.AutoApproveContent
import dev.phonerecall.settings.AutoApproveView
import dev.phonerecall.settings.OutboundContent
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

/** Fates 2 to PNG (app/build/shots/fates2-*.png), on made-up data: auto-approve stats and budget, changed card, ledger, outbound log. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fates2Shots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "fates2-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val zone = ZoneId.of("America/New_York")
    private val now = java.time.ZonedDateTime.of(2026, 10, 19, 18, 40, 0, 0, zone).toInstant().toEpochMilli()
    private val h = 3_600_000L

    @Composable private fun Page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    private fun gutter(wide: Boolean) = if (wide) Space.gutterWide else Space.gutterNarrow

    // ---- Auto-approve stats and the attention budget ----

    private val auto = AutoApproveView(
        on = setOf(ProposalKind.ADD_REMINDER),
        stats = mapOf(
            ProposalKind.SET_ALARM to (KindStats(16, 0, 1, 2) to null),
            ProposalKind.SET_TIMER to (KindStats(9, 0, 0, 1) to null),
            ProposalKind.ADD_REMINDER to (KindStats(15, 0, 0, 0) to null),
            ProposalKind.ADD_TASK to (KindStats(6, 1, 2, 3) to null),
            ProposalKind.CREATE_CARD to (KindStats(1, 3, 0, 4) to now + 26 * 24 * h),
            ProposalKind.ADD_TO_LIST to (KindStats(11, 0, 3, 0) to null),
            ProposalKind.LIGHTS to (KindStats(4, 0, 0, 0) to null),
            ProposalKind.COMPLETE_TASK to (KindStats(3, 0, 0, 0) to null),
            ProposalKind.ADD_CALENDAR_EVENT to (KindStats(5, 1, 1, 0) to null),
            ProposalKind.PLAY_MUSIC to (KindStats(7, 2, 0, 1) to null),
        ),
        budget = AttentionBudget(5, 3),
        today = AttentionDay("2026-10-19", cards = (1..5).map { "c$it" }.toSet(), notified = 3),
        waiting = listOf(DigestItem("1", "Delivery update", "2026-10-20"), DigestItem("2", "Worth a look", "2026-10-20"), DigestItem("n:3", "Liverpool 2-1 Chelsea", "2026-10-20")),
    )

    @Composable private fun AutoPage(dark: Boolean, wide: Boolean) = Page(dark) {
        Box(Modifier.widthIn(max = 720.dp)) { AutoApproveContent(gutter(wide), auto, AutoApproveCalls(), zone) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun autoCoverDark() { compose.setContent { AutoPage(true, false) }; save("auto-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun autoCoverLight() { compose.setContent { AutoPage(false, false) }; save("auto-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1700dp-xhdpi") fun autoInnerDark() { compose.setContent { AutoPage(true, true) }; save("auto-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1700dp-xhdpi") fun autoInnerLight() { compose.setContent { AutoPage(false, true) }; save("auto-inner-light") }

    // ---- Fates cards: changed since he saw it, the meta-proposal, the once-a-day ask ----

    private val event = Proposal(
        id = 11, createdAt = now - h, expiresAt = now + 48 * h, status = ProposalStatus.PENDING, title = "Add to calendar: Dentist, Thu, Oct 22, 9:00 AM",
        steps = listOf(Step(ProposalKind.ADD_CALENDAR_EVENT, mapOf("title" to "Dentist", "start" to "2026-10-22T09:00", "end" to "2026-10-22T10:00", "all_day" to "false", "calendar_id" to "3", "calendar" to "Personal"), "Add to calendar: Dentist")),
    )
    private val meta = Proposal(
        id = 12, createdAt = now - h, expiresAt = now + 6 * 24 * h, status = ProposalStatus.PENDING, title = "Add reminder to auto-approve?", proposer = "Fates",
        steps = listOf(Step(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to "add_reminder", "why" to "You confirmed ${Fatigue.AUTO_CONFIRMS} and turned down none in 30 days"), "Run reminder proposals without asking")),
    )
    private val list = Proposal(
        id = 13, createdAt = now - h, expiresAt = now + 60 * h, status = ProposalStatus.PENDING, title = "Add pasta, tomatoes, garlic to Groceries?",
        steps = listOf(Step(ProposalKind.ADD_TO_LIST, mapOf("section" to "Groceries", "items" to "pasta, tomatoes, garlic"), "Add pasta, tomatoes, garlic to Groceries")),
    )

    private val cb = FatesCallbacks(
        confirm = { _, _, done -> done(Bindings.CHANGED) },
        ask = { it.id == 13L },
    )

    @Composable private fun Cards(dark: Boolean, wide: Boolean) = Page(dark) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Fates", gutter(wide), kicker = "Proposals")
            val col: @Composable (List<Proposal>) -> Unit = { ps ->
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Space.m)) { for (p in ps) FatesCard(p, cb, zone = zone) }
            }
            if (wide) Row(Modifier.padding(horizontal = gutter(wide)), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Box(Modifier.weight(1f)) { col(listOf(event)) }
                Box(Modifier.weight(1f)) { col(listOf(meta, list)) }
            } else Box(Modifier.padding(horizontal = gutter(wide))) { col(listOf(event, meta, list)) }
        }
    }

    private fun changed(dark: Boolean, wide: Boolean, name: String) {
        compose.setContent { Cards(dark, wide) }
        compose.onAllNodesWithText("Confirm")[0].performClick()
        save(name)
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun cardsCoverDark() = changed(true, false, "changed-cover-dark")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun cardsCoverLight() = changed(false, false, "changed-cover-light")
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi") fun cardsInnerDark() = changed(true, true, "changed-inner-dark")
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi") fun cardsInnerLight() = changed(false, true, "changed-inner-light")

    // ---- The agent ledger ----

    private fun s(ms: Long) = ms / 1000.0
    private val agents = listOf(
        AgentStatus("iris", "Iris", "claude", "claude-auto", true, "connected", false, null),
        AgentStatus("atlas", "Atlas", "codex", "codex", true, "tmux", false, null),
    )
    private val jobs = listOf(
        WorkerJob("j1", "iris", "iris.research", "Find the winter tire deals nearby", "delivered", true, s(now - 7 * h), s(now - 7 * h), null),
        WorkerJob("j2", "iris", "iris.mail", "Did the security deposit refund arrive?", "done", true, s(now - 30 * h), s(now - 26 * h), "Yes, \$1,450 on Oct 16."),
        WorkerJob("j3", "atlas", "atlas.check", "Check why the backup timer ran late", "done", false, s(now - 3 * h), s(now - 2 * h), "Borg ran at 06:02; the storage box mount was slow."),
        WorkerJob("j4", "atlas", "atlas.check", "Is the disk filling up?", "working", true, s(now - 40 * 60_000L), s(now - 20 * 60_000L), null),
    )
    private val chat = listOf(
        ChatMsg(1, "iris", "owner", "Can you find the lease renewal date?", s(now - 5 * h), "text", null, null),
        ChatMsg(2, "iris", "owner", "Also the parking permit form", s(now - 3 * h), "text", null, null),
    )
    private val lemnos = LemnosState(pairing = DeviceKeys.State.PAIRED, online = true, agents = agents, jobs = jobs, chat = mapOf("iris" to chat), syncedAt = now)
    private val athena = listOf(
        LongJob("a1", "Research winter tires for the Mazda 3", startedAt = now - 31 * h, status = "progress"),
        LongJob("a2", "Compare renters insurance quotes", startedAt = now - 50 * h, status = "done"),
        LongJob("a3", "Watch for the passport appointment slot", startedAt = now - 4 * h, status = "started"),
    )

    @Composable private fun Ledger(dark: Boolean, wide: Boolean) = Page(dark) {
        val list: @Composable (Modifier) -> Unit = { m ->
            dev.phonerecall.mind.PantheonContent(gutter(wide), emptyMap(), dev.phonerecall.home.SampleData.workers, m,
                live = { liveItems(lemnos, if (wide) "iris" else null, {}, TaskActions(), null, athena, now) })
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f))
            ChatPane("iris", chat, agents, gutter(wide), Modifier.weight(1.25f).statusBarsPadding(), onBack = null, jobs = jobs, now = now) {}
        } else list(Modifier)
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi") fun ledgerCoverDark() { compose.setContent { Ledger(true, false) }; save("ledger-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi") fun ledgerCoverLight() { compose.setContent { Ledger(false, false) }; save("ledger-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun ledgerInnerDark() { compose.setContent { Ledger(true, true) }; save("ledger-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun ledgerInnerLight() { compose.setContent { Ledger(false, true) }; save("ledger-inner-light") }

    // ---- What left the phone ----

    private val rows = listOf(
        OutboundRow(now - 10 * 60_000L, "api.anthropic.com", "Delphi chat", setOf(FieldCat.MESSAGE_TEXT, FieldCat.NAMES), 3),
        OutboundRow(now - 40 * 60_000L, "api.anthropic.com", "Triage of notification labels", setOf(FieldCat.NOTIFICATION_LINES)),
        OutboundRow(now - 2 * h, "api.open-meteo.com", "Aeolia weather", setOf(FieldCat.COORDINATES), 4),
        OutboundRow(now - 3 * h, "tilecache.rainviewer.com", "Aeolia radar and map", emptySet(), 64),
        OutboundRow(now - 5 * h, "api.tomtom.com", "Odyssey traffic", setOf(FieldCat.COORDINATES, FieldCat.KEY), 2),
        OutboundRow(now - 9 * h, "zeus.example.com", "Lemnos, your server", setOf(FieldCat.MESSAGE_TEXT), 12),
        OutboundRow(now - 26 * h, "api.anthropic.com", "Arrival text for a store", setOf(FieldCat.PLACE_NAMES, FieldCat.LIST_ITEMS)),
        OutboundRow(now - 27 * h, "api.spotify.com", "Music (Spotify)", emptySet(), 6),
        OutboundRow(now - 30 * h, "api.anthropic.com", "Athena's mind (Managed Agents)", setOf(FieldCat.SUMMARIES), 9),
        OutboundRow(now - 31 * h, "site.api.espn.com", "Olympia scores", emptySet(), 18),
    )

    @Composable private fun Out(dark: Boolean, wide: Boolean) = Page(dark) {
        Box(Modifier.widthIn(max = 720.dp)) { OutboundContent(gutter(wide), rows, {}, zone) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun outCoverDark() { compose.setContent { Out(true, false) }; save("outbound-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun outCoverLight() { compose.setContent { Out(false, false) }; save("outbound-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi") fun outInnerDark() { compose.setContent { Out(true, true) }; save("outbound-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi") fun outInnerLight() { compose.setContent { Out(false, true) }; save("outbound-inner-light") }
}
