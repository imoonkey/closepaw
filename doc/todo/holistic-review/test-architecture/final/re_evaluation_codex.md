# Test Architecture Re-evaluation (Codex)

**Date**: 2026-04-16

## Current-State Snapshot

The April 8 plan is materially stale.

- Current tree: 89 Kotlin files under `app/src/test/kotlin` with about 14,371 lines, and 268 Kotlin production files under `app/src/main/kotlin` with about 37,509 lines.
- Several packages that were called effectively blank on April 8 are no longer blank:
  - `llm/` now has 7 test files, including `CodexSseParserTest.kt`, `OpenAIErrorClassifierTest.kt`, `CloudStreamRetryPolicyTest.kt`, and `CloudStreamRetryRunnerTest.kt`.
  - `auth/` now has `OAuthCredentialStoreFailClosedTest.kt`.
  - `trace/` now has direct tests for `CognitionTraceRedactor` and `FileTraceRecorder`.
  - `platform/virtualdisplay/` now has `VdLifecycleArbiterTest.kt`.
  - `app/` now has meaningful security coverage via `AppSettingsStoreFailClosedTest.kt` and `MainActivityIntentApplierSecurityTest.kt`.
- Some originally high-risk areas are still genuinely weak:
  - `onboarding/` still has zero direct tests.
  - `SessionCoordinator`, `AgentServiceEventHandler`, `TurnPlanningPhaseRunner`, `TurnExecutionPhaseRunner`, `MessageConverter`, `VirtualDisplayViewerTouchHandler`, `VirtualDisplaySurfaceController`, and `VirtualDisplayCaptureCoordinator` still have zero direct tests.
  - The LLM boundary is no longer blank, but it is still incomplete, and one part of it is currently wrong: `OpenAIErrorClassifier` still has false-positive matching that current tests explicitly preserve.

Bottom line: the original review identified the right broad theme, but many file-level judgments and priorities no longer match the current code.

## Stale References I Corrected

- `trace/CognitionTraceRedactorTest.kt` in the plan corresponds to current `trace/CognitionTraceRedactorSecurityTest.kt`.
- `tool/impl/ShellToolTest.kt` does not exist. Current direct coverage is `tool/impl/ShellToolBlocklistTest.kt`.
- `CloudStreamRetryPolicy` no longer exposes a `shouldRetry(...)` style API. The current production entry point is `CloudStreamRetryPolicy.decide(...)`.
- `platform/virtualdisplay/` is no longer "0 tests"; `VdLifecycleArbiterTest.kt` now exists.
- The old package-level claim that `llm/` was "absent, concerning" is now false. The current issue is partial and uneven coverage, not absence.

## Executive Judgment

The plan should not be rubber-stamped.

- The real work now is at the LLM provider/conversion/client boundary and the orchestration seams.
- A dedicated "Phase 0 cleanup" is mostly not the right opening move anymore. Some of it is still useful as an enabler, but some of it is pure engineering theater.
- Several items are already done or partially done.
- Several of the most important current gaps were not in the final plan at all: `ChatCompletionInterop`, `ToolParameterExtractor`, client-level tests for `CodexResponseClient` / `OpenAIResponseClient` / `ChatCompletionClient`, and the expanded responsibilities in `SessionLlmBootstrapper`.

Classification totals:

- `STILL VALID`: 19
- `ALREADY FIXED`: 2
- `FAKE PROBLEM`: 3
- `NEEDS REVISION`: 10

## Item-by-Item Re-evaluation

## Phase 0: Test Infrastructure Cleanup

### 0.1 Consolidate RecordingPlatform into TestFixtures — FAKE PROBLEM

Current evidence: local platform fakes still exist in `ClickExecutorTest.kt`, `ScrollExecutorTest.kt`, `LongPressExecutorTest.kt`, `TypeExecutorTest.kt`, and `SwipeExecutorTest.kt`, while `test/TestFixtures.kt` only contains `FakeAndroidPlatform`.

