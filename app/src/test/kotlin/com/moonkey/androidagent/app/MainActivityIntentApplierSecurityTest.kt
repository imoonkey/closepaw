package com.moonkey.androidagent.app

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Security regression: external intent extras must be ignored in production builds.
 */
class MainActivityIntentApplierSecurityTest {

    private val settingsState = AppSettingsState(mockk(relaxed = true))

    @Test
    fun `production build ignores all sensitive intent extras`() {
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
            isDebugBuild = false,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            log = {}
        )

        // Nothing should change — all extras ignored
        assertThat(settingsState.apiKey).isEmpty()
        assertThat(settingsState.openRouterApiKey).isEmpty()
        assertThat(settingsState.novitaApiKey).isEmpty()
        assertThat(result.pendingTraceEnabled).isNull()
        assertThat(result.pendingTraceRunId).isNull()
        assertThat(result.pendingExcludedTools).isEmpty()
    }

    @Test
    fun `production build preserves existing pending state`() {
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
    fun `debug build applies intent extras normally`() {
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
            isDebugBuild = true,
            currentPendingTraceEnabled = null,
            currentPendingTraceRunId = null,
            currentPendingExcludedTools = emptySet(),
            log = {}
        )

        assertThat(settingsState.apiKey).isEqualTo("debug-key")
        assertThat(result.pendingTraceEnabled).isTrue()
        assertThat(result.pendingTraceRunId).isEqualTo("debug-run")
    }
}
