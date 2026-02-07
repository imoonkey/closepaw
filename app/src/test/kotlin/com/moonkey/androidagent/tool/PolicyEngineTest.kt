package com.moonkey.androidagent.tool

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.protocol.RiskLevel
import org.json.JSONObject
import org.junit.Test

class PolicyEngineTest {

    @Test
    fun `deny list takes precedence over allow list`() {
        val engine = PolicyEngine(ApprovalMode.AUTO_APPROVE)

        engine.allowTool("mobile_action")
        engine.denyTool("mobile_action")

        val decision = engine.check("mobile_action", JSONObject())
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    @Test
    fun `smart mode asks for high risk actions`() {
        val engine = PolicyEngine(ApprovalMode.SMART)
        engine.setRiskLevel("mobile_action", RiskLevel.HIGH)

        val decision = engine.check("mobile_action", JSONObject())

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
        val askUser = decision as PolicyDecision.AskUser
        assertThat(askUser.riskLevel).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun `always ask mode requests approval`() {
        val engine = PolicyEngine(ApprovalMode.ALWAYS_ASK)

        val decision = engine.check("open_app", JSONObject())

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `auto approve mode allows tools`() {
        val engine = PolicyEngine(ApprovalMode.AUTO_APPROVE)

        val decision = engine.check("open_app", JSONObject())

        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }
}
