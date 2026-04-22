# Agent Core Review

> **Module**: `agent/` - Agent.kt, Turn.kt, AgentConfig.kt, AgentSource.kt
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The Agent Core implements a ReAct (Reasoning + Acting) loop that:
1. Captures the current screen state (Perceive)
2. Calls the LLM for next action decision (Think)
3. Executes tool calls via ToolRouter (Act)
4. Captures post-action screen state (Observe)
5. Repeats until goal achieved, max turns, or stopped

The `Turn` class handles a single LLM call including message construction and response parsing for tool calls.

---

## High-Risk Issues (Must Fix)

### H1. Double Screen Capture in Observation Phase
**Location**: `Agent.kt:216-227` and `BaseTool.kt:193-208`

**Problem**: The observation capture happens twice:
1. `BaseTool.capturePostActionObservation()` captures screen after tool execution (but result is unused)
2. `Agent.captureObservation()` captures screen again after tool execution

The `ToolExecutionResult.Success.observation` field is populated but never surfaced to the Agent. Instead, the Agent performs a redundant capture.

**Impact**: 
- Wasted performance (double screen capture per tool call)
- Potential state inconsistency if screen changes between captures
- The 300ms delay in BaseTool + 500ms delay in Agent = 800ms total observation delay

**Fix**:
```kotlin
// Option 1: Remove BaseTool observation capture (simpler)
// In BaseTool.kt, remove capturePostActionObservation() call

// Option 2: Use BaseTool observation in Agent (recommended)
// In Agent.kt, extract observation from ToolCallResult instead of re-capturing
val result = services.toolRouter.execute(...)
val observation = when (result) {
    is ToolCallResult.Success -> result.observation // Need to add this field
    else -> captureObservation()
}
```

---

### H2. Snapshot Staleness in Multi-Tool Execution
**Location**: `Agent.kt:191-193`

**Problem**: When executing multiple tool calls from a single LLM response, all tool calls use the SAME snapshot captured at the beginning of the turn:
```kotlin
val context = SimpleToolRouterContext(
    platform = services.platform,
    currentSnapshot = snapshot  // Same snapshot for all tools!
)
```

If an LLM returns multiple tool calls (e.g., click then type), the second tool uses the pre-first-tool snapshot, which may have stale element indices.

**Impact**: Element index mismatch causing wrong element interaction or "element not found" errors.

**Fix**:
```kotlin
for (toolCall in turnResult.toolCalls) {
    // Re-capture snapshot before each tool if not the first one
    val currentSnapshot = if (toolCall != turnResult.toolCalls.first()) {
        services.platform.captureScreen()
    } else {
        snapshot
    }
    
    val context = SimpleToolRouterContext(
        platform = services.platform,
        currentSnapshot = currentSnapshot
    )
    // ... execute tool
}
```

---

### H3. Tool Call Parsing Regex May Miss Valid JSON
**Location**: `Turn.kt:188` and `Turn.kt:199`

**Problem**: The primary regex for tool blocks:
```kotlin
val toolPattern = Regex("```tool\\s*\\n?([\\s\\S]*?)\\n?```", RegexOption.MULTILINE)
```

And the fallback JSON pattern:
```kotlin
val jsonPattern = Regex("""\{[^{}]*"name"\s*:\s*"[^"]+"\s*[^{}]*\}""")
```

Issues:
1. Primary pattern requires `tool` immediately after backticks - LLM might add spaces: `` ``` tool ``
2. Fallback pattern `[^{}]*` cannot match nested JSON (arguments with nested objects will fail)
3. Neither handles CRLF line endings properly

**Impact**: Valid tool calls may be missed, causing agent to misunderstand LLM intent.

**Fix**:
```kotlin
// More robust primary pattern
val toolPattern = Regex("```\\s*tool\\s*\\n([\\s\\S]*?)\\n\\s*```", RegexOption.MULTILINE)

