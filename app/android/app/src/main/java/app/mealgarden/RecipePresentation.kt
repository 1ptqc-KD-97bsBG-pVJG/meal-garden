package app.mealgarden

import org.json.JSONObject

/** Change names only. A manual swap is evidence, never an executable timing revision. */
fun presentedCookStep(recipe: JSONObject, step: JSONObject, portions: Int, substitutions: JSONObject): JSONObject {
    val scaled = scaledCookStep(recipe, step, portions)
    val replacements = recipe.a("ingredients").objects().mapIndexedNotNull { index, ingredient ->
        substitutions.optString("$index").takeIf { it.isNotBlank() }?.let { ingredient.s("name") to it }
    }.sortedByDescending { it.first.length }
    if (replacements.isEmpty()) return scaled
    // A single regex pass prevents replacement names from being rewritten as another ingredient.
    val pattern = Regex(replacements.joinToString("|", "(?i)(?<![\\p{L}\\p{N}])(?:", ")(?![\\p{L}\\p{N}])") { Regex.escape(it.first) })
    fun present(value: String) = pattern.replace(value) { match -> replacements.first { it.first.equals(match.value, ignoreCase = true) }.second }
    return JSONObject(scaled.toString()).put("text", present(scaled.s("text"))).put("title", present(scaled.s("title")))
}

fun stepHasSubstitution(recipe: JSONObject, originalStep: JSONObject, substitutions: JSONObject): Boolean =
    stepIngredients(recipe, originalStep).any { substitutions.optString("$it").isNotBlank() }

/** Scale reciprocal batch shares in distribution clauses; reserved quantities retain their role. */
fun portionDivisionText(value: String, portions: Int, originalPortions: Int = 4): String {
    val container = "(?:containers?|bowls?|portions?|servings?|meals?|jars?|plates?|boxes?)"
    if (!Regex("\\b$container\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)) return value
    val reciprocal = when (portions) { 1 -> "all"; 2 -> "half"; 3 -> "one third"; 4 -> "one quarter"; else -> "1/$portions" }
    val originalShare = when (originalPortions) {
        2 -> "(?:one[- ]half|a half|half|1/2|½)"
        3 -> "(?:one[- ]third|a third|1/3|⅓)"
        4 -> "(?:one[- ]quarter|one[- ]fourth|a quarter|1/4|¼)"
        5 -> "(?:one[- ]fifth|1/5)"
        6 -> "(?:one[- ]sixth|1/6)"
        7 -> "(?:one[- ]seventh|1/7)"
        8 -> "(?:one[- ]eighth|1/8|⅛)"
        else -> "1/$originalPortions"
    }
    return Regex("(?<![\\p{L}\\p{N}/])$originalShare\\s+(?:of\\s+)?(?=the\\b)", RegexOption.IGNORE_CASE)
        .replace(value) { match ->
            val sentenceStart = value.substring(0, match.range.first).indexOfLast { it in ".!?\n" } + 1
            val sentenceEnd = value.indexOfAny(charArrayOf('.', '!', '?', '\n'), match.range.last + 1).takeIf { it >= 0 } ?: value.length
            val sentence = value.substring(sentenceStart, sentenceEnd)
            val relative = match.range.first - sentenceStart
            // Role boundaries apply locally: an earlier "each" cannot turn a later reserve into a serving.
            val boundaries = Regex("[,:;]|\\b(?:and|but|then)\\b", RegexOption.IGNORE_CASE).findAll(sentence).toList()
            val clauseStart = boundaries.lastOrNull { it.range.last < relative }?.range?.last?.plus(1) ?: 0
            val clauseEnd = boundaries.firstOrNull { it.range.first > relative }?.range?.first ?: sentence.length
            val clause = sentence.substring(clauseStart, clauseEnd)
            val beforeShare = sentence.substring(clauseStart, relative)
            val reserved = Regex("\\b(?:reserv(?:e[ds]?|ing)|sav(?:e[ds]?|ing)|retain(?:s|ed|ing)?|keep(?:s|ing)?|kept|set(?:ting)? aside|hold(?:ing)? back|leave|leaving)\\b|\\b(?:aside|for later|for another)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(beforeShare) || Regex("\\b(?:aside|for later|for another)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
            val perContainer = Regex("\\b(?:each|every|per)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
            val prior = sentence.substring(0, relative)
            val distribution = Regex("\\b(?:divide|split|distribute|portion|allocate|share|fill|pack|assemble)\\b", RegexOption.IGNORE_CASE).containsMatchIn(prior) &&
                Regex("\\b$portions\\s+$container\\b", RegexOption.IGNORE_CASE).containsMatchIn(prior)
            val appliesToContainer = beforeShare.isBlank() ||
                Regex("\\b(?:top|cover|fill|put|putting|place|spoon|scoop|ladle|pack|pour|add|layer|give|serve|portion|divide|split|distribute|allocate|share)\\b|^\\s*(?:with|using|containing)\\b", RegexOption.IGNORE_CASE).containsMatchIn(beforeShare)
            if (!reserved && (perContainer || distribution && appliesToContainer)) "$reciprocal of " else match.value
        }
}
