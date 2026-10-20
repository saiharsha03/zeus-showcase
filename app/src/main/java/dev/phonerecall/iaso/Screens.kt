package dev.phonerecall.iaso

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
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
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the screen asks the hub to do. Previews pass the defaults. */
class IasoActions(
    val refresh: () -> Unit = {},
    val connect: () -> Unit = {},
    val snap: () -> Unit = {},
    val pick: () -> Unit = {},
    val byHand: () -> Unit = {},
    val water: (Int) -> Unit = {},
    val logWeight: (String) -> Unit = {},
    val share: (Boolean) -> Unit = {},
    val deleteMeal: (Long) -> Unit = {},
)

/** The photo on its way to Claude, then the confirm sheet. */
private data class MealFlow(val photo: ByteArray?, val draft: MealDraft?, val busy: Boolean, val error: String? = null)

/**
 * Iaso. Private: the window is FLAG_SECURE while it is open (no screenshots, a blank recents preview).
 * Health Connect is read on open; meals and weights are written only from his Save and Log taps.
 */
@Composable
fun IasoScreen(wide: Boolean, gutter: Dp) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val hub = remember { Iaso.get(app) }
    val s by hub.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<MealFlow?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var shotUri by rememberSaveable { mutableStateOf<String?>(null) }

    val activity = ctx as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val refresh = { scope.launch(Dispatchers.IO) { hub.refresh() }; Unit }
    LaunchedEffect(Unit) { refresh() }

    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
    val estimate = { bytes: ByteArray? ->
        if (bytes == null) notice = "Couldn't open that photo."
        else {
            flow = MealFlow(bytes, null, busy = true)
            scope.launch {
                val r = withContext(Dispatchers.IO) { hub.estimate(bytes) }
                flow = r.fold({ MealFlow(bytes, it, busy = false) }, { MealFlow(bytes, null, busy = false, error = dev.phonerecall.brain.AthenaEngine.plainError(it).takeUnless { m -> m.startsWith("Something") } ?: it.message) })
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = shotUri?.let(Uri::parse)
        if (ok && u != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { MealPhoto.fromUri(app.contentResolver, u) }
            // The full-size original (with whatever the camera wrote into it) is deleted at once.
            withContext(Dispatchers.IO) { runCatching { app.contentResolver.delete(u, null, null) } }
            estimate(bytes)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch { estimate(withContext(Dispatchers.IO) { MealPhoto.fromUri(app.contentResolver, u) }) }
    }

    val actions = IasoActions(
        refresh = refresh,
        connect = {
            when (s.status) {
                HcStatus.NOT_INSTALLED, HcStatus.UPDATE_NEEDED -> runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                }
                else -> runCatching { permissions.launch(HcPermissions.ALL) }
            }
        },
        snap = {
            val dir = File(app.cacheDir, "meal").apply { mkdirs() }
            val f = File(dir, "shot.jpg")
            val u = FileProvider.getUriForFile(app, "${app.packageName}.iaso.photos", f)
            shotUri = u.toString()
            runCatching { camera.launch(u) }.onFailure { notice = "No camera app to take the photo." }
        },
        pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        byHand = { flow = MealFlow(null, MealDraft.blank(ZonedDateTime.now()), busy = false) },
        water = { d -> scope.launch(Dispatchers.IO) { hub.addWater(d) } },
        logWeight = { text ->
            val kg = WeightTrend.parse(text)
            if (kg == null) notice = "That doesn't look like a weight."
            else scope.launch(Dispatchers.IO) { hub.logWeight(kg); notice = "Logged ${WeightTrend.format(kg)}." }
        },
        share = { on -> scope.launch(Dispatchers.IO) { hub.setShare(on) } },
        deleteMeal = { id -> scope.launch(Dispatchers.IO) { hub.deleteMeal(id) } },
    )
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(2600); notice = null } }

    val f = flow
    if (f != null) {
        BackHandler { flow = null }
        MealConfirmContent(
            f.photo, f.draft, f.busy, f.error, gutter,
            onChange = { d -> flow = f.copy(draft = d) },
            onSave = { d ->
                scope.launch {
                    val m = withContext(Dispatchers.IO) { hub.confirm(d) }
                    if (m != null) { flow = null; notice = if (m.inHc) "Saved to Iaso and Health Connect." else "Saved to Iaso." }
                }
            },
            onDiscard = { flow = null },
        )
    } else {
        IasoContent(s, ZonedDateTime.now(), gutter, wide, actions, notice)
    }
}

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

