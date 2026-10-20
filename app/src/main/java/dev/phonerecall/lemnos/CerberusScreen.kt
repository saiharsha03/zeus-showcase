package dev.phonerecall.lemnos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule

private val SOURCES = mapOf("ssh" to "SSH sign-in", "security" to "Security", "media" to "Plexbot", "scan" to "4-hourly scan", "cve" to "CVE watch", "backup" to "Backups")
fun sourceName(s: String) = SOURCES[s] ?: s.replaceFirstChar { it.uppercase() }

/**
 * Cerberus: what the server's watchers saw. High goes straight to the phone (an FCM wake-up, then the
 * notification is built here), medium becomes a Home card, low stays in this feed. Telegram keeps its
 * own security alerts as before.
 */
@Composable
fun CerberusScreen(hub: LemnosHub, gutter: Dp) {
    val s by hub.state.collectAsStateWithLifecycle()
    val paired = s.pairing == DeviceKeys.State.PAIRED
    DisposableEffect(paired) { if (paired) hub.startLive(); onDispose { if (paired) hub.stopLive() } }
    LaunchedEffect(paired) { if (paired) hub.refresh() }
    CerberusContent(s.alerts, paired, s.syncedAt, gutter, hub::ack, hub::refresh)
}

@Composable
fun CerberusContent(alerts: List<Alert>, paired: Boolean, syncedAt: Long?, gutter: Dp, onAck: (Long) -> Unit, onRefresh: () -> Unit) {
    val c = Athena.colors
    var unread by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.ALERTS.title(), gutter, emblem = Place.ALERTS, subtitle = Place.ALERTS.subtitle()) { if (paired) TextAction("Refresh", onRefresh) }
        if (!paired) {
            Line("Cerberus watches the server once this phone is paired with ${Place.VPS.myth}.", Type.headline, c.ink2, Modifier.padding(start = gutter, end = gutter, top = Space.xl).widthIn(max = 560.dp))
            return@Column
        }
        Row(Modifier.padding(horizontal = gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Unread", { unread = true }, emphasis = unread)
            TextAction("All", { unread = false }, emphasis = !unread)
            Spacer(Modifier.weight(1f))
            syncedAt?.let { Line("Checked ${Fmt.ago(it / 1000.0)}", Type.caption, c.ink3) }
        }
        val shown = alerts.filter { !unread || !it.ack }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            if (shown.isEmpty()) item { Line(if (unread) "Nothing new. All three heads are quiet." else "No alerts yet.", Type.small, c.ink3, Modifier.padding(vertical = Space.l)) }
            for (sev in listOf("high", "medium", "low")) {
                val group = shown.filter { it.severity == sev }
                if (group.isEmpty()) continue
                item(key = "h-$sev") { Kicker(when (sev) { "high" -> "Needs you"; "medium" -> "Worth a look"; else -> "For the record" }, Modifier.padding(top = Space.l, bottom = Space.xs), color = if (sev == "high") c.alert else c.ink3) }
                items(group, key = { it.id }) { a ->
                    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusWord(sourceName(a.source), Fmt.severityTone(a.severity))
                            Spacer(Modifier.width(Space.s))
                            Line(Fmt.ago(a.ts), Type.caption, c.ink3, Modifier.weight(1f))
                            if (!a.ack) TextAction("Seen", { onAck(a.id) })
                        }
                        Line(a.title, Type.body.copy(fontWeight = Type.action.fontWeight), if (a.ack) c.ink2 else c.ink, maxLines = 2)
                        if (a.body.isNotBlank()) Line(a.body, Type.small, c.ink3, maxLines = 4)
                    }
                }
            }
        }
    }
}

/** What Home's board takes from Lemnos. Plain data, so the board stays a pure function of its inputs. */
data class LemnosHome(val security: CardContent? = null, val server: CardContent? = null, val agents: CardContent? = null) {
    companion object {
        fun of(s: LemnosState, athena: List<dev.phonerecall.mind.LongJob> = emptyList(), now: Long = System.currentTimeMillis()): LemnosHome? =
            if (s.pairing != DeviceKeys.State.PAIRED) null else LemnosHome(LemnosCards.security(s), LemnosCards.server(s), LemnosCards.agents(s, athena, now))
    }
}

/** Home's cards from Lemnos: Cerberus (security), the server, and the workers. Null when not paired. */
object LemnosCards {
    fun security(s: LemnosState): CardContent? {
        if (s.pairing != DeviceKeys.State.PAIRED || s.syncedAt == null) return null
        val open = s.alerts.filter { !it.ack && it.severity != "low" }
        val top = open.sortedWith(compareBy({ it.severity != "high" }, { -it.ts })).take(3)
        return CardContent(
            title = if (open.isEmpty()) "No new sign-ins or alerts" else "${open.size} alert${if (open.size == 1) "" else "s"} from Cerberus",
            status = if (open.any { it.severity == "high" }) "Needs you" else if (open.isEmpty()) "All clear" else "Look",
            tone = if (open.any { it.severity == "high" }) Tone.ALERT else if (open.isEmpty()) Tone.OK else Tone.ACTIVE,
            facts = top.map { Fact(sourceName(it.source), it.title.take(80)) },
        )
    }

    fun server(s: LemnosState): CardContent? {
        val h = s.health ?: return null
        val root = h.disks.firstOrNull { it.root == "home" }
        return CardContent(
            title = if (s.online == false) "Lemnos isn't answering" else "Up ${Fmt.uptime(h.uptime)}",
            status = if (s.online == false) "Offline" else "Online",
            tone = if (s.online == false) Tone.ALERT else Tone.OK,
            facts = listOfNotNull(
                Fact("Load", "%.2f on %d cores".format(h.load.firstOrNull() ?: 0.0, h.cpus)),
                Fact("Memory", "${Fmt.bytes(h.memAvailable)} free"),
                root?.free?.let { Fact("Disk", "${Fmt.bytes(it)} free") },
            ),
        )
    }

    /** The workers' state, then the ledger. An overdue answer leads the title: silence is the news. */
    fun agents(s: LemnosState, athena: List<dev.phonerecall.mind.LongJob> = emptyList(), now: Long = System.currentTimeMillis()): CardContent? {
        if (s.agents.isEmpty()) return null
        val busy = s.agents.count { it.busy }
        val ledger = ledgers(s, athena, now)
        val owing = ledger.filter { it.third.overdue > 0 }.map { if (it.first == "athena") "Athena" else it.second }
        return CardContent(
            title = when {
                owing.isNotEmpty() -> owing.joinToString(" and ") + (if (owing.size == 1) " owes" else " owe") + " you an answer"
                busy == 0 -> "Both workers idle"
                else -> "$busy waiting on a reply"
            },
            facts = s.agents.map { a -> workerStatus(a).let { (w, t) -> Fact(a.name, channelLineShort(a), w, t) } } +
                ledger.filter { it.third.asked > 0 }.map { (id, name, l) ->
                    AgentLedger.status(l).let { (w, t) -> Fact(if (id == "athena") "Athena" else name, "${l.answered} of ${l.asked} answered", w, t) }
                },
        )
    }

    private fun channelLineShort(a: AgentStatus) = when (a.channel) { "connected" -> "Zeus channel"; "not-enabled" -> "Channel off"; "offline" -> "Channel offline"; else -> "tmux" }
}
