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
}
