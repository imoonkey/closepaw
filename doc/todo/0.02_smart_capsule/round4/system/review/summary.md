# Round 4 Review Summary — Execution Plan

> Sources: `round4_code_review_claude.md`, `round4_code_review_codex.md`, `round4_code_review_codex_2.md`  
> Principle: KISS / Occam's Razor — one source of truth per concern, event-driven transitions, no shadow state  
> Verdict: **CHANGES_REQUESTED**

---

## Phase 1: State Foundation (Must-Do First)

> Stabilize the state/event substrate. All visible bugs trace back to these.

### 1A. Ack-Driven State Transitions (Remove Optimistic Jumps)

**Problem**: Click callbacks mutate `CapsuleStateHolder` first, then submit `Op` asynchronously. If session rejects (null session, callId mismatch, timeout), UI is already in wrong state.

**Consensus**: Codex + Codex2. Claude noted wiring gaps as the root cause but didn't flag the optimistic pattern itself.

**KISS Decision**: Clicks only emit intent (`Op`). State transitions happen **only** on confirmed session events.

| Current (Optimistic) | Fixed (Ack-Driven) |
|---|---|
| `onTakeover` → `stateHolder.onTakeoverRequested()` → `submitOp(Op.Takeover)` | `onTakeover` → `submitOp(Op.Takeover)` only; state moves on `SessionTakeover` event |
| `onUserResponse` → `stateHolder.onUserResponseSent(callId)` → `submitOp(Op.UserResponse)` | `onUserResponse` → `submitOp(Op.UserResponse)` only; state moves on `ThoughtUpdate`/`TurnStarted` (agent resumed) |

**Files to change**:

| File | Change |
|---|---|
| [ServiceOverlayController.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt) | Remove direct `stateHolder.onTakeoverRequested()` and `stateHolder.onUserResponseSent()` from click handlers. Keep only `submitOp()`. |
| [CapsuleStateHolder.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt) | `onTakeoverRequested()` should be triggered by the `SessionTakeover` event path instead. For `onUserResponseSent()`, transition occurs when agent's next event arrives. Consider adding optional "pending" visual feedback (e.g. disabled button) without changing mode. |

> [!IMPORTANT]
> TakeoverPending mode may still be useful as a visual-only "loading" indicator. But it should NOT be a full state machine transition driven by click — it could be a local `isSubmitting` flag in the Manager/Compose instead.

---

### 1B. Unify Completion Lifecycle (Single Owner)

**Problem**: Three actors handle completion differently:
1. `AgentService.handleEvent(TaskCompleted)` → calls `overlayController.onTaskCompleted()` → transitions state holder ✅
2. `AgentService.handleEvent(SessionCompleted)` → calls `overlayController.onSessionCompleted()` → hides UI **without** transitioning state holder ❌
3. `MainActivity.onTaskCompleted` → immediately submits `Op.Shutdown` → triggers `SessionCompleted` → double event ❌

**Consensus**: All 3 reviews.

**KISS Decision**: 
- **Service** is the single owner of finalization. On `TaskCompleted`, it transitions state → Done. On `SessionCompleted`, it also drives state → terminal.
- **MainActivity** stops calling `Op.Shutdown` on task completion. Session goes to Idle naturally. Shutdown happens only on explicit user action or app lifecycle.

**Files to change**:

| File | Change |
|---|---|
| [ServiceOverlayController.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt) | `onSessionCompleted(reason)` must call `stateHolder.onSessionEnded(reason)` to move state to `Done`/`Hidden`. Remove manual `capsuleManager.hide()` / `edgeGlowManager.hideImmediately()` — let observer handle it. |
| [CapsuleStateHolder.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt) | Add `fun onSessionEnded(reason: CompletionReason)` — transitions to `Done(message)` for `GOAL_ACHIEVED`, or directly to `Hidden` for `USER_STOPPED`/`INTERRUPTED`. |
| [MainActivity.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt) | Replace `onTaskCompleted` block (lines 117–132). Remove `Op.Shutdown` on task complete. Only call `recordingService.completeSession()`. Let session go Idle for potential multi-task. |

---

### 1C. Completion Payload End-to-End

**Problem**: `TurnOutcome.Complete(message)` contains a summary, but `Agent.kt` maps it to `AgentStopReason.GoalAchieved` (a `data object`, no message field). So `TaskCompleted.result` is always `null` for success. UI shows generic "Completed".

