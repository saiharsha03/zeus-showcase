package dev.phonerecall.qol

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore

/**
 * Media muted at work. `atWork` between the office geofence's enter and exit; `saved` is the speaker's media
 * level to give back; `undone` is his Undo for this stay (then nothing is forced until he leaves).
 */
data class MuteState(val atWork: Boolean = false, val saved: Int? = null, val undone: Boolean = false)

/** One step: the next state, and the media level to set (null leaves the volume alone). */
data class MuteStep(val state: MuteState, val volume: Int?)

/** Pure. Only the speaker's media level: headphones are never touched, the ringer and DND never. */
object WorkMute {
    fun arrive(s: MuteState, on: Boolean, volume: Int, headphones: Boolean): MuteStep {
        val at = s.copy(atWork = true, undone = false)
        if (!on || headphones || volume == 0) return MuteStep(at, null)
        return MuteStep(at.copy(saved = s.saved ?: volume), 0)
    }

    /** Headphones or a Bluetooth speaker went: at work the speaker goes back to 0; after leaving, a deferred restore. */
    fun deviceGone(s: MuteState, on: Boolean, volume: Int, headphones: Boolean): MuteStep {
        if (headphones) return MuteStep(s, null)
        if (!s.atWork) return if (s.saved != null) MuteStep(s.copy(saved = null), s.saved) else MuteStep(s, null)
        if (!on || s.undone || volume == 0) return MuteStep(s, null)
        return MuteStep(s.copy(saved = s.saved ?: volume), 0)
    }

    /** Leaving: the saved level comes back, unless headphones are in (then when they go). */
    fun leave(s: MuteState, headphones: Boolean): MuteStep {
        val out = s.copy(atWork = false, undone = false)
        if (s.saved == null || headphones) return MuteStep(out, null)
        return MuteStep(out.copy(saved = null), s.saved)
    }

    fun undo(s: MuteState): MuteStep = MuteStep(s.copy(saved = null, undone = true), s.saved)
}

/** The phone side: AudioManager, the office geofence hooks, device callbacks and the "Media muted at work" chip. */
object WorkMuteCore {
    private const val ID = 61_520
    private val lock = Any()

    private fun sp(c: Context) = c.getSharedPreferences("qol.workmute", Context.MODE_PRIVATE)
    private fun load(c: Context) = sp(c).let { MuteState(it.getBoolean("at", false), it.getInt("saved", -1).takeIf { v -> v >= 0 }, it.getBoolean("undone", false)) }
    private fun save(c: Context, s: MuteState) { sp(c).edit().putBoolean("at", s.atWork).putInt("saved", s.saved ?: -1).putBoolean("undone", s.undone).apply() }

    private val PHONES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private fun audio(c: Context) = c.getSystemService(AudioManager::class.java)
    private fun headphones(am: AudioManager) = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PHONES }
    private fun on(c: Context) = MomentKind.WORK_MUTE !in MomentsCore.load(c).never

    private fun run(c: Context, what: String, step: (MuteState, AudioManager) -> MuteStep) = synchronized(lock) {
        val am = audio(c) ?: return
        val before = load(c)
        val s = step(before, am)
        save(c, s.state)
        s.volume?.let { v ->
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }.onFailure { Clio.error(QolCore.AREA, it) }
            Clio.info(QolCore.AREA, if (v == 0) "media muted" else "media restored", what)
        }
        if (s.volume == 0) chip(c) else if (!s.state.atWork || s.state.undone) c.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
    }

    fun arrived(c: Context) = run(c, "at the office") { s, am -> WorkMute.arrive(s, on(c), am.getStreamVolume(AudioManager.STREAM_MUSIC), headphones(am)) }
    fun left(c: Context) = run(c, "left the office") { s, am -> WorkMute.leave(s, headphones(am)) }
    fun deviceGone(c: Context) = run(c, "headphones gone") { s, am -> WorkMute.deviceGone(s, on(c), am.getStreamVolume(AudioManager.STREAM_MUSIC), headphones(am)) }
    fun undo(c: Context) = run(c, "undo") { s, _ -> WorkMute.undo(s) }

    /** A Bluetooth link dropped: the route moves to the speaker a moment later. */
    fun linkGone(c: Context) { Handler(Looper.getMainLooper()).postDelayed({ kotlin.concurrent.thread { deviceGone(c) } }, 1_500L) }

    /** Wired and USB headphones while the process lives; Bluetooth also comes through the manifest receiver. */
    fun watch(c: Context) {
        val app = c.applicationContext
        runCatching {
            audio(app)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    if (removed.any { it.type in PHONES } && load(app).let { it.atWork || it.saved != null }) kotlin.concurrent.thread { deviceGone(app) }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun chip(c: Context) {
        val nm = QolCore.channel(c) ?: return
        val undo = PendingIntent.getBroadcast(c, ID, Intent(c, QolReceiver::class.java).setAction(QolReceiver.UNDO_MUTE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Media muted at work").setContentText("It comes back when you leave the office")
            .addAction(0, "Undo", undo).setSilent(true).setOnlyAlertOnce(true).setTimeoutAfter(10 * 3_600_000L).build())
    }
}
