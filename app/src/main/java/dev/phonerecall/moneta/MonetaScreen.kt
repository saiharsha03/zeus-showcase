package dev.phonerecall.moneta

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import dev.phonerecall.vault.GateLifecycle
import dev.phonerecall.vault.LockedPanel
import dev.phonerecall.vault.SecureWindow
import dev.phonerecall.vault.VaultGate
import dev.phonerecall.vault.rememberBiometric
import java.text.DateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Everything Moneta's page shows, worked out. */
data class MonetaUi(
    val view: MonthView,
    val unusual: List<Unusual>,
    val subscriptions: List<Subscription>,
    /** Newest first, each with the place Odyssey says he was at, if any. */
    val recent: List<Pair<Txn, String?>>,
    val sources: String,
    /** Moneta's own charts, then the ones Iris pinned, each with its numbers worked out. */
    val charts: List<Pair<ChartSpec, ChartData>> = emptyList(),
    val pinned: List<Pair<ChartSpec, ChartData>> = emptyList(),
    val changes: List<MonetaChange> = emptyList(),
    /** Matched with Iris, or only on one side, by row ref. */
    val recon: Map<String, Recon> = emptyMap(),
    /** The rows folded into each kept row, by the kept row's ref. */
    val folded: Map<String, List<Txn>> = emptyMap(),
    /** The daily check: rows only one side has. */
    val checks: List<Check> = emptyList(),
    /** Charges he said "No" to, by flag key. */
    val disowned: List<Pair<String, Txn>> = emptyList(),
)

/** What the page can ask for. Every money action goes through [propose]; [confirm] only ever opens an app. */
class MonetaCalls(
    val onUnlock: () -> Unit = {},
    val month: (Long) -> Unit = {},
    val dismiss: (Unusual) -> Unit = {},
    val propose: (MoneyProposal) -> Unit = {},
    val confirm: (MoneyProposal) -> Unit = {},
    val cancel: () -> Unit = {},
    val budget: (Category, Long?) -> Unit = { _, _ -> },
    val add: (Txn) -> Unit = {},
    val addSub: (Subscription) -> Unit = {},
    val refreshIris: () -> Unit = {},
    /** His edits: applied at once and logged, like Iris's. */
    val edit: (MonetaOp) -> Unit = {},
    val undo: (MonetaChange) -> Unit = {},
    /** A folded row back on its own. */
    val unmerge: (Txn) -> Unit = {},
    val checkFine: (Check) -> Unit = {},
    /** Posts in Agora that he didn't make it (no amount). */
    val tellIris: (String) -> Unit = {},
    val settle: (String) -> Unit = {},
)

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val shortDay = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val txnTime: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

@Composable
fun MonetaContent(gutter: Dp, wide: Boolean, unlocked: Boolean, failure: String?, ui: MonetaUi?, pending: MoneyProposal?, calls: MonetaCalls) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.MONEY.title(), gutter, emblem = Place.MONEY, subtitle = Place.MONEY.subtitle()) {
            if (unlocked && ui != null) StatusWord(if (ui.view.month == YearMonth.now()) "This month" else ui.view.month.month.getDisplayName(TextStyle.SHORT, Locale.US), Tone.QUIET)
        }
        if (!unlocked || ui == null) {
            LockedPanel(gutter, "Your money is behind your fingerprint.", failure, calls.onUnlock) {
                Line("Built on this phone from your bank alerts, Iris's category totals and what you add. Athena never sees amounts.", Type.small, Athena.colors.ink3, Modifier.padding(top = Space.l))
            }
            return@Column
        }
        val left: @Composable () -> Unit = {
            Column {
                Total(ui.view, calls)
                pending?.let { Proposal(it, calls) }
                ui.disowned.forEach { (key, t) -> Disowned(key, t, calls) }
                if (ui.unusual.isNotEmpty()) Unusuals(ui.unusual, calls)
                if (ui.pinned.isNotEmpty()) {
                    Kicker("From Iris", Modifier.padding(top = Space.xl, bottom = Space.xs))
                    ui.pinned.forEach { (spec, data) -> ChartCard(spec, data, onRemove = { calls.edit(MonetaOp.ChartRemove(spec.id)) }) }
                }
                Kicker("Charts", Modifier.padding(top = Space.xl, bottom = Space.xs))
                ui.charts.forEach { (spec, data) -> ChartCard(spec, data) }
                if (!wide) Categories(ui.view, calls)
            }
        }
        val right: @Composable () -> Unit = {
            Column {
                if (wide) Categories(ui.view, calls)
                Subscriptions(ui.subscriptions, calls)
                ReconcileSection(ui, calls)
                AddEntry(calls)
                Changes(ui.changes, calls)
                Line(ui.sources, Type.small, Athena.colors.ink3, Modifier.padding(top = Space.xl).widthIn(max = 560.dp))
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { left() } }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { right() } }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) { item { left() }; item { right() } }
        }
    }
}

