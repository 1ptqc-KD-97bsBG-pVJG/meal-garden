package app.mealgarden

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.unit.*

@Composable
fun MoreScreen(open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Heading("More") }
        listOf("app" to "Your app", "activity" to "Activity", "receipts" to "Receipts", "preferences" to "Preferences", "ask" to "Ask", "connection" to "Connection").forEach { (route, label) ->
            item { GardenCard(onClick = { open(route) }) { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when(route) { "app" -> Icons.Outlined.Tune; "activity" -> Icons.Outlined.History; "receipts" -> Icons.Outlined.ReceiptLong; "preferences" -> Icons.Outlined.FavoriteBorder; "ask" -> Icons.Outlined.HelpOutline; else -> Icons.Outlined.Link }, null, tint = Forest, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp)); Text(label, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
            } } }
        }
    }
}

@Composable
fun ShellDetail(vm: GardenModel, page: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        GardenTopBar(when(page) { "app" -> "Your app"; "activity" -> "Activity"; else -> "Receipts" }, onBack)
        when(page) {
            "receipts" -> MarketScreen(vm)
            "activity" -> LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.activity.a("jobs").objects(), key = { it.s("id") }) { job -> GardenCard { Text(job.s("kind").replace('_',' ')); Text(job.s("status"), style = GardenType.Small) } }
            }
            else -> TextButton(onClick = { vm.openPreferences = true }) { Text("Preferences") }
        }
    }
}
