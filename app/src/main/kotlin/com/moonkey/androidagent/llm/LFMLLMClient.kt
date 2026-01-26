package com.moonkey.androidagent.llm

import android.content.Context
import android.util.Log
import ai.liquid.leap.Conversation
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.function.LeapFunction
import ai.liquid.leap.function.LeapFunctionCall
import ai.liquid.leap.function.LeapFunctionParameter
import ai.liquid.leap.function.LeapFunctionParameterType
import ai.liquid.leap.manifest.LeapDownloader
import ai.liquid.leap.manifest.LeapDownloaderConfig
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.ChatMessageContent
import ai.liquid.leap.message.MessageResponse
import com.openai.core.JsonValue
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
import org.json.JSONArray
import org.json.JSONObject
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

                    modelLoadingState = ModelLoadingState.Loading
                    onProgress?.invoke(modelLoadingState)

                    modelRunner = downloader.loadModel(
                        modelSlug = config.modelSlug,
                        quantizationSlug = config.quantizationSlug
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

        conversation.generateResponse(lastMessage).collect { response ->
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
            conversation.generateResponse(lastMessage).collect { response ->
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
                    val args = parseJsonArguments(call.arguments())
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
            conversation.registerFunction(convertToLeapFunction(tool))
        }
        Log.d(TAG, "Registered ${tools.size} tool(s)")
    }

    private fun convertToLeapFunction(tool: FunctionTool): LeapFunction {
        val description = tool.description().orElse("")
        val schema = parseToolParameters(tool)
        val parameters = if (schema != null) {
            buildLeapParameters(schema)
        } else {
            emptyList()
        }
        return LeapFunction(tool.name(), description, parameters)
    }

    private fun parseToolParameters(tool: FunctionTool): JSONObject? {
        val rawField = tool._parameters()
        val objectMap = rawField.asObject().orElse(null)
        if (objectMap != null) {
            return jsonValueMapToJsonObject(objectMap)
        }

        val knownParams = rawField.asKnown().orElse(null)
        if (knownParams != null) {
            return jsonValueMapToJsonObject(knownParams._additionalProperties())
        }
        Log.w(TAG, "Tool parameters missing for ${tool.name()}")
        return null
    }

    private fun buildLeapParameters(schema: JSONObject): List<LeapFunctionParameter> {
        if (schema.optString("type") != "object") {
            return emptyList()
        }
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        val required = schema.optJSONArray("required")?.toStringSet() ?: emptySet()
        val parameters = mutableListOf<LeapFunctionParameter>()

        properties.keys().forEach { name ->
            val propSchema = properties.getJSONObject(name)
            val description = propSchema.optString("description", "")
            val type = parseParameterType(propSchema)
            val optional = name !in required
            parameters.add(
                LeapFunctionParameter(
                    name = name,
                    type = type,
                    description = description,
                    optional = optional
                )
            )
        }
        return parameters
    }

    private fun parseParameterType(schema: JSONObject): LeapFunctionParameterType {
        val description = schema.optString("description", null)
        return when (schema.optString("type", "string")) {
            "string" -> LeapFunctionParameterType.LeapStr(
                enumValues = schema.optJSONArray("enum")?.toStringList(),
                description = description
            )
            "integer" -> LeapFunctionParameterType.LeapInt(
                enumValues = schema.optJSONArray("enum")?.toIntList(),
                description = description
            )
            "number" -> LeapFunctionParameterType.LeapNum(
                enumValues = schema.optJSONArray("enum")?.toNumberList(),
                description = description
            )
            "boolean" -> LeapFunctionParameterType.LeapBool(description = description)
            "array" -> {
                val itemSchema = schema.optJSONObject("items")
                val itemType = if (itemSchema != null) {
                    parseParameterType(itemSchema)
                } else {
                    LeapFunctionParameterType.LeapStr()
                }
                LeapFunctionParameterType.LeapArr(itemType, description = description)
            }
            "object" -> {
                val properties = schema.optJSONObject("properties")
                val required = schema.optJSONArray("required")?.toStringList() ?: emptyList()
                val propertyTypes = mutableMapOf<String, LeapFunctionParameterType>()
                properties?.keys()?.forEach { key ->
                    propertyTypes[key] = parseParameterType(properties.getJSONObject(key))
                }
                LeapFunctionParameterType.LeapObj(
                    properties = propertyTypes,
                    required = required,
                    description = description
                )
            }
            else -> LeapFunctionParameterType.LeapStr(
                enumValues = schema.optJSONArray("enum")?.toStringList(),
                description = description
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { idx ->
            optString(idx, null)
        }
    }

    private fun JSONArray.toIntList(): List<Int> {
        return (0 until length()).mapNotNull { idx ->
            when (val value = get(idx)) {
                is Int -> value
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
    }

    private fun JSONArray.toNumberList(): List<Number> {
        return (0 until length()).mapNotNull { idx ->
            when (val value = get(idx)) {
                is Number -> value
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    private fun JSONArray.toStringSet(): Set<String> = toStringList().toSet()

    private fun convertFunctionCalls(calls: List<LeapFunctionCall>?): List<LLMToolCall> {
        if (calls.isNullOrEmpty()) return emptyList()
        return calls.map { call ->
            LLMToolCall(
                callId = "call_${UUID.randomUUID().toString().take(8)}",
                name = call.name,
                arguments = convertArgumentsToJson(call.arguments)
            )
        }
    }

    private fun convertArgumentsToJson(arguments: Map<String, Any?>): String {
        return try {
            val jsonObj = JSONObject()
            arguments.forEach { (key, value) ->
                when (value) {
                    null -> jsonObj.put(key, JSONObject.NULL)
                    is List<*> -> jsonObj.put(key, JSONArray(value))
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        jsonObj.put(key, JSONObject(value as Map<String, Any?>))
                    }
                    else -> jsonObj.put(key, value)
                }
            }
            jsonObj.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert arguments to JSON", e)
            "{}"
        }
    }

    private fun parseJsonArguments(arguments: String): Map<String, Any?>? {
        return try {
            jsonObjectToMap(JSONObject(arguments))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse function call arguments", e)
            null
        }
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key ->
            map[key] = when (val value = obj.get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> {
        return (0 until array.length()).map { idx ->
            when (val value = array.get(idx)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
    }

    private fun jsonValueMapToJsonObject(map: Map<String, JsonValue>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) ->
            obj.put(key, jsonValueToAny(value))
        }
        return obj
    }

    private fun jsonValueToAny(value: JsonValue): Any? {
        if (value.isNull()) return null
        value.asString().orElse(null)?.let { return it }
        value.asBoolean().orElse(null)?.let { return it }
        value.asNumber().orElse(null)?.let { return it }
        value.asArray().orElse(null)?.let { array ->
            return array.map { jsonValueToAny(it) }
        }
        value.asObject().orElse(null)?.let { obj ->
            return obj.mapValues { jsonValueToAny(it.value) }
        }
        return value.toString()
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
