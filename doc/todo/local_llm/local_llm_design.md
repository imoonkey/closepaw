# Local LLM Integration Design

## Overview

This document describes how to integrate on-device LLM inference (specifically LFM2.5-1.2B-Thinking) into the Android Agent app while maintaining compatibility with the existing OpenAI Responses API-based architecture.

**Goal**: Run LLM inference locally on the device with minimal changes to upper layers, supporting tool calling via the existing agent infrastructure.

**Model**: LFM2.5-1.2B-Thinking (LiquidAI)
- GGUF format for llama.cpp compatibility
- ~1GB memory footprint (4-bit quantized)
- Optimized for on-device reasoning

---

## Current Architecture Analysis

### LLMClient.kt (Current)

```kotlin
class LLMClient(apiKey: String) {
    // Non-streaming
    suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult

    // Streaming
    fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<ResponseStreamEvent>  // ← OpenAI SDK type
}
```

### Turn.kt (Consumer)

Turn.kt consumes `Flow<ResponseStreamEvent>` and processes events:
- `event.isOutputTextDelta()` → text streaming
- `event.isOutputItemDone()` → tool calls
- `event.isCreated()` → response start
- `event.isCompleted()` → response end
- `event.isFailed()` → errors

### Key Constraint

Turn.kt is **tightly coupled** to OpenAI SDK types (`ResponseStreamEvent`, `ResponseInputItem`, `FunctionTool`). The ChatGPT reference suggested "只改llm_client.kt", but this is impractical because:

1. `ResponseStreamEvent` is a sealed class from OpenAI SDK - we can't create custom implementations
2. `ResponseInputItem` and `FunctionTool` are SDK types used in method signatures

**Solution**: Introduce a thin abstraction layer in `LLMClient.kt` that both backends implement, with minimal adapter code in Turn.kt.

---

## Proposed Architecture

**简化设计**：不引入额外的 Backend 层，直接让两个 Client 实现同一个接口。

```
┌───────────────────────────────────────────────────────────────┐
│                         Turn.kt                                │
│                       (no changes)                             │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                   LLMClient (interface)                        │
│  - chatWithTools(): ResponsesResult                            │
│  - chatWithToolsStreaming(): Flow<ResponseStreamEvent>         │
└───────────────────────────────────────────────────────────────┘
           ▲                                        ▲
           │                                        │
┌──────────┴──────────┐              ┌──────────────┴──────────┐
│  OpenAILLMClient    │              │     LFMLLMClient        │
│  (current code,     │              │   (new, llama.cpp)      │
│   minor refactor)   │              │                         │
└─────────────────────┘              └─────────────────────────┘
                                                │
                                     ┌──────────┴──────────┐
                                     │  ToolCallProtocol   │
                                     │  (JSON prompting)   │
                                     └─────────────────────┘
```

**选择逻辑**：在 `SessionServices` 创建 `LLMClient` 时根据配置选择具体实现。

---

## Design Details

### 1. LLMClient Interface (New)

将现有的 `LLMClient` class 改为 interface，保持方法签名不变：

```kotlin
/**
 * LLMClient interface - abstraction for LLM inference.
 * 
 * Both OpenAI and local implementations conform to this interface,
 * allowing seamless switching between remote and on-device inference.
 */
interface LLMClient {
    
    /**
     * Non-streaming chat with tools.
     */
    suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_4O
    ): ResponsesResult
    
    /**
     * Streaming chat with tools.
     */
    fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_4O
    ): Flow<ResponseStreamEvent>
}
```

**注意**：接口直接使用 OpenAI SDK 类型 (`ResponseInputItem`, `FunctionTool`, `ResponseStreamEvent`)。
- 对 `OpenAILLMClient`：这些类型是原生的
- 对 `LFMLLMClient`：需要在内部做转换，但对外接口保持一致

### 2. OpenAILLMClient (Refactored from current code)

基本是现有代码，只需改 `class` 为 `class ... : LLMClient`：

```kotlin
/**
 * OpenAI Responses API implementation of LLMClient.
 * 
 * This is essentially the current LLMClient.kt code with minimal changes.
 */
class OpenAILLMClient(apiKey: String) : LLMClient {
    
    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .build()
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        // Existing implementation - no changes needed
        return withContext(Dispatchers.IO) {
            // ... existing retry logic and API call ...
        }
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<ResponseStreamEvent> = callbackFlow {
        // Existing implementation - no changes needed
        // ... existing streaming logic ...
    }
}
```

### 3. LFMLLMClient (New)

本地 LLM 实现，使用 **llama.android** (官方 llama.cpp Android binding) 进行推理：