Assessment: the duplication is real, but the proposed solution is not worth a standalone phase. These executor tests use intentionally local fakes with different semantics. A shared mega-fixture would save some lines and likely make the tests less readable and less explicit.

ROI: low to negative. Extract only a tiny shared helper if a new test genuinely needs the exact same fake behavior.

### 0.2 Create Shared LLM Client Fakes in TestFixtures — NEEDS REVISION

Current evidence: there are still many one-off `LLMClient` doubles and scripted streaming stubs in `AgentSessionTest.kt`, `SubAgentRunnerTest.kt`, `AgentErrorRecoveryTest.kt`, `AgentTraceObservabilityTest.kt`, `SessionServicesCleanupTest.kt`, `TurnToolFilteringTest.kt`, and `LocalBackendTurnRoutingTest.kt`.

Assessment: the underlying complaint is still real, but the original framing is too narrow. The current pain is not just "shared LLM fakes"; it is repeated ad-hoc scripted clients plus repeated manual `SessionServices(...)` assembly. A small scripted-stream fixture is useful. A large generic fake is not.

ROI: medium, but only as an enabler for the next wave of LLM/orchestration tests.

### 0.3 Create TestSessionServicesBuilder in TestFixtures — STILL VALID

Current evidence: multiple tests still hand-roll `SessionServices`, especially `AgentErrorRecoveryTest.kt`, `AgentTraceObservabilityTest.kt`, `SubAgentRunnerTest.kt`, `SessionServicesCleanupTest.kt`, `LocalBackendTurnRoutingTest.kt`, and `AgentSessionTest.kt`.

Assessment: this is still worth doing, because the next missing tests are mostly in session/agent orchestration, and those tests will need a lightweight way to stand up `SessionServices` without copy-pasting ten dependencies every time.

ROI: medium. This is not user-facing risk reduction by itself, but it is a legitimate test-enabler.

### 0.4 Standardize on Google Truth — FAKE PROBLEM

Current evidence: `LLMClientFactoryTest.kt` and `ModelCatalogTest.kt` still use JUnit assertions.

Assessment: this is pure style churn. It does not reduce risk, does not unblock meaningful tests, and should not consume any dedicated budget.

ROI: near zero.

### 0.5 Prune Low-Value Snapshot Tests — FAKE PROBLEM

Current evidence: `AgentDefTest.kt` still asserts exact tool lists, and `OpenAppToolTest.kt` still contains many alias-map entry assertions.

Assessment: the criticism is fair, but turning this into a dedicated work item is theater. These tests are mildly low-value, but they are not the current bottleneck. Simplify them opportunistically if they start blocking refactors; do not run a cleanup phase for them.

ROI: low.

## Phase 1: LLM Boundary + Safety Tools

### 1.1 CodexRequestBuilderTest — STILL VALID

Current evidence: there is still no `CodexRequestBuilderTest.kt`. `CodexRequestBuilder.kt` still owns `buildRequestBody(...)`, `convertInputItems(...)`, and `convertTools(...)` with no direct tests.

Assessment: this remains a real gap. It is pure serialization logic at an external-contract boundary, and regressions here are cheap to create and hard to notice until runtime.

ROI: high. Cheap tests, high leverage.

### 1.2 CodexSseParserTest — NEEDS REVISION

Current evidence: `CodexSseParserTest.kt` now covers single/multiple SSE events, `[DONE]`, malformed JSON, trailing flush, `response.failed`, `response.incomplete`, `error`, and a single tool-call lifecycle.

Assessment: the blank gap is gone, but the highest-value missing case from the original plan is still missing: interleaved parallel tool calls across multiple `output_index` values. Every current accumulator test uses one output index. Also, the old "chunk boundary" wording is less important than it looked on April 8 because the parser uses `BufferedReader.readLine()`; the more realistic remaining risk is interleaved function-call assembly, not raw socket chunking.

ROI: medium-high.

### 1.3 OpenAIErrorClassifierTest — NEEDS REVISION

