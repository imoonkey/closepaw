package ai.closepaw.browser.cdp.shizuku

import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.File
import rikka.shizuku.Shizuku

/** Adapter exposing the [ShizukuStatusProvider] surface backed by the real Shizuku binder. */
class ShizukuStatusAdapter : ShizukuStatusProvider {
    /**
     * Shizuku publishes its binder to apps via [rikka.shizuku.ShizukuProvider]'s on-demand
     * broadcast — which can lag a second or two behind app start (especially after
     * `adb install -r` when the prior process death dropped the cached binder). A naive
     * `Shizuku.pingBinder()` immediately at app start returns false even when Shizuku is
     * fully running and granted; the bridge's preflight then rejects the whole CDP path
     * before the wireless self-pair / UserService legs ever try.
     *
     * Retry briefly (cheap: pingBinder is a single oneway transaction) so a freshly-launched
     * process gets the same answer a long-running one would.
     */
    override fun isAvailable(): Boolean = waitFor { Shizuku.pingBinder() }
    override fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun waitFor(predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + BINDER_WAIT_MS
        var ok = runCatching { predicate() }.getOrDefault(false)
        while (!ok && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(BINDER_POLL_MS)
            ok = runCatching { predicate() }.getOrDefault(false)
        }
        return ok
    }

    companion object {
        private const val BINDER_WAIT_MS = 2_000L
        private const val BINDER_POLL_MS = 100L
    }
}

/**
 * Best-effort device-side diagnostics.
 *
 * - Abstract sockets appear in `/proc/net/unix` as `@<name>`. The file is world-readable on
 *   most Android builds, but SELinux can hide individual entries; we therefore return
 *   [SocketProbeResult.Unknown] on read failure rather than guessing wrong.
 * - Chrome process detection requires shell privileges on Android 10+ because apps cannot see
 *   foreign `/proc/<pid>` entries. The diagnostic is wired through [chromeRunningProbe], which
 *   the runtime supplies (typically [ShizukuChromeRunningProbe]). When unset we report
 *   [ChromeRunningResult.Unknown] and let the bridge defer judgment to the actual connect
 *   attempt rather than lie that Chrome is or is not running.
 */
class DefaultDevtoolsDiagnostics(
    private val procNetUnix: File = File("/proc/net/unix"),
    private val chromeRunningProbe: () -> ChromeRunningResult = { ChromeRunningResult.Unknown },
) : DevtoolsDiagnostics {

    override fun isDevtoolsSocketBound(): SocketProbeResult {
        val text = runCatching { procNetUnix.readText() }.getOrNull()
            ?: return SocketProbeResult.Unknown
        return if (containsAbstractSocket(text, ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET)) {
            SocketProbeResult.Bound
        } else {
            SocketProbeResult.NotBound
        }
    }

    override fun isChromeRunning(): ChromeRunningResult = chromeRunningProbe()

    companion object {
        /**
         * `/proc/net/unix` lines look like:
         * `0000000000000000: 00000003 00000000 00010000 0001 01 1234 @chrome_devtools_remote_5678`
         * Match `@<name>` exactly OR `@<name>_<pid>` (Chrome appends `_<pid>` on some builds).
         */
        fun containsAbstractSocket(procContent: String, socketName: String): Boolean {
            val needleExact = "@$socketName"
            return procContent.lineSequence().any { line ->
                val token = line.trim().substringAfterLast(' ', "")
                token == needleExact || token.startsWith("${needleExact}_")
            }
        }
    }
}

/**
 * Real "is Chrome running" probe backed by `pidof <package>` through Shizuku.
 *
 * Returns:
 * - [ChromeRunningResult.Running] when at least one PID prints to stdout (exit 0).
 * - [ChromeRunningResult.NotRunning] when `pidof` exits non-zero with empty stdout.
 * - [ChromeRunningResult.Unknown] on Shizuku errors so the bridge can defer to the transport
 *   instead of misreporting Chrome's state.
 *
 * Chrome's stable package is `com.android.chrome`; callers can pass another (Beta, Canary,
 * Vivaldi) when needed.
 */
class ShizukuChromeRunningProbe(
    private val chromePackage: String = CHROME_STABLE_PACKAGE,
) : () -> ChromeRunningResult {

    override fun invoke(): ChromeRunningResult {
        val process = runCatching { invokeShizukuNewProcess(arrayOf("pidof", chromePackage)) }
            .getOrNull()
            ?: return ChromeRunningResult.Unknown
        return try {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = process.waitFor()
            when {
                exit == 0 && output.isNotEmpty() -> ChromeRunningResult.Running
                exit != 0 && output.isEmpty() -> ChromeRunningResult.NotRunning
                else -> ChromeRunningResult.Unknown
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun invokeShizukuNewProcess(command: Array<String>): Process {
        val cls = Shizuku::class.java
        val method = runCatching {
            cls.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
        }.getOrNull() ?: cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    companion object {
        const val CHROME_STABLE_PACKAGE = "com.android.chrome"
    }
}
