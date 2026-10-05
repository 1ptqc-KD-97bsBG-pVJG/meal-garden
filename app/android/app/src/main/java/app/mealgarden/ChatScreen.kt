@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import java.io.ByteArrayOutputStream
import kotlin.math.*
import org.json.JSONObject

@Composable
fun ChatScreen(vm: GardenModel) {
    val list = rememberLazyListState()
    val context = LocalContext.current
    val photo =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap =
                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            val scale = max(info.size.width, info.size.height) / 1800f
                            if (scale > 1)
                                decoder.setTargetSize(
                                    (info.size.width / scale).toInt(),
                                    (info.size.height / scale).toInt(),
                                )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    vm.upload(out.toByteArray())
                } catch (e: Exception) {
                    vm.error = "Could not read that photo"
                }
            }
        }
    val snap = rememberCamera { file ->
        try {
            vm.upload(compressPhoto(ImageDecoder.createSource(file)).first)
        } catch (e: Exception) {
            vm.error = "Could not read that photo"
        } finally {
            file.delete()
        }
    }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let { vm.editDraft((vm.draft + " " + it).trim(), "voice_recognition") }
        }
    val active =
        vm.activity.a("jobs").objects().firstOrNull {
            it.s("conversation_id") == vm.conversation &&
                it.s("status") in listOf("running", "queued", "waiting")
        }
    val latestTask =
        vm.activity.a("jobs").objects().firstOrNull { it.s("conversation_id") == vm.conversation }
    val requests =
        vm.activity.a("requests").objects().filter { request ->
            vm.activity.a("jobs").objects().any {
                it.s("id") == request.s("jobId") && it.s("conversation_id") == vm.conversation
            }
        }
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.s("text")?.length) {
        if (
            vm.messages.isNotEmpty() &&
                (!list.canScrollForward ||
                    list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >=
                        vm.messages.size - 3)
        )
            list.animateScrollToItem((vm.messages.size).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("At the kitchen table", fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text(
                    if (vm.modelLabel().contains("·")) vm.modelLabel() else "Your meal-planning assistant · Sol",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            IconButton(onClick = { vm.openHistory = true }) {
                Icon(Icons.Outlined.History, "Conversation history")
            }
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Outlined.Add, "New conversation")
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            if (vm.messages.isEmpty())
                item {
                    Column(
                        Modifier.padding(vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Box(
                            Modifier.size(58.dp).background(Lime, RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                null,
                                tint = Forest,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Text("What sounds good?", fontFamily = FontFamily.Serif, fontSize = 32.sp)
                        Text(
                            "Plan a week, improvise dinner, or tell me how the last batch went. We can pick up wherever you are.",
                            color = Muted,
                            fontSize = 15.sp,
                        )
                        listOf(
                                "I have 20 minutes. What's for dinner?",
                                "Help me plan a few health-first meals",
                                "Let's take stock of my fridge",
                            )
                            .forEach { q ->
                                OutlinedCard(
                                    onClick = { vm.editDraft(q, "chat_suggestion") },
                                    shape = RoundedCornerShape(18.dp),
                                    colors =
                                        CardDefaults.outlinedCardColors(
                                            containerColor = Color.White
                                        ),
                                    border = BorderStroke(1.dp, Line),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(q, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Icon(
                                            Icons.AutoMirrored.Outlined.ArrowForward,
                                            null,
                                            Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        if (!vm.paired)
                            ActionButton("Connect your laptop", Icons.Outlined.Link) {
                                vm.openSettings = true
                            }
                    }
                }
            items(vm.messages, key = { it.s("id") }) { m ->
                when (m.s("role")) {
                    "user" ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                m.s("text"),
                                Modifier.widthIn(max = 330.dp)
                                    .clip(RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp))
                                    .background(Mist)
                                    .padding(17.dp),
                                fontSize = 15.sp,
                                lineHeight = 23.sp,
                            )
                        }
                    "card" -> m.a("panels").objects().forEach { NativePanel(vm, it) }
                    else ->
                        Column(
                            Modifier.fillMaxWidth().padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Eyebrow("MEAL GARDEN", Forest)
                            RichText(m.s("text"))
                            m.a("panels").objects().forEach { NativePanel(vm, it) }
                        }
                }
            }
            if (active != null)
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        Text(
                            active.s("progress", "Working on the laptop"),
                            fontSize = 12.sp,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.stop(active.s("id")) }) {
                            Text("Stop", fontSize = 12.sp)
                        }
                    }
                }
            if (
                active == null &&
                    latestTask != null &&
                    latestTask.s("status") in
                        listOf(
                            "failed",
                            "interrupted",
                            "partial",
                            "needs_sign_in",
                            "blocked",
                            "unverified",
                        )
            ) {
                item {
                    Note(
                        latestTask.s("status").replace('_', ' ') +
                            " · " +
                            latestTask.s("error", latestTask.s("progress"))
                    )
                }
            }
            items(requests, key = { it.s("id") }) { r -> RequestCard(vm, r) }
            item { Spacer(Modifier.height(1.dp)) }
        }
        if (!vm.online && vm.paired)
            Text(
                "Laptop offline · reconnect to send",
                modifier = Modifier.fillMaxWidth().background(Mist).padding(8.dp),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = Muted,
            )
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .background(Cream)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (vm.attachment.isNotEmpty())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Image, null, Modifier.size(16.dp))
                    Text(vm.attachmentName, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            vm.attachment = ""
                            vm.attachmentName = ""
                        }
                    ) {
                        Text("Remove")
                    }
                }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(24.dp))
                    .padding(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { if (vm.paired) snap() else vm.openSettings = true }
                ) {
                    Icon(Icons.Outlined.PhotoCamera, "Take a photo", tint = Muted)
                }
                IconButton(
                    onClick = { if (vm.paired) photo.launch("image/*") else vm.openSettings = true }
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, "Attach from gallery", tint = Muted)
                }
                TextField(
                    value = vm.draft,
                    onValueChange = { vm.editDraft(it) },
                    placeholder = { Text("What's on your mind?", fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                )
                IconButton(
                    onClick = { vm.send() },
                    enabled = !vm.busy && vm.draft.isNotBlank(),
                    modifier =
                        Modifier.padding(3.dp)
                            .background(if (vm.draft.isBlank()) Mist else Forest, CircleShape),
                ) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        "Send",
                        tint = if (vm.draft.isBlank()) Muted else Color.White,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { vm.toggleQuick() },
                    contentPadding = PaddingValues(8.dp, 0.dp),
                ) {
                    Icon(
                        if (vm.quick) Icons.Outlined.Bolt else Icons.Outlined.AutoAwesome,
                        null,
                        Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(vm.modelLabel(), fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("Runs on your laptop", fontSize = 10.sp, color = Muted)
                IconButton(
                    onClick = {
                        try {
                            voice.launch(
                                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(
                                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                            )
                        } catch (e: Exception) {
                            vm.error =
                                "Use your keyboard's microphone for dictation on this device."
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        "Dictate",
                        tint = Muted,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RequestCard(vm: GardenModel, r: JSONObject) {
    val p = r.o("params")
    var answers by remember(r.s("id")) { mutableStateOf(mapOf<String, String>()) }
    val questions = p.a("questions").objects()
    CardBox(color = Color(0xFFF3E9D5)) {
        Eyebrow("YOUR INPUT")
        if (r.s("method").contains("requestUserInput")) {
            questions.forEach { q ->
                Text(q.s("question"), fontSize = 15.sp)
                q.a("options").objects().forEach { o ->
                    FilterChip(
                        selected = answers[q.s("id")] == o.s("label"),
                        onClick = { answers = answers + (q.s("id") to o.s("label")) },
                        label = {
                            Column {
                                Text(o.s("label"))
                                if (o.s("description").isNotBlank())
                                    Text(o.s("description"), fontSize = 11.sp)
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = answers[q.s("id")] ?: "",
                    onValueChange = { answers = answers + (q.s("id") to it) },
                    label = { Text("Your answer") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = questions.all { !answers[it.s("id")].isNullOrBlank() },
                onClick = { vm.answer(r.s("id"), JSONObject(answers)) },
            ) {
                Text("Continue")
            }
        } else {
            Text(p.s("reason", "Codex is asking to perform an action on your laptop."))
            val command = p.s("command")
            if (command.isNotEmpty())
                Text(command, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.answer(r.s("id"), decision = "accept") }) {
                    Text("Allow once")
                }
                OutlinedButton(onClick = { vm.answer(r.s("id"), decision = "decline") }) {
                    Text("Decline")
                }
            }
        }
    }
}
