package app.mealgarden

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

val KitchenZones = listOf("fridge" to "Fridge", "drawers" to "Veg drawers", "freezer" to "Freezer", "pantry" to "Pantry", "counter" to "Counter")

fun kitchenZone(item: JSONObject): String {
    val location = item.s("location").lowercase()
    return when {
        "freez" in location -> "freezer"
        "drawer" in location || "crisper" in location -> "drawers"
        "pantry" in location || "cupboard" in location -> "pantry"
        "counter" in location -> "counter"
        "fridge" in location || "refriger" in location -> "fridge"
        else -> "unknown"
    }
}

/** One drawing per product and zone. The original lots remain independently editable. */
fun kitchenGroups(items: List<JSONObject>): List<JSONObject> = items.groupBy {
    (it.s("product_id").ifBlank { it.s("name") }) + ":" + kitchenZone(it)
}.values.map { lots ->
    JSONObject(lots.first().toString()).apply {
        put("_lotIds", JSONArray(lots.map { it.s("id") }))
        val amounts = lots.map { it.optDouble("balance", Double.NaN) }
        put("_total", if (amounts.all { it.isFinite() }) amounts.sum() else JSONObject.NULL)
        put("_lotCount", lots.size)
        put("basis", when {
            lots.any { it.s("basis") == "unknown" } -> "unknown"
            lots.any { it.s("basis") == "assumed" } -> "assumed"
            else -> "known"
        })
        val urgent = lots.minByOrNull { when (it.s("urgency")) { "past" -> 0; "soon" -> 1; "unknown" -> 2; "fresh" -> 3; else -> 4 } }
        if (urgent != null) put("urgency", urgent.s("urgency"))
        put("condition", when {
            lots.any { it.s("condition") == "use_soon" } -> "use_soon"
            lots.all { it.s("condition") == "fine" } -> "fine"
            else -> JSONObject.NULL
        })
    }
}

/** Reads explicit layout first, then attributed kitchen notes; never an owner-specific position. */
fun kitchenFreezerPosition(snapshot: JSONObject, preferences: List<JSONObject>): String {
    val kitchen = snapshot.o("kitchen")
    val layouts = listOf(kitchen.o("layout"), kitchen.o("storage"), kitchen, snapshot.o("household").o("kitchen").o("layout"))
    val explicit = layouts.firstNotNullOfOrNull { layout ->
        listOf("freezer_position", "freezerPosition", "freezer").firstNotNullOfOrNull { key ->
            (layout.optJSONObject(key)?.s("position") ?: layout.s(key)).lowercase().takeIf { it in listOf("above", "top", "below", "bottom") }
        }
    }
    if (explicit != null) return if (explicit in listOf("below", "bottom")) "below" else "above"
    val preference = preferences.asReversed().sortedByDescending { it.s("created_at").ifBlank { "9999" } }.firstNotNullOfOrNull { record ->
        val subject = record.s("subject").lowercase()
        val value = record.opt("value")
        if ("layout" in subject || "freezer" in subject) {
            val position = (value as? JSONObject)?.let { it.s("freezer_position", it.s("freezerPosition")) }
                ?: (value as? String).orEmpty()
            position.lowercase().takeIf { it in listOf("above", "top", "below", "bottom") }
                ?: Regex("freezer\\s+(?:is\\s+)?(?:on\\s+(?:the\\s+)?)?(above|below|top|bottom)", RegexOption.IGNORE_CASE)
                    .find(record.s("statement"))?.groupValues?.get(1)?.lowercase()
        } else null
    }
    val learned = snapshot.a("learned").objects().filter { it.s("area") == "kitchen" }.sortedByDescending { it.s("recordedAt") }.firstNotNullOfOrNull {
        Regex("freezer\\s+(?:is\\s+)?(?:on\\s+(?:the\\s+)?)?(above|below|top|bottom)", RegexOption.IGNORE_CASE)
            .find(it.s("note"))?.groupValues?.get(1)?.lowercase()
    }
    return if ((preference ?: learned) in listOf("below", "bottom")) "below" else "above"
}

private data class KitchenBox(val x: Float, val y: Float, val w: Float, val h: Float)
private data class KitchenAppliance(val name: String, val kind: String)

