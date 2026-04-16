# Test Architecture Improvement Plan — Final

**Date**: 2026-04-08, revised 2026-04-16
**Source**: Double-design review, cross-reviewed, aligned; re-evaluated against current code 2026-04-16
**Derived from**: `review.md`

---

## Guiding Principles

1. Raise confidence at module boundaries before adding more inner-loop tests
2. Prefer tests around pure reducers, parsers, classifiers, and coordinators over mock-heavy Android-runtime fakes
3. When a class is hard to test, extract pure logic first — test that
4. Spend maintenance budget on behavior with real regression cost, not static data snapshots
5. Do infrastructure cleanup opportunistically alongside new tests, not as a gate

---

## Phase 1: Fix the LLM Contract Boundary

**Goal**: Fix the known-wrong classifier and protect the external-contract boundary that is still untested or partially tested.

### 1.1 Fix OpenAIErrorClassifier + rewrite tests
**File**: `llm/OpenAIErrorClassifier.kt` (fix) + `llm/OpenAIErrorClassifierTest.kt` (rewrite)
**What**: Fix false-positive `message.contains("429")` / `message.contains("500")` matching. Replace "KNOWN BUG" tests with proper status-boundary regressions.
**Test cases**:
- Classifies HTTP 429 as retryable (not string "429" in arbitrary text)
- Classifies server errors (500, 502, 503) as retryable
- Does NOT classify status 14291, 5002, 5003 as matching 429/500
- Classifies auth error (401) as non-retryable
- Classifies bad request (400) as non-retryable
- Extracts meaningful error message from response body

**ROI**: Very high — this is a live bug with real user impact.

### 1.2 CodexRequestBuilderTest
**File**: `llm/CodexRequestBuilderTest.kt`
**Test cases**:
- User vs assistant content conversion in request JSON
- Function-call input and function-call-output serialization
- Tool schema conversion via `convertTools()`
- System message placement and formatting
- Empty/missing fields handled gracefully

**ROI**: High — pure serialization at an external-contract boundary, cheap tests, high leverage.

### 1.3 ChatCompletionInteropTest (NEW)
**File**: `llm/ChatCompletionInteropTest.kt`
**Test cases**:
- Assistant text + grouped tool-call conversion
- Multimodal user content conversion
- System-role normalization
- Function-call input/output mapping to chat completions format

**ROI**: High — this is now a real external-contract boundary since provider routing uses ChatCompletionClient for OpenRouter/chat-style models.

### 1.4 ToolParameterExtractorTest (NEW)
**File**: `llm/ToolParameterExtractorTest.kt`
**Test cases**:
- Known/unknown/raw tool-schema representations
- Schema extraction feeding CodexRequestBuilder.convertTools()

**ROI**: High — broken schema extraction silently breaks tool calling for all providers.

### 1.5 CodexSseParserTest — extend existing
**File**: `llm/CodexSseParserTest.kt` (extend)
**What**: Add interleaved parallel tool-call accumulation tests. Basic coverage already exists.
**Test cases**:
- Interleaved tool-call deltas across multiple `output_index` values
- Parallel tool-call argument assembly with interleaved chunks

**ROI**: Medium-high — the remaining highest-risk gap in an otherwise-improved file.

### 1.6 Selected client-level tests (NEW)
**Files**: `llm/CodexResponseClientTest.kt` and/or `llm/ChatCompletionClientTest.kt`
**Test cases** (focus on pure logic, not network):
- Request construction correctness
- Streaming terminal condition handling (finish reasons)
- Tool-call accumulation at client level
- Error translation mapping

**ROI**: Medium-high — client orchestration is where provider regressions actually manifest.

---

## Phase 2: Orchestration Seams

**Goal**: Cover the joins where tested components combine into user-visible runtime behavior.

### 2.1 TurnPlanningPhaseRunnerTest
**File**: `agent/TurnPlanningPhaseRunnerTest.kt`
**Test cases**:
- Planning-phase history write with screen observation
- Model resolution and trace request/response logging
- Arbitration warning emission for dropped tools
- `agent_thought` emission during planning

**ROI**: High — this seam now carries more cross-module behavior than originally recognized.

### 2.2 TurnExecutionPhaseRunnerTest
**File**: `agent/TurnExecutionPhaseRunnerTest.kt`
**Test cases**:
- Observation capture after tool execution
- History output recording
- Approval event emission
- Abort-on-failure behavior
- Post-action observation capture

**ROI**: High — the execution seam where most cross-module regressions happen.

### 2.3 SessionCoordinatorTest
**File**: `session/SessionCoordinatorTest.kt`
**Test cases**:
- Queue vs immediate-submit behavior
- Dead-session teardown and `consumeDeadSessionFileName()`
- Session creation and cleanup lifecycle
- Automatic drain logic

**ROI**: High — stateful, concurrent, user-visible, easy to regress with subtle queueing bugs.

