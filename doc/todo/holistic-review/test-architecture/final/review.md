# Test Architecture Review — Final

**Date**: 2026-04-08, revised 2026-04-16
**Source**: Double-design review (Claude + Codex), cross-reviewed, aligned; re-evaluated against current code 2026-04-16
**Scope**: 89 test files (~14,371 lines) covering 268 Kotlin production files (~37,509 lines) under `app/src/main/kotlin`

---

## Overall Assessment

The test suite is **strong for pure, deterministic inner logic** and **weak at runtime boundaries**. Policy engines, prompt construction, history compression, perceptor internals, action targeting, and state-holder logic are well protected. The highest-churn, highest-integration code — LLM wire format, service lifecycle, onboarding/auth, virtual display orchestration, and chat/session coordination — is not.

Since the original April 8 review, significant progress has been made: `llm/` went from 3 to 7 test files, `auth/` and `trace/` are no longer blank, and action executors gained cancellation coverage. However, the core pattern holds: **coverage is unevenly distributed**, concentrated in already-safe inner logic while orchestration seams and external-contract boundaries remain underprotected.

**Known active bug**: `OpenAIErrorClassifier` has false-positive matching (`message.contains("429")` matches status 14291). Tests currently preserve this bug rather than catching it.

---

## Module Coverage Map

### Assessment Scale

| Rating | Meaning |
|--------|---------|
| **Strong** | Direct tests cover main behavior and failure paths |
| **Mixed** | Some valuable coverage exists, but important classes are uncovered |
| **Shallow** | Tests exist but only touch helpers or a narrow slice |
| **Absent, acceptable** | Low-value unit target; better covered elsewhere |
| **Absent, concerning** | Material business/runtime risk with no meaningful direct tests |

### Execution Core

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `agent/` (incl. cognition, definition, subagent) | 24 files, ~3261 lines | 17+ files | **Mixed** | Strong on tool filtering, policies, prompt building, model resolution, sub-agent flow, error recovery, turn outcome decision. Gaps: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner |
| `session/` | 13 files, ~1933 lines | 6+ files | **Mixed** | AgentSession, ScratchpadState, TodoState, ServicesCleanup, LlmBootstrapper covered. SessionCoordinator, SessionAgentRunner, SessionCheckpointCoordinator uncovered |
| `tool/` (incl. action, impl, handlers) | 36 files, ~4746 lines | 16+ files | **Mixed** | Strong on router/policy/validation, click/scroll/long-press. ShellTool validation covered (ShellToolBlocklistTest). Gaps: TypeExecutor success paths (only cancellation tested), AskUserTool, UiChangeDetector |
| `perception/` | 6 files, ~787 lines | 3 files | **Strong** | Perceptor, PerceptorInternals, ScreenSummary well covered |
| `history/` | 15 files, ~2083 lines | 7 files | **Strong** | Good coverage of storage, management, recording. MessageConverter uncovered |
| `memory/` | 3 files, ~384 lines | 2 files | **Strong** | Focused coverage of storage and recall |
| `llm/` | 19 files, ~3020 lines | 7 files | **Mixed** | ModelCatalog, LLMClientFactory, LFM conversion, CodexSseParser (basic), OpenAIErrorClassifier (has known bug), CloudStreamRetryPolicy, CloudStreamRetryRunner tested. Gaps: CodexRequestBuilder, ChatCompletionInterop, ToolParameterExtractor, client-level tests, interleaved parallel tool-call parsing |
| `platform/` | 12 files, ~1967 lines | 2 files | **Mixed** | NodeActionPerformer and AppManager exercised. AccessibilityPlatform and related runtime logic untested |
| `platform/virtualdisplay/` | 16 files, ~2192 lines | 1 file | **Shallow** | VdLifecycleArbiterTest exists. TouchHandler, SurfaceController, CaptureCoordinator still unit-testable but untested |

### App Runtime, Auth, and Trace

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `app/` | 14 files, ~3028 lines | 4 files | **Shallow** | OverlayLocationPolicy, AppSettingsState, AppSettingsStoreFailClosed, MainActivityIntentApplierSecurity. AgentService, AgentServiceEventHandler, ServiceOverlayController uncovered |
| `auth/` | 3 files, ~661 lines | 1 file | **Shallow** | OAuthCredentialStoreFailClosedTest covers fail-closed behavior. OAuth flow helpers, JWT parsing, PKCE construction untested |
| `onboarding/` | 8 files, ~1149 lines | 0 files | **Absent, concerning** | OnboardingViewModel (503-line async state machine) with zero direct tests |
| `trace/` | 11 files, ~1249 lines | 2+ files | **Mixed** | CognitionTraceRedactorSecurityTest covers core redaction patterns. FileTraceRecorderTest covers durability. Gaps: AgentTraceArtifacts, LlmInputItemsTraceSerializer, path sanitization, JWT/long-token redaction edge cases |
| `debug/` | 2 files, ~435 lines | 0 files | **Absent, acceptable** | Debug-only tooling |

