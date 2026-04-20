package ai.closepaw.qa

import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.settings.LlmAuthSettingsPage
import ai.closepaw.ui.settings.ModelLoadingStatus
import ai.closepaw.ui.settings.OpenAiAuthUiState
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-S8: LLM Auth page callback contract — tabs are inert until the user commits
 * an action inside the tab; provider sub-selector is also inert (no settings
 * writes) until a model is committed; commits canonicalize per Section 5.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLlmAuthTest {

    @get:Rule val compose = createComposeRule()

    /** Extended test catalog with an OPENAI_CODEX entry so OAuth-tab canonicalization has a target. */
    private fun catalog(): ModelCatalog = ModelCatalog.fromJson(
        """
        {
          "gpt-5.2": {"display_name": "GPT-5.2", "provider":"OPENAI_API", "api": "response", "model_id": "gpt-5.2"},
          "gpt-5.2-chat": {"display_name": "GPT-5.2 (Chat API)", "provider":"OPENAI_API", "api": "chat", "model_id": "gpt-5.2"},
          "gpt-5.2-codex": {"display_name": "GPT-5.2 Codex", "provider":"OPENAI_CODEX", "api": "response", "model_id": "gpt-5.2"},
          "glm-5": {"display_name": "GLM-5", "provider": "OPENROUTER", "api": "chat", "model_id": "z-ai/glm-5"},
          "autoglm": {"display_name": "AutoGLM", "provider": "NOVITA", "api": "chat", "model_id": "zai-org/autoglm"}
        }
        """.trimIndent()
    )

    @Composable
    private fun AuthPage(
        llmBackend: LLMBackendType = LLMBackendType.OPENAI,
        onBackendChange: (LLMBackendType) -> Unit = {},
        selectedModel: String = "gpt-5.2",
        onModelChange: (String) -> Unit = {},
        selectedExecutorModel: String? = null,
        onExecutorModelChange: (String?) -> Unit = {},
        onStartOAuth: () -> Unit = {},
        initialAuthTab: AuthMode? = null,
    ) {
        ClosePawTheme {
            LlmAuthSettingsPage(
                llmBackend = llmBackend,
                onBackendChange = onBackendChange,
                selectedModel = selectedModel,
                onModelChange = onModelChange,
                modelCatalog = catalog(),
                selectedExecutorModel = selectedExecutorModel,
                onExecutorModelChange = onExecutorModelChange,
                agentMode = AgentMode.BASIC,
                selectedLocalModel = "LFM2.5-1.2B-Instruct",
                onLocalModelChange = {},
                modelLoadingStatus = ModelLoadingStatus.Idle,
                openAiAuthUiState = OpenAiAuthUiState.SignedOut,
                onStartOAuth = onStartOAuth,
                onCancelOAuth = {},
                onSignOut = {},
                onBack = {},
                onClose = {},
                initialAuthTab = initialAuthTab,
            )
        }
    }

    // S5: switching tabs (no action) does NOT fire backend/model commits.
    @Test fun tab_switch_does_not_commit_backend() {
        var backendCalls = 0
        var modelCalls = 0
        compose.setContent {
            AuthPage(
                onBackendChange = { backendCalls++ },
                onModelChange = { modelCalls++ },
            )
        }

        compose.onNodeWithText("Sign In").performClick()
        // Local tab is intentionally disabled (LlmAuthSettingsPage.kt:157-174);
        // assert that state instead of clicking it.
        compose.onNodeWithText("Local").assertIsNotEnabled()
        compose.onNodeWithText("API Key").performClick()

        assertEquals("onBackendChange must not fire on tab switch alone", 0, backendCalls)
        assertEquals("onModelChange must not fire on tab switch alone", 0, modelCalls)
    }

    // S6: clicking Start OAuth commits backend=OPENAI.
    @Test fun oauth_start_commits_backend() {
        var lastBackend: LLMBackendType? = null
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2-codex",
                onBackendChange = { lastBackend = it },
            )
        }

        compose.onNodeWithText("Sign in with OpenAI").performClick()

        assertEquals(LLMBackendType.OPENAI, lastBackend)
    }

    // S7: provider sub-selector click is view-only — no settings writes
    // (Section 5: "tab switch is view-only … only mutation is model commit").
    @Test fun api_key_provider_switch_is_inert() {
        var modelChanges = 0
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2",
                onModelChange = { modelChanges++ },
            )
        }

        // OpenAI initial; switch to OpenRouter.
        compose.onNodeWithText("OpenRouter").performClick()
        // API Key field label reflects the provider change (view-only).
        compose.onNodeWithText("OpenRouter Key").assertExists()
        assertEquals("provider sub-selector must not commit a model", 0, modelChanges)
    }

    // S8: switching from OAuth-selected model into API Key tab shows the API-Key
    // default provider (OPENAI_API), not the OAuth provider — Section 5 canonicalization.
    @Test fun api_key_tab_with_codex_model_shows_openai_provider() {
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2-codex",   // OAuth-mode model
                initialAuthTab = AuthMode.ApiKey,
            )
        }
        // API Key label should be OpenAI Key (default ApiKey provider), not Codex.
        compose.onNodeWithText("OpenAI Key").assertExists()
    }

    // S9: committing via Start OAuth canonicalizes an incompatible selected model
    // to the OAuth-provider (OPENAI_CODEX) default — e.g. gpt-5.2-chat → gpt-5.2-codex.
    @Test fun incompatible_model_auto_resets_on_method_switch() {
        var lastModel: String? = null
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2-chat",     // ApiKey-mode model
                onModelChange = { lastModel = it },
            )
        }

        compose.onNodeWithText("Sign In").performClick()
        compose.onNodeWithText("Sign in with OpenAI").performClick()

        // Canonicalized to the OPENAI_CODEX default (OAuth tab).
        assertNotEquals("gpt-5.2-chat", lastModel)
        assertEquals("gpt-5.2-codex", lastModel)
    }

    // S10: SettingsSheet deep-link → initialAuthTab = OAuth lands on Sign In tab.
    @Test fun initial_auth_tab_oauth_opens_sign_in_tab() {
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2",
                initialAuthTab = AuthMode.OAuth,
            )
        }
        // Sign In tab content is visible: the "Sign in with OpenAI" button only
        // exists on the SIGN_IN tab.
        compose.onNodeWithText("Sign in with OpenAI").assertExists()
    }

    // S11: initialAuthTab = ApiKey forces API Key tab even when the selected
    // model is an OAuth-mode provider.
    @Test fun initial_auth_tab_api_key_overrides_model_mode() {
        compose.setContent {
            AuthPage(
                selectedModel = "gpt-5.2-codex",    // would default to Sign In tab
                initialAuthTab = AuthMode.ApiKey,
            )
        }
        compose.onNodeWithText("OpenAI Key").assertExists()
        // Sign-in button should NOT be present on API Key tab.
        compose.onAllNodesWithText("Sign in with OpenAI").assertCountEquals(0)
    }
}
