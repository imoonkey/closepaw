package ai.closepaw.ui.settings

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.SessionState
import ai.closepaw.session.AgentSession
import ai.closepaw.session.SessionCoordinator
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class MemoryFileEditorTest {

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

    private fun userFile(): File = File(memoryDir, "user.md")

    // ------------------------------------------------------------
    // (1) Bounded: 8 KB content remains editable; tier selector
    //     visible above; Save/Discard reachable after entering EDIT.
    // ------------------------------------------------------------
    @Test fun bounded_editor_handles_large_content_with_visible_controls_and_tier_selector_above() {
        // ~4 KB of multi-line content — large enough to exceed the bounded
        // 240.dp window so the internal scroll path is exercised, but well
        // under the 8192-byte file cap so the editor renders normally.
        val big = "line\n".repeat(800)
        userFile().writeText(big)

        compose.setContent {
            ClosePawTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("TIER_SELECTOR_PROBE", modifier = Modifier.padding(8.dp))
                    MemoryFileEditor(
                        memoryStore = memoryStore,
                        scope = MemoryScope.USER,
                        packageName = null,
                        gate = gate,
                        bounded = true,
                        onOpenFull = {},
                    )
                }
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithText("TIER_SELECTOR_PROBE").assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsEnabled()
        compose.onNodeWithTag(MEMORY_EDITOR_DELETE_TAG).assertIsEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_DISCARD_TAG).assertIsDisplayed()
    }

    // ------------------------------------------------------------
    // (2) Gate: session start locks the editor, banner appears,
    //     typed buffer survives.
    // ------------------------------------------------------------
    @Test fun gate_lock_disables_actions_shows_banner_and_preserves_typed_buffer() {
        userFile().writeText("hello world")

        compose.setContent {
            ClosePawTheme {
                MemoryFileEditor(
                    memoryStore = memoryStore,
                    scope = MemoryScope.USER,
                    packageName = null,
                    gate = gate,
                    bounded = false,
                )
            }
        }

        compose.waitUntil(5_000) {
            !gate.memoryEditLocked.value &&
                compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onAllNodesWithTag(MEMORY_EDITOR_BANNER_TAG).fetchSemanticsNodes().let {
            assert(it.isEmpty()) { "banner should be hidden when unlocked" }
        }
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("typed-buffer-must-survive")
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsEnabled()

        // Session start → gate locks.
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(5_000) { gate.memoryEditLocked.value }

        compose.onNodeWithTag(MEMORY_EDITOR_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(MEMORY_EDIT_LOCKED_BANNER).assertIsDisplayed()
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MEMORY_EDITOR_DISCARD_TAG).assertIsNotEnabled()

        // Buffer survives the lock; file on disk is untouched.
        compose.onNodeWithText("typed-buffer-must-survive").assertIsDisplayed()
        assertEquals("hello world", userFile().readText())
    }

    // ------------------------------------------------------------
    // (3) TOCTOU: click Save while the lock transitions in flight,
    //     write is aborted and the file is unchanged.
    // ------------------------------------------------------------
    @Test fun save_handler_re_checks_gate_inside_coroutine_and_aborts_on_locked_race() {
        userFile().writeText("untouched")

        // A dispatcher that parks the next task until release() is called.
        // We use this to interleave: click Save → coroutine launched and
        // parked → flip gate to locked → release → coroutine resumes and
        // re-checks gate.value (now true) → aborts before writing.
        val parking = ParkingDispatcher()

        var aborts = 0
        compose.setContent {
            ClosePawTheme {
                MemoryFileEditor(
                    memoryStore = memoryStore,
                    scope = MemoryScope.USER,
                    packageName = null,
                    gate = gate,
                    bounded = false,
                    onAborted = { aborts++ },
                    ioDispatcher = parking,
                )
            }
        }

        compose.waitUntil(5_000) {
            !gate.memoryEditLocked.value &&
                compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("racy-new-content")
        // Drain the initial load task (the LaunchedEffect also goes through
        // our dispatcher when it dispatches IO).
        parking.releaseAll()
        compose.waitForIdle()

        // Click Save while the gate is still unlocked. The launch goes to
        // our parking dispatcher and waits for release.
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsEnabled().performClick()
        // Wait for the coroutine to enqueue work into our dispatcher.
        compose.waitUntil(2_000) { parking.pendingCount() >= 1 }

        // Flip the gate to locked BEFORE releasing the parked IO task.
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(2_000) { gate.memoryEditLocked.value }

        // Resume the parked IO; the action-time re-check inside withContext
        // must observe locked=true and abort.
        parking.releaseAll()
        compose.waitForIdle()
        compose.waitUntil(2_000) { aborts == 1 }

        assertEquals(1, aborts)
        assertEquals("untouched", userFile().readText())
    }

    // ------------------------------------------------------------
    // (4) Bounded: ↗ Open is disabled while the editor is dirty.
    // ------------------------------------------------------------
    @Test fun bounded_open_full_disabled_while_dirty() {
        userFile().writeText("original")

        compose.setContent {
            ClosePawTheme {
                MemoryFileEditor(
                    memoryStore = memoryStore,
                    scope = MemoryScope.USER,
                    packageName = null,
                    gate = gate,
                    bounded = true,
                    onOpenFull = {},
                )
            }
        }

        compose.waitUntil(5_000) {
            !gate.memoryEditLocked.value &&
                compose.onAllNodesWithTag(MEMORY_EDITOR_OPEN_FULL_TAG).fetchSemanticsNodes().size == 1
        }

        compose.onNodeWithTag(MEMORY_EDITOR_OPEN_FULL_TAG).assertIsEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_OPEN_FULL_TAG).assertIsEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG).performTextInput("X")
        compose.onNodeWithTag(MEMORY_EDITOR_OPEN_FULL_TAG).assertIsNotEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_DISCARD_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_OPEN_FULL_TAG).assertIsEnabled()
    }
}

/**
 * A dispatcher that queues every dispatched runnable and runs nothing until
 * [releaseAll] is called. Lets a test interleave external state changes
 * (e.g. gate flipping locked) with a parked IO coroutine.
 */
private class ParkingDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    fun pendingCount(): Int = queue.size

    fun releaseAll() {
        while (true) {
            val r = queue.poll() ?: break
            r.run()
        }
    }
}