Current evidence: `OpenAIErrorClassifierTest.kt` exists, but it explicitly contains tests named `KNOWN BUG -- 14291 falsely matches 429 substring`, `KNOWN BUG -- 5002 falsely matches 500 substring`, and `KNOWN BUG -- 5003 falsely matches 500 substring`. The production code in `OpenAIErrorClassifier.kt` still uses naive `message.contains("429")` and `message.contains("500")` matching.

Assessment: this is not "already covered." It is a live correctness bug with tests currently preserving the bug. The right work now is to fix the classifier and replace the bug-capture tests with true regressions for proper status-boundary handling.

ROI: very high.

### 1.4 CloudStreamRetryPolicyTest — ALREADY FIXED

Current evidence: `CloudStreamRetryPolicyTest.kt` now covers retryable vs non-retryable behavior, rate-limit wait handling, backoff doubling/cap, max-attempt cutoffs, and the post-partial-output fail/stop behavior. `CloudStreamRetryRunnerTest.kt` also exists and exercises the policy in flow context.

Assessment: this item is done. The plan's `shouldRetry(...)` language is stale; the current production API is `CloudStreamRetryPolicy.decide(...)`, and it is directly tested.

ROI if revisited: low.

### 1.5 ShellToolTest — NEEDS REVISION

Current evidence: the current direct file is `ShellToolBlocklistTest.kt`, not `ShellToolTest.kt`. It covers `ShellTool.validate(...)`: blocked commands, metacharacters, empty/missing command, and safe-command acceptance. It does not cover `ShellInvocation.execute(...)` timeout, truncation, exit-code formatting, or pre-cancel short-circuit in `ShellTool.kt`.

Assessment: directionally right, but the target changed. Validation coverage now exists. The missing value is execution-path coverage.

ROI: medium.

### 1.6 AskUserToolTest — STILL VALID

Current evidence: there is still no `AskUserToolTest.kt`. The only related direct coverage is `UserResponseChannelTest.kt`, which tests the channel primitive, not the tool.

Assessment: this gap is still real. `AskUserTool.kt` has tool-level behavior that the channel test does not cover: validation when another ask is pending, event emission, timeout output, and cancellation mapping.

ROI: medium-high.

## Phase 2: Orchestration + Trace

### 2.1 SessionCoordinatorTest — STILL VALID

Current evidence: there is still no `SessionCoordinatorTest.kt`. `SessionCoordinator.kt` has public behavior around `submit(...)`, `createAndSubmit(...)`, `enqueue(...)`, `attachSession(...)`, `detachSession()`, `consumeDeadSessionFileName()`, `clearSession()`, and automatic drain logic.

Assessment: this remains a real seam. It is stateful, concurrent, user-visible, and easy to regress with subtle queueing bugs.

ROI: high.

### 2.2 AgentServiceEventHandlerTest — STILL VALID

Current evidence: there is still no `AgentServiceEventHandlerTest.kt`. `AgentServiceEventHandler.handleEvent(...)` contains a large `when` over agent events, mutating recording state, status text, and overlay behavior.

Assessment: still valid. This file is exactly the kind of extracted side-effect hub that unit tests should hit directly.

ROI: high.

### 2.3 TurnPlanningPhaseRunnerTest — STILL VALID

Current evidence: there is still no `TurnPlanningPhaseRunnerTest.kt`. `TurnPlanningPhaseRunner.runPlanningPhase(...)` now does more than the old plan called out: prompt building, screen-observation history write, model resolution, trace request/response logging, arbitration, dropped-tool warnings, and `agent_thought` emission.

Assessment: still valid, arguably more so than before.

ROI: high.

### 2.4 CognitionTraceRedactorTest — NEEDS REVISION

Current evidence: there is now direct coverage in `CognitionTraceRedactorSecurityTest.kt`. It covers redaction of password-like JSON keys, token-like JSON keys, email text, bearer tokens, nested password fields, and non-sensitive text preservation.

