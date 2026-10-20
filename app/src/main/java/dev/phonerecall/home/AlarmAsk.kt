package dev.phonerecall.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.phonerecall.brain.AlarmRules
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField

/** "Other" on the routine question: his own words for what the alarm is for. */
@Composable
fun AlarmAskDialog(minute: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Athena.colors
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(Radius.block)
        Column(Modifier.widthIn(max = 520.dp).clip(shape).background(c.raised).border(1.dp, c.edge, shape).padding(Space.xl)) {
            Line("What's your ${AlarmRules.clock(minute)} alarm for?", Type.title, c.ink)
            Spacer(Modifier.height(Space.l))
            UnderlineField(text, { text = it }, "Like gym, school run", Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction("Save", { if (text.isNotBlank()) onSave(text.trim()); onDismiss() })
                Spacer(Modifier.width(Space.s))
                TextAction("Cancel", onDismiss)
            }
        }
    }
}
