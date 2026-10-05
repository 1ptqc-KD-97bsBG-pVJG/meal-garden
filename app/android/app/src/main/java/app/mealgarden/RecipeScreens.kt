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
import kotlin.math.*
import org.json.JSONObject
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun RecipesScreen(vm: GardenModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Ready") }
    val all = vm.snapshot.a("recipes").objects()
    val recipes =
        all.filter { r ->
            (filter == "All" ||
                (filter == "Ready" && r.s("readiness") == "ready") ||
                (filter == "Saved" && vm.prefs.getBoolean("favorite:${r.s("id")}", false))) &&
                (r.toString().contains(query, ignoreCase = true))
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Heading("Recipes") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find a dish or ingredient") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ready", "Saved", "All").forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime),
                    )
                }
            }
        }
        item {
            Eyebrow(
                "${recipes.size} recipes · ${all.count{it.s("readiness")=="ready"}} ready to cook"
            )
        }
        items(recipes, key = { it.s("id") }) { r ->
            RecipeTile(r) { vm.selectedRecipe = r.s("id") }
        }
        if (recipes.isEmpty())
            item {
                Note(
                    "Nothing on this shelf yet. Try another search or save a recipe with the heart button."
                )
            }
        item {
            CardBox(color = Mist) {
                Eyebrow("TRY SOMETHING NEW")
                Text(
                    "A craving is a good place to start.",
                    fontFamily = FontFamily.Serif,
                    fontSize = 23.sp,
                )
                Text(
                    "Develop a new recipe around an ingredient, a favorite flavor, or your equipment.",
                    fontSize = 13.sp,
                    color = Muted,
                )
                TextButton(
                    onClick = {
                        vm.ask(
                            "I'd like to discover a new recipe that fits my health and cost priorities. Start with my existing collection, then help me explore."
                        )
                    }
                ) {
                    Text("Explore with your assistant")
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
fun RecipeScreen(vm: GardenModel, r: JSONObject) {
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
        if (previous != null && previous != cookDay()) {
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
    val ready = r.s("readiness") == "ready"
    val steps = r.a("steps").objects()
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Substitutions for this cook session: ingredient index -> what was used instead.
    var subs by remember(sk) { mutableStateOf(runCatching { org.json.JSONObject(vm.prefs.getString("subs:$sk", "{}")!!) }.getOrDefault(org.json.JSONObject())) }
    var subFor by remember { mutableStateOf(-1) }
    fun saveSub(i: Int, text: String, reason: String? = null) {
        subs = org.json.JSONObject(subs.toString()).apply {
            if (text.isBlank()) { remove("$i"); remove("reason:$i") }
            else { put("$i", text.trim()); if (reason != null) { if (reason.isBlank()) remove("reason:$i") else put("reason:$i", reason.trim()) } }
        }
        vm.prefs.edit().putString("subs:$sk", subs.toString()).apply()
        vm.track("cook_substitution", "recipeId" to rid, "ingredient" to i, "substitution" to text)
    }
    fun subsSummary(): String = r.a("ingredients").objects().withIndex()
        .filter { subs.has("${it.index}") }
        .joinToString("; ") { "${it.value.s("name")} → ${subs.getString("${it.index}")}${subs.optString("reason:${it.index}").takeIf { it.isNotBlank() }?.let { reason -> " ($reason)" }.orEmpty()}" }
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
                    PortionDestinationRows(portions, portionCounts) { destination, count ->
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
                            portions, portionCounts, batchWeight.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }, subs, sk, cookRating, cookAspects) {
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
                OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text("e.g. frozen jasmine rice") }, singleLine = true)
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
                    Text(
                        "Tell your assistant what changed. Your saved recipe stays intact.",
                        fontSize = 14.sp,
                    )
                    OutlinedTextField(
                        value = adjustText,
                        onValueChange = { adjustText = it },
                        label = { Text("What changed for this cook?") },
                        placeholder = {
                            Text("Different container, fewer portions, missing ingredient, guest…")
                        },
                        minLines = 4,
                    )
                    Text(
                        "The assistant will decide whether the recipe needs to change and return only the useful next steps.",
                        fontSize = 12.sp,
                        color = Muted,
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
        CookMode(vm, r, sk, portions, amountsMode, amountRevision, { amountRevision++ }, subs, portionCounts,
            { destination, count ->
                portionCounts = JSONObject(portionCounts.toString()).put(destination, count)
                vm.prefs.edit().putString("cook-portions:$sk", portionCounts.toString()).apply()
            }, { subFor = it }, { focus = false; report = true }, { focus = false }, {
                if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.selectedRecipe = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Recipes", Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { adjust = true }) { Icon(Icons.Outlined.HelpOutline, "Ask or adjust this cook") }
            IconButton(onClick = {
                saved = !saved
                vm.prefs.edit().putBoolean("favorite:$rid", saved).apply()
                vm.track("recipe_favorite", "recipeId" to rid, "saved" to saved)
            }) { Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "Save recipe", tint = if (saved) Clay else Forest) }
        }
        LazyColumn(Modifier.weight(1f).testTag("recipe-scroll"), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 160.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Heading(r.s("title"))
                val photo = vm.foodLog().firstNotNullOfOrNull { capture ->
                    val linked = capture.o("server").o("interpretation").s("matchedRecipeId") == rid || capture.o("interpretation").s("matchedRecipeId") == rid || capture.s("recipeId", capture.s("recipe_id")) == rid ||
                        capture.o("interpretation").a("items").objects().any { it.s("recipe_id", it.s("recipeId")) == rid || it.o("recipe").s("id") == rid }
                    if (linked) vm.capturePhotos(capture).firstOrNull()?.let { vm.capturePhotoFile(capture, it) }?.takeIf { it.exists() } else null
                }
                if (photo != null) LocalPhoto(photo, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(22.dp)))
                else GardenBowl(Modifier.fillMaxWidth().height(130.dp), seed = rid.hashCode())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { portions--; vm.prefs.edit().putInt("cook-yield:$sk", portions).remove("cook-portions:$sk").apply(); portionCounts = JSONObject() }, enabled = portions > 1) { Icon(Icons.Outlined.Remove, "Fewer portions") }
                    Text("$portions portions", fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { portions++; vm.prefs.edit().putInt("cook-yield:$sk", portions).remove("cook-portions:$sk").apply(); portionCounts = JSONObject() }, enabled = portions < maxCookPortions(r)) { Icon(Icons.Outlined.Add, "More portions") }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                    Text(" ${r.optInt("total_minutes")} min", fontSize = 13.sp)
                }
            }
            if (r.s("portion_limit_reason").isNotBlank()) item { Text(r.s("portion_limit_reason"), fontSize = 13.sp, color = Muted) }
            if (!ready) item {
                Note("This archive entry needs ingredient and method review before cooking or shopping.")
                ActionButton("Develop this recipe", Icons.Outlined.AutoAwesome) { vm.ask("Please review and normalize the candidate recipe ${r.s("title")} ($rid). Use its original source as evidence, adapt it for my current kitchen and preferences, and make a complete ready recipe.") }
            }
            else {
                val parent = vm.recipe(r.s("parent_recipe_id"))
                if (r.s("parent_recipe_id").isNotBlank() || subs.length() > 0) item {
                    SectionLabel("Changed for this cook")
                    if (parent != null) {
                        val prior = parent.a("ingredients").objects().associateBy { it.s("id") }
                        r.a("ingredients").objects().forEach { a ->
                            val original = prior[a.s("id")]
                            if (original == null || original.s("amount") != a.s("amount") || original.s("unit") != a.s("unit") || original.s("detail") != a.s("detail")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (a.optBoolean("app_made") || r.optBoolean("app_made") || r.s("changed_by") in listOf("app", "assistant") || r.s("source_method").contains("agent") || r.o("source").s("type").contains("agent")) Icon(Icons.Outlined.AutoAwesome, "App change", Modifier.size(18.dp))
                                    Text("${a.s("name")} · ${amountText(a.s("amount"))} ${a.s("unit")}\n${a.s("change_reason", r.s("variant_reason", r.o("source").s("note", "Saved recipe variant")))}", fontSize = 13.sp)
                                }
                            }
                        }
                        prior.values.filter { old -> r.a("ingredients").objects().none { it.s("id") == old.s("id") } }.forEach { old -> Text("${old.s("name")} removed · ${r.s("variant_reason", "Saved recipe variant")}", fontSize = 13.sp) }
                        steps.forEachIndexed { i, step ->
                            val old = parent.a("steps").optJSONObject(i)
                            if (old == null || old.s("text") != step.s("text") || old.s("equipment") != step.s("equipment")) {
                                Text("Step ${i + 1} · ${stepSentence(step)}\n${step.s("change_reason", r.s("variant_reason", r.o("source").s("note", "Reason not recorded")))}", fontSize = 13.sp)
                            }
                        }
                    } else if (r.s("parent_recipe_id").isNotBlank()) Text(r.s("variant_reason", r.o("source").s("note", "Saved recipe variant")), fontSize = 13.sp)
                    r.a("ingredients").objects().forEachIndexed { i, a -> if (subs.has("$i")) {
                        Text("${a.s("name")} → ${subs.getString("$i")}\n${subs.optString("reason:$i", "Reason not recorded")}", fontSize = 13.sp)
                    } }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ingredients", Modifier.weight(1f), fontSize = 19.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { amountSettings = true }) { Text(amountsMode); Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) }
                    }
                }
                items(r.a("ingredients").objects().withIndex().toList(), key = { "ingredient${it.index}" }) { (i, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CookIngredientRow(vm, r, i, portions, amountsMode, amountRevision, { amountRevision++ }, subs.optString("$i")) }
                        IconButton(onClick = { subFor = i }) { Icon(Icons.Outlined.SwapHoriz, "I substituted this", Modifier.size(18.dp), tint = Muted) }
                    }
                }
                item {
                    Button(onClick = { focus = true; vm.track("cook_open", "recipeId" to rid, "session" to sk) }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) { Text("Start cooking", fontSize = 18.sp) }
                }
                items(steps.withIndex().toList(), key = { "step${it.index}" }) { (i, originalStep) ->
                    val step = scaledCookStep(r, originalStep, portions)
                    CardBox {
                        val activity = stepActivity(step)
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${i + 1}", color = Forest, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(stepSentence(step), Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 28.sp)
                            Icon(activity.second, activity.first, Modifier.size(22.dp), tint = Color.Unspecified)
                        }
                        if (step.s("text") != stepSentence(step)) Text(step.s("text").removePrefix(stepSentence(step)).trim(), fontSize = 14.sp, lineHeight = 23.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            stepIngredients(r, step).forEach { index ->
                                val ingredient = r.a("ingredients").getJSONObject(index)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ingredientIcon(ingredient), null, Modifier.size(16.dp), tint = Color.Unspecified)
                                    Text(ingredient.s("name"), fontSize = 11.sp, color = Muted)
                                }
                            }
                        }
                        stepSetting(step).takeIf { it.isNotBlank() }?.let { Text(it, color = Forest, fontSize = 13.sp) }
                        stepDoneness(step).takeIf { it.isNotBlank() }?.let { Text("Done when: $it", color = Forest, fontSize = 13.sp) }
                    }
                }
                item { RecipeHealthCard(vm, r) }
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
