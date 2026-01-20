# Session & Protocol - Consolidated Review Summary

> **Files**: `session/AgentSession.kt`, `session/SessionServices.kt`, `protocol/*.kt`
> **Reviewers**: Claude, Codex, Gemini

**Team Note**: currently frontend only support pause/resume and stop, two interactions. While in the future we would support more (e.g., make this more conversational, user can add comments after pause, or add a follow-up task in the same session after task stopped/completed). We should be clear on what is currently implemented and what will future be planned to support soon (which we will keep the interface, but document/mark clearly) and what will not be supported soon (which we may be better off to keep it in the doc and remove it from the code to avoid confusion). source-of-truth doc is doc/main/, especially agent_protocol.md. Think hard on this, many of the issues below are symptoms of this, you should treat the root, and think from first principles first.

---

## Root Cause Analysis

**The core problem**: Protocol code defines aspirational features that aren't implemented, creating confusion between what the API promises and what actually works.

### What's Actually Implemented (Frontend Uses)
| Operation | Effect | Works? |
|-----------|--------|--------|
| `Op.Start(goal)` | Start agent with a goal | ✅ Yes |
| `Op.Pause` | Cooperative pause | ✅ Yes |
| `Op.Resume` | Resume from pause | ✅ Yes |
| `Op.Shutdown` | Stop session | ✅ Yes (has bugs) |
| `Op.Approve` | Respond to approval request | ✅ Yes |

### What's Defined But NOT Implemented (Causes Confusion)
| Item | Location | Status | Action |
|------|----------|--------|--------|
| `Op.Start.config` | `Op.kt:29` | Ignored | **REMOVE** from Op.Start |
| `Op.UserInput` | `Op.kt:73-75` | Logs warning only | **KEEP** but mark `@Planned` |
| `SessionState.Cancelled` | `SessionState.kt:32` | Never used | **REMOVE** |
| `SessionState.Error` | `SessionState.kt:38` | Never used | **USE IT** or remove |
| `CancellationReason` | `SessionState.kt:44-53` | Never used | **REMOVE** entirely |
| `CompletionReason.TIMEOUT` | `AgentEvent.kt:230` | No timeout impl | **REMOVE** (no plans) |
| `CompletionReason.TASK_IMPOSSIBLE` | `AgentEvent.kt:233` | Never used | **KEEP** (agent could use) |
| `TurnPhase.REFLECTION` | `AgentEvent.kt:207` | Never emitted | **KEEP** (planned feature) |
| `ApprovalResolved` event | `AgentEvent.kt:176-181` | Never emitted | **FIX** - emit it |

### Recommended Cleanup Strategy

1. **Remove dead code** that will NOT be implemented soon:
   - `Op.Start.config` → remove parameter, config is set at session creation
   - `SessionState.Cancelled` → redundant with `Shutdown` + `USER_STOPPED`
   - `CancellationReason` → redundant with `CompletionReason`
   - `CompletionReason.TIMEOUT` → no timeout feature planned

2. **Mark planned features clearly** in code and docs:
   - `Op.UserInput` → add `// TODO: Planned for conversational mode`
   - `TurnPhase.REFLECTION` → add `// TODO: Planned for action verification`

3. **Fix actual bugs** in implemented features:
   - Channel closing before final event (Issue 1)
   - Double completion event (Issue 2)
   - Missing ApprovalResolved emission (M4)

4. **Update `doc/main/agent_protocol.md`** to be source of truth:
   - Clearly mark what's implemented vs. planned
   - Remove references to unimplemented features

---

## High-Risk Issues (Must Fix)

### 1. Event Channel Closed Before Final Event Delivery
**Reviewer**: Claude
**Location**: `AgentSession.kt:320-321`

**Problem**: In `handleShutdown()`, `SessionCompleted` event is emitted then channel is immediately closed. With `Channel.BUFFERED`, close happens before collectors process the final event.

**Impact**: UI may never receive `SessionCompleted` event, leaving user confused.

**Fix**: Don't close eagerly - let channel close naturally when scope is cancelled, or add delay before close.

**Team Note**: Fix it. Add a small delay (e.g., 100ms) before closing the channel, or better yet, don't close the channel explicitly - let it close naturally when the session scope is cancelled.


---

### 2. Double Completion Event on Normal Shutdown
**Reviewer**: Claude
**Location**: `AgentSession.kt:224-244` and `AgentSession.kt:309-318`

