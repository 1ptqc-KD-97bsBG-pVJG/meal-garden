package app.mealgarden

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fictional fixtures. A paired emulator must never receive test writes. */
@RunWith(AndroidJUnit4::class)
class MoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: GardenModel

    @Before fun setup() {
        compose.runOnUiThread {
            assertTrue("Never test writes against a paired laptop", Vault(compose.activity.application).token.isEmpty())
            compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            File(compose.activity.filesDir, "snapshot.json").delete()
            vm = GardenModel(compose.activity.application)
            assertFalse(vm.paired)
            vm.snapshot.put("captures", JSONArray()).put("cooking", JSONArray())
                .put("assumptions", JSONArray()).put("preferences", JSONArray())
        }
    }

    @After fun cleanup() {
        if (!::vm.isInitialized) return
        compose.runOnUiThread {
            vm.prefs.edit().clear().commit()
            File(compose.activity.filesDir, "snapshot.json").delete()
            File(compose.activity.filesDir, "cache/messages-more-fixture.json").delete()
        }
    }

    private fun settings() {
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { YourAppScreen(vm) {} } } }
        compose.waitForIdle()
    }

    private fun today() {
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { TodayScreen(vm) } } }
        compose.waitForIdle()
    }

    @Test fun actualModuleSwitchesHideAndRestoreTheirTodaySurfaces() {
        val modules = listOf(
            "timeline" to hasContentDescription("Log another meal"),
            "nutrition" to hasContentDescription("Open health day"),
            "basket" to hasText("Today's basket"),
            "week" to hasText("This week"),
        )
        modules.forEach { (id, surface) ->
            today()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(surface)
            compose.onNode(surface).assertExists()
            settings()
            compose.onNodeWithTag("module:$id").performScrollTo().assertIsOn().performClick()
            compose.onNodeWithTag("module:$id").assertIsOff()
            today()
            var foundDisabledSurface = false
            try {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(surface)
                foundDisabledSurface = true
            } catch (_: AssertionError) { }
            assertFalse("Disabled $id must be absent throughout Today", foundDisabledSurface)
            compose.onNode(surface).assertDoesNotExist()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("TONIGHT?"))
            compose.onNodeWithText("TONIGHT?").assertExists()
            settings()
            compose.onNodeWithTag("module:$id").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("module:$id").assertIsOn()
            today()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(surface)
            compose.onNode(surface).assertExists()
        }
        compose.runOnIdle {
            val restored = GardenModel(compose.activity.application)
            modules.forEach { (id, _) -> assertTrue(restored.prefs.getBoolean("module:$id", false)) }
        }
    }

    @Test fun activityUsesCaptureEvidenceAndUndoQueuesAnActualCorrection() {
        val fixture = JSONObject("""{
          "captures":[{"id":"capture-lunch","kind":"text","capturedAt":"2031-02-10T12:00:00Z","interpretation":{"title":"Lentil lunch"}}],
          "pantry":[{"id":"lentil-lot","name":"Lentils","balance":2,"basis":"known","base_unit":"count","location":"pantry"}],
          "assumptions":[{"id":"assumed-use","statement":"Assumed lentils were used for lunch.","created_at":"2031-02-10T12:00:00Z","evidence":["movement-lunch"]}],
          "preferences":[]
        }""")
        compose.runOnUiThread {
            fixture.keys().forEach { vm.snapshot.put(it, fixture.get(it)) }
            vm.activity.put("jobs", JSONArray().put(j(
                "id" to "job-lunch", "kind" to "log_food", "status" to "completed",
                "created" to "2031-02-10T12:00:00Z", "conversation_id" to "conversation-lunch",
                "input" to j("captureId" to "capture-lunch", "text" to "Internal input prompt that must not become the Activity title."),
            )))
            compose.activity.setContent { GardenTheme { ActivityScreen(vm) {} } }
        }
        compose.onNodeWithText("Lentil lunch").assertExists()
        val date = humanDate("2031-02-10T12:00:00Z")
        compose.onNodeWithText("Food log · $date").assertExists()
        compose.onNodeWithText("Internal input prompt that must not become the Activity title.").assertDoesNotExist()
        compose.onNodeWithTag("activity-other-evidence").performClick()
        val assumption = compose.onNodeWithTag("activity-assumption:assumed-use")
        assumption.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed")).performClick()
        assumption.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded"))
        assumption.performClick().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
        assumption.performClick()
        compose.onNodeWithTag("activity-undo:assumed-use").performClick()
        compose.onNodeWithText("Assumed lentils were used for lunch.").assertDoesNotExist()
        compose.onNodeWithText("Waiting to sync").assertExists()
        compose.runOnIdle {
            val queued = vm.outbox.single()
            assertEquals("/api/assumptions/resolve", queued.s("route"))
            assertEquals(listOf("assumed-use"), queued.o("payload").a("ids").strings())
            assertEquals("corrected", queued.o("payload").s("status"))
            assertTrue(queued.o("payload").s("idempotencyKey").isNotBlank())
            assertTrue(vm.graphAssumptions().isEmpty())
            assertEquals("The phone waits for the engine to reverse the linked movement", 2.0, vm.graphPantry().single().optDouble("balance"), 0.0)
            val restored = GardenModel(compose.activity.application)
            assertEquals(queued.toString(), restored.outbox.single().toString())
        }
        compose.onNodeWithText("Lentil lunch").performClick()
        compose.onNodeWithText("Open conversation").assertExists()
        compose.onNodeWithText("Food log · $date").assertExists()
    }

    @Test fun generatedTaskEvidenceIsCompactAndRecipeAnswersLeaveTheAskSheet() {
        val prompt = "Internal source instructions for reading a meal photo."
        var recipeId = ""
        compose.runOnUiThread {
            recipeId = vm.snapshot.a("recipes").objects().first(::recipeReady).s("id")
            vm.snapshot.put("captures", JSONArray().put(j("id" to "capture-lunch", "kind" to "meal",
                "capturedAt" to "2031-02-10T12:00:00Z", "interpretation" to j("title" to "Lentil lunch"))))
            vm.activity.put("jobs", JSONArray().put(j("id" to "job-lunch", "kind" to "log_food",
                "input" to j("captureId" to "capture-lunch"))))
            val messages = listOf(
                j("id" to "task-source", "role" to "user", "text" to prompt, "job_id" to "job-lunch",
                    "created" to "2031-02-10T12:00:00Z", "metadata" to j("source" to "food_log")),
                j("id" to "task-answer", "role" to "assistant", "text" to "Meal details recorded.",
                    "panels" to JSONArray().put(j("title" to "Meal idea", "body" to "Lentils and tomatoes",
                        "actions" to JSONArray().put(j("type" to "recipe", "value" to recipeId, "label" to "Open dish")))))
            )
            File(compose.activity.filesDir, "cache/messages-more-fixture.json").apply {
                parentFile!!.mkdirs(); writeText(JSONArray(messages).toString())
            }
            vm.chooseConversation("more-fixture")
            vm.tab = 4
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ask").performClick()
        compose.onNodeWithTag("ask-answers").performScrollToNode(hasTestTag("chat-task:task-source"))
        compose.onNodeWithText("Food log · Lentil lunch").assertExists()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        compose.onNodeWithTag("chat-task-details:task-source").performClick()
        compose.onNodeWithText(prompt).assertExists()
        compose.onNodeWithTag("chat-task-details:task-source").performClick()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        compose.onNodeWithTag("ask-answers").performScrollToNode(hasText("Open dish"))
        compose.onNodeWithText("Open dish").performClick()
        compose.onNodeWithTag("recipe-scroll").assertIsDisplayed()
        compose.onNodeWithTag("ask-answers").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(recipeId, vm.selectedRecipe)
            assertEquals(prompt, vm.messages.first().s("text"))
            assertTrue(vm.outbox.isEmpty())
        }
    }

    @Test fun moreHasDirectShoppingAndPreferencesHideHistoryUntilAsked() {
        var destination = ""
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { MoreScreen { destination = it } } } }
        compose.onNodeWithText("Shopping").performClick()
        compose.runOnIdle { assertEquals("shopping", destination) }
        compose.runOnUiThread {
            vm.snapshot.put("preferences", JSONArray().put(j("id" to "pref-greens", "kind" to "taste", "subject" to "orbit_greens",
                "stance" to "avoid", "value" to j("priority_order" to JSONArray().put("long_term_health").put("food_waste")), "statement" to "Avoid these greens now.", "source" to "stated", "confidence" to "known", "created_at" to "2031-02-10",
                "history" to JSONArray().put(j("statement" to "Previously loved these greens.", "source" to "imported", "confidence" to "assumed", "created_at" to "2031-02-08")))))
            compose.activity.setContent { GardenTheme { PreferencesScreen(vm) } }
        }
        compose.onNodeWithText("Orbit greens").assertExists()
        compose.onNodeWithText("Avoid · long term health · food waste").assertExists()
        compose.onNodeWithText("Avoid these greens now.").assertDoesNotExist()
        compose.onNodeWithText("Previously loved these greens.").assertDoesNotExist()
        compose.onNodeWithTag("preference:taste:orbit_greens").performClick()
        compose.onNodeWithText("Previously loved these greens.").assertExists()
        compose.onNodeWithText("Avoid these greens now.").assertExists()
        compose.runOnIdle { assertTrue("Reading preference history never writes facts", vm.outbox.isEmpty()) }
    }

    @Test fun assumptionsGroupTheirSourceAndKeepEvidenceBehindEachFood() {
        compose.runOnUiThread {
            vm.snapshot.put("assumptions", JSONArray().put(j("id" to "a-1", "foodName" to "Orbit lentils", "statement" to "Full first evidence.",
                "source" to j("id" to "cook-1", "kind" to "recipe", "title" to "Orbit bowl"), "evidence" to JSONArray().put("event-1"))).put(j(
                "id" to "a-2", "foodName" to "Lunar greens", "statement" to "Full second evidence.",
                "source" to j("id" to "cook-1", "kind" to "recipe", "title" to "Orbit bowl"), "evidence" to JSONArray().put("event-1"))))
            compose.activity.setContent { GardenTheme { ActivityScreen(vm) {} } }
        }
        compose.onAllNodesWithText("Orbit bowl").assertCountEquals(1)
        compose.onNodeWithText("Orbit lentils").assertExists()
        compose.onNodeWithText("Lunar greens").assertExists()
        compose.onNodeWithText("Full first evidence.").assertDoesNotExist()
        compose.onNodeWithTag("activity-assumption:a-1").performClick()
        compose.onNodeWithText("Full first evidence.").assertExists()
        compose.onNodeWithText("Full second evidence.").assertDoesNotExist()
        compose.runOnIdle { assertTrue(vm.outbox.isEmpty()) }
    }


    @Test fun localShoppingShowsUpcomingMealIngredientsWithoutSyncingOrDeducingPantry() {
        compose.runOnUiThread {
            vm.snapshot.put("today", "2031-02-10")
                .put("activePlan", j("id" to "orbit-plan", "meals" to JSONArray()
                    .put(j("date" to "2031-02-08", "recipe_id" to "past-dish"))
                    .put(j("date" to "2031-02-10", "recipe_id" to "orbit-bowl", "batches" to 2))))
                .put("recipes", JSONArray().put(j("id" to "orbit-bowl", "title" to "Orbit bowl", "readiness" to "ready", "ingredients" to JSONArray().put(
                    j("name" to "Lunar lentils", "amount" to 80, "unit" to "g")), "steps" to JSONArray().put(j("text" to "Simmer the lentils.")))))
                .put("shopping", j("items" to JSONArray().put(j("id" to "unrelated", "name" to "Comet grain", "amount" to 900, "unit" to "g", "purchase_count" to 2, "purchase" to j("unit" to "bags", "label" to "500 g bags")))))
            compose.activity.setContent { GardenTheme { MarketScreen(vm) } }
        }
        compose.onNodeWithText("Next planned meal").assertExists()
        compose.onNodeWithText("Lunar lentils").assertExists()
        compose.onNodeWithText("160 g").assertExists()
        compose.runOnIdle {
            assertEquals("orbit-bowl", nextPlannedMeal(vm.snapshot)?.s("recipe_id"))
            assertTrue(vm.outbox.isEmpty())
        }
    }


    @Test fun shortKitchenLabelsOnlyStripAnExplicitWholeBrandPrefix() {
        assertEquals("Roasted comet greens", kitchenShortName(j("name" to "Orbit Foods Roasted comet greens", "brand" to "Orbit Foods")))
        assertEquals("Orbital greens", kitchenShortName(j("name" to "Orbital greens", "brand" to "Orbit")))
        assertEquals("Unknown long food description", kitchenShortName(j("name" to "Unknown long food description")))
        assertEquals("Extra-Firm Tofu", kitchenShortName(j("name" to "Orbit Market Signature Extra-Firm Tofu", "kind" to "packaged")))
        assertEquals("Orbit Market Signature Extra-Firm Tofu", kitchenShortName(j("name" to "Orbit Market Signature Extra-Firm Tofu", "kind" to "homemade", "batch_id" to "batch-one")))
        assertEquals("2.4 kg", kitchenDisplayAmount(2400.0, "g"))
    }


    @Test fun otherEvidenceIsCollapsedAndItsFullStatementRequiresATapWithoutInventingFood() {
        compose.runOnUiThread {
            vm.snapshot.put("assumptions", JSONArray().put(j("id" to "linked", "foodName" to "Lunar greens", "statement" to "Linked food evidence.",
                "source" to j("id" to "known-cook", "kind" to "recipe", "title" to "Lunar bowl"))).put(j(
                "id" to "unlinked", "statement" to "Mysterious orbit food was used, according to an old note.", "evidence" to JSONArray().put("unknown-note"))))
            compose.activity.setContent { GardenTheme { ActivityScreen(vm) {} } }
        }
        compose.onNodeWithTag("activity-assumption:linked").assertExists()
        compose.onNodeWithTag("activity-assumption:unlinked").assertDoesNotExist()
        compose.onNodeWithText("Mysterious orbit food was used, according to an old note.").assertDoesNotExist()
        compose.onNodeWithTag("activity-other-evidence").performClick()
        compose.onNodeWithTag("activity-assumption:unlinked").assertExists().performClick()
        compose.onNodeWithText("Mysterious orbit food was used, according to an old note.").assertExists()
        compose.onNodeWithText("Food not linked").assertExists()
        compose.onNodeWithTag("activity-other-evidence").performClick()
        compose.onNodeWithTag("activity-assumption:unlinked").assertDoesNotExist()
        compose.onNodeWithText("Mysterious orbit food was used, according to an old note.").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, vm.graphAssumptions().size)
            assertTrue(vm.outbox.isEmpty())
        }
    }

}
