package app.mealgarden

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/** Run explicitly against a temporary companion on port 14783, with adb reverse tcp:14783 tcp:14783. Excluded from default offline tests. */
@RunWith(AndroidJUnit4::class)
class LiveConnectionTest {
 @get:Rule val compose = createAndroidComposeRule<MainActivity>()
 @Test fun pairAndChatThroughCopiedCompanion() {
  val html=URL("http://127.0.0.1:14783/setup").readText()
  val code=Regex("<strong>([0-9]+)</strong>").find(html)!!.groupValues[1]
  compose.onNodeWithContentDescription("Settings").performClick()
  compose.onNodeWithText("Laptop address").performScrollTo().performTextInput("http://127.0.0.1:14783")
  compose.onNodeWithText("8-digit pairing code").performScrollTo().performTextInput(code)
  compose.onNodeWithText("Connect laptop").performScrollTo().performClick()
  val model = androidx.lifecycle.ViewModelProvider(compose.activity)[GardenModel::class.java]
  compose.waitUntil(20000){model.online && !model.busy}
  compose.runOnIdle { org.junit.Assert.assertEquals("", model.error) }
  compose.onNodeWithText("Chat",useUnmergedTree=true).performClick()
  compose.onNodeWithText("What's on your mind?").performTextInput("Say Kitchen connected.")
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
  compose.onNodeWithText("Emulator kitchen").assertIsDisplayed()
 }
}
