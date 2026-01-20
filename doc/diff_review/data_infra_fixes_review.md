# Diff Review: Data & Infrastructure Fixes

> **Reviewer**: Code review following `sop/diff_review.md`
> **Files Reviewed**: `LLMClient.kt`, `HistoryManager.kt`, `PolicyEngine.kt`, `Perceptor.kt`, `Models.kt`, `AccessibilityPlatform.kt`, `Turn.kt`, `Agent.kt`, `SessionServices.kt`, `AgentSession.kt`, `AgentService.kt`
> **Source**: Fixes for `doc/review/summary/data_infra_summary.md`

---

## 1) Summary

These changes implement fixes for all high-priority issues and selected medium-priority issues from `doc/review/summary/data_infra_summary.md`:

1. **LLMClient converted to instance-based** - No longer a singleton; supports DI, thread-safe initialization, different API keys per session (H1)
2. **Token estimation bug fixed** - Uses nullable `Long?` to avoid returning 0 on first call (H2)
3. **FunctionCall.estimateTokens() fixed** - Operator precedence bug corrected (H3)
4. **Tool definitions verified** - Turn.kt already uses `toolRegistry.generateResponsesApiTools()` (H4)
5. **Memory leak in Perception fixed** - AccessibilityNodeInfo references no longer stored; uses bounds for actions (H5)
6. **PolicyEngine thread safety** - Uses `AtomicReference` for approval mode (M2)
7. **TruncationPolicy.NONE overflow fixed** - Sentinel value with special handling (M3)
8. **extractRetryAfter patterns fixed** - More specific regex patterns to avoid false matches (M4)
9. **Documentation added** - PolicyEngine unused entries, dropLastNUserTurns algorithm, TODOs for deferred items

---

## 2) High-Risk Issues - All Fixed

### H1. LLMClient Singleton Converted to Class

**Files Changed**: `LLMClient.kt`, `SessionServices.kt`, `AgentSession.kt`, `AgentService.kt`, `Turn.kt`, `Agent.kt`

**Before**:
```kotlin
object LLMClient {
    private var client: OpenAIClient? = null
    private var isInitialized = false
    fun initialize(apiKey: String) { ... }
}
```

**After**:
```kotlin
class LLMClient(apiKey: String) {
    private val client: OpenAIClient
    init {
        client = OpenAIOkHttpClient.builder().apiKey(apiKey).build()
    }
}
```

**Integration**:
- `SessionServices` now holds `llmClient` as a member
- `SessionServices.create()` accepts `apiKey` parameter
- `Turn` receives `llmClient` via constructor injection
- `AgentSession.create()` passes `apiKey` through to `SessionServices`
- `AgentService` passes `apiKey` to session creation

---

### H2. Token Estimation Bug Fixed

**File**: `HistoryManager.kt:34, 103-110`

**Before**:
```kotlin
private var lastTokenEstimate: Long = 0  // INITIALIZED TO 0

fun estimateTokenCount(): Long {
    if (lastTokenEstimate >= 0) {  // 0 IS >= 0, returns immediately!
        return lastTokenEstimate
    }
    ...
}
```

**After**:
```kotlin
private var lastTokenEstimate: Long? = null  // NULLABLE

fun estimateTokenCount(): Long {
    lastTokenEstimate?.let { return it }  // Only returns if cached
    val estimate = items.sumOf { it.estimateTokens() }
    lastTokenEstimate = estimate
    return estimate
}
```

---

### H3. FunctionCall.estimateTokens() Expression Bug Fixed

**File**: `HistoryManager.kt:351`

**Before**:
```kotlin
override fun estimateTokens(): Long = 
    (name.length + arguments.toString().length) * 0.25f.toLong() + 10
//                                               ^^^^^^^^^^^^^^^^
//                                               0.25f.toLong() = 0!
```

**After**:
```kotlin
override fun estimateTokens(): Long = 
    ((name.length + arguments.toString().length) * 0.25f).toLong() + 10
//   ^                                                  ^
//   Parentheses ensure multiplication happens BEFORE toLong()
```

---

### H4. Tool Definitions - Already Fixed

**Status**: Verified fixed in previous work.

`Turn.kt:59` uses `toolRegistry.generateResponsesApiTools()` for dynamic tool schema generation.

---

### H5. Memory Leak in Perception Fixed

**Files Changed**: `Models.kt`, `Perceptor.kt`, `AccessibilityPlatform.kt`

**Before** (Models.kt):
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val rootOriginal: AccessibilityNodeInfo?,  // Memory leak!
    val elements: List<PerceptionElement>,
    val rawMap: Map<Int, AccessibilityNodeInfo>  // Never recycled!
)
```

**After**:
```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>  // Only data, no node references
)
```

**Action Changes** (AccessibilityPlatform.kt):
- `performClick()` now uses stored `center` coordinates with gesture-based tap
- `performType()` re-queries the accessibility tree at action time to get fresh node
- Added `findNodeAtLocation()` helper for type actions

---

## 3) Medium Issues Fixed

### M2. PolicyEngine Thread Safety

**File**: `PolicyEngine.kt:19, 135-138`

**Before**:
```kotlin
class PolicyEngine(private var approvalMode: ApprovalMode = ApprovalMode.SMART)
```

**After**:
```kotlin
class PolicyEngine(initialApprovalMode: ApprovalMode = ApprovalMode.SMART) {
    private val approvalMode = AtomicReference(initialApprovalMode)
    
