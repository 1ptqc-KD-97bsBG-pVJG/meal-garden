package app.mealgarden

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.s(key: String, default: String = "") =
    if (isNull(key)) default else optString(key, default)

fun JSONObject.o(key: String) = optJSONObject(key) ?: JSONObject()

fun JSONObject.a(key: String) = optJSONArray(key) ?: JSONArray()

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

fun j(vararg fields: Pair<String, Any?>) =
    JSONObject().apply { fields.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

class Vault(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("garden-token", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "garden-token",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    var token: String
        get() =
            try {
                val saved = prefs.getString("token", null)
                if (saved == null) ""
                else {
                    val parts = saved.split(":")
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, Base64.decode(parts[0], 0)),
                    )
                    String(c.doFinal(Base64.decode(parts[1], 0)))
                }
            } catch (e: Exception) {
                ""
            }
        set(value) {
            if (value.isEmpty()) prefs.edit().remove("token").apply()
            else {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key())
                prefs
                    .edit()
                    .putString(
                        "token",
                        Base64.encodeToString(c.iv, 2) +
                            ":" +
                            Base64.encodeToString(c.doFinal(value.toByteArray()), 2),
                    )
                    .apply()
            }
        }

    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(v) {
            prefs.edit().putString("endpoint", v).apply()
        }
}

class GardenModel(app: Application) : AndroidViewModel(app) {
    val prefs = app.getSharedPreferences("garden", Context.MODE_PRIVATE)
    val vault = Vault(app)
    var snapshot by mutableStateOf(JSONObject())
        private set

    val household get() = snapshot.o("household")
    val kitchenName get() = household.s("name", "Your kitchen")
    val personName get() = household.o("person").s("name", "You")
    val shoppingEnabled get() = household.optJSONObject("shopping") != null
    val shoppingListName get() = household.o("shopping").s("list_name", "Shopping list")
    val storeName get() = household.o("shopping").o("store").s("chain", "Store")

    private fun applyHousehold() {
        householdZone = runCatching { java.time.ZoneId.of(household.s("timezone", java.time.ZoneId.systemDefault().id)) }.getOrDefault(java.time.ZoneId.systemDefault())
    }

    var activity by mutableStateOf(JSONObject())
        private set

    var messages by mutableStateOf(listOf<JSONObject>())
        private set

    var conversation by mutableStateOf(prefs.getString("conversation", "") ?: "")
    var online by mutableStateOf(false)
        private set

    var paired by mutableStateOf(vault.token.isNotEmpty())
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
    var notice by mutableStateOf("")
    var attachment by mutableStateOf("")
    var attachmentName by mutableStateOf("")
    var tab by mutableIntStateOf(0)
    var selectedRecipe by mutableStateOf<String?>(null)
    var openSettings by mutableStateOf(false)
    var openPreferences by mutableStateOf(false)
    var openHistory by mutableStateOf(false)
    var openFridgeCheck by mutableStateOf(false)
    var openFoodLog by mutableStateOf(false)
    var openCapture by mutableStateOf(false)
    var openHealth by mutableStateOf(false)
    var openActivity by mutableStateOf(false)
    var selectedPantryItem by mutableStateOf("")
    var cameraRequests by mutableIntStateOf(0)
    var noteRequests by mutableIntStateOf(0)
    var openLunchGuide by mutableStateOf(false)
    var captures by mutableStateOf(loadCaptures())
        private set
    var draftCapture by mutableStateOf<JSONObject?>(null)
    var capturePhotoBusy by mutableStateOf(false)
        private set
    private var syncingCaptures = false
    var quick by mutableStateOf(false)
    var draft by mutableStateOf("")
    var appNotes by mutableStateOf(loadAppNotes())
        private set
    var noteScreenshotReady by mutableStateOf(false)
        private set
    var noteScreenshotProblem by mutableStateOf("")
        private set
    private var pendingNoteScreenshot: ByteArray? = null
    private var pendingNoteScreenContext: JSONObject? = null
    private var pendingNoteScreenshotWidth = 0
    private var pendingNoteScreenshotHeight = 0
    private var syncingNotes = false
    private var lastSnapshot = 0L
    private var pendingSendKey = ""
    private var pendingSendSignature = ""
    private var pendingSendClient: JSONObject? = null
    private var composeStartedAt = ""
    private var composeScreen = ""
    private var composeOrigin = "chat_composer"
    private var uiModelAtCompose = ""
    private var composeRecipeId = ""
    private var modelDisplayHistory = JSONArray()

    private fun currentScreen() = listOf("Today", "Recipes", "Ask", "Kitchen", "More").getOrElse(tab) { "Meal Garden" }
    private fun shoppingDraft(text: String) =
        Regex("\\b(shopping list|aisle)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            (shoppingEnabled && shoppingListName.isNotBlank() && text.contains(shoppingListName, ignoreCase = true)) ||
            Regex("\\badd\\b[\\s\\S]{0,120}\\b(?:to|on)\\s+(?:my|the)\\s+(?:shopping\\s+)?list\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun uiModel() = if (quick || shoppingDraft(draft)) "gpt-6.1-sol" else "gpt-6.1-sol"
    fun modelLabel() = if (shoppingDraft(draft)) "Shopping · 6.1 Sol" else if (quick) "Quick · 6.1 Sol" else "6.1 Sol"
    private fun phoneTime() = java.time.OffsetDateTime.now().toString()
    private fun recordModelDisplay(reason: String) {
        modelDisplayHistory.put(j("at" to phoneTime(), "model" to uiModel(), "reason" to reason))
    }

    fun toggleQuick() {
        val before = uiModel()
        quick = !quick
        if (draft.isNotBlank() && before != uiModel()) recordModelDisplay("mode_toggle")
    }

    fun editDraft(text: String, origin: String = "chat_composer") {
        val before = uiModel()
        if (draft.isBlank() && text.isNotBlank()) {
            composeStartedAt = phoneTime()
            composeScreen = currentScreen()
            composeOrigin = origin
            uiModelAtCompose = uiModel()
            modelDisplayHistory = JSONArray()
            recordModelDisplay("compose_started")
        }
        draft = text
        if (text.isBlank()) { composeStartedAt = "";modelDisplayHistory = JSONArray() }
        else if (before != uiModel()) recordModelDisplay("draft_changed")
    }

    init {
        snapshot =
            try {
                val f = java.io.File(app.filesDir, "snapshot.json")
                JSONObject(
                    if (f.exists()) f.readText()
                    else app.assets.open("snapshot.json").bufferedReader().readText()
                )
            } catch (e: Exception) {
                JSONObject()
            }
        applyHousehold()
        activity = readCache("activity")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        messages = cachedMessages(conversation)
        viewModelScope.launch {
            while (true) {
                if (paired) refresh()
                delay(2500)
            }
        }
    }

    suspend fun api(
        route: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = true,
        endpoint: String = vault.endpoint,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(endpoint.trimEnd('/') + route).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 5000
                conn.readTimeout = 12000
                conn.instanceFollowRedirects = false
                if (auth) conn.setRequestProperty("Authorization", "Bearer " + vault.token)
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text =
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() } ?: "{}"
                val result = JSONObject(text)
                if (code !in 200..299) throw Exception(result.s("error", "Laptop returned $code"))
                result
            } finally {
                conn.disconnect()
            }
        }

