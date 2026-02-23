package com.moonkey.androidagent.history.model

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.history.ResponseItem
import org.json.JSONObject
import org.junit.Test

class HistoryItemConverterTest {

    @Test
    fun `round trip preserves all response item fields`() {
        val items =
            listOf(
                ResponseItem.Message(
                    role = "user",
                    content = "Screen state",
                    name = "tester",
                    isScreenObservation = true
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
}

