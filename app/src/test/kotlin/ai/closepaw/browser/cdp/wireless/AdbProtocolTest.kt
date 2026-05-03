package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class AdbProtocolTest {

    @Test
    fun `roundtrip preserves all fields including payload`() {
        val payload = "hello world".toByteArray()
        val out = ByteArrayOutputStream()
        AdbProtocol.Message.write(out, AdbProtocol.A_WRTE, 7, 9, payload)

        val msg = AdbProtocol.Message.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(msg.command).isEqualTo(AdbProtocol.A_WRTE)
        assertThat(msg.arg0).isEqualTo(7)
        assertThat(msg.arg1).isEqualTo(9)
        assertThat(msg.payload).isEqualTo(payload)
    }

    @Test
    fun `roundtrip empty payload`() {
        val out = ByteArrayOutputStream()
        AdbProtocol.Message.write(out, AdbProtocol.A_OKAY, 1, 2, ByteArray(0))
        val msg = AdbProtocol.Message.read(ByteArrayInputStream(out.toByteArray()))
        assertThat(msg.command).isEqualTo(AdbProtocol.A_OKAY)
        assertThat(msg.arg0).isEqualTo(1)
        assertThat(msg.arg1).isEqualTo(2)
        assertThat(msg.payload).isEmpty()
    }

    @Test
    fun `bad magic is rejected`() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(AdbProtocol.A_OKAY)
        header.putInt(0)
        header.putInt(0)
        header.putInt(0)
        header.putInt(0)
        header.putInt(0xDEADBEEF.toInt()) // bogus magic
        try {
            AdbProtocol.Message.read(ByteArrayInputStream(header.array()))
            error("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().contains("bad magic")
        }
    }

    @Test
    fun `payload length over max is rejected`() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = AdbProtocol.A_WRTE
        header.putInt(cmd)
        header.putInt(0)
        header.putInt(0)
        header.putInt(AdbProtocol.A_MAX_PAYLOAD + 1)
        header.putInt(0)
        header.putInt(cmd.inv())
        try {
            AdbProtocol.Message.read(ByteArrayInputStream(header.array()))
            error("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().contains("out of range")
        }
    }

    @Test
    fun `negative payload length is rejected`() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = AdbProtocol.A_WRTE
        header.putInt(cmd)
        header.putInt(0)
        header.putInt(0)
        header.putInt(-1)
        header.putInt(0)
        header.putInt(cmd.inv())
        try {
            AdbProtocol.Message.read(ByteArrayInputStream(header.array()))
            error("expected IOException")
        } catch (e: IOException) {
            assertThat(e).hasMessageThat().contains("out of range")
        }
    }

    @Test
    fun `cnxn factory yields correct bytes`() {
        val cnxn = AdbProtocol.cnxn()
        assertThat(cnxn.command).isEqualTo(AdbProtocol.A_CNXN)
        assertThat(cnxn.arg0).isEqualTo(AdbProtocol.A_VERSION_SKIP_CHECKSUM)
        assertThat(cnxn.arg1).isEqualTo(AdbProtocol.A_MAX_PAYLOAD)
        // "host::" + NUL = 7 bytes
        assertThat(cnxn.payload).isEqualTo("host::".toByteArray() + 0.toByte())
        assertThat(cnxn.payload.size).isEqualTo(7)
    }

    @Test
    fun `openLocalAbstract payload is null-terminated localabstract prefix`() {
        val open = AdbProtocol.openLocalAbstract(1, "chrome_devtools_remote")
        assertThat(open.command).isEqualTo(AdbProtocol.A_OPEN)
        assertThat(open.arg0).isEqualTo(1)
        assertThat(open.arg1).isEqualTo(0)
        val expected = "localabstract:chrome_devtools_remote".toByteArray() + 0.toByte()
        assertThat(open.payload).isEqualTo(expected)
        assertThat(open.payload.last()).isEqualTo(0.toByte())
    }

    @Test
    fun `header is little-endian with valid magic`() {
        val out = ByteArrayOutputStream()
        AdbProtocol.Message.write(out, AdbProtocol.A_OPEN, 0x11223344, 0x55667788, ByteArray(0))
        val raw = out.toByteArray()
        assertThat(raw.size).isEqualTo(24)

        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(header.int).isEqualTo(AdbProtocol.A_OPEN)
        assertThat(header.int).isEqualTo(0x11223344)
        assertThat(header.int).isEqualTo(0x55667788)
        assertThat(header.int).isEqualTo(0)            // length
        assertThat(header.int).isEqualTo(0)            // checksum
        assertThat(header.int).isEqualTo(AdbProtocol.A_OPEN.inv())
    }

    @Test
    fun `magic constants match AOSP four-cc little-endian`() {
        assertThat(AdbProtocol.A_CNXN).isEqualTo(fourCc('C', 'N', 'X', 'N'))
        assertThat(AdbProtocol.A_OPEN).isEqualTo(fourCc('O', 'P', 'E', 'N'))
        assertThat(AdbProtocol.A_OKAY).isEqualTo(fourCc('O', 'K', 'A', 'Y'))
        assertThat(AdbProtocol.A_CLSE).isEqualTo(fourCc('C', 'L', 'S', 'E'))
        assertThat(AdbProtocol.A_WRTE).isEqualTo(fourCc('W', 'R', 'T', 'E'))
        assertThat(AdbProtocol.A_AUTH).isEqualTo(fourCc('A', 'U', 'T', 'H'))
        assertThat(AdbProtocol.A_STLS).isEqualTo(fourCc('S', 'T', 'L', 'S'))
    }

    private fun fourCc(a: Char, b: Char, c: Char, d: Char): Int =
        a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)
}
