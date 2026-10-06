package app.mealgarden

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

private fun normalizedFoodName(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
private val foodAliases = foodIconCatalog.filter { it.id != "food-unknown" }.flatMap { entry -> entry.aliases.map { normalizedFoodName(it) to entry } }
    .sortedWith(compareBy<Pair<String, FoodIconEntry>> { it.second.fallback }.thenBy { it.first in it.second.weakAliases }.thenByDescending { it.first.length })

/** The same stable longest whole-phrase match as design-lab/icons/match.js. */
fun foodIconId(name: String): String {
    val words = " ${normalizedFoodName(name)} "
    return foodAliases.firstOrNull { words.contains(" ${it.first} ") }?.second?.id ?: "food-unknown"
}

@Composable
fun FoodIcon(id: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val entry = foodIconCatalog.firstOrNull { it.id == id } ?: foodIconCatalog.last()
    Icon(ImageVector.vectorResource(entry.drawable), contentDescription, modifier, tint = Color.Unspecified)
}

@Composable
fun ingredientIcon(a: JSONObject, fallback: ImageVector? = null): ImageVector {
    val entry = foodIconCatalog.first { it.id == foodIconId(a.s("name")) }
    return ImageVector.vectorResource(entry.drawable)
}

/** Recipe pictures show main foods, keeping seasoning and drink ingredients in their rows. */
fun recipePlateIconIds(recipe: JSONObject): List<String> {
    val ids = recipe.a("ingredients").objects().map { foodIconId(it.s("name")) }.distinct()
    val foods = ids.filter { id -> foodIconCatalog.first { it.id == id }.category !in setOf("sauce", "drink", "supplement", "unknown") && id !in setOf("food-basil", "food-oregano", "food-garlic", "food-ginger") }
    return foods.ifEmpty { ids }.take(5).ifEmpty { listOf("food-unknown") }
}


/** Conservative packaged-food display helper; callers must exclude dishes and batches. */
fun foodShortLabel(name: String): String? {
    if (normalizedFoodName(name).split(' ').size < 5 ||
        Regex("\\b(?:curry|bowl|salad|soup|stew|sandwich|wrap|dish|meal|recipe|with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return null
    val id = foodIconId(name)
    val entry = foodIconCatalog.first { it.id == id }
    if (entry.fallback) return null
    val core = normalizedFoodName(entry.name).split(' ').map { it.removeSuffix("s") }
    val normalizedSource = " ${normalizedFoodName(name)} "
    return entry.aliases.asSequence().filter { alias ->
        val normalized = normalizedFoodName(alias)
        val words = normalized.split(' ').map { it.removeSuffix("s") }
        alias !in entry.weakAliases && core.all { it in words } && normalizedSource.contains(" $normalized ") && normalized.length <= 32
    }.sortedByDescending { normalizedFoodName(it).length }.firstNotNullOfOrNull { alias ->
        val phrase = normalizedFoodName(alias).split(' ').joinToString("[\\s\\p{P}]+") { Regex.escape(it) }
        Regex("(?<![\\p{L}\\p{N}])$phrase(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).find(name)?.value
    }
}
