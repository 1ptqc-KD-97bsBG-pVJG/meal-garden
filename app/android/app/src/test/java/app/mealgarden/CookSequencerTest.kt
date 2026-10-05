package app.mealgarden

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CookSequencerTest {
    @Test fun containerLimitPreservesReviewedCapacityAndOtherBatchDefaults() {
        val jar = JSONObject().put("yield_notes", "Two portions in a 46 fl oz jar").put("max_portions", 2)
        assertEquals(2, defaultCookPortions(jar))
        assertEquals(2, maxCookPortions(jar))
        assertEquals(4, defaultCookPortions(JSONObject().put("yield_notes", "Two bowls")))
        assertEquals(5, defaultCookPortions(JSONObject().put("yield_notes", "5 breakfasts")))
    }

    @Test fun batchYieldDoesNotConfusePlannedDinnersWithPortionsMade() {
        assertEquals(4, basePortions(JSONObject().put("yield_notes", "Four filling rice bowls; two planned dinners and two flexible portions.")))
        assertEquals(2, basePortions(JSONObject().put("yield_notes", "Two portions in a 46 fl oz jar.")))
        assertEquals(5, basePortions(JSONObject().put("yield_notes", "5 breakfasts, plus optional toppings.")))
    }
    @Test fun scaledIngredientMeasuresPreserveFractionsAndApplianceSettings() {
        val recipe = JSONObject().put("yield_notes", "Two portions")
        val original = JSONObject().put("text", "Mix 1 1/2 cups yogurt, 1/3 cup oats and 1½ tsp oil. Cook at Power 2 for 10 minutes.")
        assertEquals("Mix 3 cups yogurt, ⅔ cup oats and 3 tsp oil. Cook at Power 2 for 10 minutes.", scaledCookStep(recipe, original, 4).getString("text"))
        assertEquals(2, basePortions(JSONObject().put("yield_notes", "One full 14–16 oz block; roughly two substantial side portions.")))
    }
    @Test fun brownedEdgesDoNotImplyBrownRiceInTheSkillet() {
        val recipe = JSONObject().put("ingredients", JSONArray("[{\"id\":\"rice\",\"name\":\"Brown rice\"},{\"id\":\"cabbage\",\"name\":\"Green cabbage\"},{\"id\":\"carrots\",\"name\":\"Carrots\"}]"))
        assertEquals(listOf(1, 2), stepIngredients(recipe, JSONObject().put("text", "Cook cabbage and carrots until tender with browned edges.")))
    }
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "tests/fixtures/cook-sequencer-vectors.json").exists() }
    private fun step(s: JSONObject) = CookStep(
        if (s.has("minutes")) s.getDouble("minutes") else null,
        s.optDouble("passive_minutes", 0.0),
        if (s.has("start_minute")) s.getDouble("start_minute") else null, s.optString("equipment"), s.optString("text"),
        if (s.has("timer_minutes")) s.getDouble("timer_minutes") else null,
    )
    private fun running(value: JSONObject) = CookRunning(value.getInt("step"), value.getLong("endsAt"))
    private fun optionalIndex(o: JSONObject, key: String) = if (o.isNull(key)) null else o.getInt(key)
    @Test fun sharedVectors() {
        val vectors = JSONArray(File(root(), "tests/fixtures/cook-sequencer-vectors.json").readText())
        for (caseIndex in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(caseIndex)
            val jsonSteps = vector.getJSONArray("steps")
            val steps = (0 until jsonSteps.length()).map { step(jsonSteps.getJSONObject(it)) }
            val states = MutableList(steps.size) { CookProgress() }
            var clock = 0L
            val actions = vector.getJSONArray("actions")
            for (actionIndex in 0 until actions.length()) {
                val action = actions.getJSONObject(actionIndex)
                when (action.getString("type")) {
                    "advance" -> clock = action.getLong("time")
                    "start" -> { val i = action.getInt("step"); states[i] = CookProgress(startedAt = clock) }
                    "finish" -> { val i = action.getInt("step"); states[i] = states[i].copy(doneAt = clock) }
                    else -> error("Unknown action: $action")
                }
                val expected = action.getJSONObject("expected")
                val jsonRunning = expected.getJSONArray("running")
                val wanted = CookSequence(optionalIndex(expected, "now"),
                    (0 until jsonRunning.length()).map { running(jsonRunning.getJSONObject(it)) }, optionalIndex(expected, "next"),
                    if (expected.isNull("waitingOn")) null else running(expected.getJSONObject("waitingOn")))
                assertEquals("${vector.getString("name")} action $actionIndex", wanted, CookSequencer.sequence(steps, states, clock))
            }
        }
        assertTrue("Shared vectors must cover meaningful cases", vectors.length() >= 6)
    }
    @Test fun readyRecipesFinishWithoutDeadlock() {
        File(root(), "sample-household/recipes").listFiles()!!.forEach { directory ->
            val file = File(directory, "recipe.json")
            if (!file.exists()) return@forEach
            val recipe = JSONObject(file.readText())
            if (recipe.optString("readiness") != "ready") return@forEach
            val jsonSteps = recipe.getJSONArray("steps")
            val steps = (0 until jsonSteps.length()).map { step(jsonSteps.getJSONObject(it)) }
            val states = MutableList(steps.size) { CookProgress() }; var clock = 0L
            repeat(steps.size * 4 + 1) {
                val result = CookSequencer.sequence(steps, states, clock)
                if (result.now != null) {
                    val i = result.now
                    states[i] = if (states[i].startedAt == null) CookProgress(startedAt = clock) else states[i].copy(doneAt = clock)
                } else if (result.running.isNotEmpty()) clock = result.running.first().endsAt
            }
            val result = CookSequencer.sequence(steps, states, clock)
            assertNull(recipe.getString("id"), result.now)
            assertTrue(recipe.getString("id"), result.running.isEmpty())
            assertTrue(recipe.getString("id"), states.indices.all { states[it].doneAt != null || CookSequencer.end(steps[it], states[it])?.let { end -> steps[it].passiveMinutes > 0 && end <= clock } == true })
        }
    }
}
