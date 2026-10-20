package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The routing schema: what a model may answer, and what the rules settle without one. */
class RambleSchemaTest {
    @Test fun parsesEveryRoute() {
        assertEquals(Route.Task("Book the oil change", "2026-10-23"), RambleSchema.parse("""{"route":"task","title":"Book the oil change","due":"2026-10-23"}""")!!.route)
        assertEquals(
            Route.Event("Barcelona vs Real Madrid", "2026-10-25T10:15", "2026-10-25T12:15", false),
            RambleSchema.parse("""Sure: {"route":"event","title":"Barcelona vs Real Madrid","start":"2026-10-25T10:15","end":"2026-10-25T12:15"}""")!!.route,
        )
        assertEquals(Route.ClosetNote("Green scarf needs a wash"), RambleSchema.parse("""{"route":"closet","text":"Green scarf needs a wash"}""")!!.route)
        assertEquals(Route.ListItems("Groceries", listOf("Tomatoes", "Garlic")), RambleSchema.parse("""{"route":"list","section":"Groceries","items":["tomatoes","Garlic","garlic"]}""")!!.route)
        assertEquals(Route.Worker(WorkerOwner.IRIS, "Check whether the Amazon refund came in"), RambleSchema.parse("""{"route":"worker","worker":"iris","job":"Check whether the Amazon refund came in"}""")!!.route)
        assertEquals(Route.Keep("A thought about Primer"), RambleSchema.parse("""{"route":"keep","text":"A thought about Primer"}""")!!.route)
    }

    @Test fun expansionsAndRelevanceWindows() {
        val s = RambleSchema.parse(
            """{"route":"task","title":"Make pasta","expand":{"section":"Groceries","items":["pasta","tomatoes","garlic","parmesan"]},
               "relevant_until":"2026-11-05","relevance":"seasonal","reason":"Foliage season ends"}""",
        )!!
        assertEquals(Expansion("Groceries", listOf("Pasta", "Tomatoes", "Garlic", "Parmesan")), s.expand)
        assertEquals("2026-11-05", s.relevantUntil)
        assertEquals(RelevanceKind.SEASONAL, s.relevance)
        assertEquals("Foliage season ends", s.reason)
    }

    @Test fun rejectsAnythingMalformed() {
        assertNull(RambleSchema.parse("no json here"))
        assertNull(RambleSchema.parse("""{"route":"delete_everything"}"""))
        assertNull(RambleSchema.parse("""{"route":"task"}"""))
        assertNull(RambleSchema.parse("""{"route":"event","title":"Dinner","start":"tomorrow evening"}"""))
        assertNull(RambleSchema.parse("""{"route":"worker","worker":"zeus","job":"reboot the server"}"""))
        assertNull(RambleSchema.parse("""{"route":"list","section":"Groceries","items":[]}"""))
        assertNull(RambleSchema.parse("""{"route":"list","section":"Groceries","items":[${(1..21).joinToString(",") { "\"i$it\"" }}]}"""))
        assertNull(RambleSchema.parse("""{"route":"task","title":"${"x".repeat(201)}"}"""))
        // A bad optional field is dropped, the route still stands.
        val s = RambleSchema.parse("""{"route":"task","title":"Call Taylor","due":"soon","relevant_until":"whenever"}""")!!
        assertEquals(Route.Task("Call Taylor", null), s.route)
        assertNull(s.relevantUntil)
    }

    @Test fun rulesSettleTheObviousOnes() {
        assertEquals(Route.ListItems("Groceries", listOf("Tomatoes", "Garlic")), RambleSchema.rules("need tomatoes and garlic")!!.route)
        assertEquals(Route.ListItems("Shopping", listOf("Phone case")), RambleSchema.rules("buy a phone case")!!.route)
        assertEquals(WorkerOwner.IRIS, (RambleSchema.rules("did my refund come in")!!.route as Route.Worker).owner)
        assertEquals(WorkerOwner.CIRCLE, (RambleSchema.rules("research winter tires")!!.route as Route.Worker).owner)
        assertEquals(WorkerOwner.ATLAS, (RambleSchema.rules("is the server slow?")!!.route as Route.Worker).owner)
        assertEquals(WorkerOwner.CIRCLE, (RambleSchema.rules("keep an eye on Inter Miami away tickets")!!.route as Route.Worker).owner)
        assertNull(RambleSchema.rules("need to call Taylor about the report"))
        assertNull(RambleSchema.rules("make pasta this week"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RamblerFilingTest {
    @Test fun aListCaptureMakesTheSectionOnceAndSkipsDuplicates() {
        val rig = EngineRig()
        rig.engine.capture(CaptureKind.TEXT, "need tomatoes and garlic")
        rig.engine.capture(CaptureKind.TEXT, "need garlic")
        val groceries = rig.sections.byName("Groceries")!!
        assertEquals(Actor.ATHENA, groceries.createdBy)
        assertEquals(listOf("Garlic", "Tomatoes"), rig.store.items(groceries.id).map { it.text }.sorted())
        assertEquals("Already on Groceries", rig.store.captures().first().outcome)
        assertEquals(1, rig.sections.changes().count { it.op == "create" })
    }

    @Test fun expansionsAreProposalsWithoutDuplicates() {
        val rig = EngineRig()
        rig.engine.capture(CaptureKind.TEXT, "need tomatoes")
        rig.cloud = FakeCloud { """{"route":"task","title":"Make pasta","expand":{"section":"Groceries","items":["pasta","tomatoes","garlic","parmesan"]}}""" }
        rig.engine.capture(CaptureKind.VOICE, "make pasta this week")
        assertEquals("Make pasta", rig.tasks.rows.values.single().title)
        val p = rig.pending().single()
        assertEquals(ProposalKind.ADD_TO_LIST, p.steps.single().kind)
        // Tomatoes are already on the list, so only the rest is proposed.
        assertEquals("Pasta, Garlic, Parmesan", p.steps.single().payload["items"])
        // The model only saw his capture, never anything else.
        assertTrue(rig.cloud!!.prompts.single().endsWith("Capture: make pasta this week"))
    }

    @Test fun workerJobsWaitForThePhase3Channel() {
        val rig = EngineRig()
        rig.engine.capture(CaptureKind.TEXT, "did my refund come in")
        val job = rig.store.jobs().single()
        assertEquals(WorkerOwner.IRIS, job.owner)
        assertEquals(SenseStore.JOB_WAITING, job.status)
        assertEquals("Iris", rig.store.captures().single().place)
        assertTrue(rig.cloud!!.prompts.isEmpty())
    }

    @Test fun undoPutsItBack() {
        val rig = EngineRig()
        rig.cloud = FakeCloud { """{"route":"task","title":"Book the oil change"}""" }
        val id = rig.engine.capture(CaptureKind.TEXT, "oil change soon")
        assertEquals(1, rig.tasks.rows.size)
        rig.engine.undoCapture(id)
        assertEquals(0, rig.tasks.rows.size)
        assertEquals("Undone. Kept here.", rig.store.capture(id)!!.outcome)
    }

    @Test fun withNoModelItStaysUnsorted() {
        val rig = EngineRig()
        rig.cloud = null
        val id = rig.engine.capture(CaptureKind.TEXT, "that thing from the podcast")
        assertEquals(CaptureStatus.UNSORTED, rig.store.capture(id)!!.status)
    }
}
