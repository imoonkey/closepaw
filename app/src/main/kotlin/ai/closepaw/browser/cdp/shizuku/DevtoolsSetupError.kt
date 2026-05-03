package ai.closepaw.browser.cdp.shizuku

/**
 * Setup-time failure modes for the Shizuku Chrome DevTools bridge.
 *
 * Each subtype maps to one actionable diagnostic surfaced by the spike. The bridge MUST never
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

    class AppProcessSocketInaccessible(cause: Throwable?) : DevtoolsSetupError(
        code = "app_process_socket_inaccessible",
        message = "Chrome's chrome_devtools_remote abstract socket is bound but cannot be reached " +
            "from the ClosePaw app process. This is usually SELinux/package isolation. The bridge " +
            "should fall back to the Shizuku UserService.",
        cause = cause,
    )

    class UserServiceSocketInaccessible(cause: Throwable?) : DevtoolsSetupError(
        code = "user_service_socket_inaccessible",
        message = "The Shizuku UserService cannot reach Chrome's chrome_devtools_remote socket. " +
            "Confirm Shizuku is started with shell privileges and Chrome exposes the DevTools " +
            "socket on this device build.",
        cause = cause,
    )

    /**
     * Phase 2 path failure: the Shizuku UserService wrote /data/local/tmp/chrome-command-line
     * with `--remote-debugging-port=N` and force-restarted Chrome, but Chrome did not bind the
     * TCP loopback port within the wait window. On stock Chrome this almost always means the
     * per-Chrome-profile flag at chrome://flags#enable-command-line-on-non-rooted-devices is
     * still off, so Chrome silently ignores the command-line file. The fix is a one-time user
     * action — see the message body.
     */
    class ChromeRemoteDebuggingFlagNotEnabled(cause: Throwable?) : DevtoolsSetupError(
        code = "chrome_flag_not_enabled",
        message = "Chrome did not bind the requested TCP debug port. Open Chrome → " +
            "chrome://flags#enable-command-line-on-non-rooted-devices → enable → relaunch Chrome, " +
            "then try again.",
        cause = cause,
    )

    /**
     * Surfaced when ALL in-device transports failed AND the host-mediated relay is either
     * not wired or its 127.0.0.1 port range probe came up empty. The fix is a one-time host
     * action: run `scripts/setup-cdp-relay.sh` on the user's PC while their device is
     * attached over ADB. The transport then chains device 127.0.0.1:&lt;port&gt; through the host
     * adbd into Chrome's `chrome_devtools_remote` socket.
     */
    class HostMediatedRelayUnreachable(cause: Throwable?) : DevtoolsSetupError(
        code = "host_mediated_relay_unreachable",
        message = "Chrome DevTools is not reachable on this device. To enable the " +
            "host-mediated CDP relay, attach the device over ADB and run " +
            "`scripts/setup-cdp-relay.sh` on the host once. The script wires " +
            "`adb forward tcp:9222 localabstract:chrome_devtools_remote` and " +
            "`adb reverse tcp:9222 tcp:9222`, after which ClosePaw can talk CDP through " +
            "device-local 127.0.0.1:9222.",
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
        message = "Wireless-ADB self-pair could not be brought up on this device. " +
            "Verify (a) Shizuku is granted, (b) the device is on a Wi-Fi network, " +
            "(c) IAdbManager AIDL is reachable from the shell uid (com.android.shell " +
            "must hold MANAGE_DEBUGGING — true on stock AOSP and most OEM builds).",
        cause = cause,
    )

    class MalformedResponse(detail: String, cause: Throwable? = null) : DevtoolsSetupError(
        code = "malformed_response",
        message = "Chrome DevTools returned a malformed HTTP/WebSocket response: $detail",
        cause = cause,
    )
}
