@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.mealgarden

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PantryScreen(vm: GardenModel) {
    KitchenContent(vm)
}

/** The check and the everyday Kitchen use one drawing, one panel, and one outbox. */
@Composable
fun KitchenContent(vm: GardenModel, checking: Boolean = false) {
    var zone by rememberSaveable(checking) { mutableStateOf(if (checking) "fridge" else "") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedLots by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var key by rememberSaveable { mutableStateOf(false) }
    var showSettled by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var appliance by rememberSaveable { mutableStateOf("") }
    var receiptItem by remember { mutableStateOf<JSONObject?>(null) }
    val listState = rememberLazyListState()
    var panelRequest by remember { mutableIntStateOf(0) }
    val pantry = vm.graphPantry()
    val active = pantry.filter { it.optDouble("balance", Double.NaN) != 0.0 }
    val settled = pantry.filter { it.optDouble("balance", Double.NaN) == 0.0 }
    val checkZones = KitchenZones + if (zone == "unknown" || active.any { kitchenZone(it) == "unknown" }) listOf("unknown" to "Unplaced") else emptyList()
    val visible = active.filter { zone.isBlank() || kitchenZone(it) == zone }
    val filtered = kitchenGroups(visible) + if (showSettled) kitchenGroups(settled.filter { zone.isBlank() || kitchenZone(it) == zone }) else emptyList()
    val selected = pantry.firstOrNull { it.s("id") == selectedId }
    fun open(food: JSONObject) { selectedId = food.s("id"); selectedLots = food.a("_lotIds").strings().ifEmpty { listOf(selectedId) }; appliance = "" }
    LaunchedEffect(vm.selectedPantryItem) {
        val requested = vm.selectedPantryItem
        if (requested.isNotBlank()) {
            pantry.firstOrNull { it.s("id") == requested }?.let { food ->
                val isSettled = food.optDouble("balance", Double.NaN) == 0.0
                val group = kitchenGroups(pantry.filter { (it.optDouble("balance", Double.NaN) == 0.0) == isSettled })
                    .firstOrNull { requested in it.a("_lotIds").strings() }
                open(group ?: food)
                selectedId = requested
                zone = kitchenZone(food)
                if (isSettled) showSettled = true
                finished = false
                panelRequest++
                vm.track("pantry_link_open", "itemId" to requested)
            }
            vm.selectedPantryItem = ""
        }
    }
    LaunchedEffect(panelRequest) {
        if (panelRequest > 0 && selected != null) {
            withFrameNanos { }
            // The title, drawing and filters precede the optional key and item panel.
            val index = 3 + (if (key) 1 else 0) + (if (appliance.isNotBlank()) 1 else 0)
            listState.animateScrollToItem(index)
        }
    }
    if (checking && finished) { KitchenPlanning(vm, onBack = { finished = false }); return }
    LazyColumn(Modifier.fillMaxSize().testTag("graph-kitchen"), state = listState, contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GardenTopBar(if (checking) checkZones.firstOrNull { it.first == zone }?.second ?: "Kitchen check" else "Kitchen", onBack = if (checking) ({ vm.openFridgeCheck = false }) else null) {
                TextButton(onClick = { key = !key }) { Icon(Icons.Outlined.Key, null, Modifier.size(16.dp)); Spacer(Modifier.width(3.dp)); Text("Key") }
                if (!checking) IconButton(onClick = { vm.openActivity = true }) { Icon(Icons.Outlined.List, "Activity", tint = Forest) }
                IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add food", tint = Forest) }
            }
        }
        if (key) item { GardenCard {
            StateKey()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Box(Modifier.size(17.dp, 3.dp).background(Amber)); Text("Use soon", style = GardenType.Small) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Box(Modifier.size(17.dp, 3.dp).background(Clay)); Text("Check freshness", style = GardenType.Small) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Text("?", style = GardenType.Small); Text("Unknown", style = GardenType.Small) }
            }
        } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pantry.isEmpty() && vm.snapshot.o("inventory").s("status") == "not_inventoried") {
                    Row(Modifier.testTag("kitchen-stock-unknown"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.HelpOutline, null, Modifier.size(17.dp), tint = Muted)
                        Text("Food on hand unknown", style = GardenType.Small)
                    }
                }
                KitchenDrawing(vm, Modifier.fillMaxWidth().height(if (checking || zone.isNotBlank()) 270.dp else 340.dp), zone = zone, selectedId = selectedId, onItem = ::open, onAppliance = { appliance = it; selectedId = "" })
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!checking) GardenChip("All", selected = zone.isBlank(), onClick = { zone = "" })
                if (active.any { kitchenZone(it) == "unknown" }) GardenChip("Unplaced", selected = zone == "unknown", onClick = { zone = "unknown" })
                KitchenZones.forEach { (id, label) ->
                    val needs = active.any { kitchenZone(it) == id && (it.s("basis") == "unknown" || it.s("condition") == "use_soon" || it.s("urgency") in listOf("soon", "past")) }
                    GardenChip(label, selected = zone == id, tint = if (needs) AmberLight else Paper2, onClick = { vm.track("kitchen_zone", "zone" to id); zone = id })
                }
            }
        }
        if (appliance.isNotBlank()) item { GardenPanel(appliance, onClose = { appliance = "" }) {} }
        if (selected != null) item(key = "selected-pantry-panel") {
            AnimatedContent(targetState = selectedId, label = "kitchen-item-panel") { current ->
                pantry.firstOrNull { it.s("id") == current }?.let { food ->
                    KitchenItemPanel(vm, food, pantry.filter { it.s("id") in selectedLots }, onLot = { selectedId = it.s("id") }, onClose = { selectedId = "" }, onReceipt = { receiptItem = food })
                }
            }
        }
        if (zone.isNotBlank() || checking || showSettled) {
            items(filtered, key = { it.s("product_id").ifBlank { it.s("id") } + ":" + kitchenZone(it) + ":" + (it.optDouble("_total", Double.NaN) == 0.0) }) { food ->
                GardenCard(onClick = { open(food) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(ingredientIcon(food), null, Modifier.size(36.dp))
                        Text(food.s("name"), modifier = Modifier.weight(1f), style = GardenType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val amount = food.optDouble("_total", Double.NaN)
                        if (amount.isFinite() && amount >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (food.s("basis") == "assumed") SparkleMark(Modifier.size(12.dp))
                            Text("${kitchenNumber(amount)} ${food.s("base_unit")}", style = GardenType.Small)
                        } else Text(if (amount < 0) "Check amount" else "?", style = GardenType.Small)
                        kitchenState(vm, food)?.let { StateMark(it, showLabel = false) }
                    }
                }
            }
        }
        if (settled.isNotEmpty()) item { TextButton(onClick = { showSettled = !showSettled }) { Text(if (showSettled) "Hide used up or tossed" else "Used up or tossed · ${settled.size}") } }
        vm.outbox.filter { it.s("route") == "/api/pantry/add" }.forEach { pending ->
            item { GardenChip("${pending.o("payload").s("name")} · waiting to sync", icon = Icons.Outlined.CloudUpload) }
        }
        item {
            if (checking) {
                val index = checkZones.indexOfFirst { it.first == zone }.coerceAtLeast(0)
                if (index < checkZones.lastIndex) GardenPrimaryButton("Next · ${checkZones[index + 1].second}", onClick = { selectedId = ""; zone = checkZones[index + 1].first }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.ArrowForward)
                else GardenPrimaryButton("Finished check", onClick = { vm.track("kitchen_check_finished"); selectedId = ""; finished = true }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Check)
            } else GardenPrimaryButton("Check kitchen", onClick = { vm.track("kitchen_check_open"); vm.openFridgeCheck = true }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Check)
        }
        if (vm.outbox.isNotEmpty()) item { Text("${vm.outbox.size} waiting to sync", style = GardenType.Small) }
    }
    if (adding) AddKitchenFood(vm, pantry, onClose = { adding = false })
    receiptItem?.let { KitchenReceipt(vm, it, onClose = { vm.track("receipt_close"); receiptItem = null }) }
}

