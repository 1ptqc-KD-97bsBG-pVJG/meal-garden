package app.mealgarden

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GardenUiTest {
 @get:Rule val compose = createAndroidComposeRule<MainActivity>()
 @Test fun offlineRecipeCookingAndFreshSession() {
  compose.activity.getSharedPreferences("garden", android.content.Context.MODE_PRIVATE).edit().clear().commit()
  compose.onNodeWithText("Recipes", useUnmergedTree=true).performClick()
  compose.onNodeWithText("Find a dish or ingredient").assertIsDisplayed()
  compose.onNodeWithText("Find a dish or ingredient").performTextInput("Lentil Tomato")
  compose.onNodeWithText("Lentil Tomato Pot").performClick()
  compose.onNodeWithTag("recipe-scroll").performScrollToNode(hasText("Start cooking"))
  compose.onNodeWithText("Start cooking").performClick()
  compose.onNodeWithText("STEP 1 OF 5").assertIsDisplayed()
  compose.onNodeWithText("Start step").performScrollTo().performClick()
  compose.onNodeWithText("Finish step").performScrollTo().performClick()
  compose.onNodeWithText("STEP 2 OF 5").assertIsDisplayed()
  compose.onNodeWithContentDescription("Leave cooking").performClick()
  compose.onNodeWithText("Start cooking").performScrollTo().performClick()
  compose.onNodeWithText("STEP 2 OF 5").assertIsDisplayed()
 }
 @Test fun tabsAndQuestionComposerWorkOffline() {
  compose.onNodeWithTag("nav:More").performClick()
  compose.onNodeWithText("Ask").performClick()
  compose.onNodeWithTag("ask-answers").assertIsDisplayed()
  compose.onNodeWithText("What can I make without shopping?").performClick()
  compose.onNodeWithTag("chat-input").assertTextContains("What can I make without shopping?")
  compose.onNodeWithText("Full chat").performClick()
  compose.onNodeWithTag("chat-history").assertIsDisplayed()
  compose.onNodeWithTag("ask-answers").assertDoesNotExist()
  compose.onNodeWithContentDescription("Conversation history").performClick()
  compose.onNodeWithTag("conversation-history").assertIsDisplayed()
  compose.onNodeWithContentDescription("Back").performClick()
  compose.onNodeWithTag("chat-history").assertIsDisplayed()
  compose.onNodeWithTag("nav:Kitchen").performClick()
  compose.onAllNodesWithText("Kitchen").onFirst().assertIsDisplayed()
  compose.onNodeWithTag("nav:More").performClick()
  compose.onNodeWithText("Receipts").performClick()
  compose.onNodeWithTag("garden-receipts").assertIsDisplayed()
  compose.onNodeWithContentDescription("Import receipt photo").assertIsDisplayed()
 }
 @Test fun fieldNoteCanBeSavedWithoutStartingAChat() {
  compose.onNodeWithContentDescription("Leave an app note").performClick()
  compose.onNodeWithText("Note this screen").performClick()
  compose.onNodeWithText("What should we revisit?").performTextInput("The date on this plan was confusing")
  compose.onNodeWithText("Save note").performClick()
  compose.onNodeWithContentDescription("Leave an app note").performClick()
  compose.onNode(hasText("Review", substring = true)).performClick()
  compose.onNodeWithText("The date on this plan was confusing").assertIsDisplayed()
 }
}
