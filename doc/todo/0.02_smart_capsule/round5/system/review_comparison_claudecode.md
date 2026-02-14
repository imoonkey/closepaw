# Round 5 Review Comparison: Claude Code vs Codex

> Date: 2026-02-14
> Compares: `review_design_claudecode.md` (Claude Code / Opus 4) vs `review_design_codex.md` (Codex)

---

## 1. Consensus (Both Agree)

These findings appear in both reviews with the same diagnosis and same proposed fix direction.

### 1.1 StatusIsland Observer Auto-Show Is the Root Cause of Mutual Exclusivity Bugs

| | Claude Code | Codex |
|---|---|---|
| Finding | Problem C: `startObserving()` calls `show()` on `mode != Hidden`, creating two owners for visibility | Critical #2: island observer ignores context and user toggle, "self-resurrects" on every mode change |
| Bugs | 2.5, 5.1 | 2.4, 2.5, 5.1, 5.4 |
| Fix | Observer only updates display; controller owns visibility via `applyVisibility()` | Observer only receives Presentation instructions; controller owns `derive + apply` |

**Verdict: Full consensus.** Both identify the same code path (StatusIslandManager:86-99) and propose the same fix pattern (strip visibility logic from observer, centralize in controller).

### 1.2 VD Context Model Missing MAIN_APP

| | Claude Code | Codex |
|---|---|---|
| Finding | Fix F3: `applyVisibility()` hides overlays when `isAppInForeground` | Critical #3: VD branch ignores `TYPE_WINDOW_STATE_CHANGED`, no `MAIN_APP` surface tracking |
| Bugs | 1.4, 3.2, 3.2.1 | 1.4, 3.2 |
| Fix | `applyVisibility()` checks `isAppInForeground`; already has the variable, just not used in VD path | Introduce `UserSurface.MAIN_APP` tracking for VD; handle window state changes in VD branch |

**Verdict: Consensus on the problem. Slight difference in mechanism.** Claude Code reuses the existing `isAppInForeground` boolean. Codex proposes a new `UserSurface` enum. Functionally equivalent; Codex's `UserSurface` is slightly cleaner conceptually (explicit enum vs boolean), Claude Code's approach requires fewer new types.

### 1.3 Delete `performHandoff()`

| | Claude Code | Codex |
|---|---|---|
| Finding | Problem D / Fix F6 | Critical #4 |
| Bugs | 5.3, 5.4 | 5.3 |

**Verdict: Full consensus.** Both say delete it, no-op on completion.

### 1.4 NavSpec Bugs (Minimize in MAIN_APP, Broken Wiring)

| | Claude Code | Codex |
|---|---|---|
| Finding | Fix F3 (NavSpec), Fix F7 (wiring investigation) | High #1: NavSpec exposes wrong buttons in MAIN_APP |
| Bugs | 3.2.1, 5.2 | 3.2.1, 5.2 |

**Verdict: Consensus.** Both flag the same NavSpec logic gap. Claude Code provides the specific code fix (`context != CapsuleContext.MAIN_APP` guard). Codex provides the ground truth nav policy (Section 2.6).

### 1.5 A11y Input Focus Conflict

| | Claude Code | Codex |
|---|---|---|
| Finding | Fix F5: disable input in Running/TakeoverPending for A11y overlay | High #2 + Section 2.5: Input/Focus Policy |
| Bugs | 2.6, 2.7 | 2.6, 2.7 |
| Fix | Manager disables EditText focusability per mode+platform | Same policy: Running/TakeoverPending = Row3 read-only, Takeover/WaitingForInput = allow input |

**Verdict: Full consensus.** Same policy, same modes, same implementation approach.

### 1.6 Pending Command Feedback (Takeover Click)

| | Claude Code | Codex |
|---|---|---|
| Finding | Fix F8: TakeoverPending is never entered; call `onTakeoverRequested()` on click | High #3: no pending feedback |
| Bugs | 1.3, 2.1 | 1.3, 2.1, 3.1 |

**Verdict: Consensus on the problem. Conflict on the solution.** See Section 2.1 below.

### 1.7 Chat/History Gaps (Supplement, Completion Message)

| | Claude Code | Codex |
|---|---|---|
| Finding | Phase 4 items | High #4: supplement not in recording/chat; completion summary not merged into agent message |
| Bugs | 3.4, 3.5 | 3.4, 3.5 |

