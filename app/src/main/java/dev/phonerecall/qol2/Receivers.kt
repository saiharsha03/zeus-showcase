package dev.phonerecall.qol2

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/** A quick timer running out, and a restart (re-arm the timers still running). */
class TouchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "touches-receiver") {
            try {
                when (intent.action) {
                    ACTION_TIMER -> intent.getStringExtra(EXTRA_ID)?.let { Touches.timerDone(app, it) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Touches.rearm(app)
                }
            } catch (e: Exception) {
                dev.phonerecall.clio.Clio.error(Touches.AREA, e)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TIMER = "dev.phonerecall.TOUCH_TIMER"
        const val EXTRA_ID = "id"
    }
}

/** Bluetooth audio dropping (headphones, speakers, car audio: the audio/video class; never its name). */
class TouchAudioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION") val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (runCatching { d.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO }.getOrDefault(true)) return
        val music = runCatching { app.getSystemService(AudioManager::class.java).isMusicActive }.getOrDefault(false)
        val pending = goAsync()
        thread(name = "touches-audio") {
            try { Touches.audioDropped(app, music) } catch (e: Exception) { dev.phonerecall.clio.Clio.error(Touches.AREA, e) } finally { pending.finish() }
        }
    }
}

/** "Read later" in the share sheet: a link goes to the Read later pocket, "Jamie has my controller" to Borrowed. No screen. */
class TouchShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent?.getStringExtra(Intent.EXTRA_TITLE)
        val app = applicationContext
        thread(name = "touches-share") {
            val said = runCatching { Touches.share(app, text, subject) }.getOrNull()
            runOnUiThread {
                Toast.makeText(app, said ?: "No link in that", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
