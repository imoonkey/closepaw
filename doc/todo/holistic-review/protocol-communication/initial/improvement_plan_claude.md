# Protocol Module Improvement Plan

Date: 2026-04-08
Based on: `protocol-communication/review.md`

---

## Priority 1: Delete Dead Code (zero behavioral change)

### P1-A. Delete AgentError.kt

**Rationale:** 170 lines, 11 variants, companion factory. Never instantiated, never dispatched on, `isRecoverable` never read. Error handling uses `CompletionReason.ERROR`, `AgentStopReason.Error(message: String)`, and `StatusUpdate`.

**Steps:**
1. Delete `protocol/AgentError.kt`
2. Remove `val error: AgentError` from `SessionError` data class (or delete `SessionError` entirely -- see P1-B)
3. Verify build: `./gradlew assembleDebug`

**Risk:** None. No code references any AgentError subtype.

**Lines removed:** ~170

---

### P1-B. Delete SessionError Event

**Rationale:** Declared in `SessionLifecycleEvents.kt`, handled in `AgentServiceEventHandler` and `ChatEventReducer`, but **never emitted** by any producer. Consumer handlers are dead branches.

**Steps:**
1. Remove `SessionError` data class from `SessionLifecycleEvents.kt`
2. Remove `is SessionError ->` branches from:
   - `AgentServiceEventHandler.handleEvent()`
   - `ChatEventReducer.handle()`
3. Remove `import ...SessionError` where orphaned
4. Verify build

**Risk:** None. The event never enters the SharedFlow.

**Lines removed:** ~15

---

### P1-C. Delete AgentEventDomains.kt (12 marker interfaces)

**Rationale:** 12 sealed interfaces (`SessionLifecycleEvent`, `TaskLifecycleEvent`, `ActionDomainEvent`, etc.) used only as supertypes. No consumer dispatches on `is SessionLifecycleEvent` -- all match concrete types. The markers provide zero runtime value.

**Steps:**
1. Delete `protocol/AgentEventDomains.kt`
2. Update all event data classes to extend `AgentEvent` directly:
   - `SessionLifecycleEvents.kt`: `SessionStarted`, `SessionCompleted`, `SessionTakeover`, `SessionResumed`, `SupplementReceived` -- change `: SessionLifecycleEvent` to `: AgentEvent`
   - `TaskLifecycleEvents.kt`: `TaskStarted`, `TaskCompleted` -- change `: TaskLifecycleEvent` to `: AgentEvent`
   - `TurnEvents.kt`: 3 events -- change `: TurnDomainEvent` to `: AgentEvent`
   - `StreamingEvents.kt`: `MessageDelta` -- change `: StreamingDomainEvent` to `: AgentEvent`
   - `ActionEvents.kt`: 2 events -- change `: ActionDomainEvent` to `: AgentEvent`
   - `ApprovalEvents.kt`: 2 events -- change `: ApprovalDomainEvent` to `: AgentEvent`
   - `AskUserEvents.kt`: `AskUser` -- change `: AskUserDomainEvent` to `: AgentEvent`
   - `PerceptionEvents.kt`: `ScreenCaptured` -- change `: PerceptionDomainEvent` to `: AgentEvent`
   - `PlanningStateEvents.kt`: 2 events -- change `: PlanningStateEvent` to `: AgentEvent`
   - `SubAgentEvents.kt`: 3 events -- change `: SubAgentDomainEvent` to `: AgentEvent`
   - `ThoughtEvents.kt`: `ThoughtUpdate` -- change `: ThoughtDomainEvent` to `: AgentEvent`
   - `StatusEvents.kt`: `StatusUpdate` -- change `: StatusDomainEvent` to `: AgentEvent`
3. Verify build

**Risk:** Low. If a future feature needs domain-level filtering (e.g., "give me all action events"), the interface can be re-introduced at that time with exactly the consumers that need it. YAGNI.

**Lines removed:** ~37 (file) + minor per-event simplification

---

### P1-D. Remove StatusUpdate.emoji Field

**Rationale:** The `emoji: String? = null` field is never populated with a non-null value. All status messages embed emojis directly in the `status` string (e.g., `"⚠️ Agent is busy"`). The consumer in `AgentServiceEventHandler` has dead logic: `if (event.emoji != null) { "${event.emoji} ${event.status}" }`.

