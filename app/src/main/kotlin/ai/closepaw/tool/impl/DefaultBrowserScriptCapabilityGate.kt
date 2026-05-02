package ai.closepaw.tool.impl

import ai.closepaw.browser.cdp.shizuku.DevtoolsSetupError
import ai.closepaw.browser.cdp.shizuku.ShizukuStatusProvider
import kotlinx.coroutines.CancellationException

/**
 * Production capability gate. Each check fails closed with a distinct, actionable code so the
 * agent and UI can compose specific guidance ("turn on experimental flag", "grant Shizuku
 * permission", "open Chrome", "enable Chrome USB debugging path") instead of a generic error.
 *
 * Order matters: the cheapest checks come first so we don't spin up Shizuku UserService binding
 * when the experimental flag is off.
 *
 * Dependencies are injected as functional seams so the gate has zero Android coupling and
 * the wiring (AppSettingsStore, ShizukuStatusAdapter, ShizukuChromeDevtoolsBridge.preflight,
 * BrowserScriptRunner.run) lives in SessionServices.
 */
class DefaultBrowserScriptCapabilityGate(
    private val isExperimentalEnabled: () -> Boolean,
    private val shizukuStatus: ShizukuStatusProvider,
    private val preflight: suspend () -> Unit,
    private val invokerFactory: () -> BrowserScriptInvoker,
) : BrowserScriptCapabilityGate {

    override suspend fun acquire(): BrowserScriptCapabilityGate.Outcome {
        if (!isExperimentalEnabled()) {
            return unavailable(
                CODE_EXPERIMENTAL_DISABLED,
                "Browser automation is disabled. Enable it in app settings before invoking " +
                    "browser_script.",
            )
        }
        if (!shizukuStatus.isAvailable()) {
            return fromSetupError(DevtoolsSetupError.ShizukuUnavailable)
        }
        if (!shizukuStatus.hasPermission()) {
            return fromSetupError(DevtoolsSetupError.ShizukuPermissionMissing)
        }
        try {
            preflight()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: DevtoolsSetupError) {
            return fromSetupError(e)
        }
        return BrowserScriptCapabilityGate.Outcome.Available(invokerFactory())
    }

    private fun unavailable(code: String, reason: String) =
        BrowserScriptCapabilityGate.Outcome.Unavailable(code, reason)

    private fun fromSetupError(e: DevtoolsSetupError) = unavailable(
        e.code,
        e.message ?: "browser_script capability error: ${e.code}",
    )

    companion object {
        const val CODE_EXPERIMENTAL_DISABLED: String = "experimental_disabled"
    }
}
