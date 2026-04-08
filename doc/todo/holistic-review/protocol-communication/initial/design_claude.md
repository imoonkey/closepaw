# Protocol Module Review

Date: 2026-04-08
Scope: `app/src/main/kotlin/com/moonkey/androidagent/protocol/` (27 files)

---

## 1. Module Inventory

| File | Type | Description |
|---|---|---|
| Op.kt | sealed interface | 8 user operations (SQ) |
| AgentEvent.kt | sealed interface | Base event (sessionId + timestamp) |
| AgentEventDomains.kt | 12 sealed interfaces | Domain marker interfaces |
| SessionLifecycleEvents.kt | 6 data classes | Session start/complete/error/takeover/resume/supplement |
| TaskLifecycleEvents.kt | 2 data classes | Task start/complete |
| TurnEvents.kt | 3 data classes | Turn start/complete/phase-changed |
| StreamingEvents.kt | 1 data class | MessageDelta |
| ActionEvents.kt | 2 data classes | ActionProposed/Executed |
| ApprovalEvents.kt | 2 data classes | ApprovalRequired/Resolved |
| AskUserEvents.kt | 1 data class | AskUser |
| PerceptionEvents.kt | 1 data class | ScreenCaptured |
| PlanningStateEvents.kt | 2 data classes | TodosUpdated/ScratchpadUpdated |
| SubAgentEvents.kt | 3 data classes | SubAgent start/activity/complete |
| ThoughtEvents.kt | 1 data class | ThoughtUpdate |
| StatusEvents.kt | 1 data class | StatusUpdate |
| SessionConfig.kt | data class + 4 enums | Config + AgentMode/LLMBackendType/PlatformMode/ApprovalMode |
| SessionState.kt | sealed interface | 5 states: Created/Running/Idle/Paused/Shutdown |
| TurnPhase.kt | enum | PERCEPTION/PLANNING/EXECUTION |
| AgentError.kt | sealed class | 11 error variants + companion factory |
| ApprovalTypes.kt | 2 enums + 1 data class | ApprovalScope/ApprovalDecision/ApprovalDetails |
| AskUserType.kt | enum | QUESTION/ACTION |
| AppTier.kt | enum | BLOCKED/CAUTIOUS/NORMAL |
| CompletionReason.kt | enum | 7 reasons |
| ScreenStatePhase.kt | enum | PRE_TURN/POST_ACTION |
| SessionId.kt | value class | UUID wrapper |
| TodoModels.kt | data class + enum | Todo/TodoStatus |
| TextUtils.kt | function | sanitizeThought() |

**Totals: 27 files, 25 concrete event classes, 12 domain markers, 11 error variants, 8 ops, 7 enums, 5 states.**

---

## 2. Perspective A: Design Completeness and Correctness

### 2A-1. Op (Submission Queue) -- Correct and Complete

All 8 operations map to real user intents observed in consumers:

| Op | Consumer (AgentSession) | Valid States |
|---|---|---|
| Takeover | handleTakeover() | Running |
| Resume | handleResume() | Paused |
| Interrupt | handleInterrupt() | Running |
| Shutdown | handleShutdown() | Any |
| UserInput | handleUserInput() | Created, Idle |
| Supplement | handleSupplement() | Running, Paused |
| UserResponse | handleUserResponse() | Running, Paused (pending ask_user) |
| Approve | handleApproval() | Running (pending approval) |

No missing ops. No redundant ops. The `when` in `AgentSession.submit()` is exhaustive without `else`.

**Verdict: Clean.**

### 2A-2. Event Hierarchy -- Structurally Sound, Some Dead Weight

The `AgentEvent` base with `sessionId` + `timestamp` is correct. Every event needs both fields. The sealed interface approach allows `when` exhaustiveness checks.

**Domain marker interfaces (12):**
These are declared in `AgentEventDomains.kt` and used ONLY as supertypes in the event data classes. No consumer ever dispatches on `is SessionLifecycleEvent` or `is ActionDomainEvent`. All `when` blocks match on concrete types (e.g., `is TaskStarted`, `is ActionExecuted`).

The markers serve as **documentation** ("this event belongs to the action domain") but provide zero runtime value. The Kotlin type system already enforces the hierarchy via the `sealed interface AgentEvent` root.

