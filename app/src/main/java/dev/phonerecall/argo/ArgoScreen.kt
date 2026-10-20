package dev.phonerecall.argo

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import java.time.ZoneId
import kotlin.concurrent.thread

/** Argo's sail, for the rail and More. */
val ShipIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    val b = androidx.compose.ui.graphics.vector.ImageVector.Builder("ship", 24.dp, 24.dp, 24f, 24f)
    for (d in listOf("M3.5 16.5 H20.5 L18 20 H6 Z", "M12 3.5 V16.5", "M12 4.5 L18 14 H12", "M12 6.5 L7 14 H12")) {
        b.addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(), stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 1.6f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }
    b.build()
}

class ArgoCallbacks(
    val onAdd: (place: String, whenText: String) -> Unit = { _, _ -> },
    val onAct: (String) -> Unit = {},
    val openVault: () -> Unit = {},
    val onMaps: (String) -> Unit = {},
    /** Moneta's fingerprint, for the trip's amounts. */
    val onShowAmounts: () -> Unit = {},
)

@Composable
fun ArgoScreen(gutter: Dp, wide: Boolean, openVault: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val d by ArgoStore.state.collectAsStateWithLifecycle()
    remember { thread(name = "argo-ui") { ArgoStore.load(ctx); runCatching { Argo.tick(ctx) } }; 0 }
    // Amounts are Moneta's: they show only after its fingerprint, on a secure window, and relock like Moneta.
    val gate = remember { dev.phonerecall.vault.VaultGate() }
    val bio = dev.phonerecall.vault.rememberBiometric()
    dev.phonerecall.vault.GateLifecycle(gate)
    if (gate.unlocked) dev.phonerecall.vault.SecureWindow()
    val moneta by dev.phonerecall.moneta.MonetaCore.get(ctx).state.collectAsStateWithLifecycle()
    val charges = remember(moneta) { Argo.charges(ctx) }
    val cb = ArgoCallbacks(
        onAdd = { p, w -> thread(name = "argo-ui") { Argo.note(ctx, p, w, null, who = "The owner") } },
        onAct = { a -> thread(name = "argo-ui") { Argo.act(ctx, a) } },
        openVault = openVault,
        onMaps = { q -> runCatching { ctx.startActivity(Argo.mapsIntent(q)) } },
        onShowAmounts = { gate.unlock(bio, "Show trip amounts", "Money is behind your fingerprint") { dev.phonerecall.moneta.MonetaCore.get(ctx).load() } },
    )
    ArgoContent(d, System.currentTimeMillis(), ZoneId.systemDefault(), gutter, wide, cb, charges, gate.unlocked)
}

@Composable
fun ArgoContent(d: ArgoData, now: Long, zone: ZoneId, gutter: Dp, wide: Boolean, cb: ArgoCallbacks, charges: List<Triple<Long, String, Long>> = emptyList(), amounts: Boolean = false) {
    val c = Athena.colors
    val open = d.trips.filter { it.open }.sortedBy { it.window(zone)?.first ?: Long.MAX_VALUE }
    val past = d.trips.filter { it.status == TripStatus.ARCHIVED || it.status == TripStatus.DONE }.sortedByDescending { it.window(zone)?.first ?: it.updatedAt }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.TRIPS.title(), gutter, emblem = Place.TRIPS, subtitle = Place.TRIPS.subtitle())
        LazyColumn(
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.blockGap),
            modifier = Modifier.fillMaxWidth().then(if (wide) Modifier.widthIn(max = 720.dp) else Modifier),
        ) {
            item { AddTrip(cb) }
            d.change?.takeIf { !it.ack }?.let { ch ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Line(ch.text, Type.small, c.ink2, Modifier.weight(1f))
                        TextAction("Undo", { cb.onAct("undo") })
                    }
                }
            }
            if (open.isEmpty()) item { Line("No trips yet. Tell Athena \"I'm going to Denver in March\", or add one here. Travel on your calendar and bookings Iris sees show up on their own.", Type.small, c.ink2) }
            items(open, key = { it.id }) { t ->
                // The next trip when it starts within a week of this one ending: maybe the same trip.
                val near = open.firstOrNull { o -> o.id != t.id && t.days(zone)?.second?.let { e -> o.days(zone)?.first?.let { s -> s >= e.minusDays(1) && s <= e.plusDays(7) } } == true }
                TripBlock(t, d.weather[t.id], now, zone, cb, TripLedger.of(t, charges, zone), amounts, near)
            }
            val asking = d.inquiries.filter { it.status in Hints.OPEN }
            if (asking.isNotEmpty()) {
                item { Kicker("Athena is looking into", Modifier.padding(top = Space.l)) }
                items(asking, key = { it.id }) { q ->
                    FactRow(if (q.status == InqStatus.QUESTION) "Asks you" else "Checking", q.hint + if (q.status == InqStatus.ASKED) " (asked Iris)" else "")
                }
            }
            if (past.isNotEmpty()) {
                item { Kicker("Past trips", Modifier.padding(top = Space.l)) }
                items(past, key = { it.id }) { t -> Line(t.recap ?: TripWords.recap(t, zone), Type.small, c.ink2) }
            }
        }
    }
}

@Composable
private fun AddTrip(cb: ArgoCallbacks) {
    var place by remember { mutableStateOf("") }
    var whenText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            UnderlineField(place, { place = it }, "Where", Modifier.weight(1f))
            UnderlineField(whenText, { whenText = it }, "When", Modifier.weight(1.4f))
        }
        TextAction("Add trip", { if (place.isNotBlank()) { cb.onAdd(place.trim(), whenText.trim()); place = ""; whenText = "" } }, enabled = place.isNotBlank(), emphasis = place.isNotBlank())
    }
}

