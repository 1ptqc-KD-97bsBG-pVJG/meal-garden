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

@Composable
fun ConnectionScreen(vm: GardenModel) {
    var address by rememberSaveable { mutableStateOf(vm.vault.endpoint) }
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        PairingScanner(onBack = { scanning = false }) { details ->
            scanning = false
            address = details.endpoint
            code = details.code
            vm.connect(address, code)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.openSettings = false }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Text(vm.kitchenName, fontSize = 14.sp)
        }
        LazyColumn(
            contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Icon(Icons.Outlined.Devices, null, tint = Forest, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(15.dp))
                Heading(
                    "Your phone.\nYour kitchen.\nYour laptop.",
                    "Connected over Wi-Fi or your Tailscale network.",
                )
            }
            if (vm.paired) {
                item {
                    CardBox(color = Mist) {
                        Eyebrow(if (vm.online) "CONNECTED" else "LAPTOP UNREACHABLE")
                        Text(vm.vault.endpoint, fontSize = 15.sp)
                        Text(
                            if (vm.online)
                                "Your assistant is ready. Recipes and cooking progress remain available offline."
                            else
                                "Check that the companion is running and both devices are on the same Wi-Fi or Tailscale network.",
                            fontSize = 13.sp,
                            color = Muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.refreshNow() }) { Text("Check again") }
                            TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                        }
                    }
                }
                if (vm.shoppingEnabled) item {
                    ActionButton("Check browser sign-ins", Icons.Outlined.TravelExplore) {
                        vm.openSettings = false
                        vm.send("connection")
                    }
                }
            } else {
                item {
                    CardBox {
                        Eyebrow("1 · ON YOUR LAPTOP")
                        Text(
                            "Open Start Meal Garden.command in this project. Then open localhost:4783/setup in the laptop's browser.",
                            fontSize = 14.sp,
                        )
                        Eyebrow("2 · ON YOUR PHONE")
                        Text(
                            "Scan the QR code on your laptop to connect automatically, or enter the details below.",
                            fontSize = 14.sp,
                        )
                        Button(onClick = { scanning = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan laptop QR code")
                        }
                        Text("OR CONNECT MANUALLY", fontSize = 11.sp, color = Muted)
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Laptop address") },
                            placeholder = { Text("http://100.x.x.x:4783") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(8) },
                            label = { Text("8-digit pairing code") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { vm.connect(address, code) },
                            enabled = !vm.busy && address.isNotBlank() && code.length == 8,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (vm.busy)
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Connect laptop")
                        }
                    }
                }
            }
            item {
                ActionButton("Preferences", Icons.Outlined.Tune) { vm.track("preferences_open"); vm.openPreferences = true }
            }
            item {
                CardBox {
                    Eyebrow("ASSISTANT")
                    Text(
                        "Codex, through your subscription",
                        fontFamily = FontFamily.Serif,
                        fontSize = 23.sp,
                    )
                    Text(
                        "GPT-6.1 Sol · medium reasoning",
                        fontSize = 14.sp,
                        color = Muted,
                    )
                    Text(
                        "Choose Quick in chat for small mechanical tasks. Meal planning and browser work use Sol.",
                        fontSize = 12.sp,
                        color = Muted,
                    )
                }
            }
            item {
                CardBox {
                    Eyebrow("BUILT TO GROW")
                    Text(
                        "Local compute, when you're ready.",
                        fontFamily = FontFamily.Serif,
                        fontSize = 23.sp,
                    )
                    Text(
                        "The app talks to a companion service. A future queued local-model provider can run on your M5 Max or another computer while the phone experience stays familiar.",
                        fontSize = 13.sp,
                        color = Muted,
                    )
                }
            }
            item {
                Text(
                    "Meal Garden 0.2 · personal preview\nNative Android · your existing meal workspace",
                    fontSize = 11.sp,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: GardenModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.openHistory = false }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Text("Your conversations", fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Outlined.Add, "New conversation")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Heading("Keep the thread.", "Pick up a conversation whenever you're ready.") }
            items(vm.activity.a("conversations").objects(), key = { it.s("id") }) { c ->
                CardBox(modifier = Modifier.clickable { vm.chooseConversation(c.s("id")) }) {
                    Text(
                        c.s("title"),
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(c.s("created").take(10), fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}
