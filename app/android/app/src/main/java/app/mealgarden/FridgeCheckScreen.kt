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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun FridgeCheckScreen(vm: GardenModel) {
    var reviewing by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var correctingId by rememberSaveable { mutableStateOf("") }
    var selectedItem by rememberSaveable { mutableStateOf("") }
    var receiptItem by remember { mutableStateOf<JSONObject?>(null) }
    val assumptions = vm.graphAssumptions()
    val pantry = vm.graphPantry()
    if (!reviewing) { KitchenContent(vm, checking = true); return }
    LazyColumn(Modifier.fillMaxSize().testTag("graph-kitchen"), contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { GardenTopBar("Here's what I assumed", onBack = { vm.openFridgeCheck = false }) }
        if (assumptions.isNotEmpty()) item {
            GardenCard {
                (if (expanded) assumptions else assumptions.take(3)).forEach { assumption ->
                    Row(Modifier.fillMaxWidth().clickable { vm.track("assumption_open", "id" to assumption.s("id")); correctingId = assumption.s("id") }.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SparkleMark()
                        Text(assumption.s("statement").removePrefix("Confirm imported preference: "), modifier = Modifier.weight(1f), style = GardenType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.Edit, "Correct", Modifier.size(18.dp), tint = Muted)
                    }
                }
                if (assumptions.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "Show all") }
            }
        } else item { GardenCard { Text("No open assumptions", style = GardenType.Body) } }
        val correction = assumptions.firstOrNull { it.s("id") == correctingId }
        if (correction != null) item {
            GardenPanel("Correct assumption", onClose = { correctingId = "" }, modifier = Modifier.testTag("assumption-detail")) {
                var fullEvidence by rememberSaveable(correctingId) { mutableStateOf(false) }
                Text(correction.s("statement"), style = GardenType.Body, maxLines = if (fullEvidence) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { fullEvidence = !fullEvidence }) { Text(if (fullEvidence) "Less" else "Details") }
                val evidence = correction.a("evidence").strings()
                val linked = pantry.filter { it.s("product_id") == correction.s("productId").ifBlank { correction.s("product_id") } || it.s("id") in evidence }
                linked.forEach { lot ->
                    GardenChip(lot.s("name"), onClick = { selectedItem = lot.s("id") })
                    if (correction.s("componentId").isNotBlank()) TextButton(onClick = {
                        vm.graphWrite("/api/intake/allocate", j("componentId" to correction.s("componentId"), "itemId" to lot.s("id")), "intake_allocate"); correctingId = ""
                    }) { Text("Use ${lot.s("location")} lot") }
                }
                vm.graphPreferences().filter { it.s("id") in evidence }.forEach { preference ->
                    TextButton(onClick = { vm.changePreference(preference, null, null); correctingId = "" }) { Text("Remove preference") }
                }
                GardenQuietButton("Mark corrected", onClick = { vm.resolveAssumptions(listOf(correction.s("id")), "corrected"); correctingId = "" })
            }
        }
        val selected = pantry.firstOrNull { it.s("id") == selectedItem }
        if (selected != null) item { KitchenItemPanel(vm, selected, onClose = { selectedItem = "" }, onReceipt = { receiptItem = selected }) }
        item { GardenPrimaryButton("Looks right", onClick = { vm.resolveAssumptions(assumptions.map { it.s("id") }, "confirmed"); vm.track("kitchen_assumptions_reviewed"); reviewing = false }, modifier = Modifier.fillMaxWidth()) }
    }
    receiptItem?.let { KitchenReceipt(vm, it, onClose = { receiptItem = null }) }
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
    val preferences = vm.graphPreferences().filter {
        !(it.s("stance") == "neutral" && it.s("statement").startsWith("No current preference about "))
    }
    Column(Modifier.fillMaxSize().background(Paper)) {
        GardenTopBar("Preferences", onBack = { vm.track("preferences_back"); vm.openPreferences = false })
        LazyColumn(contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preferences.groupBy { it.s("kind") }.forEach { (kind, rows) ->
                item { SectionLabel(kind.replaceFirstChar { it.uppercase() }) }
                items(rows, key = { it.s("kind") + it.s("subject") }) { preference ->
                    GardenCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (preference.s("source") == "imported" && vm.graphAssumptions().any { preference.s("id") in it.a("evidence").strings() })
                                Icon(Icons.Outlined.AutoAwesome, "Unconfirmed", Modifier.size(18.dp), tint = Forest)
                            Text(preference.s("statement"), style = GardenType.Body, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.track("preference_open", "subject" to preference.s("subject")); editing = preference }) {
                                Icon(Icons.Outlined.Edit, "Change preference", tint = Muted, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { preference ->
        var statement by remember(preference) { mutableStateOf(preference.s("statement")) }
        AlertDialog(onDismissRequest = { vm.track("preference_cancel"); editing = null },
            title = { Text("Preference", style = GardenType.Section) }, shape = GardenShape.Panel, containerColor = Paper,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(statement, { statement = it.take(3000) }, label = { Text("Statement") },
                        shape = GardenShape.Button, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("never", "avoid", "neutral", "like", "love").forEach { stance ->
                            GardenChip(stance.replaceFirstChar { it.uppercase() }, selected = preference.s("stance") == stance, onClick = {
                                vm.changePreference(preference, when (stance) { "never" -> "Never choose"; "avoid" -> "Avoid"; "neutral" -> "No strong preference about"; "like" -> "Like"; else -> "Love" } + " ${preference.s("subject")}", stance)
                                editing = null
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = statement.isNotBlank(), onClick = {
                vm.changePreference(preference, statement.trim(), preference.s("stance").ifEmpty { null }); editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.changePreference(preference, null, null); editing = null }) { Text("Remove", color = Clay) } })
    }
}
