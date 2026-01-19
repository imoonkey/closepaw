# Data & Infrastructure Review

> **Module**: `data/llm/`, `infra/history/`, `infra/policy/`
> **Reviewer**: Claude
> **Date**: January 19, 2026

## Summary

The Data & Infrastructure layer provides supporting services:
- `LLMClient`: OpenAI API wrapper with retry logic
- `HistoryManager`: Conversation history with truncation
- `PolicyEngine`: Tool approval decisions
- `ChatMessage`: Simple message type for LLM calls

---

## High-Risk Issues (Must Fix)

### H1. LLMClient is a Global Singleton With Mutable State
**Location**: `LLMClient.kt:20-24`

**Problem**: `LLMClient` is an `object` (singleton) with mutable state:
```kotlin
object LLMClient {
    private var client: OpenAIClient? = null
    private var isInitialized = false
    
    fun initialize(apiKey: String) {
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
        isInitialized = true
    }
}
```

Issues:
1. No thread synchronization on `initialize()` - race condition if called from multiple threads
2. Calling `initialize()` with different API keys replaces the client for ALL sessions
3. No way to have different API keys for different sessions
4. State persists across sessions (if one session initializes, another can use it)

**Impact**: Security (wrong API key used), race conditions, unpredictable behavior.

**Fix**: Make it instance-based and inject via SessionServices:
```kotlin
class LLMClient(private val apiKey: String) {
    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .build()
    
    suspend fun chat(messages: List<ChatMessage>): String {
        // ... existing logic without null checks
    }
}

// In SessionServices
data class SessionServices(
    val llmClient: LLMClient,  // Instance per session
    // ...
)
```

---

### H2. Rate Limit Retry Doesn't Respect Backoff Properly
**Location**: `LLMClient.kt:48-83`

**Problem**: The retry loop structure has issues:
```kotlin
for (attempt in 1..MAX_RETRIES) {
    try {
        return@withContext executeChat(messages)
    } catch (e: RateLimitException) {
        // ...
        val waitMs = e.retryAfterMs ?: backoffMs  // Uses backoffMs from LAST iteration
        delay(waitMs)
        backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()  // Updated AFTER delay
    }
}
```

On the first retry (attempt=2), it uses `INITIAL_BACKOFF_MS` (1000), then updates to 2000. But the pattern should be: wait based on CURRENT backoff, then increase for NEXT attempt.

Also, after `MAX_RETRIES` exhausted, it throws `lastException` which may be from an earlier attempt, not the most recent.

**Impact**: Suboptimal retry timing, confusing error messages.

**Fix**:
```kotlin
var backoffMs = INITIAL_BACKOFF_MS

for (attempt in 1..MAX_RETRIES) {
    try {
        return@withContext executeChat(messages)
    } catch (e: RateLimitException) {
        lastException = e
        
        if (attempt == MAX_RETRIES) {
            Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded")
            throw e  // Throw the LATEST exception
        }
        
        val waitMs = e.retryAfterMs ?: backoffMs
        Log.w(TAG, "Rate limited (attempt $attempt/$MAX_RETRIES), waiting ${waitMs}ms...")
        
        delay(waitMs)
        
        // Increase backoff for NEXT attempt (even if retry-after was used)
        backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
    }
}
```

---

### H3. HistoryManager.estimateTokenCount() Caching Bug
**Location**: `HistoryManager.kt:103-110`

**Problem**: Token cache invalidation uses -1 as sentinel:
```kotlin
private var lastTokenEstimate: Long = 0

fun estimateTokenCount(): Long {
    if (lastTokenEstimate >= 0) {  // BUG: 0 is treated as valid cached value
        return lastTokenEstimate
    }
    
    lastTokenEstimate = items.sumOf { it.estimateTokens() }
    return lastTokenEstimate
}
```

And invalidation:
```kotlin
lastTokenEstimate = -1 // Invalidate cache
```

But if the actual token count is 0 (empty history), `lastTokenEstimate >= 0` returns true, so 0 is returned without recalculation.

More importantly, `lastTokenEstimate` is initialized to 0, so the FIRST call to `estimateTokenCount()` returns 0 without calculating!

**Impact**: First token estimate is always 0, leading to incorrect context window decisions.

**Fix**: Use nullable type:
```kotlin
private var lastTokenEstimate: Long? = null

fun estimateTokenCount(): Long {
    lastTokenEstimate?.let { return it }
    
    val estimate = items.sumOf { it.estimateTokens() }
    lastTokenEstimate = estimate
    return estimate
}

// Invalidation
lastTokenEstimate = null
```

---

### H4. HistoryManager.clear() Sets Token Estimate to 0, Not Invalidated
**Location**: `HistoryManager.kt:93-96`

