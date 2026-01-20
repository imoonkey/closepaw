# Session & Protocol - Consolidated Review Summary

> **Files**: `session/AgentSession.kt`, `session/SessionServices.kt`, `protocol/*.kt`
> **Reviewers**: Claude, Codex, Gemini

## High-Risk Issues (Must Fix)

### 1. Event Channel Closed Before Final Event Delivery
**Reviewer**: Claude
**Location**: `AgentSession.kt:318-319`

**Problem**: In `handleShutdown()`, `SessionCompleted` event is emitted then channel is immediately closed. With `Channel.BUFFERED`, close happens before collectors process the final event.

**Impact**: UI may never receive `SessionCompleted` event, leaving user confused.

**Fix**: Don't close eagerly - let channel close naturally when scope is cancelled, or add delay before close.

---

### 2. Double Completion Event on Normal Shutdown
**Reviewer**: Claude
**Location**: `AgentSession.kt:222-241` and `AgentSession.kt:307-316`

**Problem**: When agent completes normally via `handleAgentComplete()`, it emits `SessionCompleted`. If `handleShutdown()` is later called, it emits another `SessionCompleted`.

**Impact**: UI receives duplicate completion events, causing double cleanup.

**Fix**: Track if completion was already emitted with a `completionEmitted` flag.

---

### 3. Built-in Tools Registration Hardcoded
**Reviewer**: Gemini
**Location**: `SessionServices.kt:108-116`

**Problem**: `registerBuiltInTools` hardcodes the list of tools (ClickTool, TypeTool, etc.), violating Open-Closed Principle.

**Fix**: Use ServiceLoader pattern, reflection-based scanner, or central ToolConfig.

---

### 4. Interrupt Does Not Actually Cancel In-Flight Work
**Reviewer**: Codex
**Location**: `AgentSession.kt:280-288`

**Problem**: `Op.Interrupt` calls `agent?.stop()` but this only halts future turns. LLM calls and tool execution already in progress continue.

**Impact**: Interrupt is not truly immediate - documented as "stop after current action" behavior may be unexpected.

**Fix**: Plumb cancellation token into Turn/ToolRouter and cancel in-flight work, or document limitation clearly.

---

## Medium Issues (Should Fix)

### M1. Op.Start.config Ignored
**Consensus**: Codex, Claude
**Location**: `AgentSession.kt`

UI-provided session settings (model, approval mode, delays) in `op.config` are silently discarded.

**Fix**: Either apply config or remove from `Op.Start` to avoid misleading callers.

---

### M2. Session Event Flow Never Completes on Normal Finish
**Reviewer**: Codex
**Location**: `AgentSession.kt`

`events` collectors can leak across sessions if channel isn't closed after completion.

**Fix**: Close channel after emitting SessionCompleted or expose session-scope job.

---

### M3. Op.UserInput Not Implemented
**Consensus**: Codex, Gemini, Claude
**Location**: `AgentSession.kt:322-326`

`Op.UserInput` is accepted but does nothing - just logs warning. Protocol advertises user guidance support.

**Fix**: Implement (add to history and re-run turn) or explicitly mark unsupported in protocol docs.

---

### M4. Missing ApprovalResolved Events
**Reviewer**: Codex
**Location**: `AgentSession.kt`

Protocol defines `ApprovalResolved`, but session never emits it - UIs can't update approval state.

**Fix**: Emit `AgentEvent.ApprovalResolved` when approval decision is applied.

---

### M5. SessionState Has Unused States
**Reviewer**: Claude
**Location**: `SessionState.kt:31-33`

`SessionState.Cancelled` and `SessionState.Error` are defined but never transitioned to.

**Fix**: Use these states appropriately or remove to clean up API.

---

### M6. CancellationReason Never Used
**Reviewer**: Claude
**Location**: `SessionState.kt:44-53`

`CancellationReason` sealed interface defined but never instantiated.

**Fix**: Remove or integrate with completion events.

---

### M7. Race Condition in Shutdown
**Reviewer**: Gemini
**Location**: `AgentSession.kt:301`

If `handleShutdown` and `handleAgentComplete` are called concurrently (agent finishes exactly when user hits stop), there might be a race. Mitigation exists via state check but atomic transitions would be safer.

---

### M8. Agent Reference Not Nulled After Stop
**Reviewer**: Claude
**Location**: `AgentSession.kt:286-288`

In `handleInterrupt()`, agent is stopped but reference kept. Multiple calls to interrupt keep calling stop on stopped agent.

**Fix**: Null the reference or add stopped state check.

---

### M9. State Transition Missing for Interrupt
**Reviewer**: Claude
**Location**: `AgentSession.kt:280-288`

When `Op.Interrupt` is handled, agent is stopped but no state transition occurs and no event is emitted. State says "Running" but agent is actually stopped.

**Fix**: Emit an event or transition to appropriate state.

---

### M10. SessionServices.create() Not Actually Suspend
**Reviewer**: Claude
**Location**: `SessionServices.kt:65`

Marked `suspend` but performs no suspending operations.

**Fix**: Remove `suspend` modifier.

---

## Low-Risk Suggestions (Nice to Have)

| Issue | Reviewer | Location | Suggestion |
|-------|----------|----------|------------|
| Duplicate completion on shutdown | Codex | `AgentSession.kt` | Guard against emitting completion if already Completed |
| SessionId could include timestamp | Claude | `SessionId.kt:13` | Aids log correlation |
| CompletionReason.TIMEOUT unused | Claude | `AgentEvent.kt:230` | Remove or implement timeout |
| Event interface default timestamp | Claude | `AgentEvent.kt` | Add companion factory for now() |
| TurnPhase.REFLECTION never emitted | Claude | `AgentEvent.kt:207` | Remove if not used |
| ApprovalDetails.description default empty | Claude | `ApprovalTypes.kt:64` | Make required or add validation |
| SessionId generation | Gemini | `SessionId.kt` | Verify secure random/UUID usage |
| Event flow buffering | Gemini | `AgentSession.kt:105` | Consider DROP_OLDEST for burst scenarios |

---

## Open Questions

1. **Channel capacity**: `Channel.BUFFERED` defaults to 64. Is this sufficient for burst event scenarios?

2. **Multiple sessions**: Does the architecture support parallel sessions? Should it?

3. **Event ordering**: If collector is slow, could events be dropped with bounded channel?
