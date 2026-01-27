package com.moonkey.androidagent.llm

import android.content.Context
import android.util.Log
import ai.liquid.leap.Conversation
import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.function.LeapFunctionCall
import ai.liquid.leap.manifest.LeapDownloader
import ai.liquid.leap.manifest.LeapDownloaderConfig
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.ChatMessageContent
import ai.liquid.leap.message.MessageResponse
import com.openai.models.ChatModel
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * LFMLLMClient - Local LLM client using LiquidAI Leap SDK.
 *
 * Uses Leap's function calling, conversation, and model loading APIs directly.
 */
class LFMLLMClient(
    private val context: Context,
    private val config: LocalLLMConfig = LocalLLMConfig()
) : LLMClient() {

    companion object {
        private const val TAG = "LFMLLMClient"
    }

    private val modelMutex = Mutex()
    @Volatile
    private var modelRunner: ModelRunner? = null
    @Volatile
    private var modelLoadingState: ModelLoadingState = ModelLoadingState.NotLoaded

    /**
     * Model loading state for UI feedback.
     */
    sealed interface ModelLoadingState {
        data object NotLoaded : ModelLoadingState
        data class Downloading(val progress: Float) : ModelLoadingState
        data object Loading : ModelLoadingState
        data object Ready : ModelLoadingState
        data class Error(val message: String) : ModelLoadingState
    }

    override fun isReady(): Boolean = modelLoadingState is ModelLoadingState.Ready

    /**
     * Get the current model loading state.
     */
    fun getLoadingState(): ModelLoadingState = modelLoadingState

    /**
     * Load the model (safe to call multiple times).
     */
    suspend fun loadModel(onProgress: ((ModelLoadingState) -> Unit)? = null) {
        modelMutex.withLock {
            if (modelRunner != null) {
                Log.d(TAG, "Model already loaded")
                return
            }

            try {
                withContext(Dispatchers.IO) {
                    modelLoadingState = ModelLoadingState.Downloading(0f)
                    onProgress?.invoke(modelLoadingState)

                    val saveDir = File(context.filesDir, "leap_models").absolutePath
                    val downloader = LeapDownloader(
                        config = LeapDownloaderConfig(saveDir = saveDir)
                    )

                    modelRunner = downloader.loadModel(
                        modelSlug = config.modelSlug,
                        quantizationSlug = config.quantizationSlug,
                        progress = { progressData ->
                            val progress = progressData.progress
                            modelLoadingState = if (progress >= 1f) {
                                ModelLoadingState.Loading
                            } else {
                                ModelLoadingState.Downloading(progress)
                            }
                            onProgress?.invoke(modelLoadingState)
                        }
                    )

                    modelLoadingState = ModelLoadingState.Ready
                    onProgress?.invoke(modelLoadingState)
                    Log.i(TAG, "Model loaded: ${config.modelSlug}/${config.quantizationSlug}")
                }
            } catch (e: Exception) {
                modelLoadingState = ModelLoadingState.Error(e.message ?: "Unknown error")
                onProgress?.invoke(modelLoadingState)
                Log.e(TAG, "Failed to load model", e)
                throw e
            }
        }
    }

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        val runner = getOrLoadModel()
        val (conversation, lastMessage) = buildConversation(runner, systemPrompt, inputItems)
        if (lastMessage == null) {
            return ResponsesResult(
                textContent = null,
                toolCalls = emptyList(),
                responseId = "local_empty"
            )
        }

        registerTools(conversation, tools)

        val responseId = "local_${UUID.randomUUID().toString().take(8)}"
        val textBuffer = StringBuilder()
        val toolCalls = mutableListOf<LLMToolCall>()
        var completeToolCalls: List<LLMToolCall> = emptyList()

        generateResponseWithOptions(conversation, lastMessage).collect { response ->
            when (response) {
                is MessageResponse.Chunk -> textBuffer.append(response.text)
                is MessageResponse.FunctionCalls -> {
                    toolCalls.addAll(convertFunctionCalls(response.functionCalls))
                }
                is MessageResponse.Complete -> {
                    if (toolCalls.isEmpty()) {
                        completeToolCalls = convertFunctionCalls(response.fullMessage.functionCalls)
                    }
                }
                else -> Unit
            }
        }

        val finalToolCalls = if (toolCalls.isNotEmpty()) toolCalls else completeToolCalls
        val textContent = textBuffer.toString().ifBlank { null }

        return ResponsesResult(
            textContent = textContent,
            toolCalls = finalToolCalls,
            responseId = responseId
        )
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<LLMStreamEvent> = flow {
        val runner = getOrLoadModel()
        val (conversation, lastMessage) = buildConversation(runner, systemPrompt, inputItems)
        if (lastMessage == null) {
            emit(LLMStreamEvent.Failed("No message to send"))
            return@flow
        }

        registerTools(conversation, tools)

        val responseId = "local_${UUID.randomUUID().toString().take(8)}"
        emit(LLMStreamEvent.Created(responseId))

        var sawFunctionCalls = false

        try {
            generateResponseWithOptions(conversation, lastMessage).collect { response ->
                when (response) {
                    is MessageResponse.Chunk -> emit(LLMStreamEvent.TextDelta(response.text))
                    is MessageResponse.FunctionCalls -> {
                        sawFunctionCalls = true
                        convertFunctionCalls(response.functionCalls).forEach { toolCall ->
                            emit(LLMStreamEvent.ToolCallDone(toolCall))
                        }
                    }
                    is MessageResponse.Complete -> {
                        if (!sawFunctionCalls) {
                            convertFunctionCalls(response.fullMessage.functionCalls).forEach { toolCall ->
                                emit(LLMStreamEvent.ToolCallDone(toolCall))
                            }
                        }
                    }
                    else -> Unit
                }
            }
            emit(LLMStreamEvent.Completed)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(LLMStreamEvent.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun cleanup() {
        modelMutex.withLock {
            modelRunner?.unload()
            modelRunner = null
            modelLoadingState = ModelLoadingState.NotLoaded
            Log.i(TAG, "LFMLLMClient cleaned up")
        }
    }

    private suspend fun getOrLoadModel(): ModelRunner {
        if (modelRunner != null) {
            return modelRunner!!
        }
        loadModel()
        return modelRunner ?: throw IllegalStateException("Model not loaded")
    }

    private fun buildConversation(
        runner: ModelRunner,
        systemPrompt: String,
        inputItems: List<ResponseInputItem>
    ): Pair<Conversation, ChatMessage?> {
        val messages = convertInputItemsToChatMessages(inputItems)
        val lastMessage = messages.lastOrNull()
        val history = if (messages.isNotEmpty()) messages.dropLast(1) else emptyList()
        val historyWithSystem = listOf(
            ChatMessage(role = ChatMessage.Role.SYSTEM, textContent = systemPrompt)
        ) + history
        val conversation = runner.createConversationFromHistory(historyWithSystem)
        return conversation to lastMessage
    }

    private fun generateResponseWithOptions(
        conversation: Conversation,
        lastMessage: ChatMessage
    ): Flow<MessageResponse> {
        val options = config.generationOptions
        return if (options != null) {
            conversation.generateResponse(lastMessage, options)
        } else {
            conversation.generateResponse(lastMessage)
        }
    }

    private fun convertInputItemsToChatMessages(
        inputItems: List<ResponseInputItem>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        for (item in inputItems) {
            when {
                item.isEasyInputMessage() -> {
                    val message = item.asEasyInputMessage()
                    val role = when (message.role().toString().lowercase()) {
                        "user" -> ChatMessage.Role.USER
                        "assistant" -> ChatMessage.Role.ASSISTANT
                        else -> null
                    }
                    val content = extractMessageContent(message.content())
                    if (role != null && content.isNotBlank()) {
                        messages.add(ChatMessage(role = role, textContent = content))
                    }
                }
                item.isFunctionCall() -> {
                    val call = item.asFunctionCall()
                    val args = LeapJsonAdapter.parseJsonArguments(call.arguments())
                    if (args != null) {
                        messages.add(
                            ChatMessage(
                                role = ChatMessage.Role.ASSISTANT,
                                content = listOf(ChatMessageContent.Text("")),
                                functionCalls = listOf(
                                    LeapFunctionCall(call.name(), args)
                                )
                            )
                        )
                    }
                }
                item.isFunctionCallOutput() -> {
                    val output = item.asFunctionCallOutput()
                    val content = output.output().toString()
                    if (content.isNotBlank()) {
                        messages.add(ChatMessage(role = ChatMessage.Role.TOOL, textContent = content))
                    }
                }
            }
        }
        return messages
    }

    private fun extractMessageContent(content: Any): String {
        return when (content) {
            is String -> content
            is List<*> -> {
                content.mapNotNull { part ->
                    when (part) {
                        is String -> part
                        else -> part?.toString()
                    }
                }.joinToString(" ")
            }
            else -> content.toString()
        }
    }

    private fun registerTools(conversation: Conversation, tools: List<FunctionTool>) {
        tools.forEach { tool ->
            conversation.registerFunction(LeapToolSchemaAdapter.toLeapFunction(tool))
        }
        Log.d(TAG, "Registered ${tools.size} tool(s)")
    }

    private fun convertFunctionCalls(calls: List<LeapFunctionCall>?): List<LLMToolCall> {
        if (calls.isNullOrEmpty()) return emptyList()
        return calls.map { call ->
            LLMToolCall(
                callId = "call_${UUID.randomUUID().toString().take(8)}",
                name = call.name,
                arguments = LeapJsonAdapter.toJsonString(call.arguments)
            )
        }
    }
}

/**
 * Configuration for local LLM.
 *
 * Available models from Leap Model Library (leap.liquid.ai/models):
 * - "LFM2.5-1.2B-Instruct" / "Q4_K_M" (~731MB, recommended for tool-calling)
 * - "LFM2.5-1.2B-Instruct" / "Q5_K_M" (~843MB, higher quality)
 * - "lfm2-350m" / "lfm2-350m-20250710-8da4w" (~400MB, smallest, less capable)
 */
data class LocalLLMConfig(
    /** Model slug (e.g., "LFM2.5-1.2B-Instruct") */
    val modelSlug: String = "LFM2.5-1.2B-Instruct",
    /** Quantization slug (e.g., "Q4_K_M") - must match Leap Model Library */
    val quantizationSlug: String = "Q4_K_M",
    /**
     * Optional generation options (e.g., functionCallParser, temperature).
     * When null, the SDK defaults from the model manifest are used.
     */
    val generationOptions: GenerationOptions? = null
)
