package dev.phonerecall.fresh

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.Dest
import dev.phonerecall.R
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.setup.Check
import dev.phonerecall.setup.ShakeCalls
import dev.phonerecall.setup.ShakeView
import dev.phonerecall.setup.ShakedownContent
import dev.phonerecall.setup.ShakedownHub
import dev.phonerecall.setup.Status
import dev.phonerecall.setup.TeleteHub
import dev.phonerecall.setup.rememberTeleteFix
import dev.phonerecall.ui.ArtImage
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.delay

/** Setup's page as plain data (so it renders in shots). */
data class GenesisSetupView(val rows: List<SetupRow> = emptyList(), val left: Int = 0, val irisAsked: Boolean = false, val checking: Boolean = false)

class GenesisCalls(
    val fix: (Check) -> Unit = {},
    val skip: (String) -> Unit = {},
    val unskip: (String) -> Unit = {},
    val next: () -> Unit = {},
)

/** The steps' names, in the strip under the header. */
private val STEPS = listOf(Stage.SETUP to "Setup", Stage.THEOGONY to "Getting to know you", Stage.SHAKEDOWN to "Shakedown")

/** Genesis in Home's place: the step it is on. The splash is drawn over the whole shell (Shell.kt). */
@Composable
fun GenesisScreen(hub: BrainHub, gutter: Dp, wide: Boolean, go: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val gen = remember { GenesisHub.get(ctx) }
    val s by gen.state.collectAsStateWithLifecycle()
    val tele = remember { TeleteHub.get(ctx) }
    val t by tele.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { tele.refresh(force = true) }
    LaunchedEffect(t.checks) { gen.update(t.checks) }
    when (s.stage) {
        Stage.SETUP -> {
            val fix = rememberTeleteFix(hub, go)
            GenesisSetupContent(GenesisSetupView(s.rows, s.left, s.irisAsked, t.checking), gutter, wide, GenesisCalls(fix, gen::skip, gen::unskip, { gen.next() }))
        }
        Stage.THEOGONY -> if (s.planning) Planning(gutter) else dev.phonerecall.theogony.TheogonyScreen(gutter, wide) { gen.next() }
        Stage.SHAKEDOWN -> {
            val shake = remember { ShakedownHub.get(ctx) }
            val sh by shake.state.collectAsStateWithLifecycle()
            val fix = rememberTeleteFix(hub, go)
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(sh.running) { while (sh.running) { now = System.currentTimeMillis(); delay(1_000) } }
            val report = sh.history.firstOrNull()
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    ShakedownContent(
                        ShakeView(sh.running, sh.stage, now - sh.startedAt, sh.live, report, sh.history.getOrNull(1), sh.history, mic = sh.mic, micResult = sh.micResult),
                        gutter,
                        ShakeCalls(
                            back = { gen.next() },
                            run = { shake.start() },
                            stop = { shake.stop() },
                            // A failure's Fix: the Telete check's own fix, else its screen. Failures never block finishing.
                            fix = { ch ->
                                val c = t.checks.firstOrNull { it.id == ch.telete }
                                if (c != null && c.status != Status.OK && c.fix != null) fix(c) else ch.dest?.let { d -> Dest.entries.firstOrNull { it.name == d }?.let(go) }
                            },
                            answer = { id, yes -> shake.answer(id, yes) },
                            manual = { shake.micTest() },
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth().topRule(Athena.colors.rule).navigationBarsPadding().padding(horizontal = gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Line(if (sh.running) "Running every check. You can finish now; it keeps going." else "Failures don't hold you up: each one keeps its Fix in Telete.", Type.small, Athena.colors.ink3, Modifier.weight(1f))
                    Spacer(Modifier.width(Space.m))
                    PrimaryAction("Finish", { gen.next() })
                }
            }
        }
        Stage.SPLASH, Stage.DONE -> Box(Modifier.fillMaxSize().background(Athena.colors.surface))
    }
}

@Composable
private fun Steps(now: Stage, gutter: Dp) {
    val c = Athena.colors
    Row(Modifier.padding(start = gutter, end = gutter, bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        for ((st, label) in STEPS) Kicker(label, color = when { st == now -> c.accentInk; st < now -> c.ink2; else -> c.ink3 })
    }
}

@Composable
fun GenesisSetupContent(view: GenesisSetupView, gutter: Dp, wide: Boolean, calls: GenesisCalls) {
    val c = Athena.colors
    val groups = view.rows.groupBy { it.check.group }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.START.title(), gutter, kicker = "Fresh start", subtitle = Place.START.subtitle())
        Steps(Stage.SETUP, gutter)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xl)) {
            item(key = "summary") {
                Column(Modifier.widthIn(max = 640.dp).padding(top = Space.s, bottom = Space.s)) {
                    Line(
                        when { view.rows.isEmpty() -> "Looking at the phone…"; view.left == 0 -> "Everything is in place."; view.left == 1 -> "One thing left."; else -> "${view.left} things left." },
                        Type.title, c.ink,
                    )
                    Line(
                        "Zeus starts clean: your keys and the server pairing stayed, the rest is new. Each fix opens the exact screen; skip anything for now and it waits in Telete.",
                        Type.small, c.ink3, Modifier.padding(top = Space.xs),
                    )
                    if (view.irisAsked) Line("Iris is already gathering what she knows for Athena.", Type.small, c.accentInk, Modifier.padding(top = Space.xs))
                }
            }
            for ((group, rows) in groups) {
                item(key = "g-$group") {
                    Row(Modifier.padding(top = Space.xl, bottom = Space.xs).widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
                        val n = rows.count { it.left }
                        Kicker(group, Modifier.weight(1f), color = if (n > 0) c.accentInk else c.ink3)
                        if (n > 0) Line("$n left", Type.caption, c.ink3)
                    }
                }
                for (r in rows) item(key = "r-${r.check.id}") { SetupLine(r, calls, Modifier.widthIn(max = 640.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().topRule(c.rule).navigationBarsPadding().padding(horizontal = gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Line(if (view.left == 0) "Next: Athena gets to know you." else "You can go on with ${view.left} left.", Type.small, c.ink3, Modifier.weight(1f))
            Spacer(Modifier.width(Space.m))
            PrimaryAction("Continue", calls.next)
        }
    }
}

@Composable
private fun SetupLine(r: SetupRow, calls: GenesisCalls, modifier: Modifier) {
    val c = Athena.colors
    val ch = r.check
    Column(modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Line(ch.label, Type.body.copy(fontWeight = Type.action.fontWeight), if (r.skipped) c.ink3 else c.ink, maxLines = 1)
                Line(ch.why, Type.small, c.ink3)
                if (ch.detail.isNotBlank() && !r.skipped) Line(ch.detail, Type.small, if (ch.status == Status.MISSING) c.alert else c.ink2, Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(Space.m))
            val (word, tone) = when {
                r.skipped -> "Later" to Tone.QUIET
                ch.status == Status.OK -> "Ready" to Tone.OK
                ch.status == Status.MISSING -> "To do" to Tone.ALERT
                ch.status == Status.OPTIONAL -> "Optional" to Tone.QUIET
                else -> "Checking" to Tone.QUIET
            }
            StatusWord(word, tone, Modifier.padding(top = 4.dp))
        }
        if (ch.status != Status.OK) {
            Row(Modifier.padding(top = Space.xs).offset(x = -Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (ch.fix != null) TextAction(ch.fixLabel, { calls.fix(ch) }, emphasis = !r.skipped)
                if (ch.status == Status.MISSING) {
                    if (r.skipped) TextAction("Undo skip", { calls.unskip(ch.id) })
                    else TextAction(if (ch.id == "home-office") "Annabeth will ask" else "Skip for now", { calls.skip(ch.id) })
                }
            }
        }
    }
}

/** While Annabeth writes the questions (at most a few minutes; the built-in ones ask if she can't). */
@Composable
fun Planning(gutter: Dp) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.SEED.title(), gutter, kicker = "Fresh start", subtitle = Place.SEED.subtitle())
        Steps(Stage.THEOGONY, gutter)
        Column(Modifier.padding(horizontal = gutter, vertical = Space.xl).widthIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtImage(R.drawable.coin_annabeth, 48.dp)
                Spacer(Modifier.width(Space.m))
                Column {
                    Line("Annabeth is writing your questions", Type.title, c.ink)
                    Line("From what Iris and the phone already know, only what Zeus still needs.", Type.small, c.ink3)
                }
            }
        }
    }
}

