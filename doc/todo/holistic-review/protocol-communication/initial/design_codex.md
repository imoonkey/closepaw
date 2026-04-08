# Protocol & Communication Review (Codex)

## Scope

- Reviewed `app/src/main/kotlin/com/moonkey/androidagent/protocol/`.
- Read code consumers where needed to validate whether the protocol surface is actually used.
- Did not read other design docs in `doc/todo/`.

## Executive Assessment

The core command/state surface is good. `Op` and `SessionState` are small, closed, and map to real runtime transitions.

The event layer is the opposite. It is more taxonomized than consumed. Today the module behaves less like a minimal protocol and more like a mixed bag of domain facts, UI projection events, and future-facing types.

## What Works

- `Op` is close to actual user intent. `Takeover`, `Resume`, `Interrupt`, `Shutdown`, `UserInput`, `Supplement`, `UserResponse`, and `Approve` are enough to drive the session.
- `SessionState` is about the right size. `Created`, `Running`, `Idle`, `Paused`, and `Shutdown` match the hot-idle lifecycle well.
- Smaller enums such as `TurnPhase`, `AskUserType`, `AppTier`, `ApprovalDecision`, `ApprovalScope`, and `ScreenStatePhase` are easy to understand and reasonably scoped.
- `sealed` is appropriate for the truly closed sets above. The problem is not “too much sealed”; it is sealing categories that are not buying anything.

## Findings

### High: The event domain hierarchy is not justified by real consumption

- `AgentEventDomains.kt` defines 12 marker interfaces, but the real consumers switch on concrete events or on plain `AgentEvent`, not on the marker types. See `AgentEventDomains.kt` lines 3-37, `ChatEventReducer.kt` lines 37-50, and `AgentServiceEventHandler.kt` lines 28-168.
- A repo-wide search outside `protocol/` found no non-protocol consumer importing `SessionLifecycleEvent`, `TaskLifecycleEvent`, `PlanningStateEvent`, `SubAgentDomainEvent`, `TurnDomainEvent`, `StreamingDomainEvent`, `ActionDomainEvent`, `PerceptionDomainEvent`, `ApprovalDomainEvent`, `AskUserDomainEvent`, `ThoughtDomainEvent`, or `StatusDomainEvent`.
- The naming is also inconsistent. Some markers are `...LifecycleEvent` or `...StateEvent`; others are `...DomainEvent`.
- Net effect: higher taxonomy cost, no simpler dispatch.

### High: The event surface is larger than the contract the system actually honors

- `TurnStarted.phase` is always `PERCEPTION` in `AgentEventDispatcher.kt` lines 45-52, and the agent immediately emits `TurnPhaseChanged(PERCEPTION)` in `Agent.kt` lines 93-94. One of those is redundant.
- `TurnCompleted`, `TodosUpdated`, and `ScratchpadUpdated` are emitted in `AgentEventDispatcher.kt` lines 64-113, but `AgentServiceEventHandler` does not handle them and falls through to the generic unhandled branch in lines 166-167. `ChatEventReducer` ignores them too in lines 39-49.
- `ApprovalResolved` is emitted in `AgentSession.kt` lines 524-530 but has no event consumer. Approval UI state is already resolved locally before the op is submitted.
- `SessionError` exists in `SessionLifecycleEvents.kt` lines 18-23, but a repo-wide search found no producer. `AgentError` shows the same pattern: a rich hierarchy in `AgentError.kt` lines 11-170, but no end-to-end use.
- `ApprovalRequired.actionId` duplicates `ApprovalDetails.callId`. See `ApprovalEvents.kt` lines 4-10 and `ApprovalTypes.kt` lines 31-52. Consumers use `details.callId`, not the duplicate field.

### High: `CompletionReason` conflates task outcome with session shutdown reason

- `CompletionReason` explicitly claims to cover both session and task completion in `CompletionReason.kt` lines 3-24.
- `TaskCompleted` carries that enum in `TaskLifecycleEvents.kt` lines 16-22, and `SessionCompleted` also carries it in `SessionLifecycleEvents.kt` lines 11-15.
- But runtime semantics differ. `TaskCompleted` is emitted when a task ends in `AgentSession.kt` lines 360-368. `SessionCompleted` is emitted later during shutdown and only maps from previous session state to `USER_STOPPED`, `IDLE_TIMEOUT`, or `INTERRUPTED` in `AgentSession.kt` lines 495-505.
- Consumers still branch on impossible or currently unreachable `SessionCompleted` reasons such as `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, and `ERROR`. See `AgentServiceEventHandler.kt` lines 116-123.
- `TASK_IMPOSSIBLE` currently has no producer at all.
- This makes the protocol sound more self-documenting than it really is.

### High: `SessionConfig` is doing too many jobs, and reload exposes the problem

- `SessionConfig` mixes execution control, model routing, platform/perception, diagnostics, and eval knobs in one flat object. See `SessionConfig.kt` lines 12-55.
- The persisted snapshot only stores a subset of those fields in `SessionCheckpointCoordinator.kt` lines 84-94. On reload, `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools` are silently dropped in lines 102-128.
- `SessionLlmConfig` also permits contradictory states: `backendType = OPENAI` with non-null `localConfig`, or `backendType = LOCAL` with `localConfig = null` until callers patch in defaults. See `SessionConfig.kt` lines 57-61.
- The result is not one coherent protocol object. It is a shared bag for runtime, persistence, debug, and experimentation.

### Medium: Identifier naming is inconsistent across the same interaction flow

- `Op.UserResponse` uses `callId` in `Op.kt` lines 84-87.
- `Op.Approve` uses `actionId` in `Op.kt` lines 92-104, but the service and UI pass the same logical value as `callId`.
- `ToolRouter.resolveApproval()` is keyed by `callId`, and `ApprovalDetails` also uses `callId`.
- This is minor at runtime, but expensive for readers. The approval flow is really about resolving a tool-call or interaction ID, not a separate “action” concept.

### Medium: The module mixes protocol facts with UI projection

- `StatusUpdate` is display-ready and carries optional emoji. See `StatusEvents.kt` lines 3-9.
- `ThoughtUpdate` carries already-truncated display text in `ThoughtEvents.kt` lines 3-8.
- The truncation rule lives in `TextUtils.kt` lines 3-13 and is applied before emission in `TurnPlanningPhaseRunner.kt` lines 259-261 and again in UI state handling in `CapsuleStateHolder.kt` lines 105-115.
- That makes `protocol/` part protocol package, part shared UI adapter package.
- This is acceptable only if the boundary is named and treated that way. Right now the package name implies something more stable and semantic than what it actually contains.

### Low: File and type granularity is higher than the amount of real behavior

- This surface is spread across 27 files, 12 marker interfaces, and multiple tiny enums/data classes.
- After pruning unused events and redundant fields, the remaining contract would fit comfortably into a smaller, easier-to-scan layout.

## Bottom Line

The closed command/state core is solid. The event and config layers need simplification.

Right now the protocol says more than the runtime guarantees: more domains, more completion reasons, more error categories, and more event types than the system or UI actually uses. The next iteration should favor deletion and splitting over adding another layer of abstraction.
