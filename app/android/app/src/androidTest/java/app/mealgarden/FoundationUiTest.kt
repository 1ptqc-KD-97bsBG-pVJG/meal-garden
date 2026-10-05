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
class FoundationUiTest {
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

    @Test fun galleryControlsChangeAmountAndReopenPanel() {
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ComponentGallery {} } } }
        compose.onNodeWithText("Components").assertIsDisplayed()
        shot("phase1-components-top")
        lazyScroll("Amounts")
        compose.onNodeWithContentDescription("More Portions").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Portions: 5").assertExists()
        compose.onNode(hasContentDescription("Half") and hasAnyAncestor(hasTestTag("gallery-amounts"))).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Less Portions").performClick()
        compose.onNodeWithContentDescription("Portions: 4").assertExists()
        shot("phase1-components-amounts")
        lazyScroll("Still have it?")
        compose.onNodeWithContentDescription("Close panel").performClick()
        compose.onNodeWithText("Open panel").performClick()
        compose.onNodeWithText("Still have it?").assertExists()
        shot("phase1-components-panel")
    }

    @Test fun eachDisabledModuleRemovesItsTodaySurface() {
        compose.runOnUiThread {
            vm.prefs.edit().apply { moduleIds.forEach { putBoolean("module:$it", false) } }.commit()
            compose.activity.setContent { GardenTheme { TodayScreen(vm) } }
        }
        val surfaces = listOf(
            "timeline" to hasContentDescription("Log another meal"),
            "nutrition" to hasContentDescription("Open health day"),
            "basket" to hasText("Today's basket"),
            "week" to hasText("This week"),
        )
        surfaces.forEach { (id, surface) ->
            compose.onNode(surface).assertDoesNotExist()
            compose.runOnUiThread { vm.prefs.edit().putBoolean("module:$id", true).commit() }
            compose.waitForIdle()
            compose.onNode(surface).assertExists()
            compose.runOnUiThread { vm.prefs.edit().putBoolean("module:$id", false).commit() }
            compose.waitForIdle()
            compose.onNode(surface).assertDoesNotExist()
        }
        compose.onNodeWithText("Tonight?", ignoreCase = true).assertExists()
        compose.runOnUiThread { vm.prefs.edit().apply { moduleIds.forEach { putBoolean("module:$it", true) } }.commit() }
        shot("phase3-today")
        lazyScroll("This week")
        shot("phase3-today-week")
    }

}
