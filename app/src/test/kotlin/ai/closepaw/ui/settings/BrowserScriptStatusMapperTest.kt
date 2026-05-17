package ai.closepaw.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the browser_script mapping table in
 * `projects/active/tool_settings_unify/design_claude.md`. The mapper resolves four
 * observable inputs (enable pref, gate pending, gate error, CDP probe) into a single
 * [ToolStatusUi] + optional [RowAction]; mis-ordering the precedence (e.g. showing the
 * probe result while a gate error is live) is exactly the regression these tests guard.
 *
 * Design doc is the source of truth for label wording.
 */
class BrowserScriptStatusMapperTest {

    // Row 1: Off, no error → "Disabled"
    @Test
    fun `pref off with no error and no pending maps to Disabled`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo("Disabled")
        assertThat(result.status.subtitle).isEqualTo("Toggle to enable")
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 2: Pending (gate running) → "Setting up…"
    @Test
    fun `gate pending wins over pref off and probe state — Setting up with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = true,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo("Setting up…")
        assertThat(result.status.subtitle).isEqualTo("Checking Shizuku…")
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 3: Gate error (any variant) → "Needs Setup" + ClearErrorAndRetry row action
    @Test
    fun `gate error ShizukuUnavailable maps to Needs Setup with verbatim message subtitle`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo("Needs Setup")
        assertThat(result.status.subtitle).isEqualTo(
            "Shizuku is not running. Start Shizuku first, then enable browser_script."
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error ShizukuPermissionDenied maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuPermissionDenied,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo("Needs Setup")
        assertThat(result.status.subtitle).isEqualTo(
            "Permission denied. Tap to retry, or re-grant in Shizuku Manager."
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error WriteFailed maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo("Needs Setup")
        assertThat(result.status.subtitle).isEqualTo(
            "Could not write Chrome's command-line file. Check Shizuku and try again."
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    // Row 4: On, probe Probing → "Checking…"
    @Test
    fun `pref on with probe Probing maps to Checking with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Probing,
        )
        assertThat(result.status.label).isEqualTo("Checking…")
        assertThat(result.status.subtitle).isEqualTo("Checking Chrome devtools socket…")
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 5: On, probe Bound → "Ready"
    @Test
    fun `pref on with probe Bound maps to Ready Positive`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo("Ready")
        assertThat(result.status.subtitle).isEqualTo("browser_script active")
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 6: On, probe NotBound → "Needs Setup" (expanded slot owns the CTA, NOT row tap)
    @Test
    fun `pref on with probe NotBound maps to Needs Setup with no row action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.NotBound,
        )
        assertThat(result.status.label).isEqualTo("Needs Setup")
        assertThat(result.status.subtitle).isEqualTo(
            "Chrome devtools socket not exposed. Enable the flag and restart Chrome."
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        // NotBound's CTA lives in the expanded slot, not on row tap — see design doc decision 3.
        assertThat(result.rowAction).isNull()
    }

    // Row 7: On, probe Unknown → "Not available"
    @Test
    fun `pref on with probe Unknown maps to Not available Warning`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo("Not available")
        assertThat(result.status.subtitle).isEqualTo(
            "Cannot probe socket on this device — the agent will still try to connect when needed."
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Precedence — the mapper's branch order is `pending > error > !enabledPref > probe`.
    // The design doc's table doesn't enumerate combinations; pinning current behavior so a
    // re-order shows up here rather than only in production.

    @Test
    fun `gate pending wins over gate error`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = true,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo("Setting up…")
        assertThat(result.rowAction).isNull()
    }

    @Test
    fun `gate error wins over pref off`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo("Needs Setup")
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }
}
