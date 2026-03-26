package com.moonkey.androidagent.tool

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.AppTier
import com.moonkey.androidagent.protocol.ApprovalMode
import org.json.JSONObject
import org.junit.Test

class PolicyEngineTest {

    private fun engineWith(
        mode: ApprovalMode = ApprovalMode.SMART,
        tiers: Map<String, AppTier> = emptyMap()
    ) = PolicyEngine(mode, AppClassifier(tiers))

    // --- BLOCKED tier ---

    @Test
    fun `blocked app denied even in auto_approve mode`() {
        val engine = engineWith(
            mode = ApprovalMode.AUTO_APPROVE,
            tiers = mapOf("com.chase.sig.android" to AppTier.BLOCKED)
        )
        val decision = engine.check("mobile_action", clickParams(), "com.chase.sig.android")
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    @Test
    fun `blocked app denied in smart mode`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val decision = engine.check("mobile_action", clickParams(), "com.bank")
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    @Test
    fun `escape actions allowed even on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val backParams = JSONObject().put("action", "back")
        val decision = engine.check("mobile_action", backParams, "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `home action allowed on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val homeParams = JSONObject().put("action", "home")
        val decision = engine.check("mobile_action", homeParams, "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    // --- CAUTIOUS tier (unknown apps) ---

    @Test
    fun `unknown app asks user in smart mode`() {
        val engine = engineWith()
        val decision = engine.check("mobile_action", clickParams(), "com.unknown.app")
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `unknown app allowed in auto_approve mode`() {
        val engine = engineWith(mode = ApprovalMode.AUTO_APPROVE)
        val decision = engine.check("mobile_action", clickParams(), "com.unknown.app")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    // --- NORMAL tier ---

    @Test
    fun `normal app allowed in smart mode`() {
        val engine = engineWith(
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        val decision = engine.check("mobile_action", clickParams(), "com.android.settings")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    // --- Non-screen-changing tools ---

    @Test
    fun `non-screen-changing tool allowed regardless of tier`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val decision = engine.check("scratchpad", JSONObject(), "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `write_todos allowed on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val decision = engine.check("write_todos", JSONObject(), "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    // --- ALWAYS_ASK mode ---

    @Test
    fun `always_ask mode requests approval for normal app`() {
        val engine = engineWith(
            mode = ApprovalMode.ALWAYS_ASK,
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        val decision = engine.check("open_app", JSONObject(), "com.android.settings")
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    // --- Null package ---

    @Test
    fun `null package treated as cautious`() {
        val engine = engineWith()
        val decision = engine.check("mobile_action", clickParams(), null)
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    private fun clickParams() = JSONObject().put("action", "click").put("index", 5)
}
