package ai.closepaw.browser.cdp.shizuku

import ai.closepaw.browser.cdp.wireless.ProcNetTcpListeners
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku UserService implementation.
 *
 * Runs inside a Shizuku-spawned host process under the shell UID and exposes a single
 * round-trip [exchange] over Chrome's `chrome_devtools_remote` abstract socket. The shell UID
 * is typically allowed to connect to that socket even when ClosePaw's app UID is blocked by
 * SELinux / package isolation.
 *
 * Security: the abstract socket name is hard-wired to [CHROME_DEVTOOLS_SOCKET]; if it were a
 * parameter the app UID could use this user service as a proxy to reach arbitrary privileged
 * sockets, which is a privilege-escalation hole.
 *
 * This class is intentionally thin: it owns no state beyond the binder lifecycle. Higher-level
 * orchestration (preflight checks, transport selection, parsing) lives in
 * [ShizukuChromeDevtoolsBridge]. Unit-testable parsing lives in [DevtoolsHttpProtocol].
 */
class ChromeDevtoolsUserService() : IChromeDevtoolsUserService.Stub() {

    /**
     * Shizuku spawns the user service process and instantiates this class via reflection. Some
     * binder hosts pass a Context-like argument; we accept and ignore it because this service
     * holds no state outside the binder lifecycle.
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Any?) : this()

    override fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        require(request.isNotEmpty()) { "request must not be empty" }
        val timeout = timeoutMs.coerceAtLeast(1)
        val deadline = SystemClock.uptimeMillis() + timeout

        val socket = LocalSocket()
        return try {
            socket.connect(
                LocalSocketAddress(CHROME_DEVTOOLS_SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )
            socket.soTimeout = timeout
            socket.outputStream.write(request)
            socket.outputStream.flush()
            // Chrome's net::HttpServer ignores `Connection: close` on the abstract socket and
            // keeps the connection open after a single response, so we MUST stop reading on the
            // HTTP boundary (Content-Length) instead of waiting for EOF — and we must not
            // shutdownOutput, because that triggers an immediate RST on this build.
            readHttpResponse(socket, deadline)
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun destroy() {
        stopRelay()
    }

    // ── Wireless ADB management ──────────────────────────────────────────────────

    private val adbManager = IAdbManagerReflection()

    override fun getCurrentBssid(): String? {
        val out = runShellCapture(
            "dumpsys wifi | grep -oE 'BSSID: [0-9a-f:]+' | head -1 | awk '{print \$2}'"
        ).trim()
        return out.takeIf { it.isNotEmpty() && it != "null" && it.contains(':') }
    }

    override fun enableWirelessDebugging(bssid: String): Boolean {
        require(bssid.isNotBlank()) { "bssid must not be blank" }
        return adbManager.allowWirelessDebugging(true, bssid)
    }

    override fun getAdbWirelessPort(): Int = adbManager.getAdbWirelessPort()

    override fun enablePairingByQrCode(name: String, psk: String): Int {
        require(name.isNotBlank()) { "name must not be blank" }
        require(psk.length >= MIN_PSK_LENGTH) { "psk must be ≥$MIN_PSK_LENGTH chars" }
        val before = ProcNetTcpListeners.snapshot()
        val wirelessPort = adbManager.getAdbWirelessPort().takeIf { it > 0 } ?: 0
        if (!adbManager.enablePairingByQrCode(name, psk)) return -1
        val ignored = before + setOfNotNull(wirelessPort.takeIf { it > 0 }, ADBD_LEGACY_PORT)
        val deadline = SystemClock.uptimeMillis() + PAIR_PORT_POLL_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val now = ProcNetTcpListeners.snapshot()
            val newPorts = now - ignored
            if (newPorts.isNotEmpty()) {
                // Pick the largest — adbd's pair port is typically a high ephemeral.
                return newPorts.max()
            }
            Thread.sleep(PAIR_PORT_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "pair port did not appear within ${PAIR_PORT_POLL_MS}ms; before=$before")
        return -1
    }

    override fun disablePairing() {
        adbManager.disablePairing()
    }

    override fun readAdbKeys(): String? = try {
        File(ADB_KEYS_PATH).readText(Charsets.US_ASCII)
    } catch (e: Exception) {
        // Shell uid is normally in the `adb` group and can read 0640 adb_keys, but some OEM
        // builds tighten this. Returning null tells the caller "uncertain" → it re-pairs,
        // which is idempotent on adbd's side.
        Log.w(TAG, "could not read $ADB_KEYS_PATH: ${e.message}")
        null
    }

    override fun writeAdbKeys(content: String): Boolean = try {
        // adbd watches /data/misc/adb (directory) for IN_MOVED_TO; in-place truncate+rewrite
        // is invisible to it. Write a sibling tmp + atomic rename so the new key set takes
        // effect on the next handshake without an adbd restart.
        val target = File(ADB_KEYS_PATH)
        val parent = target.parentFile
            ?: throw IOException("$ADB_KEYS_PATH has no parent directory")
        val tmp = File(parent, "adb_keys.closepaw.tmp")
        tmp.writeText(content, Charsets.US_ASCII)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "writeAdbKeys failed: ${e.message}", e)
        false
    }

    private fun runShellCapture(script: String): String = try {
        val proc = ProcessBuilder("sh", "-c", script).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        out
    } catch (e: Exception) {
        Log.w(TAG, "shell capture failed: $script", e)
        ""
    }

    @Volatile
    private var relayPort: Int = 0
    private var relayServer: ServerSocket? = null
    private val relayStopped = AtomicBoolean(false)

    @Synchronized
    override fun startTcpRelay(): Int {
        if (relayPort != 0) return relayPort
        // Bind 127.0.0.1:0 — kernel chooses a free port.
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        relayServer = server
        relayPort = server.localPort
        Thread({ relayAcceptLoop(server) }, "cdp-relay-accept").apply {
            isDaemon = true
        }.start()
        Log.i(TAG, "TCP relay started on 127.0.0.1:$relayPort")
        return relayPort
    }

    private fun stopRelay() {
        if (!relayStopped.compareAndSet(false, true)) return
        runCatching { relayServer?.close() }
        relayServer = null
    }

    private fun relayAcceptLoop(server: ServerSocket) {
        while (!relayStopped.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (relayStopped.get() || server.isClosed) return
                Log.w(TAG, "relay accept failed", e)
                continue
            }
            // One thread per connection; bidirectional pump until either side closes.
            Thread({ proxyConnection(client) }, "cdp-relay-${client.port}").apply {
                isDaemon = true
            }.start()
        }
    }

    private fun proxyConnection(client: Socket) {
        val abstractSocket = LocalSocket()
        try {
            abstractSocket.connect(
                LocalSocketAddress(CHROME_DEVTOOLS_SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )
            client.tcpNoDelay = true
            // Two pump threads — one each direction. Either close terminates both.
            val downstream = Thread({
                runCatching {
                    pump(abstractSocket.inputStream, client.getOutputStream())
                }
                runCatching { client.shutdownOutput() }
            }, "cdp-relay-down").apply { isDaemon = true }
            val upstream = Thread({
                runCatching {
                    pump(client.getInputStream(), abstractSocket.outputStream)
                }
                runCatching { abstractSocket.shutdownOutput() }
            }, "cdp-relay-up").apply { isDaemon = true }
            downstream.start()
            upstream.start()
            downstream.join()
            upstream.join()
        } catch (e: Throwable) {
            Log.w(TAG, "relay proxy error", e)
        } finally {
            runCatching { abstractSocket.close() }
            runCatching { client.close() }
        }
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            if (n > 0) {
                output.write(buf, 0, n)
                output.flush()
            }
        }
    }

    private fun readHttpResponse(socket: LocalSocket, deadline: Long): ByteArray {
        val sink = ByteArrayOutputStream(BUFFER)
        val buf = ByteArray(BUFFER)
        val input = socket.inputStream
        var headerEnd = -1
        while (headerEnd < 0) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw SocketTimeoutException("read deadline exceeded for $socket")
            }
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                throw IOException("read timed out after ${socket.soTimeout} ms", e)
            }
            if (n < 0) return sink.toByteArray()
            if (n > 0) sink.write(buf, 0, n)
            headerEnd = indexOfDoubleCrlf(sink.toByteArray())
        }
        val collected = sink.toByteArray()
        val contentLength = parseContentLength(collected, headerEnd) ?: return collected
        val bodyStart = headerEnd + 4
        val needed = bodyStart + contentLength - collected.size
        if (needed <= 0) return collected
        readExactly(input, sink, needed.toInt(), deadline, socket.soTimeout)
        return sink.toByteArray()
    }

    private fun readExactly(
        input: java.io.InputStream,
        sink: ByteArrayOutputStream,
        bytes: Int,
        deadline: Long,
        soTimeout: Int,
    ) {
        var remaining = bytes
        val buf = ByteArray(BUFFER)
        while (remaining > 0) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw SocketTimeoutException("body read deadline exceeded after ${bytes - remaining}/$bytes bytes")
            }
            val n = try {
                input.read(buf, 0, minOf(remaining, buf.size))
            } catch (e: SocketTimeoutException) {
                throw IOException("body read timed out after $soTimeout ms", e)
            }
            if (n < 0) throw IOException("EOF after ${bytes - remaining}/$bytes body bytes")
            sink.write(buf, 0, n)
            remaining -= n
        }
    }

    companion object {
        const val CHROME_DEVTOOLS_SOCKET = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET
        const val ADB_KEYS_PATH = "/data/misc/adb/adb_keys"
        private const val BUFFER = 4096
        private const val TAG = "ChromeDevtoolsUS"
        private const val MIN_PSK_LENGTH = 6
        private const val ADBD_LEGACY_PORT = 5555
        private const val PAIR_PORT_POLL_MS = 5_000L
        private const val PAIR_PORT_POLL_INTERVAL_MS = 100L

        private val CRLFCRLF = byteArrayOf(0x0d, 0x0a, 0x0d, 0x0a)

        internal fun indexOfDoubleCrlf(bytes: ByteArray): Int {
            outer@ for (i in 0..bytes.size - 4) {
                for (j in 0 until 4) if (bytes[i + j] != CRLFCRLF[j]) continue@outer
                return i
            }
            return -1
        }

        internal fun parseContentLength(bytes: ByteArray, headerEnd: Int): Int? {
            val headers = String(bytes, 0, headerEnd, Charsets.ISO_8859_1)
            for (line in headers.split("\r\n")) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                if (!name.equals("Content-Length", ignoreCase = true)) continue
                val value = line.substring(idx + 1).trim()
                return value.toIntOrNull()
            }
            return null
        }
    }
}
