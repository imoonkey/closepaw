package ai.closepaw.app

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.FakeSharedPreferences
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.ui.settings.BrowserScriptToggleError
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Security regression: external intent extras must be ignored in production builds.
 */
class MainActivityIntentApplierSecurityTest {

    private val settingsState = AppSettingsState(mockk(relaxed = true))
    private val modelLoadingStatusHolder =
        ModelLoadingStatusHolder(mockk(relaxed = true), CoroutineScope(Dispatchers.Unconfined), settingsState)
    private val authStore = AuthStore(mockk(relaxed = true), prefsProvider = { FakeSharedPreferences() })

    @Test
    fun `production build ignores all sensitive intent extras`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = "injected-or-key",
            openaiBaseUrl = "https://evil.example.com",
            otherApiKey = "injected-other-key",
            otherBaseUrl = "https://evil.example.com/v1",
            otherModelId = "injected/model",
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = "evil-model",
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = "pwned",
            freshSession = false,
            debugMode = true,
            traceEnabled = true,
            traceRunId = "injected-run",
            excludedTools = setOf("open_app"),
            evalTurnBudget = 5
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
            currentPendingEvalTurnBudget = null,
            log = {}
        )

        // Nothing should change — all extras ignored
        assertThat(authStore.has(LLMProvider.OPENAI_API)).isFalse()
        assertThat(authStore.has(LLMProvider.OPENROUTER)).isFalse()
        assertThat(authStore.has(LLMProvider.OTHER)).isFalse()
        assertThat(result.pendingTraceEnabled).isNull()
        assertThat(result.pendingTraceRunId).isNull()
        assertThat(result.pendingExcludedTools).isEmpty()
        assertThat(result.pendingApprovalMode).isNull()
        assertThat(result.pendingEvalTurnBudget).isNull()
    }

    @Test
    fun `production build preserves existing pending state`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = null,
            openaiBaseUrl = null,
            otherApiKey = null,
            otherBaseUrl = null,
            otherModelId = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = true,
            traceRunId = "new-run",
            excludedTools = setOf("shell"),
            evalTurnBudget = 7
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
            currentPendingEvalTurnBudget = 11,
            log = {}
        )

        // Existing state must be preserved, NOT replaced by injected values
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("existing-run")
        assertThat(result.pendingExcludedTools).containsExactly("open_app")
        assertThat(result.pendingApprovalMode).isEqualTo(ApprovalMode.SMART)
        assertThat(result.pendingEvalTurnBudget).isEqualTo(11)
    }

    @Test
    fun `debug build applies intent extras normally`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "debug-key",
            openRouterApiKey = null,
            openaiBaseUrl = null,
            otherApiKey = null,
            otherBaseUrl = null,
            otherModelId = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            approvalMode = ApprovalMode.AUTO_APPROVE,
            browserScriptEnabled = true,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = true,
            traceRunId = "debug-run",
            excludedTools = emptySet(),
            evalTurnBudget = 13
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
            currentPendingEvalTurnBudget = null,
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
        assertThat(result.pendingEvalTurnBudget).isEqualTo(13)
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
            currentPendingEvalTurnBudget = null,
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
            currentPendingEvalTurnBudget = null,
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
                currentPendingEvalTurnBudget = null,
                log = {},
                browserScriptGate = {
                    gateInvoked++
                    error("gate must not run for OFF — toggle off is unconditional")
                },
            )

            assertThat(gateInvoked).isEqualTo(0)
            assertThat(settingsState.browserScriptEnabled).isFalse()
        }

    // ── OTHER provider trio ────────────────────────────────────────────────────────────

    @Test
    fun `debug build round-trips OTHER trio and invokes invalidateCatalog`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = null,
            openRouterApiKey = null,
            openaiBaseUrl = null,
            otherApiKey = "other-key",
            otherBaseUrl = "https://api.example.com/v1",
            otherModelId = "vendor/model",
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            approvalMode = null,
            browserScriptEnabled = null,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = null,
            traceRunId = null,
            excludedTools = emptySet(),
            evalTurnBudget = null,
        )
        var invalidateCount = 0

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
            currentPendingEvalTurnBudget = null,
            log = {},
            invalidateCatalog = { invalidateCount++ },
        )

        val cred = runBlocking { authStore.get(LLMProvider.OTHER) }
        assertThat(cred).isEqualTo(AuthCredential.ApiKey("other-key"))
        assertThat(settingsState.otherBaseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(settingsState.otherModelId).isEqualTo("vendor/model")
        // invalidate is called once at the end of the apply path — that's enough for
        // catalog.value to reflect the new trio on the next read; nothing in the contract
        // requires once-per-field. (Settings updates also fire onOtherSettingsChanged,
        // which would invalidate the production repo via the AppSettingsState factory; the
        // test passes a bare AppSettingsState so only the applier's invalidate hook fires.)
        assertThat(invalidateCount).isEqualTo(1)
    }

    @Test
    fun `debug build skips invalidateCatalog when no OTHER fields present`() = runBlocking<Unit> {
        val payload = browserScriptPayload(enabled = false)
        var invalidateCount = 0

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
            currentPendingEvalTurnBudget = null,
            log = {},
            invalidateCatalog = { invalidateCount++ },
        )

        assertThat(invalidateCount).isEqualTo(0)
    }

    private fun browserScriptPayload(enabled: Boolean): MainActivityIntentPayload =
        MainActivityIntentPayload(
            apiKey = null,
            openRouterApiKey = null,
            openaiBaseUrl = null,
            otherApiKey = null,
            otherBaseUrl = null,
            otherModelId = null,
            backendType = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            approvalMode = null,
            browserScriptEnabled = enabled,
            goalText = null,
            freshSession = false,
            debugMode = null,
            traceEnabled = null,
            traceRunId = null,
            excludedTools = emptySet(),
            evalTurnBudget = null,
        )
}
