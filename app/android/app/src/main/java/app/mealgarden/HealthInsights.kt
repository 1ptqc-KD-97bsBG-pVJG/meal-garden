@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.mealgarden

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// Ranges and evidence coverage travel together. Unknown never contributes a zero.
data class NutritionRange(val low: Double, val high: Double) {
    operator fun plus(other: NutritionRange) = NutritionRange(low + other.low, high + other.high)
    fun text(unit: String = ""): String {
        fun number(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
        return (if (low == high) number(low) else "${number(low)}–${number(high)}") + unit
    }
}
data class HealthNutrient(val key: String, val label: String, val unit: String, val reference: Double? = null, val limit: Boolean = false, val why: String)
val healthNutrients = listOf(
    HealthNutrient("calories", "Energy", " kcal", why = "Enough energy supports daily function and training. A calorie target needs your individual needs; lower is not automatically better."),
    HealthNutrient("protein_g", "Protein", " g", why = "Supports muscle maintenance and growth. Needs vary with body size, activity and health; no personal target is assumed."),
    HealthNutrient("fiber_g", "Fiber", " g", 28.0, why = "Whole grains, beans, fruit and vegetables contribute fiber and support a nourishing dietary pattern."),
    HealthNutrient("sodium_mg", "Sodium", " mg", 2300.0, true, "Lower sodium intake can support blood pressure. Sauces and packaged foods often contribute; brands and portions matter."),
    HealthNutrient("saturated_fat_g", "Saturated fat", " g", 20.0, true, "Replacing some saturated fat with unsaturated fats supports heart health. Total fat is not a substitute for this measure."),
    HealthNutrient("added_sugar_g", "Added sugar", " g", 50.0, true, "Added sugar is distinct from the naturally occurring sugar in fruit and plain milk. A limit is not a goal to fill."),
)
fun nutritionRange(n: JSONObject?, key: String): NutritionRange? {
    val r = n?.optJSONObject(key) ?: return null
    val low = r.optDouble("low", Double.NaN); val high = r.optDouble("high", Double.NaN)
    return if (low.isFinite() && high.isFinite() && low >= 0 && high >= low) NutritionRange(low, high) else null
}
fun consumedInterpretation(entry: JSONObject): JSONObject? {
    val server = entry.optJSONObject("server") ?: return null
    if (server.s("status") != "interpreted") return null // A revised interpretation is pending; old totals are stale.
    val i = server.optJSONObject("interpretation") ?: return null
    return i.takeIf { it.s("category") in listOf("meal", "snack", "drink") }
}
data class FoodGroup(val id: String, val label: String, val pattern: Regex)
private fun foodPattern(s: String) = Regex("\\b(?:$s)\\b", RegexOption.IGNORE_CASE)
val healthGroups = listOf(
    FoodGroup("vegetables", "Vegetables", foodPattern("broccoli|spinach|cabbage|kale|carrots?|zucchini|bok choy|gai lan|cauliflower|mushrooms?|tomatoes?|cucumbers?|edamame|peas")),
    FoodGroup("fruit", "Fruit", foodPattern("berr(?:y|ies)|blueberries|strawberries|raspberries|blackberries|bananas?|apples?|oranges?|peaches?|pears?")),
    FoodGroup("whole_grains", "Whole grains", foodPattern("brown rice|(?:rolled |quick |old.fashioned )?oats|oatmeal|whole.wheat|whole.grain|quinoa|barley")),
    FoodGroup("plant_protein", "Beans & soy", foodPattern("tofu|edamame|lentils?|chickpeas?|cannellini|white beans?|black beans?|kidney beans?|soy milk|soymilk")),
    FoodGroup("nuts_seeds", "Nuts & seeds", foodPattern("walnuts?|almonds?|chia|flax(?:seed)?|sesame seeds?|sunflower seeds?|pumpkin seeds?|tahini|peanuts?")),
    FoodGroup("other_protein", "Other protein", foodPattern("chicken|salmon|tuna|fish|eggs?|greek yogurt|cottage cheese|turkey|beef|pork")),
)
fun groupEvidence(names: List<String>): Map<String, List<String>> = healthGroups.mapNotNull { group ->
    names.filter { name -> name.split(Regex("\\s+or\\s+", RegexOption.IGNORE_CASE)).all { group.pattern.containsMatchIn(it) } }.distinct().takeIf { it.isNotEmpty() }?.let { group.id to it }
}.toMap()
fun recipeEvidence(recipe: JSONObject): Map<String, List<String>> =
    if (recipe.s("readiness") == "ready") groupEvidence(recipe.a("ingredients").objects().filter { it.optDouble("amount", 0.0) > 0 }.map { it.s("name") }) else emptyMap()
data class DayHealth(val entries: List<JSONObject>, val consumed: List<JSONObject>, val unresolved: Int, val evidence: Map<String, List<String>>) {
    fun values(key: String): List<NutritionRange> = consumed.mapNotNull { nutritionRange(it.optJSONObject("nutrition"), key) }
    fun sum(key: String): NutritionRange? = values(key).takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }
    fun complete(key: String) = consumed.isNotEmpty() && unresolved == 0 && values(key).size == consumed.size
}
fun dayHealth(entries: List<JSONObject>): DayHealth {
    val consumed = entries.mapNotNull(::consumedInterpretation)
    val unresolved = entries.count { e ->
        val server = e.optJSONObject("server")
        server == null || server.s("status") != "interpreted" || server.o("interpretation").s("category") == "unclear"
    }
    val names = consumed.flatMap { it.a("items").objects().filter { item -> item.s("confidence") != "low" }.map { it.s("name") } }
    return DayHealth(entries, consumed, unresolved, groupEvidence(names))
}
fun healthDayHeadline(day: DayHealth, preferences: JSONObject): String {
    if (day.consumed.isEmpty()) return "Waiting for food details"
    val sodium = day.sum("sodium_mg")
    val limit = preferences.o("targets").optDouble("sodium_mg", 2300.0)
    if (sodium != null && sodium.low > limit) return if (preferences.o("targets").has("sodium_mg")) "Sodium above your limit" else "Sodium above reference"
    if (day.unresolved > 0) return "Some food still needs a read"
    if ((day.sum("fiber_g")?.low ?: 0.0) >= preferences.o("targets").optDouble("fiber_g", 28.0)) return if (preferences.o("targets").has("fiber_g")) "Fiber target reached" else "Fiber reference reached"
    return if (day.evidence.size >= 3) "Several nourishing foundations" else "Room to build variety"
}
private val goalLabels = mapOf("longevity" to "Longevity", "weight_loss" to "Weight loss", "muscle_gain" to "Muscle gain", "energy" to "Steadier energy")
fun healthNextStep(day: DayHealth, preferences: JSONObject): Pair<String, String> {
    if (day.consumed.isEmpty()) return "Start with a regular meal" to "Include a protein source, vegetables or fruit, and a satisfying grain or starch. Log what you eat to see the next useful step."
    val sodium = day.sum("sodium_mg")
    if (sodium != null && sodium.low > preferences.o("targets").optDouble("sodium_mg", 2300.0)) return "Choose a less salty next meal" to "Try vegetables, beans or tofu with rice; use less salty sauce or a lower-sodium option. Keep eating regular meals."
    if (!day.evidence.containsKey("vegetables")) return "Add vegetables to your next meal" to "A vegetable side or a bowl with broccoli, cabbage or spinach adds variety. This is based on identified foods; unlogged food may change it."
    val proteinTarget = preferences.o("targets").optDouble("protein_g", Double.NaN)
    if (proteinTarget.isFinite() && (day.sum("protein_g")?.high ?: Double.POSITIVE_INFINITY) < proteinTarget) return "Include protein in your next meal" to "Tofu, beans, Greek yogurt, fish or chicken can contribute toward your chosen protein target. Portion needs still depend on appetite and your day."
    val fiber = day.sum("fiber_g")
    if (fiber != null && fiber.high < preferences.o("targets").optDouble("fiber_g", 28.0)) return "Make room for fiber" to "Beans, oats, whole grains, fruit or vegetables can help close the fiber gap without skipping meals."
    if (!day.evidence.containsKey("fruit")) return "Fruit could add variety" to "Berries, an apple or another fruit can complement the foods identified so far."
    return "Keep the next meal balanced" to "Include a protein source and satisfying portions. A varied pattern over time matters more than perfecting one day."
}