### 2.4 AgentServiceEventHandlerTest
**File**: `app/AgentServiceEventHandlerTest.kt`
**Test cases**:
- Event-handler effects on recording service
- Overlay callback routing
- Status message emission
- Large `when` over agent events with side effects

**ROI**: High — extracted side-effect hub that unit tests should hit directly.

### 2.5 SessionCheckpointCoordinatorTest
**File**: `session/SessionCheckpointCoordinatorTest.kt`
**Test cases**:
- Checkpoint scheduling behavior
- Snapshot conversion logic

**ROI**: Medium — owns checkpoint scheduling and snapshot conversion, was quietly dropped from original plan.

---

## Phase 3: Safety-Sensitive Tool Boundaries

**Goal**: Cover safety and user-interaction boundaries.

### 3.1 AskUserToolTest
**File**: `tool/impl/AskUserToolTest.kt`
**Test cases**:
- Pending ask-user rejection when another request is active
- Event emission on ask
- Timeout output formatting
- Cancellation mapping
- Successful user response forwarding

**ROI**: Medium-high — safety/user-handoff boundary with zero direct tests.

### 3.2 ShellTool execution path tests — extend existing
**File**: `tool/impl/ShellToolBlocklistTest.kt` (extend or new file)
**What**: Validation is covered. Add execution-path coverage.
**Test cases**:
- Timeout handling
- Output truncation behavior
- Exit-code formatting
- Pre-cancel short-circuit in `ShellTool.kt`

**ROI**: Medium.

### 3.3 TypeExecutorTest — extend existing
**File**: `tool/action/TypeExecutorTest.kt` (extend)
**What**: Current tests cover only cancellation. Add success/failure paths.
**Test cases**:
- Direct text set success into focused field
- Tap-to-focus fallback success
- No editable target failure
- VD mode disabling tap-to-focus fallback

**ROI**: Medium-high — cancellation-only coverage creates false confidence.

---

## Phase 4: Onboarding and Auth

**Goal**: Cover the first-run conversion and credential handling that is still basically blank.

### 4.1 OnboardingViewModelTest
**File**: `onboarding/OnboardingViewModelTest.kt`
**Test cases**:
- Step selection on startup from stored outcomes and permission state
- Accessibility poll-after-return behavior
- OAuth success/error transitions
- Manual API-key validation success, invalid-key, and transient-error paths
- Demo success vs timeout vs wrong-package completion
- Draft-key persistence
- Auto-advance timing

**ROI**: Very high — 503-line async state machine with zero tests.

### 4.2 OnboardingStoreTest
**File**: `onboarding/OnboardingStoreTest.kt`
**Test cases**:
- Outcome persistence
- Auth-method storage
- Encrypted draft-key storage
- Migration behavior

**ROI**: Medium-high.

### 4.3 PermissionStateMonitorTest
**File**: `onboarding/PermissionStateMonitorTest.kt`
**Test cases**:
- `deriveRepairModel()` pure logic

**ROI**: High — pure logic, very cheap to test.

### 4.4 HttpLlmCredentialValidatorTest
**File**: `onboarding/HttpLlmCredentialValidatorTest.kt`
**Test cases**:
- HTTP 200 → valid mapping
- 401/403 → invalid-key mapping
- 429/5xx → transient-error mapping
- Timeout, SSL, IO exception mapping

**ROI**: High — classic high-leverage unit-test territory.

### 4.5 OpenAIOAuth pure helper tests
**File**: `auth/OpenAIOAuthTest.kt`
**Test cases** (pure helpers only, not live flow):
- PKCE shape and URL construction
- JWT email parsing and account-id extraction
- Callback parsing and state mismatch handling
- Token-expiring-soon calculation

**ROI**: Medium — recommended refactor: split into pure helpers + transport adapters.

---

## Phase 5: Chat/History State Management

**Goal**: Cover the actual reducer/controller behavior behind the helper tests.

### 5.1 ChatEventReducerTest
**File**: `ui/chat/ChatEventReducerTest.kt`
**Test cases**:
- Task start event handling
- Streaming delta accumulation and completion transitions
- Action-card proposal → execution → success/failure ordering
- Supplement insertion
- Task completion/error transitions

**ROI**: Medium-high.

### 5.2 MessageConverterTest
**File**: `history/model/MessageConverterTest.kt`
**Test cases**:
- MessageRecord to ChatMessage round-trip invariants
- Action-state parsing
- UI-facing tool name/icon conversion

**ROI**: High — small pure seam with zero protection.

### 5.3 ChatSessionHistoryControllerTest
**File**: `ui/chat/ChatSessionHistoryControllerTest.kt`
**Test cases**:
- Resume/new/delete session flows
- View-model-facing callbacks

**ROI**: Medium-high.

