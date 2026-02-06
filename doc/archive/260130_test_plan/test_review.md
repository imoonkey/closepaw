# Core Test Review (Robustness-Focused)

> Review Date: 2026-01-29
> Principle: Tests that improve robustness, stability, scalability. Not coverage theater.

## TL;DR

**High-Value Tests (Keep/Expand):**
- `HistoryManagerTest` — Tests real data invariants that prevent corruption
- `PolicyEngineTest` — Pure logic, deterministic, catches real policy bugs
- `ToolRouterTest` — State machine logic is complex enough to warrant tests
- `SessionStorageTest` — I/O edge cases matter for data durability

**Low-Value Tests (Reconsider):**
- `TurnTest` — Completion detection is trivial; test adds noise
- `SessionAgentRunnerTest` — Single happy-path test with fake LLM; doesn't catch real failures
- `AgentSessionTest` — Session lifecycle is mostly plumbing; real bugs are in the agent loop

**Not Testable Without Real Data:**
- Agent behavior under diverse LLM responses
- Error recovery patterns (need recorded real failures)
- Tool execution edge cases (need real accessibility tree)

---

## What Actually Breaks in Production?

Before evaluating tests, consider what failures matter:

| Failure Mode | Impact | Testable in JVM? |
|--------------|--------|------------------|
| LLM returns malformed tool call | Agent crashes or loops | ⚠️ Need real examples |
| History grows unbounded | OOM, slow prompts | ✅ Yes |
| Session file corrupted | User loses history | ✅ Yes |
| Approval timeout race condition | Hung agent | ✅ Yes |
| Tool execution fails silently | Agent thinks it succeeded | ⚠️ Needs real platform |
| DNS failure misclassified | Wrong retry behavior | ✅ Yes |
| Concurrent tool calls leak | Memory leak, state corruption | ✅ Yes |

Focus tests on the ✅ items. The ⚠️ items need different strategies (record/replay, manual testing, observability).

---

## Per-Test Assessment

### HIGH VALUE — Worth Investment

#### `HistoryManagerTest` ✅

**Why it matters:** History corruption = broken agent. Token budget overflow = slow/expensive calls.

**Current tests are valuable:**
- `forPrompt adds placeholder output when missing` — Prevents orphaned calls breaking LLM
- `forPrompt removes orphaned outputs` — Data integrity
- `dropLastNUserTurns` — Rollback for error recovery
- `removeFirstItem removes paired output` — Ensures call/output pairing
- `compress reduces token count` — Budget management

**Missing high-value test:**
```kotlin
@Test
fun `history growth is bounded under continuous additions`() {
    val manager = HistoryManager(HistoryConfig(maxTokens = 10_000))
    repeat(1000) { i ->
        manager.addItem(ResponseItem.FunctionCallOutput(
            callId = "call-$i",
            content = "x".repeat(500)
        ))
    }
    // Should auto-compress or reject, not OOM
    assertThat(manager.estimateTokenCount()).isLessThan(15_000)
}
```

#### `ToolRouterTest` ✅

**Why it matters:** Tool execution state machine has real complexity. Race conditions and cleanup bugs are production issues.

**Valuable tests:**
- `unknown tool returns error` — Basic safety
- `approval timeout returns cancelled` — Prevents hung agents (real issue)
- `concurrent executions tracked and cleaned up` — Memory leak prevention
- `policy deny returns error` — Security enforcement

**Test that should be added:**
```kotlin
@Test
fun `cancellation during execution cleans up state`() = runTest {
    val registry = ToolRegistry().apply { register(DelayingToolSpec(10_000L)) }
    val router = ToolRouter(registry, PolicyEngine(ApprovalMode.AUTO_APPROVE))
    
    val job = launch { router.execute("delaying_tool", JSONObject(), context) }
    advanceTimeBy(100L)
    assertThat(router.getActiveCallIds()).hasSize(1)
    
    job.cancel()
    advanceUntilIdle()
    
    assertThat(router.getActiveCallIds()).isEmpty() // No leak
}
```

