package com.moonkey.androidagent.llm.local

import android.content.Context
import android.util.Log
import com.moonkey.androidagent.llm.LLMClient
import com.moonkey.androidagent.llm.LLMStreamEvent
import com.moonkey.androidagent.llm.LLMToolCall
import com.moonkey.androidagent.llm.ResponsesResult
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.FunctionTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * LFMLLMClient - Local LLM implementation using llama.android (llama.cpp).
 * 
 * Provides on-device LLM inference for the Android Agent using LFM2.5-Thinking:
 * - Privacy: No data leaves the device
 * - Offline operation: Works without internet
 * - Low latency: No network round-trip
 * - Cost-free: No API usage fees
 * 
 * ## Model
 * 
 * Uses LFM2.5-1.2B-Thinking (LiquidAI) in GGUF format:
 * - Optimized for on-device reasoning
 * - ~1GB memory footprint (Q4_K_M quantization)
 * - Supports thinking/reasoning output
 * - Native tool calling via special tokens
 * 
 * ## LFM2.5 Chat Template
 * 
 * Based on https://docs.liquid.ai/lfm/key-concepts/chat-template:
 * ```
 * <|startoftext|><|im_start|>system
 * {system_prompt}<|im_end|>
 * <|im_start|>user
 * {user_message}<|im_end|>
 * <|im_start|>assistant
 * ```
 * 
 * ## Tool Calling
 * 
 * Based on https://docs.liquid.ai/lfm/key-concepts/tool-use:
 * - Tools defined as JSON in system prompt
 * - Tool calls wrapped in `<|tool_call_start|>` and `<|tool_call_end|>`
 * - Tool results use "tool" role
 * 
 * ## Integration Requirements
 * 
 * This class requires llama.android library to be integrated:
 * 1. Add llama.android dependency in build.gradle.kts
 * 2. Sync Gradle and build
 * 3. Download model to app's files/models directory
 */
