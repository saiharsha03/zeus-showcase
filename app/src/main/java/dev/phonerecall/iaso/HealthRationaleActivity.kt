package dev.phonerecall.iaso

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type

/** Health Connect's "why does this app want this" page. Plain words, no links out. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AthenaTheme(true) {
                Column(Modifier.fillMaxSize().background(Athena.colors.surface)) {
                    ScreenHeader(Place.HEALTH.myth, Space.gutterNarrow, emblem = Place.HEALTH, subtitle = "How Zeus uses your health data") { TextAction("Close", { finish() }) }
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.gutterNarrow)) {
                        for (p in RATIONALE) Line(p, Type.body, Athena.colors.ink2, Modifier.padding(bottom = Space.l))
                    }
                }
            }
        }
    }

    companion object {
        val RATIONALE = listOf(
            "Iaso reads steps, sleep, heart rate, resting heart rate, workouts, weight and active calories to show your day and week inside Zeus.",
            "It writes only what you log in Zeus: a weight you enter, and a meal after you check and save it.",
            "Health data stays on this phone. It is not shown on the lock screen or the watch, or on a widget unless you add the Iaso widget yourself, and is not sent to Iris or any server of yours.",
            "A meal photo is sent to Claude (api.anthropic.com) once, to estimate it, after its location and camera details are removed. Athena hears one line like \"slept 5h, short\" only if you turn sharing on.",
        )
    }
}
