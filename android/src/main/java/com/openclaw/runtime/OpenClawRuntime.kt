package com.openclaw.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

/**
 * Manages the glibc + Node.js runtime environment.
 *
 * Architecture:
 * - libld_linux.so (glibc loader) shipped as JNI lib → nativeLibraryDir (exec permission)
 * - Other glibc libs shipped as APK assets → extracted to filesDir/glibc/ (read/mmap only)
 * - Official Node.js downloaded at setup → filesDir/node/
 * - OpenClaw installed via npm → filesDir/openclaw/
 *
 * Why split? Android SELinux blocks execve() from filesDir (W^X policy).
 * Only nativeLibraryDir has exec permission. But shared libs loaded via dlopen
 * only need mmap (read), which filesDir allows. So only the loader needs JNI.
 */
object OpenClawRuntime {

    private const val TAG = "OpenClawRuntime"
    private const val NODE_VERSION = "24.15.0"
    private const val NODE_DIST_URL =
        "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.gz"

    var lastError: String = ""
        private set

    // ─── Vault (shared storage) ───────────────────────────────────

    fun getDefaultVaultPath(): String = ""

    fun hasStoragePermission(): Boolean = true

    fun initVault(vaultPath: String): Boolean {
        return try {
            val vault = File(vaultPath)
            if (!vault.exists()) vault.mkdirs()
            File(vault, ".openclaw").mkdirs()
            File(vault, ".openclaw/workspace").mkdirs()
            File(vault, ".openclaw/workspace/memory").mkdirs()
            Log.i(TAG, "Vault initialized at $vaultPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vault init failed: ${e.message}")
            lastError = e.message ?: "Vault init failed"
            false
        }
    }

    fun migrateToVault(context: Context, vaultPath: String): Boolean {
        val src = File(context.filesDir, ".openclaw/workspace")
        val dst = File("$vaultPath/.openclaw/workspace")
        if (!src.exists()) return true
        return try {
            dst.mkdirs()
            src.copyRecursively(dst, overwrite = true)
            Log.i(TAG, "Migrated workspace to vault")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed: ${e.message}")
            lastError = e.message ?: "Migration failed"
            false
        }
    }

    fun getHomeDir(context: Context, vaultPath: String?): String {
        if (vaultPath != null && File(vaultPath).isDirectory) return vaultPath
        return context.filesDir.absolutePath
    }

    // Directories

    fun getNativeLibDir(context: Context): String =
        context.applicationInfo.nativeLibraryDir

    fun getGlibcLibDir(context: Context): File = File(context.filesDir, "glibc")

    fun getLdLinux(context: Context): File = File(getNativeLibDir(context), "libld_linux.so")

    fun getNodeDir(context: Context): File = File(context.filesDir, "node")
    fun getSetupMarker(context: Context): File = File(context.filesDir, ".rura_setup_complete")
    fun getOpenclawDir(context: Context): File = File(context.filesDir, "openclaw")

    fun getHijackScript(context: Context): File = File(context.filesDir, "hijack.js")

