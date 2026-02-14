# Smart Capsule Round 4 — Code Review

> Author: Claude  
> Date: 2026-02-13  
> Scope: All `app/` code changed between `339448dd` and `HEAD` (37 files, ~3258 insertions, ~903 deletions)  
> Principle: KISS + Occam's Razor — find systemic root causes, not symptom-level patches

---

## Executive Summary

The Round 4 refactor introduced good foundational abstractions — `CapsuleMode`, `CapsuleStateHolder`, `CapsuleRenderSpec` — but the **coordination layer didn't fully commit to the new architecture**. The result is a hybrid: the state holder owns transitions, but `ServiceOverlayController` still manually drives visibility and rendering in ways that can diverge from state. This creates a class of bugs where **the state says one thing but the UI shows another**.

### Severity Ratings

| Category | Severity | Impact |
|---|---|---|
| A. State machine design gaps | 🔴 High | Causes multiple visible bugs |
| B. Controller still holds shadow concerns | 🟡 Medium | Code complexity, occasional bugs |
| C. Dual auto-hide timers | 🔴 High | Island vanishes prematurely, Done state lost |
| D. Compose capsule coupling gap | 🟡 Medium | Main app bugs (no dialog after completion) |
| E. History recording gap | 🟡 Medium | Complete_task not in history |
| F. Redundant code / violations of KISS | 🟢 Low | Maintainability debt |

---

## Part 1: State Machine Analysis

### 1.1 State Definitions — Mostly Sound

The 8-state `CapsuleMode` sealed interface is clean and well-defined:

```
Hidden → Running → TakeoverPending → Takeover → Running
                 → WaitingForInput → Running
                 → WaitingForAction → Running
                 → Done → (auto-hide) → Hidden
                 → Error → (dismiss) → Hidden
```

**✅ Good**: Each state carries exactly the data it needs (no optional fields, no booleans).

**⚠️ Issue**: `displayThought()` in `CapsuleMode.kt` returns `null` for `WaitingForInput` and `WaitingForAction`, but `CapsuleRenderSpec.from()` generates non-null `thought.text` for these states (`"💬 Awaiting response"`, `"✋ Action needed"`). This means `displayThought()` is inconsistent with the render spec. If anyone uses `displayThought()` instead of going through the spec, they'll get wrong results. **Fix: either remove `displayThought()` (KISS — one path to rendering data) or align it with the spec.**

### 1.2 Transition Guards — One Critical Gap

The guards in `CapsuleStateHolder` are mostly correct per the design matrix, but there's a **missing transition**:

| Gap | Description | Impact |
|---|---|---|
| No `onStop` / `onShutdown` transition | There is no `CapsuleStateHolder` method for `Op.Shutdown`. When user taps "Stop", the flow is: `onStop callback → submitOp(Op.Shutdown) → AgentSession.handleShutdown() → emits SessionCompleted`. But `SessionCompleted` is handled by `onSessionCompleted()` in `ServiceOverlayController`, which **does NOT transition `CapsuleStateHolder` to Done/Hidden**. It only hides the capsule. | **Capsule state can become stale.** If user returns to main app after stopping, Compose sees an active mode but the session is gone. |
| `onSessionCompleted` doesn't set mode | `ServiceOverlayController.onSessionCompleted()` calls `capsuleManager.hide()` and `edgeGlowManager.hideImmediately()` for USER_STOPPED/INTERRUPTED, but never calls `stateHolder.onTaskCompleted()` or transitions to Hidden. | **State/UI divergence.** CapsuleStateHolder still thinks there's an active task. |

**Root cause**: The design has two completion paths — `TaskCompleted` and `SessionCompleted` — but only `TaskCompleted` updates `CapsuleStateHolder`. `SessionCompleted` bypasses the state holder entirely.

**Fix**: `onSessionCompleted()` should unconditionally call `stateHolder.onTaskCompleted(reason)` (or a dedicated `stateHolder.onSessionEnded()`) to ensure the state machine reaches a terminal state. Then the UI cleanup happens automatically via the observer.

### 1.3 State × Event Matrix Audit

Checking every cell of the design matrix against actual code in `CapsuleStateHolder`:

