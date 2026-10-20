package dev.phonerecall.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import dev.phonerecall.MainActivity

/** Opens Spotify's sign-in page in a Custom Tab (the browser's process: Zeus never sees the password). */
object SpotifySignIn {
    fun start(context: Context) {
        Thread {
            val url = MusicCore.get(context).auth.begin()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
            }
        }.start()
    }
}

/**
 * Receives zeus://spotify-callback?code=...&state=... and nothing else (manifest: VIEW + BROWSABLE, that
 * scheme and host only). The state must match the sign-in Zeus started, once, within ten minutes; then
 * the code is swapped for tokens (accounts.spotify.com, through NetGate) and Zeus comes back to front.
 */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        if (data == null) { finish(); return }
        val app = applicationContext
        Thread {
            val problem = MusicCore.get(app).finishSignIn(data)
            runOnUiThread {
                // App Remote's own consent can come back here with no sign-in of ours waiting: stay quiet then.
                if (problem != NO_SIGN_IN) Toast.makeText(app, problem ?: "Spotify connected", Toast.LENGTH_SHORT).show()
                startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }.start()
    }
}
