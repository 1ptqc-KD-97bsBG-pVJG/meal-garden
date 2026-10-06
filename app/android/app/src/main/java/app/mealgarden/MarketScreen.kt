@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.mealgarden

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
fun MarketScreen(vm: GardenModel, showTitle: Boolean = true) {
    var page by rememberSaveable { mutableStateOf("Shopping") }
    val receipts = vm.snapshot.a("receipts").objects()
    var expanded by rememberSaveable { mutableStateOf("") }
    val demand = vm.snapshot.o("shopping")
    val trip = vm.snapshot.o("shoppingTrip")
    val plan = vm.snapshot.o("activePlan")
    val nextMeal = nextPlannedMeal(vm.snapshot)
    val nextRecipe = nextMeal?.let { meal -> vm.snapshot.a("recipes").objects().firstOrNull { it.s("id") == meal.s("recipe_id") && recipeReady(it) } }
    val buyRows = trip.a("buy").objects()
    var decisions by remember(trip.s("reviewKey")) { mutableStateOf(buyRows.map {
        it.s("purchase_decision").ifBlank { if (it.s("purchase_condition").isBlank()) "buy" else "" }
    }) }
    val reviewPending = vm.outbox.any { it.s("route") == "/api/shopping/review" }
    val unresolved = buyRows.any { it.s("purchase_condition").isNotBlank() && it.s("purchase_decision") !in listOf("buy", "have") }
    val past = vm.snapshot.optBoolean("planIsPast", false)
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showTitle) item { Heading("Shopping") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("Shopping", "Purchases", "Activity").forEach { label ->
                    GardenChip(label, selected = page == label, onClick = { page = label })
                }
            }
        }
        when (page) {
            "Shopping" -> {
                item {
                    GardenCard {
                        Text(vm.shoppingListName, style = GardenType.Section)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            GardenChip("Local quantities")
                            if (!vm.shoppingEnabled) GardenChip("Shopping not connected", tint = AmberLight)
                            if (past) GardenChip("Past plan", tint = AmberLight)
                        }
                        GardenPrimaryButton(if (past) "Plan next meals" else "Choose meals", {
                            vm.ask("Create a current meal plan from my kitchen and ready recipes. Show the missing ingredients for the next meal.")
                        }, modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.RestaurantMenu)
                        if (vm.shoppingEnabled) GardenQuietButton("Sync ${vm.shoppingListName}", { vm.send("shopping") }, icon = Icons.Outlined.Sync,
                            enabled = !past && trip.s("plan_id") == plan.s("id") && !unresolved && !reviewPending)
                    }
                }
                if (nextMeal != null && nextRecipe != null) item {
                    GardenCard {
                        SectionLabel("Next planned meal")
                        Text(nextRecipe.s("title"), style = GardenType.Section)
                        Text(humanDate(nextMeal.s("date")), style = GardenType.Small)
                        nextRecipe.a("ingredients").objects().forEach { ingredient ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Image(ingredientIcon(ingredient), null, Modifier.size(30.dp))
                                Text(ingredient.s("name"), style = GardenType.Body, modifier = Modifier.weight(1f))
                                val amount = ingredient.optDouble("amount", Double.NaN) * nextMeal.optDouble("batches", 1.0)
                                if (amount.isFinite()) Text("${kitchenNumber(amount)} ${ingredient.s("unit")}", style = GardenType.Small)
                            }
                        }
                        GardenChip("Check kitchen before buying", tint = AmberLight)
                        GardenQuietButton("Open recipe", onClick = { vm.selectedRecipe = nextRecipe.s("id"); vm.openShopping = false; vm.tab = 1 })
                    }
                }
                if (trip.s("plan_id") == plan.s("id")) item {
                    GardenCard {
                        SectionLabel(trip.o("store").s("name", vm.storeName))
                        val address = trip.o("store").s("address")
                        if (address.isNotBlank()) Text(address, style = GardenType.Small)
                        if (unresolved) GardenChip("Check before buying", tint = AmberLight)
                        buyRows.forEachIndexed { index, row ->
                            HorizontalDivider(color = Line)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(ingredientIcon(j("name" to row.s("name"))), null, Modifier.size(25.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                                Text(row.s("name"), style = GardenType.Body, modifier = Modifier.weight(1f))
                                Text("${row.optInt("quantity")} ${row.s("unit")}", style = GardenType.Small)
                            }
                            if (row.s("purchase_condition").isNotBlank()) Text(row.s("purchase_condition"), style = GardenType.Small)
                            if (row.s("department").isNotBlank()) GardenChip(row.s("department"))
                            if (row.s("purchase_condition").isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf("buy" to "Buy", "have" to "Have enough").forEach { (value, label) ->
                                    FilterChip(selected = decisions.getOrNull(index) == value,
                                        onClick = { decisions = decisions.toMutableList().apply { set(index, value) } },
                                        enabled = !reviewPending, label = { Text(label) })
                                }
                            }
                        }
                        if (buyRows.any { it.s("purchase_condition").isNotBlank() }) GardenPrimaryButton(
                            if (reviewPending) "Waiting to sync" else "Save shopping choices", {
                                vm.graphWrite("/api/shopping/review", j("reviewKey" to trip.s("reviewKey"), "decisions" to org.json.JSONArray(decisions)), "shopping_review")
                            }, enabled = decisions.none { it.isBlank() } && !reviewPending && !past)
                        trip.a("check_before_buying").strings().forEach { Text(it, style = GardenType.Small) }
                    }
                }
                item {
                    SectionLabel(if (past) "Saved plan ingredients" else "Plan ingredients", "Plan meals") {
                        vm.ask("Create a current meal plan and rebuild grocery demand from the recipes we choose.")
                    }
                    if (plan.s("title").isNotBlank()) Text(plan.s("title"), style = GardenType.Small)
                }
                items(demand.a("items").objects(), key = { it.s("id") }) { ingredient ->
                    GardenCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(ingredientIcon(ingredient), null, Modifier.size(29.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(ingredient.s("name"), style = GardenType.Body, fontWeight = FontWeight.Medium)
                                Text("${ingredient.s("purchase_count")} ${ingredient.o("purchase").s("unit")} · ${ingredient.o("purchase").s("label")}", style = GardenType.Small)
                                Text("Recipe use: ${ingredient.s("amount")} ${ingredient.s("unit")}", style = GardenType.Small)
                            }
                        }
                    }
                }
                if (demand.a("items").length() == 0) item { Text("No shopping quantities", style = GardenType.Body, color = Muted) }
            }
            "Purchases" -> {
                item {
                    GardenCard {
                        SectionLabel(vm.storeName)
                        if (vm.shoppingEnabled) GardenPrimaryButton("Refresh purchases", { vm.send("purchases") }, icon = Icons.Outlined.Refresh)
                        GardenQuietButton("Import receipt photo", {
                            vm.ask("I'd like to import a receipt photo. Extract food purchases, preserve uncertain quantities, omit payment and loyalty details, and check for duplicates.")
                        }, icon = Icons.Outlined.Receipt)
                    }
                }
                if (receipts.isEmpty()) item { Text("No imported receipts", style = GardenType.Body, color = Muted) }
                else item { SectionLabel("${receipts.size} imported receipts") }
                items(receipts, key = { it.s("id") }) { receipt ->
                    GardenCard(onClick = { expanded = if (expanded == receipt.s("id")) "" else receipt.s("id") }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.ReceiptLong, null, tint = Forest, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(receipt.s("store"), style = GardenType.Section)
                                Text("${humanDate(receipt.s("date"))} · ${receipt.a("items").length()} items", style = GardenType.Small)
                            }
                            if (!receipt.isNull("total")) Text("$${"%.2f".format(java.util.Locale.US, receipt.optDouble("total"))}", style = GardenType.Body)
                            Icon(if (expanded == receipt.s("id")) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted)
                        }
                        GardenChip("Stock unconfirmed", tint = AmberLight)
                        if (expanded == receipt.s("id")) {
                            receipt.a("items").objects().forEach { line ->
                                HorizontalDivider(color = Line)
                                Row {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.s("name"), style = GardenType.Body)
                                        Text(line.s("quantity"), style = GardenType.Small)
                                    }
                                    if (!line.isNull("price")) Text("$${"%.2f".format(java.util.Locale.US, line.optDouble("price"))}", style = GardenType.Small)
                                }
                            }
                            Text(receipt.s("source"), style = GardenType.Small)
                        }
                    }
                }
            }
            "Activity" -> {
                if (vm.activity.a("jobs").length() == 0) item { Text("No laptop tasks", style = GardenType.Body, color = Muted) }
                items(vm.activity.a("jobs").objects(), key = { it.s("id") }) { job ->
                    GardenCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when (job.s("kind")) { "shopping" -> "${vm.shoppingListName} sync"; "purchases" -> "${vm.storeName} refresh"; "connection" -> "Browser check"; else -> "Conversation" },
                                style = GardenType.Body, modifier = Modifier.weight(1f))
                            GardenChip(job.s("status").replace('_', ' '), tint = if (job.s("status") in listOf("failed", "partial", "needs_sign_in", "interrupted", "unverified")) AmberLight else Mist)
                        }
                        if (job.s("progress").isNotBlank()) Text(job.s("progress"), style = GardenType.Small)
                        if (job.s("error").isNotEmpty()) Text(job.s("error"), style = GardenType.Small, color = Clay)
                        if (job.s("model").isNotBlank()) Text(job.s("model"), style = GardenType.Small, color = Muted)
                        Text(activityDate(job.s("created")), style = GardenType.Small)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.chooseConversation(job.s("conversation_id")) }) { Text("View conversation") }
                            if (job.s("status") in listOf("running", "waiting", "queued"))
                                TextButton(onClick = { vm.stop(job.s("id")) }) { Text("Stop task", color = Clay) }
                        }
                    }
                }
            }
        }
    }
}


/** A plan is intent. Upcoming meals do not imply cooking, eating or pantry deductions. */
fun nextPlannedMeal(snapshot: org.json.JSONObject): org.json.JSONObject? {
    val today = snapshot.s("today").ifBlank { java.time.LocalDate.now(householdZone).toString() }
    return snapshot.o("activePlan").a("meals").objects().filter { it.s("date") >= today }
        .sortedBy { it.s("date") }.firstOrNull()
}
