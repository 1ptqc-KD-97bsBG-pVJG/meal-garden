package app.mealgarden

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Synthetic fixture only. The emulator must be unpaired; writes stay in its local outbox. */
@RunWith(AndroidJUnit4::class)
class GraphAppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: GardenModel
    private val fixture = JSONObject("""{
      "pantry":[
        {"id":"pasta-lot","product_id":"pasta","name":"Whole wheat pasta","base_unit":"g","kind":"packaged","location":"pantry","balance":907.18474,"basis":"known","purchased_on":"2026-10-01","urgency":"stable"},
        {"id":"spinach-lot","product_id":"spinach","name":"Spinach","base_unit":"g","kind":"generic","location":"fridge","balance":null,"basis":"unknown","purchased_on":"2026-10-01","ageDays":3,"typicalDays":5,"urgency":"soon"}
      ],
      "assumptions":[{"id":"a1","statement":"Half the spinach went into soup.","productId":"spinach","source":{"id":"cook-soup","kind":"recipe","title":"Soup"},"evidence":[]},{"id":"a2","statement":"You like ginger.","evidence":["pref1"]}],
      "receipts":[{"id":"receipt","date":"2026-10-01","store":"Test store","items":[{"name":"Whole wheat pasta","quantity":"2 × 16 oz"},{"name":"Spinach","quantity":"1 × 300 g"}]}],
      "preferences":[{"id":"pref1","kind":"taste","subject":"ginger","statement":"Like ginger.","stance":"like","source":"imported","confidence":"assumed"}],"batches":[]
    }""")
    private fun setup(screen: String = "kitchen") {
        compose.runOnUiThread {
            assertTrue("Never test writes against a paired laptop", Vault(compose.activity.application).token.isEmpty())
            compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            vm = GardenModel(compose.activity.application)
            fixture.keys().forEach { vm.snapshot.put(it, fixture.get(it)) }
            File(compose.activity.filesDir, "snapshot.json").writeText(vm.snapshot.toString())
            vm.openFridgeCheck = screen == "kitchen"
            vm.openPreferences = screen == "preferences"
            compose.activity.setContent { GardenTheme { GardenApp(vm) } }
        }
        compose.waitForIdle()
    }
    @After fun clearSyntheticWrites() {
        compose.runOnUiThread {
            vm.prefs.edit().remove("outbox").remove("report-saved:fixture").remove("report-saved:sync-fixture").commit()
            File(compose.activity.filesDir, "snapshot.json").delete()
            Vault(compose.activity.application).token = ""
        }
    }
    private fun shot(name: String) {
        compose.waitForIdle(); Thread.sleep(400)
        val file = File(compose.activity.filesDir, "redesign-screenshots/phase4-$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun scrollTo(text: String) {
        compose.onNodeWithTag("graph-kitchen").performScrollToNode(hasText(text))
    }
    private fun panel(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("kitchen-item-panel")))
    private fun openFood(name: String) {
        scrollTo(name)
        compose.onNode(hasText(name) and hasClickAction()).performClick()
        compose.onNodeWithTag("kitchen-item-panel").performScrollTo()
    }
    @Test fun kitchenAssumptionsAmountsMarksAndReceipts() {
        setup()
        compose.onNodeWithText("Here's what I assumed").assertIsDisplayed()
        shot("kitchen-assumptions")
        compose.onNodeWithText("Half the spinach went into soup.").assertDoesNotExist()
        compose.onNodeWithTag("check-assumption:a1").assertExists().performClick()
        compose.onNodeWithText("Half the spinach went into soup.").assertIsDisplayed()
        compose.onNodeWithText("Mark corrected").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("corrected", vm.outbox.first().o("payload").s("status")) }
        compose.onNodeWithText("Looks right").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(vm.graphAssumptions().isEmpty())
            assertEquals("confirmed", vm.outbox.last().o("payload").s("status"))
        }
        shot("fixture-kitchen-check-zone")
        compose.onNodeWithText("Pantry", ignoreCase = false).performScrollTo().performClick()
        openFood("Whole wheat pasta")
        compose.onNodeWithContentDescription("Less packages").performScrollTo().performClick()
        compose.runOnIdle {
            val write = vm.outbox.last()
            assertEquals("/api/pantry/count", write.s("route"))
            assertEquals("pasta-lot", write.o("payload").s("itemId"))
            assertEquals(453.59237, write.o("payload").getDouble("amount"), .0001)
            assertEquals("known", write.o("payload").s("confidence"))
        }
        shot("kitchen-amount-control")
        panel("Bought ${humanDate("2026-10-01")}").performScrollTo().performClick()
        compose.onNodeWithText("Test store").assertIsDisplayed()
        shot("kitchen-receipt")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Fridge", ignoreCase = false).performScrollTo().performClick()
        openFood("Spinach")
        compose.onNodeWithTag("pantry-count:spinach-lot").performScrollTo().performClick()
        compose.onNodeWithText("g").performScrollTo().performTextInput("150")
        compose.waitForIdle()
        compose.onNodeWithText("g").assertIsDisplayed()
        shot("fixture-kitchen-exact-entry")
        panel("Save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(150.0, vm.outbox.last().o("payload").getDouble("amount"), 0.0)
            assertEquals("known", vm.outbox.last().o("payload").s("confidence"))
        }
        panel("Half").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(150.0, vm.outbox.last().o("payload").getDouble("amount"), 0.0)
            assertEquals("assumed", vm.outbox.last().o("payload").s("confidence"))
        }
        shot("kitchen-four-level-amount")
        panel("Fine").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("/api/pantry/condition", vm.outbox.last().s("route"))
            assertEquals("fine", vm.outbox.last().o("payload").s("condition"))
        }
        val restored = GardenModel(compose.activity.application)
        assertEquals(vm.outbox.size, restored.outbox.size)
        assertEquals(150.0, restored.graphPantry().first { it.s("id") == "spinach-lot" }.getDouble("balance"), 0.0)
    }
    @Test fun cookingCreatesDurableLinkedBatchAndRating() {
        setup("preferences")
        compose.runOnIdle {
            val recipe = JSONObject("""{"id":"test-soup","revision":1,"title":"Soup","yield":{"servings":4},"ingredients":[{"name":"Spinach","amount":100,"unit":"g"},{"name":"Beans","amount":200,"unit":"g"}]}""")
            val counts = j("fridge" to 2, "freezer" to 1, "eatenNow" to 1)
            vm.cooked(recipe, "Made soup", 4, counts, 800.0, j("0" to "Kale", "reason:0" to "Had it"), "fixture", 8, j("flavor" to 8)) {}
            assertEquals(listOf("/api/cooking", "/api/batches", "/api/reactions"), vm.outbox.map { it.s("route") })
            val batch = vm.outbox[1].o("payload")
            assertEquals("test-soup", batch.s("recipeId"))
            assertEquals(800.0, batch.getDouble("yieldG"), 0.0)
            assertEquals("Kale", batch.a("ingredients").objects()[0].s("name"))
            assertTrue(batch.a("ingredients").objects()[0].isNull("amount"))
            assertEquals(200.0, batch.a("ingredients").objects()[1].getDouble("amount"), 0.0)
            assertEquals(batch.s("id"), vm.outbox.last().o("payload").s("batchId"))
            vm.cooked(recipe, "Made soup", 4, counts, 800.0, JSONObject(), "fixture", 8, JSONObject()) {}
            assertEquals(3, vm.outbox.size)
        }
    }
    @Test fun preferencesUseSupersedingGraphRoute() {
        setup("preferences")
        compose.onNodeWithContentDescription("Assumed").assertExists()
        compose.onNodeWithContentDescription("Change preference").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle {
            assertEquals("/api/preferences", vm.outbox.first().s("route"))
            assertEquals("neutral", vm.outbox.first().o("payload").s("stance"))
            assertFalse(vm.outbox.first().o("payload").getBoolean("isHard"))
            assertEquals("/api/assumptions/resolve", vm.outbox.last().s("route"))
            assertEquals("corrected", vm.outbox.last().o("payload").s("status"))
        }
        compose.onNodeWithText("Like ginger.").assertDoesNotExist()
    }
    @Test fun outboxSyncSplitsWeighedBatchAndPersistsNoDuplicateDestinations() {
        setup("preferences")
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    val parts = request.split(" ")
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(":").trim().toInt()
                    }
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < length) { val n = reader.read(chars, offset, length - offset); if (n < 0) break; offset += n }
                    val body = if (length > 0) JSONObject(String(chars)) else JSONObject()
                    val route = parts[1]
                    if (parts[0] == "POST") requests.add(route to body)
                    val response = when (route) {
                        "/api/snapshot" -> fixture.toString()
                        "/api/batches" -> "{\"pantryItem\":{\"id\":\"new-batch-lot\"}}"
                        else -> "{}"
                    }.toByteArray()
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(response); output.flush()
                }
            }
        }
        try {
            compose.runOnUiThread {
                // The only paired endpoint in this test is a synthetic server inside this emulator.
                val vault = Vault(compose.activity.application)
                vault.endpoint = "http://127.0.0.1:${server.localPort}"
                vault.token = "synthetic-test-token"
                vm = GardenModel(compose.activity.application)
                vm.cooked(j("id" to "test-soup", "title" to "Soup", "revision" to 1), "Test cook", 4,
                    j("fridge" to 2, "freezer" to 1, "eatenNow" to 1), 800.0, JSONObject(), "sync-fixture", 7, JSONObject()) {}
            }
            compose.waitUntil(15_000) { requests.any { it.first == "/api/reactions" } && vm.outbox.isEmpty() }
            val writes = requests.filter { it.first != "/api/telemetry" }
            val routes = writes.map { it.first }
            assertEquals(listOf("/api/cooking", "/api/batches", "/api/pantry/transfer", "/api/pantry/count", "/api/reactions"), routes)
            val transfer = requests.first { it.first == "/api/pantry/transfer" }.second
            assertEquals("new-batch-lot", transfer.s("itemId"))
            assertEquals(200.0, transfer.getDouble("amount"), 0.0)
            val count = requests.first { it.first == "/api/pantry/count" }.second
            assertEquals(400.0, count.getDouble("amount"), 0.0)
            assertEquals("assumed", count.s("confidence"))
            assertEquals(5, writes.map { it.second.s("idempotencyKey") }.toSet().size)
        } finally {
            compose.runOnUiThread { vm.disconnect() }
            compose.waitUntil(5_000) { !vm.paired }
            Vault(compose.activity.application).token = ""
            server.close(); worker.join(1000)
        }
    }

    @Test fun foodLogDetailShowsProductMethodAndSavesIntakeRating() {
        setup("food-log")
        compose.runOnUiThread {
            vm.snapshot.put("captures", JSONArray().put(j("id" to "capture-test", "kind" to "meal", "capturedAt" to "2026-10-04T12:00:00-07:00", "status" to "interpreted",
                "interpretation" to j("title" to "Pasta lunch", "category" to "meal", "method" to "computed", "intakeId" to "intake-test",
                    "components" to JSONArray().put(j("productId" to "pasta", "grams" to 80))))))
            compose.activity.setContent { MaterialTheme { FoodLogScreen(vm) } }
        }
        compose.onNodeWithText("Pasta lunch").performClick()
        compose.onNodeWithText("Whole wheat pasta").assertExists()
        compose.onNodeWithText("Computed").assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(9f) }
        compose.onNodeWithText("Flavor").performScrollTo().performClick()
        compose.onNodeWithText("Save rating").performScrollTo().performClick()
        compose.runOnIdle {
            val write = vm.outbox.last()
            assertEquals("/api/reactions", write.s("route"))
            assertEquals("intake-test", write.o("payload").s("intakeId"))
            assertEquals(9, write.o("payload").optInt("rating"))
            assertEquals(9, write.o("payload").o("aspects").optInt("flavor"))
        }
    }

    @Test fun leftoversUseGraphPantryRoutesAndCanBeCorrected() {
        setup()
        compose.runOnUiThread {
            vm.snapshot.put("assumptions", JSONArray())
            vm.snapshot.put("pantry", JSONArray().put(j("id" to "batch-lot", "product_id" to "homemade-soup", "name" to "Soup leftovers", "kind" to "homemade", "base_unit" to "g", "location" to "fridge", "balance" to 400, "basis" to "assumed", "typicalDays" to 4, "ageDays" to 1)))
            vm.snapshot.put("batches", JSONArray().put(j("id" to "batch", "pantry_item_id" to "batch-lot", "title" to "Soup leftovers", "yield_g" to 800, "portions_made" to 4)))
            compose.activity.setContent { GardenTheme { Box(Modifier.systemBarsPadding()) { KitchenContent(vm) } } }
        }
        compose.onNode(hasContentDescription("Soup leftovers", substring = true) and hasClickAction()).performClick()
        panel("2 portions left").performScrollTo().assertExists()
        panel("Move to freezer").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("/api/pantry/transfer", vm.outbox.last().s("route"))
            assertTrue(vm.outbox.last().o("payload").isNull("amount"))
            assertEquals("freezer", vm.outbox.last().o("payload").s("toLocation"))
        }
        panel("Used up").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0.0, vm.outbox.last().o("payload").getDouble("amount"), 0.0) }
        panel("Fine").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("/api/pantry/count", vm.outbox[vm.outbox.lastIndex - 1].s("route"))
            assertTrue(vm.outbox[vm.outbox.lastIndex - 1].o("payload").isNull("amount"))
            assertEquals("/api/pantry/condition", vm.outbox.last().s("route"))
            assertEquals("batch-lot", vm.outbox.last().o("payload").s("itemId"))
            assertEquals("fine", vm.outbox.last().o("payload").s("condition"))
        }
        panel("Set amount").assertExists()
        panel("How much left?").assertExists()
        compose.onNodeWithText("0 g left").assertDoesNotExist()
        shot("kitchen-leftover-correction")
    }

}
