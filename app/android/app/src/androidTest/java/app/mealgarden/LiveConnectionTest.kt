package app.mealgarden

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.After
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/** Temporary copied companion only: adb reverse tcp:14783 tcp:14783 and -e temporaryCompanionPort 14783. Excluded from default offline tests. */
@RunWith(AndroidJUnit4::class)
class LiveConnectionTest {
 @get:Rule val compose = createAndroidComposeRule<MainActivity>()
 private val temporaryEndpoint = "http://127.0.0.1:14783"
 private var ownsTestConnection = false
 @Before fun requireTemporaryCompanion() {
  org.junit.Assert.assertEquals("Opt in with -e temporaryCompanionPort 14783", "14783", InstrumentationRegistry.getArguments().getString("temporaryCompanionPort"))
  compose.runOnUiThread {
   org.junit.Assert.assertTrue("Never replace an existing pairing", Vault(compose.activity.application).token.isEmpty())
   ownsTestConnection = true
   compose.activity.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE).edit().clear().commit()
  }
 }
 @After fun unpairTemporaryConnection() {
  if (!ownsTestConnection) return
  val model = androidx.lifecycle.ViewModelProvider(compose.activity)[GardenModel::class.java]
  if (model.vault.token.isNotBlank()) org.junit.Assert.assertEquals("Only revoke the owned test pairing", temporaryEndpoint, model.vault.endpoint)
  try {
   if (model.vault.token.isNotBlank()) {
    compose.runOnUiThread { model.disconnect() }
    compose.waitUntil(20000) { !model.paired && model.vault.token.isEmpty() }
   }
  } finally {
   compose.runOnUiThread {
    // The token-empty preflight above authorizes cleanup only of this test's local connection.
    if (model.vault.token.isEmpty() || model.vault.endpoint == temporaryEndpoint) {
     model.vault.token = ""
     compose.activity.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE).edit().clear().commit()
     java.io.File(compose.activity.filesDir, "snapshot.json").delete()
    }
   }
  }
  org.junit.Assert.assertTrue(Vault(compose.activity.application).token.isEmpty())
 }
 @Test fun pairAndChatThroughCopiedCompanion() {
  val html=URL("http://127.0.0.1:14783/setup").readText()
  val code=Regex("<strong>([0-9]+)</strong>").find(html)!!.groupValues[1]
  compose.onNodeWithContentDescription("Settings").performClick()
  compose.onNodeWithText("Laptop address").performScrollTo().performTextClearance()
  compose.onNodeWithText("Laptop address").performTextInput(temporaryEndpoint)
  compose.onNodeWithText("8-digit pairing code").performScrollTo().performTextInput(code)
  compose.onNodeWithText("Connect laptop").performScrollTo().performClick()
  val model = androidx.lifecycle.ViewModelProvider(compose.activity)[GardenModel::class.java]
  compose.waitUntil(20000){model.online && !model.busy}
  compose.runOnIdle { org.junit.Assert.assertEquals("", model.error) }
  compose.onNodeWithTag("nav:More").performClick()
  compose.onNodeWithText("Ask").performClick()
  compose.onNodeWithText("Full chat").performClick()
  compose.onNodeWithTag("chat-history").assertIsDisplayed()
  compose.onNodeWithTag("chat-input").performTextInput("Say Kitchen connected.")
  compose.runOnIdle {
   val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
   input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
  }
  compose.waitForIdle()
  compose.onNodeWithContentDescription("Send").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
  compose.waitUntil(5000) { model.draft.isEmpty() || model.error.isNotBlank() }
  compose.waitUntil(20000){model.error.isNotBlank() || compose.onAllNodesWithText("Kitchen connected.").fetchSemanticsNodes().isNotEmpty()}
  compose.runOnIdle { org.junit.Assert.assertEquals("", model.error) }
  compose.onNodeWithText("Kitchen connected.").assertIsDisplayed()
  compose.onNodeWithContentDescription("Settings").performClick()
  compose.onNodeWithText(model.kitchenName).assertIsDisplayed()
  compose.runOnIdle { org.junit.Assert.assertEquals(temporaryEndpoint, model.vault.endpoint); org.junit.Assert.assertTrue(model.paired) }
 }
}
