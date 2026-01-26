package com.moonkey.androidagent.llm

import android.content.Context
import android.util.Log
import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.LeapModelLoadingException
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.function.LeapFunction
import ai.liquid.leap.function.LeapFunctionParameter
import ai.liquid.leap.function.LeapFunctionParameterType
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.MessageResponse
import com.openai.models.ChatModel
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * LFMLLMClient - Local LLM client using LiquidAI Leap SDK.
 * 
 * Features:
 * - Local on-device inference with LFM models
 * - Function/tool calling support
 * - Streaming response generation
 * - Automatic model download and caching
 * 
 * Converts between OpenAI types (used by interface) and Leap SDK types internally.
 * 
 * @param context Android context for model downloading
 * @param config Local LLM configuration (model slug, quantization)
 */
class LFMLLMClient(
    private val context: Context,
    private val config: LocalLLMConfig = LocalLLMConfig()
) : LLMClient() {
    
    companion object {
        private const val TAG = "LFMLLMClient"
    }
    
    private var modelRunner: ModelRunner? = null
    private var conversation: Conversation? = null
    private var currentSystemPrompt: String? = null
    private val modelMutex = Mutex()
    
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
     * Load the model. Call this before using chatWithTools.
     * 
     * This can be called multiple times safely - it will skip if already loaded.
     * 
     * @param onProgress Progress callback for UI updates
     */
    suspend fun loadModel(onProgress: ((ModelLoadingState) -> Unit)? = null) {
        modelMutex.withLock {
            if (modelRunner != null) {
                Log.d(TAG, "Model already loaded, skipping")
                return
            }
            
            try {
                withContext(Dispatchers.IO) {
                    // Resolve the model
                    val modelToUse = LeapDownloadableModel.resolve(config.modelSlug, config.quantizationSlug)
                        ?: throw LeapModelLoadingException("Model ${config.modelSlug}/${config.quantizationSlug} not found in Leap Model Library")
                    
                    Log.d(TAG, "Resolved model: ${modelToUse.modelSlug}/${modelToUse.quantizationSlug}")
                    
                    val modelDownloader = LeapModelDownloader(context)
                    
                    // Request download (idempotent if already downloaded)
                    modelDownloader.requestDownloadModel(modelToUse)
                    
                    // Wait for download to complete
                    var isModelAvailable = false
                    while (!isModelAvailable) {
                        val status = modelDownloader.queryStatus(modelToUse)
                        when (status) {
                            LeapModelDownloader.ModelDownloadStatus.NotOnLocal -> {
                                modelLoadingState = ModelLoadingState.Downloading(0f)
                                onProgress?.invoke(modelLoadingState)
                                Log.d(TAG, "Model not downloaded, waiting...")
                            }
                            is LeapModelDownloader.ModelDownloadStatus.DownloadInProgress -> {
                                val progress = if (status.totalSizeInBytes > 0) {
                                    status.downloadedSizeInBytes.toFloat() / status.totalSizeInBytes
                                } else {
                                    0f
                                }
                                modelLoadingState = ModelLoadingState.Downloading(progress)
                                onProgress?.invoke(modelLoadingState)
                                Log.d(TAG, "Downloading model: ${(progress * 100).toInt()}%")
                            }
                            is LeapModelDownloader.ModelDownloadStatus.Downloaded -> {
                                isModelAvailable = true
                                Log.d(TAG, "Model downloaded")
                            }
                        }
                        if (!isModelAvailable) {
                            delay(500)
                        }
                    }
                    
                    // Load the model
                    modelLoadingState = ModelLoadingState.Loading
                    onProgress?.invoke(modelLoadingState)
                    
                    val modelFile = modelDownloader.getModelFile(modelToUse)
                    Log.d(TAG, "Loading model from: ${modelFile.path}")
                    
                    modelRunner = LeapClient.loadModel(modelFile.path)
                    
                    modelLoadingState = ModelLoadingState.Ready
                    onProgress?.invoke(modelLoadingState)
                    Log.i(TAG, "Model loaded successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                modelLoadingState = ModelLoadingState.Error(e.message ?: "Unknown error")
                onProgress?.invoke(modelLoadingState)
                throw e
            }
        }
    }
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel // Ignored for local model
    ): ResponsesResult {
        // Ensure model is loaded
        if (modelRunner == null) {
            loadModel()
        }
        
        val runner = modelRunner ?: throw IllegalStateException("Model not loaded")
        
        return withContext(Dispatchers.Default) {
            // Get or create conversation
            val conv = getOrCreateConversation(runner, systemPrompt)
            
            // Register tools
            registerTools(conv, tools)
            
            // Convert input items to the last user message
            val lastUserMessage = extractLastUserMessage(inputItems)
            
            Log.d(TAG, "Calling local LLM with message: ${lastUserMessage.take(100)}...")
            
            // Collect streaming response into a complete result
            val textContent = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()
            
            val chatMessage = ChatMessage(role = ChatMessage.Role.USER, textContent = lastUserMessage)
            
            conv.generateResponse(chatMessage).collect { response ->
                when (response) {
                    is MessageResponse.Chunk -> {
                        textContent.append(response.text)
                    }
                    is MessageResponse.FunctionCalls -> {
                        response.functionCalls.forEach { call ->
                            toolCalls.add(LLMToolCall(
                                callId = "call_${UUID.randomUUID().toString().take(8)}",
                                name = call.name,
                                arguments = convertArgumentsToJson(call.arguments)
                            ))
                            Log.d(TAG, "Tool call: ${call.name}")
                        }
                    }
                    else -> {
                        // Ignore other response types (ReasoningChunk, etc.)
                    }
                }
            }
            
            val result = ResponsesResult(
                textContent = textContent.toString().takeIf { it.isNotEmpty() },
                toolCalls = toolCalls,
                responseId = "local_${UUID.randomUUID().toString().take(8)}"
            )
            
            Log.d(TAG, "Local LLM result: ${result.textContent?.take(200)}..., ${result.toolCalls.size} tool calls")
            result
        }
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel // Ignored for local model
    ): Flow<LLMStreamEvent> = flow {
        // Ensure model is loaded
        if (modelRunner == null) {
            loadModel()
        }
        
        val runner = modelRunner ?: throw IllegalStateException("Model not loaded")
        
        // Get or create conversation
        val conv = getOrCreateConversation(runner, systemPrompt)
        
        // Register tools
        registerTools(conv, tools)
        
        // Convert input items to the last user message
        val lastUserMessage = extractLastUserMessage(inputItems)
        
        Log.d(TAG, "Starting streaming with local LLM, message: ${lastUserMessage.take(100)}...")
        
        // Emit created event
        val responseId = "local_${UUID.randomUUID().toString().take(8)}"
        emit(LLMStreamEvent.Created(responseId))
        
        val chatMessage = ChatMessage(role = ChatMessage.Role.USER, textContent = lastUserMessage)
        
        try {
            conv.generateResponse(chatMessage).collect { response ->
                when (response) {
                    is MessageResponse.Chunk -> {
                        emit(LLMStreamEvent.TextDelta(response.text))
                    }
                    is MessageResponse.FunctionCalls -> {
                        response.functionCalls.forEach { call ->
                            val toolCall = LLMToolCall(
                                callId = "call_${UUID.randomUUID().toString().take(8)}",
                                name = call.name,
                                arguments = convertArgumentsToJson(call.arguments)
                            )
                            emit(LLMStreamEvent.ToolCallDone(toolCall))
                            Log.d(TAG, "Streaming tool call: ${call.name}")
                        }
                    }
                    else -> {
                        // Ignore other response types
                    }
                }
            }
            
            emit(LLMStreamEvent.Completed)
            Log.d(TAG, "Streaming completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(LLMStreamEvent.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.Default)
    
    override suspend fun cleanup() {
        modelMutex.withLock {
            Log.d(TAG, "Cleaning up LFMLLMClient...")
            conversation = null
            currentSystemPrompt = null
            modelRunner?.unload()
            modelRunner = null
            modelLoadingState = ModelLoadingState.NotLoaded
            Log.i(TAG, "LFMLLMClient cleaned up")
        }
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Get existing conversation or create a new one if system prompt changed.
     */
    private fun getOrCreateConversation(runner: ModelRunner, systemPrompt: String): Conversation {
        val existing = conversation
        return if (existing != null && currentSystemPrompt == systemPrompt) {
            existing
        } else {
            Log.d(TAG, "Creating new conversation with system prompt")
            currentSystemPrompt = systemPrompt
            runner.createConversation(systemPrompt).also {
                conversation = it
            }
        }
    }
    
    /**
     * Register tools as LeapFunctions on the conversation.
     */
    private fun registerTools(conv: Conversation, tools: List<FunctionTool>) {
        tools.forEach { tool ->
            val leapFunction = convertToLeapFunction(tool)
            conv.registerFunction(leapFunction)
        }
        Log.d(TAG, "Registered ${tools.size} tools")
    }
    
    /**
     * Convert OpenAI FunctionTool to Leap SDK LeapFunction.
     * 
     * Note: Parameter extraction from FunctionTool is challenging because the OpenAI SDK
     * performs internal validation. We use a defensive approach to handle this.
     */
    private fun convertToLeapFunction(tool: FunctionTool): LeapFunction {
        val name = tool.name()
        val description = tool.description().orElse("")
        val parameters = mutableListOf<LeapFunctionParameter>()
        
        // Try to parse parameters - the OpenAI SDK's parameters() method can throw
        // OpenAIInvalidDataException during internal validation. We catch all exceptions
        // and fall back to an empty parameter list (the model can still call the function).
        try {
            // Access parameters via Optional
            val paramsOptional = tool.parameters()
            if (paramsOptional.isPresent) {
                val params = paramsOptional.get()
                // Convert JsonValue to string and parse as JSON
                val schemaJson = JSONObject(params.toString())
                val properties = schemaJson.optJSONObject("properties")
                
                properties?.keys()?.forEach { paramName ->
                    val paramSchema = properties.optJSONObject(paramName)
                    val paramType = inferLeapParameterTypeFromJson(paramSchema)
                    val paramDescription = paramSchema?.optString("description", "") ?: ""
                    parameters.add(LeapFunctionParameter(
                        name = paramName,
                        type = paramType,
                        description = paramDescription
                    ))
                }
            }
        } catch (e: Exception) {
            // OpenAI SDK may throw OpenAIInvalidDataException during parameter access
            // Log and continue with empty parameters - the function can still be called
            Log.w(TAG, "Could not parse parameters for $name (will use empty params): ${e.message}")
        }
        
        return LeapFunction(name, description, parameters)
    }
    
    /**
     * Infer LeapFunctionParameterType from JSON schema object.
     */
    private fun inferLeapParameterTypeFromJson(schema: JSONObject?): LeapFunctionParameterType {
        if (schema == null) return LeapFunctionParameterType.String()
        
        return when (schema.optString("type", "string")) {
            "string" -> LeapFunctionParameterType.String()
            "integer", "number" -> LeapFunctionParameterType.Number()
            "boolean" -> LeapFunctionParameterType.Boolean()
            "array" -> {
                val items = schema.optJSONObject("items")
                val itemType = inferLeapParameterTypeFromJson(items)
                LeapFunctionParameterType.Array(itemType)
            }
            else -> LeapFunctionParameterType.String()
        }
    }
    
    /**
     * Extract the last user message from input items.
     * 
     * Leap SDK uses a simpler conversation model - we extract the last user message
     * and rely on the conversation history managed by the SDK.
     */
    private fun extractLastUserMessage(inputItems: List<ResponseInputItem>): String {
        // Find the last user message
        for (item in inputItems.reversed()) {
            if (item.isEasyInputMessage()) {
                val message = item.asEasyInputMessage()
                if (message.role().toString().equals("USER", ignoreCase = true)) {
                    // Handle content which can be string or list
                    return message.content().toString()
                }
            }
        }
        
        // Fallback: concatenate all user messages
        val userMessages = inputItems.mapNotNull { item ->
            if (item.isEasyInputMessage()) {
                val message = item.asEasyInputMessage()
                if (message.role().toString().equals("USER", ignoreCase = true)) {
                    message.content().toString()
                } else null
            } else null
        }
        
        return userMessages.lastOrNull() ?: ""
    }
    
    /**
     * Convert Leap SDK function arguments map to JSON string.
     */
    private fun convertArgumentsToJson(arguments: Map<String, Any?>): String {
        return try {
            JSONObject(arguments).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert arguments to JSON", e)
            "{}"
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
    val quantizationSlug: String = "Q4_K_M"
)