@Composable
fun HealthEntryCard(vm: GardenModel, entries: List<JSONObject>) {
    val day = dayHealth(entries)
    GardenCard(color = Mist, onClick = { vm.openHealth = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, null, tint = Forest, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text("Eating", style = GardenType.Section, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, "Open health insights", tint = Forest, modifier = Modifier.size(18.dp))
        }
        Text(healthNextStep(day, vm.healthPreferences).first, style = GardenType.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("protein_g" to "Protein", "fiber_g" to "Fiber").forEach { (key, label) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = GardenType.Small)
                    Text(day.sum(key)?.text(" g") ?: "Unknown", style = GardenType.Body,
                        fontWeight = FontWeight.SemiBold, color = Forest)
                    val nutrient = healthNutrients.first { it.key == key }
                    val custom = vm.healthPreferences.o("targets").optDouble(key, Double.NaN).takeIf { it.isFinite() }
                    val target = custom ?: nutrient.reference
                    if (target != null) {
                        day.sum(key)?.let { RangeRail(it, target, false) }
                        Text(healthTargetLabel(nutrient, custom), style = GardenType.Small)
                    }
                }
            }
        }
        if (day.unresolved > 0) GardenChip("${day.unresolved} awaiting details", tint = Paper2)
    }
}

@Composable
fun HealthScreen(vm: GardenModel) {
    foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
    val today = foodToday()
    val log = vm.foodLog()
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    var goals by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf(false) }
    val dates = (listOf(today) + log.map { localDay(it.s("capturedAt")) }.filter { it != LocalDate.MIN }).distinct().sortedDescending()
    val date = LocalDate.parse(selected)
    val day = dayHealth(log.filter { localDay(it.s("capturedAt")) == date })
    val preferences = vm.healthPreferences
    val recommendations = vm.snapshot.a("recipes").objects().filter { recipeReady(it) }.sortedByDescending { r ->
        val evidence = recipeEvidence(r)
        evidence.keys.count { !day.evidence.containsKey(it) } * 3 + if (evidence.containsKey("plant_protein")) 1 else 0
    }.take(3)
    Column(Modifier.fillMaxSize().background(Paper)) {
        GardenTopBar("Health insights", onBack = { vm.openHealth = false }) {
            IconButton(onClick = { method = true }) { Icon(Icons.Outlined.Info, "How insights work", tint = Muted) }
        }
        LazyColumn(contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    dates.forEach { d -> GardenChip(if (d == today) "Today" else humanDate(d.toString()),
                        selected = selected == d.toString(), onClick = { selected = d.toString() }) }
                }
            }
            item {
                GardenCard {
                    Text(healthDayHeadline(day, preferences), style = GardenType.Section)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        GardenChip("${day.consumed.size} food entries")
                        GardenChip("Partial day")
                        if (day.unresolved > 0) GardenChip("${day.unresolved} awaiting details", tint = AmberLight)
                        val rough = day.consumed.count { it.s("confidence") == "low" }
                        if (rough > 0) GardenChip("$rough rough estimates", tint = AmberLight)
                    }
                }
            }
            item {
                SectionLabel("Your priorities", "Edit goals") { goals = true }
                if (vm.pendingHealthPreferences != null) Text(if (vm.paired) "Goals waiting to sync" else "Goals saved on this phone", style = GardenType.Small)
            }
            item { GoalFitCard(day, preferences) }
            item {
                GardenCard(color = Mist) {
                    Text(if (date == today) "Next meal" else "Try next", style = GardenType.Section)
                    Text(healthNextStep(day, preferences).first, style = GardenType.Body, color = Forest)
                    recommendations.firstOrNull()?.let { recipe ->
                        GardenQuietButton(recipe.s("title"), onClick = { vm.openHealth = false; vm.selectedRecipe = recipe.s("id") }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.MenuBook)
                    }
                }
            }
            item { SectionLabel("Nutrients") }
            items(healthNutrients, key = { it.key }) { n -> NutrientCard(n, day, preferences) }
            item {
                GardenCard {
                    SectionLabel("Food variety")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        healthGroups.forEach { group ->
                            val names = day.evidence[group.id]
                            GardenChip(group.label, tint = if (names != null) Mist else Paper2,
                                icon = if (names != null) Icons.Outlined.Check else Icons.Outlined.Remove)
                        }
                    }
                }
            }
            item { SectionLabel("Ideas from your recipes") }
            items(recommendations, key = { "health-${it.s("id")}" }) { r ->
                GardenCard(onClick = { vm.openHealth = false; vm.openFoodLog = false; vm.selectedRecipe = r.s("id") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GardenRecipePlate(r, Modifier.size(48.dp))
                        Text(r.s("title"), style = GardenType.Section, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        healthGroups.filter { recipeEvidence(r).containsKey(it.id) && !day.evidence.containsKey(it.id) }
                            .forEach { GardenChip(it.label, tint = Mist) }
                    }
                }
            }
            item { GardenQuietButton("Review or add food details", { vm.openHealth = false; vm.openFoodLog = true }, icon = Icons.Outlined.EditNote) }
        }
    }
    if (goals) HealthGoalsDialog(vm) { goals = false }
    if (method) HealthMethodDialog { method = false }
}

