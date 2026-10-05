package app.mealgarden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppNoteCapture(vm: GardenModel, stage: String, setStage: (String) -> Unit, screen: String) {
    var text by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var saveError by remember { mutableStateOf("") }
    var x by remember { mutableStateOf<Float?>(null) }
    var y by remember { mutableStateOf<Float?>(null) }
    if (stage.isEmpty()) return
    BackHandler { setStage("") }
    when (stage) {
        "pick" -> Box(Modifier.fillMaxSize().background(Color(0x44081710)).pointerInput(screen) {
            detectTapGestures { offset ->
                x = (offset.x / size.width).coerceIn(0f, 1f)
                y = (offset.y / size.height).coerceIn(0f, 1f)
                target = "Spot at ${(x!! * 100).toInt()}%, ${(y!! * 100).toInt()}% of $screen"
                setStage("write")
            }
        }) {
            Surface(Modifier.align(Alignment.TopCenter).padding(24.dp), color = Forest, shape = RoundedCornerShape(18.dp)) {
                Text("Tap the part of this screen you want to remember", Modifier.padding(18.dp), color = Color.White)
            }
        }
        "menu" -> AlertDialog(
            onDismissRequest = { setStage("") },
            title = { Text("Note or ask", fontFamily = FontFamily.Serif) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (!vm.noteScreenshotReady) Text("No screen image${if (vm.noteScreenshotProblem.isNotBlank()) ": ${vm.noteScreenshotProblem}" else ""}", fontSize = 12.sp, color = Muted)
                Button(onClick = { setStage("ask") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Forest)) { Text("Ask a question") }
                OutlinedButton(onClick = { target = ""; x = null; y = null; setStage("write") }, modifier = Modifier.fillMaxWidth()) { Text("Note this screen") }
                OutlinedButton(onClick = { setStage("pick") }, modifier = Modifier.fillMaxWidth()) { Text("Point to something") }
                TextButton(onClick = { setStage("list") }) { Text("Review ${vm.appNotes.size} notes") }
            } },
            confirmButton = { TextButton(onClick = { setStage("") }) { Text("Close") } },
        )
        "ask" -> {
            var question by rememberSaveable { mutableStateOf("") }
            val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
                result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { question = (question + " " + it).trim() }
            }
            AlertDialog(
                onDismissRequest = { setStage("") },
                title = { Text("Quick question", fontFamily = FontFamily.Serif) },
                text = {
                    OutlinedTextField(question, { question = it.take(4000) }, minLines = 2, placeholder = { Text("Ask about this screen or anything else") },
                        trailingIcon = { IconButton(onClick = { voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }) { Icon(androidx.compose.material.icons.Icons.Outlined.Mic, "Dictate") } })
                },
                confirmButton = { TextButton(enabled = question.isNotBlank(), onClick = {
                    vm.askInBackground("Quick question asked from the $screen screen${vm.selectedRecipe?.let { " (recipe $it)" } ?: ""}. Answer briefly and directly; this is a one-off question, not a planning request.\n\n$question", "quick_ask")
                    setStage("")
                }) { Text("Ask") } },
                dismissButton = { TextButton(onClick = { setStage("") }) { Text("Cancel") } },
            )
        }
        "write" -> AlertDialog(
            onDismissRequest = { setStage("") },
            title = { Text("A note for later", fontFamily = FontFamily.Serif) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$screen${if (x != null) " · ${(x!! * 100).toInt()}%, ${(y!! * 100).toInt()}%" else ""}", fontSize = 12.sp, color = Muted)
                Text(if (vm.noteScreenshotReady) "Screen image attached · saved separately from the text" else "Screen image unavailable · screen and build details will be saved", fontSize = 12.sp, color = Muted)
                OutlinedTextField(value = target, onValueChange = { target = it.take(150) }, label = { Text("Element or area (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                OutlinedTextField(value = text, onValueChange = { text = it; saveError = "" }, label = { Text("What should we revisit?") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                Text("${text.length} characters · Saves on phone first · No model call", fontSize = 12.sp, color = Muted)
                if (saveError.isNotEmpty()) Text(saveError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(onClick = {
                if (vm.saveAppNote(text, screen, target, x, y)) {
                    text = ""; target = ""; x = null; y = null; saveError = ""; setStage("")
                } else saveError = "Could not save on this phone. Your draft is still here; please try again."
            }, enabled = text.isNotBlank()) { Text("Save note") } },
            dismissButton = { TextButton(onClick = { setStage("") }) { Text("Cancel") } },
        )
        "list" -> AlertDialog(
            onDismissRequest = { setStage("") },
            title = { Text("Field notes", fontFamily = FontFamily.Serif) },
            text = { if (vm.appNotes.isEmpty()) Text("No notes yet. Tap the pencil whenever something feels off.") else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.appNotes, key = { it.s("id") }) { note ->
                    CardBox {
                        Text(note.s("screen") + if (note.s("target").isNotBlank()) " · ${note.s("target")}" else "", fontSize = 11.sp, color = Muted)
                        Text(note.s("text"), fontSize = 14.sp)
                        val details = buildString {
                            append(note.s("phoneTime").take(16).replace("T", " "))
                            append(" · v${note.s("appVersion")}")
                            if (note.optJSONObject("screenshot") != null) append(" · screen image")
                            append(if (note.optBoolean("synced")) " · on laptop" else " · waiting to sync")
                        }
                        Text(details, fontSize = 11.sp, color = Muted)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { setStage("") }) { Text("Done") } },
        )
    }
}