**Verdict: Consensus.** Both treat these as lower priority. Codex provides slightly more evidence (specific line numbers in AgentSession and ChatViewModel).

### 1.8 Centralized Visibility Function

| | Claude Code | Codex |
|---|---|---|
| Finding | `applyVisibility()` with truth table | `derivePresentation(state)` + `applyPresentation()` |

**Verdict: Consensus on pattern.** Both propose a single deterministic function. Codex names it more formally (derive + apply); Claude Code inlines it as one function. Same idea.

### 1.9 ShowPreference / PanelMode

| | Claude Code | Codex |
|---|---|---|
| Finding | `ShowPreference { CAPSULE, ISLAND }` | `PanelMode { CAPSULE, ISLAND }` |

**Verdict: Same concept, different names.** Functionally identical.

---

## 2. Conflicts (Different Conclusions on Same Issue)

### 2.1 Pending Feedback: Optimistic State Transition vs Dedicated PendingCommand

| Claude Code | Codex |
|---|---|
| **Optimistic**: Call `stateHolder.onTakeoverRequested()` on click to enter `TakeoverPending` immediately. If session rejects, the next event naturally exits the state. | **Ephemeral**: Introduce a separate `PendingCommand` sealed interface (Stop/Takeover/Resume/UserResponse). Click sets `pendingCommand`, ack event clears it. Task state machine is NEVER driven by clicks. |
| Pros: Simpler, no new types. `TakeoverPending` state already exists and does exactly this. | Pros: Stricter separation of "truth" (session events) from "UX feedback" (local pending). No risk of desync. |
| Cons: If session rejects takeover, the UI is in `TakeoverPending` until the next event arrives (could be seconds). | Cons: New type (`PendingCommand`), new state flow, both renderers must read it. More wiring. |

