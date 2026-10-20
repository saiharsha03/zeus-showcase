package dev.phonerecall.moments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The box parser on real-world instruction text, written the way OCR returns it (line breaks mid-sentence). */
class CookParseTest {
    private fun best(text: String) = CookParser.parse(text).best!!
    private fun min(m: Int) = m * 60

    @Test fun digiornoOvenWithStandAndInternalTempIgnored() {
        val r = CookParser.parse(
            """
            COOKING INSTRUCTIONS
            Keep frozen. Cook thoroughly.
            Preheat oven to 400°F. Place pizza on center oven rack.
            Bake 22-25 minutes. Pizza must be cooked to 160°F.
            Do not use a baking sheet. Let stand 5 minutes.
            DiGiorno Rising Crust Pizza
            """.trimIndent(),
        )
        val v = r.best!!
        assertEquals(Method.OVEN, v.method)
        assertEquals(Temp(400, false), v.temp)
        assertEquals(Span(min(22), min(25)), v.cook)
        assertEquals(min(5), v.stand)
        assertTrue(v.preheat)
        assertEquals("Pizza", r.name)
    }

    @Test fun redBaronOvenOnlyMicrowaveNotRecommended() {
        val r = CookParser.parse(
            """
            CONVENTIONAL OVEN
            Preheat oven to 400°F.
            Remove pizza from overwrap and cardboard.
            Bake on middle rack for 16 to 18
            minutes or until cheese is melted and edges are golden brown.
            Microwave not recommended.
            """.trimIndent(),
        )
        assertEquals(1, r.variants.size)
        assertEquals(Span(min(16), min(18)), r.best!!.cook)
        assertEquals(400, r.best!!.temp!!.degrees)
    }

    @Test fun stouffersLasagnaPicksOvenAndKeepsMicrowave() {
        val r = CookParser.parse(
            """
            MICROWAVE (1100 WATTS)
            Cut film to vent. Cook on HIGH 10 minutes. Peel back film, stir sauce gently. Re-cover.
            Cook on HIGH 5-6 minutes more. Let stand 2 minutes.
            CONVENTIONAL OVEN
            Preheat oven to 375°F. Leave film on tray, do not vent. Bake 60 minutes.
            Remove film. Bake an additional 5 minutes. Let stand 5 minutes.
            STOUFFER'S Lasagna with Meat & Sauce
            """.trimIndent(),
        )
        assertEquals(setOf(Method.MICROWAVE, Method.OVEN), r.variants.map { it.method }.toSet())
        val oven = r.best!!
        assertEquals(Method.OVEN, oven.method)
        assertEquals(Temp(375, false), oven.temp)
        assertEquals(Span(min(65)), oven.cook)
        assertEquals(Turn(min(60), "Remove film", phrase = true), oven.turn)
        assertEquals(min(5), oven.stand)
        val mw = r.variants.first { it.method == Method.MICROWAVE }
        assertEquals(Span(min(15), min(16)), mw.cook)
        assertEquals(Turn(min(10), "Stir"), mw.turn)
        assertEquals(min(2), mw.stand)
        assertFalse(mw.preheat)
        assertEquals("Lasagna", r.name)
    }

    @Test fun bettyCrockerBrowniesPanSizesAreAlternatives() {
        val v = best(
            """
            Heat oven to 350°F (325°F for dark or nonstick pan). Grease bottom of pan.
            Stir brownie mix, water, oil and eggs in medium bowl until well blended. Spread in pan.
            Bake 13x9 pan 24-26 minutes, 9x9 pan 37-39 minutes, 8x8 pan 41-43 minutes, or until
            toothpick inserted 2 inches from side comes out almost clean. Cool completely, about 1 hour.
            """.trimIndent(),
        )
        assertEquals(Temp(350, false), v.temp)
        assertEquals(Span(min(24), min(26)), v.cook)
        assertEquals(listOf(Span(min(37), min(39)), Span(min(41), min(43))), v.others)
        assertEquals(min(60), v.stand)
        assertTrue(v.preheat)
        assertNull(v.turn)
    }

