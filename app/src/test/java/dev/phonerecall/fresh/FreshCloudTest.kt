package dev.phonerecall.fresh

import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.MindPrompts
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.Rig
import dev.phonerecall.mind.SchedChange
import dev.phonerecall.mind.Schedules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshCloudTest {
    /** Athena as she was the day before: a daily session, a task's session, her own schedule, a paused built-in, memory files. */
    private fun lived(): Rig {
        val r = Rig()
        r.mind.book.apply(SchedChange("create", "bills-check", "0 9 * * 1", "Check the week's bills."))
        val athena = r.mind.ensure()
        r.mind.session(athena)
        val annabeth = r.api.findAgent(Protege.ANNABETH.agentName)!!
        r.api.createSession(annabeth, r.mind.ids.environment!!, r.mind.ids.store!!, "task", "n1", 25)
        r.api.setDeploymentPaused(r.mind.ids.deployment(Schedules.PREFIX + "dream")!!, true)
        r.mind.syncMemory()
        r.api.createMemory(r.mind.ids.store!!, "/notes/old.md", "an old note")
        return r
    }

    @Test fun herCloudSideStartsClean() {
        val r = lived()
        assertEquals(2, r.api.sessions.size)
        assertTrue(r.api.memories.isNotEmpty())
        // The wipe leaves her stored agents and store, and marks the reset due.
        r.kv.put(FreshCloud.KEY, FreshCloud.PENDING)
        val done = FreshCloud(r.api, r.kv).run()!!
        assertEquals(2, done.sessions)
        assertTrue(r.api.sessions.keys.all { it in r.api.archivedSessions })
        // Her own schedule is archived; the built-ins stay, unpaused.
        assertEquals(1, done.deployments)
        assertTrue(r.api.deployments.values.none { it.name == Schedules.PREFIX + "bills-check" })
        assertEquals(Schedules.ALL.size, r.api.deployments.size)
        assertTrue(r.api.paused.isEmpty())
        // Her memory store is empty.
        assertTrue(r.api.memories.isEmpty())
        assertFalse(FreshCloud.due(r.kv))
        // Once only.
        assertNull(FreshCloud(r.api, r.kv).run())
    }

    @Test fun ensureRunsItFirstAndSheStartsABrandNewSessionWithNoHandoff() {
        val r = lived()
        val old = r.mind.currentSession()!!
        // What the wipe leaves of the mind's prefs: the kept keys only, plus the reset due.
        val kept = r.kv.map.filterKeys { dev.phonerecall.fresh.FreshPlan.keepKey("mind", it) }
        r.kv.map.clear(); r.kv.map.putAll(kept)
        r.kv.put(FreshCloud.KEY, FreshCloud.PENDING)
        val athena = r.mind.ensure()
        assertTrue(old in r.api.archivedSessions)
        assertTrue(r.sink.logs.any { it.startsWith("Athena fresh start:") })
        // The built-ins are deployed again at the book's hours (her schedule isn't in the book any more).
        assertEquals(Schedules.ALL.map { Schedules.PREFIX + it.kind }.toSet(), r.api.deployments.values.map { it.name }.toSet())
        val s = r.mind.session(athena)!!
        assertTrue(s != old)
        assertTrue(r.mind.seed().contains("Nothing yet: this is your first session."))
    }

    @Test fun aFailureRetriesThenGivesUpRatherThanBlockHerForever() {
        val r = Rig()
        r.kv.put(FreshCloud.KEY, FreshCloud.PENDING)
        val api = object : dev.phonerecall.mind.MindApi by r.api {
            override fun findAgent(name: String) = throw java.io.IOException("offline")
        }
        val logs = ArrayList<String>()
        repeat(FreshCloud.MAX_TRIES - 1) { assertTrue(runCatching { FreshCloud(api, r.kv) { a, d -> logs += "$a: $d" }.run() }.isFailure) }
        assertTrue(FreshCloud.due(r.kv))
        assertNull(FreshCloud(api, r.kv) { a, d -> logs += "$a: $d" }.run())
        assertFalse(FreshCloud.due(r.kv))
        assertTrue(logs.single().startsWith("fresh start incomplete"))
    }

    @Test fun notDueOnAPhoneThatWasNeverWiped() {
        val kv = MapKv()
        assertNull(FreshCloud(dev.phonerecall.mind.FakeMindApi(), kv).run())
        assertEquals(MindPrompts.STORE_NAME, "athena")
    }
}
