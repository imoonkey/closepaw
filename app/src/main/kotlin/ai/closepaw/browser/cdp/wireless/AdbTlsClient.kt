package ai.closepaw.browser.cdp.wireless

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Plain TCP -> TLS 1.3 wrap to the wireless ADB TLS port. adbd does TLS immediately on
 * accept on the dedicated wireless port (no A_STLS handshake on this port). Server cert is
 * not pinned: adbd authenticates US via our client cert against `/data/misc/adb/adb_keys`.
 */
internal object AdbTlsClient {
    fun connect(host: String, port: Int, keyStore: AdbCryptoKeyStore, timeoutMs: Int): SSLSocket {
        WirelessAdbProviders.ensure()
        val material = keyStore.loadOrCreate()
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), timeoutMs)

        val context = SSLContext.getInstance("TLS", "Conscrypt")
        context.init(
            arrayOf(SingleCertKeyManager(material.keyPair.private, material.certificate)),
            arrayOf<javax.net.ssl.TrustManager>(TrustAllManager),
            SecureRandom(),
        )
        val tls = context.socketFactory.createSocket(plain, host, port, /* autoClose = */ true) as SSLSocket
        tls.useClientMode = true
        // Don't restrict enabledProtocols — let Conscrypt negotiate. adbd pins TLS 1.3 since
        // Android 11; older devices fall back to TLS 1.2 which Conscrypt also supports.
        tls.soTimeout = timeoutMs
        tls.startHandshake()
        return tls
    }

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

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
