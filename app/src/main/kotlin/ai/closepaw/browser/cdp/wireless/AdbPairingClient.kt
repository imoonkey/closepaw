/*
 * SPAKE2 + AES-GCM peer-info exchange structurally adapted from
 * MuntashirAkon/libadb-android (Apache-2.0). Original Copyright (C) Muntashir Al-Islam.
 * SPDX-License-Identifier: Apache-2.0
 *
 * SPAKE2-25519 primitive provided by `Spake25519` (in this package), a pure-Kotlin port of
 * BoringSSL `spake25519.c` over `net.i2p.crypto:eddsa` (CC0). Replaces the previous LGPL-3.0
 * JitPack dep `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` — see
 * `projects/active/browser/cn/diag_20260504_spake_alternatives.md` for rationale.
 */
package ai.closepaw.browser.cdp.wireless

import java.io.IOException
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

data class PairingResult(val authorizedPubkeyBase64: String, val peerGuid: String?)

class AdbPairingClient(
    private val keyStore: AdbCryptoKeyStore,
    private val deviceLabel: String = "ClosePaw",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun pair(host: String, port: Int, psk: ByteArray, timeoutMs: Int = 10_000): PairingResult {
        require(psk.isNotEmpty()) { "psk must not be empty" }
        return runInterruptible(ioDispatcher) { runPair(host, port, psk, timeoutMs) }
    }

    private fun runPair(host: String, port: Int, psk: ByteArray, timeoutMs: Int): PairingResult {
        val material = keyStore.loadOrCreate()
        val socket = AdbPairingTls.connect(host, port, timeoutMs)
        try {
            val exporter = TlsExporter.export(socket, EXPORTER_LABEL, null, EXPORTER_LENGTH)
            val password = psk + exporter

            val spake = Spake25519(Spake25519.Role.ALICE, MY_NAME, THEIR_NAME)
            val ourMsg = spake.generateMessage(password)
            try {
                AdbPairingPacket.write(socket.outputStream, AdbPairingPacket.TYPE_SPAKE2_MSG, ourMsg)
                val theirSpake = AdbPairingPacket.read(socket.inputStream)
                if (theirSpake.type != AdbPairingPacket.TYPE_SPAKE2_MSG) {
                    throw IOException("Expected SPAKE2_MSG, got type=${theirSpake.type}")
                }
                val keyMaterial = spake.processMessage(theirSpake.payload)
                val secretKey = hkdfSha256(keyMaterial, HKDF_INFO, AES_KEY_BYTES)

                val plainPeerInfo = buildPeerInfoPlaintext(material.keyPair.public as RSAPublicKey)
                val ciphertext = aesGcmEncrypt(secretKey, counterIv(0), plainPeerInfo)
                AdbPairingPacket.write(socket.outputStream, AdbPairingPacket.TYPE_PEER_INFO, ciphertext)

                val peerInfoFrame = AdbPairingPacket.read(socket.inputStream)
                if (peerInfoFrame.type != AdbPairingPacket.TYPE_PEER_INFO) {
                    throw IOException("Expected PEER_INFO, got type=${peerInfoFrame.type}")
                }
                val peerPlain = aesGcmDecrypt(secretKey, counterIv(0), peerInfoFrame.payload)
                val peerGuid = parsePeerGuid(peerPlain)

                return PairingResult(authorizedPubkeyBase64 = keyStore.androidPubkeyBase64(), peerGuid = peerGuid)
            } finally {
                runCatching { spake.destroy() }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildPeerInfoPlaintext(publicKey: RSAPublicKey): ByteArray {
        val plain = ByteArray(PEER_INFO_SIZE)
        plain[0] = PEER_TYPE_RSA_PUB_KEY
        val name = "$deviceLabel@${android.os.Build.MODEL}"
        val encoded = AndroidPubkey.encodeWithName(publicKey, name)
        val copyLen = minOf(encoded.size, PEER_INFO_SIZE - 1)
        System.arraycopy(encoded, 0, plain, 1, copyLen)
        return plain
    }

    private fun parsePeerGuid(plain: ByteArray): String? {
        if (plain.size < 2) return null
        if (plain[0] != PEER_TYPE_DEVICE_GUID) return null
        val end = (1 until plain.size).firstOrNull { plain[it] == 0.toByte() } ?: plain.size
        if (end <= 1) return null
        return String(plain, 1, end - 1, Charsets.US_ASCII)
    }

    companion object {
        // AOSP `kExportedKeyLabel = "adb-label\0"` — 10 bytes including the trailing NUL.
        internal const val EXPORTER_LABEL = "adb-label\u0000"
        internal const val EXPORTER_LENGTH = 64
        // AOSP `kClientName = "adb pair client\0"` / `kServerName = "adb pair server\0"` — 16 bytes each.
        internal val MY_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        internal val THEIR_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
        internal val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        internal const val AES_KEY_BYTES = 16
        internal const val PEER_INFO_SIZE = 8192
        internal const val PEER_TYPE_RSA_PUB_KEY: Byte = 0
        internal const val PEER_TYPE_DEVICE_GUID: Byte = 1

        internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val out = ByteArray(length)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(ikm, null, info))
                generateBytes(out, 0, length)
            }
            return out
        }

        internal fun counterIv(counter: Long): ByteArray {
            val iv = ByteArray(12)
            var v = counter
            for (i in 0 until 8) {
                iv[i] = (v and 0xFFL).toByte()
                v = v ushr 8
            }
            return iv
        }

        internal fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return cipher.doFinal(plaintext)
        }

        internal fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
