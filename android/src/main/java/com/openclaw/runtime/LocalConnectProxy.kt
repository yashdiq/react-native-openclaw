package com.openclaw.runtime

import android.util.Log
import java.io.*
import java.net.*

class LocalConnectProxy {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var running = false

    companion object {
        private const val TAG = "LocalProxy"
        private const val BUFFER_SIZE = 65536
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    fun start(): Int {
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        running = true

        acceptThread = Thread({
            try {
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    client.soTimeout = READ_TIMEOUT
                    client.tcpNoDelay = true
                    client.setReceiveBufferSize(BUFFER_SIZE)
                    client.setSendBufferSize(BUFFER_SIZE)
                    Thread({ handleClient(client) }).start()
                }
            } catch (_: Exception) {}
        }, "LocalProxy-Accept")
        acceptThread!!.start()

        Log.i(TAG, "Local CONNECT proxy started on port $port")
        return port
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "Local CONNECT proxy stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input) ?: return
            Log.d(TAG, "Proxy request: ${requestLine.take(80)}")

            if (!requestLine.startsWith("CONNECT")) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            while (true) {
                val headerLine = readLine(input) ?: break
                if (headerLine.isEmpty()) break
            }

            val target = requestLine.split(" ")[1]
            val parts = target.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 443

            val remote = Socket()
            remote.tcpNoDelay = true
            remote.soTimeout = READ_TIMEOUT
            remote.setReceiveBufferSize(BUFFER_SIZE)
            remote.setSendBufferSize(BUFFER_SIZE)
            remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            val clientIn = client.getInputStream()
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            val clientOut = client.getOutputStream()

            val t1 = Thread({
                try {
                    val buf = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (clientIn.read(buf).also { len = it } != -1) {
                        remoteOut.write(buf, 0, len)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {}
                try { remote.shutdownOutput() } catch (_: Exception) {}
            }, "Proxy-C2R-${host}")
            t1.start()

            try {
                val buf = ByteArray(BUFFER_SIZE)
                var len: Int
                while (remoteIn.read(buf).also { len = it } != -1) {
                    clientOut.write(buf, 0, len)
                    clientOut.flush()
                }
            } catch (_: Exception) {}
            try { client.shutdownOutput() } catch (_: Exception) {}

            t1.join(10000)
        } catch (e: Exception) {
            Log.w(TAG, "Proxy handler error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isNotEmpty()) sb.toString() else null
            if (b == '\r'.code && prev != '\n'.code) {
                prev = b
                continue
            }
            if (b == '\n'.code) {
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
