package ai.closepaw.browser.cdp.wireless

import ai.closepaw.browser.cdp.shizuku.IChromeDevtoolsUserService
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * App-side facade for the wireless-ADB management calls exposed by [IChromeDevtoolsUserService].
 * The binder runs in the Shizuku-spawned shell-UID process which holds MANAGE_DEBUGGING; from
 * the app UID these IAdbManager calls would all SecurityException. [binderProvider] is supplied
 * by the caller (typically wired to [ShizukuUserServiceProvider]) so binding only happens when
 * the manager is actually used.
 */
class AdbWirelessManager(
    private val binderProvider: suspend () -> IChromeDevtoolsUserService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun currentBssid(): String? = onBinder { it.getCurrentBssid() }

    /**
     * Looks up the current Wi-Fi BSSID then calls
     * `IAdbManager.allowWirelessDebugging(true, bssid)` via the shell-UID service. Wireless ADB
     * is BSSID-scoped on Android 14+, so a missing BSSID (Wi-Fi off, cellular only) is fatal.
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
     * Calls `IAdbManager.enablePairingByQrCode(name, psk)` then discovers the listening pair
     * port via `/proc/net/tcp` diff. `psk` is taken as bytes (caller may use a binary PSK) and
     * rendered as the UTF-8 string adb's TLS-PSK pairing accepts.
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

    /**
     * True iff [pubkeyBase64] (base64 of the 524-byte AOSP `android_pubkey` blob) is present in
     * `/data/misc/adb/adb_keys`. Substring match is safe — RSA-2048 base64 is ~700 chars and
     * collisions across distinct keys are cryptographically impossible. Returns false on
     * unreadable adb_keys; caller re-pairs (idempotent on adbd).
     */
    suspend fun isPubkeyAuthorized(pubkeyBase64: String): Boolean {
        if (pubkeyBase64.isEmpty()) return false
        val content = onBinder { it.readAdbKeys() } ?: return false
        return content.contains(pubkeyBase64)
    }

    /**
     * Removes accumulated `ClosePaw@*` entries from `/data/misc/adb/adb_keys`, retaining
     * exactly one — the line whose pubkey equals [retainPubkeyBase64]. Non-ClosePaw lines pass
     * through unchanged.
     *
     * Returns true iff the file was actually rewritten. Returns false defensively when the
     * current pubkey isn't found as a first-token in any line — never delete the only ClosePaw
     * entry we cannot verify is current.
     */
    suspend fun pruneAdbKeys(retainPubkeyBase64: String): Boolean {
        require(retainPubkeyBase64.isNotEmpty()) { "retainPubkeyBase64 must not be empty" }
        val content = onBinder { it.readAdbKeys() } ?: return false
        val result = pruneClosePawEntries(content, retainPubkeyBase64)
        if (!result.foundCurrent) return false
        if (!result.changed) return false
        return onBinder { it.writeAdbKeys(result.content) }
    }

    private suspend inline fun <T> onBinder(crossinline block: (IChromeDevtoolsUserService) -> T): T {
        val binder = binderProvider()
        return try {
            runInterruptible(ioDispatcher) { block(binder) }
        } catch (t: Throwable) {
            Log.w(TAG, "binder call failed: ${t.javaClass.simpleName}: ${t.message}", t)
            throw t
        }
    }

    internal data class PruneResult(
        val content: String,
        val foundCurrent: Boolean,
        val changed: Boolean,
    )

    companion object {
        private const val TAG = "AdbWirelessManager"
        private const val CLOSEPAW_NAME_PREFIX = "ClosePaw@"
        // adbd's auth.cpp tokenizes adb_keys on \s+ (not space-only), so honor tab-separated
        // entries from other writers when matching/preserving lines.
        private val WHITESPACE = Regex("\\s+")

        /**
         * Drop blank lines and any `ClosePaw@*`-named line whose pubkey is not
         * [retainPubkeyBase64]. The first surviving copy of [retainPubkeyBase64] is kept once;
         * duplicates are dropped. Non-ClosePaw lines pass through unchanged. Caller filters by
         * `foundCurrent && changed` to decide whether a write-back is needed.
         */
        internal fun pruneClosePawEntries(
            content: String,
            retainPubkeyBase64: String,
        ): PruneResult {
            val out = StringBuilder()
            var keptCurrent = false
            var foundCurrent = false
            for (raw in content.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.isBlank()) continue
                val tokens = line.split(WHITESPACE, limit = 2)
                val pubkey = tokens[0]
                val name = if (tokens.size > 1) tokens[1] else ""
                val isClosePaw = name.startsWith(CLOSEPAW_NAME_PREFIX) || pubkey == retainPubkeyBase64
                if (isClosePaw) {
                    if (pubkey == retainPubkeyBase64) {
                        foundCurrent = true
                        if (!keptCurrent) {
                            out.append(line).append('\n')
                            keptCurrent = true
                        }
                    }
                } else {
                    out.append(line).append('\n')
                }
            }
            val outStr = out.toString()
            return PruneResult(outStr, foundCurrent, changed = outStr != content)
        }
    }
}
