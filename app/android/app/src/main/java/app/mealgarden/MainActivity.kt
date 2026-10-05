@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.mealgarden

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GardenTheme { GardenApp() } }
    }
}

@Composable
fun GardenApp(vm: GardenModel = viewModel()) {
    val snack = remember { SnackbarHostState() }
    val screens = rememberSaveableStateHolder()
    var noteStage by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("") }
    var gallery by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val camera = rememberCamera { vm.beginPhotoCapture(it) }
    LaunchedEffect(vm.cameraRequests) { if (vm.cameraRequests > 0) camera() }
    LaunchedEffect(vm.error) { if (vm.error.isNotBlank()) { snack.showSnackbar(vm.error, duration = SnackbarDuration.Long); vm.error = "" } }
    LaunchedEffect(vm.notice) { if (vm.notice.isNotBlank()) { snack.showSnackbar(vm.notice); vm.notice = "" } }
    // Engine tab values remain stable for persisted telemetry and task navigation.
    LaunchedEffect(vm.tab) { page = "" }
    val selected = vm.selectedRecipe?.let { vm.recipe(it) }
    val key = when {
        gallery -> "components"
        vm.openPreferences -> "preferences"
        vm.openSettings -> "connection"
        vm.openHistory -> "history"
        vm.openFridgeCheck -> "kitchen-check"
        vm.openHealth -> "health"
        vm.openFoodLog -> "food-log"
        selected != null -> "recipe:${selected.s("id")}:${selected.optInt("revision")}"
        page.isNotBlank() -> page
        else -> "tab:${vm.tab}"
    }
    val title = when {
        gallery -> "Components"
        vm.openPreferences -> "Preferences"
        vm.openSettings -> "Connection"
        vm.openHistory -> "Conversations"
        vm.openFridgeCheck -> "Kitchen check"
        vm.openHealth -> "Eating"
        vm.openFoodLog -> "Food log"
        selected != null -> selected.s("title")
        page == "app" -> "Your app"
        page == "activity" -> "Activity"
        page == "receipts" -> "Receipts"
        else -> listOf("Today", "Recipes", "Ask", "Kitchen", "More").getOrElse(vm.tab) { "Meal Garden" }
    }
    fun back() {
        when {
            gallery -> gallery = false
            vm.openPreferences -> vm.openPreferences = false
            vm.openSettings -> vm.openSettings = false
            vm.openHistory -> vm.openHistory = false
            vm.openFridgeCheck -> vm.openFridgeCheck = false
            vm.openHealth -> vm.openHealth = false
            vm.openFoodLog -> vm.openFoodLog = false
            selected != null -> vm.selectedRecipe = null
            page.isNotBlank() -> page = ""
            vm.tab == 2 -> vm.tab = 4
        }
    }
    val nested = gallery || vm.openPreferences || vm.openSettings || vm.openHistory || vm.openFridgeCheck || vm.openHealth || vm.openFoodLog || selected != null || page.isNotBlank() || vm.tab == 2
    BackHandler(enabled = nested && noteStage.isEmpty()) { back() }
    LaunchedEffect(key) { vm.track("screen", "key" to key) }
    fun note() { vm.captureNoteScreen(view, key, title, selected?.optInt("revision")); noteStage = "menu" }
    LaunchedEffect(vm.noteRequests) { if (vm.noteRequests > 0) note() }
    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("meal garden", style = GardenType.Section, color = Forest, modifier = Modifier.weight(1f))
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    IconButton(onClick = { gallery = true }) { Icon(Icons.Outlined.Widgets, "Component gallery", tint = Muted, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { vm.openSettings = true }) { Icon(if (vm.online) Icons.Outlined.Link else Icons.Outlined.LinkOff, "Settings", tint = if (vm.online) Forest else Muted, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { note() }) { Icon(Icons.Outlined.EditNote, "Leave an app note", tint = Forest, modifier = Modifier.size(22.dp)) }
            }
        },
        bottomBar = {
            if (!gallery) GardenBottomBar(
                selected = when (vm.tab) { 0 -> 0; 3 -> 1; 1 -> 3; else -> 4 },
                onSelect = { index ->
                    vm.openPreferences = false; vm.openSettings = false; vm.openHistory = false
                    vm.openFridgeCheck = false; vm.openHealth = false; vm.openFoodLog = false
                    vm.selectedRecipe = null; page = ""
                    vm.tab = when (index) { 0 -> 0; 1 -> 3; 3 -> 1; else -> 4 }
                },
                onCapture = { vm.beginTextCapture() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                TimerDock(vm)
                screens.SaveableStateProvider(key) {
                    when {
                        gallery -> ComponentGallery(insetTop = false) { gallery = false }
                        vm.openPreferences -> PreferencesScreen(vm)
                        vm.openSettings -> ConnectionScreen(vm)
                        vm.openHistory -> HistoryScreen(vm)
                        vm.openFridgeCheck -> FridgeCheckScreen(vm)
                        vm.openHealth -> HealthScreen(vm)
                        vm.openFoodLog -> FoodLogScreen(vm)
                        selected != null -> RecipeScreen(vm, selected)
                        page.isNotBlank() -> ShellDetail(vm, page) { page = "" }
                        else -> when (vm.tab) {
                            0 -> TodayScreen(vm)
                            1 -> RecipesScreen(vm)
                            2 -> ChatScreen(vm)
                            3 -> PantryScreen(vm)
                            4 -> MoreScreen { route ->
                                when (route) {
                                    "preferences" -> vm.openPreferences = true
                                    "connection" -> vm.openSettings = true
                                    "ask" -> vm.tab = 2
                                    else -> page = route
                                }
                            }
                        }
                    }
                }
            }
            AppNoteCapture(vm, noteStage, { noteStage = it }, title)
            CaptureSheet(vm)
        }
    }
}
