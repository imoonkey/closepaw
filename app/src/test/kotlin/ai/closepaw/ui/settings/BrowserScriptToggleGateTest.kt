package ai.closepaw.ui.settings

import ai.closepaw.browser.setup.CommandLineWriter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for the shared `browser_script` enable gate. Both the Tools toggle and the
 * Permissions Experimental toggle must call this helper, so every branch matters: a regression
 * here re-opens the back-door where one toggle persists ON without the gating the other does.
 */
class BrowserScriptToggleGateTest {

    @Test
    fun `returns ShizukuUnavailable when isShizukuAvailable is false`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { false },
            hasShizukuPermission = { error("must not be called") },
            ensureCommandLineWritten = { error("must not be called") },
        )

        assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuUnavailable)
    }

    @Test
    fun `returns ShizukuNeedsPermission when permission is false`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { false },
            ensureCommandLineWritten = { error("must not be called") },
        )

        assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuNeedsPermission)
    }

    @Test
    fun `returns WriteFailed when writer reports Failed`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            ensureCommandLineWritten = { CommandLineWriter.Outcome.Failed },
        )

        assertThat(result).isEqualTo(BrowserScriptToggleError.WriteFailed)
    }

    @Test
    fun `returns null on Written outcome — gate passes`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            ensureCommandLineWritten = { CommandLineWriter.Outcome.Written },
        )

        assertThat(result).isNull()
    }

    @Test
    fun `returns null on AlreadyCorrect outcome — idempotent re-enable still gates clean`() =
        runTest {
            val result = gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { true },
                ensureCommandLineWritten = { CommandLineWriter.Outcome.AlreadyCorrect },
            )

            assertThat(result).isNull()
        }

    @Test
    fun `permission check is short-circuited — never queries permission when binder unavailable`() =
        runTest {
            // Counts to ensure short-circuit: if hasShizukuPermission ran, we'd see a non-zero
            // count and the assertion below would fail. This guards against re-ordering
            // accidents that would make the gate query permission against a dead binder.
            var permissionCallCount = 0
            gateBrowserScriptEnable(
                isShizukuAvailable = { false },
                hasShizukuPermission = { permissionCallCount++; true },
                ensureCommandLineWritten = { CommandLineWriter.Outcome.Written },
            )

            assertThat(permissionCallCount).isEqualTo(0)
        }

    @Test
    fun `write is short-circuited — never spawns shell when permission missing`() = runTest {
        var writeCallCount = 0
        gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { false },
            ensureCommandLineWritten = { writeCallCount++; CommandLineWriter.Outcome.Written },
        )

        assertThat(writeCallCount).isEqualTo(0)
    }
}
