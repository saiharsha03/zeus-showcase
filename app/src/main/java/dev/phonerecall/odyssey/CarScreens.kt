package dev.phonerecall.odyssey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.topRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Fill in" on the lease card opens Odyssey on the Car tab. */
object CarNav {
    @Volatile var open = false
    fun take(): Boolean = open.also { open = false }
}

/**
 * The fill-up's odometer: typed, or read from a dashboard photo by ML Kit on the phone and shown for him
 * to check. Nothing is logged until Confirm.
 */
@Composable
fun OdometerDialog(id: String, onConfirm: (Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Athena.colors
    val d by OdysseyStore.state.collectAsStateWithLifecycle()
    val f = d.car.fillups.firstOrNull { it.id == id }
    val last = d.car.lastReading()?.miles
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val snap = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp == null) return@rememberLauncherForActivityResult
        note = "Reading the photo…"
        Garage.readOdometer(bmp, last) { v ->
            if (v != null) { text = v.toString(); photo = true; note = "Read %,d mi from the photo. Check it before you confirm.".format(Locale.US, v) }
            else note = "Couldn't find the odometer in that photo. Type it, or try closer to the \"mi\"."
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(Radius.block)
        Column(Modifier.widthIn(max = 520.dp).clip(shape).background(c.raised).border(1.dp, c.edge, shape).padding(Space.xl)) {
            Line(f?.let { "Fill-up at ${it.place}" } ?: "Fill-up", Type.title, c.ink)
            f?.let { FuelStop.line(it) }?.let { Line(it, Type.small, c.ink2, Modifier.padding(top = Space.xs)) }
            Spacer(Modifier.height(Space.l))
            Kicker("Odometer")
            UnderlineField(text, { v -> text = v.filter { it.isDigit() }.take(6); photo = false }, last?.let { "Last %,d mi".format(Locale.US, it) } ?: "Miles on the dash", Modifier.fillMaxWidth(), keyboard = KeyboardType.Number)
            note?.let { Line(it, Type.caption, c.ink3, Modifier.padding(top = Space.xs)) }
            val miles = text.toIntOrNull()
            val low = miles != null && last != null && miles < last
            if (low) Line("That's below the last reading (%,d).".format(Locale.US, last), Type.caption, c.accentInk, Modifier.padding(top = Space.xs))
            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                PrimaryAction("Confirm", { if (miles != null && miles >= 1_000 && !low) onConfirm(miles, photo) })
                TextAction("Snap dashboard", { runCatching { snap.launch(null) } })
                TextAction("Cancel", onDismiss)
            }
            Line("Confirm logs it to Iris's fill-ups and to Moneta as Fuel. The photo is read on the phone and not kept.", Type.caption, c.ink3, Modifier.padding(top = Space.m))
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** The Car tab: the lease (Iris's fields marked), the pace in miles, and fill-ups without amounts (those are in Moneta). */
fun LazyListScope.car(d: OdysseyData, now: java.time.ZonedDateTime, onLease: (Lease) -> Unit, onAskIris: () -> Unit) {
    val car = d.car
    val l = car.lease
    item { Kicker("Lease", Modifier.padding(top = Space.l, bottom = Space.xs)) }
    item {
        Line(
            when {
                car.leaseAsked == 0L -> "Iris will look for the lease in your email first."
                car.leaseAnswered == 0L -> "Iris is looking for the lease in your email."
                l.missing().isEmpty() -> "All set. Fields from Iris are marked; change any of them."
                else -> "Still missing: ${l.missing().joinToString(", ")}."
            },
            Type.small, Athena.colors.ink2,
        )
    }
    fun row(key: String, label: String, value: String, hint: String, number: Boolean, set: (String) -> Lease) = item(key = "lease-$key") {
        LeaseField(label, value, hint, number, if (key in l.fromIris) l.sources[key]?.let { "From Iris: $it" } ?: "From Iris" else null) { onLease(set(it)) }
    }
    row("car", "Car", l.car.orEmpty(), "Make and model", false) { l.copy(car = it.ifBlank { null }) }
    row("start", "Lease start", l.start.orEmpty(), "2026-01-15", false) { l.copy(start = it.ifBlank { null }) }
    row("termMonths", "Term (months)", l.termMonths?.toString().orEmpty(), "36", true) { l.copy(termMonths = it.toIntOrNull()) }
    row("allowance", "Miles a year", l.allowance?.toString().orEmpty(), "10000", true) { l.copy(allowance = it.toIntOrNull()) }
    row("startOdometer", "Odometer at delivery", l.startOdometer?.toString().orEmpty(), "12", true) { l.copy(startOdometer = it.toIntOrNull()) }
    row("overageCents", "Overage (cents a mile)", l.overageCents?.toString().orEmpty(), "15", true) { l.copy(overageCents = it.toIntOrNull()) }
    row("delivered", "Delivered", l.delivered.orEmpty(), "2026-01-15", false) { l.copy(delivered = it.ifBlank { null }) }
    row("lessor", "Lessor", l.lessor.orEmpty(), "Example Finance", false) { l.copy(lessor = it.ifBlank { null }) }
    item { Row(Modifier.padding(top = Space.m)) { TextAction("Ask Iris again", onAskIris) } }
    LeasePace.pace(l, car.readings, d.drives, now.toLocalDate(), now.zone)?.let { p ->
        item { Kicker("Pace", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
        item {
            val (title, _) = LeasePace.words(p)
            FactRow("Now", title)
            FactRow("Pace", "%,d mi a year against %,d".format(Locale.US, p.perYear, p.allowance) + if (p.over > 0) ", ~%,d over by the end".format(Locale.US, p.over) else "")
            LeasePace.mpg(car.fillups)?.let { (m, avg) -> FactRow("Fuel", "%.1f mpg".format(Locale.US, m) + (avg?.let { " (usual %.1f)".format(Locale.US, it) } ?: "")) }
        }
    }
    item { Kicker("Fill-ups", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    val fills = car.fillups.filter { it.state == "logged" }.sortedByDescending { it.at }
    if (fills.isEmpty()) item { Line("Your fill-ups show here once you add the odometer at the pump. Amounts are in Moneta.", Type.body, Athena.colors.ink2) }
    for (f in fills.take(20)) item(key = "fill-${f.id}") {
        val z = ZoneId.systemDefault()
        Row(Modifier.fillMaxWidth().topRule(Athena.colors.rule).padding(vertical = Space.m)) {
            Line(DAY.format(Instant.ofEpochMilli(f.at).atZone(z)), Type.small, Athena.colors.ink2, Modifier.width(64.dp))
            Line(f.place, Type.body, Athena.colors.ink, Modifier.weight(1f), maxLines = 1)
            Line(listOfNotNull(f.gallons?.let { "%.1f gal".format(Locale.US, it) }, f.odometer?.let { "%,d mi".format(Locale.US, it) }).joinToString(" · "), Type.small, Athena.colors.ink2)
        }
    }
}

@Composable
private fun LeaseField(label: String, value: String, hint: String, number: Boolean, from: String?, save: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().topRule(Athena.colors.rule).padding(vertical = Space.s)) {
        Kicker(label)
        UnderlineField(v, { v = if (number) it.filter { ch -> ch.isDigit() }.take(7) else it.take(60) }, hint, Modifier.fillMaxWidth(), onSubmit = { if (v != value) save(v.trim()) }, keyboard = if (number) KeyboardType.Number else KeyboardType.Text)
        if (v != value) Row(Modifier.padding(top = Space.xs)) { TextAction("Save", { save(v.trim()) }, emphasis = true) }
        from?.let { Line(it, Type.caption, Athena.colors.ink3, Modifier.padding(top = Space.xs), maxLines = 2) }
    }
}
