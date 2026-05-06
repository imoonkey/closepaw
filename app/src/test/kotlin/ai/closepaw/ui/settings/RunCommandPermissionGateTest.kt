package ai.closepaw.ui.settings

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Coverage for [classifyRunCommandPermission].
 *
 * Pinning the disposition matrix matters because each row has a distinct user-visible effect:
 * [RunCommandPermissionDisposition.Granted] silently runs setup, `Request` fires the system
 * dialog, and `OpenAppSettings` sends the user out to system settings. Mis-classifying any
 * one of them re-creates the bug we are fixing — either the row never asks (so the user is
 * stuck), or it spam-asks even after the user grants the permission, or it sends them to App
 * Settings before they have ever seen the dialog.
 *
 * The launcher / activity wiring around the classifier is exercised on-device only — the
 * `ActivityResultRegistry` requires a real Activity lifecycle to register the contract, which
 * is out of reach for JVM unit tests.
 */
class RunCommandPermissionGateTest {

    @Test
    fun `granted overrides everything else`() {
        // Even if hasAttempted is true and rationale is false (the "permanent deny" pair),
        // an explicit grant must dominate — the system has flipped the bit and we should not
        // chase the user with a dialog or App Settings.
        assertThat(
            classifyRunCommandPermission(
                isGranted = true,
                hasAttempted = true,
                shouldShowRationale = false,
            )
        ).isEqualTo(RunCommandPermissionDisposition.Granted)
    }

    @Test
    fun `granted dominates even with hasAttempted false and rationale true`() {
        // Defensive: neither input matters once the framework reports GRANTED. Pin it so a
        // future refactor doesn't accidentally invert the classifier's priority.
        assertThat(
            classifyRunCommandPermission(
                isGranted = true,
                hasAttempted = false,
                shouldShowRationale = true,
            )
        ).isEqualTo(RunCommandPermissionDisposition.Granted)
    }

    @Test
    fun `first-launch state — never asked, no rationale — routes to Request`() {
        // Android contract: shouldShowRationale is false BEFORE the first ask. Without
        // hasAttempted, this would mis-route to OpenAppSettings on a fresh install — exactly
        // the bug we are fixing — so this test pins the disambiguation.
        assertThat(
            classifyRunCommandPermission(
                isGranted = false,
                hasAttempted = false,
                shouldShowRationale = false,
            )
        ).isEqualTo(RunCommandPermissionDisposition.Request)
    }

    @Test
    fun `first-deny — rationale becomes true — routes to Request for retry`() {
        // After a single deny on Android 11+, the system surfaces the rationale flag. The user
        // can still see the dialog on the next request, so the row tap should fire it again
        // rather than punting to App Settings.
        assertThat(
            classifyRunCommandPermission(
                isGranted = false,
                hasAttempted = true,
                shouldShowRationale = true,
            )
        ).isEqualTo(RunCommandPermissionDisposition.Request)
    }

    @Test
    fun `permanent-deny — asked once and rationale false — routes to OpenAppSettings`() {
        // The "Don't ask again" / second-deny state. The system suppresses the dialog and the
        // only recovery is the App Settings → Permissions screen.
        assertThat(
            classifyRunCommandPermission(
                isGranted = false,
                hasAttempted = true,
                shouldShowRationale = false,
            )
        ).isEqualTo(RunCommandPermissionDisposition.OpenAppSettings)
    }

    @Test
    fun `not-asked with rationale true — still routes to Request`() {
        // Edge case that should not happen in practice (rationale=true before any ask), but
        // the safe default is to fire the dialog. Pinned so a future "optimization" that drops
        // the !hasAttempted branch can't silently send the user to App Settings.
        assertThat(
            classifyRunCommandPermission(
                isGranted = false,
                hasAttempted = false,
                shouldShowRationale = true,
            )
        ).isEqualTo(RunCommandPermissionDisposition.Request)
    }

    /**
     * In-flight guard: rapid double-taps before the launcher callback fires must not stack
     * multiple system permission dialogs. Without [RunCommandPermissionGate.pending], a double
     * tap from the user (or the simultaneous Switch+Row tap paths in TermuxShellSettingsRow)
     * would call [ActivityResultLauncher.launch] twice — the system queues the second dialog
     * after the first, and the late granted callback re-runs setup unconditionally.
     */
    @Test
    fun `requestPermission ignores rapid double-tap while in flight`() {
        val activity = mockk<Activity>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<String>>(relaxed = true)
        val gate = RunCommandPermissionGate(activity)
        gate.attachLauncher(launcher)

        gate.requestPermission()
        gate.requestPermission()
        gate.requestPermission()

        verify(exactly = 1) { launcher.launch(TERMUX_RUN_COMMAND_PERMISSION) }
        assertThat(gate.pending).isTrue()
    }

    @Test
    fun `clearPending re-arms the gate for the next request`() {
        val activity = mockk<Activity>(relaxed = true)
        val launcher = mockk<ActivityResultLauncher<String>>(relaxed = true)
        val gate = RunCommandPermissionGate(activity)
        gate.attachLauncher(launcher)

        gate.requestPermission()
        gate.clearPending() // simulates the launcher callback firing
        gate.requestPermission()

        verify(exactly = 2) { launcher.launch(TERMUX_RUN_COMMAND_PERMISSION) }
    }

    @Test
    fun `requestPermission without an attached launcher does not strand the pending flag`() {
        // Constructor-time tap path: gate exists but SideEffect hasn't attached the launcher
        // yet. Must be a true no-op — if pending got set without the launcher actually firing,
        // the gate would deadlock (no callback → never clears → all future taps ignored).
        val activity = mockk<Activity>(relaxed = true)
        val gate = RunCommandPermissionGate(activity)

        gate.requestPermission()

        assertThat(gate.pending).isFalse()
    }
}
