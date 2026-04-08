# Protocol & Communication Improvement Plan (Codex)

## Goals

- Make the protocol say only what the runtime actually guarantees.
- Keep the closed command/state sets that are already working.
- Delete speculative surface before introducing new abstraction.
- Separate durable domain facts from UI projection, or rename the boundary honestly.

## Guiding Position

This should move toward a smaller, sharper contract, not a richer hierarchy.

The current system does not need more inheritance, more wrapper types, or a generic event envelope. It needs fewer nouns, cleaner lifecycle semantics, and one obvious meaning for each type.

## Phase 1: Prune Event Surface

### 1. Collapse the marker-interface taxonomy

- Remove `AgentEventDomains.kt` entirely, or keep at most a very small number of categories only if a real consumer dispatches by them.
- Keep `AgentEvent` as the main closed event root.
- Let file grouping carry the domain organization instead of a second type hierarchy.

### 2. Remove dead or redundant events

- Drop `TurnStarted.phase`, or stop emitting the initial `TurnPhaseChanged(PERCEPTION)`.
- Remove `TurnCompleted` unless a concrete consumer is added.
- Remove `ApprovalResolved` unless it is used for recording, UI, or replay.
- Remove `TodosUpdated` and `ScratchpadUpdated` unless something actually reacts to them.
- Remove `SessionError` unless the system will genuinely emit it.

### 3. Remove duplicate approval identity

- Remove `ApprovalRequired.actionId`.
- Use a single ID field on approval events and approval ops.
- Make that ID name match the underlying concept.

## Phase 2: Repair Lifecycle Semantics

### 1. Split task outcome from session end reason

- Replace `CompletionReason` with two enums:
- `TaskOutcome`
- `SessionEndReason`

- Suggested shape:
- `TaskOutcome`: `GOAL_ACHIEVED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`, `USER_STOPPED`
- `SessionEndReason`: `USER_STOPPED`, `IDLE_TIMEOUT`, `INTERRUPTED`

### 2. Keep task and session events semantically separate

- `TaskCompleted(..., outcome: TaskOutcome)`
- `SessionCompleted(..., reason: SessionEndReason)`

- This removes impossible states from consumers and makes the hot-idle model easier to read.

### 3. Decide whether hot idle deserves its own event

- If UI/recording care about the session becoming reusable after a task, add a dedicated idle-transition event.
- If not, keep hot idle internal and stop overloading `SessionCompleted` to imply more than “session shut down.”

## Phase 3: Unify Interaction and Approval Naming

### 1. Pick one canonical ID noun

- Prefer `callId` or `toolCallId`.
- Do not mix `callId` and `actionId` for the same underlying value.

### 2. Rename approval ops to the actual behavior

- Rename `Op.Approve` to something like `ResolveApproval`.
- Suggested shape:

```kotlin
data class ResolveApproval(
    val callId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val packageName: String? = null
) : Op
```

### 3. Make approval payloads immutable and minimal

- Remove `ApprovalDetails.args` if the UI never uses it.
- If raw arguments must survive, prefer an immutable representation such as `JsonObject`, not `JSONObject`.

## Phase 4: Reshape `SessionConfig`

### 1. Decide what `SessionConfig` is for

It should be one of these, not all of them:

- Full runtime config
- Persistable resume config
- Session-launch UI config

### 2. Minimum fix

- Persist and restore every field that materially affects runtime behavior.
- If some fields are intentionally non-persisted, document that explicitly.

### 3. Better structure

- Split by ownership:
- `ExecutionConfig`: `maxTurns`, `actionDelayMs`, `approvalMode`, `agentMode`, `platformMode`, `perceptionConfig`
- `ModelConfig`: `mainModel`, `executorModel`, backend/local settings
- `ObservabilityConfig`: `debugMode`, `traceEnabled`, `traceRunId`
- `EvalConfig`: `excludedTools` only if this really belongs in production session startup

### 4. Make invalid LLM states unrepresentable

- Replace `SessionLlmConfig(backendType, localConfig?)` with a shape that cannot contradict itself.
- If that feels too heavy, at least enforce invariants in an `init` block and stop carrying unused null combinations.

## Phase 5: Clarify the Boundary of `protocol/`

### Option A: `protocol/` is the UI event stream

- Keep `StatusUpdate`, `ThoughtUpdate`, and related display-shaped events there.
- Rename the package or document clearly that this is a UI-facing session stream, not a pure domain contract.

### Option B: `protocol/` is the domain contract

- Move these out of it:
- `StatusUpdate`
- `ThoughtUpdate`
- `sanitizeThought()`
- emoji/display formatting helpers

- This is the direction I would choose. It keeps the core contract cleaner and reduces accidental coupling between agent logic and presentation.

## Suggested Target Surface

### Keep sealed

- `Op`
- `SessionState`
- `AgentEvent`

### Keep small enums

- `TurnPhase`
- `AskUserType`
- `ApprovalDecision`
- `ApprovalScope`
- `AppTier`
- `ScreenStatePhase`

### Split or rename

- `CompletionReason` -> `TaskOutcome` + `SessionEndReason`
- `Op.Approve` -> `ResolveApproval(callId, decision, scope, packageName?)`

### Keep only concrete events that are actually consumed

- Session: `SessionStarted`, `SessionCompleted`, `SessionTakeover`, `SessionResumed`
- Task: `TaskStarted`, `TaskCompleted`
- Turn: `TurnStarted`, `TurnPhaseChanged`
- Action: `ActionProposed`, `ActionExecuted`
- Perception: `ScreenCaptured`
- Interaction: `AskUser`, `ApprovalRequired`
- Streaming/UI: keep only if the package is explicitly UI-facing

## Order of Execution

1. Fix completion semantics and approval ID naming first.
2. Prune unused events and marker interfaces next.
3. Reshape `SessionConfig` after the lifecycle contract is stable.
4. Only then decide whether to split UI projection out of `protocol/`.

## Non-Goals

- Do not add a richer event inheritance tree.
- Do not add a generic message envelope.
- Do not keep backward-compat hacks inside this module.

The right move here is simplification, not framework-building.