**Event completeness:**
- `SessionError` is declared but **never emitted**. The session reports errors via `StatusUpdate` with emoji strings or via `SessionCompleted(reason=ERROR)`. The `AgentError` sealed class it wraps is also never constructed at emission sites.
- `TodosUpdated` and `ScratchpadUpdated` are emitted by `AgentEventDispatcher` but **never consumed** by any `when` branch. They flow into the `SharedFlow` and hit `else -> Unit` everywhere.

### 2A-3. SessionConfig -- Appropriate Granularity

The 12 fields (now 14 including `SessionLlmConfig` sub-fields) each have distinct consumers:

| Field | Consumer |
|---|---|
| maxTurns | Agent turn loop |
| actionDelayMs | TurnExecutionPhaseRunner |
| approvalMode | PolicyEngine, ToolRouter |
| agentMode | AgentDefRegistry, SessionLlmBootstrapper |
| llm | SessionServices, SessionLlmBootstrapper |
| debugMode | Trace/logging paths |
| traceEnabled | TraceRecorderFactory |
| traceRunId | TraceRecorderFactory, ScreenCaptured event |
| perceptionConfig | AgentTurnRunner |
| mainModel | SessionLlmBootstrapper, SessionRecordingService |
| executorModel | SessionLlmBootstrapper |
| platformMode | PlatformFactory, overlay UI |
| excludedTools | ToolRouter |

All fields are used. None are redundant. The `SessionLlmConfig` sub-grouping is justified: it bundles backend type + local config for routing decisions.

**Verdict: Right granularity.**

### 2A-4. SessionState -- Correct State Machine

The 5 states and their transitions match the diagram in `SessionState.kt`:

```
Created --UserInput--> Running --Pause--> Paused
                         |                  |
                         | <--Resume--------+
                         |
                         +--TaskCompleted--> Idle --UserInput--> Running
                                              |
                                              +--IdleTimeout--> Shutdown
```

`AgentSession` enforces these transitions with guard checks (e.g., `_state.value != SessionState.Running`). `SessionCoordinator` also respects the state machine for drain decisions.

Missing: No `WaitingForApproval` or `WaitingForUser` session state. These are handled at the capsule/UI layer (`CapsuleMode`), not session state. This is the correct boundary -- the session remains `Running` while waiting, since the agent loop is the one blocked.

**Verdict: Correct and minimal.**

### 2A-5. AgentError -- Over-Engineered, Under-Used

11 error variants are defined. Actual usage:
- `AgentError` appears in `SessionError.error` field (which is never emitted).
- `AgentError.from()` companion factory is **never called**.
- No code ever constructs `InvalidStateError`, `SessionClosedError`, `ApprovalDeniedError`, `PolicyDeniedError`, `ValidationError`, `UnknownToolError`, `PermissionError`, `PlatformError`, `LLMParseError`, `LLMError`, or `UnexpectedError` directly.
- The `isRecoverable` flag is never read by any consumer.

The entire `AgentError` hierarchy is **dead code**. Error handling in practice uses: (a) `StatusUpdate` with error strings, (b) `CompletionReason.ERROR`, (c) `AgentStopReason.Error(message)` in the agent layer.

### 2A-6. Serialization Consistency

Only `ScreenStatePhase` has `@Serializable` (kotlinx.serialization). All other types rely on Kotlin data class equality/copy. `ApprovalDetails.args` uses `org.json.JSONObject` (not serializable by default). This is an inconsistency but not a bug -- events flow in-process via `SharedFlow` and don't cross serialization boundaries.

`SessionCheckpointCoordinator` manually serializes config fields for persistence, not using kotlinx.serialization on `SessionConfig` itself. This is acceptable for the current in-process design.

### 2A-7. Sealed Class vs. Interface Usage

- `AgentEvent`, `Op`, `SessionState`: sealed **interfaces** -- correct (no shared state needed).
- `AgentError`: sealed **class** with `abstract val message` and `abstract val isRecoverable` -- correct pattern for shared abstract state, but the type is unused.
- Domain markers: sealed **interfaces** -- structurally fine, but unused for dispatch.

---

## 3. Perspective B: Simplicity (KISS)

### 3B-1. Domain Marker Interfaces: 12 Unused Abstractions