@Composable
private fun GoalFitCard(day: DayHealth, preferences: JSONObject) {
    val priorities = preferences.a("priorities").strings()
    var selected by rememberSaveable { mutableStateOf("longevity") }
    val active = selected.takeIf { it in priorities } ?: priorities.firstOrNull() ?: "longevity"
    GardenCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            priorities.forEachIndexed { index, goal -> GardenChip("${index + 1} · ${goalLabels[goal] ?: goal}",
                selected = active == goal, onClick = { selected = goal }) }
        }
        val key = if (active == "muscle_gain") "protein_g" else if (active == "longevity") "fiber_g" else "calories"
        val n = healthNutrients.first { it.key == key }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(n.label, style = GardenType.Body, modifier = Modifier.weight(1f))
            Text(day.sum(key)?.text(n.unit) ?: "Unknown", style = GardenType.Section, color = Forest)
        }
        val custom = preferences.o("targets").optDouble(key, Double.NaN).takeIf { it.isFinite() }
        val target = custom ?: n.reference
        if (target != null) {
            day.sum(key)?.let { RangeRail(it, target, n.limit) }
            Text(healthTargetLabel(n, custom), style = GardenType.Small)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (active == "longevity") GardenChip("${day.evidence.keys.count { it != "other_protein" }} plant-food groups")
            if (key == "calories" && custom == null) GardenChip("Calorie needs not set")
            if (key == "protein_g" && custom == null) GardenChip("Protein target not set")
            if (active == "weight_loss") GardenChip("Deficit unknown", tint = AmberLight)
        }
    }
}

