@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mealgarden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ComponentGallery(insetTop: Boolean = true, onBack: () -> Unit) {
    var portions by rememberSaveable { mutableIntStateOf(4) }
    var remaining by rememberSaveable { mutableIntStateOf(3) }
    var state by rememberSaveable { mutableStateOf(PantryStateMark.Fine) }
    var zone by rememberSaveable { mutableStateOf("Fridge") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Paper).then(if (insetTop) Modifier.statusBarsPadding() else Modifier)) {
        GardenTopBar("Components", onBack)
        LazyColumn(Modifier.weight(1f).testTag("component-gallery"), contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GardenCard {
                    Text("Today", style = GardenType.Title)
                    Text("Tonight?", style = GardenType.Section)
                    Text("Around 6", style = GardenType.Body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Paper" to Paper, "Card" to CardSurface, "Forest" to Forest,
                            "Leaf" to Leaf, "Lime" to Lime, "Amber" to Amber, "Clay" to Clay, "Ice" to Ice).forEach { (label, color) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(color))
                                Text(label, style = GardenType.Small)
                            }
                        }
                    }
                }
            }
            item {
                GardenCard {
                    SectionLabel("Plates")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        GardenPlate("Rice bowl", seed = 1, mark = PlateMark.MadeToday)
                        GardenPlate("Rice bowl", seed = 1, count = 2, drink = true, mark = PlateMark.Leftover)
                        GardenPlate("Pasta", seed = 2)
                        GardenPlate("", empty = true)
                    }
                    PlateKey()
                }
            }
            item {
                GardenCard {
                    SectionLabel("Chips")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Fridge", "Freezer", "Pantry", "Counter").forEach { label ->
                            GardenChip(label, selected = zone == label, onClick = { zone = label })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        GardenChip("Use soon", tint = AmberLight, icon = Icons.Outlined.Schedule)
                        GardenChip("Frozen", tint = IceLight, icon = Icons.Outlined.AcUnit)
                        GardenChip("Ready", tint = Mist, icon = Icons.Outlined.Check)
                    }
                }
            }
            item {
                GardenCard {
                    SectionLabel("Buttons")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GardenPrimaryButton("Looks right", {}, icon = Icons.Outlined.Check)
                        GardenQuietButton("Later", {})
                    }
                    GardenPrimaryButton("Start cooking", {}, modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.Restaurant)
                    GardenQuietButton("Unavailable", {}, enabled = false)
                }
            }
            item {
                GardenCard(modifier = Modifier.testTag("gallery-amounts")) {
                    SectionLabel("Amounts")
                    AmountStepper(portions, { portions = it }, label = "Portions", min = 1, max = 20)
                    FourLevelAmount(remaining, { remaining = it })
                    GardenStack(portions, Modifier.align(Alignment.CenterHorizontally))
                }
            }
            item {
                if (panel) GardenPanel("Still have it?", onClose = { panel = false }, modifier = Modifier.testTag("gallery-panel")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Have it")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf(PantryStateMark.Fine, PantryStateMark.UseSoon).forEach { mark ->
                                    StateMark(mark, modifier = Modifier.weight(1f), selected = state == mark,
                                        assumed = mark == PantryStateMark.Fine, onClick = { state = mark })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Don't")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf(PantryStateMark.UsedUp, PantryStateMark.Tossed).forEach { mark ->
                                    StateMark(mark, modifier = Modifier.weight(1f), selected = state == mark,
                                        onClick = { state = mark })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SparkleMark(); Text("Half a bag", style = GardenType.Small)
                    }
                    FourLevelAmount(remaining, { remaining = it })
                    GardenQuietButton("Move to freezer", {}, icon = Icons.Outlined.AcUnit)
                } else GardenQuietButton("Open panel", { panel = true })
            }
            item { GardenCard { SectionLabel("Key"); StateKey() } }
        }
        GardenBottomBar(tab, { tab = it }, { tab = 2 })
    }
}