    @Test fun anniesStovetopIgnoresStirringOccasionally() {
        val r = CookParser.parse(
            """
            STOVETOP DIRECTIONS
            1. Boil 6 cups of water. Stir in pasta; cook 6-8 minutes, stirring occasionally. Drain.
            2. Add butter, milk and cheese mix; stir until smooth.
            Annie's Shells & White Cheddar
            """.trimIndent(),
        )
        val v = r.best!!
        assertEquals(Method.STOVETOP, v.method)
        assertEquals(Span(min(6), min(8)), v.cook)
        assertNull(v.turn)
        assertFalse(v.preheat)
        assertEquals("Mac and cheese", r.name)
    }

    @Test fun oreIdaFriesAirFryerVersusOven() {
        val r = CookParser.parse(
            """
            AIR FRYER: Preheat air fryer to 400°F. Cook 10-12 minutes, shaking basket halfway through.
            CONVENTIONAL OVEN: Preheat oven to 425°F. Spread frozen fries in a single layer on a
            baking sheet. Bake 18-22 minutes, turning once.
            """.trimIndent(),
        )
        val air = r.variants.first { it.method == Method.AIR_FRYER }
        assertEquals(Temp(400, false), air.temp)
        assertEquals(Span(min(10), min(12)), air.cook)
        assertEquals(Turn(min(5), "Shake"), air.turn)
        val oven = r.best!!
        assertEquals(Method.OVEN, oven.method)
        assertEquals(Temp(425, false), oven.temp)
        assertEquals(Span(min(18), min(22)), oven.cook)
        assertEquals(Turn(min(9), "Flip"), oven.turn)
        assertEquals("Fries", r.name)
    }

    @Test fun totinosPizzaRollsTwoVariants() {
        val r = CookParser.parse(
            """
            Conventional oven: Preheat oven to 425°F. Arrange rolls on a baking sheet. Bake 12-14 minutes.
            Microwave: Arrange 6 rolls on a plate. Microwave on HIGH 1 minute. Let stand 1 minute before eating.
            """.trimIndent(),
        )
        assertEquals(Span(min(12), min(14)), r.best!!.cook)
        val mw = r.variants.first { it.method == Method.MICROWAVE }
        assertEquals(Span(min(1)), mw.cook)
        assertEquals(min(1), mw.stand)
    }

    @Test fun riceSimmerAndRest() {
        val v = best("STOVETOP: Bring 2 cups water and 1 cup rice to a boil. Reduce heat, cover and simmer 18 minutes. Remove from heat and let stand 5 minutes. Fluff with fork.")
        assertEquals(Method.STOVETOP, v.method)
        assertEquals(Span(min(18)), v.cook)
        assertEquals(min(5), v.stand)
    }

    @Test fun celsiusUkPizzaWithFanAndGasMark() {
        val v = best("Oven cook: Preheat oven to 220°C / Fan 200°C / Gas Mark 7. Remove all packaging. Place on a baking tray in the centre of the oven for 15-18 minutes, turning halfway.")
        assertEquals(Method.OVEN, v.method)
        assertEquals(Temp(220, true), v.temp)
        assertEquals(428, v.temp!!.f)
        assertEquals(Span(min(15), min(18)), v.cook)
        assertEquals(Turn(450, "Flip"), v.turn)
    }

    @Test fun hotPocketsMicrowaveOnly() {
        val v = best("MICROWAVE (1100 watts): Remove sandwich from sleeve. Place on a microwave-safe plate. Cook on HIGH 2 minutes. Let stand 2 minutes.")
        assertEquals(Method.MICROWAVE, v.method)
        assertNull(v.temp)
        assertEquals(Span(min(2)), v.cook)
        assertEquals(min(2), v.stand)
        assertFalse(v.preheat)
    }

