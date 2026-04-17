# Test Architecture Review (Codex)

**Date:** 2026-04-08  
**Scope read in full:** all `68` unit-test files under `app/src/test/kotlin`, and all `296` production files under `app/src/main` (`267` Kotlin files plus assets, resources, manifest, and AIDL).

## Overall Read

The current suite is strongest where the code is pure, local, and deterministic: policy, prompt construction, history compaction, perceptor internals, action targeting, and state-holder logic. It is weakest at the boundaries that most often break on real devices: Android service lifecycle, onboarding/auth, virtual display orchestration, LLM wire-format/retry logic, and chat/session coordination.

That means the repo is not “under-tested” in a generic sense. It is unevenly tested. The safest inner-loop logic is well protected; the highest-churn, highest-integration code is not.

## 1. Module-By-Module Coverage Map

Assessment legend:

- **Strong**: direct tests cover the main behavior and failure paths.
- **Mixed**: some valuable coverage exists, but important classes in the module are still uncovered.
- **Shallow**: tests exist, but they only touch helpers or a narrow slice of the module.
- **Absent, acceptable**: low-value unit target; better covered elsewhere or not worth direct unit tests.
- **Absent, concerning**: material business/runtime risk with no meaningful direct tests.

### Execution Core

| Module | Production Surface | Current Test Surface | Assessment | Notes |
| --- | --- | --- | --- | --- |
| `agent`, `agent/cognition/*`, `agent/definition`, `agent/subagent` | 24 files, 3261 lines | 17 files, 2395 lines | **Mixed** | Strong coverage for tool filtering, policies, prompt building, model resolution, sub-agent flow, and some error recovery. Gaps remain in `TurnPlanningPhaseRunner`, `TurnExecutionPhaseRunner`, and `AgentTurnRunner`, which are the actual orchestration seam of the turn loop. |
| `session` | 13 files, 1933 lines | 6 files, 639 lines | **Mixed** | `AgentSession`, `ScratchpadState`, `TodoState`, and service routing are covered. `SessionCoordinator`, `SessionAgentRunner`, `SessionCheckpointCoordinator`, and bootstrap classes are not. |
| `tool`, `tool/action`, `tool/impl`, `tool/handlers` | 36 files, 4746 lines | 16 files, 2644 lines | **Mixed** | Strong on router/policy/core tool validation and on click/scroll/long-press flows. Missing direct coverage for `TypeExecutor`, `SwipeExecutor`, `UiChangeDetector`, `PointActionExecutorCore`, `ShellTool`, `AskUserTool`, and the invocation handlers. |
| `perception` | 6 files, 787 lines | 3 files, 732 lines | **Strong** | `Perceptor`, `PerceptorInternals`, and `ScreenSummary` are well covered with concrete edge cases. |
| `history`, `history/model`, `history/storage` | 15 files, 2083 lines | 7 files, 1020 lines | **Strong** | Good coverage of storage, history management, recording, and reloadability. The main uncovered logic is `MessageConverter`, which sits on a user-visible boundary. |
| `memory` | 3 files, 384 lines | 2 files, 240 lines | **Strong** | Good, focused coverage of storage and recall behavior. |
| `llm` | 19 files, 3020 lines | 3 files, 810 lines | **Absent, concerning** | Tests cover `ModelCatalog`, `LLMClientFactory`, and `LFMLLMClient` conversion only. The riskiest code is untested: `CodexRequestBuilder`, `CodexSseParser`, `CodexResponseClient`, `OpenAIErrorClassifier`, `CloudLlmRetry`, `CloudStreamRetryPolicy`, and `CloudStreamRetryRunner`. |
| `platform` | 12 files, 1967 lines | 2 files, 549 lines | **Mixed** | `NodeActionPerformer` and `AppManager` are well exercised. `AccessibilityPlatform`, `AccessibilityGestureInjector`, `AccessibilityScreenshotCapturer`, `PlatformFactory`, and related runtime logic are untested. |
| `platform/virtualdisplay` | 16 files, 2192 lines | 0 files | **Absent, concerning** | No direct tests in the most integration-heavy device-control package. Some parts genuinely need device/instrumented coverage, but several collaborators are still unit-testable. |

### App Runtime, Auth, and Trace

