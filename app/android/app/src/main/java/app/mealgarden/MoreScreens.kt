@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mealgarden

import android.graphics.ImageDecoder
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun MoreScreen(open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { GardenTopBar("More") }
        listOf("app" to "Your app", "activity" to "Activity", "receipts" to "Receipts", "preferences" to "Preferences", "ask" to "Ask", "connection" to "Connection").forEach { (route, label) ->
            item { GardenCard(onClick = { open(route) }) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(when (route) { "app" -> Icons.Outlined.Tune; "activity" -> Icons.Outlined.History; "receipts" -> Icons.Outlined.ReceiptLong; "preferences" -> Icons.Outlined.FavoriteBorder; "ask" -> Icons.Outlined.HelpOutline; else -> Icons.Outlined.Link }, null, tint = Forest, modifier = Modifier.size(24.dp))
                Text(label, style = GardenType.Body, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
            } } }
        }
    }
}

@Composable
fun ShellDetail(vm: GardenModel, page: String, onBack: () -> Unit) {
    when (page) {
        "app" -> YourAppScreen(vm, onBack)
        "activity" -> ActivityScreen(vm, onBack)
        "receipts" -> ReceiptsScreen(vm, onBack)
        "shopping" -> Column { GardenTopBar("Shopping", onBack); MarketScreen(vm, showTitle = false) }
        else -> YourAppScreen(vm, onBack)
    }
}

private data class GardenModule(val id: String, val name: String, val where: String, val icon: ImageVector, val default: Boolean = true, val advanced: Boolean = false)
private val gardenModules = listOf(
    GardenModule("timeline", "Food timeline", "Today", Icons.Outlined.RadioButtonChecked),
    GardenModule("nutrition", "Nutrition ranges", "Today", Icons.Outlined.BarChart),
    GardenModule("basket", "Today's basket", "Today", Icons.Outlined.ShoppingBasket),
    GardenModule("week", "Weekly variety", "Today", Icons.Outlined.CalendarViewWeek),
    GardenModule("cookQuestions", "Cook questions", "Cook", Icons.Outlined.HelpOutline),
    GardenModule("weight", "Weight on capture", "Capture", Icons.Outlined.Scale, default = false),
    GardenModule("lanes", "Appliance lanes", "Cook", Icons.Outlined.ViewWeek, default = false, advanced = true),
    GardenModule("shoppingTimeline", "Shopping in the timeline", "Today", Icons.Outlined.ShoppingBasket, default = false, advanced = true),
)

@Composable
fun YourAppScreen(vm: GardenModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("your-app"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { GardenTopBar("Your app", onBack) }
        items(gardenModules, key = { it.id }) { module ->
            val enabled = moduleEnabled(vm, module.id, module.default)
            GardenCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(module.icon, null, tint = if (enabled) Forest else Muted, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(module.name, style = GardenType.Body)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GardenChip(module.where, onClick = {
                                vm.track("module_destination", "module" to module.id)
                                when (module.where) {
                                    "Today" -> { onBack(); vm.tab = 0 }
                                    "Capture" -> { onBack(); vm.openCapture = true }
                                    else -> { onBack(); vm.tab = 1 }
                                }
                            })
                            if (module.advanced) GardenChip("Advanced")
                        }
                    }
                    Switch(enabled, onCheckedChange = { checked ->
                        vm.prefs.edit().putBoolean("module:${module.id}", checked).apply()
                        vm.track("module_toggle", "module" to module.id, "enabled" to checked)
                    }, modifier = Modifier.testTag("module:${module.id}").semantics { contentDescription = module.name })
                }
                if (module.advanced) ModulePreview(vm, module.id)
            }
        }
    }
}

@Composable
private fun ModulePreview(vm: GardenModel, id: String) {
    GardenCard(color = Paper2, modifier = Modifier.testTag("module-preview:$id")) {
        Text("Preview", style = GardenType.Small)
        if (id == "lanes") {
            val name = vm.snapshot.o("kitchen").a("available").strings().firstOrNull { appliance -> appliance.lowercase().let { "oven" in it || "stove" in it || "induction" in it } } ?: "Appliance"
            listOf(name to .45f, "Chop" to .8f).forEach { (label, progress) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (label == "Chop") Icons.Outlined.ContentCut else Icons.Outlined.Kitchen, null, Modifier.size(18.dp), tint = Forest)
                    Text(label, style = GardenType.Small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(95.dp), color = Forest, trackColor = Line)
                }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GardenBowl(Modifier.size(36.dp))
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = Muted)
            GardenChip(if (vm.shoppingEnabled) vm.storeName else "Shopping", icon = Icons.Outlined.ShoppingBasket)
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = Muted)
            GardenBowl(Modifier.size(36.dp))
        }
    }
}

