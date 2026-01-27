# Type Strategy Evaluation: Reuse OpenAI Types vs Abstract Types

## Option 1: Reuse OpenAI Responses API Types (Simplified)

Keep using `ResponseInputItem`, `FunctionTool`, `ResponseStreamEvent`, `ChatModel` as the interface types. `LFMLLMClient` converts Leap SDK types to OpenAI types internally.

### Pros ✅

1. **Minimal Changes**
   - `Turn.kt` stays unchanged (already uses `ResponseInputItem`, `FunctionTool`, `ResponseStreamEvent`)
   - `ToolRegistry.kt` stays unchanged (already generates `FunctionTool`)
   - Only `LLMClient.kt` and new `LFMLLMClient.kt` need changes
   - **Impact: ~2 files changed vs ~6 files**

2. **Faster Implementation**
   - No abstraction layer to design and implement
   - No type conversion functions needed in `Turn.kt` or `ToolRegistry.kt`
   - Conversion logic isolated to `LFMLLMClient` only

3. **Type Safety Preserved**
   - Existing code already type-safe with OpenAI types
   - No risk of breaking type contracts in `Turn.kt`

4. **Less Code**
   - No `LLMClientTypes.kt` file needed
   - No conversion functions in `Turn.kt` or `ToolRegistry.kt`
   - Simpler overall codebase

### Cons ❌

1. **Semantic Mismatch**
   - OpenAI types don't perfectly match Leap SDK concepts
   - `ResponseInputItem` is designed for OpenAI's API structure
   - Leap SDK uses `ChatMessage` with different structure (role, content array)
   - May need to force-fit Leap SDK concepts into OpenAI types

2. **Dependency on OpenAI SDK**
   - Even when using local model, codebase depends on OpenAI SDK types
   - Adds ~2-3MB to APK even if only using local model
   - **Impact: Medium** (SDK is already a dependency, but this makes it required)

3. **Conversion Complexity in LFMLLMClient**
   - Still need conversion layer, just moved to `LFMLLMClient`
   - Must convert:
     - `List<ChatMessage>` → `List<ResponseInputItem>`
     - `LeapFunction` → `FunctionTool`
     - `MessageResponse` → `ResponseStreamEvent`
   - Conversion logic may be complex (e.g., `ChatMessage` has different structure)

4. **Future Extensibility**
   - If adding another backend (Anthropic, Gemini, etc.), would need to convert to OpenAI types
   - Creates awkward dependency: "all backends must speak OpenAI's language"
   - **Impact: Low** (only relevant if adding more backends)

5. **Potential Confusion**
   - Using OpenAI types for non-OpenAI backend is semantically odd
   - Code reviewers might be confused seeing `ResponseInputItem` used with local model
   - **Impact: Low** (documentation can clarify)

## Option 2: Create Abstract Types (Original Plan)

Create `LLMInputItem`, `LLMTool`, `LLMStreamEvent`, `LLMModel` as abstract types. Both implementations convert to/from these.

### Pros ✅

1. **Semantic Correctness**
   - Types represent LLM concepts, not OpenAI-specific API
   - Clear separation: abstract interface vs implementation details
   - Better for future backends

2. **No OpenAI SDK Dependency for Local Model**
   - Could theoretically remove OpenAI SDK if only using local model
   - Cleaner dependency graph

3. **Better Abstraction**
   - Interface clearly defines what LLM clients must support
   - Implementation details hidden from callers

### Cons ❌

1. **More Changes**
   - `Turn.kt` needs updates (use `LLMInputItem` instead of `ResponseInputItem`)
   - `ToolRegistry.kt` needs new method (`generateLLMTools()`)
   - More files to modify (~6 files vs ~2 files)

2. **More Code**
   - Need `LLMClientTypes.kt` with type definitions
   - Need conversion functions in both implementations
   - More complex overall

3. **Slower Implementation**
   - Need to design abstract types carefully
   - More testing needed (conversion logic in multiple places)

## Recommendation: **Reuse OpenAI Types** ✅

### Rationale

1. **Minimal Impact**: Only 2 files need changes (`LLMClient.kt` → abstract, `LFMLLMClient.kt` → new)
2. **Faster to Implement**: No abstraction layer design needed
3. **Lower Risk**: Existing code (`Turn.kt`, `ToolRegistry.kt`) stays unchanged
4. **Good Enough**: OpenAI types are well-designed and can represent the concepts we need

### Implementation Strategy

1. Make `LLMClient` abstract with OpenAI types in signature:
   ```kotlin
   abstract class LLMClient {
       abstract suspend fun chatWithTools(
           systemPrompt: String,
           inputItems: List<ResponseInputItem>,  // Keep OpenAI type
           tools: List<FunctionTool>,            // Keep OpenAI type
           model: ChatModel                      // Keep OpenAI type
       ): ResponsesResult
       
       abstract fun chatWithToolsStreaming(
           systemPrompt: String,
           inputItems: List<ResponseInputItem>,
           tools: List<FunctionTool>,
           model: ChatModel
       ): Flow<ResponseStreamEvent>              // Keep OpenAI type
   }
   ```

2. `OpenAILLMClient`: Pass through types directly (no conversion)

3. `LFMLLMClient`: Convert internally:
   - `List<ResponseInputItem>` → `List<ChatMessage>` (for Leap SDK)
   - `List<FunctionTool>` → `List<LeapFunction>` (for registration)
   - `MessageResponse` → `ResponseStreamEvent` (for streaming)
   - `ChatModel` → ignore (local model doesn't use this)

### Conversion Complexity Assessment

**Easy Conversions:**
- `FunctionTool` → `LeapFunction`: Straightforward (name, description, parameters)
- `ResponseStreamEvent` from `MessageResponse`: Need to map event types, but structure similar

**Moderate Complexity:**
- `List<ResponseInputItem>` → `List<ChatMessage>`: 
  - `ResponseInputItem.ofEasyInputMessage()` → `ChatMessage(role, textContent)`
  - `ResponseInputItem.ofFunctionCall()` → Need to handle in conversation history
  - `ResponseInputItem.ofFunctionCallOutput()` → Need to handle as tool message
  - **Note**: Leap SDK conversation history is simpler (just `List<ChatMessage>`), so we may need to flatten function call/output into messages

### Key Insight

The conversion complexity exists either way:
- **Option 1**: Conversion in `LFMLLMClient` only (isolated)
- **Option 2**: Conversion in both `OpenAILLMClient` and `LFMLLMClient` (duplicated)

Option 1 is simpler because conversion is isolated to one place.

## Decision: **Reuse OpenAI Types**

**Action Items:**
1. ✅ Skip `LLMClientTypes.kt`
2. ✅ Keep `LLMClient` abstract but use OpenAI types in signatures
3. ✅ `LFMLLMClient` converts Leap SDK types → OpenAI types internally
4. ✅ `Turn.kt` and `ToolRegistry.kt` stay unchanged
