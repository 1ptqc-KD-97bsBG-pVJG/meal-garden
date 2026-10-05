@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mealgarden

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag

private val Amber = Color(0xFFD9A441)

/** Marks shown on every reset row, in order. */
private val marks = listOf(
    Triple("looks_ok", Icons.Outlined.CheckCircle, "fine"),
    Triple("use_soon", Icons.Outlined.Schedule, "soon"),
    Triple("gone", Icons.Outlined.RemoveCircleOutline, "gone"),
    Triple("discard", Icons.Outlined.DeleteOutline, "toss"),
)

private fun markColor(state: String) = when (state) {
    "looks_ok" -> Forest; "use_soon" -> Amber; "discard" -> Clay; else -> Muted
}

/** Kitchen reset: triage what's here (computed on the laptop from receipts, pantry lots and checks), then plan from it. */
@Composable
fun FridgeCheckScreen(vm: GardenModel) {
    if (vm.snapshot.optJSONArray("pantry") != null) { GraphKitchenScreen(vm); return }
    val reset = vm.snapshot.optJSONObject("kitchenReset")
    var showStable by rememberSaveable { mutableStateOf(false) }
    var showChecked by rememberSaveable { mutableStateOf(false) }
    var showGone by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 20.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openFridgeCheck = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Kitchen reset", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.noteRequests++ }) { Icon(Icons.Outlined.EditNote, "Leave an app note", tint = Muted) }
            reset?.optJSONObject("counts")?.let { c ->
                val total = c.optInt("perishable").coerceAtLeast(1)
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { c.optInt("checked") / total.toFloat() }, Modifier.size(40.dp), color = Forest, trackColor = Mist, strokeWidth = 4.dp)
                    Text("${c.optInt("toCheck")}", fontSize = 13.sp, color = Forest)
                }
            }
        }
        if (reset == null) {
            Note("Connect to the laptop to load what's in your kitchen.")
            return
        }
        val items = reset.a("items").objects().map { item ->
            vm.pendingMark(item.s("fullName"))?.let { JSONObject(item.toString()).put("state", it) } ?: item
        }
        val unchecked = items.filter { it.s("state") == "unchecked" && it.s("risk") != "stable" }
        val checkFirst = unchecked.filter { it.s("risk") in listOf("past", "soon", "unknown") }
        val recent = unchecked - checkFirst.toSet()
        val checked = items.filter { it.s("state") in listOf("looks_ok", "use_soon") && it.s("risk") != "stable" }
        val stable = items.filter { it.s("risk") == "stable" && it.s("state") !in listOf("gone", "discard") }
        val gone = items.filter { it.s("state") in listOf("gone", "discard") }
        val leftovers = reset.a("leftovers").objects()
        val resolved = reset.a("resolvedLeftovers").objects()
        val planned = reset.a("planned").objects().filter { !it.optBoolean("cooked") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Legend() }
            if (leftovers.isNotEmpty()) {
                item { Group("Leftovers", leftovers.size) }
                items(leftovers, key = { "l" + it.s("cookingId") }) { LeftoverRow(vm, it) }
            }
            if (resolved.isNotEmpty()) {
                item { Group("Leftovers you settled", resolved.size) }
                items(resolved, key = { "r" + it.s("cookingId") }) { LeftoverRow(vm, it, settled = true) }
            }
            if (checkFirst.isNotEmpty()) {
                item { Group("Check first", checkFirst.size) }
                items(checkFirst, key = { "i" + it.s("key") }) { ItemRow(vm, it) }
            }
            if (recent.isNotEmpty()) {
                item { Group("Recently bought", recent.size) }
                items(recent, key = { "i" + it.s("key") }) { ItemRow(vm, it) }
            }
            if (checked.isNotEmpty()) {
                item { Group("Checked", checked.size, if (showChecked) "hide" else "show") { showChecked = !showChecked } }
                if (showChecked) items(checked, key = { "i" + it.s("key") }) { ItemRow(vm, it) }
                else item { SummaryChips(checked) }
            }
            item { Group("Pantry & freezer", stable.size, if (showStable) "hide" else "show") { showStable = !showStable } }
            if (showStable) items(stable, key = { "i" + it.s("key") }) { ItemRow(vm, it) }
            if (gone.isNotEmpty()) {
                item { Group("Gone or tossed", gone.size, if (showGone) "hide" else "change") { showGone = !showGone } }
                if (showGone) items(gone, key = { "i" + it.s("key") }) { ItemRow(vm, it) }
            }
            item {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Something else is here")
                }
            }
            if (planned.isNotEmpty()) {
                item { Group("Planned, not cooked", planned.size) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        planned.forEach { p ->
                            AssistChip(onClick = { vm.openFridgeCheck = false; vm.selectedRecipe = p.s("recipeId") }, label = { Text(p.s("title"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Outlined.MenuBook, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
        }
        // Planning dock: the laptop compiles the plan context from the marks above.
        Column(Modifier.fillMaxWidth().background(Mist).navigationBarsPadding().padding(16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(note, { note = it.take(2000) }, Modifier.fillMaxWidth(), maxLines = 4,
                placeholder = { Text("Anything else? e.g. tired tonight, 30 min max", fontSize = 13.sp) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.startTask("interview") }, enabled = !vm.busy) {
                    Icon(Icons.Outlined.Kitchen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Kitchen interview")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.startTask("reset_plan", note) }, enabled = !vm.busy, colors = ButtonDefaults.buttonColors(containerColor = Forest), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Plan from what's here")
                }
            }
            if (vm.outbox.isNotEmpty()) Text("${vm.outbox.size} change${if (vm.outbox.size == 1) "" else "s"} waiting to sync", fontSize = 11.sp, color = Muted)
        }
    }
    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("What else is here?") },
            text = { OutlinedTextField(name, { name = it.take(150) }, singleLine = true, placeholder = { Text("e.g. half a bag of frozen peas") }) },
            confirmButton = { TextButton(onClick = { vm.markItem(name.trim(), "looks_ok"); adding = false }, enabled = name.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End)) {
        marks.forEach { (state, icon, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = markColor(state)); Spacer(Modifier.width(3.dp)); Text(label, fontSize = 11.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun Group(title: String, count: Int, action: String = "", onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 19.sp, color = Ink)
        Spacer(Modifier.width(8.dp))
        Text("$count", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
        if (action.isNotEmpty()) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Age against typical shelf life: a short bar that turns amber, then clay, as the window closes. */
@Composable
private fun FreshnessBar(age: Int?, typical: Int?) {
    val fraction = if (age == null || typical == null || typical <= 0) null else (age / typical.toFloat())
    val color = when { fraction == null -> Line; fraction >= 1f -> Clay; fraction >= .6f -> Amber; else -> Forest }
    Box(Modifier.width(56.dp).height(5.dp).clip(CircleShape).background(Mist)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((fraction ?: 0f).coerceIn(.06f, 1f)).clip(CircleShape).background(color))
    }
}

@Composable
private fun ItemRow(vm: GardenModel, item: JSONObject) {
    val state = item.s("state")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.s("name"), fontSize = 15.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.s("risk") != "stable") FreshnessBar(item.optInt("ageDays", -1).takeIf { it >= 0 }, item.optInt("typicalDays", -1).takeIf { it > 0 })
                Text(listOfNotNull(
                    item.optInt("ageDays", -1).takeIf { it >= 0 }?.let { "${it}d" },
                    item.s("location").takeIf { it != "unknown" },
                ).joinToString(" · "), fontSize = 11.sp, color = Muted)
            }
        }
        marks.forEach { (value, icon, label) ->
            val selected = state == value
            IconButton(onClick = { vm.markItem(item.s("fullName"), value) }, modifier = Modifier.size(40.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (selected) markColor(value) else Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(icon, label, Modifier.size(20.dp), tint = if (selected) Color.White else markColor(value).copy(alpha = .75f))
                }
            }
        }
    }
}

@Composable
private fun SummaryChips(items: List<JSONObject>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Pill(it.s("name"), if (it.s("state") == "use_soon") Amber.copy(alpha = .35f) else Mist) }
    }
}

@Composable
private fun LeftoverRow(vm: GardenModel, leftover: JSONObject, settled: Boolean = false) {
    val id = leftover.s("cookingId")
    val age = leftover.optInt("ageDays")
    // A settled row shows the saved answer selected; tapping another chip corrects it.
    val pending = vm.pendingLeftover(id) ?: leftover.s("state").takeIf { settled }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, if (age > 4 && !settled) Clay else Line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(leftover.s("title"), fontSize = 15.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!settled) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FreshnessBar(age, 4)
            Text("cooked ${age}d ago${if (age > 4) " · past the 3–4 day fridge window" else ""}", fontSize = 11.sp, color = if (age > 4) Clay else Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("still_have" to "Still have", "eaten" to "Ate it", "frozen" to "Froze", "discarded" to "Tossed").forEach { (value, label) ->
                FilterChip(selected = pending == value, onClick = { vm.leftover(id, value) }, label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime))
            }
        }
    }
}

