package com.openclaw.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class BootStartWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BootStartWorker"
        private const val READY_TIMEOUT_MS = 30_000L
        private const val READY_POLL_MS = 200L
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, "rura_gateway")
            .setContentTitle("Rura")
            .setContentText("Starting gateway...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setSilent(true)
            .build()
        return ForegroundInfo(OpenClawGatewayService.NOTIFICATION_ID + 1, notification)
    }

    override suspend fun doWork(): Result {
        return try {
            val prefs = context.getSharedPreferences("rura", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("autoStartOnBoot", false)

            if (!autoStart) {
                Log.i(TAG, "Skipping — autoStartOnBoot is OFF")
                return Result.success()
            }

            if (!OpenClawRuntime.isSetupComplete(context)) {
                Log.w(TAG, "Skipping — setup not complete")
                return Result.success()
            }

            Log.i(TAG, "Auto-starting gateway from boot...")

            val serviceIntent = Intent(context, OpenClawGatewayService::class.java).apply {
                action = "START"
            }
            context.startForegroundService(serviceIntent)

            val envVars = OpenClawProcessModule.getCachedEnvVars(context)
            Log.i(TAG, "Cached env vars: ${envVars.keys}")

            val vaultPath = if (OpenClawRuntime.hasStoragePermission() &&
                prefs.getBoolean("vaultEnabled", false)) {
                prefs.getString("vaultPath", null)
            } else null

            val proc = OpenClawRuntime.spawnOpenClaw(context, envVars, vaultPath)
            if (proc == null) {
                Log.e(TAG, "Failed to spawn OpenClaw")
                return Result.failure()
            }

            OpenClawGatewayService.gatewayPid = OpenClawRuntime.getPid(proc)

            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d("OpenClawGateway", line!!)
                    }
                } catch (_: Exception) {}
            }.start()

            val port = OpenClawRuntime.gatewayPort
            val ready = waitForReady(port)
            if (ready) {
                Log.i(TAG, "Gateway ready on port $port")
                Result.success()
            } else {
                Log.w(TAG, "Gateway did not become ready within ${READY_TIMEOUT_MS}ms")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot start failed: ${e.message}")
            Result.failure()
        }
    }

    private fun waitForReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                try { Thread.sleep(READY_POLL_MS) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }
}
