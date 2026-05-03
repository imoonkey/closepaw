package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPairingPacketTest {

    @Test
    fun `write then read round-trip preserves type and payload`() {
        val payload = ByteArray(64) { it.toByte() }
        val out = ByteArrayOutputStream()
        AdbPairingPacket.write(out, AdbPairingPacket.TYPE_SPAKE2_MSG, payload)

        val read = AdbPairingPacket.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.type).isEqualTo(AdbPairingPacket.TYPE_SPAKE2_MSG)
        assertThat(read.payload).isEqualTo(payload)
    }

    @Test
    fun `peer info round trip with maximum payload`() {
        val payload = ByteArray(AdbPairingPacket.MAX_PAYLOAD) { (it and 0x7F).toByte() }
        val out = ByteArrayOutputStream()
        AdbPairingPacket.write(out, AdbPairingPacket.TYPE_PEER_INFO, payload)

        val read = AdbPairingPacket.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(read.type).isEqualTo(AdbPairingPacket.TYPE_PEER_INFO)
        assertThat(read.payload.size).isEqualTo(AdbPairingPacket.MAX_PAYLOAD)
    }

    @Test
    fun `read rejects unsupported version`() {
        val bytes = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00)
        val ex = assertThrows(IOException::class.java) {
            AdbPairingPacket.read(ByteArrayInputStream(bytes))
        }
        assertThat(ex).hasMessageThat().contains("version")
    }

    @Test
    fun `read rejects payload size over MAX_PAYLOAD`() {
        // version=1 type=0 payload=16385 (big-endian)
        val bytes = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x40, 0x01)
        val ex = assertThrows(IOException::class.java) {
            AdbPairingPacket.read(ByteArrayInputStream(bytes))
        }
        assertThat(ex).hasMessageThat().contains("payload length")
    }

    @Test
    fun `read rejects unknown type`() {
        val bytes = byteArrayOf(0x01, 0x05, 0x00, 0x00, 0x00, 0x00)
        val ex = assertThrows(IOException::class.java) {
            AdbPairingPacket.read(ByteArrayInputStream(bytes))
        }
        assertThat(ex).hasMessageThat().contains("type")
    }

    @Test
    fun `header is big-endian`() {
        // payload of 0x0102 (258) bytes — verify header bytes are 00 00 01 02 (BE)
        val payload = ByteArray(0x0102) { 0x00 }
        val out = ByteArrayOutputStream()
        AdbPairingPacket.write(out, AdbPairingPacket.TYPE_SPAKE2_MSG, payload)
        val bytes = out.toByteArray()
        assertThat(bytes[0]).isEqualTo(0x01.toByte())  // version
        assertThat(bytes[1]).isEqualTo(0x00.toByte())  // type
        assertThat(bytes[2]).isEqualTo(0x00.toByte())
        assertThat(bytes[3]).isEqualTo(0x00.toByte())
        assertThat(bytes[4]).isEqualTo(0x01.toByte())
        assertThat(bytes[5]).isEqualTo(0x02.toByte())
    }

    @Test
    fun `read truncated header throws`() {
        val bytes = byteArrayOf(0x01, 0x00, 0x00)
        assertThrows(EOFException::class.java) {
            AdbPairingPacket.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `write rejects oversize payload`() {
        val payload = ByteArray(AdbPairingPacket.MAX_PAYLOAD + 1)
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket.write(ByteArrayOutputStream(), AdbPairingPacket.TYPE_PEER_INFO, payload)
        }
    }

    @Test
    fun `write rejects unknown type`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket.write(ByteArrayOutputStream(), 0x05.toByte(), ByteArray(1))
        }
    }
}
