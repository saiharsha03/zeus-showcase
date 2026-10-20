package dev.phonerecall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.R

/** What a status word means. Rendered as uppercase coloured text, never a dot or a badge. */
enum class Tone { OK, ACTIVE, ALERT, QUIET }

@Composable
fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> Athena.colors.ok
    Tone.ACTIVE -> Athena.colors.accentInk
    Tone.ALERT -> Athena.colors.alert
    Tone.QUIET -> Athena.colors.ink3
}

@Composable
fun StatusWord(word: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(word.uppercase(), modifier, style = Type.label, color = toneColor(tone), maxLines = 1)
}

/** Small uppercase label for a zone or a fact. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Athena.colors.ink3) {
    Text(text.uppercase(), modifier, style = Type.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A full-width hairline drawn at the top of a zone. */
fun Modifier.topRule(color: Color): Modifier = drawBehind {
    val w = 1.dp.toPx().coerceAtLeast(1f)
    drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
}

/** A labelled value row: LABEL then value, optional status word at the end. Replaces "a · b" meta lines. */
@Composable
fun FactRow(label: String, value: String, status: String? = null, tone: Tone = Tone.QUIET, labelWidth: Dp = 92.dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
        Kicker(label, Modifier.width(labelWidth).padding(top = 4.dp))
        Text(value, Modifier.weight(1f), style = Type.small, color = Athena.colors.ink)
        if (status != null) {
            Spacer(Modifier.width(Space.s))
            StatusWord(status, tone, Modifier.padding(top = 4.dp))
        }
    }
}

/** A text button: the default action style. 44 dp tall for touch, no container. */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Athena.colors
    val color = when { !enabled -> c.ink3; emphasis -> c.accentInk; else -> c.ink }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Radius.forSize(44.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, style = Type.action, color = color, maxLines = 1) }
}

/** The one filled button a screen may have. */
@Composable
fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Athena.colors
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Radius.forSize(44.dp)))
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.action, color = c.onAccent, maxLines = 1) }
}

/**
 * The one header style, app-wide. Title left, optional actions right. A place's header carries its
 * 40 dp coin emblem left of the name.
 */
@Composable
fun ScreenHeader(
    title: String,
    gutter: Dp,
    emblem: Place? = null,
    kicker: String? = null,
    subtitle: String? = null,
    onTitleTap: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = gutter, end = gutter - Space.s, top = Space.l, bottom = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emblem?.emblem != null) { Emblem(emblem, 40.dp); Spacer(Modifier.width(Space.m)) }
        Column(Modifier.weight(1f)) {
            if (kicker != null) Kicker(kicker)
            Text(
                title,
                if (onTitleTap != null) Modifier.clickable(interactionSource = null, indication = null, onClick = onTitleTap) else Modifier,
                style = Type.headline, color = Athena.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = Type.small, color = Athena.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Plain text in a role, coloured from tokens. */
@Composable
fun Line(text: String, style: TextStyle, color: Color = Athena.colors.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A flat text field: text on the page with a hairline under it, saffron when focused. No outlined box. */
@Composable
fun UnderlineField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    onSubmit: (() -> Unit)? = null,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    enabled: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val c = Athena.colors
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val line = if (focused) c.accent else c.rule
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        singleLine = true,
        visualTransformation = visualTransformation,
        textStyle = style.copy(color = c.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboard,
            imeAction = if (onSubmit != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
            capitalization = if (keyboard == androidx.compose.ui.text.input.KeyboardType.Password) androidx.compose.ui.text.input.KeyboardCapitalization.None else androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
            autoCorrectEnabled = keyboard != androidx.compose.ui.text.input.KeyboardType.Password,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .heightIn(min = 48.dp)
            .watchFocus { focused = it }
            .drawBehind {
                val w = (if (focused) 2.dp else 1.dp).toPx()
                drawLine(line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            },
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = Space.m), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = style, color = c.ink3, maxLines = 1)
                inner()
            }
        },
    )
}

private fun Modifier.watchFocus(onChange: (Boolean) -> Unit) =
    this.then(Modifier.onFocusChanged { onChange(it.isFocused) })