| Event | Expected | Actual | ✅/❌ |
|---|---|---|---|
| `onTaskStarted` from any state | → Running | ✅ Universal, cancels auto-hide | ✅ |
| `onThoughtUpdate` in non-Running | No-op | ✅ Guard: `if (!is Running) return` | ✅ |
| `onTakeoverRequested` in non-Running | No-op | ✅ Guard: `as? Running ?: return` | ✅ |
| `onTakeoverConfirmed` in Running | → Takeover | ✅ Accepts both Running and TakeoverPending | ✅ |
| `onResumed` in non-Takeover/TakeoverPending | No-op | ✅ Guard check | ✅ |
| `onAskUser` from any state | → WaitingFor* | ✅ Universal, no guard | ✅ |
| `onUserResponseSent` in non-WaitingFor* | No-op | ✅ Guard check | ✅ |
| `onTaskCompleted` in Hidden/Done/Error | No-op | ✅ Guard check | ✅ |
| `onError` from any state | → Error | ✅ Universal, cancels auto-hide | ✅ |
| `onDismissError` in non-Error | No-op | ✅ Guard check | ✅ |

**Result**: The `CapsuleStateHolder` internal transitions are correct. The bug is in the **wiring** — events that should reach the state holder don't always do so.

---

## Part 2: Systemic Bug Root Causes

I identified **5 root causes** that explain the bugs mentioned (and likely several more not yet discovered):

### 🔴 Root Cause 1: `SessionCompleted` Bypasses State Machine

**Files**: `ServiceOverlayController.kt:273-296`, `AgentService.kt:353-368`

When `AgentEvent.SessionCompleted` fires (user pressed Stop, agent finished session), `onSessionCompleted()` hides overlays directly but **never transitions CapsuleStateHolder**:

```kotlin
// ServiceOverlayController.kt
fun onSessionCompleted(reason: CompletionReason) {
    when (platformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> { statusIslandManager?.hide() }
        PlatformMode.ACCESSIBILITY -> {
            // ... hides glow and capsule, but NEVER calls stateHolder.onTaskCompleted()
            if (reason == USER_STOPPED || reason == INTERRUPTED) {
                capsuleManager.hide()
            }
        }
    }
}
```

**Visible bugs caused**:
- "完成后complete_task没显示在history里" — because the UI doesn't see the state transition
- "完成后smart capsule在main app里不显示对话框，显示一个已完成" — the Compose capsule reads `stateHolder.mode` which may still be Running/Done from `TaskCompleted`, while the overlay was forcibly hidden
- Status island vanishing unexpectedly — session ends but state holder doesn't know

### 🔴 Root Cause 2: Dual Auto-Hide Timers

**Files**: `CapsuleStateHolder.kt:185-198`, `StatusIslandManager.kt:103-108`

`CapsuleStateHolder` has an auto-hide timer (3s → `Done` → `Hidden`).  
`StatusIslandManager` has its **own independent** auto-hide timer (3s → `hide()`).

These timers are uncoordinated:

1. `CapsuleStateHolder` auto-hides after `Done` → sets mode to `Hidden`
2. `StatusIslandManager` auto-hides independently → removes view from window manager
3. If `StatusIslandManager` fires first, the island disappears but `stateHolder.mode` is still `Done` — so the Compose capsule still shows "Done"
4. If `CapsuleStateHolder` fires first, mode becomes `Hidden`, but the island may still be visible for milliseconds

**Worse**: `StatusIslandManager` auto-hides on **both** `Done` **and** `Hidden`. So when `CapsuleStateHolder` transitions `Done → Hidden`, `renderIsland()` is called with `Hidden` mode, which triggers another auto-hide on the island. But by then `pillView` may already be null.

```kotlin
// StatusIslandManager.kt:104 — hides on BOTH Done AND Hidden
if (mode is CapsuleMode.Done || mode is CapsuleMode.Hidden) {
    val runnable = Runnable { hide() }
    autoHideRunnable = runnable
    handler.postDelayed(runnable, AUTO_HIDE_DELAY_MS)
}
```

**Fix**: Remove auto-hide from `StatusIslandManager`. Let `CapsuleStateHolder` be the single timer. When mode transitions to `Hidden`, the controller should `hideIsland()`. One timer, one source of truth.

### 🔴 Root Cause 3: VD Observer Capsule Show/Hide Logic

