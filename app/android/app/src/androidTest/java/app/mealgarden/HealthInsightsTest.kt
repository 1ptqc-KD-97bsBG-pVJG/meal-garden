package app.mealgarden

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthInsightsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun entry(id: String, category: String = "meal", nutrition: String = "{}", status: String = "interpreted", items: String = "[]") =
        JSONObject("""{"id":"$id","capturedAt":"2026-10-03T12:00:00-07:00","synced":true,"server":{"status":"$status","interpretation":{"category":"$category","nutrition":$nutrition,"items":$items}}}""")

    @Test fun eatenFoodTotalsExcludeLabelsStaleReadingsAndUnknownValues() {
        val meal = entry("meal", nutrition = """{"calories":{"low":500,"high":700},"fiber_g":{"low":0.3,"high":1.7}}""")
        val label = entry("label", "nutrition_label", """{"calories":{"low":2000,"high":2000}}""")
        val pending = entry("pending", nutrition = """{"calories":{"low":1000,"high":1500}}""", status = "interpreting")
        val unknown = entry("unknown")
        val result = dayHealth(listOf(meal, label, pending, unknown))
        assertEquals(2, result.consumed.size)
        assertEquals(1, result.unresolved)
        assertEquals(NutritionRange(500.0,700.0), result.sum("calories"))
        assertEquals(NutritionRange(0.3,1.7), result.sum("fiber_g"))
        assertNull(result.sum("sodium_mg"))
        assertFalse(result.complete("calories"))
    }
    @Test fun foodGroupsUseIngredientsAndConfidenceRatherThanRecipeTitles() {
        val candidate = JSONObject("""{"readiness":"candidate","ingredients":[{"name":"Broccoli","amount":2}]}""")
        assertTrue(recipeEvidence(candidate).isEmpty())
        val recipe = JSONObject("""{"readiness":"ready","title":"Healthy whole grain protein bowl","ingredients":[{"name":"White rice","amount":2},{"name":"Soy sauce","amount":2}]}""")
        assertTrue(recipeEvidence(recipe).isEmpty())
        assertFalse(groupEvidence(listOf("Milk or unsweetened soy milk")).containsKey("plant_protein"))
        assertFalse(groupEvidence(listOf("Chicken or tofu")).containsKey("plant_protein"))
        assertTrue(groupEvidence(listOf("Broccoli or cabbage")).containsKey("vegetables"))
        val log = entry("meal", items = """[{"name":"broccoli","confidence":"low"},{"name":"tofu","confidence":"high"}]""")
        assertFalse(dayHealth(listOf(log)).evidence.containsKey("vegetables"))
        assertTrue(dayHealth(listOf(log)).evidence.containsKey("plant_protein"))
    }
    @Test fun foodDayRespectsOffsetBoundaryAndInvalidTime() {
        foodDayStartHour = 4
        householdZone = java.time.ZoneId.of("America/Vancouver")
        assertEquals(java.time.LocalDate.parse("2026-10-02"), localDay("2026-10-03T10:59:00Z"))
        assertEquals(java.time.LocalDate.parse("2026-10-03"), localDay("2026-10-03T11:00:00Z"))
        assertEquals(java.time.LocalDate.MIN, localDay("not a timestamp"))
    }
    @Test fun generalReferencesAreNotCalorieOrProteinTargets() {
        assertNull(healthNutrients.first { it.key == "calories" }.reference)
        assertNull(healthNutrients.first { it.key == "protein_g" }.reference)
        assertEquals(2300.0,healthNutrients.first { it.key == "sodium_mg" }.reference!!,0.0)
        val result=dayHealth(listOf(entry("meal",nutrition="""{"sodium_mg":{"low":2500,"high":3000}}""")))
        assertEquals("Sodium above reference",healthDayHeadline(result,JSONObject()))
        assertTrue(healthNextStep(result,JSONObject()).second.contains("Keep eating regular meals"))
    }
    @Test fun populatedDayShowsRangesAndMissingNutrientCoverage() {
        val app = compose.activity.application as Application
        val file = java.io.File(app.filesDir, "snapshot.json")
        val before = if (file.exists()) file.readText() else null
        // The preceding offset-boundary test changes householdZone; this fixture owns its clock context.
        val fixtureDay = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusHours(4).toLocalDate()
        val now = fixtureDay.atTime(12, 0).atOffset(java.time.ZoneOffset.UTC).toString()
        fun remote(entry: JSONObject) = entry.o("server").put("id",entry.s("id")).put("kind","meal").put("capturedAt",now)
        val first = entry("one",nutrition="""{"calories":{"low":500,"high":650},"protein_g":{"low":25,"high":35},"fiber_g":{"low":8,"high":12},"sodium_mg":{"low":600,"high":1100}}""",items="""[{"name":"Broccoli and tofu","confidence":"high"}]""")
        val second = entry("two",nutrition="""{"calories":{"low":500,"high":700},"protein_g":{"low":35,"high":45},"fiber_g":{"low":14,"high":18}}""",items="""[{"name":"Rolled oats and berries","confidence":"high"}]""")
        val label = entry("label","nutrition_label","""{"calories":{"low":2000,"high":2000}}""")
        try {
            val snapshot = JSONObject().put("household", j("id" to "health-fixture", "timezone" to "UTC"))
                .put("settings", j("dayStartHour" to 4)).put("recipes", JSONArray())
                .put("captures", JSONArray(listOf(remote(first), remote(second), remote(label))))
            file.writeText(snapshot.toString())
            val vm = GardenModel(app); vm.disconnect()
            compose.activity.setContentForHealthTest(vm)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("2 food entries"))
            compose.onNodeWithText("2 food entries").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Energy",substring=false))
            compose.onNodeWithText("1000–1350 kcal").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sodium",substring=false))
            compose.onNodeWithText("600–1100 mg").assertIsDisplayed()
            compose.onNodeWithText("Values for 1 of 2 read food entries").assertIsDisplayed()
            compose.onNodeWithText("Total may exceed reference; check missing values").assertIsDisplayed()
        } finally { if (before == null) file.delete() else file.writeText(before) }
    }
    @Test fun healthScreenShowsCoverageGoalsAndExplanation() {
        val vm = GardenModel(compose.activity.application as Application)
        vm.disconnect()

        compose.activity.setContentForHealthTest(vm)
        compose.onNodeWithText("Health insights").assertIsDisplayed()
        compose.onNodeWithText("Waiting for food details").assertIsDisplayed()
        compose.onNodeWithText("2 · Steadier energy").assertIsDisplayed()
        compose.onNodeWithText("Edit goals").performClick()
        compose.onNodeWithText("Your health goals").assertIsDisplayed()
        compose.onNodeWithText("Add Weight loss").performScrollTo().performClick()
        compose.onNodeWithText("3 · Weight loss").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("How insights work").performClick()
        compose.onNodeWithText("How insights work").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sodium"))
        compose.onNodeWithText("FDA daily reference limit: 2300 mg").assertIsDisplayed()
    }
    @Test fun generalAndPersonalTargetsHaveDistinctVisibleLabels() {
        val fiber = healthNutrients.first { it.key == "fiber_g" }
        assertEquals("FDA daily reference: 28 g", healthTargetLabel(fiber, null))
        assertEquals("Your target: 35 g", healthTargetLabel(fiber, 35.0))
        assertEquals("Protein target not set", healthTargetLabel(healthNutrients.first { it.key == "protein_g" }, null))
        val app = compose.activity.application
        val vm = GardenModel(app)
        vm.disconnect()
        val time = java.time.ZonedDateTime.now(householdZone).toOffsetDateTime().toString()
        val read = j("id" to "read", "capturedAt" to time, "status" to "interpreted",
            "interpretation" to j("category" to "meal", "nutrition" to j("calories" to j("low" to 100, "high" to 130))))
        val pending = j("id" to "pending", "capturedAt" to time, "status" to "interpreting")
        vm.snapshot.put("captures", JSONArray().put(read).put(pending))
        compose.activity.setContentForHealthTest(vm)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Energy", substring = false))
        compose.onNodeWithText("Values for 1 of 1 read food entries · 1 awaiting details").assertIsDisplayed()
    }

    @Test fun nextMealRecipeOpensItsDetailWhenHealthWasOpenedFromFoodLog() {
        val title = "Aurora supper"
        val recipe = j("id" to "aurora-supper", "revision" to 1, "title" to title, "readiness" to "ready",
            "yield" to j("servings" to 4), "active_minutes" to 5,
            "ingredients" to JSONArray().put(j("id" to "grain", "name" to "Millet", "amount" to 200, "unit" to "g")),
            "steps" to JSONArray().put(j("id" to "mix", "title" to "Mix", "text" to "Stir the cooked grain and serve.", "minutes" to 5)))
        compose.runOnUiThread {
            assertTrue("Use an unpaired test emulator", Vault(compose.activity.application).token.isEmpty())
            val vm = GardenModel(compose.activity.application)
            val time = java.time.ZonedDateTime.now(householdZone).toOffsetDateTime().toString()
            vm.snapshot.put("recipes", JSONArray().put(recipe)).put("settings", j("dayStartHour" to 4))
                .put("captures", JSONArray().put(j("id" to "aurora-lunch", "kind" to "meal", "capturedAt" to time, "status" to "interpreted",
                    "interpretation" to j("title" to "Aurora lunch", "category" to "meal"))))
            vm.openFoodLog = true
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.onNodeWithTag("food-log-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open health insights").performClick()
        compose.onNodeWithText("Health insights").assertIsDisplayed()
        compose.onNodeWithTag("health-next-recipe").performScrollTo().performClick()
        compose.onNodeWithTag("recipe-scroll").assertIsDisplayed()
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("recipe-scroll"))).assertIsDisplayed()
        compose.onNodeWithTag("food-log-list").assertDoesNotExist()
        compose.onNodeWithText("Health insights").assertDoesNotExist()
    }

}
private fun MainActivity.setContentForHealthTest(vm: GardenModel) {
    this.setContent { MaterialTheme { HealthScreen(vm) } }
}
