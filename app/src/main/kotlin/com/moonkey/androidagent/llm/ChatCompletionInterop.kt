package com.moonkey.androidagent.llm

import android.util.Log
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem

/**
 * Converts between OpenAI Responses API types and Chat Completions API types.
 *
 * Contained in one file per the KISS design principle. Callers produce
 * ResponseInputItem lists; ChatCompletionClient converts them internally.
 */
internal object ChatCompletionInterop {

    private const val TAG = "ChatCompletionInterop"

    /**
     * Convert system prompt to a system message.
     */
    fun systemMessage(prompt: String): ChatCompletionMessageParam =
        ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder()
                .content(prompt)
                .build()
        )

    /**
     * Convert ResponseInputItem list to ChatCompletionMessageParam list.
     *
     * Groups adjacent function calls into one assistant message (Chat API requires
     * all tool_calls from a single turn to be in one assistant message).
     */
    fun convertInputItems(
        inputItems: List<ResponseInputItem>
    ): List<ChatCompletionMessageParam> {
        val result = mutableListOf<ChatCompletionMessageParam>()
        var i = 0

        while (i < inputItems.size) {
            val item = inputItems[i]
            when {
                item.isEasyInputMessage() -> {
                    result.add(convertEasyMessage(item.asEasyInputMessage()))
                    i++
                }
                item.isFunctionCall() -> {
                    // Collect consecutive function calls into one assistant message
                    val toolCalls = mutableListOf<ChatCompletionMessageToolCall>()
                    while (i < inputItems.size && inputItems[i].isFunctionCall()) {
                        val fc = inputItems[i].asFunctionCall()
                        toolCalls.add(
                            ChatCompletionMessageToolCall.ofFunction(
                                ChatCompletionMessageFunctionToolCall.builder()
                                    .id(fc.callId())
                                    .function(
                                        ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(fc.name())
                                            .arguments(fc.arguments())
                                            .build()
                                    )
                                    .build()
                            )
                        )
                        i++
                    }
                    result.add(
                        ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder()
                                .toolCalls(toolCalls)
                                .build()
                        )
                    )
                }
                item.isFunctionCallOutput() -> {
                    val fco = item.asFunctionCallOutput()
                    val outputStr = if (fco.output().isString()) {
                        fco.output().asString()
                    } else {
                        fco.output().toString()
                    }
                    result.add(
                        ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(fco.callId())
                                .content(outputStr)
                                .build()
                        )
                    )
                    i++
                }
                else -> {
                    Log.w(TAG, "Skipping unknown ResponseInputItem type: ${item.javaClass.simpleName}")
                    i++
                }
            }
        }
        return result
    }

    /**
     * Convert FunctionTool list to ChatCompletionTool list.
     *
     * FunctionTool.Parameters stores the JSON schema as additional properties
     * (type, properties, required). We transfer them to FunctionParameters.
     */
    fun convertTools(tools: List<FunctionTool>): List<ChatCompletionTool> =
        tools.map { tool ->
            val paramsBuilder = FunctionParameters.builder()
            tool.parameters().ifPresent { params ->
                params._additionalProperties().forEach { (key, value) ->
                    paramsBuilder.putAdditionalProperty(key, value)
                }
            }

            ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                    .function(
                        FunctionDefinition.builder()
                            .name(tool.name())
                            .description(tool.description().orElse(""))
                            .parameters(paramsBuilder.build())
                            .build()
                    )
                    .build()
            )
        }

    // ── Private ─────────────────────────────────────────────────────────

    private fun convertEasyMessage(msg: EasyInputMessage): ChatCompletionMessageParam {
        val content = msg.content()
        return when (msg.role()) {
            EasyInputMessage.Role.USER -> convertUserMessage(content)

            EasyInputMessage.Role.ASSISTANT ->
                ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                        .content(extractStringContent(content))
                        .build()
                )

            EasyInputMessage.Role.DEVELOPER,
            EasyInputMessage.Role.SYSTEM ->
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(extractStringContent(content))
                        .build()
                )

            else ->
                // Unknown roles become user messages
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(extractStringContent(content))
                        .build()
                )
        }
    }

    /**
     * Convert user message content, handling both plain text and multimodal
     * (text + image) content lists.
     */
    private fun convertUserMessage(
        content: EasyInputMessage.Content
    ): ChatCompletionMessageParam {
        // Simple string content
        if (content.isTextInput()) {
            return ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(content.asTextInput())
                    .build()
            )
        }

        // Multimodal content list (text + images)
        if (content.isResponseInputMessageContentList()) {
            val parts = content.asResponseInputMessageContentList().mapNotNull { part ->
                when {
                    part.isInputText() ->
                        ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder()
                                .text(part.asInputText().text())
                                .build()
                        )
                    part.isInputImage() -> {
                        val url = part.asInputImage().imageUrl().orElse(null)
                        if (url == null) {
                            Log.w(TAG, "Skipping image without URL in multimodal message")
                            return@mapNotNull null
                        }
                        ChatCompletionContentPart.ofImageUrl(
                            ChatCompletionContentPartImage.builder()
                                .imageUrl(
                                    ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(url)
                                        .build()
                                )
                                .build()
                        )
                    }
                    else -> null // Skip unsupported content types
                }
            }
            // If all parts were filtered out, fall back to string representation
            if (parts.isEmpty()) {
                return ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(content.toString())
                        .build()
                )
            }
            return ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .contentOfArrayOfContentParts(parts)
                    .build()
            )
        }

        // Fallback
        return ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .content(content.toString())
                .build()
        )
    }

    /**
     * Extract string content from any EasyInputMessage.Content variant.
     */
    private fun extractStringContent(content: EasyInputMessage.Content): String =
        when {
            content.isTextInput() -> content.asTextInput()
            content.isResponseInputMessageContentList() -> {
                content.asResponseInputMessageContentList()
                    .filter { it.isInputText() }
                    .joinToString("\n") { it.asInputText().text() }
            }
            else -> content.toString()
        }
}
