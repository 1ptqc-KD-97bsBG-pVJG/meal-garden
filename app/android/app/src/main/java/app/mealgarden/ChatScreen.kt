@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mealgarden

import android.content.Intent
import android.graphics.ImageDecoder
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.flow.collect

private val askPrompts = listOf("What should I use first?", "What can I make without shopping?", "How is protein this week?")

@Composable
fun ChatScreen(vm: GardenModel) {
    Column(Modifier.fillMaxSize()) {
        GardenTopBar("Ask") {
            IconButton(onClick = { vm.openHistory = true }) { Icon(Icons.Outlined.History, "Conversation history", tint = Forest) }
            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Outlined.Add, "New conversation", tint = Forest) }
        }
        ChatAnswers(vm, Modifier.weight(1f))
        ChatComposer(vm)
    }
}

/** The same conversation and pending requests continue in the small Ask surface. */
@Composable
fun AskSheet(vm: GardenModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Paper, contentColor = Ink) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask", style = GardenType.Section, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDismiss(); vm.openHistory = true }) { Text("History") }
                TextButton(onClick = { onDismiss(); vm.tab = 2 }) { Text("Full chat") }
            }
            ChatAnswers(vm, Modifier.weight(1f, fill = false).heightIn(min = 96.dp, max = 320.dp), compact = true)
            ChatComposer(vm, compact = true)
        }
    }
}

@Composable
private fun ChatAnswers(vm: GardenModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val list = rememberLazyListState()
    val jobs = vm.activity.a("jobs").objects()
    val active = jobs.firstOrNull { it.s("conversation_id") == vm.conversation && it.s("status") in listOf("running", "queued", "waiting") }
    val latest = jobs.firstOrNull { it.s("conversation_id") == vm.conversation }
    val requests = vm.activity.a("requests").objects().filter { request -> jobs.any { it.s("id") == request.s("jobId") && it.s("conversation_id") == vm.conversation } }
    val messages = if (compact) vm.messages.takeLast(3) else vm.messages
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followLatest = !list.canScrollForward
        }
    }
    LaunchedEffect(messages.lastOrNull()?.s("id"), messages.lastOrNull()?.s("text")) {
        if (messages.isNotEmpty() && followLatest && !list.isScrollInProgress)
            list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    LazyColumn(modifier.fillMaxWidth().testTag(if (compact) "ask-answers" else "chat-history"), state = list, contentPadding = PaddingValues(14.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (messages.isEmpty()) {
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                askPrompts.forEach { prompt -> GardenChip(prompt, onClick = { vm.editDraft(prompt, "chat_suggestion") }) }
            } }
            if (!vm.paired) item { GardenQuietButton("Connect your laptop", onClick = { vm.openSettings = true }, icon = Icons.Outlined.Link) }
        }
        items(messages, key = { it.s("id") }) { message -> ChatMessage(vm, message) }
        if (active != null) item {
            GardenCard(color = Paper2) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = Forest)
                    Text(active.s("progress", "Working"), style = GardenType.Small, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.stop(active.s("id")) }) { Text("Stop") }
                }
            }
        }
        if (active == null && latest != null && latest.s("status") in listOf("failed", "interrupted", "partial", "needs_sign_in", "blocked", "unverified")) item {
            GardenCard(color = AmberLight) {
                Text(latest.s("status").replace('_', ' ').replaceFirstChar { it.uppercase() }, style = GardenType.Body)
                latest.s("error", latest.s("progress")).takeIf { it.isNotBlank() }?.let { Text(it, style = GardenType.Small) }
            }
        }
        items(requests, key = { "request:${it.s("id")}" }) { request -> RequestCard(vm, request) }
    }
}

/** Generated task prompts keep their original evidence behind an explicit details control. */
internal fun taskMessageTitle(vm: GardenModel, message: JSONObject): String? {
    if (message.s("role") != "user") return null
    val job = vm.activity.a("jobs").objects().firstOrNull { it.s("id") == message.s("job_id") }
    val source = message.o("metadata").s("source")
    return when {
        source == "food_log" || job?.s("kind") == "log_food" -> {
            val title = job?.let { activityJobTitle(vm, it) }.orEmpty()
            "Food log" + if (title.isNotBlank() && title != "Food log") " · $title" else ""
        }
        source == "reflection" || job?.s("kind") == "reflect" -> {
            val title = job?.o("input")?.s("recipeId")?.let { vm.recipe(it)?.s("title") }.orEmpty()
            "Cook feedback" + if (title.isNotBlank()) " · $title" else ""
        }
        source == "product_lookup" || job?.s("kind") == "product_lookup" -> "Food details"
        else -> null
    }
}

