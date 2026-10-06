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

/** Fictional captures on an unpaired emulator; source photos live only in its temporary cache. */
@RunWith(AndroidJUnit4::class)
class CaptureUiTest {
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

    @Test fun blankAndZeroCaptureWeightRemainUnspecified() {
        assertNull(optionalCaptureWeight("")); assertNull(optionalCaptureWeight("  "))
        assertNull(optionalCaptureWeight("0")); assertNull(optionalCaptureWeight("0.0"))
        assertTrue(captureWeightIsValid("")); assertTrue(captureWeightIsValid("0"))
        assertEquals(125.5, optionalCaptureWeight("125,5")!!, 0.0)
        listOf("-1", "NaN", "Infinity", "no").forEach { assertFalse(captureWeightIsValid(it)); assertNull(optionalCaptureWeight(it)) }
        listOf("", "0", "125").forEachIndexed { index, weight ->
            compose.runOnUiThread { vm.beginTextCapture(); compose.activity.setContent { GardenTheme { CaptureSheet(vm) } } }
            compose.onNodeWithText("Note").performTextInput("Rice bowl $index")
            compose.onNodeWithText("Add weight").performScrollTo().performClick()
            if (weight.isNotBlank()) compose.onNodeWithTag("capture-weight").performTextInput(weight)
            compose.onNodeWithTag("capture-save").performScrollTo().assertIsEnabled()
            if (index == 2) shot("phase6-capture-weight")
            compose.onNodeWithTag("capture-save").performClick()
            compose.runOnIdle {
                val saved = vm.captures.first()
                assertEquals(if (index == 2) "Rice bowl $index\nWeight: 125 g" else "Rice bowl $index", saved.s("note"))
            }
        }
    }

    private fun syntheticPhoto(name: String, color: Int): File {
        val file = File(compose.activity.cacheDir, "$name.png")
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test fun severalPhotosStayInOneDraftAndRemovalChangesOnlyChosenPhoto() {
        val first = syntheticPhoto("capture-one", android.graphics.Color.GREEN)
        compose.runOnUiThread { vm.beginPhotoCapture(first) }
        compose.waitUntil(10_000) { !vm.capturePhotoBusy && vm.draftCapture != null }
        val id = vm.draftCapture!!.s("id")
        val second = syntheticPhoto("capture-two", android.graphics.Color.BLUE)
        compose.runOnUiThread { vm.addCapturePhoto(second, id) }
        compose.waitUntil(10_000) { !vm.capturePhotoBusy && vm.capturePhotos(vm.draftCapture!!).size == 2 }
        val photos = vm.capturePhotos(vm.draftCapture!!)
        assertEquals(id, vm.draftCapture!!.s("id"))
        assertEquals(2, photos.map { it.s("sha256") }.toSet().size)
        photos.forEach { assertTrue(vm.capturePhotoFile(vm.draftCapture!!, it).exists()) }
        compose.runOnUiThread { compose.activity.setContent { GardenTheme { CaptureSheet(vm) } } }
        compose.onNodeWithTag("capture-photos").assertExists()
        compose.onNodeWithTag("capture-photo-${photos[0].s("id")}").assertExists()
        compose.onNodeWithTag("capture-photo-${photos[1].s("id")}").assertExists()
        shot("phase6-capture-photos")
        compose.onNodeWithContentDescription("Remove photo 1").performClick()
        compose.runOnIdle {
            assertEquals(listOf(photos[1].s("id")), vm.capturePhotos(vm.draftCapture!!).map { it.s("id") })
            assertFalse(vm.capturePhotoFile(vm.draftCapture!!, photos[0]).exists())
            assertTrue(vm.capturePhotoFile(vm.draftCapture!!, photos[1]).exists())
        }
        compose.onNodeWithTag("capture-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, vm.capturePhotos(vm.captures.first()).size)
            val restored = GardenModel(compose.activity.application)
            assertEquals(1, restored.capturePhotos(restored.captures.first()).size)
            assertEquals(id, restored.captures.first().s("id"))
        }
    }

    @Test fun linkedBatchAndProductNavigateToKitchenWithoutChangingFoodEvidence() {
        val lot = j("id" to "rice-lot", "product_id" to "rice", "name" to "Cooked rice", "base_unit" to "g", "location" to "fridge", "balance" to 250, "basis" to "known")
        val batch = j("id" to "rice-batch", "title" to "Rice batch", "pantry_item_id" to "rice-lot", "product_id" to "rice", "yield_g" to 500, "portions_made" to 2)
        val capture = j("id" to "rice-entry", "kind" to "meal", "capturedAt" to java.time.OffsetDateTime.now().toString(), "status" to "interpreted",
            "interpretation" to j("title" to "Rice lunch", "category" to "meal", "method" to "computed", "batchId" to "rice-batch", "intakeId" to "rice-intake",
                "questions" to JSONArray().put("How much rice did you eat?").put("Was anything added?"),
                "components" to JSONArray().put(j("name" to "Cooked rice", "productId" to "rice", "grams" to 100))))
        compose.runOnUiThread {
            vm.snapshot.put("pantry", JSONArray().put(lot)).put("batches", JSONArray().put(batch)).put("captures", JSONArray().put(capture))
            vm.openFoodLog = true
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.onNodeWithText("How much rice did you eat?").assertExists()
        compose.onNodeWithText("Was anything added?").assertDoesNotExist()
        compose.onNodeWithTag("food-log-question-rice-entry").performScrollTo().performClick()
        compose.onNodeWithText("Detail").assertExists()
        shot("phase6-capture-follow-up")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(vm.outbox.isEmpty()) }
        compose.onNodeWithText("Rice lunch").performScrollTo().performClick()
        compose.onNodeWithTag("food-log-batch-rice-batch").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(vm.openFoodLog); assertEquals(3, vm.tab); assertTrue(vm.outbox.isEmpty()) }
        compose.onNodeWithTag("kitchen-drawing").assertExists()
        compose.runOnUiThread { vm.openFoodLog = true }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("food-log-product-rice").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Rice lunch").performScrollTo().performClick()
        compose.onNodeWithTag("food-log-product-rice").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(vm.openFoodLog); assertEquals(3, vm.tab); assertTrue(vm.outbox.isEmpty())
            assertEquals(250.0, vm.graphPantry().single().getDouble("balance"), 0.0)
            assertEquals("rice-batch", vm.snapshot.a("batches").getJSONObject(0).s("id"))
            assertEquals("rice-entry", vm.foodLog().single().s("id"))
        }
        compose.onNodeWithTag("kitchen-drawing").assertExists()
        shot("phase6-capture-linked-kitchen")
    }
}