/** Converts only explicit mass units. Cups, cloves and substituted amounts stay unknown. */
fun ingredientGrams(ingredient: JSONObject): Double? {
    val amount = ingredient.optDouble("amount", Double.NaN).takeIf { it.isFinite() && it >= 0 } ?: return null
    return when (ingredient.s("unit").lowercase()) {
        "g", "gram", "grams" -> amount
        "kg" -> amount * 1000
        "oz" -> amount * 28.349523125
        "lb", "lbs" -> amount * 453.59237
        else -> null
    }
}

private fun number(value: Double) = if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

/** Receipt evidence supplies a full-lot reference, never the current amount. Ambiguous matches stay unknown. */
fun pantryPackage(snapshot: JSONObject, item: JSONObject): Pair<Double, Double>? {
    val name = item.s("name").lowercase().trim()
    val lines = snapshot.a("receipts").objects().filter { it.s("date").take(10) == item.s("purchased_on").take(10) }
        .flatMap { it.a("items").objects() }.filter { it.s("name").lowercase().replace(Regex("\\s*[-–]\\s*\\d.*$"), "").trim() == name }
    val line = lines.singleOrNull() ?: return null
    val quantity = line.s("quantity")
    val parsed = Regex("^(\\d+(?:\\.\\d+)?)\\s*[×x]\\s*(\\d+(?:\\.\\d+)?)\\s*(kg|g|oz|lb|ct|count|ml|l)\\b", RegexOption.IGNORE_CASE).find(quantity)
    if (parsed != null) {
        val packages = parsed.groupValues[1].toDouble()
        val size = parsed.groupValues[2].toDouble()
        val unit = parsed.groupValues[3].lowercase()
        val base = item.s("base_unit")
        val converted = when {
            base == "g" -> ingredientGrams(j("amount" to size, "unit" to unit))
            base == "ml" && unit == "ml" -> size
            base == "ml" && unit == "l" -> size * 1000
            base == "count" && unit in listOf("ct", "count") -> size
            else -> null
        } ?: return null
        return converted to packages
    }
    val count = quantity.toDoubleOrNull()?.takeIf { it > 0 }
    return if (item.s("base_unit") == "count" && count != null) 1.0 to count else null
}

