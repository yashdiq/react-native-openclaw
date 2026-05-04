package com.openclaw.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

class OpenClawProcessModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = NAME

    companion object {
        const val NAME = "RuraProcess"
        private const val LOG_BATCH_SIZE = 20
        private const val LOG_BATCH_INTERVAL_MS = 100L
        private const val GATEWAY_READY_TIMEOUT_MS = 15000L
        private const val GATEWAY_READY_POLL_MS = 150L

        fun getCachedEnvVars(context: Context): Map<String, String> {
            val prefs = context.getSharedPreferences("rura", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("cachedEnvVars", null) ?: return emptyMap()
            val json = org.json.JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                map[key] = json.getString(key)
            }
            return map
        }
    }

    private var gatewayProcess: Process? = null
    private var logThread: Thread? = null
    private var stderrThread: Thread? = null

    @ReactMethod
    fun runSetup(promise: Promise) {
        val context = reactApplicationContext
        val emitter = context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)

        Thread {
            try {
                OpenClawRuntime.fullSetup(context) { step, percent ->
                    emitter.emit("RuraSetupProgress", Arguments.createMap().apply {
                        putString("step", step)
                        putInt("percent", percent)
                    })
                }

                promise.resolve(Arguments.createMap().apply {
                    putBoolean("success", true)
                    putString("nodeVersion", OpenClawRuntime.getNodeVersion(context))
                })
            } catch (e: OpenClawRuntime.SetupException) {
                Log.e(NAME, "Setup failed: ${e.message}")
                promise.reject("SETUP_FAILED", e.message)
            } catch (e: Exception) {
                Log.e(NAME, "Setup error: ${e.message}", e)
                promise.reject("SETUP_ERROR", e.message)
            }
        }.start()
    }

    @ReactMethod
    fun isSetupComplete(promise: Promise) {
        promise.resolve(OpenClawRuntime.isSetupComplete(reactApplicationContext))
    }

    @ReactMethod
    fun isNodeInstalled(promise: Promise) {
        promise.resolve(OpenClawRuntime.isNodeInstalled(reactApplicationContext))
    }

    @ReactMethod
    fun getNodeVersion(promise: Promise) {
        promise.resolve(OpenClawRuntime.getNodeVersion(reactApplicationContext))
    }

    @ReactMethod
    fun start(promise: Promise) {
        try {
            if (!OpenClawRuntime.isSetupComplete(reactApplicationContext)) {
                promise.reject("NOT_SETUP", "Run setup first")
                return
            }

            val serviceIntent = Intent(reactApplicationContext, OpenClawGatewayService::class.java).apply {
                action = "START"
            }
            reactApplicationContext.startService(serviceIntent)

            val vaultPath = getVaultPath()
            val proc = OpenClawRuntime.spawnOpenClaw(reactApplicationContext, emptyMap(), vaultPath)
            if (proc == null) {
                promise.reject("START_ERROR", "Failed to spawn process")
                return
            }

            gatewayProcess = proc
            OpenClawGatewayService.gatewayPid = OpenClawRuntime.getPid(proc)
            startLogReader(proc)

            val port = OpenClawRuntime.gatewayPort
            Thread {
                val ready = waitForGatewayReady(port)
                if (ready) {
                    promise.resolve("started")
                } else {
                    promise.reject("START_TIMEOUT", "Gateway did not start within 15s")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(NAME, "Start error: ${e.message}")
            promise.reject("START_ERROR", e.message)
        }
    }

    @ReactMethod
    fun startWithEnv(envMap: ReadableMap, promise: Promise) {
        try {
            if (!OpenClawRuntime.isSetupComplete(reactApplicationContext)) {
                promise.reject("NOT_SETUP", "Run setup first")
                return
            }

            val serviceIntent = Intent(reactApplicationContext, OpenClawGatewayService::class.java).apply {
                action = "START"
            }
            reactApplicationContext.startService(serviceIntent)

            val envVars = mutableMapOf<String, String>()
            val iterator = envMap.keySetIterator()
            while (iterator.hasNextKey()) {
                val key = iterator.nextKey()
                envVars[key] = envMap.getString(key) ?: ""
            }
            Log.i(NAME, "startWithEnv env vars: ${envVars.keys}")

            cacheEnvVars(envVars)

            val vaultPath = getVaultPath()
            val proc = OpenClawRuntime.spawnOpenClaw(reactApplicationContext, envVars, vaultPath)
            if (proc == null) {
                promise.reject("START_ERROR", "Failed to spawn process")
                return
            }

            gatewayProcess = proc
            OpenClawGatewayService.gatewayPid = OpenClawRuntime.getPid(proc)
            startLogReader(proc)

            val port = OpenClawRuntime.gatewayPort
            Thread {
                val ready = waitForGatewayReady(port)
                if (ready) {
                    promise.resolve("started")
                } else {
                    promise.reject("START_TIMEOUT", "Gateway did not start within 15s")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(NAME, "Start error: ${e.message}")
            promise.reject("START_ERROR", e.message)
        }
    }

    private fun waitForGatewayReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + GATEWAY_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                try { Thread.sleep(GATEWAY_READY_POLL_MS) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }

    @ReactMethod
    fun stop(promise: Promise) {
        val proc = gatewayProcess
        val thread = logThread
        val errThread = stderrThread
        gatewayProcess = null
        logThread = null
        stderrThread = null
        OpenClawGatewayService.gatewayPid = 0

        if (proc != null) {
            proc.destroyForcibly()
            try {
                val ourPid = OpenClawRuntime.getPid(proc)
                if (ourPid > 0) {
                    killChildProcesses(ourPid)
                }
            } catch (_: Exception) {}
        }

        try {
            OpenClawRuntime.gatewayProxy?.stop()
            OpenClawRuntime.gatewayProxy = null
        } catch (_: Exception) {}

        OpenClawRuntime.gatewayPort = 0

        try {
            val lockFile = java.io.File(reactApplicationContext.filesDir, ".openclaw/gateway.lock")
            if (lockFile.exists()) lockFile.delete()
        } catch (_: Exception) {}

        listOf(thread, errThread).forEach { t ->
            if (t != null) {
                try {
                    t.interrupt()
                    t.join(2000)
                } catch (_: InterruptedException) {}
            }
        }

        try {
            val serviceIntent = Intent(reactApplicationContext, OpenClawGatewayService::class.java).apply {
                action = "STOP"
            }
            reactApplicationContext.stopService(serviceIntent)
        } catch (_: Exception) {}

        promise.resolve("stopped")
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

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(gatewayProcess?.isAlive ?: false)
    }

    @ReactMethod
    fun runInAlpine(command: String, promise: Promise) {
        Thread {
            try {
                val output = OpenClawRuntime.runWithNode(reactApplicationContext, command)
                promise.resolve(output)
            } catch (e: Exception) {
                promise.reject("COMMAND_ERROR", e.message)
            }
        }.start()
    }

    @ReactMethod
    fun verifyRuntime(promise: Promise) {
        val context = reactApplicationContext
        val ldLinux = OpenClawRuntime.getLdLinux(context)
        val nodeBin = java.io.File(OpenClawRuntime.getNodeDir(context), "bin/node")
        val openclawBin = java.io.File(OpenClawRuntime.getOpenclawDir(context), "node_modules/.bin/openclaw")

        promise.resolve(Arguments.createMap().apply {
            putBoolean("glibcExists", ldLinux.exists())
            putBoolean("glibcExecutable", ldLinux.canExecute())
            putBoolean("nodeInstalled", nodeBin.exists())
            putBoolean("openclawInstalled", openclawBin.exists())
            putBoolean("setupComplete", OpenClawRuntime.isSetupComplete(context))
        })
    }

    @ReactMethod
    fun getRuntimeInfo(promise: Promise) {
        val context = reactApplicationContext
        promise.resolve(Arguments.createMap().apply {
            putString("filesDir", context.filesDir.absolutePath)
            putString("nativeLibDir", OpenClawRuntime.getNativeLibDir(context))
            putBoolean("glibcReady", OpenClawRuntime.getLdLinux(context).exists())
            putBoolean("nodeReady", OpenClawRuntime.isNodeInstalled(context))
            putBoolean("openclawReady", java.io.File(OpenClawRuntime.getOpenclawDir(context), "node_modules/.bin/openclaw").exists())
            putBoolean("setupComplete", OpenClawRuntime.isSetupComplete(context))
        })
    }

    @ReactMethod
    fun getGatewayLogs(promise: Promise) {
        val proc = gatewayProcess
        if (proc == null) {
            promise.resolve("No gateway process")
            return
        }
        promise.resolve(Arguments.createMap().apply {
            putBoolean("alive", proc.isAlive)
            putString("lastError", OpenClawRuntime.lastError)
        })
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Double) {}

    private fun cacheEnvVars(envVars: Map<String, String>) {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        val json = org.json.JSONObject()
        for ((k, v) in envVars) json.put(k, v)
        prefs.edit().putString("cachedEnvVars", json.toString()).apply()
    }

    private fun startLogReader(proc: Process) {
        val emitter = reactApplicationContext.getJSModule(
            DeviceEventManagerModule.RCTDeviceEventEmitter::class.java
        )

        val readerFactory = { stream: java.io.InputStream ->
            Runnable {
                try {
                    val reader = BufferedReader(InputStreamReader(stream))
                    val batch = mutableListOf<String>()
                    var lastFlush = System.currentTimeMillis()

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        batch.add(line!!)
                        val now = System.currentTimeMillis()

                        if (batch.size >= LOG_BATCH_SIZE || (now - lastFlush) >= LOG_BATCH_INTERVAL_MS) {
                            val arr = Arguments.createArray()
                            batch.forEach { arr.pushString(it) }
                            emitter.emit("RuraLogBatch", arr)
                            batch.clear()
                            lastFlush = now
                        }
                    }
                    if (batch.isNotEmpty()) {
                        val arr = Arguments.createArray()
                        batch.forEach { arr.pushString(it) }
                        emitter.emit("RuraLogBatch", arr)
                    }
                } catch (_: java.io.InterruptedIOException) {
                } catch (e: Exception) {
                    if (proc.isAlive) {
                        Log.e(NAME, "Log reader error: ${e.message}")
                    }
                }
            }
        }

        logThread = Thread {
            readerFactory(proc.inputStream).run()

            try {
                val exitCode = proc.waitFor()
                val msg = "[rura] Process exited with code $exitCode"
                Log.w(NAME, msg)
                emitter.emit("RuraLog", msg)
                emitter.emit("RuraGatewayExit", exitCode)
            } catch (_: InterruptedException) {}
        }.also { it.start() }

        stderrThread = Thread(readerFactory(proc.errorStream)).also { it.start() }
    }

    // ─── Vault ─────────────────────────────────────────────────────

    private fun getVaultPath(): String? {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("vaultEnabled", false)) return null
        return prefs.getString("vaultPath", null)
    }

    @ReactMethod
    fun hasStoragePermission(promise: Promise) {
        promise.resolve(true)
    }

    @ReactMethod
    fun requestStoragePermission(promise: Promise) {
        promise.resolve(true)
    }

    @ReactMethod
    fun getDefaultVaultPath(promise: Promise) {
        val vaultDir = File(reactApplicationContext.filesDir, "vault")
        promise.resolve(vaultDir.absolutePath)
    }

    @ReactMethod
    fun initVault(vaultPath: String, promise: Promise) {
        if (!OpenClawRuntime.initVault(vaultPath)) {
            promise.reject("VAULT_ERROR", OpenClawRuntime.lastError)
            return
        }
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        prefs.edit().putString("vaultPath", vaultPath).putBoolean("vaultEnabled", true).apply()
        promise.resolve(vaultPath)
    }

    @ReactMethod
    fun migrateToVault(vaultPath: String, promise: Promise) {
        if (OpenClawRuntime.migrateToVault(reactApplicationContext, vaultPath)) {
            promise.resolve(true)
        } else {
            promise.reject("MIGRATE_ERROR", OpenClawRuntime.lastError)
        }
    }

    @ReactMethod
    fun getVaultPath(promise: Promise) {
        promise.resolve(getVaultPath())
    }

    @ReactMethod
    fun setVaultEnabled(enabled: Boolean, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("vaultEnabled", enabled).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun isVaultEnabled(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        promise.resolve(prefs.getBoolean("vaultEnabled", false))
    }

    @ReactMethod
    fun setAutoStartOnBoot(enabled: Boolean, promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("autoStartOnBoot", enabled).apply()
        promise.resolve(true)
    }

    @ReactMethod
    fun getAutoStartOnBoot(promise: Promise) {
        val prefs = reactApplicationContext.getSharedPreferences("rura", Context.MODE_PRIVATE)
        promise.resolve(prefs.getBoolean("autoStartOnBoot", false))
    }
}
