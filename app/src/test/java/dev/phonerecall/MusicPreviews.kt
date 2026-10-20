package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.FatesCard
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Step
import dev.phonerecall.music.MusicItem
import dev.phonerecall.music.MusicPageContent
import dev.phonerecall.music.MusicSettings
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.music.NowPlayingContent
import dev.phonerecall.music.Player
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space

/** Fixed music screens for the screenshot test. */
object MusicPreviews {
    private val now = System.currentTimeMillis()

    private val festival = Proposal(
        1, now, now + 7_200_000L, ProposalStatus.PENDING, "Devotional music for Dussehra",
        listOf(Step(ProposalKind.PLAY_MUSIC, mapOf("query" to "Telugu devotional", "name" to "Devotional music for Dussehra", "reason" to "Dussehra today"), "Play Devotional music for Dussehra")),
        proposer = "Music",
    )
    private val drove = Proposal(
        2, now - 600_000L, now, ProposalStatus.CONFIRMED, "While driving: play Road trip",
        listOf(Step(ProposalKind.MUSIC_WHILE_DRIVING, mapOf("uri" to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "name" to "Road trip"), "While driving: play Road trip", undo = mapOf("action" to "pause"))),
        proposer = "Drive mode", auto = true, outcome = "Done automatically", decidedAt = now - 590_000L,
    )

    private val playlists = listOf(
        MusicItem("Road trip", "spotify:playlist:1", "The owner"), MusicItem("Telugu devotional", "spotify:playlist:2", "The owner"),
        MusicItem("Deep Focus", "spotify:playlist:3", "Spotify"), MusicItem("Arijit evenings", "spotify:playlist:4", "The owner"),
    )

    @Composable private fun Page(content: @Composable () -> Unit) = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    /** Olympus's header area: a context card he taps, and the now-playing card after drive music ran by itself. */
    @Composable fun Olympus() = Page {
        Column {
            ScreenHeader("Olympus", Space.gutterNarrow)
            Column(Modifier.padding(horizontal = Space.gutterNarrow), verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
                FatesCard(festival, FatesCallbacks())
                NowPlayingContent(NowPlaying("Om Namah Shivaya", "S. P. Balasubrahmanyam", playing = true), drove, {}, {})
                NowPlayingContent(NowPlaying("Kesariya", "Arijit Singh", playing = false, source = Player.SPOTIFY), null, {}, {})
            }
        }
    }

    @Composable fun ThemisMusic() = Page {
        MusicPageContent(MusicSettings(connected = true, drivePlaylist = playlists[0], driveSense = true, spotifyInstalled = true), playlists, null, Space.gutterNarrow, {})
    }

    @Composable fun ThemisMusicSignedOut() = Page {
        MusicPageContent(MusicSettings(spotifyInstalled = true), null, null, Space.gutterNarrow, {})
    }
}