| Module | Production Surface | Current Test Surface | Assessment | Notes |
| --- | --- | --- | --- | --- |
| `app` | 14 files, 3028 lines | 2 files, 477 lines | **Shallow** | Existing tests cover `OverlayLocationPolicy` and `AppSettingsState`. There is no direct coverage for `AgentService`, `AgentServiceEventHandler`, `ServiceOverlayController`, `MainActivityIntent*`, or `AgentServiceViewerBridge`. |
| `auth` | 3 files, 661 lines | 0 files | **Absent, concerning** | No tests for OAuth flow helpers, JWT parsing, token exchange logic, or credential persistence behavior. |
| `onboarding` | 8 files, 1149 lines | 0 files | **Absent, concerning** | `OnboardingViewModel` alone is 503 lines of async state-machine logic with zero direct tests. `DefaultOnboardingDemoController`, `OnboardingStore`, `PermissionStateMonitor`, and `HttpLlmCredentialValidator` are also untested. |
| `trace` | 11 files, 1249 lines | 0 direct files; 1 indirect scenario in `AgentTraceObservabilityTest` | **Shallow** | The indirect test proves one happy-path redaction flow. It does not isolate `CognitionTraceRedactor`, `FileTraceRecorder`, or `AgentTraceArtifacts`. |
| `debug` | 2 files, 435 lines | 0 files | **Absent, acceptable** | Debug-only tooling. Unit tests are lower value than the runtime seams above. |

### UI and Presentation

| Module | Production Surface | Current Test Surface | Assessment | Notes |
| --- | --- | --- | --- | --- |
| `ui/chat` | 4 files, 812 lines | 4 files, 184 lines | **Shallow** | This looks covered by file count, but the tests only hit top-level helper functions in `ChatViewModel.kt`: completion summary, action block update, completion append, and replay cutoff logic. `ChatViewModel`, `ChatEventReducer`, and `ChatSessionHistoryController` are effectively untested. |
| `ui/overlay`, `ui/overlay/model` | 6 files, 643 lines | 4 files, 575 lines | **Strong** | Good state and render-spec coverage. This is one of the better-balanced UI slices. |
| `ui/overlay/compose`, `ui/overlay/visualizer` | 10 files, 1230 lines | 0 files | **Absent, acceptable** | Mostly Compose and Android overlay host wiring. Better validated via instrumented or UX flows than pure unit tests. |
| `ui/settings`, `ui/onboarding`, `ui/navigation`, `ui/capsule`, `ui/capsule/surface`, `ui/chat/components`, `ui/chat/model`, `ui/common`, `ui/theme`, `ui/session`, `ui/viewer` | 35 files, 5604 lines | 0 files | **Absent, mostly acceptable** | This is a lot of code, but most of it is declarative UI. Unit-test priority is low unless logic is extracted from these files into reducers/policies. |

### Schemas, Utilities, and Non-Code Production Surface

| Module | Production Surface | Current Test Surface | Assessment | Notes |
| --- | --- | --- | --- | --- |
| `protocol`, `model`, `util` | 30 files, 1159 lines | 1 file, 42 lines | **Absent, mostly acceptable** | `protocol/*` is dominated by immutable events, enums, and small helper types. Most direct unit tests here would be low-value. The exception is logic-bearing helpers like `AgentError` and any util that grows behavior. |
| `assets`, `res`, `AndroidManifest.xml`, `aidl` | 29 files, about 714 lines | 0 direct files | **Absent, acceptable** | Asset loading and path-safety are already indirectly covered by `AssetAppSkillRepositoryTest`. Static content files should not receive broad unit-test expansion. |

## 2. Critical Coverage Gaps Ranked By Risk

### 1. LLM wire-format, parser, and retry stack

**Files:** `llm/CodexRequestBuilder.kt`, `llm/CodexSseParser.kt`, `llm/CodexResponseClient.kt`, `llm/OpenAIErrorClassifier.kt`, `llm/CloudLlmRetry.kt`, `llm/CloudStreamRetryPolicy.kt`, `llm/CloudStreamRetryRunner.kt`

**Why this is highest risk:** this is the external-contract boundary. If request JSON drifts, SSE parsing breaks, or retry/error classification is wrong, the agent either fails silently, emits malformed tool calls, or wastes time and credits on bad retries. The current suite only protects catalog/factory/one conversion path, not the actual OpenAI/Codex integration logic.

**Failure modes likely to slip through today:**

- malformed SSE event ordering or trailing-buffer handling
- incorrect tool-call argument accumulation across parallel tool calls
- misclassified 429/5xx/network failures
- backoff/retry after partial stream output
- request-body shape regressions for assistant vs user message content

### 2. Service and session orchestration boundary

**Files:** `app/AgentService.kt`, `app/AgentServiceEventHandler.kt`, `app/ServiceOverlayController.kt`, `session/SessionCoordinator.kt`, `session/SessionAgentRunner.kt`, `session/SessionCheckpointCoordinator.kt`

