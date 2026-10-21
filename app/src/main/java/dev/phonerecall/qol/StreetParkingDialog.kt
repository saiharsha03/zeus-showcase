package dev.phonerecall.qol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField

/** Themis → Kairos → Street parking: his rule in plain words, what he calls the car, and "turn it around". */
@Composable
fun StreetParkingDialog(existing: StreetRule?, onSave: (StreetRule?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { StreetParkingForm(existing, onSave, onDismiss) }
}

/** The dialog's body, also rendered on its own in StreetParkingShots. */
@Composable
fun StreetParkingForm(existing: StreetRule?, onSave: (StreetRule?) -> Unit, onDismiss: () -> Unit) {
    val c = Athena.colors
    var words by remember { mutableStateOf(existing?.let(StreetParking::words).orEmpty()) }
    var car by remember { mutableStateOf(existing?.car.orEmpty()) }
    var turn by remember { mutableStateOf(existing?.turnAround ?: false) }
    val parsed = StreetParking.parse(words, car.trim())
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.widthIn(max = 520.dp).clip(shape).background(c.raised).border(1.dp, c.edge, shape).padding(Space.xl)) {
        Line("Street parking", Type.title, c.ink)
        Line("Each side and when it starts. Zeus reminds you 45 minutes before it flips, when the car is parked at Home.", Type.small, c.ink2, Modifier.padding(top = Space.xs))
        Spacer(Modifier.height(Space.l))
        Kicker("Sides")
        UnderlineField(words, { words = it }, "east Tue 8 AM, west Fri 8 AM", Modifier.fillMaxWidth())
        // Only when Zeus read it differently from how he typed it (or couldn't read it).
        val reads = parsed?.let(StreetParking::words)
        if (reads == null || !reads.equals(words.trim(), ignoreCase = true)) {
            Line(reads?.let { "Reads as: $it" } ?: "Two sides, each with a day and a time.", Type.caption, c.ink3, Modifier.padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.m))
        Kicker("The car")
        UnderlineField(car, { car = it }, "the car", Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.m))
        // Text buttons carry their own side padding: pulled back so the words line up with the fields.
        TextAction(if (turn) "Turn it around: on" else "Turn it around: off", { turn = !turn }, Modifier.offset(x = -Space.s))
        Spacer(Modifier.height(Space.xl))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Save", { parsed?.let { onSave(it.copy(turnAround = turn)); onDismiss() } })
            Spacer(Modifier.width(Space.s))
            TextAction("Cancel", onDismiss)
            if (existing != null) {
                Spacer(Modifier.weight(1f))
                TextAction("Clear", { onSave(null); onDismiss() })
            }
        }
    }
}
