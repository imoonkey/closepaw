# Protocol & Communication — Final Improvement Plan

Date: 2026-04-08
Basis: Codex design (structural base) + Claude tactical items (concrete deletions)
Status: Aligned — both reviewers APPROVE

---

## Goals

- Make the protocol say only what the runtime actually guarantees.
- Keep the closed command/state sets that already work.
- Delete speculative surface before introducing new abstraction.
- Separate durable domain facts from UI projection.

## Guiding Position

Move toward a smaller, sharper contract. No richer inheritance, no generic envelope, no backward-compat hacks.

---

## Phase 1: Prune Event Surface (zero behavioral change)

### 1A. Delete AgentError.kt

~170 lines, 11 variants + companion factory. Never instantiated anywhere.

**Steps:**
1. Delete `protocol/AgentError.kt`
2. Remove `val error: AgentError` from `SessionError` (or delete `SessionError` entirely — see 1B)
3. `./gradlew assembleDebug`

### 1B. Delete SessionError

Declared in `SessionLifecycleEvents.kt`, handled in consumers, never emitted.

**Steps:**
1. Remove `SessionError` data class from `SessionLifecycleEvents.kt`
2. Remove `is SessionError ->` branches from `AgentServiceEventHandler` and `ChatEventReducer`
3. Clean orphaned imports
4. Build verify

### 1C. Collapse Marker-Interface Taxonomy

Delete `AgentEventDomains.kt`. Update all 25 event data classes to extend `AgentEvent` directly. Let file grouping carry the domain organization.

### 1D. Remove Dead/Redundant Event Elements

- Remove `StatusUpdate.emoji` field and consumer branching in `AgentServiceEventHandler`
- Stop emitting `TodosUpdated` and `ScratchpadUpdated` (or delete the event classes entirely)
- Stop emitting `ApprovalResolved` (UI resolution is local)
- Remove `ApprovalRequired.actionId` (duplicates `ApprovalDetails.callId`)
- Remove `TurnStarted.phase` field OR stop emitting initial `TurnPhaseChanged(PERCEPTION)` — pick one

### 1E. Move sanitizeThought()

Move `protocol/TextUtils.kt` → `util/TextUtils.kt`. Update imports in `CapsuleStateHolder.kt`, `TurnPlanningPhaseRunner.kt`, `CapsuleModeTest.kt`.

**Target: ~257 lines removed, ~27 types eliminated. Single commit.**

---

## Phase 2: Repair Lifecycle Semantics

### 2A. Split CompletionReason

Replace `CompletionReason` with two enums:

```kotlin
enum class TaskOutcome {
    GOAL_ACHIEVED, MAX_TURNS, TASK_IMPOSSIBLE, ERROR, USER_STOPPED
}

enum class SessionEndReason {
    USER_STOPPED, IDLE_TIMEOUT, INTERRUPTED
}
```

Update:
- `TaskCompleted(..., outcome: TaskOutcome)`
- `SessionCompleted(..., reason: SessionEndReason)`

This eliminates impossible states from consumers.

### 2B. Decide on Hot-Idle Transition Event

If UI/recording needs to know when a session becomes reusable after a task, add a dedicated idle-transition event. Otherwise keep hot idle internal and stop overloading `SessionCompleted`.

---

## Phase 3: Unify Interaction and Approval Naming

### 3A. Canonical ID Noun

Pick `callId` (or `toolCallId`). Rename `Op.Approve.actionId` → `callId`. Ensure consistency across `Op.Approve`, `ApprovalRequired`, `ApprovalDetails`, and `ToolRouter.resolveApproval()`.

### 3B. Rename Op.Approve

```kotlin
data class ResolveApproval(
    val callId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val packageName: String? = null
) : Op
```

### 3C. Immutable Approval Payload

Replace `ApprovalDetails.args: JSONObject` with `JsonObject` (kotlinx.serialization) or `Map<String, Any>` if the UI reads it. If UI never uses args, remove the field.

---

## Phase 4: Reshape SessionConfig

### 4A. Split by Ownership

```kotlin
data class ExecutionConfig(
    val maxTurns: Int,
    val actionDelayMs: Long,
    val approvalMode: ApprovalMode,
    val agentMode: AgentMode,
    val platformMode: PlatformMode,
    val perceptionConfig: PerceptionConfig
)

data class ModelConfig(
    val mainModel: String,
    val executorModel: String?,
    // backend + local settings — make invalid states unrepresentable
)

data class ObservabilityConfig(
    val debugMode: Boolean,
    val traceEnabled: Boolean,
    val traceRunId: String?
)

// EvalConfig only if excludedTools belongs in production startup
```

### 4B. Fix Persistence

Persist and restore every field that materially affects runtime behavior. If some fields are intentionally non-persisted, document that explicitly and enforce it at the type level (separate persisted vs transient configs).

### 4C. Make Invalid LLM States Unrepresentable

Replace `SessionLlmConfig(backendType, localConfig?)` with a sealed type or validated builder that cannot produce contradictory combinations.

---

## Phase 5: Clarify protocol/ Boundary

### Option B (adopted): protocol/ = domain contract

Move out of `protocol/`:
- `StatusUpdate` → `ui/events/` or `session/events/`
- `ThoughtUpdate` → same
- Any emoji/display formatting helpers

Keep in `protocol/`:
- `Op`, `SessionState`, `AgentEvent`, lifecycle events, action events, approval events, perception events
- Config types, enums, value classes

---

## Execution Order

| Step | Phase | Risk | Dependency |
|------|-------|------|------------|
| 1 | Phase 1 (prune) | None | None — pure deletion |
| 2 | Phase 2 (lifecycle) | Medium | Touches consumers |
| 3 | Phase 3 (naming) | Low | Phase 2 stable |
| 4 | Phase 4 (config) | Medium | Phase 2 stable |
| 5 | Phase 5 (boundary) | Low | All prior phases |

Phase 1 can land immediately as a single commit. Phases 2-5 should each be separate PRs with build verification.

---

## Non-Goals

- Do not add a richer event inheritance tree
- Do not add a generic message envelope
- Do not keep backward-compat hacks
- Do not consolidate single-event files (acceptable at current scale)

---

## Summary

| Phase | Lines Removed/Changed | Types Affected | Risk |
|-------|----------------------|----------------|------|
| 1. Prune | ~257 removed | ~27 eliminated | None |
| 2. Lifecycle | ~50 changed | 1 split → 2 | Medium |
| 3. Naming | ~30 changed | 2-3 renamed | Low |
| 4. Config | ~100 restructured | 1 split → 3-4 | Medium |
| 5. Boundary | ~40 moved | 2-3 relocated | Low |

After all phases: a smaller, sharper protocol module where every type earns its place and the contract matches what the runtime actually guarantees.
