# Data & Infrastructure - Consolidated Review Summary

> **Files**: `data/llm/LLMClient.kt`, `data/llm/ChatMessage.kt`, `infra/history/HistoryManager.kt`, `infra/policy/PolicyEngine.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. LLMClient is a Global Singleton With Mutable State
**Reviewer**: Claude
**Location**: `LLMClient.kt:20-24`

**Problem**: `LLMClient` is an `object` (singleton) with mutable state:
```kotlin
object LLMClient {
    private var client: OpenAIClient? = null
    private var isInitialized = false
}
```

Issues:
1. No thread synchronization on `initialize()` - race condition
2. Calling `initialize()` with different API keys replaces client for ALL sessions
3. No way to have different API keys for different sessions
4. State persists across sessions

**Impact**: Security (wrong API key used), race conditions, unpredictable behavior.

**Fix**: Make instance-based and inject via SessionServices:
```kotlin
class LLMClient(private val apiKey: String) { ... }
```

**Team Note**: fix it.

---

### 2. Token Estimation Bug - Always Returns 0 Initially
**Reviewer**: Claude
**Location**: `HistoryManager.kt:103-110`

**Problem**: Token cache initialized to 0 and checked with `>= 0`:
```kotlin
private var lastTokenEstimate: Long = 0  // INITIALIZED TO 0

fun estimateTokenCount(): Long {
    if (lastTokenEstimate >= 0) {  // 0 IS >= 0, returns immediately!
        return lastTokenEstimate
    }
    lastTokenEstimate = items.sumOf { it.estimateTokens() }
    return lastTokenEstimate
}
```

First call returns 0 without calculating!

**Impact**: First token estimate is always 0, leading to incorrect context window decisions.

**Fix**: Use nullable type:
```kotlin
private var lastTokenEstimate: Long? = null
fun estimateTokenCount(): Long {
    lastTokenEstimate?.let { return it }
    // ... calculate
}

```

**Team Note**: fix it.

---

### 3. FunctionCall.estimateTokens() Expression Bug
**Reviewer**: Claude
**Location**: `HistoryManager.kt:351`

**Problem**: Type error that compiles due to autoboxing:
```kotlin
override fun estimateTokens(): Long = 
    (name.length + arguments.toString().length) * 0.25f.toLong() + 10
//                                               ^^^^^^^^^^^^^^^^
//                                               0.25f.toLong() = 0!
```

`0.25f.toLong()` equals `0`, so entire multiplication is 0. Result is always 10.

**Impact**: Function call token estimates always 10, massively underestimated counts.

**Fix**: 
```kotlin
((name.length + arguments.toString().length) * 0.25f).toLong() + 10
```

**Team Note**: fix it.
---

### 4. Inconsistent Tool Definitions (Cross-Module)
**Consensus**: Gemini, Claude (also in Agent Core)
**Location**: `ToolRegistry.kt` vs `Turn.kt`

`ToolRegistry` can generate dynamic schemas, but `Turn.kt` uses hardcoded string. Adding tool requires updates in two places.

**Impact**: High risk of divergence, hallucinated tools.

**Fix**: Refactor Turn.kt to use ToolRegistry for generating prompt instructions.

**Team Note**: This should have been fixed. Double check if this is the case, if not, fix it.

---

### 5. Memory Leak Potential in Perception
**Reviewer**: Gemini
**Location**: `Perceptor.kt:38`

`ScreenSnapshot` retains raw `AccessibilityNodeInfo` root. Comment explicitly warns about memory leaks. (See Platform review for details.)

**Team Note**: fix it. Use platform_perception_summary.md as needed.
---

## Medium Issues (Should Fix)

### M1. Rate Limit Retry Doesn't Respect Backoff Properly
**Reviewer**: Claude
**Location**: `LLMClient.kt:48-83`

Backoff is updated AFTER delay, not before. First retry uses initial backoff. Also, after max retries, throws `lastException` which may be from earlier attempt.

**Fix**: Update backoff BEFORE delay for next attempt, throw latest exception.

**Team Note**: Add a TODO, but does not fix it for now.
---

### M2. PolicyEngine Approval Mode Not Thread-Safe
**Reviewer**: Claude
**Location**: `PolicyEngine.kt:18-19`, `PolicyEngine.kt:135-138`

`approvalMode` is a var modified without synchronization. Concurrent `check()` and `setApprovalMode()` causes undefined behavior.

**Fix**: Use `AtomicReference` for approval mode.

**Team Note**: fix it.
---

### M3. TruncationPolicy.NONE Uses MAX_VALUE
**Reviewer**: Claude
**Location**: `HistoryManager.kt:306`

`Int.MAX_VALUE / 0.25f` can cause overflow in `truncateOutput()`.

**Fix**: Handle NONE specially - return output without truncation calculation.

**Team Note**: fix it.
---

### M4. extractRetryAfter() Patterns Too Broad
**Reviewer**: Claude
**Location**: `LLMClient.kt:186-204`

Pattern `(\d+)\s*seconds?` matches ANY number followed by "seconds" anywhere. E.g., "Request failed after 5 seconds of processing" extracts 5.

**Fix**: Use more specific patterns like "try again in N seconds".

**Team Note**: If this problem still exists, fix it.
---

### M5. PolicyEngine.DEFAULT_RISK_LEVELS Has Unused Entries
**Reviewer**: Claude
**Location**: `PolicyEngine.kt:38-44`

Risk levels defined for tools that don't exist (install, uninstall, delete, purchase, send).

**Fix**: Remove or document as "reserved for future".

**Team Note**: document as "reserved for future".
---

### M6. ChatMessage Too Simple
**Reviewer**: Claude
**Location**: `ChatMessage.kt`

Doesn't support function call messages, function results, multi-part content (images), or OpenAI's native function calling format.

**Fix**: Either expand or document why text-based tool calls are preferred.

**Team Note**: This should have been fixed. If not fixed, propose solution to fix it.
---

### M7. HistoryManager.dropLastNUserTurns() Complex Logic
**Reviewer**: Claude
**Location**: `HistoryManager.kt:128-153`

Index calculation is confusing and would benefit from documentation explaining the algorithm.

**Team Note**: fix it with some documentation in code.

---

### M8. Hardcoded Policy Risk Levels
**Reviewer**: Gemini
**Location**: `PolicyEngine.kt:26`

Risk levels hardcoded. No way to configure per-deployment.

**Fix**: Load from configuration file or allow remote configuration.

**Team Note**: add todo, but skip for now.
---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| Token estimation accuracy | Gemini | `HistoryManager.kt` | Use proper tokenizer (JTokkit) |
| Ghost Snapshot documentation | Gemini | `HistoryManager.kt` | Document why these exist |
| HistoryManager track by type | Claude | `HistoryManager.kt` | Add getStats() method |
| PolicyEngine custom callbacks | Claude | `PolicyEngine.kt` | Support custom evaluators per tool |
| LLMClient log token usage | Claude | `LLMClient.kt` | Log response.usage() |
| GhostSnapshot never created | Claude | `HistoryManager.kt:372-377` | Use in compression or remove |

---

## Open Questions

1. **Context window limits**: Code estimates tokens but doesn't prevent exceeding model limits. Should there be a hard check before sending to LLM?

2. **API key security**: API key passed from MainActivity and logged (first 10 chars). Secure enough for production?

3. **Multi-model support**: SessionConfig has `model: String` but LLMClient hardcodes GPT_4O. Is model switching supposed to be supported?
**Team Note**: supposed to be supported. This should have been fixed, fix it if not yet.