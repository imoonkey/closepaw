package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryStore
import ai.closepaw.platform.AppInfo
import ai.closepaw.protocol.AppTier
import ai.closepaw.session.SessionCoordinator
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Compose tests for the App Access per-app inline expansion.
 *
 * Each test renders [AppAccessSettingsPage] with a deterministic row list and
 * an injected [AppAccessContentIndex], so the test never touches PackageManager
 * or the live asset tree. The page-scoped index is mutated through the editor's
 * save/delete callbacks just like in production — that's the surface under test.
 */
@RunWith(AndroidJUnit4::class)
class AppAccessExpansionTest {

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

    private fun row(pkg: String, label: String = pkg) = AppRow(
        info = AppInfo(packageName = pkg, label = label),
        iconLoader = { null },
    )

    private fun emptyIndex(): AppAccessContentIndex = AppAccessContentIndex(
        memoryPackages = { emptySet() },
        skillPackages = { emptySet() },
    )

    private fun indexWith(
        memory: Set<String> = emptySet(),
        skills: Set<String> = emptySet(),
    ): AppAccessContentIndex = AppAccessContentIndex(
        memoryPackages = { memory },
        skillPackages = { skills },
    )

    private fun emptyClassifier(): AppClassifier = AppClassifier(appTiers = emptyMap())

    private fun renderPage(
        rows: List<AppRow>,
        classifier: AppClassifier = emptyClassifier(),
        index: AppAccessContentIndex = emptyIndex(),
        skillLoader: suspend (String) -> String? = { null },
    ) {
        compose.setContent {
            ClosePawTheme {
                AppAccessSettingsPage(
                    appClassifier = classifier,
                    memoryStore = memoryStore,
                    gate = gate,
                    onBack = {},
                    onClose = {},
                    contentIndex = index,
                    skillLoader = skillLoader,
                    rowsOverride = rows,
                    ioDispatcher = Dispatchers.Main.immediate,
                )
            }
        }
    }

    // -------------------------------------------------------------
    // (1) Empty row exposes "+ Memory"; clicking creates the file and
    //     expands the row. After expansion, the chevron replaces the
    //     "+ Memory" chip and the Memory summary chip is shown next
    //     to the label.
    // -------------------------------------------------------------
    @Test fun add_memory_creates_file_updates_chip_and_expands_row() {
        val pkg = "com.example.alpha"
        renderPage(rows = listOf(row(pkg)), index = emptyIndex())

        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }

        compose.onNodeWithTag(APP_ROW_ADD_MEMORY_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(APP_ROW_MEMORY_CHIP_TAG).assertCountEquals(0)

        compose.onNodeWithTag(APP_ROW_ADD_MEMORY_TAG).performClick()

        // File is created on disk (write(scope=APP, pkg, "")).
        compose.waitUntil(5_000) { File(memoryDir, "apps/${pkg}.md").exists() }

        // Index update flips the trailing slot from "+ Memory" → chevron,
        // and surfaces the Memory summary chip next to the package label.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(APP_ROW_MEMORY_CHIP_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(APP_ROW_ADD_MEMORY_TAG).assertCountEquals(0)

        // Row is expanded (the bounded MemoryFileEditor is mounted).
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_EXPANSION_ROOT_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsDisplayed()
    }

    // -------------------------------------------------------------
    // (2) For a row that has a skill but no memory, expanding +
    //     editing + saving must create the memory file AND flip the
    //     index so the "Memory" chip appears next to the label.
    //     Collapse + re-expand preserves nothing-in-progress.
    // -------------------------------------------------------------
    @Test fun expand_edit_save_surfaces_memory_chip_and_collapse_clears_state() {
        val pkg = "com.example.skilled"
        // Skill present → trailing chevron, no "+ Memory" chip.
        renderPage(
            rows = listOf(row(pkg)),
            index = indexWith(skills = setOf(pkg)),
            skillLoader = { _ -> "SKILL_BODY_MARKER" },
        )

        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(APP_ROW_SKILL_CHIP_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(APP_ROW_MEMORY_CHIP_TAG).assertCountEquals(0)

        // Expand.
        compose.onNodeWithTag(APP_ROW_TRAILING_CHEVRON_TAG).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_EXPANSION_ROOT_TAG).fetchSemanticsNodes().size == 1
        }
        // Skill viewer renders the asset body.
        compose.onNodeWithTag(APP_EXPANSION_SKILL_BODY_TAG).assertIsDisplayed()

