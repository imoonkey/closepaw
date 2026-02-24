package com.moonkey.androidagent.history.model

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.MessageKind
import com.moonkey.androidagent.history.ResponseItem
import org.json.JSONObject
import org.junit.Test

class HistoryItemConverterTest {

    @Test
    fun `round trip preserves all response item fields`() {
        val items =
            listOf(
                ResponseItem.Message(
                    kind = MessageKind.SCREEN_OBSERVATION,
                    content = "Screen state",
                    name = "tester"
                ),
                ResponseItem.FunctionCall(
                    id = "call_1",
                    name = "mobile_action",
                    arguments = JSONObject("""{"action":"click","element_index":3}""")
                ),
                ResponseItem.FunctionCallOutput(
                    callId = "call_1",
                    content = "Success",
                    success = true,
                    truncated = false
                )
            )

        val records = HistoryItemConverter.toRecords(items)
        val restored = HistoryItemConverter.fromRecords(records)

        assertThat(restored).hasSize(items.size)
        assertThat(restored[0]).isEqualTo(items[0])
        val call = restored[1] as ResponseItem.FunctionCall
        assertThat(call.id).isEqualTo("call_1")
        assertThat(call.name).isEqualTo("mobile_action")
        assertThat(call.arguments.toString())
            .isEqualTo(JSONObject("""{"action":"click","element_index":3}""").toString())
        assertThat(restored[2]).isEqualTo(items[2])
    }

    @Test
    fun `fromRecord migrates legacy role plus isScreenObservation format`() {
        // Old checkpoint format: role + isScreenObservation, no kind field
        val legacyScreen = PersistedHistoryItem.Message(
            role = "user", content = "Screen state", isScreenObservation = true
        )
        val legacyUser = PersistedHistoryItem.Message(
            role = "user", content = "open settings"
        )
        val legacyAssistant = PersistedHistoryItem.Message(
            role = "assistant", content = "done"
        )

        val screen = HistoryItemConverter.fromRecord(legacyScreen) as ResponseItem.Message
        assertThat(screen.kind).isEqualTo(MessageKind.SCREEN_OBSERVATION)

        val user = HistoryItemConverter.fromRecord(legacyUser) as ResponseItem.Message
        assertThat(user.kind).isEqualTo(MessageKind.USER_INTENT)

        val assistant = HistoryItemConverter.fromRecord(legacyAssistant) as ResponseItem.Message
        assertThat(assistant.kind).isEqualTo(MessageKind.ASSISTANT_TEXT)
    }

    @Test
    fun `fromRecord falls back gracefully on unknown kind string`() {
        val unknown = PersistedHistoryItem.Message(kind = "UNKNOWN_FUTURE_KIND", content = "test")
        val restored = HistoryItemConverter.fromRecord(unknown) as ResponseItem.Message
        assertThat(restored.kind).isEqualTo(MessageKind.USER_INTENT)
    }
}