Assessment: the blank gap is gone, but the plan item is stale. The current file name is different, and the remaining useful cases are now narrower: JWT-looking tokens, long mixed alphanumeric tokens, multiple sensitive patterns in one string, and combinations flowing through `redactJson(...)` and `redactText(...)`. Re-adding basic password/email cases would be duplicate work.

ROI: medium.

### 2.5 TypeExecutorTest — NEEDS REVISION

Current evidence: `TypeExecutorTest.kt` now exists, but every test is a cancellation-path test: direct-set cancelled, tap-to-focus cancelled, focused-set cancelled, and focused typing cancelled.

Assessment: the current gap is not "no tests." The current gap is that the main success/failure behavior is still unproven: direct set success, tap-to-focus fallback success, no editable target failure, and VD-mode no-tap behavior.

ROI: medium-high.

### 2.6 TurnExecutionPhaseRunnerTest — STILL VALID

Current evidence: there is still no `TurnExecutionPhaseRunnerTest.kt`. `TurnOutcomeDecisionTest.kt` now covers the extracted pure `AgentRuntimeTypes.decideTurnOutcome(...)`, but that does not cover `TurnExecutionPhaseRunner.executeActions(...)`.

Assessment: this item is still valid. The extracted pure logic means the execution seam should now be tested more precisely: history writes, approval event emission, post-action observation capture, and abort-on-failure behavior. Re-testing `decideTurnOutcome(...)` through `AgentTurnRunner` would just be coverage theater.

ROI: high.

## Phase 3: Onboarding + Chat + First VD Seam

### 3.1 OnboardingViewModelTest — STILL VALID

Current evidence: there is still no `OnboardingViewModelTest.kt`. `OnboardingViewModel.kt` remains a large async state machine with permission polling, provider selection, OAuth/manual auth branching, draft-key persistence, demo preflight, step skipping, and auto-advance timing.

Assessment: this is still one of the highest-value blank areas in the entire codebase.

ROI: very high.

### 3.2 ChatEventReducerTest — NEEDS REVISION

Current evidence: there is still no `ChatEventReducerTest.kt`, but the chat area is no longer blank. There are helper-oriented tests: `ChatActionExecutionMappingTest.kt`, `ChatCompletionMessageTest.kt`, `ChatCompletionSummaryTest.kt`, `ChatRebindEventFilterTest.kt`, and `ChatStartupFailureTest.kt`.

Assessment: the original direction is right, but the details are stale. Replay-cutoff filtering is already covered by `ChatRebindEventFilterTest.kt`, and some completion/action helper logic is already covered indirectly. The missing value now is direct `ChatEventReducer.handle(...)` lifecycle coverage: task start, delta accumulation, action proposal/execution ordering, supplement insertion, and task completion/error transitions.

ROI: medium-high.

### 3.3 MessageConverterTest — STILL VALID

Current evidence: there is still no `MessageConverterTest.kt`. `history/model/MessageConverter.kt` contains real mapping logic in both directions, including action-state parsing and UI-facing tool name/icon conversion.

Assessment: still valid. This is a small pure seam with zero direct protection.

ROI: high.

### 3.4 FileTraceRecorderTest — NEEDS REVISION

Current evidence: `FileTraceRecorderTest.kt` now exists, but it only covers durability: `flush()`, `close()`, and repeated flush-after-record rounds.

Assessment: the plan item is only partially complete. The useful remaining work is path sanitization via `newArtifactPath(...)` / `sanitizePathSegment(...)`, artifact directory creation, and concurrency behavior around the async write channel. The file is not blank anymore.

ROI: medium.

### 3.5 VirtualDisplayViewerTouchHandlerTest — STILL VALID

Current evidence: there is still no `VirtualDisplayViewerTouchHandlerTest.kt`. `VirtualDisplayViewerTouchHandler.kt` still contains pure, testable logic for coordinate scaling/clamping, display validity checks, tap vs swipe shell fallback, and action-state tracking across down/move/up.

Assessment: still valid.

ROI: medium-high.

## Backlog