```kotlin
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine

/**
 * Local LFM (Liquid Foundation Model) implementation of LLMClient.
 * 
 * Uses official llama.android binding for on-device inference with GGUF models.
 * Tool calling is achieved through structured JSON prompting.
 * 
 * Model: LFM2.5-1.2B-Thinking-GGUF (Q4_K_M, ~731MB)
 * Source: https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF
 * 
 * Key advantage: InferenceEngine.sendUserPrompt() returns native Flow<String>,
 * eliminating the need for blocking-to-Flow wrappers.
 */
class LFMLLMClient(
    private val context: Context,
    private val config: LocalLLMConfig = LocalLLMConfig()
) : LLMClient {
    
    companion object {
        private const val TAG = "LFMLLMClient"
    }
    
    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)
    private val modelPath: File get() = File(context.filesDir, "models/${config.modelFileName}")
    
    /**
     * Check if the local model is ready for inference.
     */
    fun isReady(): Boolean {
        return modelPath.exists() && engine.state.value.isModelLoaded
    }
    
    /**
     * Initialize and load the local model.
     * Must be called before using chatWithTools/chatWithToolsStreaming.
     */
    suspend fun initialize(): Result<Unit> {
        try {
            if (!modelPath.exists()) {
                return Result.failure(ModelNotDownloadedException())
            }
            
            // Wait for engine initialization
            engine.state.first { it !is InferenceEngine.State.Initializing }
            
            // Load model using llama.android API
            engine.loadModel(modelPath.absolutePath)
            
            Log.i(TAG, "Local model loaded: ${config.modelFileName}")
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local model", e)
            return Result.failure(e)
        }
    }
    
    /**
     * Release model resources. Call when done or app backgrounded.
     */
    fun release() {
        engine.cleanUp()
        Log.i(TAG, "Local model released")
    }
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel  // ignored for local model
    ): ResponsesResult {
        check(engine.state.value.isModelLoaded) { 
            "Model not loaded. Call initialize() first." 
        }
        
        // Convert OpenAI types to our prompt format
        val prompt = ToolCallProtocol.buildPrompt(systemPrompt, inputItems, tools)
        
        // Collect all tokens (non-streaming)
        val buffer = StringBuilder()
        engine.sendUserPrompt(prompt, config.maxTokens).collect { token ->
            buffer.append(token)
        }
        
        // Parse output and convert to ResponsesResult
        return parseOutputToResult(buffer.toString())
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel  // ignored for local model
    ): Flow<LLMStreamEvent> = flow {
        check(engine.state.value.isModelLoaded) { 
            "Model not loaded. Call initialize() first." 
        }
        
        val responseId = UUID.randomUUID().toString()
        emit(LLMStreamEvent.Created(responseId))
        
        val prompt = ToolCallProtocol.buildPrompt(systemPrompt, inputItems, tools)
        val buffer = StringBuilder()
        
        // Stream tokens using llama.android's native Flow<String>!
        // No blocking wrapper needed - this is the key advantage
        engine.sendUserPrompt(prompt, config.maxTokens).collect { token ->
            buffer.append(token)
            
            // Try to parse complete JSON
            val parseResult = LocalOutputParser.tryParseCompleteJson(buffer.toString())
            
            when (parseResult) {
                is LocalOutputParser.ParseResult.Incomplete -> {
                    // Keep buffering, optionally emit partial text for UI feedback
                }
                is LocalOutputParser.ParseResult.Message -> {
                    emit(LLMStreamEvent.TextDelta(parseResult.content))
                    emit(LLMStreamEvent.Completed)
                    return@collect
                }
                is LocalOutputParser.ParseResult.ToolCall -> {
                    emit(LLMStreamEvent.ToolCallComplete(
                        callId = "local_${UUID.randomUUID()}",
                        name = parseResult.name,
                        arguments = parseResult.arguments
                    ))
                    emit(LLMStreamEvent.Completed)
                    return@collect
                }
                is LocalOutputParser.ParseResult.Invalid -> {
                    // Continue buffering
                }
            }
        }
        
        // Parse final buffer if stream ended without complete JSON
        val finalResult = LocalOutputParser.tryParseCompleteJson(buffer.toString())
        when (finalResult) {
            is LocalOutputParser.ParseResult.Message -> {
                emit(LLMStreamEvent.TextDelta(finalResult.content))
            }
            is LocalOutputParser.ParseResult.ToolCall -> {
                emit(LLMStreamEvent.ToolCallComplete(
                    callId = "local_${UUID.randomUUID()}",
                    name = finalResult.name,
                    arguments = finalResult.arguments
                ))
            }
            else -> {
                // Fallback: emit raw output as text
                Log.w(TAG, "Could not parse JSON output, returning raw: ${buffer.toString().take(100)}")
                emit(LLMStreamEvent.TextDelta(buffer.toString()))
            }
        }
        
        emit(LLMStreamEvent.Completed)
    }
    
    // ========== Helper: Parse output to result ==========
    
    private fun parseOutputToResult(output: String): ResponsesResult {
        val parseResult = LocalOutputParser.tryParseCompleteJson(output)
        
        return when (parseResult) {
            is LocalOutputParser.ParseResult.Message -> {
                ResponsesResult(
                    textContent = parseResult.content,
                    toolCalls = emptyList(),
                    responseId = UUID.randomUUID().toString()
                )
            }
            is LocalOutputParser.ParseResult.ToolCall -> {
                ResponsesResult(
                    textContent = null,
                    toolCalls = listOf(LLMToolCall(
                        callId = "local_${UUID.randomUUID()}",
                        name = parseResult.name,
                        arguments = parseResult.arguments
                    )),
                    responseId = UUID.randomUUID().toString()
                )
            }
            else -> {
                // Fallback: treat as text
                Log.w(TAG, "Could not parse JSON, returning raw output")
                ResponsesResult(
                    textContent = output,
                    toolCalls = emptyList(),
                    responseId = UUID.randomUUID().toString()
                )
            }
        }
    }
}
```

