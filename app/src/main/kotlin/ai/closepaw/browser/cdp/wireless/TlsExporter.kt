package ai.closepaw.browser.cdp.wireless

import java.io.IOException
import javax.net.ssl.SSLSocket
import org.conscrypt.Conscrypt

/**
 * Wraps Conscrypt's RFC 5705 TLS exporter. We bundle org.conscrypt:conscrypt-android as a
 * dependency rather than reflect at the platform Conscrypt: HiddenApiBypass is unreliable
 * on some Android builds (the class loader can't see hidden API bytecode at all on certain
 * vendors), and the bundled AAR is ~3 MB — small price for a stable handshake.
 */
internal object TlsExporter {

    fun export(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        return try {
            Conscrypt.exportKeyingMaterial(socket, label, context, length)
        } catch (t: Throwable) {
            throw IOException(
                "TLS exporter call failed: ${t.message}. SSLSocket impl=${socket.javaClass.name}; " +
                    "Conscrypt.isConscrypt=${runCatching { Conscrypt.isConscrypt(socket) }.getOrNull()}",
                t,
            )
        }
    }
}
