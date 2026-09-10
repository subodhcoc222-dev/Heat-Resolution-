package com.desk.sentry

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FirebaseManager {

    private const val PREFS_NAME = "DeskSentryCloudPrefs"
    private const val KEY_DEVICE_ID = "paired_device_id"
    private const val FIXED_DEVICE_ID = "349806"

    private val db = FirebaseDatabase.getInstance()

    // Self-Healing Live State Tracking
    @Volatile var isCloudConnected = false
    @Volatile var lastSuccessfulConnectionMs = System.currentTimeMillis()

    fun getOrGenerateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_ID, FIXED_DEVICE_ID).apply()
        return FIXED_DEVICE_ID
    }

    // Firebase Internal Socket Health Listener
    fun setupConnectionMonitoring(context: Context) {
        val connectedRef = db.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                isCloudConnected = connected
                if (connected) {
                    lastSuccessfulConnectionMs = System.currentTimeMillis()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // Hard Socket Re-boot Engine
    fun forceReconnectFirebase(context: Context) {
        try {
            db.goOffline()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                db.goOnline()
            }, 300)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pushHeartbeatAndPower(context: Context, isCharging: Boolean, batteryPct: Int) {
        val deviceId = getOrGenerateDeviceId(context)
        val ref = db.getReference("desk_sentry").child(deviceId)

        val updates = mapOf(
            "last_heartbeat" to ServerValue.TIMESTAMP,
            "is_charging" to isCharging,
            "battery_level" to batteryPct,
            "status" to "ONLINE"
        )
        ref.updateChildren(updates)
    }

    fun logSecurityEvent(context: Context, eventType: String, detail: String) {
        val deviceId = getOrGenerateDeviceId(context)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())

        val eventRef = db.getReference("desk_sentry")
            .child(deviceId)
            .child("security_logs")
            .child(todayKey)
            .push()

        val data = mapOf(
            "time" to timeStr,
            "type" to eventType,
            "detail" to detail,
            "timestamp" to ServerValue.TIMESTAMP
        )
        eventRef.setValue(data)
    }

    fun syncDayEvents(context: Context, dateKey: String, jsonString: String) {
        val deviceId = getOrGenerateDeviceId(context)
        db.getReference("desk_sentry")
            .child(deviceId)
            .child("events")
            .child(dateKey)
            .setValue(jsonString)
    }

    fun syncAvailableDates(context: Context, dates: Set<String>) {
        val deviceId = getOrGenerateDeviceId(context)
        db.getReference("desk_sentry")
            .child(deviceId)
            .child("available_dates")
            .setValue(dates.toList())
    }

    fun listenForSnapshotCommands(context: Context, onTakeSnapshot: () -> Unit) {
        val deviceId = getOrGenerateDeviceId(context)
        val cmdRef = db.getReference("desk_sentry").child(deviceId).child("commands").child("request_snap")

        cmdRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requested = snapshot.getValue(Boolean::class.java) ?: false
                if (requested) {
                    onTakeSnapshot()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun uploadSnapshot(context: Context, bitmap: Bitmap, onComplete: () -> Unit = {}) {
        val deviceId = getOrGenerateDeviceId(context)

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 45, baos)
        val imageBytes = baos.toByteArray()
        val base64String = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val updates = mapOf(
            "latest_snapshot_base64" to base64String,
            "latest_snap_time" to ServerValue.TIMESTAMP,
            "commands/request_snap" to false
        )

        db.getReference("desk_sentry").child(deviceId).updateChildren(updates).addOnCompleteListener {
            onComplete()
        }
    }
}