### 4. Tool Calling Protocol for Local Model

Since local models don't natively support OpenAI-style tool calling, we use a **structured JSON protocol** injected into the system prompt:

```kotlin
/**
 * Converts OpenAI SDK types to a prompt format that instructs
 * the local model to output structured JSON for tool calls.
 */
object ToolCallProtocol {
    
    /**
     * Build complete prompt for local model inference.
     * 
     * @param systemPrompt Base system prompt
     * @param inputItems Conversation history (OpenAI SDK type)
     * @param tools Available tools (OpenAI SDK type)
     */
    fun buildPrompt(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>
    ): String {
        val toolsDescription = formatTools(tools)
        val conversationHistory = formatInputItems(inputItems)
        
        return """
$systemPrompt

## Output Format (CRITICAL)

You MUST respond with ONLY a single JSON object. No other text before or after.

When you need to use a tool, respond with:
{"type":"tool_call","name":"TOOL_NAME","arguments":{...}}

When you have the final answer (no tool needed), respond with:
{"type":"message","content":"Your response here"}

## Available Tools

$toolsDescription

## Conversation

$conversationHistory

## Your Response (JSON only):
""".trimIndent()
    }
    
    private fun formatTools(tools: List<FunctionTool>): String {
        return tools.joinToString("\n\n") { tool ->
            val params = tool.parameters()?.toString() ?: "{}"
            """### ${tool.name()}
${tool.description() ?: "No description"}
Parameters: $params"""
        }
    }
    
    private fun formatInputItems(items: List<ResponseInputItem>): String {
        return items.mapNotNull { item ->
            when {
                item.isEasyInputMessage() -> {
                    val msg = item.asEasyInputMessage()
                    val role = when (msg.role()) {
                        EasyInputMessage.Role.USER -> "User"
                        EasyInputMessage.Role.ASSISTANT -> "Assistant"
                        else -> "System"
                    }
                    "$role: ${msg.content()}"
                }
                item.isFunctionCall() -> {
                    val fc = item.asFunctionCall()
                    "Assistant called: ${fc.name()}(${fc.arguments()})"
                }
                item.isFunctionCallOutput() -> {
                    val fco = item.asFunctionCallOutput()
                    "Tool result: ${fco.output()}"
                }
                else -> null
            }
        }.joinToString("\n\n")
    }
}
```

### 5. JSON Parsing with Recovery

Local models may produce malformed JSON. Implement robust parsing with recovery:

```kotlin
object LocalOutputParser {
    
    sealed interface ParseResult {
        data class Message(val content: String) : ParseResult
        data class ToolCall(val name: String, val arguments: String) : ParseResult
        data object Incomplete : ParseResult
        data class Invalid(val raw: String) : ParseResult
    }
    
    fun tryParseCompleteJson(buffer: String): ParseResult {
        // Step 1: Find JSON boundaries
        val jsonStart = buffer.indexOf('{')
        val jsonEnd = buffer.lastIndexOf('}')
        
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            return ParseResult.Incomplete
        }
        
        // Step 2: Extract JSON candidate
        val candidate = buffer.substring(jsonStart, jsonEnd + 1)
        
        // Step 3: Try to parse
        return try {
            val json = JSONObject(candidate)
            val type = json.optString("type", "")
            
            when (type) {
                "message" -> ParseResult.Message(json.getString("content"))
                "tool_call" -> ParseResult.ToolCall(
                    name = json.getString("name"),
                    arguments = json.getJSONObject("arguments").toString()
                )
                else -> ParseResult.Invalid(candidate)
            }
        } catch (e: JSONException) {
            // Try to repair common issues
            tryRepairAndParse(candidate)
        }
    }
    
    private fun tryRepairAndParse(raw: String): ParseResult {
        var repaired = raw
        
        // Common repairs:
        // 1. Trailing comma
        repaired = repaired.replace(Regex(",\\s*}"), "}")
        repaired = repaired.replace(Regex(",\\s*]"), "]")
        
        // 2. Single quotes → double quotes
        repaired = repaired.replace("'", "\"")
        
        // 3. Unquoted keys
        repaired = repaired.replace(Regex("(\\{|,)\\s*(\\w+)\\s*:")) { match ->
            "${match.groupValues[1]}\"${match.groupValues[2]}\":"
        }
        
        return try {
            val json = JSONObject(repaired)
            val type = json.optString("type", "")
            when (type) {
                "message" -> ParseResult.Message(json.getString("content"))
                "tool_call" -> ParseResult.ToolCall(
                    name = json.getString("name"),
                    arguments = json.optJSONObject("arguments")?.toString() ?: "{}"
                )
                else -> ParseResult.Invalid(raw)
            }
        } catch (e: Exception) {
            ParseResult.Invalid(raw)
        }
    }
}
```

