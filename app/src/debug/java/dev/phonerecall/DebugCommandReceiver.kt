package dev.phonerecall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthropic.core.jsonMapper

/**
 * Debug builds only, DUMP-protected (adb shell). Read-only verification hooks:
 *   adb shell am broadcast -a dev.phonerecall.DEBUG --es cmd hestia_status -n dev.phonerecall/.DebugCommandReceiver
 *   adb logcat -d -s ZeusHestia
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "hestia_status" -> {
                val core = dev.phonerecall.hestia.HestiaCore.get(context)
                core.backend.start()
                Log.i("ZeusHestia", jsonMapper().writeValueAsString(dev.phonerecall.hestia.HomeRules.debugStatus(core.snapshot)))
            }
            else -> Log.w("ZeusDebug", "Unknown cmd ${intent.getStringExtra("cmd")}")
        }
    }
}
