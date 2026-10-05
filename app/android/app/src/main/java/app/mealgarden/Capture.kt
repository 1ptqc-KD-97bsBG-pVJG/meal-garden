@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Decode (honoring EXIF rotation), downscale to [maxSide], and re-encode as JPEG. */
fun compressPhoto(source: ImageDecoder.Source, maxSide: Int = 1800, quality: Int = 85): Triple<ByteArray, Int, Int> {
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = max(info.size.width, info.size.height) / maxSide.toFloat()
        if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
        return Triple(out.toByteArray(), bitmap.width, bitmap.height)
    } finally {
        bitmap.recycle()
    }
}

/**
 * Disposable camera: the photo goes to app cache (never the gallery) and [onPhoto] receives the file.
 * Returns a launcher that asks for camera permission first when needed.
 */
@Composable
fun rememberCamera(onPhoto: (File) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPhoto)
    var pending by rememberSaveable { mutableStateOf("") }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = File(pending)
        pending = ""
        if (ok && file.length() > 0) latest(file) else file.delete()
    }
    val open = {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        pending = file.absolutePath
        take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) open()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

@Composable
fun LocalPhoto(file: File, modifier: Modifier, targetPx: Int = 480) {
    val image by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            if (!file.exists()) null
            else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    Box(modifier.background(Mist)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private val captureKinds = listOf(
    Triple("meal", "Meal", Icons.Outlined.RestaurantMenu),
    Triple("snack", "Snack", Icons.Outlined.Cookie),
    Triple("drink", "Drink", Icons.Outlined.LocalCafe),
    Triple("label", "Label", Icons.Outlined.Receipt),
    Triple("menu", "Menu", Icons.Outlined.MenuBook),
    Triple("other", "Other", Icons.Outlined.MoreHoriz),
)

fun kindIcon(kind: String): ImageVector = captureKinds.find { it.first == kind }?.third ?: Icons.Outlined.RestaurantMenu

/** Bottom sheet shown right after a photo (or for a text-only log). One tap saves. */
@Composable
fun CaptureSheet(vm: GardenModel) {
    val draft = vm.draftCapture ?: return
    val id = draft.s("id")
    val photos = vm.capturePhotos(draft)
    val hasPhoto = photos.isNotEmpty()
    val camera = rememberCamera { vm.addCapturePhoto(it, id) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        vm.addCapturePhotos(uris, id)
    }
    var kind by rememberSaveable(id) { mutableStateOf("meal") }
    var note by rememberSaveable(id) { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.let { note = (note + " " + it).trim() }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.discardCapture() }, sheetState = sheet, containerColor = Cream) {
        Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hasPhoto) {
                Text("${photos.size} ${if (photos.size == 1) "photo" else "photos"} · one entry", color = Muted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    photos.forEachIndexed { index, photo ->
                        Box {
                            LocalPhoto(vm.capturePhotoFile(draft, photo), Modifier.size(160.dp).clip(RoundedCornerShape(20.dp)), 480)
                            IconButton(onClick = { vm.removeCapturePhoto(photo) }, enabled = !vm.capturePhotoBusy,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).background(Cream, CircleShape)) {
                                Icon(Icons.Outlined.Close, "Remove photo ${index + 1}", tint = Forest)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { camera() }, enabled = !vm.capturePhotoBusy && photos.size < 10) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Take photo")
                }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !vm.capturePhotoBusy && photos.size < 10) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose photos")
                }
            }
            if (vm.capturePhotoBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                captureKinds.forEach { (value, label, icon) ->
                    FilterChip(
                        selected = kind == value,
                        onClick = { kind = value },
                        label = { Text(label) },
                        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Forest, selectedLeadingIconColor = Forest),
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(4000) },
                placeholder = { Text(if (hasPhoto) "Anything the photo won't show? (optional)" else "What did you have?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = {
                        voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                    }) { Icon(Icons.Outlined.Mic, "Dictate", tint = Muted) }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.discardCapture() }) { Text("Discard", color = Muted) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.saveCapture(kind, note) },
                    enabled = !vm.capturePhotoBusy && (hasPhoto || note.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Log it")
                }
            }
        }
    }
}

private fun range(nutrition: JSONObject?, key: String): NutritionRange? = nutritionRange(nutrition, key)
private fun rangeText(r: NutritionRange?, unit: String) = r?.text(unit)

