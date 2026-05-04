package com.openclaw.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class OpenClawGatewayService : Service() {

    companion object {
        private const val CHANNEL_ID = "rura_gateway"
        private const val TAG = "OpenClawGateway"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.rura.ACTION_STOP_GATEWAY"
        const val ACTION_WAKELOCK = "com.rura.ACTION_TOGGLE_WAKELOCK"

        var gatewayPid: Long = 0
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHeld = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                killGatewayAndStop()
                return START_NOT_STICKY
            }
            ACTION_WAKELOCK -> {
                toggleWakeLock()
                val notification = buildNotification()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun killGatewayAndStop() {
        Log.i(TAG, "Exit requested — killing gateway PID=$gatewayPid")

        if (gatewayPid > 0) {
            killChildProcesses(gatewayPid)
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", gatewayPid.toString())).waitFor()
            } catch (_: Exception) {}
            gatewayPid = 0
        }

        try {
            OpenClawRuntime.gatewayProxy?.stop()
            OpenClawRuntime.gatewayProxy = null
        } catch (_: Exception) {}

        OpenClawRuntime.gatewayPort = 0

        try {
            val lockFile = java.io.File(filesDir, ".openclaw/gateway.lock")
            if (lockFile.exists()) lockFile.delete()
        } catch (_: Exception) {}

        releaseWakeLock()
        stopSelf()
    }

    private fun killChildProcesses(parentPid: Long) {
        try {
            val pb = ProcessBuilder("sh", "-c", "pgrep -P $parentPid")
            pb.redirectErrorStream(true)
            val result = pb.start()
            val output = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            if (output.isNotEmpty()) {
                output.split("\n").forEach { pid ->
                    val trimmed = pid.trim()
                    if (trimmed.isNotEmpty()) {
                        try { Runtime.getRuntime().exec(arrayOf("kill", "-9", trimmed)).waitFor() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        if (wakeLockHeld) return
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "rura::gateway-wakelock"
            ).apply {
                acquire()
            }
            wakeLockHeld = true
            Log.i(TAG, "Wakelock acquired — CPU will stay active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakelock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        if (!wakeLockHeld) return
        try {
            wakeLock?.release()
            wakeLockHeld = false
            Log.i(TAG, "Wakelock released — CPU can sleep normally")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wakelock: ${e.message}")
        }
    }

    private fun toggleWakeLock() {
        if (wakeLockHeld) releaseWakeLock() else acquireWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rura",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Rura gateway is running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, OpenClawGatewayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wakelockIntent = Intent(this, OpenClawGatewayService::class.java).apply {
            action = ACTION_WAKELOCK
        }
        val wakelockPendingIntent = PendingIntent.getService(
            this, 1, wakelockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wakelockLabel = if (wakeLockHeld) "Release Wakelock" else "Acquire Wakelock"
        val statusText = if (wakeLockHeld) {
            "Gateway active • Wakelock held (CPU stays on)"
        } else {
            "Gateway active • Wakelock off (CPU may sleep)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rura")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_lock_idle_lock,
                wakelockLabel,
                wakelockPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Exit",
                stopPendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(statusText)
            )
            .build()
    }
}
