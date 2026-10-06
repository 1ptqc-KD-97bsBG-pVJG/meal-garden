package app.mealgarden

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.LinkAnnotation
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
        // Scrolling the stepper into view does not bring the amount row below it above the fixed navigation bar.
        compose.onNode(hasContentDescription("Half") and hasAnyAncestor(hasTestTag("gallery-amounts")))
            .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Less Portions").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Portions: 4").assertExists()
        shot("phase1-components-amounts")
        lazyScroll("Still have it?")
        compose.onNodeWithContentDescription("Close panel").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Open panel").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Still have it?").assertExists()
        shot("phase1-components-panel")
    }

    @Test fun eachDisabledModuleRemovesItsTodaySurface() {
        compose.runOnUiThread {
            vm.prefs.edit().apply { moduleIds.forEach { putBoolean("module:$it", false) } }.commit()
            compose.activity.setContent { GardenTheme { Box(Modifier.systemBarsPadding()) { TodayScreen(vm) } } }
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

    @Test fun freshTodayShowsConnectionBeforeHouseholdCards() {
        compose.onNodeWithText("Your kitchen starts here").assertIsDisplayed()
        compose.onNodeWithText("Tonight?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Component gallery").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Leave an app note").assertIsDisplayed()
        shot("lane16-first-connect")
        compose.onNodeWithText("Connect your laptop").performClick()
        compose.onNodeWithTag("garden-connection").assertIsDisplayed()
    }

    @Test fun askKeepsActionAboveLongEvidenceAndShowsOnePhotoControls() {
        val text = "Try the aurora dish.\n\n" + "Original evidence and reasoning remain available. ".repeat(40)
        val message = j("id" to "answer", "role" to "assistant", "text" to text, "panels" to JSONArray().put(
            j("title" to "Aurora dish", "body" to "Panel evidence", "actions" to JSONArray().put(j("type" to "recipe", "value" to "aurora", "label" to "Cook Aurora")))))
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ChatMessage(vm, message) } } }
        compose.onNodeWithText("Cook Aurora").assertIsDisplayed()
        shot("lane16-compact-ask")
        compose.onNodeWithText("Cook Aurora").performClick()
        compose.runOnIdle { assertEquals("aurora", vm.selectedRecipe) }
        compose.onNodeWithText("Panel evidence").assertDoesNotExist()
        compose.onNodeWithTag("chat-answer-details:answer").performClick()
        compose.onNodeWithText("Panel evidence").assertExists()
        compose.runOnUiThread {
            vm.selectedRecipe = null; vm.attachment = "fictional-attachment"
            val preview = File(compose.activity.cacheDir, "ask-photo-${vm.attachment.hashCode()}.jpg")
            val bitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.CYAN)
            preview.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            compose.activity.setContent { GardenTheme { ChatScreen(vm) } }
        }
        compose.onNodeWithText("1 photo").assertIsDisplayed()
        compose.onNodeWithTag("chat-attachment-preview").assertIsDisplayed()
        compose.onNodeWithText("Replace").assertIsDisplayed()
        shot("lane16-ask-attachment")
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("1 photo").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", vm.attachment); assertTrue(vm.outbox.isEmpty()) }
    }

    @Test fun compactAnswerShowsReadableMarkdownAndKeepsLinkedEvidenceInDetails() {
        val summary = "Warm the aurora dish with Reference."
        val evidence = "The observation keeps its original attribution."
        val message = j("id" to "formatted-answer", "role" to "assistant",
            "text" to "### Warm **the aurora dish** with [Reference](https://example.com/reference).\n\n$evidence",
            "panels" to JSONArray().put(j("actions" to JSONArray().put(
                j("type" to "recipe", "value" to "aurora", "label" to "Cook Aurora")))))
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ChatMessage(vm, message) } } }
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[Reference]", substring = true).assertDoesNotExist()
        compose.onNodeWithText("https://example.com/reference", substring = true).assertDoesNotExist()
        compose.onNodeWithText(evidence, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cook Aurora").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("aurora", vm.selectedRecipe) }
        compose.onNodeWithTag("chat-answer-details:formatted-answer").performClick()
        val fullAnswer = compose.onNodeWithText("$summary\n\n$evidence").assertIsDisplayed().fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertTrue("Details preserves the original source link", fullAnswer.getLinkAnnotations(0, fullAnswer.length)
            .any { (it.item as? LinkAnnotation.Url)?.url == "https://example.com/reference" })
        compose.onNodeWithText("Cook Aurora").assertIsDisplayed()
    }

    @Test fun healthyConnectionStaysQuietAndOfflineWarningOpensConnection() {
        var opened = false
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ConnectionStatus(true, true) { opened = true } } } }
        compose.onNodeWithText("Laptop offline").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ConnectionStatus(true, false) { opened = true } } } }
        compose.onNodeWithText("Laptop offline").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { ConnectionStatus(false, false) {} } } }
        compose.onNodeWithText("Laptop offline").assertDoesNotExist()
    }

    @Test fun attachedPhotoAppearsWhenItsCacheArrivesAfterUploadId() {
        val id = "delayed-preview-fixture"
        val cache = ChatPreviewCache(compose.activity.cacheDir)
        val file = File(compose.activity.cacheDir, "ask-photo-${id.hashCode()}.jpg")
        val source = File.createTempFile("delayed-preview-", ".jpg", compose.activity.cacheDir)
        file.delete()
        try {
            compose.runOnUiThread {
                vm.attachment = id
                compose.activity.setContent { GardenTheme { ChatComposer(vm, previews = cache) } }
            }
            compose.onNodeWithText("1 photo").assertIsDisplayed()
            compose.onNodeWithContentDescription("Attached photo").assertIsDisplayed()
            compose.onNodeWithTag("chat-attachment-preview").assertDoesNotExist()
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            // Complete the same delayed upload/cache handoff used by the real composer.
            compose.runOnUiThread { cache.complete(id, source) }
            // No typing, mode toggle, or other event is needed to refresh the attachment.
            compose.onNodeWithTag("chat-attachment-preview").assertIsDisplayed()
            compose.onNodeWithContentDescription("Attached photo").assertDoesNotExist()
            compose.onNodeWithText("Remove").performClick()
            compose.onNodeWithTag("chat-attachment-preview").assertDoesNotExist()
        } finally { file.delete(); source.delete() }
    }

}
