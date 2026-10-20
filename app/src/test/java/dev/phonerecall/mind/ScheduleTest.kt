package dev.phonerecall.mind

import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.StepExecutor
import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.confirmNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

class CronTest {
    @Test fun presetsRoundTrip() {
        val presets = listOf(
            Cron.Preset.Hourly(15), Cron.Preset.EveryHours(3, 0), Cron.Preset.Daily(6, 0), Cron.Preset.Weekdays(8, 30),
            Cron.Preset.Weekly(DayOfWeek.SUNDAY, 9, 0), Cron.Preset.Weekly(DayOfWeek.FRIDAY, 17, 45),
        )
        for (p in presets) {
            assertTrue(p.cron(), Cron.valid(p.cron()))
            assertEquals(p, Cron.of(p.cron()))
        }
        assertEquals(Cron.Preset.Custom("0 9 1 * *"), Cron.of("0 9 1 * *"))
        assertEquals("Weekdays at 8:30 AM", Cron.words("30 8 * * 1-5"))
        assertEquals("Sundays at 9:00 AM", Cron.words("0 9 * * 0"))
        assertEquals("Sundays at 9:00 AM", Cron.words("0 9 * * 7"))
        assertEquals("Daily at 8:00 PM", Cron.words("0 20 * * *"))
        assertEquals("Every 3 hours at :00", Cron.words("0 */3 * * *"))
    }

    @Test fun badCronIsRefusedWithWhy() {
        for (bad in listOf("", "0 6 * *", "60 6 * * *", "0 24 * * *", "0 6 32 * *", "0 6 * 13 *", "0 6 * * 8", "a b c d e", "0 6-2 * * *", "*/0 * * * *")) {
            assertNotNull(bad, Cron.problem(bad))
        }
        assertNull(Cron.problem("0,30 9-17 * * 1-5"))
        assertTrue(Cron.inDstWindow("30 2 * * *"))
        assertFalse(Cron.inDstWindow("0 6 * * *"))
    }

    @Test fun nextRunsFollowTheZone() {
        val z = ZoneId.of("America/New_York")
        val from = Instant.parse("2026-10-20T14:00:00Z")      // Tue 10:00 AM in Buffalo
        assertEquals("2026-10-21T06:00-04:00[America/New_York]", Cron.next("0 6 * * *", z, from).single().toString())
        assertEquals("2026-10-25T09:00-04:00[America/New_York]", Cron.next("0 9 * * 0", z, from).single().toString())
        assertEquals(listOf(11, 12), Cron.next("0 * * * *", z, from, count = 2).map { it.hour })
    }
}

class ScheduleBookTest {
    private fun change(action: String, name: String, cron: String? = null, prompt: String? = null) = SchedChange(action, name, cron, prompt)

    @Test fun builtInsAreThereAndKeepTheirPrompts() {
        val b = ScheduleBook(MapKv())
        assertEquals(listOf("daily-plan", "evening-recap", "weekly-hygiene", "dream"), b.jobs().map { it.name })
        assertTrue(b.jobs().all { it.builtin && it.full.startsWith("Zeus · ") })
        assertNotNull(b.problem(change("delete", "daily-plan")))
        assertNotNull(b.problem(change("update", "daily-plan", prompt = "Do something else")))
        b.apply(change("update", "Zeus · daily-plan", cron = "30 6 * * *"))
        b.apply(change("pause", "evening-recap"))
        assertEquals("30 6 * * *", b.job("daily-plan")!!.cron)
        assertTrue(b.job("evening-recap")!!.paused)
        assertEquals(Schedules.ALL[0].what, b.job("daily-plan")!!.prompt)
    }

    @Test fun validationRefusesBadCronForeignNamesAndPastTheCap() {
        val b = ScheduleBook(MapKv())
        assertNotNull(b.problem(change("create", "weekly-review", cron = "0 25 * * *", prompt = "x")))
        assertNotNull(b.problem(change("create", "weekly-review", cron = "30 1 * * *", prompt = "x")))      // DST window
        assertNotNull(b.problem(change("create", "Acme · nightly", cron = "0 6 * * *", prompt = "x")))       // not hers
        assertNotNull(b.problem(change("update", "somebody-elses", cron = "0 6 * * *")))                    // doesn't exist
        assertNotNull(b.problem(change("create", "Weekly Review", cron = "0 6 * * *", prompt = "x")))
        assertNotNull(b.problem(change("create", "weekly-review", cron = "0 6 * * *")))                       // no prompt
        assertNotNull(b.problem(change("launch", "weekly-review")))
        for (i in 1..ScheduleBook.CAP) b.apply(change("create", "job-$i", cron = "0 ${6 + i % 12} * * *", prompt = "Do thing $i"))
        assertEquals("At most 12 schedules of her own; delete one first", b.problem(change("create", "one-more", cron = "0 6 * * *", prompt = "x")))
        b.apply(change("delete", "job-1"))
        assertEquals(setOf("job-1"), b.pendingDeletes())
        assertNull(b.problem(change("create", "one-more", cron = "0 6 * * *", prompt = "x")))
    }
}