@Composable
fun ActivityScreen(vm: GardenModel, onBack: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf("") }
    val assumptions = vm.graphAssumptions()
    val jobs = vm.activity.a("jobs").objects()
    LazyColumn(Modifier.fillMaxSize().testTag("garden-activity"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { GardenTopBar("Activity", onBack) }
        if (assumptions.isNotEmpty()) item { Text("Assumed", style = GardenType.Section) }
        items(assumptions, key = { "assumption:${it.s("id")}" }) { assumption ->
            val assumptionKey = "assumption:${assumption.s("id")}"
            val isExpanded = expanded == assumptionKey
            GardenCard(modifier = Modifier.testTag("activity-assumption:${assumption.s("id")}").semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" }, onClick = { expanded = if (isExpanded) "" else assumptionKey }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SparkleMark()
                    Text(assumption.s("statement").removePrefix("Confirm imported preference: "), style = GardenType.Body, modifier = Modifier.weight(1f), maxLines = if (isExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = Muted)
                    TextButton(onClick = {
                        val preferences = vm.graphPreferences().filter { it.s("id") in assumption.a("evidence").strings() }
                        if (preferences.isEmpty()) vm.resolveAssumptions(listOf(assumption.s("id")), "corrected")
                        else preferences.forEach { vm.changePreference(it, null, null) }
                    }, modifier = Modifier.testTag("activity-undo:${assumption.s("id")}")) { Text("Undo") }
                }
                Text(activityDate(assumption.s("created_at")), style = GardenType.Small)
            }
        }
        if (vm.outbox.isNotEmpty()) item { Text("Waiting to sync", style = GardenType.Section) }
        items(vm.outbox, key = { "outbox:${it.o("payload").s("idempotencyKey")}" }) { write ->
            GardenCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CloudUpload, null, tint = Muted, modifier = Modifier.size(18.dp))
                    val payload = write.o("payload")
                    val food = vm.graphPantry().firstOrNull { it.s("id") == payload.s("itemId") }?.s("name").orEmpty()
                    Text(food.ifBlank { payload.s("name").ifBlank { activityWriteLabel(write.s("route")) } }, style = GardenType.Body, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(activityWriteLabel(write.s("route")), style = GardenType.Small)
                }
                Text(activityDate(write.s("at")), style = GardenType.Small)
            }
        }
        if (jobs.isNotEmpty()) item { Text("On the laptop", style = GardenType.Section) }
        items(jobs, key = { "job:${it.s("id")}" }) { job ->
            GardenCard(modifier = Modifier.testTag("activity-job:${job.s("id")}"), onClick = { expanded = if (expanded == job.s("id")) "" else job.s("id") }) {
                val title = activityJobTitle(vm, job)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(when (job.s("status")) { "completed" -> Icons.Outlined.CheckCircleOutline; "failed", "blocked", "partial", "unverified", "needs_sign_in" -> Icons.Outlined.ErrorOutline; "running", "queued", "waiting" -> Icons.Outlined.Schedule; else -> Icons.Outlined.PauseCircleOutline }, null, Modifier.size(21.dp), tint = if (job.s("status") == "completed") Forest else Muted)
                    Text(title, modifier = Modifier.weight(1f), style = GardenType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(activityStatus(job.s("status")), style = GardenType.Small)
                }
                Text((if (title != activityJobLabel(job.s("kind"))) "${activityJobLabel(job.s("kind"))} · " else "") + activityDate(job.s("created").ifBlank { job.s("updated") }), style = GardenType.Small)
                if (expanded == job.s("id")) {
                    job.s("model").takeIf { it.isNotBlank() }?.let { Text(it, style = GardenType.Small) }
                    job.s("progress").takeIf { it.isNotBlank() }?.let { Text(it, style = GardenType.Body) }
                    job.s("error").takeIf { it.isNotBlank() }?.let { Text(it, color = Clay, fontSize = 13.sp) }
                    TextButton(onClick = { vm.chooseConversation(job.s("conversation_id")); onBack() }) { Text("Open conversation") }
                }
                if (job.s("status") in listOf("running", "queued", "waiting")) TextButton(onClick = { vm.stop(job.s("id")) }, modifier = Modifier.testTag("activity-stop:${job.s("id")}")) { Text("Stop") }
            }
        }
        if (jobs.isEmpty() && assumptions.isEmpty() && vm.outbox.isEmpty()) item { GardenCard { Text("No recent activity", style = GardenType.Body) } }
    }
}

/** Capture interpretation titles are evidence; job input prompts never become row titles. */
fun activityJobTitle(vm: GardenModel, job: JSONObject): String {
    val captureId = job.o("input").s("captureId").ifBlank { job.s("capture_id") }
    val capture = captureId.takeIf { it.isNotBlank() }?.let { id -> vm.foodLog().firstOrNull { it.s("id") == id } }
    return capture?.o("server")?.o("interpretation")?.s("title")?.takeIf { it.isNotBlank() } ?: activityJobLabel(job.s("kind"))
}

