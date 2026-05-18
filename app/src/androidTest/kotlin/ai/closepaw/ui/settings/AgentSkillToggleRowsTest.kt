package ai.closepaw.ui.settings

import ai.closepaw.agent.cognition.skills.AgentSkillEntry
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.ui.theme.ClosePawTheme
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray

/**
 * Compose UI tests for [AgentSkillToggleRows].
 *
 * Strategy:
 * - Inject a deterministic `skillsLoader` so the test does not depend on filesDir/skills
 *   or the bundled installer running. Two synthetic skills cover toggle, info viewer,
 *   and next-session subtitle scenarios.
 * - Use the real prefs-backed [AppSettingsStore]; clear `agent_prefs` in @Before so
 *   toggle persistence can be asserted directly against the stored JSON array.
 * - Render `AgentSkillToggleRows` directly (internal visibility crosses main +
 *   androidTest), not via [AgentBehaviorSettingsPage], to keep the test scoped and to
 *   pass `isSessionRunning` for the subtitle assertion.
 */
@RunWith(AndroidJUnit4::class)
class AgentSkillToggleRowsTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var prefs: SharedPreferences

    private val browserUse = AgentSkillLoaderResult(
        entry = AgentSkillEntry(
            name = "browser-use",
            description = "Drive Chrome to fill forms and click links.",
            filePath = "/skills/browser-use/SKILL.md",
        ),
        content = "BROWSER_USE_PROMPT_BODY_MARKER_42",
    )

    private val calendar = AgentSkillLoaderResult(
        entry = AgentSkillEntry(
            name = "calendar-math",
            description = "Compute relative dates and durations.",
            filePath = "/skills/calendar-math/SKILL.md",
        ),
        content = "CALENDAR_PROMPT_BODY_MARKER_99",
    )

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = target.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After fun teardown() {
        prefs.edit().clear().commit()
    }

    private fun setContent(
        isSessionRunning: Boolean = false,
        skills: List<AgentSkillLoaderResult> = listOf(browserUse, calendar),
    ) {
        compose.setContent {
            ClosePawTheme {
                Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    AgentSkillToggleRows(
                        isSessionRunning = isSessionRunning,
                        skillsLoader = { skills },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // Discovery + rendering -------------------------------------------------------------------

    @Test fun renders_one_row_per_discovered_skill() {
        setContent()
        compose.onNodeWithText("browser-use").assertIsDisplayed()
        compose.onNodeWithText("calendar-math").assertIsDisplayed()
        // Two skills → two Switches inside the section.
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
    }

    @Test fun section_hidden_when_no_skills_discovered() {
        setContent(skills = emptyList())
        compose.onAllNodesWithContentDescription("View browser-use prompt").assertCountEquals(0)
        // Section heading should also not render.
        compose.onNodeWithText("Agent Skills").assertDoesNotExist()
    }

    // Switch inversion + persistence ---------------------------------------------------------

    @Test fun switch_starts_on_when_skill_not_disabled() {
        setContent()
        compose.onAllNodes(isToggleable())[0].assertIsOn()
    }

    @Test fun toggling_off_persists_disabled_name() {
        setContent()
        // Index 0 is the first row (browser-use) because the loader preserves order.
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitUntil(timeoutMillis = 3000) {
            disabledSetFromPrefs().contains("browser-use")
        }
        assertTrue(disabledSetFromPrefs().contains("browser-use"))
        // The other skill stays enabled.
        assertEquals(false, disabledSetFromPrefs().contains("calendar-math"))
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test fun toggling_on_removes_disabled_name() {
        // Seed prefs so browser-use starts disabled.
        prefs.edit().putString(
            "disabled_agent_skills",
            JSONArray().put("browser-use").toString(),
        ).commit()
        setContent()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitUntil(timeoutMillis = 3000) {
            !disabledSetFromPrefs().contains("browser-use")
        }
        assertEquals(false, disabledSetFromPrefs().contains("browser-use"))
    }

    // Next-session subtitle ------------------------------------------------------------------

    @Test fun disabled_while_running_shows_next_session_subtitle() {
        prefs.edit().putString(
            "disabled_agent_skills",
            JSONArray().put("browser-use").toString(),
        ).commit()
        setContent(isSessionRunning = true)
        compose.onNodeWithText("Takes effect next session").assertIsDisplayed()
        // The enabled skill keeps its description, not the next-session subtitle.
        compose.onNodeWithText(calendar.entry.description).assertIsDisplayed()
    }

    @Test fun disabled_without_running_session_shows_description() {
        prefs.edit().putString(
            "disabled_agent_skills",
            JSONArray().put("browser-use").toString(),
        ).commit()
        setContent(isSessionRunning = false)
        compose.onNodeWithText("Takes effect next session").assertDoesNotExist()
        compose.onNodeWithText(browserUse.entry.description).assertIsDisplayed()
    }

    @Test fun enabled_skill_with_running_session_shows_description_not_next_session() {
        // browser-use is NOT in disabled set; even with a session running it shows description.
        setContent(isSessionRunning = true)
        compose.onNodeWithText(browserUse.entry.description).assertIsDisplayed()
        // Subtitle text must not appear since no skill is currently disabled.
        compose.onNodeWithText("Takes effect next session").assertDoesNotExist()
    }

    // Info icon viewer -----------------------------------------------------------------------

    @Test fun info_icon_opens_viewer_with_skill_content() {
        setContent()
        compose.onNodeWithContentDescription("View browser-use prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(browserUse.content).assertIsDisplayed()
        // Other skill's content must not leak into the open dialog.
        compose.onNodeWithText(calendar.content).assertDoesNotExist()
    }

    @Test fun viewer_close_button_dismisses_dialog() {
        setContent()
        compose.onNodeWithContentDescription("View browser-use prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(browserUse.content).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(browserUse.content).assertDoesNotExist()
    }

    // Helpers --------------------------------------------------------------------------------

    private fun disabledSetFromPrefs(): Set<String> {
        val raw = prefs.getString("disabled_agent_skills", null) ?: return emptySet()
        val arr = JSONArray(raw)
        return buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }
}
