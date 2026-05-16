package ai.closepaw.qa

import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.DisplayModeSection
import ai.closepaw.ui.settings.SettingsHomePage
import ai.closepaw.ui.settings.ShizukuStatus
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers spm-qa-coverage:
 *  (a) selector toggle invokes onPlatformModeChange with correct PlatformMode
 *  (b) Virtual Display option is a no-op when ShizukuStatus is Unavailable
 *  (c) selector selection reflects persisted intent, not live effective mode
 *  (d) Home subtitle reflects effectivePlatformMode (chip present/absent)
 */
@RunWith(AndroidJUnit4::class)
class DisplayModeSettingsTest {

    @get:Rule val compose = createComposeRule()

    // (a) Toggling selector calls onPlatformModeChange with the tapped mode.
    @Test fun selector_toggle_invokes_callback_with_target_mode() {
        var lastMode: PlatformMode? = null
        compose.setContent {
            var mode by remember { mutableStateOf(PlatformMode.ACCESSIBILITY) }
            ClosePawTheme {
                DisplayModeSection(
                    persistedMode = mode,
                    effectiveMode = null,
                    status = ShizukuStatus.Ready,
                    onModeChange = {
                        lastMode = it
                        mode = it
                    },
                    onLearnMore = {},
                    onGrant = {},
                )
            }
        }

        // Tap Virtual Display when Shizuku Ready → callback fires with VIRTUAL_DISPLAY.
        compose.onNodeWithText("Virtual Display").performClick()
        assertEquals(PlatformMode.VIRTUAL_DISPLAY, lastMode)

        // Tap back to Accessibility → callback fires with ACCESSIBILITY.
        compose.onNodeWithText("Accessibility").performClick()
        assertEquals(PlatformMode.ACCESSIBILITY, lastMode)
    }

    // (b) Virtual Display option must NOT invoke callback when status is Unavailable.
    @Test fun virtual_display_option_is_noop_when_shizuku_unavailable() {
        var callbackCount = 0
        compose.setContent {
            ClosePawTheme {
                DisplayModeSection(
                    persistedMode = PlatformMode.ACCESSIBILITY,
                    effectiveMode = null,
                    status = ShizukuStatus.Unavailable,
                    onModeChange = { callbackCount++ },
                    onLearnMore = {},
                    onGrant = {},
                )
            }
        }

        compose.onNodeWithText("Virtual Display").performClick()
        assertEquals(0, callbackCount)

        // Sanity: the unavailable-status row is rendered, confirming the section is shown.
        compose.onNodeWithText("Shizuku not running").assertExists()
    }

    @Test fun selector_selection_reflects_persisted_mode_not_effective_mode() {
        compose.setContent {
            var mode by remember { mutableStateOf(PlatformMode.ACCESSIBILITY) }
            ClosePawTheme {
                DisplayModeSection(
                    persistedMode = mode,
                    effectiveMode = PlatformMode.ACCESSIBILITY,
                    status = ShizukuStatus.Ready,
                    onModeChange = { mode = it },
                    onLearnMore = {},
                    onGrant = {},
                )
            }
        }

        compose.onNodeWithTag("display-mode-virtual-display").performClick()

        compose.onNodeWithTag("display-mode-virtual-display").assertIsSelected()
        compose.onNodeWithTag("display-mode-accessibility").assertIsNotSelected()
        compose.onNodeWithText("Current session: Accessibility").assertExists()
    }

    // (d) Home subtitle includes ' · VD' / ' · A11y' chip from effectivePlatformMode,
    //     and omits it when null.
    @Test fun home_subtitle_reflects_effective_platform_mode() {
        var current: PlatformMode? by mutableStateOf<PlatformMode?>(null)
        compose.setContent { HomeUnderTest(effectivePlatformMode = current) }

        // null → no chip; subtitle ends with "Debug off".
        compose.onNodeWithText("Setup required · Debug off", substring = true).assertExists()
        compose.onAllNodesWithText("VD", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("A11y", substring = true).assertCountEquals(0)

        // VIRTUAL_DISPLAY → chip ' · VD' appended.
        current = PlatformMode.VIRTUAL_DISPLAY
        compose.onNodeWithText("Setup required · Debug off · VD", substring = true).assertExists()

        // ACCESSIBILITY → chip ' · A11y' appended.
        current = PlatformMode.ACCESSIBILITY
        compose.onNodeWithText("Setup required · Debug off · A11y", substring = true).assertExists()
    }
}

@androidx.compose.runtime.Composable
private fun HomeUnderTest(effectivePlatformMode: PlatformMode?) {
    ClosePawTheme {
        SettingsHomePage(
            llmBackend = LLMBackendType.OPENAI,
            selectedModel = "gpt-5.2",
            modelOptions = listOf("gpt-5.2" to "GPT-5.2"),
            selectedLocalModel = "LFM2.5-1.2B-Instruct",
            modelCatalog = testModelCatalog(),
            perceptionMode = "accessibility_only",
            isAccessibilityEnabled = false,
            isOverlayEnabled = false,
            debugMode = false,
            effectivePlatformMode = effectivePlatformMode,
            onNavigate = {},
            onDismiss = {},
        )
    }
}
