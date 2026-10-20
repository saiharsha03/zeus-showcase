package dev.phonerecall.music

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
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
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MUSIC_KINDS = setOf(ProposalKind.PLAY_MUSIC, ProposalKind.MUSIC_WHILE_DRIVING)

/** The last music proposal that ran by itself in the past half hour: "Played automatically" with Undo. */
fun autoPlayed(proposals: List<Proposal>, now: Long): Proposal? = proposals.firstOrNull {
    it.auto && it.status == ProposalStatus.CONFIRMED && it.steps.any { s -> s.kind in MUSIC_KINDS } && now - (it.decidedAt ?: 0L) < 30 * 60_000L
}

/**
 * Olympus: what is playing, with Pause/Play, Back and Next. These taps are his own, so they act at once
 * (no proposal); proposals are for what Athena or drive mode suggests. Hidden when nothing plays.
 */
@Composable
fun NowPlayingCard(hub: BrainHub) {
    val ctx = LocalContext.current
    val core = remember { MusicCore.get(ctx) }
    val now by core.now.collectAsStateWithLifecycle()
    val proposals by hub.proposals.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { core.refresh() } }
            delay(5_000)
        }
    }
    val auto = autoPlayed(proposals, System.currentTimeMillis())
    NowPlayingContent(
        now, auto,
        act = { a -> scope.launch(Dispatchers.IO) { runCatching { core.control(a) }; runCatching { core.refresh() } } },
        undo = { hub.undo(it) },
    )
}

@Composable
fun NowPlayingContent(now: NowPlaying?, auto: Proposal?, act: (String) -> Unit, undo: (Long) -> Unit) {
    if (now == null && auto == null) return
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.block(Tint.MUSIC)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(now?.let { if (it.playing) "Now playing" else "Paused" } ?: "Music", Modifier.weight(1f))
            now?.let { StatusWord(it.source.label, Tone.QUIET) }
        }
        if (now != null) {
            Spacer(Modifier.height(Space.s))
            Line(now.title, Type.title, c.ink, maxLines = 1)
            if (now.artist.isNotBlank()) Line(now.artist, Type.small, c.ink2, maxLines = 1)
            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                TextAction("Back", { act("previous") })
                TextAction(if (now.playing) "Pause" else "Play", { act(if (now.playing) "pause" else "resume") }, emphasis = true)
                TextAction("Next", { act("next") })
            }
        }
        if (auto != null) {
            Row(Modifier.fillMaxWidth().topRule(c.rule).padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                val what = auto.steps.firstOrNull { it.kind in MUSIC_KINDS }?.payload?.get("name") ?: auto.title
                val why = if (auto.steps.any { it.kind == ProposalKind.MUSIC_WHILE_DRIVING }) " for the drive" else ""
                Line("Started automatically$why: $what", Type.small, c.ink2, Modifier.weight(1f), maxLines = 2)
                TextAction("Undo", { undo(auto.id) })
            }
        }
    }
}

/** Themis, Music: connect or disconnect Spotify, the default player, the drive playlist and the drive signal. */
@Composable
fun MusicPage(gutter: Dp, back: () -> Unit) {
    val ctx = LocalContext.current
    val core = remember { MusicCore.get(ctx) }
    val s by core.state.collectAsStateWithLifecycle()
    val skipped by core.skipped.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<MusicItem>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.connected) {
        core.reload()
        if (s.connected) withContext(Dispatchers.IO) {
            runCatching { core.web.playlists() }.onSuccess { playlists = it }.onFailure { problem = it.message }
            // The first App Remote connect shows Spotify's own one-time consent.
            if (s.spotifyInstalled) runCatching { core.remote.connect() }
        }
    }
    val askActivity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { core.setDriveSense(true); DriveSense.arm(ctx) }
    }
    MusicPageContent(
        s, playlists, problem, gutter, back,
        connect = { SpotifySignIn.start(ctx) },
        disconnect = { scope.launch(Dispatchers.IO) { core.disconnect() }; playlists = null },
        setPlayer = core::setPlayer,
        setDrive = core::setDrivePlaylist,
        toggleSense = {
            if (s.driveSense) { core.setDriveSense(false); DriveSense.disarm(ctx) }
            else if (DriveSense.allowed(ctx)) { core.setDriveSense(true); DriveSense.arm(ctx) }
            else askActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        },
        skipped = skipped,
        toggleLearn = { core.setLearnSkips(!s.learnSkips) },
        bringBack = core::bringBack,
    )
}

