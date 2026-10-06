@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.Manifest
import android.app.*
import android.content.*
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.*
import org.json.JSONObject
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

private val recipeMealLabels = listOf("Breakfast", "Lunch & dinner", "Soups", "Sides", "Snacks", "Desserts", "Drinks", "Other recipes")

/** Declared meal types are authoritative; tags and titles support legacy records only. */
internal fun recipeMealGroup(recipe: JSONObject): Int {
    fun group(value: String): Int? {
        val text = value.lowercase()
        return when {
            Regex("\\b(?:breakfast|brunch)\\b").containsMatchIn(text) -> 0
            Regex("\\b(?:lunch|dinner|main|supper)\\b").containsMatchIn(text) -> 1
            Regex("\\bsoups?\\b").containsMatchIn(text) -> 2
            Regex("\\bsides?\\b").containsMatchIn(text) -> 3
            Regex("\\bsnacks?\\b").containsMatchIn(text) -> 4
            Regex("\\b(?:desserts?|sweets?|treats?)\\b").containsMatchIn(text) -> 5
            Regex("\\b(?:drinks?|beverages?)\\b").containsMatchIn(text) -> 6
            else -> null
        }
    }
    val primary = recipe.s("meal_type").trim()
    if (primary.isNotEmpty()) return group(primary) ?: 7
    val declared = recipe.a("meal_types").strings().filter { it.isNotBlank() }
    if (declared.isNotEmpty()) return declared.firstNotNullOfOrNull(::group) ?: 7
    recipe.a("tags").strings().firstNotNullOfOrNull(::group)?.let { return it }
    val title = recipe.s("title").lowercase()
    return when {
        Regex("oats|yogurt|muffin|pancake|breakfast|granola").containsMatchIn(title) -> 0
        Regex("soup|broth").containsMatchIn(title) -> 2
        else -> 1
    }
}

