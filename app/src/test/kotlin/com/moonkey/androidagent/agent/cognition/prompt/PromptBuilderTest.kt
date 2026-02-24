package com.moonkey.androidagent.agent.cognition.prompt

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.MessageKind
import com.moonkey.androidagent.history.ResponseItem
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.PerceptionConfig
import com.moonkey.androidagent.protocol.Todo
import com.moonkey.androidagent.protocol.TodoStatus
import com.moonkey.androidagent.session.AgentSessionState
import org.json.JSONObject
import org.junit.Test

class PromptBuilderTest {

    private val emptySnapshot = ScreenSnapshot(timestamp = 1L, elements = emptyList())

    /** Snapshot with accessibility data for mode-aware tests. */
    private val snapshotWithElements = ScreenSnapshot(
        timestamp = 1L,
        elements = listOf(
            PerceptionElement(
                index = 0,
                text = "Button",
                resourceId = "",
                className = "TextView",
                description = "",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isEnabled = true,
                isFocused = false,
                isLongClickable = false,
                bounds = Bounds(0, 0, 100, 50),
                center = Point(50, 25)
            )
        )
    )

    // ── Screen Compression ──────────────────────────────────────────────

    @Test
    fun `compressOldScreenObservations preserves all items when fewer than threshold`() {
        val builder = createBuilder()
        val items = listOf(
            screenObservation("Screen state (10 elements):\n```json\n[]\n```"),
            screenObservation("Screen state (20 elements):\n```json\n[]\n```")
        )
        val result = builder.compressOldScreenObservations(items)
        assertThat(result).isEqualTo(items)
    }

    @Test
    fun `compressOldScreenObservations compresses oldest when exceeding threshold`() {
        val builder = createBuilder(recentFullScreenTurns = 2)
        val items = listOf(
            screenObservation("Screen state (10 elements):\n```json\n[{\"old\":true}]\n```"),
            assistantMessage("I'll click element 5"),
            screenObservation("Screen state (20 elements):\n```json\n[]\n```"),
            assistantMessage("Done"),
            screenObservation("Screen state (30 elements):\n```json\n[]\n```")
        )

        val result = builder.compressOldScreenObservations(items)

        // First screen observation should be compressed
        val first = result[0] as ResponseItem.Message
        assertThat(first.content).isEqualTo("Screen: 10 elements (compressed)")
        assertThat(first.kind).isEqualTo(MessageKind.SCREEN_OBSERVATION)

        // Second and third screen observations should be preserved
        val second = result[2] as ResponseItem.Message
        assertThat(second.content).contains("```json")

        val third = result[4] as ResponseItem.Message
        assertThat(third.content).contains("```json")
    }

    @Test
    fun `compressOldScreenObservations never compresses non-screen messages`() {
        val builder = createBuilder(recentFullScreenTurns = 1)
        val items = listOf(
            userIntent("Goal: Open Gmail"),
            screenObservation("Screen state (10 elements):\n```json\n[]\n```"),
            assistantMessage("Opening Gmail"),
            screenObservation("Screen state (20 elements):\n```json\n[]\n```")
        )

        val result = builder.compressOldScreenObservations(items)

        // Goal message untouched
        val goal = result[0] as ResponseItem.Message
        assertThat(goal.content).isEqualTo("Goal: Open Gmail")

        // First screen compressed, second preserved
        val firstScreen = result[1] as ResponseItem.Message
        assertThat(firstScreen.content).contains("compressed")

        val secondScreen = result[3] as ResponseItem.Message
        assertThat(secondScreen.content).contains("```json")
    }

    @Test
    fun `compressScreenContent extracts element count`() {
        val builder = createBuilder()
        val result = builder.compressScreenContent(
            "Screen state (42 elements):\n```json\n[{\"index\":0}]\n```"
        )
        assertThat(result).isEqualTo("Screen: 42 elements (compressed)")
    }