// For fallback, use proper JSON extraction
private fun extractJsonObjects(text: String): List<String> {
    val results = mutableListOf<String>()
    var depth = 0
    var start = -1
    
    text.forEachIndexed { i, c ->
        when (c) {
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    results.add(text.substring(start, i + 1))
                    start = -1
                }
            }
        }
    }
    return results
}
```

---

### H4. Completion Detection False Positives
**Location**: `Turn.kt:212-217`

**Problem**: Completion is detected based on substring matching:
```kotlin
val isComplete = toolCalls.isEmpty() && (
    response.contains("DONE:", ignoreCase = false) ||
    response.contains("goal achieved", ignoreCase = true) ||
    response.contains("task completed", ignoreCase = true) ||
    response.contains("successfully completed", ignoreCase = true)
)
```

This can trigger false positives if:
- LLM says "I haven't achieved the goal yet" (contains "achieved")
- LLM says "The task completed previously was X" (discussing past context)
- LLM quotes the system prompt about completion

**Impact**: Agent may terminate prematurely thinking goal is achieved.

**Fix**:
```kotlin
// Only check for explicit DONE marker first
val isComplete = toolCalls.isEmpty() && (
    response.startsWith("DONE:", ignoreCase = false) ||
    response.contains("\nDONE:", ignoreCase = false) ||
    // More specific patterns
    response.matches(Regex(".*(?:^|\\n)(?:I have|The goal has been|Successfully|Done[.!:]).*", RegexOption.DOT_MATCHES_ALL))
)
```

---

### H5. Network Error Classification Inverted
**Location**: `Agent.kt:254-263`

**Problem**: The error recovery logic has inverted logic:
```kotlin
val isNetworkError = message.contains("internet", ignoreCase = true) || ...

TurnOutcome.Error(
    message = message.ifEmpty { "Unknown error" },
    recoverable = !isNetworkError  // WRONG: Network errors ARE often recoverable!
)
```

Network errors (timeout, connection reset) are typically transient and recoverable with retry. Making them non-recoverable causes the agent to stop on temporary network issues.

**Impact**: Agent stops unnecessarily on transient network errors.

**Fix**:
```kotlin
// Determine recoverability based on error type
val isRecoverable = when {
    e is java.net.SocketTimeoutException -> true  // Retry-able
    e is java.net.UnknownHostException -> false   // DNS failure - unlikely to resolve
    message.contains("rate limit", ignoreCase = true) -> true  // Retry after backoff
    message.contains("connection", ignoreCase = true) -> true  // Often transient
    else -> false
}

TurnOutcome.Error(
    message = message.ifEmpty { "Unknown error" },
    recoverable = isRecoverable
)
```

---

## Medium Issues (Should Fix)

### M1. Missing Tool Call ID Linkage
**Location**: `Agent.kt:181-188` vs `Agent.kt:220-226`

**Problem**: Tool call is recorded with `toolCall.id`, but the tool result uses `result.callId` which is generated by ToolRouter. These are different IDs, breaking the call/output linkage in history.

```kotlin
// Recording call
services.historyManager.addItem(
    ResponseItem.FunctionCall(
        id = toolCall.id,  // UUID from Turn.kt
        ...
    )
)

// Recording output
services.historyManager.addItem(
    ResponseItem.FunctionCallOutput(
        callId = toolCall.id,  // Should match, but result.callId is different
        ...
    )
)
```

**Impact**: HistoryManager normalization may create duplicate outputs or orphan calls.

**Fix**: Use consistent ID throughout:
```kotlin
val callId = toolCall.id  // Use Turn-generated ID consistently
// Pass this to ToolRouter or ignore ToolRouter's generated ID
```

---

### M2. Pause State Race Condition
**Location**: `Agent.kt:60`, `Agent.kt:324-327`, `Agent.kt:329-332`

**Problem**: `pauseState` is a `MutableStateFlow<Boolean>` but `pause()` and `resume()` are `suspend fun` that modify it without synchronization:
```kotlin
suspend fun pause() {
    pauseState.value = true  // No atomicity guarantee with emitStatus
    emitStatus("⏸️ Paused")
}
```

If `pause()` and `resume()` are called rapidly in succession, the state and emitted status could become inconsistent.

**Impact**: UI may show "Paused" but agent is actually running, or vice versa.

**Fix**: Use atomic operations or mutex:
```kotlin
private val pauseMutex = Mutex()

