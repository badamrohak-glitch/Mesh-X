package com.meshx.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.meshx.MainActivity
import com.meshx.R
import com.meshx.ble.BleScanner
import com.meshx.bridge.BridgeEventEmitter
import org.json.JSONObject

/**
 * User-controlled foreground service for keeping local BLE discovery active while MESH-X is backgrounded.
 * Displays an ongoing low-priority notification and respects Android 14+ connectedDevice FGS rules.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServiceAndCleanup(userRequested = true)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        setBackgroundEnabled(this, true)
        setPendingResume(this, false)

        BleScanner.initialize(applicationContext)
        val advertisingRequested = BleScanner.startAdvertising()
        val scanningRequested = BleScanner.startScanning(lowPowerMode = true)
        MeshMaintenanceJobService.schedule(applicationContext)

        val bleStatus = BleScanner.getCapabilities()
        val scanning = bleStatus.optBoolean("scanning", false)
        val advertising = bleStatus.optBoolean("advertising", false)

        val startupError = when {
            !scanningRequested -> "Unable to start BLE scanning."
            !advertisingRequested -> "Unable to start BLE advertising."
            else -> null
        }

        emitBackgroundState(
            running = true,
            reason = if (startupError == null) "started" else "ble_start_failed",
            scanning = scanning,
            advertising = advertising,
            error = startupError,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        BleScanner.stopScanning()
        BleScanner.stopAdvertising()
        emitBackgroundState(running = false, reason = "stopped")
        super.onDestroy()
    }

    private fun stopServiceAndCleanup(userRequested: Boolean) {
        if (userRequested) {
            setBackgroundEnabled(this, false)
            setPendingResume(this, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MESH-X Local Discovery",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown when user-enabled background BLE discovery is active."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MESH-X BLE discovery service")
            .setContentText("Background device discovery service is running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun emitBackgroundState(
        running: Boolean,
        reason: String,
        scanning: Boolean = false,
        advertising: Boolean = false,
        error: String? = null,
    ) {
        BridgeEventEmitter.emit(
            "BACKGROUND_STATE_CHANGED",
            JSONObject().apply {
                put("running", running)
                put("reason", reason)
                put("scanning", scanning)
                put("advertising", advertising)
                if (error != null) {
                    put("error", error)
                }
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "meshx_background_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_STOP = "com.meshx.background.ACTION_STOP"
        private const val PREFS_NAME = "meshx_background"
        private const val KEY_ENABLED = "background_enabled"
        private const val KEY_PENDING_RESUME = "pending_resume"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun isBackgroundEnabled(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun setBackgroundEnabled(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }

        fun isPendingResume(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING_RESUME, false)
        }

        fun setPendingResume(context: Context, pending: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_RESUME, pending)
                .apply()
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            setBackgroundEnabled(appCtx, false)
            setPendingResume(appCtx, false)
            appCtx.stopService(Intent(appCtx, MeshForegroundService::class.java))
        }
    }
}
