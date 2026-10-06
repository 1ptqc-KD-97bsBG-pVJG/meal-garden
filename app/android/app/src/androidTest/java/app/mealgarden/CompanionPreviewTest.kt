package app.mealgarden

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Explicit preview only: a copied companion, loopback port, and no saved food records. */
@RunWith(AndroidJUnit4::class)
class CompanionPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: GardenModel
    private var phase = 0

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val file = File(compose.activity.filesDir, "preview-screenshots/phase$phase-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun hasTextNode(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun hasDescription(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    private fun click(text: String) {
        val clickable = compose.onAllNodes(hasText(text) and hasClickAction())
        val target = if (clickable.fetchSemanticsNodes().size == 1) clickable[0] else compose.onAllNodesWithText(text)[0]
        if (!target.isDisplayed()) target.performScrollTo()
        target.performClick()
        compose.waitForIdle()
    }
    private fun tab(text: String) {
        compose.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.waitForIdle()
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun lazyScroll(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }
    private fun resetTo(destination: Int) {
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) back()
        compose.runOnUiThread {
            vm.selectedRecipe = null; vm.openSettings = false; vm.openPreferences = false
            vm.openHistory = false; vm.openFridgeCheck = false; vm.openHealth = false; vm.openFoodLog = false; vm.openCapture = false
            vm.tab = destination
        }
        compose.waitForIdle()
        tab(when (destination) { 0 -> "Today"; 1 -> "Recipes"; 3 -> "Kitchen"; else -> "More" })
    }

    @Test fun walkCopiedCompanionInterface() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("This preview requires explicit copiedCompanion=true", arguments.getString("copiedCompanion") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val notificationPermission = instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(notificationPermission).use { it.readBytes() }
        phase = arguments.getString("phase")?.toIntOrNull() ?: error("Pass phase=1 through 8")
        require(phase in 1..8)
        val endpoint = arguments.getString("endpoint", "http://127.0.0.1:14783").trimEnd('/')
        val address = URI(endpoint)
        require(endpoint.startsWith("http://127.0.0.1:14783") && address.scheme == "http" && address.host == "127.0.0.1" && address.port == 14783 && address.userInfo == null && address.query == null && address.fragment == null && address.path.isNullOrBlank())
        val vault = Vault(compose.activity.application)
        assertTrue("Start on an unpaired test emulator", vault.token.isEmpty())
        val preferences = compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE)
        assertEquals("Do not sync pending domain writes during a preview", 0, JSONArray(preferences.getString("outbox", "[]")!!).length())
        assertNull("Do not sync pending goals during a preview", preferences.getString("health-preferences-pending", null))
        val localNotes = JSONArray(preferences.getString("appNotes", "[]")!!).objects()
        assertTrue("Do not sync pending field notes during a preview", localNotes.all { it.optBoolean("synced", false) })
        val localCaptures = JSONArray(preferences.getString("captures", "[]")!!).objects()
        assertTrue("Do not sync unsaved capture observations during a preview", localCaptures.all { it.optBoolean("synced", false) })
        val html = URL("$endpoint/setup").readText()
        val code = Regex("<strong>\\s*([0-9]{8})\\s*</strong>").find(html)?.groupValues?.get(1)
            ?: error("The copied companion did not provide a pairing code")
        val connection = URL("$endpoint/pair").openConnection() as HttpURLConnection
        val paired = try {
            connection.requestMethod = "POST"; connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json"); connection.doOutput = true
            connection.outputStream.use { it.write(j("code" to code, "name" to "Interface preview").toString().toByteArray()) }
            check(connection.responseCode == 200) { "Pairing failed with HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
        var cachedSnapshot = ""
        try {
            compose.runOnUiThread {
                vault.endpoint = endpoint; vault.token = paired.getString("token")
                vm = GardenModel(compose.activity.application)
                cachedSnapshot = vm.snapshot.toString()
                vm.refreshNow()
                compose.activity.setContent { GardenTheme { GardenApp(vm) } }
            }
            compose.waitUntil(30_000) { vm.online && vm.snapshot.toString() != cachedSnapshot && vm.snapshot.a("recipes").length() > 0 }
            compose.runOnIdle { assertEquals("", vm.error); assertTrue(vm.outbox.isEmpty()) }
            when (phase) {
                1 -> gallery()
                2 -> shell()
                3 -> todayAndHealth()
                4 -> if (arguments.getString("focus") == "assumptions") kitchenAssumptions() else kitchen()
                5 -> recipesAndCooking()
                6 -> captureAndLog()
                7 -> more()
                8 -> notesAndGallery()
            }
            compose.runOnIdle { assertTrue("Preview actions must leave no domain writes", vm.outbox.isEmpty()) }
        } finally {
            compose.runOnUiThread {
                if (::vm.isInitialized) { vm.discardCapture(); vm.timers.keys.toList().forEach(vm::stopTimer) }
                vault.token = ""
            }
        }
    }

    private fun gallery() {
        compose.onNodeWithContentDescription("Component gallery").performClick()
        shot("components-top")
        lazyScroll("Amounts"); shot("components-amounts")
        lazyScroll("Still have it?"); shot("components-panel")
        lazyScroll("Key"); shot("components-key")
        back()
    }

    private fun shell() {
        listOf("Today", "Kitchen", "Recipes", "More").forEach { label -> tab(label); shot("shell-${label.lowercase()}") }
        compose.onNodeWithContentDescription("Capture").performClick()
        shot("shell-capture")
        if (hasTextNode("Discard")) click("Discard") else back()
        listOf("Your app", "Activity", "Receipts", "Preferences", "Connection").forEach { label ->
            resetTo(4); click(label); shot("shell-${label.lowercase().replace(' ', '-')}")
        }
        resetTo(4); click("Ask"); shot("shell-ask")
        resetTo(4)
        compose.runOnUiThread { vm.openHistory = true }
        shot("shell-history")
    }

    private fun todayAndHealth() {
        resetTo(0); shot("today")
        click("Key"); shot("today-key"); click("Done")
        if (hasTextNode("This week")) lazyScroll("This week") else {
            val lists = compose.onAllNodes(hasScrollToIndexAction())
            if (lists.fetchSemanticsNodes().isNotEmpty()) lists[0].performScrollToNode(hasText("This week"))
        }
        shot("today-week")
        resetTo(0)
        if (hasDescription("Open health insights")) compose.onNodeWithContentDescription("Open health insights").performClick()
        else compose.runOnUiThread { vm.openHealth = true }
        shot("health")
        click("Edit goals"); shot("health-goals"); click("Cancel")
        if (hasDescription("How insights work")) { compose.onNodeWithContentDescription("How insights work").performClick(); shot("health-method"); back() }
        else if (hasTextNode("How this works")) { click("How this works"); shot("health-method"); back() }
    }

    private fun kitchen() {
        resetTo(3); shot("kitchen")
        click("Key"); shot("kitchen-key"); click("Key")
        compose.onNodeWithContentDescription("Add food").performClick(); shot("kitchen-add-food"); click("Cancel")
        val zoneNames = listOf("Fridge", "Veg drawers", "Freezer", "Pantry", "Counter", "Unplaced")
        zoneNames.forEach { zone ->
            compose.onNodeWithTag("graph-kitchen").performScrollToNode(hasText("Fridge") and hasClickAction())
            if (hasTextNode(zone)) {
                click(zone); shot("kitchen-${zone.lowercase().replace(' ', '-')}")
                val food = vm.graphPantry().firstOrNull { item ->
                    item.optDouble("balance", Double.NaN) != 0.0 && when (zone) {
                        "Freezer" -> item.s("location").contains("freez", true)
                        "Pantry" -> item.s("location").contains("pantry", true) || item.s("location").contains("cupboard", true)
                        "Counter" -> item.s("location").contains("counter", true)
                        "Veg drawers" -> item.s("location").contains("drawer", true) || item.s("location").contains("crisper", true)
                        "Fridge" -> item.s("location").contains("fridge", true)
                        else -> item.s("location") in listOf("unknown", "unplaced", "")
                    }
                }
                if (food != null) {
                    val match = hasContentDescription(food.s("name"), substring = true) and hasClickAction()
                    val icons = compose.onAllNodes(match)
                    if (icons.fetchSemanticsNodes().isNotEmpty()) icons[0].performClick()
                    else if (hasTextNode(food.s("name"))) click(food.s("name"))
                    if (compose.onAllNodesWithTag("kitchen-item-panel").fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNodeWithTag("kitchen-item-panel").performScrollTo(); shot("kitchen-${zone.lowercase().replace(' ', '-')}-item")
                        if (hasTextNode("Set amount")) { click("Set amount"); compose.onNodeWithTag("kitchen-item-panel").performScrollTo(); shot("kitchen-${zone.lowercase().replace(' ', '-')}-exact"); click("Cancel") }
                        val bought = food.s("purchased_on").take(10)
                        val receipt = compose.onAllNodesWithTag("kitchen-receipt:${food.s("id")}")
                        if (bought.isNotBlank() && receipt.fetchSemanticsNodes().isNotEmpty()) { receipt[0].performScrollTo().performClick(); shot("kitchen-${zone.lowercase().replace(' ', '-')}-receipt"); click("Close") }
                        if (hasDescription("Close panel")) compose.onAllNodesWithContentDescription("Close panel")[0].performScrollTo().performClick()
                    }
                }
            }
        }
        kitchenAssumptions()
    }

    private fun kitchenAssumptions() {
        resetTo(3)
        if (hasTextNode("Check kitchen")) click("Check kitchen") else compose.runOnUiThread { vm.openFridgeCheck = true }
        shot("kitchen-assumptions")
        val first = vm.graphAssumptions().firstOrNull()
        if (first != null && hasTextNode(first.s("statement"))) { click(first.s("statement")); compose.onNodeWithTag("assumption-detail").performScrollTo(); shot("kitchen-assumption-detail") }
        // Looks right and correction actions intentionally remain untouched.
        resetTo(3)
    }

    private fun recipesAndCooking() {
        resetTo(1); shot("recipes")
        val ready = vm.snapshot.a("recipes").objects().firstOrNull(::recipeReady)
        assumeTrue("The copied collection has no ready recipe", ready != null)
        compose.runOnUiThread { vm.selectedRecipe = ready!!.s("id") }
        shot("recipe")
        if (hasTextNode("Mixed")) { click("Mixed"); shot("recipe-amounts"); click("Close") }
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("I cooked this"))
        click("I cooked this"); shot("recipe-report"); click("Later")
        compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Start cooking"))
        shot("recipe-start-and-steps")
        click("Start cooking"); shot("cook-initial-step")
        val substitutions = compose.onAllNodes(hasText("I substituted") and hasClickAction() and isEnabled())
        if (substitutions.fetchSemanticsNodes().isNotEmpty()) { substitutions[0].performScrollTo().performClick(); shot("cook-substitute-picker"); click("Cancel") }
        compose.onNodeWithContentDescription("Ask a question").performScrollTo().performClick(); shot("cook-question"); click("Cancel")
        val start = compose.onAllNodes(hasText("Start step") and hasClickAction())
        val timer = compose.onAllNodes(hasText("Start ", substring = true) and hasText("min timer", substring = true) and hasClickAction())
        if (start.fetchSemanticsNodes().isNotEmpty()) { start[0].performScrollTo().performClick(); shot("cook-started-step") }
        else if (timer.fetchSemanticsNodes().isNotEmpty()) { timer[0].performScrollTo().performClick(); shot("cook-timer-next-step") }
        compose.onNodeWithContentDescription("Leave cooking").performClick()
        resetTo(1)
        val variant = vm.snapshot.a("recipes").objects().firstOrNull { recipeReady(it) && it.s("parent_recipe_id").isNotBlank() }
        if (variant != null) { compose.runOnUiThread { vm.selectedRecipe = variant.s("id") }; compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Changed for this cook")); shot("recipe-variant-changes"); resetTo(1) }
        val candidate = vm.snapshot.a("recipes").objects().firstOrNull { !recipeReady(it) }
        if (candidate != null) {
            compose.onNodeWithTag("recipes-scroll").performScrollToNode(hasTestTag("recipes-not-ready"))
            compose.onNodeWithTag("recipes-not-ready").performClick(); shot("recipes-archive")
        }
        if (candidate != null) { compose.runOnUiThread { vm.selectedRecipe = candidate.s("id") }; shot("recipe-candidate"); resetTo(1) }
    }

    private fun captureAndLog() {
        resetTo(0)
        compose.onNodeWithContentDescription("Capture").performClick(); shot("capture-entry")
        if (hasTextNode("No photo")) click("No photo")
        if (hasTextNode("Add weight")) { click("Add weight"); shot("capture-weight-blank") }
        if (hasTextNode("Discard")) click("Discard") else back()
        previewExistingPhotos()
        resetTo(0)
        compose.runOnUiThread { vm.openFoodLog = true }
        shot("food-log")
        val capture = vm.foodLog().firstOrNull { it.o("server").s("status") == "interpreted" && it.o("server").o("interpretation").s("intakeId").isNotBlank() } ?: vm.foodLog().firstOrNull()
        if (capture != null) {
            val title = capture.o("server").o("interpretation").s("title").ifBlank { capture.s("note").ifBlank { capture.s("kind").replaceFirstChar { it.uppercase() } } }
            if (title.isNotBlank()) {
                lazyScroll(title); click(title); shot("food-log-detail")
                val rating = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
                if (rating.fetchSemanticsNodes().isNotEmpty()) { rating[0].performScrollTo(); shot("food-log-rating") }
            }
        }
        previewCaptureEvidence()
        resetTo(0)
    }

    private fun previewCaptureEvidence() {
        val entries = vm.foodLog()
        val pantry = vm.graphPantry()
        val batches = vm.snapshot.a("batches").objects()
        fun interpretation(entry: JSONObject) = entry.o("server").o("interpretation")
        fun openEntry(entry: JSONObject) {
            resetTo(0)
            compose.runOnUiThread { vm.openFoodLog = true }
            compose.waitForIdle()
            val tag = "food-log-entry-${entry.s("id")}"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
            val node = compose.onNodeWithTag(tag)
            if (node.fetchSemanticsNode().config[SemanticsProperties.StateDescription] != "Expanded") node.performClick()
            compose.waitForIdle()
        }
        entries.firstOrNull { it.o("server").s("status") == "interpreted" &&
            interpretation(it).a("questions").strings().any { question -> question.isNotBlank() }
        }?.let { entry ->
            resetTo(0)
            compose.runOnUiThread { vm.openFoodLog = true }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("food-log-entry-${entry.s("id")}"))
            compose.onNodeWithTag("food-log-question-${entry.s("id")}").performScrollTo().performClick()
            shot("food-log-follow-up")
            compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        }
        entries.firstOrNull { it.o("server").a("details").length() > 0 }?.let { entry ->
            openEntry(entry)
            val detail = entry.o("server").a("details").objects().firstOrNull { it.s("text").isNotBlank() }
            if (detail != null) {
                compose.onAllNodesWithText(detail.s("text"), useUnmergedTree = true)[0].performScrollTo()
                shot("food-log-saved-details")
            }
        }
        entries.firstOrNull { entry ->
            val batch = batches.firstOrNull { it.s("id") == interpretation(entry).s("batchId") }
            batch != null && pantry.any { it.s("id") == batch.s("pantry_item_id") }
        }?.let { entry ->
            openEntry(entry)
            compose.onNodeWithTag("food-log-batch-${interpretation(entry).s("batchId")}")
                .performScrollTo().performClick()
            compose.waitForIdle()
            val panels = compose.onAllNodesWithTag("kitchen-item-panel")
            if (panels.fetchSemanticsNodes().isNotEmpty()) {
                panels[0].performScrollTo(); shot("food-log-linked-batch")
            }
        }
        entries.firstOrNull { entry -> interpretation(entry).a("components").objects().any { component ->
            component.s("productId").isNotBlank() && pantry.any { it.s("product_id") == component.s("productId") }
        } }?.let { entry ->
            val component = interpretation(entry).a("components").objects().first { candidate ->
                candidate.s("productId").isNotBlank() && pantry.any { it.s("product_id") == candidate.s("productId") }
            }
            openEntry(entry)
            compose.onNodeWithTag("food-log-product-${component.s("productId")}")
                .performScrollTo().performClick()
            compose.waitForIdle()
            val panels = compose.onAllNodesWithTag("kitchen-item-panel")
            if (panels.fetchSemanticsNodes().isNotEmpty()) {
                panels[0].performScrollTo(); shot("food-log-linked-product")
            }
        }
        resetTo(0)
    }

    private fun previewExistingPhotos() {
        val media = vm.snapshot.a("captures").objects().flatMap { capture ->
            if (capture.has("mediaList")) capture.a("mediaList").objects() else listOfNotNull(capture.optJSONObject("media"))
        }.filter { Regex("^[a-f0-9]{64}$").matches(it.s("sha256")) }.distinctBy { it.s("sha256") }.take(2)
        if (media.isEmpty()) return
        val vault = Vault(compose.activity.application)
        media.forEachIndexed { index, image ->
            val connection = URL("${vault.endpoint}/api/media/${image.s("sha256")}").openConnection() as HttpURLConnection
            val temporary = File(compose.activity.cacheDir, "interface-preview-source-$index.img")
            try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.setRequestProperty("Authorization", "Bearer ${vault.token}")
                check(connection.responseCode == 200) { "Copied capture photo unavailable" }
                connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                compose.runOnUiThread {
                    if (index == 0) vm.beginPhotoCapture(temporary) else vm.addCapturePhoto(temporary, vm.draftCapture!!.s("id"))
                }
                compose.waitUntil(15_000) { !vm.capturePhotoBusy && vm.draftCapture != null }
            } finally { connection.disconnect(); temporary.delete() }
        }
        shot("capture-existing-photos")
        if (hasTextNode("Add weight")) { click("Add weight"); shot("capture-photos-weight") }
        if (hasTextNode("Discard")) click("Discard") else compose.runOnUiThread { vm.discardCapture() }
    }

    private fun more() {
        listOf("Your app", "Activity", "Receipts", "Preferences", "Connection").forEach { label -> resetTo(4); click(label); shot(label.lowercase().replace(' ', '-')) }
        resetTo(4); click("Ask"); shot("ask")
        val historyLink = listOf("Chat history", "Full chat", "Conversations", "History").firstOrNull(::hasTextNode)
        if (historyLink != null) { click(historyLink); shot("chat-history-entry") }
        resetTo(4); compose.runOnUiThread { vm.openHistory = true }; shot("history")
        resetTo(4); gallery()
    }

    private fun notesAndGallery() {
        resetTo(0)
        compose.onNodeWithContentDescription("Leave an app note").performClick(); shot("note-menu")
        click("Note this screen"); shot("note-draft")
        click("Cancel")
        if (vm.timers.isNotEmpty()) shot("timer-strip")
        gallery()
    }
}