**Consensus**: Codex2 traced the full data chain. Claude noted the UI gap independently.

**KISS Decision**: Carry the message through the entire chain.

**Files to change**:

| File | Change |
|---|---|
| [AgentRuntimeTypes.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt) | `data object GoalAchieved` → `data class GoalAchieved(val message: String = "Goal achieved")` |
| [Agent.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt) | Line ~103: `stopReason = AgentStopReason.GoalAchieved` → `AgentStopReason.GoalAchieved(result.message)` |
| [AgentSession.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt) | Line ~257: `val resultMessage = if (reason is Error) reason.message else null` → also extract message from `GoalAchieved` |
| [CapsuleStateHolder.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt) | `onTaskCompleted(reason)` should also accept an optional `message: String?` to set `Done(message)` |

---

## Phase 2: Observer Model Unification

> Make all renderers reactive. Eliminate push-only island and manual hide/show.

### 2A. Island → Observe Model (Like Capsule)

**Problem**: `SmartCapsuleManager` observes `stateHolder.mode` (reactive). `StatusIslandManager` is pushed by controller via `renderIsland()` (imperative). Internal transitions (auto-hide, dismiss) don't propagate to island.

**Consensus**: Codex + Claude.

**KISS Decision**: `StatusIslandManager` also observes `stateHolder.mode`. Controller only manages window attach/detach.

**Files to change**:

| File | Change |
|---|---|
| [StatusIslandManager.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt) | Add `fun startObserving(stateHolder)` that launches a coroutine collecting `stateHolder.mode`. On `Hidden`: auto-hide window. Remove `autoHideRunnable` entirely. |
| [ServiceOverlayController.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt) | Remove all `renderIsland()` calls from event handlers. Call `statusIslandManager.startObserving(stateHolder)` on init. Keep `showIsland()` / `hideIsland()` only for explicit show/hide (island tap → capsule swap). |

### 2B. Remove Dual Auto-Hide Timer

**Consensus**: Claude primary, Codex supports via unified state source.

| File | Change |
|---|---|
| [StatusIslandManager.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt) | Delete `autoHideRunnable`, `handler.postDelayed()` in `renderIsland()`. Timer only lives in `CapsuleStateHolder.autoHideJob`. |
| [CapsuleStateHolder.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt) | No change needed — existing `autoHideJob` is the single timer. |

### 2C. VD Capsule: Don't Auto-Hide

**Problem**: In VD mode, capsule is hidden on every `ThoughtUpdate` or `SessionResumed`, regardless of why it was shown (user tap vs ask_user interaction).

**Conflict resolution**: Claude proposed tracking "who opened capsule". Codex2 proposed "never auto-hide". **KISS choice: don't auto-hide.** User minimizes explicitly. Less state, no edge cases.

| File | Change |
|---|---|
| [ServiceOverlayController.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt) | Remove `if (capsuleManager.isShowing()) capsuleManager.hide()` from `onThoughtUpdate()` VD branch and `onSessionResumed()` VD branch. |

---

## Phase 3: VD Navigation + Local Fixes

> Fix broken wiring and inconsistent semantics.

### 3A. Wire OPEN_VIEWER in ChatScreen

**Consensus**: All 3 reviews.

| File | Change |
|---|---|
| [ChatScreen.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt) | Replace `onNavigate = { /* No-op */ }` with actual handler. Pass an `onOpenViewer: () -> Unit` callback from `MainActivity` that launches `VirtualDisplayViewerActivity`. |
| [MainActivity.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt) | Add `onOpenViewer` parameter to `ChatScreen` call, launching viewer intent. |

### 3B. Route Error Dismiss Through ViewModel

**Consensus**: Claude primary.

| File | Change |
|---|---|
| [ChatViewModel.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt) | Add `fun dismissError()` that calls `AgentService.instance?.capsuleStateHolder?.onDismissError()` and resets `_taskBannerState.value = TaskBannerState.Idle`. |
| [ChatScreen.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt) | `onDismissError = { viewModel.dismissError() }` |

### 3C. Narrow `hasActiveTask` Semantics

**Consensus**: Codex + Codex2.

| File | Change |
|---|---|
| [CapsuleStateHolder.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt) | `hasActiveTask` should exclude `Done` and `Error` (terminal states). Only `Running`, `TakeoverPending`, `Takeover`, `WaitingForInput`, `WaitingForAction` are "active". |

### 3D. Island Tap: Show-Before-Hide