@Composable
private fun Total(v: MonthView, calls: MonetaCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line(monthTitle.format(v.month), Type.title, c.ink, Modifier.weight(1f))
            TextAction("Earlier", { calls.month(-1) })
            TextAction("Later", { calls.month(1) }, enabled = v.month.isBefore(YearMonth.now()))
        }
        Line(Money.fmt(v.total, whole = true), Type.display, c.ink, Modifier.padding(top = Space.xs))
        val usual = v.usual
        Line(
            when {
                usual == null -> "Spent so far. Moneta learns your usual over the next months."
                v.total > usual -> "Spent. ${Money.fmt(v.total - usual, whole = true)} more than your usual ${Money.fmt(usual, whole = true)}."
                else -> "Spent. ${Money.fmt(usual - v.total, whole = true)} under your usual ${Money.fmt(usual, whole = true)}."
            },
            Type.small, c.ink2,
        )
    }
}

@Composable
private fun Unusuals(list: List<Unusual>, calls: MonetaCalls) {
    val c = Athena.colors
    Kicker("To look at", Modifier.padding(top = Space.xl, bottom = Space.s))
    list.take(5).forEach { u ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(u.txn.merchant, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                Line(Money.fmt(u.txn.cents), Type.body, c.ink)
            }
            Line(u.reason, Type.small, c.alert)
            Line(listOfNotNull(u.txn.bank, u.txn.last4?.let { "card ending $it" }, txnTime.format(Date(u.txn.at))).joinToString(", "), Type.small, c.ink3)
            Row {
                TextAction("It's fine", { calls.dismiss(u) })
                TextAction("Dispute", { calls.propose(MoneyActions.propose(MoneyActionKind.DISPUTE, u.txn.merchant, u.txn.bank)) })
            }
        }
    }
}

/** A money proposal, inline. Confirm opens the bank's app and nothing else; it is never on auto-approve. */
@Composable
private fun Proposal(p: MoneyProposal, calls: MonetaCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.l).topRule(c.accent).padding(vertical = Space.m)) {
        Kicker("Proposal", color = c.accentInk)
        Line(p.title, Type.title, c.ink, Modifier.padding(top = Space.xs))
        Line(p.reason, Type.small, c.ink2)
        Row {
            TextAction("Open ${p.bank ?: "the app"}", { calls.confirm(p) }, emphasis = true)
            TextAction("Not now", calls.cancel)
        }
    }
}

@Composable
private fun Categories(v: MonthView, calls: MonetaCalls) {
    val c = Athena.colors
    var editing by remember { mutableStateOf<Category?>(null) }
    var draft by remember { mutableStateOf("") }
    Kicker("By category", Modifier.padding(top = Space.xl, bottom = Space.s))
    if (v.categories.isEmpty()) Line("No spending seen yet this month.", Type.small, c.ink3)
    val top = (v.categories.maxOfOrNull { maxOf(it.cents, it.budget ?: 0) } ?: 1).coerceAtLeast(1)
    v.categories.forEach { s ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).clickable { editing = s.category; draft = s.budget?.let { (it / 100).toString() }.orEmpty() }.padding(vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(s.category.word, Type.body, c.ink, Modifier.weight(1f))
                if (s.over) { StatusWord("Over", Tone.ALERT); Spacer(Modifier.width(Space.s)) }
                Line(Money.fmt(s.cents, whole = true), Type.body, c.ink)
            }
            Box(Modifier.padding(top = Space.xs).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.rule)) {
                Box(Modifier.fillMaxWidth((s.cents.toFloat() / top).coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (s.over) c.alert else c.accent))
            }
            val notes = listOfNotNull(s.usual?.let { "usual ${Money.fmt(it, whole = true)}" }, s.budget?.let { "budget ${Money.fmt(it, whole = true)}" })
            if (notes.isNotEmpty()) Line(notes.joinToString(", ").replaceFirstChar { it.uppercase() }, Type.caption, c.ink3, Modifier.padding(top = Space.xs))
            if (editing == s.category) {
                UnderlineField(draft, { draft = it.filter(Char::isDigit).take(6) }, "Monthly budget in dollars", Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
                Row {
                    TextAction("Save", { calls.budget(s.category, draft.toLongOrNull()?.times(100)); editing = null }, emphasis = true)
                    if (s.budget != null) TextAction("No budget", { calls.budget(s.category, null); editing = null })
                    TextAction("Cancel", { editing = null })
                }
            }
        }
    }
    Line("Tap a category to set a budget.", Type.caption, c.ink3, Modifier.padding(top = Space.s))
}

