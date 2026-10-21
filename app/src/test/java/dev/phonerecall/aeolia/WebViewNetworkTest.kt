package dev.phonerecall.aeolia

import dev.phonerecall.brain.NetGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WebViews would use Chromium's own TLS verifier, not TlsGuard, so none of them may reach the network themselves. */
class WebViewNetworkTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    @Test fun aeoliaPageAnswersEveryRequestThroughWeatherNet() {
        val t = File(main, "java/dev/phonerecall/aeolia/AeoliaPage.kt").readText()
        assertTrue("shouldInterceptRequest" in t)
        assertTrue("WeatherNet.get(url)" in t)                      // Fetch route: TlsGuard via WeatherNet
        assertTrue("AeoliaRoutes.Route.Block" in t)                 // everything else is answered 403, never passed on
        assertFalse("return null" in t.substringAfter("shouldInterceptRequest").substringBefore("blocked()"))
        assertTrue("TlsGuard.open(" in File(main, "java/dev/phonerecall/aeolia/WeatherNet.kt").readText())
    }

    @Test fun aeoliaAssetsOnlyNameNetGateHosts() {
        val ok = NetGate.HOSTS + setOf("www.w3.org", "www.weather.gov")   // w3.org is an SVG namespace; weather.gov is link text, navigation is blocked
        val re = Regex("https?://([A-Za-z0-9.-]+)")
        File(main, "assets/aeolia").walkTopDown().filter { it.extension in setOf("js", "html") }.forEach { f ->
            re.findAll(f.readText().replace(Regex("(?s)/[*].*?[*]/"), " ").replace(Regex("(?m)^[ \t]*//.*"), " ")).forEach { assertTrue("${f.name} names ${it.groupValues[1]}", it.groupValues[1].lowercase() in ok) }   // comments name sources, not requests
        }
    }

    @Test fun terminalWebViewBlocksNetworkLoads() {
        val t = File(main, "java/dev/phonerecall/lemnos/Terminal.kt").readText()
        assertTrue("blockNetworkLoads = true" in t)
        val html = File(main, "assets/lemnos/term.html").readText()
        assertFalse(Regex("https?://|fetch[(]|XMLHttpRequest").containsMatchIn(html))
    }
}