@Composable
private fun NutrientCard(n: HealthNutrient, day: DayHealth, preferences: JSONObject) {
    var expanded by rememberSaveable(n.key) { mutableStateOf(false) }
    val value = day.sum(n.key)
    val custom = preferences.o("targets").optDouble(n.key, Double.NaN)
    val target = custom.takeIf { it.isFinite() } ?: n.reference
    val coverage = day.values(n.key).size
    val complete = day.complete(n.key)
    CardBox(modifier = Modifier.clickable { expanded = !expanded }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(n.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value?.text(n.unit) ?: "Unknown", fontSize = 17.sp, color = Forest)
        }
        if (target != null) {
            if (value != null) RangeRail(value, target, n.limit)
            val amount = NutritionRange(target, target).text(n.unit)
            Text(if (custom.isFinite()) "Your ${if (n.limit) "limit" else "target"}: $amount" else "FDA daily reference${if (n.limit) " limit" else ""}: $amount", fontSize = 12.sp, color = Muted)
            if (value != null) {
                val status = when {
                    n.limit && value.low > target -> "Above ${if (custom.isFinite()) "your limit" else "reference"} in known food"
                    n.limit && (!complete || value.high > target) -> "Total may exceed ${if (custom.isFinite()) "your limit" else "reference"}; check missing values"
                    n.limit -> "Known food is below reference · unlogged food may add more"
                    value.low >= target -> "${if (custom.isFinite()) "Target" else "Reference"} reached in known food"
                    value.high >= target -> "Estimate overlaps ${if (custom.isFinite()) "target" else "reference"}"
                    !complete -> "Remaining amount unknown · some food needs detail"
                    else -> "${NutritionRange((target - value.high).coerceAtLeast(0.0), (target - value.low).coerceAtLeast(0.0)).text(n.unit)} to ${if (custom.isFinite()) "target" else "reference"}"
                }
                Text(status, fontSize = 12.sp, color = if (n.limit && value.low > target) Clay else Forest)
            }
        } else Text(if (n.key == "calories") "No calorie target" else "No protein target", fontSize = 12.sp, color = Muted)
        Text("Values for $coverage of ${day.consumed.size} read food entries${if (day.unresolved > 0) " · ${day.unresolved} awaiting details" else ""}", fontSize = 11.sp, color = Muted)
        if (expanded) Text(n.why, fontSize = 13.sp)
    }
}