    @Test
    fun `compressScreenContent handles missing element count`() {
        val builder = createBuilder()
        val result = builder.compressScreenContent("some text without element count")
        assertThat(result).isEqualTo("Screen: unknown (compressed)")
    }

    @Test
    fun `compressScreenContent handles screenshot-only format`() {
        val builder = createBuilder()
        val result = builder.compressScreenContent(
            "No accessibility tree available for this screen.\nUse coordinate-based actions (x, y)"
        )
        assertThat(result).isEqualTo("Screen: screenshot only (compressed)")
    }

    // ── Memory Section ──────────────────────────────────────────────────

    @Test
    fun `buildMemoryText returns null when both empty`() {
        val builder = createBuilder()
        assertThat(builder.buildMemoryText()).isNull()
    }

    @Test
    fun `buildMemoryText includes todos when present`() {
        val state = AgentSessionState()
        state.todos.update(listOf(
            Todo(description = "Open Gmail", status = TodoStatus.IN_PROGRESS),
            Todo(description = "Read first email", status = TodoStatus.PENDING)
        ))
        val builder = createBuilder(sessionState = state)

        val text = builder.buildMemoryText()

        assertThat(text).isNotNull()
        assertThat(text).contains("## Working Memory")
        assertThat(text).contains("### Todo List")
        assertThat(text).contains("[IN_PROGRESS] Open Gmail")
        assertThat(text).contains("[PENDING] Read first email")
    }

    @Test
    fun `buildMemoryText includes scratchpad when has entries`() {
        val state = AgentSessionState()
        state.scratchpad.write("email_count", "5")
        val builder = createBuilder(sessionState = state)

        val text = builder.buildMemoryText()

        assertThat(text).isNotNull()
        assertThat(text).contains("### Scratchpad")
        assertThat(text).contains("- email_count")
    }

    @Test
    fun `buildMemoryText combines todos and scratchpad`() {
        val state = AgentSessionState()
        state.todos.update(listOf(
            Todo(description = "Do something", status = TodoStatus.PENDING)
        ))
        state.scratchpad.write("key1", "val1")
        val builder = createBuilder(sessionState = state)

        val text = builder.buildMemoryText()

        assertThat(text).contains("### Todo List")
        assertThat(text).contains("### Scratchpad")
        // Todo comes before scratchpad
        val todoIdx = text!!.indexOf("### Todo List")
        val scratchIdx = text.indexOf("### Scratchpad")
        assertThat(todoIdx).isLessThan(scratchIdx)
    }

    // ── Observation Section ─────────────────────────────────────────────

