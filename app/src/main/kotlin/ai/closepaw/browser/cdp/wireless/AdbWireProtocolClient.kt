package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_CLSE
import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_CNXN
import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_MAX_PAYLOAD
import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_OKAY
import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_STLS
import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_WRTE
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Minimal mTLS client for the AOSP wireless ADB protocol after pairing. Speaks just enough of
 * the wire protocol to open one local-abstract stream — enough to relay DevTools traffic to
 * `chrome_devtools_remote`. Single-stream by design (localId always 1); re-open per call.
 */
class AdbWireProtocolClient(
    private val keyStore: AdbCryptoKeyStore,
) {
    suspend fun exchange(
        host: String,
        tlsPort: Int,
        destination: String,
        request: ByteArray,
        timeoutMs: Int = 10_000,
    ): ByteArray = runInterruptible(Dispatchers.IO) {
        val channel = AdbTlsClient.connectWithStls(host, tlsPort, keyStore, timeoutMs)
        try {
            runExchange(channel.inputStream, channel.outputStream, destination, request)
        } finally {
            runCatching { channel.close() }
        }
    }

    suspend fun openLocalAbstract(
        host: String,
        tlsPort: Int,
        destination: String,
        timeoutMs: Int = 10_000,
    ): AdbStream = runInterruptible(Dispatchers.IO) {
        val channel = AdbTlsClient.connectWithStls(host, tlsPort, keyStore, timeoutMs)
        try {
            val (remoteId, maxPayload) = handshakeAndOpen(channel.inputStream, channel.outputStream, destination)
            AdbStream(channel, LOCAL_ID, remoteId, maxPayload)
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    internal fun runExchange(
        input: InputStream,
        out: OutputStream,
        destination: String,
        request: ByteArray,
    ): ByteArray {
        val (remoteId, maxPayload) = handshakeAndOpen(input, out, destination)
        sendChunked(input, out, LOCAL_ID, remoteId, request, maxPayload)
        val response = readUntilHttpComplete(input, out, LOCAL_ID, remoteId)
        runCatching { AdbProtocol.Message.write(out, A_CLSE, LOCAL_ID, remoteId, EMPTY) }
        return response
    }

    private fun handshakeAndOpen(input: InputStream, out: OutputStream, destination: String): Pair<Int, Int> {
        // Post-mTLS the daemon SPEAKS FIRST: adbd_wifi_secure_connect calls send_connect() which
        // writes A_CNXN to us. We must NOT pre-emptively send our own A_CNXN — adbd's
        // handle_packet for a duplicate runs handle_offline, fires the disconnect callback, and
        // emits A_STLS over the encrypted channel ("unexpected reply to OPEN: cmd=0x534c5453").
        val peerCnxn = readExpecting(input, A_CNXN, allowAuth = false)
        android.util.Log.i(TAG, "post-TLS got A_CNXN bannerLen=${peerCnxn.payload.size}")
        val maxPayload = minOf(A_MAX_PAYLOAD, peerCnxn.arg1.takeIf { it > 0 } ?: A_MAX_PAYLOAD)

        // Settle so adbd finishes attaching the transport (registering with the asocket service
        // map) before we OPEN — otherwise the OPEN can race adbd's post-send_connect setup and
        // the local-abstract proxy never starts forwarding.
        Thread.sleep(POST_CNXN_SETTLE_MS)

        val open = AdbProtocol.openLocalAbstract(LOCAL_ID, destination)
        AdbProtocol.Message.write(out, open.command, open.arg0, open.arg1, open.payload)

        val reply = AdbProtocol.Message.read(input)
        when (reply.command) {
            A_OKAY -> {
                if (reply.arg1 != LOCAL_ID) {
                    throw IOException("OKAY localId mismatch: got=${reply.arg1} expected=$LOCAL_ID")
                }
                val remoteId = reply.arg0
                android.util.Log.i(TAG, "A_OPEN(\"$destination\") -> OKAY remoteId=$remoteId")
                // Mirror host adb: ack the daemon's OKAY before sending data so adbd's
                // local_socket calls peer->ready() and engages FDE_READ on the chrome side.
                AdbProtocol.Message.write(out, A_OKAY, LOCAL_ID, remoteId, EMPTY)
                return remoteId to maxPayload
            }
            A_CLSE -> throw IOException("destination rejected: $destination")
            else -> throw IOException("unexpected reply to OPEN: cmd=0x${"%08x".format(reply.command)}")
        }
    }

    private fun readExpecting(input: InputStream, expected: Int, allowAuth: Boolean): AdbProtocol.Message {
        while (true) {
            val msg = AdbProtocol.Message.read(input)
            when {
                msg.command == expected -> return msg
                msg.command == A_STLS -> throw IOException("unexpected A_STLS on TLS port")
                !allowAuth && msg.command == AdbProtocol.A_AUTH ->
                    throw IOException("unexpected A_AUTH on TLS port (pubkey not in adb_keys?)")
                else -> throw IOException("unexpected cmd while waiting for 0x${"%08x".format(expected)}: 0x${"%08x".format(msg.command)}")
            }
        }
    }

    private fun sendChunked(
        input: InputStream,
        out: OutputStream,
        localId: Int,
        remoteId: Int,
        payload: ByteArray,
        maxPayload: Int,
    ) {
        if (payload.isEmpty()) return
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxPayload, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            AdbProtocol.Message.write(out, A_WRTE, localId, remoteId, chunk)
            val ack = AdbProtocol.Message.read(input)
            if (ack.command == A_CLSE) throw IOException("peer closed during write")
            if (ack.command != A_OKAY || ack.arg0 != remoteId || ack.arg1 != localId) {
                throw IOException(
                    "expected OKAY(remote=$remoteId,local=$localId); got " +
                        "cmd=0x${"%08x".format(ack.command)} arg0=${ack.arg0} arg1=${ack.arg1}"
                )
            }
            offset = end
        }
    }

    /**
     * Drain incoming A_WRTE frames until the embedded HTTP/1.1 response is complete or the peer
     * closes. Chrome's `/json/version` ignores `Connection: close` and leaves the socket idle
     * after responding, so adbd never sees chrome's EOF and never sends A_CLSE; falling back to
     * Content-Length lets us return as soon as the body is fully drained — same as host adb's
     * curl behavior.
     *
     * Synchronous DevTools HTTP only; streaming clients should use [openLocalAbstract] /
     * [AdbStream].
     */
    private fun readUntilHttpComplete(input: InputStream, out: OutputStream, localId: Int, remoteId: Int): ByteArray {
        val sink = ByteArrayOutputStream()
        var headerEnd = -1
        var contentLength = -1
        while (true) {
            val msg = AdbProtocol.Message.read(input)
            when (msg.command) {
                A_WRTE -> {
                    if (msg.arg0 != remoteId || msg.arg1 != localId) {
                        throw IOException("WRTE id mismatch: arg0=${msg.arg0} arg1=${msg.arg1}")
                    }
                    sink.write(msg.payload)
                    AdbProtocol.Message.write(out, A_OKAY, localId, remoteId, EMPTY)
                    val current = sink.toByteArray()
                    if (headerEnd < 0) {
                        headerEnd = findHeaderEnd(current)
                        if (headerEnd >= 0) {
                            contentLength = parseContentLength(current, headerEnd)
                        }
                    }
                    if (headerEnd >= 0 && contentLength >= 0 && current.size >= headerEnd + 4 + contentLength) {
                        return current
                    }
                }
                A_CLSE -> return sink.toByteArray()
                A_OKAY -> Unit
                else -> throw IOException("unexpected cmd in read loop: 0x${"%08x".format(msg.command)}")
            }
        }
    }

    private fun findHeaderEnd(buf: ByteArray): Int {
        if (buf.size < 4) return -1
        for (i in 0..buf.size - 4) {
            if (buf[i] == 0x0D.toByte() && buf[i + 1] == 0x0A.toByte() &&
                buf[i + 2] == 0x0D.toByte() && buf[i + 3] == 0x0A.toByte()
            ) return i
        }
        return -1
    }

    private fun parseContentLength(buf: ByteArray, headerEnd: Int): Int {
        val headerText = String(buf, 0, headerEnd, Charsets.ISO_8859_1)
        for (line in headerText.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                return line.substring(idx + 1).trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }

    companion object {
        const val LOCAL_ID = 1
        private val EMPTY = ByteArray(0)
        private const val TAG = "AdbWireProto"
        private const val POST_CNXN_SETTLE_MS = 200L
    }
}

/**
 * Bidirectional byte channel over a single ADB local-abstract stream. Reads and writes are
 * serialized through the underlying TLS channel from a single reader thread; concurrent writes
 * from multiple threads are not supported.
 */
class AdbStream internal constructor(
    private val socket: AdbTlsClient.TlsChannel,
    private val localId: Int,
    private val remoteId: Int,
    private val negotiatedMaxPayload: Int,
) : Closeable {

    private val pumpInput: InputStream = socket.inputStream
    private val pumpOutput: OutputStream = socket.outputStream
    private val incoming = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val writeAck = java.util.concurrent.SynchronousQueue<Boolean>()

    @Volatile private var closed = false
    @Volatile private var readerError: Throwable? = null

    private val reader = Thread({ runReader() }, "adb-stream-$localId").apply {
        isDaemon = true
        start()
    }

    val inputStream: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n == -1) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val buf = ensureBuffer() ?: return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            if (pos == buf.size) current = null
            return n
        }

        private fun ensureBuffer(): ByteArray? {
            val cur = current
            if (cur != null && pos < cur.size) return cur
            val next = incoming.take()
            if (next === EOF) {
                readerError?.let { throw IOException("reader failed", it) }
                return null
            }
            current = next
            pos = 0
            return next
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xff).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream closed")
            var sent = 0
            while (sent < len) {
                val chunk = minOf(negotiatedMaxPayload, len - sent)
                val payload = b.copyOfRange(off + sent, off + sent + chunk)
                synchronized(pumpOutput) {
                    AdbProtocol.Message.write(pumpOutput, A_WRTE, localId, remoteId, payload)
                }
                val ok = writeAck.take()
                if (!ok) throw IOException("peer closed during write")
                sent += chunk
            }
        }

        override fun flush() {
            synchronized(pumpOutput) { pumpOutput.flush() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            synchronized(pumpOutput) {
                AdbProtocol.Message.write(pumpOutput, A_CLSE, localId, remoteId, ByteArray(0))
            }
        }
        runCatching { socket.close() }
        reader.interrupt()
        incoming.offer(EOF)
        writeAck.offer(false)
    }

    private fun runReader() {
        try {
            while (!closed) {
                val msg = AdbProtocol.Message.read(pumpInput)
                when (msg.command) {
                    A_WRTE -> {
                        if (msg.arg0 == remoteId && msg.arg1 == localId && msg.payload.isNotEmpty()) {
                            incoming.put(msg.payload)
                        }
                        synchronized(pumpOutput) {
                            AdbProtocol.Message.write(pumpOutput, A_OKAY, localId, remoteId, ByteArray(0))
                        }
                    }
                    A_OKAY -> {
                        if (msg.arg0 == remoteId && msg.arg1 == localId) writeAck.offer(true)
                    }
                    A_CLSE -> {
                        closed = true
                        incoming.offer(EOF)
                        writeAck.offer(false)
                        return
                    }
                    else -> { /* ignore noise */ }
                }
            }
        } catch (t: Throwable) {
            if (!closed) readerError = t
            incoming.offer(EOF)
            writeAck.offer(false)
        }
    }

    companion object {
        private val EOF = ByteArray(0)
    }
}
