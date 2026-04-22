package ai.closepaw.history

import com.google.common.truth.Truth.assertThat
import ai.closepaw.history.model.ContentBlockRecord
import org.junit.Test

class AgentMessageBufferTest {

    @Test
    fun `buildPartialSnapshot returns null when empty`() {
        val buffer = AgentMessageBuffer()

        assertThat(buffer.buildPartialSnapshot()).isNull()
    }

    @Test
    fun `start and appendText produces text block`() {
        val buffer = AgentMessageBuffer()
        buffer.start("msg-1", timestamp = 100L)

        buffer.appendText("hello")

        val snapshot = buffer.buildPartialSnapshot()
        assertThat(snapshot).isNotNull()
        assertThat(snapshot?.id).isEqualTo("msg-1")
        assertThat(snapshot?.blocks).containsExactly(ContentBlockRecord.Text("hello"))
    }

    @Test
    fun `recordAction finalizes text before action`() {
        val buffer = AgentMessageBuffer()
        buffer.start("msg-2", timestamp = 200L)
        buffer.appendText("text")

        buffer.recordAction(
            ContentBlockRecord.Action(
                id = "a1",
                toolName = "mobile_action",
                description = "Click",
                state = "executing",
                resultSummary = null
            )
        )

        val snapshot = buffer.finalizeSnapshot()
        assertThat(snapshot?.blocks).containsExactly(
            ContentBlockRecord.Text("text"),
            ContentBlockRecord.Action(
                id = "a1",
                toolName = "mobile_action",
                description = "Click",
                state = "executing",
                resultSummary = null
            )
        )
    }

    @Test
    fun `updateActionState mutates existing action`() {
        val buffer = AgentMessageBuffer()
        buffer.start("msg-3", timestamp = 300L)
        buffer.recordAction(
            ContentBlockRecord.Action(
                id = "a2",
                toolName = "open_app",
                description = "Open app",
                state = "executing",
                resultSummary = null
            )
        )

        buffer.updateActionState("a2", "success", "ok")

        val snapshot = buffer.finalizeSnapshot()
        assertThat(snapshot?.blocks).containsExactly(
            ContentBlockRecord.Action(
                id = "a2",
                toolName = "open_app",
                description = "Open app",
                state = "success",
                resultSummary = "ok"
            )
        )
    }

    @Test
    fun `recordFinalAnswer drains streamed text buffer to avoid duplication`() {
        // Tool-less text completion: deltas stream the answer into textBuffer,
        // then TaskCompleted resurfaces it as the FinalText. Without draining,
        // the snapshot would contain Text("Done") + FinalText("Done"). uxfb-3
        // codex final review HIGH.
        val buffer = AgentMessageBuffer()
        buffer.start("msg", timestamp = 1L)
        buffer.appendText("Hello, here is the answer.")
        buffer.recordFinalAnswer("Hello, here is the answer.")

        val snapshot = buffer.finalizeSnapshot()
        assertThat(snapshot?.blocks).containsExactly(
            ContentBlockRecord.FinalText("Hello, here is the answer.")
        )
    }

    @Test
    fun `recordFinalAnswer with mismatched answer drops streamed text and appends final`() {
        // complete_task path: arguments.answer can differ from any streamed prose.
        val buffer = AgentMessageBuffer()
        buffer.start("msg", timestamp = 1L)
        buffer.appendText("Trying to open the app...")
        buffer.recordFinalAnswer("Done — opened Settings.")

        val snapshot = buffer.finalizeSnapshot()
        assertThat(snapshot?.blocks).containsExactly(
            ContentBlockRecord.FinalText("Done — opened Settings.")
        )
    }
}
