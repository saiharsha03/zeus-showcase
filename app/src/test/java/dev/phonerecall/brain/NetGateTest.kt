package dev.phonerecall.brain

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

class NetGateTest {
    /** Stands in for OkHttp: counts what actually got through. */
    private class Counting : HttpClient {
        var calls = 0
        override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse { calls++; throw IllegalStateException("reached the network") }
        override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> { calls++; return CompletableFuture() }
        override fun close() = Unit
    }

    private fun req(base: String) = HttpRequest.builder().method(HttpMethod.POST).baseUrl(base).addPathSegments("v1", "messages").build()

    @Test fun allowsTheLemnosAndHetznerHosts() {
        assertTrue(NetGate.allows("https://zeus.example.com/api/health"))
        assertTrue(NetGate.allows("wss://zeus.example.com/ws/term?ticket=x"))
        assertTrue(NetGate.allows("https://api.hetzner.cloud/v1/servers"))
        assertFalse(NetGate.allows("ws://zeus.example.com/ws/term"))          // cleartext socket
        assertFalse(NetGate.allows("https://claw.example.com/api/ping"))       // the retired Claw site
        assertFalse(NetGate.allows("https://hub.example.com/"))                 // the retired hub
        assertFalse(NetGate.allows("https://203.0.113.10/"))                              // the bare address
        assertFalse(NetGate.allows("https://x.zeus.example.com/"))             // a subdomain
        assertFalse(NetGate.allows("https://console.hetzner.cloud/"))
        assertFalse(NetGate.allows("https://api.hetzner.cloud:8443/v1/servers"))
        // Firebase registers itself (network_security_config.xml), but Zeus's own code never calls Google.
        assertFalse(NetGate.allows("https://firebaseinstallations.googleapis.com/v1/projects/your-gcp-project/installations"))
        assertFalse(NetGate.allows("https://fcm.googleapis.com/v1/projects/your-gcp-project/messages:send"))
    }

    @Test fun okHttpInterceptorRefusesBeforeConnecting() {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor(NetGate.okHttp).build()
        try {
            client.newCall(okhttp3.Request.Builder().url("https://example.com/").build()).execute()
            fail("should have been blocked")
        } catch (e: BlockedHostException) {
            assertTrue(e.url.startsWith("https://example.com"))
        }
    }

    @Test fun allowsOnlyTheAnthropicApi() {
        assertTrue(NetGate.allows("https://api.anthropic.com/v1/messages"))
        assertTrue(NetGate.allows("https://API.anthropic.com:443/v1/messages?beta=true"))
        assertFalse(NetGate.allows("http://api.anthropic.com/v1/messages"))           // cleartext
        assertFalse(NetGate.allows("https://api.anthropic.com:8443/v1/messages"))     // other port
        assertFalse(NetGate.allows("https://evil.com/v1/messages"))
        assertFalse(NetGate.allows("https://api.anthropic.com.evil.com/v1"))           // suffix trick
        assertFalse(NetGate.allows("https://console.anthropic.com/"))                  // other subdomain
        assertFalse(NetGate.allows("https://user@api.anthropic.com/v1"))               // user info
        assertFalse(NetGate.allows("not a url"))
    }

