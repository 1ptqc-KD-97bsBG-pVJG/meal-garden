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
import kotlinx.coroutines.launch
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
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

/** Capture intent is a UI preference; interpretation remains evidence from the companion. */
@Composable
fun CaptureHome(vm: GardenModel) {
    var mode by rememberSaveable { mutableStateOf(vm.prefs.getString("capture-mode", "meal") ?: "meal") }
    fun selectMode(value: String) {
        mode = value
        vm.prefs.edit().putString("capture-mode", value).apply()
    }
    val camera = rememberCamera { vm.beginPhotoCapture(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> vm.beginPhotoCapture(uris) }
    val recent = vm.foodLog().firstNotNullOfOrNull { entry ->
        vm.capturePhotos(entry).firstOrNull { vm.capturePhotoFile(entry, it).isFile }?.let { entry to it }
    }
    Column(Modifier.fillMaxSize().background(Paper)) {
        GardenTopBar("Capture") {
            TextButton(onClick = { vm.openFoodLog = true }) { Text("Food log", color = Forest) }
        }
        LazyColumn(contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    captureKinds.forEach { (value, label, icon) -> GardenChip(label, selected = mode == value,
                        icon = icon, onClick = { selectMode(value) }) }
                }
            }
            item {
                GardenCard {
                    if (recent != null) {
                        val (entry, photo) = recent
                        LocalPhoto(vm.capturePhotoFile(entry, photo), Modifier.fillMaxWidth().height(220.dp).clip(GardenShape.Hero), 800)
                        val title = entry.o("server").o("interpretation").s("title")
                        if (title.isNotBlank()) Text(title, style = GardenType.Small)
                    } else Box(Modifier.fillMaxWidth().height(185.dp).clip(GardenShape.Hero).background(Paper2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PhotoCamera, null, tint = Forest, modifier = Modifier.size(64.dp))
                    }
                    GardenPrimaryButton("Take photo", camera, modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.PhotoCamera, enabled = !vm.capturePhotoBusy)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GardenQuietButton("Choose photos", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f), icon = Icons.Outlined.PhotoLibrary, enabled = !vm.capturePhotoBusy)
                    GardenQuietButton("No photo", { vm.beginTextCapture() }, modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.EditNote, enabled = !vm.capturePhotoBusy)
                }
            }
            if (vm.capturePhotoBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest) }
        }
    }
}