@Composable
fun RecipesScreen(vm: GardenModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    val all = vm.snapshot.a("recipes").objects()
    val notReady = all.count { !recipeReady(it) }
    val visible = all.filter { r ->
        (showAll || savedOnly || recipeReady(r) && (r.s("parent_recipe_id").isBlank() || all.none { it.s("id") == r.s("parent_recipe_id") && recipeReady(it) })) &&
            (!savedOnly || vm.prefs.getBoolean("favorite:${r.s("id")}", false)) &&
            (query.isBlank() || r.toString().contains(query, ignoreCase = true))
    }
    val groups = visible.groupBy(::recipeMealGroup).toSortedMap()
    LazyColumn(
        Modifier.fillMaxSize().testTag("recipes-scroll"),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Heading(if (showAll) "All recipes" else "Recipes", action = {
                IconButton(onClick = { vm.ask("Help me find a recipe to cook from my collection, or develop something new.", origin = "recipes") }) {
                    Icon(Icons.Outlined.HelpOutline, "Ask about recipes", tint = Forest)
                }
            })
        }
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Find a dish or ingredient") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showAll && !savedOnly, onClick = { showAll = false; savedOnly = false }, label = { Text("Ready") })
                FilterChip(selected = savedOnly, onClick = { savedOnly = !savedOnly }, label = { Text("Saved") })
            }
        }
        groups.forEach { (kind, group) ->
            item { SectionLabel(recipeMealLabels[kind]) }
            item {
                GardenCard {
                    group.sortedWith(compareBy<JSONObject> { !recipeReady(it) }.thenBy { it.s("title") }).forEachIndexed { row, recipe ->
                        if (row > 0) HorizontalDivider(color = Line)
                        val children = all.filter { it.s("parent_recipe_id") == recipe.s("id") && recipeReady(it) }
                        RecipeShelfRow(recipe, children.size) { vm.selectedRecipe = recipe.s("id") }
                    }
                }
            }
        }
        if (visible.isEmpty()) item { Text("No recipes found", color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
        if (!showAll && notReady > 0) item {
            TextButton(onClick = { showAll = true; savedOnly = false }, modifier = Modifier.testTag("recipes-not-ready")) {
                Text("$notReady more not ready to cook")
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RecipeShelfRow(recipe: JSONObject, variants: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GardenRecipePlate(recipe, Modifier.size(60.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recipe.s("title"), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            if (!recipeReady(recipe)) GardenChip("Not ready", tint = AmberLight)
            else if (variants > 0) GardenChip("$variants variant${if (variants == 1) "" else "s"}")
        }
        recipeDuration(recipe).takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.widthIn(max = 96.dp)) }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
    }
}

@Composable
fun RecipeScreen(vm: GardenModel, r: JSONObject) {
    val showNutrition = moduleEnabled(vm, "nutrition")
    val context = LocalContext.current
    val rid = r.s("id")
    val key = "$rid:${r.optInt("revision")}"
    var session by remember(key) { mutableIntStateOf(vm.prefs.getInt("session:$key", 0)) }
    val sk = "$key:$session"
    var portions by remember(sk) { mutableIntStateOf(vm.prefs.getInt("cook-yield:$sk", defaultCookPortions(r)).coerceIn(1, maxCookPortions(r))) }
    var portionCounts by remember(sk) { mutableStateOf(runCatching { JSONObject(vm.prefs.getString("cook-portions:$sk", "{}")!!) }.getOrDefault(JSONObject())) }
    var amountsMode by remember { mutableStateOf(vm.prefs.getString("ingredientAmounts", "Mixed") ?: "Mixed") }
    var amountRevision by remember { mutableIntStateOf(0) }
    var amountSettings by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        val previous = vm.prefs.getString("cook-day:$sk", null)
        if (previous != null && previous != cookDay(vm)) {
            r.a("steps").objects().indices.forEach { cancelTimer(context, "$sk:$it"); vm.stopTimer("$sk:$it") }
            session++; vm.prefs.edit().putInt("session:$key", session).apply()
        }
    }
    var saved by remember(rid) { mutableStateOf(vm.prefs.getBoolean("favorite:$rid", false)) }
    var focus by rememberSaveable(rid) { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    var reportText by rememberSaveable(sk) { mutableStateOf("") }
    var batchWeight by rememberSaveable(sk) { mutableStateOf("") }
    var cookRating by rememberSaveable(sk) { mutableStateOf<Int?>(null) }
    var cookAspects by remember(sk) { mutableStateOf(JSONObject()) }
    var reportSaved by remember(sk) { mutableStateOf(vm.prefs.getBoolean("report-saved:$sk", false)) }
    var adjust by remember { mutableStateOf(false) }
    var adjustText by rememberSaveable(rid) { mutableStateOf("") }
    val ready = recipeReady(r)
    val steps = r.a("steps").objects()
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Substitutions for this cook session: ingredient index -> what was used instead.
    var subs by remember(sk) { mutableStateOf(runCatching { org.json.JSONObject(vm.prefs.getString("subs:$sk", "{}")!!) }.getOrDefault(org.json.JSONObject())) }
    var disabledSubs by remember(sk) { mutableStateOf(runCatching { JSONObject(vm.prefs.getString("subs-disabled:$sk", "{}")!!) }.getOrDefault(JSONObject())) }
    val appliedSubs = JSONObject(subs.toString()).apply {
        r.a("ingredients").objects().indices.filter { disabledSubs.optBoolean("$it") }.forEach { remove("$it"); remove("reason:$it") }
    }
    var subFor by remember { mutableStateOf(-1) }
    fun saveSub(i: Int, text: String, reason: String? = null) {
        subs = org.json.JSONObject(subs.toString()).apply {
            if (text.isBlank()) { remove("$i"); remove("reason:$i") }
            else { put("$i", text.trim()); if (reason != null) { if (reason.isBlank()) remove("reason:$i") else put("reason:$i", reason.trim()) } }
        }
        disabledSubs = JSONObject(disabledSubs.toString()).apply { remove("$i") }
        vm.prefs.edit().putString("subs:$sk", subs.toString()).putString("subs-disabled:$sk", disabledSubs.toString()).apply()
        vm.track("cook_substitution", "recipeId" to rid, "ingredient" to i, "substitution" to text)
    }
    fun subsSummary(): String = r.a("ingredients").objects().withIndex()
        .filter { appliedSubs.has("${it.index}") }
        .joinToString("; ") { "${it.value.s("name")} → ${appliedSubs.getString("${it.index}")}${appliedSubs.optString("reason:${it.index}").takeIf { it.isNotBlank() }?.let { reason -> " ($reason)" }.orEmpty()}" }
    DisposableEffect(focus) {
        val window = (context as? Activity)?.window
        if (focus) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    if (report)
        AlertDialog(
            onDismissRequest = { report = false },
            title = { Text("How did it go?", fontFamily = FontFamily.Serif) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PortionDestinationRows(portions, portionCounts, r) { destination, count ->
                        portionCounts = JSONObject(portionCounts.toString()).put(destination, count)
                        vm.prefs.edit().putString("cook-portions:$sk", portionCounts.toString()).apply()
                    }
                    OutlinedTextField(batchWeight, { batchWeight = it }, label = { Text("Whole batch weight · g (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    ReactionControl(vm, sk, cookRating, cookAspects) { rating, aspects -> cookRating = rating; cookAspects = aspects }
                    OutlinedTextField(
                        value = reportText,
                        onValueChange = { reportText = it },
                        label = { Text("Your cooking notes") },
                        minLines = 4,
                    )

                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reportSaved && (batchWeight.isBlank() || batchWeight.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) && listOf("fridge", "freezer", "eatenNow").sumOf { portionCounts.optInt(it) } == portions,
                    onClick = {
                        vm.cooked(r, reportText + batchWeight.toDoubleOrNull()?.takeIf { it > 0 }?.let { "\nWhole batch: $it g." }.orEmpty() + "\n\nPortions: fridge ${portionCounts.optInt("fridge")}, freezer ${portionCounts.optInt("freezer")}, eaten now ${portionCounts.optInt("eatenNow")}." + subsSummary().takeIf { it.isNotBlank() }?.let { "\n\nSubstitutions: $it" }.orEmpty(),
                            portions, portionCounts, batchWeight.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }, appliedSubs, sk, cookRating, cookAspects) {
                            reportSaved = true
                            vm.prefs.edit().putBoolean("report-saved:$sk", true).apply()
                            report = false
                            reportText = ""
                        }
                    },
                ) {
                    Text("Save report")
                }
            },
            dismissButton = { TextButton(onClick = { report = false }) { Text("Later") } },
        )
    if (subFor >= 0) {
        val ingredient = r.a("ingredients").optJSONObject(subFor) ?: org.json.JSONObject()
        var text by remember(subFor) { mutableStateOf(subs.optString("$subFor")) }
        var reason by remember(subFor) { mutableStateOf(subs.optString("reason:$subFor")) }
        AlertDialog(
            onDismissRequest = { subFor = -1 },
            title = { Text("Instead of ${ingredient.s("name")}", fontFamily = FontFamily.Serif) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text("Replacement ingredient") }, singleLine = true)
                OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Why? (optional)") })
            } },
            confirmButton = {
                Row {
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        saveSub(subFor, text, reason)
                        vm.askInBackground("While cooking ${r.s("title")} (${r.s("id")}, rev ${r.optInt("revision")}), I used $text instead of ${ingredient.s("name")}. Does that change anything for this cook, like timing, liquid or amounts? Keep it short.", "substitution")
                        subFor = -1
                    }) { Text("Save & ask") }
                    TextButton(onClick = { saveSub(subFor, text, reason); subFor = -1 }) { Text("Save") }
                }
            },
            dismissButton = { if (subs.has("$subFor")) TextButton(onClick = { saveSub(subFor, ""); subFor = -1 }) { Text("Remove") } },
        )
    }
    if (adjust)
        AlertDialog(
            onDismissRequest = { adjust = false },
            title = { Text("Adjust this cook", fontFamily = FontFamily.Serif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = adjustText,
                        onValueChange = { adjustText = it },
                        label = { Text("What changed for this cook?") },
                        placeholder = {
                            Text("Different container, fewer portions, missing ingredient, guest…")
                        },
                        minLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = adjustText.isNotBlank(),
                    onClick = {
                        val situation = adjustText.trim()
                        adjust = false
                        adjustText = ""
                        vm.ask(
                            """I'm about to make ${r.s("title")} ($rid, revision ${r.optInt("revision")}).

For this cooking session only: $situation

Keep the saved recipe unchanged. First decide whether any adjustment is actually needed. Give me a concise, action-first response containing only the changes I need for this cook, with quantities at the point of use. Preserve the useful parts of the existing recipe. Ask a question only if you cannot proceed responsibly. If nothing needs to change, say that briefly. Include a native action back to this recipe when supported.""",
                            send = true,
                            origin = "recipe_adjustment",
                        )
                    },
                ) {
                    Text("Ask assistant")
                }
            },
            dismissButton = { TextButton(onClick = { adjust = false }) { Text("Cancel") } },
        )
    if (focus) {
        CookMode(vm, r, sk, portions, amountsMode, amountRevision, { amountRevision++ }, appliedSubs, portionCounts,
            { destination, count ->
                portionCounts = JSONObject(portionCounts.toString()).put(destination, count)
                vm.prefs.edit().putString("cook-portions:$sk", portionCounts.toString()).apply()
            }, { subFor = it }, { focus = false; report = true }, { focus = false }, {
                if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.selectedRecipe = null }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Recipes")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { adjust = true }) { Icon(Icons.Outlined.HelpOutline, "Ask or adjust this cook") }
            IconButton(onClick = {
                saved = !saved
                vm.prefs.edit().putBoolean("favorite:$rid", saved).apply()
                vm.track("recipe_favorite", "recipeId" to rid, "saved" to saved)
            }) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "Save recipe", tint = if (saved) Clay else Forest) }
        }
        LazyColumn(Modifier.weight(1f).testTag("recipe-scroll"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                val photo = vm.foodLog().firstNotNullOfOrNull { capture ->
                    val linked = capture.o("server").o("interpretation").s("matchedRecipeId") == rid || capture.o("interpretation").s("matchedRecipeId") == rid || capture.s("recipeId", capture.s("recipe_id")) == rid ||
                        capture.o("interpretation").a("items").objects().any { it.s("recipe_id", it.s("recipeId")) == rid || it.o("recipe").s("id") == rid }
                    if (linked) vm.capturePhotos(capture).firstOrNull()?.let { vm.capturePhotoFile(capture, it) }?.takeIf { it.exists() } else null
                }
                if (photo != null) LocalPhoto(photo, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(22.dp)))
                else GardenRecipePlate(r, Modifier.fillMaxWidth().height(100.dp))
                Heading(r.s("title"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { portions--; vm.prefs.edit().putInt("cook-yield:$sk", portions).remove("cook-portions:$sk").apply(); portionCounts = JSONObject() }, enabled = portions > 1) { Icon(Icons.Outlined.Remove, "Fewer portions") }
                    Text("$portions portions", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { portions++; vm.prefs.edit().putInt("cook-yield:$sk", portions).remove("cook-portions:$sk").apply(); portionCounts = JSONObject() }, enabled = portions < maxCookPortions(r)) { Icon(Icons.Outlined.Add, "More portions") }
                    Spacer(Modifier.weight(1f))
                    if (recipeDuration(r).isNotBlank()) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                        Text(" ${recipeDuration(r)}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (r.s("portion_limit_reason").isNotBlank()) item { Text(r.s("portion_limit_reason"), fontSize = 13.sp, color = Muted) }
            if (!ready) item {
                GardenChip("Not ready to cook", tint = AmberLight)
                ActionButton("Develop this recipe", Icons.Outlined.AutoAwesome) { vm.ask("Please review and normalize the candidate recipe ${r.s("title")} ($rid). Use its original source as evidence, adapt it for my current kitchen and preferences, and make a complete ready recipe.") }
            }
            else {
                item {
                    GardenPrimaryButton("Start cooking", onClick = { focus = true; vm.track("cook_open", "recipeId" to rid, "session" to sk) }, modifier = Modifier.fillMaxWidth().height(60.dp), icon = Icons.Outlined.RestaurantMenu)
                }
                val parent = vm.recipe(r.s("parent_recipe_id"))
                val children = vm.snapshot.a("recipes").objects().filter { it.s("parent_recipe_id") == rid && recipeReady(it) }
                val variantCanChange = !focus && runCatching {
                    val progress = JSONObject(vm.prefs.getString("cook-progress:$sk", "{}")!!)
                    progress.keys().asSequence().none { progress.optJSONObject(it)?.has("startedAt") == true }
                }.getOrDefault(false) && vm.timers.keys.none { it.startsWith("$sk:") }
                if (parent != null || children.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (parent != null) AssistChip(onClick = { vm.selectedRecipe = parent.s("id") }, label = { Text("From ${parent.s("title")}", maxLines = 2) })
                        if (parent != null && recipeReady(parent)) RecipeVariantSwitch(r, true, variantCanChange) { vm.selectedRecipe = parent.s("id") }
                        children.forEach { child -> RecipeVariantSwitch(child, false, variantCanChange) { vm.selectedRecipe = child.s("id") } }
                    }
                }
                val changes = recipeChanges(r, parent)
                if (changes.isNotEmpty() || subs.length() > 0) item {
                    GardenCard {
                        SectionLabel("Changed for this cook")
                        changes.forEach { change -> RecipeChangeRow(change) }
                        r.a("ingredients").objects().forEachIndexed { i, ingredient -> if (subs.has("$i")) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(ingredientIcon(ingredient), null, Modifier.size(30.dp), tint = Color.Unspecified)
                                Column(Modifier.weight(1f)) {
                                    Text("${ingredient.s("name")} → ${subs.getString("$i")}", fontSize = 14.sp)
                                    subs.optString("reason:$i").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Muted) }
                                }
                                Switch(modifier = Modifier.semantics { contentDescription = "Use ${subs.optString("$i")}" }, checked = !disabledSubs.optBoolean("$i"), onCheckedChange = { enabled ->
                                    disabledSubs = JSONObject(disabledSubs.toString()).put("$i", !enabled)
                                    vm.prefs.edit().putString("subs-disabled:$sk", disabledSubs.toString()).apply()
                                    vm.track("cook_substitution_toggle", "recipeId" to rid, "ingredient" to i, "enabled" to enabled)
                                })
                            }
                        } }
                    }
                }
                item {
                    GardenCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ingredients", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { amountSettings = true }) { Text(amountsMode); Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) }
                        }
                        r.a("ingredients").objects().indices.toList().chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { i ->
                                    Box(Modifier.weight(1f)) {
                                        RecipeIngredientTile(vm, r, i, portions, amountsMode, amountRevision, { amountRevision++ }, appliedSubs.optString("$i"), { subFor = i })
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                items(steps.withIndex().toList(), key = { "step${it.index}" }) { (i, originalStep) ->
                    val step = presentedCookStep(r, originalStep, portions, appliedSubs)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val activity = stepActivity(step)
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${i + 1}", color = Forest, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(stepSentence(step), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
                            Icon(activity.second, activity.first, Modifier.size(22.dp), tint = Color.Unspecified)
                        }
                        if (step.s("text") != stepSentence(step)) Text(step.s("text").removePrefix(stepSentence(step)).trim(), fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            stepIngredients(r, step).forEach { index ->
                                val ingredient = r.a("ingredients").getJSONObject(index)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ingredientIcon(ingredient), null, Modifier.size(24.dp), tint = Color.Unspecified)
                                    Text(ingredient.s("name"), fontSize = 11.sp, color = Muted)
                                }
                            }
                        }
                        stepSetting(step).takeIf { it.isNotBlank() }?.let { Text(it, color = Forest, fontSize = 13.sp) }
                        stepDoneness(step).takeIf { it.isNotBlank() }?.let { Text("Done when: $it", color = Forest, fontSize = 13.sp) }
                    }
                }
                if (showNutrition) item { RecipeHealthCard(vm, r) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            steps.indices.forEach { index -> cancelTimer(context, "$sk:$index"); vm.stopTimer("$sk:$index") }
                            session++; vm.prefs.edit().putInt("session:$key", session).apply()
                            vm.track("cook_fresh_session", "recipeId" to rid)
                        }) { Text("Start a fresh session") }
                        TextButton(onClick = { report = true }) { Text("I cooked this") }
                    }
                }
            }
            item {
                val source = r.o("source").s("url")
                if (source.startsWith("https://")) {
                    val uri = LocalUriHandler.current
                    TextButton(onClick = { uri.openUri(source) }) { Text("View recipe source"); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(15.dp)) }
                }
            }
        }
    }
    if (amountSettings) AlertDialog(onDismissRequest = { amountSettings = false }, title = { Text("Amounts") }, text = {
        Column { listOf("Natural", "Mixed", "Exact").forEach { mode ->
            Row(Modifier.fillMaxWidth().clickable { amountsMode = mode; vm.prefs.edit().putString("ingredientAmounts", mode).apply(); amountRevision++; amountSettings = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = amountsMode == mode, onClick = null); Text(mode)
            }
        } }
    }, confirmButton = { TextButton(onClick = { amountSettings = false }) { Text("Close") } })
}