**Why this is high risk:** this is the runtime shell around the agent. These classes control startup, shutdown, event collection, input queuing, overlay coordination, and session handoff. They are large, stateful, and coroutine-driven. They are also where real-device failures become user-visible: dropped inputs, stuck overlays, zombie sessions, lost shutdowns, or stale viewer state.

**Current protection:** minimal. The app package is covered by two narrowly focused tests that do not touch lifecycle or orchestration.

### 3. Onboarding and auth flow

**Files:** `onboarding/OnboardingViewModel.kt`, `onboarding/DefaultOnboardingDemoController.kt`, `onboarding/OnboardingStore.kt`, `onboarding/PermissionStateMonitor.kt`, `onboarding/HttpLlmCredentialValidator.kt`, `auth/OpenAIOAuth.kt`, `auth/OAuthCredentialStore.kt`

**Why this is high risk:** this is first-run conversion plus credential handling. `OnboardingViewModel` is a multi-step async state machine; `OpenAIOAuth` and `OAuthCredentialStore` are security-sensitive; `HttpLlmCredentialValidator` decides whether a key is accepted or rejected. None of it has direct test protection.

**Current protection:** `AppSettingsStateTest` covers final API-key selection in one downstream state object, not onboarding/auth behavior itself.

### 4. Virtual display platform and its pure collaborators

**Files:** `platform/virtualdisplay/VirtualDisplayPlatform.kt`, `VirtualDisplayViewerTouchHandler.kt`, `VirtualDisplaySurfaceController.kt`, `VirtualDisplayCaptureCoordinator.kt`, `VirtualDisplayInputInjector.kt`, `ShizukuDisplayTransport.kt`

**Why this is high risk:** this is a large, failure-prone device-control stack with multiple fallback paths: touch forwarding, shell fallback, surface switching, capture fallback, IME suppression, and display-specific action routing. Not all of it should be unit-tested end-to-end, but the pure decision layers are currently uncovered.

**Important nuance:** “hard to unit-test” is not the same as “should remain untested.” `VirtualDisplayViewerTouchHandler` and `VirtualDisplaySurfaceController` are obvious unit-test targets right now.

### 5. Safety-sensitive tools with no direct tests

**Files:** `tool/impl/ShellTool.kt`, `tool/impl/AskUserTool.kt`

**Why this is high risk:** both tools sit on behavioral/safety boundaries. `ShellTool` tries to enforce command guardrails and timeout/output behavior; `AskUserTool` blocks the agent and controls the user-interaction handoff. Neither has direct tests.

**Failure modes likely to slip through today:**

- blocked-command logic too weak or too broad
- shell timeout/output truncation regressions
- duplicate ask-user handling or timeout semantics breaking capsule behavior

### 6. Agent planning and execution orchestration

**Files:** `agent/TurnPlanningPhaseRunner.kt`, `agent/TurnExecutionPhaseRunner.kt`, `agent/AgentTurnRunner.kt`

**Why this is high risk:** these classes connect prompt building, LLM streaming, arbitration, tool execution, observation capture, and event emission. The suite tests many ingredients around them, but not the orchestration itself. That leaves a real gap at the seam where most cross-module regressions happen.

### 7. Chat state management and persistence mapping

**Files:** `ui/chat/ChatViewModel.kt`, `ui/chat/ChatEventReducer.kt`, `ui/chat/ChatSessionHistoryController.kt`, `history/model/MessageConverter.kt`

**Why this is medium-to-high risk:** this is visible user behavior. Session resume, replay cutoff, streaming update ordering, action-card transitions, and record-to-UI conversion can all regress while the current helper tests still pass.

### 8. Trace/privacy pipeline

**Files:** `trace/CognitionTraceRedactor.kt`, `trace/AgentTraceArtifacts.kt`, `trace/FileTraceRecorder.kt`, `trace/LlmInputItemsTraceSerializer.kt`

**Why this is medium risk:** privacy bugs are expensive, and observability bugs make the rest of the system harder to debug. The current indirect test is useful, but not enough to trust redaction and artifact packaging across edge cases.

### 9. Action verification beyond click/scroll/long-press

**Files:** `tool/action/TypeExecutor.kt`, `tool/action/SwipeExecutor.kt`, `tool/action/UiChangeDetector.kt`, `tool/action/PointActionExecutorCore.kt`, `tool/action/PostActionAnalysis.kt`

**Why this is medium risk:** the action suite is better than average, but it is skewed. Click and targeting logic are heavily exercised; typing, swiping, and UI-change verification are not. That is a practical gap because typing and swipe verification are frequent failure points on devices.

