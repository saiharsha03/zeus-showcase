package dev.phonerecall.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonerecall.R

/*
 * Athena's design system. Every screen takes colour, type, spacing, radius and motion from here.
 * Rules (DESIGN-BRIEF.md): warm charcoal, paper ink, one saffron accent; no purple, gradients, glass,
 * pills, chips, dots or shimmer; motion only on state change, critically damped, settling in ~250 ms.
 */

/**
 * What a block is about. Each has its own muted surface tint, derived from the charcoal (or paper) base,
 * so categories read at a glance. Never purple, indigo or lilac. A new card kind picks one of these.
 */
enum class Tint { NEUTRAL, CALENDAR, TASKS, BRAIN, ALERT, AGENTS, SERVER, MUSIC, RAIN, SUN, WORK, SPORT, OOO }

/** Colour tokens. Screens never use a hex value; they use these names. */
@Immutable
data class AthenaColors(
    val surface: Color,      // the page
    val raised: Color,       // toasts, the lifted list row, dialogs
    val ink: Color,          // primary text and icons
    val ink2: Color,         // secondary text
    val ink3: Color,         // labels, hints, disabled (still WCAG AA on every tint)
    val rule: Color,         // hairlines inside lists
    val edge: Color,         // the 1 dp border that keeps a block distinct from the page
    val accent: Color,       // saffron fill: the one primary button, the selected underline
    val onAccent: Color,     // text on saffron
    val accentInk: Color,    // saffron as text (darker in light mode so it stays legible)
    val ok: Color,           // status word: fine
    val alert: Color,        // status word: needs him now
    val isDark: Boolean,
    /** Block surfaces per tint: normal, and slightly stronger for the top block. */
    private val tints: Map<Tint, Pair<Color, Color>>,
) {
    fun block(t: Tint, strong: Boolean = false): Color = tints.getValue(t).let { if (strong) it.second else it.first }
    /** Stat tiles: a step beyond the strong tint, toward ink, so they read on any block. */
    fun tile(t: Tint): Color = androidx.compose.ui.graphics.lerp(block(t, strong = true), ink, if (isDark) 0.07f else 0.05f)
}

private const val SAFFRON = 0xFFE8A33D

private fun tint(normal: Long, strong: Long) = Color(normal) to Color(strong)

val DarkColors = AthenaColors(
    surface = Color(0xFF15120F),
    raised = Color(0xFF2A251F),
    ink = Color(0xFFF2ECE2),
    ink2 = Color(0xFFBDB4A7),
    ink3 = Color(0xFFA0968A),
    rule = Color(0x24F2ECE2),
    edge = Color(0x17F2ECE2),
    accent = Color(SAFFRON),
    onAccent = Color(0xFF15120F),
    accentInk = Color(SAFFRON),
    ok = Color(0xFFA3C292),
    alert = Color(0xFFEE8070),
    isDark = true,
    tints = mapOf(
        Tint.NEUTRAL to tint(0xFF211D19, 0xFF28231E),
        Tint.CALENDAR to tint(0xFF18252A, 0xFF1B2E33),   // deep slate-teal
        Tint.TASKS to tint(0xFF24211E, 0xFF2B2723),      // warm graphite
        Tint.BRAIN to tint(0xFF2A2219, 0xFF33291C),      // saffron-tinted charcoal
        Tint.ALERT to tint(0xFF2E1D1A, 0xFF38211D),      // muted brick
        Tint.AGENTS to tint(0xFF20231A, 0xFF272B1E),     // deep olive
        Tint.SERVER to tint(0xFF1C2228, 0xFF212930),     // cool steel
        Tint.MUSIC to tint(0xFF2B1D18, 0xFF34221B),      // deep rust
        Tint.RAIN to tint(0xFF1A2229, 0xFF1E2931),       // weather: wet
        Tint.SUN to tint(0xFF2A2418, 0xFF332B1B),        // weather: dry and bright; birthdays
        Tint.WORK to tint(0xFF1A2420, 0xFF1E2C27),       // work: moss slate
        Tint.SPORT to tint(0xFF18261C, 0xFF1C2E21),      // sport: pitch green
        Tint.OOO to tint(0xFF29241D, 0xFF312B22),        // out of office: sand
    ),
)

