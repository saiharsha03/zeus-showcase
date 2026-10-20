package dev.phonerecall.rhythms

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import java.time.LocalDate
import java.time.ZoneId

data class AppRow(val pkg: String, val label: String, val line: String, val skipped: Int)
data class CallRow(val key: String, val name: String, val line: String, val notes: List<Bookmark> = emptyList())
data class NudgeCardView(
    val key: String, val title: String, val line: String, val ways: List<Pair<String, String>>,
    /** Things to tell them, right before the call; ticked off after. */
    val notes: List<Bookmark> = emptyList(),
    /** A weather extreme at home, when there is one. */
    val weather: String? = null,
)

/** Everything the Rhythms page shows, built from the store (or made up for the screenshots). */
data class RhythmsView(
    val settings: RhythmsSettings = RhythmsSettings(),
    val usage: Boolean = false,
    val callLog: Boolean = false,
    val senses: Boolean = false,
    val apps: List<AppRow> = emptyList(),
    val calls: List<CallRow> = emptyList(),
    /** Apps he kept out himself: label and package. */
    val excluded: List<Pair<String, String>> = emptyList(),
    val nudge: NudgeCardView? = null,
    /** "Check on home" lines: heatwaves and heavy rain only. */
    val home: List<String> = emptyList(),
)

class RhythmsCalls(
    val toggle: () -> Unit = {}, val toggleNudges: () -> Unit = {}, val allowUsage: () -> Unit = {}, val allowCallLog: () -> Unit = {},
    val allowSenses: () -> Unit = {}, val forget: (String) -> Unit = {}, val exclude: (String) -> Unit = {}, val include: (String) -> Unit = {},
    val call: (String, String) -> Unit = { _, _ -> }, val later: (String) -> Unit = {}, val notToday: (String) -> Unit = {},
    val addNote: (String, String) -> Unit = { _, _ -> }, val tick: (Long) -> Unit = {}, val toggleHomeWeather: () -> Unit = {},
)

fun view(d: RhythmsData, now: Long, zone: ZoneId, usage: Boolean, callLog: Boolean, senses: Boolean, label: (String) -> String): RhythmsView {
    val since = LocalDate.now(zone).minusDays(14).toString()
    return RhythmsView(
        d.settings, usage, callLog, senses,
        apps = d.habits.map { h -> AppRow(h.pkg, h.label, Habits.line(h), d.skips.count { it.pkg == h.pkg && it.date >= since }) },
        calls = d.callHabits.map { h ->
            val awake = Rhythms.awake(d, h.key, CallHabits.zone(h), now)?.let { " · " + Awake.line(it, CallHabits.abbr(CallHabits.zone(h), CallHabits.upcoming(h, now))) }.orEmpty()
            CallRow(h.key, h.name, CallHabits.line(h, now, zone) + awake, d.bookmarks.filter { it.key == h.key })
        },
        excluded = d.settings.excluded.map { label(it) to it },
        nudge = Rhythms.live(d, now)?.let { (h, _) ->
            NudgeCardView(
                h.key, CallHabits.ask(h), CallHabits.nowThere(h, now), Rhythms.ways(d, h).map { it to Rhythms.wayLabel(it) },
                d.bookmarks.filter { it.key == h.key }, Rhythms.extras(d, h, now).lastOrNull { !it.startsWith("Tell them") },
            )
        },
        home = if (!d.settings.homeWeather) emptyList() else java.time.Instant.ofEpochMilli(now).atZone(dev.phonerecall.calls.CallRouting.INDIA).toLocalDate().let { today ->
            d.homeAlerts.filter { it.date >= today.toString() }.map { HomeWeather.words(it, d.settings.homeCity, today).second }
        },
    )
}

