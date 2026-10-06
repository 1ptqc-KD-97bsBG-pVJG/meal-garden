package app.mealgarden

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionScreen(vm: GardenModel) {
    var address by rememberSaveable { mutableStateOf(vm.vault.endpoint) }
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        PairingScanner(onBack = { scanning = false }) { details ->
            scanning = false; address = details.endpoint; code = details.code
            vm.connect(address, code)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("garden-connection"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { GardenTopBar("Connection", onBack = { vm.openSettings = false }) }
        item {
            GardenCard(color = if (vm.online) Mist else CardSurface) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (vm.online) Icons.Outlined.Link else Icons.Outlined.LinkOff, null, Modifier.size(27.dp), tint = Forest)
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.paired) vm.kitchenName else "Connect your laptop", style = GardenType.Section)
                        Text(if (vm.online) "Connected" else if (vm.paired) "Laptop offline" else "Not connected", style = GardenType.Small)
                    }
                }
                if (vm.paired) {
                    Text(vm.vault.endpoint, style = GardenType.Small)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GardenQuietButton("Check again", onClick = { vm.refreshNow() }, icon = Icons.Outlined.Refresh, enabled = !vm.busy)
                        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                    }
                    if (vm.outbox.isNotEmpty()) GardenChip("${vm.outbox.size} waiting to sync", icon = Icons.Outlined.CloudUpload)
                } else {
                    Text("Open localhost:4783/setup on your laptop.", style = GardenType.Body)
                    GardenPrimaryButton("Scan laptop QR code", onClick = { scanning = true }, icon = Icons.Outlined.QrCodeScanner, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!vm.paired) item {
            GardenCard {
                Text("Manual connection", style = GardenType.Section)
                OutlinedTextField(address, { address = it }, label = { Text("Laptop address") }, placeholder = { Text("http://100.x.x.x:4783") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("8-digit pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                GardenPrimaryButton(if (vm.busy) "Connecting…" else "Connect laptop", onClick = { vm.connect(address, code) }, enabled = !vm.busy && address.isNotBlank() && code.length == 8, modifier = Modifier.fillMaxWidth())
            }
        }
        if (vm.shoppingEnabled && vm.paired) item {
            GardenQuietButton("Check browser sign-ins", onClick = { vm.openSettings = false; vm.send("connection") }, icon = Icons.Outlined.TravelExplore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun HistoryScreen(vm: GardenModel) {
    val conversations = vm.activity.a("conversations").objects()
    LazyColumn(Modifier.fillMaxSize().testTag("conversation-history"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { GardenTopBar("Conversations", onBack = { vm.openHistory = false }) {
            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Outlined.Add, "New conversation", tint = Forest) }
        } }
        if (conversations.isEmpty()) item { GardenCard { Text("No conversations yet", style = GardenType.Body) } }
        items(conversations, key = { it.s("id") }) { conversation ->
            GardenCard(onClick = { vm.chooseConversation(conversation.s("id")) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(23.dp), tint = Forest)
                    Column(Modifier.weight(1f)) {
                        Text(conversation.s("title").ifBlank { "Conversation" }, style = GardenType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(activityDate(conversation.s("updated", conversation.s("created"))), style = GardenType.Small)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                }
            }
        }
    }
}
