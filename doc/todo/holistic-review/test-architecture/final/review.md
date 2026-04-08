# Test Architecture Review — Final

**Date**: 2026-04-08
**Source**: Double-design review (Claude + Codex), cross-reviewed, aligned
**Scope**: 68 test files (~10,293 lines) covering 267 Kotlin production files under `app/src/main/kotlin`

---

## Overall Assessment

The test suite is **strong for pure, deterministic inner logic** and **weak at runtime boundaries**. Policy engines, prompt construction, history compression, perceptor internals, action targeting, and state-holder logic are well protected. The highest-churn, highest-integration code — LLM wire format, service lifecycle, onboarding/auth, virtual display orchestration, and chat/session coordination — is not.

The suite is not under-tested generically. It is **unevenly tested**. The next gains come from moving coverage outward toward the app's unstable boundaries.

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
| `agent/` (incl. cognition, definition, subagent) | 24 files, 3261 lines | 17 files, 2395 lines | **Mixed** | Strong on tool filtering, policies, prompt building, model resolution, sub-agent flow, error recovery. Gaps: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner |
| `session/` | 13 files, 1933 lines | 6 files, 639 lines | **Mixed** | AgentSession, ScratchpadState, TodoState covered. SessionCoordinator, SessionAgentRunner, SessionCheckpointCoordinator uncovered |
| `tool/` (incl. action, impl, handlers) | 36 files, 4746 lines | 16 files, 2644 lines | **Mixed** | Strong on router/policy/validation and click/scroll/long-press. Missing: TypeExecutor, SwipeExecutor, UiChangeDetector, ShellTool, AskUserTool |
| `perception/` | 6 files, 787 lines | 3 files, 732 lines | **Strong** | Perceptor, PerceptorInternals, ScreenSummary well covered |
| `history/` | 15 files, 2083 lines | 7 files, 1020 lines | **Strong** | Good coverage of storage, management, recording. MessageConverter uncovered |
| `memory/` | 3 files, 384 lines | 2 files, 240 lines | **Strong** | Focused coverage of storage and recall |
| `llm/` | 19 files, 3020 lines | 3 files, 810 lines | **Absent, concerning** | Only ModelCatalog, LLMClientFactory, LFMLLMClient conversion tested. CodexRequestBuilder, CodexSseParser, OpenAIErrorClassifier, CloudStreamRetryPolicy, CloudStreamRetryRunner all untested |
| `platform/` | 12 files, 1967 lines | 2 files, 549 lines | **Mixed** | NodeActionPerformer and AppManager exercised. AccessibilityPlatform and related runtime logic untested |
| `platform/virtualdisplay/` | 16 files, 2192 lines | 0 files | **Absent, concerning** | No direct tests. Several pure collaborators (TouchHandler, SurfaceController, CaptureCoordinator) are unit-testable |

### App Runtime, Auth, and Trace

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `app/` | 14 files, 3028 lines | 2 files, 477 lines | **Shallow** | Only OverlayLocationPolicy and AppSettingsState. AgentService, AgentServiceEventHandler, ServiceOverlayController uncovered |
| `auth/` | 3 files, 661 lines | 0 files | **Absent, concerning** | Zero tests for OAuth flow helpers, JWT parsing, token exchange, credential persistence |
| `onboarding/` | 8 files, 1149 lines | 0 files | **Absent, concerning** | OnboardingViewModel (503 lines of async state machine) with zero direct tests |
| `trace/` | 11 files, 1249 lines | 0 direct, 1 indirect | **Shallow** | Only one happy-path redaction flow via AgentTraceObservabilityTest. CognitionTraceRedactor, FileTraceRecorder, AgentTraceArtifacts not isolated |
| `debug/` | 2 files, 435 lines | 0 files | **Absent, acceptable** | Debug-only tooling |

### UI and Presentation

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `ui/chat/` | 4 files, 812 lines | 4 files, 184 lines | **Shallow** | Tests only hit helper functions. ChatViewModel, ChatEventReducer, ChatSessionHistoryController effectively untested |
| `ui/overlay/` + `model/` | 6 files, 643 lines | 4 files, 575 lines | **Strong** | Good state and render-spec coverage |
| `ui/overlay/compose/`, `ui/overlay/visualizer/` | 10 files, 1230 lines | 0 files | **Absent, acceptable** | Compose/overlay wiring — better via instrumented/UX flows |
| `ui/settings/`, `ui/onboarding/`, `ui/navigation/`, etc. | 35 files, 5604 lines | 0 files | **Absent, mostly acceptable** | Declarative UI — low unit-test priority unless logic is extracted |

### Schemas and Utilities

| Module | Prod Surface | Test Surface | Rating | Notes |
|--------|-------------|-------------|--------|-------|
| `protocol/`, `model/`, `util/` | 30 files, 1159 lines | 1 file, 42 lines | **Absent, mostly acceptable** | Dominated by immutable events/enums. Exception: logic-bearing helpers |
| `assets/`, `res/`, `AndroidManifest.xml` | 29 files, ~714 lines | 0 direct | **Absent, acceptable** | Asset loading/path safety covered by AssetAppSkillRepositoryTest |

---

## Critical Coverage Gaps (Risk-Ranked)

### 1. LLM Wire-Format, Parser, and Retry Stack (HIGHEST)

**Files**: CodexRequestBuilder, CodexSseParser, CodexResponseClient, OpenAIErrorClassifier, CloudLlmRetry, CloudStreamRetryPolicy, CloudStreamRetryRunner