**Steps:**
1. Remove `val emoji: String? = null` from `StatusUpdate` in `StatusEvents.kt`
2. Remove `emoji` parameter from `AgentSession.emitStatus()` and the `StatusUpdate` construction inside it
3. Simplify `AgentServiceEventHandler`: remove the `if (event.emoji != null)` branch, just use `event.status`
4. Verify build

**Lines removed:** ~10

---

## Priority 2: Resolve Emit-Without-Consume (design decision needed)

### P2-A. TodosUpdated / ScratchpadUpdated: Emit or Don't

**Rationale:** `AgentEventDispatcher` emits these events. No consumer handles them. They are presumably intended for a future "planning panel" UI.

**Options:**
- **Option A (Remove emission):** Delete `todosUpdated()` and `scratchpadUpdated()` from `AgentEventDispatcher`. Delete the event data classes from `PlanningStateEvents.kt`. Delete the file if empty. This is the KISS option -- re-add when needed.
- **Option B (Keep for observability):** If trace/debug tooling reads the event stream, these may have value. In that case, add a comment documenting their purpose.

**Recommendation:** Option A. The events can be trivially re-added when a consumer exists. Currently they add ~10 lines of emission code and ~15 lines of type definitions for zero effect.

---

## Priority 3: Improve Placement / Hygiene

### P3-A. Move sanitizeThought() Out of Protocol

**Rationale:** `protocol/TextUtils.kt` contains a pure string utility. It creates a dependency from `ui/overlay/CapsuleStateHolder` and `agent/TurnPlanningPhaseRunner` on the protocol package for non-protocol functionality.

**Steps:**
1. Move `sanitizeThought()` to `util/TextUtils.kt` or `ui/common/TextUtils.kt`
2. Update imports in `CapsuleStateHolder.kt`, `TurnPlanningPhaseRunner.kt`, and `CapsuleModeTest.kt`
3. Delete `protocol/TextUtils.kt`

**Risk:** None. Pure refactor.

---

### P3-B. Replace JSONObject in ApprovalDetails (Defer)

**Rationale:** `ApprovalDetails.args: JSONObject` couples the protocol layer to `org.json`. The args are displayed in the approval UI and logged. A `Map<String, Any>` would be more idiomatic.

**Recommendation:** Defer. The coupling is contained (one field, in-process), and changing it ripples into `ToolRouter` JSON handling. Not worth the churn today.

---

## Priority 4: Consolidate Single-Event Files (Optional)

### P4-A. Merge Trivial Event Files

Five files contain a single data class each:
- `StreamingEvents.kt` (MessageDelta)
- `AskUserEvents.kt` (AskUser)
- `ThoughtEvents.kt` (ThoughtUpdate)
- `StatusEvents.kt` (StatusUpdate)
- `PerceptionEvents.kt` (ScreenCaptured)

These could merge into a single `MiscEvents.kt` or into topically adjacent files (e.g., ThoughtUpdate into TurnEvents, StatusUpdate into SessionLifecycleEvents).

**Recommendation:** Skip. The current organization is clear enough, and merging adds no functional value. File count is acceptable.

---

## Summary

| ID | Action | Lines Removed | Types Removed | Risk |
|---|---|---|---|---|
| P1-A | Delete AgentError.kt | ~170 | 11 + 1 sealed class | None |
| P1-B | Delete SessionError | ~15 | 1 data class | None |
| P1-C | Delete AgentEventDomains.kt | ~37 | 12 sealed interfaces | Low |
| P1-D | Remove StatusUpdate.emoji | ~10 | 0 (field only) | None |
| P2-A | Remove TodosUpdated/ScratchpadUpdated | ~25 | 2 data classes + emission | None |
| P3-A | Move sanitizeThought() | 0 (move) | 0 | None |
| **Total** | | **~257** | **~27 types** | |

After all Priority 1+2 changes: **27 files -> 23 files, ~257 lines removed, 27 types eliminated.** Zero behavioral change. The remaining protocol module would be:

- **Op.kt** -- 8 ops (unchanged)
- **AgentEvent.kt** -- base event (unchanged)
- **7 event files** -- 20 concrete event classes extending AgentEvent directly
- **SessionConfig.kt** -- config + enums (unchanged)
- **SessionState.kt** -- 5 states (unchanged)
- **TurnPhase.kt** -- 3 phases (unchanged)
- **4 domain type files** -- ApprovalTypes, AskUserType, AppTier, CompletionReason, ScreenStatePhase, TodoModels (unchanged)
- **SessionId.kt** -- value class (unchanged)

Clean, minimal, every type earning its place.
