package app.mealgarden

import androidx.compose.runtime.*
import android.content.SharedPreferences

// Phone presentation choices never alter food evidence or household targets.
@Composable
fun moduleEnabled(vm: GardenModel, id: String, default: Boolean = true): Boolean {
    var enabled by remember(id) { mutableStateOf(vm.prefs.getBoolean("module:$id", default)) }
    DisposableEffect(vm.prefs, id) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key -> if (key == "module:$id") enabled = prefs.getBoolean(key, default) }
        vm.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { vm.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
