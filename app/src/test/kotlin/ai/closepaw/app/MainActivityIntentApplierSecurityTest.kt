package ai.closepaw.app

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.FakeSharedPreferences
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.ui.settings.BrowserScriptToggleError
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Security regression: external intent extras must be ignored in production builds.
 */
class MainActivityIntentApplierSecurityTest {

    private val settingsState = AppSettingsState(mockk(relaxed = true))
    private val modelLoadingStatusHolder = ModelLoadingStatusHolder(settingsState)
    private val authStore = AuthStore(mockk(relaxed = true), prefsProvider = { FakeSharedPreferences() })

    @Test
    fun `production build ignores all sensitive intent extras`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = "injected-or-key",
            novitaApiKey = "injected-novita",
            openaiBaseUrl = "https://evil.example.com",
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = "evil-model",
            subagentModel = "evil-subagent",
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = "pwned",
            freshSession = false,
            debugMode = true,
            traceEnabled = true,
            traceRunId = "injected-run",
            excludedTools = setOf("open_app")
        )

        val result = applyIntentPayloadToSettings(
            payload = payload,
            settingsState = settingsState,
            modelLoadingStatusHolder = modelLoadingStatusHolder,
            authStore = authStore,
            isDebugBuild = false,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            currentPendingApprovalMode = null,
            log = {}
        )

        // Nothing should change — all extras ignored
        assertThat(authStore.has(LLMProvider.OPENAI_API)).isFalse()
        assertThat(authStore.has(LLMProvider.OPENROUTER)).isFalse()
        assertThat(authStore.has(LLMProvider.NOVITA)).isFalse()
        assertThat(result.pendingTraceEnabled).isNull()
        assertThat(result.pendingTraceRunId).isNull()
        assertThat(result.pendingExcludedTools).isEmpty()
        assertThat(result.pendingApprovalMode).isNull()
    }

    @Test
    fun `production build preserves existing pending state`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = null,
            novitaApiKey = null,
            openaiBaseUrl = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            subagentModel = null,
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = true,
            traceRunId = "new-run",
            excludedTools = setOf("shell")
        )

        val result = applyIntentPayloadToSettings(
            payload = payload,
            settingsState = settingsState,
            modelLoadingStatusHolder = modelLoadingStatusHolder,
            authStore = authStore,
            isDebugBuild = false,
            currentPendingTraceEnabled = true,
            currentPendingTraceRunId = "existing-run",
            currentPendingExcludedTools = setOf("open_app"),
            currentPendingApprovalMode = ApprovalMode.SMART,
            log = {}
        )

        // Existing state must be preserved, NOT replaced by injected values
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("existing-run")
        assertThat(result.pendingExcludedTools).containsExactly("open_app")
        assertThat(result.pendingApprovalMode).isEqualTo(ApprovalMode.SMART)
    }

    @Test
    fun `debug build applies intent extras normally`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "debug-key",
            openRouterApiKey = null,
            novitaApiKey = null,
            openaiBaseUrl = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            subagentModel = null,
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = true,
            traceRunId = "debug-run",
            excludedTools = emptySet()
        )

        val result = applyIntentPayloadToSettings(
            payload = payload,
            settingsState = settingsState,
            modelLoadingStatusHolder = modelLoadingStatusHolder,
            authStore = authStore,
            isDebugBuild = true,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            currentPendingApprovalMode = null,
            log = {},
            // Inject a passing gate — we're testing that the rest of the apply path runs in
            // debug mode, not the gate itself (separate test).
            browserScriptGate = { null },
        )

        val cred = runBlocking { authStore.get(LLMProvider.OPENAI_API) }
        assertThat(cred).isEqualTo(AuthCredential.ApiKey("debug-key"))
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("debug-run")
        assertThat(result.pendingApprovalMode).isEqualTo(ApprovalMode.AUTO_APPROVE)
        assertThat(settingsState.browserScriptEnabled).isTrue()
    }

    // ── browser_script gating (debug-only path) ─────────────────────────────────────────

    @Test
    fun `debug build does NOT persist browser_script ON when gate denies`() = runBlocking<Unit> {
        val payload = browserScriptPayload(enabled = true)

        applyIntentPayloadToSettings(
            payload = payload,
            settingsState = settingsState,
            modelLoadingStatusHolder = modelLoadingStatusHolder,
            authStore = authStore,
            isDebugBuild = true,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            currentPendingApprovalMode = null,
            log = {},
            browserScriptGate = { BrowserScriptToggleError.ShizukuUnavailable },
        )

        // Same contract as the UI toggle: never persist ON for an unusable tool.
        assertThat(settingsState.browserScriptEnabled).isFalse()
    }

    @Test
    fun `debug build persists browser_script ON when gate succeeds`() = runBlocking<Unit> {
        val payload = browserScriptPayload(enabled = true)
        var gateInvoked = 0

        applyIntentPayloadToSettings(
            payload = payload,
            settingsState = settingsState,
            modelLoadingStatusHolder = modelLoadingStatusHolder,
            authStore = authStore,
            isDebugBuild = true,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            currentPendingApprovalMode = null,
            log = {},
            browserScriptGate = { gateInvoked++; null },
        )

        assertThat(gateInvoked).isEqualTo(1)
        assertThat(settingsState.browserScriptEnabled).isTrue()
    }

    @Test
    fun `debug build persists browser_script OFF unconditionally — gate is bypassed`() =
        runBlocking<Unit> {
            // Pre-load to ON so we can observe the OFF transition.
            settingsState.updateBrowserScriptEnabled(true)
            val payload = browserScriptPayload(enabled = false)
            var gateInvoked = 0

            applyIntentPayloadToSettings(
                payload = payload,
                settingsState = settingsState,
                modelLoadingStatusHolder = modelLoadingStatusHolder,
                authStore = authStore,
                isDebugBuild = true,
                currentPendingTraceEnabled = null,
                currentPendingTraceRunId = null,
                currentPendingExcludedTools = emptySet(),
                currentPendingApprovalMode = null,
                log = {},
                browserScriptGate = {
                    gateInvoked++
                    error("gate must not run for OFF — toggle off is unconditional")
                },
            )

            assertThat(gateInvoked).isEqualTo(0)
            assertThat(settingsState.browserScriptEnabled).isFalse()
        }

    private fun browserScriptPayload(enabled: Boolean): MainActivityIntentPayload =
        MainActivityIntentPayload(
            apiKey = null,
            openRouterApiKey = null,
            novitaApiKey = null,
            openaiBaseUrl = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            subagentModel = null,
            approvalMode = null,
            browserScriptEnabled = enabled,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = null,
            traceRunId = null,
            excludedTools = emptySet(),
        )
}
