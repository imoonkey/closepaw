# Test Architecture Improvement Plan

**Date**: 2026-04-08
**Derived from**: [review.md](review.md)

---

## Priority 1: High-Value Test Additions

### 1.1 CloudStreamRetryPolicy unit tests
**Risk mitigated**: Infinite retry loops, wasted API credits, unrecoverable failures treated as retryable
**File**: `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicyTest.kt`
**Estimated effort**: Small (1 file, ~80 lines)

Test cases:
- `shouldRetry returns true for retryable HTTP status codes (429, 500, 502, 503)`
- `shouldRetry returns false for non-retryable codes (400, 401, 403, 404)`
- `shouldRetry returns false after max attempts exceeded`
- `backoff delay increases with attempt number`
- `backoff respects maximum delay cap`

### 1.2 OpenAIErrorClassifier unit tests
**Risk mitigated**: Misclassified API errors leading to wrong retry/fail decisions
**File**: `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt`
**Estimated effort**: Small (1 file, ~60 lines)

Test cases:
- `classifies rate limit (429) as retryable`
- `classifies server error (500, 502, 503) as retryable`
- `classifies authentication error (401) as non-retryable`
- `classifies bad request (400) as non-retryable`
- `extracts meaningful error message from response body`

### 1.3 CognitionTraceRedactor isolated tests
**Risk mitigated**: PII leakage in trace artifacts
**File**: `app/src/test/kotlin/com/moonkey/androidagent/trace/CognitionTraceRedactorTest.kt`
**Estimated effort**: Small (1 file, ~100 lines)

Test cases:
- `redacts email addresses in middle of text`
- `redacts API key patterns (sk-..., sk_live_...)`
- `redacts Bearer tokens`
- `redacts multiple patterns in single string`
- `preserves non-sensitive content unchanged`
- `handles empty and null input`
- `redacts tokens in JSON-formatted strings`

---

## Priority 2: Moderate-Value Test Additions

### 2.1 TypeExecutor tests
**Risk mitigated**: Text entry failures on real devices (complement to NodeActionPerformerTest)
**File**: `app/src/test/kotlin/com/moonkey/androidagent/tool/action/TypeExecutorTest.kt`
**Estimated effort**: Medium (1 file, ~150 lines, reuse RecordingPlatform)

Test cases:
- `execute types text into focused field`
- `execute targets specific element for type action`
- `execute clears field before typing when clear=true`
- `execute appends to existing text when clear=false`
- `execute returns failure when no editable element found`

### 2.2 SwipeExecutor tests
**Risk mitigated**: Swipe gesture failures
**File**: `app/src/test/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutorTest.kt`
**Estimated effort**: Small (1 file, ~80 lines)

Test cases:
- `execute dispatches swipe gesture with correct coordinates`
- `execute calculates correct start/end from direction`
- `execute returns failure when gesture fails`

---

## Priority 3: Test Infrastructure Improvements (Reduction > Addition)

### 3.1 Consolidate RecordingPlatform into TestFixtures
**Impact**: Eliminates ~180 lines of duplication across 3 files
**File to modify**: `app/src/test/kotlin/com/moonkey/androidagent/test/TestFixtures.kt`

Create a shared `RecordingPlatform` class in TestFixtures:
```kotlin
class RecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>,
    private val displayInfo: DisplayInfo = DisplayInfo(1080, 2400, 3f)
) : AndroidPlatform { ... }
```

Then update ClickExecutorTest, ScrollExecutorTest, LongPressExecutorTest to import it.

### 3.2 Create TestLLMClient variants in TestFixtures
**Impact**: Eliminates ~200 lines of duplication across 7 files
**File to modify**: `app/src/test/kotlin/com/moonkey/androidagent/test/TestFixtures.kt`

Add three configurable fake LLM clients:
```kotlin
// Returns a fixed response
class StubLLMClient(textContent: String = "done") : LLMClient()

// Throws on call
class FailingLLMClient(throwable: Throwable) : LLMClient()

// Captures what was sent to it
class CapturingLLMClient(response: ResponsesResult) : LLMClient()
```

### 3.3 Create TestSessionServicesBuilder in TestFixtures
**Impact**: Eliminates ~150 lines of buildServices() duplication
**File to modify**: `app/src/test/kotlin/com/moonkey/androidagent/test/TestFixtures.kt`

```kotlin
class TestSessionServicesBuilder {
    var llmClient: LLMClient = StubLLMClient()
    var platform: AndroidPlatform = FakeAndroidPlatform()
    var traceRecorder: TraceRecorder = NoopTraceRecorder
    // ... other overridable fields
    fun build(): SessionServices { ... }
}
```

### 3.4 Standardize on Google Truth
**Impact**: Consistency; eliminates mixed assertion styles
**Files to modify**: `LLMClientFactoryTest.kt`, `ModelCatalogTest.kt`

Replace `assertEquals`/`assertTrue`/`assertFalse` with `assertThat(x).isEqualTo(y)` etc.

---

## Priority 4: Tests to Consider Removing or Rewriting

### 4.1 Consider relaxing AgentDefTest tool list assertions
**Current**: Tests verify exact tool list contents with `containsExactly`
**Risk**: Breaks on every tool addition
**Recommendation**: Keep as-is but acknowledge these are intentional "snapshot" tests. If churn becomes a problem, change to `containsAtLeast` for the critical tools and verify count separately.

### 4.2 Remove OpenAppTool alias map tests
**Current**: 5 individual tests verify specific entries in `AppAliases.PACKAGE_MAP`
**Issue**: Tests specific data, not behavior. Will need updating every time an alias is added/changed.
**Recommendation**: Replace with a single test: `alias map resolves known app aliases` that checks the map is non-empty and a sample entry resolves correctly. Or remove entirely -- the alias map is pure data.

---

## Execution Order

| Phase | Item | Effort | Impact |
|-------|------|--------|--------|
| 1 | 1.1 CloudStreamRetryPolicy tests | S | High (protects against retry bugs) |
| 1 | 1.2 OpenAIErrorClassifier tests | S | High (protects error classification) |
| 1 | 1.3 CognitionTraceRedactor tests | S | High (PII protection) |
| 2 | 3.1 Consolidate RecordingPlatform | S | Medium (maintenance reduction) |
| 2 | 3.2 Consolidate LLM client fakes | S | Medium (maintenance reduction) |
| 2 | 3.3 Consolidate buildServices helpers | M | Medium (maintenance reduction) |
| 3 | 2.1 TypeExecutor tests | M | Medium (action reliability) |
| 3 | 2.2 SwipeExecutor tests | S | Low-Medium (action reliability) |
| 4 | 3.4 Standardize assertion library | S | Low (consistency) |

**Total estimated effort**: 6-8 files modified/created, ~600-800 lines of code.
**Net line change**: Approximately +400 new test lines, -500 duplicated lines = **-100 net lines** while improving coverage.

---

## What NOT to Do

- Do NOT add tests for Compose UI components (visual testing via `/ux-visual-debug`)
- Do NOT add tests for virtual display platform code (needs instrumented tests)
- Do NOT add tests for pure data classes in `protocol/`
- Do NOT add tests for network clients (OpenAIResponseClient, ChatCompletionClient) -- they wrap SDKs
- Do NOT add Robolectric tests for `auth/` -- the integration point (AppSettingsState.buildApiKeys) is already tested
- Do NOT add tests for `debug/` receiver/executor -- dev tooling only
