package ai.closepaw.browser.cdp.shizuku

import ai.closepaw.browser.cdp.RelayAuthToken
import ai.closepaw.browser.cdp.wireless.ProcNetTcpListeners
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
 * can usually reach that socket on AOSP-spec builds; locked OEM builds (e.g., nubia P0110)
 * commonly deny it via SELinux, in which case the cascade falls back to
 * [ai.closepaw.browser.cdp.wireless.WirelessAdbSelfPairTransport].
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

    /**
     * Tri-state read state of `/data/misc/adb/adb_keys` — see AIDL doc for the encoding. Uses
     * `Os.lstat` to disambiguate ENOENT (which `Os.access` collapses with EACCES into a single
     * boolean false) from real "permission denied". The lstat / access pair has a TOCTOU window
     * that's irrelevant here: we're asking "what's the most likely state right now", not
     * synchronizing against an adbd write.
     */
    override fun adbKeysReadStatus(): Int = try {
        Os.lstat(ADB_KEYS_PATH)
        // File exists; check whether shell uid can read it.
        if (Os.access(ADB_KEYS_PATH, OsConstants.R_OK)) {
            ADB_KEYS_STATUS_READABLE
        } else {
            ADB_KEYS_STATUS_EACCES
        }
    } catch (e: android.system.ErrnoException) {
        when (e.errno) {
            OsConstants.ENOENT -> ADB_KEYS_STATUS_MISSING
            // EACCES from lstat can happen if /data/misc/adb itself is unreadable to us; same
            // semantics as "file exists but we can't see it" for cache purposes.
            OsConstants.EACCES -> ADB_KEYS_STATUS_EACCES
            else -> {
                Log.w(TAG, "adbKeysReadStatus: unexpected errno=${e.errno}: ${e.message}")
                ADB_KEYS_STATUS_OTHER
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "adbKeysReadStatus: unexpected: ${e.message}")
        ADB_KEYS_STATUS_OTHER
    }

    override fun writeAdbKeys(content: String): Boolean = try {
        // Atomic-rename for crash safety only — adbd reads /data/misc/adb/adb_keys at every
        // auth (libadbd_auth iterates the file when a peer sends A_AUTH SIGNATURE), so the new
        // key set takes effect on the next handshake without an adbd restart. Best-effort
        // metadata restore: we capture the existing file's mode/uid/gid/SELinux context and
        // try to put them back on the new inode. Several of those steps need root or
        // CAP_CHOWN, so we log + continue on failure rather than aborting — the file is
        // recoverable (the framework re-writes it via AdbDebuggingManager on the next pair).
        val target = File(ADB_KEYS_PATH)
        val parent = target.parentFile
            ?: throw IOException("$ADB_KEYS_PATH has no parent directory")
        val targetStat = try {
            Os.stat(target.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "writeAdbKeys: stat($ADB_KEYS_PATH) failed: ${e.message}")
            return false
        }
        val tmp = File(parent, "adb_keys.closepaw.tmp")
        try {
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(content.toByteArray(Charsets.US_ASCII))
                raf.fd.sync()
            }
            // st_mode includes file-type bits; mask to permission bits.
            runCatching { Os.chmod(tmp.absolutePath, targetStat.st_mode and 0xfff) }
                .onFailure { Log.w(TAG, "writeAdbKeys: chmod failed: ${it.message}") }
            runCatching { Os.chown(tmp.absolutePath, targetStat.st_uid, targetStat.st_gid) }
                .onFailure {
                    // Expected on non-root: shell uid lacks CAP_CHOWN. The dir's policy still
                    // labels new files with adb_keys_file:s0 so adbd can read either way.
                    Log.i(TAG, "writeAdbKeys: chown to ${targetStat.st_uid}:${targetStat.st_gid} failed (expected as non-root): ${it.message}")
                }
            runCatching { restoreSelinuxContext(tmp.absolutePath) }
                .onFailure { Log.w(TAG, "writeAdbKeys: restorecon failed: ${it.message}") }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            // fsync parent dir so the rename itself is durable across crash.
            runCatching {
                val dirFd = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
                try { Os.fsync(dirFd) } finally { Os.close(dirFd) }
            }.onFailure { Log.w(TAG, "writeAdbKeys: parent dir fsync failed: ${it.message}") }
            true
        } finally {
            // If rename succeeded the tmp is gone; if it didn't, drop the orphan.
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "writeAdbKeys failed: ${e.message}", e)
        false
    }

    /**
     * Hidden-API call to `android.os.SELinux.restorecon(String)`; relabels [path] back to the
     * default file context for its directory. HiddenApiBypass is engaged process-wide so this
     * resolves on Android P+. Returns true when relabel applied, false when restorecon
     * couldn't (no fcontext entry, or shell lacks the relabel domain transition).
     */
    private fun restoreSelinuxContext(path: String): Boolean = try {
        val cls = Class.forName("android.os.SELinux")
        val m = cls.getMethod("restorecon", String::class.java)
        m.invoke(null, path) as Boolean
    } catch (e: ReflectiveOperationException) {
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
    @Volatile
    private var expectedToken: String = ""

    @Synchronized
    override fun startTcpRelay(authToken: String?): Int {
        require(!authToken.isNullOrEmpty()) { "authToken must not be empty" }
        if (relayPort != 0) {
            // Idempotent for the same token; reject token rotation so a buggy second caller
            // can't silently shadow the first session's auth boundary.
            check(RelayAuthToken.verify(expectedToken, authToken)) {
                "startTcpRelay called with a different token than the one already in use"
            }
            return relayPort
        }
        expectedToken = authToken
        // Bind 127.0.0.1:0 — kernel chooses a free port. Token in WS Upgrade header gates access.
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        relayServer = server
        relayPort = server.localPort
        Thread({ relayAcceptLoop(server) }, "cdp-relay-accept").apply {
            isDaemon = true
        }.start()
        Log.i(TAG, "TCP relay started on 127.0.0.1:$relayPort (token-gated)")
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
            client.tcpNoDelay = true
            // Slowloris defense: TOTAL wall-clock deadline for the WS Upgrade head. The helper
            // walks per-read soTimeout down with the budget, so a 1-byte-per-tick dribbler trips
            // the deadline once cumulative wall time crosses it (per-read soTimeout alone is
            // defeatable by sending one byte just under the cap each iteration). Restored to 0
            // (infinite) after auth so the long-lived proxied stream isn't capped.
            client.soTimeout = RelayAuthToken.PRE_AUTH_DEADLINE_MS
            // Token gate: read the WS Upgrade request line + headers before connecting upstream.
            // A connection from any local app — or from this app over the wrong code path — is
            // 403'd here, never reaching Chrome's CDP.
            val parsed = try {
                RelayAuthToken.readHttpRequestHead(
                    input = client.getInputStream(),
                    totalDeadlineMs = RelayAuthToken.PRE_AUTH_DEADLINE_MS,
                    setReadTimeout = { client.soTimeout = it },
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "relay token gate: pre-auth deadline exceeded (slowloris?): ${e.message}")
                RelayAuthToken.write408(client.getOutputStream())
                return
            }
            if (parsed !is RelayAuthToken.ParseResult.Success) {
                Log.w(TAG, "relay token gate: rejected (${(parsed as RelayAuthToken.ParseResult.Failure).reason})")
                RelayAuthToken.write403(client.getOutputStream())
                return
            }
            if (!RelayAuthToken.verify(expectedToken, parsed.token)) {
                Log.w(TAG, "relay token gate: rejected (token missing or mismatched)")
                RelayAuthToken.write403(client.getOutputStream())
                return
            }
            // Auth OK — restore the post-open idle behavior. The bidirectional pump runs until
            // either side closes; capping it would chop long-running CDP sessions.
            client.soTimeout = 0
            abstractSocket.connect(
                LocalSocketAddress(CHROME_DEVTOOLS_SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )
            // Replay the buffered request bytes upstream verbatim. Chrome's HTTP server ignores
            // unknown headers, so [RelayAuthToken.HEADER_NAME] passes through harmlessly.
            abstractSocket.outputStream.write(parsed.bytes)
            abstractSocket.outputStream.flush()
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
        // Stable wire constants for [adbKeysReadStatus]; mirror the AIDL doc.
        const val ADB_KEYS_STATUS_READABLE = 0
        const val ADB_KEYS_STATUS_EACCES = 1
        const val ADB_KEYS_STATUS_MISSING = 2
        const val ADB_KEYS_STATUS_OTHER = 3
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
