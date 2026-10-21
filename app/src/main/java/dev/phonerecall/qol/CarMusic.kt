package dev.phonerecall.qol

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import dev.phonerecall.music.MediaFallback
import dev.phonerecall.music.MusicCore
import dev.phonerecall.music.Player

/** What the car connecting does with music. */
enum class CarPlay { OFF, ON_CALL, PLAYING, RECENT, RESUME, PLAY }

/**
 * The car's Bluetooth connected: his drive playlist (owner_1 when none is chosen) starts, or the paused
 * Spotify resumes, only when nothing is playing and he isn't on a call. Undo pauses it. Reversible, so it runs
 * on its own and is logged; drive mode's music proposal then skips this trip.
 */
object CarMusic {
    private const val ID = 61_530
    const val ONCE_MS = 30 * 60_000L

    fun decide(on: Boolean, onCall: Boolean, playing: Boolean, recent: Boolean, spotifyPaused: Boolean): CarPlay = when {
        !on -> CarPlay.OFF
        onCall -> CarPlay.ON_CALL
        playing -> CarPlay.PLAYING
        // The link flaps at a stop: once per drive.
        recent -> CarPlay.RECENT
        spotifyPaused -> CarPlay.RESUME
        else -> CarPlay.PLAY
    }

    /** Blocking; called from QolCore's car thread. */
    fun connected(c: Context) {
        val now = System.currentTimeMillis()
        val sp = c.getSharedPreferences("qol.carmusic", Context.MODE_PRIVATE)
        val media = MediaFallback(c)
        val spotify = media.controller(Player.SPOTIFY.pkg)
        val d = decide(
            on = MomentKind.CAR_MUSIC !in MomentsCore.load(c).never, onCall = Quiet.onCall(c),
            playing = media.now()?.playing == true, recent = now - sp.getLong("at", 0L) < ONCE_MS,
            spotifyPaused = spotify?.metadata != null && spotify.playbackState?.state != PlaybackState.STATE_PLAYING,
        )
        if (d != CarPlay.RESUME && d != CarPlay.PLAY) { if (d != CarPlay.OFF) Clio.info(QolCore.AREA, "car music skipped", d.name.lowercase().replace('_', ' ')); return }
        val core = MusicCore.get(c)
        val name = core.state.value.drivePlaylist?.name ?: MusicCore.DEFAULT_PLAYLIST
        runCatching {
            if (d == CarPlay.RESUME) core.control("resume")
            else core.play(core.state.value.drivePlaylist?.let { mapOf("uri" to it.uri, "name" to it.name) } ?: mapOf("query" to MusicCore.DEFAULT_PLAYLIST, "name" to MusicCore.DEFAULT_PLAYLIST))
        }.onSuccess {
            sp.edit().putLong("at", now).apply()
            core.drivePlayed(now)
            val what = if (d == CarPlay.RESUME) "Spotify resumed" else "Playing $name"
            Clio.info(QolCore.AREA, "car music", what)
            undoChip(c, what)
        }.onFailure { Clio.error(QolCore.AREA, it) }
    }

    private fun undoChip(c: Context, what: String) {
        val nm = QolCore.channel(c) ?: return
        val undo = PendingIntent.getBroadcast(c, ID, Intent(c, QolReceiver::class.java).setAction(QolReceiver.UNDO_MUSIC), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(what).setContentText("The car connected").addAction(0, "Undo", undo)
            .setSilent(true).setTimeoutAfter(5 * 60_000L).build())
    }

    fun undo(c: Context) {
        c.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
        runCatching { MusicCore.get(c).control("pause") }.onSuccess { Clio.info(QolCore.AREA, "car music undone") }.onFailure { Clio.error(QolCore.AREA, it) }
    }
}
