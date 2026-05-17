package ai.closepaw.ui.settings

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the Termux mapping table in `projects/active/tool_settings_unify/design_claude.md`.
 *
 * The label words are load-bearing — `SettingsTermuxRowTest` (instrumentation) matches on
 * them, and so does the design doc. Tone / spinner / subtitle assertions guard against
 * silent UX regressions when someone edits the mapper.
 */
class TermuxStatusMapperTest {

    @Test
    fun `Disabled state maps to Disabled label, Neutral tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Disabled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo("Disabled")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo("Toggle to enable")
    }

    @Test
    fun `enabledPref false forces Disabled regardless of underlying state`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = false,
        )
        assertThat(ui.label).isEqualTo("Disabled")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo("Toggle to enable")
    }

    @Test
    fun `NotInstalled maps to Not Installed label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NotInstalled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo("Not Installed")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo("Install Termux from F-Droid")
    }

    @Test
    fun `NeedsSetup maps to Needs Setup label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo("Needs Setup")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
    }

    @Test
    fun `SetupInProgress maps to Setting up label, Neutral tone, spinner shown`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.SetupInProgress,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo("Setting up…")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isTrue()
        assertThat(ui.subtitle).isEqualTo("This may take a minute")
    }

    @Test
    fun `Ready maps to Ready label, Positive tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo("Ready")
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo("Termux bridge running — tap to restart")
    }

    // Below: NeedsSetupReason subtitle matrix. The design table only requires the umbrella
    // label "Needs Setup" / Warning tone for every NeedsSetup variant, but the subtitle is
    // what tells the user what to actually do. Pin each reason so a future copy edit shows
    // up in CI rather than only in the user's hands.
    //
    // Design doc is the source of truth for label wording — these tests pin current subtitle
    // wording of the mapper.

    @Test
    fun `NeedsSetup PERMISSION_MISSING with null disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Tap to grant RUN_COMMAND permission to Termux.")
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Request disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Request,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Tap to grant RUN_COMMAND permission to Termux.")
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Granted disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Granted,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Tap to grant RUN_COMMAND permission to Termux.")
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with OpenAppSettings disposition redirects to App Settings`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.OpenAppSettings,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            "Permission permanently denied. Tap to open App Settings → Permissions."
        )
    }

    @Test
    fun `NeedsSetup ALLOW_EXTERNAL_APPS_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            "Allow external apps is disabled in Termux. Enable it, then tap setup."
        )
    }

    @Test
    fun `NeedsSetup TERMUX_NOT_RUNNING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            "Termux is not running. Tap to open Termux, then return here."
        )
    }

    @Test
    fun `NeedsSetup TERMUX_RUN_COMMAND_UNAVAILABLE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            "This Termux build cannot accept external commands. Install Termux from F-Droid " +
                "(the Google Play build is incompatible)."
        )
    }

    @Test
    fun `NeedsSetup PACKAGES_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Missing packages — tap to install python/git/ripgrep")
    }

    @Test
    fun `NeedsSetup BRIDGE_OUTDATED subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.BRIDGE_OUTDATED),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Bridge daemon out of date — tap to update")
    }

    @Test
    fun `NeedsSetup HEALTH_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Bridge unreachable — tap to retry setup")
    }

    @Test
    fun `NeedsSetup TERMUX_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Termux command timed out — open Termux once and retry")
    }

    @Test
    fun `NeedsSetup PORT_IN_USE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PORT_IN_USE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Port 18422 in use by another process")
    }

    @Test
    fun `NeedsSetup UNKNOWN subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.UNKNOWN),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo("Setup error — tap to retry")
    }
}