fun hm(min: Long): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
fun thousands(n: Long): String = String.format(Locale.US, "%,d", n)

@Composable
private fun Block(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(modifier.fillMaxWidth().clip(shape).background(c.block(Tint.NEUTRAL)).border(1.dp, c.edge, shape).padding(Space.blockPad), content = content)
}

@Composable
private fun Zone(title: String) = Kicker(title, Modifier.padding(top = Space.xl, bottom = Space.s))

@Composable
fun IasoContent(s: IasoState, now: ZonedDateTime, gutter: Dp, wide: Boolean, a: IasoActions = IasoActions(), notice: String? = null) {
    val c = Athena.colors
    val today = now.toLocalDate()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.HEALTH.title(), gutter, emblem = Place.HEALTH, subtitle = Place.HEALTH.subtitle()) {
            TextAction(if (s.reading) "Reading" else "Refresh", a.refresh, enabled = !s.reading)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxxl)) {
            StatusLine(s, a)
            if (notice != null) Line(notice, Type.small, c.accentInk, Modifier.padding(vertical = Space.s))
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Column(Modifier.weight(1f)) { BodyColumn(s, now, today) }
                    Column(Modifier.weight(1f)) { LogColumn(s, now, today, a) }
                }
            } else {
                BodyColumn(s, now, today)
                LogColumn(s, now, today, a)
            }
            Zone("Privacy")
            ShareToggle(s.shareWithBrain) { a.share(!s.shareWithBrain) }
            Line(
                "Health stays on this phone. It never goes on the lock screen or the watch, or on a widget unless you add the Iaso widget, and never to Iris. Photos go to Claude once, for the estimate, without their location.",
                Type.small, c.ink3, Modifier.padding(top = Space.s),
            )
        }
    }
}

@Composable
private fun StatusLine(s: IasoState, a: IasoActions) {
    val c = Athena.colors
    val (text, action) = when (s.status) {
        HcStatus.NOT_INSTALLED -> "Health Connect isn't on this phone. Samsung Health and the Galaxy Watch reach Zeus through it." to "Get it"
        HcStatus.UPDATE_NEEDED -> "Health Connect needs an update before Iaso can read it." to "Update"
        HcStatus.NOT_PERMITTED -> "Allow Iaso to read steps, sleep, heart rate, workouts and weight from Health Connect." to "Connect"
        HcStatus.PARTIAL -> "Some readings are off in Health Connect." to "Allow"
        HcStatus.READY -> (if (s.loaded && s.report.readAt > 0 && s.report.days.all { it.steps == null } && s.report.sleep.isEmpty())
            "Nothing from the watch yet. In Samsung Health, turn on Health Connect sync." to null else null to null)
    }
    if (text == null) { if (s.error != null) Line(s.error, Type.small, c.alert, Modifier.padding(bottom = Space.s)); return }
    Block(Modifier.padding(bottom = Space.s)) {
        Line(text, Type.body, c.ink)
        if (action != null) PrimaryAction(action, a.connect, Modifier.padding(top = Space.m))
    }
}