## 3. Test Quality Analysis

### What is working well

- The suite is behavior-first. The best tests describe user-relevant outcomes instead of private method structure.
- High-value pure logic is covered with scenario-rich tests: `PolicyEngineTest`, `PromptBuilderTest`, `HistoryManagerTest`, `PerceptorInternalsTest`, `ClickExecutorTest`, `NodeActionPerformerTest`, `TurnToolFilteringTest`, and `SubAgentRunnerTest`.
- The repo generally prefers simple fakes over pervasive mocks. `FakeAndroidPlatform`, scripted LLM clients, and recording platforms make the tests easier to read and less coupled to implementation.
- Naming is strong. Backtick test names are descriptive and usually precise about the expected behavior.
- Edge-case coverage is real, not superficial. Examples include malformed inline tool-call recovery, path traversal rejection, text-entry contamination, duplicate action IDs, and node recycling.
- Coroutine testing is mostly disciplined. The suite uses `runTest` in the async-heavy files instead of falling back to real sleeping.

### Issues and structural weaknesses

- Coverage is concentrated around a few already-safe files. The suite has `500` test methods, but they cluster heavily in places like `CapsuleStateHolderTest` (`41` tests), `PerceptorInternalsTest` (`34`), `ModelCatalogTest` (`34`), `NodeActionPerformerTest` (`24`), and `OverlayLocationPolicyTest` (`24`), while whole runtime packages are blank.
- File-count coverage can be misleading. `ui/chat` has four test files, but only `184` total test lines against `812` production lines, and all four tests target helper functions rather than reducer/viewmodel/controller behavior.
- Test fixture duplication is noticeable and unnecessary. There are repeated `RecordingPlatform` implementations, repeated `LLMClient` fakes, and repeated `buildServices` or `buildSession` helpers across the suite.
- Low-value exact-data assertions already exist at the edge of the suite. `AgentDefTest` snapshots exact tool lists, and `OpenAppToolTest` checks specific alias-map entries. Those tests are not wrong, but adding more tests in that style would increase maintenance faster than confidence.
- Assertion style is inconsistent. Most of the suite uses Truth, but a small slice still uses JUnit assertions.
- Boundary parsing and classification logic lacks adversarial tests. The riskiest untested code is exactly where malformed or unexpected input arrives.
- Only one test file in the suite sets the main dispatcher. That tracks with the broader problem: ViewModel-, service-, and lifecycle-shaped code is barely exercised directly.

### Specific suite-quality recommendations

- Consolidate test scaffolding into shared fixtures under `app/src/test/kotlin/com/moonkey/androidagent/test/`.
- Prefer configurable fake clients/builders over one-off fake classes in each file.
- Keep behavior-focused tests, but stop expanding per-entry data snapshot tests.
- Add more seam tests that span two or three collaborators, especially in `agent`, `session`, `app`, and `ui/chat`.

## 4. What Tests Should NOT Be Added

These omissions are good omissions, or should remain omissions unless the architecture changes.

- Do not add constructor/getter/enum tests for the `protocol/*` event classes and similar passive models. They are data carriers, not behavior.
- Do not add pixel-perfect unit tests for Compose rendering in `ui/settings`, `ui/onboarding`, `ui/chat/components`, `ui/overlay/compose`, `ui/theme`, `ui/navigation`, `ui/capsule`, or `ui/viewer`. If UI verification is needed, use screenshot/instrumented/UX flows, not brittle unit snapshots.
- Do not add live-network unit tests against OpenAI, ChatGPT, OpenRouter, OAuth, or Shizuku-backed services. Replace them with request-builder, parser, classifier, and collaborator tests.
- Do not add giant mock-heavy unit tests that try to fake the entire Android runtime for `AccessibilityService`, overlay windows, or virtual displays. If a class is too hard to test directly, extract the pure decision logic and test that.
- Do not add per-entry tests for static maps and content files such as app aliases, app-skill markdown content, colors, or string resources. Test loaders, path safety, and behavior, not static content inventories.
- Do not add broad tests for `AndroidManifest.xml`, `res/xml/*`, `res/values/*`, or AIDL declarations unless app logic starts parsing or deriving behavior from them.

## 5. Bottom Line

The suite is already good at protecting deterministic inner logic. The next gains do not come from “more tests” in the abstract. They come from moving coverage outward toward the app’s unstable boundaries:

- LLM protocol/retry behavior
- service/session lifecycle orchestration
- onboarding/auth state and storage
- virtual display control logic
- chat/session UI state reducers
- privacy/trace infrastructure

That is where the current risk is concentrated, and that is where additional tests will materially change confidence.
