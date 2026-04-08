# Test Architecture Improvement Plan — Final

**Date**: 2026-04-08
**Source**: Double-design review (Claude + Codex), cross-reviewed, aligned
**Derived from**: `review.md`

---

## Guiding Principles

1. Raise confidence at module boundaries before adding more inner-loop tests
2. Prefer tests around pure reducers, parsers, classifiers, and coordinators over mock-heavy Android-runtime fakes
3. When a class is hard to test, extract pure logic first — test that
4. Spend maintenance budget on behavior with real regression cost, not static data snapshots
5. Consolidate test infrastructure before expanding test count

---

## Phase 0: Test Infrastructure Cleanup

**Goal**: Reduce duplication before adding new tests, so new files stay small.

### 0.1 Consolidate RecordingPlatform into TestFixtures
**Impact**: Eliminates ~180 lines across ClickExecutorTest, ScrollExecutorTest, LongPressExecutorTest

```kotlin
// In TestFixtures.kt
class RecordingPlatform(
    private val actionResults: List<ActionResult>,
    private val capturedSnapshots: List<ScreenSnapshot>,
    private val displayInfo: DisplayInfo = DisplayInfo(1080, 2400, 3f)
) : AndroidPlatform { ... }
```

### 0.2 Create shared LLM client fakes in TestFixtures
**Impact**: Eliminates ~200 lines across 7 files

```kotlin
class StubLLMClient(textContent: String = "done") : LLMClient()
class FailingLLMClient(throwable: Throwable) : LLMClient()
class CapturingLLMClient(response: ResponsesResult) : LLMClient()
```

### 0.3 Create TestSessionServicesBuilder in TestFixtures
**Impact**: Eliminates ~150 lines of buildServices() duplication

```kotlin
class TestSessionServicesBuilder {
    var llmClient: LLMClient = StubLLMClient()
    var platform: AndroidPlatform = FakeAndroidPlatform()
    var traceRecorder: TraceRecorder = NoopTraceRecorder
    fun build(): SessionServices { ... }
}
```

### 0.4 Standardize on Google Truth
**Files**: LLMClientFactoryTest.kt, ModelCatalogTest.kt
Replace `assertEquals`/`assertTrue` with `assertThat(x).isEqualTo(y)`.

### 0.5 Prune low-value snapshot tests
**Optional**: Relax AgentDefTest exact tool list assertions to `containsAtLeast` for critical tools + count check. Simplify OpenAppToolTest alias-entry tests to a single behavioral test.

---

## Phase 1: LLM Boundary + Safety Tools

**Goal**: Protect the external-contract boundary and safety-sensitive tool behavior.

### 1.1 CodexRequestBuilderTest
**File**: `llm/CodexRequestBuilderTest.kt`
**Test cases**:
- User vs assistant content conversion in request JSON
- Function-call input and function-call-output serialization
- System message placement and formatting
- Empty/missing fields handled gracefully

### 1.2 CodexSseParserTest
**File**: `llm/CodexSseParserTest.kt`
**Test cases**:
- Parallel tool-call accumulation across `output_index`
- Trailing SSE buffer flush and `[DONE]` handling
- Malformed SSE event recovery
- Partial event buffering across chunk boundaries

### 1.3 OpenAIErrorClassifierTest
**File**: `llm/OpenAIErrorClassifierTest.kt`
**Test cases**:
- Classifies rate limit (429) as retryable
- Classifies server errors (500, 502, 503) as retryable
- Classifies auth error (401) as non-retryable
- Classifies bad request (400) as non-retryable
- Extracts meaningful error message from response body

### 1.4 CloudStreamRetryPolicyTest
**File**: `llm/CloudStreamRetryPolicyTest.kt`
**Test cases**:
- shouldRetry returns true for retryable HTTP status codes
- shouldRetry returns false for non-retryable codes
- shouldRetry returns false after max attempts exceeded
- Backoff delay increases with attempt number
- Backoff respects maximum delay cap
- Retry-after header extraction

### 1.5 ShellToolTest
**File**: `tool/impl/ShellToolTest.kt`
**Test cases**:
- Blocked destructive command rejection
- Safe command acceptance
- Timeout handling
- Output truncation behavior

### 1.6 AskUserToolTest
**File**: `tool/impl/AskUserToolTest.kt`
**Test cases**:
- Pending ask-user rejection when another request is active
- Timeout and cancellation semantics
- Successful user response forwarding

---

## Phase 2: Orchestration + Trace

**Goal**: Cover the orchestration seams where cross-module regressions happen.

### 2.1 SessionCoordinatorTest
**File**: `session/SessionCoordinatorTest.kt`
**Test cases**:
- Queue vs immediate-submit behavior
- Dead-session teardown and consumeDeadSessionFileName()
- Session creation and cleanup lifecycle

### 2.2 AgentServiceEventHandlerTest
**File**: `app/AgentServiceEventHandlerTest.kt`
**Test cases**:
- Event-handler effects on recording service
- Overlay callback routing
- Status message emission