@Composable
private fun Subscriptions(subs: List<Subscription>, calls: MonetaCalls) {
    val c = Athena.colors
    Kicker("Subscriptions", Modifier.padding(top = Space.xl, bottom = Space.s))
    if (subs.isEmpty()) Line("None seen yet. Moneta spots them after two charges on the same beat.", Type.small, c.ink3)
    subs.forEach { s ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(s.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f))
                Line(s.cents?.let { Money.fmt(it) + " " + s.cycle.word.lowercase() } ?: s.cycle.word, Type.small, c.ink2)
            }
            Line(listOfNotNull(s.next?.let { "Renews ${shortDay.format(it)}" }, s.source.word).joinToString(". "), Type.small, c.ink3)
            TextAction("Cancel it", { calls.propose(MoneyActions.propose(MoneyActionKind.CANCEL_SUBSCRIPTION, s.name, s.bank)) })
        }
    }
}

/** He said "No" to a flag: open the bank's app to dispute it, or tell Iris in Agora (merchant, day, card; never the amount). */
@Composable
private fun Disowned(key: String, t: Txn, calls: MonetaCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.l).topRule(c.alert).padding(vertical = Space.m)) {
        Kicker("You said you didn't make this", color = c.alert)
        Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Line(t.merchant, Type.title, c.ink, Modifier.weight(1f), maxLines = 1)
            Line(Money.fmt(t.cents), Type.body, c.ink)
        }
        Line(listOfNotNull(t.bank, t.last4?.let { "••$it" }, txnTime.format(Date(t.at))).joinToString(", "), Type.small, c.ink3)
        Line("Call or open ${t.bank ?: "the bank"} to lock the card. Iris can follow up; she gets the merchant, day and card, not the amount.", Type.small, c.ink2)
        FlowRow {
            TextAction("Open ${t.bank ?: "the app"}", { calls.confirm(MoneyActions.propose(MoneyActionKind.DISPUTE, t.merchant, t.bank)) }, emphasis = true)
            TextAction("Tell Iris", { calls.tellIris(key) })
            TextAction("Done", { calls.settle(key) })
        }
    }
}

/** Recent rows with where each came from, then the daily check. */
@Composable
fun ReconcileSection(ui: MonetaUi, calls: MonetaCalls) {
    Column {
        Recent(ui, calls)
        Checks(ui.checks, calls)
    }
}

@Composable
private fun Recent(ui: MonetaUi, calls: MonetaCalls) {
    val c = Athena.colors
    val list = ui.recent
    var open by remember { mutableStateOf<String?>(null) }
    Kicker("Recent", Modifier.padding(top = Space.xl, bottom = Space.s))
    if (list.isEmpty()) Line("Bank alerts from Chase, Bank of America, Amex, Capital One and Google Wallet, and everything Iris logs, show up here.", Type.small, c.ink3)
    list.take(15).forEach { (t, place) ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).clickable { open = if (open == t.ref) null else t.ref }.padding(vertical = Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(t.merchant, Type.body, c.ink, Modifier.weight(1f), maxLines = 1)
                Line((if (t.kind.spend) "" else if (t.kind == TxnKind.REFUND) "+" else "") + Money.fmt(t.cents), Type.body, if (t.kind == TxnKind.DECLINED) c.ink3 else c.ink)
            }
            Line(
                listOfNotNull(t.kind.takeIf { !it.spend }?.word, t.category.word.takeIf { t.kind.spend }, t.bank ?: t.source.word, t.last4?.let { "••$it" }, place?.let { "at $it" }, txnTime.format(Date(t.at))).joinToString(", "),
                Type.small, c.ink3, maxLines = 1,
            )
            ui.recon[t.ref]?.let { r -> Line(r.word, Type.caption, if (r == Recon.MATCHED) c.ink3 else c.ink2) }
            if (open == t.ref) {
                ui.folded[t.ref].orEmpty().forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Line("Also from ${f.via?.let { v -> "${f.bank ?: ""} ${v.word}".trim() } ?: f.source.word}", Type.small, c.ink2, Modifier.weight(1f), maxLines = 2)
                        TextAction("Unmerge", { calls.unmerge(f); open = null })
                    }
                }
                FlowRow(Modifier.padding(top = Space.xs)) {
                    Category.entries.filter { it != Category.TRANSFERS && it != t.category }.forEach { k -> TextAction(k.word, { calls.edit(MonetaOp.Recategorize(t.ref, k)); open = null }) }
                }
                TextAction("Delete", { calls.edit(MonetaOp.Delete(t.ref)); open = null })
            }
        }
    }
}