#### `PolicyEngineTest` ✅

**Why it matters:** Policy bugs = security issues or broken UX.

**All 4 tests are pure logic tests. Keep all.**

#### `SessionStorageTest` ✅

**Why it matters:** File corruption = user data loss.

**Add these for durability:**
```kotlin
@Test
fun `concurrent writes don't corrupt file`() = runTest {
    // Simulate rapid saves during active session
}

@Test
fun `partial write on crash doesn't lose previous data`() {
    // Write, simulate interrupt, verify recovery
}
```

#### `AgentErrorRecoveryTest` ✅

**Why it matters:** Error classification determines retry vs. fail. Wrong classification = stuck agent or unnecessary failures.

**Current tests are good:**
- DNS failure → non-recoverable (correct: no point retrying)
- Socket timeout → recoverable (correct: transient, should retry)

**Missing:**
```kotlin
@Test
fun `rate limit error triggers backoff`() { ... }

@Test  
fun `context length exceeded is non-recoverable`() { ... }
```

---

### MEDIUM VALUE — Keep But Don't Expand

#### `ToolCallStateTest`

Pure predicate test. Useful as documentation. Don't add more.

#### `ToolRegistryTest`

Basic collection operations. One happy path test is enough.

#### Tool Validation Tests (`MobileActionToolTest`, etc.)

Validation logic is simple. Current coverage is sufficient. Don't add more permutations.

#### `SessionRecordingServiceTest`

Debounce and persistence logic matters. Current tests are adequate.

---

### LOW VALUE — Reconsider

#### `TurnTest` ⚠️

**Problem:** Tests trivial logic with heavy fake infrastructure.

The "completion detection" logic is:
```kotlin
val isComplete = toolCalls.any { it.name == "complete_task" } || 
                 (toolCalls.isEmpty() && textContent != null)
```

This is 3 lines of trivial logic. The test scaffolding (fake LLM client, fake registry) is 70+ lines. The test doesn't catch real bugs because:
- Real LLM might return malformed tool calls (not tested)
- Real LLM might return unexpected tool names (not tested)
- The "complete_task" check is just a string comparison

**Recommendation:** Delete or convert to documentation comment. If you want to test Turn behavior, use recorded real LLM responses.

#### `AgentSessionTest` ⚠️

**Problem:** Tests lifecycle plumbing, not agent intelligence.

What this tests:
- `Interrupt` → state becomes `Idle`
- `Shutdown` → state becomes `Shutdown`, emits `SessionCompleted`
- Event replay cache works

These are state machine transitions that are:
1. Trivial to verify by reading the code
2. Unlikely to regress (state machine is simple)
3. Not where production bugs occur

**Real bugs in AgentSession are:**
- Event ordering under concurrent operations
- Memory leaks from flow collectors
- Deadlocks under rapid start/stop

Current tests don't catch these.

**Recommendation:** Keep `shutdown from running emits session completed` as a smoke test. Remove others. Add integration test with real agent loop if event ordering matters.

#### `SessionAgentRunnerTest` ⚠️

**Problem:** Single happy-path test with fully mocked LLM.

```kotlin
@Test
fun `start completes with goal achieved`() = runTest {
    // ... setup with fake LLM that always returns "done"
    runner.start(taskInput = "goal", taskId = "task-1")
    assertThat(completion.getCompleted()).isEqualTo(AgentStopReason.GoalAchieved)
}
```

This test passes because `RunnerTestLLMClient` always returns `"done"` with no tool calls, which triggers completion. It doesn't test:
- What happens when LLM returns tool calls
- What happens when tool execution fails
- What happens when agent is stopped mid-turn

**Recommendation:** Either delete (it's false confidence) or replace with integration test using recorded LLM responses.

#### `SessionHistoryManagerTest` ⚠️

Same problem — single test with happy path. Either expand to cover error cases or delete.

---

## Beyond Tests: Robustness Improvements

### 1. Inject Time/Delay for Determinism

Current code uses `delay(ms)` with real time. This makes tests flaky and prevents deterministic replay.

**Change:**
```kotlin
// In Agent.kt or SessionServices
interface DelayProvider {
    suspend fun delay(ms: Long)
}

