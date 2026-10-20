package dev.phonerecall

import android.content.Intent
import android.graphics.Color
import dev.phonerecall.nowbar.ACTION_OPEN_HOME
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.LocalPlainNames
import androidx.compose.runtime.CompositionLocalProvider
import dev.phonerecall.ui.rememberHinge

private const val HANDLED = "dev.phonerecall.SHARE_HANDLED"

class MainActivity : ComponentActivity() {
    private var vmRef: AthenaViewModel? = null

    /** A Now Bar tap while Athena is already open: go to Home. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_HOME) vmRef?.requestHome()
        vmRef?.let { shared(it, intent); dest(it, intent) }
    }

    /** A store arrival or the list widget asks for a list ("section:<id>") or a place by name. */
    private fun dest(vm: AthenaViewModel, intent: Intent?) {
        // "No" on a Moneta flag: the bank's app opens, and the charge waits on Moneta's page to tell Iris.
        intent?.getStringExtra(dev.phonerecall.moneta.MonetaCore.Alerts.EXTRA_NO)?.let { key ->
            intent.removeExtra(dev.phonerecall.moneta.MonetaCore.Alerts.EXTRA_NO)
            dev.phonerecall.moneta.MonetaCore.Alerts.cancel(this, key)
            dev.phonerecall.moneta.MonetaCore.get(this).disown(key)
            val bank = intent.getStringExtra(dev.phonerecall.moneta.MonetaCore.Alerts.EXTRA_BANK)
            (dev.phonerecall.moneta.MoneyActions.confirm(dev.phonerecall.moneta.MoneyActions.propose(dev.phonerecall.moneta.MoneyActionKind.DISPUTE, "", bank), tappedByOwner = true)
                as? dev.phonerecall.moneta.MoneyEffect.OpenBank)?.let { dev.phonerecall.moneta.openBank(this, it) }
        }
        val d = intent?.getStringExtra(EXTRA_DEST) ?: return
        intent.removeExtra(EXTRA_DEST)
        vm.requestDest(d)
    }

    companion object { const val EXTRA_DEST = "dev.phonerecall.DEST" }

    /** Text, a link or an image shared to Zeus: kept in Parnassus, which opens. */
    private fun shared(vm: AthenaViewModel, intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.getBooleanExtra(HANDLED, false)) return
        intent.putExtra(HANDLED, true)
        // A Google Maps place goes to Odyssey (read on the phone), everything else to Parnassus.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null && dev.phonerecall.odyssey.MapsLink.isMapsLink(text)) {
            val app = applicationContext
            kotlin.concurrent.thread { dev.phonerecall.odyssey.Odyssey.shared(app, text) }
            vm.requestDest(Dest.ODYSSEY.name)
            return
        }
        vm.brain.captureShared(intent)
        vm.requestDest(Dest.PARNASSUS.name)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Hestia: the Google Home consent screen is an activity result, so it is registered before STARTED.
        dev.phonerecall.hestia.HestiaCore.attach(this)
        setContent {
            val vm: AthenaViewModel = viewModel()
            LaunchedEffect(vm) {
                vmRef = vm
                if (intent?.action == ACTION_OPEN_HOME) vm.requestHome()
                shared(vm, intent)
                dest(vm, intent)
            }
            val settings by vm.settings.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
            LaunchedEffect(settings.dark) {
                val style = if (settings.dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            // Cover screen is Compact; the unfolded inner screen is Medium or Expanded. Recomputed on every
            // resize (the activity handles fold changes itself), so the bar and rail swap live.
            val wide = calculateWindowSizeClass(this).widthSizeClass != WindowWidthSizeClass.Compact
            val hinge = rememberHinge(this)
            AthenaTheme(dark = settings.dark) {
                CompositionLocalProvider(LocalPlainNames provides settings.plainNames) {
                    Shell(vm, wide = wide, hinge = hinge)
                }
            }
        }
    }
}