**Why highest risk**: This is the external-contract boundary. Incorrect wire format, broken SSE parsing, or wrong retry/error classification leads to silent failures, malformed tool calls, or wasted API credits.

**Failure modes unprotected today**:
- Malformed SSE event ordering or trailing-buffer handling
- Incorrect tool-call argument accumulation across parallel calls
- Misclassified 429/5xx/network failures
- Backoff/retry after partial stream output
- Request-body shape regressions

### 2. Safety-Sensitive Tools (HIGH)

**Files**: ShellTool, AskUserTool

**Why high risk**: Both sit on behavioral/safety boundaries. ShellTool enforces command guardrails and timeout/output behavior. AskUserTool blocks the agent and controls user-interaction handoff.

**Failure modes**: Blocked-command logic too weak/broad, timeout/truncation regressions, duplicate ask-user handling breaking capsule behavior.

### 3. Service and Session Orchestration (HIGH)

**Files**: AgentService, AgentServiceEventHandler, ServiceOverlayController, SessionCoordinator, SessionAgentRunner, SessionCheckpointCoordinator

**Why high risk**: Runtime shell around the agent. Controls startup, shutdown, event collection, input queuing, overlay coordination, session handoff. Large, stateful, coroutine-driven. Where device failures become user-visible.

### 4. Agent Planning and Execution Orchestration (MEDIUM-HIGH)

**Files**: TurnPlanningPhaseRunner, TurnExecutionPhaseRunner, AgentTurnRunner

**Why**: Connects prompt building, LLM streaming, arbitration, tool execution, observation capture, and event emission. Ingredients are tested but the orchestration seam is not.

### 5. Onboarding and Auth Flow (MEDIUM-HIGH)

**Files**: OnboardingViewModel (503-line state machine), DefaultOnboardingDemoController, OnboardingStore, PermissionStateMonitor, HttpLlmCredentialValidator, OpenAIOAuth, OAuthCredentialStore

**Why**: First-run conversion plus credential handling. Multi-step async state machine with zero direct test protection.

### 6. Trace/Privacy Pipeline (MEDIUM)

**Files**: CognitionTraceRedactor, AgentTraceArtifacts, FileTraceRecorder, LlmInputItemsTraceSerializer

**Why**: Privacy bugs are expensive. Current indirect test proves one happy path — doesn't isolate redaction or artifact packaging edge cases.

### 7. Chat State Management (MEDIUM)

**Files**: ChatViewModel, ChatEventReducer, ChatSessionHistoryController, MessageConverter

**Why**: User-visible behavior. Session resume, replay cutoff, streaming update ordering, action-card transitions can regress while helper tests pass.

### 8. Action Verification Beyond Click/Scroll/Long-Press (MEDIUM)

**Files**: TypeExecutor, SwipeExecutor, UiChangeDetector, PointActionExecutorCore

**Why**: Action suite is skewed — typing and swipe verification are frequent device failure points but uncovered.

### 9. Virtual Display Pure Collaborators (MEDIUM)

**Files**: VirtualDisplayViewerTouchHandler, VirtualDisplaySurfaceController, VirtualDisplayCaptureCoordinator

**Why**: "Hard to unit-test" ≠ "should remain untested." Touch handler and surface controller have pure decision logic that's unit-testable today.

---

## Test Quality Analysis

### Strengths

1. **Behavior-first testing**: Tests describe user-relevant outcomes, not implementation details
2. **Descriptive naming**: Backtick-style Kotlin test names are consistently precise
3. **Fakes over mocks**: FakeAndroidPlatform, scripted LLM clients, RecordingPlatform — readable and refactor-resilient
4. **Real edge-case coverage**: Inline tool-call recovery, path traversal rejection, hint contamination, duplicate indexing
5. **Clean structure**: Consistent arrange-act-assert, well-named helpers, TemporaryFolder for filesystem tests
6. **Correct coroutine testing**: runTest with TestScope, advanceTimeBy, no real delays

### Issues

1. **Coverage clustering**: 500 test methods concentrated in already-safe files (CapsuleStateHolderTest: 41, PerceptorInternalsTest: 34, ModelCatalogTest: 34) while runtime packages are blank
2. **Fixture duplication**: 3x RecordingPlatform, 7+ LLMClient fakes, 5x buildServices() helpers — ~530 duplicated lines
3. **Mixed assertion libraries**: Most use Truth, but LLMClientFactoryTest and ModelCatalogTest use JUnit assertions
4. **Low-value exact-data assertions**: AgentDefTest snapshots exact tool lists, OpenAppToolTest checks specific alias-map entries — high maintenance, low confidence gain
5. **Missing boundary/adversarial tests**: Riskiest untested code is where malformed or unexpected input arrives

---

## What Tests Should NOT Be Added

- Compose UI rendering tests (use `/ux-visual-debug` or instrumented tests)
- Protocol event/enum data class tests (pure data carriers)
- Live-network tests against OpenAI/OAuth/Shizuku (test parsers/classifiers/collaborators instead)
- Giant mock-heavy tests faking the entire Android runtime (extract pure logic, test that)
- Per-entry tests for static maps, app-skill content, colors, strings (test loaders and behavior)
- Broad tests for AndroidManifest, resources, AIDL declarations
- Virtual display end-to-end unit tests (instrumented coverage territory)

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