@Composable
private fun BodyColumn(s: IasoState, now: ZonedDateTime, today: LocalDate) {
    val c = Athena.colors
    val d = s.report.day(today)
    val night = s.report.lastNight(today)
    Zone("Today")
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Rings(
                listOf(
                    (d?.steps ?: 0L) / Goals.STEPS.toFloat(),
                    (night?.asleepMin ?: 0L) / Goals.SLEEP_MIN.toFloat(),
                    ((d?.activeKcal ?: 0.0) / Goals.ACTIVE_KCAL).toFloat(),
                ),
                Modifier.size(132.dp),
            )
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                RingFact(c.accent, "Steps", d?.steps?.let { thousands(it) } ?: "No data", "of ${thousands(Goals.STEPS)}")
                RingFact(c.ink, "Sleep", night?.let { hm(it.asleepMin) } ?: "No data", "of ${hm(Goals.SLEEP_MIN)}")
                RingFact(c.ok, "Active", d?.activeKcal?.let { "${Math.round(it)} kcal" } ?: "No data", "of ${Goals.ACTIVE_KCAL.toInt()}")
            }
        }
        if (s.report.workoutsToday.isNotEmpty()) {
            Spacer(Modifier.height(Space.m))
            for (w in s.report.workoutsToday) FactRow(w.name, "${hm(w.minutes)} at ${TIME.format(Instant.ofEpochMilli(w.start).atZone(now.zone))}")
        }
    }

    Zone("Sleep last night")
    Block {
        if (night == null) Line("No sleep recorded. Wear the watch to bed and it lands here in the morning.", Type.body, c.ink2)
        else {
            Row(verticalAlignment = Alignment.Bottom) {
                Line(hm(night.asleepMin), Type.headline, c.ink)
                Spacer(Modifier.width(Space.s))
                if (night.asleepMin < Goals.SHORT_SLEEP_MIN) StatusWord("Short", Tone.ALERT, Modifier.padding(bottom = 6.dp))
                else if (night.asleepMin >= Goals.SLEEP_MIN) StatusWord("Rested", Tone.OK, Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(Space.s))
            FactRow("In bed", "${TIME.format(Instant.ofEpochMilli(night.start).atZone(now.zone))} to ${TIME.format(Instant.ofEpochMilli(night.end).atZone(now.zone))}")
            night.deepMin?.let { FactRow("Deep", hm(it)) }
            night.remMin?.let { FactRow("REM", hm(it)) }
            val week = s.report.sleep.filter { !it.date.isBefore(today.minusDays(6)) }
            if (week.size >= 3) FactRow("Week", "${hm(week.sumOf { it.asleepMin } / week.size)} a night")
        }
    }

    Zone("Heart")
    Block {
        val rest = d?.restingHr ?: s.report.days.lastOrNull { it.restingHr != null }?.restingHr
        Row(verticalAlignment = Alignment.Bottom) {
            Line(rest?.let { "$it" } ?: "--", Type.headline, c.ink)
            Spacer(Modifier.width(Space.s))
            Line("bpm resting", Type.small, c.ink3, Modifier.padding(bottom = 4.dp))
        }
        val pts = s.report.todayHr
        if (pts.size >= 2) {
            Spacer(Modifier.height(Space.m))
            Spark(pts.map { it.bpm.toDouble() }, Modifier.fillMaxWidth().height(64.dp), c.alert)
            Row(Modifier.fillMaxWidth()) {
                Line(TIME.format(Instant.ofEpochMilli(pts.first().at).atZone(now.zone)), Type.caption, c.ink3, Modifier.weight(1f))
                Line("${pts.minOf { it.bpm }} to ${pts.maxOf { it.bpm }} bpm today", Type.caption, c.ink3)
            }
        } else Line("Today's heart rate shows here once the watch syncs.", Type.small, c.ink2, Modifier.padding(top = Space.xs))
        val restWeek = s.report.days.mapNotNull { it.restingHr }
        if (restWeek.size >= 3) FactRow("Week", "Resting ${restWeek.min()} to ${restWeek.max()} bpm")
    }
}