**Files**: `ServiceOverlayController.kt:312-325, 340-354, 367-381`

In VD mode, the capsule overlay is shown for `ask_user` interactions and hidden when the agent resumes active work. But the show/hide decisions are scattered and inconsistent:

```kotlin
fun onThoughtUpdate(thought: String) {
    // ...
    PlatformMode.VIRTUAL_DISPLAY -> {
        // "If capsule is showing (from ask_user), hide it — interaction is done"
        if (capsuleManager.isShowing()) capsuleManager.hide()  // (A)
        renderIsland()
    }
}

fun onSessionResumed() {
    // ...
    PlatformMode.VIRTUAL_DISPLAY -> {
        if (capsuleManager.isShowing()) capsuleManager.hide()  // (B)
        renderIsland()
    }
}
```

Problem: The capsule is shown when the user taps the island or opens the viewer (`onIslandTapped`, `onViewerOpened`), but it's also shown for `ask_user`. Lines (A) and (B) hide the capsule whenever the agent starts working again, **regardless of why it was shown**. If the user opened the capsule via island tap (to monitor progress), a thought update will immediately close it.

**Fix**: Track *why* the capsule overlay was shown (user-requested vs ask_user-triggered). Only auto-hide it when the ask_user interaction is complete, not on every thought update. Or simpler KISS solution: **don't auto-hide the capsule in VD mode at all** — let the user minimize it themselves.

### 🟡 Root Cause 4: Compose Capsule `onDismissError` Bypasses ViewModel

**Files**: `ChatScreen.kt:130`

```kotlin
onDismissError = { stateHolder?.onDismissError() }
```

This calls `stateHolder.onDismissError()` directly from the Composable, bypassing `ChatViewModel` entirely. Every other action goes through the ViewModel (`viewModel::sendMessage`, `viewModel::requestTakeover`, etc.), creating an inconsistent pattern.

**Impact**: If you ever need to add error tracking, logging, or analytics on dismiss, there's no central place to hook into. More immediately, the ViewModel's `_taskBannerState` stays in `Error` state even after the capsule error is dismissed — the banner and capsule can show conflicting states.

**Fix**: Add `fun dismissError()` to `ChatViewModel` that calls `stateHolder.onDismissError()` and also updates `_taskBannerState` to `Idle`.

### 🟡 Root Cause 5: `onSend` Callback Not Wired in Overlay Capsule

**Files**: `SmartCapsuleManager.kt:275-277`, `ServiceOverlayController.kt:55-77`

`SmartCapsuleManager.handleRow3Submit()` handles `CapsuleMode.Hidden` correctly:

```kotlin
is CapsuleMode.Hidden -> { onSend?.invoke(text) }
```

But in `ServiceOverlayController`'s init block, `capsuleManager.onSend` is **never set**. The only callback wiring is:

```kotlin
this.onStop = ...
this.onTakeover = ...
this.onResume = ...
this.onSupplement = ...
this.onUserResponse = ...
this.onOpenApp = ...
this.onDismissError = ...
this.onMinimize = ...
this.onOpenViewer = ...
// Missing: this.onSend = ???
```

