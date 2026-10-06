package app.mealgarden

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecipePresentationTest {
    private fun recipe(vararg steps: JSONObject) = JSONObject().put("portions", 4).put("steps", JSONArray(steps.toList()))

    @Test fun waitingUsesNaturalUnitsAndHandsOnTimeRatherThanElapsedMinutes() {
        val overnight = recipe(
            JSONObject().put("minutes", 8).put("start_minute", 0).put("text", "Mix."),
            JSONObject().put("minutes", 722).put("passive_minutes", 720).put("start_minute", 8).put("text", "Chill overnight."))
        assertEquals("10 min + overnight", recipeDuration(overnight))
        val resting = recipe(JSONObject().put("minutes", 145).put("passive_minutes", 120).put("text", "Let it rest."))
        assertEquals("25 min + 2 h rest", recipeDuration(resting))
        val later = recipe(
            JSONObject().put("minutes", 10).put("start_minute", 0).put("text", "Mix."),
            JSONObject().put("minutes", 60).put("passive_minutes", 60).put("start_minute", 1440).put("text", "Chill."))
        assertEquals("10 min + 1 day chill", recipeDuration(later))
        assertEquals("2 days 1 h", naturalDuration(2940.0))
        assertEquals("45 min", recipeDuration(JSONObject().put("total_minutes", 45)))
        val concurrent = recipe(
            JSONObject().put("minutes", 65).put("passive_minutes", 60).put("start_minute", 0).put("text", "Chill."),
            JSONObject().put("minutes", 61).put("passive_minutes", 60).put("start_minute", 5).put("text", "Chill."))
        assertEquals("6 min + 1 h chill", recipeDuration(concurrent))
        val extraWait = recipe(
            JSONObject().put("minutes", 488).put("passive_minutes", 480).put("start_minute", 0).put("text", "Chill overnight."),
            JSONObject().put("minutes", 122).put("passive_minutes", 120).put("start_minute", 488).put("text", "Let it rest."))
        assertEquals("10 min + 10 h wait", recipeDuration(extraWait))
        val multiDay = recipe(JSONObject().put("minutes", 2885).put("passive_minutes", 2880).put("text", "Chill overnight, then keep chilled."))
        assertEquals("5 min + 2 days chill", recipeDuration(multiDay))
        val partial = recipe(JSONObject().put("minutes", 8), JSONObject().put("text", "Cook until soft."))
        assertEquals("Time incomplete", recipeDuration(partial))
        assertEquals("2 h", recipeDuration(partial.put("total_minutes", 120)))
    }

    @Test fun portionDivisionsConsumeTheWholeBatchWithoutScalingHeatOrTimer() {
        val base = recipe()
        val step = JSONObject().put("text", "Divide among four containers, putting one quarter of the mixture in each. Bake at 200°C for 12 minutes.")
            .put("minutes", 12).put("timer_minutes", 12)
        val scaled = scaledCookStep(base, step, 2)
        assertEquals("Divide among 2 containers, putting half of the mixture in each. Bake at 200°C for 12 minutes.", scaled.s("text"))
        assertEquals(12, scaled.optInt("minutes"))
        assertEquals(12, scaled.optInt("timer_minutes"))
        assertEquals("Use half of the onion.", portionDivisionText("Use half of the onion.", 2))
        assertEquals("Fill 2 containers, putting half of the batch in each. Keep one quarter of the topping aside.",
            portionDivisionText("Fill 2 containers, putting one quarter of the batch in each. Keep one quarter of the topping aside.", 2, 4))
        assertEquals("Fill 2 containers, putting half of the batch in each. Keep half of the topping aside.",
            portionDivisionText("Fill 2 containers, putting one quarter of the batch in each. Keep half of the topping aside.", 2, 4))
        assertEquals("Fill 5 jars, putting 1/5 of the batch in each.",
            portionDivisionText("Fill 5 jars, putting ⅓ of the batch in each.", 5, 3))
        assertEquals("Fill 3 jars, with one third of the mixture in each.", portionDivisionText("Fill 3 jars, with one quarter of the mixture in each.", 3))
    }

    @Test fun implicitContainerDistributionScalesOnlyTheServingClause() {
        val base = recipe()
        val step = JSONObject().put("text", "Split the prepared filling between four bowls and spoon one quarter of the sauce over the filling. Heat at 190°C for 11 minutes.")
            .put("minutes", 11).put("timer_minutes", 11).put("equipment", "Oven")
        val original = step.toString()
        val scaled = scaledCookStep(base, step, 2)
        assertEquals("Split the prepared filling between 2 bowls and spoon half of the sauce over the filling. Heat at 190°C for 11 minutes.", scaled.s("text"))
        assertEquals(11, scaled.optInt("minutes"))
        assertEquals(11, scaled.optInt("timer_minutes"))
        assertEquals("Oven", scaled.s("equipment"))
        assertEquals(original, step.toString())
        assertEquals("Fill 2 containers, putting half of the batch in each, and reserve one quarter of the topping.",
            portionDivisionText("Fill 2 containers, putting one quarter of the batch in each, and reserve one quarter of the topping.", 2, 4))
        assertEquals("Fill 2 containers, putting half of the batch in each, but put one quarter of the topping aside.",
            portionDivisionText("Fill 2 containers, putting one quarter of the batch in each, but put one quarter of the topping aside.", 2, 4))
        assertEquals("Set 2 bowls beside the oven and save one quarter of the batch for later.",
            portionDivisionText("Set 2 bowls beside the oven and save one quarter of the batch for later.", 2, 4))
        assertEquals("Split the filling between 2 bowls and add one third of the sauce.",
            portionDivisionText("Split the filling between 2 bowls and add one third of the sauce.", 2, 4))
    }

    @Test fun substitutionPresentationChangesOnlyWholeIngredientNamesAndNeverTimingOrCanonicalRecords() {
        val base = recipe().put("ingredients", JSONArray().put(JSONObject().put("name", "Pear")))
        val step = JSONObject().put("title", "Warm pear").put("text", "Warm the pear. Keep the pearl barley aside.")
            .put("minutes", 9).put("passive_minutes", 7).put("timer_minutes", 7)
        val original = step.toString()
        val changed = presentedCookStep(base, step, 4, JSONObject().put("0", "Peach"))
        assertEquals("Warm the Peach. Keep the pearl barley aside.", changed.s("text"))
        assertEquals("Warm Peach", changed.s("title"))
        assertEquals(7, changed.optInt("timer_minutes"))
        assertEquals(7, changed.optInt("passive_minutes"))
        assertEquals(original, step.toString())
    }

    @Test fun recipePicturesFollowIngredientsAndDoNotChangeWhenRecipeIdChanges() {
        fun dish(id: String, vararg names: String) = JSONObject().put("id", id)
            .put("ingredients", JSONArray(names.map { JSONObject().put("name", it) }))
        val grain = recipePlateIconIds(dish("cloud", "Quinoa", "Carrots", "Salt", "Olive oil"))
        assertEquals(listOf("food-quinoa", "food-carrot"), grain)
        assertEquals(grain, recipePlateIconIds(dish("renamed", "Quinoa", "Carrots", "Salt", "Olive oil")))
        assertNotEquals(grain, recipePlateIconIds(dish("dawn", "Yogurt", "Banana", "Oats")))
    }

    @Test fun sharedMatcherNormalizesAndFallsBackToFoodCategories() {
        for ((name, icon) in listOf(
            "Cloud pear snack" to "food-fruit", "Sunrise toast triangles" to "food-grain",
            "Silver quinoa bake" to "food-quinoa", "Blue banana pudding" to "food-banana",
            "Acorn snack pouch" to "food-nut-seed", "Golden smoothie" to "food-drink",
            "Fennel moon soup" to "food-vegetable", "Amber seitan strips" to "food-protein",
            "Cloud kefir pot" to "food-dairy", "Twilight cookie" to "food-sweet",
            "Pearlescent milkweed" to "food-unknown", "Orbital mystery" to "food-unknown",
            "Unknown pear" to "food-fruit", "Unknown tea" to "food-drink", "Unknown snack" to "food-nut-seed",
            "BABY\nSPINACH" to "food-spinach", "Cárrot cloud" to "food-carrot",
            "fresh green-onions" to "food-green-onion", "Lemon juice" to "food-drink")) assertEquals(name, icon, foodIconId(name))
    }
    @Test fun shortPackagedLabelsKeepLiteralFoodPhrasesAndRefuseDishesOrBrandOnlyMatches() {
        assertEquals("Extra-Firm Tofu", foodShortLabel("Cloud Company Organic Extra-Firm Tofu Package"))
        assertEquals("Rolled Oats", foodShortLabel("Moon Company Family Size Rolled Oats"))
        assertNull(foodShortLabel("Cloud Company Tofu Curry With Vegetables"))
        assertNull(foodShortLabel("Silver Company Family Size Fruit Snack"))
        assertNull(foodShortLabel("Cloud Company Signature Mystery Package"))
        assertNull(foodShortLabel("Cloud Company Traditional Italian Sauce Package"))
    }

    @Test fun declaredMealTypesOverrideDishWordsAndContradictoryTags() {
        assertEquals(1, recipeMealGroup(JSONObject().put("meal_type", "dinner").put("title", "Savory pudding").put("tags", JSONArray().put("dessert"))))
        assertEquals(4, recipeMealGroup(JSONObject().put("meal_types", JSONArray().put("snack")).put("title", "Oats morsels").put("tags", JSONArray().put("breakfast"))))
        assertEquals(5, recipeMealGroup(JSONObject().put("meal_type", "dessert").put("title", "Savory squares").put("tags", JSONArray().put("dinner"))))
        assertEquals(7, recipeMealGroup(JSONObject().put("meal_type", "unlisted-type").put("title", "Breakfast oats")))
        assertEquals(3, recipeMealGroup(JSONObject().put("meal_type", "side")))
        assertEquals(6, recipeMealGroup(JSONObject().put("meal_type", "drink")))
    }

}