### TurnExecutionPhaseRunnerTest — STILL VALID

Current evidence: same as Phase 2.6. Still absent.

Assessment: still valid, but this backlog entry is now redundant because the class still belongs in the main priorities, not deferred cleanup.

ROI: high.

### CloudLlmRetryTest — STILL VALID

Current evidence: there is still no `CloudLlmRetryTest.kt`. `CloudLlmRetry.executeWithRetry(...)` remains the non-streaming retry policy used by `OpenAIResponseClient`, `ChatCompletionClient`, and `CodexResponseClient`.

Assessment: still valid, but it is no longer the first LLM test I would write. Client-level tests and the classifier bug are more urgent.

ROI: medium.

### SwipeExecutorTest — ALREADY FIXED

Current evidence: `SwipeExecutorTest.kt` now exists and covers cancellation, success with observation, and failure.

Assessment: this broad gap is closed. The coverage is not exhaustive, but the original backlog rationale of "simpler action; lower strategic value" is now satisfied well enough.

ROI if revisited: low.

### VirtualDisplaySurfaceControllerTest — STILL VALID

Current evidence: there is still no `VirtualDisplaySurfaceControllerTest.kt`. `VirtualDisplaySurfaceController.kt` remains a unit-testable stateful collaborator with mode switching, invalid-surface guards, and Shizuku result handling.

Assessment: still valid.

ROI: medium.

### VirtualDisplayCaptureCoordinatorTest — STILL VALID

Current evidence: there is still no `VirtualDisplayCaptureCoordinatorTest.kt`. `VirtualDisplayCaptureCoordinator.kt` owns PixelCopy fallback, repeated-failure demotion to ImageReader, trace artifact storage for a11y trees, and screenshot capture mode switching.

Assessment: still valid and now arguably more important because this class is where live-preview capture fallback logic lives.

ROI: medium-high.

### AgentTraceArtifactsTest — STILL VALID

Current evidence: there is still no `AgentTraceArtifactsTest.kt`. `AgentTraceArtifacts.kt` still packages redacted request/response/tool/snapshot artifacts and is a key trace-integrity seam.

Assessment: still valid.

ROI: medium.

### OnboardingStoreTest — STILL VALID

Current evidence: there is still no `OnboardingStoreTest.kt`.

Assessment: still valid. `OnboardingStore.kt` now owns outcome persistence, auth-method storage, encrypted draft-key storage, and migration behavior. The fail-closed coverage in `AppSettingsStoreFailClosedTest.kt` and `OAuthCredentialStoreFailClosedTest.kt` does not cover this store.

ROI: medium-high.

### PermissionStateMonitorTest — STILL VALID

Current evidence: there is still no `PermissionStateMonitorTest.kt`.

Assessment: still valid. `deriveRepairModel(...)` is pure logic and very cheap to test.

ROI: high.

### HttpLlmCredentialValidatorTest — STILL VALID

Current evidence: there is still no `HttpLlmCredentialValidatorTest.kt`.

Assessment: still valid. The validator maps HTTP codes, SSL failures, timeouts, and IO failures into user-facing states. That is classic high-leverage unit-test territory.

ROI: high.

### MobileActionInvocation / UIActionInvocation Tests — NEEDS REVISION

Current evidence: the current tree already has partial coverage in this neighborhood: `MobileActionToolTest.kt` validates parameter handling, and `CapturePrivacyGateTest.kt` exercises observation masking through both `MobileActionInvocation` and `UIActionInvocation`.

Assessment: the original gap is only partially still true. The missing value now is not raw existence testing; it is direct coverage of outcome mapping, cancellation, description formatting, and post-action observation failure handling.

ROI: medium.

### ChatSessionHistoryControllerTest — STILL VALID

Current evidence: there is still no `ChatSessionHistoryControllerTest.kt`.

Assessment: still valid. The controller coordinates resume/new/delete flows and view-model-facing callbacks. It is small enough to test and still unprotected.

ROI: medium-high.

