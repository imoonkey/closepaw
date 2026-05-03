package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * App-side facade for the wireless-ADB management calls exposed by [IChromeDevtoolsUserService].
 *
 * The binder runs in the Shizuku-spawned shell-UID process which holds MANAGE_DEBUGGING; from
 * the app UID these IAdbManager calls would all SecurityException. [binderProvider] is supplied
 * by the caller (typically wired to [ShizukuUserServiceProvider]) so binding only happens when
 * the manager is actually used — and so unit tests can hand in a fake binder.
 *
 * All binder calls run on [ioDispatcher] via [runInterruptible] — coroutine cancellation
 * interrupts the IO thread carrying the binder transaction, mirroring [UserServiceTransport].
 */
class AdbWirelessManager(
    private val binderProvider: suspend () -> IChromeDevtoolsUserService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun currentBssid(): String? = onBinder { it.getCurrentBssid() }

    /**
     * Looks up the current Wi-Fi BSSID then asks the shell-UID service to call
     * `IAdbManager.allowWirelessDebugging(true, bssid)`. Returns failure when no BSSID is
     * available (Wi-Fi off, or device on cellular only) — wireless ADB is BSSID-scoped on
     * Android 14+.
     */
    suspend fun enableWirelessDebugging(): Result<Unit> = runCatching {
        val bssid = currentBssid()
            ?: throw IOException("No Wi-Fi BSSID available; cannot enable wireless ADB")
        val ok = onBinder { it.enableWirelessDebugging(bssid) }
        if (!ok) throw IOException("IAdbManager.allowWirelessDebugging returned false; check logcat")
    }

    /** -1 when wireless ADB is not listening. */
    suspend fun getAdbWirelessPort(): Int = onBinder { it.getAdbWirelessPort() }

    /**
     * Asks the shell-UID service to call `IAdbManager.enablePairingByQrCode(name, psk)` then
     * discover the listening pair port via `/proc/net/tcp` diff. Throws on failure.
     *
     * `psk` is taken as bytes here (caller may use a binary PSK); we render it as the same
     * string adb expects (UTF-8 text — adb's TLS-PSK pairing accepts any 6+ char string).
     */
    suspend fun openPairPort(name: String, psk: ByteArray): Int {
        require(psk.isNotEmpty()) { "psk must not be empty" }
        val pskStr = String(psk, Charsets.UTF_8)
        val port = onBinder { it.enablePairingByQrCode(name, pskStr) }
        if (port <= 0) throw IOException("pair port did not appear within 5s")
        return port
    }

    suspend fun closePairPort() {
        onBinder { it.disablePairing() }
    }

    suspend fun isPubkeyAuthorized(fingerprintBase64: String): Boolean =
        onBinder { it.isPubkeyInAdbKeys(fingerprintBase64) }

    private suspend inline fun <T> onBinder(crossinline block: (IChromeDevtoolsUserService) -> T): T {
        val binder = binderProvider()
        return try {
            runInterruptible(ioDispatcher) { block(binder) }
        } catch (t: Throwable) {
            Log.w(TAG, "binder call failed: ${t.javaClass.simpleName}: ${t.message}", t)
            throw t
        }
    }

    companion object {
        private const val TAG = "AdbWirelessManager"
    }
}
