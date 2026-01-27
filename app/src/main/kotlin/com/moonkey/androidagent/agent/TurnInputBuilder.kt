package com.moonkey.androidagent.agent

import android.util.Log
import com.moonkey.androidagent.history.HistoryManager
import com.moonkey.androidagent.history.ResponseItem
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText

class TurnInputBuilder(
    private val historyManager: HistoryManager
) {
    companion object {
        private const val TAG = "TurnInputBuilder"
    }

    /**
     * Build input items from history and current context using proper ResponseInputItem types.
     */
    fun build(userContext: AgentPromptBuilder.UserContext): List<ResponseInputItem> {
        val estimatedTokens = historyManager.estimateTokenCount()
        if (estimatedTokens > 20_000) {
            Log.w(TAG, "History approaching token limit ($estimatedTokens tokens), compressing...")
            historyManager.compress(15_000)
            Log.d(TAG, "After compression: ${historyManager.estimateTokenCount()} tokens")
        }

        val items = mutableListOf<ResponseInputItem>()

        historyManager.forPrompt().forEach { item ->
            when (item) {
                is ResponseItem.Message -> {
                    val role = when (item.role) {
                        "user" -> EasyInputMessage.Role.USER
                        "assistant" -> EasyInputMessage.Role.ASSISTANT
                        else -> null
                    }
                    if (role != null) {
                        items.add(
                            ResponseInputItem.ofEasyInputMessage(
                                EasyInputMessage.builder()
                                    .role(role)
                                    .content(item.content)
                                    .build()
                            )
                        )
                    }
                }
                is ResponseItem.FunctionCall -> {
                    items.add(
                        ResponseInputItem.ofFunctionCall(
                            ResponseFunctionToolCall.builder()
                                .callId(item.id)
                                .name(item.name)
                                .arguments(item.arguments.toString())
                                .build()
                        )
                    )
                }
                is ResponseItem.FunctionCallOutput -> {
                    items.add(
                        ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                .callId(item.callId)
                                .output(item.content)
                                .build()
                        )
                    )
                }
            }
        }

        items.add(buildUserContextItem(userContext))

        return items
    }

    private fun buildUserContextItem(userContext: AgentPromptBuilder.UserContext): ResponseInputItem {
        val builder = EasyInputMessage.builder()
            .role(EasyInputMessage.Role.USER)

        val image = userContext.image
        if (image == null) {
            builder.content(userContext.text)
        } else {
            val contentItems = listOf(
                ResponseInputContent.ofInputText(
                    ResponseInputText.builder()
                        .text(userContext.text)
                        .build()
                ),
                ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder()
                        .detail(ResponseInputImage.Detail.AUTO)
                        .imageUrl(image.toDataUrl())
                        .build()
                )
            )
            builder.contentOfResponseInputMessageContentList(contentItems)
        }

        return ResponseInputItem.ofEasyInputMessage(builder.build())
    }
}
