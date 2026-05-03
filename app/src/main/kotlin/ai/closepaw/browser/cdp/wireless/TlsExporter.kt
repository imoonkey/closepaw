package ai.closepaw.browser.cdp.wireless

import java.io.IOException
import java.lang.reflect.Method
import javax.net.ssl.SSLSocket
import org.lsposed.hiddenapibypass.HiddenApiBypass

internal object TlsExporter {

    @Volatile private var cachedMethod: Method? = null
    @Volatile private var exemptionApplied = false

    fun export(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        val method = resolveMethod()
        return try {
            method.invoke(null, socket, label, context, length) as ByteArray
        } catch (t: Throwable) {
            throw IOException(
                "TLS exporter call failed — platform Conscrypt rejected the SSLSocket. " +
                    "Cause: ${t.cause?.message ?: t.message}",
                t,
            )
        }
    }

    private fun resolveMethod(): Method {
        cachedMethod?.let { return it }
        synchronized(this) {
            cachedMethod?.let { return it }
            applyHiddenApiExemption()
            val method = try {
                val cls = Class.forName("org.conscrypt.Conscrypt")
                cls.getDeclaredMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
            } catch (t: Throwable) {
                throw IOException(
                    "TLS exporter not available — platform Conscrypt missing or hidden-API blocked. " +
                        "Bundle org.conscrypt:conscrypt-android as a fallback. Cause: ${t.message}",
                    t,
                )
            }
            method.isAccessible = true
            cachedMethod = method
            return method
        }
    }

    private fun applyHiddenApiExemption() {
        if (exemptionApplied) return
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Lorg/conscrypt/") }
        exemptionApplied = true
    }
}
