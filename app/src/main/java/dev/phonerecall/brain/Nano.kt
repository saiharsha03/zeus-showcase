package dev.phonerecall.brain

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini Nano through the ML Kit GenAI Prompt API, ported from PhoneRecall's Nano.kt. The model runs
 * in AICore, a separate system process; nothing here touches the network. One shared client for the
 * whole process, created once and never closed (closing it and making another was PhoneRecall's slow
 * path). Every step logs under tag "Zeus-Nano" so a device check is one `adb logcat -s Zeus-Nano`.
 * AICore refuses apps in the background (BACKGROUND_USE_BLOCKED), so callers check [foreground] and
 * fall back to Haiku.
 */
object Nano {
    private const val TAG = "Zeus-Nano"

    /** "ready (model name)", "downloadable", "downloading 42%", "unavailable", "error: ...". */
    val status = MutableStateFlow("not checked")
    val lastError = MutableStateFlow<String?>(null)

    @Volatile private var client: GenerativeModel? = null
    private val lock = Mutex()
    @Volatile private var downloading = false

    @Synchronized
    private fun model(): GenerativeModel = client ?: Generation.getClient().also { client = it; Log.i(TAG, "client created (shared, never closed)") }

    private fun name(s: Int) = when (s) {
        FeatureStatus.AVAILABLE -> "ready"; FeatureStatus.DOWNLOADABLE -> "downloadable"
        FeatureStatus.DOWNLOADING -> "downloading"; else -> "unavailable"
    }

    /** True when Zeus is on screen; AICore only answers then. */
    fun foreground(): Boolean = runCatching {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }.getOrDefault(false)

    /** Asks AICore; starts the download once when the model is downloadable. True when Nano can be used now. */
    suspend fun check(): Boolean {
        val s = try {
            model().checkStatus().also { Log.i(TAG, "checkStatus=$it (${name(it)})") }
        } catch (t: Throwable) {
            fail("checkStatus", t); status.value = "error: ${t.message ?: t.javaClass.simpleName}"; return false
        }
        if (s == FeatureStatus.AVAILABLE) {
            val base = runCatching { model().getBaseModelName() }.onFailure { Log.w(TAG, "getBaseModelName failed: ${it.message}") }.getOrNull()
            status.value = "ready" + (base?.let { " ($it)" } ?: "")
            return true
        }
        status.value = if (downloading) status.value else name(s)
        if (s == FeatureStatus.DOWNLOADABLE && !downloading) startDownload()
        return false
    }

    private suspend fun startDownload() {
        if (downloading) return
        downloading = true
        Log.i(TAG, "starting model download (AICore fetches it, not Zeus)")
        var total = 0L
        try {
            model().download().collect { d ->
                when (d) {
                    is DownloadStatus.DownloadStarted -> { total = d.bytesToDownload; status.value = "downloading" }
                    is DownloadStatus.DownloadProgress -> status.value = if (total > 0) "downloading ${d.totalBytesDownloaded * 100 / total}%" else "downloading"
                    is DownloadStatus.DownloadCompleted -> { Log.i(TAG, "download completed"); status.value = "ready" }
                    is DownloadStatus.DownloadFailed -> { fail("download", d.e); status.value = "download failed" }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            fail("download", t); status.value = "download failed"
        } finally { downloading = false }
    }

    private fun fail(step: String, t: Throwable) {
        val msg = "${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, "$step failed: $msg", t)
        lastError.value = "$step failed: $msg"
    }

    /** One prompt. Null when Zeus is in the background, Nano isn't ready, or the call failed (logged). */
    suspend fun generate(feature: String, prompt: String, maxTokens: Int = 300, temperature: Float = 0.2f, image: Bitmap? = null): String? = lock.withLock {
        if (!foreground()) { Log.i(TAG, "[$feature] skipped: Zeus is in the background (AICore would refuse)"); return null }
        if (!check()) { Log.w(TAG, "[$feature] skipped: ${status.value}"); return null }
        val started = System.currentTimeMillis()
        try {
            val request = if (image != null) generateContentRequest(ImagePart(image), TextPart(prompt)) { this.temperature = temperature; maxOutputTokens = maxTokens }
            else generateContentRequest(TextPart(prompt)) { this.temperature = temperature; maxOutputTokens = maxTokens }
            Log.i(TAG, "[$feature] prompt ${prompt.length} chars")
            val text = model().generateContent(request).candidates.firstOrNull()?.text?.trim()
            Log.i(TAG, "[$feature] reply ${text?.length ?: 0} chars in ${System.currentTimeMillis() - started} ms")
            if (text.isNullOrEmpty()) { lastError.value = "Gemini Nano returned nothing"; null } else { lastError.value = null; text }
        } catch (t: Throwable) {
            fail("[$feature] generate", t)
            null
        }
    }
}