### 6. Stream Event Abstraction (Simplified with llama.android)

由于 llama.android 的 `InferenceEngine.sendUserPrompt()` 原生返回 `Flow<String>`，我们不再需要处理 OpenAI SDK 事件类型的创建问题。

**解决方案：自定义 LLMStreamEvent 类型**

定义统一的事件类型，两个 Client 都使用：

```kotlin
// In LLMClient.kt - 自定义事件类型
sealed interface LLMStreamEvent {
    data class Created(val id: String) : LLMStreamEvent
    data class TextDelta(val delta: String) : LLMStreamEvent
    data class ToolCallComplete(val callId: String, val name: String, val arguments: String) : LLMStreamEvent
    data object Completed : LLMStreamEvent
    data class Failed(val error: String) : LLMStreamEvent
}

// LLMClient interface 返回自定义类型
interface LLMClient {
    fun chatWithToolsStreaming(...): Flow<LLMStreamEvent>
}
```

**Turn.kt 适配**（改动很小）：

```kotlin
// Turn.kt 改为消费 LLMStreamEvent
llmClient.chatWithToolsStreaming(...).collect { event ->
    when (event) {
        is LLMStreamEvent.Created -> { /* response started */ }
        is LLMStreamEvent.TextDelta -> emit(TurnStreamEvent.TextDelta(event.delta))
        is LLMStreamEvent.ToolCallComplete -> { 
            // Handle tool call
            pendingToolCalls.add(ToolCall(event.callId, event.name, event.arguments))
        }
        is LLMStreamEvent.Completed -> { /* response finished */ }
        is LLMStreamEvent.Failed -> throw LLMException(event.error)
    }
}
```

**OpenAILLMClient 适配**：

```kotlin
// OpenAILLMClient 将 ResponseStreamEvent 转换为 LLMStreamEvent
override fun chatWithToolsStreaming(...): Flow<LLMStreamEvent> = flow {
    val openAIFlow = client.responses().createStream(...)
    
    openAIFlow.collect { event ->
        when {
            event.isCreated() -> emit(LLMStreamEvent.Created(event.asCreated().response().id()))
            event.isOutputTextDelta() -> emit(LLMStreamEvent.TextDelta(event.asOutputTextDelta().delta()))
            event.isOutputItemDone() -> {
                // Convert tool calls
                val item = event.asOutputItemDone().item()
                if (item.isFunctionCall()) {
                    val fc = item.asFunctionCall()
                    emit(LLMStreamEvent.ToolCallComplete(fc.callId(), fc.name(), fc.arguments()))
                }
            }
            event.isCompleted() -> emit(LLMStreamEvent.Completed)
            event.isFailed() -> emit(LLMStreamEvent.Failed(event.asFailed().error().message()))
        }
    }
}
```

**优势**：
- `LFMLLMClient` 实现简洁，直接使用 `engine.sendUserPrompt()` 的 `Flow<String>`
- 不需要处理 OpenAI SDK sealed class 的创建问题
- Turn.kt 改动很小，只是 when 分支的事件类型变了

### 7. Client Selection (SessionServices)

在 `SessionServices` 或 App 初始化时选择使用哪个 Client：

```kotlin
// SessionServices.kt
class SessionServices(
    context: Context,
    config: AgentConfig
) {
    val llmClient: LLMClient = createLLMClient(context, config)
    
    private fun createLLMClient(context: Context, config: AgentConfig): LLMClient {
        return when (config.llmBackend) {
            LLMBackendType.OPENAI -> {
                OpenAILLMClient(config.apiKey)
            }
            LLMBackendType.LOCAL -> {
                LFMLLMClient(context, config.localLLMConfig).also { client ->
                    // Initialize in background
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = client.initialize()
                        if (result.isFailure) {
                            Log.e(TAG, "Failed to init local model, falling back to OpenAI")
                            // Could switch to OpenAI here if needed
                        }
                    }
                }
            }
            LLMBackendType.AUTO -> {
                // Try local first, fallback to OpenAI
                val localClient = LFMLLMClient(context, config.localLLMConfig)
                if (localClient.isReady()) {
                    localClient
                } else {
                    Log.i(TAG, "Local model not ready, using OpenAI")
                    OpenAILLMClient(config.apiKey)
                }
            }
        }
    }
}

enum class LLMBackendType {
    OPENAI,  // Always use OpenAI
    LOCAL,   // Always use local (fail if not ready)
    AUTO     // Prefer local, fallback to OpenAI
}
```

### 8. Configuration

