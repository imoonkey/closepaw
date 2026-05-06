package ai.closepaw.qa

import ai.closepaw.protocol.AgentMode
import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxBridgeStatus
import ai.closepaw.ui.settings.AgentBehaviorSettingsPage
import ai.closepaw.ui.theme.ClosePawTheme
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for TermuxShellSettingsRow inside AgentBehaviorSettingsPage.
 *
 * Strategy:
 *  - mockkObject(TermuxBridgeManager.Companion) intercepts the singleton get(), so the row
 *    binds to a mock manager whose state we control via a MutableStateFlow.
 *  - Espresso-Intents is not on the androidTest classpath. Instead, LocalContext is overridden
 *    with an IntentRecordingContext that captures startActivity() calls. It also stubs
 *    getPackageManager().getLaunchIntentForPackage("com.termux") so launchTermux() produces a
 *    deterministic intent regardless of whether Termux is installed on the test device.
 *  - AppSettingsStore is the real prefs-backed store; tests clear prefs in @Before to decouple
 *    from prior runs. Toggle persistence is asserted directly against prefs.
 *  - The row composable is private; we render it through its module-internal parent
 *    AgentBehaviorSettingsPage with dummy values for unrelated controls.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTermuxRowTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var stateFlow: MutableStateFlow<TermuxBridgeStatus>
    private lateinit var manager: TermuxBridgeManager
    private lateinit var prefs: SharedPreferences
    private lateinit var sentIntents: MutableList<Intent>
    private lateinit var stubPackageManager: PackageManager
    private val launchTermuxIntent: Intent =
        Intent("ai.closepaw.test.LAUNCH_TERMUX").setPackage("com.termux")

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = target.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        stateFlow = MutableStateFlow<TermuxBridgeStatus>(TermuxBridgeStatus.Ready)
        manager = mockk(relaxed = true)
        every { manager.state } returns stateFlow
        coEvery { manager.detectInstalled() } returns TermuxBridgeStatus.Ready
        coEvery { manager.healthCheck() } returns TermuxBridgeStatus.Ready
        coEvery { manager.setup() } returns TermuxBridgeStatus.Ready
        coEvery { manager.restart() } returns TermuxBridgeStatus.Ready

        mockkObject(TermuxBridgeManager.Companion)
        every { TermuxBridgeManager.get(any()) } returns manager

        // Build the PackageManager stub on the test thread, not inside the composable —
        // mockk's bytecode generation can race with UI-thread composition setup.
        stubPackageManager = mockk(relaxed = true)
        every { stubPackageManager.getLaunchIntentForPackage("com.termux") } returns
            Intent(launchTermuxIntent)

        sentIntents = mutableListOf()
    }

    @After fun teardown() {
        unmockkAll()
    }

    private fun setRowContent(initialStatus: TermuxBridgeStatus) {
        stateFlow.value = initialStatus
        compose.setContent {
            ClosePawTheme {
                val baseContext = LocalContext.current
                val recording = remember(baseContext) {
                    IntentRecordingContext(baseContext, sentIntents, stubPackageManager)
                }
                CompositionLocalProvider(LocalContext provides recording) {
                    AgentBehaviorSettingsPage(
                        maxTurns = 20,
                        onMaxTurnsChange = {},
                        agentMode = AgentMode.BASIC,
                        onAgentModeChange = {},
                        perceptionMode = "accessibility_only",
                        onPerceptionModeChange = {},
                        browserScriptEnabled = false,
                        onBrowserScriptEnabledChange = {},
                        onBack = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // 3-state indicator coverage --------------------------------------------------------------

    @Test fun renders_ready_state_indicator() {
        setRowContent(TermuxBridgeStatus.Ready)
        compose.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test fun renders_needs_setup_state_indicator() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PORT_IN_USE))
        compose.onNodeWithText("Needs Setup").assertIsDisplayed()
    }

    @Test fun renders_not_installed_state_indicator() {
        setRowContent(TermuxBridgeStatus.NotInstalled)
        compose.onNodeWithText("Not Installed").assertIsDisplayed()
    }

    // Subtitle copy ---------------------------------------------------------------------------

    @Test fun termux_not_running_subtitle_uses_new_copy() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING))
        compose.onNodeWithText(
            "Termux is not running. Tap to open Termux, then return here."
        ).assertIsDisplayed()
    }

    // Toggle persistence ----------------------------------------------------------------------

    @Test fun toggle_off_persists_to_prefs() {
        // Default termux_shell_enabled is true, so the switch starts ON.
        setRowContent(TermuxBridgeStatus.Ready)
        // Two Switches now exist on the page (Termux and Browser Script). Termux is rendered
        // first in the layout (Execution section) — index [0] in document order.
        compose.onAllNodes(isToggleable())[0].performClick()
        // setTermuxShellEnabled is suspend (Dispatchers.IO write); poll prefs.
        compose.waitUntil(timeoutMillis = 3000) {
            !prefs.getBoolean("termux_shell_enabled", true)
        }
        assertEquals(false, prefs.getBoolean("termux_shell_enabled", true))
    }

    @Test fun toggle_on_persists_to_prefs() {
        // Seed prefs to false so the switch starts OFF.
        prefs.edit().putBoolean("termux_shell_enabled", false).commit()
        setRowContent(TermuxBridgeStatus.Ready)
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitUntil(timeoutMillis = 3000) {
            prefs.getBoolean("termux_shell_enabled", false)
        }
        assertEquals(true, prefs.getBoolean("termux_shell_enabled", false))
    }

    // Action branches -------------------------------------------------------------------------

    @Test fun tap_when_not_installed_dispatches_fdroid_intent() {
        setRowContent(TermuxBridgeStatus.NotInstalled)
        // Compose merges the clickable Surface with its Text descendants — performClick on
        // the status label resolves to the Surface's click action.
        compose.onNodeWithText("Not Installed").performClick()
        compose.waitUntil(timeoutMillis = 3000) { sentIntents.isNotEmpty() }
        val sent = sentIntents.first()
        assertEquals(Intent.ACTION_VIEW, sent.action)
        assertEquals("https://f-droid.org/packages/com.termux/", sent.data?.toString())
    }

    @Test fun tap_when_termux_not_running_dispatches_launch_intent() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING))
        compose.onNodeWithText("Needs Setup").performClick()
        compose.waitUntil(timeoutMillis = 3000) { sentIntents.isNotEmpty() }
        val sent = sentIntents.first()
        // IntentRecordingContext stubs getLaunchIntentForPackage("com.termux") to launchTermuxIntent;
        // the row should add FLAG_ACTIVITY_NEW_TASK before dispatching.
        assertEquals("com.termux", sent.`package`)
        assertEquals(launchTermuxIntent.action, sent.action)
        assertTrue(
            "expected FLAG_ACTIVITY_NEW_TASK on launch intent",
            sent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test fun tap_when_port_in_use_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PORT_IN_USE))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
        assertTrue("no intent should be sent for PORT_IN_USE", sentIntents.isEmpty())
    }

    @Test fun tap_when_packages_missing_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
    }

    @Test fun tap_when_allow_external_apps_missing_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
    }

    @Test fun tap_when_bridge_outdated_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.BRIDGE_OUTDATED))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
    }

    @Test fun tap_when_health_timeout_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
    }

    @Test fun tap_when_unknown_calls_manager_setup() {
        setRowContent(TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.UNKNOWN))
        compose.onNodeWithText("Needs Setup").performClick()
        coVerify(timeout = 3000) { manager.setup() }
    }

    private class IntentRecordingContext(
        base: Context,
        private val intents: MutableList<Intent>,
        private val pm: PackageManager,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getPackageManager(): PackageManager = pm
        override fun startActivity(intent: Intent) { intents.add(intent) }
        override fun startActivity(intent: Intent, options: Bundle?) { intents.add(intent) }
    }
}