/** Hour at which a food day starts (a setting from the laptop, default 4 a.m.), so late-night food counts toward the evening. */
var foodDayStartHour = 4

var householdZone: ZoneId = ZoneId.systemDefault()

fun foodToday(): LocalDate = java.time.ZonedDateTime.now(householdZone).minusHours(foodDayStartHour.toLong()).toLocalDate()

fun localDay(capturedAt: String): LocalDate = try {
    OffsetDateTime.parse(capturedAt).atZoneSameInstant(householdZone).minusHours(foodDayStartHour.toLong()).toLocalDate()
} catch (_: Exception) { LocalDate.MIN }

private fun localTime(capturedAt: String): String = try {
    OffsetDateTime.parse(capturedAt).atZoneSameInstant(householdZone).format(DateTimeFormatter.ofPattern("h:mm a"))
} catch (_: Exception) { "" }

/** A projection of captures, never a separate food store. */
data class TodayPlate(val entries: List<JSONObject>, val drinks: List<JSONObject> = emptyList())

private fun stripInterpretation(entry: JSONObject) = entry.o("server").o("interpretation")
private fun stripCategory(entry: JSONObject) = stripInterpretation(entry).s("category").ifBlank { entry.s("kind") }
private fun captureInstant(entry: JSONObject) = runCatching { OffsetDateTime.parse(entry.s("capturedAt")).toInstant() }.getOrNull()

fun todayPlates(entries: List<JSONObject>, day: LocalDate): List<TodayPlate> {
    val food = entries.filter { localDay(it.s("capturedAt")) == day && stripCategory(it) != "supplement" }
        .sortedBy { captureInstant(it) }
    val meals = food.filter { stripCategory(it) == "meal" }
    val attached = food.filter { stripCategory(it) == "drink" }.mapNotNull { drink ->
        val time = captureInstant(drink) ?: return@mapNotNull null
        val nearest = meals.minByOrNull { kotlin.math.abs(java.time.Duration.between(time, captureInstant(it)).toMillis()) }
            ?: return@mapNotNull null
        if (kotlin.math.abs(java.time.Duration.between(time, captureInstant(nearest)).toMillis()) <= 30 * 60_000L) drink to nearest else null
    }
    val groups = linkedMapOf<String, MutableList<JSONObject>>()
    food.filter { entry -> attached.none { it.first === entry } }.forEachIndexed { index, entry ->
        // Blank or missing titles must not collapse unrelated pending captures.
        val title = stripInterpretation(entry).s("title")
        val key = if (title.isNotBlank()) "title:$title" else "entry:$index"
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return groups.values.map { group -> TodayPlate(group, attached.filter { pair -> group.any { it === pair.second } }.map { it.first }) }
}

/** Only interpretation categories count toward the three-meal prompt, not capture intent. */
fun needsMealPlate(entries: List<JSONObject>, day: LocalDate) =
    entries.count { localDay(it.s("capturedAt")) == day && stripInterpretation(it).s("category") == "meal" } < 3

enum class PlateMark { MadeToday, Leftover }

fun todayPlateMark(entry: JSONObject, snapshot: JSONObject): PlateMark? {
    val interpretation = stripInterpretation(entry)
    val time = captureInstant(entry) ?: return null
    val day = localDay(entry.s("capturedAt"))
    fun mark(timestamp: String): PlateMark? {
        val made = runCatching { OffsetDateTime.parse(timestamp).toInstant() }.getOrNull() ?: return null
        if (made > time) return null
        val madeDay = localDay(timestamp)
        return if (madeDay == day) PlateMark.MadeToday else if (madeDay < day) PlateMark.Leftover else null
    }
    val batches = snapshot.a("batches").objects()
    val direct = interpretation.s("batchId")
    val productIds = interpretation.a("components").objects().map { it.s("productId") }.filter { it.isNotBlank() }
    // Homemade products in the current API carry their batch on the pantry/batch view.
    val linkedIds = snapshot.a("pantry").objects().filter { it.s("product_id") in productIds }.map { it.s("batch_id") }
    val linked = batches.filter { it.s("id") == direct && direct.isNotBlank() || it.s("id") in linkedIds || it.s("product_id") in productIds }
    if (direct.isNotBlank() || linked.isNotEmpty()) {
        val marks = linked.map { mark(it.s("made_at", it.s("madeAt"))) }.distinct()
        return marks.singleOrNull() // Mixed origins or missing dates do not earn a definite mark.
    }
    // Explicit product links must not become a guessed recipe link.
    if (productIds.isNotEmpty()) return null
    val recipe = interpretation.s("matchedRecipeId").takeIf { it.isNotBlank() } ?: return null
    val reports = snapshot.a("cooking").objects().filter { it.s("recipeId") == recipe && it.s("status") == "reported_cooked" }
    val candidates = reports.mapNotNull { report ->
        val estimate = report.optJSONObject("cookedDateEstimate")
        if (estimate != null) {
            val first = runCatching { LocalDate.parse(estimate.s("earliest")) }.getOrNull()
            val last = runCatching { LocalDate.parse(estimate.s("latest")) }.getOrNull()
            // A date range can establish an earlier cook, but cannot establish a same-day cook.
            if (first != null && last != null && first <= last && last < day) last to PlateMark.Leftover else null
        } else {
            val timestamp = report.s("madeAt", report.s("createdAt"))
            mark(timestamp)?.let { localDay(timestamp) to it }
        }
    }
    return candidates.maxByOrNull { it.first }?.second
}

/** Consumed food only; missing nutrient values remain unknown. */
@Composable
fun DayNumbers(vm: GardenModel, entries: List<JSONObject>) {
    val day = dayHealth(entries)
    GardenCard(onClick = { vm.track("today_eating_card_tap", "foodDay" to foodToday().toString()); vm.openHealth = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Eating today", style = GardenType.Section, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, "Open health day", tint = Forest, modifier = Modifier.size(18.dp))
        }
        listOf(Triple("calories", "Energy", " kcal"), Triple("protein_g", "Protein", " g"), Triple("fiber_g", "Fiber", " g")).forEach { (key, label, unit) ->
            val amount = day.sum(key)
            val target = vm.healthPreferences.o("targets").optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0 }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = GardenType.Small, color = Ink, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (amount != null && target != null) {
                        val denominator = maxOf(target, amount.high, 1.0)
                        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Paper2)) {
                            Box(Modifier.fillMaxWidth((amount.high / denominator).toFloat().coerceIn(0f,1f)).fillMaxHeight().background(Lime))
                            Box(Modifier.fillMaxWidth((amount.low / denominator).toFloat().coerceIn(0f,1f)).fillMaxHeight().background(Leaf))
                        }
                    }
                    Text(amount?.text(unit) ?: "Unknown", style = GardenType.Small)
                }
            }
        }
        if (day.unresolved > 0) GardenChip("${day.unresolved} awaiting details", icon = Icons.Outlined.HelpOutline)
    }
}