fun batchPortions(batch: JSONObject, amount: Double?): Double? {
    val yield = batch.optDouble("yield_g", Double.NaN)
    val made = batch.optDouble("portions_made", Double.NaN)
    return if (amount != null && yield.isFinite() && yield > 0 && made.isFinite()) amount * made / yield else null
}

@Composable
private fun GraphKitchenScreen(vm: GardenModel) {
    val pantry = vm.graphPantry()
    val assumptions = vm.graphAssumptions()
    var correcting by remember { mutableStateOf<JSONObject?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var foodName by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var foodUnit by rememberSaveable { mutableStateOf("g") }
    var foodLocation by rememberSaveable { mutableStateOf("fridge") }
    var expandedKinds by remember { mutableStateOf(setOf<String>()) }
    var receiptDate by rememberSaveable { mutableStateOf("") }
    var showSettled by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    val batches = vm.snapshot.a("batches").objects()
    val batchItems = batches.map { it.s("pantry_item_id") }.toSet()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = { vm.track("kitchen_back"); vm.openFridgeCheck = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Kitchen check", fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.noteRequests++ }) { Icon(Icons.Outlined.EditNote, "Leave an app note") }
        }
        LazyColumn(Modifier.weight(1f).testTag("graph-kitchen"), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TextButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, null); Text("Add food") } }
            vm.outbox.filter { it.s("route") == "/api/pantry/add" }.forEach { pending ->
                item { Text("${pending.o("payload").s("name")} · waiting to sync", fontSize = 13.sp) }
            }
            if (assumptions.isNotEmpty()) {
                item {
                    Text("Here's what I assumed", fontFamily = FontFamily.Serif, fontSize = 22.sp)
                    Button(onClick = { vm.resolveAssumptions(assumptions.map { it.s("id") }, "confirmed") }) { Text("Looks right") }
                }
                listOf("pantry" to "Pantry", "cooking" to "Cooking", "preferences" to "Preferences").forEach { (kind, title) ->
                    val group = assumptions.filter { it.s("kind", "pantry") == kind }
                    if (group.isNotEmpty()) {
                        item {
                            Group(title, group.size, if (kind in expandedKinds) "Less" else "Show all") {
                                expandedKinds = if (kind in expandedKinds) expandedKinds - kind else expandedKinds + kind
                            }
                        }
                        items(if (kind in expandedKinds) group else group.take(if (kind == "preferences") 2 else 0), key = { "assumption:${it.s("id")}" }) { assumption ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mist).clickable {
                                vm.track("assumption_open", "id" to assumption.s("id")); correcting = assumption
                            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, "Assumed", Modifier.size(18.dp), tint = Forest)
                                Text(assumption.s("statement").removePrefix("Confirm imported preference: "), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.Edit, "Correct", Modifier.size(18.dp), tint = Muted)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, "Assumed", Modifier.size(16.dp)); Text("Assumed", fontSize = 12.sp)
                    FreshnessBar(1, 4); Text("Freshness", fontSize = 12.sp)
                }
            }
            val active = pantry.filter { it.optDouble("balance", Double.NaN) != 0.0 }
            val leftovers = active.filter { it.s("id") in batchItems }
            if (leftovers.isNotEmpty()) item { Group("Leftovers", leftovers.size) }
            items(leftovers, key = { it.s("id") }) { item ->
                GraphPantryRow(vm, item, batches.firstOrNull { it.s("pantry_item_id") == item.s("id") }) { receiptDate = it }
            }
            val foods = active.filter { it.s("id") !in batchItems }.sortedBy { when (it.s("urgency")) { "past" -> 0; "soon" -> 1; "unknown" -> 2; "fresh" -> 3; else -> 4 } }
            if (foods.isNotEmpty()) item { Group("At home", foods.size) }
            items(foods, key = { it.s("id") }) { GraphPantryRow(vm, it, null) { date -> receiptDate = date } }
            val settled = pantry.filter { it.optDouble("balance", Double.NaN) == 0.0 }
            if (settled.isNotEmpty()) {
                item { Group("Used up or tossed", settled.size, if (showSettled) "Hide" else "Change") { vm.track("pantry_settled_toggle"); showSettled = !showSettled } }
                if (showSettled) items(settled, key = { it.s("id") }) { item -> GraphPantryRow(vm, item, batches.firstOrNull { it.s("pantry_item_id") == item.s("id") }) { receiptDate = it } }
            }
        }
        Column(Modifier.fillMaxWidth().background(Mist).padding(16.dp, 8.dp)) {
            OutlinedTextField(note, { note = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("Anything else?") }, maxLines = 2)
            Button(onClick = { vm.startTask("reset_plan", note) }, enabled = !vm.busy, modifier = Modifier.align(Alignment.End)) { Text("Plan from what's here") }
            if (vm.outbox.isNotEmpty()) Text("${vm.outbox.size} waiting to sync", fontSize = 11.sp, color = Muted)
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add food") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(foodName, { foodName = it.take(250) }, label = { Text("Food or product name") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("fridge", "freezer", "pantry", "counter").forEach { location -> FilterChip(foodLocation == location, { foodLocation = location }, label = { Text(location) }) }
            }
            val existing = pantry.filter { it.s("name").equals(foodName.trim(), ignoreCase = true) }.distinctBy { it.s("product_id") }.singleOrNull()
            if (existing != null) Text("${existing.s("name")} · ${existing.s("base_unit")}")
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("g", "ml", "count").forEach { unit -> FilterChip(foodUnit == unit, { foodUnit = unit }, label = { Text(unit) }) }
            }
            OutlinedTextField(amountText, { amountText = it }, label = { Text("Amount · ${existing?.s("base_unit") ?: foodUnit}") }, placeholder = { Text("Unknown") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = foodName.isNotBlank() && (amountText.isBlank() || amountText.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true), onClick = {
        val existing = pantry.filter { it.s("name").equals(foodName.trim(), ignoreCase = true) }.distinctBy { it.s("product_id") }.singleOrNull()
        vm.graphWrite("/api/pantry/add", j("name" to foodName.trim(), "productId" to existing?.s("product_id"), "baseUnit" to (existing?.s("base_unit") ?: foodUnit), "amount" to amountText.toDoubleOrNull(), "location" to foodLocation), "pantry_add")
        adding = false; foodName = ""; amountText = ""
    }) { Text("Save food") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    correcting?.let { assumption ->
        AlertDialog(onDismissRequest = { vm.track("assumption_cancel"); correcting = null }, title = { Text("Correct assumption") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(assumption.s("statement"))
                if (assumption.s("componentId").isNotBlank()) {
                    pantry.filter { it.s("product_id") == assumption.s("productId") }.forEach { lot ->
                        TextButton(onClick = {
                            vm.graphWrite("/api/intake/allocate", j("componentId" to assumption.s("componentId"), "itemId" to lot.s("id")), "intake_allocate")
                            correcting = null
                        }) { Text("Use ${lot.s("location")} lot · ${lot.s("purchased_on").take(10).ifBlank { lot.s("created_at").take(10) }} · ${lot.s("balance")} ${lot.s("base_unit")}") }
                    }
                }
                val preferences = vm.graphPreferences().filter { it.s("id") in assumption.a("evidence").strings() }
                preferences.forEach { preference -> TextButton(onClick = {
                    vm.changePreference(preference, null, null); correcting = null
                }) { Text("Remove preference") } }
            } },
            confirmButton = { TextButton(onClick = { vm.resolveAssumptions(listOf(assumption.s("id")), "corrected"); correcting = null }) { Text("Undo assumption") } },
            dismissButton = { TextButton(onClick = { vm.track("assumption_cancel"); correcting = null }) { Text("Cancel") } })
    }
    if (receiptDate.isNotEmpty()) AlertDialog(onDismissRequest = { vm.track("receipt_close"); receiptDate = "" }, title = { Text("Bought $receiptDate") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.snapshot.a("receipts").objects().filter { it.s("date").take(10) == receiptDate }.forEach { receipt ->
                Text(receipt.s("store"), fontFamily = FontFamily.Serif)
                receipt.a("items").objects().forEach { Text("${it.s("name")} · ${it.s("quantity")}", fontSize = 14.sp) }
            }
        }
    }, confirmButton = { TextButton(onClick = { vm.track("receipt_close"); receiptDate = "" }) { Text("Close") } })
}

