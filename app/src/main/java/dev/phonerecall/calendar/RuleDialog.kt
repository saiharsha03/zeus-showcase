package dev.phonerecall.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.Relevance
import dev.phonerecall.data.Rule
import dev.phonerecall.data.RuleAction
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField

/**
 * "Hide events like this" / "Always show": offers a rule built from the event (its calendar and a
 * keyword), editable before it is saved. Also edits an existing rule from Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleDialog(
    eventTitle: String?,
    calendar: CalendarInfo?,
    existing: Rule?,
    startAction: RuleAction = RuleAction.HIDE,
    onSave: (Rule) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val c = Athena.colors
    var action by remember { mutableStateOf(existing?.action ?: startAction) }
    var words by remember { mutableStateOf(existing?.match ?: Relevance.suggestKeyword(eventTitle.orEmpty())) }
    var onlyThis by remember { mutableStateOf(existing?.calendarId != null || (existing == null && calendar != null)) }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(Radius.block)
        Column(Modifier.widthIn(max = 520.dp).clip(shape).background(c.raised).border(1.dp, c.edge, shape).padding(Space.xl)) {
            Line(if (existing != null) "Edit rule" else if (action == RuleAction.HIDE) "Hide events like this" else "Always show events like this", Type.title, c.ink)
            if (eventTitle != null) Line(eventTitle, Type.small, c.ink2, Modifier.padding(top = Space.xs), maxLines = 2)
            Spacer(Modifier.height(Space.l))
            Kicker("Do")
            Words(listOf("Hide them", "Always show"), if (action == RuleAction.HIDE) 0 else 1) { action = if (it == 0) RuleAction.HIDE else RuleAction.KEEP }
            Spacer(Modifier.height(Space.m))
            Kicker("When the title has")
            UnderlineField(words, { words = it }, "A word, or several with |", Modifier.fillMaxWidth())
            Line("Separate words with | to match any of them.", Type.caption, c.ink3, Modifier.padding(top = Space.xs))
            if (calendar != null || existing?.calendarId != null) {
                Spacer(Modifier.height(Space.m))
                Kicker("In")
                Words(listOf("Only ${calendar?.name ?: "that calendar"}", "Any calendar"), if (onlyThis) 0 else 1) { onlyThis = it == 0 }
            }
            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction("Save", {
                    val w = words.trim()
                    if (w.isNotEmpty()) {
                        onSave(
                            Rule(
                                id = existing?.id ?: "user-${System.nanoTime()}",
                                match = w, action = action,
                                calendarId = if (onlyThis) (calendar?.id ?: existing?.calendarId) else null,
                                kinds = existing?.kinds.orEmpty(),
                                seeded = existing?.seeded ?: false,
                                label = existing?.label.orEmpty(),
                            ),
                        )
                    }
                    onDismiss()
                })
                Spacer(Modifier.width(Space.s))
                TextAction("Cancel", onDismiss)
                if (onDelete != null) {
                    Spacer(Modifier.weight(1f))
                    TextAction("Delete", { onDelete(); onDismiss() })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    FlowRow(Modifier.offset(x = -Space.s), horizontalArrangement = Arrangement.Start) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.RadioButton) { onPick(i) }.padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
                Line(
                    o, Type.action, if (on) c.ink else c.ink2,
                    Modifier.drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) } },
                    maxLines = 1,
                )
            }
        }
    }
}
