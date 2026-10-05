package app.mealgarden

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Run only on an unpaired test emulator. Reports are opened but never sent. */
@RunWith(AndroidJUnit4::class)
class RecipeCookModeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun allowTimerNotifications() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val parcel = instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { it.readBytes() }
    }
    private lateinit var vm: GardenModel
    private fun click(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText(text))
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Native screenshots read SurfaceFlinger, after the Compose semantics frame.
        Thread.sleep(500)
        val file = File(compose.activity.filesDir, "lane09-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun open(rid: String) { compose.runOnUiThread { vm.selectedRecipe = rid }; compose.waitForIdle() }
    @Test fun actualRecipesResumeFinishAndReport() {
        compose.runOnUiThread {
            assertTrue("Never test writes against a paired laptop", Vault(compose.activity.application).token.isEmpty())
            File(compose.activity.filesDir, "snapshot.json").delete()
            compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            vm = GardenModel(compose.activity.application)
            assertFalse("Never test writes against a paired laptop", vm.paired)
            vm.prefs.edit().clear().commit()
            compose.activity.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Forest, surface = Cream, background = Cream)) { GardenApp(vm) } }
        }
        open("lentil-tomato-pot")
        shot("recipe-view")
        click("Mixed")
        compose.waitForIdle()
        shot("amounts-dialog")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Exact").fetchSemanticsNodes().isNotEmpty() }
        click("Exact")
        compose.runOnIdle { assertEquals("Exact", vm.prefs.getString("ingredientAmounts", "")) }
        click("Start cooking")
        click("I substituted"); compose.onNode(hasText("Carrots") and hasClickAction()).performClick()
        compose.onNodeWithText("e.g. frozen jasmine rice").performTextInput("Parsnips")
        compose.onNodeWithText("Why? (optional)").performTextInput("Used what I had")
        click("Save")
        compose.onNodeWithText("→ Parsnips").assertExists()
        click("Start step"); click("Finish step")
        compose.onNodeWithContentDescription("Leave cooking").performClick()
        click("Start cooking")
        compose.onNodeWithText("STEP 2 OF 5").assertIsDisplayed()
        click("Start step"); click("Finish step")
        click("Start 18 min timer")
        shot("cook-running-timer")
        compose.runOnIdle { assertTrue(vm.timers.keys.any { it.startsWith("lentil-tomato-pot:") }) }
        click("Done early")
        click("Start step"); click("Finish step")
        click("Start step"); click("Finish step")
        compose.onNode(hasText("4 portions") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        repeat(2) { compose.onNodeWithContentDescription("Add one to Fridge").performClick() }
        compose.onNodeWithContentDescription("Add one to Freezer").performClick()
        compose.onNodeWithContentDescription("Add one to Eaten now").performClick()
        shot("cook-end")
        click("Cooking report")
        compose.onNodeWithText("How did it go?").assertIsDisplayed()
        compose.onNodeWithText("Your cooking notes").performTextInput("Emulator check")
        compose.onNodeWithText("Save report").assertIsEnabled()
        compose.onNodeWithText("Later").performClick()
        open("apple-seed-oats")
        click("Start cooking")
        repeat(3) { click("Start step"); click("Finish step") }
        click("Start 360 min timer"); click("Done early")
        compose.onNode(hasText("4 portions") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        repeat(4) { compose.onNodeWithContentDescription("Add one to Fridge").performClick() }
        click("Cooking report")
        compose.onNodeWithText("How did it go?").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
    }
    @Test fun passiveTimerAdvancesToPrepAndNoIngredientStepDisablesSubstitution() {
        compose.runOnUiThread {
            assertTrue("Never test writes against a paired laptop", Vault(compose.activity.application).token.isEmpty())
            File(compose.activity.filesDir, "snapshot.json").delete()
            compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            vm = GardenModel(compose.activity.application)
            assertFalse(vm.paired)
            vm.prefs.edit().clear().commit()
            val recipe = JSONObject("""{"id":"sequencer-ui-fixture","revision":1,"title":"Cook test","readiness":"ready","ingredients":[{"id":"tofu","name":"Tofu","amount":1,"unit":"block"}],"steps":[{"title":"Preheat","text":"Preheat the Ninja to 400°F.","equipment":"Ninja","start_minute":0,"minutes":5,"passive_minutes":4},{"title":"Cube","text":"Cube the tofu.","start_minute":1,"minutes":2}]}""")
            vm.snapshot.put("recipes", JSONArray().put(recipe))
            vm.selectedRecipe = recipe.getString("id")
            compose.activity.setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Forest, surface = Cream, background = Cream)) { GardenApp(vm) } }
        }
        click("Start cooking")
        compose.onNodeWithText("I substituted").performScrollTo().assertIsNotEnabled()
        click("Start 4 min timer")
        compose.onNodeWithText("STEP 2 OF 2").assertIsDisplayed()
        compose.onNodeWithText("I substituted").performScrollTo().assertIsEnabled()
        shot("passive-timer-next-step")
        click("Start step"); click("Finish step")
        compose.onNodeWithText("Waiting").assertIsDisplayed()
        click("Skip the wait")
        compose.onNode(hasText("4 portions") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
}