@Composable
private fun GraphPantryRow(vm: GardenModel, item: JSONObject, batch: JSONObject?, onReceipt: (String) -> Unit) {
    val id = item.s("id")
    val balance = item.optDouble("balance", Double.NaN).takeIf { it.isFinite() }
    val pack = pantryPackage(vm.snapshot, item)
    val full = if (batch != null) batch.optDouble("yield_g", Double.NaN).takeIf { it.isFinite() && it > 0 } else pack?.let { it.first * it.second }
    val useCount = batch == null && item.s("kind") == "packaged" && pack != null && pack.second > 1
    var settingAmount by rememberSaveable(id) { mutableStateOf(false) }
    var fullText by rememberSaveable(id) { mutableStateOf("") }
    var fractionToSet by rememberSaveable(id) { mutableStateOf<Double?>(null) }
    val pending = vm.outbox.lastOrNull { it.o("payload").s("itemId") == id }
    val state = when {
        pending?.s("route") == "/api/pantry/toss" -> "tossed"
        balance == 0.0 -> "gone"
        item.s("condition") == "use_soon" -> "use_soon"
        item.s("condition") == "fine" -> "fine"
        else -> ""
    }
    val suggestion = if (state.isEmpty()) when (item.s("urgency")) { "soon", "past" -> "use_soon"; "fresh" -> "fine"; else -> "" } else ""
    fun count(value: Double, confidence: String = "known") = vm.graphWrite("/api/pantry/count", j("itemId" to id, "amount" to value, "confidence" to confidence), "pantry_amount")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.s("name"), fontSize = 17.sp, color = Ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!item.isNull("typicalDays") && item.optInt("typicalDays") > 0) FreshnessBar(item.optInt("ageDays", -1).takeIf { it >= 0 }, item.optInt("typicalDays"))
            else item.s("purchased_on").take(10).takeIf { it.isNotEmpty() }?.let { date ->
                Text("bought $date", fontSize = 12.sp, color = Forest, modifier = Modifier.clickable { vm.track("pantry_receipt", "date" to date); onReceipt(date) })
            }
            Text(item.s("location"), fontSize = 12.sp, color = Muted)
        }
        if (balance != null) {
            val portions = batch?.let { batchPortions(it, balance) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.s("basis") != "known") Icon(Icons.Outlined.AutoAwesome, "Estimated amount", Modifier.size(14.dp), tint = Forest)
                Text(if (balance < 0) "Amount needs checking" else if (batch != null && portions != null) "${number(portions)} portions left" else "${number(balance)} ${item.s("base_unit")} left", fontSize = 13.sp, color = Muted)
            }
            if (useCount) Row(verticalAlignment = Alignment.CenterVertically) {
                val packages = balance / pack!!.first
                IconButton(onClick = { count((balance - pack.first).coerceAtLeast(0.0)) }) { Icon(Icons.Outlined.Remove, "One package less") }
                Text("${number(packages)} packages")
                IconButton(onClick = { count(balance + pack.first) }) { Icon(Icons.Outlined.Add, "One package more") }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Little" to .25, "Half" to .5, "Most" to .75, "Full" to 1.0).forEach { (label, fraction) ->
                    FilterChip(selected = full != null && full > 0 && kotlin.math.abs(balance / full - fraction) < .01, onClick = {
                        if (full != null && full > 0) count(full * fraction, "assumed")
                        else { vm.track("pantry_full_amount_open", "itemId" to id, "fraction" to fraction); fractionToSet = fraction; fullText = ""; settingAmount = true }
                    }, label = { Text(label) })
                }
            }
        }
        TextButton(modifier = Modifier.testTag("pantry-count:$id"), onClick = { vm.track("pantry_amount_open", "itemId" to id); fractionToSet = null; fullText = ""; settingAmount = true }) { Text("Set amount") }
        // A same-day count/mark already answers presence. Keep the controls editable.
        val confirmedToday = runCatching {
            java.time.OffsetDateTime.parse(item.s("last_confirmed_at"))
                .atZoneSameInstant(householdZone).toLocalDate() ==
                java.time.LocalDate.now(householdZone)
        }.getOrDefault(false)
        if ((!confirmedToday && pending == null) || balance?.let { it < 0 } == true) Text("Still have it?", fontSize = 15.sp)
        listOf("Have it" to listOf("fine" to "Fine", "use_soon" to "Use soon"), "Don't" to listOf("gone" to "Used up", "tossed" to "Tossed")).forEach { (group, choices) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group, fontSize = 12.sp, color = Muted, modifier = Modifier.width(52.dp))
                choices.forEach { (value, label) ->
                    FilterChip(selected = state == value, onClick = {
                        when (value) {
                            "fine", "use_soon" -> {
                                if (balance == 0.0) vm.graphWrite("/api/pantry/count", j("itemId" to id, "amount" to null), "pantry_restore_unknown")
                                vm.graphWrite("/api/pantry/condition", j("itemId" to id, "condition" to value), "pantry_$value")
                            }
                            "gone" -> count(0.0)
                            else -> vm.graphWrite("/api/pantry/toss", j("itemId" to id), "pantry_toss")
                        }
                    }, label = { Text(if (batch != null && value == "gone") "Ate it" else if (batch != null && value == "fine") "Still have" else label, fontSize = 12.sp) },
                        leadingIcon = if (suggestion == value) ({ Icon(Icons.Outlined.AutoAwesome, "Suggested", Modifier.size(14.dp)) }) else null)
                }
            }
        }
        if (item.s("location") != "freezer") TextButton(onClick = {
            vm.graphWrite("/api/pantry/transfer", j("itemId" to id, "amount" to null, "toLocation" to "freezer"), "pantry_freeze")
        }) { Icon(Icons.Outlined.AcUnit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Move to freezer") }
    }
    if (settingAmount) AlertDialog(onDismissRequest = { vm.track("pantry_amount_cancel"); settingAmount = false }, title = { Text(if (fractionToSet == null) "Amount left" else "Full amount") }, text = {
        OutlinedTextField(fullText, { fullText = it }, label = { Text(item.s("base_unit")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
    }, confirmButton = { TextButton(enabled = fullText.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true, onClick = {
        count(fullText.toDouble() * (fractionToSet ?: 1.0), if (fractionToSet == null) "known" else "assumed"); settingAmount = false
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { vm.track("pantry_amount_cancel"); settingAmount = false }) { Text("Cancel") } })
}

@Composable
fun ReactionControl(vm: GardenModel, key: String, rating: Int?, aspects: JSONObject, onChange: (Int?, JSONObject) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rating", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(rating?.let { "$it / 10" } ?: "Optional", fontSize = 12.sp, color = Muted)
            if (rating != null) TextButton(onClick = { vm.track("rating_clear", "target" to key); onChange(null, JSONObject()) }) { Text("Clear") }
        }
        Slider(value = (rating ?: 5).toFloat(), onValueChange = { value ->
            onChange(value.roundToInt(), aspects)
        }, onValueChangeFinished = { vm.track("rating_change", "target" to key) }, valueRange = 1f..10f, steps = 8)
        if (rating != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("portion", "flavor", "effort").forEach { aspect ->
                FilterChip(selected = aspects.has(aspect), onClick = {
                    val next = JSONObject(aspects.toString()).apply { if (has(aspect)) remove(aspect) else put(aspect, rating) }
                    vm.track("rating_aspect", "target" to key, "aspect" to aspect); onChange(rating, next)
                }, label = { Text(aspect.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) })
            }
        }
    }
}

@Composable
fun PreferencesScreen(vm: GardenModel) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.track("preferences_back"); vm.openPreferences = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Preferences", fontFamily = FontFamily.Serif, fontSize = 24.sp)
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.graphPreferences().filter { !(it.s("stance") == "neutral" && it.s("statement").startsWith("No current preference about ")) }.groupBy { it.s("kind") }.forEach { (kind, preferences) ->
                item { Text(kind.replaceFirstChar { it.uppercase() }, fontFamily = FontFamily.Serif, fontSize = 20.sp) }
                items(preferences, key = { it.s("kind") + it.s("subject") }) { preference ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (preference.s("source") == "imported" && vm.graphAssumptions().any { preference.s("id") in it.a("evidence").strings() }) Icon(Icons.Outlined.AutoAwesome, "Unconfirmed", Modifier.size(18.dp), tint = Forest)
                        Text(preference.s("statement"), fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        IconButton(onClick = { vm.track("preference_open", "subject" to preference.s("subject")); editing = preference }) { Icon(Icons.Outlined.Edit, "Change preference") }
                    }
                }
            }
        }
    }
    editing?.let { preference ->
        var statement by remember(preference) { mutableStateOf(preference.s("statement")) }
        AlertDialog(onDismissRequest = { vm.track("preference_cancel"); editing = null }, title = { Text("Preference") }, text = {
            Column {
                OutlinedTextField(statement, { statement = it.take(3000) }, label = { Text("Statement") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("never", "avoid", "neutral", "like", "love").forEach { stance -> TextButton(onClick = {
                        vm.changePreference(preference, when (stance) { "never" -> "Never choose"; "avoid" -> "Avoid"; "neutral" -> "No strong preference about"; "like" -> "Like"; else -> "Love" } + " ${preference.s("subject")}", stance); editing = null
                    }) { Text(stance.replaceFirstChar { it.uppercase() }) } }
                }
            }
        }, confirmButton = { TextButton(enabled = statement.isNotBlank(), onClick = { vm.changePreference(preference, statement.trim(), preference.s("stance").ifEmpty { null }); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.changePreference(preference, null, null); editing = null }) { Text("Remove") } })
    }
}