private fun kitchenAppliances(snapshot: JSONObject): List<KitchenAppliance> {
    val kitchen = snapshot.o("kitchen")
    val applianceArray = kitchen.a("appliances")
    fun equipmentRecord(record: JSONObject, key: String = ""): Pair<String, String> {
        val name = record.s("name").ifBlank { listOf(record.s("brand"), record.s("model")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { key.ifBlank { record.s("type", record.s("kind", "Appliance")) } } }
        return name to "$key ${record.s("type", record.s("kind"))} $name"
    }
    val objects = applianceArray.objects().map { equipmentRecord(it) } + kitchen.o("appliances").let { obj ->
        obj.keys().asSequence().mapNotNull { key ->
            when (val record = obj.opt(key)) {
                is JSONObject -> equipmentRecord(record, key)
                is String -> record to "$key $record"
                else -> null
            }
        }.toList()
    }
    val names = (0 until applianceArray.length()).mapNotNull { applianceArray.opt(it) as? String } +
        kitchen.a("available").strings() + kitchen.a("equipment").strings()
    return (objects + names.map { it to it }).mapNotNull { (name, description) ->
        val n = description.lowercase().replace('-', ' ').replace('_', ' ')
        val kind = when {
            "microwave" in n -> "microwave"
            "induction" in n || "duxtop" in n || "hot plate" in n || "hotplate" in n -> "hob"
            "air fry" in n || "toaster oven" in n || "countertop oven" in n || "dt551" in n -> "counteroven"
            "stove" in n || "cooktop" in n || "range" in n -> "stove"
            "oven" in n -> "oven"
            else -> return@mapNotNull null
        }
        KitchenAppliance(name, kind)
    }.distinctBy { it.kind }
}

/** Full kitchen and a compact cook-mode drawing share the same data and geometry. */
@Composable
fun KitchenDrawing(vm: GardenModel, modifier: Modifier = Modifier, activeEquipment: String = "", onItem: (JSONObject) -> Unit = {}, zone: String = "", selectedId: String = "", onAppliance: (String) -> Unit = {}) {
    val below = kitchenFreezerPosition(vm.snapshot, vm.graphPreferences()) == "below"
    val pantry = vm.graphPantry()
    val groups = kitchenGroups(pantry.filter { it.optDouble("balance", Double.NaN) != 0.0 })
    val zones = linkedMapOf(
        "freezer" to KitchenBox(10f, if (below) 236f else 8f, 108f, 55f),
        "fridge" to KitchenBox(10f, if (below) 8f else 65f, 108f, 133f),
        "drawers" to KitchenBox(10f, if (below) 144f else 201f, 108f, 87f),
        "pantry" to KitchenBox(130f, 8f, 200f, 84f),
        "counter" to KitchenBox(206f, 97f, 124f, 43f),
        "unknown" to KitchenBox(205f, 208f, 122f, 80f),
    ).filterKeys { it != "unknown" || zone == "unknown" || groups.any { food -> kitchenZone(food) == "unknown" } }
    val appliances = kitchenAppliances(vm.snapshot)
    val active = activeEquipment.lowercase()
    val compact = active.isNotBlank()
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds().testTag("kitchen-drawing").semantics { contentDescription = "Kitchen drawing" }) {
        val height = if (constraints.hasBoundedHeight) maxHeight else 340.dp
        val focus = zones[zone]?.takeIf { !compact }
        val viewport = focus?.let { KitchenBox(it.x - 5, it.y - 5, it.w + 10, it.h + 10) } ?: KitchenBox(0f, 0f, 340f, 300f)
        val scale = minOf(maxWidth.value / viewport.w, height.value / viewport.h)
        val dx = (maxWidth.value - viewport.w * scale) / 2 - viewport.x * scale
        val dy = (height.value - viewport.h * scale) / 2 - viewport.y * scale
        fun position(box: KitchenBox) = Modifier.offset((dx + box.x * scale).dp, (dy + box.y * scale).dp).size((box.w * scale).dp, (box.h * scale).dp)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val s = scale.dp.toPx()
            val x = dx.dp.toPx(); val y = dy.dp.toPx()
            fun rect(box: KitchenBox, color: Color, stroke: Color? = null) {
                val origin = Offset(x + box.x * s, y + box.y * s); val size = Size(box.w * s, box.h * s)
                drawRoundRect(color, origin, size, CornerRadius(5 * s))
                if (stroke != null) drawRoundRect(stroke, origin, size, CornerRadius(5 * s), style = Stroke(1.1f * s))
            }
            rect(KitchenBox(0f, 0f, 340f, 300f), Paper2)
            rect(KitchenBox(5f, 3f, 118f, 293f), Color(0xFFE7ECE5), Muted)
            zones.forEach { (name, box) ->
                rect(box, when (name) { "freezer" -> IceLight; "drawers" -> Color(0xFFDCEBD9); "fridge" -> CardSurface; else -> Color.Transparent })
                val rows = when (name) { "fridge" -> 3; "drawers", "pantry" -> 2; "unknown" -> 3; else -> 1 }
                repeat(rows) { row ->
                    val by = y + (box.y + 17 + (row + 1) * (box.h - 17) / rows) * s
                    drawLine(if (name in listOf("pantry", "counter")) Color(0xFFB08A5E) else Line, Offset(x + box.x * s, by), Offset(x + (box.x + box.w) * s, by), 2 * s)
                }
            }
            rect(KitchenBox(130f, 193f, 200f, 5f), Color(0xFF8C6B4F))
            rect(KitchenBox(130f, 199f, 200f, 92f), Color(0xFFE7DCC6), Line)
            drawLine(Line, Offset(x + 232 * s, y + 199 * s), Offset(x + 232 * s, y + 291 * s), s)
            drawCircle(Muted, 2 * s, Offset(x + 224 * s, y + 246 * s)); drawCircle(Muted, 2 * s, Offset(x + 240 * s, y + 246 * s))
        }
        zones.forEach { (name, box) ->
            val dim by animateFloatAsState(if (zone.isNotBlank() && zone != name || compact) .4f else 1f, label = "kitchen-zone")
            Box(position(box).alpha(dim).testTag("kitchen-zone:$name")) {
                if (!compact) Text((KitchenZones.firstOrNull { it.first == name }?.second ?: "Unplaced").uppercase(), fontSize = (8.5f * scale).sp, color = Muted, modifier = Modifier.padding(4.dp))
                val foods = groups.filter { kitchenZone(it) == name }
                if (!compact) {
                    val rows = when (name) { "fridge" -> 3; "drawers", "pantry" -> 2; "unknown" -> 3; else -> 1 }
                    val perRow = maxOf(if (name in listOf("pantry", "counter")) 6 else 3, (foods.size + rows - 1) / rows)
                    val cellW = box.w / perRow
                    val cellH = (box.h - 17) / rows
                    foods.forEachIndexed { index, food ->
                        val row = index / perRow; val col = index % perRow
                        val selected = selectedId in food.a("_lotIds").strings()
                        val amount = food.optDouble("_total", Double.NaN)
                        val packageCounts = pantry.filter { it.s("id") in food.a("_lotIds").strings() }.map { lot ->
                            val balance = lot.optDouble("balance", Double.NaN)
                            pantryPackage(vm.snapshot, lot)?.first?.let { size -> if (balance.isFinite() && size > 0) balance / size else null }
                        }
                        val quantity = if (packageCounts.isNotEmpty() && packageCounts.all { it != null }) packageCounts.filterNotNull().sum()
                            else if (food.s("base_unit") == "count") amount else Double.NaN
                        val color = when { food.s("condition") == "fine" -> Forest; kitchenZone(food) == "freezer" && food.s("condition") != "use_soon" -> Forest; food.s("condition") == "use_soon" || food.s("urgency") == "soon" -> Amber; food.s("urgency") == "past" -> Clay; else -> Forest }
                        Column(Modifier.offset((col * cellW * scale).dp, ((17 + row * cellH) * scale).dp).size((cellW * scale).dp, (cellH * scale).dp)
                            .background(if (selected) Lime.copy(alpha = .5f) else Color.Transparent, RoundedCornerShape(5.dp))
                            .then(if (!amount.isFinite() || amount < 0) Modifier.border(1.dp, Faint.copy(alpha = .4f), RoundedCornerShape(5.dp)) else Modifier)
                            .clickable { vm.track("pantry_item_open", "itemId" to food.s("id")); onItem(food) }
                            .semantics { contentDescription = food.s("name") + when {
                                !amount.isFinite() -> ", amount unknown"
                                amount < 0 -> ", amount needs checking"
                                quantity.isFinite() -> ", ${kitchenNumber(quantity)} left"
                                else -> ", ${kitchenNumber(amount)} ${food.s("base_unit")} left"
                            } }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Image(ingredientIcon(food), null, Modifier.fillMaxSize().padding(2.dp))
                                if (food.s("basis") == "assumed") SparkleMark(Modifier.align(Alignment.TopEnd).size(8.dp))
                            }
                            if (!amount.isFinite() || amount < 0) Text("?", color = Muted, fontSize = (8.5f * scale).sp)
                            val showAmount = quantity.isFinite() && quantity > 1 || amount.isFinite() && amount >= 0 && food.optInt("_lotCount") > 1
                            if (showAmount) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(if (quantity.isFinite()) "×${kitchenNumber(quantity)}" else "${kitchenNumber(amount)}${food.s("base_unit")}", color = Ink, fontSize = (8.5f * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.fillMaxWidth(.78f).height(3.dp).background(color.copy(alpha = if (food.s("basis") == "unknown") .25f else .85f), RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        appliances.forEachIndexed { index, appliance ->
            val box = when (appliance.kind) {
                "stove" -> KitchenBox(132f, 180f, 61f, 110f)
                "oven" -> KitchenBox(134f, 210f, 57f, 70f)
                "microwave" -> KitchenBox(132f, 103f, 68f, 40f)
                "hob" -> KitchenBox(271f, 157f, 52f, 37f)
                "counteroven" -> KitchenBox(207f, 143f, 57f, 49f)
                else -> KitchenBox(132f + index * 56, 145f, 50f, 47f)
            }
            val activeKind = when {
                "microwave" in active -> "microwave"
                "ninja" in active || "air fry" in active || "toaster" in active || "countertop oven" in active -> "counteroven"
                "duxtop" in active || "induction" in active || "hob" in active || "hot plate" in active -> "hob"
                "stove" in active || "cooktop" in active -> "stove"
                "oven" in active -> "oven"
                else -> null
            }
            val highlight = compact && if (activeKind != null) appliance.kind == activeKind
                else active != "none" && active.contains(appliance.name.lowercase())
            val opacity by animateFloatAsState(if (compact && !highlight) .35f else 1f, label = "appliance-highlight")
            Canvas(position(box).alpha(opacity).clickable { vm.track("kitchen_appliance", "name" to appliance.name); onAppliance(appliance.name) }
                .semantics { contentDescription = appliance.name + if (highlight) ", in use" else "" }) {
                if (highlight) drawRoundRect(Lime, size = size, cornerRadius = CornerRadius(9.dp.toPx()))
                val pad = 4.dp.toPx(); val body = Size(size.width - pad * 2, size.height - pad * 2)
                if (appliance.kind == "hob") {
                    drawRoundRect(Ink, Offset(pad, size.height * .7f), Size(body.width, size.height * .25f), CornerRadius(3.dp.toPx()))
                    drawRoundRect(Color(0xFFB9BDB8), Offset(size.width * .2f, pad), Size(size.width * .6f, size.height * .62f), CornerRadius(3.dp.toPx()))
                    drawLine(Ink, Offset(size.width * .16f, pad), Offset(size.width * .85f, pad), 2.dp.toPx())
                } else {
                    drawRoundRect(if (appliance.kind == "stove") Paper2 else Color(0xFF454A45), Offset(pad, pad), body, CornerRadius(4.dp.toPx()))
                    drawRoundRect(Ink, Offset(size.width * .13f, size.height * .25f), Size(size.width * .66f, size.height * .6f), CornerRadius(3.dp.toPx()))
                    drawLine(Line, Offset(size.width * .16f, size.height * .18f), Offset(size.width * .75f, size.height * .18f), 2.dp.toPx())
                    drawCircle(if (highlight) Lime else Ice, 2.dp.toPx(), Offset(size.width * .88f, size.height * .35f))
                    if (appliance.kind == "stove") repeat(2) { burner -> drawOval(Ink, Offset(size.width * (.13f + burner * .48f), 0f), Size(size.width * .28f, size.height * .055f)) }
                }
            }
        }
    }
}

fun kitchenNumber(value: Double): String = if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)