/** Blank and zero mean the weight was not given. */
fun optionalCaptureWeight(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()
    ?.takeIf { it.isFinite() && it > 0.0 }

fun captureWeightIsValid(raw: String): Boolean = raw.isBlank() || raw.trim().replace(',', '.').toDoubleOrNull()
    ?.let { it.isFinite() && it >= 0.0 } == true

/** The native camera and gallery save several images into one durable draft. */
@Composable
fun CaptureSheet(vm: GardenModel) {
    val draft = vm.draftCapture ?: return
    val id = draft.s("id")
    val photos = vm.capturePhotos(draft)
    val hasPhoto = photos.isNotEmpty()
    val camera = rememberCamera { vm.addCapturePhoto(it, id) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> vm.addCapturePhotos(uris, id) }
    var kind by rememberSaveable(id) { mutableStateOf(vm.prefs.getString("capture-mode", "meal") ?: "meal") }
    var note by rememberSaveable(id) { mutableStateOf("") }
    val showWeight = moduleEnabled(vm, "weight", default = false)
    var addWeight by rememberSaveable(id) { mutableStateOf(false) }
    val weightVisible = showWeight || addWeight
    var weight by rememberSaveable(id) { mutableStateOf("") }
    val enteredWeight = optionalCaptureWeight(weight)
    val weightValid = captureWeightIsValid(weight)
    val hasWeight = weightVisible && enteredWeight != null
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.let { note = (note + " " + it).trim() }
    }
    GardenSheet("Log food", { vm.discardCapture() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasPhoto) {
                Row(Modifier.fillMaxWidth().testTag("capture-photos").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    photos.forEachIndexed { index, photo ->
                        Box(Modifier.testTag("capture-photo-${photo.s("id")}")) {
                            LocalPhoto(vm.capturePhotoFile(draft, photo), Modifier.size(148.dp).clip(GardenShape.Card), 480)
                            IconButton(onClick = { vm.removeCapturePhoto(photo) }, enabled = !vm.capturePhotoBusy,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(48.dp).background(Paper, CircleShape)) {
                                Icon(Icons.Outlined.Close, "Remove photo ${index + 1}", tint = Forest, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GardenQuietButton("Take photo", camera, icon = Icons.Outlined.PhotoCamera,
                    enabled = !vm.capturePhotoBusy && photos.size < 10)
                GardenQuietButton("Choose photos", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    icon = Icons.Outlined.PhotoLibrary, enabled = !vm.capturePhotoBusy && photos.size < 10)
            }
            if (vm.capturePhotoBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                captureKinds.forEach { (value, label, icon) -> GardenChip(label, selected = kind == value,
                    icon = icon, onClick = { kind = value }) }
            }
            OutlinedTextField(value = note, onValueChange = { note = it.take(4000) },
                label = { Text("Note") }, placeholder = { Text(if (hasPhoto) "Optional" else "What did you have?") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, shape = GardenShape.Button,
                trailingIcon = { IconButton(onClick = {
                    voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                }) { Icon(Icons.Outlined.Mic, "Dictate", tint = Muted) } })
            if (!weightVisible) TextButton(onClick = { addWeight = true }) { Text("Add weight") }
            else OutlinedTextField(value = weight, onValueChange = { weight = it.take(20) },
                label = { Text("Weight (g)") }, placeholder = { Text("Optional") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("capture-weight"), shape = GardenShape.Button, isError = !weightValid,
                supportingText = if (!weightValid) { { Text("Enter grams or leave blank") } } else null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GardenQuietButton("Discard", { vm.discardCapture() })
                GardenPrimaryButton("Log it", {
                    val evidence = if (hasWeight) {
                        val amount = java.math.BigDecimal.valueOf(enteredWeight!!).stripTrailingZeros().toPlainString()
                        listOf(note.trim(), "Weight: $amount g").filter { it.isNotBlank() }.joinToString("\n")
                    } else note
                    if (vm.saveCapture(kind, evidence)) {
                        vm.openCapture = false; vm.openHealth = false
                        vm.openFoodLog = true
                    }
                }, modifier = Modifier.weight(1f).testTag("capture-save"), icon = Icons.Outlined.Check,
                    enabled = !vm.capturePhotoBusy && (!weightVisible || weightValid) && (hasPhoto || note.isNotBlank() || hasWeight))
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

internal fun captureFoodIcon(entry: JSONObject): String {
    val candidates = listOf(stripInterpretation(entry).s("title"), entry.s("note"), stripCategory(entry))
    return candidates.map(::foodIconId).firstOrNull { it != "food-unknown" } ?: "food-unknown"
}

@Composable
private fun FoodPlate(vm: GardenModel, plate: TodayPlate) {
    val entry = plate.entries.first()
    val stacked = plate.entries.size > 1
    val mark = plate.entries.map { todayPlateMark(it, vm.snapshot) }.distinct().singleOrNull()
    val title = stripInterpretation(entry).s("title").ifBlank { entry.s("note").lineSequence().firstOrNull().orEmpty() }.ifBlank { entry.s("kind", "Food") }
    var expand by remember { mutableStateOf(false) }
    GardenPlate(title, seed = title.hashCode(), count = plate.entries.size, drink = plate.drinks.isNotEmpty(), mark = mark, onClick = {
        vm.track(if (stacked) "today_stack_tap" else "today_plate_tap", "captureId" to entry.s("id"), "count" to plate.entries.size)
        if (stacked) expand = true else vm.openFoodLog = true
    }, food = {
        val photo = vm.capturePhotos(entry).firstOrNull()?.let { vm.capturePhotoFile(entry,it) }?.takeIf { it.exists() }
        if (photo != null) LocalPhoto(photo, Modifier.size(54.dp).clip(CircleShape), 240)
        else FoodIcon(captureFoodIcon(entry), Modifier.size(38.dp))
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
    val showNutrition = moduleEnabled(vm, "nutrition")
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        vm.beginPhotoCapture(uris)
    }
    foodDayStartHour = vm.snapshot.o("settings").optInt("dayStartHour", 4)
    val entries = vm.foodLog()
    val today = foodToday()
    var expanded by rememberSaveable { mutableStateOf("") }
    var detailFor by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val groups = entries.groupBy { localDay(it.s("capturedAt")) }
    var latestSeen by rememberSaveable { mutableStateOf(entries.firstOrNull()?.s("id").orEmpty()) }
    LaunchedEffect(entries.firstOrNull()?.s("id")) {
        val latest = entries.firstOrNull()?.s("id").orEmpty()
        if (latest != latestSeen) { list.scrollToItem(0); expanded = ""; latestSeen = latest }
    }
    Column(Modifier.fillMaxSize().background(Paper)) {
        GardenTopBar("Food log", onBack = { vm.openFoodLog = false })
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GardenQuietButton("Choose photos", { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = Icons.Outlined.PhotoLibrary, modifier = Modifier.weight(1f))
            GardenQuietButton("No photo", { vm.beginTextCapture() }, icon = Icons.Outlined.EditNote,
                modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.keys.forEach { day ->
                val label = when (day) { LocalDate.MIN -> "Unknown date"; today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> humanDate(day.toString()) }
                GardenChip(label, modifier = Modifier.testTag("food-log-jump-$day"), onClick = {
                    val index = (if (showNutrition) 1 else 0) + groups.entries.takeWhile { it.key != day }.sumOf { it.value.size + 1 }
                    scope.launch { list.scrollToItem(index) }
                })
            }
        }
        LazyColumn(
            modifier = Modifier.testTag("food-log-list"), state = list,
            contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showNutrition) item { HealthEntryCard(vm, entries.filter { localDay(it.s("capturedAt")) == today }) }
            if (entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(40.dp), tint = Muted)
                    Spacer(Modifier.height(10.dp))
                    Text("No food logged", style = GardenType.Section, color = Muted)
                }
            }
            groups.forEach { (day, dayEntries) ->
                item(key = "day-$day") {
                    val dayHealth = dayHealth(dayEntries)
                    val total = dayHealth.sum("calories")
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            when (day) { LocalDate.MIN -> "Unknown date"; today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> humanDate(day.toString()) },
                            style = GardenType.Section, modifier = Modifier.weight(1f),
                        )
                        if (showNutrition && total != null) {
                            Text("${total.text()} kcal", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
                items(dayEntries, key = { it.s("id") }) { entry ->
                    FoodLogRow(vm, entry, showNutrition, expanded == entry.s("id"), { expanded = if (expanded == entry.s("id")) "" else entry.s("id") }, { detailFor = entry.s("id") })
                }
            }
        }
    }
    if (detailFor.isNotEmpty()) {
        var text by rememberSaveable(detailFor) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { detailFor = "" },
            title = { Text("Add detail") },
            text = { OutlinedTextField(text, { text = it.take(4000) }, label = { Text("Detail") }, minLines = 2) },
            confirmButton = { TextButton(onClick = { vm.addCaptureDetail(detailFor, text) { detailFor = "" } }, enabled = text.isNotBlank()) { Text("Update") } },
            dismissButton = { TextButton(onClick = { detailFor = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FoodLogRow(vm: GardenModel, entry: JSONObject, showNutrition: Boolean, open: Boolean, toggle: () -> Unit, addDetail: () -> Unit) {
    val id = entry.s("id")
    val server = entry.optJSONObject("server")
    val interp = server?.optJSONObject("interpretation")
    val status = when {
        !entry.optBoolean("synced", false) -> "waiting"
        server == null -> "syncing"
        else -> server.s("status")
    }
    val nutrition = if (showNutrition && status == "interpreted") interp?.optJSONObject("nutrition") else null
    fun openPantry(lot: JSONObject?) {
        vm.selectedPantryItem = lot?.s("id").orEmpty()
        vm.openCapture = false; vm.openFoodLog = false; vm.openHealth = false; vm.openFridgeCheck = false
        vm.openActivity = false; vm.openPreferences = false; vm.openSettings = false; vm.openHistory = false; vm.openShopping = false
        vm.selectedRecipe = null
        vm.tab = 3
    }
    val photos = vm.capturePhotos(entry)
    val hasPhoto = photos.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().testTag("food-log-entry-$id").semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.clip(GardenShape.Card).background(CardSurface).border(1.dp, Line, GardenShape.Card).clickable(onClick = toggle).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val thumb = Modifier.size(58.dp).clip(GardenShape.Button)
            if (hasPhoto) LocalPhoto(vm.capturePhotoFile(entry, photos.first()), thumb, 200)
            else Box(thumb.background(Mist), contentAlignment = Alignment.Center) { FoodIcon(captureFoodIcon(entry), Modifier.size(45.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    interp?.s("title")?.ifBlank { null } ?: entry.s("note").ifBlank { entry.s("kind").replaceFirstChar { it.uppercase() } },
                    fontSize = 15.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
        // Correction and retry belong beside the entry, before optional photo/evidence.
        if (entry.optBoolean("synced", false)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GardenQuietButton("Correct", addDetail, modifier = Modifier.testTag("food-log-correct-$id"), icon = Icons.Outlined.EditNote)
            if (status == "failed") GardenQuietButton("Try again", { vm.retryInterpretation(id) }, modifier = Modifier.testTag("food-log-retry-$id"), icon = Icons.Outlined.Refresh)
        }
        if (!open) {
            interp?.takeIf { status == "interpreted" }?.optJSONArray("questions")?.strings()?.firstOrNull { it.isNotBlank() }?.let { q ->
                Row(Modifier.testTag("food-log-question-$id").clip(GardenShape.Button).background(Mist).clickable(onClick = addDetail).padding(10.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.HelpOutline, null, Modifier.size(16.dp), tint = Forest)
                    Spacer(Modifier.width(8.dp))
                    Text(q, fontSize = 13.sp, color = Forest)
                }
            }
            return@Column
        }
        if (hasPhoto) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            photos.forEach { photo -> LocalPhoto(vm.capturePhotoFile(entry, photo), Modifier.size(148.dp).clip(GardenShape.Card), 480) }
        }
        if (interp != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val batchId = interp.s("batchId")
                if (batchId.isNotBlank()) {
                    val batch = vm.snapshot.a("batches").objects().firstOrNull { it.s("id") == batchId }
                    GardenChip(batch?.s("title")?.ifBlank { "Linked batch" } ?: "Linked batch", icon = Icons.Outlined.SoupKitchen,
                        modifier = Modifier.testTag("food-log-batch-$batchId"), onClick = {
                            val lot = vm.graphPantry().firstOrNull { it.s("id") == batch?.s("pantry_item_id") }
                            vm.track("food_log_batch", "batchId" to batchId, "itemId" to lot?.s("id").orEmpty())
                            openPantry(lot)
                        })
                }
                interp.a("components").objects().filter { it.s("productId").isNotBlank() }
                    .distinctBy { it.s("productId") }.forEach { component ->
                    val productId = component.s("productId")
                    val lots = vm.graphPantry().filter { it.s("product_id") == productId }
                    val product = lots.firstOrNull()
                    GardenChip(product?.s("name")?.ifBlank { component.s("name", "Linked product") }
                        ?: component.s("name", "Linked product"), icon = Icons.Outlined.Link,
                        modifier = Modifier.testTag("food-log-product-$productId"), onClick = {
                            val currentLots = vm.graphPantry().filter { it.s("product_id") == productId }
                            val referencedId = component.s("pantryItemId", component.s("pantry_item_id"))
                            val lot = currentLots.firstOrNull { it.s("id") == referencedId }
                                ?: currentLots.firstOrNull { it.optDouble("balance", Double.NaN) != 0.0 }
                                ?: currentLots.firstOrNull()
                            vm.track("food_log_product", "productId" to productId, "itemId" to lot?.s("id").orEmpty())
                            openPantry(lot)
                        })
                }
                if (interp.s("method").isNotBlank()) {
                    val computed = interp.s("method") == "computed"
                    GardenChip(if (computed) "Computed" else "Estimated",
                        icon = if (computed) Icons.Outlined.Calculate else Icons.Outlined.AutoAwesome)
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
        if (showNutrition && interp != null && status == "interpreted") FoodHealthBreakdown(interp)
        else if (interp != null && status != "interpreted") Text("Updating interpretation · excluded from current totals", fontSize = 12.sp, color = Muted)
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
        interp?.takeIf { status == "interpreted" }?.optJSONArray("questions")?.strings()?.firstOrNull { it.isNotBlank() }?.let { q ->
            Row(Modifier.fillMaxWidth().testTag("food-log-question-$id").clip(GardenShape.Button).background(Mist).clickable(onClick = addDetail).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.HelpOutline, null, tint = Forest, modifier = Modifier.size(17.dp))
                Text(q, style = GardenType.Body, color = Forest)
            }
        }
        entry.s("note").takeIf { it.isNotBlank() }?.let { Text("“$it”", fontSize = 13.sp, color = Muted) }
        server?.optJSONArray("details")?.objects()?.forEach { Text(it.s("text"), style = GardenType.Small) }
        if (status == "failed") Text(server?.s("error") ?: "", fontSize = 12.sp, color = Clay)
    }
}

@Composable
private fun StatusDot(status: String) {
    val (color, label) = when (status) {
        "interpreted" -> Forest to "Read"
        "interpreting", "pending" -> Forest to "Reading"
        "failed" -> Clay to "Couldn't read"
        "syncing" -> Muted to "Sending"
        else -> Muted to "On phone"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(when (status) {
            "interpreted" -> Icons.Outlined.Check
            "failed" -> Icons.Outlined.ErrorOutline
            "syncing" -> Icons.Outlined.CloudUpload
            else -> Icons.Outlined.Schedule
        }, null, Modifier.size(13.dp), tint = color)
        Text(label, fontSize = 11.sp, color = color)
    }
}
