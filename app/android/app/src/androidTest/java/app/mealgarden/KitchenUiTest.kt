package app.mealgarden

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
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

/** Fictional fixtures; never run local writes on an emulator paired to a household. */
@RunWith(AndroidJUnit4::class)
class KitchenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: GardenModel
    private val moduleIds = listOf("timeline", "nutrition", "basket", "week", "shoppingTimeline")

    @Before fun setup() {
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

    private fun tab(name: String) = compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    private fun lazyScroll(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun kitchenFixture() = JSONObject("""{
      "kitchen":{"layout":{"freezer_position":"below"},"available":["Stove","Oven","Microwave","Induction hob","Countertop oven"]},
      "pantry":[
        {"id":"greens-lot","product_id":"greens","name":"Spinach","location":"fridge","base_unit":"g","kind":"generic","balance":null,"basis":"unknown"},
        {"id":"beans-lot","product_id":"beans","name":"Beans","location":"fridge","base_unit":"count","kind":"packaged","balance":2,"basis":"known"}
      ],"batches":[],"receipts":[],"assumptions":[],"preferences":[]
    }""")

    @Test fun storedKitchenLayoutAndUnknownAmountsStayVisible() {
        compose.runOnUiThread { kitchenFixture().let { fixture -> fixture.keys().forEach { vm.snapshot.put(it, fixture.get(it)) } }; vm.tab = 3; compose.activity.setContent { GardenTheme { GardenApp(vm) } } }
        val freezerBelow = compose.onNodeWithTag("kitchen-zone:freezer").fetchSemanticsNode().boundsInRoot
        val fridgeAbove = compose.onNodeWithTag("kitchen-zone:fridge").fetchSemanticsNode().boundsInRoot
        assertTrue("Stored below layout is visible in the drawing", freezerBelow.top > fridgeAbove.bottom)
        compose.onNodeWithContentDescription("Spinach, amount unknown").assertExists()
        compose.onNodeWithContentDescription("Beans, 2 left").assertExists()
        compose.onNodeWithText("0 g left").assertDoesNotExist()
        shot("phase4-kitchen-below")
        compose.runOnUiThread {
            vm.snapshot.o("kitchen").o("layout").put("freezer_position", "above")
            vm.tab = 3; compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.waitForIdle()
        val freezerAbove = compose.onNodeWithTag("kitchen-zone:freezer").fetchSemanticsNode().boundsInRoot
        val fridgeBelow = compose.onNodeWithTag("kitchen-zone:fridge").fetchSemanticsNode().boundsInRoot
        assertTrue("Stored above layout is visible in the drawing", freezerAbove.bottom <= fridgeBelow.top)
        assertEquals("below", kitchenFreezerPosition(JSONObject(), listOf(j("subject" to "freezer layout", "statement" to "Freezer is below the fridge."))))
        val grouped = kitchenGroups(listOf(j("id" to "one", "product_id" to "rice", "location" to "pantry", "balance" to 100), j("id" to "two", "product_id" to "rice", "location" to "pantry", "balance" to null)))
        assertEquals(1, grouped.size)
        assertTrue("A partial unknown lot never becomes a zero quantity", grouped.single().isNull("_total"))
    }

    @Test fun itemPanelSwitchesWithoutBlockingKitchenAndDoesNotCountAnUnknown() {
        compose.runOnUiThread { kitchenFixture().let { fixture -> fixture.keys().forEach { vm.snapshot.put(it, fixture.get(it)) } }; vm.tab = 3; compose.activity.setContent { GardenTheme { GardenApp(vm) } } }
        compose.onNodeWithContentDescription("Spinach, amount unknown").performClick()
        compose.onNodeWithTag("kitchen-item-panel").assertExists()
        compose.onNodeWithTag("kitchen-drawing").assertExists()
        compose.onNode(hasText("How much left?") and hasAnyAncestor(hasTestTag("kitchen-item-panel"))).assertExists()
        assertTrue(vm.outbox.isEmpty())
        compose.onNodeWithContentDescription("Beans, 2 left").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Beans") and hasAnyAncestor(hasTestTag("kitchen-item-panel"))).assertExists()
        compose.onNode(hasText("Spinach") and hasAnyAncestor(hasTestTag("kitchen-item-panel"))).assertDoesNotExist()
        compose.onNodeWithTag("kitchen-drawing").assertExists()
        assertTrue("Switching a panel does not assert pantry facts", vm.outbox.isEmpty())
        compose.onNodeWithTag("kitchen-item-panel").performScrollTo()
        shot("phase4-kitchen-item-panel")
    }

}
