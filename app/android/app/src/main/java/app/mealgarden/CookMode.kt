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

fun cookStep(s: JSONObject) = CookStep(
    if (s.has("minutes")) s.optDouble("minutes") else null,
    s.optDouble("passive_minutes", 0.0),
    if (s.has("start_minute")) s.optDouble("start_minute") else null,
    s.s("equipment"), s.s("text"), if (s.has("timer_minutes")) s.optDouble("timer_minutes") else null,
)
fun cookDay() = LocalDate.now().toString()
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
    val number = Regex("\\b([0-9]+|one|two|three|four|five|six|seven|eight)\\s+(?:[a-z-]+\\s+){0,3}(?:servings?|portions?|bowls?|meals?|breakfasts?|dinners?|sides?)\\b").find(yield)?.groupValues?.get(1) ?: return 4
    return number.toIntOrNull() ?: listOf("one", "two", "three", "four", "five", "six", "seven", "eight").indexOf(number) + 1
}
fun ingredientAmount(prefs: SharedPreferences, r: JSONObject, a: JSONObject, portions: Int, mode: String): String {
    val multiplier = portions.toDouble() / basePortions(r)
    val raw = a.s("amount")
    val exact = "${amountText(raw.toDoubleOrNull()?.times(multiplier)?.let { String.format(java.util.Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') } ?: raw)} ${a.s("unit")}".trim()
    val flavorCritical = Regex("salt|sauce|oil|spice|ginger|honey|vinegar|baking", RegexOption.IGNORE_CASE).containsMatchIn(a.s("name"))
    val defaultExact = mode == "Exact" || (mode == "Mixed" && flavorCritical)
    val natural = !prefs.getBoolean("amount-exact:${r.s("id")}:${a.s("id", a.s("name"))}", defaultExact)
    // Legacy detail often describes preparation. Keep exact quantities when scaling it cannot be validly scaled.
    return if (natural && multiplier == 1.0 && a.s("detail").isNotBlank()) a.s("detail") else exact
}

@Composable
fun CookIngredientRow(vm: GardenModel, r: JSONObject, index: Int, portions: Int, mode: String,
                      amountRevision: Int, onFlip: () -> Unit, substitution: String = "") {
    val a = r.a("ingredients").getJSONObject(index)
    val amount = ingredientAmount(vm.prefs, r, a, portions, mode)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ingredientIcon(a), null, Modifier.size(24.dp), tint = Color.Unspecified)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.s("name"), fontSize = 15.sp)
            if (substitution.isNotBlank()) Text("→ $substitution", fontSize = 13.sp, color = Forest)
        }
        TextButton(onClick = {
            val key = "amount-exact:${r.s("id")}:${a.s("id", a.s("name"))}"
            val exact = vm.prefs.getBoolean(key, mode == "Exact" || (mode == "Mixed" && Regex("salt|sauce|oil|spice|ginger|honey|vinegar|baking", RegexOption.IGNORE_CASE).containsMatchIn(a.s("name"))))
            vm.prefs.edit().putBoolean(key, !exact).apply(); onFlip()
        }, modifier = Modifier.widthIn(max = 180.dp)) { Text(amount, fontSize = 13.sp) }
    }
}

@Composable
fun PortionDestinationRows(total: Int, counts: JSONObject, onChange: (String, Int) -> Unit) {
    val assigned = listOf("fridge", "freezer", "eatenNow").sumOf { counts.optInt(it) }
    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
        repeat((total - assigned).coerceIn(0, 5)) { i ->
            GardenBowl(Modifier.size(110.dp).offset(y = (i * 8 - 16).dp), seed = total)
        }
        if (total == assigned) Icon(Icons.Outlined.CheckCircle, "All portions placed", Modifier.size(50.dp), tint = Forest)
    }
    Text("${total - assigned} to place", color = Forest)
    listOf("fridge" to "Fridge", "freezer" to "Freezer", "eatenNow" to "Eaten now").forEach { (key, label) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            IconButton(onClick = { onChange(key, counts.optInt(key) - 1) }, enabled = counts.optInt(key) > 0) { Icon(Icons.Outlined.Remove, "Remove one from $label") }
            Text("${counts.optInt(key)}", fontSize = 22.sp)
            IconButton(onClick = { onChange(key, counts.optInt(key) + 1) }, enabled = assigned < total) { Icon(Icons.Outlined.Add, "Add one to $label") }
        }
    }
}

