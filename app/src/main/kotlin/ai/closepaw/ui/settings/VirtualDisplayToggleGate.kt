package ai.closepaw.ui.settings

import ai.closepaw.platform.virtualdisplay.PermissionRequestResult
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.ShizukuRuntimeGateway
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared gating layer for switching the platform mode to `VIRTUAL_DISPLAY`.
 *
 * Virtual Display requires Shizuku (the screen-capture projection is launched via Shizuku's
 * shell UID). Persisting the mode as VD when Shizuku is unavailable would leave the user with
 * a permanently broken capture pipeline and no actionable feedback in the UI.
 *
 * The single UI surface that flips the mode — the Display section in Settings — MUST go through
 * [gateVirtualDisplayEnable] (or the Compose-friendly [rememberVirtualDisplayToggleGate]).
 * Programmatic writes from `MainActivityIntentApplier` (deep links / intents) intentionally
 * bypass the gate; the Display status card surfaces the resulting "persisted=VD but Shizuku
 * not ready" state honestly so the bypass is visible rather than hidden.
 *
 * Asymmetry vs. [BrowserScriptToggleGate]: this gate does NOT preflight any file I/O. The
 * browser_script gate runs `CommandLineWriter.ensureWritten` to verify Chrome's command-line
 * file was written and surfaces a `WriteFailed` variant on failure. Virtual Display has no
 * analogous preflight step — successful Shizuku permission is the entire contract — so the
 * sealed interface has no `WriteFailed` variant and the suspend helper takes no writer lambda.
 * Readers expecting a write-failure error path here will not find one by design.
 *
 * When the binder is alive but permission is missing, the gate requests Shizuku permission
 * inline (Shizuku service shows its own system dialog) and waits for the result before
 * deciding. This avoids the post-`adb install -r` trap where Shizuku Manager shows "granted"
 * (matched by package name) but [ShizukuClient.hasPermission] reports DENIED (matched by the
 * fresh UID) — the only fix is for the app to call `requestPermission` so Shizuku writes a
 * fresh consent row keyed to the new UID.
 */

/** Categorized failures from the gated enable flow. */
internal sealed interface VirtualDisplayToggleError {
    data object ShizukuUnavailable : VirtualDisplayToggleError
    /** User explicitly tapped Deny on the Shizuku consent dialog this turn. */
    data object ShizukuPermissionDenied : VirtualDisplayToggleError
}

internal fun VirtualDisplayToggleError.message(): String = when (this) {
    VirtualDisplayToggleError.ShizukuUnavailable ->
        "Shizuku is not running. Install or start Shizuku, then turn on Virtual Display."
    VirtualDisplayToggleError.ShizukuPermissionDenied ->
        "Permission denied. Tap to retry, or grant in Shizuku Manager."
}

/**
 * Pure suspend helper. Returns null when the toggle should be persisted ON, or the
 * categorized failure otherwise. The lambdas have production-ready defaults; tests inject
 * fakes to exercise every branch without a Shizuku binder.
 *
 * Callers are responsible for dispatcher selection — the helper itself is dispatcher-agnostic
 * so unit tests run deterministically under `runTest`.
 */
internal suspend fun gateVirtualDisplayEnable(
    isShizukuAvailable: suspend () -> Boolean = { ShizukuClient().isAvailable() },
    hasShizukuPermission: suspend () -> Boolean = { ShizukuClient().hasPermission() },
    requestShizukuPermission: suspend () -> PermissionRequestResult = {
        ShizukuRuntimeGateway().requestPermissionAndAwait()
    },
): VirtualDisplayToggleError? {
    if (!isShizukuAvailable()) return VirtualDisplayToggleError.ShizukuUnavailable
    if (!hasShizukuPermission()) {
        when (requestShizukuPermission()) {
            PermissionRequestResult.Granted -> {
                // Re-check once: defends against the listener firing with GRANTED while the
                // underlying UID consent row was somehow not written (paranoia path; should not
                // happen on a healthy Shizuku install).
                if (!hasShizukuPermission()) {
                    return VirtualDisplayToggleError.ShizukuPermissionDenied
                }
            }
            PermissionRequestResult.Denied ->
                return VirtualDisplayToggleError.ShizukuPermissionDenied
            PermissionRequestResult.Error ->
                return VirtualDisplayToggleError.ShizukuUnavailable
        }
    }
    return null
}

/**
 * Compose-friendly state holder for the VD platform-mode toggle. The Display section calls
 * [setEnabled]; the holder runs the gate, exposes [pending] while it works, and exposes
 * [error] when the gate refuses. The persist callback only fires after a successful gate —
 * a failed gate leaves the mode untouched (no back-door).
 *
 * Toggle OFF (switching back to accessibility-only) is unconditional — it never makes things
 * worse, so no gate.
 */
internal class VirtualDisplayToggleGate internal constructor(
    private val scope: CoroutineScope,
    private val onPersist: (Boolean) -> Unit,
    private val gate: suspend () -> VirtualDisplayToggleError? = ::gateVirtualDisplayEnable,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<VirtualDisplayToggleError?>(null)
        private set

    /**
     * Begin a toggle attempt. Compose call sites MUST invoke [clearError] immediately before
     * this so any stale inline error is wiped on tap — that covers the early-bail branches in
     * here (e.g. `pending == true && value == true`) where we would otherwise leave the
     * previous error visible after the user has gone off, fixed the underlying issue, and come
     * back to retry.
     */
    fun setEnabled(value: Boolean) {
        if (!value) {
            error = null
            onPersist(false)
            return
        }
        if (pending) return
        error = null
        pending = true
        scope.launch {
            val result = withContext(ioDispatcher) { gate() }
            pending = false
            if (result == null) {
                onPersist(true)
            } else {
                error = result
            }
        }
    }

    fun clearError() {
        error = null
    }
}

@Composable
internal fun rememberVirtualDisplayToggleGate(
    onPersist: (Boolean) -> Unit,
): VirtualDisplayToggleGate {
    val scope = rememberCoroutineScope()
    return remember(scope, onPersist) {
        VirtualDisplayToggleGate(scope = scope, onPersist = onPersist)
    }
}