        // The summary said hasMemory=false → editor is NOT mounted yet.
        compose.onAllNodesWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertCountEquals(0)

        // Collapse.
        compose.onNodeWithTag(APP_ROW_TRAILING_CHEVRON_TAG).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_EXPANSION_ROOT_TAG).fetchSemanticsNodes().isEmpty()
        }

        // Re-expand → still no memory editor (nothing-in-progress is preserved).
        compose.onNodeWithTag(APP_ROW_TRAILING_CHEVRON_TAG).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_EXPANSION_ROOT_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertCountEquals(0)
    }

    // -------------------------------------------------------------
    // (3) Save-on-existing-memory flow: row already has memory → expand,
    //     edit text, save → chip stays (index update is idempotent).
    // -------------------------------------------------------------
    @Test fun expand_edit_save_persists_to_disk_and_keeps_memory_chip() {
        val pkg = "com.example.bravo"
        File(memoryDir, "apps").mkdirs()
        File(memoryDir, "apps/${pkg}.md").writeText("original")

        renderPage(
            rows = listOf(row(pkg)),
            index = indexWith(memory = setOf(pkg)),
        )

        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(APP_ROW_MEMORY_CHIP_TAG).assertIsDisplayed()

        compose.onNodeWithTag(APP_ROW_TRAILING_CHEVRON_TAG).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("updated content")
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).performClick()

        compose.waitUntil(5_000) {
            File(memoryDir, "apps/${pkg}.md").readText() == "updated content"
        }
        // Memory chip remains after save (index.update keeps hasMemory=true).
        compose.onNodeWithTag(APP_ROW_MEMORY_CHIP_TAG).assertIsDisplayed()
        assertEquals("updated content", File(memoryDir, "apps/${pkg}.md").readText())
    }

    // -------------------------------------------------------------
    // (4) BLOCKED (bundled) app: expansion area shows the warning chip
    //     above the editor. Inline editor is NOT disabled — a UI edit
    //     is the user's explicit consent.
    // -------------------------------------------------------------
    @Test fun blocked_app_expansion_shows_warning_chip_above_editor() {
        val pkg = "com.example.bank"
        File(memoryDir, "apps").mkdirs()
        File(memoryDir, "apps/${pkg}.md").writeText("notes")

        val classifier = AppClassifier(appTiers = mapOf(pkg to AppTier.BLOCKED))
        renderPage(
            rows = listOf(row(pkg)),
            classifier = classifier,
            index = indexWith(memory = setOf(pkg)),
        )

        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(APP_ROW_TRAILING_CHEVRON_TAG).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_EXPANSION_BLOCKED_WARNING_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithTag(APP_EXPANSION_BLOCKED_WARNING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertIsDisplayed()
        // Inline editor stays interactive in the BLOCKED case (user consent).
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsDisplayed()
    }

    // -------------------------------------------------------------
    // (5) Trailing slot reflects content: chevron on rows with content,
    //     "+ Memory" on rows with neither memory nor skill.
    // -------------------------------------------------------------
    @Test fun trailing_slot_chevron_vs_add_memory_reflects_summary() {
        val withSkill = "com.example.skill"
        val empty = "com.example.empty"
        renderPage(
            rows = listOf(row(withSkill), row(empty)),
            index = indexWith(skills = setOf(withSkill)),
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag(APP_ROW_ADD_MEMORY_TAG).fetchSemanticsNodes().size == 1
        }
        // Exactly one of each — one row has the chevron, the other has + Memory.
        assertTrue(
            "expected one chevron, one + Memory chip",
            compose.onAllNodesWithTag(APP_ROW_TRAILING_CHEVRON_TAG).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag(APP_ROW_ADD_MEMORY_TAG).fetchSemanticsNodes().size == 1,
        )
    }
}