    @Test
    fun `buildObservationText includes screen state when accessibility available`() {
        val builder = createBuilder()
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).contains("Screen state (1 elements):")
        assertThat(text).contains("```json")
    }

    @Test
    fun `buildObservationText shows screenshot-only guidance when perceptionConfig is ScreenshotOnly`() {
        val builder = createBuilder(perceptionConfig = PerceptionConfig.ScreenshotOnly())
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).contains("No accessibility tree available for this screen.")
        assertThat(text).contains("Use coordinate-based actions (x, y)")
    }

    @Test
    fun `buildObservationText places warnings before screen state`() {
        val builder = createBuilder()
        val warnings = listOf(
            "⚠️ Screen unchanged for 3 turns.",
            "🛑 FINAL TURN (10). Complete now."
        )
        val text = builder.buildObservationText(snapshotWithElements, null, warnings)

        val warningIdx = text.indexOf("⚠️ Screen unchanged")
        val screenIdx = text.indexOf("Screen state")
        assertThat(warningIdx).isLessThan(screenIdx)
        assertThat(text).contains("🛑 FINAL TURN")
    }

    @Test
    fun `buildObservationText has no warnings when list empty`() {
        val builder = createBuilder()
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).doesNotContain("⚠️")
        assertThat(text).startsWith("Screen state")
    }

    @Test
    fun `buildObservationText excludes screenshot hint when vision not supported`() {
        val builder = createBuilder(supportsVision = false)
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).doesNotContain("Screenshot attached")
    }

    @Test
    fun `buildObservationText has no Available tools line`() {
        val builder = createBuilder()
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).doesNotContain("Available tools:")
    }

    @Test
    fun `buildObservationText has no What action prompt`() {
        val builder = createBuilder()
        val text = builder.buildObservationText(snapshotWithElements, null, emptyList())

        assertThat(text).doesNotContain("What action should I take")
    }

    @Test
    fun `buildObservationText has no system_reminder XML tags`() {
        val builder = createBuilder()
        val warnings = listOf("⚠️ Some warning")
        val text = builder.buildObservationText(snapshotWithElements, null, warnings)

        assertThat(text).doesNotContain("<system_reminder>")
        assertThat(text).doesNotContain("</system_reminder>")
    }

    // ── Full buildInputItems ────────────────────────────────────────────

    @Test
    fun `buildInputItems produces history then memory then observation`() {
        val historyManager = HistoryManager()
        historyManager.addItem(userIntent("Goal: Test"))
        historyManager.addItem(assistantMessage("I'll test"))

        val state = AgentSessionState()
        state.todos.update(listOf(
            Todo(description = "Test task", status = TodoStatus.PENDING)
        ))

        val builder = PromptBuilder(
            historyManager = historyManager,
            sessionState = state,
            supportsVision = true
        )

        val items = builder.buildInputItems(emptySnapshot, null)

        // 2 history items + 1 memory + 1 observation = 4
        assertThat(items).hasSize(4)
    }

    @Test
    fun `buildInputItems omits memory when empty`() {
        val historyManager = HistoryManager()
        historyManager.addItem(userIntent("Goal: Test"))

        val builder = PromptBuilder(
            historyManager = historyManager,
            sessionState = AgentSessionState(),
            supportsVision = true
        )

        val items = builder.buildInputItems(emptySnapshot, null)

        // 1 history + 0 memory + 1 observation = 2
        assertThat(items).hasSize(2)
    }

    @Test
    fun `buildInputItems includes function call pairs from history`() {
        val historyManager = HistoryManager()
        historyManager.addItem(userIntent("Goal: Test"))
        historyManager.addItem(assistantMessage("Opening app"))
        historyManager.addItem(
            ResponseItem.FunctionCall(
                id = "call-1",
                name = "open_app",
                arguments = JSONObject().put("app_name", "Gmail")
            )
        )
        historyManager.addItem(
            ResponseItem.FunctionCallOutput(
                callId = "call-1",
                content = "Success: Gmail opened"
            )
        )

        val builder = PromptBuilder(
            historyManager = historyManager,
            sessionState = AgentSessionState(),
            supportsVision = true
        )

        val items = builder.buildInputItems(emptySnapshot, null)

        // 4 history items + 0 memory + 1 observation = 5
        assertThat(items).hasSize(5)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun createBuilder(
        historyManager: HistoryManager = HistoryManager(),
        sessionState: AgentSessionState = AgentSessionState(),
        supportsVision: Boolean = true,
        perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
        recentFullScreenTurns: Int = 3
    ): PromptBuilder = PromptBuilder(
        historyManager = historyManager,
        sessionState = sessionState,
        supportsVision = supportsVision,
        perceptionConfig = perceptionConfig,
        recentFullScreenTurns = recentFullScreenTurns
    )

    private fun screenObservation(content: String) = ResponseItem.Message(
        kind = MessageKind.SCREEN_OBSERVATION,
        content = content
    )

    private fun userIntent(content: String) = ResponseItem.Message(
        kind = MessageKind.USER_INTENT,
        content = content
    )

    private fun assistantMessage(content: String) = ResponseItem.Message(
        kind = MessageKind.ASSISTANT_TEXT,
        content = content
    )
}
