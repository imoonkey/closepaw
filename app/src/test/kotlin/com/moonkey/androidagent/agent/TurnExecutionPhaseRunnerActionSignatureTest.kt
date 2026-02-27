package com.moonkey.androidagent.agent

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class TurnExecutionPhaseRunnerActionSignatureTest {
    @Test
    fun `selectActionSignatureForNextTurn prefers screen action over cognitive tool`() {
        val calls =
                listOf(
                        ToolCallRequest(id = "memo", name = "scratchpad", arguments = JSONObject()),
                        ToolCallRequest(
                                id = "tap",
                                name = "mobile_action",
                                arguments = JSONObject("""{"action":"click"}""")
                        )
                )

        val signature = selectActionSignatureForNextTurn(calls)

        assertThat(signature).isEqualTo("mobile_action:click")
    }

    @Test
    fun `selectActionSignatureForNextTurn falls back to first tool when no screen action`() {
        val calls =
                listOf(
                        ToolCallRequest(id = "todo", name = "write_todos", arguments = JSONObject()),
                        ToolCallRequest(id = "memo", name = "scratchpad", arguments = JSONObject())
                )

        val signature = selectActionSignatureForNextTurn(calls)

        assertThat(signature).isEqualTo("write_todos")
    }

    @Test
    fun `classifyActionSignature includes click target to avoid over-blocking`() {
        val call =
                ToolCallRequest(
                        id = "tap",
                        name = "mobile_action",
                        arguments = JSONObject("""{"action":"click","element_index":12}""")
                )

        val signature = classifyActionSignature(call)

        assertThat(signature).isEqualTo("mobile_action:click:idx=12")
    }
}
