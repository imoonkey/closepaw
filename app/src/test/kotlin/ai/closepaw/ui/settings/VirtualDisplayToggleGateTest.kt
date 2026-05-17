package ai.closepaw.ui.settings

import ai.closepaw.platform.virtualdisplay.PermissionRequestResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Coverage for the Virtual Display enable gate. The Display section toggle is the single UI
 * surface that flips the platform mode to VD, and every gate branch matters: a regression here
 * re-opens the back-door where the mode persists as VD without the gating contract holding.
 *
 * Mirrors [BrowserScriptToggleGateTest] one-for-one, minus the writer-preflight cases (this
 * gate has no I/O preflight by design — see the KDoc on [VirtualDisplayToggleGate]).
 */
class VirtualDisplayToggleGateTest {

    @Test
    fun `returns ShizukuUnavailable when isShizukuAvailable is false`() = runTest {
        val result = gateVirtualDisplayEnable(
            isShizukuAvailable = { false },
            hasShizukuPermission = { error("must not be called") },
            requestShizukuPermission = { error("must not be called") },
        )

        assertThat(result).isEqualTo(VirtualDisplayToggleError.ShizukuUnavailable)
    }

    @Test
    fun `permission check is short-circuited — never queries permission when binder unavailable`() =
        runTest {
            var permissionCallCount = 0
            gateVirtualDisplayEnable(
                isShizukuAvailable = { false },
                hasShizukuPermission = { permissionCallCount++; true },
                requestShizukuPermission = { error("must not be called") },
            )

            assertThat(permissionCallCount).isEqualTo(0)
        }

    @Test
    fun `already-granted path does not trigger inline request — no spurious dialog`() = runTest {
        var requestCount = 0
        val result = gateVirtualDisplayEnable(
            isShizukuAvailable = { true },
            hasShizukuPermission = { true },
            requestShizukuPermission = {
                requestCount++
                PermissionRequestResult.Granted
            },
        )

        assertThat(requestCount).isEqualTo(0)
        assertThat(result).isNull()
    }

    @Test
    fun `denied permission triggers inline request — granted result proceeds to success`() =
        runTest {
            var requestCount = 0
            // Permission flips to true after the request is granted, simulating Shizuku writing
            // a fresh consent row keyed to the current UID.
            var permissionGranted = false
            val result = gateVirtualDisplayEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { permissionGranted },
                requestShizukuPermission = {
                    requestCount++
                    permissionGranted = true
                    PermissionRequestResult.Granted
                },
            )

            assertThat(requestCount).isEqualTo(1)
            assertThat(result).isNull()
        }

    @Test
    fun `denied permission triggers inline request — user denies returns ShizukuPermissionDenied`() =
        runTest {
            val result = gateVirtualDisplayEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Denied },
            )

            assertThat(result).isEqualTo(VirtualDisplayToggleError.ShizukuPermissionDenied)
        }

    @Test
    fun `gateway error during request returns ShizukuUnavailable — surface as binder-dropped`() =
        runTest {
            val result = gateVirtualDisplayEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Error },
            )

            assertThat(result).isEqualTo(VirtualDisplayToggleError.ShizukuUnavailable)
        }

    @Test
    fun `granted result with stale checkSelfPermission re-check returns ShizukuPermissionDenied`() =
        runTest {
            // Paranoia path: listener fires GRANTED but the underlying UID consent row was not
            // written. Should not happen on a healthy Shizuku install, but if it does we must
            // not silently proceed — the user needs actionable feedback.
            val result = gateVirtualDisplayEnable(
                isShizukuAvailable = { true },
                hasShizukuPermission = { false },
                requestShizukuPermission = { PermissionRequestResult.Granted },
            )

            assertThat(result).isEqualTo(VirtualDisplayToggleError.ShizukuPermissionDenied)
        }

    // ── VirtualDisplayToggleGate (Compose state holder) ─────────────────────────────────

    @Test
    fun `setEnabled(false) bypasses gate — persists false without invoking gate`() = runTest {
        val persisted = mutableListOf<Boolean>()
        var gateInvocations = 0
        val gate = VirtualDisplayToggleGate(
            scope = backgroundScope,
            onPersist = { persisted += it },
            gate = {
                gateInvocations++
                null
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        gate.setEnabled(false)
        yield()

        assertThat(persisted).containsExactly(false)
        assertThat(gateInvocations).isEqualTo(0)
        assertThat(gate.error).isNull()
    }

    @Test
    fun `double-tap-on during pending is a no-op — second setEnabled(true) does not relaunch gate`() =
        runTest {
            val persisted = mutableListOf<Boolean>()
            var gateInvocations = 0
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val gate = VirtualDisplayToggleGate(
                scope = backgroundScope,
                onPersist = { persisted += it },
                gate = {
                    gateInvocations++
                    // Suspend so the first attempt stays in `pending` while the second tap lands.
                    release.await()
                    null
                },
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )

            gate.setEnabled(true)
            yield()
            assertThat(gate.pending).isTrue()
            assertThat(gateInvocations).isEqualTo(1)

            // Second tap while pending — must NOT relaunch.
            gate.setEnabled(true)
            yield()
            assertThat(gateInvocations).isEqualTo(1)

            release.complete(Unit)
            yield()
            assertThat(gate.pending).isFalse()
            assertThat(persisted).containsExactly(true)
        }

    @Test
    fun `clearError wipes a stale error from a prior failed gate run`() = runTest {
        val persisted = mutableListOf<Boolean>()
        val gate = VirtualDisplayToggleGate(
            scope = backgroundScope,
            onPersist = { persisted += it },
            gate = { VirtualDisplayToggleError.ShizukuUnavailable },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        gate.setEnabled(true)
        yield()

        assertThat(gate.error).isEqualTo(VirtualDisplayToggleError.ShizukuUnavailable)
        assertThat(persisted).isEmpty()

        gate.clearError()

        assertThat(gate.error).isNull()
    }

    @Test
    fun `clearError before setEnabled keeps gate behavior intact on retry`() = runTest {
        val persisted = mutableListOf<Boolean>()
        var attempt = 0
        val gate = VirtualDisplayToggleGate(
            scope = backgroundScope,
            onPersist = { persisted += it },
            gate = {
                // First attempt fails (e.g., Shizuku not yet granted), second succeeds (user
                // returned and granted). Mirrors the call-site contract:
                //     onCheckedChange = { gate.clearError(); gate.setEnabled(it) }
                attempt++
                if (attempt == 1) VirtualDisplayToggleError.ShizukuPermissionDenied else null
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        gate.setEnabled(true)
        yield()
        assertThat(gate.error).isEqualTo(VirtualDisplayToggleError.ShizukuPermissionDenied)

        gate.clearError()
        gate.setEnabled(true)
        yield()

        assertThat(gate.error).isNull()
        assertThat(persisted).containsExactly(true)
    }
}