**Problem**: Clear sets a valid value instead of invalidating:
```kotlin
fun clear() {
    items.clear()
    lastTokenEstimate = 0  // Sets to 0, which is correct... 
    Log.d(TAG, "History cleared")
}
```

This is actually correct for clear() specifically, but inconsistent with other methods that use -1 for invalidation. The real issue is H3 above makes this ambiguous.

---

### H5. ResponseItem.FunctionCall.estimateTokens() Has Type Error
**Location**: `HistoryManager.kt:351`

**Problem**: The expression has a type error that compiles due to autoboxing:
```kotlin
override fun estimateTokens(): Long = (name.length + arguments.toString().length) * 0.25f.toLong() + 10
```

`0.25f.toLong()` equals `0`, so the entire multiplication is 0! The calculation should be:
```kotlin
((name.length + arguments.toString().length) * 0.25f).toLong() + 10
```

**Impact**: Function call token estimates are always 10, leading to massively underestimated token counts.

**Fix**:
```kotlin
override fun estimateTokens(): Long = 
    ((name.length + arguments.toString().length) * 0.25f).toLong() + 10
```

---

## Medium Issues (Should Fix)

### M1. PolicyEngine Approval Mode State Not Thread-Safe
**Location**: `PolicyEngine.kt:18-19`, `PolicyEngine.kt:135-138`

**Problem**: `approvalMode` is a `var` modified without synchronization:
```kotlin
class PolicyEngine(
    private var approvalMode: ApprovalMode = ApprovalMode.SMART
) {
    fun setApprovalMode(mode: ApprovalMode) {
        Log.d(TAG, "Approval mode changed: $approvalMode -> $mode")
        approvalMode = mode  // Not thread-safe!
    }
}
```

If `check()` and `setApprovalMode()` are called concurrently, undefined behavior.

**Impact**: Race condition causing inconsistent approval decisions.

**Fix**: Use atomic or synchronized access:
```kotlin
class PolicyEngine(
    initialMode: ApprovalMode = ApprovalMode.SMART
) {
    private val _approvalMode = AtomicReference(initialMode)
    
    fun setApprovalMode(mode: ApprovalMode) {
        val old = _approvalMode.getAndSet(mode)
        Log.d(TAG, "Approval mode changed: $old -> $mode")
    }
    
    fun check(toolName: String, params: JSONObject): PolicyDecision {
        val currentMode = _approvalMode.get()
        // ...
    }
}
```

---

### M2. TruncationPolicy.NONE Uses MAX_VALUE Which Can Overflow
**Location**: `HistoryManager.kt:306`

**Problem**:
```kotlin
enum class TruncationPolicy(val maxTokens: Int) {
    NONE(Int.MAX_VALUE),  // Can cause overflow in calculations
    // ...
}
```

In `truncateOutput()`:
```kotlin
val maxChars = (policy.maxTokens / TOKENS_PER_CHAR).toInt()
// Int.MAX_VALUE / 0.25f = huge float, then toInt() = Int.MAX_VALUE or undefined
```

**Impact**: Potential integer overflow issues.

**Fix**: Handle NONE specially or use a sentinel:
```kotlin
private fun truncateOutput(output: ResponseItem.FunctionCallOutput, policy: TruncationPolicy): ResponseItem.FunctionCallOutput {
    if (policy == TruncationPolicy.NONE) {
        return output  // No truncation
    }
    
    val maxChars = (policy.maxTokens / TOKENS_PER_CHAR).toInt()
    // ...
}
```

---

### M3. HistoryManager.dropLastNUserTurns() Has Off-By-One Risk
**Location**: `HistoryManager.kt:128-153`

**Problem**: Complex index calculation for dropping turns:
```kotlin
val userTurnPositions = items.mapIndexedNotNull { index, item ->
    if (item is ResponseItem.Message && item.role == "user") index else null
}

val cutIndex = if (n >= userTurnPositions.size) {
    userTurnPositions.first()  // Drops everything
} else {
    userTurnPositions[userTurnPositions.size - n]  // Off by one?
}
```

If `userTurnPositions = [0, 5, 10]` and `n = 1`, then:
- `userTurnPositions.size - n = 3 - 1 = 2`
- `userTurnPositions[2] = 10`
- Removes from index 10 onward

This drops the last turn starting at index 10, which seems correct. But it's confusing code that would benefit from documentation.

**Impact**: Potential logic error (needs verification).

**Fix**: Add comments explaining the algorithm:
```kotlin
/**
 * Drop the last N user turns.
 * A "turn" starts at a user message and includes all subsequent messages
 * until the next user message.
 * 
 * Example: [U, A, U, A, F, O] with n=1 removes the last U onwards: [U, A]
 */
```

