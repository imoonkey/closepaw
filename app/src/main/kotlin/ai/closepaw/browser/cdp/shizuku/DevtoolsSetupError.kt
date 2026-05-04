package ai.closepaw.browser.cdp.shizuku

/**
 * Setup-time failure modes for the Shizuku Chrome DevTools bridge.
 *
 * Each subtype maps to one actionable diagnostic surfaced by the bridge. The bridge MUST never
 * return a generic error: the agent and tool layer rely on these distinct cases to compose
 * accurate setup guidance for the user.
 */
sealed class DevtoolsSetupError(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    object ShizukuUnavailable : DevtoolsSetupError(
        code = "shizuku_unavailable",
        message = "Shizuku binder is not running. Start Shizuku and try again.",
    )

    object ShizukuPermissionMissing : DevtoolsSetupError(
        code = "shizuku_permission_missing",
        message = "ClosePaw is not authorized by Shizuku. Grant Shizuku permission to ClosePaw.",
    )

    object ChromeNotRunning : DevtoolsSetupError(
        code = "chrome_not_running",
        message = "Chrome is not running. Open Chrome and load any page, then try again.",
    )

    object DevtoolsSocketMissing : DevtoolsSetupError(
        code = "devtools_socket_missing",
        message = "Chrome is running but its DevTools socket (chrome_devtools_remote) is not " +
            "exposed. Enable Chrome's USB-debugging path (chrome://inspect on a connected host) " +
            "and reopen Chrome.",
    )

    class UserServiceSocketInaccessible(cause: Throwable?) : DevtoolsSetupError(
        code = "user_service_socket_inaccessible",
        message = "The Shizuku UserService cannot reach Chrome's chrome_devtools_remote socket. " +
            "Confirm Shizuku is started with shell privileges and Chrome exposes the DevTools " +
            "socket on this device build.",
        cause = cause,
    )

    /**
     * Wireless-ADB self-pair transport could not bring up its in-device path. Distinct from
     * Shizuku-missing because the failure here is post-Shizuku: we have the binder, but
     * IAdbManager rejected `allowWirelessDebugging` / `enablePairingByQrCode`, or the device
     * has no Wi-Fi BSSID (AdbDebuggingManager refuses to enable wireless adb without one), or
     * the embedded TLS-PSK / mTLS handshake itself failed.
     */
    class WirelessAdbSelfPairUnavailable(cause: Throwable?) : DevtoolsSetupError(
        code = "wireless_adb_self_pair_unavailable",
        // Inline the cause class+message because the capability gate / agent UI surfaces only
        // [message] (not the chained stack), so SSLHandshakeException / SocketTimeoutException
        // / IOException details would otherwise be invisible to the user.
        message = buildWirelessAdbSelfPairMessage(cause),
        cause = cause,
    )

    class MalformedResponse(detail: String, cause: Throwable? = null) : DevtoolsSetupError(
        code = "malformed_response",
        message = "Chrome DevTools returned a malformed HTTP/WebSocket response: $detail",
        cause = cause,
    )
}

private const val WIRELESS_ADB_SELF_PAIR_BASE_MESSAGE: String =
    "Wireless-ADB self-pair could not be brought up on this device. " +
        "Verify (a) Shizuku is granted, (b) the device is on a Wi-Fi network, " +
        "(c) IAdbManager AIDL is reachable from the shell uid (com.android.shell " +
        "must hold MANAGE_DEBUGGING — true on stock AOSP and most OEM builds)."

private fun buildWirelessAdbSelfPairMessage(cause: Throwable?): String {
    if (cause == null) return WIRELESS_ADB_SELF_PAIR_BASE_MESSAGE
    val detail = cause.message?.takeIf { it.isNotBlank() }
        ?.let { ": $it" } ?: ""
    return "$WIRELESS_ADB_SELF_PAIR_BASE_MESSAGE Underlying cause: ${cause.javaClass.simpleName}$detail"
}
