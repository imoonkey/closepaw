package ai.closepaw.tool.impl

import ai.closepaw.browser.cdp.shizuku.DevtoolsSetupError
import ai.closepaw.browser.script.ScriptResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for the production capability gate. Each gate condition must surface a distinct,
 * stable code so the agent and UI can compose the right setup guidance.
 *
 * Order matters: the experimental flag is checked BEFORE the bridge preflight, so disabling
 * the experimental flag must not trigger transport probes that themselves spin up Shizuku
 * binding or a TCP relay connect when the feature is off.
 */
class DefaultBrowserScriptCapabilityGateTest {

    @Test
    fun `experimental disabled fails closed before any other probe`() = runTest {
        var preflightCalled = false
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { false },
            preflight = { preflightCalled = true },
            invokerFactory = { error("invoker built without gates") },
        )

        val outcome = gate.acquire()

        assertThat(outcome).isInstanceOf(BrowserScriptCapabilityGate.Outcome.Unavailable::class.java)
        val u = outcome as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DefaultBrowserScriptCapabilityGate.CODE_EXPERIMENTAL_DISABLED)
        assertThat(u.reason).contains("Enable")
        assertThat(preflightCalled).isFalse()
    }

    @Test
    fun `preflight ShizukuUnavailable surfaces precise code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { throw DevtoolsSetupError.ShizukuUnavailable },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ShizukuUnavailable.code)
    }

    @Test
    fun `preflight ShizukuPermissionMissing surfaces precise code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { throw DevtoolsSetupError.ShizukuPermissionMissing },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ShizukuPermissionMissing.code)
    }

    @Test
    fun `preflight DevtoolsSocketMissing surfaces precise code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { throw DevtoolsSetupError.DevtoolsSocketMissing },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.DevtoolsSocketMissing.code)
        assertThat(u.reason).contains("DevTools socket")
    }

    @Test
    fun `preflight ChromeNotRunning surfaces precise code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { throw DevtoolsSetupError.ChromeNotRunning },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ChromeNotRunning.code)
    }

    @Test
    fun `preflight HostMediatedRelayUnreachable surfaces actionable setup hint`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { throw DevtoolsSetupError.HostMediatedRelayUnreachable(null) },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo("host_mediated_relay_unreachable")
        assertThat(u.reason).contains("scripts/setup-cdp-relay.sh")
    }

    @Test
    fun `all checks pass returns Available with the supplied invoker`() = runTest {
        val sentinel = BrowserScriptInvoker { _, _ -> ScriptResult.Ok("\"ok\"") }
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            preflight = { /* no-op success */ },
            invokerFactory = { sentinel },
        )

        val available = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Available
        assertThat(available.invoker).isSameInstanceAs(sentinel)
    }
}
