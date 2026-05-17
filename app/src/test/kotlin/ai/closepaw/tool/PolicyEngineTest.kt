package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ApprovalMode
import kotlinx.coroutines.runBlocking
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
    fun `system_button back allowed on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val backParams = JSONObject().put("button", "back")
        val decision = engine.check("system_button", backParams, "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `system_button home allowed on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val homeParams = JSONObject().put("button", "home")
        val decision = engine.check("system_button", homeParams, "com.bank")
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `mobile_action back denied on blocked app - only system_button is escape`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val backParams = JSONObject().put("action", "back")
        val decision = engine.check("mobile_action", backParams, "com.bank")
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    @Test
    fun `system_button enter not treated as escape on blocked app`() {
        val engine = engineWith(
            tiers = mapOf("com.bank" to AppTier.BLOCKED)
        )
        val enterParams = JSONObject().put("button", "enter")
        val decision = engine.check("system_button", enterParams, "com.bank")
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
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

    @Test
    fun `browser_script asks in smart mode for chrome even though chrome is normal`() {
        val engine = engineWith(
            tiers = mapOf("com.android.chrome" to AppTier.NORMAL)
        )

        val decision = engine.check("browser_script", JSONObject(), "com.android.chrome")

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
        val ask = decision as PolicyDecision.AskUser
        assertThat(ask.appTier).isEqualTo(AppTier.NORMAL)
        assertThat(ask.reason).contains("Browser automation")
    }

    @Test
    fun `browser_script smart rule is not bypassed by user allow-list`() {
        val engine = engineWith(
            tiers = mapOf("com.android.chrome" to AppTier.NORMAL)
        )
        engine.allowPackageForSession("com.android.chrome")

        val decision = engine.check("browser_script", JSONObject(), "com.android.chrome")

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `session allow-list allows cautious app in smart mode`() {
        val engine = engineWith()
        engine.allowPackageForSession("com.unknown.app")

        val decision = engine.check("mobile_action", clickParams(), "com.unknown.app")

        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `browser_script allowed in auto approve mode after runtime gates`() {
        val engine = engineWith(
            mode = ApprovalMode.AUTO_APPROVE,
            tiers = mapOf("com.android.chrome" to AppTier.NORMAL)
        )

        val decision = engine.check("browser_script", JSONObject(), "com.android.chrome")

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

    // --- Destination tier ---

    @Test
    fun `normal current with cautious destination asks user in smart mode`() {
        val engine = engineWith(
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        val decision = engine.check(
            "open_app", clickParams(), "com.android.settings",
            destinationPackage = "com.unknown.app"
        )
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `destination allow-list allows open_app to cautious destination in smart mode`() {
        val engine = engineWith(
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        engine.allowPackageForSession("com.unknown.app")

        val decision = engine.check(
            "open_app", clickParams(), "com.android.settings",
            destinationPackage = "com.unknown.app"
        )

        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `null destination does not change behavior`() {
        val engine = engineWith(
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        val decision = engine.check(
            "open_app", clickParams(), "com.android.settings",
            destinationPackage = null
        )
        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `blocked destination denied in smart mode`() {
        val engine = engineWith(
            tiers = mapOf(
                "com.android.settings" to AppTier.NORMAL,
                "com.bank" to AppTier.BLOCKED
            )
        )
        val decision = engine.check(
            "open_app", clickParams(), "com.android.settings",
            destinationPackage = "com.bank"
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    // --- User override interactions (canonical step ordering) ---

    @Test
    fun `user NORMAL override on cautious app under ALWAYS_ASK still asks - mode wins`() = runBlocking {
        // Use a package not in bundled tiers (defaults to CAUTIOUS) so the NORMAL override is
        // actually Accepted rather than auto-removed by the "matches bundled default" rule.
        val classifier = AppClassifier(emptyMap())
        val result = classifier.setOverride("com.unknown.app", AppTier.NORMAL)
        assertThat(result).isEqualTo(SetOverrideResult.Accepted)
        assertThat(classifier.userOverrides.value).containsKey("com.unknown.app")
        val engine = PolicyEngine(ApprovalMode.ALWAYS_ASK, classifier)

        val decision = engine.check("open_app", JSONObject(), "com.unknown.app")
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `browser_script under SMART asks even with NORMAL override on cautious app`() = runBlocking {
        // Bundled-CAUTIOUS (absent from map) + user NORMAL override → override is Accepted, but
        // the browser_script rule must still fire (NORMAL override does NOT bypass step 4).
        val classifier = AppClassifier(emptyMap())
        val result = classifier.setOverride("com.android.chrome", AppTier.NORMAL)
        assertThat(result).isEqualTo(SetOverrideResult.Accepted)
        assertThat(classifier.userOverrides.value).containsKey("com.android.chrome")
        val engine = PolicyEngine(ApprovalMode.SMART, classifier)

        val decision = engine.check("browser_script", JSONObject(), "com.android.chrome")

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `session-allowed package allowed under SMART`() {
        val engine = engineWith()
        engine.allowPackageForSession("com.unknown.app")

        val decision = engine.check("mobile_action", clickParams(), "com.unknown.app")

        assertThat(decision).isEqualTo(PolicyDecision.Allow)
    }

    @Test
    fun `session-allowed package still asks under ALWAYS_ASK`() {
        val engine = engineWith(mode = ApprovalMode.ALWAYS_ASK)
        engine.allowPackageForSession("com.unknown.app")

        val decision = engine.check("mobile_action", clickParams(), "com.unknown.app")

        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `open_app to bundled BLOCKED destination from NORMAL current is denied under SMART`() {
        val engine = engineWith(
            tiers = mapOf(
                "com.android.settings" to AppTier.NORMAL,
                "com.bank" to AppTier.BLOCKED
            )
        )
        val decision = engine.check(
            "open_app", JSONObject(), "com.android.settings",
            destinationPackage = "com.bank"
        )
        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    @Test
    fun `open_app to CAUTIOUS destination under SMART asks user`() {
        val engine = engineWith(
            tiers = mapOf("com.android.settings" to AppTier.NORMAL)
        )
        val decision = engine.check(
            "open_app", JSONObject(), "com.android.settings",
            destinationPackage = "com.unknown"
        )
        assertThat(decision).isInstanceOf(PolicyDecision.AskUser::class.java)
    }

    @Test
    fun `user NORMAL override on bundled BLOCKED is refused and SMART still denies`() = runBlocking {
        // Bundled-BLOCKED is an absolute floor: setOverride refuses the write, classify()
        // pins to BLOCKED, and SMART-mode actions on the package are denied.
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        val result = classifier.setOverride("com.bank", AppTier.NORMAL)
        assertThat(result).isEqualTo(SetOverrideResult.RefusedBlocked)
        assertThat(classifier.classify("com.bank")).isEqualTo(AppTier.BLOCKED)
        val engine = PolicyEngine(ApprovalMode.SMART, classifier)

        val decision = engine.check("mobile_action", clickParams(), "com.bank")

        assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
    }

    private fun clickParams() = JSONObject().put("action", "click").put("index", 5)
}
