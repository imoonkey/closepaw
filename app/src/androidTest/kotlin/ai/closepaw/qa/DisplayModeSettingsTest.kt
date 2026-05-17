package ai.closepaw.qa

import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.settings.DisplaySection
import ai.closepaw.ui.settings.SettingsHomePage
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * Covers spm-qa-coverage for the refactored Display Mode toggle:
 *  (a) toggling the switch OFF invokes onPlatformModeChange(ACCESSIBILITY) unconditionally
 *  (b) switch checked state strictly mirrors persistedMode, never effectiveMode
 *  (c) Home page's Agent Behavior subtitle reflects effectivePlatformMode chip
 *
 * Note on Shizuku-gated paths: the OFF→ON gate (ShizukuUnavailable / NeedsPermission) is
 * exercised by VirtualDisplayToggleGate JVM tests; the Compose layer here can't inject
 * ShizukuStatus into DisplaySection without restructuring it, so we don't reproduce that
 * coverage from the UI side.
 */
@RunWith(AndroidJUnit4::class)
class DisplayModeSettingsTest {

    @get:Rule val compose = createComposeRule()

    // (a) Toggle OFF is unconditional — fires onPlatformModeChange(ACCESSIBILITY) with no gate.
    @Test fun switch_toggle_off_invokes_callback_with_accessibility() {
        var lastMode: PlatformMode? = null
        compose.setContent {
            var mode by remember { mutableStateOf(PlatformMode.VIRTUAL_DISPLAY) }
            ClosePawTheme {
                DisplaySection(
                    persistedMode = mode,
                    effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
                    onPlatformModeChange = {
                        lastMode = it
                        mode = it
                    },
                )
            }
        }

        // Switch starts ON because persistedMode == VIRTUAL_DISPLAY.
        compose.onNodeWithTag("display-mode-switch").assertIsOn()
        compose.onNodeWithTag("display-mode-switch").performClick()
        assertEquals(PlatformMode.ACCESSIBILITY, lastMode)
        compose.onNodeWithTag("display-mode-switch").assertIsOff()
    }

    // (b) Switch checked state mirrors persistedMode even when effectiveMode differs —
    //     the previous radio invariant ("only one selected") becomes "switch reflects persisted".
    @Test fun switch_state_mirrors_persisted_mode_not_effective_mode() {
        compose.setContent {
            ClosePawTheme {
                DisplaySection(
                    persistedMode = PlatformMode.ACCESSIBILITY,
                    effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
                    onPlatformModeChange = {},
                )
            }
        }

        // Persisted ACCESSIBILITY → switch OFF, despite live session being on VIRTUAL_DISPLAY.
        // Mapper row 5: subtitle calls out the lingering session.
        compose.onNodeWithTag("display-mode-switch").assertIsOff()
        compose.onNodeWithText(
            "Current session is still on a virtual display",
            substring = true,
        ).assertExists()
    }

    // (c) Home Agent Behavior subtitle pulls a "VD" chip from effectivePlatformMode.
    //     The old combined "Setup required · Debug off · VD" string is gone — that subtitle
    //     was split: permissions row keeps the "· Debug" half, agent-behavior row owns "· VD".
    @Test fun home_subtitle_reflects_effective_platform_mode() {
        var current: PlatformMode? by mutableStateOf<PlatformMode?>(null)
        compose.setContent {
            HomeUnderTest(
                persistedPlatformMode = PlatformMode.ACCESSIBILITY,
                effectivePlatformMode = current,
            )
        }

        // null effective → perception-only subtitle "Transcript", no VD chip on screen.
        compose.onNodeWithText("Transcript").assertExists()
        compose.onAllNodesWithText("VD", substring = true).assertCountEquals(0)

        // effective=VIRTUAL_DISPLAY, persisted=ACCESSIBILITY → chip "VD (this session)".
        current = PlatformMode.VIRTUAL_DISPLAY
        compose.onNodeWithText("Transcript · VD (this session)", substring = true).assertExists()
    }
}

@androidx.compose.runtime.Composable
private fun HomeUnderTest(
    persistedPlatformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
) {
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
            platformMode = persistedPlatformMode,
            effectivePlatformMode = effectivePlatformMode,
            appClassifier = AppClassifier(appTiers = emptyMap()),
            onNavigate = {},
            onDismiss = {},
        )
    }
}