val LightColors = AthenaColors(
    surface = Color(0xFFF3EEE5),
    raised = Color(0xFFE6DFD3),
    ink = Color(0xFF1C1814),
    ink2 = Color(0xFF4F463D),
    ink3 = Color(0xFF62594E),
    rule = Color(0x261C1814),
    edge = Color(0x1A1C1814),
    accent = Color(SAFFRON),
    onAccent = Color(0xFF1C1814),
    accentInk = Color(0xFF7A4C0B),
    ok = Color(0xFF37602F),
    alert = Color(0xFFA33427),
    isDark = false,
    tints = mapOf(
        Tint.NEUTRAL to tint(0xFFEAE4DA, 0xFFE3DCD0),
        Tint.CALENDAR to tint(0xFFE1EBE9, 0xFFD6E4E2),
        Tint.TASKS to tint(0xFFEBE5DC, 0xFFE3DCD1),
        Tint.BRAIN to tint(0xFFF3E5CC, 0xFFEEDCBC),
        Tint.ALERT to tint(0xFFF2DFDA, 0xFFEDD3CC),
        Tint.AGENTS to tint(0xFFE6E9D8, 0xFFDCE1CA),
        Tint.SERVER to tint(0xFFE0E6EB, 0xFFD5DDE4),
        Tint.MUSIC to tint(0xFFF1E0D6, 0xFFEBD4C7),
        Tint.RAIN to tint(0xFFDEE5EC, 0xFFD2DCE6),
        Tint.SUN to tint(0xFFF3E8D1, 0xFFEDDFC1),
        Tint.WORK to tint(0xFFDFE9E4, 0xFFD3E1DA),
        Tint.SPORT to tint(0xFFDCEADF, 0xFFCFE2D3),
        Tint.OOO to tint(0xFFEEE6D9, 0xFFE6DCCB),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Space Grotesk, the one typeface. Variable font, so each weight is a real instance. */
val Grotesk = FontFamily(grotesk(300), grotesk(400), grotesk(500), grotesk(700))

/** The type scale, defined once. Screens pick a role; they never set a size. */
object Type {
    /** Big numbers and the hero line: light weight, tight tracking. */
    val display = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Light, fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-2).sp)
    /** Screen titles and the top card's main line. */
    val headline = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.6).sp)
    /** Card titles. */
    val title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp)
    /** Running text. */
    val body = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    /** Secondary text, values in fact rows. */
    val small = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    /** Text buttons and nav labels. */
    val action = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    /** Nav labels and small captions, mixed case. */
    val caption = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
    /** Uppercase labels and status words. */
    val label = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.3.sp)
}

/** Spacing tokens (4 dp grid). */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    /** Page side margin: cover screen, inner screen. */
    val gutterNarrow = 16.dp
    val gutterWide = 20.dp
    /** Gap between blocks, both ways. */
    val blockGap = 12.dp
    /** Padding inside a block. */
    val blockPad = 18.dp
}

/** Corner radius scales with the element: about a fifth of its short side, 2 to 20 dp. */
object Radius {
    fun forSize(shortSide: Dp): Dp = (shortSide.value * 0.18f).coerceIn(2f, 20f).dp
    /** Blocks: 18 dp, 12 dp for one-line blocks. */
    val block = 18.dp
    val blockSmall = 12.dp
}

/** Motion: critically damped springs, used on state change only. Stiffness 500 settles in ~250 ms. */
object Motion {
    private const val STIFFNESS = 500f
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = STIFFNESS)
    val place: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = STIFFNESS, visibilityThreshold = IntOffset(1, 1))
}

private val LocalColors = staticCompositionLocalOf { DarkColors }

/** Read tokens with `Athena.colors`. */
object Athena {
    val colors: AthenaColors @Composable get() = LocalColors.current
}

@Composable
fun AthenaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val base = if (dark) DarkColors else LightColors
    // Easter egg: seven taps on the Olympus title turn the saffron accent lightning white for 10 s.
    val c = if (!Eggs.thunder) base
        else if (dark) base.copy(accent = Color(0xFFF2F5FF), accentInk = Color(0xFFF2F5FF), onAccent = Color(0xFF15120F))
        else base.copy(accent = Color(0xFFDCE4F2), accentInk = Color(0xFF2B3A55), onAccent = Color(0xFF1C1814))
    // Material 3 is only a set of primitives here; its scheme is mapped onto Athena's tokens so nothing purple leaks in.
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.ink2, tertiary = c.ink2,
        background = c.surface, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.raised, onSurfaceVariant = c.ink2, surfaceContainer = c.raised,
        outline = c.rule, outlineVariant = c.rule, error = c.alert,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.ink2, tertiary = c.ink2,
        background = c.surface, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.raised, onSurfaceVariant = c.ink2, surfaceContainer = c.raised,
        outline = c.rule, outlineVariant = c.rule, error = c.alert,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalColors provides c,
            LocalContentColor provides c.ink,
            LocalTextSelectionColors provides TextSelectionColors(c.accent, c.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

/** The tint for an event category, the same on Home and in Calendar. */
fun tintFor(category: dev.phonerecall.data.EventCategory): Tint = when (category) {
    dev.phonerecall.data.EventCategory.WORK -> Tint.WORK
    dev.phonerecall.data.EventCategory.PERSONAL -> Tint.CALENDAR
    dev.phonerecall.data.EventCategory.SPORT -> Tint.SPORT
    dev.phonerecall.data.EventCategory.BIRTHDAY -> Tint.SUN
    dev.phonerecall.data.EventCategory.TRAVEL -> Tint.RAIN
    dev.phonerecall.data.EventCategory.OOO -> Tint.OOO
    dev.phonerecall.data.EventCategory.HOLIDAY -> Tint.BRAIN
}
