@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.mealgarden

import android.app.Activity
import android.content.SharedPreferences
import android.app.NotificationManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

fun cookStep(s: JSONObject) = CookStep(
    if (s.has("minutes")) s.optDouble("minutes") else null,
    s.optDouble("passive_minutes", 0.0),
    if (s.has("start_minute")) s.optDouble("start_minute") else null,
    s.s("equipment"), s.s("text"), if (s.has("timer_minutes")) s.optDouble("timer_minutes") else null,
)
fun cookDay(vm: GardenModel) = LocalDate.now(runCatching { ZoneId.of(vm.snapshot.o("household").s("timezone", "UTC")) }.getOrDefault(ZoneOffset.UTC)).toString()
/** Scale only ingredient measures and explicit portion counts; never appliance settings or times. */
fun scaledCookStep(r: JSONObject, step: JSONObject, portions: Int): JSONObject {
    val factor = portions.toDouble() / basePortions(r)
    if (factor == 1.0) return step
    val glyphs = mapOf('⅛' to .125, '¼' to .25, '⅓' to (1.0 / 3), '½' to .5, '⅔' to (2.0 / 3), '¾' to .75)
    fun quantity(raw: String): Double {
        val last = raw.last()
        if (last in glyphs) return (raw.dropLast(1).toDoubleOrNull() ?: 0.0) + glyphs.getValue(last)
        val parts = raw.split(Regex("\\s+"))
        return parts.sumOf { part ->
            if ('/' in part) part.substringBefore('/').toDouble() / part.substringAfter('/').toDouble() else part.toDouble()
        }
    }
    val perContainer = Regex("\\bin each\\b", RegexOption.IGNORE_CASE).containsMatchIn(step.s("text"))
    val text = if (perContainer) step.s("text") else Regex("(?<![\\w./–-])([0-9]+\\s+[0-9]+/[0-9]+|[0-9]+/[0-9]+|[0-9]+(?:[.][0-9]+)?[⅛¼⅓½⅔¾]?|[⅛¼⅓½⅔¾])\\s+(cups?|tablespoons?|teaspoons?|tbsp|tsp|oz|grams?|g|cans?|bunch(?:es)?|medium|heads?)\\b", RegexOption.IGNORE_CASE)
        .replace(step.s("text")) { match ->
            val number = quantity(match.groupValues[1]) * factor
            "${amountText(String.format(java.util.Locale.US, "%.3f", number).trimEnd('0').trimEnd('.'))} ${match.groupValues[2]}"
        }
    val original = basePortions(r)
    val word = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight").getOrNull(original)
    val scaled = Regex("\\b(?:$original${word?.let { "|$it" }.orEmpty()}) (?=(?:portions|bowls|meals|containers)\\b)", RegexOption.IGNORE_CASE).replace(text, "$portions ")
    return JSONObject(step.toString()).put("text", scaled)
}
fun stepSentence(s: JSONObject): String = s.s("text").trim().split(Regex("(?<=[.!?])\\s+(?=[A-Z])")).firstOrNull()
    ?.takeIf { it.isNotBlank() } ?: s.s("title").trim().let { if (it.endsWith('.')) it else "$it." }
fun stepSetting(s: JSONObject): String = listOf(s.s("equipment"), s.s("setting", s.s("settings")),
    Regex("(?:Duxtop\\s+)?Power\\s+[0-9]+(?:[.][0-9]+)?(?:[–−-][0-9.]+)?|[0-9]+\\s*°[FC]|(?:medium|low|high)(?:[- ](?:low|high))? heat", RegexOption.IGNORE_CASE)
        .findAll(s.s("text")).joinToString(" · ") { it.value }).filter { it.isNotBlank() }.distinct().joinToString(" · ")
