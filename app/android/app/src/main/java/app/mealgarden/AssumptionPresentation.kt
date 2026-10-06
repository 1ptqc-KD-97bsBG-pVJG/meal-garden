package app.mealgarden

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Presentation follows structured links only. Unlinked statements remain evidence, never pantry facts. */
data class AssumptionPresentation(val record: JSONObject, val name: String, val sourceKey: String,
    val sourceTitle: String, val reason: String, val food: JSONObject?)

fun presentAssumption(snapshot: JSONObject, assumption: JSONObject): AssumptionPresentation {
    val evidence = assumption.a("evidence").strings()
    val productId = assumption.s("productId").ifBlank { assumption.s("product_id") }
    val food = snapshot.a("pantry").objects().firstOrNull {
        productId.isNotBlank() && it.s("product_id") == productId || it.s("id") in evidence
    }
    val preference = snapshot.a("preferences").objects().firstOrNull { it.s("id") in evidence }
    val source = assumption.o("source")
    val sourceKind = source.s("kind").ifBlank { when (assumption.s("kind")) {
        "cooking" -> "recipe"; "preferences" -> "preference"; else -> "kitchen"
    } }
    val reason = when (sourceKind) {
        "recipe", "batch" -> "from recipe"
        "receipt" -> "from receipt"
        "photo", "capture" -> "from photo"
        "intake" -> "from food log"
        "preference" -> "from preference"
        else -> "amount check"
    }
    val sourceTitle = humanDatesInText(source.s("title")).ifBlank { when (sourceKind) {
        "recipe", "batch" -> "Cooking"; "receipt" -> "Receipt"; "photo", "capture" -> "Photo";
        "intake" -> "Food log"; "preference" -> "Preferences"; else -> "Kitchen"
    } }
    val name = assumption.s("foodName").ifBlank { food?.s("name").orEmpty() }
        .ifBlank { preference?.s("subject")?.replace('_', ' ').orEmpty() }
        .ifBlank { if (sourceKind == "preference") "Preference" else "Food not linked" }
    return AssumptionPresentation(assumption, name,
        source.s("id").ifBlank { evidence.sorted().joinToString("|").ifBlank { assumption.s("id") } }, sourceTitle, reason,
        food ?: assumption.s("foodName").takeIf { it.isNotBlank() }?.let { j("name" to it) })
}

fun assumptionGroups(snapshot: JSONObject, assumptions: List<JSONObject>): List<List<AssumptionPresentation>> =
    assumptions.map { presentAssumption(snapshot, it) }.groupBy { it.sourceKey }.values.toList()
        .sortedBy { group -> if (group.all { it.unlinkedFood }) 1 else 0 }

@Composable
fun AssumptionFoodRow(row: AssumptionPresentation, tag: String, expanded: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).semantics {
        stateDescription = if (expanded) "Expanded" else "Collapsed"
    }.clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (row.food != null) Image(ingredientIcon(row.food), null, Modifier.size(34.dp))
        else Icon(if (row.reason == "from preference") Icons.Outlined.FavoriteBorder else Icons.Outlined.Inventory2, null, Modifier.size(30.dp), tint = Muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.name, style = GardenType.Body)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SparkleMark(Modifier.size(10.dp)); Text(row.reason, style = GardenType.Small)
            }
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight, "Evidence", Modifier.size(20.dp), tint = Muted)
    }
}

@Composable
fun AssumptionEvidence(record: JSONObject) {
    Text(humanDatesInText(record.s("statement")), style = GardenType.Body)
    Text(humanDate(record.s("created_at")), style = GardenType.Small)
    record.a("evidence").strings().forEach { evidence -> Text(humanDatesInText(evidence), style = GardenType.Small) }
}


val AssumptionPresentation.unlinkedFood: Boolean get() = food == null && reason != "from preference"
