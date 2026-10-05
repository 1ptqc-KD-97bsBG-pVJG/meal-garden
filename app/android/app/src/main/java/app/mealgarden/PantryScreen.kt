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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlin.math.*
import org.json.JSONObject

@Composable
fun PantryScreen(vm: GardenModel) {
    var form by rememberSaveable { mutableStateOf(false) }
    var item by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("fridge") }
    var quantity by rememberSaveable { mutableStateOf("unknown") }
    var work by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.openLunchGuide) { if (vm.openLunchGuide) { work = true; vm.openLunchGuide = false } }
    val observations = vm.snapshot.a("observations").objects()
    val cooked = vm.snapshot.a("cooking").objects()
    if (form)
        AlertDialog(
            onDismissRequest = { form = false },
            title = { Text("A note from your kitchen", fontFamily = FontFamily.Serif) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = item,
                        onValueChange = { item = it },
                        label = { Text("Item") },
                        placeholder = { Text("Half a block of tofu") },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("fridge", "freezer", "pantry", "counter").forEach { l ->
                            FilterChip(
                                selected = location == l,
                                onClick = { location = l },
                                label = { Text(l) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("unknown", "none", "low", "enough", "exact").forEach { q ->
                            FilterChip(
                                selected = quantity == q,
                                onClick = { quantity = q },
                                label = { Text(q) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Details, quantity, or dates") },
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = item.isNotBlank() && note.isNotBlank(),
                    onClick = {
                        vm.observation(item, note, location, quantity) {
                            form = false
                            item = ""
                            note = ""
                        }
                    },
                ) {
                    Text("Save note")
                }
            },
            dismissButton = { TextButton(onClick = { form = false }) { Text("Cancel") } },
        )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Heading("Kitchen") }
        item {
            CardBox(color = Mist) {
                Eyebrow("PANTRY SNAPSHOT")
                Text(
                    if (observations.isEmpty()) "Let's see what you have."
                    else "${observations.size} notes from your kitchen",
                    fontFamily = FontFamily.Serif,
                    fontSize = 26.sp,
                )
                Text(
                    "Your full pantry hasn't been inventoried. Notes record what you've reported; they don't assume what's left today.",
                    fontSize = 13.sp,
                    color = Muted,
                )
                ActionButton("Add a pantry note", Icons.Outlined.Add) {
                    if (vm.paired) form = true else vm.openSettings = true
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(
                    "Fridge check",
                    "Add a photo in chat",
                    Icons.Outlined.PhotoCamera,
                    Modifier.weight(1f),
                ) {
                    vm.ask(
                        "Help me take stock of my fridge from a photo. Treat visible items as observations; ask about hidden quantities and dates."
                    )
                }
                QuickTile(
                    "Use-first ideas",
                    "Plan around your notes",
                    Icons.Outlined.Eco,
                    Modifier.weight(1f),
                ) {
                    vm.ask(
                        "Review my pantry notes and propose use-first meal ideas. Confirm what remains before changing a plan; don't infer food safety from a photo or unknown date."
                    )
                }
            }
        }
        if (observations.isNotEmpty()) item { SectionLabel("Recently reported") }
        items(observations.take(20), key = { it.s("id") }) { o ->
            CardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        o.s("item"),
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(o.s("location"))
                }
                Text(o.s("note"), fontSize = 14.sp)
                Text(
                    "${o.s("createdAt").take(10)} · ${o.s("quantityState")} · reported",
                    fontSize = 11.sp,
                    color = Muted,
                )
                TextButton(
                    onClick = {
                        vm.ask(
                            "Update my pantry observation for ${o.s("item")}: ${o.s("note")}. Here's what changed: "
                        )
                    }
                ) {
                    Text("Update in chat")
                }
            }
        }
        item { SectionLabel("Lunch, away from home") }
        item {
            CardBox {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.LunchDining, null, tint = Forest)
                    Column {
                        Text("Your work salad bar", fontFamily = FontFamily.Serif, fontSize = 24.sp)
                        Text(
                            "Saved guidance · observed September 22",
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                }
                Text("A practical bowl, with room to rotate.", fontSize = 14.sp)
                OutlinedButton(onClick = { work = !work }, shape = RoundedCornerShape(13.dp)) {
                    Text(if (work) "Close lunch guide" else "Open lunch guide")
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                if (work) SaladGuide(vm)
                TextButton(
                    onClick = {
                        vm.ask(
                            "Help me choose lunch from my saved work salad-bar options today. Keep health first and cost second; distinguish estimates from measured weights."
                        )
                    }
                ) {
                    Text("Talk through today's lunch")
                }
            }
        }
        if (cooked.isNotEmpty()) {
            item { SectionLabel("Cooking journal") }
            items(cooked.take(12), key = { it.s("id") }) { c ->
                CardBox {
                    Text(
                        vm.recipe(c.s("recipeId"))?.s("title") ?: c.s("recipeId"),
                        fontFamily = FontFamily.Serif,
                        fontSize = 21.sp,
                    )
                    Text(c.s("note"), fontSize = 14.sp)
                    Text(
                        "Reported cooked · ${c.o("cookedDateEstimate").let { e -> if (e.s("earliest").isNotBlank()) "around ${e.s("earliest")}–${e.s("latest")}" else c.s("createdAt").take(10) }}",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
            }
        }
    }
}

@Composable
fun SaladGuide(vm: GardenModel) {
    val dining = vm.snapshot.a("dining").objects().firstOrNull() ?: JSONObject()
    val rec = dining.o("recommendations")
    var weight by rememberSaveable { mutableFloatStateOf(14f) }
    Text("Cost explorer", fontFamily = FontFamily.Serif, fontSize = 22.sp)
    Text(
        "${"%.1f".format(weight)} oz × $${dining.o("price").optDouble("usd_per_ounce",0.6)} = $${"%.2f".format(weight*dining.o("price").optDouble("usd_per_ounce",0.6))}",
        fontWeight = FontWeight.Medium,
        color = Forest,
    )
    Slider(value = weight, onValueChange = { weight = it }, valueRange = 5f..22f)
    Text(
        "Estimate using the last reported $0.60/oz price. Free side dressing isn't included in charged weight.",
        fontSize = 11.sp,
        color = Muted,
    )
    val patterns = findPatterns(dining)
    patterns.take(3).forEach { p ->
        HorizontalDivider(color = Line)
        Text(p.s("name").replace('-', ' '), fontWeight = FontWeight.Medium, fontSize = 14.sp)
        p.a("portions").objects().forEach { x ->
            Text("${x.s("serving")} · ${x.s("ingredient")}", fontSize = 12.sp)
        }
        if (p.s("bring_from_home").isNotBlank())
            Text(p.s("bring_from_home"), fontSize = 12.sp, color = Muted)
    }
    if (patterns.isEmpty())
        Text(
            "Greens, legumes, tofu or another protein, a whole grain, and a small seed topping. Ask the assistant for your saved exact scoop guide.",
            fontSize = 13.sp,
        )
    Text(dining.s("measurement_note"), fontSize = 11.sp, color = Muted)
}

fun findPatterns(obj: JSONObject): List<JSONObject> {
    obj.keys().forEach { k ->
        when (val v = obj.opt(k)) {
            is org.json.JSONArray -> {
                val list = v.objects()
                if (list.any { it.has("portions") }) return list
            }
            is JSONObject -> {
                val list = findPatterns(v)
                if (list.isNotEmpty()) return list
            }
        }
    }
    return emptyList()
}
