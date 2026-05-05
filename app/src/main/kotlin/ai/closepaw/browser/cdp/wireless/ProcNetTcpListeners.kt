package ai.closepaw.browser.cdp.wireless

import java.io.File

/**
 * Parses /proc/net/tcp and /proc/net/tcp6 to extract the set of currently-LISTENing TCP ports.
 *
 * Format reference (proc(5)):
 * ```
 *   sl  local_address rem_address   st ...
 *    0: 00000000:1F90 00000000:0000 0A ...
 * ```
 * `local_address` is `IP_HEX:PORT_HEX`. The port is uppercase 4-hex-digit and is in the
 * canonical "human-readable, big-endian" form on Linux — `1F90` == 8080. State `0A` == LISTEN.
 *
 * Used by [AdbWirelessManager] to discover the pairing port: snapshot before
 * `enablePairingByQrCode`, snapshot after, the new port is the pair listener.
 */
internal object ProcNetTcpListeners {

    private val TCP4 = File("/proc/net/tcp")
    private val TCP6 = File("/proc/net/tcp6")
    private const val LISTEN_STATE = "0A"

    fun snapshot(): Set<Int> = buildSet {
        addAll(parse(TCP4))
        addAll(parse(TCP6))
    }

    private fun parse(file: File): Set<Int> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptySet()
        val out = HashSet<Int>()
        // Skip the header line; data lines start with whitespace + index + ':'.
        for (line in text.lineSequence().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) continue
            val local = cols[1]
            val state = cols[3]
            if (state != LISTEN_STATE) continue
            val colon = local.lastIndexOf(':')
            if (colon < 0) continue
            val portHex = local.substring(colon + 1)
            val port = portHex.toIntOrNull(16) ?: continue
            if (port in 1..65535) out += port
        }
        return out
    }
}
