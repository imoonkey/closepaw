package ai.closepaw.agent

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.ActionOutcome
import ai.closepaw.tool.ToolCallResult
import org.junit.Test

class ActionOutcomeMappingTest {

    @Test
    fun `Success maps to SUCCESS`() {
        val r: ToolCallResult = ToolCallResult.Success(callId = "c1", output = "ok")
        assertThat(r.toActionOutcome()).isEqualTo(ActionOutcome.SUCCESS)
    }

    @Test
    fun `Error maps to FAILED`() {
        val r: ToolCallResult = ToolCallResult.Error(callId = "c2", error = "boom")
        assertThat(r.toActionOutcome()).isEqualTo(ActionOutcome.FAILED)
    }

    @Test
    fun `Cancelled maps to SKIPPED`() {
        val r: ToolCallResult = ToolCallResult.Cancelled(callId = "c3", reason = "denied")
        assertThat(r.toActionOutcome()).isEqualTo(ActionOutcome.SKIPPED)
    }

    @Test
    fun `only SUCCESS produces checkmark status`() {
        fun statusFor(outcome: ActionOutcome, tool: String) = when (outcome) {
            ActionOutcome.SUCCESS -> "✓ $tool executed"
            ActionOutcome.FAILED -> "✗ $tool failed"
            ActionOutcome.SKIPPED -> "⊘ $tool skipped"
        }
        assertThat(statusFor(ActionOutcome.SUCCESS, "tap")).startsWith("✓")
        assertThat(statusFor(ActionOutcome.FAILED, "tap")).doesNotContain("✓")
        assertThat(statusFor(ActionOutcome.SKIPPED, "tap")).doesNotContain("✓")
    }
}
