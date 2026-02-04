package com.moonkey.androidagent.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.ResponseItem
import org.json.JSONObject
import org.junit.Test

class ExecutorStepPolicyTest {

    @Test
    fun `evaluate warns starting two steps before configured limit`() {
        val policy = ExecutorStepPolicy(maxSteps = 5, narrativeSummaryOnLimit = true)

        val continueDecision = policy.evaluate(stepCount = 2, delegatedQuery = "query", history = emptyList())
        val warningDecision = policy.evaluate(stepCount = 3, delegatedQuery = "query", history = emptyList())

        assertThat(continueDecision).isEqualTo(ExecutorStepDecision.Continue)
        assertThat(warningDecision).isEqualTo(ExecutorStepDecision.WarnApproaching)
    }

    @Test
    fun `evaluate returns narrative summary at step limit`() {
        val policy = ExecutorStepPolicy(maxSteps = 5, narrativeSummaryOnLimit = true)
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

        val decision = policy.evaluate(stepCount = 5, delegatedQuery = "Tap search", history = history)

        assertThat(decision is ExecutorStepDecision.ForceStop).isTrue()
        val forceStop = decision as ExecutorStepDecision.ForceStop
        assertThat(forceStop.narrativeSummary).contains("Delegated query: Tap search")
        assertThat(forceStop.narrativeSummary).contains("mobile_action (click)")
    }

    @Test
    fun `evaluate does not force stop when narratives are disabled`() {
        val policy = ExecutorStepPolicy(maxSteps = 5, narrativeSummaryOnLimit = false)

        val decision = policy.evaluate(stepCount = 5, delegatedQuery = "query", history = emptyList())

        assertThat(decision).isEqualTo(ExecutorStepDecision.WarnApproaching)
    }
}
