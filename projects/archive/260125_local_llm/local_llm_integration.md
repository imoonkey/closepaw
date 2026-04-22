# Plan: Local LLM Integration (LFM2.5-1.2B-Instruct)

## Summary

Refactor `LLMClient` into an abstract class/interface to support both OpenAI and local models (LiquidAI Leap SDK). Create separate implementations (`OpenAILLMClient` and `LFMLLMClient`) that share the same interface, allowing higher-level code (`Turn.kt`, `Agent.kt`) to work unchanged. Use LiquidAI's Leap SDK to load and run the LFM2.5-1.2B-Instruct model (Q4_K_M quantization) locally on the device.

## Affected Components

- `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClient.kt` - Refactor to abstract class (keep OpenAI types in interface)
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAILLMClient.kt` - New: OpenAI implementation
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt` - New: LFM implementation using Leap SDK (converts Leap types → OpenAI types internally)
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt` - Update to create appropriate client based on config
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt` - Add LLM backend type to SessionConfig
- `app/build.gradle.kts` - Add Leap SDK dependency

**Note**: We're reusing OpenAI Responses API types (`ResponseInputItem`, `FunctionTool`, `ResponseStreamEvent`, `ChatModel`) as the interface, so `Turn.kt` and `ToolRegistry.kt` stay unchanged. See `type_strategy_evaluation.md` for rationale.

## Phases

### Phase 1: Create Abstract Interface (Reuse OpenAI Types)
**Risk: Low**

1. **Create abstract `LLMClient` class** (`app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClient.kt`)
   - Convert current `LLMClient` class to abstract class
   - **Keep OpenAI types in interface** (simplifies implementation):
     - `chatWithTools(systemPrompt, inputItems: List<ResponseInputItem>, tools: List<FunctionTool>, model: ChatModel): ResponsesResult`
     - `chatWithToolsStreaming(...): Flow<ResponseStreamEvent>`
   - Keep companion object constants (MAX_RETRIES, etc.) as shared defaults
   - Keep `ResponsesResult` and `LLMToolCall` (already exist)
   - Action: Extract current implementation to `OpenAILLMClient`, make `LLMClient` abstract

### Phase 2: Implement OpenAI Client
**Risk: Low**

3. **Create `OpenAILLMClient.kt`** (`app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAILLMClient.kt`)
   - Move current `LLMClient` implementation here
   - **No conversion needed** - types match directly (OpenAI types in interface = OpenAI SDK types)
   - Keep all existing retry logic, error handling, rate limiting
   - Action: Copy current implementation directly (no conversion layer)

### Phase 3: Implement LFM Client with Leap SDK
**Risk: Medium**

4. **Add Leap SDK dependency** (`app/build.gradle.kts`)
   - Add `implementation("ai.liquid.leap:leap-sdk:0.9.2")`
   - Ensure minSdk = 31 (required by Leap SDK)
   - Action: Update build.gradle.kts