### ChatViewModelTest — NEEDS REVISION

Current evidence: there is still no `ChatViewModelTest.kt`, but helper behavior is now partially covered by `ChatCompletionSummaryTest.kt`, `ChatCompletionMessageTest.kt`, `ChatActionExecutionMappingTest.kt`, `ChatRebindEventFilterTest.kt`, and `ChatStartupFailureTest.kt`.

Assessment: the remaining useful target is now the actual `ChatViewModel` coordination layer: `startEventCollection(...)`, pending input consumption, approvals, session history delegation, resume/new-session flows, and teardown. Do not spend time re-testing the extracted helper functions just to inflate coverage.

ROI: medium-high.

## New Gaps the Original Plan Does Not Cover Well

These are the places where the old plan is now missing the actual current risk.

### 1. `ChatCompletionInterop` Is Untested and Now Important

`ChatCompletionInterop.kt` now does non-trivial conversion between `ResponseInputItem` / `FunctionTool` and Chat Completions types, including assistant text + grouped tool calls, multimodal user content, and system-role normalization.

Why it matters now: provider routing now uses `ChatCompletionClient` for OpenRouter/chat-style models. That makes this conversion layer a real external-contract boundary, not just a helper.

### 2. `ToolParameterExtractor` Is Untested

`ToolParameterExtractor.kt` handles known/unknown/raw tool-schema representations from the OpenAI SDK. It feeds `CodexRequestBuilder.convertTools(...)`.

Why it matters now: this is now part of the tool-schema boundary for both Codex and chat-compatible providers. A broken schema extraction silently breaks tool calling.

### 3. Client-Level Coverage Is Still Missing

There are still no direct tests for:

- `CodexResponseClient`
- `OpenAIResponseClient`
- `ChatCompletionClient`

The old plan focused on parsers and retry helpers, which was reasonable on April 8. Today the bigger risk is client-level orchestration: request construction, streaming terminal conditions, tool-call accumulation, finish-reason handling, and error translation.

### 4. `OpenAIErrorClassifier` Is Not Just Under-Tested; It Is Wrong

This deserves repeating. The original plan framed this as missing coverage. Current reality is worse: the implementation has false-positive logic, and the tests explicitly encode the bug as current behavior. This should be treated as a fix plus test rewrite, not just "add more tests."

### 5. `SessionLlmBootstrapper` Has Gained Important Responsibilities

Current direct coverage is minimal: `SessionLlmBootstrapperTest.kt` only checks off-main-thread enforcement, while `SessionServicesProviderRoutingTest.kt` covers two provider-routing cases through `SessionServices.create(...)`.

What is still untested:

- provider base URL override extraction (`__BASE_URL_<PROVIDER>`)
- fallback catalog loading when `llm_models.json` is missing or malformed
- asset-manager-based catalog caching
- missing-key enforcement across main and executor model combinations beyond the one pro-mode case

This is now a real seam and should be in the revised plan.

### 6. `SessionCheckpointCoordinator` and `SessionAgentRunner` Were Quietly Dropped

The original review called out `SessionAgentRunner` and `SessionCheckpointCoordinator` as part of the runtime/session orchestration risk. The final improvement plan dropped them. They are still untested now.

That drop no longer looks justified. `SessionCheckpointCoordinator.kt` is especially worth revisiting because it owns checkpoint scheduling and snapshot conversion logic.

### 7. `LlmInputItemsTraceSerializer` Is Still Untested

The original review mentioned it in the trace/privacy gap. The final plan did not. It is still untested, and it now depends on `ChatCompletionInterop.extractStringContent(...)`, which makes the trace path share logic with the chat conversion path.

## Revised Priority Order

This is the order I would use today. It is not the original phase order.

### 1. Fix the LLM Contract Boundary That Is Still Wrong or Untested

Top targets:

- `OpenAIErrorClassifier`
- `CodexRequestBuilder`
- `ChatCompletionInterop`
- `ToolParameterExtractor`
- selected client-level tests for `CodexResponseClient`, `OpenAIResponseClient`, and `ChatCompletionClient`
- targeted add-on coverage for `CodexSseParser` interleaved multi-tool streams