```kotlin
data class LocalLLMConfig(
    /** GGUF model filename (in app's files/models directory) */
    val modelFileName: String = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
    
    /** Context window size (tokens) */
    val contextSize: Int = 2048,
    
    /** Number of CPU threads (recommend 4 for most devices) */
    val threads: Int = 4,
    
    /** Maximum tokens to generate */
    val maxTokens: Int = 512,
    
    /** Sampling temperature (lower = more deterministic for tool calls) */
    val temperature: Float = 0.3f,
    
    /** Nucleus sampling */
    val topP: Float = 0.9f
)
```

---

## Model Information

### LFM2.5-1.2B-Thinking-GGUF

**Source**: [huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF)

**Architecture**: `lfm2` (hybrid model optimized for on-device deployment)

**Quantization Options** (choose based on device capability):

| Quantization | Size | Memory | Quality | Recommended |
|--------------|------|--------|---------|-------------|
| Q4_0 | 696 MB | ~1 GB | Good | Budget devices |
| **Q4_K_M** | **731 MB** | **~1 GB** | **Better** | **Recommended** |
| Q5_K_M | 843 MB | ~1.2 GB | Great | Mid-range |
| Q6_K | 963 MB | ~1.4 GB | Excellent | Flagship |
| Q8_0 | 1.25 GB | ~1.8 GB | Best | High-end |

**Download URL**:
```
https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf
```

**Features**:
- Has built-in chat template
- Supports reasoning/thinking tasks
- Optimized for edge/mobile deployment
- Confirmed compatible with llama.cpp (listed in supported models)
```

---

## Implementation Plan

### Phase 1: Interface Extraction & Event Abstraction

1. **Define `LLMStreamEvent`** sealed interface (see section 6)
2. **Extract `LLMClient` interface** from current `LLMClient.kt`
   - Change streaming return type to `Flow<LLMStreamEvent>`
3. **Rename current class** to `OpenAILLMClient implements LLMClient`
   - Add event conversion from `ResponseStreamEvent` to `LLMStreamEvent`
4. **Update Turn.kt** to consume `LLMStreamEvent` (small change)
5. **Update SessionServices** to create `LLMClient` (not `OpenAILLMClient` directly)
6. **Verify no breaking changes** - existing behavior unchanged

### Phase 2: llama.android Integration (~2 hours)

1. **Add llama.cpp as git submodule**
   ```bash
   git submodule add https://github.com/ggml-org/llama.cpp.git libs/llama.cpp
   ```

2. **Copy the `lib` module** from `examples/llama.android/lib` to `app/libs/aichat`

3. **Fix CMakeLists.txt path** - update `LLAMA_SRC` to point to submodule location:
   ```cmake
   set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../libs/llama.cpp/)
   ```

4. **Update settings.gradle.kts**:
   ```kotlin
   include(":app:libs:aichat")
   ```

5. **Add dependency in app/build.gradle.kts**:
   ```kotlin
   implementation(project(":app:libs:aichat"))
   ```

6. **First build** - wait for llama.cpp compilation (~5-15 min, one-time)

### Phase 3: Local Client Implementation

1. **Create `LFMLLMClient`** using `AiChat.getInferenceEngine()` API
2. **Implement `ToolCallProtocol`** - prompt construction with JSON protocol
3. **Implement `LocalOutputParser`** - JSON parsing with recovery
4. **Wire up streaming** - leverage native `Flow<String>` from `engine.sendUserPrompt()`

### Phase 4: Model Management

1. **Model download service** - background download from HuggingFace
2. **Download progress UI** in settings
3. **Model validation** (checksum verification)
4. **Storage management** (delete/update models)

### Phase 5: Integration & Testing

1. **Client selection logic** in SessionServices
2. **Settings UI** for backend selection
3. **Performance tuning** - context size, threads, quantization
4. **Memory management** - proper cleanup, lifecycle handling
5. **Tool call accuracy testing** - compare with OpenAI baseline

---

## Library Choice

### Recommended: llama.android (Official)

The official Android binding from llama.cpp repo, recently rewritten with production-quality Kotlin API.

- **Pros**: 
  - Maintained by llama.cpp team directly - stays current with updates
  - **Native Kotlin `Flow<String>` for streaming** - perfect fit for our architecture
  - Automatic hardware detection (SME2 for ARM, AMX for x86-64)
  - Production-proven (used by Arm AI Chat on Google Play)
  - Built-in state management via `StateFlow<State>`
  - No blocking-to-Flow wrappers needed
- **Cons**: 
  - No Maven artifact - requires vendoring as a module
  - First build compiles llama.cpp (~5-15 min one-time cost)
- **Source**: [github.com/ggml-org/llama.cpp/examples/llama.android](https://github.com/ggml-org/llama.cpp)

**Integration Steps:**

```bash
# 1. Add llama.cpp as git submodule
git submodule add https://github.com/ggml-org/llama.cpp.git libs/llama.cpp

# 2. Copy the lib module
cp -r libs/llama.cpp/examples/llama.android/lib app/libs/aichat

