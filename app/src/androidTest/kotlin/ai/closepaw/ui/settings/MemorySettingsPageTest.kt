package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionState
import ai.closepaw.session.AgentSession
import ai.closepaw.session.SessionCoordinator
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Tests for the Settings → Memory page surface and the IA reshuffle (LLM &
 * Authentication moves under Behavior, Voice header drops, Memory row added
 * under Behavior).
 */
@RunWith(AndroidJUnit4::class)
class MemorySettingsPageTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var memoryDir: File
    private lateinit var memoryStore: MemoryStore
    private lateinit var coordinator: SessionCoordinator
    private lateinit var gateScope: CoroutineScope
    private lateinit var gate: MemoryEditGate

    @Before fun setup() {
        memoryDir = tempFolder.newFolder("memory")
        memoryStore = MemoryStore(memoryDir)
        gateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        coordinator = SessionCoordinator(gateScope)
        gate = MemoryEditGate(coordinator, gateScope)
    }

    @After fun teardown() {
        gateScope.cancel()
    }

    private fun fakeSession(state: MutableStateFlow<SessionState>): AgentSession {
        val session = mockk<AgentSession>(relaxed = true)
        every { session.state } returns state
        coEvery { session.submit(any()) } returns Unit
        return session
    }

    @Test fun page_lists_only_user_and_device_rows() {
        compose.setContent {
            ClosePawTheme {
                MemorySettingsPage(memoryStore, gate, onBack = {}, onClose = {})
            }
        }

        compose.onNodeWithTag(MEMORY_SETTINGS_USER_ROW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_SETTINGS_DEVICE_ROW_TAG).assertIsDisplayed()
        compose.onNodeWithText("User Memory").assertIsDisplayed()
        compose.onNodeWithText("Device Memory").assertIsDisplayed()
    }

    @Test fun banner_appears_only_when_gate_is_locked() {
        compose.setContent {
            ClosePawTheme {
                MemorySettingsPage(memoryStore, gate, onBack = {}, onClose = {})
            }
        }

        // Initially the gate emits its `true` initial value until the underlying
        // flow produces a real state. Wait for the unlocked state before asserting
        // the banner is hidden.
        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }
        compose.waitForIdle()
        compose.onAllNodesWithTag(MEMORY_SETTINGS_BANNER_TAG).assertCountEquals(0)

        // Start a session → gate locks → banner visible.
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(5_000) { gate.memoryEditLocked.value }

        compose.onNodeWithTag(MEMORY_SETTINGS_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(MEMORY_EDIT_LOCKED_BANNER).assertIsDisplayed()
    }

    @Test fun tapping_user_row_opens_unbounded_memory_file_editor() {
        File(memoryDir, "user.md").writeText("hello from user memory")

        compose.setContent {
            ClosePawTheme {
                MemorySettingsPage(memoryStore, gate, onBack = {}, onClose = {})
            }
        }

        compose.onNodeWithTag(MEMORY_SETTINGS_USER_ROW_TAG).performClick()

        // The editor page header carries the "User Memory" title, and the
        // editor body exposes the textfield + Edit affordance.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("User Memory").assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsDisplayed()
    }

    // IA reshuffle: LLM & Authentication moves under Behavior, Voice header
    // disappears, Memory row joins Behavior. Asserted directly on
    // SettingsHomePage so we are testing the IA, not the sheet wrapper.
    @Test fun home_ia_collapses_voice_into_behavior_and_adds_memory_row() {
        compose.setContent {
            ClosePawTheme {
                SettingsHomePage(
                    llmBackend = LLMBackendType.OPENAI,
                    selectedModel = "gpt-5.2",
                    modelOptions = listOf("gpt-5.2" to "GPT-5.2"),
                    selectedLocalModel = "LFM2.5-1.2B-Instruct",
                    modelCatalog = ai.closepaw.llm.ModelCatalog.fromJson(
                        """
                        {"gpt-5.2": {"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"}}
                        """.trimIndent()
                    ),
                    perceptionMode = "accessibility_only",
                    isAccessibilityEnabled = true,
                    isOverlayEnabled = true,
                    debugMode = false,
                    effectivePlatformMode = PlatformMode.ACCESSIBILITY,
                    appClassifier = AppClassifier(appTiers = emptyMap()),
                    onNavigate = {},
                    onDismiss = {},
                )
            }
        }

        // Voice header is gone.
        compose.onAllNodesWithText("Voice").assertCountEquals(0)
        // Behavior section header is present.
        compose.onNodeWithText("Behavior").assertIsDisplayed()
        // All three Behavior rows render.
        compose.onNodeWithText("LLM & Authentication").assertIsDisplayed()
        compose.onNodeWithText("Agent Behavior").assertIsDisplayed()
        compose.onNodeWithText("Memory").assertIsDisplayed()
    }
}
