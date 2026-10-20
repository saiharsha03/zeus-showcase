package dev.phonerecall.brain

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.core.http.Interceptor
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** A request NetGate refused. Not an IOException, so the SDK never retries it. */
class BlockedHostException(val url: String) : SecurityException("Blocked by NetGate: ${NetGate.redact(url)}")

/**
 * The one door to the internet. It allows exactly these hosts, https (or wss for Lemnos) on 443 only, and
 * blocks and logs everything else:
 * - api.anthropic.com (the brain)
 * - Lemnos's gateway (the VPS, behind a client certificate) and api.hetzner.cloud (reboot and power cycle
 *   when the box is down)
 * - accounts.spotify.com (PKCE token exchange and refresh) and api.spotify.com (the Web API)
 * - site.api.espn.com and api.jolpi.ca (Olympia's public sports JSON, read only, no keys)
 * - Aeolia's weather hosts ([WEATHER_HOSTS]): Open-Meteo forecast, geocoding and air quality, NWS alerts,
 *   RainViewer radar, Iowa Mesonet NEXRAD and HRRR, and Esri's gray canvas basemap tiles. Public, no keys.
 *   aeolia/WeatherNet.kt narrows each one to the exact paths BetweenWeather calls.
 * - api.tomtom.com and 511ny.org (Odyssey's traffic: the two ends of the trip being checked go to TomTom
 *   at check time; 511NY gets only the key and its list is filtered on the phone)
 * - generativelanguage.googleapis.com (Gemini Live, voice/LiveSession.kt): only the Live WebSocket path
 *   ([LIVE_PATH]) and only while a Live session is open ([liveOpen]). The Gemini key goes only there.
 * - www.googleapis.com, for Google Calendar birthday events only (data/Birthdays.kt): only his primary
 *   calendar's events path ([CALENDAR_PATH]). The sign-in for it is Google Play services' (its own process).
 * - api.github.com and release-assets.githubusercontent.com ([GITHUB_HOSTS]), for Prometheus only
 *   (prometheus/GitHub.kt): PR and CI status, merge, starting the release workflow, and the APK download
 *   GitHub redirects to. Each hop is checked here; the redirect is followed by hand, never by OkHttp.
 * The Anthropic SDK is built with [interceptor] and Lemnos's OkHttp client with [okHttp]; the Spotify and
 * Olympia clients call [check] themselves. Spotify's sign-in page opens in a Custom Tab (the browser's
 * process) and App Remote is IPC to the Spotify app, so neither needs a host here. Hestia's Google Home
 * APIs are IPC to Google Play services (its own process does the network), so they need none either.
 *
 * Second layer, outside this code: res/xml/network_security_config.xml turns cleartext off and trusts
 * no certificate authority except for those hosts plus firebaseinstallations.googleapis.com, which
 * Firebase's own code calls once to register for FCM wake-ups (the push itself arrives through Google
 * Play services, another process).
 */
object NetGate {
    const val ORIGIN = "https://api.anthropic.com"
    const val LEMNOS_HOST = "zeus.example.com"
    const val HETZNER_HOST = "api.hetzner.cloud"
    /** Olympia's two sports hosts. Exact names: no subdomains. */
    val SPORTS_HOSTS = setOf("site.api.espn.com", "api.jolpi.ca")
    /** Aeolia's weather hosts, exactly the ones BetweenWeather's page and native code call. */
    val WEATHER_HOSTS = setOf(
        "api.open-meteo.com", "geocoding-api.open-meteo.com", "air-quality-api.open-meteo.com", "api.weather.gov",
        "api.rainviewer.com", "tilecache.rainviewer.com", "mesonet.agron.iastate.edu", "server.arcgisonline.com",
    )
    /** Odyssey's two traffic hosts. Exact names: no subdomains. */
    val ROAD_HOSTS = setOf("api.tomtom.com", "511ny.org")
    /** Gemini Live: one path on this host, only while [liveOpen]. Not in [HOSTS]. */
    const val LIVE_HOST = "generativelanguage.googleapis.com"
    const val LIVE_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    /** Google Calendar birthday events only: his primary calendar's events, nothing else on this host. Not in [HOSTS]. */
    const val CALENDAR_HOST = "www.googleapis.com"
    const val CALENDAR_PATH = "/calendar/v3/calendars/primary/events"
    /** Prometheus only: the GitHub REST API and the host its release-asset downloads redirect to (checked 10-20). */
    val GITHUB_HOSTS = setOf("api.github.com", "release-assets.githubusercontent.com")
    /** True only between a Live session's start and stop. */
    @Volatile var liveOpen = false
    val HOSTS = setOf("api.anthropic.com", LEMNOS_HOST, HETZNER_HOST, "accounts.spotify.com", "api.spotify.com") + SPORTS_HOSTS + WEATHER_HOSTS + ROAD_HOSTS + GITHUB_HOSTS

