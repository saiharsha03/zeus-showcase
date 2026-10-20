package dev.phonerecall.moments

import dev.phonerecall.brain.Audit
import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.home.ActionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Bundled plans, the context cards, the quiet rules and auto-approve gating. */
class MomentsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private fun secs(p: MomentPlan) = p.steps.map { it.args.getValue("seconds").toInt() }
    private fun labels(p: MomentPlan) = p.steps.map { it.args.getValue("label") }

    // ---- bundled plans ----

    @Test fun pizzaPlanPreheatCheckDoneOffsetByPreheat() {
        val v = CookParser.parse("Preheat oven to 425°F. Bake 18-20 minutes. Let stand 2 minutes.").best!!
        val p = MomentPlans.cook(v, "Pizza")
        assertEquals("Pizza: 425°F · 18–20 min", p.title)
        // Preheat 10 min, then check at 16, done at 18, rest 2: all from Confirm.
        assertEquals(listOf(600, 600 + 960, 600 + 1080, 600 + 1200), secs(p))
        assertEquals(listOf("Oven at 425°F. Put the pizza in", "Check the pizza", "Pizza should be done (up to 20 min)", "Ready to eat: pizza"), labels(p))
        assertTrue(p.steps.all { it.kind == ProposalKind.SHORT_TIMER })
    }

    @Test fun ovenAlreadyHotDropsPreheatAndOffsets() {
        val v = CookParser.parse("Preheat oven to 425°F. Bake 18-20 minutes.").best!!
        assertEquals(listOf(960, 1080), secs(MomentPlans.cook(v, "Pizza", preheatFirst = false)))
    }

    @Test fun halfwayStepAndLongTimersAreOrdinary() {
        val v = CookParser.parse("Preheat oven to 375°F. Bake 60 minutes. Remove film. Bake an additional 5 minutes. Let stand 5 minutes.").best!!
        val p = MomentPlans.cook(v, "Lasagna")
        assertEquals(listOf(600, 600 + 3600, 600 + 3900, 600 + 4200), secs(p))
        assertEquals("Remove film", labels(p)[1])
        assertEquals(listOf(ProposalKind.SHORT_TIMER, ProposalKind.SET_TIMER, ProposalKind.SET_TIMER, ProposalKind.SET_TIMER), p.steps.map { it.kind })
    }

    @Test fun recipeStepsRunBackToBack() {
        val p = MomentPlans.recipe(listOf(RecipeStep("Simmer", 600), RecipeStep("Bake", 1500), RecipeStep("Rest", 300)))!!
        assertEquals(listOf(600, 2100, 2400), secs(p))
        assertEquals("Bake: done (step 2 of 3)", labels(p)[1])
    }

    @Test fun saidLaundryTeaWorkoutAndCooking() {
        val now = at(19)
        val laundry = (MomentText.read("washer 45 min", now) as Said.Plan).plan
        assertEquals(MomentKind.LAUNDRY, laundry.kind)
        assertEquals(ProposalKind.SHORT_TIMER, laundry.steps[0].kind)
        assertEquals("2700", laundry.steps[0].args["seconds"])
        assertEquals(ProposalKind.ADD_REMINDER, laundry.steps[1].kind)
        assertEquals("2026-10-20T20:00", laundry.steps[1].args["at"])

        val tea = (MomentText.read("steeping green tea", now) as Said.Plan).plan
        assertEquals(listOf(180), secs(tea))

        val w = (MomentText.read("plank workout, 3 sets of 45 seconds with 15 seconds rest", now) as Said.Plan).plan
        assertEquals(listOf(45, 60, 105, 120, 165), secs(w))

        // Putting it in: the oven is hot, so no preheat; a guess from the table, said so.
        val lasagna = (MomentText.read("putting lasagna in", now) as Said.Plan).plan
        assertTrue(lasagna.title.endsWith("(a guess)"))
        assertEquals(listOf(3600, 3900), secs(lasagna))

        val baking = (MomentText.read("bake 25 min then rest 5", now) as Said.Plan).plan
        assertEquals(listOf(1500), secs(baking).take(1))

        assertTrue(MomentText.read("putting the casserole in the oven", now) is Said.Ask)
        assertNull(MomentText.read("hello there", now))
    }

    // ---- auto-approve gating ----

    private val store = MemoryStore()
    private val exec = FakeExecutor()
    private val fates = Fates(store, FakeState(), exec, Audit { _, _, _ -> }, clock = { ms(at(19)) })

    @Test fun shortTimersAutoRunOnlyWhenListedAndAllShort() {
        val v = CookParser.parse("Preheat oven to 425°F. Bake 18-20 minutes.").best!!
        val plan = MomentPlans.cook(v, "Pizza")
        val off = fates.propose(plan.steps, plan.title, null, proposer = "Kairos") as ProposeResult.Created
        assertEquals(ProposalStatus.PENDING, off.proposal.status)

        fates.autoApprove = setOf(ProposalKind.SHORT_TIMER)
        val on = fates.propose(plan.steps, plan.title, null, proposer = "Kairos") as ProposeResult.Created
        assertTrue(on.decision is Decision.Done)
        assertEquals(3, exec.ran.size)

        // One step an hour or longer: the whole plan asks.
        val lasagna = MomentPlans.cook(CookParser.parse("Preheat oven to 375°F. Bake 60 minutes.").best!!, "Lasagna")
        assertEquals(ProposalStatus.PENDING, (fates.propose(lasagna.steps, lasagna.title, null) as ProposeResult.Created).proposal.status)
        // Laundry carries a reminder, not a short timer: it asks too.
        val laundry = MomentPlans.laundry(2700, at(19))
        assertEquals(ProposalStatus.PENDING, (fates.propose(laundry.steps, laundry.title, null) as ProposeResult.Created).proposal.status)
    }

    @Test fun aShortTimerOfAnHourValidatesAsAnOrdinaryTimer() {
        val r = fates.propose(listOf(dev.phonerecall.brain.RawStep(ProposalKind.SHORT_TIMER, mapOf("seconds" to "3600"))), null, null) as ProposeResult.Created
        assertEquals(ProposalKind.SET_TIMER, r.proposal.steps.single().kind)
        assertTrue(ProposalKind.SHORT_TIMER in ProposalKind.autoCandidates)
        assertFalse(ProposalKind.SHORT_TIMER.modelTool)
    }

    // ---- context cards ----

    private val pizza = Purchase("digiorno", ms(at(12)) - 86_400_000L)
    private fun home(now: ZonedDateTime) = MomentContext(now, odysseyOn = true, atHome = true, purchase = pizza)

    @Test fun frozenPizzaTonightAtHomeInTheEvening() {
        val c = MomentRules.pick(home(at(18, 30)), MomentsPrefs())!!
        assertEquals(MomentKind.COOKING, c.kind)
        assertEquals("Frozen pizza tonight?", c.content.title)
        assertEquals("425°F · 16–20 min", c.content.lead)
        assertEquals(listOf(ActionRole.MOMENT_GO, ActionRole.MOMENT_SNAP, ActionRole.MOMENT_LATER, ActionRole.MOMENT_NEVER), c.content.actions.map { it.role })
        assertTrue(c.plans["go"]!!.title.endsWith("(a guess)"))
        // Not at 4 PM, not away, not four days later.
        assertNull(MomentRules.pick(home(at(16)), MomentsPrefs()))
        assertNull(MomentRules.pick(home(at(18)).copy(atHome = false), MomentsPrefs()))
        assertNull(MomentRules.pick(home(at(18)).copy(purchase = pizza.copy(at = ms(at(18)) - 4 * 86_400_000L)), MomentsPrefs()))
    }

    @Test fun parkingMeterAfterADriveWithOneAndTwoHours() {
        val now = at(13, 10)
        val c = MomentRules.pick(MomentContext(now, odysseyOn = true, parked = Parked(ms(now) - 5 * 60_000L, "Maple St")), MomentsPrefs())!!
        assertEquals(MomentKind.PARKING, c.kind)
        assertEquals(listOf(3300), secs(c.plans["60"]!!))
        assertEquals(ProposalKind.SHORT_TIMER, c.plans["60"]!!.steps[0].kind)
        assertEquals(ProposalKind.SET_TIMER, c.plans["120"]!!.steps[0].kind)
        // Stale after 20 minutes; never with Odyssey off.
        assertNull(MomentRules.pick(MomentContext(now, odysseyOn = true, parked = Parked(ms(now) - 25 * 60_000L, null)), MomentsPrefs()))
        assertNull(MomentRules.pick(MomentContext(now, odysseyOn = false, parked = Parked(ms(now), null)), MomentsPrefs()))
    }

    @Test fun leavingHomeBundlesLightsAndRain() {
        val now = at(8, 0)
        val ctx = MomentContext(now, odysseyOn = true, atHome = true, departure = at(8, 20), lightsOn = listOf(LightOn("l1", "Kitchen"), LightOn("l2", "Porch")), rainLater = "Rain from 4 PM")
        val c = MomentRules.pick(ctx, MomentsPrefs())!!
        assertEquals(MomentKind.LEAVING, c.kind)
        assertEquals("Heading out? 2 lights on", c.content.title)
        assertEquals(listOf("Lights", "Weather", "You leave"), c.content.facts.map { it.label })
        val plan = c.plans["go"]!!
        assertEquals(listOf(ProposalKind.HOME_CONTROL, ProposalKind.HOME_CONTROL), plan.steps.map { it.kind })
        assertEquals("off", plan.steps[0].args["action"])
        // Nothing to say: no card. An hour before: no card.
        assertNull(MomentRules.pick(ctx.copy(lightsOn = emptyList(), rainLater = null), MomentsPrefs()))
        assertNull(MomentRules.pick(ctx.copy(now = at(7, 0)), MomentsPrefs()))
        // A drive that just left home still gets it, even while driving.
        assertNotNull(MomentRules.pick(ctx.copy(atHome = false, driving = true, leftHome = true, departure = null), MomentsPrefs()))
    }

    @Test fun batteryLowBeforeDeparture() {
        val ctx = MomentContext(at(7, 40), odysseyOn = true, atHome = true, departure = at(8, 20), battery = 18)
        assertEquals("Charge now: 18%", MomentRules.pick(ctx, MomentsPrefs())!!.content.title)
        assertNull(MomentRules.pick(ctx.copy(charging = true), MomentsPrefs()))
        assertNull(MomentRules.pick(ctx.copy(battery = 40), MomentsPrefs()))
        assertNull(MomentRules.pick(ctx.copy(now = at(6, 30)), MomentsPrefs()))
    }

    // ---- quiet rules ----

    @Test fun oneCardMostUrgentFirstAndNoneWhilePending() {
        val now = at(18, 30)
        val both = home(now).copy(parked = Parked(ms(now) - 60_000L, null))
        assertEquals(MomentKind.PARKING, MomentRules.pick(both, MomentsPrefs())!!.kind)
        assertNull(MomentRules.pick(both.copy(pending = true), MomentsPrefs()))
    }

    @Test fun quietHoursAndDriving() {
        assertNull(MomentRules.pick(home(at(23, 30)).copy(), MomentsPrefs()))
        // 8:30 PM is still open; driving hides cooking but not leaving.
        assertNotNull(MomentRules.pick(home(at(20, 30)), MomentsPrefs()))
        assertFalse(MomentRules.open(MomentKind.COOKING, MomentsPrefs(), at(19), driving = true))
        assertTrue(MomentRules.open(MomentKind.LEAVING, MomentsPrefs(), at(19), driving = true))
        assertFalse(MomentRules.open(MomentKind.LEAVING, MomentsPrefs(), at(6, 30), driving = true))
    }

    @Test fun notNowSnoozesForTheDayNeverTurnsOffAndThreeMissesRestAWeek() {
        val now = at(18, 30)
        val day = now.toLocalDate().toEpochDay()
        val c = MomentRules.pick(home(now), MomentsPrefs())!!
        var p = MomentsPrefs().later(c.kind, c.key, day)
        assertNull(MomentRules.pick(home(now), p))
        // The next day, a new purchase comes back.
        val tomorrow = now.plusDays(1)
        val fresh = home(tomorrow).copy(purchase = Purchase("frozen pizza", ms(tomorrow) - 3_600_000L))
        assertNotNull(MomentRules.pick(fresh, p))
        // Third "Not now" in a row: a week of rest.
        p = p.later(MomentKind.COOKING, "b", day + 1).later(MomentKind.COOKING, "c", day + 2)
        assertEquals(day + 9, p.restUntil[MomentKind.COOKING])
        assertNull(MomentRules.pick(home(now.plusDays(5)).copy(purchase = Purchase("frozen pizza", ms(now.plusDays(5)) - 60_000L)), p))
        // Taking one resets the count.
        assertNull(MomentsPrefs().later(MomentKind.COOKING, "a", day).took(MomentKind.COOKING, "a").misses[MomentKind.COOKING])
        // Never: off until Themis turns it back on.
        val never = MomentsPrefs().off(MomentKind.COOKING)
        assertNull(MomentRules.pick(home(now), never))
        assertNotNull(MomentRules.pick(home(now), never.on(MomentKind.COOKING)))
        // A moment he took does not come back.
        assertNull(MomentRules.pick(home(now), MomentsPrefs().took(c.kind, c.key)))
    }

    @Test fun receiptsAndRain() {
        assertEquals("digiorno", Purchases.item("Your Instacart order was delivered: DiGiorno Rising Crust, milk, bananas"))
        assertNull(Purchases.item("DiGiorno pizza is 2 for \$10 this week"))
        assertNull(Purchases.item("Your order was delivered: milk, bananas"))
        val now = ms(at(8))
        assertEquals("Rain from 4 PM", Signals.rain("Rain from 4 PM.", ms(at(16)), 20, now))
        assertEquals("Rain chance 70% today", Signals.rain("Clearing by noon", ms(at(12)), 70, now))
        assertNull(Signals.rain("Clearing by noon", ms(at(12)), 10, now))
    }
}
