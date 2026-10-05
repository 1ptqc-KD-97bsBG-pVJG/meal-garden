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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlin.math.*

@Composable
fun MarketScreen(vm: GardenModel) {
    var page by rememberSaveable { mutableStateOf("Shopping") }
    val receipts = vm.snapshot.a("receipts").objects()
    var expanded by rememberSaveable { mutableStateOf("") }
    val demand = vm.snapshot.o("shopping")
    val trip = vm.snapshot.o("shoppingTrip")
    val plan = vm.snapshot.o("activePlan")
    val buyRows = trip.a("buy").objects()
    var decisions by remember(trip.s("reviewKey")) { mutableStateOf(buyRows.map { it.s("purchase_decision").ifBlank { if (it.s("purchase_condition").isBlank()) "buy" else "" } }) }
    val reviewPending = vm.outbox.any { it.s("route") == "/api/shopping/review" }
    val unresolved = buyRows.any { it.s("purchase_condition").isNotBlank() && it.s("purchase_decision") !in listOf("buy", "have") }
    val past = vm.snapshot.optBoolean("planIsPast", false)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp),
    ) {
        item { Heading("Shopping") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Shopping", "Purchases", "Activity").forEach { f ->
                    FilterChip(
                        selected = page == f,
                        onClick = { page = f },
                        label = { Text(f) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime),
                    )
                }
            }
        }
        when (page) {
            "Shopping" -> {
                item {
                    CardBox(color = Forest) {
                        Eyebrow(if (vm.shoppingEnabled) vm.household.o("shopping").s("list_service").replace('_', ' ').uppercase() else "CHECK AT HOME", Lime)
                        Text(
                            vm.shoppingListName,
                            fontFamily = FontFamily.Serif,
                            fontSize = 31.sp,
                            color = Color.White,
                        )
                        Button(
                            onClick = { vm.send("shopping") },
                            enabled = vm.shoppingEnabled && !past && trip.s("plan_id") == plan.s("id") && !unresolved && !reviewPending,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Lime,
                                    contentColor = Forest,
                                ),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync ${vm.shoppingListName}")
                        }
                    }
                }
                if (past)
                    item {
                        Note(
                            "This demand belongs to the saved plan. Start a new plan before buying it again. Sync will ask about the intended meals.",
                            Icons.Outlined.History,
                        )
                    }
                if (trip.s("plan_id") == plan.s("id")) item {
                    CardBox(color = Mist) {
                        Eyebrow("THIS STORE TRIP")
                        val conditional = unresolved
                        Text("${trip.o("store").s("name", vm.storeName)} · ${if (conditional) "check before buying" else "buy these"}", fontFamily = FontFamily.Serif, fontSize = 24.sp)
                        Text(trip.o("store").s("address"), fontSize = 12.sp, color = Muted)
                        buyRows.forEachIndexed { index, row ->
                            HorizontalDivider(color = Line)
                            Text("${row.optInt("quantity")} ${row.s("unit")} · ${row.s("name")}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (row.s("purchase_condition").isNotBlank()) Text(row.s("purchase_condition"), fontSize = 12.sp, color = Muted)
                            Text(row.s("department"), fontSize = 12.sp, color = Muted)
                            if (row.s("purchase_condition").isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("buy" to "Buy", "have" to "Have enough").forEach { (value, label) ->
                                    FilterChip(decisions.getOrNull(index) == value, { decisions = decisions.toMutableList().apply { set(index, value) } }, enabled = !reviewPending, label = { Text(label) })
                                }
                            }
                        }
                        if (buyRows.any { it.s("purchase_condition").isNotBlank() }) Button(enabled = decisions.none { it.isBlank() } && !reviewPending && !past, onClick = {
                            vm.graphWrite("/api/shopping/review", j("reviewKey" to trip.s("reviewKey"), "decisions" to org.json.JSONArray(decisions)), "shopping_review")
                        }) { Text(if (reviewPending) "Waiting to sync" else "Save shopping choices") }
                        trip.a("check_before_buying").strings().forEach { Text("Check: $it", fontSize = 12.sp, color = Muted) }
                    }
                }
                item {
                    SectionLabel("All recipe ingredients · check at home", "Plan meals") {
                        vm.ask(
                            "Create a current meal plan and rebuild grocery demand from the recipes we choose."
                        )
                    }
                    Text(plan.s("title"), fontSize = 12.sp, color = Muted)
                }
                items(demand.a("items").objects(), key = { it.s("id") }) { i ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ShoppingBasket,
                            null,
                            tint = Muted,
                            modifier = Modifier.size(19.dp),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(i.s("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${i.s("purchase_count")} ${i.o("purchase").s("unit")} · ${i.o("purchase").s("label")}",
                                fontSize = 12.sp,
                                color = Muted,
                            )
                            Text(
                                "Recipe use: ${i.s("amount")} ${i.s("unit")}",
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                    }
                }
                item {
                    Note(
                        if (vm.shoppingEnabled) "These are local recipe quantities. Current shopping-list rows and checkmarks are read on the laptop during sync." else "These are local recipe quantities. No shopping integration is configured for this household."
                    )
                }
            }
            "Purchases" -> {
                item {
                    CardBox(color = Mist) {
                        Eyebrow("${vm.storeName.uppercase()} PURCHASE HISTORY")
                        Text("What came home?", fontFamily = FontFamily.Serif, fontSize = 29.sp)
                        Text(
                            "Refresh your recent receipts using the signed-in browser on your laptop.",
                            fontSize = 14.sp,
                            color = Muted,
                        )
                        if (vm.shoppingEnabled) ActionButton("Refresh purchases", Icons.Outlined.Refresh) {
                            vm.send("purchases")
                        }
                        TextButton(onClick = {
                            vm.ask("I'd like to import a receipt photo. Extract food purchases, preserve uncertain quantities, omit payment and loyalty details, and check for duplicates.")
                        }) { Text("Import receipt photo") }
                    }
                }
                if (receipts.isEmpty())
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(13.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ReceiptLong,
                                null,
                                tint = Muted,
                                modifier = Modifier.size(42.dp),
                            )
                            Text(
                                "Your first receipt starts here.",
                                fontFamily = FontFamily.Serif,
                                fontSize = 23.sp,
                            )
                            Text(
                                "No purchases have been imported yet. Attach a receipt photo in chat.",
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                color = Muted,
                            )
                            TextButton(
                                onClick = {
                                    vm.ask(
                                        "I'd like to import a receipt photo. Extract food purchases, preserve uncertain quantities, omit payment and loyalty details, and check for duplicates."
                                    )
                                }
                            ) {
                                Text("Use a receipt photo")
                            }
                        }
                    }
                else {
                    item { SectionLabel("${receipts.size} imported receipts") }
                    items(receipts, key = { it.s("id") }) { r ->
                        CardBox {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    expanded = if (expanded == r.s("id")) "" else r.s("id")
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.s("store"),
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 25.sp,
                                    )
                                    Text(
                                        "${r.s("date")} · ${r.a("items").length()} items",
                                        fontSize = 12.sp,
                                        color = Muted,
                                    )
                                }
                                if (!r.isNull("total"))
                                    Text(
                                        "$${"%.2f".format(r.optDouble("total"))}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                Icon(Icons.Outlined.ExpandMore, null)
                            }
                            if (expanded == r.s("id")) {
                                r.a("items").objects().forEach { i ->
                                    HorizontalDivider(color = Line)
                                    Row {
                                        Column(Modifier.weight(1f)) {
                                            Text(i.s("name"), fontSize = 14.sp)
                                            Text(i.s("quantity"), fontSize = 11.sp, color = Muted)
                                        }
                                        if (!i.isNull("price"))
                                            Text(
                                                "$${"%.2f".format(i.optDouble("price"))}",
                                                fontSize = 13.sp,
                                            )
                                    }
                                }
                                Text(r.s("source"), fontSize = 11.sp, color = Muted)
                            }
                            Text(
                                "Purchase evidence · current stock unconfirmed",
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }
            "Activity" -> {
                item {
                    Text(
                        "Tasks keep running on the laptop when you leave the app. Check back here for results or sign-in issues.",
                        fontSize = 14.sp,
                        color = Muted,
                    )
                }
                if (vm.activity.a("jobs").length() == 0)
                    item {
                        Note("No laptop tasks yet. Start a conversation or refresh your purchases.")
                    }
                items(vm.activity.a("jobs").objects(), key = { it.s("id") }) { job ->
                    CardBox {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (job.s("kind")) {
                                    "shopping" -> "${vm.shoppingListName} sync"
                                    "purchases" -> "${vm.storeName} refresh"
                                    "connection" -> "Browser check"
                                    else -> "Conversation"
                                },
                                fontFamily = FontFamily.Serif,
                                fontSize = 23.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Pill(
                                job.s("status").replace('_', ' '),
                                if (
                                    job.s("status") in
                                        listOf(
                                            "failed",
                                            "partial",
                                            "needs_sign_in",
                                            "interrupted",
                                            "unverified",
                                        )
                                )
                                    Color(0xFFF3E4D3)
                                else Mist,
                            )
                        }
                        Text(job.s("progress"), fontSize = 13.sp, color = Muted)
                        if (job.s("error").isNotEmpty())
                            Text(job.s("error"), fontSize = 12.sp, color = Clay)
                        Text(
                            job.s("created").take(16).replace('T', ' ') + " · " + job.s("model"),
                            fontSize = 10.sp,
                            color = Muted,
                        )
                        TextButton(onClick = { vm.chooseConversation(job.s("conversation_id")) }) {
                            Text("View conversation")
                        }
                        if (job.s("status") in listOf("running", "waiting", "queued"))
                            TextButton(onClick = { vm.stop(job.s("id")) }) { Text("Stop task") }
                    }
                }
            }
        }
    }
}
