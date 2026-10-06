package app.mealgarden

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fictional recipe records on an unpaired emulator; canonical recipes stay unchanged. */
@RunWith(AndroidJUnit4::class)
class RecipeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: GardenModel

    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val permission = instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        compose.runOnUiThread {
            assertTrue("Never test writes against a paired laptop", Vault(compose.activity.application).token.isEmpty())
            compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            File(compose.activity.filesDir, "snapshot.json").delete()
            vm = GardenModel(compose.activity.application)
            assertFalse(vm.paired)
            vm.snapshot.put("captures", JSONArray()).put("cooking", JSONArray())
                .put("assumptions", JSONArray()).put("preferences", JSONArray())
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (!::vm.isInitialized) return
        compose.runOnUiThread {
            vm.timers.keys.toList().forEach { key -> cancelTimer(compose.activity, key); vm.stopTimer(key) }
            vm.discardCapture()
            vm.captures.forEach { capture -> vm.capturePhotos(capture).forEach { vm.capturePhotoFile(capture, it).delete() } }
            vm.prefs.edit().clear().commit()
            File(compose.activity.filesDir, "snapshot.json").delete()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val file = File(compose.activity.filesDir, "redesign-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun typedRecipeGroupsAndReadinessGuardsMatchExecutableRecords() {
        fun recipe(id: String, title: String, mealType: String, readiness: String = "ready", executable: Boolean = true) = j(
            "id" to id, "revision" to 1, "title" to title, "meal_type" to mealType, "readiness" to readiness,
            "ingredients" to JSONArray().put(j("id" to "beans", "name" to "Beans", "amount" to 1, "unit" to "can")),
            "steps" to if (executable) JSONArray().put(j("title" to "Warm beans", "text" to "Warm the beans over medium heat.")) else JSONArray(),
        )
        val breakfast = recipe("savory-start", "Savory start", "breakfast")
        val dinner = recipe("apple-oats-dinner", "Apple oats dinner", "dinner")
        val soup = recipe("pea-soup", "Pea soup", "soup")
        val candidate = recipe("unreviewed", "Unreviewed dish", "dinner", readiness = "candidate")
        val missingMethod = recipe("needs-method", "Needs a method", "dinner", executable = false)
        compose.runOnUiThread {
            vm.snapshot.put("recipes", JSONArray().put(dinner).put(soup).put(candidate).put(missingMethod).put(breakfast))
            compose.activity.setContent { GardenTheme { RecipesScreen(vm) } }
        }
        val breakfastRow = compose.onNodeWithText("Savory start").fetchSemanticsNode().boundsInRoot
        val dinnerRow = compose.onNodeWithText("Apple oats dinner").fetchSemanticsNode().boundsInRoot
        val soupRow = compose.onNodeWithText("Pea soup").fetchSemanticsNode().boundsInRoot
        assertTrue("Declared breakfast precedes dinner even when dinner has an oats title", breakfastRow.top < dinnerRow.top)
        assertTrue("Soups form the final labeled group", dinnerRow.top < soupRow.top)
        listOf("Breakfast", "Lunch & dinner", "Soups").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Unreviewed dish").assertDoesNotExist()
        compose.onNodeWithText("Needs a method").assertDoesNotExist()
        compose.onNodeWithText("2 more not ready to cook").performScrollTo().performClick()
        compose.onNodeWithTag("recipes-scroll").performScrollToNode(hasText("Needs a method"))
        compose.onNodeWithText("Needs a method").assertExists()
        compose.onNodeWithTag("recipes-scroll").performScrollToNode(hasText("Unreviewed dish"))
        compose.onNodeWithText("Unreviewed dish").assertExists()
        listOf(candidate, missingMethod).forEach { incomplete ->
            compose.runOnUiThread { compose.activity.setContent { GardenTheme { RecipeScreen(vm, incomplete) } } }
            compose.onNodeWithText("Not ready to cook").assertExists()
            compose.onNodeWithText("Start cooking").assertDoesNotExist()
        }
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { RecipeScreen(vm, breakfast) } } }
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Start cooking"))
        compose.onNodeWithText("Start cooking").assertIsEnabled()
    }

    @Test fun readinessRejectsMissingAmountsAndNonExecutableSteps() {
        val ready = j("id" to "beans", "readiness" to "ready",
            "ingredients" to JSONArray().put(j("id" to "beans", "name" to "Beans", "amount" to 1, "unit" to "can")),
            "steps" to JSONArray().put(j("text" to "Warm the beans.")))
        assertTrue(recipeReady(ready))
        val nullAmount = JSONObject(ready.toString()).apply { a("ingredients").getJSONObject(0).put("amount", JSONObject.NULL) }
        val blankName = JSONObject(ready.toString()).apply { a("ingredients").getJSONObject(0).put("name", "") }
        val unstructured = JSONObject(ready.toString()).apply { a("ingredients").put("Beans") }
        val noInstruction = JSONObject(ready.toString()).apply { a("steps").getJSONObject(0).put("text", " ") }
        val impossibleTime = JSONObject(ready.toString()).apply { a("steps").getJSONObject(0).put("minutes", 1).put("passive_minutes", 2) }
        listOf(nullAmount, blankName, unstructured, noInstruction, impossibleTime).forEach { assertFalse(recipeReady(it)) }
    }

    @Test fun savedVariantSwitchUsesWholeExecutableRecordsAndStopsAfterCookingStarts() {
        val parent = j("id" to "base-beans", "revision" to 1, "title" to "Base beans", "meal_type" to "dinner", "readiness" to "ready",
            "ingredients" to JSONArray().put(j("id" to "beans", "name" to "Beans", "amount" to 1, "unit" to "can")),
            "steps" to JSONArray().put(j("title" to "Warm beans", "text" to "Warm 1 can of beans over medium heat.", "minutes" to 2)).put(j("text" to "Divide the beans between bowls.")))
        val child = JSONObject(parent.toString()).apply {
            put("id", "bean-variant"); put("title", "Extra beans"); put("parent_recipe_id", parent.s("id"))
            put("variation_scope", "meal-specific"); put("variant_reason", "Use an extra can"); put("changed_by", "app")
            a("ingredients").getJSONObject(0).put("amount", 2)
            a("steps").getJSONObject(0).put("text", "Warm 2 cans of beans over medium heat.")
        }
        val originalParent = parent.toString()
        val originalChild = child.toString()
        compose.runOnUiThread {
            vm.snapshot.put("recipes", JSONArray().put(parent).put(child))
            vm.prefs.edit().putBoolean("favorite:bean-variant", true).commit()
            vm.tab = 1
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.onNodeWithText("Saved").performClick()
        compose.onNode(hasText("Extra beans") and hasClickAction()).performClick()
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasTestTag("recipe-variant:bean-variant"))
        compose.onNodeWithTag("recipe-variant:bean-variant").assertIsOn().performClick()
        compose.runOnIdle { assertEquals("base-beans", vm.selectedRecipe) }
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasTestTag("recipe-variant:bean-variant"))
        compose.onNodeWithTag("recipe-variant:bean-variant").assertIsOff().performClick()
        compose.runOnIdle { assertEquals("bean-variant", vm.selectedRecipe) }
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Start cooking"))
        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Leave cooking").performClick()
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasTestTag("recipe-variant:bean-variant"))
        compose.onNodeWithTag("recipe-variant:bean-variant").assertIsOn().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(originalParent, parent.toString())
            assertEquals(originalChild, child.toString())
            assertTrue(vm.outbox.isEmpty())
        }
        shot("phase5-whole-variant")
    }

    @Test fun passiveApplianceKeepsItsStationTimerDuringPrep() {
        val recipe = j("id" to "station-fixture", "revision" to 1, "title" to "Roast tofu", "readiness" to "ready",
            "ingredients" to JSONArray().put(j("id" to "tofu", "name" to "Tofu", "amount" to 1, "unit" to "block")),
            "steps" to JSONArray()
                .put(j("title" to "Preheat", "text" to "Preheat the oven to 200°C.", "equipment" to "Oven", "start_minute" to 0, "minutes" to 5, "passive_minutes" to 4))
                .put(j("title" to "Cube", "text" to "Cube the tofu.", "start_minute" to 1, "minutes" to 2)))
        compose.runOnUiThread {
            vm.snapshot.put("recipes", JSONArray().put(recipe))
            vm.snapshot.put("kitchen", j("available" to JSONArray().put("Oven").put("Countertop oven").put("Microwave")))
            vm.selectedRecipe = recipe.s("id")
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Start cooking"))
        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Start 4 min timer").assertIsDisplayed().performClick()
        compose.onNodeWithText("STEP 2 OF 2").assertIsDisplayed()
        compose.onNodeWithTag("cook-kitchen").performScrollTo().assertExists()
        compose.onNodeWithText("Oven · 4:00").assertExists()
        compose.onNodeWithContentDescription("Oven, in use").assertExists()
        compose.onAllNodes(hasContentDescription(", in use", substring = true)).assertCountEquals(1)
        compose.onNodeWithText("Skip the wait").assertDoesNotExist()
        compose.onNodeWithText("Done early").assertExists()
        compose.runOnIdle {
            assertTrue(vm.timers.keys.any { it.startsWith("station-fixture:") })
            assertTrue(vm.outbox.isEmpty())
        }
        shot("phase5-running-station-prep")
        compose.onNodeWithContentDescription("Leave cooking").performClick()
    }

    @Test fun yieldParsingUsesMealCountAndIgnoresNutritionFigures() {
        assertEquals(4, basePortions(j("yield_notes" to "Four small cold or warm meals; approximately 22–26 g protein per serving.")))
        assertEquals(4, basePortions(j("yield_notes" to "Approximately 26 g protein per serving.")))
        assertEquals(6, basePortions(j("servings" to 6, "yield_notes" to "Four bowls.")))
    }

    @Test fun cookSessionDayUsesConfiguredHouseholdTimezone() {
        compose.runOnUiThread { vm.snapshot.put("household", j("id" to "test", "timezone" to "Pacific/Kiritimati")) }
        assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati")).toString(), cookDay(vm))
    }
    @Test fun thumbControlsAdvanceOnceAndKitchenTimerKeepsRunningAfterLeavingCookMode() {
        val recipe = j("id" to "cloud-bowl", "revision" to 1, "title" to "Cloud bowl", "readiness" to "ready", "portions" to 4,
            "ingredients" to JSONArray().put(j("id" to "pear", "name" to "Pear", "amount" to 1, "unit" to "whole")),
            "steps" to JSONArray().put(j("text" to "Slice the pear.", "minutes" to 2))
                .put(j("text" to "Divide among four bowls, each with one quarter of the mixture.", "minutes" to 1)))
        compose.runOnUiThread {
            vm.snapshot.put("recipes", JSONArray().put(recipe))
            vm.selectedRecipe = recipe.s("id")
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.onNodeWithText("Start cooking").assertIsDisplayed().performClick()
        compose.onNodeWithTag("cook-primary").assertTextContains("Next").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Back").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("I substituted").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Kitchen timer").performClick()
        compose.onNodeWithText("1 min").performClick()
        compose.onNodeWithText("Start timer").performClick()
        compose.runOnIdle {
            assertEquals(1, vm.timers.size)
            assertTrue(vm.timers.keys.single().startsWith("kitchen:"))
            val remaining = vm.timers.values.single().optLong("deadline") - System.currentTimeMillis()
            assertTrue("Uses a real one-minute deadline", remaining in 50_000..60_500)
            assertFalse(vm.prefs.contains("cook-progress:cloud-bowl:1:0"))
        }
        compose.onNodeWithTag("cook-primary").performClick()
        compose.onNodeWithText("STEP 2 OF 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Leave cooking").performClick()
        compose.runOnIdle { assertEquals(1, vm.timers.size) }
    }

    @Test fun visibleDestinationPlusPlacesPortionsAndEnforcesTotal() {
        val counts = androidx.compose.runtime.mutableStateOf(JSONObject())
        compose.runOnUiThread {
            compose.activity.setContent { GardenTheme { androidx.compose.foundation.layout.Column {
                PortionDestinationRows(2, counts.value) { key, count ->
                    counts.value = JSONObject(counts.value.toString()).put(key, count)
                }
            } } }
        }
        compose.onNodeWithContentDescription("Add one to Fridge").assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(1, counts.value.optInt("fridge")) }
        compose.onNodeWithContentDescription("Add one to Freezer").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, counts.value.optInt("freezer")) }
        compose.onNodeWithContentDescription("Add one to Eaten now").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove one from Freezer").performClick()
        compose.onNodeWithContentDescription("Add one to Eaten now").assertIsEnabled()

    }

    @Test fun snackAndDessertGroupsUseDeclaredTypesBeforeTitleAndTags() {
        fun recipe(id: String, title: String, type: String, tag: String) = j(
            "id" to id, "revision" to 1, "title" to title, "meal_type" to type, "readiness" to "ready",
            "tags" to JSONArray().put(tag),
            "ingredients" to JSONArray().put(j("name" to "Beans", "amount" to 1, "unit" to "can")),
            "steps" to JSONArray().put(j("text" to "Warm the beans.")))
        val dinner = recipe("cloud-pudding", "Cloud savory pudding", "dinner", "dessert")
        val snack = recipe("moon-morsels", "Moon oats morsels", "snack", "breakfast").apply {
            remove("meal_type"); put("meal_types", JSONArray().put("snack"))
        }
        val dessert = recipe("silver-squares", "Silver savory squares", "dessert", "dinner")
        compose.runOnUiThread {
            vm.snapshot.put("recipes", JSONArray().put(dessert).put(snack).put(dinner))
            compose.activity.setContent { GardenTheme { RecipesScreen(vm) } }
        }
        for ((label, title) in listOf("Lunch & dinner" to dinner.s("title"), "Snacks" to snack.s("title"), "Desserts" to dessert.s("title"))) {
            compose.onNodeWithTag("recipes-scroll").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNodeWithText("Breakfast").assertDoesNotExist()
    }

}
