package app.mealgarden

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** A finished cooking timer: what it was for, so several at once stay distinguishable. */
data class FiredTimer(val key: String, val title: String, val detail: String)

/**
 * Full-screen timer alarm. Shown over the lock screen, over other apps (when "display over other apps" is allowed),
 * and inside Meal Garden. It chimes and vibrates until every finished timer is cleared or snoozed.
 */
class TimerAlarmActivity : ComponentActivity() {
    private val fired = mutableStateListOf<FiredTimer>()
    private var chime: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        add(intent)
        setContent {
            // Chime and vibrate every few seconds for up to two minutes, then stay on screen silently.
            LaunchedEffect(Unit) {
                repeat(40) {
                    if (fired.isEmpty()) return@LaunchedEffect
                    ring()
                    delay(3000)
                }
            }
            GardenTheme {
                Column(
                    Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(14.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    fired.toList().forEach { timer ->
                        Column(
                            Modifier.fillMaxWidth().clip(GardenShape.Card).background(CardSurface).padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(62.dp).clip(CircleShape).background(AmberLight), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Timer, "Timer done", Modifier.size(31.dp), tint = Forest)
                            }
                            Text(timer.title, style = GardenType.Title, textAlign = TextAlign.Center)
                            if (timer.detail.isNotBlank()) Text(timer.detail, style = GardenType.Body, color = Muted, textAlign = TextAlign.Center)
                            GardenPrimaryButton("Done", { finish(timer, 0) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), icon = Icons.Outlined.Check)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 5).forEach { minutes ->
                                    GardenQuietButton("+$minutes min", { finish(timer, minutes) },
                                        modifier = Modifier.weight(1f), icon = Icons.Outlined.Timer)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        startActivity(Intent(this@TimerAlarmActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }) { Text("Open Meal Garden", color = Forest) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        add(intent)
    }

    private fun add(intent: Intent?) {
        val key = intent?.getStringExtra("key") ?: return
        if (fired.none { it.key == key }) fired.add(FiredTimer(key, intent.getStringExtra("title") ?: "Timer", intent.getStringExtra("detail") ?: ""))
        // This screen is the alert now; the notification only exists to launch it from the lock screen.
        getSystemService(android.app.NotificationManager::class.java)?.cancel(key.hashCode())
    }

    /** Clear a finished timer, or snooze it by [extraMinutes]. Both update the shared timer list the app reads. */
    private fun finish(timer: FiredTimer, extraMinutes: Int) {
        val prefs = getSharedPreferences("garden", Context.MODE_PRIVATE)
        val timers = try { JSONObject(prefs.getString("timers", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (extraMinutes > 0) {
            val deadline = System.currentTimeMillis() + extraMinutes * 60000L
            timers.put(timer.key, (timers.optJSONObject(timer.key) ?: JSONObject().put("title", timer.title).put("detail", timer.detail)).put("deadline", deadline))
            scheduleTimer(this, timer.key, timer.title, deadline, timer.detail)
        } else timers.remove(timer.key)
        val actions = try { JSONArray(prefs.getString("timerFires", "[]")) } catch (_: Exception) { JSONArray() }
        actions.put(JSONObject().put("key", timer.key).put("title", timer.title).put("action", if (extraMinutes > 0) "snooze_$extraMinutes" else "done").put("firedAt", System.currentTimeMillis()))
        prefs.edit().putString("timers", timers.toString()).putString("timerFires", actions.toString()).apply()
        getSystemService(android.app.NotificationManager::class.java)?.cancel(timer.key.hashCode())
        fired.remove(timer)
        if (fired.isEmpty()) { stopChime(); finishAndRemoveTask() }
    }

    private fun ring() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 180, 350), -1))
        } catch (_: Exception) {}
        try {
            stopChime()
            val samples = chimeSamples()
            chime = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build().apply { write(samples, 0, samples.size); play() }
        } catch (_: Exception) {}
    }

    private fun stopChime() {
        try { chime?.stop(); chime?.release() } catch (_: Exception) {}
        chime = null
    }

    override fun onDestroy() {
        stopChime()
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_RATE = 44100

        /** A soft two-note kitchen chime (G5 then C6), each note fading out; pleasant, but on the alarm stream. */
        fun chimeSamples(): ShortArray {
            val total = (SAMPLE_RATE * 1.6).toInt()
            val out = ShortArray(total)
            fun note(frequency: Double, startSeconds: Double) {
                val start = (startSeconds * SAMPLE_RATE).toInt()
                for (i in start until total) {
                    val t = (i - start) / SAMPLE_RATE.toDouble()
                    val envelope = exp(-3.2 * t) * minOf(1.0, t * 200)
                    val value = (sin(2 * PI * frequency * t) + 0.3 * sin(4 * PI * frequency * t)) * envelope * 9000
                    out[i] = (out[i] + value).toInt().coerceIn(-32000, 32000).toShort()
                }
            }
            note(783.99, 0.0)
            note(1046.50, 0.38)
            return out
        }
    }
}