**Consensus**: Claude + Codex2.

| File | Change |
|---|---|
| [ServiceOverlayController.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt) | In `onIslandTapped()`: show capsule first, confirm it's visible, then hide island. Guard: if no active task, don't hide island — show a toast or navigate to main app. |

### 3E. Remove Dead Code

**Consensus**: Claude primary. Codex2 noted `Hidden` Row3 in overlay is unreachable.

| Code to Remove | File |
|---|---|
| `displayThought()`, `isExpanded()` | `CapsuleMode.kt` |
| `updateStatus()` | `ServiceOverlayController.kt` |
| `maybeUpdatePlaceholderThought()` | `ServiceOverlayController.kt` |
| `showCapsule()` (redundant with `onTaskStarted()`) | `ServiceOverlayController.kt` |

---

## Phase 4: Context Consistency (If Time Permits)

### 4A. Unify CapsuleContext Update Points

**Consensus**: Codex + Codex2.

Currently `CapsuleContext` is set at scattered points: `onIslandTapped()`, `onViewerOpened()`, `setPlatformMode()`. On VD task start, context is not set to `BACKGROUND`.

**KISS Decision**: Single reducer function `updateContext(trigger: ContextTrigger)` in `ServiceOverlayController` that derives context from (platformMode, foregroundActivity, viewerVisible).

### 4B. Separate Task State from Window Visibility

**Consensus**: Claude + Codex2.

`CapsuleMode.Hidden` conflates "no active task" with "window not visible". In Compose (main app), Hidden = input dock. In overlay, Hidden = `hide()`. This duality is the source of the spec confusion.

**KISS Direction**: Keep as-is for now. The spec already handles it (overlay ignores Hidden's Row3). But if adding more modes later, consider splitting into `TaskUiState` + `OverlayVisibilityState`.

---

## Conflicts Between Reviews — Resolved

| Conflict | Options | KISS Choice | Rationale |
|---|---|---|---|
| VD 👁 button: hide vs enable | Hide in MAIN_APP ∨ Wire real handler | **Wire real `OPEN_VIEWER`** | User expects 👁 to work. Hiding adds conditional complexity. |
| VD capsule auto-close: track opener vs never auto-close | Track `ShowReason` enum ∨ Never auto-close | **Never auto-close** | No new state, no edge cases. User minimizes explicitly. |
| Completion: reuse `onTaskCompleted` vs new `onSessionEnded` | Reuse ∨ New method | **New `onSessionEnded(reason)`** | Task vs session are distinct lifecycles. Separate methods prevent semantic leak. |
| `displayThought`/`isExpanded`: keep vs delete | Keep as helpers ∨ Delete | **Delete** | `CapsuleRenderSpec` is the single rendering truth source. Two paths = two divergence points. |
| `TakeoverPending` as full state vs loading indicator | Full CapsuleMode ∨ Local `isSubmitting` flag | **Local loading flag** | KISS: no state machine transition for a transient visual. Reduces transition matrix. |

---

## Execution Order

```
Phase 1 (Foundation) ─── Must be done as a batch. All visible bugs trace here.
  1A: Ack-driven transitions
  1B: Completion lifecycle unification
  1C: Completion payload e2e

Phase 2 (Observer) ─── Makes system self-consistent. Done after Phase 1.
  2A: Island → observe model
  2B: Remove dual timer
  2C: VD capsule no auto-hide

Phase 3 (Local Fixes) ─── Safe to parallelize. Independent of each other.
  3A: Wire OPEN_VIEWER
  3B: Error dismiss via ViewModel
  3C: Narrow hasActiveTask
  3D: Island tap guard
  3E: Dead code removal

Phase 4 (Optional) ─── Only if Phase 1-3 reveal more context bugs.
  4A: Context reducer
  4B: State/visibility separation
```

## Verification

After each phase, run:
```bash
./gradlew assembleDebug lint test
```

Manual smoke tests per phase:
- **Phase 1**: Start task → complete → verify Done state in both overlay and main app. Stop mid-task → verify Hidden state. Check `TaskCompleted.result` has real summary.
- **Phase 2**: VD mode: verify island auto-hides only after state holder timer. Tap island → capsule shows → minimize → island returns. Internal state change (error dismiss) → island updates.
- **Phase 3**: VD + MAIN_APP → tap 👁 → viewer opens. Error → dismiss → banner resets. Tap island with no active task → no black hole.
