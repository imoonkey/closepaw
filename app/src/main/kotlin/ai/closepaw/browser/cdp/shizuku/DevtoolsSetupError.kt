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

    class MalformedResponse(detail: String, cause: Throwable? = null) : DevtoolsSetupError(
        code = "malformed_response",
        message = "Chrome DevTools returned a malformed HTTP/WebSocket response: $detail",
        cause = cause,
    )
}
