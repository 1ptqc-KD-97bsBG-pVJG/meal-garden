@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.mealgarden

import android.app.*
import android.content.*
import android.provider.Settings
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
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import kotlin.math.*
import kotlinx.coroutines.delay

@Composable
fun TimerControl(
    vm: GardenModel,
    key: String,
    title: String,
    minutes: Int,
    detail: String = "",
    askNotification: () -> Unit,
) {
    val context = LocalContext.current
    val deadline = vm.timers[key]?.optLong("deadline") ?: 0L
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (deadline > 0) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remaining = ((deadline - tick + 999) / 1000).coerceAtLeast(0)
    val running = deadline > 0 && remaining > 0
    val finished = deadline > 0 && !running
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                if (running || finished) {
                    cancelTimer(context, key)
                    vm.stopTimer(key)
                } else {
                    askNotification()
                    scheduleTimer(context, key, title, vm.startTimer(key, title, minutes, detail), detail)
                }
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "${remaining/60}:${(remaining%60).toString().padStart(2,'0')} · Cancel"
                else if (finished) "Timer done · Reset" else "Start $minutes min timer"
            )
        }
    }
    // A full-screen alarm over other apps needs two one-time permissions; offer whichever is missing.
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val needsFullScreen = android.os.Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
    val needsOverlay = !Settings.canDrawOverlays(context)
    if (deadline > 0 && (needsFullScreen || needsOverlay))
        TextButton(
            onClick = {
                val action = if (needsOverlay) Settings.ACTION_MANAGE_OVERLAY_PERMISSION else Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                try { context.startActivity(Intent(action, android.net.Uri.parse("package:${context.packageName}"))) } catch (_: Exception) {}
            }
        ) {
            Text(if (needsOverlay) "Let timers show over other apps" else "Let timers show on the lock screen", fontSize = 12.sp)
        }
}

fun timerIntent(context: Context, key: String, title: String = "", deadline: Long = 0L, detail: String = ""): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        Intent(context, TimerReceiver::class.java).putExtra("title", title).putExtra("key", key).putExtra("deadline", deadline).putExtra("detail", detail),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

fun alarmScreenIntent(context: Context, key: String, title: String, detail: String): Intent =
    Intent(context, TimerAlarmActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("key", key).putExtra("title", title).putExtra("detail", detail)

fun scheduleTimer(context: Context, key: String, title: String, deadline: Long, detail: String = "") {
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pending = timerIntent(context, key, title, deadline, detail)
    // setAlarmClock is Android's most reliable timing: exact, and exempt from battery deferral.
    if (alarm.canScheduleExactAlarms()) {
        val show = PendingIntent.getActivity(context, key.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(deadline, show), pending)
    } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
}

fun cancelTimer(context: Context, key: String) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(
        timerIntent(context, key)
    )
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // An alarm-style channel: rings on the alarm stream and vibrates, so it is hard to miss while cooking.
        nm.createNotificationChannel(
            NotificationChannel("cooking-alarm", "Cooking timer alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            }
        )
        // Record when the alarm actually fired so lateness can be measured.
        val prefs = context.getSharedPreferences("garden", Context.MODE_PRIVATE)
        try {
            val fires = org.json.JSONArray(prefs.getString("timerFires", "[]"))
            fires.put(org.json.JSONObject().put("key", intent.getStringExtra("key")).put("title", intent.getStringExtra("title"))
                .put("deadline", intent.getLongExtra("deadline", 0L)).put("firedAt", System.currentTimeMillis()))
            prefs.edit().putString("timerFires", fires.toString()).apply()
        } catch (_: Exception) {}
        val key = intent.getStringExtra("key") ?: ""
        val screen = alarmScreenIntent(context, key, intent.getStringExtra("title") ?: "Timer", intent.getStringExtra("detail") ?: "")
        val open = PendingIntent.getActivity(context, key.hashCode(), screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Take over the screen directly when allowed (app in front, or "display over other apps" granted).
        try { context.startActivity(screen) } catch (_: Exception) {}
        try {
            nm.notify(
                (intent.getStringExtra("key") ?: "").hashCode(),
                NotificationCompat.Builder(context, "cooking-alarm")
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setSmallIcon(app.mealgarden.R.drawable.ic_garden)
                    .setContentTitle("Timer done")
                    .setContentText(intent.getStringExtra("title"))
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        } catch (_: SecurityException) {}
    }
}


/** Short alarm for a timer finishing while the app is open: alarm-stream sound plus vibration. */
fun ringTimerAlarm(context: Context) {
    try {
        context.getSystemService(android.os.Vibrator::class.java)
            ?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 800), -1))
    } catch (_: Exception) {}
    try {
        val ringtone = android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)) ?: return
        ringtone.audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
        ringtone.play()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { ringtone.stop() } catch (_: Exception) {} }, 4500)
    } catch (_: Exception) {}
}

/** Running and finished timers, visible on every screen. Tap a finished timer to clear it. */
@Composable
fun TimerDock(vm: GardenModel) {
    if (vm.timers.isEmpty()) return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); vm.reloadTimers(); delay(500) } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        vm.timers.entries.sortedBy { it.value.optLong("deadline") }.forEach { (key, t) ->
            val remaining = ((t.optLong("deadline") - now + 999) / 1000).coerceAtLeast(0)
            val done = remaining == 0L
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (done) Clay else Forest)
                    .clickable {
                        if (done) {
                            cancelTimer(context, key); vm.stopTimer(key)
                            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(key.hashCode())
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (done) Icons.Outlined.NotificationsActive else Icons.Outlined.Timer, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (done) "${t.s("title")} · done" else "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}  ${t.s("title")}",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                if (done) { Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.Close, "Clear", Modifier.size(16.dp), tint = Color.White) }
            }
        }
    }
}