fun kitchenState(vm: GardenModel, item: JSONObject): PantryStateMark? {
    val pending = vm.outbox.lastOrNull { it.o("payload").s("itemId") == item.s("id") }
    return when {
        pending?.s("route") == "/api/pantry/toss" -> PantryStateMark.Tossed
        item.optDouble("balance", Double.NaN) == 0.0 -> PantryStateMark.UsedUp
        item.s("condition") == "use_soon" -> PantryStateMark.UseSoon
        item.s("condition") == "fine" -> PantryStateMark.Fine
        else -> null
    }
}

@Composable
fun KitchenItemPanel(vm: GardenModel, food: JSONObject, lots: List<JSONObject> = listOf(food), onLot: (JSONObject) -> Unit = {}, onClose: () -> Unit, onReceipt: () -> Unit) {
    val id = food.s("id")
    val amount = food.optDouble("balance", Double.NaN).takeIf { it.isFinite() }
    val pack = pantryPackage(vm.snapshot, food)?.takeIf { it.first.isFinite() && it.first > 0 && it.second.isFinite() && it.second > 0 }
    val batch = vm.snapshot.a("batches").objects().firstOrNull { it.s("pantry_item_id") == id }
    val full = batch?.optDouble("yield_g", Double.NaN)?.takeIf { it.isFinite() && it > 0 } ?: pack?.let { it.first * it.second }
    val counted = batch == null && ((food.s("kind") == "packaged" && pack != null) || food.s("base_unit") == "count")
    val packageSize = pack?.first ?: 1.0
    var exact by rememberSaveable(id) { mutableStateOf(false) }
    var fullAmount by rememberSaveable(id) { mutableStateOf(false) }
    var fraction by rememberSaveable(id) { mutableStateOf(1.0) }
    var amountText by rememberSaveable(id) { mutableStateOf("") }
    val state = kitchenState(vm, food)
    val suggest = if (state == null) when (food.s("urgency")) { "past", "soon" -> PantryStateMark.UseSoon; "fresh" -> PantryStateMark.Fine; else -> null } else null
    fun count(value: Double?, confidence: String = "known") = vm.graphWrite("/api/pantry/count", j("itemId" to id, "amount" to value, "confidence" to confidence), "pantry_amount")
    GardenPanel("Still have it?", onClose = onClose, modifier = Modifier.testTag("kitchen-item-panel")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(ingredientIcon(food), null, Modifier.size(32.dp))
            Text(food.s("name"), style = GardenType.Body, modifier = Modifier.weight(1f))
        }
        if (lots.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { lots.forEachIndexed { index, lot ->
            val date = lot.s("purchased_on").take(10).ifBlank { lot.s("created_at").take(10) }
            GardenChip(if (date.isBlank()) "Item ${index + 1}" else "bought $date", selected = lot.s("id") == id, onClick = { onLot(lot) })
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Have it" to listOf(PantryStateMark.Fine, PantryStateMark.UseSoon), "Don't" to listOf(PantryStateMark.UsedUp, PantryStateMark.Tossed)).forEach { (label, choices) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = GardenType.Small)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { choices.forEach { choice ->
                        StateMark(choice, Modifier.weight(1f), selected = state == choice, assumed = suggest == choice, onClick = {
                            when (choice) {
                                PantryStateMark.Fine, PantryStateMark.UseSoon -> {
                                    if (amount == 0.0) count(null)
                                    vm.graphWrite("/api/pantry/condition", j("itemId" to id, "condition" to if (choice == PantryStateMark.Fine) "fine" else "use_soon"), "pantry_${choice.name.lowercase()}")
                                }
                                PantryStateMark.UsedUp -> count(0.0)
                                PantryStateMark.Tossed -> vm.graphWrite("/api/pantry/toss", j("itemId" to id), "pantry_toss")
                            }
                        })
                    } }
                }
            }
        }
        val portions = batch?.let { batchPortions(it, amount) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (food.s("basis") == "assumed") SparkleMark()
            Text(when {
                amount == null -> "How much left?"
                amount < 0 -> "Amount needs checking"
                portions != null -> "${kitchenNumber(portions)} ${if (kotlin.math.abs(portions - 1.0) < .001) "portion" else "portions"} left"
                counted -> "${kitchenNumber(amount / packageSize)} ${if (food.s("kind") == "packaged") (if (abs(amount / packageSize - 1.0) < .001) "package" else "packages") else "left"}"
                else -> "${kitchenNumber(amount)} ${food.s("base_unit")} left"
            }, style = GardenType.Small)
        }
        if (counted && amount != null && amount >= 0 && abs(amount / packageSize - (amount / packageSize).roundToInt()) < .01) {
            AmountStepper((amount / packageSize).roundToInt(), onChange = { count(it * packageSize) }, label = if (food.s("kind") == "packaged") "packages" else "left")
        } else if (!counted) {
            val current = if (full != null && amount != null && amount >= 0) (1..4).firstOrNull { abs(amount / full - it * .25) < .01 } ?: 0 else 0
            FourLevelAmount(current, onChange = { level ->
                val chosen = level * .25
                if (full != null) count(full * chosen, "assumed")
                else { fraction = chosen; fullAmount = true; exact = true; amountText = "" }
            })
        }
        TextButton(onClick = { exact = true; fullAmount = false; fraction = 1.0; amountText = "" }, modifier = Modifier.testTag("pantry-count:$id")) { Text(if (counted && amount == null) "How many left?" else "Set amount") }
        if (exact) {
            HorizontalDivider(color = Line)
            Text(if (fullAmount) "Full amount" else "Amount left", style = GardenType.Small)
            OutlinedTextField(amountText, { amountText = it }, modifier = Modifier.fillMaxWidth(), label = { Text(food.s("base_unit")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GardenQuietButton("Cancel", onClick = { exact = false })
                GardenPrimaryButton("Save", enabled = amountText.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true, onClick = { count(amountText.toDouble() * fraction, if (fullAmount) "assumed" else "known"); exact = false })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (food.s("location") != "freezer" && amount != 0.0) GardenChip("Move to freezer", tint = IceLight, icon = Icons.Outlined.AcUnit, onClick = {
                vm.graphWrite("/api/pantry/transfer", j("itemId" to id, "amount" to null, "toLocation" to "freezer"), "pantry_freeze")
            })
            val purchased = food.s("purchased_on").take(10)
            if (purchased.isNotBlank()) TextButton(onClick = { vm.track("pantry_receipt", "date" to purchased); onReceipt() }, modifier = Modifier.testTag("kitchen-receipt:$id")) { Text("bought $purchased", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AddKitchenFood(vm: GardenModel, pantry: List<JSONObject>, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("fridge") }
    var unit by rememberSaveable { mutableStateOf("g") }
    var amount by rememberSaveable { mutableStateOf("") }
    val existing = pantry.filter { it.s("name").equals(name.trim(), ignoreCase = true) }.distinctBy { it.s("product_id") }.singleOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text("Add food", style = GardenType.Section) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(250) }, label = { Text("Food") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { KitchenZones.filter { it.first != "drawers" }.forEach { (id, title) -> GardenChip(title, selected = location == id, onClick = { location = id }) } }
            if (existing == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("g", "ml", "count").forEach { u -> GardenChip(u, selected = unit == u, onClick = { unit = u }) } }
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount · ${existing?.s("base_unit") ?: unit}") }, placeholder = { Text("Unknown") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && (amount.isBlank() || amount.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true), onClick = {
        if (vm.graphWrite("/api/pantry/add", j("name" to name.trim(), "productId" to existing?.s("product_id"), "baseUnit" to (existing?.s("base_unit") ?: unit), "amount" to amount.toDoubleOrNull(), "location" to location), "pantry_add")) onClose()
    }) { Text("Save food") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

/** Use an explicit receipt link when supplied; ambiguous date-and-food matches need a choice. */
fun kitchenReceipts(snapshot: JSONObject, item: JSONObject): List<JSONObject> {
    val purchase = snapshot.a("purchases").objects().firstOrNull { it.s("id") == item.s("purchase_id") }
    val receiptId = item.s("receipt_id").ifBlank { purchase?.s("receipt_id").orEmpty() }
    val receipts = snapshot.a("receipts").objects()
    if (receiptId.isNotBlank()) return receipts.filter { it.s("id") == receiptId }
    val date = item.s("purchased_on").take(10)
    val name = item.s("name").lowercase().trim()
    if (date.isBlank() || name.isBlank()) return emptyList()
    return receipts.filter { receipt ->
        receipt.s("date").take(10) == date && receipt.a("items").objects().any {
            it.s("name").lowercase().replace(Regex("\\s*[-–]\\s*\\d.*$"), "").trim() == name
        }
    }
}

@Composable
fun KitchenReceipt(vm: GardenModel, item: JSONObject, onClose: () -> Unit) {
    val date = item.s("purchased_on").take(10)
    val receipts = kitchenReceipts(vm.snapshot, item)
    var choice by rememberSaveable(item.s("id")) { mutableStateOf("") }
    val selected = if (receipts.size == 1) receipts.first() else receipts.firstOrNull { it.s("id") == choice }
    AlertDialog(onDismissRequest = onClose, title = { Text("Bought $date", style = GardenType.Section) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (receipts.isEmpty()) Text("Receipt unavailable", style = GardenType.Small)
            if (selected == null && receipts.size > 1) receipts.forEach { receipt ->
                GardenCard(onClick = { choice = receipt.s("id") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(receipt.s("store"), modifier = Modifier.weight(1f), style = GardenType.Body)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                    }
                }
            }
            selected?.let { receipt ->
                Text(receipt.s("store"), style = GardenType.Section)
                receipt.a("items").objects().forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) { Text(line.s("name"), fontSize = 14.sp); Text(line.s("quantity"), style = GardenType.Small) }
                        if (!line.isNull("price")) Text("$${"%.2f".format(java.util.Locale.US, line.optDouble("price"))}", style = GardenType.Small)
                    }
                }
                if (!receipt.isNull("total")) Text("Total $${"%.2f".format(java.util.Locale.US, receipt.optDouble("total"))}", style = GardenType.Body)
                if (receipts.size > 1) TextButton(onClick = { choice = "" }) { Text("Other receipts") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun KitchenPlanning(vm: GardenModel, onBack: () -> Unit) {
    var choice by rememberSaveable { mutableStateOf("") }
    val foods = vm.graphPantry().filter { it.optDouble("balance", Double.NaN) != 0.0 }.map { it.s("name").lowercase() }
    val recipes = vm.snapshot.a("recipes").objects().filter { recipeReady(it) }
        .sortedByDescending { recipe -> recipe.a("ingredients").objects().count { ingredient -> foods.any { food -> food.contains(ingredient.s("name").lowercase()) || ingredient.s("name").lowercase().contains(food) } } }.take(3)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { GardenTopBar("What to cook?", onBack = onBack) }
        items(recipes, key = { it.s("id") }) { recipe ->
            GardenCard(color = if (choice == recipe.s("id")) Mist else CardSurface, onClick = { choice = recipe.s("id") }) {
                Text(recipe.s("title"), style = GardenType.Section)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { recipe.a("ingredients").objects().take(5).forEach { Image(ingredientIcon(it), null, Modifier.size(30.dp)) } }
                TextButton(onClick = { vm.openFridgeCheck = false; vm.selectedRecipe = recipe.s("id"); vm.tab = 1 }) { Text("Open recipe") }
            }
        }
        item { GardenQuietButton("Something else", onClick = { vm.startTask("reset_plan", "Suggest something else from my checked kitchen.") }, modifier = Modifier.fillMaxWidth()) }
        if (choice.isNotBlank()) item { GardenPrimaryButton("Plan this", onClick = { vm.startTask("reset_plan", "Plan ${recipes.firstOrNull { it.s("id") == choice }?.s("title") ?: choice} from my checked kitchen. Confirm missing ingredients and preserve recorded pantry amounts.") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) }
    }
}