---

### M4. LLMClient.extractRetryAfter() Patterns May Over-Match
**Location**: `LLMClient.kt:186-204`

**Problem**: The regex patterns are broad:
```kotlin
val patterns = listOf(
    Regex("""retry.?after[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
    Regex("""wait[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
    Regex("""(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)  // Too broad!
)
```

The last pattern matches ANY number followed by "seconds" anywhere in the error message. E.g., "Request failed after 5 seconds of processing" would extract 5.

**Impact**: Incorrect retry-after values.

**Fix**: Be more specific:
```kotlin
val patterns = listOf(
    Regex("""retry.?after[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
    Regex("""please wait[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
    Regex("""try again in (\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
)
```

---

### M5. PolicyEngine.DEFAULT_RISK_LEVELS Has Unused Entries
**Location**: `PolicyEngine.kt:38-44`

**Problem**: Risk levels are defined for tools that don't exist:
```kotlin
private val DEFAULT_RISK_LEVELS = mapOf(
    // ...
    // High risk - potentially destructive
    "install" to RiskLevel.HIGH,    // No install tool exists
    "uninstall" to RiskLevel.HIGH,  // No uninstall tool exists
    "delete" to RiskLevel.HIGH,     // No delete tool exists
    "purchase" to RiskLevel.HIGH,   // No purchase tool exists
    "send" to RiskLevel.HIGH        // No send tool exists
)
```

**Impact**: Dead configuration. May confuse developers about available tools.

**Fix**: Remove or document as "reserved for future":
```kotlin
// Current tools
"click" to RiskLevel.LOW,
// ...

// Reserved for future high-risk tools (not implemented)
// "install" to RiskLevel.HIGH,
```

---

### M6. ChatMessage Is Too Simple
**Location**: `ChatMessage.kt`

**Problem**: The `ChatMessage` class is minimal:
```kotlin
enum class Role {
    SYSTEM,
    USER,
    ASSISTANT
}

data class ChatMessage(val role: Role, val content: String)
```

It doesn't support:
- Function call messages
- Function results
- Multi-part content (images)
- Tool calls in the OpenAI format

All tool call handling is done via text parsing in Turn.kt rather than using the API's native function calling.

**Impact**: Missing native function calling support, less reliable tool parsing.

**Fix**: Either expand ChatMessage or document why text-based tool calls are preferred:
```kotlin
// Document the design decision:
/**
 * Simple chat message for text-based communication.
 * 
 * Note: We use text-based tool calls (```tool blocks) rather than OpenAI's
 * native function calling for flexibility and provider independence.
 */
data class ChatMessage(val role: Role, val content: String)
```

---

## Low-Risk Suggestions (Nice to Have)

### L1. HistoryManager Could Track Messages by Type
**Location**: `HistoryManager.kt`

Adding type-based counts would aid debugging:
```kotlin
fun getStats(): HistoryStats {
    return HistoryStats(
        totalItems = items.size,
        messages = items.count { it is ResponseItem.Message },
        functionCalls = items.count { it is ResponseItem.FunctionCall },
        functionOutputs = items.count { it is ResponseItem.FunctionCallOutput },
        estimatedTokens = estimateTokenCount()
    )
}
```

---

### L2. PolicyEngine Could Support Tool-Specific Callbacks
**Location**: `PolicyEngine.kt`

For complex approval logic, support custom evaluators:
```kotlin
private val customEvaluators = mutableMapOf<String, (JSONObject) -> PolicyDecision>()

fun registerCustomPolicy(toolName: String, evaluator: (JSONObject) -> PolicyDecision) {
    customEvaluators[toolName] = evaluator
}
```

---

### L3. LLMClient Could Log Token Usage
**Location**: `LLMClient.kt`

OpenAI responses include usage info:
```kotlin
val usage = response.usage()
Log.d(TAG, "Tokens - prompt: ${usage?.promptTokens()}, completion: ${usage?.completionTokens()}")
```

---

### L4. ResponseItem.GhostSnapshot Never Created
**Location**: `HistoryManager.kt:372-377`

`GhostSnapshot` is defined but never instantiated. Either use it in compression or remove.

---

## Questions

1. **Context window limits**: The code estimates tokens but doesn't prevent exceeding model limits. Should there be a hard check before sending to LLM?

2. **API key security**: The API key is passed from MainActivity and logged (first 10 chars). Is this secure enough for production?

3. **Multi-model support**: SessionConfig has `model: String = "gpt-4o"` but LLMClient hardcodes `ChatModel.GPT_4O`. Is model switching supported?
