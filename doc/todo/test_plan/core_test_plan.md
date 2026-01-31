# Core Test Plan (Robustness First)

This plan targets robustness, stability, and scalability. Coverage is a byproduct, not the goal.
Focus: deterministic core logic, failure recovery, concurrency, and resource bounds. Avoid tests that only pass because everything was mocked into triviality.

## Goals

- Prevent regressions in state machines, tool execution, and history management.
- Validate failure recovery and retry behavior under realistic error modes.
- Ensure scalability controls (token budget, history growth, approvals) stay bounded.
- Improve readability/testability where it blocks high-value tests.

## Non-Goals (for now)

- UI/Compose tests (manual or instrumentation only).
- Full Android accessibility integration in JVM unit tests.
- “Green because mock said so” tests (e.g., LLM client faked into a happy path only).

## Core Invariants (Must Hold)

### Session lifecycle (`protocol/SessionState`, `session/AgentSession`)
- Only valid transitions occur (Created → Running → Idle/Paused → Completed/Shutdown).
- `SessionStarted` emitted once; `SessionCompleted` emitted once.
- `Shutdown` stops agent and cleans up services, regardless of current state.

### Tool execution lifecycle (`tool/ToolCallState`, `tool/ToolRouter`)
- Validation failures never execute a tool.
- Approval wait handles: approve → execute, deny/abort/timeout → cancelled.
- Terminal states are final; active calls are cleaned up.
- Snapshot refresh happens after approval wait.

### History integrity (`history/HistoryManager`)
- Every function call has a corresponding output after normalization.
- No orphaned outputs remain.
- Token estimation cache is invalidated on every mutation.
- Truncation respects policy; NONE means no truncation.

### Failure recovery (`agent/Agent`)
- Recoverable errors retry; non-recoverable errors terminate.
- Network/DNS classification is consistent and deterministic.
- Cancel/stop interrupts do not produce extra actions/events.

## Test Philosophy (No Fake Reality)

- Prefer deterministic fakes over mocks (fake platform, fake tools, fake clock).
- Record/replay real LLM responses for integration-style core tests.
- Keep unit tests about behavior and invariants, not internal implementation.

## Test Coverage Map (Risk-Weighted)

### P0 — Immediate
- `HistoryManager` normalization, truncation, rollback, token estimate.
- `PolicyEngine` allow/deny/risk evaluation and overrides.
- `ToolRouter` state machine: validation error, approval timeout, deny/abort/approve.
- Session persistence pipeline: `SessionRecordingService` debounce + write, `SessionStorage` I/O,
  `SessionHistoryManager` list/load/resume semantics.

### P1 — High
- `Agent` error recovery classification (DNS vs transient vs fatal).
- `Agent` turn execution contract (single tool, completion behavior).
- `SessionAgentRunner` stop/pause/resume semantics.
- `Op.Interrupt` vs `Op.Shutdown`: task ends vs session termination, and event emission.

### P2 — Medium
- Concurrency in `ToolRouter` (overlapping calls, cancellation).
- SharedFlow behavior in `AgentSession` (event replay/buffer limits).
- Compression strategy in `HistoryManager.compress()`.

### P3 — Foundational (Low Risk, Low Effort)
- `ToolRegistry` registration, filtering, and schema generation.
- Tool parameter validation for built-in tools (`tool/impl/*.kt`).
- `Perceptor.toPromptJson()` output shape using synthetic `ScreenSnapshot`.
- `Turn` response processing (complete_task vs action calls).
- Core data model invariants (`Bounds`, `Point`, `ScreenSnapshot`).

## Test Types and Scaffolding

### Unit Tests (JVM)
- `HistoryManagerTest`, `PolicyEngineTest`, `ToolRouterTest`.
- Fakes:
  - `FakeAndroidPlatform` with deterministic `captureScreen()`.
  - `FakeToolSpec` with predictable validation/execution behavior.
  - `FakeClock` / `DelayController` if time-sensitive logic needs determinism.

### Integration-Style Core Tests
- Record/replay LLM responses for `Agent`/`Turn` behavior.
- Validate end-to-end tool selection and history updates without real Android UI.
- Avoid a fake LLM that always returns a happy tool call; it hides failures.

### Manual/Visual Tests (Required for Reality)
- Use `scripts/debug-run.sh` to validate real agent behavior on-device.
- Verify: perception quality, action selection, action execution, and recovery.

## Suggested Refactors (Only if Needed for Tests)

- Inject clock/delay provider into `Agent` and `ToolRouter` (eliminate real sleeps).
- Extract `Turn` execution strategy behind an interface for deterministic replay.
- Introduce test fixtures module (`app/src/testFixtures`) with reusable fakes.

## Test Infrastructure Notes

- Prefer Truth + JUnit 4 (consistent with existing tests).
- Add coroutines test library if we need deterministic timing.
- Add Robolectric only if a JVM test needs `android.util.Log` behavior.

## Scalability/Robustness Tests

- History growth under long sessions: ensure auto-compress triggers.
- Approval queue under load: pending approvals do not leak.
- ToolRouter call cleanup: `activeToolCalls` is empty after terminal states.

## Initial Test Implementation (Starting Now)

- `HistoryManagerTest`: normalize history, truncate policy NONE, drop last turns.
- `PolicyEngineTest`: deny/allow list, SMART mode high risk asks.
- `ToolRouterTest`: unknown tool → error; approval timeout → cancelled.

## Reporting & Quality Gate

- Every P0 test must pass before merge.
- New bugs get a regression test first.

## Anti-Patterns to Avoid

- Over-mocking: if it takes more than a couple of mocks, test the wrong thing.
- Fake LLM responses that never fail: worthless for robustness.
- UI snapshot tests: fragile and low signal.

## Progress (Keep Updated)

- ✅ `HistoryManagerTest` (normalize, rollback, truncation, pair removal)
- ✅ `HistoryManagerTest` (compress reduces tokens)
- ✅ `PolicyEngineTest` (modes + risk + allow/deny)
- ✅ `ToolRouterTest` (unknown tool, approval timeout, approve, deny)
- ✅ `ToolRouterTest` (concurrency + cleanup)
- ✅ `SessionStorageTest` (read/write + ordering)
- ✅ `SessionRecordingServiceTest` (debounce + completion metadata)
- ✅ `SessionRecordingServiceTest` (action block update)
- ✅ `SessionHistoryManagerTest` (list/load + missing session)
- ✅ `AgentMessageBufferTest` (text/action sequencing)
- ✅ `ToolCallStateTest` (terminal state detection)
- ✅ `ToolRegistryTest` (register/unregister/filter)
- ⚠️ `TurnTest` (removed: trivial completion logic)
- ✅ `AgentSessionTest` (shutdown smoke test only)
- ⚠️ `SessionAgentRunnerTest` (removed: happy-path-only test)
- ✅ `AgentErrorRecoveryTest` (dns vs transient)
- ✅ `MobileActionToolTest` (basic validation)
- ✅ `AppControlToolTest` (basic validation)
- ✅ `CompleteTaskToolTest` (basic validation)
- ✅ `PerceptorTest` (prompt JSON shape)
- ✅ `ModelsTest` (bounds + snapshot)
