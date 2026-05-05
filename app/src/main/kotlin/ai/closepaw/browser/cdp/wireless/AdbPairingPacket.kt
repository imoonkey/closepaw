package ai.closepaw.browser.cdp.wireless

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class AdbPairingPacket(val type: Byte, val payload: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPairingPacket) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.toInt() + payload.contentHashCode()

    companion object {
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2_MSG: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val MAX_PAYLOAD = 16384
        const val HEADER_SIZE = 6

        fun read(input: InputStream): AdbPairingPacket {
            val data = DataInputStream(input)
            val version = data.readUnsignedByte().toByte()
            if (version != VERSION) {
                throw IOException("Unsupported pairing packet version: $version")
            }
            val type = data.readUnsignedByte().toByte()
            if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) {
                throw IOException("Unknown pairing packet type: $type")
            }
            val payloadLength = data.readInt()
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
                throw IOException("Invalid pairing payload length: $payloadLength")
            }
            val payload = ByteArray(payloadLength)
            data.readFully(payload)
            return AdbPairingPacket(type, payload)
        }

        fun write(output: OutputStream, type: Byte, payload: ByteArray) {
            require(type == TYPE_SPAKE2_MSG || type == TYPE_PEER_INFO) {
                "Invalid pairing packet type: $type"
            }
            require(payload.size in 0..MAX_PAYLOAD) {
                "Payload size out of range: ${payload.size}"
            }
            val data = DataOutputStream(output)
            data.writeByte(VERSION.toInt())
            data.writeByte(type.toInt())
            data.writeInt(payload.size)
            data.write(payload)
            data.flush()
        }
    }
}
