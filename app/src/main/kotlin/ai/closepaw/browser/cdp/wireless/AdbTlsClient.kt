package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.wireless.AdbProtocol.A_STLS
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Plain TCP -> A_STLS exchange -> TLS 1.3 wrap to the wireless ADB TLS port.
 *
 * Per AOSP `daemon/adb_wifi.cpp` the adbd state machine on the TLS port is:
 *   - Daemon accepts TCP, starts plain read thread.
 *   - Client sends A_CNXN  -> daemon `handle_new_connection` -> `send_tls_request` (A_STLS).
 *   - Client reads A_STLS, sends its own A_STLS.
 *   - Daemon `adbd_auth_tls_handshake` performs TLS handshake.
 *   - On success daemon `adbd_wifi_secure_connect` calls `send_connect(t)` which writes A_CNXN
 *     to us through TLS. The client MUST wait for that A_CNXN before sending any further
 *     adb wire packets — sending another A_CNXN here makes adbd run `handle_new_connection`
 *     again, which calls `handle_offline()` (firing the disconnect callback) and emits a
 *     stray A_STLS over the encrypted channel.
 *
 * Server cert is not pinned: adbd authenticates US via our client cert against
 * `/data/misc/adb/adb_keys` (validated during the mTLS handshake).
 */
internal object AdbTlsClient {

    /** Bidirectional byte-stream channel over the post-handshake mTLS connection. */
    interface TlsChannel : Closeable {
        val inputStream: InputStream
        val outputStream: OutputStream
        /**
         * Reset the underlying socket's SO_TIMEOUT after the handshake completes. 0 = infinite.
         * The handshake uses a short bounded timeout; long-lived streams (CDP WebSocket relay)
         * must clear it so idle gaps between frames don't tear down the socket.
         */
        fun setIdleReadTimeoutMs(ms: Int)
    }

    fun connectWithStls(
        host: String,
        port: Int,
        keyStore: AdbCryptoKeyStore,
        handshakeTimeoutMs: Int,
    ): TlsChannel {
        WirelessAdbProviders.ensure()
        val material = keyStore.loadOrCreate()

        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), handshakeTimeoutMs)
        plain.soTimeout = handshakeTimeoutMs

        // Step 1: pre-TLS A_CNXN -> A_STLS handshake (plaintext). Banner advertises only the
        // features we actually implement on the wire. Notably we do NOT advertise `delayed_ack`:
        // with delayed_ack negotiated, every A_OKAY must carry a 4-byte `acked_bytes` payload
        // (see AOSP packages/modules/adb/sockets.cpp `local_socket_ack`), and our minimal client
        // sends bare A_OKAYs. Mismatched delayed-ack state would silently no-op the ack on the
        // daemon side. Listing common features keeps the banner shape adbd expects.
        AdbProtocol.Message.write(
            plain.getOutputStream(),
            AdbProtocol.A_CNXN,
            AdbProtocol.A_VERSION_SKIP_CHECKSUM,
            AdbProtocol.A_MAX_PAYLOAD,
            "host::features=shell_v2,cmd,stat_v2,fixed_push_mkdir,apex,abb_exec,sendrecv_v2 ".toByteArray(Charsets.UTF_8),
        )
        plain.getOutputStream().flush()

        val first = AdbProtocol.Message.read(plain.getInputStream())
        Log.i(TAG, "wireless adb greeting cmd=0x${"%08x".format(first.command)} arg0=0x${"%08x".format(first.arg0)} arg1=${first.arg1} payloadLen=${first.payload.size}")
        if (first.command != A_STLS) {
            throw java.io.IOException(
                "unexpected greeting from wireless adb: cmd=0x${"%08x".format(first.command)}"
            )
        }
        AdbProtocol.Message.write(
            plain.getOutputStream(),
            A_STLS,
            A_STLS_VERSION,
            0,
            ByteArray(0),
        )
        plain.getOutputStream().flush()

        // Step 2: mTLS handshake. Mirrors libadb-android's SslUtils — provider-qualified to the
        // bundled Conscrypt registered by [WirelessAdbProviders] (the platform's hidden Conscrypt
        // can't export keying material on some vendor builds).
        val context = SSLContext.getInstance("TLSv1.3", "Conscrypt")
        context.init(
            arrayOf(SingleCertKeyManager(material.keyPair.private, material.certificate)),
            arrayOf<javax.net.ssl.TrustManager>(TrustAllManager),
            SecureRandom(),
        )
        val factory: SSLSocketFactory = context.socketFactory
        val tls = factory.createSocket(plain, host, port, /* autoClose = */ true) as SSLSocket
        tls.useClientMode = true
        tls.enabledProtocols = arrayOf("TLSv1.3")
        tls.soTimeout = handshakeTimeoutMs
        tls.startHandshake()
        Log.i(TAG, "TLS session: protocol=${tls.session.protocol} cipher=${tls.session.cipherSuite}")
        return SocketChannel(tls)
    }

    internal class SocketChannel(private val socket: Socket) : TlsChannel {
        override val inputStream: InputStream get() = socket.getInputStream()
        override val outputStream: OutputStream get() = socket.getOutputStream()
        override fun setIdleReadTimeoutMs(ms: Int) { socket.soTimeout = ms }
        override fun close() { runCatching { socket.close() } }
    }

    private const val A_STLS_VERSION = 0x01000000

    private class SingleCertKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        private val chain = arrayOf(certificate)
        private val aliases = arrayOf(ALIAS)

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = aliases
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = aliases
        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String = ALIAS

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String = ALIAS

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = privateKey

        companion object {
            private const val ALIAS = "adb"
        }
    }

    private object TrustAllManager : javax.net.ssl.X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val TAG = "AdbTlsClient"
}