This means in overlay mode, if the capsule is in `Hidden` mode with Row 3 visible (which it wouldn't be in normal overlay flow, but the spec says `Hidden` has a Row3), the send button does nothing. This is a minor gap since the overlay capsule isn't typically shown in `Hidden` mode, but it's a latent bug waiting to happen.

---

## Part 3: Refactoring Proposals (KISS + Occam's Razor)

### Proposal 1: Unify `TaskCompleted` and `SessionCompleted` Handling

**Current**: Two separate event handlers with different behaviors.  
**Proposed**: A single path through `CapsuleStateHolder` for all completion scenarios.

```kotlin
// ServiceOverlayController.kt — simplified
fun onSessionCompleted(reason: CompletionReason) {
    // ALWAYS go through state holder — let the observer pattern do the rest
    stateHolder.onTaskCompleted(reason)
    
    // Only VD-specific cleanup
    if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
        // Island auto-hides via observation (state → Hidden triggers hide)
    }
}
```

This eliminates 20+ lines of manual hide/show logic and ensures the state machine is always in the correct state.

### Proposal 2: One Timer, One Place

**Current**: `CapsuleStateHolder.autoHideJob` + `StatusIslandManager.autoHideRunnable`  
**Proposed**: Only `CapsuleStateHolder` has a timer. When it transitions `Done → Hidden`, the controller reacts:

```kotlin
// In ServiceOverlayController — observe mode transitions
scope.launch {
    stateHolder.mode.collect { mode ->
        if (mode is CapsuleMode.Hidden) {
            hideIsland()
            // A11y: capsule observer already calls hide() internally
        }
    }
}
```

Remove `autoHideRunnable` from `StatusIslandManager` entirely. KISS: one component owns the timer, others react.

### Proposal 3: Remove `displayThought()` and `isExpanded()`

**Current**: `CapsuleMode.kt` has extension functions `displayThought()` and `isExpanded()` that partially duplicate `CapsuleRenderSpec.from()`.  
**Proposed**: Delete both. All rendering decisions should go through `CapsuleRenderSpec`. 

`displayThought()` is unused except in tests. `isExpanded()` is used in two places:
- `SmartCapsuleManager.isHeightTransition()` — replace with `spec.expandedBody != null`
- Tests — update to use `CapsuleRenderSpec.from(mode).expandedBody != null`

**Why**: Two paths to "what does this mode look like" = two places for bugs. One path = KISS.

### Proposal 4: Collapse `updateStatus()` → `onThoughtUpdate()`

**Files**: `ServiceOverlayController.kt:141-150`

`updateStatus()` is a legacy method that does platform-specific branching for showing status in the island vs. the capsule placeholder. It's called from `AgentService.updateStatus()`, which is itself called from several `handleEvent()` branches.

But every meaningful status update already goes through a more specific event (`ThoughtUpdate`, `TurnPhaseChanged`, `ActionExecuted`). The only unique call path is `AgentEvent.StatusUpdate` — which also calls `ServiceOverlayController.updateStatus()`.

**Proposed**: Remove `updateStatus()`. Let `StatusUpdate` events flow through the normal event pipeline. If they're worth showing, they should go through `onThoughtUpdate()`. If they're not worth showing (most aren't — they're log-level messages), don't push them to the UI at all.

### Proposal 5: Remove `maybeUpdatePlaceholderThought()` 

**Files**: `ServiceOverlayController.kt:399-406`

This method is a hack: it waits for the thought text to be "Thinking..." and then replaces it with cleaned status text. This is fragile (depends on exact string matching) and creates a side channel for thought updates.

The proper fix: ensure `AgentTurnRunner` / `AgentEventDispatcher` emits proper `ThoughtUpdate` events early enough that the placeholder "Thinking..." is never visible for long. If that's already happening, this method is dead code. If it's not, the fix belongs in the agent layer, not as a UI workaround.

### Proposal 6: Simplify `ServiceOverlayController` Event Handler Pattern

Currently every event handler has the pattern:
```kotlin
fun onXxx() {
    stateHolder.onXxx()
    when (platformMode) {
        VD -> { ... }
        A11y -> { ... }
    }
}
```

The A11y branches are almost always "Manager auto-renders via observer" (a comment that does nothing). The VD branches are typically `renderIsland()`. This can be simplified:

```kotlin
fun onXxx() {
    stateHolder.onXxx()
    syncOverlays()  // single helper replaces all per-event visibility logic
}

private fun syncOverlays() {
    when (platformMode) {
        VD -> renderIsland()
        A11y -> edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }
}
```

Not every handler can use the generic helper (some have special logic like showing capsule for `ask_user`), but many can. Reduces 15+ `when (platformMode)` blocks to ~5.

---

## Part 4: Per-State Rendering Correctness

### 4.1 Overlay Capsule (View) via `SmartCapsuleRenderer`

Checked each state against `CapsuleRenderSpec.from()` and verified `SmartCapsuleRenderer.render()` applies all fields:

| State | Row1 (dot+thought) | Row2 (buttons) | Row3 (input) | Expanded | Verdict |
|---|---|---|---|---|---|
| Running | ✅ 🔵 pulse + thought | ✅ Takeover + Stop | ✅ "Add note" | ✅ hidden | ✅ |
| TakeoverPending | ✅ 🟡 static + "Handing over..." | ✅ disabled primary + Stop | ✅ "Add note" | ✅ hidden | ✅ |
| Takeover | ✅ 🟡 static + dimmed thought | ✅ Resume + Stop | ✅ "Add note" | ✅ hidden | ✅ |
| WaitingForInput | ✅ no dot + "💬 Awaiting..." | ✅ no primary + Stop | ✅ "Send →" + clear | ✅ question | ✅ |
| WaitingForAction | ✅ no dot + "✋ Action needed" | ✅ Done + Stop | ✅ hidden | ✅ instruction | ✅ |
| Done | ✅ 🟢 static + "✓ msg" | ✅ hidden | ✅ hidden | ✅ hidden | ✅ |
| Error | ✅ 🔴 static + "⚠ msg" | ✅ no primary + Close | ✅ hidden | ✅ hidden | ✅ |
| Hidden | ✅ no dot + empty | ✅ hidden | ✅ "Send →" | ✅ hidden | ⚠️ See below |

**⚠️ Hidden state in overlay**: The spec defines Row3 for Hidden mode (acting as InputDock), but the overlay observer calls `hide()` when mode is Hidden. So Row3 is never rendered in Hidden mode for the overlay. This is correct behavior — the spec's Hidden Row3 is **only** for the Compose capsule in `MAIN_APP` context. But it's confusing in the spec because the spec doesn't distinguish contexts.

### 4.2 Compose Capsule (`SmartCapsuleCompose`)

The Compose capsule has a clear rendering path: `mode → CapsuleRenderSpec.from(mode) → UI`.  

**⚠️ Issue**: Row2 (buttons + nav) shows a divider and the entire row even when there are no buttons (like in `Done` state). The `AnimatedVisibility` wraps **all** of Row1 + Row2 + expanded body together — it's visible whenever `isTaskActive` (mode != Hidden). So in `Done` state:
- Row1: shows "✓ Completed" ✅
- Divider 1: visible ❌ (buttons are hidden — divider is visual noise)
- Row2: CapsuleRow2 renders both button rows as empty, but the Row itself is still laid out with `Arrangement.SpaceBetween` — may show an empty row
- Divider 2: visible only if Row3 exists (null for Done, so hidden) ✅

**Fix**: Conditionally hide divider 1 and Row2 when both `spec.buttons.primary == null && spec.buttons.stop == null`.

### 4.3 Status Island

The island renders mode text correctly. One issue:

**⚠️ Chinese text in island**: `StatusIslandManager.renderMode()` uses English text in the current code, which matches the design requirement "code里面没有中文". The Round 4 design doc Section 4.9 still has a Chinese version as an example (`"交接中..."`, `"已暂停"`), but the actual code uses English. ✅ This is correct — the code is right, the design doc is stale.

---

## Part 5: Specific Bug Analysis

### Bug: "VD模式下点eye看不到virtual display"

**Root cause**: The 👁 button in the Compose capsule calls `onNavigate(NavAction.OPEN_VIEWER)` → but `ChatScreen.kt:134` says:

```kotlin
onNavigate = { /* No-op: nav buttons hidden in MAIN_APP context */ }
```

The NavSpec for `MAIN_APP` context:
- `showWatch = platformMode != ACCESSIBILITY && context != SCREEN_VIEWING`

So in `MAIN_APP` + `VIRTUAL_DISPLAY` mode, `showWatch = true`. The 👁 button IS visible. But the click handler is a no-op! The button appears but does nothing.

**Fix**: Wire `onNavigate` to actually handle `NavAction.OPEN_VIEWER` — launch `VirtualDisplayViewerActivity`.

### Bug: "一些按钮点了没反应"

Multiple causes:
1. **👁 button in main app** — no-op handler (see above)
2. **Debouncing in overlay** — `SmartCapsuleManager.debounced()` uses 300ms debounce. Quick taps are swallowed. This is aggressive for a button-heavy UI.
3. **Stop button in Error mode** — correctly calls `onDismissError`, but `onDismissError` callback in `ServiceOverlayController` only calls `stateHolder.onDismissError()` → transitions to `Hidden` → observer calls `hide()`. This works, but if the observer hasn't fired yet when the user taps again, the debounce blocks it. 300ms is too aggressive.

**Fix**: Reduce debounce to 150ms, or use per-button debounce instead of a global one.

### Bug: "status island一点没了"

**Root cause**: When user taps the island, `onIslandTapped()` is called → it shows capsule overlay + hides island. But the user tapped the island — it was a single tap to expand details. The expected behavior is either:
1. Island stays visible as a "minimized" indicator, or
2. Island is explicitly replaced by the capsule (with a minimize button to return to island)

Option 2 is the design intent (minimize button is wired). But the transition feels abrupt because there's no animation. Also, if `showCapsuleOverlay()` fails (e.g., window manager exception), the island is hidden but the capsule doesn't appear → the user sees nothing.

**Fix**: Show capsule before hiding island. Only `hideIsland()` after `capsuleManager.isShowing()` returns `true`.

### Bug: "完成后complete_task没显示在history里"

**Root cause**: In `AgentService.handleEvent()`:
```kotlin
is AgentEvent.TaskCompleted -> {
    recordingService?.completeAgentMessage()
    overlayController?.onTaskCompleted(event.reason)
}
```

`recordingService?.completeAgentMessage()` marks the current agent message as complete. But the `ChatViewModel.EventReducer.handleTaskCompleted()` calls:
```kotlin
updateLastAgentMessage { msg -> msg.copy(state = AgentMessageState.Complete) }
```

This updates the in-memory message list but **doesn't save to history**. The `ChatSessionHistoryController` likely handles persistence elsewhere, but the `complete_task` result (`event.result`) is only used for the banner (`TaskBannerState.Completed(summary = event.result ?: "Task complete")`). It's not appended to the agent message's content blocks.

**Fix**: In `handleTaskCompleted()`, append a `ContentBlock.Text` with the completion message to the agent's content blocks before marking as `Complete`. Also ensure `ChatSessionHistoryController` auto-saves on task completion.

### Bug: "完成后smart capsule在main app里不显示对话框，显示一个已完成"

**Root cause**: The Compose capsule in `ChatScreen.kt` reads `capsuleMode` from `stateHolder.mode`. When the task completes:
1. `CapsuleStateHolder.onTaskCompleted(GOAL_ACHIEVED)` → mode = `Done("Completed")`
2. Compose renders `Done` state: just shows Row1 with "✓ Completed" — no buttons, no row3, no expanded body
3. After 3s auto-hide, mode → `Hidden` → only Row3 (input) visible

This IS the designed behavior for `Done` state. The user expects a "completion dialog" but gets a one-line status. The issue is that the **design doesn't include a completion summary** in the capsule.

**Fix (UX)**: Consider showing `event.result` as an expanded body in `Done` state, or show the result in a toast/snackbar instead. Quick impl: change `CapsuleRenderSpec` for `Done` to include an `expandedBody`:

```kotlin
is CapsuleMode.Done -> CapsuleRenderSpec(
    // ...
    expandedBody = mode.message.takeIf { it != "Completed" },  // show if there's a real message
    // ...
)
```

---

## Part 6: Code Simplification Opportunities

### 6.1 `SmartCapsuleManager` — 392 lines, could be ~300

- `flashSupplementConfirmation()` (lines 163-176) — this directly mutates `thoughtText.text`, bypassing the observer pattern. It should instead create a transient `CapsuleMode` variant (or the spec should have a `supplementConfirmation` field) so the renderer handles it.
- `capsuleContext`, `platformMode`, `hasIsland` fields (lines 71-73) — duplicate state from `CapsuleStateHolder`. Use `stateHolder.context`, `stateHolder.platformMode` instead.

### 6.2 `ServiceOverlayController` — 458 lines, should target ~300

- `showCapsule()` (lines 152-162) — only called from `AgentService.runAgent()` as "show initial overlay". But `onTaskStarted()` already shows the capsule. This is a redundant entry point.
- `updateStatus()` (lines 141-150) — legacy method, as discussed in Proposal 4.
- `onMessageDelta()` (lines 214-223) — just calls `maybeUpdatePlaceholderThought()`. The whole method + helper can be removed if proper `ThoughtUpdate` events are emitted early.

### 6.3 `SmartCapsuleCompose` — 368 lines, well-structured

- The only simplification is removing the manual `previousModeState` tracking (lines 92-97). This could be moved into `CapsuleStateHolder` as a public StateFlow.

### 6.4 `CapsuleRenderSpec` — Clean, keep as-is

This file is the gem of the refactor. No changes needed.

### 6.5 Dead Code Candidates

| Code | File | Reason |
|---|---|---|
| `displayThought()` | `CapsuleMode.kt` | Duplicates `CapsuleRenderSpec`, unused in production |
| `isExpanded()` | `CapsuleMode.kt` | Only 1 usage, replace with spec check |
| `updateStatus()` | `ServiceOverlayController.kt` | Legacy, bypassed by newer event handlers |
| `maybeUpdatePlaceholderThought()` | `ServiceOverlayController.kt` | Fragile hack, fix in agent layer instead |
| `showCapsule()` | `ServiceOverlayController.kt` | Redundant with `onTaskStarted()` |

---

## Part 7: Priority-Ordered Fix List

| # | Fix | Root Cause | Impact | Effort |
|---|---|---|---|---|
| 1 | `onSessionCompleted()` must transition `CapsuleStateHolder` | RC1 | All completion bugs | S |
| 2 | Remove island's independent auto-hide timer | RC2 | Island timing bugs | S |
| 3 | Wire `onNavigate(OPEN_VIEWER)` in `ChatScreen` | Bug analysis | 👁 button broken | XS |
| 4 | Add `dismissError()` to `ChatViewModel` | RC4 | State divergence | XS |
| 5 | Fix Done state divider in Compose capsule | Rendering | Visual polish | XS |
| 6 | Show capsule before hiding island | Bug analysis | "island一点没了" | S |
| 7 | Track why capsule was shown in VD mode | RC3 | Capsule auto-dismissed | M |
| 8 | Remove `displayThought()`, `isExpanded()` | KISS | Maintainability | S |
| 9 | Unify event handler pattern in controller | KISS | Line count reduction | M |
| 10 | Delete legacy `updateStatus()` / `maybeUpdatePlaceholderThought()` | KISS | Dead code cleanup | S |

> **XS** = < 10 lines, **S** = 10-30 lines, **M** = 30-80 lines

**Recommendation**: Fixes 1-6 should be done as a batch — they address all reported bugs. Fixes 7-10 are cleanup that can follow.

---

## Appendix: File-by-File Health Check

| File | Lines | Health | Notes |
|---|---|---|---|
| `CapsuleMode.kt` | 62 | 🟢 | Clean sealed interface. Remove extension functions |
| `CapsuleRenderSpec.kt` | 166 | 🟢 | Best file in the codebase. No changes needed |
| `CapsuleColors.kt` | 24 | 🟢 | Simple, correct |
| `GlowState.kt` | 34 | 🟢 | Clean derivation function |
| `CapsuleStateHolder.kt` | 200 | 🟢 | Solid. Add `onSessionEnded()` method |
| `CapsuleContext.kt` | 18 | 🟢 | Simple enum |
| `SmartCapsuleRenderer.kt` | 189 | 🟢 | Pure renderer, well-scoped |
| `SmartCapsuleAnimator.kt` | 113 | 🟢 | Clean window-level animations |
| `SmartCapsuleManager.kt` | 392 | 🟡 | Good observer pattern, but has shadow context state |
| `SmartCapsuleCompose.kt` | 368 | 🟡 | Good spec usage. Fix divider and nav wiring |
| `StatusIslandManager.kt` | 209 | 🟡 | Remove independent auto-hide timer |
| `ServiceOverlayController.kt` | 458 | 🟡 | Too many `when (platformMode)` blocks, legacy methods |
| `AgentService.kt` | 542 | 🟡 | Event handler is clean. Missing `SessionCompleted` → state holder |
| `ChatScreen.kt` | 203 | 🟡 | `onNavigate` and `onDismissError` wiring gaps |
| `ChatViewModel.kt` | 412 | 🟡 | Missing `dismissError()`, completion not saved to history |
| `UserResponseChannel.kt` | 68 | 🟢 | Simple, correct, well-tested |
| `AskUserTool.kt` | 150 | 🟢 | Clean tool implementation |
| `AgentSession.kt` | 439 | 🟢 | Well-structured session lifecycle |
