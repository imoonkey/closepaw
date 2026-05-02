package ai.closepaw.tool.impl

import ai.closepaw.browser.cdp.shizuku.DevtoolsSetupError
import ai.closepaw.browser.cdp.shizuku.ShizukuStatusProvider
import ai.closepaw.browser.script.ScriptResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for the production capability gate. Each gate condition must surface a distinct,
 * stable code so the agent and UI can compose the right setup guidance.
 *
 * Order matters: experimental flag is checked BEFORE Shizuku, so disabling the experimental
 * flag must not trigger a Shizuku probe (avoids spinning up the UserService binding when the
 * feature is off).
 */
class DefaultBrowserScriptCapabilityGateTest {

    @Test
    fun `experimental disabled fails closed before any other probe`() = runTest {
        var shizukuProbed = false
        var preflightCalled = false
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { false },
            shizukuStatus = recordingStatus(true, true) { shizukuProbed = true },
            preflight = { preflightCalled = true },
            invokerFactory = { error("invoker built without gates") },
        )

        val outcome = gate.acquire()

        assertThat(outcome).isInstanceOf(BrowserScriptCapabilityGate.Outcome.Unavailable::class.java)
        val u = outcome as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DefaultBrowserScriptCapabilityGate.CODE_EXPERIMENTAL_DISABLED)
        assertThat(u.reason).contains("Enable")
        assertThat(shizukuProbed).isFalse()
        assertThat(preflightCalled).isFalse()
    }

    @Test
    fun `shizuku unavailable maps to ShizukuUnavailable code and skips preflight`() = runTest {
        var preflightCalled = false
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            shizukuStatus = staticStatus(available = false, permission = false),
            preflight = { preflightCalled = true },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ShizukuUnavailable.code)
        assertThat(preflightCalled).isFalse()
    }

    @Test
    fun `shizuku permission missing maps to ShizukuPermissionMissing code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            shizukuStatus = staticStatus(available = true, permission = false),
            preflight = { error("preflight unexpectedly called") },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ShizukuPermissionMissing.code)
    }

    @Test
    fun `preflight DevtoolsSocketMissing surfaces precise code`() = runTest {
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            shizukuStatus = staticStatus(available = true, permission = true),
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
            shizukuStatus = staticStatus(available = true, permission = true),
            preflight = { throw DevtoolsSetupError.ChromeNotRunning },
            invokerFactory = { error("not reached") },
        )

        val u = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Unavailable
        assertThat(u.code).isEqualTo(DevtoolsSetupError.ChromeNotRunning.code)
    }

    @Test
    fun `all checks pass returns Available with the supplied invoker`() = runTest {
        val sentinel = BrowserScriptInvoker { _, _ -> ScriptResult.Ok("\"ok\"") }
        val gate = DefaultBrowserScriptCapabilityGate(
            isExperimentalEnabled = { true },
            shizukuStatus = staticStatus(available = true, permission = true),
            preflight = { /* no-op success */ },
            invokerFactory = { sentinel },
        )

        val available = gate.acquire() as BrowserScriptCapabilityGate.Outcome.Available
        assertThat(available.invoker).isSameInstanceAs(sentinel)
    }

    private fun staticStatus(available: Boolean, permission: Boolean): ShizukuStatusProvider =
        object : ShizukuStatusProvider {
            override fun isAvailable(): Boolean = available
            override fun hasPermission(): Boolean = permission
        }

    private fun recordingStatus(
        available: Boolean,
        permission: Boolean,
        onProbe: () -> Unit,
    ): ShizukuStatusProvider = object : ShizukuStatusProvider {
        override fun isAvailable(): Boolean { onProbe(); return available }
        override fun hasPermission(): Boolean { onProbe(); return permission }
    }
}