@Composable
fun MusicPageContent(
    s: MusicSettings,
    playlists: List<MusicItem>?,
    problem: String?,
    gutter: Dp,
    back: () -> Unit,
    connect: () -> Unit = {},
    disconnect: () -> Unit = {},
    setPlayer: (Player) -> Unit = {},
    setDrive: (MusicItem?) -> Unit = {},
    toggleSense: () -> Unit = {},
    skipped: List<TrackStat> = emptyList(),
    toggleLearn: () -> Unit = {},
    bringBack: (String) -> Unit = {},
) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Music", gutter, subtitle = "Spotify first, Apple Music as the fallback") {
            TextAction("Done", back)
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item { Kicker("Spotify", Modifier.padding(top = Space.l, bottom = Space.s)) }
            item {
                if (s.connected) {
                    Row_("Connected", "Playback, now playing, your playlists and recently played. Nothing else.") { StatusWord("On", Tone.OK) }
                    Row_("Disconnect", "Forgets the sign-in on this phone", onClick = disconnect)
                } else {
                    Line(
                        "Sign in once on Spotify's own page. Zeus keeps the sign-in sealed in Calypso and never sees your password.",
                        Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp),
                    )
                    PrimaryAction("Connect Spotify", connect)
                    Spacer(Modifier.height(Space.l))
                }
            }
            item {
                Row_("Spotify app", if (s.spotifyInstalled) "Plays on the phone through the app" else "Not installed: Zeus plays on your other Spotify devices") {
                    StatusWord(if (s.spotifyInstalled) "Found" else "Missing", if (s.spotifyInstalled) Tone.OK else Tone.QUIET)
                }
            }
            problem?.let { item { Line(it, Type.small, c.alert, Modifier.padding(vertical = Space.s)) } }

            item { Kicker("Default player", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            for (p in Player.entries) item(key = "player-${p.name}") {
                Row_(p.label, if (p == Player.SPOTIFY) "App Remote on the phone, the Web API for search" else "Its media session, when Spotify isn't wanted", onClick = { setPlayer(p) }) {
                    StatusWord(if (s.player == p) "Default" else "Use", if (s.player == p) Tone.ACTIVE else Tone.QUIET)
                }
            }

            item { Kicker("While driving", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            item {
                Row_("Detect driving", "Phone motion only, on the phone. Starting a drive proposes your drive playlist.", onClick = toggleSense) {
                    StatusWord(if (s.driveSense) "On" else "Off", if (s.driveSense) Tone.ACTIVE else Tone.QUIET)
                }
            }
            item {
                Line(
                    "Drive music asks first. To have it start by itself, turn on Music while driving in Themis, Auto-approve. It still shows with Undo.",
                    Type.small, c.ink3, Modifier.padding(vertical = Space.s).widthIn(max = 560.dp),
                )
            }
            item {
                Row_("Drive playlist", s.drivePlaylist?.name ?: "${MusicCore.DEFAULT_PLAYLIST} (default)", onClick = if (s.drivePlaylist != null) ({ setDrive(null) }) else null) {
                    if (s.drivePlaylist != null) StatusWord("Clear", Tone.QUIET)
                }
            }
            item { Kicker("Skips", Modifier.padding(top = Space.xxl, bottom = Space.s)) }
            item {
                Row_("Learn from skips", "Counts on this phone only. Playlists Zeus plays leave out songs you usually skip.", onClick = toggleLearn) {
                    StatusWord(if (s.learnSkips) "On" else "Off", if (s.learnSkips) Tone.ACTIVE else Tone.QUIET)
                }
            }
            if (s.learnSkips) {
                item { Kicker("Usually skipped · ${skipped.size}", Modifier.padding(top = Space.l, bottom = Space.s)) }
                if (skipped.isEmpty()) item {
                    Line("Nothing yet. A song you skip in 3 of its last 4 plays shows here, and gets one comeback after 30 days.", Type.small, c.ink3, Modifier.padding(vertical = Space.s).widthIn(max = 560.dp))
                }
                for (t in skipped) item(key = "skip-${t.uri}") {
                    Row_(t.name, t.artist.ifBlank { null }) { TextAction("Bring back", { bringBack(t.uri) }) }
                }
            }

            if (s.connected && playlists != null) {
                item { Kicker("Your playlists", Modifier.padding(top = Space.l, bottom = Space.s)) }
                for (pl in playlists.take(40)) item(key = pl.uri) {
                    val on = s.drivePlaylist?.uri == pl.uri
                    Row_(pl.name, pl.by, onClick = { setDrive(pl) }) { StatusWord(if (on) "Drive" else "Pick", if (on) Tone.ACTIVE else Tone.QUIET) }
                }
            }
        }
    }
}

/** Themis's flat row: title, one line, a trailing word, under a hairline. */
@Composable
private fun Row_(title: String, about: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}