@Composable
private fun RingFact(color: Color, label: String, value: String, of: String) {
    val c = Athena.colors
    Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(Space.s))
        Column {
            Kicker(label)
            Row(verticalAlignment = Alignment.Bottom) {
                Line(value, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Spacer(Modifier.width(Space.xs))
                Line(of, Type.caption, c.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LogColumn(s: IasoState, now: ZonedDateTime, today: LocalDate, a: IasoActions) {
    val c = Athena.colors
    val meals = s.mealsOn(today, now.zone)
    Zone("Food today")
    Block {
        if (meals.isEmpty()) Line("Nothing logged yet. Snap your plate and Claude suggests the numbers; you check them before anything is saved.", Type.body, c.ink2)
        else {
            Row(verticalAlignment = Alignment.Bottom) {
                Line("${meals.sumOf { it.kcal }}", Type.headline, c.ink)
                Spacer(Modifier.width(Space.s))
                Line("kcal", Type.small, c.ink3, Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Line("P ${Math.round(meals.sumOf { it.proteinG })}g  C ${Math.round(meals.sumOf { it.carbsG })}g  F ${Math.round(meals.sumOf { it.fatG })}g", Type.caption, c.ink2, Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(Space.s))
            for (m in meals) MealRow(m, now) { a.deleteMeal(m.id) }
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Snap meal", a.snap)
            Spacer(Modifier.width(Space.s))
            TextAction("Photo", a.pick)
            TextAction("Type it", a.byHand)
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s).topRule(c.rule).padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Kicker("Water", Modifier.width(72.dp))
            Line("${s.water[today] ?: 0} glasses", Type.body, c.ink, Modifier.weight(1f))
            if ((s.water[today] ?: 0) > 0) TextAction("Less", { a.water(-1) })
            TextAction("Add a glass", { a.water(1) }, emphasis = true)
        }
    }

    Zone("Weight")
    Block {
        val pts = s.weights
        val last = pts.lastOrNull()
        val slope = WeightTrend.weeklySlope(pts, now.toInstant().toEpochMilli())
        Row(verticalAlignment = Alignment.Bottom) {
            Line(last?.let { WeightTrend.format(it.kg) } ?: "No readings", Type.headline, c.ink)
            Spacer(Modifier.weight(1f))
            WeightTrend.words(slope)?.let { StatusWord(it, Tone.QUIET, Modifier.padding(bottom = 6.dp)) }
        }
        if (last != null) Line("Last ${Fmt.ago(last.at, now)}", Type.caption, c.ink3)
        val daily = WeightTrend.daily(pts.filter { it.at >= now.minusDays(60).toInstant().toEpochMilli() }, now.zone)
        if (daily.size >= 2) {
            Spacer(Modifier.height(Space.m))
            WeightChart(daily, WeightTrend.smooth(daily), Modifier.fillMaxWidth().height(120.dp))
            Row(Modifier.fillMaxWidth()) {
                Line(Fmt.day(daily.first().at, now), Type.caption, c.ink3, Modifier.weight(1f))
                Line("Dots are readings, the line is the trend", Type.caption, c.ink3)
            }
        }
        var entry by rememberSaveable { mutableStateOf("") }
        Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(entry, { entry = it }, "Weight in kg", Modifier.weight(1f), keyboard = KeyboardType.Decimal, onSubmit = { a.logWeight(entry); entry = "" })
            Spacer(Modifier.width(Space.s))
            PrimaryAction("Log", { a.logWeight(entry); entry = "" })
        }
    }
}

@Composable
private fun MealRow(m: Meal, now: ZonedDateTime, onDelete: () -> Unit) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule).padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(m.dish, Type.body, c.ink, maxLines = 1)
            Line(listOf(m.type.label, TIME.format(Instant.ofEpochMilli(m.at).atZone(now.zone)), m.portion).filter { it.isNotBlank() }.joinToString(", "), Type.caption, c.ink3, maxLines = 1)
        }
        Spacer(Modifier.width(Space.m))
        Line("${m.kcal}", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
        Spacer(Modifier.width(Space.xs))
        TextAction("Remove", onDelete)
    }
}

@Composable
private fun ShareToggle(on: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).clickable(role = Role.Switch, onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line("Share a one-line summary with Athena", Type.body.copy(fontWeight = Type.action.fontWeight), if (on) c.ink else c.ink2)
            Line("Only words like \"slept 5h, short\". Never heart rate, weight or meals.", Type.small, c.ink3)
        }
        Spacer(Modifier.width(Space.m))
        StatusWord(if (on) "On" else "Off", if (on) Tone.ACTIVE else Tone.QUIET)
    }
}