class ScheduleDeployTest {
    /** Fates with a hand that applies confirmed schedule steps to the mind's book, like BrainCore does. */
    private class BookHand(val book: ScheduleBook) : StepExecutor {
        override fun run(step: Step): ExecResult {
            val p = step.payload
            book.apply(SchedChange(p.getValue("action"), p.getValue("name"), p["schedule"], p["prompt"], p["why"], p["test_run"] == "true"))
            return ExecResult(step.summary)
        }
        override fun undo(step: Step) = false
    }

    @Test fun aConfirmedProposalDeploysAndATrialReportsToAgoraOnly() {
        val r = Rig()
        r.mind.ensure()
        val store = MemoryStore()
        val fates = Fates(store, FakeState(schedulesOf = { r.mind.book.jobs() }), BookHand(r.mind.book), { _, _, _ -> }, { r.now })
        val args = mapOf("action" to "create", "name" to "weekly-review", "schedule" to "0 18 * * 5", "prompt" to "Review the week in one card.", "why" to "Iris handed it over", "test_run" to "true")
        val created = fates.propose(listOf(RawStep(ProposalKind.SET_SCHEDULE, args)), null, null) as ProposeResult.Created
        assertEquals("New Athena schedule weekly-review: Fridays at 6:00 PM", created.proposal.title)
        assertTrue("nothing yet", r.api.deployments.values.none { it.name == "Zeus · weekly-review" })
        assertTrue(fates.confirmNow(created.proposal.id, store) is Decision.Done)

        r.mind.ensure()
        val d = r.api.deployments.values.single { it.name == "Zeus · weekly-review" }
        assertEquals("0 18 * * 5", r.api.deploymentSpecs.getValue(d.id).cron)
        assertTrue(r.api.deploymentSpecs.getValue(d.id).message.contains("Review the week in one card."))
        assertEquals(setOf("weekly-review"), r.mind.book.pendingTrials())
        r.mind.runNow("weekly-review", trial = true)
        r.mind.book.trialStarted("weekly-review")
        assertEquals(d.id, r.api.runList.single().deploymentId)

        val storeId = r.mind.ids.store!!
        // Its first outbox file goes to Agora, as counts only: the run's titles and note never leave the phone.
        r.api.createMemory(storeId, "/outbox/weekly-review-2026-10-23.json", """{"cards":[{"title":"Dr. Rao, blood test","body":"x"}],"proposals":[{"steps":[]}],"note":"paid ${'$'}1,234 rent"}""")
        r.mind.pullOutbox()
        assertTrue(r.sink.cards.isEmpty())
        assertEquals("Trial run of Zeus · weekly-review worked: 1 card(s), 1 proposal(s). It runs on its schedule from now on.", r.sink.agora.single())
        assertNull(dev.phonerecall.mind.AskGuard.refuse(r.sink.agora.single()))
        assertFalse(r.mind.book.trialRunning("weekly-review"))

        // Pause, then delete: paused on the server, then archived.
        r.mind.book.apply(SchedChange("pause", "weekly-review"))
        r.mind.applyPauses(capped = false)
        assertTrue(d.id in r.api.paused)
        r.mind.book.apply(SchedChange("delete", "weekly-review"))
        r.mind.ensure()
        assertEquals(listOf(d.id), r.api.archived)
        assertTrue(r.mind.book.pendingDeletes().isEmpty())
    }

    @Test fun aProposalForAForeignOrBadScheduleIsRefused() {
        val r = Rig()
        val fates = Fates(MemoryStore(), FakeState(schedulesOf = { r.mind.book.jobs() }), BookHand(r.mind.book), { _, _, _ -> }, { r.now })
        for (args in listOf(
            mapOf("action" to "create", "name" to "x-ray", "schedule" to "not a cron", "prompt" to "x"),
            mapOf("action" to "pause", "name" to "Other · nightly"),
            mapOf("action" to "delete", "name" to "weekly-hygiene"),
        )) assertTrue(args.toString(), fates.propose(listOf(RawStep(ProposalKind.SET_SCHEDULE, args)), null, null) is ProposeResult.Invalid)
        // Without the mind on the phone there is nothing to check against.
        assertTrue(Fates(MemoryStore(), FakeState(), BookHand(r.mind.book), { _, _, _ -> }, { r.now })
            .propose(listOf(RawStep(ProposalKind.SET_SCHEDULE, mapOf("action" to "pause", "name" to "daily-plan"))), null, null) is ProposeResult.Invalid)
    }
}