@Composable
internal fun ChatMessage(vm: GardenModel, message: JSONObject) {
    val taskTitle = taskMessageTitle(vm, message)
    if (taskTitle != null) {
        var expanded by rememberSaveable(message.s("id")) { mutableStateOf(false) }
        GardenCard(color = Paper2, modifier = Modifier.testTag("chat-task:${message.s("id")}")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(18.dp), tint = Forest)
                Text(taskTitle, style = GardenType.Body, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat-task-details:${message.s("id")}")) { Text(if (expanded) "Less" else "Details") }
            }
            if (message.s("created").isNotBlank()) Text(activityDate(message.s("created")), style = GardenType.Small)
            if (expanded) RichText(message.s("text"))
        }
    } else if (message.s("role") == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(message.s("text"), style = GardenType.Body, modifier = Modifier.widthIn(max = 330.dp).clip(GardenShape.Card).background(Mist).padding(12.dp))
    } else {
        val panels = message.a("panels").objects()
        var expanded by rememberSaveable(message.s("id")) { mutableStateOf(false) }
        val text = message.s("text")
        GardenCard(modifier = Modifier.testTag("chat-answer:${message.s("id")}")) {
            val summary = panels.firstOrNull()?.s("title")?.takeIf { it.isNotBlank() }
                ?: text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("^[#*\\s]+"), "")
            Text(summary, style = GardenType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Actions stay next to the recommendation rather than below the full transcript.
            panels.flatMap { it.a("actions").objects() }.forEach { action ->
                GardenPrimaryButton(action.s("label"), onClick = {
                    if (action.s("type") == "recipe") vm.selectedRecipe = action.s("value")
                    else vm.ask(action.s("value"), origin = "native_card")
                }, modifier = Modifier.fillMaxWidth())
            }
            if (text.isNotBlank() || panels.any { it.s("body").isNotBlank() }) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("chat-answer-details:${message.s("id")}")) { Text(if (expanded) "Less" else "Details") }
                if (expanded) {
                    if (text.isNotBlank()) RichText(text)
                    panels.forEach { panel ->
                        if (panel.s("title").isNotBlank()) Text(panel.s("title"), style = GardenType.Section)
                        if (panel.s("body").isNotBlank() && panel.s("body") != text) RichText(panel.s("body"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatComposer(vm: GardenModel, compact: Boolean = false, previews: ChatPreviewCache? = null) {
    val context = LocalContext.current
    val previewCache = previews ?: remember { ChatPreviewCache(context.cacheDir) }
    var pendingPreview by remember { mutableStateOf<File?>(null) }
    var previousAttachment by remember { mutableStateOf(vm.attachment) }
    fun attach(source: ImageDecoder.Source) {
        val bytes = compressPhoto(source).first
        pendingPreview?.delete()
        pendingPreview = File.createTempFile("ask-preview-", ".jpg", context.cacheDir).apply { writeBytes(bytes) }
        previousAttachment = vm.attachment
        vm.upload(bytes)
    }
    LaunchedEffect(vm.attachment) {
        if (vm.attachment.isNotBlank() && vm.attachment != previousAttachment) {
            pendingPreview?.let { file ->
                previewCache.complete(vm.attachment, file)
                file.delete(); pendingPreview = null
            }
            previousAttachment = vm.attachment
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try { attach(ImageDecoder.createSource(context.contentResolver, uri)) }
        catch (_: Exception) { vm.error = "Could not read that photo" }
    }
    val camera = rememberCamera { file ->
        try { attach(ImageDecoder.createSource(file)) }
        catch (_: Exception) { vm.error = "Could not read that photo" }
        finally { file.delete() }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.editDraft((vm.draft + " " + it).trim(), "voice_recognition") }
    }
    Column(Modifier.fillMaxWidth().imePadding().background(Paper).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!vm.online && vm.paired) Text("Laptop offline · reconnect to send", style = GardenType.Small, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        if (vm.attachment.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val preview = previewCache.file(vm.attachment)
            if (preview != null) LocalPhoto(preview, Modifier.size(52.dp).clip(GardenShape.Button).testTag("chat-attachment-preview"), 160)
            else Icon(Icons.Outlined.Image, "Attached photo", Modifier.size(28.dp), tint = Forest)
            Text("1 photo", style = GardenType.Small, modifier = Modifier.weight(1f))
            TextButton(onClick = { photo.launch("image/*") }, enabled = !vm.busy) { Text("Replace") }
            TextButton(onClick = { preview?.delete(); pendingPreview?.delete(); pendingPreview = null; vm.attachment = ""; vm.attachmentName = "" }, enabled = !vm.busy) { Text("Remove") }
        }
        Row(Modifier.fillMaxWidth().clip(GardenShape.Card).background(CardSurface).border(1.dp, Line, GardenShape.Card).padding(4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(vm.draft, onValueChange = { vm.editDraft(it) }, modifier = Modifier.weight(1f).testTag("chat-input"), minLines = 1, maxLines = if (compact) 3 else 5,
                placeholder = { Text(if (compact) "Anything about your food" else "What's on your mind?", fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(focusedContainerColor = CardSurface, unfocusedContainerColor = CardSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            IconButton(onClick = { vm.send() }, enabled = !vm.busy && vm.draft.isNotBlank(), modifier = Modifier.background(if (vm.draft.isBlank()) Paper2 else Forest, CircleShape)) {
                Icon(Icons.Outlined.ArrowUpward, "Send", tint = if (vm.draft.isBlank()) Muted else Paper)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (vm.paired) camera() else vm.openSettings = true }, enabled = !vm.busy) { Icon(Icons.Outlined.PhotoCamera, if (vm.attachment.isBlank()) "Take a photo" else "Replace photo with camera", tint = Muted) }
            IconButton(onClick = { if (vm.paired) photo.launch("image/*") else vm.openSettings = true }, enabled = !vm.busy) { Icon(Icons.Outlined.AddPhotoAlternate, if (vm.attachment.isBlank()) "Attach from gallery" else "Replace photo from gallery", tint = Muted) }
            IconButton(onClick = {
                try { voice.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                catch (_: Exception) { vm.error = "Use your keyboard's microphone for dictation on this device." }
            }) { Icon(Icons.Outlined.Mic, "Dictate", tint = Muted) }
            Spacer(Modifier.weight(1f))
            GardenChip(if (vm.quick) "Quick" else "Thorough", selected = vm.quick, icon = if (vm.quick) Icons.Outlined.Bolt else Icons.Outlined.AutoAwesome, onClick = { vm.toggleQuick() })
        }
    }
}

/** The upload callback publishes the completed preview to the composer as observable UI state. */
internal class ChatPreviewCache(private val cacheDir: File) {
    private var ready by mutableStateOf<Pair<String, File>?>(null)

    fun complete(attachmentId: String, source: File) {
        val target = target(attachmentId)
        source.copyTo(target, overwrite = true)
        ready = attachmentId to target
    }

    fun file(attachmentId: String): File? =
        ready?.takeIf { it.first == attachmentId }?.second
            ?: target(attachmentId).takeIf { it.exists() }

    private fun target(attachmentId: String) = File(cacheDir, "ask-photo-${attachmentId.hashCode()}.jpg")
}

@Composable
fun RequestCard(vm: GardenModel, r: JSONObject) {
    val params = r.o("params")
    var answers by rememberSaveable(r.s("id")) { mutableStateOf(mapOf<String, String>()) }
    val questions = params.a("questions").objects()
    GardenCard(color = AmberLight) {
        if (r.s("method").contains("requestUserInput")) {
            questions.forEach { question ->
                Text(question.s("question"), style = GardenType.Section)
                question.a("options").objects().forEach { option ->
                    GardenCard(color = if (answers[question.s("id")] == option.s("label")) Mist else CardSurface, onClick = { answers = answers + (question.s("id") to option.s("label")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = answers[question.s("id")] == option.s("label"), onClick = { answers = answers + (question.s("id") to option.s("label")) })
                            Text(option.s("label"), style = GardenType.Body, modifier = Modifier.weight(1f))
                        }
                        if (option.s("description").isNotBlank()) Text(option.s("description"), style = GardenType.Small)
                    }
                }
                OutlinedTextField(answers[question.s("id")] ?: "", onValueChange = { answers = answers + (question.s("id") to it) }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth())
            }
            GardenPrimaryButton("Continue", enabled = questions.isNotEmpty() && questions.all { !answers[it.s("id")].isNullOrBlank() }, onClick = { vm.answer(r.s("id"), JSONObject(answers)) })
        } else {
            Text(params.s("reason", "Allow this action?"), style = GardenType.Body)
            params.s("command").takeIf { it.isNotBlank() }?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GardenPrimaryButton("Allow once", onClick = { vm.answer(r.s("id"), decision = "accept") })
                GardenQuietButton("Decline", onClick = { vm.answer(r.s("id"), decision = "decline") })
            }
        }
    }
}
