/*
 * Adapted from MuntashirAkon/libadb-android (Apache-2.0).
 * Original Copyright (C) Muntashir Al-Islam.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.closepaw.browser.cdp.wireless

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

object AndroidPubkey {
    const val ENCODED_SIZE = 524
    const val MODULUS_SIZE_BYTES = 256
    private const val MODULUS_SIZE_BITS = MODULUS_SIZE_BYTES * 8
    private val R32: BigInteger = BigInteger.ONE.shiftLeft(32)

    fun encode(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        require(modulus.bitLength() == MODULUS_SIZE_BITS) {
            "Only RSA-2048 is supported (got ${modulus.bitLength()} bits)"
        }

        val modulusSizeWords = MODULUS_SIZE_BITS / 32
        val n0inv = R32.subtract(modulus.modInverse(R32)).toInt().toLong() and 0xFFFFFFFFL
        val rr = BigInteger.ONE.shiftLeft(2 * MODULUS_SIZE_BITS).mod(modulus)

        val buf = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(modulusSizeWords)
        buf.putInt(n0inv.toInt())
        buf.put(toLittleEndianFixed(modulus, MODULUS_SIZE_BYTES))
        buf.put(toLittleEndianFixed(rr, MODULUS_SIZE_BYTES))
        buf.putInt(publicKey.publicExponent.toInt())
        return buf.array()
    }

    fun encodeWithName(publicKey: RSAPublicKey, name: String): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(encode(publicKey))
        return "$b64 $name\n".toByteArray(Charsets.US_ASCII)
    }

    private fun toLittleEndianFixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        // BigInteger.toByteArray() is big-endian, two's-complement (may have sign byte).
        val unsigned = if (raw.size > size && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= size) { "Value larger than $size bytes (got ${unsigned.size})" }
        val out = ByteArray(size)
        for (i in unsigned.indices) {
            out[i] = unsigned[unsigned.size - 1 - i]
        }
        return out
    }
}