@Composable
private fun TripBlock(t: Trip, w: DestWeather?, now: Long, zone: ZoneId, cb: ArgoCallbacks, money: TripMoney, amounts: Boolean, near: Trip? = null) {
    val c = Athena.colors
    val m = Travel.moment(t, now, zone)
    val gaps = Gaps.of(t, w, now, zone, ArgoLinks.alarms)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().background(c.block(Tint.CALENDAR), shape).border(1.dp, c.edge, shape).padding(Space.blockPad),
    ) {
        Kicker(when (m.phase) { Phase.TRAVEL -> "Travel day"; Phase.AWAY -> "Trip mode"; Phase.IDEA -> "Idea"; else -> "Coming up" })
        Row {
            Line("${t.place} ${if (m.phase == Phase.AWAY || m.phase == Phase.TRAVEL) "now" else Travel.countdown(t, now, zone)}", Type.title, c.ink, Modifier.weight(1f))
            StatusWord(if (gaps.isEmpty()) "All set" else "${gaps.size} missing", if (gaps.isEmpty()) Tone.OK else Tone.ACTIVE)
        }
        Line(Travel.dates(t, zone) + when { t.locked -> " · confirmed"; t.draft -> " · draft, Athena will check it"; else -> "" }, Type.small, c.ink2)
        Spacer(Modifier.height(Space.s))
        val others = t.stops.filter { !Cities.same(it.place, t.place) }
        if (others.isNotEmpty()) FactRow("Also", others.joinToString(", ") { s -> s.place + (s.start?.let { st -> " " + Clock.range(java.time.LocalDate.parse(st), java.time.LocalDate.parse(s.end ?: st)) } ?: "") })
        if (t.layovers.isNotEmpty()) FactRow("Via", t.layovers.joinToString(", "))
        for (g in gaps) FactRow(if (g.gap == Gap.WEATHER) "Weather" else if (g.gap == Gap.ALARM) "Alarm" else "Missing", g.text, if (g.gap.name in t.askedIris) "Iris is looking" else null)
        for (l in t.legs.sortedBy { it.departAt }) {
            val z = if (l.returning) t.destZone(zone) else zone
            FactRow(
                if (l.returning) "Back" else l.mode.word,
                listOf(listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" "), listOf(l.from, l.to).filter { it.isNotBlank() }.joinToString(" to "),
                    l.departAt.takeIf { it > 0 }?.let { "${Clock.day(Trip.day(it, z))}, ${Clock.hm(it, z)}" }.orEmpty(), l.platform).filter { it.isNotBlank() }.joinToString(" · "),
            )
            if (l.doc != null) FactRow("Ticket", "In Calypso")
        }
        for (s in t.stays) { FactRow("Stay", listOf(s.name, s.address).filter { it.isNotBlank() }.joinToString(", ")); if (s.doc != null) FactRow("Booking", "In Calypso") }
        // Day by day: legs, check-in and out, tickets and tables.
        for ((day, items) in Itinerary.days(t, zone)) FactRow(Clock.day(day), items.joinToString(" · ") { it.text })
        for (p in t.plans.filter { it.at <= 0 }) FactRow("Plan", p.title)
        if (t.people.isNotEmpty() && t.plans.none { it.people.isNotEmpty() }) FactRow("With", t.people.joinToString(", "))
        for (n in t.notes.takeLast(2)) FactRow("You said", n)
        ArgoCards.weatherLine(w, t.days(zone)?.first ?: Trip.day(now, zone))?.let { FactRow("There", it) }
        t.firstBack?.let { FactRow("Back to", it.removePrefix("First thing back: ")) }
        // Committed (booked: paid or awaiting payment) vs discretionary (the budget left). Words first; amounts behind the fingerprint.
        if (amounts) {
            FactRow("Committed", TripLedger.dollars(money.committedCents) + (if (money.dueCents > 0) ", ${TripLedger.dollars(money.dueCents)} still to pay" else ""))
            FactRow("To spend", money.discretionaryCents?.let { "${TripLedger.dollars(it)} discretionary, of a ${TripLedger.dollars(money.budgetCents!!)} budget" } ?: "No budget set")
        } else FactRow("Money", money.words())
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            gaps.firstOrNull { it.gap.ask != null && it.gap.name !in t.askedIris }?.let { g -> TextAction("Ask Iris", { cb.onAct("ask:${t.id}:${g.gap.name}") }, emphasis = true) }
            if (t.docs.isNotEmpty() || t.legs.any { it.doc != null }) TextAction("Confirmations", cb.openVault)
            t.stays.firstOrNull()?.let { s -> TextAction("Directions", { cb.onMaps(s.address.ifBlank { s.name }) }) }
            if (!amounts && (money.paid + money.due > 0 || money.budgetCents != null)) TextAction("Amounts", cb.onShowAmounts)
            TextAction(if (t.status == TripStatus.IDEA) "Not going" else "Archive", { cb.onAct(if (t.status == TripStatus.IDEA) "dismiss:${t.id}" else "archive:${t.id}") })
        }
        // His edits: each locks the trip and is remembered, so the same pattern assembles right next time.
        if (others.isNotEmpty() || near != null) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            others.firstOrNull()?.let { s ->
                TextAction("${s.place} was a layover", { cb.onAct("layover:${t.id}:${s.place}") })
                TextAction("Split at ${s.place}", { cb.onAct("split:${t.id}:${s.place}") })
            }
            near?.let { o -> TextAction("Same trip as ${o.place}", { cb.onAct("merge:${t.id}:${o.id}") }) }
        }
    }
}
