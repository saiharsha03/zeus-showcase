package dev.phonerecall.hestia

import android.content.Context
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in for Hestia's Google Home backend, compiled when the Google Home APIs SDK is not in app/libs/home-sdk
 * (it is not on a public Maven, so the public build has no copy). Same API as the real one in src/homeSdk;
 * Hestia shows "not connected" with the reason and never sends a command.
 */
class GoogleHomeBackend(@Suppress("unused") private val app: Context) : HomeBackend {
    private val _state = MutableStateFlow(HomeSnapshot(Link.NotConnected(REASON)))
    override val state: StateFlow<HomeSnapshot> = _state.asStateFlow()

    fun register(@Suppress("UNUSED_PARAMETER") activity: ComponentActivity) {}

    override fun start() {}

    override suspend fun requestPermission(): String? = REASON

    override suspend fun command(deviceId: String, command: HomeCommand) { throw HomeError(REASON) }

    private companion object {
        const val REASON = "This build has no Google Home APIs SDK (add it to app/libs/home-sdk, see docs/HESTIA.md)"
    }
}