# 3. Fix CMakeLists.txt path (update LLAMA_SRC to point to submodule)
# In app/libs/aichat/src/main/cpp/CMakeLists.txt:
# set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../libs/llama.cpp/)
```

```kotlin
// settings.gradle.kts
include(":app:libs:aichat")

// app/build.gradle.kts
dependencies {
    implementation(project(":app:libs:aichat"))
}
```

**Requirements:**
- NDK 29 (29.0.13113456)
- CMake 3.31.6
- minSdk 33

### Alternative: java-llama.cpp (kherud)

Java/Kotlin binding with Maven Central availability.

- **Pros**: 
  - One-line dependency: `implementation("de.kherud:llama:4.1.0")`
  - Well-documented API
- **Cons**: 
  - Returns blocking `Iterable` - requires `withContext(Dispatchers.IO)` wrapper
  - May have stale native binaries
  - Separate project - may lag behind llama.cpp updates
- **Source**: [github.com/kherud/java-llama.cpp](https://github.com/kherud/java-llama.cpp)

### Alternative: kotlinllamacpp (ljcamargo)

Kotlin-first binding optimized for ARM.

- **Pros**: Kotlin-native API, ARM optimization
- **Cons**: Earlier stage of development
- **Source**: [github.com/ljcamargo/kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp)

### NOT Recommended: ONNX Runtime

- LFM2.5-1.2B-Thinking has GGUF format optimized for llama.cpp
- ONNX would require format conversion and tokenizer handling
- Stick with GGUF + llama.cpp ecosystem

---

## Changes Summary

### Files Modified

| File | Change Type | Scope |
|------|-------------|-------|
| `LLMClient.kt` | Refactor | Extract interface, add `LLMStreamEvent`, rename class to `OpenAILLMClient` |
| `Turn.kt` | Minor | Change event type from `ResponseStreamEvent` to `LLMStreamEvent` |
| `SessionServices.kt` | Minor | Add client selection logic |
| `settings.gradle.kts` | Minor | Include `:app:libs:aichat` module |
| `app/build.gradle.kts` | Minor | Add `implementation(project(":app:libs:aichat"))` |

### Files/Directories Added

| Path | Purpose |
|------|---------|
| `libs/llama.cpp/` | Git submodule - llama.cpp source |
| `app/libs/aichat/` | Copied from `examples/llama.android/lib` |
| `LFMLLMClient.kt` | Local LLM implementation using `InferenceEngine` |
| `ToolCallProtocol.kt` | Prompt building for local model |
| `LocalOutputParser.kt` | JSON parsing with recovery |

### Turn.kt Changes

Turn.kt 改为消费 `LLMStreamEvent`（自定义类型），改动很小：

```kotlin
// Turn.kt 改动示例
llmClient.chatWithToolsStreaming(...).collect { event ->
    when (event) {
        is LLMStreamEvent.Created -> { /* response started */ }
        is LLMStreamEvent.TextDelta -> emit(TurnStreamEvent.TextDelta(event.delta))
        is LLMStreamEvent.ToolCallComplete -> { 
            pendingToolCalls.add(ToolCall(event.callId, event.name, event.arguments))
        }
        is LLMStreamEvent.Completed -> { /* response finished */ }
        is LLMStreamEvent.Failed -> throw LLMException(event.error)
    }
}
```

---

## Fallback Strategy

选择逻辑在 `SessionServices.createLLMClient()` 中实现。

对于 `AUTO` 模式，可以用 wrapper 实现运行时 fallback：

```kotlin
/**
 * Wrapper that tries local first, falls back to OpenAI on failure.
 */
class FallbackLLMClient(
    private val local: LFMLLMClient,
    private val remote: OpenAILLMClient
) : LLMClient {
    
    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): ResponsesResult {
        return if (local.isReady()) {
            try {
                local.chatWithTools(systemPrompt, inputItems, tools, model)
            } catch (e: Exception) {
                Log.w(TAG, "Local inference failed, falling back to OpenAI", e)
                remote.chatWithTools(systemPrompt, inputItems, tools, model)
            }
        } else {
            remote.chatWithTools(systemPrompt, inputItems, tools, model)
        }
    }
    
    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel
    ): Flow<LLMStreamEvent> {
        return if (local.isReady()) {
            local.chatWithToolsStreaming(systemPrompt, inputItems, tools, model)
                .catch { e ->
                    Log.w(TAG, "Local streaming failed, falling back to OpenAI", e)
                    emitAll(remote.chatWithToolsStreaming(systemPrompt, inputItems, tools, model))
                }
        } else {
            remote.chatWithToolsStreaming(systemPrompt, inputItems, tools, model)
        }
    }
}
```

---

## Error Handling

### Local Model Errors

```kotlin
sealed class LocalLLMException : Exception() {
    /** Model file not downloaded */
    class ModelNotDownloadedException : LocalLLMException()
    
    /** Insufficient memory to load model */
    class OutOfMemoryException : LocalLLMException()
    
    /** Model file corrupted */
    class ModelCorruptedException : LocalLLMException()
    
