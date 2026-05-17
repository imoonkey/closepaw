package ai.closepaw.ui.settings

import ai.closepaw.browser.setup.CommandLineWriter
import ai.closepaw.platform.virtualdisplay.PermissionRequestResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Coverage for the `browser_script` enable gate. The Agent Behavior → Tools toggle is the
 * single UI surface that flips the pref, and every gate branch matters: a regression here
 * re-opens the back-door where the toggle persists ON without the gating contract holding.
 *
 * The gate now requests Shizuku permission inline when binder is alive but consent is missing
 * (recovers from the post-`adb install -r` UID-mismatch trap). The new tests pin the inline
 * request behavior so we never quietly revert to the old "send user to Shizuku Manager" path.
 */
class BrowserScriptToggleGateTest {

    @Test
    fun `returns ShizukuUnavailable when isShizukuAvailable is false`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { false },
            hasShizukuPermission = { error("must not be called") },
            requestShizukuPermission = { error("must not be called") },
            ensureCommandLineWritten = { error("must not be called") },
        )

        assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuUnavailable)
    }

    @Test
    fun `returns WriteFailed when writer reports Failed`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            requestShizukuPermission = { error("must not be called") },
            ensureCommandLineWritten = { CommandLineWriter.Outcome.Failed },
        )

        assertThat(result).isEqualTo(BrowserScriptToggleError.WriteFailed)
    }

    @Test
    fun `returns null on Written outcome — gate passes`() = runTest {
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            requestShizukuPermission = { error("must not be called") },
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
                requestShizukuPermission = { error("must not be called") },
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
                requestShizukuPermission = { error("must not be called") },
                ensureCommandLineWritten = { CommandLineWriter.Outcome.Written },
            )

            assertThat(permissionCallCount).isEqualTo(0)
        }

    @Test
    fun `write is short-circuited — never spawns shell when permission missing and request denied`() =
        runTest {
            var writeCallCount = 0
            gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Denied },
                ensureCommandLineWritten = { writeCallCount++; CommandLineWriter.Outcome.Written },
            )

            assertThat(writeCallCount).isEqualTo(0)
        }

    // ── Inline permission request paths ─────────────────────────────────────────────────

    @Test
    fun `denied permission triggers inline request — granted result proceeds to write`() =
        runTest {
            var requestCount = 0
            var writeCount = 0
            // Permission flips to true after the request is granted, simulating Shizuku writing
            // a fresh consent row keyed to the current UID.
            var permissionGranted = false
            val result = gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { permissionGranted },
                requestShizukuPermission = {
                    requestCount++
                    permissionGranted = true
                    PermissionRequestResult.Granted
                },
                ensureCommandLineWritten = {
                    writeCount++
                    CommandLineWriter.Outcome.Written
                },
            )

            assertThat(requestCount).isEqualTo(1)
            assertThat(writeCount).isEqualTo(1)
            assertThat(result).isNull()
        }

    @Test
    fun `denied permission triggers inline request — user denies returns ShizukuPermissionDenied`() =
        runTest {
            var writeCount = 0
            val result = gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Denied },
                ensureCommandLineWritten = {
                    writeCount++
                    CommandLineWriter.Outcome.Written
                },
            )

            assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuPermissionDenied)
            assertThat(writeCount).isEqualTo(0)
        }

    @Test
    fun `gateway error during request returns ShizukuUnavailable — surface as binder-dropped`() =
        runTest {
            val result = gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Error },
                ensureCommandLineWritten = { error("must not be called") },
            )

            assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuUnavailable)
        }

    @Test
    fun `granted result with stale checkSelfPermission re-check returns ShizukuPermissionDenied`() =
        runTest {
            // Paranoia path: listener fires GRANTED but the underlying UID consent row was not
            // written. Should not happen on a healthy Shizuku install, but if it does we must
            // not silently proceed — the user needs actionable feedback.
            val result = gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Granted },
                ensureCommandLineWritten = { error("must not be called") },
            )

            assertThat(result).isEqualTo(BrowserScriptToggleError.ShizukuPermissionDenied)
        }

    @Test
    fun `already-granted path does not trigger inline request — no spurious dialog`() = runTest {
        var requestCount = 0
        val result = gateBrowserScriptEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            requestShizukuPermission = {
                requestCount++
                PermissionRequestResult.Granted
            },
            ensureCommandLineWritten = { CommandLineWriter.Outcome.Written },
        )

        assertThat(requestCount).isEqualTo(0)
        assertThat(result).isNull()
    }

    @Test
    fun `cancellation mid-request — write never runs`() = runTest {
        var writeCount = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            gateBrowserScriptEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = {
                    started.complete(Unit)
                    // Suspend forever, simulating a user who never taps Allow/Deny. Cancellation
                    // of the parent coroutine should propagate here and unwind cleanly.
                    delay(Long.MAX_VALUE)
                    PermissionRequestResult.Granted
                },
                ensureCommandLineWritten = {
                    writeCount++
                    CommandLineWriter.Outcome.Written
                },
            )
        }
        started.await()
        yield()
        job.cancel()
        job.join()

        assertThat(writeCount).isEqualTo(0)
    }

    // ── BrowserScriptToggleGate (Compose state holder) ──────────────────────────────────

    @Test
    fun `clearError wipes a stale error from a prior failed gate run`() = runTest {
        val persisted = mutableListOf<Boolean>()
        val gate = BrowserScriptToggleGate(
            scope = backgroundScope,
            onPersist = { persisted += it },
            gate = { BrowserScriptToggleError.ShizukuUnavailable },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        gate.setEnabled(true)
        // runTest with Unconfined dispatcher resolves the launch synchronously enough that the
        // gate has settled by the time we observe state.
        yield()

        assertThat(gate.error).isEqualTo(BrowserScriptToggleError.ShizukuUnavailable)
        assertThat(persisted).isEmpty()

        gate.clearError()

        assertThat(gate.error).isNull()
    }

    @Test
    fun `clearError before setEnabled keeps gate behavior intact on retry`() = runTest {
        val persisted = mutableListOf<Boolean>()
        var attempt = 0
        val gate = BrowserScriptToggleGate(
            scope = backgroundScope,
            onPersist = { persisted += it },
            gate = {
                // First attempt fails (e.g., Shizuku not yet granted), second succeeds (user
                // returned and granted). Mirrors the call-site contract:
                //     onCheckedChange = { gate.clearError(); gate.setEnabled(it) }
                attempt++
                if (attempt == 1) BrowserScriptToggleError.ShizukuPermissionDenied else null
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        gate.setEnabled(true)
        yield()
        assertThat(gate.error).isEqualTo(BrowserScriptToggleError.ShizukuPermissionDenied)

        // What the Compose surface does on the next tap.
        gate.clearError()
        gate.setEnabled(true)
        yield()

        assertThat(gate.error).isNull()
        assertThat(persisted).containsExactly(true)
    }
}
