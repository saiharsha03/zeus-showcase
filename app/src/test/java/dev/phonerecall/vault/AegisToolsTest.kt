package dev.phonerecall.vault

import com.anthropic.core.jsonMapper
import dev.phonerecall.brain.EngineTest
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.mind.FakeContext
import dev.phonerecall.mind.FakeExtras
import dev.phonerecall.mind.MindTools
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AegisToolsTest {
    private val secret = "sk-ant-api03-SEALED-0123456789"

    private fun box(r: VaultRig) = ToolBox(EngineTest.EmptyReads, Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> }), vault = r.tools)
    private fun mind(r: VaultRig) = MindTools(box(r), FakeExtras(), r.tools) { Sanitizer(FakeContext().names(), FakeContext().sealed()) }

    @Test fun `every agent read is logged with who, when and why`() {
        val r = VaultRig()
        r.aegis.put("delivery_address", "12 Elm St, Springfield", "for orders", AegisScope.AGENTS)
        r.now += 60_000
        val out = box(r).run("aegis_read", mapOf("name" to "delivery_address", "why" to "Costco order"), null)
        assertFalse(out.isError)
        assertEquals("12 Elm St, Springfield", jsonMapper().readTree(out.text)["value"].asText())
        val row = r.aegis.log().first()
        assertEquals(listOf("Delphi", "used", "delivery_address", "Costco order", r.now), listOf(row.who, row.action, row.item, row.why, row.at))
        assertEquals(1, r.aegis.items().single().uses)
    }

    @Test fun `revoked and Athena-only items are refused, and the refusal is logged`() {
        val r = VaultRig()
        r.aegis.put("wifi", "Hearth-5G / pass", "home Wi-Fi", AegisScope.ATHENA)
        r.aegis.put("ff_number", "DL 1234567", "Delta SkyMiles", AegisScope.AGENTS)
        // Athena's circle may use only items shared with workers.
        val circle = mind(r).run("aegis_read", mapOf("name" to "wifi", "why" to "setting up a plug"), null, asCircle = true)
        assertTrue(circle.isError)
        assertEquals("Athena's circle refused", r.aegis.log().first().let { "${it.who} ${it.action}" })
        assertFalse(mind(r).run("aegis_read", mapOf("name" to "ff_number", "why" to "book flight"), null, asCircle = true).isError)
        // Athena herself gets Athena-only items, unless he revoked them.
        assertFalse(mind(r).run("aegis_read", mapOf("name" to "wifi", "why" to "x"), null, asCircle = false).isError)
        r.aegis.revoke("wifi", true)
        val refused = mind(r).run("aegis_read", mapOf("name" to "wifi", "why" to "again"), null, asCircle = false)
        assertTrue(refused.isError)
        assertTrue(refused.text.contains("turned agent use off"))
        assertEquals(listOf("Athena", "refused", "again"), r.aegis.log().first().let { listOf(it.who, it.action, it.why) })
        // A revoked item isn't even listed.
        assertFalse(box(r).run("aegis_list", emptyMap(), null).text.contains("wifi"))
    }

    @Test fun `no tool reaches Calypso's contents`() {
        val r = VaultRig()
        val b = box(r)
        val m = mind(r)
        // The only Calypso tool is the deposit, for Delphi and for the mind.
        assertEquals(listOf("calypso_deposit"), b.definitions.map { it.name() }.filter { it.contains("calypso") })
        assertEquals(listOf("calypso_deposit"), (m.specs(false) + m.specs(true)).map { it.name }.filter { it.contains("calypso") }.distinct())
        val dep = b.run("calypso_deposit", mapOf("label" to "Anthropic key", "value" to secret), null)
        assertFalse(dep.isError)
        assertFalse(dep.text.contains(secret))
        for (name in listOf("calypso_read", "calypso_list", "calypso_open", "calypso_move", "read_calypso")) {
            assertTrue(name, b.run(name, emptyMap(), null).isError)
            assertTrue(name, m.run(name, emptyMap(), null, false).isError)
        }
        // Nothing any read tool answers carries it.
        val reads = listOf("now", "read_calendar", "read_tasks", "read_availability", "read_home_cards", "search_memory", "aegis_list")
        assertTrue(reads.none { b.run(it, mapOf("query" to "Anthropic key"), null).text.contains(secret) })
        assertTrue(listOf("now", "read_cards", "read_sections", "search_memory", "aegis_list").none { m.run(it, mapOf("query" to "Anthropic"), null, false).text.contains(secret) })
        assertTrue(b.run("aegis_read", mapOf("name" to "Anthropic key", "why" to "x"), null).isError)
    }

    @Test fun `the tools hold only the drop and Aegis reads`() {
        // Structural: no tool class has a field that could open Calypso.
        val forbidden = listOf(Calypso::class.java, VaultDb::class.java, BoxKeys::class.java, Unwrapper::class.java, VaultCore::class.java, Aegis::class.java)
        for (cls in listOf(VaultTools::class.java, ToolBox::class.java, MindTools::class.java)) {
            for (f in cls.declaredFields) assertTrue("${cls.simpleName}.${f.name}", forbidden.none { it.isAssignableFrom(f.type) })
        }
        val r = VaultRig()
        val drop = VaultTools::class.java.getDeclaredField("drop").apply { isAccessible = true }.get(r.tools)
        assertFalse(drop is Calypso)
    }

    @Test fun `the gate opens only on a good fingerprint and locks again`() {
        var now = 0L
        val g = VaultGate({ now })
        val bio = FakeBiometric(succeed = false)
        g.unlock(bio, "Open", "x")
        assertFalse(g.unlocked)
        assertEquals("Fingerprint not recognised", g.failure)
        bio.succeed = true
        g.unlock(bio, "Open", "x")
        assertTrue(g.unlocked)
        now = 10_000; g.stopped(screenOff = false); now = 50_000; g.started()
        assertTrue("back within 60 s", g.unlocked)
        g.stopped(screenOff = false); now = 120_000; g.started()
        assertFalse("away over 60 s", g.unlocked)
        g.unlock(bio, "Open", "x")
        g.stopped(screenOff = true)
        assertFalse("screen off", g.unlocked)
    }
}