suspend fun pause() = pauseMutex.withLock {
    if (!pauseState.value) {
        pauseState.value = true
        emitStatus("⏸️ Paused")
    }
}
```

---

### M3. History Manager Not Receiving LLM Response on Tool Parse Error
**Location**: `Agent.kt:164-170`

**Problem**: The assistant response is only recorded if `turnResult.content != null`:
```kotlin
if (turnResult.content != null) {
    services.historyManager.addItem(
        ResponseItem.Message(role = "assistant", content = turnResult.content)
    )
}
```

However, `content` is the raw LLM response which should never be null (it's the response string). The condition should check for non-empty instead.

**Impact**: Empty LLM responses might not be recorded, though this is an edge case.

**Fix**:
```kotlin
if (!turnResult.content.isNullOrBlank()) {
    services.historyManager.addItem(...)
}
```

---

### M4. Tool Instructions Hardcoded in Turn.kt
**Location**: `Turn.kt:109-173`

**Problem**: Tool instructions are hardcoded with specific tools (click, type, scroll, etc.) rather than dynamically generated from the ToolRegistry.

```kotlin
private fun buildToolInstructions(): String {
    return """
        1. **click** - Click on a UI element
           Arguments: {"element_index": <integer>}
        ...
    """.trimIndent()
}
```

This violates DRY - tool definitions exist in ToolRegistry but are duplicated here.

**Impact**: If tools are added/removed/modified, instructions may become out of sync.

**Fix**:
```kotlin
private fun buildToolInstructions(): String {
    val toolDocs = toolRegistry.getAll().joinToString("\n\n") { tool ->
        """
        **${tool.name}** - ${tool.description}
        Parameters: ${tool.parameterSchema.toString(2)}
        """.trimIndent()
    }
    
    return """
        ## Tool Usage
        When you need to perform an action, respond with a tool call in this EXACT format:
        ```tool
        {"name": "TOOL_NAME", "arguments": {"param": value}}
        ```
        
        ## Available Tools
        $toolDocs
        
        ## Rules
        - Use ONLY ONE tool call per response
        ...
    """.trimIndent()
}
```

---

### M5. AgentSource Enum Unused
**Location**: `AgentSource.kt`

**Problem**: The `AgentSource` enum (Primary, SubAgent) is defined but never used anywhere in the codebase. It's a placeholder for future multi-agent support.

**Impact**: Dead code that may confuse readers.

**Fix**: Either remove or add a TODO comment explaining the future plan:
```kotlin
/**
 * AgentSource - Identifies whether an agent is primary or delegated.
 * 
 * TODO: Not yet implemented. Will be used when sub-agent spawning is added.
 * For now, all agents are implicitly Primary.
 */
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. Magic Number for UI Settle Delay
**Location**: `Agent.kt:273`

The 500ms delay is a magic number:
```kotlin
delay(500) // Brief delay for UI to settle
```

Consider making this configurable via `AgentConfig`:
```kotlin
data class AgentConfig(
    ...
    val observationDelayMs: Long = 500,
)
```

---

### L2. Turn Number Not Used in TurnCompleted Event
**Location**: `Agent.kt:369-376`

`emitTurnCompleted` emits `turnNumber = turnCount` but the `turnId` is `"turn-$turnCount"` - redundant data.

Consider removing one or the other for cleaner API.

---

### L3. Default System Prompt Could Be External
**Location**: `Agent.kt:38-55`

The system prompt is a large string literal inside the code. Consider loading from a resource file for easier modification without recompilation.

---

### L4. Observation Sealed Class Has Unused Variant
**Location**: `Agent.kt:392-395`

`Observation.TextOutput` is defined but never instantiated. All observations are `ScreenState`. Either remove or document the intended use case.

---

## Questions

1. **Multi-tool per turn**: The system prompt says "Use ONLY ONE tool call per response" but the code handles multiple. Is this intentional? If so, should the system prompt be updated?

2. **Sub-agent interface**: `cancellationSignal: CompletableDeferred<AgentStopReason>` seems designed for external cancellation (perhaps from a parent agent). Is this the intended use case?

3. **Context window management**: There's no explicit check for context window limits before calling the LLM. Is this handled elsewhere or assumed to be sufficient?
