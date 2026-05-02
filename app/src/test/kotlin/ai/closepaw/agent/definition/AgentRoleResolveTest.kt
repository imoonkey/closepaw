package ai.closepaw.agent.definition

import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.ToolName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentRoleResolveTest {

    private val roles = listOf(StandaloneRoleDef, PlannerRoleDef, ExecutorRoleDef)

    @Test
    fun `available snapshot exposes termux shell and workspace prompt for each role`() {
        roles.forEach { role ->
            val resolved = role.resolve(availableSnapshot)

            assertThat(resolved.allowedTools).contains(ToolName.TermuxShell)
            assertThat(resolved.systemPrompt).contains("Workspace Shell")
        }
    }

    @Test
    fun `unavailable snapshot hides termux shell and workspace prompt for each role`() {
        roles.forEach { role ->
            val resolved = role.resolve(unavailableSnapshot)

            assertThat(resolved.allowedTools).doesNotContain(ToolName.TermuxShell)
            assertThat(resolved.systemPrompt).doesNotContain("Workspace Shell")
        }
    }

    @Test
    fun `excluded termux shell hides tool and workspace prompt for each role`() {
        roles.forEach { role ->
            val resolved = role.resolve(
                snapshot = availableSnapshot,
                excludedTools = setOf(ToolName.TermuxShell)
            )

            assertThat(resolved.allowedTools).doesNotContain(ToolName.TermuxShell)
            assertThat(resolved.systemPrompt).doesNotContain("Workspace Shell")
        }
    }

    @Test
    fun `planner workspace prompt directs planner to run termux shell directly`() {
        val resolved = PlannerRoleDef.resolve(availableSnapshot)

        assertThat(resolved.systemPrompt).contains(PLANNER_WORKSPACE_SHELL_DIRECTIVE)
    }

    @Test
    fun planner_without_termux_does_not_contain_directive() {
        val resolved = PlannerRoleDef.resolve(unavailableSnapshot)

        assertThat(resolved.systemPrompt).doesNotContain(PLANNER_WORKSPACE_SHELL_DIRECTIVE)
    }

    @Test
    fun standalone_with_termux_does_not_contain_directive() {
        val resolved = StandaloneRoleDef.resolve(availableSnapshot)

        assertThat(resolved.systemPrompt).doesNotContain(PLANNER_WORKSPACE_SHELL_DIRECTIVE)
    }

    @Test
    fun executor_with_termux_does_not_contain_directive() {
        val resolved = ExecutorRoleDef.resolve(availableSnapshot)

        assertThat(resolved.systemPrompt).doesNotContain(PLANNER_WORKSPACE_SHELL_DIRECTIVE)
    }

    @Test
    fun `executor timeout increases when termux shell is exposed`() {
        val resolved = ExecutorRoleDef.resolve(availableSnapshot)

        assertThat(resolved.timeoutMs).isEqualTo(150_000)
    }

    @Test
    fun `executor timeout remains base value when termux shell is hidden`() {
        val resolved = ExecutorRoleDef.resolve(unavailableSnapshot)

        assertThat(resolved.timeoutMs).isEqualTo(ExecutorRoleDef.timeoutMs)
    }

    @Test
    fun executor_with_termux_excluded_keeps_base_timeout() {
        val resolved = ExecutorRoleDef.resolve(
            snapshot = availableSnapshot,
            excludedTools = setOf(ToolName.TermuxShell)
        )

        assertThat(resolved.timeoutMs).isEqualTo(ExecutorRoleDef.timeoutMs)
    }

    private companion object {
        const val PLANNER_WORKSPACE_SHELL_DIRECTIVE =
            "For workspace commands (termux_shell), execute directly instead of delegating."
        val availableSnapshot = TermuxCapabilitySnapshot(
            available = true,
            enabled = true,
            status = TermuxBridgeStatus.Ready
        )
        val unavailableSnapshot = TermuxCapabilitySnapshot(
            available = false,
            enabled = true,
            status = TermuxBridgeStatus.Disabled
        )
    }
}