/** Rows only one side has: a missed alert, or a duplicate Moneta couldn't pair. */
@Composable
private fun Checks(list: List<Check>, calls: MonetaCalls) {
    if (list.isEmpty()) return
    val c = Athena.colors
    Kicker("Check these", Modifier.padding(top = Space.xl, bottom = Space.s))
    list.forEach { k ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(k.txn.merchant, Type.body, c.ink, Modifier.weight(1f), maxLines = 1)
                Line(Money.fmt(k.txn.cents), Type.body, c.ink)
            }
            Line(listOfNotNull(k.txn.bank ?: k.txn.source.word, k.txn.last4?.let { "••$it" }, txnTime.format(Date(k.txn.at))).joinToString(", "), Type.small, c.ink3, maxLines = 1)
            Line(k.reason, Type.small, c.ink2)
            Row {
                TextAction("It's fine", { calls.checkFine(k) })
                TextAction("Delete", { calls.edit(MonetaOp.Delete(k.txn.ref)) })
            }
        }
    }
}

/** Every change Iris or he made, newest first. Each can be undone once. */
@Composable
private fun Changes(list: List<MonetaChange>, calls: MonetaCalls) {
    val c = Athena.colors
    Kicker("Changes", Modifier.padding(top = Space.xl, bottom = Space.s))
    if (list.isEmpty()) Line("What Iris (or you) change in Moneta shows here, with an undo.", Type.small, c.ink3)
    list.take(20).forEach { ch ->
        Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("${ch.who} ${ch.summary}", Type.small, if (ch.undone) c.ink3 else c.ink, maxLines = 2)
                Line(txnTime.format(Date(ch.at)), Type.caption, c.ink3)
            }
            when {
                ch.undone -> StatusWord("Undone", Tone.QUIET)
                ch.undoable -> TextAction("Undo", { calls.undo(ch) })
            }
        }
    }
}

@Composable
private fun AddEntry(calls: MonetaCalls) {
    val c = Athena.colors
    var open by remember { mutableStateOf(false) }
    var what by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(Category.OTHER) }
    var sub by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = Space.l)) {
        if (!open) { TextAction("Add an entry", { open = true }, emphasis = true); return@Column }
        Kicker("Add an entry")
        UnderlineField(what, { what = it.take(40) }, "Where or what, like Farmers market", Modifier.fillMaxWidth())
        UnderlineField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' }.take(9) }, "Amount in dollars", Modifier.fillMaxWidth(), keyboard = KeyboardType.Decimal)
        FlowRow { listOf(Category.GROCERIES, Category.DINING, Category.FUEL, Category.SHOPPING, Category.FUN, Category.OTHER).forEach { k -> TextAction(k.word, { cat = k }, emphasis = k == cat) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (sub) "Monthly subscription: yes" else "Monthly subscription: no", { sub = !sub })
        }
        val cents = amount.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
        Row {
            TextAction("Save", {
                if (cents != null && what.isNotBlank()) {
                    if (sub) calls.addSub(Subscription(what.trim(), cents, Cycle.MONTHLY, LocalDate.now().plusMonths(1), SubSource.MANUAL))
                    else calls.add(Txn(at = System.currentTimeMillis(), cents = cents, kind = TxnKind.SPEND, merchant = what.trim(), category = cat, source = Source.MANUAL))
                    what = ""; amount = ""; open = false
                }
            }, emphasis = true, enabled = cents != null && cents > 0 && what.isNotBlank())
            TextAction("Cancel", { open = false })
        }
        Line("Entries stay on this phone.", Type.caption, c.ink3)
    }
}