The 12 interfaces in `AgentEventDomains.kt` add a layer of hierarchy that no consumer uses. All event dispatching (in `AgentServiceEventHandler`, `ChatEventReducer`, `CapsuleStateHolder`) matches on **concrete event types**. The markers are organizational scaffolding that could be replaced by comments or file grouping.

**Cost:** 12 extra types in the type system, an extra file, and indirection for anyone reading event definitions ("What is `ActionDomainEvent`?" -> "Just a marker, look at the concrete types").

### 3B-2. AgentError: 170 Lines of Dead Code

The entire `AgentError.kt` (11 variants, companion factory, abstract properties) is never instantiated, never dispatched on, and never influences control flow. The protocol designed a rich error type system that was never adopted by the implementation.

### 3B-3. SessionError Event: Declared, Never Emitted

`SessionError(error: AgentError)` is handled in consumers (`AgentServiceEventHandler`, `ChatEventReducer`) but never emitted by any producer. This is a protocol contract that exists only on the receiving side.

### 3B-4. TodosUpdated / ScratchpadUpdated: Emitted, Never Consumed

These events are emitted by `AgentEventDispatcher` but fall through to `else -> Unit` in all consumers. They add noise to the event stream with no observable effect.

### 3B-5. StatusUpdate.emoji: Unused Field

The `emoji` field on `StatusUpdate` is optional (`null` default). `AgentSession.emitStatus()` declares the parameter but every call site passes only the `status` string (with emoji baked into the string itself, e.g., `"⚠️ Agent is busy"`). The separate `emoji` field is dead.

### 3B-6. TextUtils.kt: Misplaced Utility

`sanitizeThought()` is a pure string function. Placing it in `protocol/` creates a dependency from UI code on the protocol package for a simple truncation utility. It belongs in a `util/` or `ui/common/` package.

### 3B-7. ApprovalDetails.args: JSONObject in Protocol

`ApprovalDetails` holds `args: JSONObject`. This leaks the `org.json` dependency into the protocol layer, which should ideally be pure Kotlin data types. A `Map<String, Any>` or a dedicated args type would be cleaner.

### 3B-8. File-Per-Event-Domain: Marginal Value at Current Scale

25 event classes across 10 files (one per domain) is reasonable organization. But some files contain a single data class (StreamingEvents.kt, AskUserEvents.kt, ThoughtEvents.kt, StatusEvents.kt, PerceptionEvents.kt). At this scale, consolidating single-event domains into their parent would be simpler.

### 3B-9. TurnPhase: 3 Values but Only 2 Original

TurnPhase originally had PLANNING and EXECUTION. PERCEPTION was added for the pre-turn screen capture phase. All three are actively used (TurnStarted defaults to PERCEPTION, AgentEventDispatcher emits PLANNING and EXECUTION transitions). This is justified.

---

## 4. Synthesis: What Matters

### High-Impact Simplification Opportunities

1. **Delete AgentError.kt entirely.** 170 lines of dead code. The codebase handles errors through simpler mechanisms (CompletionReason.ERROR, StatusUpdate, AgentStopReason.Error).

2. **Delete AgentEventDomains.kt.** 12 marker interfaces with zero dispatch usage. Events already have natural file grouping and the sealed `AgentEvent` root provides exhaustiveness.

3. **Delete SessionError from SessionLifecycleEvents.kt.** Never emitted. If error reporting is needed in the future, it can be re-added when there's a producer.

4. **Stop emitting TodosUpdated / ScratchpadUpdated, or add consumers.** These are either premature (events for future UI features) or forgotten. If no UI needs them, remove emission.

5. **Remove StatusUpdate.emoji field.** Emojis are embedded in status strings already. The separate field is never populated.

6. **Move sanitizeThought() to util/ or ui/common/.** Protocol should be pure domain types.

### Low-Impact / Defer

7. ApprovalDetails.args JSONObject -- works fine in-process, would matter if events were serialized across boundaries.

8. Single-event files -- marginal complexity, grouping is clear enough.

9. Serialization consistency -- not an issue for in-process events.

### Verdict

The protocol design is fundamentally sound. The Op/Event/State/Config structure is correct and well-used. The main issue is **accumulated dead weight**: types designed speculatively that were never adopted. Six concrete deletions would remove ~250 lines and 23 types with zero behavioral change.
