package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class AdbWireProtocolClientTest {

    private lateinit var dir: File
    private lateinit var client: AdbWireProtocolClient

    @Before fun setUp() {
        dir = Files.createTempDirectory("adb-wire-test").toFile()
        client = AdbWireProtocolClient(AdbCryptoKeyStore(dir))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `runExchange opens local-abstract, returns concatenated WRTE payload`() {
        val server = FakeAdbd()
        val response = client.runExchange(
            input = server.toClient(),
            out = server.fromClient(),
            destination = "chrome_devtools_remote",
            request = "GET /json HTTP/1.1\r\n\r\n".toByteArray(),
        )

        assertThat(String(response, Charsets.UTF_8)).isEqualTo("PONG-PART-1PONG-PART-2")

        val client2Server = parseFrames(server.clientWroteBytes())
        // Post-mTLS the daemon SPEAKS FIRST (sends A_CNXN). Client sends OPEN, then a "ready"
        // OKAY (mandatory — without it adbd's local_socket never enables FDE_READ on the chrome
        // side and stalls), then WRTE.
        assertThat(client2Server[0].command).isEqualTo(AdbProtocol.A_OPEN)
        assertThat(client2Server[1].command).isEqualTo(AdbProtocol.A_OKAY)
        assertThat(client2Server[2].command).isEqualTo(AdbProtocol.A_WRTE)
        assertThat(String(client2Server[2].payload, Charsets.UTF_8))
            .isEqualTo("GET /json HTTP/1.1\r\n\r\n")
        // After receiving each server WRTE, client must OKAY back. The trailing A_CLSE the
        // production runExchange sends is wrapped in runCatching - it's best-effort cleanup
        // after we've already seen the peer's A_CLSE, so we don't assert on it (in this in-
        // process pipe setup the worker has already exited and the pipe is broken).
        val tailCommands = client2Server.drop(3).map { it.command }
        assertThat(tailCommands).containsAtLeastElementsIn(
            listOf(AdbProtocol.A_OKAY, AdbProtocol.A_OKAY)
        ).inOrder()
    }

    @Test
    fun `runExchange throws when destination is rejected with A_CLSE`() {
        val server = FakeAdbd(rejectOpen = true)
        try {
            client.runExchange(
                input = server.toClient(),
                out = server.fromClient(),
                destination = "no_such_socket",
                request = "irrelevant".toByteArray(),
            )
            error("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().contains("destination rejected: no_such_socket")
        }
    }

    @Test
    fun `runExchange throws on unexpected A_AUTH instead of A_CNXN`() {
        val server = FakeAdbd(sendAuthInsteadOfCnxn = true)
        try {
            client.runExchange(
                input = server.toClient(),
                out = server.fromClient(),
                destination = "x",
                request = ByteArray(1),
            )
            error("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().contains("A_AUTH")
        }
    }

    /**
     * In-process fake adbd, post-mTLS view: the daemon speaks first by sending A_CNXN. After
     * receiving the client's A_OPEN it replies A_OKAY + two A_WRTE chunks + A_CLSE. The
     * pre-mTLS A_CNXN/A_STLS dance is handled by [AdbTlsClient] (not exercised here).
     */
    private class FakeAdbd(
        private val rejectOpen: Boolean = false,
        private val sendAuthInsteadOfCnxn: Boolean = false,
    ) {
        private val toClientSrc = PipedOutputStream()
        private val toClientSink = PipedInputStream(toClientSrc, 64 * 1024)

        private val fromClientSink = ByteArrayOutputStream()
        private val fromClientFork = TeeOutputStream(PipedOutputStream(), fromClientSink)
        private val fromClientReader = PipedInputStream(fromClientFork.first as PipedOutputStream, 64 * 1024)

        private val worker = Thread({ run() }, "fake-adbd").apply {
            isDaemon = true
            start()
        }

        fun toClient(): java.io.InputStream = toClientSink
        fun fromClient(): java.io.OutputStream = fromClientFork
        fun clientWroteBytes(): ByteArray {
            worker.join(2_000)
            return fromClientSink.toByteArray()
        }

        private fun run() {
            try {
                if (sendAuthInsteadOfCnxn) {
                    AdbProtocol.Message.write(
                        toClientSrc, AdbProtocol.A_AUTH, 1, 0, ByteArray(20),
                    )
                    return
                }

                // Daemon speaks first: send A_CNXN.
                AdbProtocol.Message.write(
                    toClientSrc,
                    AdbProtocol.A_CNXN,
                    AdbProtocol.A_VERSION_SKIP_CHECKSUM,
                    AdbProtocol.A_MAX_PAYLOAD,
                    "device::test".toByteArray() + 0.toByte(),
                )

                // Read client OPEN
                val open = AdbProtocol.Message.read(fromClientReader)
                check(open.command == AdbProtocol.A_OPEN)

                if (rejectOpen) {
                    AdbProtocol.Message.write(toClientSrc, AdbProtocol.A_CLSE, 0, open.arg0, ByteArray(0))
                    return
                }
                val remoteId = 42
                AdbProtocol.Message.write(toClientSrc, AdbProtocol.A_OKAY, remoteId, open.arg0, ByteArray(0))

                // Client must send a "ready" OKAY back so adbd's local_socket calls peer->ready()
                // and starts reading from chrome's local-abstract socket. Consume it before WRTE.
                val readyOkay = AdbProtocol.Message.read(fromClientReader)
                check(readyOkay.command == AdbProtocol.A_OKAY) {
                    "expected post-OPEN ready OKAY, got 0x${"%08x".format(readyOkay.command)}"
                }

                // Read client WRTE -> ack
                val wrte = AdbProtocol.Message.read(fromClientReader)
                check(wrte.command == AdbProtocol.A_WRTE)
                AdbProtocol.Message.write(toClientSrc, AdbProtocol.A_OKAY, remoteId, open.arg0, ByteArray(0))

                // Server emits two payload frames, expecting OKAY for each
                AdbProtocol.Message.write(
                    toClientSrc, AdbProtocol.A_WRTE, remoteId, open.arg0, "PONG-PART-1".toByteArray(),
                )
                val ack1 = AdbProtocol.Message.read(fromClientReader)
                check(ack1.command == AdbProtocol.A_OKAY)
                AdbProtocol.Message.write(
                    toClientSrc, AdbProtocol.A_WRTE, remoteId, open.arg0, "PONG-PART-2".toByteArray(),
                )
                val ack2 = AdbProtocol.Message.read(fromClientReader)
                check(ack2.command == AdbProtocol.A_OKAY)

                AdbProtocol.Message.write(toClientSrc, AdbProtocol.A_CLSE, remoteId, open.arg0, ByteArray(0))
            } catch (_: Throwable) {
                // Pipe closed by client teardown - fine.
            } finally {
                runCatching { toClientSrc.close() }
            }
        }
    }

    private fun parseFrames(bytes: ByteArray): List<AdbProtocol.Message> {
        val input = ByteArrayInputStream(bytes)
        val out = mutableListOf<AdbProtocol.Message>()
        while (input.available() > 0) {
            out += AdbProtocol.Message.read(input)
        }
        return out
    }

    /** Pair-like tee that fans writes to both downstream sinks. */
    private class TeeOutputStream(
        val first: java.io.OutputStream,
        val second: java.io.OutputStream,
    ) : java.io.OutputStream() {
        override fun write(b: Int) {
            first.write(b); second.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            first.write(b, off, len); second.write(b, off, len)
        }

        override fun flush() {
            first.flush(); second.flush()
        }

        override fun close() {
            first.close(); second.close()
        }
    }
}