internal fun activityDate(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).atZoneSameInstant(householdZone).toLocalDate().toString()
}.getOrElse { Regex("^\\d{4}-\\d{2}-\\d{2}").find(value)?.value ?: "Date unknown" }

private fun activityWriteLabel(route: String) = when (route) {
    "/api/pantry/count" -> "Amount"; "/api/pantry/toss" -> "Tossed"; "/api/pantry/condition" -> "Condition"; "/api/pantry/transfer" -> "Moved"; "/api/pantry/add" -> "Added food"; "/api/assumptions/resolve" -> "Assumption"; "/api/preferences" -> "Preference"; "/api/reactions" -> "Rating"; "/api/batches" -> "Cooked"; else -> "Saved change"
}
private fun activityJobLabel(kind: String) = when (kind) { "reset_plan" -> "Meal plan"; "log_food" -> "Food log"; "shopping" -> "Shopping"; "purchases" -> "Receipts"; "connection" -> "Connection"; "interview" -> "Kitchen"; "product_lookup" -> "Food details"; "reflect" -> "Cook feedback"; else -> "Conversation" }
private fun activityStatus(status: String) = when (status) { "completed" -> "Done"; "queued" -> "Queued"; "running" -> "Working"; "waiting" -> "Needs you"; "cancelled" -> "Stopped"; "unverified" -> "Not verified"; "needs_sign_in" -> "Sign-in needed"; else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() } }

@Composable
fun ReceiptsScreen(vm: GardenModel, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    var shopping by rememberSaveable { mutableStateOf(false) }
    val receiptCamera = rememberCamera { file ->
        try {
            val bytes = compressPhoto(ImageDecoder.createSource(file)).first
            vm.attachment = ""; vm.attachmentName = ""
            vm.upload(bytes)
            vm.ask("Import this receipt photo. Keep uncertain amounts visible, omit payment and loyalty details, and check for duplicates.", origin = "receipt_import")
        } catch (_: Exception) { vm.error = "Could not read that receipt photo" }
        finally { file.delete() }
    }
    fun importReceipt() { if (vm.paired) receiptCamera() else vm.openSettings = true }
    val receipts = vm.snapshot.a("receipts").objects().sortedByDescending { it.s("date") }
    val receipt = receipts.firstOrNull { it.s("id") == selected }
    BackHandler(enabled = shopping || receipt != null) { if (shopping) shopping = false else selected = "" }
    if (shopping) { Column { GardenTopBar("Shopping", onBack = { shopping = false }); MarketScreen(vm, showTitle = false) }; return }
    LazyColumn(Modifier.fillMaxSize().testTag("garden-receipts"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GardenTopBar(if (receipt != null) "Receipt" else "Receipts", onBack = if (receipt != null) ({ selected = "" }) else onBack) {
                if (receipt == null) IconButton(onClick = { importReceipt() }) { Icon(Icons.Outlined.PhotoCamera, "Import receipt photo", tint = Forest) }
            }
        }
        if (receipt == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.shoppingEnabled) GardenQuietButton("Refresh", onClick = { vm.send("purchases") }, icon = Icons.Outlined.Refresh, enabled = !vm.busy)
                    GardenQuietButton("Shopping", onClick = { shopping = true }, icon = Icons.Outlined.ShoppingBasket)
                }
            }
            if (receipts.isEmpty()) item { GardenCard { Text("No receipts yet", style = GardenType.Body); GardenQuietButton("Import receipt photo", onClick = { importReceipt() }, icon = Icons.Outlined.PhotoCamera) } }
            items(receipts, key = { it.s("id") }) { record ->
                GardenCard(onClick = { vm.track("receipt_open", "id" to record.s("id")); selected = record.s("id") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(26.dp), tint = Forest)
                        Column(Modifier.weight(1f)) { Text(record.s("store"), style = GardenType.Body); Text(record.s("date").take(10), style = GardenType.Small) }
                        if (!record.isNull("total")) Text(receiptMoney(record.optDouble("total")), style = GardenType.Body)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                    }
                }
            }
        } else {
            item { GardenCard { Text(receipt.s("store"), style = GardenType.Section); Text(receipt.s("date").take(10), style = GardenType.Small) } }
            items(receipt.a("items").objects()) { line ->
                GardenCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(ingredientIcon(line), null, Modifier.size(34.dp))
                        Column(Modifier.weight(1f)) { Text(line.s("name"), style = GardenType.Body); if (line.s("quantity").isNotBlank()) Text(line.s("quantity"), style = GardenType.Small) }
                        if (!line.isNull("price")) Text(receiptMoney(line.optDouble("price")), style = GardenType.Small)
                    }
                }
            }
            if (!receipt.isNull("total")) item { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", style = GardenType.Section); Text(receiptMoney(receipt.optDouble("total")), style = GardenType.Section) } }
        }
    }
}

private fun receiptMoney(amount: Double) = "$${"%.2f".format(java.util.Locale.US, amount)}"
