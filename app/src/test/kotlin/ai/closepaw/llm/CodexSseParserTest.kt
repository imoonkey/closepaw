package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class CodexSseParserTest {

    // ── SSE line parsing ──────────────────────────────────────────────────

    @Test
    fun `single SSE event is parsed correctly`() {
        val sse = sseBlock("response.created", JSONObject().apply {
            put("response", JSONObject().put("id", "resp-1"))
        })
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).hasSize(1)
        assertThat(events[0].type).isEqualTo("response.created")
    }

    @Test
    fun `multiple SSE events separated by blank lines`() {
        val sse = sseBlock("response.created", JSONObject().apply {
            put("response", JSONObject().put("id", "resp-1"))
        }) + sseBlock("response.done", JSONObject().apply {
            put("type", "response.done")
        })
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).hasSize(2)
    }

    @Test
    fun `DONE marker is skipped`() {
        val sse = "data: [DONE]\n\n"
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).isEmpty()
    }

    @Test
    fun `malformed JSON is silently skipped`() {
        val sse = "data: {not valid json\n\n"
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).isEmpty()
    }

    @Test
    fun `event without type field is skipped`() {
        val sse = "data: {\"data\": \"value\"}\n\n"
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).isEmpty()
    }

    @Test
    fun `trailing data without blank line is still parsed`() {
        val sse = "data: {\"type\": \"response.done\"}"  // No trailing \n\n
        val events = CodexSseParser.parse(sse.byteInputStream()).toList()
        assertThat(events).hasSize(1)
        assertThat(events[0].type).isEqualTo("response.done")
    }

    // ── Event mapping ─────────────────────────────────────────────────────

    @Test
    fun `response_created maps to Created with responseId`() {
        val event = sseEvent("response.created", JSONObject().apply {
            put("response", JSONObject().put("id", "resp-abc"))
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Created::class.java)
        assertThat((result as LLMStreamEvent.Created).responseId).isEqualTo("resp-abc")
    }

    @Test
    fun `response_output_text_delta maps to TextDelta`() {
        val event = sseEvent("response.output_text.delta", JSONObject().apply {
            put("delta", "Hello world")
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.TextDelta::class.java)
        assertThat((result as LLMStreamEvent.TextDelta).delta).isEqualTo("Hello world")
    }

    @Test
    fun `response_done maps to Completed`() {
        val event = sseEvent("response.done", JSONObject())
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)
        assertThat(result).isEqualTo(LLMStreamEvent.Completed)
    }

    @Test
    fun `response_completed maps to Completed`() {
        val event = sseEvent("response.completed", JSONObject())
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)
        assertThat(result).isEqualTo(LLMStreamEvent.Completed)
    }

    @Test
    fun `response_incomplete maps to Failed with reason`() {
        // Fixed: response.incomplete is no longer treated as success.
        val event = sseEvent("response.incomplete", JSONObject().apply {
            put("response", JSONObject().put("incomplete_reason", "max_output_tokens"))
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).contains("incomplete")
        assertThat(result.error).contains("max_output_tokens")
    }

    @Test
    fun `response_failed maps to Failed with error message`() {
        val event = sseEvent("response.failed", JSONObject().apply {
            put("response", JSONObject().apply {
                put("error", JSONObject().put("message", "content filter triggered"))
            })
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("content filter triggered")
    }

    @Test
    fun `response_failed without error object falls back to Unknown error`() {
        val event = sseEvent("response.failed", JSONObject())
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("Unknown error")
    }

    @Test
    fun `error event maps to Failed`() {
        val event = sseEvent("error", JSONObject().apply {
            put("message", "server overloaded")
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("server overloaded")
    }

    @Test
    fun `error event without message uses code`() {
        val event = sseEvent("error", JSONObject().apply {
            put("code", "rate_limit_exceeded")
        })
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)

        assertThat(result).isInstanceOf(LLMStreamEvent.Failed::class.java)
        assertThat((result as LLMStreamEvent.Failed).error).isEqualTo("rate_limit_exceeded")
    }

    @Test
    fun `unknown event type returns null`() {
        val event = sseEvent("response.some_unknown", JSONObject())
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val result = CodexSseParser.mapToStreamEvent(event, accumulator)
        assertThat(result).isNull()
    }

    // ── Tool call accumulation ────────────────────────────────────────────

    @Test
    fun `output_item_added starts tracking function call`() {
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val addedEvent = sseEvent("response.output_item.added", JSONObject().apply {
            put("output_index", 0)
            put("item", JSONObject().apply {
                put("type", "function_call")
                put("call_id", "call-1")
                put("name", "click")
            })
        })

        val result = CodexSseParser.mapToStreamEvent(addedEvent, accumulator)
        assertThat(result).isNull() // accumulated, not emitted yet
    }

    @Test
    fun `full tool call lifecycle produces ToolCallDone`() {
        val accumulator = CodexSseParser.ToolCallAccumulator()

        // 1. Item added
        CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.added", JSONObject().apply {
                put("output_index", 0)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-1")
                    put("name", "click")
                })
            }),
            accumulator
        )

        // 2. Arguments deltas
        CodexSseParser.mapToStreamEvent(
            sseEvent("response.function_call_arguments.delta", JSONObject().apply {
                put("output_index", 0)
                put("delta", "{\"x\":")
            }),
            accumulator
        )
        CodexSseParser.mapToStreamEvent(
            sseEvent("response.function_call_arguments.delta", JSONObject().apply {
                put("output_index", 0)
                put("delta", "100}")
            }),
            accumulator
        )

        // 3. Item done
        val result = CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.done", JSONObject().apply {
                put("output_index", 0)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-1")
                    put("name", "click")
                    put("arguments", "{\"x\":100}")
                })
            }),
            accumulator
        )

        assertThat(result).isInstanceOf(LLMStreamEvent.ToolCallDone::class.java)
        val toolCall = (result as LLMStreamEvent.ToolCallDone).toolCall
        assertThat(toolCall.callId).isEqualTo("call-1")
        assertThat(toolCall.name).isEqualTo("click")
        assertThat(toolCall.arguments).isEqualTo("{\"x\":100}")
    }

    @Test
    fun `output_item_done for non-function_call returns null`() {
        val accumulator = CodexSseParser.ToolCallAccumulator()
        val event = sseEvent("response.output_item.done", JSONObject().apply {
            put("output_index", 0)
            put("item", JSONObject().apply {
                put("type", "message")
                put("content", JSONArray().put(JSONObject().put("text", "hi")))
            })
        })

        val result = CodexSseParser.mapToStreamEvent(event, accumulator)
        assertThat(result).isNull()
    }

    @Test
    fun `interleaved tool-call deltas across two output indices assemble correctly`() {
        val accumulator = CodexSseParser.ToolCallAccumulator()

        // Two function calls added at different output indices
        CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.added", JSONObject().apply {
                put("output_index", 0)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-A")
                    put("name", "click")
                })
            }),
            accumulator
        )
        CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.added", JSONObject().apply {
                put("output_index", 1)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-B")
                    put("name", "type_text")
                })
            }),
            accumulator
        )

        // Interleaved argument deltas: A, B, A, B, A
        val deltaSequence = listOf(
            0 to "{\"x\":",
            1 to "{\"text\":\"he",
            0 to "10",
            1 to "llo\"}",
            0 to ",\"y\":20}",
        )
        for ((idx, delta) in deltaSequence) {
            CodexSseParser.mapToStreamEvent(
                sseEvent("response.function_call_arguments.delta", JSONObject().apply {
                    put("output_index", idx)
                    put("delta", delta)
                }),
                accumulator
            )
        }

        // Done for index 1 first (reverse order)
        val resultB = CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.done", JSONObject().apply {
                put("output_index", 1)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-B")
                    put("name", "type_text")
                })
            }),
            accumulator
        )
        val resultA = CodexSseParser.mapToStreamEvent(
            sseEvent("response.output_item.done", JSONObject().apply {
                put("output_index", 0)
                put("item", JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call-A")
                    put("name", "click")
                })
            }),
            accumulator
        )

        assertThat(resultB).isInstanceOf(LLMStreamEvent.ToolCallDone::class.java)
        val toolB = (resultB as LLMStreamEvent.ToolCallDone).toolCall
        assertThat(toolB.callId).isEqualTo("call-B")
        assertThat(toolB.name).isEqualTo("type_text")
        assertThat(toolB.arguments).isEqualTo("{\"text\":\"hello\"}")

        assertThat(resultA).isInstanceOf(LLMStreamEvent.ToolCallDone::class.java)
        val toolA = (resultA as LLMStreamEvent.ToolCallDone).toolCall
        assertThat(toolA.callId).isEqualTo("call-A")
        assertThat(toolA.name).isEqualTo("click")
        assertThat(toolA.arguments).isEqualTo("{\"x\":10,\"y\":20}")
    }

    @Test
    fun `three parallel tool calls with heavily interleaved chunks assemble correctly`() {
        val accumulator = CodexSseParser.ToolCallAccumulator()

        // Add three function calls at indices 0, 1, 2
        val adds = listOf(
            Triple(0, "call-0", "scroll"),
            Triple(1, "call-1", "click"),
            Triple(2, "call-2", "type_text"),
        )
        for ((idx, callId, name) in adds) {
            CodexSseParser.mapToStreamEvent(
                sseEvent("response.output_item.added", JSONObject().apply {
                    put("output_index", idx)
                    put("item", JSONObject().apply {
                        put("type", "function_call")
                        put("call_id", callId)
                        put("name", name)
                    })
                }),
                accumulator
            )
        }

        // Heavily interleaved deltas across all three indices
        val deltas = listOf(
            2 to "{\"t",
            0 to "{\"dir",
            1 to "{",
            2 to "ext\":",
            1 to "\"id\"",
            0 to "\":\"down\"",
            2 to "\"bye\"",
            1 to ":\"btn-1\"}",
            0 to ",\"n\":3}",
            2 to "}",
        )
        for ((idx, delta) in deltas) {
            CodexSseParser.mapToStreamEvent(
                sseEvent("response.function_call_arguments.delta", JSONObject().apply {
                    put("output_index", idx)
                    put("delta", delta)
                }),
                accumulator
            )
        }

        // Emit done in an out-of-order sequence: 1, 2, 0
        val results = listOf(1, 2, 0).map { idx ->
            val (_, callId, name) = adds[idx]
            CodexSseParser.mapToStreamEvent(
                sseEvent("response.output_item.done", JSONObject().apply {
                    put("output_index", idx)
                    put("item", JSONObject().apply {
                        put("type", "function_call")
                        put("call_id", callId)
                        put("name", name)
                    })
                }),
                accumulator
            )
        }

        val byId = results.map { (it as LLMStreamEvent.ToolCallDone).toolCall }.associateBy { it.callId }

        assertThat(byId["call-0"]!!.name).isEqualTo("scroll")
        assertThat(byId["call-0"]!!.arguments).isEqualTo("{\"dir\":\"down\",\"n\":3}")

        assertThat(byId["call-1"]!!.name).isEqualTo("click")
        assertThat(byId["call-1"]!!.arguments).isEqualTo("{\"id\":\"btn-1\"}")

        assertThat(byId["call-2"]!!.name).isEqualTo("type_text")
        assertThat(byId["call-2"]!!.arguments).isEqualTo("{\"text\":\"bye\"}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sseEvent(type: String, json: JSONObject): CodexSseParser.SseEvent {
        json.put("type", type)
        return CodexSseParser.SseEvent(type, json)
    }

    private fun sseBlock(type: String, json: JSONObject): String {
        json.put("type", type)
        return "data: $json\n\n"
    }
}
