# Phase 3 Code Review: Virtual Display Stage 4 — StatusIslandManager and Mode Branching

**Reviewer**: Code Reviewer (systematic)  
**Date**: 2025-02-11  
**Scope**: StatusIslandManager, ServiceOverlayController mode branching, AgentService VD support, handoff logic

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 1 |
| Medium | 3 |
| Low | 2 |

**Recommendation: CHANGES_REQUESTED** — 1 High (thread safety), 3 Medium issues.

---

## 1. ServiceOverlayController.kt — A11y Mode Code Paths

### Findings

#### [LOW] ACCESSIBILITY branches are unchanged ✅

**Verdict**: Each `when(platformMode)` ACCESSIBILITY branch preserves the original logic:

- `updateStatus` → `capsuleManager.updateStatus(status)` (unchanged)
- `showCapsule` → `capsuleManager.show()` (unchanged)
- `hideAll` → `edgeGlowManager.hideImmediately(); capsuleManager.hide()` (unchanged)
- `handleWindowStateChanged` → `handleWindowStateChangedA11y(...)` (unchanged; VD branch no-ops)
- `onTaskStarted` → edge glow + capsule logic with `isAppInForeground` (unchanged)
- `onMessageDelta` → `capsuleManager.onMessageDelta(...)` (unchanged)
- `onTurnPhaseChanged` → `edgeGlowManager.updateState(...)` (unchanged)
- `onActionExecuted` → edge glow + capsule with fallback (unchanged)
- `onTaskCompleted` → `capsuleManager.onTaskCompleted()` (unchanged; SmartCapsuleManager still takes no args)
- `onSessionCompleted` → reason-branched glow + capsule hide (unchanged)
- `onSessionError` → `edgeGlowManager.updateState` + `capsuleManager.onError` (unchanged)
- `onSessionPaused` / `onSessionResumed` → glow + capsule (unchanged)

**Zero regression in A11y mode.**

---

## 2. StatusIslandManager.kt — Lifecycle and Leak Prevention

### Findings

#### [MEDIUM] `updatePauseState` does not post `statusDot` update to main thread

**Line**: 115–116  
**Problem**: `(statusDot?.background as? GradientDrawable)?.setColor(dotColor)` runs on the caller's thread. `updatePauseState` is invoked from `handleEvent` (Main) and `onSessionPaused/Resumed` (Main), so it is currently safe. But `updatePauseState` is a public API; if called from a background thread, this would violate UI thread usage.

**Fix**: Post the dot update for consistency with `pauseIconText?.post { ... }`:
```kotlin
pauseIconText?.post {
    pauseIconText?.text = if (paused) "▶" else "⏸"
}
statusDot?.post {
    (statusDot?.background as? GradientDrawable)?.setColor(dotColor)
}
```

**Severity**: Medium — current callers are main-thread, but the API is not self-documenting.

---

#### [LOW] `handler.removeCallbacksAndMessages(null)` correctly prevents stale callbacks ✅

**Line**: 74  
**Verdict**: `hide()` clears all pending `postDelayed` callbacks (auto-hide from `showSuccess`/`showError`, controls auto-hide from `toggleInlineControls`). Prevents:
- Stale `hide()` after `dispose()`
- References to detached views in delayed callbacks

---

#### [LOW] `dispose()` delegates to `hide()` ✅

**Line**: 118–120  
**Verdict**: `dispose()` calls `hide()`, which nulls `pillView`, `statusText`, `statusDot`, `controlsContainer`, `pauseIconText`. No retained references. Service reference is the owning service; no Activity leak.

---

## 3. AgentService.kt — Viewer Notifications and Thread Safety

### Findings

#### [HIGH] `notifyViewerVisible` / `notifyViewerHidden` may run on wrong thread

**Lines**: 408–420  
**Problem**: These methods are intended to be called from `VirtualDisplayViewerActivity` lifecycle (onStart/onStop) — i.e. main thread. `switchToLivePreview` and `switchToImageReader` in `VirtualDisplayPlatform`:
- Call `shizuku.setVirtualDisplaySurface()` (binder call)
- Mutate `liveSurfaceView`, `surfaceMode`, `pixelCopyFailCount`

If the Activity ever calls these from a background thread (e.g. `lifecycleScope.launch(Dispatchers.IO)`), we risk concurrent access to `surfaceMode`/`liveSurfaceView` with capture coroutines. `@Volatile` gives visibility but not atomicity.

**Fix**: Enforce main-thread, or document the requirement clearly:
```kotlin
fun notifyViewerVisible(surfaceView: SurfaceView) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        Handler(Looper.getMainLooper()).post { notifyViewerVisible(surfaceView) }
        return
    }
    val platform = session?.getServices()?.platform as? VirtualDisplayPlatform ?: return
    platform.switchToLivePreview(surfaceView)
}
```
Or add a KDoc: `/** Must be called from the main thread. */`