class RealDelayProvider : DelayProvider {
    override suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
}

class TestDelayProvider(private val scheduler: TestCoroutineScheduler) : DelayProvider {
    override suspend fun delay(ms: Long) {
        scheduler.advanceTimeBy(ms)
    }
}
```

### 2. Record/Replay for LLM Responses

The test plan mentions this but it's not implemented. This is the highest-value testing investment for agent behavior.

**Approach:**
```kotlin
// Record mode: save to file
class RecordingLLMClient(delegate: LLMClient, outputDir: File) : LLMClient() {
    override suspend fun chatWithTools(...): ResponsesResult {
        val result = delegate.chatWithTools(...)
        saveToFile(inputItems, result)
        return result
    }
}

// Replay mode: load from file
class ReplayLLMClient(recordingDir: File) : LLMClient() {
    override suspend fun chatWithTools(...): ResponsesResult {
        return loadMatchingResponse(inputItems)
    }
}
```

Then run agent with real LLM, record responses, commit recordings, replay in tests.

### 3. Structured Error Types

Current error handling mixes error classification with error handling:

```kotlin
// Current: error type implicit in exception class
catch (e: UnknownHostException) -> non-recoverable
catch (e: SocketTimeoutException) -> recoverable
```

**Better:**
```kotlin
sealed class AgentError {
    sealed class Recoverable : AgentError() {
        data class NetworkTransient(val cause: Throwable) : Recoverable()
        data class RateLimit(val retryAfterMs: Long) : Recoverable()
    }
    sealed class NonRecoverable : AgentError() {
        data class DNSFailure(val host: String) : NonRecoverable()
        data class ContextTooLong(val tokens: Int) : NonRecoverable()
        data class InvalidAPIKey : NonRecoverable()
    }
}

fun classifyError(e: Throwable): AgentError = when {
    e is UnknownHostException -> AgentError.NonRecoverable.DNSFailure(...)
    e is SocketTimeoutException -> AgentError.Recoverable.NetworkTransient(e)
    // etc.
}
```

This makes error handling testable and explicit.

### 4. Observability for Production Debugging

Tests can't catch everything. Add structured logging for production debugging:

```kotlin
// In Agent.kt
logger.info("turn_started", mapOf(
    "turn_number" to turnNumber,
    "history_tokens" to historyManager.estimateTokenCount(),
    "screen_elements" to snapshot.elements.size
))

logger.info("turn_completed", mapOf(
    "turn_number" to turnNumber,
    "tool_calls" to result.toolCalls.size,
    "is_complete" to result.isComplete,
    "duration_ms" to elapsed
))
```

This helps debug production issues that tests will never catch.

---

## Revised Test Priority

### Do Now
1. Add `HistoryManager` growth bounds test
2. Add `ToolRouter` cancellation cleanup test
3. Add error classification tests for rate limit, context length

### Do Soon
1. Implement record/replay infrastructure for LLM responses
2. Extract `FakeAndroidPlatform` to avoid duplication (pragmatic, not coverage-driven)

### Delete or Simplify
1. `TurnTest` — Delete or convert to comment
2. `SessionAgentRunnerTest` — Delete single happy-path test
3. `AgentSessionTest` — Keep one smoke test, remove others

### Don't Bother
- Adding more permutations to tool validation tests
- Testing session lifecycle state transitions
- Testing trivial getters/setters

---

## Summary

**Tests that catch real bugs:** History integrity, tool router state machine, policy enforcement, file I/O durability, error classification.

**Tests that don't catch real bugs:** Turn completion detection, session lifecycle plumbing, happy-path-with-fake-LLM tests.

**What tests can't catch:** LLM behavior variance, real accessibility tree edge cases, performance under load.

**Invest in:** Record/replay infrastructure, deterministic time control, structured error types, observability.

**Don't invest in:** More unit tests for session lifecycle, more tool validation permutations, test coverage metrics.