    /** Inference failed */
    class InferenceException(cause: Throwable) : LocalLLMException()
    
    /** Output parsing failed */
    class ParseException(val rawOutput: String) : LocalLLMException()
}
```

### Recovery Flow

```kotlin
override fun chatStreaming(request: LLMRequest): Flow<LLMStreamEvent> = flow {
    var attempts = 0
    val maxAttempts = 2
    
    while (attempts < maxAttempts) {
        try {
            // ... generate and parse ...
            
            val parseResult = tryParseCompleteJson(buffer.toString())
            if (parseResult is ParseResult.Invalid && attempts < maxAttempts - 1) {
                // Retry with correction prompt
                attempts++
                buffer.clear()
                buffer.append(CORRECTION_PROMPT)
                continue
            }
            
            // ... emit events ...
            return@flow
            
        } catch (e: Exception) {
            if (attempts < maxAttempts - 1) {
                attempts++
                continue
            }
            emit(LLMStreamEvent.Failed(e.message ?: "Unknown error"))
        }
    }
}

private const val CORRECTION_PROMPT = """
Your previous output was not valid JSON. 
Output ONLY a valid JSON object matching this format:
{"type":"message","content":"..."} OR {"type":"tool_call","name":"...","arguments":{...}}
"""
```

---

## Model Download Strategy

```kotlin
class ModelDownloadManager(private val context: Context) {
    
    companion object {
        // HuggingFace download URLs for LFM2.5-1.2B-Thinking-GGUF
        val MODEL_URLS = mapOf(
            "Q4_0" to "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_0.gguf",
            "Q4_K_M" to "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            "Q5_K_M" to "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q5_K_M.gguf",
            "Q6_K" to "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q6_K.gguf",
            "Q8_0" to "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q8_0.gguf"
        )
        
        val MODEL_SIZES = mapOf(
            "Q4_0" to 696L * 1024 * 1024,      // 696 MB
            "Q4_K_M" to 731L * 1024 * 1024,    // 731 MB
            "Q5_K_M" to 843L * 1024 * 1024,    // 843 MB
            "Q6_K" to 963L * 1024 * 1024,      // 963 MB
            "Q8_0" to 1280L * 1024 * 1024      // 1.25 GB
        )
    }
    
    private val modelsDir = File(context.filesDir, "models")
    
    suspend fun downloadModel(
        quantization: String = "Q4_K_M",
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = MODEL_URLS[quantization] 
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown quantization: $quantization"))
        val expectedSize = MODEL_SIZES[quantization] ?: 0L
        val modelName = "LFM2.5-1.2B-Thinking-$quantization.gguf"
        
        try {
            modelsDir.mkdirs()
            val targetFile = File(modelsDir, modelName)
            val tempFile = File(modelsDir, "$modelName.tmp")
            
            // Download with progress
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connect()
            
            val contentLength = connection.contentLength.toLong().takeIf { it > 0 } ?: expectedSize
            
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        onProgress(totalBytes.toFloat() / contentLength)
                    }
                }
            }
            
            // Rename temp to final
            tempFile.renameTo(targetFile)
            
            Log.i("ModelDownload", "Downloaded $modelName (${targetFile.length()} bytes)")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e("ModelDownload", "Failed to download model", e)
            Result.failure(e)
        }
    }
    
    fun isModelDownloaded(quantization: String = "Q4_K_M"): Boolean {
        val modelName = "LFM2.5-1.2B-Thinking-$quantization.gguf"
        return File(modelsDir, modelName).exists()
    }
    
    fun getModelPath(quantization: String = "Q4_K_M"): File {
        val modelName = "LFM2.5-1.2B-Thinking-$quantization.gguf"
        return File(modelsDir, modelName)
    }
    
    fun deleteModel(quantization: String = "Q4_K_M"): Boolean {
        val modelName = "LFM2.5-1.2B-Thinking-$quantization.gguf"
        return File(modelsDir, modelName).delete()
    }
    
    fun getAvailableSpace(): Long {
        return modelsDir.freeSpace
    }
}
```

---

## Performance Considerations

### Memory Management

```kotlin
class LFMLLMClient(...) {
    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)
    
    // Release model when not in use (e.g., app backgrounded)
    fun release() {
        engine.cleanUp()  // Unloads model, frees resources
    }
    
    // Full cleanup when app is closing
    fun destroy() {
        engine.destroy()  // Cancels coroutines, frees GGML backends
    }
    
    // Re-initialize when needed
    suspend fun ensureReady(): Boolean {
        val state = engine.state.value
        return when (state) {
            is InferenceEngine.State.ModelReady -> true
            is InferenceEngine.State.Initialized -> {
                initialize()
                engine.state.value.isModelLoaded
            }
            else -> false
        }
    }
}

// In Activity/Service lifecycle
override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
        // Release model under memory pressure
        llmClient?.let { 
            if (it is LFMLLMClient) it.release() 
        }
    }
}