@Composable
fun CookMode(vm: GardenModel, r: JSONObject, sk: String, portions: Int, mode: String, amountRevision: Int,
             onFlip: () -> Unit, subs: JSONObject, counts: JSONObject, onCount: (String, Int) -> Unit,
             onSubstitute: (Int) -> Unit, onReport: () -> Unit, onLeave: () -> Unit, askNotification: () -> Unit) {
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
    var full by remember { mutableStateOf(false) }
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
        vm.prefs.edit().putString(storage, data.toString()).putString("cook-day:$sk", cookDay()).apply()
        vm.track(name, "recipeId" to r.s("id"), "session" to sk, "step" to i)
        tick = time; full = false
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
    val stepScroll = rememberScrollState()
    LaunchedEffect(current, complete) { stepScroll.scrollTo(0); full = false }
    BackHandler { onLeave() }
    Dialog(onDismissRequest = onLeave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onLeave) { Icon(Icons.Outlined.Close, "Leave cooking") }
                    Text(r.s("title"), Modifier.weight(1f), fontSize = 14.sp, maxLines = 2)
                }
                TimerDock(vm)
                Column(Modifier.weight(1f).verticalScroll(stepScroll).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (complete && review == null) {
                        Heading("$portions portions")
                        PortionDestinationRows(portions, counts, onCount)
                        Button(onClick = onReport, enabled = listOf("fridge", "freezer", "eatenNow").sumOf { counts.optInt(it) } == portions,
                            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cooking report") }
                        TextButton(onClick = { review = steps.lastIndex }) { Text("Back to the last step") }
                    } else {
                        if (step != null) {
                            Eyebrow("STEP ${current + 1} OF ${steps.size}")
                            Text(stepSentence(step), fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 35.sp)
                            used.forEach { i -> CookIngredientRow(vm, r, i, portions, mode, amountRevision, onFlip, subs.optString("$i")) }
                            stepSetting(step).takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold, color = Forest) }
                            stepDoneness(step).takeIf { it.isNotBlank() }?.let { Text("Done when: $it", color = Forest) }
                            TextButton(onClick = { full = !full }) { Text(if (full) "Hide full step" else "Full step") }
                            if (full) Text(step.s("text"), lineHeight = 24.sp)
                            val started = progress[current].startedAt != null
                            val finished = progress[current].doneAt != null
                            val passive = CookSequencer.passiveMillis(recipeSteps[current]) > 0
                            Button(onClick = {
                                if (review != null) { review = null; full = false }
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
                            }, Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (review != null) "Return to cooking" else if (finished) "Continue" else if (started) "Finish step" else if (passive) "Start ${ceilMinutes(CookSequencer.passiveMillis(recipeSteps[current]))} min timer" else "Start step")
                            }
                        } else {
                            Heading("Waiting")
                            sequence.waitingOn?.let { Text(steps[it.step].s("title"), fontSize = 20.sp) }
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Timer running") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val prior = (current ?: steps.size) - 1
                            TextButton(onClick = { review = prior; full = false; vm.track("step_back", "recipeId" to r.s("id"), "step" to prior) }, enabled = prior >= 0) { Text("Back") }
                            TextButton(onClick = {
                                if (review != null) review = null else current?.let { record(it, "step_finish", true); stop(it) }
                            }, enabled = current != null) { Text("Next") }
                            TextButton(onClick = { sequence.waitingOn?.let { record(it.step, "step_skip", true); stop(it.step) } }, enabled = sequence.waitingOn != null) { Text("Skip the wait") }
                            TextButton(onClick = { pickSub = true }, enabled = used.isNotEmpty()) { Text("I substituted") }
                            TextButton(onClick = { (current?.takeIf { it in timerSteps } ?: sequence.waitingOn?.step ?: sequence.running.firstOrNull()?.step)?.let { record(it, "step_done_early", true); stop(it) } }, enabled = timerSteps.isNotEmpty() || sequence.running.isNotEmpty()) { Text("Done early") }
                            IconButton(onClick = { question = true }) { Icon(Icons.Outlined.HelpOutline, "Ask a question") }
                        }
                        Spacer(Modifier.height(8.dp))
                        sequence.next?.let { i -> Text("Next · ${stepSentence(scaledCookStep(r, steps[i], portions))}", color = Muted, fontSize = 13.sp) }
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
        vm.askInBackground("Cooking ${r.s("title")} (${r.s("id")}), step ${current?.plus(1) ?: "waiting"}: ${step?.s("text").orEmpty()}. Substitutions: $subs. Question: $questionText", "cook_question")
        question = false; questionText = ""
    }) { Text("Ask") } }, dismissButton = { TextButton(onClick = { question = false }) { Text("Cancel") } })
}
private fun ceilMinutes(ms: Long) = kotlin.math.ceil(ms / 60000.0).toInt()
