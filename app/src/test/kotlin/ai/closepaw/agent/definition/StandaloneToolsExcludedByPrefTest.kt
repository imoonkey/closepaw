package ai.closepaw.agent.definition

import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.ToolName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Verifies the LLM allowlist gate for the user pref controlling `browser_script`.
 *
 * This is the same resolution path SessionAgentRunner uses: it calls
 * [AgentRoleDef.resolve] with `excludedTools` derived from [standaloneToolsExcludedByPref].
 * If the pref is OFF and resolve still surfaces browser_script, the LLM sees the tool — which
 * was the regression Codex caught.
 */
class StandaloneToolsExcludedByPrefTest {

    private val termuxUnavailable = TermuxCapabilitySnapshot(
        available = false,
        enabled = false,
        status = TermuxBridgeStatus.Disabled,
    )

    @Test
    fun `pref off excludes browser_script from helper set`() {
        assertThat(standaloneToolsExcludedByPref(browserScriptEnabled = false))
            .containsExactly(ToolName.BrowserScript.raw)
    }

    @Test
    fun `pref on returns empty exclusion set`() {
        assertThat(standaloneToolsExcludedByPref(browserScriptEnabled = true)).isEmpty()
    }

    @Test
    fun `pref off causes resolved Standalone allowlist to drop browser_script`() {
        val excluded = standaloneToolsExcludedByPref(browserScriptEnabled = false)
            .map { ToolName.from(it) }.toSet()

        val resolved = StandaloneRoleDef.resolve(
            snapshot = termuxUnavailable,
            excludedTools = excluded,
        )

        assertThat(resolved.allowedToolNames).doesNotContain(ToolName.BrowserScript.raw)
        // Other tools must remain — exclusion must not over-prune.
        assertThat(resolved.allowedToolNames).contains("mobile_action")
        assertThat(resolved.allowedToolNames).contains("shell")
    }

    @Test
    fun `pref on causes resolved Standalone allowlist to include browser_script`() {
        val excluded = standaloneToolsExcludedByPref(browserScriptEnabled = true)
            .map { ToolName.from(it) }.toSet()

        val resolved = StandaloneRoleDef.resolve(
            snapshot = termuxUnavailable,
            excludedTools = excluded,
        )

        assertThat(resolved.allowedToolNames).contains(ToolName.BrowserScript.raw)
    }
}
