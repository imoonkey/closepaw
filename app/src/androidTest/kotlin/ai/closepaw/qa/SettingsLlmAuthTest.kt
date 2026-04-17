package ai.closepaw.qa

import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
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
 * an action inside the tab, and commits canonicalize model selection.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLlmAuthTest {

    @get:Rule val compose = createComposeRule()

    // S5: switching tabs (no action) does NOT fire backend/auth commits.
    @Test fun tab_switch_does_not_commit_backend() {
        var backendCalls = 0
        var authCalls = 0
        compose.setContent {
            TestLlmAuthPage(
                onBackendChange = { backendCalls++ },
                onAuthMethodChange = { authCalls++ },
            )
        }

        compose.onNodeWithText("Sign In").performClick()
        compose.onNodeWithText("Local").performClick()
        compose.onNodeWithText("API Key").performClick()

        assertEquals("onBackendChange must not fire on tab switch alone", 0, backendCalls)
        assertEquals("onAuthMethodChange must not fire on tab switch alone", 0, authCalls)
    }

    // S6: clicking Start OAuth commits backend=OPENAI and authMethod="oauth".
    @Test fun oauth_start_commits_backend_and_method() {
        var lastBackend: LLMBackendType? = null
        var lastAuth: String? = "<unset>"
        compose.setContent {
            TestLlmAuthPage(
                authMethod = "oauth",
                selectedModel = "gpt-5.2",
                onBackendChange = { lastBackend = it },
                onAuthMethodChange = { lastAuth = it },
            )
        }

        compose.onNodeWithText("Sign in with OpenAI").performClick()

        assertEquals(LLMBackendType.OPENAI, lastBackend)
        assertEquals("oauth", lastAuth)
    }

    // S7: switching provider in API Key tab updates the displayed Cloud Model
    // (current model invalid for new provider → canonicalized to a provider-valid one).
    @Test fun api_key_provider_switch_changes_model_options() {
        compose.setContent {
            var model by remember { mutableStateOf("gpt-5.2") }
            TestLlmAuthPage(
                authMethod = null,
                selectedModel = model,
                onModelChange = { model = it },
            )
        }

        // API Key tab is the default for authMethod=null. OpenAI initial.
        compose.onNodeWithText("GPT-5.2").assertExists()

        compose.onNodeWithText("OpenRouter").performClick()

        // GLM-5 is the only OpenRouter model in the test catalog.
        compose.onNodeWithText("GLM-5").assertExists()
        compose.onAllNodesWithText("GPT-5.2").assertCountEquals(0)
    }

    // S8: model incompatible with the Sign In tab's OpenAI/RESPONSE filter is
    // auto-canonicalized when the user commits via Start OAuth.
    @Test fun incompatible_model_auto_resets_on_method_switch() {
        var lastModel: String? = null
        compose.setContent {
            TestLlmAuthPage(
                authMethod = null,                  // API Key tab initially
                selectedModel = "gpt-5.2-chat",     // OpenAI but CHAT — not valid for Sign In
                onModelChange = { lastModel = it },
            )
        }

        // Move to Sign In tab, then trigger commit via Start OAuth.
        compose.onNodeWithText("Sign In").performClick()
        compose.onNodeWithText("Sign in with OpenAI").performClick()

        // Canonicalized to a valid OpenAI RESPONSE model.
        assertNotEquals("gpt-5.2-chat", lastModel)
        assertEquals("gpt-5.2", lastModel)
    }
}