    fun ensureHijackScript(context: Context) {
        val target = getHijackScript(context)
        val versionFile = File(context.filesDir, ".hijack_version")
        val currentVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).longVersionCode.toString()
        } catch (_: Exception) { "unknown" }

        if (target.exists() && versionFile.exists() &&
            versionFile.readText() == currentVersion) {
            Log.d(TAG, "hijack.js up to date (v$currentVersion), skipping rewrite")
            return
        }

        val ldLinux = getLdLinux(context).absolutePath
        val glibcDir = getGlibcLibDir(context).absolutePath
        val nodeBin = File(getNodeDir(context), "bin/node.bin").absolutePath

        target.writeText("""'use strict';
const cp = require('child_process');
const path = require('path');
const fs = require('fs');
const dns = require('dns');

const LD_LINUX = '${ldLinux}';
const GLIBC_DIR = '${glibcDir}';
const NODE_BIN = '${nodeBin}';

// ─── DNS fix ─────────────────────────────────────────────────────
// glibc's getaddrinfo() needs /etc/resolv.conf (doesn't exist on Android).
// Fix: override dns.lookup to use c-ares (dns.resolve) with explicit servers.
// Also force IPv4-first because Telegram's IPv6 is unreachable on mobile.
const DNS_SERVERS = ['8.8.8.8', '1.1.1.1', '8.8.4.4'];

try {
  dns.setServers(DNS_SERVERS);
  dns.setDefaultResultOrder('ipv4first');

  const origLookup = dns.lookup;
  dns.lookup = function(hostname, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    const family = options?.family ?? 0;
    const resolveFamily = family === 6 ? 'AAAA' : 'A';

    dns.resolve(hostname, resolveFamily, (err, addresses) => {
      if (err) {
        return origLookup.call(dns, hostname, options, callback);
      }
      const addr = Array.isArray(addresses) ? addresses[0] : addresses;
      callback(null, addr, family === 6 ? 6 : 4);
    });
  };
} catch {}

// ─── process.execPath patch ──────────────────────────────────────
Object.defineProperty(process, 'execPath', {
  value: NODE_BIN,
  writable: true,
  configurable: true,
});

// ─── child_process patches ───────────────────────────────────────
const origSpawn = cp.spawn;
cp.spawn = function(file, args, opts) {
  if (file && (file === 'node' || file.endsWith('/node') || file.endsWith('/node.bin'))) {
    const newArgs = ['--library-path', GLIBC_DIR, NODE_BIN, ...(args || [])];
    return origSpawn.call(this, LD_LINUX, newArgs, opts);
  }
  return origSpawn.call(this, file, args, opts);
};

const origSpawnSync = cp.spawnSync;
cp.spawnSync = function(file, args, opts) {
  if (file && (file === 'node' || file.endsWith('/node') || file.endsWith('/node.bin'))) {
    const newArgs = ['--library-path', GLIBC_DIR, NODE_BIN, ...(args || [])];
    return origSpawnSync.call(this, LD_LINUX, newArgs, opts);
  }
  return origSpawnSync.call(this, file, args, opts);
};

const origExecFile = cp.execFile;
cp.execFile = function(file, args, opts, cb) {
  if (file && (file === 'node' || file.endsWith('/node') || file.endsWith('/node.bin'))) {
    const newArgs = ['--library-path', GLIBC_DIR, NODE_BIN, ...(args || [])];
    return origExecFile.call(this, LD_LINUX, newArgs, opts, cb);
  }
  return origExecFile.call(this, file, args, opts, cb);
};

const origExec = cp.exec;
cp.exec = function(cmd, opts, cb) {
  if (typeof cmd === 'string' && cmd.includes('node ')) {
    cmd = cmd.replace(/(^|\s)node(?=\s)/g, " LD_LINUX --library-path GLIBC_DIR NODE_BIN");
    cmd = cmd.trim();
  }
  return origExec.call(this, cmd, opts, cb);
};

// ─── os.networkInterfaces patch ───────────────────────────────────
try {
  const os = require('os');
  const _orig = os.networkInterfaces.bind(os);
  os.networkInterfaces = () => {
    try {
      const ifaces = _orig();
      if (!ifaces) return {};
      const safe = {};
      for (const [name, addrs] of Object.entries(ifaces)) {
        if (!Array.isArray(addrs)) continue;
        safe[name] = addrs.filter(a => {
          if (!a || !a.address || !a.netmask) return true;
          const addrV6 = a.address.includes(':');
          const maskV6 = a.netmask.includes(':');
          return addrV6 === maskV6;
        });
      }
      return safe;
    } catch {
      return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', internal: true }] };
    }
  };
} catch {}
""".trimIndent() + "\n")

        versionFile.writeText(currentVersion)
        Log.i(TAG, "Wrote hijack.js (v$currentVersion)")
    }

    fun getLibraryPath(context: Context): String {
        return getGlibcLibDir(context).absolutePath
    }

    // Setup steps

    fun verifyGlibc(context: Context) {
        val ldLinux = getLdLinux(context)
        if (!ldLinux.exists()) {
            throw SetupException(
                "glibc loader not found at ${ldLinux.absolutePath}. " +
                "nativeLibraryDir=${getNativeLibDir(context)}"
            )
        }
        Log.i(TAG, "glibc loader OK: ${ldLinux.absolutePath}")
    }

    fun extractGlibcLibs(context: Context) {
        val glibcDir = getGlibcLibDir(context)
        val marker = File(glibcDir, ".extracted")
        if (marker.exists()) {
            Log.i(TAG, "glibc libs already extracted")
            return
        }
        glibcDir.mkdirs()
        val libs = listOf(
            "libc.so.6", "libm.so.6", "libdl.so.2", "librt.so.1",
            "libpthread.so.0", "libstdc++.so.6", "libgcc_s.so.1"
        )
        for (lib in libs) {
            try {
                context.assets.open("glibc/$lib").use { input ->
                    FileOutputStream(File(glibcDir, lib)).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                throw SetupException("Failed to extract $lib: ${e.message}")
            }
        }
        marker.createNewFile()
        Log.i(TAG, "All glibc libs extracted to ${glibcDir.absolutePath}")
    }

    fun downloadNodeJs(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val nodeDir = getNodeDir(context)
        val nodeBin = File(nodeDir, "bin/node")
        if (nodeBin.exists() && nodeBin.canExecute()) {
            createNodeWrapper(context)
            return
        }
        nodeDir.mkdirs()
        val tempTar = File(context.cacheDir, "node.tar.gz")
        try {
            val url = URL(NODE_DIST_URL)
            val conn = url.openConnection()
            val totalSize = conn.contentLengthLong
            var downloaded = 0L
            conn.getInputStream().use { input ->
                FileOutputStream(tempTar).use { output ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        output.write(buf, 0, len)
                        downloaded += len
                        onProgress(downloaded, totalSize)
                    }
                }
            }
            val pb = ProcessBuilder(
                "tar", "zxf", tempTar.absolutePath,
                "-C", nodeDir.absolutePath, "--strip-components=1"
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw SetupException("tar failed (exit $exitCode): ${output.take(300)}")
            }
            tempTar.delete()
            if (!nodeBin.exists()) {
                throw SetupException("node binary not found")
            }
            nodeBin.setExecutable(true, false)
            createNodeWrapper(context)
            Log.i(TAG, "Node.js installed: ${getNodeVersion(context)}")
        } catch (e: SetupException) {
            throw e
        } catch (e: Exception) {
            throw SetupException("Node.js download failed: ${e.message}")
        }
    }

    private fun createNodeWrapper(context: Context) {
        val nodeDir = File(getNodeDir(context), "bin")
        val nodeBin = File(nodeDir, "node")
        val nodeReal = File(nodeDir, "node.bin")
        if (nodeReal.exists() && nodeBin.exists()) {
            val firstLine = nodeBin.bufferedReader().use { it.readLine() }
            if (firstLine?.startsWith("#!") == true) return
        }
        if (nodeBin.exists() && !nodeReal.exists()) {
            nodeBin.renameTo(nodeReal)
        }
        if (!nodeReal.exists()) return
        val ldLinux = getLdLinux(context)
        val glibcDir = getGlibcLibDir(context)
        val wrapperContent = "#!/system/bin/sh\n" +
            "exec ${ldLinux.absolutePath} " +
            "--library-path ${glibcDir.absolutePath} " +
            "${nodeReal.absolutePath} \"\$@\"\n"
        if (nodeBin.exists()) nodeBin.delete()
        nodeBin.writeText(wrapperContent)
        nodeBin.setExecutable(true, false)
        nodeBin.setReadable(true, false)
    }

    fun installOpenClaw(context: Context) {
        val openclawDir = getOpenclawDir(context)
        val openclawBin = File(openclawDir, "node_modules/.bin/openclaw")
        if (openclawBin.exists()) return
        openclawDir.mkdirs()
        val proxy = LocalConnectProxy()
        val proxyPort = proxy.start()
        val proxyUrl = "http://127.0.0.1:$proxyPort"
        try {
            runWithNode(context, "npm init -y", openclawDir, proxyUrl)
            var installResult = ""
            var lastError = ""
            for (attempt in 1..3) {
                installResult = runWithNode(context, "npm install openclaw", openclawDir, proxyUrl)
                if (openclawBin.exists()) break
                lastError = installResult.take(500)
                if (installResult.contains("code 126")) {
                    installResult = runWithNode(context, "npm install openclaw --ignore-scripts", openclawDir, proxyUrl)
                    if (openclawBin.exists()) break
                    lastError = installResult.take(500)
                }
                if (attempt < 3 && (installResult.contains("ECONNRESET") || installResult.contains("ENOTFOUND") || installResult.contains("network"))) {
                    Thread.sleep((attempt * 3000).toLong())
                    continue
                }
                break
            }
            if (!openclawBin.exists()) {
                throw SetupException("OpenClaw not found after npm install ($lastError)")
            }
            openclawBin.setExecutable(true, false)
        } finally {
            proxy.stop()
        }
    }

    fun runWithNode(context: Context, command: String, workDir: File? = null, proxyUrl: String? = null): String {
        val ldLinux = getLdLinux(context)
        val nodeBin = File(getNodeDir(context), "bin/node")
        if (!ldLinux.exists()) return "ERROR: glibc loader not found"
        val nodeReal = File(getNodeDir(context), "bin/node.bin")
        val nodeExec = if (nodeReal.exists()) nodeReal else nodeBin
        val resolvedCmd = resolveCommand(context, command)
        val args = mutableListOf(
            ldLinux.absolutePath, "--library-path", getLibraryPath(context), nodeExec.absolutePath
        )
        args.addAll(resolvedCmd)
        val pb = ProcessBuilder(args)
        if (workDir != null) pb.directory(workDir)
        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("PATH", "${File(getNodeDir(context), "bin").absolutePath}:/usr/bin:/bin")
            put("NODE_ENV", "production")
            put("OPENSSL_CONF", "/dev/null")
            if (proxyUrl != null) {
                put("http_proxy", proxyUrl)
                put("https_proxy", proxyUrl)
                put("npm_config_proxy", proxyUrl)
                put("npm_config_https_proxy", proxyUrl)
                put("npm_config_strict_ssl", "false")
            }
        }
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            Log.d(TAG, "exit=$exitCode output=${output.take(500)}")
            output
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun resolveCommand(context: Context, command: String): List<String> {
        val parts = command.trim().split("\\s+".toRegex())
        return when {
            parts[0] == "node" -> parts.drop(1)
            parts[0] == "npm" -> {
                val npmCli = File(getNodeDir(context), "lib/node_modules/npm/bin/npm-cli.js")
                listOf(npmCli.absolutePath) + parts.drop(1)
            }
            else -> listOf("-e", command)
        }
    }

    fun isSetupComplete(context: Context): Boolean = getSetupMarker(context).exists()
    fun isNodeInstalled(context: Context): Boolean {
        return File(getNodeDir(context), "bin/node").exists() ||
               File(getNodeDir(context), "bin/node.bin").exists()
    }
    fun getNodeVersion(context: Context): String =
        runWithNode(context, "node --version").trim()

    var gatewayProxy: LocalConnectProxy? = null
    var gatewayPort: Int = 0
    private var lastGoodPort: Int = 0

    fun findAvailablePort(): Int {
        if (lastGoodPort > 0) {
            try { java.net.ServerSocket(lastGoodPort).use { return lastGoodPort } } catch (_: Exception) {}
        }
        return java.net.ServerSocket(0).use { it.localPort }.also { lastGoodPort = it }
    }

    private fun pluginsStaged(homeDir: String): Boolean {
        val depsBase = File(homeDir, ".openclaw/plugin-runtime-deps")
        if (!depsBase.isDirectory) return false
        val dirs = depsBase.listFiles { f: File -> f.isDirectory } ?: return false
        return dirs.any { dir -> File(dir, "node_modules/grammy").isDirectory }
    }

    private fun cleanStaleLocks(homeDir: String) {
        val depsBase = File(homeDir, ".openclaw/plugin-runtime-deps")
        if (!depsBase.isDirectory) return
        val lockDirs = depsBase.listFiles { f: File -> f.isDirectory }?.flatMap { versionDir ->
            versionDir.listFiles { f: File ->
                f.name.endsWith(".openclaw-runtime-deps.lock") && f.isDirectory
            }?.toList() ?: emptyList()
        } ?: return
        for (lockDir in lockDirs) {
            try {
                lockDir.deleteRecursively()
                Log.i(TAG, "Cleaned stale lock: ${lockDir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean lock: ${e.message}")
            }
        }
    }

    fun spawnOpenClaw(context: Context, extraEnv: Map<String, String> = emptyMap(), vaultPath: String? = null): Process? {
        val ldLinux = getLdLinux(context)
        val nodeReal = File(getNodeDir(context), "bin/node.bin")
        val nodeBin = File(getNodeDir(context), "bin/node")
        val nodeExec = if (nodeReal.exists()) nodeReal else nodeBin
        val openclawBin = File(getOpenclawDir(context), "node_modules/.bin/openclaw")
        if (!openclawBin.exists()) {
            Log.e(TAG, "OpenClaw not installed")
            return null
        }

        val homeDir = getHomeDir(context, vaultPath)
        cleanStaleLocks(homeDir)

        gatewayPort = findAvailablePort()
        Log.i(TAG, "Gateway will use port $gatewayPort")

        ensureHijackScript(context)
        val hijackPath = getHijackScript(context).absolutePath

        val staged = pluginsStaged(homeDir)
        Log.i(TAG, "Plugin deps staged: $staged")

        val proxyUrl: String? = if (!staged) {
            gatewayProxy = LocalConnectProxy()
            val proxyPort = gatewayProxy!!.start()
            val url = "http://127.0.0.1:$proxyPort"
            Log.i(TAG, "Runtime proxy started: $url (plugin staging needed)")
            url
        } else {
            gatewayProxy = null
            Log.i(TAG, "Skipping proxy — plugins already staged")
            null
        }

        val args = mutableListOf(
            ldLinux.absolutePath, "--library-path", getLibraryPath(context),
            nodeExec.absolutePath, "--unhandled-rejections=none",
            openclawBin.absolutePath,
            "gateway", "--dev", "--auth", "none", "--port", gatewayPort.toString()
        )

        val pb = ProcessBuilder(args)
        pb.directory(getOpenclawDir(context))
        pb.environment().apply {
            put("HOME", homeDir)
            put("PATH", "${File(getNodeDir(context), "bin").absolutePath}:/usr/bin:/bin")
            put("NODE_ENV", "production")
            put("NODE_OPTIONS", "--require $hijackPath")
            if (proxyUrl != null) {
                put("http_proxy", proxyUrl)
                put("https_proxy", proxyUrl)
                put("no_proxy", listOf(
                    "127.0.0.1", "localhost", "::1",
                    "openrouter.ai", "*.openrouter.ai",
                    "litellm.ai", "*.litellm.ai",
                    "api.anthropic.com", "api.openai.com",
                    "generativelanguage.googleapis.com",
                    "api.groq.com", "open.bigmodel.cn"
                ).joinToString(","))
            } else {
                put("no_proxy", "127.0.0.1,localhost,::1")
            }
            for ((key, value) in extraEnv) {
                put(key, value)
                Log.d(TAG, "env: $key=${value.take(10)}...")
            }
        }
        pb.redirectErrorStream(true)

        return try {
            val proc = pb.start()
            Log.i(TAG, "OpenClaw spawned (pid=${getPid(proc)}, port=$gatewayPort)")
            proc
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn OpenClaw: ${e.message}")
            gatewayProxy?.stop()
            gatewayProxy = null
            null
        }
    }

    fun fullSetup(context: Context, onProgress: (step: String, percent: Int) -> Unit) {
        try {
            onProgress("Verifying glibc loader...", 5)
            verifyGlibc(context)
            onProgress("Extracting glibc libraries...", 10)
            extractGlibcLibs(context)
            onProgress("Preparing runtime patches...", 12)
            ensureHijackScript(context)
            onProgress("Downloading Node.js v$NODE_VERSION (~40MB)...", 30)
            downloadNodeJs(context) { downloaded, total ->
                if (total > 0) {
                    val pct = 30 + ((downloaded.toDouble() / total) * 40).toInt().coerceAtMost(69)
                    onProgress("Downloading... ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB", pct)
                }
            }
            val nodeVer = getNodeVersion(context)
            onProgress("Node.js $nodeVer ready! Installing OpenClaw...", 75)
            installOpenClaw(context)
            onProgress("Setup complete! Node.js $nodeVer + OpenClaw", 100)
            markSetupComplete(context)
        } catch (e: SetupException) {
            lastError = e.message ?: "Unknown error"
            onProgress("Failed: ${e.message}", -1)
            throw e
        }
    }

    fun markSetupComplete(context: Context) { getSetupMarker(context).createNewFile() }

    fun getPid(process: Process): Long {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getLong(process)
        } catch (e: Exception) { -1L }
    }

    class SetupException(message: String) : Exception(message)
}