/** A saved variant is an executable whole; its quantities and instructions change together. */
@Composable
private fun RecipeVariantSwitch(variant: JSONObject, applied: Boolean, enabled: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (applied) "Use saved variant" else variant.s("title"), modifier = Modifier.weight(1f), style = GardenType.Body)
        Switch(checked = applied, enabled = enabled, onCheckedChange = { onChange() },
            modifier = Modifier.testTag("recipe-variant:${variant.s("id")}").semantics { contentDescription = "Use variant ${variant.s("title")}" })
    }
}

private data class RecipeChange(val what: String, val reason: String, val ingredient: JSONObject? = null, val appMade: Boolean = false)

/** Compare canonical variants, without pretending they can be individually reverted. */
private fun recipeChanges(recipe: JSONObject, parent: JSONObject?): List<RecipeChange> {
    if (parent == null) return emptyList()
    val appMade = recipe.optBoolean("app_made") || recipe.s("changed_by") in listOf("app", "assistant") || recipe.s("source_method").contains("agent") || recipe.o("source").s("type").contains("agent")
    val reason = recipe.s("variant_reason", recipe.o("source").s("note"))
    fun identity(ingredient: JSONObject) = ingredient.s("id", ingredient.s("name"))
    val prior = parent.a("ingredients").objects().associateBy(::identity)
    val ingredients = recipe.a("ingredients").objects()
    val result = ingredients.mapNotNull { ingredient ->
        val old = prior[identity(ingredient)]
        val changed = old == null || listOf("name", "amount", "unit", "detail").any { old.s(it) != ingredient.s(it) }
        if (!changed) null else RecipeChange(
            if (old == null) "${ingredient.s("name")} added" else "${ingredient.s("name")} · ${amountText(ingredient.s("amount"))} ${ingredient.s("unit")}".trim(),
            ingredient.s("change_reason", reason), ingredient, appMade || ingredient.optBoolean("app_made"))
    }.toMutableList()
    prior.values.filter { old -> ingredients.none { identity(it) == identity(old) } }.forEach { old ->
        result += RecipeChange("${old.s("name")} removed", reason, old, appMade)
    }
    recipe.a("steps").objects().forEachIndexed { i, step ->
        val old = parent.a("steps").optJSONObject(i)
        if (old == null || listOf("text", "equipment", "setting", "settings", "minutes", "passive_minutes", "timer_minutes").any { old.s(it) != step.s(it) }) {
            result += RecipeChange("Step ${i + 1} · ${stepSentence(step)}", step.s("change_reason", reason), appMade = appMade || step.optBoolean("app_made"))
        }
    }
    return result
}