### UI and Presentation

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `ui/chat/` | 4 files, ~812 lines | 4+ files | **Shallow** | ChatActionExecutionMapping, ChatCompletionMessage, ChatCompletionSummary, ChatRebindEventFilter, ChatStartupFailure tested — but only helpers. ChatViewModel, ChatEventReducer, ChatSessionHistoryController effectively untested |
| `ui/overlay/` + `model/` | 6 files, ~643 lines | 4 files | **Strong** | Good state and render-spec coverage |
| `ui/overlay/compose/`, `ui/overlay/visualizer/` | 10 files, ~1230 lines | 0 files | **Absent, acceptable** | Compose/overlay wiring — better via instrumented/UX flows |
| `ui/settings/`, `ui/onboarding/`, `ui/navigation/`, etc. | 35 files, ~5604 lines | 0 files | **Absent, mostly acceptable** | Declarative UI — low unit-test priority unless logic is extracted |

### Schemas and Utilities

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `protocol/`, `model/`, `util/` | 30 files, ~1159 lines | 1 file | **Absent, mostly acceptable** | Dominated by immutable events/enums |
| `assets/`, `res/`, `AndroidManifest.xml` | 29 files, ~714 lines | 0 direct | **Absent, acceptable** | Asset loading/path safety covered by AssetAppSkillRepositoryTest |

---

## Critical Coverage Gaps (Risk-Ranked)

### 1. LLM Contract Boundary — Incomplete and Partially Wrong (HIGHEST)

**Still untested**: CodexRequestBuilder, ChatCompletionInterop, ToolParameterExtractor, CodexResponseClient, OpenAIResponseClient, ChatCompletionClient

**Partially tested with known issues**:
- OpenAIErrorClassifier — **live bug**: naive `message.contains("429")` / `message.contains("500")` matching causes false positives (status 14291 matches 429, status 5002 matches 500). Tests currently preserve the bug.
- CodexSseParser — basic coverage exists but interleaved parallel tool-call accumulation across multiple `output_index` values is untested.

**Already fixed**: CloudStreamRetryPolicy (fully tested via `decide()` API), CloudStreamRetryRunner.

**Failure modes still unprotected**:
- Request-body shape regressions (CodexRequestBuilder)
- Provider-routing conversion regressions (ChatCompletionInterop)
- Tool-schema extraction failures (ToolParameterExtractor)
- Client-level streaming terminal conditions, finish-reason handling, error translation
- Interleaved parallel tool-call accumulation in SSE parsing

### 2. Service and Session Orchestration (HIGH)

**Files**: AgentServiceEventHandler, SessionCoordinator, SessionCheckpointCoordinator, SessionAgentRunner

**Why high risk**: Runtime shell around the agent. Controls startup, shutdown, event collection, input queuing, overlay coordination, session handoff. Large, stateful, coroutine-driven. Where device failures become user-visible.

### 3. Agent Planning and Execution Orchestration (HIGH)

**Files**: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner

**Why**: Connects prompt building, LLM streaming, arbitration, tool execution, observation capture, and event emission. These now carry more cross-module behavior than the April 8 review recognized. TurnOutcomeDecision is now extracted and tested, but the phase runners themselves are not.

### 4. Safety-Sensitive Tool Boundaries (MEDIUM-HIGH)

**AskUserTool** — still zero direct tests. Tool-level behavior (pending rejection, event emission, timeout output, cancellation mapping) not covered by UserResponseChannelTest.

**ShellTool** — validation covered by ShellToolBlocklistTest. Execution path (timeout, truncation, exit-code formatting, pre-cancel short-circuit) still untested.

**TypeExecutor** — tests exist but only cover cancellation paths. Success behavior (direct set, tap-to-focus fallback, VD-mode no-tap) untested.

### 5. Onboarding and Auth Flow (MEDIUM-HIGH)

**Files**: OnboardingViewModel (503 lines, zero tests), OnboardingStore, PermissionStateMonitor, HttpLlmCredentialValidator, OpenAIOAuth (PKCE/JWT helpers)

**Why**: First-run conversion plus credential handling. Multi-step async state machine with zero direct test protection. OAuthCredentialStoreFailClosedTest covers only fail-closed storage, not flow logic.

### 6. Chat State Management (MEDIUM)

**Files**: ChatEventReducer, ChatSessionHistoryController, ChatViewModel, MessageConverter

**Why**: Helper tests exist (5 files) but actual reducer/controller/viewmodel behavior is untested. Replay cutoff is now covered by ChatRebindEventFilterTest, but delta accumulation, action-card transitions, session lifecycle flows are not.

