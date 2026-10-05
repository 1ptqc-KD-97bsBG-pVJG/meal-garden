@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.app.*
import android.content.*
import androidx.activity.compose.*
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun TodayScreen(vm: GardenModel) {
    var showStripKey by rememberSaveable { mutableStateOf(false) }
    if (showStripKey) TodayStripKey { showStripKey = false }
    val snap = vm.snapshot
    val ready = snap.a("recipes").objects().filter { it.s("readiness") == "ready" }
    val plan = snap.o("activePlan")
    val today = LocalDate.now(householdZone).toString()
    val todayMeals = plan.a("meals").objects().filter { it.s("date") == today }
    val past =
        plan.a("meals").objects().isNotEmpty() &&
            plan.a("meals").objects().all { it.s("date") < today }
    val preservationCount = snap.a("observations").objects().groupBy { it.s("item").lowercase() }
        .values.count { reports ->
            reports.maxByOrNull { it.s("createdAt") }?.let {
                it.s("preservationNeed") == "consider_use_or_freeze" && it.s("condition") != "discard"
            } == true
        }
    val hero = ready.find { recipe -> todayMeals.any { it.s("recipe_id") == recipe.s("id") } } ?: ready.firstOrNull()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp, 10.dp, 24.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(LocalDate.parse(today).format(DateTimeFormatter.ofPattern("EEEE, MMM d")), fontFamily = FontFamily.Serif, fontSize = 28.sp, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { showStripKey = true }) { Text("Key", color = Forest) }
            }
        }
        item { FoodLogStrip(vm) }
        if (!vm.paired)
            item {
                Note(
                    "Your recipes are ready to explore. Connect your laptop to chat, save notes, and run shopping tasks.",
                    Icons.Outlined.Devices,
                )
            }
        val reset = snap.optJSONObject("kitchenReset")
        if (reset != null) item {
            val counts = reset.o("counts")
            val soon = reset.a("items").objects().count { it.s("state") == "use_soon" }
            val leftovers = reset.a("leftovers").length()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Mist).clickable { vm.openFridgeCheck = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.Kitchen, null, tint = Forest)
                Column(Modifier.weight(1f)) {
                    Text("Kitchen reset", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = Ink)
                    Text(listOfNotNull(
                        "${counts.optInt("toCheck")} to check",
                        soon.takeIf { it > 0 }?.let { "$it use soon" },
                        leftovers.takeIf { it > 0 }?.let { "$it leftover${if (it == 1) "" else "s"}" },
                    ).joinToString(" · "), fontSize = 13.sp, color = Muted)
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Forest)
            }
        } else if (preservationCount > 0) item {
            CardBox(color = Mist) {
                Eyebrow("USE OR FREEZE SOON")
                Text("$preservationCount ingredients need a decision", fontFamily = FontFamily.Serif, fontSize = 22.sp)
                TextButton(onClick = { vm.openFridgeCheck = true }) { Text("See kitchen reset") }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Forest)) {
                GardenBowl(Modifier.size(180.dp).align(Alignment.TopEnd).offset(30.dp, 20.dp))
                Column(
                    Modifier.padding(23.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Eyebrow(
                        if (todayMeals.isEmpty()) "TODAY" else "PLANNED TODAY",
                        Lime,
                    )
                    Text(
                        if (todayMeals.isEmpty()) "Nothing planned"
                        else todayMeals.joinToString("\n") { vm.recipe(it.s("recipe_id"))?.s("title") ?: it.s("recipe_id") },
                        fontFamily = FontFamily.Serif,
                        fontSize = 26.sp,
                        lineHeight = 31.sp,
                        color = Color.White,
                        modifier = Modifier.width(225.dp),
                    )
                    Button(
                        onClick = {
                            val first = todayMeals.firstOrNull()?.s("recipe_id")
                            if (first != null && vm.recipe(first) != null) vm.selectedRecipe = first
                            else vm.ask(
                                "Help me plan meals starting today. Read my preferences and latest observations. Ask only for the missing details that matter."
                            )
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Lime,
                                contentColor = Forest,
                            ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(if (todayMeals.isEmpty()) "Plan dinner" else "Open recipe")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickTile(
                    "Use what I have",
                    "Less waste",
                    Icons.Outlined.Eco,
                    Modifier.weight(1f),
                ) {
                    vm.ask(
                        "Help me use what I have. Read my recent pantry observations, confirm anything uncertain, and suggest a practical meal."
                    )
                }
                QuickTile(
                    "Lunch at work",
                    "Salad-bar guide",
                    Icons.Outlined.LunchDining,
                    Modifier.weight(1f),
                ) {
                    vm.openLunchGuide = true
                    vm.tab = 3
                }
            }
        }
        if (past && snap.optJSONObject("kitchenReset") == null) item {
            CardBox(color = Mist) {
                Eyebrow("PICK UP WHERE YOU ARE")
                Text("A quick kitchen reset", fontFamily = FontFamily.Serif, fontSize = 25.sp)
                Text("The old plan is a starting point. Check what remains from the September 14 shop, then choose the next meal and store list.", fontSize = 13.sp, color = Muted)
                ActionButton("Check ingredients") { vm.openFridgeCheck = true }
            }
        }
        item { SectionLabel("Something worth cooking", "All recipes") { vm.tab = 1 } }
        hero?.let { r -> item { RecipeTile(r) { vm.selectedRecipe = r.s("id") } } }
        item {
            SectionLabel("Your plan", if (past) "Refresh plan" else "Talk it through") {
                vm.ask(
                    "Review my existing plan and help me update it for today. Don't assume old meals were cooked."
                )
            }
            Spacer(Modifier.height(9.dp))
            CardBox {
                Eyebrow(if (past) "PREVIOUS PLAN" else "SAVED PLAN")
                Text(
                    plan.s("title", "A fresh start"),
                    fontFamily = FontFamily.Serif,
                    fontSize = 22.sp,
                )
                Text(
                    if (past)
                        "These dates have passed. Nothing here is assumed cooked or still in your fridge."
                    else plan.s("note", "Start a conversation to plan a few meals."),
                    fontSize = 13.sp,
                    color = Muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                plan.a("meals").objects().forEach { m ->
                    HorizontalDivider(color = Line)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.selectedRecipe = m.s("recipe_id") }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            m.s("date").takeLast(5),
                            fontSize = 12.sp,
                            color = Muted,
                            modifier = Modifier.width(42.dp),
                        )
                        Text(
                            vm.recipe(m.s("recipe_id"))?.s("title") ?: m.s("recipe_id"),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp))
                    }
                }
            }
        }
        item {
            Text(
                "MEAL GARDEN  ·  YOUR KITCHEN",
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                color = Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
