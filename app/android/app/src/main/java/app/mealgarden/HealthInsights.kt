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
    CardBox(color = Mist, modifier = Modifier.clickable { vm.openHealth = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, null, tint = Forest)
            Spacer(Modifier.width(10.dp))
            Text("Health · day so far", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, "Open health insights", tint = Forest)
        }
        Text(healthDayHeadline(day, vm.healthPreferences), fontFamily = FontFamily.Serif, fontSize = 23.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("protein_g" to "protein", "fiber_g" to "fiber").forEach { (key, label) ->
                Text("${day.sum(key)?.text(" g") ?: "—"} $label", fontSize = 13.sp, color = Forest)
            }
        }
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openHealth = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Health insights", fontFamily = FontFamily.Serif, fontSize = 25.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { method = true }) { Icon(Icons.Outlined.Info, "How insights work") }
            IconButton(onClick = { vm.noteRequests++ }) { Icon(Icons.Outlined.EditNote, "Leave an app note", tint = Muted) }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dates.forEach { d -> FilterChip(selected == d.toString(), { selected = d.toString() }, label = { Text(if (d == today) "Today" else d.format(DateTimeFormatter.ofPattern("MMM d"))) }) }
                }
            }
            item {
                CardBox(color = Forest) {
                    Eyebrow(if (date == today) "DAY SO FAR" else "LOGGED DAY", Lime)
                    Text(healthDayHeadline(day, preferences), fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 32.sp, color = Color.White)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("${day.consumed.size} food entries", Lime)
                        if (day.unresolved > 0) Pill("${day.unresolved} awaiting details", Lime)
                        val rough = day.consumed.count { it.s("confidence") == "low" }
                        if (rough > 0) Pill("$rough rough estimates", Lime)
                        Pill("Partial day", Lime)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your priorities", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { goals = true }) { Text("Edit goals") }
                }
                if (vm.pendingHealthPreferences != null) Text(if (vm.paired) "Goals waiting to sync" else "Goals saved on this phone", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (!preferences.o("targets").has("calories")) Text("Calorie needs not set", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            item { GoalFitCard(day, preferences) }
            item {
                val next = healthNextStep(day, preferences)
                CardBox(color = Mist) {
                    Eyebrow(if (date == today) "NEXT FOOD CHOICE" else "PATTERN TO TRY")
                    Text(next.first, fontFamily = FontFamily.Serif, fontSize = 24.sp)
                    Text(next.second, fontSize = 14.sp, color = Ink)
                }
            }
            item { SectionLabel("Nutrients") }
            items(healthNutrients, key = { it.key }) { n -> NutrientCard(n, day, preferences) }
            item {
                CardBox {
                    SectionLabel("Food variety")
                    Text("Identified in logged foods · presence, not servings", fontSize = 12.sp, color = Muted)
                    healthGroups.forEach { group ->
                        val names = day.evidence[group.id]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (names != null) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (names != null) Forest else Muted, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.label, fontSize = 14.sp)
                                Text(names?.joinToString(", ") ?: "Not identified", fontSize = 12.sp, color = Muted)
                            }
                        }
                    }
                }
            }
            item { SectionLabel("Ideas from your recipes") }
            val recommendations = vm.snapshot.a("recipes").objects().filter { it.s("readiness") == "ready" }.sortedByDescending { r ->
                val evidence = recipeEvidence(r)
                evidence.keys.count { !day.evidence.containsKey(it) } * 3 + if (evidence.containsKey("plant_protein")) 1 else 0
            }.take(3)
            items(recommendations, key = { "health-${it.s("id")}" }) { r ->
                CardBox(modifier = Modifier.clickable { vm.openHealth = false; vm.openFoodLog = false; vm.selectedRecipe = r.s("id") }) {
                    Text(r.s("title"), fontFamily = FontFamily.Serif, fontSize = 22.sp)
                    val adds = healthGroups.filter { recipeEvidence(r).containsKey(it.id) && !day.evidence.containsKey(it.id) }.map { it.label }
                    Text(if (adds.isEmpty()) "Includes familiar nourishing foods" else "Could add: ${adds.joinToString(" · ")}", fontSize = 13.sp, color = Forest)
                    Text("View meal breakdown →", fontSize = 13.sp, color = Forest)
                }
            }
            item {
                TextButton(onClick = { vm.openHealth = false; vm.openFoodLog = true }) { Text("Review or add food details") }
            }
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
    CardBox {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            priorities.forEachIndexed { index, goal -> FilterChip(active == goal, { selected = goal }, label = { Text("${index + 1} · ${goalLabels[goal] ?: goal}") }) }
        }
        val protein = day.sum("protein_g")?.text(" g") ?: "Unknown"
        val fiber = day.sum("fiber_g")?.text(" g") ?: "Unknown"
        val energy = day.sum("calories")?.text(" kcal") ?: "Unknown"
        when (active) {
            "weight_loss" -> {
                Text(energy, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text("Energy in known food", fontSize = 12.sp, color = Muted)
                Text(if (preferences.o("targets").has("calories")) "Compare this estimate with your chosen target below. Incomplete intake and unknown energy expenditure cannot establish a deficit." else "Weight-loss progress is unknown without your energy needs and fuller intake. Include satisfying portions and regular meals; fiber and protein sources can help with meal planning.", fontSize = 13.sp)
            }
            "muscle_gain" -> {
                Text("$protein protein", fontFamily = FontFamily.Serif, fontSize = 25.sp)
                Text("Known for ${day.values("protein_g").size} of ${day.consumed.size} food entries", fontSize = 12.sp, color = Muted)
                Text(if (preferences.o("targets").has("protein_g")) "Your protein target is shown below. Include protein across meals and enough food to support training." else "A personal protein target needs body size, training and health context. Include a protein source across meals; food alone does not establish muscle gain.", fontSize = 13.sp)
            }
            "energy" -> {
                Text(energy, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text("Energy in known food", fontSize = 12.sp, color = Muted)
                Text("Regular meals with a protein source and a satisfying grain or starch can support your day. This log cannot establish whether intake is enough or explain tiredness.", fontSize = 13.sp)
            }
            else -> {
                Text("$fiber fiber", fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text("${day.evidence.keys.count { it != "other_protein" }} plant-food groups identified", fontSize = 12.sp, color = Muted)
                Text("Variety, fiber sources and the balance of fats, sodium and sugars inform these insights. Micronutrients and your longer-term pattern are not fully assessed yet.", fontSize = 13.sp)
            }
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
            Text(n.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value?.text(n.unit) ?: "Unknown", fontSize = 19.sp, color = Forest)
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
        } else Text(if (n.key == "calories") "No calorie target · no deficit assumed" else "No personal protein target", fontSize = 12.sp, color = Muted)
        Text("Known for $coverage of ${day.consumed.size} food entries${if (day.unresolved > 0) " · ${day.unresolved} awaiting details" else ""}", fontSize = 11.sp, color = Muted)
        if (expanded) Text(n.why, fontSize = 13.sp)
    }
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
        Text("Ingredient profile · amounts per serving not calculated", fontSize = 12.sp, color = Muted)
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
            Text("Choose your goals. Tap an active priority to move it to the top.", fontSize = 13.sp)
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
            Text("Leave blank to use general references. Enter targets you have chosen or agreed with a professional; the app does not calculate a weight-loss deficit.", fontSize = 12.sp)
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
            Text("Food days start at your configured hour in Los Angeles time. Recipe profiles use named ingredients only; amounts and nutrient totals are not calculated.", fontSize = 13.sp)
            TextButton(onClick = { uri.openUri("https://www.who.int/news-room/fact-sheets/detail/healthy-diet") }) { Text("WHO · Healthy diet") }
            TextButton(onClick = { uri.openUri("https://www.fda.gov/food/nutrition-facts-label/daily-value-nutrition-and-supplement-facts-labels") }) { Text("FDA · Daily values") }
            TextButton(onClick = { uri.openUri("https://www.fna.usda.gov/cnpp/healthy-eating-index") }) { Text("USDA · Dietary pattern scoring") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
