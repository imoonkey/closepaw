package ai.closepaw.browser.cdp.wireless

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AOSP `system/core/adb/protocol.txt` wire framing. Six little-endian uint32 fields
 * followed by an optional payload. Checksum is unused since A_VERSION_SKIP_CHECKSUM
 * (0x01000001) — adbd always sends 0 and ignores the field on reads.
 */
internal object AdbProtocol {
    const val A_CNXN = 0x4E584E43
    const val A_OPEN = 0x4E45504F
    const val A_OKAY = 0x59414B4F
    const val A_CLSE = 0x45534C43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534C5453

    const val A_VERSION_SKIP_CHECKSUM = 0x01000001
    const val A_MAX_PAYLOAD = 1024 * 1024
    const val HEADER_SIZE = 24

    private val EMPTY = ByteArray(0)

    data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Message &&
                command == other.command &&
                arg0 == other.arg0 &&
                arg1 == other.arg1 &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var h = command
            h = 31 * h + arg0
            h = 31 * h + arg1
            h = 31 * h + payload.contentHashCode()
            return h
        }

        companion object {
            fun read(input: InputStream): Message {
                val header = readExactly(input, HEADER_SIZE)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = buf.int
                val arg0 = buf.int
                val arg1 = buf.int
                val length = buf.int
                buf.int // checksum (ignored)
                val magic = buf.int
                if (magic != command.inv()) {
                    throw IOException("bad magic: cmd=0x${"%08x".format(command)} magic=0x${"%08x".format(magic)}")
                }
                if (length < 0 || length > A_MAX_PAYLOAD) {
                    throw IOException("payload length out of range: $length")
                }
                val payload = if (length == 0) EMPTY else readExactly(input, length)
                return Message(command, arg0, arg1, payload)
            }

            fun write(
                output: OutputStream,
                command: Int,
                arg0: Int,
                arg1: Int,
                payload: ByteArray,
            ) {
                if (payload.size > A_MAX_PAYLOAD) {
                    throw IOException("payload exceeds A_MAX_PAYLOAD: ${payload.size}")
                }
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(command)
                header.putInt(arg0)
                header.putInt(arg1)
                header.putInt(payload.size)
                header.putInt(0)
                header.putInt(command.inv())
                output.write(header.array())
                if (payload.isNotEmpty()) output.write(payload)
                output.flush()
            }

            private fun readExactly(input: InputStream, n: Int): ByteArray {
                val out = ByteArray(n)
                var read = 0
                while (read < n) {
                    val r = input.read(out, read, n - read)
                    if (r < 0) throw EOFException("EOF after $read/$n bytes")
                    read += r
                }
                return out
            }
        }
    }

    fun cnxn(maxPayload: Int = A_MAX_PAYLOAD, banner: String = "host::"): Message =
        Message(A_CNXN, A_VERSION_SKIP_CHECKSUM, maxPayload, banner.toNulTerminatedBytes())

    fun openLocalAbstract(localId: Int, name: String): Message =
        Message(A_OPEN, localId, 0, "localabstract:$name".toNulTerminatedBytes())

    fun okay(localId: Int, remoteId: Int): Message =
        Message(A_OKAY, localId, remoteId, EMPTY)

    fun close(localId: Int, remoteId: Int): Message =
        Message(A_CLSE, localId, remoteId, EMPTY)

    fun write(localId: Int, remoteId: Int, payload: ByteArray): Message =
        Message(A_WRTE, localId, remoteId, payload)

    private fun String.toNulTerminatedBytes(): ByteArray {
        val raw = toByteArray(Charsets.UTF_8)
        val out = ByteArray(raw.size + 1)
        System.arraycopy(raw, 0, out, 0, raw.size)
        return out
    }
}
