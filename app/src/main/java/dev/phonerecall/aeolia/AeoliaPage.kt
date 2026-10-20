package dev.phonerecall.aeolia

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.phonerecall.places.PlaceStore
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the page may load. The page itself is served from the app's assets under a private origin
 * (https://aeolia.zeus.local/, never on the network); weather requests that pass [WeatherNet] are
 * fetched natively and handed over; everything else gets an empty 403.
 */
object AeoliaRoutes {
    const val HOST = "aeolia.zeus.local"
    const val PAGE = "https://$HOST/"

    sealed interface Route {
        data class Asset(val file: String, val mime: String) : Route
        data object Fetch : Route
        data object Block : Route
    }

    private val ASSETS = listOf(
        Regex("/[a-z0-9-]+\\.js") to "application/javascript",
        Regex("/fonts/[a-z_]+\\.ttf") to "font/ttf",
        Regex("/samsung/chars/[a-z-]+\\.mp4") to "video/mp4",
        Regex("/samsung/[a-z0-9-]+\\.webp") to "image/webp",
    )

    /** The bundled page: https, this host, default port, no user info. */
    fun isPage(url: String): Boolean {
        val u = try { URI(url) } catch (_: Exception) { return false }
        return u.scheme == "https" && u.host == HOST && u.port == -1 && u.rawUserInfo == null
    }

    fun route(url: String, method: String): Route {
        if (method != "GET") return Route.Block
        if (isPage(url)) {
            val path = try { URI(url).rawPath } catch (_: Exception) { return Route.Block }
            if (path == "/" || path == "/index.html") return Route.Asset("aeolia/index.html", "text/html")
            for ((re, mime) in ASSETS) if (re.matches(path)) return Route.Asset("aeolia$path", mime)
            return Route.Block
        }
        return if (WeatherNet.allows(url)) Route.Fetch else Route.Block
    }
}

/**
 * The one Aeolia WebView for the process. It is kept across fold changes (the shell rebuilds the
 * screen when the bar swaps for the rail) and moved between hosts, so the page reflows instead of
 * reloading. Its context is the activity while shown and the application otherwise.
 */
class AeoliaHost private constructor(private val app: Context) {
    private val ctx = MutableContextWrapper(app)
    private var web: WebView? = null
    @Volatile private var trusted = false
    @Volatile private var activity: Activity? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicInteger()
    /** The Zeus tokens as a JS object literal, sent on every page load and theme change. */
    @Volatile private var theme = "{}"

    /** Bumped on every attach, so a late detach from the previous screen leaves the new one alone. */
    private var holder = 0

    fun attach(a: Activity): Pair<WebView, Int> {
        activity = a
        ctx.baseContext = a
        val w = web ?: create().also { web = it }
        (w.parent as? ViewGroup)?.removeView(w)
        w.onResume()
        return w to ++holder
    }

    fun detach(token: Int) {
        val w = web ?: return
        if (token != holder) return
        w.onPause()
        (w.parent as? ViewGroup)?.removeView(w)
        activity = null
        ctx.baseContext = app
    }

    fun theme(js: String) {
        if (js == theme) return
        theme = js
        web?.let { w -> w.post { if (trusted) w.evaluateJavascript("window.applyZeus&&applyZeus($js)", null) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(ctx).apply {
        setBackgroundColor(0)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true             // the page keeps its own small preferences
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false  // the Samsung character is a muted looping video
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(Bridge(), "Weather")
        webViewClient = Client()
        loadUrl(AeoliaRoutes.PAGE)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse {
            val url = r.url.toString()
            return when (val route = AeoliaRoutes.route(url, r.method)) {
                is AeoliaRoutes.Route.Asset -> runCatching {
                    WebResourceResponse(route.mime, if (route.mime.startsWith("text/") || route.mime.endsWith("javascript")) "UTF-8" else null, app.assets.open(route.file))
                }.getOrElse { blocked() }
                AeoliaRoutes.Route.Fetch -> runCatching {
                    val body = WeatherNet.get(url)
                    val type = WeatherNet.mime(body)
                    // Images get CORS so the radar can drop weak echoes on a canvas; JSON stays same-origin.
                    if (type.startsWith("image/")) WebResourceResponse(type, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(body))
                    else WebResourceResponse(type, "UTF-8", ByteArrayInputStream(body))
                }.getOrElse { blocked() }
                AeoliaRoutes.Route.Block -> {
                    // Off the page's origin: NetGate logs it like any other refused request.
                    if (!AeoliaRoutes.isPage(url)) runCatching { WeatherNet.require(url) }
                    blocked()
                }
            }
        }

        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest) = !AeoliaRoutes.isPage(r.url.toString())
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { trusted = AeoliaRoutes.isPage(url) }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { trusted = AeoliaRoutes.isPage(url) }
        override fun onPageFinished(view: WebView, url: String) {
            trusted = AeoliaRoutes.isPage(url)
            if (trusted) view.evaluateJavascript("window.applyZeus&&applyZeus($theme)", null)
        }
    }

    private fun reply(fn: String, json: String) {
        val w = web ?: return
        w.post { if (trusted) w.evaluateJavascript("$fn($json)", null) }
    }

    private fun clip(s: String?, max: Int): String = (s ?: "").replace(Regex("\\p{Cntrl}"), " ").trim().take(max)
    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private val prefs get() = WeatherStore.prefs(app)

    /** What the page calls. Every method checks that the bundled page is the one asking. */
    private val hasSamsungPack by lazy { runCatching { app.assets.list("aeolia/samsung")?.isNotEmpty() == true }.getOrDefault(false) }

    inner class Bridge {
        @JavascriptInterface fun saved(): String = if (!trusted) "null" else Aeolia.selection(app).toString()
        @JavascriptInterface fun cached(): String = if (!trusted) "null" else prefs.getString("forecast", "null")!!
        @JavascriptInterface fun cachedAlerts(): String = if (!trusted) "null" else prefs.getString("alerts", "null")!!
        /** True when the local-only Samsung scene pack is in assets/aeolia/samsung (never in the public repo). */
        @JavascriptInterface fun samsungPack(): Boolean = trusted && hasSamsungPack
        @JavascriptInterface fun getPreferences(): String = if (!trusted) "null" else prefs.getString("ui", "{}")!!

        @JavascriptInterface fun savePreferences(value: String?) {
            if (!trusted || value == null || value.length > 65536) return
            if (runCatching { JSONObject(value) }.isSuccess) prefs.edit().putString("ui", value).apply()
        }

        @JavascriptInterface fun radar() {
            if (!trusted) return
            worker.submit {
                val result = JSONObject(); val iem = JSONObject(); var any = false
                runCatching { iem.put("n0q", JSONObject(WeatherNet.text(WeatherNet.N0Q))); any = true }
                runCatching { iem.put("hrrr", JSONObject(WeatherNet.text(WeatherNet.HRRR))) }
                runCatching { result.put("rainviewer", JSONObject(WeatherNet.text(WeatherNet.RAINVIEWER))); any = true }
                result.put("iem", iem)
                if (any) reply("radarReady", result.toString()) else reply("radarUnavailable", "null")
            }
        }

        @JavascriptInterface fun search(query: String?) {
            if (!trusted) return
            val q = clip(query, 100)
            if (q.length < 2) return
            worker.submit {
                runCatching { reply("searchResults", WeatherNet.text(WeatherNet.searchUrl(q))) }
                    .onFailure { reply("showError", JSONObject.quote("Couldn't search cities. Check your connection and retry.")) }
            }
        }

        @JavascriptInterface fun forecast(lat: Double, lon: Double, rawName: String?, rawCountry: String?) {
            if (!trusted || !Aeolia.coords(lat, lon)) return
            val name = clip(rawName, 80)
            val country = clip(rawCountry, 3).replace(Regex("[^A-Za-z]"), "")
            val g = generation.incrementAndGet()
            worker.submit {
                try {
                    if (g != generation.get()) return@submit
                    val data = WeatherNet.text(WeatherNet.forecastUrl(lat, lon))
                    if (g != generation.get()) return@submit  // a newer place was chosen while this one downloaded
                    Aeolia.forecastArrived(app, lat, lon, name, country, data)
                    reply("renderWeather", JSONObject(data).put("selected_lat", lat).put("selected_lon", lon).toString())
                } catch (_: Exception) {
                    if (g == generation.get()) reply("showError", JSONObject.quote("Couldn't update weather. Check your connection, then tap refresh."))
                }
            }
        }

        @JavascriptInterface fun details(lat: Double, lon: Double, rawCountry: String?) {
            if (!trusted || !Aeolia.coords(lat, lon)) return
            val country = clip(rawCountry, 3)
            worker.submit {
                try {
                    // NWS covers the US; elsewhere the page says alerts are not available, rather than "all clear".
                    val alerts = if (country == "IN") JSONObject().put("unsupported", true).put("features", JSONArray())
                    else JSONObject(WeatherNet.text(WeatherNet.alertsUrl(lat, lon)))
                    alerts.put("selected_lat", lat).put("selected_lon", lon).put("fetched_at", System.currentTimeMillis())
                    prefs.edit().putString("alerts", alerts.toString()).apply()
                    if (country != "IN") Aeolia.alertsArrived(app, lat, lon, alerts.toString())
                    reply("renderAlerts", alerts.toString())
                } catch (_: Exception) {
                    Aeolia.alertsArrived(app, lat, lon, null)
                    reply("alertsUnavailable", JSONObject().put("selected_lat", lat).put("selected_lon", lon)
                        .put("message", "NWS alerts could not be checked. Try refresh; check weather.gov for official warnings.").toString())
                }
                try {
                    val air = WeatherNet.text(WeatherNet.airUrl(lat, lon))
                    Aeolia.airArrived(app, lat, lon, air)
                    reply("renderAir", JSONObject(air).put("selected_lat", lat).put("selected_lon", lon).toString())
                } catch (_: Exception) { reply("airUnavailable", "null") }
            }
        }

        /** BetweenWeather refreshed holidays from Google's public calendars; Zeus doesn't allow that host, so the bundled dates are used. */
        @JavascriptInterface fun holidays() { if (trusted) reply("holidaysUnavailable", "null") }

        @JavascriptInterface fun notifications(): Boolean = trusted && Aeolia.notificationsOn(app)

        @JavascriptInterface fun setNotifications(enabled: Boolean) {
            if (!trusted) return
            prefs.edit().putBoolean("notifications", enabled).apply()
            if (enabled && !granted(Manifest.permission.POST_NOTIFICATIONS)) activity?.let { a ->
                a.runOnUiThread { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4401) }
            }
            reply("notificationsChanged", enabled.toString())
        }

        /** Home and Work from Themis > Places (named so), for the tabs; null keeps BetweenWeather's Buffalo and Mumbai. */
        @JavascriptInterface fun places(): String {
            if (!trusted) return "null"
            val mine = runCatching { PlaceStore(app).all() }.getOrDefault(emptyList())
            val picked = listOf("home" to "Home", "work" to "Work").mapNotNull { (key, label) ->
                mine.firstOrNull { it.name.trim().lowercase() == key || (key == "work" && it.name.trim().lowercase() == "office") }?.let { label to it }
            }
            if (picked.isEmpty()) return "null"
            return JSONArray(picked.map { (label, p) -> JSONObject().put("id", label.lowercase()).put("lat", p.lat).put("lon", p.lng).put("name", label).put("country", "") }).toString()
        }

        /** The Here tab shows only when Zeus already has location permission (asked in Themis > Places). */
        @JavascriptInterface fun hereAllowed(): Boolean = trusted && (granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION))

        @JavascriptInterface fun locate() {
            if (!trusted) return
            if (!hereAllowed()) {
                reply("locationDenied", "null")
                reply("showError", JSONObject.quote("Location is off for Zeus. Allow it in Themis > Places to see the weather here."))
                return
            }
            val a = activity ?: return
            a.runOnUiThread {
                val lm = app.getSystemService(LocationManager::class.java)
                val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
                try {
                    lm.getCurrentLocation(provider, null, app.mainExecutor) { l ->
                        if (l == null) {
                            reply("locationUnavailable", "null")
                            reply("showError", JSONObject.quote("Location unavailable. Search for your city instead."))
                        } else reply("deviceLocation", JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("name", "Current location").toString())
                    }
                } catch (_: SecurityException) { reply("locationDenied", "null") }
            }
        }

        @JavascriptInterface fun openOfficial(url: String?) {
            if (!trusted || url == null || url.length > 500) return
            val u = runCatching { URI(url) }.getOrNull() ?: return
            if (u.scheme == "https" && u.rawUserInfo == null && u.port == -1 && (u.host == "www.weather.gov" || u.host == "api.weather.gov")) {
                // weather.gov in the browser (its own process), only when he taps the source link.
                activity?.let { a -> a.runOnUiThread { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
            }
        }

        /** The Nimbus line on screen, kept for the Olympus card and the widgets. */
        @JavascriptInterface fun saveQuip(raw: String?) {
            if (!trusted) return
            val text = clip(raw, 200).ifEmpty { return }
            WeatherStore.update(app) { if (it.quip == text) it else it.copy(quip = text) }
        }

        @JavascriptInterface fun haptic() { if (trusted) web?.let { w -> w.post { w.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } } }

        /** Gemini Nano rewrites the day's facts; replies nanoQuip({id,text,status,reason}). */
        @JavascriptInterface fun quip(facts: String?, rawTone: String?, rawId: String?) {
            if (!trusted || facts == null || facts.length > 2048) return
            val f = runCatching { JSONObject(facts) }.getOrNull() ?: return
            val id = clip(rawId, 200)
            scope.launch {
                val r = runCatching { NanoQuip.quip(f, rawTone ?: "") }.getOrElse { NanoQuip.Result(null, "error", it.javaClass.simpleName) }
                reply("nanoQuip", JSONObject().put("id", id).put("text", r.text ?: JSONObject.NULL).put("status", r.status).put("reason", r.reason ?: JSONObject.NULL).toString())
            }
        }

        @JavascriptInterface fun nanoStatus() {
            if (!trusted) return
            scope.launch { runCatching { dev.phonerecall.brain.Nano.check() }; reply("nanoStatus", JSONObject.quote(NanoQuip.status())) }
        }
    }

    companion object {
        @Volatile private var instance: AeoliaHost? = null
        fun get(c: Context): AeoliaHost = instance ?: synchronized(this) { instance ?: AeoliaHost(c.applicationContext).also { instance = it } }

        private fun css(x: Color) = "#%06X".format(x.toArgb() and 0xFFFFFF)

        /** Zeus's tokens for the page: warm charcoal surfaces, ink, saffron. */
        fun tokens(c: dev.phonerecall.ui.AthenaColors): String = JSONObject()
            .put("dark", c.isDark).put("bg", css(c.surface)).put("bg2", css(c.block(Tint.NEUTRAL))).put("bg3", css(c.raised))
            .put("ink", css(c.ink)).put("ink2", css(c.ink2)).put("ink3", css(c.ink3)).put("accent", css(c.accent)).put("accentInk", css(c.accentInk))
            .put("alert", css(c.alert))
            .toString()
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Aeolia: Zeus's header over BetweenWeather's page. [preview] draws a plain block instead (screenshot tests have no WebView). */
@Composable
fun AeoliaScreen(gutter: Dp, preview: Boolean = false) {
    val c = Athena.colors
    val tokens = AeoliaHost.tokens(c)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.WEATHER.title(), gutter, emblem = Place.WEATHER, subtitle = Place.WEATHER.subtitle())
        if (preview) { Box(Modifier.fillMaxWidth().weight(1f).background(c.block(Tint.RAIN))); return@Column }
        val activity = LocalContext.current.activity() ?: return@Column
        val host = AeoliaHost.get(activity)
        val token = remember { IntArray(1) }
        AndroidView(
            factory = { host.attach(activity).let { (w, t) -> token[0] = t; w } },
            update = { host.theme(tokens) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        DisposableEffect(host) { onDispose { host.detach(token[0]) } }
    }
}