**Problem**: When agent completes normally via `handleAgentComplete()`, it emits `SessionCompleted`. If `handleShutdown()` is later called, it emits another `SessionCompleted`.

**Impact**: UI receives duplicate completion events, causing double cleanup.

**Fix**: Track if completion was already emitted with a `completionEmitted` flag.

**Team Note**: NOT FIXED. Currently `handleAgentComplete` checks for `Shutdown` state, but `handleShutdown` doesn't check for `Completed` state. Fix it by adding a `completionEmitted` flag to guard against double emission.

---

### 3. Built-in Tools Registration Hardcoded
**Reviewer**: Gemini
**Location**: `SessionServices.kt:120-134`

**Problem**: `registerBuiltInTools` hardcodes the list of tools (ClickTool, TypeTool, etc.), violating Open-Closed Principle.

**Fix**: Use ServiceLoader pattern, reflection-based scanner, or central ToolConfig.

**Team Note**: Keep current approach for now. The hardcoded list is acceptable for the current scope of ~8 built-in tools. Add a TODO comment for future refactoring to ServiceLoader pattern when we need plugin tools.

---

### 4. Interrupt Does Not Actually Cancel In-Flight Work
**Reviewer**: Codex
**Location**: `AgentSession.kt:282-290`

**Problem**: `Op.Interrupt` calls `agent?.stop()` but this only halts future turns. LLM calls and tool execution already in progress continue.

**Impact**: Interrupt is not truly immediate - documented as "stop after current action" behavior may be unexpected.

**Fix**: Plumb cancellation token into Turn/ToolRouter and cancel in-flight work, or document limitation clearly.

**Team Note**: Document limitation clearly. Add a comment in `Op.Interrupt` and `agent_protocol.md` stating that interrupt is cooperative and will complete after the current action finishes. True cancellation of in-flight LLM calls is complex and deferred.

---

## Medium Issues (Should Fix)

### M1. Op.Start.config Ignored
**Consensus**: Codex, Claude
**Location**: `AgentSession.kt:153-179`

UI-provided session settings (model, approval mode, delays) in `op.config` are silently discarded. The session is created via `AgentSession.create()` with a separate config, and `handleStart()` ignores `op.config`.

**Fix**: Either apply config or remove from `Op.Start` to avoid misleading callers.

**Team Note**: SYMPTOM OF ROOT CAUSE - aspirational code. **REMOVE** `config` from `Op.Start`. The correct design is:
1. Session config is set at `AgentSession.create()` time (before session starts)
2. `Op.Start` only provides the `goal` string
3. This matches how the code actually works - make the API honest

---

### M2. Session Event Flow Never Completes on Normal Finish
**Reviewer**: Codex
**Location**: `AgentSession.kt`

`events` collectors can leak across sessions if channel isn't closed after completion.

**Fix**: Close channel after emitting SessionCompleted or expose session-scope job.

**Team Note**: NOT FIXED. Related to Issue 1. The channel is only closed in `handleShutdown()`. Fix by also closing the channel after `handleAgentComplete()` emits `SessionCompleted` (with the same delay as recommended in Issue 1).

---

### M3. Op.UserInput Not Implemented
**Consensus**: Codex, Gemini, Claude
**Location**: `AgentSession.kt:324-328`

`Op.UserInput` is accepted but does nothing - just logs warning. Protocol advertises user guidance support.

**Fix**: Implement (add to history and re-run turn) or explicitly mark unsupported in protocol docs.

**Team Note**: SYMPTOM OF ROOT CAUSE. This is a planned feature for conversational mode. Keep the interface but:
1. Add `// TODO: Planned for conversational mode - not yet implemented` in `Op.UserInput`
2. Update `agent_protocol.md` to clearly mark as "Planned" (not just a note)
3. Change log from warning to info: "UserInput received but conversational mode not yet implemented"

---

### M4. Missing ApprovalResolved Events
**Reviewer**: Codex
**Location**: `AgentSession.kt:330-333`

Protocol defines `ApprovalResolved`, but session never emits it - UIs can't update approval state.

**Fix**: Emit `AgentEvent.ApprovalResolved` when approval decision is applied.

**Team Note**: NOT FIXED. `handleApproval()` calls `toolRouter.resolveApproval()` but doesn't emit the event. Fix by adding `emit(AgentEvent.ApprovalResolved(...))` after the `toolRouter.resolveApproval()` call.

---