    fun setApprovalMode(mode: ApprovalMode) {
        val oldMode = approvalMode.getAndSet(mode)
        ...
    }
    
    fun getApprovalMode(): ApprovalMode = approvalMode.get()
}
```

---

### M3. TruncationPolicy.NONE Overflow Fixed

**File**: `HistoryManager.kt:304-306, 238-248`

**Before**:
```kotlin
enum class TruncationPolicy(val maxTokens: Int) {
    NONE(Int.MAX_VALUE),  // Can overflow in calculations!
    ...
}
```

**After**:
```kotlin
enum class TruncationPolicy(val maxTokens: Int) {
    NONE(-1),  // Sentinel value
    ...
}

private fun truncateOutput(...): ResponseItem.FunctionCallOutput {
    if (policy == TruncationPolicy.NONE) {
        return output  // No truncation, avoid calculation
    }
    ...
}
```

---

### M4. extractRetryAfter Patterns Fixed

**File**: `LLMClient.kt:233-250`

**Before** (overly broad):
```kotlin
Regex("""(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
// Matches "Request failed after 5 seconds of processing" -> extracts 5!
```

**After** (specific):
```kotlin
val patterns = listOf(
    Regex("""retry.?after[:\s]+(\d+)""", RegexOption.IGNORE_CASE),
    Regex("""(?:please\s+)?wait(?:\s+for)?\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE),
    Regex("""try\s+again\s+in\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE),
    Regex("""available\s+in\s+(\d+)\s*seconds?""", RegexOption.IGNORE_CASE)
)
```

---

## 4) Documentation & TODOs Added

| Item | Location | Action |
|------|----------|--------|
| M1. Rate limit backoff | `LLMClient.kt:82-86` | Added TODO comment explaining current behavior |
| M5. PolicyEngine unused entries | `PolicyEngine.kt:38-44` | Documented as "reserved for future tools" |
| M7. dropLastNUserTurns() | `HistoryManager.kt:128-153` | Added algorithm documentation |
| M8. Hardcoded risk levels | `PolicyEngine.kt:26` | Added TODO for configurable risk levels |

---

## 5) Verified Already Fixed

| Issue | Status | Evidence |
|-------|--------|----------|
| H4. Tool definitions | Fixed | `Turn.kt:59` uses `generateResponsesApiTools()` |
| M6. ChatMessage | Fixed | File deleted; using `ResponseItem` classes |
| Q3. Multi-model support | Fixed | `Turn.kt:51, 86-100` accepts and converts model names |

---

## 6) Verification Checklist

### Original Issues - Status

| Issue | Status | Verification |
|-------|--------|--------------|
| H1. LLMClient singleton | ✅ Fixed | Now instance-based class with DI |
| H2. Token estimation bug | ✅ Fixed | Uses nullable `Long?` |
| H3. estimateTokens() bug | ✅ Fixed | Correct operator precedence |
| H4. Tool definitions | ✅ Verified | Uses ToolRegistry |
| H5. Memory leak | ✅ Fixed | No AccessibilityNodeInfo storage |
| M1. Rate limit backoff | ✅ TODO added | Comment added |
| M2. PolicyEngine thread safety | ✅ Fixed | Uses AtomicReference |
| M3. TruncationPolicy overflow | ✅ Fixed | Sentinel value with special handling |
| M4. extractRetryAfter patterns | ✅ Fixed | More specific patterns |
| M5. Unused entries | ✅ Documented | "Reserved for future" |
| M6. ChatMessage | ✅ Verified | File deleted |
| M7. dropLastNUserTurns | ✅ Documented | Algorithm comments added |
| M8. Hardcoded risk levels | ✅ TODO added | Comment added |
| Q3. Multi-model support | ✅ Verified | Model conversion working |

### Code Quality

- [x] No obvious memory leaks (AccessibilityNodeInfo issue fixed)
- [x] Thread safety improved (PolicyEngine, LLMClient initialization)
- [x] Error handling maintained
- [x] Logging adequate for debugging
- [x] No hardcoded secrets or credentials

---

## 7) Conclusion

All high-priority issues from the data_infra_summary have been addressed:
- The LLMClient singleton has been converted to a properly injectable class
- Memory leaks from AccessibilityNodeInfo storage have been fixed
- Token estimation bugs have been corrected
- Thread safety has been improved

Medium-priority issues have been addressed through fixes (M2, M3, M4) or documentation/TODOs (M1, M5, M7, M8).

**Verdict**: Data & Infrastructure fixes complete.
