package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdbPairingClientTest {

    @Test
    fun `aes-gcm encrypt then decrypt with counter zero round trips`() {
        val key = ByteArray(16) { it.toByte() }
        val plain = "the quick brown fox jumps over the lazy dog".toByteArray()
        val iv = AdbPairingClient.counterIv(0)

        val ct = AdbPairingClient.aesGcmEncrypt(key, iv, plain)
        // Ciphertext = body + 16-byte tag
        assertThat(ct.size).isEqualTo(plain.size + 16)

        val pt = AdbPairingClient.aesGcmDecrypt(key, iv, ct)
        assertThat(pt).isEqualTo(plain)
    }

    @Test
    fun `counter iv is 12 bytes little-endian`() {
        val iv0 = AdbPairingClient.counterIv(0)
        assertThat(iv0.size).isEqualTo(12)
        assertThat(iv0).isEqualTo(ByteArray(12))

        val iv1 = AdbPairingClient.counterIv(1)
        assertThat(iv1[0]).isEqualTo(0x01.toByte())
        for (i in 1 until 12) assertThat(iv1[i]).isEqualTo(0x00.toByte())

        val iv = AdbPairingClient.counterIv(0x0102030405060708L)
        assertThat(iv[0]).isEqualTo(0x08.toByte())
        assertThat(iv[1]).isEqualTo(0x07.toByte())
        assertThat(iv[7]).isEqualTo(0x01.toByte())
        // last 4 bytes are zero
        for (i in 8 until 12) assertThat(iv[i]).isEqualTo(0x00.toByte())
    }

    @Test
    fun `hkdf sha256 with spec info returns 16 deterministic bytes`() {
        val ikm = ByteArray(64) { (it * 3).toByte() }
        val key1 = AdbPairingClient.hkdfSha256(ikm, AdbPairingClient.HKDF_INFO, 16)
        val key2 = AdbPairingClient.hkdfSha256(ikm, AdbPairingClient.HKDF_INFO, 16)
        assertThat(key1).hasLength(16)
        assertThat(key1).isEqualTo(key2)

        val different = AdbPairingClient.hkdfSha256(ByteArray(64) { 0 }, AdbPairingClient.HKDF_INFO, 16)
        assertThat(key1).isNotEqualTo(different)
    }

    @Test
    fun `hkdf info string is the spec ascii bytes`() {
        val expected = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        assertThat(AdbPairingClient.HKDF_INFO).isEqualTo(expected)
        assertThat(AdbPairingClient.HKDF_INFO.size).isEqualTo(32)
    }

    @Test
    fun `tls exporter label is 10 bytes including trailing NUL`() {
        val bytes = AdbPairingClient.EXPORTER_LABEL.toByteArray(Charsets.UTF_8)
        assertThat(bytes.size).isEqualTo(10)
        assertThat(bytes[9]).isEqualTo(0.toByte())
        // bytes 0..8 == "adb-label"
        assertThat(String(bytes, 0, 9, Charsets.US_ASCII)).isEqualTo("adb-label")
    }

    @Test
    fun `spake2 names are 16 bytes including trailing NUL`() {
        assertThat(AdbPairingClient.MY_NAME.size).isEqualTo(16)
        assertThat(AdbPairingClient.THEIR_NAME.size).isEqualTo(16)
        assertThat(AdbPairingClient.MY_NAME[15]).isEqualTo(0.toByte())
        assertThat(AdbPairingClient.THEIR_NAME[15]).isEqualTo(0.toByte())
        assertThat(String(AdbPairingClient.MY_NAME, 0, 15)).isEqualTo("adb pair client")
        assertThat(String(AdbPairingClient.THEIR_NAME, 0, 15)).isEqualTo("adb pair server")
    }

    @Test
    fun `peer info layout is 8192 bytes type then padded encoded line`() {
        // Hand-roll a small "encoded line" — we don't need a real key, just verify layout invariants.
        val mockEncoded = "QUJDRA== abc@dev\n".toByteArray(Charsets.US_ASCII)
        val plain = ByteArray(AdbPairingClient.PEER_INFO_SIZE)
        plain[0] = AdbPairingClient.PEER_TYPE_RSA_PUB_KEY
        System.arraycopy(mockEncoded, 0, plain, 1, mockEncoded.size)

        assertThat(plain.size).isEqualTo(8192)
        assertThat(plain[0]).isEqualTo(0.toByte())
        // bytes [1..1+mockEncoded.size) match the encoded line
        for (i in mockEncoded.indices) {
            assertThat(plain[i + 1]).isEqualTo(mockEncoded[i])
        }
        // remainder is zero-padded
        for (i in (1 + mockEncoded.size) until 8192) {
            assertThat(plain[i]).isEqualTo(0.toByte())
        }
    }
}
