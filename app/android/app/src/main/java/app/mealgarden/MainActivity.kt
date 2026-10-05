@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.app.*
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Forest,
                        secondary = Clay,
                        background = Cream,
                        surface = Cream,
                        onSurface = Ink,
                        onBackground = Ink,
                        outline = Line,
                    ),
                typography =
                    Typography(
                        bodyLarge =
                            androidx.compose.ui.text.TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            ),
                        bodyMedium =
                            androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                    ),
            ) {
                GardenApp()
            }
        }
    }
}

@Composable
fun GardenApp(vm: GardenModel = viewModel()) {
    val snack = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    var noteStage by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm.error) {
        if (vm.error.isNotBlank()) {
            snack.showSnackbar(vm.error, duration = SnackbarDuration.Long)
            vm.error = ""
        }
    }
    LaunchedEffect(vm.notice) {
        if (vm.notice.isNotBlank()) {
            snack.showSnackbar(vm.notice)
            vm.notice = ""
        }
    }
    val selected = vm.selectedRecipe?.let { vm.recipe(it) }
    val noteView = LocalView.current
    val camera = rememberCamera { vm.beginPhotoCapture(it) }
    LaunchedEffect(vm.cameraRequests) { if (vm.cameraRequests > 0) camera() }
    // Focused sub-screens (kitchen reset) hide the global chrome and offer the note pencil in their own top bar.
    val focused = vm.openFridgeCheck || vm.openHealth || vm.openPreferences
    val screenKey = when {
        vm.openPreferences -> "preferences"
        vm.openSettings -> "settings"
        vm.openHistory -> "history"
        vm.openFridgeCheck -> "fridge-check"
        vm.openHealth -> "health"
        vm.openFoodLog -> "food-log"
        selected != null -> "recipe:${selected.s("id")}:${selected.optInt("revision")}" 
        else -> "tab:${vm.tab}"
    }
    val noteScreen = when {
        vm.openPreferences -> "Preferences"
        vm.openFridgeCheck -> "Kitchen reset"
        vm.openHealth -> "Health insights"
        vm.openFoodLog -> "Food log"
        selected != null -> "Recipe: ${selected.s("title")}" 
        vm.openSettings -> "Connection"
        vm.openHistory -> "Conversations"
        else -> listOf("Today", "Recipes", "Chat", "Pantry", "Market").getOrElse(vm.tab) { "Meal Garden" }
    }
    LaunchedEffect(screenKey) { vm.track("screen", "key" to screenKey) }
    LaunchedEffect(vm.noteRequests) {
        if (vm.noteRequests > 0) {
            vm.captureNoteScreen(noteView, screenKey, noteScreen, selected?.optInt("revision"))
            noteStage = "menu"
        }
    }
    BackHandler(enabled = noteStage.isEmpty() && (vm.openPreferences || vm.openSettings || vm.openHistory || vm.openFridgeCheck || vm.openHealth || vm.openFoodLog || selected != null)) {
        when {
            vm.openPreferences -> vm.openPreferences = false
            vm.openSettings -> vm.openSettings = false
            vm.openHistory -> vm.openHistory = false
            vm.openFridgeCheck -> vm.openFridgeCheck = false
            vm.openHealth -> vm.openHealth = false
            vm.openFoodLog -> vm.openFoodLog = false
            else -> vm.selectedRecipe = null
        }
    }
    Scaffold(
        containerColor = Cream,
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (noteStage.isEmpty() && !vm.openSettings && !vm.openHistory && !focused) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallFloatingActionButton(onClick = {
                vm.captureNoteScreen(noteView, screenKey, noteScreen, selected?.optInt("revision"))
                noteStage = "menu"
            }, containerColor = Lime) { Icon(Icons.Outlined.EditNote, "Leave an app note", tint = Forest) }
            // Chat has its own camera in the composer; the floating one would cover Send.
            if (vm.tab != 2 || selected != null || vm.openFoodLog) FloatingActionButton(onClick = { camera() }, containerColor = Forest, contentColor = Cream, shape = CircleShape) {
                Icon(Icons.Outlined.PhotoCamera, "Log food")
            }
        } },
        bottomBar = {
            if (selected == null && !vm.openSettings && !vm.openHistory && !focused)
                NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                    listOf(
                            "Today" to Icons.Outlined.WbSunny,
                            "Recipes" to Icons.AutoMirrored.Outlined.MenuBook,
                            "Chat" to Icons.Outlined.AutoAwesome,
                            "Pantry" to Icons.Outlined.Kitchen,
                            "Market" to Icons.Outlined.ShoppingBag,
                        )
                        .forEachIndexed { index, pair ->
                            NavigationBarItem(
                                selected = vm.tab == index,
                                onClick = { vm.openFoodLog = false; vm.tab = index },
                                icon = { Icon(pair.second, pair.first) },
                                label = { Text(pair.first, fontSize = 11.sp) },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = Forest,
                                        selectedTextColor = Forest,
                                        indicatorColor = Lime,
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = Muted,
                                    ),
                            )
                        }
                }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()) {
            if (selected == null && !vm.openSettings && !vm.openHistory && !vm.openFoodLog && !focused) TopBrand(vm)
            TimerDock(vm)
            screenState.SaveableStateProvider(screenKey) {
                when {
                    vm.openPreferences -> PreferencesScreen(vm)
                    vm.openSettings -> ConnectionScreen(vm)
                    vm.openHistory -> HistoryScreen(vm)
                    vm.openFridgeCheck -> FridgeCheckScreen(vm)
                    vm.openHealth -> HealthScreen(vm)
                    vm.openFoodLog -> FoodLogScreen(vm)
                    selected != null -> RecipeScreen(vm, selected)
                    else ->
                        when (vm.tab) {
                            0 -> TodayScreen(vm)
                            1 -> RecipesScreen(vm)
                            2 -> ChatScreen(vm)
                            3 -> PantryScreen(vm)
                            4 -> MarketScreen(vm)
                        }
                }
            }
        }
        AppNoteCapture(vm, noteStage, { noteStage = it }, noteScreen)
        CaptureSheet(vm)
        }
    }
}