@Composable
private fun RecipeChangeRow(change: RecipeChange) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        change.ingredient?.let { Icon(ingredientIcon(it), null, Modifier.size(30.dp), tint = Color.Unspecified) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (change.appMade) SparkleMark()
                Text(change.what, fontSize = 14.sp, lineHeight = 19.sp)
            }
            if (change.reason.isNotBlank()) Text(change.reason, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun RecipeIngredientTile(vm: GardenModel, recipe: JSONObject, index: Int, portions: Int, mode: String,
                                 amountRevision: Int, onFlip: () -> Unit, substitution: String, onSubstitute: () -> Unit) {
    val ingredient = recipe.a("ingredients").getJSONObject(index)
    // Reading the revision makes per-ingredient preference changes immediately visible.
    val amount = remember(recipe.s("id"), amountRevision, portions, mode, ingredient.toString()) { ingredientAmount(vm.prefs, recipe, ingredient, portions, mode) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Icon(ingredientIcon(ingredient), null, Modifier.size(30.dp), tint = Color.Unspecified)
        Column(Modifier.weight(1f)) {
            Text(ingredient.s("name"), fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
            if (substitution.isNotBlank()) Text("→ $substitution", fontSize = 12.sp, color = Forest)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amount, fontSize = 12.sp, color = Forest,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { flipIngredientAmount(vm.prefs, recipe, ingredient, mode); onFlip() }.heightIn(min = 48.dp).padding(vertical = 6.dp))
                IconButton(onClick = onSubstitute, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, "Substitute ${ingredient.s("name")}", Modifier.size(16.dp), tint = Muted)
                }
            }
        }
    }
}

/** 0.333 -> "⅓", 1.5 -> "1½"; falls back to the original text for anything unusual. */
fun amountText(raw: String): String {
    val v = raw.toDoubleOrNull() ?: return raw
    val whole = kotlin.math.floor(v).toInt()
    val part = v - whole
    val glyph = listOf(0.0 to "", 0.125 to "⅛", 0.25 to "¼", 1 / 3.0 to "⅓", 0.375 to "⅜", 0.5 to "½", 0.625 to "⅝", 2 / 3.0 to "⅔", 0.75 to "¾", 0.875 to "⅞", 1.0 to "+")
        .minByOrNull { kotlin.math.abs(it.first - part) }!!
    if (kotlin.math.abs(glyph.first - part) > 0.02) return raw.trimEnd('0').trimEnd('.')
    if (glyph.second == "+") return "${whole + 1}"
    return if (whole == 0) glyph.second.ifEmpty { "0" } else "$whole${glyph.second}"
}
