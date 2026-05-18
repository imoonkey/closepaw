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
import androidx.compose.ui.test.onAllNodesWithText
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

        // A dispatcher that delegates to real IO until armed. When armed it
        // queues blocks instead, letting the test interleave external state
        // changes (e.g. gate flipping locked) with a parked IO coroutine.
        // We arm AFTER the initial load so the editor populates normally,
        // then park only the save-path IO hop.
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

        // Wait for the initial load to complete: Edit becomes enabled only
        // when loadingDone is true (and the gate is unlocked).
        compose.waitUntil(5_000) {
            !gate.memoryEditLocked.value &&
                compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).assertIsEnabled()

        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("racy-new-content")
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsEnabled()

        // Arm parking before Save so only the save-path IO hop is parked.
        parking.arm()

        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).performClick()
        // Wait for the save coroutine to dispatch its IO block onto our parker.
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

    // ------------------------------------------------------------
    // (5) Lock cycle: agent writes to disk while locked. On unlock,
    //     the editor reloads from disk and discards user's stale
    //     buffer — preventing a Save from clobbering agent appends.
    // ------------------------------------------------------------
    @Test fun locked_to_unlocked_reloads_buffer_from_disk_discarding_stale_user_edits() {
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

        // User enters EDIT and types a stale buffer that conflicts with what
        // the agent is about to write.
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("user typed text")
        compose.onNodeWithText("user typed text").assertIsDisplayed()

        // Session starts → gate locks. Text field becomes readOnly, buffer
        // is held but cannot drift further.
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(5_000) { gate.memoryEditLocked.value }

        // Agent appends to disk while we're locked (simulate via direct write).
        userFile().writeText("hello world\n## agent appended this")

        // Session ends → gate unlocks. The editor must reload from disk and
        // discard the user's stale "user typed text" buffer.
        state.value = SessionState.Shutdown
        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }

        // Buffer reflects disk content (with agent's append), NOT the user's
        // typed text. Any subsequent Save would write the merged file.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("hello world\n## agent appended this").fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithText("user typed text").fetchSemanticsNodes().let {
            assert(it.isEmpty()) {
                "stale user buffer must be discarded after lock cycle, but the text is still on screen"
            }
        }
        assertEquals("hello world\n## agent appended this", userFile().readText())
    }

    // ------------------------------------------------------------
    // (6) TOCTOU: click Delete confirm while the lock transitions
    //     in flight; delete is aborted and the file remains.
    // ------------------------------------------------------------
    @Test fun delete_handler_re_checks_gate_inside_coroutine_and_aborts_on_locked_race() {
        userFile().writeText("untouched")

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
                compose.onAllNodesWithTag(MEMORY_EDITOR_DELETE_TAG).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(MEMORY_EDITOR_DELETE_TAG).assertIsEnabled()

        // Open the confirm dialog.
        compose.onNodeWithTag(MEMORY_EDITOR_DELETE_TAG).performClick()
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag(MEMORY_EDITOR_DELETE_CONFIRM_TAG).fetchSemanticsNodes().size == 1
        }

        // Arm parking only for the delete-path IO hop.
        parking.arm()

        compose.onNodeWithTag(MEMORY_EDITOR_DELETE_CONFIRM_TAG).performClick()
        compose.waitUntil(2_000) { parking.pendingCount() >= 1 }

        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(2_000) { gate.memoryEditLocked.value }

        parking.releaseAll()
        compose.waitForIdle()
        compose.waitUntil(2_000) { aborts == 1 }

        assertEquals(1, aborts)
        assertEquals("untouched", userFile().readText())
    }

    // ------------------------------------------------------------
    // (7) Reload window: between the locked→unlocked flip and the
    //     post-unlock disk read completing, Save must stay disabled
    //     so a stale pre-session buffer cannot clobber agent appends.
    //     After reload completes, normal save resumes.
    // ------------------------------------------------------------
    @Test fun locked_to_unlocked_reload_window_keeps_save_disabled_then_re_enables() {
        userFile().writeText("on disk pre-session")
        val parking = ParkingDispatcher()

        compose.setContent {
            ClosePawTheme {
                MemoryFileEditor(
                    memoryStore = memoryStore,
                    scope = MemoryScope.USER,
                    packageName = null,
                    gate = gate,
                    bounded = false,
                    ioDispatcher = parking,
                )
            }
        }

        // Initial load runs unparked (arm() not called yet).
        compose.waitUntil(5_000) {
            !gate.memoryEditLocked.value &&
                compose.onAllNodesWithTag(MEMORY_EDITOR_EDIT_TAG).fetchSemanticsNodes().size == 1
        }

        // Enter EDIT, type a stale buffer destined to lose to the agent's append.
        compose.onNodeWithTag(MEMORY_EDITOR_EDIT_TAG).performClick()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("stale user buffer")
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsEnabled()

        // Session starts → lock.
        val state = MutableStateFlow<SessionState>(SessionState.Running)
        coordinator.attachSession(fakeSession(state))
        compose.waitUntil(5_000) { gate.memoryEditLocked.value }

        // Agent appends to disk while locked.
        userFile().writeText("on disk post-session")

        // Arm parking BEFORE unlock so the post-unlock reload IO hop is parked.
        parking.arm()

        // Session ends → unlock → reload starts → parked on `parking`.
        state.value = SessionState.Shutdown
        compose.waitUntil(5_000) { !gate.memoryEditLocked.value }
        compose.waitUntil(2_000) { parking.pendingCount() >= 1 }

        // (1) Save stays disabled while the reload is parked; banner stays.
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MEMORY_EDITOR_DISCARD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MEMORY_EDITOR_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(MEMORY_EDIT_RELOADING_BANNER).assertIsDisplayed()

        // (2) Clicking Save during the reload window is a no-op: the button
        //     is disabled, no IO is dispatched, and the file is unchanged.
        val pendingBeforeClick = parking.pendingCount()
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(pendingBeforeClick, parking.pendingCount())
        assertEquals("on disk post-session", userFile().readText())

        // (3) Release reload → reloading=false → Save re-enabled, normal save
        //     reflects the reloaded buffer plus any new edits.
        parking.releaseAndDisarm()
        compose.waitForIdle()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("on disk post-session").fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithTag(MEMORY_EDITOR_BANNER_TAG).fetchSemanticsNodes().let {
            assert(it.isEmpty()) { "banner should disappear once reload completes" }
        }

        // Still in EDIT mode (entered before the lock); edit the freshly
        // reloaded buffer and save normally.
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MEMORY_EDITOR_TEXTFIELD_TAG)
            .performTextReplacement("user save after reload")
        compose.onNodeWithTag(MEMORY_EDITOR_SAVE_TAG).performClick()
        compose.waitUntil(5_000) { userFile().readText() == "user save after reload" }
        assertEquals("user save after reload", userFile().readText())
    }
}

/**
 * A dispatcher that delegates to [Dispatchers.IO] by default. When [arm] is
 * called, subsequent dispatches are queued and run nothing until
 * [releaseAll] is invoked. Lets a test park a specific IO hop (e.g. the
 * save coroutine) while letting unrelated hops (e.g. the initial load) run
 * normally.
 */
private class ParkingDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var armed = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (armed) {
            queue.add(block)
        } else {
            Dispatchers.IO.dispatch(context, block)
        }
    }

    fun arm() { armed = true }

    fun pendingCount(): Int = queue.size

    fun releaseAll() {
        while (true) {
            val r = queue.poll() ?: break
            r.run()
        }
    }

    /** Drain pending blocks AND stop queueing future dispatches. */
    fun releaseAndDisarm() {
        armed = false
        releaseAll()
    }
}