5. **Create `LFMLLMClient.kt`** (`app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt`)
   - Implement `LLMClient` abstract class
   - Model loading lifecycle:
     - Use `LeapDownloader.loadModel()` to load model (async, suspend)
     - Store `ModelRunner` instance
     - Handle `LeapModelLoadingException`
     - Model: "LFM2.5-1.2B-Instruct", quantization: "Q4_K_M"
   - Conversation management:
     - Create `Conversation` from `ModelRunner.createConversation(systemPrompt)`
     - Maintain conversation history via `Conversation.history`
     - Register functions via `Conversation.registerFunction()` (convert from `FunctionTool`)
   - **Type conversions (Leap SDK → OpenAI types)**:
     - `List<ResponseInputItem>` → `List<ChatMessage>` (for Leap SDK)
     - `List<FunctionTool>` → `List<LeapFunction>` (for registration)
     - `MessageResponse` → `ResponseStreamEvent` (for streaming)
     - `ChatModel` → ignore (local model doesn't use model parameter)
   - Implement `chatWithTools()`:
     - Convert `List<ResponseInputItem>` to `List<ChatMessage>` for Leap SDK
     - Register tools as `LeapFunction` objects
     - Call `Conversation.generateResponse()` (non-streaming: collect all chunks)
     - Parse `MessageResponse` to extract text and function calls
     - Convert back to `ResponsesResult`
   - Implement `chatWithToolsStreaming()`:
     - Use `Conversation.generateResponse()` which returns `Flow<MessageResponse>`
     - Convert `MessageResponse` events to `ResponseStreamEvent`
     - Handle `MessageResponse.Chunk`, `MessageResponse.FunctionCalls`, etc.
   - Error handling:
     - Wrap Leap SDK exceptions in appropriate error types
     - Handle model loading failures gracefully
   - Action: Full implementation with Leap SDK integration + type conversion layer

### Phase 4: No Changes Needed to Turn.kt or ToolRegistry
**Risk: None** ✅

6. **No changes needed** - `Turn.kt` and `ToolRegistry.kt` already use OpenAI types, which we're keeping as the interface
   - `Turn.kt` already uses `ResponseInputItem`, `FunctionTool`, `ResponseStreamEvent`
   - `ToolRegistry.kt` already generates `FunctionTool`
   - Action: None - these files stay unchanged

### Phase 5: Update SessionServices for Client Selection
**Risk: Medium**

8. **Update `SessionConfig`** (`app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`)
   - Add `llmBackend: LLMBackendType` field (default: `OPENAI`)
   - Add `localLLMConfig: LocalLLMConfig?` field (optional, for local model settings)
   - Define `enum class LLMBackendType { OPENAI, LOCAL }`
   - Define `data class LocalLLMConfig(modelSlug: String, quantizationSlug: String)`
   - Action: Extend SessionConfig

9. **Update `SessionServices.create()`** (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`)
   - Add `context: Context` parameter (needed for LFM model loading)
   - Create appropriate client based on `config.llmBackend`:
     - `OPENAI`: `OpenAILLMClient(config.apiKey ?: throw IllegalArgumentException("API key required for OpenAI"))`
     - `LOCAL`: `LFMLLMClient(context, config.localLLMConfig ?: defaultLocalConfig)`
   - Handle initialization:
     - For LFM, model loading is async - may need to handle "not ready" state
     - Consider lazy initialization or background loading
   - Action: Update factory method with client selection logic

10. **Update callers of `SessionServices.create()`**
    - Find all places that call `SessionServices.create()` and add `context` parameter
    - Action: Search and update call sites

### Phase 6: Testing and Error Handling
**Risk: Medium**

11. **Handle model loading lifecycle**
    - LFM model loading is async and can take time
    - Consider:
      - Lazy loading: Load model on first use
      - Background loading: Start loading when SessionServices is created
      - Status tracking: Expose `isReady()` method on `LLMClient`
    - Action: Implement appropriate loading strategy

12. **Error handling for local model**
    - Handle `LeapModelLoadingException` gracefully
    - Consider fallback to OpenAI if local model fails to load
    - Log appropriate error messages
    - Action: Add error handling and logging

13. **Update UI/Config to support backend selection**
    - If there's a settings UI, add option to select LLM backend
    - Update any config files/documentation
    - Action: Check for UI components that need updating

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Type conversion complexity | M | M | Conversion isolated to LFMLLMClient only, create clear conversion functions, test thoroughly |
| Leap SDK API differences | M | H | Study Leap SDK docs and examples carefully, test early |
| Model loading performance | H | M | Implement async loading, show loading state to user |
| Memory usage (local model) | M | M | Monitor memory, ensure device has sufficient RAM (3GB+) |
| Streaming API differences | M | M | Map Leap SDK streaming events carefully to abstract events |
| Tool calling format differences | M | H | Study Leap SDK function calling API, ensure compatibility |
| Breaking existing code | L | H | Careful refactoring, test after each phase |

## Testing Strategy

### Unit Tests
- Test type conversions in `LFMLLMClient` (Leap SDK types ↔ OpenAI types)
- Test `OpenAILLMClient` with mocked OpenAI SDK
- Test `LFMLLMClient` with mocked Leap SDK (if possible)

### Integration Tests
- Test `Turn.kt` with both client types
- Test `SessionServices.create()` with different backend types
- Test end-to-end flow with both backends

### Manual Testing
- Load LFM model on physical device (requires 3GB+ RAM)
- Test tool calling with LFM model
- Test streaming with LFM model
- Verify conversation history works correctly
- Test error handling (model loading failure, etc.)
- Compare behavior between OpenAI and LFM implementations

## Implementation Notes

### Leap SDK Usage Pattern (from example)
```kotlin
// Load model
val modelRunner = LeapDownloader.loadModel(
    modelSlug = "LFM2.5-1.2B-Instruct",
    quantizationSlug = "Q4_K_M"
)

// Create conversation
val conversation = modelRunner.createConversation(systemPrompt)

// Register functions
conversation.registerFunction(LeapFunction(...))

// Generate response (streaming)
conversation.generateResponse(message).collect { event ->
    when (event) {
        is MessageResponse.Chunk -> { /* text delta */ }
        is MessageResponse.FunctionCalls -> { /* tool calls */ }
        // ...
    }
}
```

### Key Differences to Handle
1. **History format**: Leap SDK uses `List<ChatMessage>`, OpenAI uses `List<ResponseInputItem>`
   - **Conversion**: `ResponseInputItem` → `ChatMessage` in `LFMLLMClient`
   - Need to handle function call/output items (may need to flatten into messages)
2. **Tool registration**: Leap SDK registers functions on `Conversation`, OpenAI passes in request
   - **Conversion**: `FunctionTool` → `LeapFunction` before calling `registerFunction()`
3. **Streaming events**: Different event types need mapping
   - **Conversion**: `MessageResponse` → `ResponseStreamEvent` in streaming flow
4. **Model loading**: Leap SDK requires async loading, OpenAI is instant
   - Handle lazy/background loading strategy
5. **Error types**: Different exception types need unified handling
   - Wrap `LeapModelLoadingException` appropriately

### Default Configuration
```kotlin
val defaultLocalLLMConfig = LocalLLMConfig(
    modelSlug = "LFM2.5-1.2B-Instruct",
    quantizationSlug = "Q4_K_M"
)
```

## Success Criteria

1. ✅ `LLMClient` is abstract, `OpenAILLMClient` and `LFMLLMClient` both implement it
2. ✅ `Turn.kt` and `Agent.kt` work unchanged with both implementations
3. ✅ LFM model loads successfully on device
4. ✅ Tool calling works with LFM model
5. ✅ Streaming works with LFM model
6. ✅ Conversation history is maintained correctly
7. ✅ Error handling is robust
8. ✅ No breaking changes to existing OpenAI functionality

**Proceed? (yes/modify/no)**