fun stepDoneness(s: JSONObject): String = s.s("done_when", s.s("doneness_cue")).ifBlank {
    Regex("\\buntil\\s+([^.!?]+)", RegexOption.IGNORE_CASE).find(s.s("text"))?.groupValues?.get(1).orEmpty()
}
@Composable
fun stepActivity(s: JSONObject): Pair<String, ImageVector> {
    val text = (s.s("title") + " " + s.s("text")).lowercase()
    return when {
        "microwave" in text -> "Microwave" to ImageVector.vectorResource(R.drawable.mg_act_microwave)
        "press tofu" in text -> "Press tofu" to ImageVector.vectorResource(R.drawable.mg_act_press)
        "roast" in text || "bake" in text -> "Roast" to ImageVector.vectorResource(R.drawable.mg_act_roast)
        "boil" in text -> "Boil" to ImageVector.vectorResource(R.drawable.mg_act_boil)
        "preheat" in text || "heat " in text || "cook " in text || "boil" in text -> "Heat" to ImageVector.vectorResource(R.drawable.mg_act_heat)
        "chop" in text || "slice" in text || "cut " in text || "shred" in text -> "Chop" to ImageVector.vectorResource(R.drawable.mg_act_chop)
        "mix" in text || "stir" in text || "whisk" in text -> "Mix" to ImageVector.vectorResource(R.drawable.mg_act_mix)
        "wait" in text || "rest" in text -> "Wait" to ImageVector.vectorResource(R.drawable.mg_act_wait)
        "pack" in text || "divide" in text -> "Pack" to ImageVector.vectorResource(R.drawable.mg_act_assemble)
        else -> "Prepare" to ImageVector.vectorResource(R.drawable.mg_act_wash)
    }
}
@Composable
fun ingredientIcon(a: JSONObject, fallback: ImageVector? = null): ImageVector {
    val name = a.s("name").lowercase()
    val matches = listOf(
        "almondmilk" to R.drawable.mg_food_milk,
        "almond milk" to R.drawable.mg_food_milk,
        "oatmilk" to R.drawable.mg_food_milk,
        "oat milk" to R.drawable.mg_food_milk,
        "soymilk" to R.drawable.mg_food_soy_milk,
        "prego tomato sauce with olive oil and garlic" to R.drawable.mg_food_tomato_sauce,
        "prego pasta sauce garlic and black pepper" to R.drawable.mg_food_tomato_sauce,
        "roasted garlic ginger soy stir fry sauce" to R.drawable.mg_food_stir_fry_sauce,
        "preferred sesame or bowl dipping sauce" to R.drawable.mg_food_dressing,
        "coarse ground black pepper" to R.drawable.mg_food_pepper,
        "halves and pieces walnuts" to R.drawable.mg_food_walnut,
        "ginger soy stir fry sauce" to R.drawable.mg_food_stir_fry_sauce,
        "traditional italian sauce" to R.drawable.mg_food_tomato_sauce,
        "herb marinade or dressing" to R.drawable.mg_food_dressing,
        "raw and unfiltered honey" to R.drawable.mg_food_honey,
        "japanese barbecue sauce" to R.drawable.mg_food_teriyaki,
        "extra virgin olive oil" to R.drawable.mg_food_olive_oil,
        "long grain brown rice" to R.drawable.mg_food_rice,
        "whole wheat spaghetti" to R.drawable.mg_food_spaghetti,
        "whole grain spaghetti" to R.drawable.mg_food_spaghetti,
        "whole roasted chicken" to R.drawable.mg_food_chicken,
        "roasted sesame seeds" to R.drawable.mg_food_sesame,
        "unsweetened soy milk" to R.drawable.mg_food_soy_milk,
        "low sodium soy sauce" to R.drawable.mg_food_soy_sauce,
        "cauliflower florets" to R.drawable.mg_food_cauliflower,
        "triple berry medley" to R.drawable.mg_food_berries,
        "herb marinated tofu" to R.drawable.mg_food_tofu,
        "neutral cooking oil" to R.drawable.mg_food_cooking_oil,
        "apple cider vinegar" to R.drawable.mg_food_vinegar,
        "rotisserie chicken" to R.drawable.mg_food_chicken,
        "beans and lentils" to R.drawable.mg_food_lentil,
        "whole grain penne" to R.drawable.mg_food_pasta,
        "low sodium tamari" to R.drawable.mg_food_soy_sauce,
        "broccoli florets" to R.drawable.mg_food_broccoli,
        "english cucumber" to R.drawable.mg_food_cucumber,
        "cannellini beans" to R.drawable.mg_food_cannellini,
        "hemp seed hearts" to R.drawable.mg_food_hemp,
        "milk alternative" to R.drawable.mg_food_milk,
        "regular cinnamon" to R.drawable.mg_food_cinnamon,
        "ground flaxseed" to R.drawable.mg_food_flax,
        "extra firm tofu" to R.drawable.mg_food_tofu,
        "super firm tofu" to R.drawable.mg_food_tofu,
        "broccoli crown" to R.drawable.mg_food_broccoli,
        "teriyaki sauce" to R.drawable.mg_food_teriyaki,
        "barbecue sauce" to R.drawable.mg_food_teriyaki,
        "stir fry sauce" to R.drawable.mg_food_stir_fry_sauce,
        "green cabbage" to R.drawable.mg_food_cabbage,
        "garlic cloves" to R.drawable.mg_food_garlic,
        "black lentils" to R.drawable.mg_food_lentil,
        "mixed berries" to R.drawable.mg_food_berries,
        "walnut halves" to R.drawable.mg_food_walnut,
        "whole almonds" to R.drawable.mg_food_almond,
        "flaxseed meal" to R.drawable.mg_food_flax,
        "nonfat yogurt" to R.drawable.mg_food_yogurt,
        "vegetable oil" to R.drawable.mg_food_cooking_oil,
        "dipping sauce" to R.drawable.mg_food_dressing,
        "dried oregano" to R.drawable.mg_food_oregano,
        "baby spinach" to R.drawable.mg_food_spinach,
        "coleslaw mix" to R.drawable.mg_food_cabbage,
        "green onions" to R.drawable.mg_food_green_onion,
        "fresh ginger" to R.drawable.mg_food_ginger,
        "string beans" to R.drawable.mg_food_green_bean,
        "lentil beans" to R.drawable.mg_food_lentil,
        "jasmine rice" to R.drawable.mg_food_rice,
        "sesame seeds" to R.drawable.mg_food_sesame,
        "plain yogurt" to R.drawable.mg_food_yogurt,
        "greek yogurt" to R.drawable.mg_food_yogurt,
        "tomato sauce" to R.drawable.mg_food_tomato_sauce,
        "rice vinegar" to R.drawable.mg_food_vinegar,
        "ground cumin" to R.drawable.mg_food_cumin,
        "black pepper" to R.drawable.mg_food_pepper,
        "cauliflower" to R.drawable.mg_food_cauliflower,
        "green onion" to R.drawable.mg_food_green_onion,
        "ginger root" to R.drawable.mg_food_ginger,
        "green beans" to R.drawable.mg_food_green_bean,
        "string bean" to R.drawable.mg_food_green_bean,
        "white beans" to R.drawable.mg_food_cannellini,
        "black beans" to R.drawable.mg_food_black_bean,
        "rolled oats" to R.drawable.mg_food_oats,
        "short pasta" to R.drawable.mg_food_pasta,
        "corn starch" to R.drawable.mg_food_cornstarch,
        "hemp hearts" to R.drawable.mg_food_hemp,
        "cooking oil" to R.drawable.mg_food_cooking_oil,
        "pasta sauce" to R.drawable.mg_food_tomato_sauce,
        "fresh basil" to R.drawable.mg_food_basil,
        "green bean" to R.drawable.mg_food_green_bean,
        "cannellini" to R.drawable.mg_food_cannellini,
        "black bean" to R.drawable.mg_food_black_bean,
        "brown rice" to R.drawable.mg_food_rice,
        "quick oats" to R.drawable.mg_food_oats,
        "cornstarch" to R.drawable.mg_food_cornstarch,
        "chia seeds" to R.drawable.mg_food_chia,
        "canola oil" to R.drawable.mg_food_cooking_oil,
        "white miso" to R.drawable.mg_food_miso,
        "scallions" to R.drawable.mg_food_green_onion,
        "mushrooms" to R.drawable.mg_food_mushroom,
        "courgette" to R.drawable.mg_food_zucchini,
        "chickpeas" to R.drawable.mg_food_chickpea,
        "garbanzos" to R.drawable.mg_food_chickpea,
        "spaghetti" to R.drawable.mg_food_spaghetti,
        "olive oil" to R.drawable.mg_food_olive_oil,
        "soy sauce" to R.drawable.mg_food_soy_sauce,
        "raw honey" to R.drawable.mg_food_honey,
        "broccoli" to R.drawable.mg_food_broccoli,
        "coleslaw" to R.drawable.mg_food_cabbage,
        "scallion" to R.drawable.mg_food_green_onion,
        "mushroom" to R.drawable.mg_food_mushroom,
        "shiitake" to R.drawable.mg_food_mushroom,
        "zucchini" to R.drawable.mg_food_zucchini,
        "cucumber" to R.drawable.mg_food_cucumber,
        "chickpea" to R.drawable.mg_food_chickpea,
        "garbanzo" to R.drawable.mg_food_chickpea,
        "mukimame" to R.drawable.mg_food_edamame,
        "flaxseed" to R.drawable.mg_food_flax,
        "soy milk" to R.drawable.mg_food_soy_milk,
        "teriyaki" to R.drawable.mg_food_teriyaki,
        "dressing" to R.drawable.mg_food_dressing,
        "marinade" to R.drawable.mg_food_dressing,
        "cinnamon" to R.drawable.mg_food_cinnamon,
        "spinach" to R.drawable.mg_food_spinach,
        "cabbage" to R.drawable.mg_food_cabbage,
        "carrots" to R.drawable.mg_food_carrot,
        "crimini" to R.drawable.mg_food_mushroom,
        "lentils" to R.drawable.mg_food_lentil,
        "edamame" to R.drawable.mg_food_edamame,
        "berries" to R.drawable.mg_food_berries,
        "walnuts" to R.drawable.mg_food_walnut,
        "almonds" to R.drawable.mg_food_almond,
        "chicken" to R.drawable.mg_food_chicken,
        "yoghurt" to R.drawable.mg_food_yogurt,
        "vinegar" to R.drawable.mg_food_vinegar,
        "oregano" to R.drawable.mg_food_oregano,
        "unknown" to R.drawable.mg_food_unknown,
        "greens" to R.drawable.mg_food_spinach,
        "carrot" to R.drawable.mg_food_carrot,
        "garlic" to R.drawable.mg_food_garlic,
        "ginger" to R.drawable.mg_food_ginger,
        "lentil" to R.drawable.mg_food_lentil,
        "walnut" to R.drawable.mg_food_walnut,
        "almond" to R.drawable.mg_food_almond,
        "sesame" to R.drawable.mg_food_sesame,
        "yogurt" to R.drawable.mg_food_yogurt,
        "tamari" to R.drawable.mg_food_soy_sauce,
        "ceylon" to R.drawable.mg_food_cinnamon,
        "pepper" to R.drawable.mg_food_pepper,
        "pasta" to R.drawable.mg_food_pasta,
        "penne" to R.drawable.mg_food_pasta,
        "berry" to R.drawable.mg_food_berries,
        "limes" to R.drawable.mg_food_lime,
        "prego" to R.drawable.mg_food_tomato_sauce,
        "honey" to R.drawable.mg_food_honey,
        "cumin" to R.drawable.mg_food_cumin,
        "basil" to R.drawable.mg_food_basil,
        "rice" to R.drawable.mg_food_rice,
        "oats" to R.drawable.mg_food_oats,
        "lime" to R.drawable.mg_food_lime,
        "chia" to R.drawable.mg_food_chia,
        "flax" to R.drawable.mg_food_flax,
        "hemp" to R.drawable.mg_food_hemp,
        "tofu" to R.drawable.mg_food_tofu,
        "milk" to R.drawable.mg_food_milk,
        "miso" to R.drawable.mg_food_miso,
        "oat" to R.drawable.mg_food_oats
    )
    val drawing = matches.firstOrNull { Regex("\\b${Regex.escape(it.first)}\\b").containsMatchIn(name) }?.second
    return drawing?.let { ImageVector.vectorResource(it) } ?: fallback ?: ImageVector.vectorResource(R.drawable.mg_food_unknown)
}
/** Prefer explicit references. Legacy text matching is display-only; it never asserts pantry use. */
fun stepIngredients(r: JSONObject, s: JSONObject): List<Int> {
    val refs = s.a("ingredients").strings() + s.a("ingredient_ids").strings()
    val text = (s.s("title") + " " + s.s("text")).lowercase()
    val stop = setOf("no", "salt", "added", "extra", "virgin", "fresh", "frozen", "shelled", "low", "sodium", "roasted", "ground", "dry", "whole", "brown", "green", "black", "red", "white", "yellow")
    return r.a("ingredients").objects().mapIndexedNotNull { index, ingredient ->
        val words = ingredient.s("name").lowercase().split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in stop }.map { it.removeSuffix("s") }
        if (if (refs.isNotEmpty()) ingredient.s("id") in refs else words.any { Regex("\\b${Regex.escape(it)}(?:s|es)?\\b").containsMatchIn(text) }) index else null
    }
}
fun maxCookPortions(r: JSONObject) = r.optInt("max_portions", 20).coerceIn(1, 20)
fun defaultCookPortions(r: JSONObject) = basePortions(r).coerceIn(4, 5).coerceAtMost(maxCookPortions(r))

