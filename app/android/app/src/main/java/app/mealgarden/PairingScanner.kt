package app.mealgarden

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.net.URI
import org.json.JSONObject

data class PairingDetails(val endpoint: String, val code: String)

fun parsePairingQr(raw: String): PairingDetails {
    require(raw.length <= 2048) { "This is not a Meal Garden pairing code." }
    val data = try { JSONObject(raw) } catch (_: Exception) {
        throw IllegalArgumentException("Scan the QR code on your laptop's Meal Garden setup page.")
    }
    require(data.optString("app") == "meal-garden" && data.optInt("version") == 1) {
        "Scan the QR code on your laptop's Meal Garden setup page."
    }
    val endpoint = data.optString("endpoint")
    val uri = try { URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("Invalid laptop address in QR code.") }
    require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "Invalid laptop address in QR code." }
    val code = data.optString("code")
    require(Regex("[0-9]{8}").matches(code)) { "Invalid pairing code. Refresh the laptop setup page." }
    return PairingDetails(endpoint.trimEnd('/'), code)
}

@Composable
fun PairingScanner(onBack: () -> Unit, onPairedCode: (PairingDetails) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    var torch by remember { mutableStateOf(false) }
    val onResult by rememberUpdatedState(onPairedCode)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; denied = !it }
    BackHandler(onBack = onBack)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().background(Paper).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GardenTopBar("Scan pairing code", onBack)
        Text("localhost:4783/setup", style = GardenType.Small, color = Forest)
        if (allowed) {
            val camera = remember(context) {
                DecoratedBarcodeView(context).apply {
                    statusView.visibility = View.GONE
                    viewFinder.setMaskColor(android.graphics.Color.argb(165, 8, 24, 15))
                    barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                }
            }
            DisposableEffect(camera, lifecycle) {
                var handled = false
                camera.decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        if (handled || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                        val details = try { parsePairingQr(result.text) } catch (e: Exception) {
                            scanError = e.message ?: "Try the Meal Garden QR code."; return
                        }
                        handled = true
                        camera.pause()
                        onResult(details)
                    }
                })
                val listener = object : CameraPreview.StateListener {
                    override fun previewSized() {}
                    override fun previewStarted() {}
                    override fun previewStopped() {}
                    override fun cameraClosed() {}
                    override fun cameraError(error: Exception) { scanError = "Camera couldn't open. Close other camera apps, then try again, or enter the code manually." }
                }
                camera.barcodeView.addStateListener(listener)
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME && !handled) camera.resume()
                    if (event == Lifecycle.Event.ON_PAUSE) camera.pause()
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) camera.resume()
                onDispose { lifecycle.removeObserver(observer); camera.pause() }
            }
            AndroidView(factory = { camera }, modifier = Modifier.fillMaxWidth().weight(1f).clip(GardenShape.Hero), update = { if (torch) it.setTorchOn() else it.setTorchOff() })
            if (scanError.isNotEmpty()) Text(scanError, color = Clay, style = GardenType.Small)
            if(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                GardenQuietButton(if (torch) "Light off" else "Light on", { torch = !torch },
                    modifier = Modifier.align(Alignment.CenterHorizontally), icon = Icons.Outlined.FlashlightOn)
            }
        } else {
            Surface(Modifier.fillMaxWidth().weight(1f), shape = GardenShape.Hero, color = Paper2) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(56.dp), tint = Forest)
                    Spacer(Modifier.height(20.dp))
                    Text("Camera access", style = GardenType.Section)
                    Spacer(Modifier.height(20.dp))
                    GardenPrimaryButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) })
                    if (denied) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Camera settings", color = Forest) }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Enter code manually", color = Forest) }
    }
}