internal fun healthTargetLabel(n: HealthNutrient, custom: Double?): String {
    val target = custom ?: n.reference ?: return if (n.key == "calories") "Calorie needs not set" else "Protein target not set"
    val amount = NutritionRange(target, target).text(n.unit)
    return if (custom != null) "Your ${if (n.limit) "limit" else "target"}: $amount"
        else "FDA daily reference${if (n.limit) " limit" else ""}: $amount"
}

@Composable
private fun RangeRail(value: NutritionRange, target: Double, limit: Boolean) {
    // The darker bar is the low estimate, the lighter segment spans uncertainty.
    val denominator = maxOf(target, value.high, 1.0)
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Line)) {
        Box(Modifier.fillMaxWidth((value.high / denominator).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(if (limit && value.high > target) Clay.copy(alpha = .35f) else Lime))
        Box(Modifier.fillMaxWidth((value.low / denominator).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(if (limit && value.low > target) Clay else Forest))
        Box(Modifier.fillMaxWidth((target / denominator).toFloat().coerceIn(0f, 1f)).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) { Box(Modifier.width(2.dp).fillMaxHeight().background(Ink)) }
    }
}

@Composable
fun RecipeHealthCard(vm: GardenModel, recipe: JSONObject) {
    var expanded by rememberSaveable(recipe.s("id")) { mutableStateOf(false) }
    val evidence = recipeEvidence(recipe)
    val names = recipe.a("ingredients").objects().map { it.s("name") }
    val sodium = names.filter { foodPattern("soy sauce|tamari|miso|salt|teriyaki|bottled|prego|rotisserie").containsMatchIn(it) }
    val saturated = names.filter { foodPattern("butter|cream|cheese|yogurt|milk|coconut").containsMatchIn(it) }
    val sugars = names.filter { foodPattern("honey|maple syrup|sugar|teriyaki").containsMatchIn(it) }
    CardBox(color = Mist) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, null, tint = Forest, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Meal health", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "Breakdown") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            healthGroups.filter { evidence.containsKey(it.id) }.forEach { Pill(it.label) }
        }
        GardenChip("Ingredients only", tint = Paper2)
        if (expanded) {
            healthGroups.filter { evidence.containsKey(it.id) }.forEach { group ->
                Text(group.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(evidence[group.id]!!.joinToString(" · "), fontSize = 13.sp)
            }
            if (sodium.isNotEmpty()) Text("Sodium to check: ${sodium.joinToString(", ")}. Brand and sauce portion matter.", fontSize = 13.sp)
            if (saturated.isNotEmpty()) Text("Saturated fat to check: ${saturated.joinToString(", ")}. Type and brand matter.", fontSize = 13.sp)
            if (sugars.isNotEmpty()) Text("Added sugar to check: ${sugars.joinToString(", ")}.", fontSize = 13.sp)
            val possibleProtein = names.any { name -> healthGroups.filter { it.id in listOf("plant_protein", "other_protein") }.any { it.pattern.containsMatchIn(name) } }
            if (!possibleProtein) Text("Protein source not identified. Consider a protein side.", fontSize = 13.sp)
            Text("Longevity: variety and fiber sources. Weight loss: portions and fullness; calories are unknown. Muscle gain: protein sources identified here do not establish grams or adequacy.", fontSize = 13.sp)
            foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
            val day = dayHealth(vm.foodLog().filter { localDay(it.s("capturedAt")) == foodToday() })
            val adds = healthGroups.filter { evidence.containsKey(it.id) && !day.evidence.containsKey(it.id) }.map { it.label }
            if (adds.isNotEmpty()) {
                Eyebrow("IF YOU EAT THIS NEXT")
                Text("Could add ${adds.joinToString(", ").lowercase()} to today's identified foods.", fontSize = 14.sp, color = Forest)
            }
            Text("This checks named ingredients, not nutrient quantities, food safety, allergies or a clinical health score. Actual portions and substitutions may change the result.", fontSize = 12.sp, color = Muted)
            TextButton(onClick = { vm.selectedRecipe = null; vm.openHealth = true }) { Text("See your day & goals") }
        }
    }
}

