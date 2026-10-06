package app.mealgarden

import org.json.JSONObject

/** Shared UI gate; candidates and malformed executable records remain visible in the archive. */
internal fun recipeReady(recipe: JSONObject): Boolean {
    val rawIngredients = recipe.a("ingredients")
    val rawSteps = recipe.a("steps")
    val ingredients = rawIngredients.objects()
    val steps = rawSteps.objects()
    if (recipe.s("readiness") != "ready" || ingredients.isEmpty() || steps.isEmpty() ||
        ingredients.size != rawIngredients.length() || steps.size != rawSteps.length()) return false
    return ingredients.all { ingredient ->
        ingredient.opt("name") is String && ingredient.s("name").isNotBlank() && when (val amount = ingredient.opt("amount")) {
            is Number -> amount.toDouble().let { it.isFinite() && it >= 0 }
            is String -> amount.isNotBlank() && (amount.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } ?: true)
            else -> false
        }
    } && steps.all { step ->
        step.opt("text") is String && step.s("text").isNotBlank() &&
            listOf("minutes", "start_minute", "passive_minutes", "timer_minutes").all { key ->
                !step.has(key) || !step.isNull(key) && step.optDouble(key, Double.NaN).let { it.isFinite() && it >= 0 }
            } && step.optDouble("passive_minutes", 0.0) <= step.optDouble("minutes", 0.0)
    }
}