**My assessment**: Claude Code's approach is simpler and works for takeover (the guard in `onThoughtUpdate` will just keep the mode as TakeoverPending until confirmed, which IS the correct UX). However, Codex's approach is more principled and handles edge cases better (e.g., what if the session emits a `ThoughtUpdate` after `Op.Takeover` but before `SessionTakeover`? Claude Code's guard on `onThoughtUpdate` silently ignores it, which is correct but might confuse debugging).

**Recommendation**: Use Claude Code's approach (optimistic `onTakeoverRequested()`) for now. Add `PendingCommand` later if desync issues emerge.

### 2.2 WaitingForInput/WaitingForAction Ack Event

| Claude Code | Codex |
|---|---|
| No new ack event needed. `onUserResponseSent()` is called from the UI side after submitting `Op.UserResponse`. This is already implemented. The state transitions to `Running("Processing response...")`. | **Critical #1**: `onUserResponseSent()` is never called because no event triggers it. Proposes new `AgentEvent.UserResponseAccepted(callId)` / `UserResponseRejected(callId, reason)` events. |

**Analysis**: Let me trace the actual call chain:
- User clicks Send in capsule overlay -> `SmartCapsuleManager.handleRow3Submit()` -> `onUserResponse?.invoke(callId, text)` -> `ServiceOverlayController.onUserResponse` callback -> `AgentService` submits `Op.UserResponse(callId, response)` -> `AgentSession.handleUserResponse()` -> delivers to `UserResponseChannel`.
- Nobody calls `stateHolder.onUserResponseSent()` in this chain.

Codex is correct: **there is no code path that calls `stateHolder.onUserResponseSent()`**. The state stays in `WaitingForInput` after the user submits. It only exits when the next `ThoughtUpdate` arrives (if `onThoughtUpdate()` guard allows it — but the guard says `if (_mode.value !is CapsuleMode.Running) return`, so it REJECTS the thought update because mode is still `WaitingForInput`).

Wait, actually `onTaskStarted()` is universal and would transition out. And `onAskUser()` is universal. But `onThoughtUpdate()` is Running-only. So after submitting a response, the state is stuck in `WaitingForInput` until:
- A new `TaskStarted` (unlikely mid-task)
- Another `AskUser` (possible but coincidental)
- A `TaskCompleted`
- A `SessionError`

The `ThoughtUpdate` that the agent emits when it resumes processing would be IGNORED because the guard rejects it.

**This is a real bug that Claude Code missed.** The fix is either:
- (a) Codex's approach: add ack events from the session layer
- (b) Simpler KISS approach: call `stateHolder.onUserResponseSent()` in the controller's `onUserResponse` callback (optimistic, same as takeover)

**Recommendation**: Use the simpler approach (b) — call `onUserResponseSent()` optimistically on submit. This immediately transitions to `Running("Processing response...")`, which then accepts subsequent `ThoughtUpdate` events. Add proper ack events if robustness becomes an issue.

### 2.3 A11y Overlay showApp (Phone Icon)

| Claude Code | Codex |
|---|---|
| Keep phone icon in A11y overlay. Only disable Row1 tap (accidental gesture). User might legitimately want to check main app. | Hide `showApp` entirely in A11y mode. Opening the app disrupts agent workflow. |

**Analysis**: Both have valid points. Opening the main app in A11y mode DOES change the foreground and the agent will detect it. But the user might need to check progress or stop the agent from the main app.

**Recommendation**: Keep the phone icon but add a confirmation or at minimum pause the agent when the user taps it. For now, Claude Code's approach (keep icon, remove Row1 tap) is the simpler path. Codex's stricter approach can be revisited.

### 2.4 State Model: Evolve CapsuleMode vs Replace with TaskUiState

| Claude Code | Codex |
|---|---|
| Keep `CapsuleMode` as-is. The state machine is fundamentally correct. Bugs are in visibility coordination, not in the state enum. | Replace `CapsuleMode` with `TaskUiState` (renames `Hidden` -> `Idle`, `Done` -> `Completed`, `Error` -> `Failed`). Split state into 5 orthogonal concerns: `TaskUiState + UserSurface + PanelMode + PendingCommand + PlatformMode`. |

**Analysis**: Codex's model is more formally rigorous (clean separation of concerns). Claude Code's approach requires fewer changes (keep existing types, add one `ShowPreference` enum). The semantic rename (`Hidden` -> `Idle`) is clearer but requires touching every consumer.

**Recommendation**: Codex's decomposition is conceptually better but riskier to implement (touches more files, introduces more types). Claude Code's incremental approach is safer. The core state enum (`CapsuleMode`) works fine; the problems are in the visibility layer, not the state layer. **Keep `CapsuleMode`, add `ShowPreference`, fix visibility logic.**

---

## 3. Coverage Gaps (One Covers, Other Doesn't)

### 3.1 Codex Covers, Claude Code Doesn't

| Topic | Details |
|---|---|
| **Critical #1: WaitingForInput ack gap** | Codex identifies that `onUserResponseSent()` is never called — the transition from WaitingForInput back to Running has no trigger. Claude Code mentions the method exists but doesn't trace its call chain to verify it's actually invoked. **This is a real critical bug.** |
| **Ack event architecture** | Codex proposes `SessionTakeoverPending`, `UserResponseAccepted`, `UserResponseRejected` as new `AgentEvent` types for strict event-driven transitions. Claude Code uses optimistic local transitions without session-layer ack events. |
| **`CapsuleMode.Hidden` semantic split** | Codex flags (Medium #1) that `Hidden` means both "no task" (Compose: show Row3 input) and "window not visible" (overlay: hide everything). This dual meaning creates confusion. Claude Code mentions this in Problem F but dismisses it. Codex's note is more actionable. |
| **Verification plan** | Codex includes a concrete test plan (Section 5): unit tests for state reducer, presentation policy, ack event flow; integration tests for island/capsule mutual exclusivity; manual regression via `user_flow_test_codex.md`. Claude Code does not include a test plan. |
| **Invariant E (WaitingFor* exit must be via callId ack)** | Codex explicitly states that WaitingFor* states can only be exited via matched `callId` — no random event should jump out. Claude Code's state machine has universal overrides (`onTaskStarted`, `onError`) that CAN exit WaitingFor*, which technically violates this invariant. Whether this matters in practice depends on whether those universal events can race with user responses. |
| **Supplement writing to SessionRecordingService** | Codex traces that supplement writes to `HistoryManager` but NOT to `SessionRecordingService` (AgentSession:321-337). Claude Code only notes "add supplement to chat history" without identifying the recording service gap. |

### 3.2 Claude Code Covers, Codex Doesn't

| Topic | Details |
|---|---|
| **What's well-designed (keep-as-is list)** | Claude Code explicitly lists 9 components that should NOT be changed (Section 1.1). This is valuable for preventing over-refactoring. Codex doesn't have an explicit "keep" list. |
| **SmartCapsuleManager size analysis** | Claude Code analyzes SmartCapsuleManager's 394 lines, lists all 8 responsibilities, and concludes it's cohesive enough to keep. Codex doesn't discuss this file's structure. |
| **Duplicate `previousMode` in manager** | Claude Code identifies that `SmartCapsuleManager.previousMode` duplicates `stateHolder.previousMode`. Minor but real duplication. |
| **Dead code inventory** | Claude Code explicitly lists 3 dead code items (InputDock, InputState, onMessageDelta) for deletion. Codex doesn't mention dead code. |
| **`performHandoff()` as separate Fix** | Both identify it, but Claude Code provides the delete-it reasoning more clearly (cross-display launch is fundamentally wrong for sandboxed VD). |
| **Bug 1.5 status clarification** | Claude Code traces the code to confirm bug 1.5 is already fixed (SmartCapsuleCompose IS the bottom bar). Codex doesn't address 1.5 directly. |
| **VD Nav button wiring investigation** | Claude Code traces the full callback chain for bug 5.2 (phone icon in VD viewer) and concludes the wiring is correct — the issue might be debounce or activity stack behavior. Codex notes the bug but doesn't trace the wiring. |
| **Concrete `applyVisibility()` code** | Claude Code provides full Kotlin pseudocode for the visibility function with all branches. Codex describes the pattern (`derive + apply`) but doesn't show the implementation. |
| **ShowPreference transition table** | Claude Code defines when `ShowPreference` changes (minimize click, island tap, viewer open/close, task start). Codex mentions `PanelMode` but doesn't specify its transition rules. |
| **Event x Visibility matrix** | Claude Code provides a full matrix of 18 events x visibility actions for both A11y and VD modes (Section 2.4). Codex has invariants (Section 2.4) but not the per-event matrix. |

---

## 4. Summary Table

| Dimension | Claude Code | Codex | Winner |
|---|---|---|---|
| **State machine diagnosis** | "Fundamentally correct, visibility is the problem" | "Needs ack events and strict event-driven transitions" | Codex (found the WaitingFor* ack gap) |
| **Visibility system** | `applyVisibility()` with truth table, concrete code | `derivePresentation()` + `applyPresentation()`, declarative invariants | Tie (same idea, CC has code, Codex has invariants) |
| **Pending feedback** | Optimistic `TakeoverPending` on click | `PendingCommand` sealed interface | CC simpler, Codex more principled |
| **Architecture scope** | Incremental fixes, keep existing types | Decompose into 5 orthogonal state dimensions | CC more pragmatic, Codex more rigorous |
| **Completeness** | Covers all bugs, provides detailed code-level fixes | Covers all bugs, provides formal state model + test plan | Tie (different strengths) |
| **Dead code / what to keep** | Explicit keep-list + delete-list | Not covered | CC |
| **Test plan** | Not included | Included (unit + integration + manual) | Codex |
| **Implementation risk** | Low (incremental, ~-50 lines net) | Medium (new types, session-layer changes) | CC lower risk |

---

## 5. Recommended Synthesis

For implementation, take:

1. **From Claude Code**: `applyVisibility()` truth table, `ShowPreference` enum, concrete fixes (F1-F9), dead code deletion, keep-as-is list, incremental approach
2. **From Codex**: WaitingForInput ack fix (call `onUserResponseSent()` from controller), `UserSurface` concept (but implement as the existing `isAppInForeground` boolean for now), verification plan structure, Invariant E awareness
3. **Defer to later**: Codex's `TaskUiState` rename, `PendingCommand` type, session-layer ack events (add these if optimistic approach causes problems)

---

## 6. Comparison of Conflict Resolution Recommendations (Claude Code vs Codex)

Both comparison docs identified the same 4 conflicts. Here's how each resolved them:

### Conflict 1: State Machine Assessment (Incremental vs Structural Refactor)

| | Claude Code Comparison | Codex Comparison |
|---|---|---|
| Recommendation | Keep `CapsuleMode` as-is. Add `ShowPreference` only. Bugs are in visibility logic, not the state enum. Defer structural decomposition. | Keep CapsuleMode for now, but treat Codex's ack completeness as mandatory. "短期按 Claude 降风险落地，长期按 Codex 保证状态机可证明正确。" |
| Difference | **Frames decomposition as unnecessary** — current types already express the right things. | **Frames decomposition as deferred but eventually needed** — explicitly keeps the door open for the 5-dimension split later. |

**Actual gap**: Small. Both say "keep CapsuleMode now." Codex comparison hedges more toward eventual structural refactor; Claude Code comparison is more definitive that it's unnecessary.

### Conflict 2: TakeoverPending (Optimistic vs Ack-Driven)

| | Claude Code Comparison | Codex Comparison |
|---|---|---|
| Recommendation | Use optimistic `onTakeoverRequested()` on click. Add `PendingCommand` later if desync issues emerge. | "点击后给反馈可以做，但不要污染任务真相；等 ack 再推进主状态更稳。" Prefers `PendingCommand` / ack-driven. |
| Difference | **Directly disagree.** Claude Code says optimistic is fine because `TakeoverPending` already exists as a state and works correctly. Codex says optimistic mutation is "polluting task truth." |

**Actual gap**: This is the sharpest disagreement. The question is: is `TakeoverPending` a task truth or a UI feedback? Claude Code says it's a valid task state (the task IS pending takeover). Codex says the task is still Running until the session confirms.

### Conflict 3: A11y showApp (Keep Phone Icon vs Hide Everything)

| | Claude Code Comparison | Codex Comparison |
|---|---|---|
| Recommendation | Keep phone icon, remove Row1 tap only. User might legitimately need to check main app. | Decide with a real UX test. |
| Qi's note (in Codex doc) | N/A | "就不该存在。不该有show app或者row1 tap回app的能力。" |

**Actual gap**: Resolved by Qi's direct input — **remove both**. No phone icon, no Row1 tap in A11y overlay. This overrides both recommendations.

### Conflict 4: WaitingForInput Ack

| | Claude Code Comparison | Codex Comparison |
|---|---|---|
| Recommendation | Call `onUserResponseSent()` from controller optimistically (same as takeover). Add real ack events if needed later. | Supplement ack events (`UserResponseAccepted`/`Rejected`) are mandatory. Highest priority correctness bug. |
| Difference | Claude Code treats this as a minor wiring fix (just call the existing method). Codex treats this as a correctness hole requiring session-layer event changes. |

**Actual gap**: Medium. Both agree `onUserResponseSent()` must be called. They differ on whether to add session-layer ack events now or later.

---

## 7. Final Recommendation

After reviewing both comparison documents, Qi's direct input, and re-examining the code:

### On each conflict:

**1. State model**: Keep `CapsuleMode`. No rename. No 5-dimension decomposition. The types work. The bugs are in visibility logic. Both comparisons agree on this for the short term, and the long-term case for decomposition is speculative.

**2. TakeoverPending**: Use optimistic `onTakeoverRequested()` on click. Rationale:
- `TakeoverPending` already exists as a `CapsuleMode` state with correct render spec and correct transition guards.
- Adding a parallel `PendingCommand` flow means renderers must check TWO sources (mode + pendingCommand) to decide what to show. That's more complex, not simpler.
- The "task truth pollution" argument is theoretically sound but practically moot: the session ALWAYS accepts takeover (there's no rejection path in `AgentSession.handleTakeover()`). There is zero risk of desync.
- If a rejection path is added to the session later, add `PendingCommand` then. YAGNI now.

**3. A11y showApp**: Per Qi's direct input — remove entirely. No phone icon, no Row1 tap in A11y overlay. The agent needs uninterrupted screen control.

**4. WaitingForInput ack**: Call `stateHolder.onUserResponseSent()` from `ServiceOverlayController.onUserResponse` callback. This is a 1-line fix. Do NOT add session-layer ack events (`UserResponseAccepted`/`Rejected`) now because:
- The delivery via `UserResponseChannel` is synchronous from the UI's perspective (suspends until delivered). It either succeeds or the channel is closed.
- Adding ack events means modifying `AgentEvent`, `AgentSession`, `CapsuleStateHolder`, and all observers — a large surface area change for a problem that the 1-line optimistic fix solves.
- If `UserResponseChannel` delivery becomes unreliable (e.g., async with potential rejection), add proper ack events then.

### Implementation order:

1. `applyVisibility()` + `ShowPreference` + island observer fix (solves ~8 bugs)
2. Call `onUserResponseSent()` from controller (1 line, solves WaitingFor* stuck state)
3. Call `onTakeoverRequested()` from controller (1 line, solves pending feedback)
4. Remove showApp + Row1 tap in A11y overlay (per Qi)
5. Disable input in A11y Running/TakeoverPending
6. Delete `performHandoff()`, `InputDock.kt`, `InputState`, `onMessageDelta()`
7. NavSpec context fixes
8. Supplement + completion message in chat history