// Monitor engine state for UI updates
lifecycleScope.launch {
    engine.state.collect { state ->
        when (state) {
            is InferenceEngine.State.LoadingModel -> showLoadingUI()
            is InferenceEngine.State.ModelReady -> hideLoadingUI()
            is InferenceEngine.State.Generating -> showGeneratingIndicator()
            is InferenceEngine.State.Error -> showError(state.exception)
            else -> {}
        }
    }
}
```

### Context Window Management

```kotlin
private fun truncateMessagesToFit(
    messages: List<LLMMessage>,
    maxContextTokens: Int
): List<LLMMessage> {
    // Keep system prompt + recent messages
    // Estimate ~4 chars per token
    val estimatedTokens = messages.sumOf { 
        when (it) {
            is LLMMessage.User -> it.content.length / 4
            is LLMMessage.Assistant -> it.content.length / 4
            is LLMMessage.FunctionCall -> (it.name.length + it.arguments.length) / 4
            is LLMMessage.FunctionResult -> it.output.length / 4
        }
    }
    
    if (estimatedTokens <= maxContextTokens) return messages
    
    // Keep first (system context) and last N messages
    val keepFirst = 1
    val keepLast = messages.size - keepFirst
    // ... truncation logic ...
}
```

---

## Testing Strategy

### Unit Tests

```kotlin
class LocalOutputParserTest {
    @Test
    fun `parses valid message JSON`() {
        val input = """{"type":"message","content":"Hello"}"""
        val result = LocalOutputParser.tryParseCompleteJson(input)
        assertThat(result).isInstanceOf(ParseResult.Message::class.java)
    }
    
    @Test
    fun `parses valid tool call JSON`() {
        val input = """{"type":"tool_call","name":"click","arguments":{"index":5}}"""
        val result = LocalOutputParser.tryParseCompleteJson(input)
        assertThat(result).isInstanceOf(ParseResult.ToolCall::class.java)
    }
    
    @Test
    fun `repairs trailing comma`() {
        val input = """{"type":"message","content":"Hello",}"""
        val result = LocalOutputParser.tryParseCompleteJson(input)
        assertThat(result).isInstanceOf(ParseResult.Message::class.java)
    }
}
```

### Integration Tests

1. **End-to-end tool calling** - verify local model can trigger click, type, scroll
2. **Fallback behavior** - verify auto-switch to OpenAI when local fails
3. **Memory stress test** - long conversation with many tool calls
4. **Concurrent access** - multiple turns simultaneously

---

## Known Issues & Caveats

### 1. GPU Support on Android
- Vulkan backend has known issues on Android (compilation failures, missing `glslc`)
- **Recommendation**: Use CPU-only for reliability
- llama.android defaults to CPU with automatic hardware feature detection (SME2, etc.)
- Can revisit GPU acceleration when llama.cpp Android Vulkan support matures

### 2. llama.android Integration
- **First build is slow** (~5-15 min) - compiles entire llama.cpp from source
- Subsequent builds are incremental and fast
- Requires NDK 29 and CMake 3.31.6 (install via Android Studio SDK Manager)
- **CMake path adjustment required** - update `LLAMA_SRC` in CMakeLists.txt to point to submodule

### 3. minSdk Requirement
- llama.android requires `minSdk = 33` (Android 13)
- If your app targets lower, you may need conditional loading or use java-llama.cpp fallback

### 4. Model File Access
- On Android 10+, use app-private storage (`context.filesDir`) or SAF
- Don't store in external storage without proper permissions
- `InferenceEngine.loadModel()` requires absolute file path

### 5. Memory Constraints
- Q4_K_M (731MB) model needs ~1GB RAM for inference
- Monitor `InferenceEngine.state` and call `cleanUp()` when backgrounded
- Consider lower quantization (Q4_0) for low-memory devices
- Use `engine.destroy()` for full cleanup when app is closing

### 6. State Management
- `InferenceEngine` is a singleton - only one model loaded at a time
- Check `engine.state.value` before operations:
  - `State.Initialized` - ready to load model
  - `State.ModelReady` - ready for inference
  - `State.Generating` - inference in progress
  - `State.Error` - need to call `cleanUp()` to reset

---

## References

### llama.android (Recommended)
- [llama.cpp Android Documentation](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/android.md) - Official build guide
- [llama.android Source](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android) - Official Android binding
- [Arm AI Chat on Google Play](https://play.google.com/store/apps/details?id=com.arm.aichat) - Production app using llama.android

### Model
- [LFM2.5-1.2B-Thinking-GGUF on HuggingFace](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF)
- [LFM2.5-1.2B-Thinking Base Model](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking)
- [Liquid AI Blog - On-Device Reasoning](https://www.liquid.ai/blog/lfm2-5-1-2b-thinking-on-device-reasoning-under-1gb)

### llama.cpp Ecosystem
- [llama.cpp GitHub](https://github.com/ggml-org/llama.cpp) - Main project, lists LFM2 as supported
- [java-llama.cpp](https://github.com/kherud/java-llama.cpp) - Alternative: Maven-based Java binding
- [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp) - Alternative: Kotlin-first binding
