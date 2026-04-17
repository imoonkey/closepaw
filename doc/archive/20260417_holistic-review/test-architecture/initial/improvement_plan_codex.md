# Test Architecture Improvement Plan (Codex)

**Date:** 2026-04-08  
**Derived from:** `design_codex.md`

## Guiding Principles

- Raise confidence at module boundaries before adding more UI or schema tests.
- Prefer tests around pure reducers, parsers, classifiers, and coordinators over brittle Android-runtime mocks.
- When a class is hard to test, extract the pure logic first instead of writing a giant mock-heavy test.
- Spend maintenance budget on behavior with real regression cost, not on static data snapshots.

## Priority 0: Build the Missing Boundary Safety Net

These are the highest-ROI additions because they cover the external contract and safety boundaries that currently have no direct protection.

### LLM wire-format, parser, and retry tests

Add these first:

- `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexRequestBuilderTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexSseParserTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudLlmRetryTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicyTest.kt` or `CloudStreamRetryRunnerTest.kt`

Cover these behaviors:

- user vs assistant content conversion in Codex request JSON
- function-call input and function-call-output serialization
- parallel tool-call accumulation across `output_index`
- trailing SSE buffer flush and `[DONE]` handling
- retry-after extraction, 429 classification, 5xx classification, connectivity classification
- retry behavior after partial stream output vs before any output
- bounded exponential backoff and retry-stop behavior

### Safety-sensitive tool tests

Add next:

- `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/AskUserToolTest.kt`

Cover these behaviors:

- blocked destructive command rejection
- safe command acceptance
- timeout handling
- output truncation behavior
- pending ask-user rejection when another request is active
- timeout and cancellation semantics for ask-user

## Priority 1: Cover Orchestration State Machines

These classes coordinate already-tested collaborators, so the missing protection is at the seam.

### Session and turn orchestration

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/session/SessionCoordinatorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunnerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunnerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandlerTest.kt`

Focus cases:

- queue vs immediate-submit behavior in `SessionCoordinator`
- dead-session teardown and `consumeDeadSessionFileName()`
- planning-phase history write, arbitration warning, and thought emission
- execution-phase observation capture, history output, and abort-on-failure behavior
- event-handler effects on recording service, overlay callbacks, and status messages

Refactor first if needed:

- inject clock/delay wrappers where time is part of the behavior
- keep Android objects behind small interfaces in tests

## Priority 2: Cover Onboarding and Auth Without Live Network Calls

This is the first-run and credential path. It should be protected, but not with live-network tests.

### Onboarding state machine

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModelTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoControllerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/OnboardingStoreTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidatorTest.kt`

Focus cases:

- step selection on startup from stored outcomes and live permission state
- accessibility poll-after-return behavior
- OAuth success/error transitions
- manual API-key validation success, invalid-key, and transient-error paths
- demo success vs timeout vs wrong-package completion
- onboarding-store migration, outcome persistence, and API-key draft behavior
- permission-repair-model derivation
- credential-validator mapping of 200, 401/403, 429/5xx, timeout, SSL, and IO exceptions

### Auth helper coverage

Add targeted pure tests, not live flow tests:

- `app/src/test/kotlin/com/moonkey/androidagent/auth/OpenAIOAuthTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/auth/OAuthCredentialStoreTest.kt` only if storage policy is extracted from Android persistence details

Focus cases:

- PKCE shape and URL construction
- JWT email parsing and account-id extraction
- callback parsing and state mismatch handling
- token-expiring-soon calculation

Recommended refactor before testing:

- split `OpenAIOAuth.kt` into pure helpers plus transport adapters
- split `OAuthCredentialStore` into storage-policy helpers plus Android persistence wrapper

## Priority 3: Close the Runtime Device-Control Gaps

### Action verification and missing executors

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/TypeExecutorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetectorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCoreTest.kt`

Focus cases:

- direct text set vs tap-to-focus fallback
- VD mode disabling tap-to-focus fallback
- swipe cancellation/failure/success verification
- unchanged vs changed vs unverifiable post-action detection
- scroll-boundary warning behavior
- point-action container promotion and ambiguity fallback

### Virtual display pure collaborators

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayViewerTouchHandlerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplaySurfaceControllerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinatorTest.kt`

Focus cases:

- viewer coordinate scaling and clamping
- tap vs swipe shell fallback behavior
- invalid-display and invalid-surface short-circuit cases
- surface mode switching success/failure behavior
- pixel-copy failure fallback to image-reader path

Do not start with:

- full `VirtualDisplayPlatform` end-to-end unit tests
- full Shizuku binder transport tests

Those belong to device/instrumented coverage once the pure collaborators are covered.

## Priority 4: Protect User-Visible Chat and Trace Behavior

### Chat state and persistence mapping

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatSessionHistoryControllerTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/history/model/MessageConverterTest.kt`

Focus cases:

- streaming delta accumulation and completion transitions
- action-card proposal to execution to success/failure mapping
- replay cutoff behavior when rebinding to a live session
- session list loading/resume/delete flows
- `MessageRecord` to `ChatMessage` round-trip invariants

### Trace and privacy

Add:

- `app/src/test/kotlin/com/moonkey/androidagent/trace/CognitionTraceRedactorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/trace/AgentTraceArtifactsTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/trace/FileTraceRecorderTest.kt`

Focus cases:

- email, bearer token, long token, and JWT redaction
- sensitive-key redaction inside JSON
- artifact naming/path sanitization
- flush and close semantics in the file recorder

## Priority 5: Reduce Test Maintenance Cost

The current suite is already paying duplication tax. Fixing that will make the next phases faster and safer.

### Consolidate fixtures

Create or extend shared fixtures under `app/src/test/kotlin/com/moonkey/androidagent/test/`:

- `RecordingPlatform` for action executor tests
- `ScriptedLLMClient`, `CapturingLLMClient`, and `FailingLLMClient`
- `TestSessionServicesBuilder`
- shared sample snapshots and perception elements

### Standardize conventions

- Standardize on Truth instead of mixing Truth and JUnit assertions.
- Keep backtick behavior-style names.
- Prefer `runTest` with virtual time for new async tests.
- Avoid more exact-data tests like alias-entry inventories unless the data is itself product logic.

### Trim low-value patterns

Do less of this:

- exact snapshot assertions over full allowed-tool lists in `AgentDefTest`
- per-entry alias-map tests in `OpenAppToolTest`

Do more of this:

- tests that protect behavior or invariants when collaborators change

## Recommended Execution Order

1. Add shared fixtures first so the next test files stay small.
2. Land the LLM parser/retry/classifier tests.
3. Land `ShellToolTest`, `AskUserToolTest`, and `SessionCoordinatorTest`.
4. Add `TurnPlanningPhaseRunnerTest`, `TurnExecutionPhaseRunnerTest`, and `AgentServiceEventHandlerTest`.
5. Add onboarding/auth state-machine tests.
6. Add action and virtual-display collaborator tests.
7. Backfill chat reducer/controller and trace/privacy tests.

## Non-Goals

- Do not expand unit testing of pure Compose rendering.
- Do not add live-network tests.
- Do not add blanket tests for `protocol/*`, theme constants, resource files, or static content inventories.
- Do not try to simulate the full Android runtime in unit tests when a smaller extracted helper would do.

## Success Criteria

This plan is working if, after the next few rounds:

- the highest-risk blank packages are no longer blank
- new tests sit at seams and collaborators, not at static data tables
- fixture duplication drops
- parser/retry/auth/service regressions can be caught without a device
- UI-only files remain mostly exempt unless logic is extracted out of them
