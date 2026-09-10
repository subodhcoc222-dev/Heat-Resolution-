package com.desk.sentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Calendar

class SentryService : Service() {

    private val CHANNEL_ID = "DeskSentryServiceChannel"
    private val NOTIFICATION_ID = 9001
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var bgMediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var screenReceiver: BroadcastReceiver? = null
    private var powerReceiver: BroadcastReceiver? = null

    private var heartbeatTimerTicks = 0

    // Self-Healing Watchdog Timers
    private var socketDisconnectedSeconds = 0
    private var preventive15MinCounterSeconds = 0

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        var isMainActivityVisible = false
        var isEventsActivityVisible = false
        var lastAppActiveTimestamp = System.currentTimeMillis()

        val isAppInForeground: Boolean
            get() = isMainActivityVisible || isEventsActivityVisible
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "DeskSentry::BgLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        // Initialize Cloud Health Monitoring
        FirebaseManager.setupConnectionMonitoring(this)

        registerScreenLockMonitor()
        registerPowerMonitor()
        initBackgroundAlarm()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("24/7 Desk Guard Active"))
        startWatchdogLoop()

        // Push immediate initial state to Firebase
        pushLiveHeartbeat()
    }

    private fun registerScreenLockMonitor() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    val activeSlot = getActiveStudySlot()
                    val isPreSlotActive = getPreSlotWindowInfo()
                    if (activeSlot != -1 || isPreSlotActive) {
                        wakeScreenAndShowApp()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerPowerMonitor() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val isCharging = action == Intent.ACTION_POWER_CONNECTED
                val batteryPct = getBatteryPercentage()

                // Self-Healing trigger: Force revive socket when power state changes
                FirebaseManager.forceReconnectFirebase(this@SentryService)
                FirebaseManager.pushHeartbeatAndPower(this@SentryService, isCharging, batteryPct)
                
                val detail = if (isCharging) "Charger Connected at $batteryPct%" else "Charger Unplugged at $batteryPct%"
                FirebaseManager.logSecurityEvent(
                    this@SentryService,
                    if (isCharging) "CHARGER_CONNECTED" else "CHARGER_UNPLUGGED",
                    detail
                )
            }
        }
        registerReceiver(powerReceiver, filter)
    }

    private fun checkChargingStatus(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    private fun pushLiveHeartbeat() {
        try {
            val isCharging = checkChargingStatus()
            val batteryPct = getBatteryPercentage()
            FirebaseManager.pushHeartbeatAndPower(this, isCharging, batteryPct)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenAndShowApp() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DeskSentry::ForceWake"
            )
            screenWakeLock.acquire(3000)

            if (!isAppInForeground) {
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundSafely()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun initBackgroundAlarm() {
        try {
            val customUriStr = prefs.getString("custom_alarm_uri", null)
            val alertUri = if (customUriStr != null) {
                Uri.parse(customUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            bgMediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desk Sentry Persistent Guard",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Enforces study sessions and sounds alarms."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Sentry (No-Negotiation Mode)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun startWatchdogLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val isBgGuardEnabled = prefs.getBoolean("bg_guard_enabled", true)
                if (!isBgGuardEnabled) {
                    stopForegroundSafely()
                    stopSelf()
                    return
                }

                // Check for Scheduled Auto-Arm execution
                val targetDateMs = prefs.getLong("auto_arm_target_date_ms", 0L)
                val isSentryArmed = prefs.getBoolean("sentry_armed", false)
                if (!isSentryArmed && targetDateMs > 0L) {
                    if (System.currentTimeMillis() >= targetDateMs) {
                        prefs.edit().remove("auto_arm_target_date_ms").putBoolean("sentry_armed", true).apply()
                        wakeScreenAndShowApp()
                    }
                }

                val activeSlot = getActiveStudySlot()
                val isPreSlotActive = getPreSlotWindowInfo()
                val timeSinceActive = System.currentTimeMillis() - lastAppActiveTimestamp

                // Auto Wakeup 11 minutes prior to slot or while slot is active
                if ((activeSlot != -1 || isPreSlotActive) && !isAppInForeground && timeSinceActive > 2500L) {
                    wakeScreenAndShowApp()
                }

                // 10-Second Periodic Cloud Heartbeat
                heartbeatTimerTicks++
                if (heartbeatTimerTicks >= 10) {
                    heartbeatTimerTicks = 0
                    pushLiveHeartbeat()
                }

                // ==========================================
                // SMART SELF-HEALING ENGINE (CAMERA APP)
                // ==========================================
                if (!FirebaseManager.isCloudConnected) {
                    socketDisconnectedSeconds++
                    // If socket is disconnected for 30 consecutive seconds, auto-heal silently
                    if (socketDisconnectedSeconds >= 30) {
                        socketDisconnectedSeconds = 0
                        FirebaseManager.forceReconnectFirebase(this@SentryService)
                        pushLiveHeartbeat()
                    }
                } else {
                    socketDisconnectedSeconds = 0
                }

                // 15-Minute Periodic Silent Preventive Re-sync (900 seconds)
                preventive15MinCounterSeconds++
                if (preventive15MinCounterSeconds >= 900) {
                    preventive15MinCounterSeconds = 0
                    if (!FirebaseManager.isCloudConnected) {
                        FirebaseManager.forceReconnectFirebase(this@SentryService)
                    }
                    pushLiveHeartbeat()
                }

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun getActiveStudySlot(): Int {
        val isSentryArmed = prefs.getBoolean("sentry_armed", false)
        val isAlwaysActive = prefs.getBoolean("always_active_mode", false)

        if (!isSentryArmed) return -1
        if (isAlwaysActive) return 1

        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val defaultTimes = arrayOf(
            Pair(5, 9), Pair(10, 14), Pair(15, 18), Pair(19, 21), Pair(21, 23)
        )

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val isDayActive = prefs.getBoolean("slot_${i}_day_$currentDayOfWeek", currentDayOfWeek != Calendar.SUNDAY)
                if (isDayActive) {
                    val idx = i - 1
                    val start = prefs.getInt("slot_${i}_start_h", defaultTimes[idx].first) * 60 + prefs.getInt("slot_${i}_start_m", 0)
                    val end = prefs.getInt("slot_${i}_end_h", defaultTimes[idx].second) * 60 + prefs.getInt("slot_${i}_end_m", if (idx == 4) 30 else 0)

                    if (currentMinutes in start until end) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun getPreSlotWindowInfo(): Boolean {
        val isSentryArmed = prefs.getBoolean("sentry_armed", false)
        val isAlwaysActive = prefs.getBoolean("always_active_mode", false)
        if (!isSentryArmed || isAlwaysActive) return false

        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val defaultTimes = arrayOf(
            Pair(5, 9), Pair(10, 14), Pair(15, 18), Pair(19, 21), Pair(21, 23)
        )

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val isDayActive = prefs.getBoolean("slot_${i}_day_$currentDayOfWeek", currentDayOfWeek != Calendar.SUNDAY)
                if (isDayActive) {
                    val idx = i - 1
                    val start = prefs.getInt("slot_${i}_start_h", defaultTimes[idx].first) * 60 + prefs.getInt("slot_${i}_start_m", 0)
                    val diff = start - currentMinutes
                    if (diff in 1..11) return true
                }
            }
        }
        return false
    }

    private fun stopForegroundSafely() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver)
                screenReceiver = null
            }
            if (powerReceiver != null) {
                unregisterReceiver(powerReceiver)
                powerReceiver = null
            }
            if (bgMediaPlayer?.isPlaying == true) {
                bgMediaPlayer?.stop()
                bgMediaPlayer?.release()
                bgMediaPlayer = null
            }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