class LFMLLMClient(
    private val context: Context,
    private val config: LocalLLMConfig = LocalLLMConfig()
) : LLMClient {
    
    companion object {
        private const val TAG = "LFMLLMClient"
        private const val MODELS_DIR = "models"
        
        // LFM2.5 special tokens
        private const val START_OF_TEXT = "<|startoftext|>"
        private const val IM_START = "<|im_start|>"
        private const val IM_END = "<|im_end|>"
        private const val TOOL_CALL_START = "<|tool_call_start|>"
        private const val TOOL_CALL_END = "<|tool_call_end|>"
    }
    
    // State tracking
    private var isModelLoaded = false
    private var isInitialized = false
    
    // llama.android engine instance (lazy loaded via reflection)
    private var llamaEngine: Any? = null
    
    // Model path
    private val modelPath: File
        get() = File(File(context.filesDir, MODELS_DIR), config.modelFileName)
    
    /**
     * Check if the local model is downloaded and ready.
     */
    fun isModelDownloaded(): Boolean {
        return modelPath.exists() && modelPath.length() > 0
    }
    
    /**
     * Check if the model is loaded and ready for inference.
     */
    fun isReady(): Boolean {
        return isModelLoaded && isInitialized
    }
    
    /**
     * Initialize and load the local model.
     * 
     * Must be called before using chatWithTools/chatWithToolsStreaming.
     * This is a potentially long-running operation that should be called
     * during app startup or awaited before first inference.
     * 
     * @return Result indicating success or failure with error details
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing LFMLLMClient...")
            
            // Check if model file exists
            if (!modelPath.exists()) {
                Log.e(TAG, "Model file not found: ${modelPath.absolutePath}")
                return@withContext Result.failure(ModelNotDownloadedException(
                    "Model not found: ${config.modelFileName}. Please download the model first."
                ))
            }
            
            Log.d(TAG, "Model file found: ${modelPath.absolutePath} (${modelPath.length() / 1024 / 1024}MB)")
            
            // Check if llama.android is integrated
            val llamaAvailable = checkLlamaAndroidAvailable()
            if (!llamaAvailable) {
                return@withContext Result.failure(LocalLLMException.NotIntegratedException(
                    "llama.android library is not integrated. Add the dependency to build.gradle.kts " +
                    "and rebuild the project."
                ))
            }
            
            // Actual initialization (when library is available)
            performModelLoad()
            
            isInitialized = true
            isModelLoaded = true
            
            Log.i(TAG, "LFMLLMClient initialized successfully")
            Result.success(Unit)
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory loading model", e)
            Result.failure(LocalLLMException.OutOfMemoryException(
                "Not enough memory to load model. Free up memory and try again."
            ))
        } catch (e: LocalLLMException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local model", e)
            Result.failure(LocalLLMException.InitializationException(
                "Failed to initialize: ${e.message}", e
            ))
        }
    }
    
    /**
     * Check if llama.android library is available.
     */
    private fun checkLlamaAndroidAvailable(): Boolean {
        return try {
            // Try to load the llama.android class
            // The actual class name may vary depending on the llama.android version
            Class.forName("android.llama.cpp.LLamaAndroid")
            true
        } catch (e: ClassNotFoundException) {
            try {
                // Try alternative class name
                Class.forName("com.llama.cpp.LLamaAndroid")
                true
            } catch (e2: ClassNotFoundException) {
                Log.w(TAG, "llama.android not found - library not integrated")
                false
            }
        }
    }
    
    /**
     * Perform actual model loading via llama.android.
     * 
     * This method uses reflection to call llama.android APIs so the code
     * compiles even without the library. When the library is integrated,
     * this can be replaced with direct calls.
     */
    private suspend fun performModelLoad() {
        // Try to find the llama.android entry point
        val llamaClass = try {
            Class.forName("android.llama.cpp.LLamaAndroid")
        } catch (e: ClassNotFoundException) {
            Class.forName("com.llama.cpp.LLamaAndroid")
        }
        
        // Create instance or get singleton
        val instanceMethod = try {
            llamaClass.getMethod("getInstance")
        } catch (e: NoSuchMethodException) {
            null
        }
        
        llamaEngine = if (instanceMethod != null) {
            instanceMethod.invoke(null)
        } else {
            llamaClass.getDeclaredConstructor().newInstance()
        }
        
        // Load the model
        val loadMethod = llamaClass.getMethod("load", String::class.java)
        loadMethod.invoke(llamaEngine, modelPath.absolutePath)
        
        Log.d(TAG, "Model loaded via llama.android")
    }
    
    /**
     * Release model resources.
     * 
     * Call when the model is no longer needed (e.g., app backgrounded)
     * to free up memory. The model can be re-initialized later.
     */
    fun release() {
        Log.i(TAG, "Releasing local model resources...")
        
        if (llamaEngine != null) {
            try {
                val engineClass = llamaEngine!!.javaClass
                val unloadMethod = try {
                    engineClass.getMethod("unload")
                } catch (e: NoSuchMethodException) {
                    engineClass.getMethod("release")
                }
                unloadMethod.invoke(llamaEngine)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing model: ${e.message}")
            }
        }
        
        llamaEngine = null
        isModelLoaded = false
        Log.i(TAG, "Local model released")
    }
    
    /**
     * Full cleanup when app is closing.
     */
    fun destroy() {
        Log.i(TAG, "Destroying LFMLLMClient...")
        release()
        isInitialized = false
        Log.i(TAG, "LFMLLMClient destroyed")
    }
    
    // ========== LLMClient Interface Implementation ==========
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel  // Ignored for local model
    ): ResponsesResult = withContext(Dispatchers.IO) {
        ensureReady()
        
        Log.d(TAG, "chatWithTools: ${inputItems.size} items, ${tools.size} tools")
        
        // Build LFM2.5 chat template prompt
        val prompt = LFMChatTemplate.buildPrompt(systemPrompt, inputItems, tools)
        
        Log.v(TAG, "Built prompt (${prompt.length} chars)")
        
        // Generate response
        val output = generateText(prompt)
        
        // Parse output using LFM-aware parser
        val parseResult = LFMOutputParser.parse(output, config.isThinkingModel)
        
        // Convert to ResponsesResult
        convertToResponsesResult(parseResult, output)
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel  // Ignored for local model
    ): Flow<LLMStreamEvent> = flow {
        try {
            ensureReady()
            
            val responseId = "local_${UUID.randomUUID()}"
            emit(LLMStreamEvent.Created(responseId))
            
            Log.d(TAG, "chatWithToolsStreaming: ${inputItems.size} items, ${tools.size} tools")
            
            // Build LFM2.5 chat template prompt
            val prompt = LFMChatTemplate.buildPrompt(systemPrompt, inputItems, tools)
            
            // Stream tokens and parse in real-time
            val buffer = StringBuilder()
            var thinkingBuffer = StringBuilder()
            var inThinking = false
            var inToolCall = false
            var toolCallBuffer = StringBuilder()
            
            generateTextStreaming(prompt).collect { token ->
                buffer.append(token)
                
                // Check for thinking content (text before tool call or JSON)
                if (config.isThinkingModel && !inToolCall) {
                    // Check if we're entering a tool call
                    if (buffer.toString().contains(TOOL_CALL_START)) {
                        inToolCall = true
                        val parts = buffer.toString().split(TOOL_CALL_START, limit = 2)
                        if (parts[0].isNotBlank()) {
                            thinkingBuffer.append(parts[0])
                            emit(LLMStreamEvent.ThinkingDelta(parts[0], isComplete = false))
                        }
                        if (parts.size > 1) {
                            toolCallBuffer.append(parts[1])
                        }
                    } else if (buffer.toString().contains("{\"")) {
                        // JSON starting - emit thinking content first
                        val jsonStart = buffer.indexOf("{\"")
                        if (jsonStart > 0) {
                            val thinking = buffer.substring(0, jsonStart).trim()
                            if (thinking.isNotBlank()) {
                                emit(LLMStreamEvent.ThinkingDelta(thinking, isComplete = true))
                            }
                        }
                    }
                }
                
                // Accumulate tool call content
                if (inToolCall) {
                    toolCallBuffer.append(token)
                    
                    // Check for tool call end
                    if (toolCallBuffer.contains(TOOL_CALL_END)) {
                        val toolCallContent = toolCallBuffer.toString()
                            .replace(TOOL_CALL_END, "")
                            .trim()
                        
                        val toolCall = LFMOutputParser.parseToolCall(toolCallContent)
                        if (toolCall != null) {
                            emit(LLMStreamEvent.ToolCallComplete(
                                callId = "local_${UUID.randomUUID()}",
                                name = toolCall.name,
                                arguments = toolCall.arguments
                            ))
                        }
                        inToolCall = false
                    }
                }
            }
            
            // Parse final result if no tool call was emitted
            if (!inToolCall) {
                val parseResult = LFMOutputParser.parse(buffer.toString(), config.isThinkingModel)
                
                when (val result = parseResult.result) {
                    is LFMOutputParser.ParseResult.Message -> {
                        emit(LLMStreamEvent.TextDelta(result.content))
                    }
                    is LFMOutputParser.ParseResult.ToolCall -> {
                        emit(LLMStreamEvent.ToolCallComplete(
                            callId = "local_${UUID.randomUUID()}",
                            name = result.name,
                            arguments = result.arguments
                        ))
                    }
                    is LFMOutputParser.ParseResult.Incomplete,
                    is LFMOutputParser.ParseResult.Invalid -> {
                        // Emit raw output as text fallback
                        emit(LLMStreamEvent.TextDelta(buffer.toString()))
                    }
                }
            }
            
            emit(LLMStreamEvent.Completed)
            
        } catch (e: LocalLLMException.NotIntegratedException) {
            Log.e(TAG, "llama.android not integrated", e)
            emit(LLMStreamEvent.Failed(e.message ?: "llama.android not integrated", e))
        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed", e)
            emit(LLMStreamEvent.Failed(e.message ?: "Local inference failed", e))
        }
    }
    
    // ========== Private Helpers ==========
    
    /**
     * Ensure the model is loaded and ready for inference.
     */
    private fun ensureReady() {
        if (!isInitialized) {
            throw LocalLLMException.NotIntegratedException(
                "LFMLLMClient not initialized. Call initialize() first, or ensure " +
                "llama.android is integrated."
            )
        }
        if (!isModelLoaded) {
            throw LocalLLMException.InitializationException(
                "Model not loaded. Call initialize() first."
            )
        }
    }
    
    /**
     * Generate text from prompt using the local model (non-streaming).
     */
    private suspend fun generateText(prompt: String): String {
        if (llamaEngine == null) {
            throw LocalLLMException.NotIntegratedException(
                "llama.android engine not available."
            )
        }
        
        // Use reflection to call llama.android
        val engineClass = llamaEngine!!.javaClass
        
        // Try different method signatures
        val generateMethod = try {
            engineClass.getMethod("generate", String::class.java, Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            try {
                engineClass.getMethod("completion", String::class.java, Int::class.javaPrimitiveType)
            } catch (e2: NoSuchMethodException) {
                engineClass.getMethod("complete", String::class.java)
            }
        }
        
        val result = if (generateMethod.parameterCount == 2) {
            generateMethod.invoke(llamaEngine, prompt, config.maxTokens)
        } else {
            generateMethod.invoke(llamaEngine, prompt)
        }
        
        return result?.toString() ?: ""
    }
    
    /**
     * Generate text from prompt using the local model (streaming).
     */
    private fun generateTextStreaming(prompt: String): Flow<String> = flow {
        if (llamaEngine == null) {
            throw LocalLLMException.NotIntegratedException(
                "llama.android engine not available."
            )
        }
        
        val engineClass = llamaEngine!!.javaClass
        
        // Check if streaming is supported
        val streamMethod = try {
            engineClass.getMethod("generateStream", String::class.java, Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            try {
                engineClass.getMethod("completionStream", String::class.java, Int::class.javaPrimitiveType)
            } catch (e2: NoSuchMethodException) {
                // Fall back to non-streaming
                null
            }
        }
        
        if (streamMethod != null) {
            @Suppress("UNCHECKED_CAST")
            val tokenFlow = streamMethod.invoke(llamaEngine, prompt, config.maxTokens) as? Flow<String>
            
            tokenFlow?.collect { token ->
                emit(token)
            }
        } else {
            // Fallback: generate all at once and emit
            val result = generateText(prompt)
            emit(result)
        }
    }
    
    /**
     * Convert parse result to ResponsesResult.
     */
    private fun convertToResponsesResult(
        parseResult: LFMOutputParser.LFMParseResult,
        rawOutput: String
    ): ResponsesResult {
        val responseId = "local_${UUID.randomUUID()}"
        
        return when (val result = parseResult.result) {
            is LFMOutputParser.ParseResult.Message -> {
                ResponsesResult(
                    textContent = result.content,
                    toolCalls = emptyList(),
                    responseId = responseId
                )
            }
            
            is LFMOutputParser.ParseResult.ToolCall -> {
                ResponsesResult(
                    textContent = null,
                    toolCalls = listOf(LLMToolCall(
                        callId = "local_${UUID.randomUUID()}",
                        name = result.name,
                        arguments = result.arguments
                    )),
                    responseId = responseId
                )
            }
            
            is LFMOutputParser.ParseResult.Incomplete,
            is LFMOutputParser.ParseResult.Invalid -> {
                // Fallback: treat raw output as text
                Log.w(TAG, "Parse failed, returning raw output as text")
                ResponsesResult(
                    textContent = rawOutput,
                    toolCalls = emptyList(),
                    responseId = responseId
                )
            }
        }
    }
}

/**
 * Exception thrown when the model file is not downloaded.
 */
class ModelNotDownloadedException(message: String) : Exception(message)

/**
 * Exceptions specific to local LLM operations.
 */
sealed class LocalLLMException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /** Model file not downloaded */
    class ModelNotFoundException(message: String) : LocalLLMException(message)
    
    /** Insufficient memory to load model */
    class OutOfMemoryException(message: String) : LocalLLMException(message)
    
    /** Model file corrupted or invalid */
    class ModelCorruptedException(message: String) : LocalLLMException(message)
    
    /** Initialization failed */
    class InitializationException(message: String, cause: Throwable? = null) : LocalLLMException(message, cause)
    
    /** Inference failed */
    class InferenceException(message: String, cause: Throwable? = null) : LocalLLMException(message, cause)
    
    /** Output parsing failed */
    class ParseException(val rawOutput: String, message: String) : LocalLLMException(message)
    
    /** llama.android library not integrated */
    class NotIntegratedException(message: String) : LocalLLMException(message)
}
