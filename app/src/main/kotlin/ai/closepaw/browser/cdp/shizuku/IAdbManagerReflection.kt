package ai.closepaw.browser.cdp.shizuku

import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.lang.reflect.Method

/**
 * Reflection wrapper around the hidden `android.debug.IAdbManager` AIDL. Cached lookups,
 * methods invoked on the proxy returned by `IAdbManager$Stub.asInterface(ServiceManager.getService("adb"))`.
 *
 * Caller MUST hold MANAGE_DEBUGGING — i.e. run inside the Shizuku-spawned shell-UID process.
 * From the app UID every IAdbManager call returns SecurityException.
 */
internal class IAdbManagerReflection {

    @Volatile private var cached: Proxy? = null

    private fun proxy(): Proxy {
        cached?.let { return it }
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "adb") as? IBinder
            ?: throw IOException("ServiceManager.getService(\"adb\") returned null")
        val stub = Class.forName("android.debug.IAdbManager\$Stub")
        val adb = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IOException("IAdbManager.asInterface returned null")
        // Proxy returned by Stub.asInterface implements the IAdbManager interface as its
        // first declared interface, regardless of whether we're inside the system server.
        val iface = adb.javaClass.interfaces.firstOrNull()
            ?: throw IOException("IAdbManager proxy declares no interfaces")
        val p = Proxy(adb, iface)
        cached = p
        return p
    }

    fun allowWirelessDebugging(enable: Boolean, bssid: String): Boolean = safe("allowWirelessDebugging") {
        val p = proxy()
        val m = p.method("allowWirelessDebugging", Boolean::class.javaPrimitiveType!!, String::class.java)
        m.invoke(p.target, enable, bssid)
        true
    }

    fun getAdbWirelessPort(): Int = try {
        val p = proxy()
        val m = p.method("getAdbWirelessPort")
        (m.invoke(p.target) as? Int) ?: -1
    } catch (t: Throwable) {
        Log.w(TAG, "getAdbWirelessPort failed", t.cause ?: t)
        -1
    }

    fun enablePairingByQrCode(name: String, psk: String): Boolean = safe("enablePairingByQrCode") {
        val p = proxy()
        val m = p.method("enablePairingByQrCode", String::class.java, String::class.java)
        m.invoke(p.target, name, psk)
        true
    }

    fun disablePairing(): Boolean = safe("disablePairing") {
        val p = proxy()
        val m = p.method("disablePairing")
        m.invoke(p.target)
        true
    }

    private inline fun safe(name: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "$name failed", t.cause ?: t)
        false
    }

    private class Proxy(val target: Any, val iface: Class<*>) {
        private val methods = HashMap<String, Method>()
        fun method(name: String, vararg params: Class<*>): Method {
            val key = name + params.joinToString(",") { it.name }
            methods[key]?.let { return it }
            val m = iface.getMethod(name, *params)
            methods[key] = m
            return m
        }
    }

    companion object {
        private const val TAG = "IAdbManagerRefl"
    }
}
