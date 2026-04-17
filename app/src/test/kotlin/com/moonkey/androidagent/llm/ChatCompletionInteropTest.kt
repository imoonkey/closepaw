package com.moonkey.androidagent.llm

import com.google.common.truth.Truth.assertThat
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import org.junit.Test

/**
 * Unit tests for ChatCompletionInterop.
 *
 * These tests validate conversion of OpenAI Responses-API types to Chat-Completions-API
 * message params, which is the real external contract for provider routing (OpenRouter /
 * chat-style models).
 */
class ChatCompletionInteropTest {

    // ── Assistant text + grouped tool calls ──────────────────────────────

    @Test
    fun `assistant text followed by function calls merges into one assistant message`() {
        val items = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.ASSISTANT)
                    .content("I'll click the button.")
                    .build()
            ),
            ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId("call-1")
                    .name("click")
                    .arguments("{\"x\":100,\"y\":200}")
                    .build()
            ),
            ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId("call-2")
                    .name("screenshot")
                    .arguments("{}")
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)

        assertThat(result).hasSize(1)
        assertThat(result[0].isAssistant()).isTrue()

        val assistant = result[0].asAssistant()
        assertThat(assistant.content().get().asText()).isEqualTo("I'll click the button.")

        val toolCalls = assistant.toolCalls().get()
        assertThat(toolCalls).hasSize(2)

        val first = toolCalls[0].asFunction()
        assertThat(first.id()).isEqualTo("call-1")
        assertThat(first.function().name()).isEqualTo("click")
        assertThat(first.function().arguments()).isEqualTo("{\"x\":100,\"y\":200}")

        val second = toolCalls[1].asFunction()
        assertThat(second.id()).isEqualTo("call-2")
        assertThat(second.function().name()).isEqualTo("screenshot")
        assertThat(second.function().arguments()).isEqualTo("{}")
    }

    @Test
    fun `standalone function calls without preceding assistant text become assistant with tool_calls only`() {
        val items = listOf(
            ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId("call-x")
                    .name("swipe")
                    .arguments("{\"dir\":\"up\"}")
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)

        assertThat(result).hasSize(1)
        val assistant = result[0].asAssistant()
        assertThat(assistant.content().isPresent).isFalse()
        val toolCalls = assistant.toolCalls().get()
        assertThat(toolCalls).hasSize(1)
        assertThat(toolCalls[0].asFunction().function().name()).isEqualTo("swipe")
    }

    // ── Multimodal user content ──────────────────────────────────────────

    @Test
    fun `multimodal user content with text and image converts to content parts`() {
        val imageUrl = "data:image/png;base64,aGVsbG8="
        val content = EasyInputMessage.Content.ofResponseInputMessageContentList(
            listOf(
                ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text("What's on screen?").build()
                ),
                ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.AUTO)
                        .imageUrl(imageUrl)
                        .build()
                )
            )
        )

        val items = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content(content)
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)

        assertThat(result).hasSize(1)
        assertThat(result[0].isUser()).isTrue()

        val user = result[0].asUser()
        val parts = user.content().asArrayOfContentParts()
        assertThat(parts).hasSize(2)

        assertThat(parts[0].isText()).isTrue()
        assertThat(parts[0].asText().text()).isEqualTo("What's on screen?")

        assertThat(parts[1].isImageUrl()).isTrue()
        assertThat(parts[1].asImageUrl().imageUrl().url()).isEqualTo(imageUrl)
    }

    @Test
    fun `plain-text user content converts to string content`() {
        val items = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content("hello world")
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)
        assertThat(result).hasSize(1)
        assertThat(result[0].asUser().content().asText()).isEqualTo("hello world")
    }

    // ── System-role normalization ────────────────────────────────────────

    @Test
    fun `system and developer roles normalize to system message and unknown role falls through to user`() {
        val items = listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.SYSTEM)
                    .content("system prompt A")
                    .build()
            ),
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.DEVELOPER)
                    .content("developer prompt B")
                    .build()
            ),
            // Multimodal system content — extractStringContent joins text parts.
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.SYSTEM)
                    .content(
                        EasyInputMessage.Content.ofResponseInputMessageContentList(
                            listOf(
                                ResponseInputContent.ofInputText(
                                    ResponseInputText.builder().text("line1").build()
                                ),
                                ResponseInputContent.ofInputText(
                                    ResponseInputText.builder().text("line2").build()
                                )
                            )
                        )
                    )
                    .build()
            ),
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.of("custom"))
                    .content("fallback")
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)

        assertThat(result).hasSize(4)
        assertThat(result[0].isSystem()).isTrue()
        assertThat(result[0].asSystem().content().asText()).isEqualTo("system prompt A")

        assertThat(result[1].isSystem()).isTrue()
        assertThat(result[1].asSystem().content().asText()).isEqualTo("developer prompt B")

        assertThat(result[2].isSystem()).isTrue()
        assertThat(result[2].asSystem().content().asText()).isEqualTo("line1\nline2")

        // Unknown role falls through to user message.
        assertThat(result[3].isUser()).isTrue()
        assertThat(result[3].asUser().content().asText()).isEqualTo("fallback")
    }

    @Test
    fun `systemMessage helper wraps a raw prompt as a system param`() {
        val msg = ChatCompletionInterop.systemMessage("you are a helper")
        assertThat(msg.isSystem()).isTrue()
        assertThat(msg.asSystem().content().asText()).isEqualTo("you are a helper")
    }

    // ── Function call input / output ─────────────────────────────────────

    @Test
    fun `function call followed by function call output converts to assistant tool_calls and tool-role message`() {
        val items = listOf(
            ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId("call-42")
                    .name("mobile_action")
                    .arguments("{\"action\":\"click\",\"element_index\":3}")
                    .build()
            ),
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId("call-42")
                    .output("clicked element 3")
                    .build()
            )
        )

        val result = ChatCompletionInterop.convertInputItems(items)

        assertThat(result).hasSize(2)

        // (1) Assistant message with a single tool_call in chat completions format.
        val assistant = result[0].asAssistant()
        val toolCalls = assistant.toolCalls().get()
        assertThat(toolCalls).hasSize(1)
        val fn = toolCalls[0].asFunction()
        assertThat(fn.id()).isEqualTo("call-42")
        assertThat(fn.function().name()).isEqualTo("mobile_action")
        assertThat(fn.function().arguments())
            .isEqualTo("{\"action\":\"click\",\"element_index\":3}")

        // (2) Tool-role message with matching toolCallId and the output string.
        assertThat(result[1].isTool()).isTrue()
        val tool = result[1].asTool()
        assertThat(tool.toolCallId()).isEqualTo("call-42")
        assertThat(tool.content().asText()).isEqualTo("clicked element 3")
    }
}