@Composable
fun FoodHealthBreakdown(interpretation: JSONObject) {
    if (interpretation.s("category") !in listOf("meal", "snack", "drink")) {
        Text("Reference capture · excluded from eaten-food totals", fontSize = 12.sp, color = Muted)
        return
    }
    val n = interpretation.optJSONObject("nutrition")
    val evidence = groupEvidence(interpretation.a("items").objects().filter { it.s("confidence") != "low" }.map { it.s("name") })
    SectionLabel("Health breakdown")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        healthGroups.filter { evidence.containsKey(it.id) }.forEach { Pill(it.label) }
    }
    listOf("fiber_g", "sodium_mg", "saturated_fat_g", "added_sugar_g").forEach { key ->
        val nutrient = healthNutrients.first { it.key == key }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(nutrient.label, fontSize = 13.sp)
            Text(nutritionRange(n, key)?.text(nutrient.unit) ?: "Unknown", fontSize = 13.sp, color = Muted)
        }
    }
    Text("${interpretation.s("confidence", "low").replaceFirstChar { it.uppercase() }} confidence · portion estimate", fontSize = 12.sp, color = Muted)
}

@Composable
private fun HealthGoalsDialog(vm: GardenModel, dismiss: () -> Unit) {
    val preferences = vm.healthPreferences
    var order by remember { mutableStateOf(preferences.a("priorities").strings()) }
    val fields = remember { mutableStateMapOf<String, String>().apply { healthNutrients.forEach { n -> put(n.key, preferences.o("targets").opt(n.key)?.toString().orEmpty()) } } }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Your health goals") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Priority order", style = GardenType.Small)
            order.forEachIndexed { index, key ->
                OutlinedButton(onClick = { order = listOf(key) + order.filter { it != key } }, modifier = Modifier.fillMaxWidth()) { Text("${index + 1} · ${goalLabels[key]}") }
            }
            goalLabels.keys.filter { it !in order }.forEach { key ->
                OutlinedButton(onClick = { order = order + key }, modifier = Modifier.fillMaxWidth()) { Text("Add ${goalLabels[key]}") }
            }
            if (order.size > 1) order.forEach { key ->
                TextButton(onClick = { order = order.filter { it != key } }) { Text("Remove ${goalLabels[key]}") }
            }
            Text("Optional daily targets", fontWeight = FontWeight.Medium)
            Text("Blank = general reference", style = GardenType.Small)
            healthNutrients.forEach { n -> OutlinedTextField(fields[n.key].orEmpty(), { fields[n.key] = it }, label = { Text("${n.label}${if (n.limit) " limit" else " target"} (${n.unit.trim()})") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (error.isNotEmpty()) Text(error, color = Clay, fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = {
        val limits = mapOf("calories" to 800.0..6000.0, "protein_g" to 20.0..300.0, "fiber_g" to 10.0..70.0, "sodium_mg" to 500.0..5000.0, "saturated_fat_g" to 5.0..60.0, "added_sugar_g" to 0.0..100.0)
        val targets = JSONObject()
        for (n in healthNutrients) {
            val text = fields[n.key].orEmpty().trim()
            if (text.isEmpty()) continue
            val value = text.toDoubleOrNull()
            if (value == null || !value.isFinite() || value !in limits[n.key]!!) { error = "${n.label}: enter ${limits[n.key]!!.start.toInt()}–${limits[n.key]!!.endInclusive.toInt()} or leave blank."; return@TextButton }
            targets.put(n.key, value)
        }
        if (vm.saveHealthPreferences(JSONObject().put("version", 1).put("priorities", org.json.JSONArray(order)).put("targets", targets))) dismiss()
    }) { Text("Save goals") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun HealthMethodDialog(dismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("How insights work") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A useful breakdown beats a grade. Diet quality depends on your overall pattern, portions and individual needs. These insights are not a validated score or a prediction of your health.", fontSize = 14.sp)
            Text("Only interpreted meals, snacks and drinks contribute. Labels, receipts and other reference captures do not. Unknown values stay unknown; bars show the low-to-high estimate. The marker is the reference. Lighter color shows uncertainty.", fontSize = 13.sp)
            Text("FDA label references use a general 2,000-calorie diet, not your personal energy needs. WHO also emphasizes adequacy, diversity and moderation. Added sugar here is the FDA label concept; WHO free sugar also includes juice and honey.", fontSize = 13.sp)
            Text("Logs remain partial. Unlogged food, supplements, micronutrients, brand differences and health conditions are not fully assessed. There is no recommendation to skip meals or compensate with exercise.", fontSize = 13.sp)
            Text("Food days start at the configured hour in your household time zone. Recipe profiles use named ingredients; nutrient totals are not calculated.", fontSize = 13.sp)
            TextButton(onClick = { uri.openUri("https://www.who.int/news-room/fact-sheets/detail/healthy-diet") }) { Text("WHO · Healthy diet") }
            TextButton(onClick = { uri.openUri("https://www.fda.gov/food/nutrition-facts-label/daily-value-nutrition-and-supplement-facts-labels") }) { Text("FDA · Daily values") }
            TextButton(onClick = { uri.openUri("https://www.fna.usda.gov/cnpp/healthy-eating-index") }) { Text("USDA · Dietary pattern scoring") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