    suspend fun refresh(force: Boolean = false) {
        try {
            activity = api("/api/activity")
            online = true
            writeCache("activity", activity.toString())
            syncAppNotes()
            syncCaptures()
            syncHealthPreferences()
            flushOutbox()
            flushTelemetry()
            if (conversation.isNotEmpty())
                messages = api("/api/messages?conversation=$conversation").a("messages").objects().also {
                    writeCache("messages-$conversation", JSONArray(it).toString())
                }
            val waitingOnLog = snapshot.a("captures").objects().any { it.s("status") == "interpreting" }
            if (force || System.currentTimeMillis() - lastSnapshot > (if (waitingOnLog) 4000 else 15000)) {
                val data = api("/api/snapshot")
                snapshot = data
                applyHousehold()
                lastSnapshot = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    java.io
                        .File(getApplication<Application>().filesDir, "snapshot.json")
                        .writeText(data.toString())
                }
            }
        } catch (e: Exception) {
            online = false
            if (force) error = e.message ?: "Laptop is unavailable"
        }
    }

    private fun loadAppNotes(): List<JSONObject> = try {
        prefs.getString("appNotes", "[]")?.let { JSONArray(it).objects() } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun persistAppNotes(notes: List<JSONObject>): Boolean {
        return try {
            val saved = JSONArray(notes).toString()
            if (!prefs.edit().putString("appNotes", saved).commit()) false
            else if (prefs.getString("appNotes", null) != saved) false
            else { appNotes = notes; true }
        } catch (_: Exception) { false }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val installedApkSha256: String by lazy {
        try {
            val app = getApplication<Application>()
            val digest = MessageDigest.getInstance("SHA-256")
            File(app.applicationInfo.sourceDir).inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    digest.update(chunk, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) { "" }
    }

    fun captureNoteScreen(view: View, route: String, title: String, recipeRevision: Int?) {
        pendingNoteScreenshot = null
        noteScreenshotReady = false
        noteScreenshotProblem = ""
        val app = getApplication<Application>()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val source = when {
            route == "health" -> "HealthInsights.kt"
            route == "fridge-check" -> "FridgeCheckScreen.kt"
            route == "food-log" -> "Capture.kt"
            route.startsWith("recipe:") || route == "tab:1" -> "RecipeScreens.kt"
            route == "tab:0" -> "HomeScreen.kt"
            route == "tab:2" -> "ChatScreen.kt"
            route == "tab:3" -> "PantryScreen.kt"
            route == "tab:4" -> "MarketScreen.kt"
            else -> "MainActivity.kt"
        }
        pendingNoteScreenContext = j(
            "route" to route,
            "title" to title,
            "sourceFile" to "app/android/app/src/main/java/app/mealgarden/$source",
            "capturedAt" to java.time.OffsetDateTime.now().toString(),
            "tab" to vmTabName(),
            "recipeRevision" to recipeRevision,
            "appVersionCode" to packageInfo.longVersionCode,
            "apkSha256" to installedApkSha256,
        )
        try {
            require(view.width > 0 && view.height > 0) { "screen has no size" }
            val scale = minOf(1f, 1080f / view.width)
            val width = (view.width * scale).toInt().coerceAtLeast(1)
            val height = (view.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap).apply { scale(scale, scale) })
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output))
                if (output.size() > 1024 * 1024) {
                    output.reset()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 58, output))
                }
                require(output.size() <= 1024 * 1024) { "screen image exceeded 1 MiB" }
                pendingNoteScreenshot = output.toByteArray()
                pendingNoteScreenshotWidth = width
                pendingNoteScreenshotHeight = height
                noteScreenshotReady = true
            } finally { bitmap.recycle() }
        } catch (e: Exception) {
            noteScreenshotProblem = e.message ?: "Screen image unavailable"
            pendingNoteScreenContext?.put("captureStatus", "capture_failed")
            pendingNoteScreenContext?.put("captureError", noteScreenshotProblem.take(200))
        }
    }

    private fun vmTabName() = listOf("Today", "Recipes", "Ask", "Kitchen", "More").getOrElse(tab) { "Unknown" }

    private fun noteScreenshotFile(id: String) = File(getApplication<Application>().filesDir, "note-screenshots/$id.jpg")

    fun saveAppNote(text: String, screen: String, target: String, x: Float?, y: Float?): Boolean {
        if (text.isBlank()) return false
        val id = UUID.randomUUID().toString()
        val context = pendingNoteScreenContext ?: j("route" to "unknown", "title" to screen, "captureStatus" to "unavailable")
        var screenshot: JSONObject? = null
        pendingNoteScreenshot?.let { bytes ->
            try {
                val file = noteScreenshotFile(id)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "$id.tmp")
                temp.writeBytes(bytes)
                check(temp.renameTo(file) && file.readBytes().contentEquals(bytes))
                screenshot = j(
                    "sha256" to sha256(bytes), "bytes" to bytes.size,
                    "width" to pendingNoteScreenshotWidth, "height" to pendingNoteScreenshotHeight,
                    "capturedAt" to context.s("capturedAt"),
                )
                context.put("captureStatus", "captured")
            } catch (e: Exception) {
                context.put("captureStatus", "storage_failed")
                context.put("captureError", e.message ?: "Could not store screenshot")
            }
        }
        if (pendingNoteScreenshot == null && context.s("captureStatus").isBlank()) context.put("captureStatus", "unavailable")
        val app = getApplication<Application>()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val note = j(
            "id" to id,
            "text" to text,
            "phoneTime" to java.time.OffsetDateTime.now().toString(),
            "screen" to screen,
            "screenContext" to context,
            "screenshot" to screenshot,
            "target" to target.trim(),
            "recipeId" to (selectedRecipe ?: ""),
            "position" to if (x != null && y != null) j("x" to x.toDouble(), "y" to y.toDouble()) else null,
            "device" to android.os.Build.MODEL,
            "appVersion" to (packageInfo.versionName ?: "unknown"),
            "appVersionCode" to packageInfo.longVersionCode,
            "apkSha256" to installedApkSha256,
            "synced" to false,
        )
        if (!persistAppNotes(listOf(note) + appNotes)) {
            noteScreenshotFile(id).delete()
            return false
        }
        pendingNoteScreenshot = null
        pendingNoteScreenContext = null
        notice = "Note saved on your phone${if (screenshot == null) " · no screenshot" else " · screen captured"}${if (online) " · syncing to laptop" else " · will sync when connected"}"
        viewModelScope.launch { syncAppNotes() }
        return true
    }

    private suspend fun syncAppNotes() {
        if (!paired || !online || syncingNotes) return
        syncingNotes = true
        try {
            for (note in appNotes.filter { !it.optBoolean("synced", false) }) {
                try {
                    val payload = JSONObject(note.toString())
                    val screenshot = note.optJSONObject("screenshot")
                    if (screenshot != null) {
                        val bytes = noteScreenshotFile(note.s("id")).readBytes()
                        check(sha256(bytes) == screenshot.s("sha256")) { "Phone screenshot checksum changed" }
                        payload.put("screenshotData", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                    val saved = api("/api/feedback", "POST", payload)
                    if (saved.s("id") != note.s("id") || saved.s("text") != note.s("text") ||
                        saved.s("apkSha256") != note.s("apkSha256") ||
                        saved.o("screenContext").s("route") != note.o("screenContext").s("route")) {
                        error = "Note copy did not match the phone draft. Kept locally for retry."
                        break
                    }
                    if (saved.optJSONObject("screenshot")?.s("sha256") != screenshot?.s("sha256")) {
                        error = "Laptop screen image did not match the phone copy. Kept locally for retry."
                        break
                    }
                    if (!persistAppNotes(appNotes.map { if (it.s("id") == note.s("id")) JSONObject(it.toString()).apply { put("synced", true) } else it })) {
                        error = "Note reached the laptop, but this phone could not update its sync status."
                        break
                    }
                } catch (e: Exception) {
                    error = "Note saved on this phone, but laptop sync failed: ${e.message ?: "Connection error"}"
                    break
                }
            }
        } finally { syncingNotes = false }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh(true) }
    }

    fun connect(address: String, code: String) {
        viewModelScope.launch {
            busy = true
            error = ""
            try {
                val url =
                    if (address.contains("://")) address.trim().trimEnd('/')
                    else "http://${address.trim().trimEnd('/')}"
                val uri = URI(url)
                require(
                    uri.scheme in listOf("http", "https") &&
                        uri.host != null &&
                        uri.userInfo == null &&
                        uri.query == null &&
                        uri.fragment == null &&
                        (uri.path.isNullOrEmpty() || uri.path == "/")
                ) {
                    "Enter a laptop address, such as http://100.x.x.x:4783"
                }
                val r =
                    api(
                        "/pair",
                        "POST",
                        j("code" to code.trim(), "name" to android.os.Build.MODEL),
                        false,
                        url,
                    )
                r.optJSONObject("household")?.let { snapshot = JSONObject(snapshot.toString()).put("household", it); applyHousehold() }
                vault.endpoint = url
                vault.token = r.s("token")
                paired = true
                openSettings = false
                refresh(true)
                notice = "Connected to $kitchenName"
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                api("/api/device", "DELETE")
            } catch (_: Exception) {}
            vault.token = ""
            paired = false
            online = false
            activity = JSONObject()
            notice = "Phone disconnected"
        }
    }

    fun chooseConversation(id: String) {
        conversation = id
        prefs.edit().putString("conversation", id).apply()
        messages = cachedMessages(id)
        openHistory = false
        tab = 2
        viewModelScope.launch { refresh() }
    }

    fun newConversation() {
        conversation = ""
        messages = emptyList()
        prefs.edit().remove("conversation").apply()
        openHistory = false
        tab = 2
    }

    fun ask(text: String, send: Boolean = false, origin: String = "app_action") {
        val sourceScreen = currentScreen()
        composeRecipeId = selectedRecipe ?: ""
        tab = 2
        selectedRecipe = null
        draft = text
        composeStartedAt = phoneTime()
        composeScreen = sourceScreen
        composeOrigin = origin
        uiModelAtCompose = uiModel()
        modelDisplayHistory = JSONArray()
        recordModelDisplay("app_action")
        if (send) send()
    }

    fun send(kind: String = "chat") {
        if (kind in listOf("shopping", "purchases", "connection") && !shoppingEnabled) {
            error = "This household has no shopping integration configured."
            return
        }
        if (!paired) {
            openSettings = true
            return
        }
        if (busy) return
        val text = draft
        if (kind == "chat" && text.isBlank()) return
        track("chat_send", "kind" to kind, "chars" to text.length, "origin" to composeOrigin, "hasPhoto" to attachment.isNotEmpty())
        val signature = "$kind|$conversation|$text|$attachment|$quick|$composeOrigin"
        if (signature != pendingSendSignature) {
            pendingSendSignature = signature
            pendingSendKey = UUID.randomUUID().toString()
            val isChat = kind == "chat"
            pendingSendClient = j(
                "origin" to if (isChat) composeOrigin else "${currentScreen().lowercase()}_$kind",
                "composeScreen" to if (isChat) composeScreen.ifEmpty { currentScreen() } else null,
                "sendScreen" to currentScreen(),
                "composeStartedAt" to if (isChat) composeStartedAt.ifEmpty { phoneTime() } else null,
                "clientSentAt" to phoneTime(),
                "uiModelAtCompose" to if (isChat) uiModelAtCompose.ifEmpty { uiModel() } else null,
                "uiModelAtSend" to if (isChat) uiModel() else null,
                "uiModeAtSend" to if (quick && kind == "chat") "quick" else "standard",
                "modelDisplayHistory" to if (isChat) modelDisplayHistory else null,
                "recipeId" to if (isChat) composeRecipeId else null,
                "device" to android.os.Build.MODEL,
                "appVersion" to appVersionName,
            )
        }
        viewModelScope.launch {
            busy = true
            error = ""
            try {
                val b =
                    j(
                        "kind" to kind,
                        "text" to text,
                        "requestKey" to pendingSendKey,
                        "conversationId" to if (kind == "chat") conversation else "",
                        "mode" to if (quick && kind == "chat") "quick" else "standard",
                        "attachmentIds" to
                            JSONArray().apply { if (attachment.isNotEmpty()) put(attachment) },
                        "client" to JSONObject(pendingSendClient?.toString() ?: "{}").apply { put("lastAttemptAt", phoneTime()) },
                    )
                val r = api("/api/jobs", "POST", b)
                pendingSendSignature = ""
                pendingSendKey = ""
                pendingSendClient = null
                conversation = r.s("conversation_id")
                prefs.edit().putString("conversation", conversation).apply()
                draft = ""
                composeStartedAt = ""
                composeRecipeId = ""
                modelDisplayHistory = JSONArray()
                attachment = ""
                attachmentName = ""
                tab = 2
                refresh(true)
            } catch (e: Exception) {
                error = e.message ?: "Could not send"
            } finally {
                busy = false
            }
        }
    }

    fun stop(id: String) {
        viewModelScope.launch {
            try {
                api("/api/cancel", "POST", j("jobId" to id))
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Could not stop task"
            }
        }
    }

    fun answer(id: String, answers: JSONObject? = null, decision: String? = null) {
        viewModelScope.launch {
            try {
                api(
                    "/api/answer",
                    "POST",
                    j("id" to id, "answers" to answers, "decision" to decision),
                )
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Could not answer"
            }
        }
    }

    private val pendingWrites = mutableMapOf<String, String>()

    private fun saveRecord(
        route: String,
        payload: JSONObject,
        success: String,
        onDone: () -> Unit,
    ) {
        if (busy) return
        val signature = route + payload.toString()
        val requestId = pendingWrites.getOrPut(signature) { UUID.randomUUID().toString() }
        payload.put("idempotencyKey", requestId)
        busy = true
        viewModelScope.launch {
            try {
                api(route, "POST", payload)
                pendingWrites.remove(signature)
                refresh(true)
                notice = success
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Could not save. Your note is still here; try again."
            } finally {
                busy = false
            }
        }
    }

    fun observation(
        item: String,
        note: String,
        location: String,
        state: String,
        onDone: () -> Unit,
    ) {
        saveRecord(
            "/api/observations",
            j("item" to item, "note" to note, "location" to location, "quantityState" to state),
            "Pantry note saved",
            onDone,
        )
    }

    // Outbox: small writes commit on the phone first and sync in order; idempotency keys make retries safe.
    var outbox by mutableStateOf(try { JSONArray(prefs.getString("outbox", "[]")).objects() } catch (_: Exception) { emptyList() })
        private set
    private var flushing = false

    private fun enqueueWrite(route: String, payload: JSONObject): Boolean {
        if (!payload.has("idempotencyKey")) payload.put("idempotencyKey", UUID.randomUUID().toString())
        val next = outbox + j("route" to route, "payload" to payload, "at" to phoneTime())
        if (!prefs.edit().putString("outbox", JSONArray(next).toString()).commit()) { error = "Could not save on this phone"; return false }
        outbox = next
        viewModelScope.launch { flushOutbox() }
        return true
    }

    private suspend fun flushOutbox() {
        if (!paired || flushing) return
        flushing = true
        try {
            while (outbox.isNotEmpty()) {
                val next = outbox.first()
                val result = try { api(next.s("route"), "POST", next.o("payload")) } catch (e: Exception) {
                    error = e.message ?: "Changes are waiting to sync"
                    break
                }
                val followups = if (next.s("route") == "/api/batches") batchDestinationWrites(next.o("payload"), result) else emptyList()
                val remaining = followups + outbox.drop(1)
                if (!prefs.edit().putString("outbox", JSONArray(remaining).toString()).commit()) {
                    error = "Could not save sync progress on this phone"
                    break
                }
                outbox = remaining
                lastSnapshot = 0L
            }
        } finally { flushing = false }
    }

    /** Pending reset marks by item, so taps show instantly before the laptop confirms. */
    fun pendingMark(item: String): String? = outbox.lastOrNull { it.s("route") == "/api/observations" && it.o("payload").s("item") == item }
        ?.o("payload")?.let { if (it.s("quantityState") == "none") "gone" else it.s("condition") }

    fun pendingLeftover(cookingId: String): String? = outbox.lastOrNull { it.s("route") == "/api/leftovers" && it.o("payload").s("cookingId") == cookingId }?.o("payload")?.s("state")

    /** Kitchen-reset check. "gone" means used up or not in the kitchen anymore. */
    fun markItem(item: String, state: String) {
        track("reset_mark", "item" to item, "state" to state)
        val note = when (state) {
            "looks_ok" -> "${personName} checked it during a kitchen reset: looks okay."
            "use_soon" -> "${personName} checked it during a kitchen reset: use soon."
            "discard" -> "${personName} checked it during a kitchen reset and tossed it."
            else -> "${personName} says it's used up or no longer in the kitchen."
        }
        enqueueWrite("/api/observations", j(
            "item" to item, "note" to note, "location" to "unknown",
            "quantityState" to if (state == "gone" || state == "discard") "none" else "unknown",
            "condition" to if (state == "gone") "unchecked" else state,
        ))
    }

    fun leftover(cookingId: String, state: String, servings: Int? = null) {
        track("leftover_mark", "cookingId" to cookingId, "state" to state)
        enqueueWrite("/api/leftovers", j("cookingId" to cookingId, "state" to state, "servings" to servings))
    }

    // Interaction timing log: every meaningful tap and screen change, flushed to the laptop in batches.
    private val telemetryFile get() = File(getApplication<Application>().filesDir, "telemetry.json")
    private val telemetry: MutableList<JSONObject> = try { JSONArray(telemetryFile.readText()).objects().toMutableList() } catch (_: Exception) { mutableListOf() }
    private var flushingTelemetry = false

    fun track(name: String, vararg props: Pair<String, Any?>) {
        telemetry.add(j("id" to UUID.randomUUID().toString(), "at" to phoneTime(), "name" to name, "props" to j(*props)))
        if (telemetry.size > 5000) telemetry.subList(0, telemetry.size - 5000).clear()
        val text = JSONArray(telemetry.toList()).toString()
        viewModelScope.launch(Dispatchers.IO) { try { telemetryFile.writeText(text) } catch (_: Exception) {} }
    }

    private suspend fun flushTelemetry() {
        if (!paired || flushingTelemetry) return
        // Timer alarms fire in a broadcast receiver; pick up what it recorded.
        val fires = try { JSONArray(prefs.getString("timerFires", "[]")).objects() } catch (_: Exception) { emptyList() }
        if (fires.isNotEmpty()) {
            prefs.edit().remove("timerFires").apply()
            fires.forEach {
                if (it.has("action")) track("timer_alarm_action", "key" to it.s("key"), "title" to it.s("title"), "action" to it.s("action"), "at" to it.optLong("firedAt"))
                else track("timer_fired", "key" to it.s("key"), "title" to it.s("title"), "deadline" to it.optLong("deadline"), "firedAt" to it.optLong("firedAt"), "lateMs" to (it.optLong("firedAt") - it.optLong("deadline")))
            }
        }
        if (telemetry.isEmpty()) return
        flushingTelemetry = true
        try {
            val batch = telemetry.take(400)
            api("/api/telemetry", "POST", j("events" to JSONArray(batch), "device" to android.os.Build.MODEL, "appVersion" to appVersionName))
            telemetry.removeAll(batch.toSet())
            val text = JSONArray(telemetry.toList()).toString()
            withContext(Dispatchers.IO) { try { telemetryFile.writeText(text) } catch (_: Exception) {} }
        } catch (_: Exception) {
        } finally { flushingTelemetry = false }
    }

    // Cooking timers live here so every screen can show them.
    var timers by mutableStateOf(try { JSONObject(prefs.getString("timers", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it) } } } catch (_: Exception) { emptyMap() })
        private set

    private fun saveTimers(next: Map<String, JSONObject>) {
        timers = next
        prefs.edit().putString("timers", JSONObject(next as Map<*, *>).toString()).apply()
    }

    /** The alarm screen edits the shared timer list (done, snooze); pick those changes up. */
    fun reloadTimers() {
        val saved = prefs.getString("timers", "{}") ?: "{}"
        if (saved != JSONObject(timers as Map<*, *>).toString())
            timers = try { JSONObject(saved).let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it) } } } catch (_: Exception) { timers }
    }

    fun startTimer(key: String, title: String, minutes: Int, detail: String = ""): Long {
        val deadline = System.currentTimeMillis() + minutes * 60000L
        saveTimers(timers + (key to j("title" to title, "deadline" to deadline, "minutes" to minutes, "detail" to detail)))
        track("timer_start", "key" to key, "title" to title, "minutes" to minutes)
        return deadline
    }

    fun stopTimer(key: String) {
        val t = timers[key] ?: return
        track("timer_stop", "key" to key, "title" to t.s("title"), "remainingMs" to (t.optLong("deadline") - System.currentTimeMillis()))
        saveTimers(timers - key)
    }

    /** Sends a chat question without leaving the current screen (e.g. mid-cook); the answer appears in Chat. */
    fun askInBackground(text: String, origin: String) {
        track("ask_background", "origin" to origin, "chars" to text.length)
        if (!paired) { error = "Connect to the laptop to ask"; return }
        viewModelScope.launch {
            try {
                api("/api/jobs", "POST", j(
                    "kind" to "chat", "text" to text, "requestKey" to UUID.randomUUID().toString(), "conversationId" to "",
                    "client" to j("origin" to origin, "sendScreen" to currentScreen(), "clientSentAt" to phoneTime(), "device" to android.os.Build.MODEL, "appVersion" to appVersionName, "recipeId" to (selectedRecipe ?: "")),
                ))
                notice = "Asked · the answer will be in Chat"
            } catch (e: Exception) {
                error = "Could not ask: ${e.message ?: "connection error"}"
            }
        }
    }

    // Offline cache: the latest activity (conversation list) and each conversation's messages live on the phone.
    private fun cacheFile(name: String) = File(getApplication<Application>().filesDir, "cache/$name.json")
    private fun readCache(name: String): String? = try { cacheFile(name).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
    private suspend fun writeCache(name: String, text: String) = withContext(Dispatchers.IO) {
        try { cacheFile(name).apply { parentFile?.mkdirs() }.writeText(text) } catch (_: Exception) {}
    }
    private fun cachedMessages(id: String): List<JSONObject> =
        if (id.isEmpty()) emptyList() else readCache("messages-$id")?.let { runCatching { JSONArray(it).objects() }.getOrNull() } ?: emptyList()

    /** Server-compiled tasks (kitchen-reset planning, kitchen interview); opens the resulting conversation. */
    fun startTask(kind: String, note: String = "") {
        track("task_start", "kind" to kind, "noteChars" to note.length)
        if (!paired) { openSettings = true; return }
        viewModelScope.launch {
            busy = true
            try {
                val r = api("/api/jobs", "POST", j(
                    "kind" to kind, "note" to note, "requestKey" to UUID.randomUUID().toString(),
                    "client" to j("origin" to kind, "sendScreen" to currentScreen(), "clientSentAt" to phoneTime(), "device" to android.os.Build.MODEL, "appVersion" to appVersionName),
                ))
                conversation = r.s("conversation_id")
                prefs.edit().putString("conversation", conversation).apply()
                openFridgeCheck = false
                selectedRecipe = null
                tab = 2
                refresh(true)
            } catch (e: Exception) {
                error = e.message ?: "Could not reach the laptop"
            } finally {
                busy = false
            }
        }
    }

    fun checkIngredient(item: String, condition: String, onDone: () -> Unit) {
        val previous = snapshot.a("observations").objects()
            .filter { it.s("item").equals(item, ignoreCase = true) }
            .maxByOrNull { it.s("createdAt") }
        val description = when (condition) {
            "looks_ok" -> "${personName} checked it today and reports it looks okay; storage and safety are not independently verified."
            "use_soon" -> "${personName} checked it today and wants to use it soon; storage and safety are not independently verified."
            else -> "${personName} checked it today and decided to discard it; do not count it as available."
        }
        saveRecord("/api/observations", j(
            "item" to item,
            "note" to (description + if (previous != null && condition != "discard") " Earlier report: ${previous.s("note").take(600)}" else ""),
            "location" to (previous?.s("location") ?: "unknown"),
            "quantityState" to if (condition == "discard") "none" else "unknown",
            "quantityEstimate" to previous?.opt("quantityEstimate"),
            "bestIfUsedBy" to previous?.s("bestIfUsedBy"),
            "preservationNeed" to if (condition == "discard") null else previous?.s("preservationNeed"),
            "condition" to condition,
        ), "Ingredient check saved", onDone)
    }

    /** Graph writes share the durable phone outbox with the older pantry marks. */
    fun graphWrite(route: String, payload: JSONObject, action: String): Boolean {
        track(action, "route" to route, "itemId" to payload.s("itemId"), "amount" to payload.opt("amount"), "condition" to payload.s("condition"))
        return enqueueWrite(route, payload)
    }

    fun graphPantry(): List<JSONObject> = snapshot.a("pantry").objects().map { original ->
        JSONObject(original.toString()).apply {
            outbox.filter { it.o("payload").s("itemId") == s("id") }.forEach { write ->
                val p = write.o("payload")
                when (write.s("route")) {
                    "/api/pantry/count" -> { put("balance", p.opt("amount")); put("basis", p.s("confidence", "known")) }
                    "/api/pantry/toss" -> { put("balance", 0); put("basis", "known") }
                    "/api/pantry/condition" -> put("condition", p.s("condition"))
                    "/api/pantry/transfer" -> if (p.isNull("amount")) {
                        put("location", p.s("toLocation"))
                        if (p.s("toLocation") == "freezer") put("typicalDays", JSONObject.NULL)
                    }
                }
            }
        }
    }

    fun graphAssumptions(): List<JSONObject> {
        val resolved = outbox.filter { it.s("route") == "/api/assumptions/resolve" }.flatMap { it.o("payload").a("ids").strings() }.toSet()
        return snapshot.a("assumptions").objects().filter { it.s("id") !in resolved }
    }

    fun resolveAssumptions(ids: List<String>, status: String) {
        if (ids.isNotEmpty()) graphWrite("/api/assumptions/resolve", j("ids" to JSONArray(ids), "status" to status), "assumptions_$status")
    }

    fun graphPreferences(): List<JSONObject> {
        val pending = outbox.filter { it.s("route") == "/api/preferences" }.map { it.o("payload") }
        return (snapshot.a("preferences").objects() + pending).asReversed().distinctBy { it.s("kind") + ":" + it.s("subject") }.asReversed()
    }

    fun changePreference(preference: JSONObject, statement: String?, stance: String?) {
        val saved = graphWrite("/api/preferences", j(
            "kind" to preference.s("kind"), "subject" to preference.s("subject"),
            "productId" to preference.s("product_id").ifEmpty { null }, "recipeId" to preference.s("recipe_id").ifEmpty { null },
            "statement" to (statement ?: "No current preference about ${preference.s("subject")}."),
            "stance" to (stance ?: "neutral"), "isHard" to (statement != null && preference.optInt("is_hard") == 1),
            "value" to if (statement != null && (stance == null || stance == preference.s("stance"))) preference.opt("value") else null,
            "source" to "stated", "confidence" to "known",
        ), if (statement == null) "preference_remove" else "preference_change")
        if (saved) {
            val related = graphAssumptions().filter { preference.s("id").isNotEmpty() && preference.s("id") in it.a("evidence").strings() }
            resolveAssumptions(related.map { it.s("id") }, "corrected")
        }
    }

    fun reaction(target: JSONObject, rating: Int, aspects: JSONObject): Boolean {
        return graphWrite("/api/reactions", JSONObject(target.toString()).put("rating", rating).put("aspects", aspects), "reaction_save")
    }

    fun cooked(recipe: JSONObject, note: String, portions: Int, destinations: JSONObject,
               weight: Double?, substitutions: JSONObject, sessionKey: String, rating: Int?, aspects: JSONObject, onDone: () -> Unit) {
        if (prefs.getBoolean("report-saved:$sessionKey", false)) return
        val batchId = prefs.getString("batch-id:$sessionKey", null) ?: UUID.randomUUID().toString()
        val ingredients = recipe.a("ingredients").objects().mapIndexed { index, ingredient ->
            val replacement = substitutions.s("$index")
            val grams = if (replacement.isNotBlank()) null else ingredientGrams(ingredient)?.times(portions.toDouble() / basePortions(recipe))
            val name = replacement.ifEmpty { ingredient.s("name") }
            val matches = snapshot.a("pantry").objects().filter { it.s("name").equals(name, ignoreCase = true) }.distinctBy { it.s("product_id") }
            val product = matches.singleOrNull()
            // No assumed density: grams only debit gram-based products. Unmatched ingredients default to grams in the domain.
            val amount = if (product == null || product.s("base_unit") == "g") grams else null
            j("name" to name, "grams" to grams, "amount" to amount, "productId" to product?.s("product_id"),
                "amountText" to if (replacement.isNotBlank()) replacement else ingredientAmount(prefs, recipe, ingredient, portions, "Exact"),
                "confidence" to "assumed")
        }
        val payload = j("madeAt" to phoneTime(), "id" to batchId, "recipeId" to recipe.s("id"), "recipeRevision" to recipe.optInt("revision"),
            "title" to recipe.s("title"), "recordedFrom" to "cook_mode", "portions" to portions,
            "yieldG" to weight, "yieldBasis" to if (weight != null) "measured" else "unknown",
            "ingredients" to JSONArray(ingredients), "location" to "fridge", "confidence" to "known",
            "substitutions" to substitutions, "portionCounts" to destinations, "idempotencyKey" to "cook-batch:$batchId")
        val writes = listOf(j("route" to "/api/cooking", "payload" to j("recipeId" to recipe.s("id"), "note" to note, "idempotencyKey" to "cook-report:$batchId"))) +
            listOf(j("route" to "/api/batches", "payload" to payload)) +
            if (rating != null) listOf(j("route" to "/api/reactions", "payload" to j("batchId" to batchId, "recipeId" to recipe.s("id"), "rating" to rating, "aspects" to aspects, "idempotencyKey" to "cook-reaction:$batchId"))) else emptyList()
        val next = outbox + writes
        if (!prefs.edit().putString("outbox", JSONArray(next).toString()).putString("batch-id:$sessionKey", batchId).putBoolean("report-saved:$sessionKey", true).commit()) {
            error = "Could not save on this phone"; return
        }
        outbox = next
        track("cook_report", "recipeId" to recipe.s("id"), "batchId" to batchId, "portions" to portions, "weightG" to weight)
        if (rating != null) track("reaction_save", "batchId" to batchId, "rating" to rating)
        notice = "Cooking report saved on this phone"
        onDone()
        viewModelScope.launch { flushOutbox() }
    }

    // The API's homemade unit is grams. Unknown yields remain unknown; never substitute portion counts for grams.
    private fun batchDestinationWrites(payload: JSONObject, result: JSONObject): List<JSONObject> {
        val weight = payload.optDouble("yieldG", Double.NaN).takeIf { it.isFinite() && it > 0 }
        val counts = payload.o("portionCounts")
        val total = payload.optInt("portions").takeIf { it > 0 } ?: return emptyList()
        val itemId = result.o("pantryItem").s("id").takeIf { it.isNotEmpty() } ?: return emptyList()
        val freezer = counts.optInt("freezer")
        val eaten = counts.optInt("eatenNow")
        val writes = mutableListOf<JSONObject>()
        fun add(route: String, p: JSONObject, suffix: String) {
            p.put("idempotencyKey", "cook-destination:${payload.s("id")}:$suffix")
            writes.add(j("route" to route, "payload" to p))
        }
        if (weight == null) {
            if (freezer == total) add("/api/pantry/transfer", j("itemId" to itemId, "amount" to null, "toLocation" to "freezer"), "freezer")
            if (eaten == total) add("/api/pantry/count", j("itemId" to itemId, "amount" to 0), "eaten")
            return writes
        }
        if (freezer > 0) add("/api/pantry/transfer", j("itemId" to itemId, "amount" to weight * freezer / total, "toLocation" to "freezer"), "freezer")
        if (eaten > 0) add("/api/pantry/count", j("itemId" to itemId, "amount" to weight * counts.optInt("fridge") / total,
            "confidence" to if (eaten == total) "known" else "assumed"), "eaten")
        return writes
    }

    fun upload(bytes: ByteArray) {
        viewModelScope.launch {
            busy = true
            try {
                val r =
                    api(
                        "/api/upload",
                        "POST",
                        j("data" to Base64.encodeToString(bytes, Base64.NO_WRAP)),
                    )
                attachment = r.s("id")
                attachmentName = "Photo attached"
                notice = "Photo ready to send"
            } catch (e: Exception) {
                error = e.message ?: "Photo upload failed"
            } finally {
                busy = false
            }
        }
    }

    fun recipe(id: String) = snapshot.a("recipes").objects().find { it.s("id") == id }

    private val appVersionName: String by lazy {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }

    // Food capture: phone storage first, laptop sync second, interpretation last.
    fun captureFile(id: String) = File(getApplication<Application>().filesDir, "captures/$id.jpg")

    private fun loadCaptures(): List<JSONObject> = try {
        prefs.getString("captures", "[]")?.let { JSONArray(it).objects() } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun persistCaptures(list: List<JSONObject>): Boolean = try {
        val saved = JSONArray(list).toString()
        if (prefs.edit().putString("captures", saved).commit() && prefs.getString("captures", null) == saved) {
            captures = list; true
        } else false
    } catch (_: Exception) { false }

    fun capturePhotos(capture: JSONObject): List<JSONObject> =
        if (capture.has("photos")) capture.a("photos").objects()
        else listOfNotNull(capture.optJSONObject("photo"))

    fun capturePhotoFile(capture: JSONObject, photo: JSONObject) = captureFile(photo.s("id", capture.s("id")))

    fun beginPhotoCapture(photo: File) = prepareCapturePhotos(listOf({ ImageDecoder.createSource(photo) }), temporaryPhoto = photo)

    fun beginPhotoCapture(uris: List<Uri>) = prepareCapturePhotos(uris.map { uri ->
        { ImageDecoder.createSource(getApplication<Application>().contentResolver, uri) }
    })

    fun addCapturePhoto(photo: File, captureId: String) = prepareCapturePhotos(
        listOf({ ImageDecoder.createSource(photo) }), captureId, photo,
    )

    fun addCapturePhotos(uris: List<Uri>, captureId: String) = prepareCapturePhotos(uris.map { uri ->
        { ImageDecoder.createSource(getApplication<Application>().contentResolver, uri) }
    }, captureId)

    private fun prepareCapturePhotos(sources: List<() -> ImageDecoder.Source>, captureId: String? = null, temporaryPhoto: File? = null) {
        if (sources.isEmpty()) return
        val previous = if (captureId != null) draftCapture?.takeIf { it.s("id") == captureId } else null
        if (capturePhotoBusy || (captureId != null && previous == null)) { temporaryPhoto?.delete(); return }
        val existing = previous?.let { capturePhotos(it) } ?: emptyList()
        if (existing.size + sources.size > 10) { error = "Use up to 10 photos per entry"; temporaryPhoto?.delete(); return }
        val id = previous?.s("id") ?: UUID.randomUUID().toString()
        val capturedAt = previous?.s("capturedAt") ?: phoneTime()
        val written = mutableListOf<File>()
        capturePhotoBusy = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    sources.map { source ->
                        val (bytes, width, height) = compressPhoto(source())
                        val photoId = UUID.randomUUID().toString()
                        val file = captureFile(photoId)
                        file.parentFile?.mkdirs()
                        val temp = File(file.parentFile, "$photoId.tmp")
                        written.add(temp)
                        written.add(file)
                        temp.writeBytes(bytes)
                        check(temp.renameTo(file) && file.readBytes().contentEquals(bytes)) { "Could not store the photo" }
                        j("id" to photoId, "sha256" to sha256(bytes), "bytes" to bytes.size, "width" to width, "height" to height)
                    }
                }
                val photos = existing + saved
                check(photos.sumOf { it.optLong("bytes") } <= 24L * 1024 * 1024) { "Photos must total at most 24 MB per entry" }
                // A dismissed draft must not be revived when an image finishes decoding.
                if (captureId != null && draftCapture?.s("id") != captureId) {
                    withContext(Dispatchers.IO) { written.forEach { it.delete() } }
                } else {
                    draftCapture = j("id" to id, "capturedAt" to capturedAt, "photos" to JSONArray(photos))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { written.forEach { it.delete() } }
                error = "Could not save that photo: ${e.message ?: "unknown error"}"
            } finally {
                withContext(Dispatchers.IO) { temporaryPhoto?.delete() }
                capturePhotoBusy = false
            }
        }
    }

    fun beginTextCapture() {
        if (capturePhotoBusy) return
        draftCapture = j("id" to UUID.randomUUID().toString(), "capturedAt" to phoneTime(), "photos" to JSONArray())
    }

    fun removeCapturePhoto(photo: JSONObject) {
        if (capturePhotoBusy) return
        val draft = draftCapture ?: return
        capturePhotoFile(draft, photo).delete()
        draftCapture = JSONObject(draft.toString()).apply {
            put("photos", JSONArray(capturePhotos(draft).filter { it !== photo }))
            remove("photo")
        }
    }

    fun discardCapture() {
        draftCapture?.let { draft -> capturePhotos(draft).forEach { capturePhotoFile(draft, it).delete() } }
        draftCapture = null
    }

    fun saveCapture(kind: String, note: String): Boolean {
        val draft = draftCapture ?: return false
        if (capturePhotoBusy) return false
        val photos = capturePhotos(draft)
        if (photos.isEmpty() && note.isBlank()) { error = "Add a photo or a few words"; return false }
        val record = j(
            "id" to draft.s("id"), "kind" to kind, "note" to note.trim(),
            "capturedAt" to draft.s("capturedAt"), "timeZone" to java.util.TimeZone.getDefault().id,
            "photos" to JSONArray(photos), "synced" to false,
        )
        if (!persistCaptures(listOf(record) + captures)) { error = "Could not save on this phone. Try again."; return false }
        draftCapture = null
        notice = if (online) "Logged · reading it on the laptop" else "Logged on your phone · will sync when connected"
        viewModelScope.launch { syncCaptures() }
        return true
    }

    private suspend fun syncCaptures() {
        if (!paired || !online || syncingCaptures) return
        syncingCaptures = true
        try {
            for (capture in captures.filter { !it.optBoolean("synced", false) }.reversed()) {
                try {
                    val id = capture.s("id")
                    val photos = capturePhotos(capture)
                    val payload = j(
                        "id" to id, "kind" to capture.s("kind"), "note" to capture.s("note"),
                        "phoneTime" to capture.s("capturedAt"), "timeZone" to capture.s("timeZone"),
                        "device" to android.os.Build.MODEL, "appVersion" to appVersionName,
                    )
                    payload.put("photos", JSONArray(withContext(Dispatchers.IO) {
                        photos.map { photo ->
                            val bytes = capturePhotoFile(capture, photo).readBytes()
                            check(sha256(bytes) == photo.s("sha256")) { "Phone photo checksum changed" }
                            j("imageData" to Base64.encodeToString(bytes, Base64.NO_WRAP), "imageSha256" to photo.s("sha256"),
                                "width" to photo.optInt("width"), "height" to photo.optInt("height"))
                        }
                    }))
                    // Older companions must reject this route rather than silently saving only the note.
                    val saved = api("/api/captures/multi", "POST", payload)
                    if (saved.s("id") != id || saved.a("mediaList").objects().map { it.s("sha256") } != photos.map { it.s("sha256") }) {
                        error = "Laptop copy of a food log did not match. Kept on the phone for retry."
                        break
                    }
                    persistCaptures(captures.map { if (it.s("id") == id) JSONObject(it.toString()).apply { put("synced", true) } else it })
                    lastSnapshot = 0L
                } catch (e: Exception) {
                    error = "Food log saved on this phone; laptop sync failed: ${e.message ?: "connection error"}"
                    break
                }
            }
        } finally { syncingCaptures = false }
    }

    /** Local captures (with photos) merged with the laptop's interpretations, newest first. */
    fun foodLog(): List<JSONObject> {
        val server = snapshot.a("captures").objects().associateBy { it.s("id") }
        val local = captures.map { c ->
            JSONObject(c.toString()).apply { server[c.s("id")]?.let { put("server", it) } }
        }
        val remoteOnly = server.values.filter { s -> captures.none { it.s("id") == s.s("id") } }
            .map { j("id" to it.s("id"), "kind" to it.s("kind"), "note" to it.s("note"), "capturedAt" to it.s("capturedAt"), "synced" to true, "server" to it) }
        return (local + remoteOnly).sortedByDescending { it.s("capturedAt") }
    }

    val healthPreferences: JSONObject
        get() = pendingHealthPreferences ?: snapshot.optJSONObject("healthPreferences")
            ?: JSONObject("{\"version\":1,\"priorities\":[\"longevity\",\"energy\"],\"targets\":{}}")

    var pendingHealthPreferences by mutableStateOf(runCatching { prefs.getString("health-preferences-pending", null)?.let { JSONObject(it) } }.getOrNull())
        private set

    fun saveHealthPreferences(value: JSONObject): Boolean {
        if (!prefs.edit().putString("health-preferences-pending", value.toString()).commit()) {
            error = "Could not save goals on this phone"; return false
        }
        pendingHealthPreferences = value
        notice = if (paired) "Goals saved · sync when connected" else "Goals saved on this phone"
        lastSnapshot = 0L
        viewModelScope.launch { if (paired) refresh(true) }
        return true
    }

    private suspend fun syncHealthPreferences() {
        val pending = pendingHealthPreferences ?: return
        val result = api("/api/health/preferences", "POST", pending)
        // Do not erase a newer edit made while this request was running.
        if (pendingHealthPreferences?.toString() == pending.toString()) {
            snapshot = JSONObject(snapshot.toString()).put("healthPreferences", result)
            prefs.edit().remove("health-preferences-pending").commit()
            pendingHealthPreferences = null
            lastSnapshot = 0L
        }
    }

    fun addCaptureDetail(id: String, text: String, onDone: () -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                api("/api/captures/detail", "POST", j("id" to id, "detailId" to UUID.randomUUID().toString(), "text" to text.trim(), "phoneTime" to phoneTime()))
                lastSnapshot = 0L
                refresh(true)
                notice = "Added · updating the estimate"
                onDone()
            } catch (e: Exception) {
                error = "Could not reach the laptop: ${e.message ?: "connection error"}. Your text is still here."
            }
        }
    }

    fun retryInterpretation(id: String) {
        viewModelScope.launch {
            try {
                api("/api/captures/interpret", "POST", j("id" to id))
                lastSnapshot = 0L
                refresh(true)
            } catch (e: Exception) {
                error = e.message ?: "Could not retry"
            }
        }
    }
}