/**
 * The finish: the Zeus coin settles while a saffron ring closes around it, then the words. Warm charcoal, one accent,
 * no glow, always on the dark page. A tap (or six seconds) opens Olympus. `animate` false draws the last frame (shots).
 */
@Composable
fun GenesisSplash(animate: Boolean = true, onDone: () -> Unit) = dev.phonerecall.ui.AthenaTheme(dark = true) { Splash(animate, onDone) }

@Composable
private fun Splash(animate: Boolean, onDone: () -> Unit) {
    val c = Athena.colors
    val ring = remember { Animatable(if (animate) 0f else 1f) }
    val coin = remember { Animatable(if (animate) 0f else 1f) }
    val words = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        coin.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        ring.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        words.animateTo(1f, tween(500))
        delay(6_000)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = Space.xl).widthIn(max = 420.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(196.dp)) {
                val accent = c.accent
                val rule = c.rule
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.dp.toPx()
                    drawArc(rule, 0f, 360f, false, style = Stroke(w))
                    drawArc(accent, -90f, 360f * ring.value, false, style = Stroke(w * 1.5f, cap = StrokeCap.Round))
                }
                // The silver disc is about two thirds of the art: zoomed so it fills the circle. Drawn at once (no async decode).
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_zeus), contentDescription = null,
                    modifier = Modifier.size(148.dp).scale(0.92f + 0.08f * coin.value).alpha(coin.value).clip(androidx.compose.foundation.shape.CircleShape)
                        .graphicsLayer { scaleX = 1.52f; scaleY = 1.52f },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            Spacer(Modifier.height(Space.xxl))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(words.value)) {
                Kicker("Genesis", color = c.accentInk)
                Spacer(Modifier.height(Space.s))
                Line("Ready, the owner.", Type.headline, c.ink)
                Spacer(Modifier.height(Space.xs))
                Line("Athena knows what she needs to start. The rest she learns as you go.", Type.body.copy(textAlign = TextAlign.Center), c.ink2)
                Spacer(Modifier.height(Space.xl))
                PrimaryAction("Open ${Place.HOME.title()}", onDone)
            }
        }
    }
}