/** Builds the page's data from Moneta's state. Pure, so the screenshot test uses it too. */
fun monetaUi(s: MonetaState, subs: List<Subscription>, month: YearMonth, now: Long, zone: ZoneId, visits: VisitLink?): MonetaUi {
    val iris = s.syncedAt.takeIf { it > 0 }?.let { "Iris's log (synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))})" }
    fun data(spec: ChartSpec) = spec to ChartEngine.compute(spec, s.txns, s.budgets, now, zone)
    return MonetaUi(
        Money.month(s.txns, month, zone, s.budgets),
        Money.unusual(s.txns, now, zone, s.dismissed, known = s.known, travelling = { MonetaHooks.trips?.tripAt(it) != null }),
        subs,
        s.txns.filter { it.mergedInto == null }.sortedByDescending { it.at }.take(15).map { it to listOfNotNull(visits?.placeAt(it.at), MonetaHooks.trips?.tripAt(it.at)?.let { t -> "$t trip" }).joinToString(" · ").ifEmpty { null } },
        "From bank alerts on this phone" + (iris?.let { ", $it" } ?: "") + " and your own entries. Iris can change anything here; every change is below with an undo. " +
            "Amounts never leave the phone and Athena never sees them." + (s.irisError?.let { " $it" } ?: ""),
        ChartSpecs.BUILT_IN.filter { it.id != "budget-burn" || s.budgets.isNotEmpty() }.map(::data),
        s.charts.map(::data),
        s.changes,
        Reconcile.status(s.txns),
        s.txns.filter { it.mergedInto != null }.groupBy { it.mergedInto!! },
        Reconcile.checks(s.txns, now, s.syncedAt > 0, s.dismissed),
        s.disowned.mapNotNull { k -> s.txns.firstOrNull { it.at == k.removePrefix("declines:").substringBefore(':').toLongOrNull() && it.mergedInto == null }?.let { k to it } },
    )
}

/** Confirm on a money proposal: open the bank's app, or the Play Store page if it isn't installed. Nothing else. */
internal fun openBank(context: Context, e: MoneyEffect.OpenBank) {
    val launch = e.pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(e.pkg?.let { "market://details?id=$it" } ?: "https://www.google.com/search?q=" + Uri.encode((e.bank ?: "my bank") + " app")))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun MonetaScreen(gutter: Dp, wide: Boolean) {
    val context = LocalContext.current
    val core = remember { MonetaCore.get(context) }
    val bio = rememberBiometric()
    val gate = remember { VaultGate() }
    val s by core.state.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var pending by remember { mutableStateOf<MoneyProposal?>(null) }
    SecureWindow()
    GateLifecycle(gate)
    val unlock = { gate.unlock(bio, "Open ${Place.MONEY.myth}", "Your money is behind your fingerprint") { core.load(); core.syncIris() } }
    LaunchedEffect(Unit) { unlock() }
    // The charts, recurring charges and the visit next to each charge run over the whole ledger (and Odyssey's
    // store), so they are worked out off the main thread when the ledger or the month changes, not on every frame.
    val built by androidx.compose.runtime.produceState<MonetaUi?>(null, s, month, gate.unlocked) {
        if (gate.unlocked && s.loaded) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            monetaUi(s, core.subscriptions(s), month, System.currentTimeMillis(), ZoneId.systemDefault(), MonetaHooks.visits)
        }
    }
    val ui = built.takeIf { gate.unlocked && s.loaded }
    MonetaContent(
        gutter, wide, gate.unlocked, gate.failure, ui, pending,
        MonetaCalls(
            onUnlock = unlock,
            month = { d -> month = month.plusMonths(d).let { if (it.isAfter(YearMonth.now())) YearMonth.now() else it } },
            dismiss = core::dismiss,
            propose = { pending = it },
            confirm = { p -> (MoneyActions.confirm(p, tappedByOwner = true) as? MoneyEffect.OpenBank)?.let { openBank(context, it) }; pending = null },
            cancel = { pending = null },
            budget = core::setBudget,
            add = core::addManual,
            addSub = core::addSubscription,
            refreshIris = core::syncIris,
            edit = core::edit,
            undo = core::undo,
            unmerge = { t -> core.edit(MonetaOp.Unmerge(t.ref)) },
            checkFine = { k -> core.dismissKey(k.key) },
            tellIris = core::tellIris,
            settle = core::settle,
        ),
    )
}