### M5. SessionState Has Unused States
**Reviewer**: Claude
**Location**: `SessionState.kt:32,38`

`SessionState.Cancelled` and `SessionState.Error` are defined but never transitioned to in `AgentSession.kt`.

**Fix**: Use these states appropriately or remove to clean up API.

**Team Note**: SYMPTOM OF ROOT CAUSE - aspirational code. 
- **REMOVE** `SessionState.Cancelled` - redundant with `Shutdown` + `CompletionReason.USER_STOPPED`
- **REMOVE** `SessionState.Error` - current design uses `Completed` + `CompletionReason.ERROR` which is cleaner (one terminal state with reason, not multiple terminal states)

---

### M6. CancellationReason Never Used
**Reviewer**: Claude
**Location**: `SessionState.kt:44-53`

`CancellationReason` sealed interface defined but never instantiated.

**Fix**: Remove or integrate with completion events.

**Team Note**: SYMPTOM OF ROOT CAUSE - aspirational code. **REMOVE** `CancellationReason` entirely. `CompletionReason` already covers all cases. This was likely designed for a more complex cancellation model that we don't need.

---

### M7. Race Condition in Shutdown
**Reviewer**: Gemini
**Location**: `AgentSession.kt:225-227, 296`

If `handleShutdown` and `handleAgentComplete` are called concurrently (agent finishes exactly when user hits stop), there might be a race. Mitigation exists via state check but atomic transitions would be safer.

**Team Note**: PARTIALLY MITIGATED. `handleAgentComplete` checks for `Shutdown` state (line 225-227) before proceeding. Current mitigation is acceptable for now. Full atomic state machine is overkill for this use case - just ensure the double-completion fix (Issue 2) uses a thread-safe flag (e.g., `AtomicBoolean`).

---

### M8. Agent Reference Not Nulled After Stop
**Reviewer**: Claude
**Location**: `AgentSession.kt:282-290`

In `handleInterrupt()`, agent is stopped but reference kept. Multiple calls to interrupt keep calling stop on stopped agent.

**Fix**: Null the reference or add stopped state check.

**Team Note**: NOT FIXED. Don't null the reference (may cause issues if agent is still processing). Instead, add a check in `agent?.stop()` to be idempotent - if already stopped, do nothing. The `Agent.stop()` method should be safe to call multiple times.

---

### M9. State Transition Missing for Interrupt
**Reviewer**: Claude
**Location**: `AgentSession.kt:282-290`

When `Op.Interrupt` is handled, agent is stopped but no state transition occurs and no event is emitted. State says "Running" but agent is actually stopped.

**Fix**: Emit an event or transition to appropriate state.

**Team Note**: This is expected behavior per the current design. `Op.Interrupt` is cooperative - it signals the agent to stop after current action, but the agent loop handles actual completion via `handleAgentComplete()`. No immediate state change is needed. Add a comment in `handleInterrupt()` explaining this: "// Interrupt is cooperative - agent will complete via handleAgentComplete()".

---

### M10. SessionServices.create() Not Actually Suspend
**Reviewer**: Claude
**Location**: `SessionServices.kt:71`

Marked `suspend` but performs no suspending operations.

**Fix**: Remove `suspend` modifier.

**Team Note**: NOT FIXED. Remove the `suspend` modifier from `SessionServices.create()`. It performs no suspending operations.

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
| Event flow buffering | Gemini | `AgentSession.kt:107` | Consider DROP_OLDEST for burst scenarios |

**Team Note**: Apply root cause thinking here too:
- `CompletionReason.TIMEOUT` → **REMOVE** (no timeout feature planned, adds confusion)
- `TurnPhase.REFLECTION` → **KEEP** with `// TODO: Planned for action verification` comment
- First item (duplicate completion) → addressed by Issue 2 fix
- Rest are fine as-is

---

## Open Questions

1. **Channel capacity**: `Channel.BUFFERED` defaults to 64. Is this sufficient for burst event scenarios?

2. **Multiple sessions**: Does the architecture support parallel sessions? Should it?

3. **Event ordering**: If collector is slow, could events be dropped with bounded channel?

**Team Note**: 
1. 64 is sufficient for current use. Events are processed quickly by UI.
2. Current design is single-session per `AgentService`. Parallel sessions are out of scope.
3. With `BUFFERED`, events suspend sender if buffer full (not dropped). This is acceptable - slow UI will back-pressure the agent.
