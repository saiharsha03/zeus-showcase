package dev.phonerecall.brain

import com.google.android.datatransport.Encoding
import com.google.android.datatransport.Event
import com.google.android.datatransport.cct.CCTDestination
import com.google.android.datatransport.runtime.TransportRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** ML Kit's telemetry is stubbed out, and the second network layer still trusts only api.anthropic.com and the two Spotify hosts. */
class TelemetryStripTest {
    @Test fun theRealUploaderIsNotOnTheClasspath() {
        for (c in listOf(
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.cct.CctBackendFactory",
            "com.google.android.datatransport.cct.CctTransportBackend",
        )) {
            try { Class.forName(c); fail("$c is still bundled") } catch (_: ClassNotFoundException) { }
        }
    }

    @Test fun mlKitsLoggerGetsATransportThatDropsEverything() {
        val before = TransportRuntime.droppedCount()
        val factory = TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE)
        val t = factory.getTransport("MLKIT_GENAI", ByteArray::class.java, Encoding.of("proto")) { it }
        t.send(Event.ofTelemetry(byteArrayOf(1, 2, 3)))
        t.schedule(Event.ofTelemetry(byteArrayOf(4))) { e -> assertEquals(null, e) }
        assertEquals(before + 2, TransportRuntime.droppedCount())
        assertTrue(CCTDestination.INSTANCE.supportedEncodings.contains(Encoding.of("proto")))
        assertEquals(null, CCTDestination.INSTANCE.extras)
    }

    /** Phase 2c: MediaPipe's RemoteLoggingClient makes exactly these calls (javap of tasks-core 1.1.0); all drop. */
    @Test fun mediaPipesStatsLoggerGetsTheSameDroppingTransport() {
        val before = TransportRuntime.droppedCount()
        val t = TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE).getTransport("MEDIAPIPE", String::class.java, Encoding.of("proto")) { it.toByteArray() }
        t.send(Event.ofData("solution event"))
        assertEquals(before + 1, TransportRuntime.droppedCount())
        for (c in listOf("com.google.android.datatransport.runtime.scheduling.DefaultScheduler", "com.google.android.datatransport.runtime.backends.BackendRegistry")) {
            try { Class.forName(c); fail("$c is still bundled") } catch (_: ClassNotFoundException) { }
        }
    }

    /** Phase 3: firebase-messaging is in, its datatransport logging must hit the same stubs and nothing else uploads. */
    @Test fun firebaseBringsNoUploader() {
        // firebase-datatransport's TransportRegistrar stays: it only asks the stub for a factory.
        for (c in listOf(
            "com.google.android.datatransport.runtime.scheduling.persistence.SQLiteEventStore",
            "com.google.android.datatransport.runtime.backends.MetadataBackendRegistry",
            "com.google.firebase.analytics.FirebaseAnalytics",
            "com.google.android.gms.measurement.AppMeasurement",
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            "com.google.android.gms.clearcut.ClearcutLogger",
        )) {
            try { Class.forName(c); fail("$c is bundled") } catch (_: ClassNotFoundException) { }
        }
        // Messaging's delivery metrics go through TransportFactory, which is the dropping stub.
        val before = TransportRuntime.droppedCount()
        TransportRuntime.getInstance().newFactory(CCTDestination.LEGACY_INSTANCE).getTransport("FCM_CLIENT_EVENT_LOGGING", String::class.java, Encoding.of("proto")) { it.toByteArray() }
            .send(Event.ofData("message_delivered"))
        assertEquals(before + 1, TransportRuntime.droppedCount())
    }

    @Test fun networkSecurityConfigTrustsOnlyTheAllowedHosts() {
        val xml = File(System.getProperty("user.dir"), "src/main/res/xml/network_security_config.xml").readText()
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(xml).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("api.anthropic.com", "zeus.example.com", "api.hetzner.cloud", "firebaseinstallations.googleapis.com", "accounts.spotify.com", "api.spotify.com") + NetGate.SPORTS_HOSTS + NetGate.WEATHER_HOSTS + NetGate.ROAD_HOSTS + NetGate.LIVE_HOST + NetGate.CALENDAR_HOST + NetGate.GITHUB_HOSTS, domains)
        assertTrue(Regex("<domain [^>]*includeSubdomains=\"true\"").find(xml) == null)
        // NetGate's own list is the same minus Firebase's registration host, which only Firebase's code calls, and
        // Gemini Live's host, which NetGate opens for one path only while a Live session runs, and the Google Calendar
        // host, which NetGate opens for his primary calendar's events path only.
        assertEquals(domains.toSet() - "firebaseinstallations.googleapis.com" - NetGate.LIVE_HOST - NetGate.CALENDAR_HOST, NetGate.HOSTS)
        assertTrue(Regex("<base-config[^>]*cleartextTrafficPermitted=\"false\"[^>]*>\\s*<trust-anchors\\s*/>").containsMatchIn(xml))
    }
}
