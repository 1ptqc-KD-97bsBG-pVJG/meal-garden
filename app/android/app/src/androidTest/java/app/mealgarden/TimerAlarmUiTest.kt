package app.mealgarden

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real alarm activity with fictional local timers on an unpaired, otherwise idle test emulator. */
@RunWith(AndroidJUnit4::class)
class TimerAlarmUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private var scenario: ActivityScenario<TimerAlarmActivity>? = null
    private var originalTimers: String? = null
    private var originalFires: String? = null
    private var armed = false
    private val key = "ui-fixture:rice-timer"
    private val title = "Rice is ready"
    private val detail = "Rest covered for 5 minutes"

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Never run alarm fixtures on a paired emulator", Vault(context).token.isEmpty())
        preferences = context.getSharedPreferences("garden", Context.MODE_PRIVATE)
        originalTimers = preferences.getString("timers", null)
        originalFires = preferences.getString("timerFires", null)
        assertEquals("Alarm fixtures need an idle test emulator", 0, JSONObject(originalTimers ?: "{}").length())
        assertEquals("Do not replace pending timer observations", 0, JSONArray(originalFires ?: "[]").length())
        assertEquals("Do not run a fixture with pending domain writes", 0, JSONArray(preferences.getString("outbox", "[]")).length())
        armed = true
    }

    @After fun cleanup() {
        if (!armed) return
        try { cancelTimer(context, key) } finally {
            try { scenario?.close() } finally {
                preferences.edit().apply {
                    if (originalTimers == null) remove("timers") else putString("timers", originalTimers)
                    if (originalFires == null) remove("timerFires") else putString("timerFires", originalFires)
                }.commit()
            }
        }
    }

    private fun launchFixture() {
        val timer = j("title" to title, "detail" to detail, "minutes" to 5,
            "deadline" to System.currentTimeMillis() - 1000)
        assertTrue(preferences.edit().putString("timers", j(key to timer).toString()).putString("timerFires", "[]").commit())
        scenario = ActivityScenario.launch<TimerAlarmActivity>(alarmScreenIntent(context, key, title, detail))
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(detail).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("+1 min").assertIsDisplayed()
        compose.onNodeWithText("+5 min").assertIsDisplayed()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(350)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val file = File(context.filesDir, "redesign-screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun actualAlarmDisplaysContextAndDoneOrSnoozeUpdatesSharedTimerState() {
        launchFixture()
        shot("phase8-timer-alarm")
        compose.onNodeWithText("Done").performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(JSONObject(preferences.getString("timers", "{}")).has(key))
        val done = JSONArray(preferences.getString("timerFires", "[]")).getJSONObject(0)
        assertEquals(key, done.s("key")); assertEquals("done", done.s("action"))
        scenario?.close(); scenario = null

        launchFixture()
        val before = System.currentTimeMillis()
        try {
            compose.onNodeWithText("+1 min").performClick()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val after = System.currentTimeMillis()
            val snoozed = JSONObject(preferences.getString("timers", "{}")).getJSONObject(key)
            assertEquals(title, snoozed.s("title")); assertEquals(detail, snoozed.s("detail"))
            assertTrue(snoozed.getLong("deadline") in (before + 60000)..(after + 60000))
            val action = JSONArray(preferences.getString("timerFires", "[]")).getJSONObject(0)
            assertEquals(key, action.s("key")); assertEquals("snooze_1", action.s("action"))
        } finally {
            // The fixture exercises actual scheduling, then cancels immediately so no alarm survives the test.
            cancelTimer(context, key)
        }
        assertEquals(0, JSONArray(preferences.getString("outbox", "[]")).length())
        assertTrue(Vault(context).token.isEmpty())
    }
}
