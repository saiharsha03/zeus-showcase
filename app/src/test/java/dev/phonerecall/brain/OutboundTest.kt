package dev.phonerecall.brain

import dev.phonerecall.places.ArrivalText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutboundTest {
    private val seen = ArrayList<OutboundRow>()
    private val stop = Outbound.onSent { seen += it }

    @After fun tearDown() { stop(); NameGuard.update(emptyList()) }

    @Test fun everyAllowedRequestIsLoggedWithoutItsContent() {
        NetGate.check("https://api.open-meteo.com/v1/forecast?latitude=42.886&longitude=-78.878")
        val r = seen.single()
        assertEquals("api.open-meteo.com", r.host)
        assertEquals("Aeolia weather", r.purpose)
        assertEquals(setOf(FieldCat.COORDINATES), r.fields)
        // Only the host, a purpose and the kinds: nothing of the URL's query survives.
        assertFalse(r.toString().contains("42.886"))
    }

    @Test fun aBlockedRequestIsNotLoggedAsSent() {
        try { NetGate.check("https://example.com/x") ; fail() } catch (_: BlockedHostException) { }
        assertTrue(seen.isEmpty())
    }

    @Test fun theCallerSaysWhatARequestIsFor() {
        Outbound.tagged("Delphi chat", setOf(FieldCat.MESSAGE_TEXT, FieldCat.NAMES)) { NetGate.check("https://api.anthropic.com/v1/messages") }
        NetGate.check("https://api.anthropic.com/v1/messages")
        assertEquals(listOf("Delphi chat", "Athena (Claude)"), seen.map { it.purpose })
        assertEquals(setOf(FieldCat.MESSAGE_TEXT, FieldCat.NAMES), seen[0].fields)
    }

    @Test fun tagsNestAndRestore() {
        Outbound.tagged("outer", emptySet()) {
            Outbound.tagged("inner", emptySet()) { NetGate.check("https://api.spotify.com/v1/me/player") }
            NetGate.check("https://api.spotify.com/v1/me/player")
        }
        assertEquals(listOf("inner", "outer"), seen.map { it.purpose })
    }

    @Test fun everyAllowedHostHasADefaultPurpose() {
        for (h in NetGate.HOSTS) assertTrue(h, Outbound.default(h, "/").purpose != "Unlisted")
        assertEquals(setOf(FieldCat.COORDINATES, FieldCat.KEY), Outbound.default("api.tomtom.com", "/routing").fields)
        assertEquals(setOf(FieldCat.VOICE, FieldCat.KEY), Outbound.default(NetGate.LIVE_HOST, NetGate.LIVE_PATH).fields)
        assertEquals("Athena's mind (Managed Agents)", Outbound.default("api.anthropic.com", "/v1/sessions").purpose)
    }

    @Test fun theLineSaysNamesAndCoordinatesYesOrNo() {
        assertEquals("names: no, coordinates: yes", Outbound.describe(setOf(FieldCat.COORDINATES)))
        assertEquals("names: yes, coordinates: no, your words", Outbound.describe(setOf(FieldCat.NAMES, FieldCat.MESSAGE_TEXT)))
    }

    @Test fun namesAndCoordinatesAreFoundInTheText() {
        NameGuard.update(listOf("Taylor Reed", "Amma"))
        assertEquals(setOf(FieldCat.MESSAGE_TEXT, FieldCat.NAMES), Outbound.fieldsOf("ask taylor about the report", setOf(FieldCat.MESSAGE_TEXT)))
        assertEquals(setOf(FieldCat.COORDINATES), Outbound.fieldsOf("from 42.8864, -78.8784", emptySet()))
        assertEquals(emptySet<FieldCat>(), Outbound.fieldsOf("tomatoes, garlic", emptySet()))
        assertTrue(HaikuModel.outgoing("rambler", "Call Amma tonight").fields.contains(FieldCat.NAMES))
        assertEquals(setOf(FieldCat.NOTIFICATION_LINES), HaikuModel.outgoing("triage", "family (mother): missed call").fields)
    }

    @Test fun nameGuardMatchesWholeWordsOnly() {
        val names = NameGuard.tokens(listOf("Taylor Reed", "Al"))
        assertEquals(setOf("taylor", "reed"), names)   // two letters are too short to trust
        assertTrue(NameGuard.contains("Taylor's report", names))
        assertFalse(NameGuard.contains("taylormade clubs", names))
        assertFalse(NameGuard.contains("anything", emptySet()))
    }

    @Test fun arrivalWordingWithANameNeverGoesToTheCloud() {
        NameGuard.update(listOf("Taylor Reed"))
        var called = false
        val cloud = CloudModel { _, _, _, _ -> called = true; "You're at Walmart." }
        val lists = mapOf("Errands" to listOf("Ask Taylor about the report", "milk"))
        val (text, by) = ArrivalText.compose("Walmart", lists, cloud, null, null)
        assertFalse(called)
        assertEquals("template", by)
        assertTrue(text.startsWith("You're at Walmart."))
        // Without a name the polish may go out.
        val (_, by2) = ArrivalText.compose("Walmart", mapOf("Groceries" to listOf("milk")), cloud, null, null)
        assertEquals("haiku", by2)
        assertTrue(called)
    }

    @Test fun anUnparseableUrlIsSkipped() {
        assertNull(Outbound.row("not a url", null, 0))
    }
}