### 2.3 TurnPlanningPhaseRunnerTest
**File**: `agent/TurnPlanningPhaseRunnerTest.kt`
**Test cases**:
- Planning-phase history write
- Arbitration warning emission
- Thought emission during planning

### 2.4 CognitionTraceRedactorTest
**File**: `trace/CognitionTraceRedactorTest.kt`
**Test cases**:
- Redacts email addresses in middle of text
- Redacts API key patterns (sk-..., sk_live_...)
- Redacts Bearer tokens
- Redacts multiple patterns in single string
- Preserves non-sensitive content unchanged
- Handles empty and null input
- Redacts tokens in JSON-formatted strings

### 2.5 TypeExecutorTest
**File**: `tool/action/TypeExecutorTest.kt`
**Test cases**:
- Direct text set into focused field
- Specific element targeting for type action
- Clear field before typing when clear=true
- Append to existing text when clear=false
- Failure when no editable element found
- VD mode disabling tap-to-focus fallback

### 2.6 (Stretch) TurnExecutionPhaseRunnerTest
**File**: `agent/TurnExecutionPhaseRunnerTest.kt`
**If capacity allows**, add before SwipeExecutorTest:
- Observation capture after tool execution
- History output recording
- Abort-on-failure behavior

---

## Phase 3: Onboarding + Chat + First VD Seam

**Goal**: Cover first-run conversion, user-visible chat behavior, and virtual display decision logic.

### 3.1 OnboardingViewModelTest
**File**: `onboarding/OnboardingViewModelTest.kt`
**Test cases**:
- Step selection on startup from stored outcomes and permission state
- Accessibility poll-after-return behavior
- OAuth success/error transitions
- Manual API-key validation success, invalid-key, and transient-error paths
- Demo success vs timeout vs wrong-package completion

### 3.2 ChatEventReducerTest
**File**: `ui/chat/ChatEventReducerTest.kt`
**Test cases**:
- Streaming delta accumulation and completion transitions
- Action-card proposal to execution to success/failure mapping
- Replay cutoff behavior when rebinding to a live session

### 3.3 MessageConverterTest
**File**: `history/model/MessageConverterTest.kt`
**Test cases**:
- MessageRecord to ChatMessage round-trip invariants
- Edge cases in content type mapping

### 3.4 FileTraceRecorderTest
**File**: `trace/FileTraceRecorderTest.kt`
**Test cases**:
- Flush and close semantics
- Artifact naming/path sanitization
- Concurrent write handling

### 3.5 VirtualDisplayViewerTouchHandlerTest
**File**: `platform/virtualdisplay/VirtualDisplayViewerTouchHandlerTest.kt`
**Test cases**:
- Viewer coordinate scaling and clamping
- Tap vs swipe shell fallback behavior
- Invalid-display short-circuit cases

---

## Backlog (Validated, Deferred)

These items are valid but not first-pass mandatory. Prioritize as capacity allows:

| Item | Notes |
|------|-------|
| TurnExecutionPhaseRunnerTest | If not landed in Phase 2 stretch |
| CloudLlmRetryTest | Non-streaming retry contract |
| SwipeExecutorTest | Simpler action; lower strategic value than orchestration |
| VirtualDisplaySurfaceControllerTest | Surface mode switching |
| VirtualDisplayCaptureCoordinatorTest | Pixel-copy fallback |
| AgentTraceArtifactsTest | Artifact packaging edge cases |
| OnboardingStoreTest | Migration, outcome persistence |
| PermissionStateMonitorTest | Permission-repair model derivation |
| HttpLlmCredentialValidatorTest | 200/401/429/timeout/SSL mapping |
| MobileActionInvocation / UIActionInvocation tests | Review after fixture cleanup |
| ChatSessionHistoryControllerTest | Session list loading/resume/delete |
| ChatViewModelTest | Full ViewModel behavior |

---

## Execution Summary

| Phase | Files | Effort | Key Risk Mitigated |
|-------|-------|--------|-------------------|
| 0 | 1 modified + 2 updated | Small | Maintenance cost reduction |
| 1 | 6 new | Medium | LLM contract + safety tools |
| 2 | 5-6 new | Medium | Orchestration seams + trace privacy |
| 3 | 5 new | Medium | First-run conversion + chat state + VD |
| Backlog | ~12 items | Variable | Second-wave boundaries |

**First-pass total**: ~16-18 new/updated test files
**Estimated net effect**: +800-1000 new test lines, -530 duplicated lines = **~+300-500 net lines** while meaningfully expanding boundary coverage

---

## Success Criteria

This plan is working if:
- The highest-risk blank packages (llm, app service, onboarding) are no longer blank
- New tests sit at seams and collaborators, not at static data tables
- Fixture duplication drops substantially
- Parser/retry/auth/service regressions can be caught without a device
- UI-only files remain mostly exempt unless logic is extracted out of them

---

## Non-Goals

- Do not expand unit testing of pure Compose rendering
- Do not add live-network tests
- Do not add blanket tests for protocol/*, theme constants, resource files, or static content inventories
- Do not try to simulate the full Android runtime in unit tests — extract pure seams instead
- Do not refactor auth storage for testability in this pass (backlog)
