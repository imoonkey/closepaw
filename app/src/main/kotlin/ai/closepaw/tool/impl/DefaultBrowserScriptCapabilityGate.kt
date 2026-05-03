package ai.closepaw.tool.impl

import ai.closepaw.browser.cdp.shizuku.DevtoolsSetupError
import kotlinx.coroutines.CancellationException

/**
 * Production capability gate.
 *
 * The gate has two responsibilities:
 *
 * 1. Reject early when the experimental browser-automation flag is off, so we never spin up
 *    transport probes for a disabled feature.
 * 2. Surface the most actionable [DevtoolsSetupError] for whichever transport state the
 *    bridge reports during preflight (Chrome socket missing, Shizuku unavailable, wireless-ADB
 *    self-pair unable to bring up its in-device path). The bridge owns the cascade
 *    (USER_SERVICE → WIRELESS_ADB_SELF_PAIR) and per-transport probe logic; the gate just
 *    turns that verdict into a stable code/reason string the agent and UI can render.
 *
 * Both supported transports require Shizuku, so the bridge's preflight short-circuits on
 * Shizuku availability before either transport is attempted.
 *
 * Dependencies are injected as functional seams so the gate has zero Android coupling and
 * the wiring (AppSettingsStore, ShizukuChromeDevtoolsBridge.preflight, BrowserScriptRunner.run)
 * lives in SessionServices.
 */
class DefaultBrowserScriptCapabilityGate(
    private val isExperimentalEnabled: () -> Boolean,
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