    data class Blocked(val at: Long, val url: String)

    private val listeners = CopyOnWriteArrayList<(Blocked) -> Unit>()

    /** Called on every blocked request (the audit log listens). Returns a handle to stop listening. */
    fun onBlocked(listener: (Blocked) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** True only for https or wss, one of [HOSTS] exactly (or the Live path while [liveOpen], or the calendar events path), default port, no user info. */
    fun allows(url: String): Boolean {
        val u = try { URI(url) } catch (_: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return (u.scheme.equals("https", ignoreCase = true) || u.scheme.equals("wss", ignoreCase = true)) &&
            (host in HOSTS || (host == LIVE_HOST && liveOpen && u.rawPath == LIVE_PATH) || (host == CALENDAR_HOST && calendarPath(u.rawPath))) &&
            (u.port == -1 || u.port == 443) &&
            u.rawUserInfo == null
    }

    /** The events collection or one event in it, never another calendar or another Google API. */
    private fun calendarPath(path: String?): Boolean =
        path == CALENDAR_PATH || (path != null && path.startsWith("$CALENDAR_PATH/") && path.indexOf('/', CALENDAR_PATH.length + 1) == -1 && !path.contains(".."))

    /** Throws [BlockedHostException] (and tells the listeners) unless [allows]. An allowed request goes in the [Outbound] log. */
    fun check(url: String) {
        if (allows(url)) { Outbound.sent(url); return }
        val b = Blocked(System.currentTimeMillis(), redact(url))
        for (l in listeners) runCatching { l(b) }
        dev.phonerecall.clio.Clio.warn("netgate", "blocked", hostOf(url))
        throw BlockedHostException(url)
    }

    /** Scheme, host and path only: no query string in logs. */
    fun redact(url: String): String = url.substringBefore('?').take(200)

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: "?"

    /** Clio's line for one request: method, host, status and time. Never the path, the query, a body or a header. */
    fun clioLine(method: String, url: String, status: Int?, ms: Long): String = "$method ${hostOf(url)} ${status ?: "no response"} · $ms ms"

    fun logRequest(method: String, url: String, status: Int?, ms: Long) = dev.phonerecall.clio.Clio.log(
        "netgate", if (status == null || status >= 400) dev.phonerecall.clio.Level.WARN else dev.phonerecall.clio.Level.INFO, "request", clioLine(method, url, status, ms),
    )

    /** For Zeus's own OkHttp clients (Lemnos, Hetzner): refuses before a connection is opened. */
    val okHttp = okhttp3.Interceptor { chain ->
        val req = chain.request()
        check(req.url.toString())
        val t = System.currentTimeMillis()
        try { chain.proceed(req).also { logRequest(req.method, req.url.toString(), it.code, System.currentTimeMillis() - t) } }
        catch (e: java.io.IOException) { logRequest(req.method, req.url.toString(), null, System.currentTimeMillis() - t); throw e }
    }

    /** Wraps the SDK's HTTP client so nothing reaches OkHttp without passing [check]. */
    val interceptor: Interceptor = object : Interceptor {
        override fun intercept(httpClient: HttpClient): HttpClient = GatedHttpClient(httpClient)
    }
}

/** The SDK's HTTP client with NetGate in front of it. */
class GatedHttpClient(private val inner: HttpClient) : HttpClient {
    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        NetGate.check(request.url())
        val t = System.currentTimeMillis()
        try { return inner.execute(request, requestOptions).also { NetGate.logRequest(request.method.toString(), request.url(), it.statusCode(), System.currentTimeMillis() - t) } }
        catch (e: Exception) { NetGate.logRequest(request.method.toString(), request.url(), null, System.currentTimeMillis() - t); throw e }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> =
        try {
            NetGate.check(request.url())
            val t = System.currentTimeMillis()
            inner.executeAsync(request, requestOptions).whenComplete { r, _ -> NetGate.logRequest(request.method.toString(), request.url(), r?.statusCode(), System.currentTimeMillis() - t) }
        } catch (e: BlockedHostException) {
            CompletableFuture<HttpResponse>().apply { completeExceptionally(e) }
        }

    override fun close() = inner.close()
}