/** Themis → Rhythms. */
@Composable
fun RhythmsPage(gutter: Dp, back: () -> Unit) {
    val ctx = LocalContext.current
    val d by RhythmsStore.state.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf(false) }
    var callLog by remember { mutableStateOf(false) }
    var senses by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        RhythmsStore.load(ctx)
        usage = Rhythms.canUsage(ctx); callLog = Rhythms.canCallLog(ctx)
        senses = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        Rhythms.refreshSoon(ctx)
    }
    val askCallLog = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { callLog = it; if (it) Rhythms.refreshSoon(ctx) }
    val pm = ctx.packageManager
    val v = view(d, System.currentTimeMillis(), ZoneId.systemDefault(), usage, callLog, senses) { p ->
        runCatching { pm.getApplicationInfo(p, 0).loadLabel(pm).toString() }.getOrDefault(p)
    }
    fun bg(f: () -> Unit) = Thread { runCatching(f) }.start()
    RhythmsContent(
        v, gutter, back, problem,
        RhythmsCalls(
            toggle = { Rhythms.settings(ctx) { it.copy(on = !it.on) } },
            toggleNudges = { Rhythms.settings(ctx) { it.copy(nudges = !it.nudges) } },
            allowUsage = { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            allowCallLog = { askCallLog.launch(Manifest.permission.READ_CALL_LOG) },
            allowSenses = { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            forget = { k -> bg { Rhythms.forget(ctx, k) } },
            exclude = { p -> bg { Rhythms.exclude(ctx, p, true) } },
            include = { p -> bg { Rhythms.exclude(ctx, p, false) } },
            // In the app the tap on the card is the confirm, same as the notification's button.
            call = { k, via -> problem = Rhythms.placeCall(ctx, k, via) },
            later = { k -> bg { Rhythms.settle(ctx, k, NudgeEvent.LATER) } },
            notToday = { k -> bg { Rhythms.settle(ctx, k, NudgeEvent.NOT_TODAY) } },
            addNote = { k, text -> bg { RhythmsStore.load(ctx).callHabits.firstOrNull { it.key == k }?.let { Rhythms.bookmark(ctx, it.name, text) } } },
            tick = { id -> bg { Rhythms.tick(ctx, id) } },
            toggleHomeWeather = { Rhythms.settings(ctx) { it.copy(homeWeather = !it.homeWeather) } },
        ),
    )
}

@Composable
fun RhythmsContent(v: RhythmsView, gutter: Dp, back: () -> Unit, problem: String? = null, cb: RhythmsCalls = RhythmsCalls()) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.RHYTHMS.title(), gutter, subtitle = Place.RHYTHMS.subtitle()) { TextAction("Done", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            v.nudge?.let { n -> item { CallNudgeCard(n, cb, Modifier.padding(top = Space.s, bottom = Space.l)) } }
            problem?.let { item { Line(it, Type.small, c.alert, Modifier.padding(vertical = Space.s)) } }

            item { Kicker("Learning", Modifier.padding(top = Space.l, bottom = Space.s)) }
            item { Row_("Learn rhythms", "App times and calls over four weeks. Stays on the phone; Athena gets words only.", cb.toggle) { StatusWord(if (v.settings.on) "On" else "Off", if (v.settings.on) Tone.ACTIVE else Tone.QUIET) } }
            item { Row_("Usage access", "Which app is on screen, and when. Settings > Usage access > Zeus.", cb.allowUsage) { Allowed(v.usage) } }
            item { Row_("Call history", "Who you call and when, from the phone's call log. Numbers are never kept.", if (v.callLog) null else cb.allowCallLog) { Allowed(v.callLog) } }
            item { Row_("WhatsApp calls", "From WhatsApp's call notifications: the name and the call line only, never a message.", if (v.senses) null else cb.allowSenses) { Allowed(v.senses) } }

            item { Kicker("Calls", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            item { Row_("Call nudges", "At the usual time, if you haven't called: one notification, one reminder, then nothing.", cb.toggleNudges) { StatusWord(if (v.settings.nudges) "On" else "Off", if (v.settings.nudges) Tone.ACTIVE else Tone.QUIET) } }
            if (v.calls.isEmpty()) item { Empty("No regular calls yet. A call at about the same time on most days, or the same day each week, shows here.") }
            for (r in v.calls) item(key = "call-${r.key}") {
                Row_(r.name, r.line, below = { Notes(r.key, r.name, r.notes, cb) }) { TextAction("Forget", { cb.forget(r.key) }) }
            }

            item { Kicker("Home", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            item { Row_("Weather at home", "${v.settings.homeCity}. Only a heatwave (42°C and up) or heavy rain becomes a Check on home card.", cb.toggleHomeWeather) { StatusWord(if (v.settings.homeWeather) "On" else "Off", if (v.settings.homeWeather) Tone.ACTIVE else Tone.QUIET) } }
            for (line in v.home) item(key = "home-$line") { Line(line, Type.body, c.alert, Modifier.padding(vertical = Space.xs)) }

            item { Kicker("Apps", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            if (v.apps.isEmpty()) item { Empty("Nothing yet. Apps you open on three or more days a week, at about the same time, show here after two weeks.") }
            for (a in v.apps) item(key = "app-${a.pkg}") {
                Row_(a.label, a.line + if (a.skipped > 0) " · skipped ${a.skipped} in the last two weeks" else "", below = {
                    Row(Modifier.offset(x = -Space.s)) { TextAction("Forget", { cb.forget(a.pkg) }); TextAction("Never learn", { cb.exclude(a.pkg) }) }
                })
            }

            item { Kicker("Never learned", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            item { Line("Banking and Moneta's apps, password managers, health, dating and immigration apps are never learned.", Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp)) }
            for ((label, pkg) in v.excluded) item(key = "ex-$pkg") { Row_(label, "Excluded by you") { TextAction("Include", { cb.include(pkg) }) } }
        }
    }
}

/** The nudge as a card: the same words and buttons as the notification, plus Not today. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CallNudgeCard(n: NudgeCardView, cb: RhythmsCalls = RhythmsCalls(), modifier: Modifier = Modifier) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(modifier.fillMaxWidth().clip(shape).background(c.block(Tint.BRAIN, strong = true)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Kicker("From " + Place.RHYTHMS.title())
        Spacer(Modifier.height(Space.s))
        Line(n.title, Type.title, c.ink)
        Line(n.line, Type.body, c.ink2)
        n.weather?.let { Line(it, Type.body, c.alert) }
        if (n.notes.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            Kicker("Tell them")
            for (b in n.notes) NoteRow(b, cb)
        }
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            n.ways.firstOrNull()?.let { (via, label) -> PrimaryAction(label, { cb.call(n.key, via) }) }
            for ((via, label) in n.ways.drop(1)) TextAction(label, { cb.call(n.key, via) })
        }
        Spacer(Modifier.height(Space.xs))
        // The quiet choices on their own line, flush with the text (TextAction pads its label).
        Row(Modifier.offset(x = -Space.s)) {
            TextAction("Later", { cb.later(n.key) })
            TextAction("Not today", { cb.notToday(n.key) })
        }
    }
}

/** One thing to tell: tap to tick it off after the call. */
@Composable
private fun NoteRow(b: Bookmark, cb: RhythmsCalls) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Checkbox) { cb.tick(b.id) }, verticalAlignment = Alignment.CenterVertically) {
        Line(if (b.done) "✓" else "○", Type.body, if (b.done) c.ok else c.ink3)
        Spacer(Modifier.width(Space.s))
        Line(b.note, Type.body, if (b.done) c.ink3 else c.ink)
    }
}

/** The call row's quick-add: things to tell this person next time. */
@Composable
private fun Notes(key: String, name: String, notes: List<Bookmark>, cb: RhythmsCalls) {
    var text by remember(key) { mutableStateOf("") }
    for (b in notes) NoteRow(b, cb)
    Row(verticalAlignment = Alignment.CenterVertically) {
        UnderlineField(text, { text = it.take(200) }, "Something to tell $name", Modifier.weight(1f), style = Type.small, onSubmit = { if (text.isNotBlank()) { cb.addNote(key, text); text = "" } })
        TextAction("Add", { if (text.isNotBlank()) { cb.addNote(key, text); text = "" } }, enabled = text.isNotBlank())
    }
}

@Composable private fun Allowed(on: Boolean) = StatusWord(if (on) "Allowed" else "Allow", if (on) Tone.OK else Tone.ACTIVE)

@Composable private fun Empty(text: String) = Line(text, Type.small, Athena.colors.ink3, Modifier.padding(vertical = Space.m).widthIn(max = 560.dp))

/** Themis's flat row, as on the Music page. */
@Composable
private fun Row_(title: String, about: String? = null, onClick: (() -> Unit)? = null, below: (@Composable () -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        // With things under the text, the trailing word stays beside the title.
        verticalAlignment = if (below != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 520.dp))
            below?.invoke()
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}