Why first: this is where external API/provider regressions happen, and one piece is already known-bad today.

### 2. Cover the Main Orchestration Seams

Top targets:

- `TurnPlanningPhaseRunner`
- `TurnExecutionPhaseRunner`
- `SessionCoordinator`
- `AgentServiceEventHandler`
- then revisit `SessionCheckpointCoordinator`

Why second: these are the joins where tested components are combined into user-visible runtime behavior.

### 3. Cover the Remaining Safety-Sensitive Tool Boundaries

Top targets:

- `AskUserTool`
- execution-path coverage for `ShellTool`
- broaden `TypeExecutor` beyond cancellation-only cases

Why here: these are meaningful boundaries, but the shell validation surface is already partially covered and the LLM/orchestration risk is now higher.

### 4. Attack Onboarding/Auth, Which Is Still Basically Blank

Top targets:

- `OnboardingViewModel`
- `OnboardingStore`
- `PermissionStateMonitor`
- `HttpLlmCredentialValidator`
- selected pure helpers in `OpenAIOAuth`

Why here: this remains high-value user-facing logic with almost no direct tests.

### 5. Tighten Chat/History State Management

Top targets:

- `ChatEventReducer`
- `MessageConverter`
- `ChatSessionHistoryController`
- targeted `ChatViewModel` coordination tests

Why here: helper extraction improved things, but the actual reducer/viewmodel/controller behavior still is not directly covered.

### 6. Finish the Virtual Display and Trace Second Wave

Top targets:

- `VirtualDisplayViewerTouchHandler`
- `VirtualDisplaySurfaceController`
- `VirtualDisplayCaptureCoordinator`
- `AgentTraceArtifacts`
- broaden `FileTraceRecorder`

Why last: still worthwhile, but lower leverage than the currently underprotected LLM and orchestration boundaries.

### 7. Do Test Infrastructure Cleanup Opportunistically, Not as a Gate

Only keep:

- a thin `TestSessionServicesBuilder`
- maybe a tiny scripted `LLMClient` fixture if it directly helps new tests

Do not open with a broad cleanup phase.

## What Is Engineering Theater

These are the parts I would explicitly de-prioritize or delete as dedicated work items.

### 1. Assertion-Library Standardization

Changing JUnit asserts to Truth in two files is not test-architecture improvement.

### 2. A Standalone Fixture-Consolidation Push for Executor Platform Fakes

The current local fakes are explicit and readable. Replacing them with a generalized fixture layer is likely to make the tests worse, not better.

### 3. A Dedicated Cleanup Pass for Low-Value Snapshot Tests

Yes, `AgentDefTest.kt` exact tool-list assertions and some `OpenAppToolTest.kt` alias checks are low-value. No, that does not deserve its own phase.

### 4. Re-testing Already-Extracted Pure Logic Through Larger Orchestration Tests

`AgentRuntimeTypes.decideTurnOutcome(...)` is already directly covered by `TurnOutcomeDecisionTest.kt`. Writing bigger tests that only re-prove the same logic would be coverage cosplay.

## Final Recommendation

Keep the spirit of the original review, but throw away the original sequencing.

The next pass should start with:

1. LLM boundary fixes and tests, especially `OpenAIErrorClassifier`, `CodexRequestBuilder`, `ChatCompletionInterop`, `ToolParameterExtractor`, and selected client-level tests.
2. Orchestration seam tests for `TurnPlanningPhaseRunner`, `TurnExecutionPhaseRunner`, `SessionCoordinator`, and `AgentServiceEventHandler`.
3. Onboarding/auth and chat state once the two highest-risk boundaries are no longer soft.

Do not spend the next round on assertion-style cleanup, fixture gardening, or "prune snapshot tests" work. That would be polishing the bike shed while the actual runtime boundary and orchestration seams are still underprotected.
