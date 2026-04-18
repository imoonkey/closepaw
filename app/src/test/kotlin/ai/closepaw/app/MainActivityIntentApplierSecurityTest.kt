package ai.closepaw.app

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Security regression: external intent extras must be ignored in production builds.
 */
class MainActivityIntentApplierSecurityTest {

    private val settingsState = AppSettingsState(mockk(relaxed = true))
    private val authStore = AuthStore(mockk(relaxed = true))

    @Test
    fun `production build ignores all sensitive intent extras`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = "injected-or-key",
            novitaApiKey = "injected-novita",
            openaiBaseUrl = "https://evil.example.com",
            backendType = null,
            agentMode = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = "evil-model",
            executorModel = "evil-executor",
            maxTurns = 999,
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
            authStore = authStore,
            isDebugBuild = false,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            log = {}
        )

        // Nothing should change — all extras ignored
        assertThat(authStore.has(LLMProvider.OPENAI_API)).isFalse()
        assertThat(authStore.has(LLMProvider.OPENROUTER)).isFalse()
        assertThat(authStore.has(LLMProvider.NOVITA)).isFalse()
        assertThat(result.pendingTraceEnabled).isNull()
        assertThat(result.pendingTraceRunId).isNull()
        assertThat(result.pendingExcludedTools).isEmpty()
    }

    @Test
    fun `production build preserves existing pending state`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "injected-key",
            openRouterApiKey = null,
            novitaApiKey = null,
            openaiBaseUrl = null,
            backendType = null,
            agentMode = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            executorModel = null,
            maxTurns = null,
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
            authStore = authStore,
            isDebugBuild = false,
            currentPendingTraceEnabled = true,
            currentPendingTraceRunId = "existing-run",
            currentPendingExcludedTools = setOf("open_app"),
            log = {}
        )

        // Existing state must be preserved, NOT replaced by injected values
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("existing-run")
        assertThat(result.pendingExcludedTools).containsExactly("open_app")
    }

    @Test
    fun `debug build applies intent extras normally`() = runBlocking<Unit> {
        val payload = MainActivityIntentPayload(
            apiKey = "debug-key",
            openRouterApiKey = null,
            novitaApiKey = null,
            openaiBaseUrl = null,
            backendType = null,
            agentMode = null,
            perceptionMode = null,
            platformMode = null,
            mainModel = null,
            executorModel = null,
            maxTurns = null,
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
            authStore = authStore,
            isDebugBuild = true,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            log = {}
        )

        val cred = runBlocking { authStore.get(LLMProvider.OPENAI_API) }
        assertThat(cred).isEqualTo(AuthCredential.ApiKey("debug-key"))
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("debug-run")
    }
}