/** Three concentric rings, outside in: steps, sleep, active. Past 100% the ring stays full. */
@Composable
fun Rings(progress: List<Float>, modifier: Modifier = Modifier) {
    val c = Athena.colors
    val colors = listOf(c.accent, c.ink, c.ok)
    val track = c.rule
    Canvas(modifier.semantics { contentDescription = "Steps, sleep and active rings" }) {
        val w = size.minDimension * 0.095f
        val gap = w * 0.45f
        for (i in progress.indices) {
            val inset = w / 2 + i * (w + gap)
            val box = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
            val tl = Offset((size.width - box.width) / 2, (size.height - box.height) / 2)
            drawArc(track, 0f, 360f, false, tl, box, style = Stroke(w))
            val p = progress[i].coerceIn(0f, 1f)
            if (p > 0f) drawArc(colors[i % colors.size], -90f, 360f * p, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** A plain line over its own range, for today's heart rate. */
@Composable
fun Spark(values: List<Double>, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max().coerceAtLeast(lo + 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - ((v - lo) / (hi - lo) * size.height * 0.85 + size.height * 0.075).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Daily readings as dots and the smoothed trend as a line, both on time. */
@Composable
fun WeightChart(readings: List<WeightPoint>, trend: List<WeightPoint>, modifier: Modifier) {
    val c = Athena.colors
    val dot = c.ink3
    val line = c.accent
    val rule = c.rule
    Canvas(modifier.semantics { contentDescription = "Weight trend" }) {
        val all = readings + trend
        val t0 = all.minOf { it.at }
        val t1 = all.maxOf { it.at }.coerceAtLeast(t0 + 1)
        val lo = all.minOf { it.kg } - 0.3
        val hi = all.maxOf { it.kg } + 0.3
        fun x(t: Long) = ((t - t0).toDouble() / (t1 - t0) * size.width).toFloat()
        fun y(kg: Double) = (size.height - (kg - lo) / (hi - lo) * size.height).toFloat()
        for (i in 0..2) { val yy = size.height * i / 2f; drawLine(rule, Offset(0f, yy), Offset(size.width, yy), 1f) }
        for (p in readings) drawCircle(dot, 3.dp.toPx(), Offset(x(p.at), y(p.kg)))
        val path = Path()
        trend.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.at), y(p.kg)) else path.lineTo(x(p.at), y(p.kg)) }
        drawPath(path, line, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * The confirm sheet. Claude's numbers arrive as editable fields; Save is the only way anything is kept.
 * While the estimate runs, the photo shows with one line saying where it went.
 */
@Composable
fun MealConfirmContent(
    photo: ByteArray?, draft: MealDraft?, busy: Boolean, error: String?, gutter: Dp,
    onChange: (MealDraft) -> Unit, onSave: (MealDraft) -> Unit, onDiscard: () -> Unit,
) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (draft?.fromPhoto == false) "Add a meal" else "Check the meal", gutter, kicker = Place.HEALTH.title()) { TextAction("Discard", onDiscard) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxxl).widthIn(max = 640.dp)) {
            if (photo != null) {
                val bmp = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
                if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(Radius.block)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(Space.m))
            }
            when {
                busy -> Line("Claude is looking at the photo. It was sent once, without its location.", Type.body, c.ink2)
                error != null -> {
                    Line(error, Type.body, c.alert)
                    TextAction("Type it instead", { onChange(MealDraft.blank(ZonedDateTime.now())) }, Modifier.padding(top = Space.s))
                }
            }
            if (draft != null) {
                if (draft.fromPhoto) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker("Claude's guess", Modifier.weight(1f))
                        draft.confidence?.let { StatusWord("${it} confidence", if (it == "low") Tone.ALERT else Tone.QUIET) }
                    }
                    Line("Check the numbers. Nothing is saved until you tap Save.", Type.small, c.ink3, Modifier.padding(top = Space.xxs))
                    draft.note?.let { Line(it, Type.small, c.ink2, Modifier.padding(top = Space.xs)) }
                }
                Field("Dish", draft.dish) { onChange(draft.copy(dish = it)) }
                Field("Portion", draft.portion) { onChange(draft.copy(portion = it)) }
                Field("Calories", draft.kcal, KeyboardType.Number) { onChange(draft.copy(kcal = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Box(Modifier.weight(1f)) { Field("Protein g", draft.protein, KeyboardType.Decimal) { onChange(draft.copy(protein = it)) } }
                    Box(Modifier.weight(1f)) { Field("Carbs g", draft.carbs, KeyboardType.Decimal) { onChange(draft.copy(carbs = it)) } }
                    Box(Modifier.weight(1f)) { Field("Fat g", draft.fat, KeyboardType.Decimal) { onChange(draft.copy(fat = it)) } }
                }
                // Text actions carry their own 8 dp; the row steps back so the first word lines up with the fields.
                Row(Modifier.padding(top = Space.m).offset(x = -Space.s), verticalAlignment = Alignment.CenterVertically) {
                    for (t in MealType.entries) TextAction(t.label, { onChange(draft.copy(type = t)) }, emphasis = t == draft.type)
                }
                if (draft.items.size > 1) {
                    Kicker("On the plate", Modifier.padding(top = Space.l, bottom = Space.xs))
                    for (i in draft.items) FactRow(i.name.take(18), listOf(i.portion, "${i.kcal} kcal").filter { it.isNotBlank() }.joinToString(", "), labelWidth = 120.dp)
                }
                draft.problem?.let { Line(it, Type.small, c.ink3, Modifier.padding(top = Space.m)) }
                Row(Modifier.padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryAction("Save", { if (draft.problem == null) onSave(draft) })
                    Spacer(Modifier.width(Space.s))
                    TextAction("Discard", onDiscard)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onValue: (String) -> Unit) {
    Column(Modifier.padding(top = Space.m)) {
        Kicker(label)
        UnderlineField(value, onValue, label, Modifier.fillMaxWidth(), keyboard = keyboard)
    }
}

/** Short dates and "how long ago", Iaso's own (Olympus's Fmt is for events). */
object Fmt {
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    fun day(at: Long, now: ZonedDateTime): String = DAY.format(Instant.ofEpochMilli(at).atZone(now.zone))
    fun ago(at: Long, now: ZonedDateTime): String {
        val d = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate(), now.toLocalDate())
        return when (d) { 0L -> "today"; 1L -> "yesterday"; in 2..13 -> "$d days ago"; else -> day(at, now) }
    }
}

// ---- Olympus ----

/** The week on Olympus: one card, in the app only. Not part of the board, so it never reaches the brain, the Now Bar or a widget. */
@Composable
fun IasoWeekCard(open: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val hub = remember { Iaso.get(app) }
    val s by hub.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { if (s.report.readAt < System.currentTimeMillis() - 30 * 60_000L) hub.refresh() } }
    if (s.status == HcStatus.NOT_INSTALLED && s.meals.isEmpty() && s.localWeights.isEmpty()) return
    IasoWeekContent(s, ZonedDateTime.now(), open)
}

@Composable
fun IasoWeekContent(s: IasoState, now: ZonedDateTime, open: () -> Unit) {
    val c = Athena.colors
    val today = now.toLocalDate()
    val week = s.report.days.filter { !it.date.isBefore(today.minusDays(6)) }
    val steps = week.mapNotNull { it.steps }
    val nights = s.report.sleep.filter { !it.date.isBefore(today.minusDays(6)) }
    val slope = WeightTrend.weeklySlope(s.weights, now.toInstant().toEpochMilli())
    val shape = RoundedCornerShape(Radius.block)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.block(Tint.NEUTRAL)).border(1.dp, c.edge, shape)
            .clickable(role = Role.Button, onClick = open).padding(Space.blockPad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("${Place.HEALTH.title()}, this week", Modifier.weight(1f))
            if (s.status == HcStatus.NOT_PERMITTED) StatusWord("Connect", Tone.ACTIVE)
        }
        Spacer(Modifier.height(Space.s))
        if (steps.isEmpty() && nights.isEmpty() && s.weights.isEmpty()) {
            Line(if (s.status == HcStatus.NOT_PERMITTED) "Connect Health Connect to see sleep, steps and heart rate from the watch." else "Your week shows here once the watch syncs.", Type.body, c.ink2)
            return@Column
        }
        if (nights.isNotEmpty()) FactRow("Sleep", "${hm(nights.sumOf { it.asleepMin } / nights.size)} a night", if (nights.count { it.asleepMin < Goals.SHORT_SLEEP_MIN } >= 3) "Short" else null, Tone.ALERT)
        if (steps.isNotEmpty()) FactRow("Steps", "${thousands(steps.sum() / steps.size)} a day", if (steps.count { it >= Goals.STEPS } >= 5) "On goal" else null, Tone.OK)
        WeightTrend.words(slope)?.let { FactRow("Weight", it) }
        val meals = s.meals.count { !Instant.ofEpochMilli(it.at).atZone(now.zone).toLocalDate().isBefore(today.minusDays(6)) }
        if (meals > 0) FactRow("Food", "$meals meals logged")
    }
}

/** Iaso's line icon (a heart with a pulse), drawn like AthenaIcons: 24 dp grid, 1.6 stroke, round caps. */
val IasoIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    val b = androidx.compose.ui.graphics.vector.ImageVector.Builder("iaso", 24.dp, 24.dp, 24f, 24f)
    for (d in listOf("M12 19.5 C6.5 15.6 3.8 12.6 3.8 9.2 C3.8 6.7 5.7 4.8 8.1 4.8 C9.7 4.8 11.1 5.7 12 7 C12.9 5.7 14.3 4.8 15.9 4.8 C18.3 4.8 20.2 6.7 20.2 9.2 C20.2 12.6 17.5 15.6 12 19.5 Z", "M7 11.5 H9.5 L11 9 L13 14 L14.5 11.5 H17")) {
        b.addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(), fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    }
    b.build()
}