### 5.4 ChatViewModelTest (targeted)
**File**: `ui/chat/ChatViewModelTest.kt`
**Test cases** (coordination layer, not re-testing helpers):
- `startEventCollection()` behavior
- Pending input consumption
- Approvals
- Session history delegation
- Teardown

**ROI**: Medium-high.

---

## Phase 6: Virtual Display and Trace Second Wave

**Goal**: Finish pure-collaborator coverage for VD and complete trace pipeline.

### 6.1 VirtualDisplayViewerTouchHandlerTest
**File**: `platform/virtualdisplay/VirtualDisplayViewerTouchHandlerTest.kt`
**Test cases**:
- Viewer coordinate scaling and clamping
- Tap vs swipe shell fallback behavior
- Invalid-display short-circuit cases
- Action-state tracking across down/move/up

**ROI**: Medium-high.

### 6.2 VirtualDisplaySurfaceControllerTest
**File**: `platform/virtualdisplay/VirtualDisplaySurfaceControllerTest.kt`
**Test cases**:
- Surface mode switching success/failure
- Invalid-surface guards
- Shizuku result handling

**ROI**: Medium.

### 6.3 VirtualDisplayCaptureCoordinatorTest
**File**: `platform/virtualdisplay/VirtualDisplayCaptureCoordinatorTest.kt`
**Test cases**:
- PixelCopy failure fallback to ImageReader
- Repeated-failure demotion
- Capture mode switching

**ROI**: Medium-high.

### 6.4 AgentTraceArtifactsTest
**File**: `trace/AgentTraceArtifactsTest.kt`
**Test cases**:
- Artifact naming/path sanitization
- Redacted request/response/tool/snapshot packaging

**ROI**: Medium.

### 6.5 FileTraceRecorderTest — extend existing
**File**: `trace/FileTraceRecorderTest.kt` (extend)
**What**: Durability is covered. Add path sanitization and concurrency.
**Test cases**:
- `newArtifactPath()` / `sanitizePathSegment()` behavior
- Artifact directory creation
- Concurrent write channel behavior

**ROI**: Medium.

---

## Backlog (Validated, Deferred)

| Item | Notes |
|------|-------|
| CloudLlmRetryTest | Non-streaming retry contract; lower urgency since streaming retry is covered |
| SessionLlmBootstrapperTest (extend) | Base URL override extraction, fallback catalog loading, asset caching, missing-key enforcement |
| LlmInputItemsTraceSerializerTest | Shares logic with ChatCompletionInterop; test after 1.3 lands |
| MobileActionInvocation / UIActionInvocation | Outcome mapping, cancellation, description formatting beyond CapturePrivacyGateTest |
| UiChangeDetectorTest | Post-action change detection |
| PointActionExecutorCoreTest | Container promotion and ambiguity fallback |

---

## Infrastructure Cleanup (Opportunistic, Not a Gate)

Do alongside new tests, not as a separate phase:

- **TestSessionServicesBuilder**: Create when writing Phase 2 orchestration tests. Multiple tests hand-roll SessionServices — a lightweight builder is a real enabler.
- **Scripted LLM client fixture**: Create a thin shared fixture if it directly helps Phase 1/2 tests. Do not build a generic framework.
- **Do NOT do as standalone work items**: RecordingPlatform consolidation (local fakes are intentionally different), assertion library standardization (pure style churn), snapshot test pruning (fix opportunistically if they block refactors).

---

## Execution Summary

| Phase | Focus | Files | Key Risk Mitigated |
|-------|-------|-------|-------------------|
| 1 | LLM contract boundary | 5-6 new + 1 fix + 1 extend | External API/provider regressions + live bug |
| 2 | Orchestration seams | 5 new | Cross-module runtime behavior |
| 3 | Safety tool boundaries | 1 new + 2 extend | User-interaction and command guardrails |
| 4 | Onboarding/auth | 5 new | First-run conversion + credential handling |
| 5 | Chat/history state | 4 new | User-visible session behavior |
| 6 | VD + trace second wave | 3-4 new + 1 extend | Device control logic + privacy pipeline |
| Backlog | Various | ~6 items | Second-wave boundaries |

---

## Success Criteria

This plan is working if:
- OpenAIErrorClassifier false-positive bug is fixed
- LLM contract boundary (request building, interop, schema extraction) is no longer soft
- Orchestration seams (planning, execution, session coordination) have direct tests
- Onboarding/auth is no longer blank
- New tests sit at seams and collaborators, not at static data tables
- Infrastructure cleanup happens naturally alongside new tests

---

## Non-Goals

- Do not expand unit testing of pure Compose rendering
- Do not add live-network tests
- Do not add blanket tests for protocol/*, theme constants, resource files, or static content inventories
- Do not try to simulate the full Android runtime in unit tests — extract pure seams instead
- Do not re-test already-extracted pure logic (e.g., decideTurnOutcome) through larger orchestration tests
- Do not run a dedicated cleanup phase for assertion styles, executor fakes, or snapshot tests
