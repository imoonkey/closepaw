package ai.closepaw.ui.settings

import ai.closepaw.browser.setup.CommandLineWriter
import ai.closepaw.platform.virtualdisplay.ShizukuClient
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
 * Shared gating layer for the `browser_script` user pref.
 *
 * `browser_script` requires Shizuku (to write `/data/local/tmp/chrome-command-line` and to
 * drive `am start` for the chrome flag deep link). Persisting the pref ON when Shizuku is
 * unavailable would leave the user with an LLM-visible tool that always errors and a
 * permanently-✗ status row with no actionable feedback.
 *
 * Both surfaces that flip the pref — the AgentBehavior **Tools** section and the
 * Permissions & Advanced **Experimental** section — MUST go through [gateBrowserScriptEnable]
 * (or the Compose-friendly [rememberBrowserScriptToggleGate]). Direct calls to
 * `AppSettingsState.updateBrowserScriptEnabled(true)` are a back-door now that both surfaces
 * share state.
 */

/** Categorized failures from the gated enable flow. */
internal sealed interface BrowserScriptToggleError {
    data object ShizukuUnavailable : BrowserScriptToggleError
    data object ShizukuNeedsPermission : BrowserScriptToggleError
    data object WriteFailed : BrowserScriptToggleError
}

internal fun BrowserScriptToggleError.message(): String = when (this) {
    BrowserScriptToggleError.ShizukuUnavailable ->
        "Shizuku is not running. Start Shizuku first, then enable browser_script."
    BrowserScriptToggleError.ShizukuNeedsPermission ->
        "Grant Shizuku permission to ClosePaw, then re-enable browser_script."
    BrowserScriptToggleError.WriteFailed ->
        "Could not write Chrome's command-line file. Check Shizuku and try again."
}

/**
 * Pure suspend helper. Returns null when the toggle should be persisted ON, or the
 * categorized failure otherwise. The lambdas have production-ready defaults; tests inject
 * fakes to exercise every branch without a Shizuku binder.
 *
 * Callers are responsible for dispatcher selection — the helper itself is dispatcher-agnostic
 * so unit tests run deterministically under `runTest`.
 */
internal suspend fun gateBrowserScriptEnable(
    isShizukuAvailable: suspend () -> Boolean = { ShizukuClient().isAvailable() },
    hasShizukuPermission: suspend () -> Boolean = { ShizukuClient().hasPermission() },
    ensureCommandLineWritten: suspend () -> CommandLineWriter.Outcome = {
        CommandLineWriter().ensureWritten()
    },
): BrowserScriptToggleError? {
    if (!isShizukuAvailable()) return BrowserScriptToggleError.ShizukuUnavailable
    if (!hasShizukuPermission()) return BrowserScriptToggleError.ShizukuNeedsPermission
    return when (ensureCommandLineWritten()) {
        CommandLineWriter.Outcome.Failed -> BrowserScriptToggleError.WriteFailed
        CommandLineWriter.Outcome.Written,
        CommandLineWriter.Outcome.AlreadyCorrect -> null
    }
}

/**
 * Compose-friendly state holder for a toggle that needs gating. Each surface that wants to
 * toggle `browser_script` calls [setEnabled]; the holder runs the gate, exposes [pending]
 * while it works, and exposes [error] when the gate refuses. The persist callback only fires
 * after a successful gate — a failed gate leaves the pref untouched (no back-door).
 *
 * Toggle OFF is unconditional — it never makes things worse, so no gate.
 */
internal class BrowserScriptToggleGate internal constructor(
    private val scope: CoroutineScope,
    private val onPersist: (Boolean) -> Unit,
    private val gate: suspend () -> BrowserScriptToggleError? = ::gateBrowserScriptEnable,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<BrowserScriptToggleError?>(null)
        private set

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
internal fun rememberBrowserScriptToggleGate(
    onPersist: (Boolean) -> Unit,
): BrowserScriptToggleGate {
    val scope = rememberCoroutineScope()
    return remember(scope, onPersist) {
        BrowserScriptToggleGate(scope = scope, onPersist = onPersist)
    }
}