### 7. Trace/Privacy Pipeline (MEDIUM)

**Partially covered**: CognitionTraceRedactorSecurityTest (core patterns), FileTraceRecorderTest (durability).

**Still untested**: AgentTraceArtifacts, LlmInputItemsTraceSerializer, path sanitization in FileTraceRecorder, JWT/long-token redaction edge cases.

### 8. Virtual Display Pure Collaborators (MEDIUM)

**Files**: VirtualDisplayViewerTouchHandler, VirtualDisplaySurfaceController, VirtualDisplayCaptureCoordinator

**Why**: Pure decision logic that's unit-testable. VdLifecycleArbiterTest is a start but doesn't cover touch handling, surface switching, or capture fallback.

### 9. SessionLlmBootstrapper — Gained Responsibilities (NEW)

**File**: SessionLlmBootstrapper

**Why**: Current coverage (off-main-thread enforcement + two provider-routing cases) doesn't cover base URL override extraction, fallback catalog loading, asset caching, or missing-key enforcement across model combinations. This is now a real seam given provider-routing complexity.

---

## Test Quality Analysis

### Strengths

1. **Behavior-first testing**: Tests describe user-relevant outcomes, not implementation details
2. **Descriptive naming**: Backtick-style Kotlin test names are consistently precise
3. **Fakes over mocks**: FakeAndroidPlatform, scripted LLM clients, RecordingPlatform — readable and refactor-resilient
4. **Real edge-case coverage**: Inline tool-call recovery, path traversal rejection, hint contamination, duplicate indexing, fail-closed security tests
5. **Clean structure**: Consistent arrange-act-assert, well-named helpers, TemporaryFolder for filesystem tests
6. **Correct coroutine testing**: runTest with TestScope, advanceTimeBy, no real delays

### Issues

1. **Coverage clustering**: Test methods concentrated in already-safe files while runtime/orchestration packages are weak
2. **Fixture duplication**: Repeated SessionServices assembly and one-off LLM stubs across session/agent tests are the biggest current pain point
3. **Known-bug-preserving tests**: OpenAIErrorClassifierTest explicitly encodes false-positive matching as "known bug" rather than failing
4. **Cancellation-only action tests**: TypeExecutor tests cover only cancellation paths, creating false confidence in coverage

---

## What Tests Should NOT Be Added

- Compose UI rendering tests (use `/ux-visual-debug` or instrumented tests)
- Protocol event/enum data class tests (pure data carriers)
- Live-network tests against OpenAI/OAuth/Shizuku (test parsers/classifiers/collaborators instead)
- Giant mock-heavy tests faking the entire Android runtime (extract pure logic, test that)
- Per-entry tests for static maps, app-skill content, colors, strings (test loaders and behavior)
- Broad tests for AndroidManifest, resources, AIDL declarations
- Virtual display end-to-end unit tests (instrumented coverage territory)
- Re-testing already-extracted pure logic through larger orchestration tests (e.g., don't re-prove decideTurnOutcome through AgentTurnRunner)

---

## Well-Covered Critical Paths (No Action Needed)

- **Policy engine** (PolicyEngineTest): All tiers, escape actions, approval modes
- **Tool arbitration** (TurnToolPolicyTest): Cognitive vs. screen tools, completion deferral
- **Loop detection** (LoopDetectionPolicyTest): Screen similarity, Jaccard threshold
- **Tool router** (ToolRouterTest): Approval flow, timeout, concurrency, cancellation
- **History compression** (HistoryManagerTest): All P0 invariants
- **Agent error recovery** (AgentErrorRecoveryTest): DNS, timeout, context length
- **Turn tool filtering** (TurnToolFilteringTest): Inline recovery, allowlist, streaming suppression
- **Sub-agent lifecycle** (SubAgentRunnerTest): Success, timeout, complete_task, step limit
- **Click executor** (ClickExecutorTest): Node click, gesture fallback, text promotion, hotspot, OOB, verification
- **Node action performer** (NodeActionPerformerTest): Recycling, hint guard, text entry contamination
- **Prompt builder** (PromptBuilderTest): Memory, observation, function history, app skill injection
- **Cloud stream retry** (CloudStreamRetryPolicyTest + CloudStreamRetryRunnerTest): Retryable/non-retryable, backoff, partial output
- **Turn outcome decision** (TurnOutcomeDecisionTest): Extracted pure logic for turn outcomes
- **Swipe executor** (SwipeExecutorTest): Cancellation, success with observation, failure
- **Session services cleanup** (SessionServicesCleanupTest): Resource teardown
- **Security fail-closed** (AppSettingsStoreFailClosedTest, OAuthCredentialStoreFailClosedTest, MainActivityIntentApplierSecurityTest): Fail-closed behaviors