    @Test fun blocksOtherHostsBeforeTheNetworkAndLogsThem() {
        val inner = Counting()
        val gated = NetGate.interceptor.intercept(inner)
        val seen = ArrayList<NetGate.Blocked>()
        val stop = NetGate.onBlocked { seen += it }
        try {
            gated.execute(req("https://example.com"), RequestOptions.none())
            fail("should have been blocked")
        } catch (e: BlockedHostException) {
            assertTrue(e.url.startsWith("https://example.com"))
        } finally { stop() }
        assertEquals(0, inner.calls)
        assertEquals(1, seen.size)

        try {
            gated.executeAsync(req("http://api.anthropic.com"), RequestOptions.none()).get()
            fail("should have been blocked")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is BlockedHostException)
        }
        assertEquals(0, inner.calls)
    }

    @Test fun stillBlocksEverythingMlKitAndGoogleWouldCall() {
        // Phase 2b added ML Kit. None of Google's endpoints may pass NetGate in this process.
        for (u in listOf(
            "https://firebaselogging-pa.googleapis.com/v1/firelog/legacy/batchlog",
            "https://firebaselogging.googleapis.com/v0cc/log/batch",
            "https://play.googleapis.com/log/batch",
            "https://generativelanguage.googleapis.com/v1beta/models",
            "https://www.googleapis.com/",
            "https://dl.google.com/",
        )) assertFalse(u, NetGate.allows(u))
    }

    @Test fun allowsExactlyTheTwoSpotifyHosts() {
        assertTrue(NetGate.allows("https://accounts.spotify.com/api/token"))
        assertTrue(NetGate.allows("https://api.spotify.com/v1/me/player"))
        for (u in listOf(
            "http://api.spotify.com/v1/me",                 // cleartext
            "https://open.spotify.com/playlist/x",          // other subdomain
            "https://spclient.wg.spotify.com/",             // Spotify's internal API
            "https://spotify.com/",
            "https://api.spotify.com.evil.com/v1",          // suffix trick
            "https://accounts.spotify.com:8443/api/token",  // other port
            "https://token@api.spotify.com/v1/me",          // user info
            "https://i.scdn.co/image/ab67",                 // cover art CDN
        )) assertFalse(u, NetGate.allows(u))
        assertEquals(setOf("api.anthropic.com", NetGate.LEMNOS_HOST, NetGate.HETZNER_HOST, "accounts.spotify.com", "api.spotify.com") + NetGate.SPORTS_HOSTS + NetGate.WEATHER_HOSTS + NetGate.ROAD_HOSTS + NetGate.GITHUB_HOSTS, NetGate.HOSTS)
    }

    @Test fun allowsOlympiasTwoSportsHostsExactly() {
        assertTrue(NetGate.allows("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/83/schedule?fixture=true"))
        assertTrue(NetGate.allows("https://api.jolpi.ca/ergast/f1/current.json"))
        assertFalse(NetGate.allows("http://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"))  // cleartext
        assertFalse(NetGate.allows("https://site.api.espn.com.evil.com/apis"))                             // suffix trick
        assertFalse(NetGate.allows("https://evil.site.api.espn.com/apis"))                                 // other subdomain
        assertFalse(NetGate.allows("https://sports.core.api.espn.com/v2/sports"))                          // ESPN's other API
        assertFalse(NetGate.allows("https://a.espncdn.com/i/teamlogos/soccer/500/83.png"))                 // logos stay off
        assertFalse(NetGate.allows("https://api.openf1.org/v1/sessions"))                                  // not used
        assertFalse(NetGate.allows("https://api.jolpi.ca:8443/ergast/f1/current.json"))                    // other port
        assertFalse(NetGate.allows("https://me@api.jolpi.ca/ergast/f1/current.json"))                      // user info
    }

    @Test fun geminiLiveOnlyOnItsSocketPathAndOnlyWhileOpen() {
        val live = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // OkHttp hands interceptors the socket URL as https.
        val asOkHttpSeesIt = live.replaceFirst("wss://", "https://")
        try {
            NetGate.liveOpen = false
            assertFalse(NetGate.allows(live))
            assertFalse(NetGate.allows(asOkHttpSeesIt))
            NetGate.liveOpen = true
            assertTrue(NetGate.allows(live))
            assertTrue(NetGate.allows(asOkHttpSeesIt))
            for (u in listOf(
                "https://generativelanguage.googleapis.com/v1beta/models",                             // the REST API stays closed
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-live:generateContent",
                "$live/extra",                                                                          // other path
                "ws://generativelanguage.googleapis.com" + NetGate.LIVE_PATH,                          // cleartext
                "wss://generativelanguage.googleapis.com:8443" + NetGate.LIVE_PATH,                    // other port
                "wss://x.generativelanguage.googleapis.com" + NetGate.LIVE_PATH,                       // subdomain
                "wss://generativelanguage.googleapis.com.evil.com" + NetGate.LIVE_PATH,                // suffix trick
                "wss://k@generativelanguage.googleapis.com" + NetGate.LIVE_PATH,                       // user info
                "https://aiplatform.googleapis.com/",
            )) assertFalse(u, NetGate.allows(u))
        } finally { NetGate.liveOpen = false }
        assertFalse(NetGate.LIVE_HOST in NetGate.HOSTS)
    }

    @Test fun githubOnlyTheApiAndTheReleaseAssetHost() {
        assertTrue(NetGate.allows("https://api.github.com/repos/your-github-user/zeus/pulls?head=your-github-user:prometheus/c1"))
        assertTrue(NetGate.allows("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r"))
        for (u in listOf(
            "http://api.github.com/repos/your-github-user/zeus",                 // cleartext
            "https://github.com/your-github-user/zeus",                          // the website
            "https://uploads.github.com/repos/your-github-user/zeus/releases",   // uploads stay off
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1",  // the old asset host
            "https://raw.githubusercontent.com/your-github-user/zeus/main/README.md",
            "https://api.github.com.evil.com/repos",                        // suffix trick
            "https://x.api.github.com/repos",                               // subdomain
            "https://api.github.com:8443/repos",                            // other port
            "https://t@api.github.com/repos",                               // user info
        )) assertFalse(u, NetGate.allows(u))
    }

    @Test fun letsTheAnthropicApiThrough() {
        val inner = Counting()
        val gated = NetGate.interceptor.intercept(inner)
        runCatching { gated.execute(req(NetGate.ORIGIN), RequestOptions.none()) }
        assertEquals(1, inner.calls)
    }
}
