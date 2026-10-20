package dev.phonerecall.closet

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.ClosetGroup
import dev.phonerecall.data.ClosetItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.DayType
import dev.phonerecall.data.EventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OutfitsTest {
    private fun item(id: String, group: ClosetGroup, category: String, kind: String, colour: String, form: Int, occ: String, shade: String = "mid", temp: String = "neutral", desc: String = "") =
        ClosetItem(id, group, category, kind, "", colour, desc, occ.split(',').toSet(), form, temp, shade, "", false, "", emptyList())

    private val all = "home,hosting,office,outing,party,date"
    private val closet = ClosetSnapshot(
        imported = true, loaded = true,
        items = listOf(
            item("navy-polo", ClosetGroup.TOPS, "top", "Polo", "Navy", 2, "date,office,outing,party", "dark", "cool"),
            item("cream-polo", ClosetGroup.TOPS, "top", "Polo", "Cream", 3, "date,office,outing,party", "light", "warm"),
            item("grey-tee", ClosetGroup.TOPS, "top", "Tee", "Heather grey", 1, "home,hosting,outing,party"),
            item("team-jersey", ClosetGroup.TOPS, "top", "Jersey", "Navy", 0, "home,hosting,outing", "dark", "cool"),
            item("grey-hoodie", ClosetGroup.TOPS, "top", "Hoodie", "Grey", 1, "home,office,outing,party"),
            item("white-shirt", ClosetGroup.TOPS, "top", "DressShirt", "White", 4, "date,impress,office,party", "light"),
            item("navy-puffer", ClosetGroup.OUTERWEAR, "top", "Puffer", "Navy", 1, "office,outing,party", "dark", "cool"),
            item("black-puffer", ClosetGroup.OUTERWEAR, "top", "Puffer", "Black", 1, "office,outing,party", "dark", desc = "lighter puffer"),
            item("trucker", ClosetGroup.OUTERWEAR, "top", "Jacket", "Mid blue denim", 2, "date,office,outing,party", "mid", "cool"),
            item("stone-chinos", ClosetGroup.BOTTOMS, "bottom", "Chinos", "Stone", 3, all, "light", "warm"),
            item("black-jeans", ClosetGroup.BOTTOMS, "bottom", "Jeans", "Black", 2, all, "dark"),
            item("navy-shorts", ClosetGroup.BOTTOMS, "shorts", "Shorts", "Navy", 0, "home,hosting,outing", "dark", "cool"),
            item("white-sneaker", ClosetGroup.SHOES, "footwear", "Sneaker", "White", 1, all, "light"),
            item("tan-derby", ClosetGroup.SHOES, "footwear", "Derby", "Tan brown", 4, "date,impress,office,party", "mid", "warm"),
            item("winter-boot", ClosetGroup.SHOES, "footwear", "Boot", "Black", 1, "office,outing,party", "dark"),
            item("olive-cap", ClosetGroup.ACCESSORIES, "accessory", "Cap", "Faded olive", 1, "home,hosting,outing"),
        ),
    )
    // A Monday in July and one in October (no festival on either).
    private val julyMon = LocalDate.of(2026, 7, 13)
    private val octMon = LocalDate.of(2026, 10, 12)

    private fun plan(date: LocalDate, type: DayType, events: List<String> = emptyList(), w: DayWeather? = null, prefs: VenusPrefs = VenusPrefs(), festival: String? = null) =
        Outfits.plan(closet, DayContext(date, type, events, festival, w), prefs)!!

    @Test fun sameInputsSameOutfit() {
        assertEquals(plan(octMon, DayType.WORKDAY).main, plan(octMon, DayType.WORKDAY).main)
    }

    @Test fun officeDayIsSmartCasualNeverAJerseyOrShorts() {
        for (d in 0L..20L) {
            val p = plan(octMon.plusDays(d), DayType.WORKDAY, w = DayWeather(feelsLikeC = 26.0))
            assertEquals(Occasion.OFFICE, p.occasion)
            assertNotEquals("team-jersey", p.main.top?.id)
            assertNotEquals("shorts", p.main.bottom?.category)
            assertTrue("office tag", "office" in p.main.top!!.occasions)
        }
    }

    @Test fun hotWeekendAllowsShortsAndNoLayer() {
        val p = plan(julyMon.plusDays(5), DayType.WEEKEND, w = DayWeather(highC = 31.0, lowC = 22.0))
        assertEquals(Warmth.HOT, p.warmth)
        assertNull(p.main.layer)
        assertFalse("no hoodie in the heat", p.main.top!!.kind == "Hoodie")
        assertEquals("shorts", p.main.bottom?.category)
    }

    @Test fun weatherBeatsTheMonth() {
        // A warm October day: no layer; a freezing one: puffer and the boot.
        assertNull(plan(octMon, DayType.WORKDAY, w = DayWeather(feelsLikeC = 22.0)).main.layer)
        val cold = plan(octMon, DayType.WORKDAY, w = DayWeather(feelsLikeC = -9.0))
        assertEquals(Warmth.FREEZING, cold.warmth)
        assertEquals("Puffer", cold.main.layer?.kind)
        assertEquals("winter-boot", cold.main.shoes?.id)
        assertTrue(cold.main.reason, cold.main.reason.contains("Feels like -9"))
    }

    @Test fun noWeatherFallsBackToTheMonth() {
        assertEquals(Warmth.COOL, Outfits.warmth(octMon, null))
        assertEquals(Warmth.FREEZING, Outfits.warmth(LocalDate.of(2027, 1, 10), null))
        assertEquals(Warmth.COLD, Outfits.warmth(julyMon, DayWeather(feelsLikeC = 20.0, snow = true)))
    }

    @Test fun snowMeansTheBoot() {
        assertEquals("winter-boot", plan(octMon, DayType.REMOTE, w = DayWeather(feelsLikeC = 1.0, snow = true)).main.shoes?.id)
    }

    @Test fun aTopIsNotRepeatedWithinSixDays() {
        val first = plan(octMon, DayType.WORKDAY).main.top!!.id
        var prefs = VenusStore.wore(VenusPrefs(), listOf(first), octMon.toEpochDay())
        // Five tops can go to the office (not the jersey): four more days without a repeat.
        val seen = mutableListOf(first)
        for (d in 1L..4L) {
            val p = plan(octMon.plusDays(d), DayType.WORKDAY, prefs = prefs)
            assertTrue("day $d repeats ${p.main.top?.id}", p.main.top!!.id !in seen)
            seen += p.main.top!!.id
            prefs = VenusStore.wore(prefs, p.main.ids, octMon.plusDays(d).toEpochDay())
        }
        // All five worn this week: the one worn longest ago comes back first.
        assertEquals(first, plan(octMon.plusDays(5), DayType.WORKDAY, prefs = prefs).main.top?.id)
    }

    @Test fun woreItKeepsTodaysOutfit() {
        val p = plan(octMon, DayType.WORKDAY)
        val alt = p.alternates.first()
        val prefs = VenusStore.wore(VenusPrefs(), alt.ids, octMon.toEpochDay())
        val after = plan(octMon, DayType.WORKDAY, prefs = prefs)
        assertTrue(after.wearing)
        assertEquals(alt.top?.id, after.main.top?.id)
        assertEquals(alt.bottom?.id, after.main.bottom?.id)
    }

    @Test fun notTodaySkipsUntilTomorrow() {
        val top = plan(octMon, DayType.WORKDAY).main.top!!.id
        val prefs = VenusStore.skip(VenusPrefs(), listOf(top), octMon.toEpochDay())
        assertNotEquals(top, plan(octMon, DayType.WORKDAY, prefs = prefs).main.top?.id)
        assertTrue(prefs.skippedOn(octMon.plusDays(1).toEpochDay()).isEmpty())
    }

    @Test fun favoritesComeForward() {
        val p = plan(octMon, DayType.REMOTE)
        val other = closet.items.first { it.id == "grey-hoodie" }.id.takeIf { it != p.main.top?.id } ?: "grey-tee"
        assertEquals(other, plan(octMon, DayType.REMOTE, prefs = VenusPrefs(favorites = setOf(other))).main.top?.id)
    }

    @Test fun dinnerStepsUpToTheDerby() {
        val p = plan(octMon, DayType.REMOTE, events = listOf("Dinner with Jordan"))
        assertEquals(Occasion.DINNER, p.occasion)
        assertEquals("tan-derby", p.main.shoes?.id)
        assertTrue(p.main.top!!.formality >= 2)
    }

    @Test fun bigFestivalIsTraditionalWear() {
        val dussehra = LocalDate.of(2026, 10, 20)
        val p = Outfits.plan(closet, Outfits.context(dussehra, null, emptyList(), ZoneId.of("America/New_York"), null))!!
        assertEquals(Occasion.FESTIVAL, p.occasion)
        assertEquals("Blue festive kurta", p.main.traditional)
        assertNull(p.main.top)
        assertTrue(p.main.reason, p.main.reason.startsWith("Dussehra today"))
        assertEquals(2, p.alternates.size)
    }

    @Test fun festivalFromTheCalendarAndTemple() {
        val zone = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 3, 19)
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val ugadi = CalItem(1, 1, "Ugadi", start, start + 86_400_000L, true, EventCategory.HOLIDAY, "Holidays", null, null, false)
        assertEquals("Ugadi", Outfits.context(d, null, listOf(ugadi), zone, null).festival)
        assertEquals(Occasion.TEMPLE, plan(octMon, DayType.WEEKEND, events = listOf("Temple with Amma")).occasion)
        assertEquals("A kurta", plan(octMon, DayType.WEEKEND, events = listOf("Temple with Amma")).main.traditional)
    }

    @Test fun wearLogKeepsOneEntryPerDayAndDropsOld() {
        var p = VenusStore.wore(VenusPrefs(), listOf("a"), 100)
        p = VenusStore.wore(p, listOf("a"), 100)
        assertEquals(listOf(100L), p.worn["a"])
        p = VenusStore.wore(p, listOf("b"), 100 + VenusStore.KEEP_DAYS.toLong())
        assertNull(p.worn["a"])
        assertEquals(p.worn, VenusStore.decodeLog(VenusStore.encodeLog(p.worn)))
    }

    @Test fun polishIsCheckedBeforeUse() {
        assertEquals("Office day, so the navy polo and stone chinos.", OutfitPolish.accept("\"Office day, so the navy polo and stone chinos.\""))
        assertNull(OutfitPolish.accept("Great choice! Looking sharp today"))
        assertNull(OutfitPolish.accept("Office day — smart casual"))
        assertNull(OutfitPolish.accept(null))
    }

    // ---- packing ----

    private fun travel(title: String, from: LocalDate, days: Int, zone: ZoneId) =
        CalItem(title.hashCode().toLong(), 1, title, from.atStartOfDay(zone).toInstant().toEpochMilli(),
            from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(), true, EventCategory.TRAVEL, "Personal", null, null, false)

    @Test fun tripsComeFromTravelEvents() {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 10, 19)
        val events = listOf(
            travel("Flight to Austin", today.plusDays(3), 1, zone),
            travel("Flight home", today.plusDays(7), 1, zone),
            travel("Trip to Toronto", today.plusDays(30), 2, zone),
        )
        val trips = Packing.trips(events, today, zone)
        assertEquals(1, trips.size)
        assertEquals("Austin", trips[0].title)
        assertEquals(5, trips[0].days)
    }

    @Test fun packingListScalesWithDaysAndWeather() {
        val trip = Trip(octMon, octMon.plusDays(4), "Austin")
        val hot = Packing.pack(closet, trip, Warmth.HOT)
        val tops = hot.lines.first { it.label == "Tops" }
        assertEquals(tops.items.size, tops.count)
        assertTrue(tops.count in 3..6)
        assertTrue(tops.items.none { it.kind == "Hoodie" })
        assertEquals(6, hot.lines.first { it.label == "Socks and underwear" }.count)
        assertTrue(hot.lines.none { it.label == "Layer" })
        val cold = Packing.pack(closet, trip, Warmth.FREEZING)
        assertEquals("Puffer", cold.lines.first { it.label == "Layer" }.items.single().kind)
        assertTrue(cold.lines.first { it.label == "Shoes" }.items.any { it.id == "winter-boot" })
        assertNotNull(cold.lines.firstOrNull { it.label == "Hat, gloves, scarf" })
    }

    @Test fun laundryAndRarelyWorn() {
        val day = octMon.toEpochDay()
        val prefs = VenusStore.wore(VenusStore.wore(VenusPrefs(), listOf("navy-polo", "black-jeans"), day - 20), listOf("cream-polo"), day - 2)
        val l = Insights.laundry(closet, prefs, octMon)
        assertEquals(listOf("cream-polo"), l.wornThisWeek.map { it.id })
        val rare = Insights.rarelyWorn(closet, prefs, octMon, days = 10)
        assertTrue("navy-polo" in rare.map { it.id })
        assertFalse("cream-polo" in rare.map { it.id })
        assertTrue(Insights.rarelyWorn(closet, VenusPrefs(), octMon).isEmpty())
    }
}