fun basePortions(r: JSONObject): Int {
    for (key in listOf("portions", "servings", "yield_portions")) if (r.optInt(key) > 0) return r.optInt(key)
    val yield = r.s("yield_notes").lowercase()
    val nutritionWords = setOf("g", "mg", "gram", "grams", "protein", "calories", "kcal", "per")
    val number = Regex("\\b([0-9]+|one|two|three|four|five|six|seven|eight)\\s+((?:[a-z-]+\\s+){0,5})(?:servings?|portions?|bowls?|meals?|breakfasts?|dinners?|sides?)\\b")
        .findAll(yield).firstOrNull { match -> match.groupValues[2].trim().split(Regex("\\s+")).none { it in nutritionWords } }
        ?.groupValues?.get(1) ?: return 4
    return number.toIntOrNull() ?: listOf("one", "two", "three", "four", "five", "six", "seven", "eight").indexOf(number) + 1
}
fun ingredientAmount(prefs: SharedPreferences, r: JSONObject, a: JSONObject, portions: Int, mode: String): String {
    val multiplier = portions.toDouble() / basePortions(r)
    val raw = a.s("amount")
    val exact = "${amountText(raw.toDoubleOrNull()?.times(multiplier)?.let { String.format(java.util.Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') } ?: raw)} ${a.s("unit")}".trim()
    val flavorCritical = flavorCriticalIngredient(a)
    val defaultExact = mode == "Exact" || (mode == "Mixed" && flavorCritical)
    val natural = !prefs.getBoolean("amount-exact:${r.s("id")}:${a.s("id", a.s("name"))}", defaultExact)
    val naturalAmount = a.s("natural_amount", a.s("naturalAmount")).ifBlank {
        a.s("detail").takeIf { Regex("^(?:all (?:of )?(?:it|the)|the (?:whole|full)|half (?:the|a)|a (?:whole|full)|about [0-9¼½¾⅓⅔⅛]|[0-9¼½¾⅓⅔⅛]+\\s*(?:heads?|crowns?|cans?|blocks?|bunches?|cups?|tbsp|tsp|tablespoons?|teaspoons?|grams?|g|ounces?|oz)\\b)", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }.orEmpty()
    }
    // Preparation notes are not amounts; an unscalable natural amount stays exact after scaling.
    return if (natural && multiplier == 1.0 && naturalAmount.isNotBlank()) naturalAmount else exact
}

private fun flavorCriticalIngredient(ingredient: JSONObject) = Regex("salt|sauce|oil|spice|ginger|honey|vinegar|baking|cumin|cinnamon|pepper|paprika|turmeric|nutmeg|cloves", RegexOption.IGNORE_CASE).containsMatchIn(ingredient.s("name"))

fun flipIngredientAmount(prefs: SharedPreferences, recipe: JSONObject, ingredient: JSONObject, mode: String) {
    val key = "amount-exact:${recipe.s("id")}:${ingredient.s("id", ingredient.s("name"))}"
    val defaultExact = mode == "Exact" || (mode == "Mixed" && flavorCriticalIngredient(ingredient))
    prefs.edit().putBoolean(key, !prefs.getBoolean(key, defaultExact)).apply()
}

@Composable
fun CookIngredientRow(vm: GardenModel, r: JSONObject, index: Int, portions: Int, mode: String,
                      amountRevision: Int, onFlip: () -> Unit, substitution: String = "") {
    val ingredient = r.a("ingredients").getJSONObject(index)
    val amount = remember(r.s("id"), amountRevision, portions, mode, ingredient.toString()) { ingredientAmount(vm.prefs, r, ingredient, portions, mode) }
    Row(Modifier.fillMaxWidth().clip(GardenShape.Button).background(CardSurface)
        .clickable { flipIngredientAmount(vm.prefs, r, ingredient, mode); onFlip() }.heightIn(min = 48.dp).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(ingredientIcon(ingredient), null, Modifier.size(26.dp), tint = Color.Unspecified)
        Column(Modifier.weight(1f)) {
            Text(ingredient.s("name"), fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
            Text(amount, fontSize = 13.sp, color = Forest, fontWeight = FontWeight.Medium)
            if (substitution.isNotBlank()) Text("→ $substitution", fontSize = 12.sp, color = Forest)
        }
    }
}

@Composable
fun PortionDestinationRows(total: Int, counts: JSONObject, onChange: (String, Int) -> Unit) {
    val assigned = listOf("fridge", "freezer", "eatenNow").sumOf { counts.optInt(it) }
    val remaining = (total - assigned).coerceAtLeast(0)
    Box(Modifier.fillMaxWidth().height(106.dp), contentAlignment = Alignment.Center) {
        if (remaining > 0) GardenStack(remaining)
        else Icon(Icons.Outlined.CheckCircle, "All portions placed", Modifier.size(48.dp), tint = Forest)
    }
    Text("$remaining to place", style = GardenType.Small, color = Forest)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("fridge" to "Fridge", "freezer" to "Freezer", "eatenNow" to "Eaten now").forEach { (key, label) ->
            val count = counts.optInt(key)
            Column(Modifier.weight(1f).clip(GardenShape.Card).background(CardSurface)
                .border(1.dp, if (count > 0) Forest else Line, GardenShape.Card)
                .clickable(enabled = assigned < total, role = androidx.compose.ui.semantics.Role.Button) { onChange(key, count + 1) }
                .semantics { contentDescription = "Add one to $label" }
                .padding(horizontal = 5.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(when (key) { "fridge" -> Icons.Outlined.Kitchen; "freezer" -> Icons.Outlined.AcUnit; else -> Icons.Outlined.Restaurant },
                    null, Modifier.size(28.dp), tint = Forest)
                Text(label, style = GardenType.Small)
                Text(count.toString(), style = GardenType.Title)
                IconButton(onClick = { onChange(key, count - 1) }, enabled = count > 0,
                    modifier = Modifier.size(48.dp).clip(GardenShape.Button).background(Paper2)) {
                    Icon(Icons.Outlined.Remove, "Remove one from $label", Modifier.size(18.dp), tint = if (count > 0) Forest else Faint)
                }
            }
        }
    }
}

@Composable
fun CookMode(vm: GardenModel, r: JSONObject, sk: String, portions: Int, mode: String, amountRevision: Int,
             onFlip: () -> Unit, subs: JSONObject, counts: JSONObject, onCount: (String, Int) -> Unit,
             onSubstitute: (Int) -> Unit, onReport: () -> Unit, onLeave: () -> Unit, askNotification: () -> Unit) {
    val showLanes = moduleEnabled(vm, "lanes", default = false)
    val showCookQuestions = moduleEnabled(vm, "cookQuestions")
    val context = LocalContext.current
    val steps = r.a("steps").objects()
    val recipeSteps = remember(r.toString()) { steps.map(::cookStep) }
    val storage = "cook-progress:$sk"
    var data by remember(sk) { mutableStateOf(runCatching { JSONObject(vm.prefs.getString(storage, "{}")!!) }.getOrDefault(JSONObject())) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var review by remember { mutableStateOf<Int?>(null) }
    var pickSub by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf(false) }
    var questionText by remember { mutableStateOf("") }
    fun progress(): List<CookProgress> = steps.indices.map { i -> data.optJSONObject("$i")?.let { o ->
        CookProgress(if (o.has("startedAt")) o.optLong("startedAt") else null, if (o.has("doneAt")) o.optLong("doneAt") else null)
    } ?: CookProgress() }
    fun record(i: Int, name: String, finish: Boolean = false) {
        val time = System.currentTimeMillis()
        if (progress()[i].startedAt == null && name != "step_start") vm.track("step_start", "recipeId" to r.s("id"), "session" to sk, "step" to i)
        if (finish && name != "step_finish") vm.track("step_finish", "recipeId" to r.s("id"), "session" to sk, "step" to i)
        data = JSONObject(data.toString()).apply {
            val state = optJSONObject("$i") ?: JSONObject()
            if (!state.has("startedAt")) state.put("startedAt", time)
            if (finish) state.put("doneAt", time)
            put("$i", state)
        }
        vm.prefs.edit().putString(storage, data.toString()).putString("cook-day:$sk", cookDay(vm)).apply()
        vm.track(name, "recipeId" to r.s("id"), "session" to sk, "step" to i)
        tick = time
    }
    fun stop(i: Int) {
        val timerKey = "$sk:$i"
        cancelTimer(context, timerKey); vm.stopTimer(timerKey)
        (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(timerKey.hashCode())
    }
    LaunchedEffect(sk) {
        while (true) {
            tick = System.currentTimeMillis()
            progress().forEachIndexed { i, state ->
                if (state.startedAt != null && state.doneAt == null && CookSequencer.passiveMillis(recipeSteps[i]) > 0 &&
                    CookSequencer.end(recipeSteps[i], state)!! <= tick && !data.optJSONObject("$i")!!.optBoolean("elapsedReported")) {
                    vm.track("cook_timer_elapsed", "recipeId" to r.s("id"), "step" to i)
                    data = JSONObject(data.toString()).apply { getJSONObject("$i").put("elapsedReported", true) }
                    vm.prefs.edit().putString(storage, data.toString()).apply()
                }
            }
            delay(500)
        }
    }
    val progress = progress()
    val sequence = CookSequencer.sequence(recipeSteps, progress, tick)
    val complete = steps.indices.all { progress[it].doneAt != null || (CookSequencer.passiveMillis(recipeSteps[it]) > 0 && CookSequencer.end(recipeSteps[it], progress[it])?.let { end -> end <= tick } == true) }
    val timerSteps = steps.indices.filter { vm.timers["$sk:$it"]?.optLong("deadline")?.let { end -> end > tick } == true }
    val current = review ?: sequence.now
    val step = current?.let { scaledCookStep(r, steps[it], portions) }
    val used = step?.let { stepIngredients(r, it) }.orEmpty()
    fun askStep(prompt: String) {
        vm.askInBackground("Cooking ${r.s("title")} (${r.s("id")}), step ${current?.plus(1) ?: "waiting"}: ${step?.s("text").orEmpty()}. Setting: ${step?.let(::stepSetting).orEmpty()}. Substitutions: $subs. Question: $prompt", "cook_question")
    }

    val stepScroll = rememberScrollState()
    LaunchedEffect(current, complete) { stepScroll.scrollTo(0) }
    BackHandler { onLeave() }
    Dialog(onDismissRequest = onLeave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onLeave) { Icon(Icons.Outlined.Close, "Leave cooking") }
                    Text(r.s("title"), Modifier.weight(1f), fontSize = 14.sp, maxLines = 2)
                    IconButton(onClick = { vm.noteRequests++ }) { Icon(Icons.Outlined.EditNote, "Leave an app note", tint = Muted) }
                }
                TimerDock(vm)
                Column(Modifier.weight(1f).verticalScroll(stepScroll).padding(horizontal = GardenSpace.Page, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (complete && review == null) {
                        Text("$portions portions", style = GardenType.Title)
                        PortionDestinationRows(portions, counts, onCount)
                        Button(onClick = onReport, enabled = listOf("fridge", "freezer", "eatenNow").sumOf { counts.optInt(it) } == portions,
                            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cooking report", color = LocalContentColor.current) }
                        TextButton(onClick = { review = steps.lastIndex }) { Text("Back to the last step") }
                    } else {
                        if (step != null) {
                            Eyebrow("STEP ${current + 1} OF ${steps.size}")
                            val direction = step.s("text").trim()
                            val firstSentence = stepSentence(step)
                            Text(firstSentence, style = GardenType.Title.copy(fontSize = 21.sp, lineHeight = 26.sp))
                            direction.removePrefix(firstSentence).trim().takeIf { it.isNotBlank() }?.let {
                                Text(it, style = GardenType.Body)
                            }
                            used.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { i -> Box(Modifier.weight(1f)) { CookIngredientRow(vm, r, i, portions, mode, amountRevision, onFlip, subs.optString("$i")) } }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            stepSetting(step).takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold, color = Forest) }
                            stepDoneness(step).takeIf { it.isNotBlank() }?.let { Text("Done when: $it", color = Forest) }
                            val started = progress[current].startedAt != null
                            val finished = progress[current].doneAt != null
                            val passive = CookSequencer.passiveMillis(recipeSteps[current]) > 0
                            Button(onClick = {
                                if (review != null) { review = null }
                                else if (!started) {
                                    record(current, "step_start")
                                    val minutes = if (passive) ceilMinutes(CookSequencer.passiveMillis(recipeSteps[current])) else step.optInt("timer_minutes")
                                    if (minutes > 0) {
                                        askNotification()
                                        val key = "$sk:$current"
                                        var deadline = vm.startTimer(key, step.s("title"), minutes, stepSetting(step))
                                        if (passive) {
                                            deadline = CookSequencer.end(recipeSteps[current], progress()[current])!!
                                            val timer = JSONObject(vm.timers[key]!!.toString()).put("deadline", deadline)
                                            val timers = JSONObject(vm.prefs.getString("timers", "{}")!!).put(key, timer)
                                            vm.prefs.edit().putString("timers", timers.toString()).apply(); vm.reloadTimers()
                                        }
                                        scheduleTimer(context, key, step.s("title"), deadline, stepSetting(step))
                                    }
                                } else { record(current, "step_finish", true); stop(current) }
                            }, Modifier.fillMaxWidth().height(58.dp), shape = GardenShape.Button) {
                                Icon(if (!started && passive) Icons.Outlined.Timer else Icons.Outlined.Check, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (review != null) "Return to cooking" else if (finished) "Continue" else if (started) "Finish step" else if (passive) "Start ${ceilMinutes(CookSequencer.passiveMillis(recipeSteps[current]))} min timer" else "Start step", color = LocalContentColor.current)
                            }
                        } else {
                            Heading("Waiting")
                            sequence.waitingOn?.let { Text(steps[it.step].s("title"), fontSize = 20.sp) }
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Timer running", color = LocalContentColor.current) }
                        }
                        // Keep a running station visible while a hands-on step has no appliance.
                        val stationStep = listOfNotNull(current, sequence.waitingOn?.step)
                            .firstOrNull { steps[it].s("equipment").isNotBlank() }
                            ?: sequence.running.firstOrNull { steps[it.step].s("equipment").isNotBlank() }?.step
                            ?: timerSteps.firstOrNull { steps[it].s("equipment").isNotBlank() }
                        val stationEquipment = stationStep?.let { steps[it].s("equipment") }.orEmpty()
                        KitchenDrawing(vm, Modifier.fillMaxWidth().height(140.dp).testTag("cook-kitchen"), activeEquipment = stationEquipment.ifBlank { "none" })
                        val stationTimer = stationStep?.let { vm.timers["$sk:$it"] }
                            ?: current?.let { vm.timers["$sk:$it"] }
                            ?: sequence.waitingOn?.let { vm.timers["$sk:${it.step}"] }
                        if (stationTimer != null) {
                            val seconds = ((stationTimer.optLong("deadline") - tick).coerceAtLeast(0) / 1000).toInt()
                            GardenChip(stationEquipment.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty() + if (seconds > 0) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "Check", tint = if (seconds > 0) Mist else AmberLight, icon = Icons.Outlined.Timer)
                        }
                        if (showLanes) CookApplianceLanes(steps, recipeSteps, progress, sequence, tick)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val prior = (current ?: steps.size) - 1
                            TextButton(onClick = { review = prior; vm.track("step_back", "recipeId" to r.s("id"), "step" to prior) }, enabled = prior >= 0) { Text("Back") }
                            TextButton(onClick = {
                                if (review != null) review = null else current?.let { record(it, "step_finish", true); stop(it) }
                            }, enabled = current != null) { Text("Next") }
                            if (sequence.waitingOn != null) TextButton(onClick = { sequence.waitingOn?.let { record(it.step, "step_skip", true); stop(it.step) } }) { Text("Skip the wait") }
                            TextButton(onClick = { pickSub = true }, enabled = used.isNotEmpty()) { Text("I substituted") }
                            if (timerSteps.isNotEmpty() || sequence.running.isNotEmpty() || sequence.waitingOn != null) TextButton(onClick = { (current?.takeIf { it in timerSteps } ?: sequence.waitingOn?.step ?: sequence.running.firstOrNull()?.step)?.let { record(it, "step_done_early", true); stop(it) } }) { Text("Done early") }
                            IconButton(onClick = { question = true }) { Icon(Icons.Outlined.HelpOutline, "Ask a question") }
                        }
                        Spacer(Modifier.height(4.dp))
                        sequence.next?.let { i -> Text("Next · ${stepSentence(scaledCookStep(r, steps[i], portions))}", color = Muted, fontSize = 13.sp) }
                        if (showCookQuestions && step != null) FlowRow(Modifier.testTag("cook-question-suggestions"),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val heatStep = Regex("heat|oven|stove|microwave|boil|simmer|roast|bake|induction", RegexOption.IGNORE_CASE)
                                .containsMatchIn(stepSetting(step) + " " + step.s("text"))
                            listOf("How do I tell it's done?", if (heatStep) "Can I change the heat?" else "Can I do this ahead?").forEach { prompt ->
                                GardenChip(prompt, icon = Icons.Outlined.HelpOutline, onClick = {
                                    vm.track("cook_question_suggestion", "recipeId" to r.s("id"), "step" to current, "question" to prompt)
                                    askStep(prompt)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (pickSub) AlertDialog(onDismissRequest = { pickSub = false }, title = { Text("I substituted") }, text = {
        Column { used.forEach { i -> TextButton(onClick = { pickSub = false; onSubstitute(i) }) { Text(r.a("ingredients").getJSONObject(i).s("name")) } } }
    }, confirmButton = { TextButton(onClick = { pickSub = false }) { Text("Cancel") } })
    if (question) AlertDialog(onDismissRequest = { question = false }, title = { Text("Question") }, text = {
        OutlinedTextField(questionText, { questionText = it }, label = { Text("Ask about this step") })
    }, confirmButton = { TextButton(enabled = questionText.isNotBlank(), onClick = {
        askStep(questionText)
        question = false; questionText = ""
    }) { Text("Ask") } }, dismissButton = { TextButton(onClick = { question = false }) { Text("Cancel") } })
}
private fun ceilMinutes(ms: Long) = kotlin.math.ceil(ms / 60000.0).toInt()


/** A view of the same deterministic sequence that drives the primary action. */
@Composable
private fun CookApplianceLanes(steps: List<JSONObject>, recipeSteps: List<CookStep>,
    states: List<CookProgress>, sequence: CookSequence, time: Long) {
    val lanes = steps.indices.groupBy { index ->
        CookSequencer.appliance(recipeSteps[index])
            ?: recipeSteps[index].equipment.substringBefore('·').trim().ifBlank { "Counter" }
    }
    GardenCard(modifier = Modifier.testTag("cook-lanes")) {
        SectionLabel("Appliance lanes")
        lanes.forEach { (name, indices) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = GardenType.Small, modifier = Modifier.width(86.dp), maxLines = 2)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    indices.forEach { index ->
                        val resolved = states[index].doneAt != null ||
                            (CookSequencer.passiveMillis(recipeSteps[index]) > 0 &&
                                CookSequencer.end(recipeSteps[index], states[index])?.let { it <= time } == true)
                        val running = sequence.running.any { it.step == index }
                        val active = sequence.now == index
                        val label = when { resolved -> "Done"; running -> "Timer"; active -> "Now"; else -> "Next" }
                        val color = when { resolved -> Mist; running -> AmberLight; active -> Forest; else -> Paper2 }
                        Box(Modifier.weight(1f).height(25.dp).clip(RoundedCornerShape(5.dp)).background(color)
                            .semantics { contentDescription = "Step ${index + 1}: $label" }, contentAlignment = Alignment.Center) {
                            Text("${index + 1}", fontSize = 11.sp, color = if (active && !resolved && !running) Paper else Muted)
                        }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GardenChip("Now", selected = true)
            GardenChip("Timer", tint = AmberLight, icon = Icons.Outlined.Timer)
            GardenChip("Done", tint = Mist, icon = Icons.Outlined.Check)
            GardenChip("Next")
        }
    }
}
