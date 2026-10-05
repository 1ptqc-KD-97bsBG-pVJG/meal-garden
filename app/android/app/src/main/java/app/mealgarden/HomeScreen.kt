@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.mealgarden

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(vm: GardenModel) {
    var key by rememberSaveable { mutableStateOf(false) }
    var basketFood by rememberSaveable { mutableStateOf("") }
    if (key) TodayStripKey { key = false }
    if (basketFood.isNotBlank()) AlertDialog(onDismissRequest = { basketFood = "" },
        title = { Text("From today's log", style = GardenType.Section) }, text = { Text(basketFood) },
        confirmButton = { TextButton(onClick = { basketFood = ""; vm.openFoodLog = true }) { Text("Open log") } },
        dismissButton = { TextButton(onClick = { basketFood = "" }) { Text("Close") } })
    foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
    val today = foodToday()
    val entries = vm.foodLog()
    val dayEntries = entries.filter { localDay(it.s("capturedAt")) == today }
    val day = dayHealth(dayEntries)
    val ready = vm.snapshot.a("recipes").objects().filter { it.s("readiness") == "ready" && it.a("ingredients").length() > 0 && it.a("steps").length() > 0 }
    val intent = vm.snapshot.o("activePlan").a("meals").objects().filter { it.s("date") == today.toString() }
    val suggestion = ready.firstOrNull { recipe -> intent.any { it.s("recipe_id") == recipe.s("id") } } ?: ready.firstOrNull()
    val showTimeline = moduleEnabled(vm,"timeline")
    val showNutrition = moduleEnabled(vm,"nutrition")
    val showBasket = moduleEnabled(vm,"basket")
    val showWeek = moduleEnabled(vm,"week")
    val showShopping = moduleEnabled(vm,"shoppingTimeline",false)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(today.format(DateTimeFormatter.ofPattern("MMMM d")))
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE")), style = GardenType.Title)
                }
                TextButton(onClick = { key = true }) { Text("Key") }
                IconButton(onClick = { vm.openFoodLog = true }) { Icon(Icons.Outlined.History, "Food log", tint = Muted) }
            }
        }
        if (showTimeline) item { FoodLogStrip(vm) }
        item {
            GardenCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Tonight?"); Spacer(Modifier.weight(1f))
                    suggestion?.optInt("total_minutes")?.takeIf { it > 0 }?.let { GardenChip("$it min", icon = Icons.Outlined.Timer) }
                }
                if (suggestion != null) {
                    Row(Modifier.fillMaxWidth().clickable { vm.selectedRecipe = suggestion.s("id") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GardenBowl(Modifier.size(72.dp), suggestion.s("id").hashCode())
                        Column(Modifier.weight(1f)) {
                            Text(suggestion.s("title"), style = GardenType.Section)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                suggestion.a("ingredients").objects().take(5).forEach { Icon(ingredientIcon(it), it.s("name"), Modifier.size(22.dp), tint = Color.Unspecified) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GardenPrimaryButton("Open recipe", { vm.selectedRecipe = suggestion.s("id") }, Modifier.weight(1f))
                        GardenQuietButton("Something else", { vm.ask("Suggest one other dinner from my ready recipes and pantry evidence. Ask about uncertain stock before deciding.") }, Modifier.weight(1f))
                    }
                } else GardenPrimaryButton("Choose dinner", { vm.ask("Help me choose dinner from my preferences and current food evidence.") }, Modifier.fillMaxWidth())
            }
        }
        if (showNutrition) item { DayNumbers(vm, dayEntries) }
        if (showBasket) item {
            val foods = day.evidence.filterKeys { it != "other_protein" }.values.flatten().distinctBy { it.lowercase() }
            GardenCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Today's basket", style = GardenType.Section, modifier = Modifier.weight(1f))
                    GardenChip("${foods.size} identified", tint = Mist)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    foods.forEach { name ->
                        Column(Modifier.width(90.dp).clickable { basketFood = name }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(ingredientIcon(j("name" to name), Icons.Outlined.Spa), null, Modifier.size(38.dp), tint = Color.Unspecified)
                            Text(name, style = GardenType.Small, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (foods.isEmpty()) Text("No foods identified yet", style = GardenType.Small)
            }
        }
        if (showWeek) item {
            GardenCard {
                SectionLabel("This week")
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(7) { n ->
                        val date = start.plusDays(n.toLong())
                        val evidence = dayHealth(entries.filter { localDay(it.s("capturedAt")) == date }).evidence
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(date.format(DateTimeFormatter.ofPattern("EEEEE")), style = GardenType.Small, color = if (date == today) Forest else Muted)
                            Column(Modifier.fillMaxWidth().height(84.dp).background(Paper2, GardenShape.Button).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (evidence.isEmpty()) Text("·", color = Faint)
                                healthGroups.take(5).forEachIndexed { i, g -> if (g.id in evidence) Box(Modifier.size(9.dp).background(varietyColors[i], CircleShape)) }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    healthGroups.take(5).forEachIndexed { i, g -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Box(Modifier.size(7.dp).background(varietyColors[i], CircleShape)); Text(g.label, style = GardenType.Small) } }
                }
            }
        }
        if (showShopping) item {
            GardenCard(onClick = { vm.tab = 4 }) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Outlined.ShoppingBasket,null,tint=Forest);Text(vm.shoppingListName,style=GardenType.Section);GardenChip("${vm.snapshot.o("shopping").a("items").length()} items") } }
        }
    }
}
private val varietyColors = listOf(Leaf, Clay, Amber, Forest, Ice)
