package ai.closepaw.browser.cdp.wireless

import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Test

class AndroidPubkeyTest {

    @Test
    fun `encode produces 524 bytes with correct header and exponent`() {
        val pk = generateKey().public as RSAPublicKey
        val encoded = AndroidPubkey.encode(pk)

        assertThat(encoded.size).isEqualTo(AndroidPubkey.ENCODED_SIZE)

        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buf.getInt(0)).isEqualTo(64) // 2048 / 32
        assertThat(buf.getInt(520)).isEqualTo(65537)
    }

    @Test
    fun `encodeWithName base64 round-trips and ends with name`() {
        val pk = generateKey().public as RSAPublicKey
        val line = AndroidPubkey.encodeWithName(pk, "test@example").toString(Charsets.US_ASCII)

        assertThat(line).endsWith(" test@example\n")
        val b64 = line.substringBefore(' ')
        val decoded = Base64.getDecoder().decode(b64)
        assertThat(decoded.size).isEqualTo(AndroidPubkey.ENCODED_SIZE)
        assertThat(decoded).isEqualTo(AndroidPubkey.encode(pk))
    }

    @Test
    fun `n0inv satisfies modulus times n0inv mod 2^32 equals -1`() {
        val pk = generateKey().public as RSAPublicKey
        val encoded = AndroidPubkey.encode(pk)
        val n0invBytes = encoded.copyOfRange(4, 8)
        val n0inv = ByteBuffer.wrap(n0invBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        val r32 = BigInteger.ONE.shiftLeft(32)
        val product = pk.modulus.multiply(BigInteger.valueOf(n0inv)).mod(r32)
        assertThat(product).isEqualTo(r32.subtract(BigInteger.ONE))
    }

    private fun generateKey() = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(0x42)) })
    }.generateKeyPair()
}