---

#### [LOW] `session` read is safe ✅

**Verdict**: `session` is read in `handleEvent` (Main) and in `notifyViewerVisible`/`notifyViewerHidden` (expected Main from Activity). Null check and cast are safe. No concurrent write to `session` during these calls.

---

## 4. AgentService.kt — `performHandoff()` Edge Cases

### Findings

#### [MEDIUM] Handoff when `lastPackage` is null is handled ✅

**Lines**: 428–431  
**Problem**: `getCurrentPackageName()` can return null (no app window, display invalid, etc.).  
**Verdict**: Log and return. Correct.

---

#### [MEDIUM] Handoff when no launch intent is handled ✅

**Lines**: 433–435  
**Problem**: `getLaunchIntentForPackage(lastPackage)` returns null for apps without a launcher activity (e.g. some system components, services).  
**Verdict**: Log and return. Correct.

---

#### [MEDIUM] `getCurrentPackageName()` during handoff — threading

**Line**: 428  
**Problem**: `VirtualDisplayPlatform.getCurrentPackageName()` uses `windowAccessor.getRootOnDisplay()`, which calls `service.getWindowsOnAllDisplays()` / `service.windows`. These AccessibilityService APIs must be called from the main thread.

**Verdict**: `performHandoff()` is invoked from `handleEvent(AgentEvent.TaskCompleted)`, which runs in `scope.launch { agentSession.events.collect { ... } }`. `scope` uses `Dispatchers.Main`. So the call is on the main thread. ✅

---

#### [LOW] Handoff when last package is our own app

**Observation**: If the automated app was `com.moonkey.androidagent`, handoff would relaunch our app. This is benign and may be desired when the goal was to open our app.

---

## 5. ServiceOverlayController — `onTaskCompleted` CompletionReason exhaustiveness

### Findings

#### [LOW] `when(reason)` covers all cases ✅

**Lines**: 189–195  
**Verdict**: `GOAL_ACHIEVED`, `ERROR`, `TASK_IMPOSSIBLE` are explicit; `else` covers `USER_STOPPED`, `MAX_TURNS`, `INTERRUPTED`. Showing "✓ Complete" for `USER_STOPPED`/`INTERRUPTED` is slightly ambiguous but acceptable for a generic completion state.

---

## 6. StatusIslandManager — Creation in A11y Mode

### Findings

#### [LOW] StatusIslandManager always created

**Line**: AgentService 141–148  
**Observation**: `StatusIslandManager` is constructed in `onServiceConnected` regardless of `platformMode`. In ACCESSIBILITY mode it is never used (all `statusIslandManager?.` calls are in VIRTUAL_DISPLAY branches). Minor overhead; acceptable for simplicity. Optional future optimization: lazy creation when switching to VD mode.

---

## 7. `openViewer()` — Hardcoded class name

### Findings

#### [LOW] Hardcoded Activity class name

**Line**: 396  
**Problem**: `setClassName(this, "com.moonkey.androidagent.ui.viewer.VirtualDisplayViewerActivity")` will break if the Activity is moved or renamed.

**Fix**: Use `Intent(this, VirtualDisplayViewerActivity::class.java)` when the Activity exists. Current try-catch around `startActivity` is good for robustness.

---

## Summary Table

| Area | Finding | Severity |
|------|---------|----------|
| A11y code paths | Unchanged, zero regression | — |
| StatusIslandManager lifecycle | `handler.removeCallbacksAndMessages` prevents leaks | — |
| StatusIslandManager | `updatePauseState` statusDot not posted | Medium |
| Viewer notifications | Thread-safety assumption undocumented | High |
| performHandoff | null package, no launch intent handled | — |
| performHandoff | `getCurrentPackageName` main-thread safe | — |
| openViewer | Hardcoded class name | Low |

---

## Recommendation

**CHANGES_REQUESTED** — Address the High and Medium issues before merge:

1. **[HIGH]** Document or enforce main-thread for `notifyViewerVisible`/`notifyViewerHidden`.
2. **[MEDIUM]** In `StatusIslandManager.updatePauseState`, post the `statusDot` color update to the main thread for consistency and future-proofing.
3. **[LOW]** When `VirtualDisplayViewerActivity` is added, switch `openViewer()` to use `Intent(this, VirtualDisplayViewerActivity::class.java)` instead of `setClassName`.

---

## Approval Criteria

- **Approve**: No Critical, no High issues.
- **Request Changes**: Any Critical or 2+ High issues.

**Current**: 1 High, 3 Medium → **CHANGES_REQUESTED**