@Composable
fun TodayStripKey(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper,
        title = { Text("Key", style = GardenType.Section) },
        text = { PlateKey() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun FoodPlate(vm: GardenModel, plate: TodayPlate) {
    val entry = plate.entries.first()
    val stacked = plate.entries.size > 1
    val mark = plate.entries.map { todayPlateMark(it, vm.snapshot) }.distinct().singleOrNull()
    val title = stripInterpretation(entry).s("title").ifBlank { entry.s("kind", "Food") }
    var expand by remember { mutableStateOf(false) }
    GardenPlate(title, seed = title.hashCode(), count = plate.entries.size, drink = plate.drinks.isNotEmpty(), mark = mark, onClick = {
        vm.track(if (stacked) "today_stack_tap" else "today_plate_tap", "captureId" to entry.s("id"), "count" to plate.entries.size)
        if (stacked) expand = true else vm.openFoodLog = true
    }, food = {
        val photo = vm.capturePhotos(entry).firstOrNull()?.let { vm.capturePhotoFile(entry,it) }?.takeIf { it.exists() }
        if (photo != null) LocalPhoto(photo, Modifier.size(54.dp).clip(CircleShape), 240)
        else Icon(ingredientIcon(j("name" to title), kindIcon(stripCategory(entry))), null, Modifier.size(38.dp), tint = Color.Unspecified)
    })
    if (expand) AlertDialog(onDismissRequest = { expand = false }, title = { Text(title, style = GardenType.Section) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plate.entries.forEach { Text(localTime(it.s("capturedAt")), style = GardenType.Body) }
        }
    }, confirmButton = { TextButton(onClick = { expand = false; vm.openFoodLog = true }) { Text("Open log") } }, dismissButton = { TextButton(onClick = { expand = false }) { Text("Close") } })
}

@Composable
fun FoodLogStrip(vm: GardenModel) {
    foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
    val today = foodToday()
    val entries = vm.foodLog().filter { localDay(it.s("capturedAt")) == today }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        todayPlates(entries, today).forEach { FoodPlate(vm, it) }
        if (needsMealPlate(entries,today)) {
            Spacer(Modifier.width(3.dp))
            GardenPlate("", modifier = Modifier.semantics { contentDescription = "Log another meal" }, empty = true, onClick = { vm.track("today_missing_meal_tap"); vm.beginTextCapture() })
        }
    }
}
@Composable
fun FoodLogScreen(vm: GardenModel) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        vm.beginPhotoCapture(uris)
    }
    foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
    val entries = vm.foodLog()
    val today = foodToday()
    var expanded by rememberSaveable { mutableStateOf("") }
    var detailFor by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openFoodLog = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Food log", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose photos")
            }
            TextButton(onClick = { vm.beginTextCapture() }) {
                Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("No photo")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HealthEntryCard(vm, entries.filter { localDay(it.s("capturedAt")) == today }) }
            if (entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(40.dp), tint = Muted)
                    Spacer(Modifier.height(10.dp))
                    Text("Take or choose a photo of what you eat.", color = Muted)
                }
            }
            entries.groupBy { localDay(it.s("capturedAt")) }.forEach { (day, dayEntries) ->
                item(key = "day-$day") {
                    val dayHealth = dayHealth(dayEntries)
                    val total = dayHealth.sum("calories")
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            when (day) { LocalDate.MIN -> "Unknown date"; today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d")) },
                            fontFamily = FontFamily.Serif, fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f),
                        )
                        if (total != null) {
                            Text("${total.text()} kcal · known food", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
                items(dayEntries, key = { it.s("id") }) { entry ->
                    FoodLogRow(vm, entry, expanded == entry.s("id"), { expanded = if (expanded == entry.s("id")) "" else entry.s("id") }, { detailFor = entry.s("id") })
                }
            }
        }
    }
    if (detailFor.isNotEmpty()) {
        var text by rememberSaveable(detailFor) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { detailFor = "" },
            title = { Text("Add detail") },
            text = { OutlinedTextField(text, { text = it.take(4000) }, placeholder = { Text("e.g. it was chicken; ate about half") }, minLines = 2) },
            confirmButton = { TextButton(onClick = { vm.addCaptureDetail(detailFor, text) { detailFor = "" } }, enabled = text.isNotBlank()) { Text("Update") } },
            dismissButton = { TextButton(onClick = { detailFor = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FoodLogRow(vm: GardenModel, entry: JSONObject, open: Boolean, toggle: () -> Unit, addDetail: () -> Unit) {
    val id = entry.s("id")
    val server = entry.optJSONObject("server")
    val interp = server?.optJSONObject("interpretation")
    val status = when {
        !entry.optBoolean("synced", false) -> "waiting"
        server == null -> "syncing"
        else -> server.s("status")
    }
    val nutrition = interp?.optJSONObject("nutrition")
    val photos = vm.capturePhotos(entry)
    val hasPhoto = photos.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(20.dp)).clickable(onClick = toggle).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumb = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
            if (hasPhoto) LocalPhoto(vm.capturePhotoFile(entry, photos.first()), thumb, 200)
            else Box(thumb.background(Mist), contentAlignment = Alignment.Center) { Icon(kindIcon(entry.s("kind")), null, tint = Forest) }
            Column(Modifier.weight(1f)) {
                Text(
                    interp?.s("title")?.ifBlank { null } ?: entry.s("note").ifBlank { entry.s("kind").replaceFirstChar { it.uppercase() } },
                    fontSize = 16.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localTime(entry.s("capturedAt")), fontSize = 12.sp, color = Muted)
                    if (photos.size > 1) Text("${photos.size} photos", fontSize = 12.sp, color = Muted)
                    StatusDot(status)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                rangeText(range(nutrition, "calories"), "")?.let {
                    Text(it, fontFamily = FontFamily.Serif, fontSize = 17.sp, color = Ink)
                    Text("kcal", fontSize = 11.sp, color = Muted)
                }
            }
        }
        if (!open) {
            interp?.optJSONArray("questions")?.strings()?.firstOrNull()?.let { q ->
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Lime.copy(alpha = .5f)).clickable(onClick = addDetail).padding(10.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.HelpOutline, null, Modifier.size(16.dp), tint = Forest)
                    Spacer(Modifier.width(8.dp))
                    Text(q, fontSize = 13.sp, color = Forest)
                }
            }
            return@Column
        }
        if (hasPhoto) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            photos.forEach { photo -> LocalPhoto(vm.capturePhotoFile(entry, photo), Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)), 1000) }
        }
        if (interp != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val batchId = interp.s("batchId")
                if (batchId.isNotBlank()) {
                    val batch = vm.snapshot.a("batches").objects().firstOrNull { it.s("id") == batchId }
                    AssistChip(onClick = { vm.track("food_log_batch", "batchId" to batchId); vm.openFridgeCheck = true },
                        label = { Text(batch?.s("title") ?: "Linked batch", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Outlined.SoupKitchen, null, Modifier.size(16.dp)) })
                } else interp.a("components").objects().distinctBy { it.s("productId") }.forEach { component ->
                    val productId = component.s("productId")
                    val product = vm.snapshot.a("pantry").objects().firstOrNull { it.s("product_id") == productId }
                    AssistChip(onClick = { vm.track("food_log_product", "productId" to productId); vm.openFridgeCheck = true },
                        label = { Text(product?.s("name") ?: component.s("name", "Linked product"), fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(16.dp)) })
                }
                if (interp.s("method").isNotBlank()) {
                    val computed = interp.s("method") == "computed"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                        Icon(if (computed) Icons.Outlined.Calculate else Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = Forest)
                        Spacer(Modifier.width(4.dp)); Text(if (computed) "Computed" else "Estimated", fontSize = 12.sp)
                    }
                }
            }
            if (interp.s("intakeId").isNotBlank() && status == "interpreted") {
                var rating by rememberSaveable(interp.s("intakeId")) { mutableStateOf<Int?>(null) }
                var aspects by remember(interp.s("intakeId")) { mutableStateOf(JSONObject()) }
                var saved by rememberSaveable(interp.s("intakeId")) { mutableStateOf(false) }
                ReactionControl(vm, interp.s("intakeId"), rating, aspects) { value, next -> rating = value; aspects = next; saved = false }
                if (rating != null) TextButton(onClick = {
                    saved = vm.reaction(j("intakeId" to interp.s("intakeId")), rating!!, aspects)
                }, enabled = !saved) { Text(if (saved) "Rating saved" else "Save rating") }
            }
        }
        if (interp != null && status == "interpreted") FoodHealthBreakdown(interp)
        else if (interp != null) Text("Updating interpretation · excluded from current totals", fontSize = 12.sp, color = Muted)
        if (nutrition != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("protein_g" to "protein", "carbs_g" to "carbs", "fat_g" to "fat", "fiber_g" to "fiber").forEach { (key, label) ->
                    rangeText(range(nutrition, key), "g")?.let { Pill("$it $label") }
                }
            }
        }
        interp?.optJSONArray("items")?.objects()?.forEach { item ->
            Column {
                Text(item.s("name"), fontSize = 14.sp, color = Ink)
                if (item.s("portion").isNotBlank()) Text(item.s("portion"), fontSize = 12.sp, color = Muted)
            }
        }
        interp?.optJSONArray("questions")?.strings()?.forEach { q ->
            Text("? $q", fontSize = 13.sp, color = Forest)
        }
        entry.s("note").takeIf { it.isNotBlank() }?.let { Text("“$it”", fontSize = 13.sp, color = Muted) }
        server?.optJSONArray("details")?.objects()?.forEach { Text("+ ${it.s("text")}", fontSize = 13.sp, color = Muted) }
        if (status == "failed") Text(server?.s("error") ?: "", fontSize = 12.sp, color = Clay)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.optBoolean("synced", false)) OutlinedButton(onClick = addDetail) { Text("Add detail") }
            if (status == "failed") OutlinedButton(onClick = { vm.retryInterpretation(id) }) { Text("Try again") }
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val (color, label) = when (status) {
        "interpreted" -> Forest to ""
        "interpreting", "pending" -> Lime to "reading"
        "failed" -> Clay to "couldn't read"
        "syncing" -> Muted to "sending"
        else -> Muted to "on phone"
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    if (label.isNotEmpty()) Text(label, fontSize = 11.sp, color = Muted)
}
