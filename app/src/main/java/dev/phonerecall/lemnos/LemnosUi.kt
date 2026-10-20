package dev.phonerecall.lemnos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import java.text.DateFormat
import java.util.Date

/** Monospace for logs, paths and screens. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

/** Tabs as text: the selected one in full ink with a short saffron rule, like the nav. */
@Composable
fun TextTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, gutter: Dp, modifier: Modifier = Modifier) {
    val c = Athena.colors
    val accent = c.accent
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter - Space.s)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Text(
                t, style = Type.action, color = if (on) c.ink else c.ink3,
                modifier = Modifier.heightIn(min = 44.dp).selectable(on, role = Role.Tab) { onSelect(i) }.padding(horizontal = Space.s, vertical = Space.m)
                    .drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height - w), Offset(size.width, size.height - w), w) } },
            )
        }
    }
}

/** One row in a list: title, a quiet second line, and something at the end. Ruled on top. */
@Composable
fun ListRow(title: String, about: String? = null, modifier: Modifier = Modifier, titleStyle: TextStyle = Type.body, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, titleStyle.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            if (!about.isNullOrBlank()) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 560.dp), maxLines = 2)
        }
        Spacer(Modifier.width(Space.s))
        trailing()
    }
}

object Fmt {
    fun bytes(n: Long?): String {
        if (n == null) return "?"
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$n B" else "%.1f %s".format(v, u[i])
    }

    fun ago(epochS: Double, now: Long = System.currentTimeMillis()): String {
        val s = ((now / 1000.0) - epochS).toLong()
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date((epochS * 1000).toLong()))
        }
    }

    fun uptime(s: Long): String = when {
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} days"
    }

    fun severityTone(s: String): Tone = when (s) { "high" -> Tone.ALERT; "medium" -> Tone.ACTIVE; else -> Tone.QUIET }
}