    @Test fun potPieWithHourAndMinutesAlternative() {
        val r = CookParser.parse("Marie Callender's Chicken Pot Pie. Preheat oven to 400°F. Place pie on a baking sheet on middle rack. Bake 55-60 minutes (or 1 hour 5 minutes in a glass dish). Let stand 5 minutes.")
        val v = r.best!!
        assertEquals(Span(min(55), min(60)), v.cook)
        assertEquals(listOf(Span(min(65))), v.others)
        assertEquals(min(5), v.stand)
        assertEquals("Pot pie", r.name)
    }

    @Test fun leanCuisineStirThenMoreWithHalfMinutes() {
        val v = best("Microwave: Cut film to vent. Cook on HIGH 2 minutes. Stir. Cook 1 to 1 1/2 minutes more. Let stand 1 minute.")
        assertEquals(Method.MICROWAVE, v.method)
        assertEquals(Span(180, 210), v.cook)
        assertEquals(Turn(min(2), "Stir"), v.turn)
        assertEquals(min(1), v.stand)
    }

    @Test fun pillsburyCookiesCoolOneMinute() {
        val r = CookParser.parse("Heat oven to 350°F. Place dough rounds 2 inches apart on ungreased cookie sheet. Bake 12 to 16 minutes or until golden brown. Cool 1 minute; remove from cookie sheet.")
        val v = r.best!!
        assertEquals(Span(min(12), min(16)), v.cook)
        assertEquals(min(1), v.stand)
        assertEquals("Cookies", r.name)
    }

    @Test fun tysonNuggetsThreeMethodsAndMinuteSeconds() {
        val r = CookParser.parse(
            """
            Oven: Preheat oven to 400°F. Arrange frozen nuggets on baking sheet. Heat 15-17 minutes, turning once halfway through.
            Microwave: Heat 4 nuggets on HIGH for 1 minute 15 seconds.
            Air Fryer: Cook at 400°F for 10-12 minutes.
            """.trimIndent(),
        )
        assertEquals(3, r.variants.size)
        assertEquals(Method.OVEN, r.best!!.method)
        assertEquals(Turn(450, "Flip"), r.best!!.turn)
        assertEquals(Span(75), r.variants.first { it.method == Method.MICROWAVE }.cook)
        val air = r.variants.first { it.method == Method.AIR_FRYER }
        assertEquals(Span(min(10), min(12)), air.cook)
        assertFalse(air.preheat)
    }

    @Test fun celsiusAirFryerShakeHalfwayAsItsOwnSentence() {
        val v = best("AIR FRYER: 200°C for 12-15 minutes. Shake halfway.")
        assertEquals(Method.AIR_FRYER, v.method)
        assertEquals(Temp(200, true), v.temp)
        assertEquals(Turn(min(6), "Shake"), v.turn)
    }

    @Test fun ingredientsOnlyFindsNothing() {
        val r = CookParser.parse("INGREDIENTS: enriched flour, water, mozzarella cheese, tomato paste. Keep frozen. Contains: wheat, milk.")
        assertFalse(r.found)
        assertNull(r.best)
    }

    @Test fun recipeStepsFromAScreen() {
        val text = """
            Ingredients
            2 tbsp olive oil, 1 onion, 400 g tomatoes
            Prep time: 10 mins  Total time: 45 mins
            1. Heat the oil and soften the onion.
            2. Add the tomatoes and simmer for 10 minutes.
            3. Bake at 400°F for 25 min.
            4. Let it rest 5 minutes before serving.
        """.trimIndent()
        val steps = Recipe.steps(text)
        assertEquals(listOf(600, 1500, 300), steps.map { it.seconds })
        assertEquals("Simmer", steps[0].label)
        assertTrue(Recipe.looksLikeRecipe(text))
        assertFalse(Recipe.looksLikeRecipe("Meeting moved to 3 PM. See you in 10 minutes."))
    }
}
