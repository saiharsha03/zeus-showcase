package dev.phonerecall.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** A vertical hinge that splits the window (book posture), in window pixels. */
@Immutable data class Hinge(val left: Int, val right: Int) {
    val center: Float get() = (left + right) / 2f
}

/** Watches the fold. Null when flat, folded, or when the hinge does not separate content. */
@Composable
fun rememberHinge(activity: ComponentActivity): Hinge? {
    var hinge by remember { mutableStateOf<Hinge?>(null) }
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                hinge = if (f != null && f.isSeparating && f.orientation == FoldingFeature.Orientation.VERTICAL) {
                    Hinge(f.bounds.left, f.bounds.right)
                } else null
            }
        }
    }
    return hinge
}
