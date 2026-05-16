package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.ResponseItem
import org.json.JSONObject
import org.junit.Test

class TurnBudgetAndDelegationSummaryTest {

    @Test
    fun `isFinalTurn returns false before limit`() {
        assertThat(isFinalTurn(turnNumber = 2, maxTurns = 5)).isFalse()
        assertThat(isFinalTurn(turnNumber = 4, maxTurns = 5)).isFalse()
    }

    @Test
    fun `isFinalTurn returns true at and beyond limit`() {
        assertThat(isFinalTurn(turnNumber = 5, maxTurns = 5)).isTrue()
        assertThat(isFinalTurn(turnNumber = 6, maxTurns = 5)).isTrue()
    }

    @Test
    fun `DelegationSummaryFormatter includes query and actions`() {
        val history =
            listOf(
                ResponseItem.FunctionCall(
                    id = "call-1",
                    name = "mobile_action",
                    arguments = JSONObject("""{"action":"click"}""")
                ),
                ResponseItem.FunctionCallOutput(
                    callId = "call-1",
                    content = "Success: tapped",
                    success = true
                )
            )

        val summary = DelegationSummaryFormatter.format(
            maxTurns = 5,
            delegatedQuery = "Tap search",
            history = history
        )

        assertThat(summary).contains("Delegated query: Tap search")
        assertThat(summary).contains("mobile_action (click)")
        assertThat(summary).contains("Agent reached turn limit (5)")
    }

    @Test
    fun `DelegationSummaryFormatter handles empty history`() {
        val summary = DelegationSummaryFormatter.format(
            maxTurns = 3,
            delegatedQuery = "Open settings",
            history = emptyList()
        )

        assertThat(summary).contains("No tool calls were executed")
        assertThat(summary).contains("No post-action observations captured")
    }
}
