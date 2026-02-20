# UI Rationality Suggestions — Code Issues & Improvement Proposals

Date: 2026-02-20
Source: Comparison of design docs (round6/round7) vs actual code implementation.

---

## Critical Issues

### C1: CapsuleOverlayHost — FLAG_NOT_TOUCHABLE Never Removed

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:273`

**Problem**: `createLayoutParams()` sets `FLAG_NOT_TOUCHABLE` in the initial flags:
```kotlin
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
```

This flag is **never removed** anywhere in the codebase. `setOverlayFocusable()` (line 245) only toggles `FLAG_NOT_FOCUSABLE`, not `FLAG_NOT_TOUCHABLE`.

**Impact**: The overlay capsule is completely non-interactive when shown as a system overlay. Users cannot:
- Click Takeover, Resume, Stop, Done, Close buttons
- Type in the Row3 input field
- Click ⊖, 📱, 👁 navigation buttons
- Tap Row1 to return to app

This effectively makes the overlay capsule purely cosmetic. All state changes the design spec relies on (F2 Takeover, F4 Stop, F5 Supplement via overlay, F6 WaitingForInput response, F8 ⊖ toggle, F10 📱 navigation) are impossible from the overlay.

**Context**: This was intentionally set per `doc/todo/eval_tune/round4/debug5/align/design/design.md` as a temporary fix for `dispatchGesture` being blocked by the overlay during eval. The doc says: "overlay 暂时set成FLAG_NOT_TOUCHABLE".

**Suggestion**: Implement touch passthrough toggling:
- When agent is executing actions (Running, not in user interaction mode): set `FLAG_NOT_TOUCHABLE` to allow `dispatchGesture` through
- When user needs to interact (Takeover, WaitingForInput, WaitingForAction, Error): remove `FLAG_NOT_TOUCHABLE`
- When interaction is locked (full-screen touch block during Running): the existing touch-eating View already handles this, so `FLAG_NOT_TOUCHABLE` should be removed in that case too (the View consumes touches instead)

Proposed implementation in `CapsuleOverlayHost`:
```kotlin
// Add method alongside setOverlayFocusable():
private fun setOverlayTouchable(touchable: Boolean) {
    if (!composeHost.isShowing()) return
    composeHost.updateLayoutParams { params ->
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }
}

// Toggle based on mode in focus observer or a new touchability observer:
// touchable when: Takeover, WaitingForInput, WaitingForAction, Error, Done
// non-touchable when: Running, TakeoverPending (agent executing, dispatchGesture needs passthrough)
```

**Priority**: P0 — without this fix, the entire A11y overlay UX is non-functional for user interaction.

---

### C2: A11y Mode Can Show Island (Design Violation)

**File**: `app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:97-104`

**Problem**: The A11y visibility path computes:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    val isOverlayContext = location != OverlayUserLocation.MAIN_APP && isActive
    OverlayVisibilityDecision(
        showCapsule = isOverlayContext && normalizedShowPreference == ShowPreference.CAPSULE,
        showIsland = isOverlayContext && normalizedShowPreference == ShowPreference.ISLAND,
        ...
    )
}
```

If `showPreference` somehow becomes `ISLAND` while in A11y mode, the island will show. The design docs explicitly state "A11y has no island" (design.md §1, §16 P3).

Currently, A11y can have `showPreference = ISLAND` because:
1. `ServiceOverlayController` initializes `showPreference = ISLAND` (line 103)
2. A11y's `onIslandTapped()` path (line 180) sets `showPreference = CAPSULE`, proving island tap is reachable in A11y

The `IslandOverlayHost` is always created (passed as `statusIslandManager` in `AgentService.kt:154`), regardless of platform mode.

**Suggestion**: Guard at the visibility level:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    val isOverlayContext = location != OverlayUserLocation.MAIN_APP && isActive
    OverlayVisibilityDecision(
        showCapsule = isOverlayContext, // Always capsule in A11y, no island choice
        showIsland = false,             // A11y never shows island
        ...
    )
}
```

Or: set `showPreference = CAPSULE` when `setPlatformMode(ACCESSIBILITY)` is called.

**Priority**: P1 — currently this requires `showPreference` to start as `ISLAND`, and the first `onTaskStarted` forces `CAPSULE`. But the initial state window between service start and first task is unguarded.

---

## Medium Issues

### M1: A11y onIslandTapped Path Has Dead Code

**File**: `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:179-181`

**Problem**: In A11y mode, `onIslandTapped()` has a code path:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    showPreference = ShowPreference.CAPSULE
    applyVisibility()
}
```

But per design, A11y should never show an island. If this code executes, it means an island was shown in A11y mode, which is already a bug (C2). This code path is either dead code or a symptom of C2.

**Suggestion**: Replace with a defensive log + return:
```kotlin
PlatformMode.ACCESSIBILITY -> {
    Log.w(TAG, "Island tapped in A11y mode — this should not happen")
    return
}
```

---

### M2: setInteractionLocked Does Not Toggle FLAG_NOT_TOUCHABLE

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:192-208`

**Problem**: `setInteractionLocked(locked=true)` expands the overlay to MATCH_PARENT and renders a touch-eating View. But with `FLAG_NOT_TOUCHABLE` permanently set (C1), the touch-eating View never receives touches. The interaction lock is thus non-functional.

**Suggestion**: When `locked=true`, also remove `FLAG_NOT_TOUCHABLE` so the touch-eating View actually blocks touches. This is prerequisite for fixing C1.

---

### M3: NavSpec Shows ⊖ in A11y (via hasIsland=true)

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:173-178`

**Problem**: `NavSpec.from()` shows ⊖ when `hasIsland && context != MAIN_APP && mode not in {WI, WA, Error, Done}`. The `hasIsland` parameter is set to `statusIslandManager != null` (`ServiceOverlayController.kt:340`), which is always true because `IslandOverlayHost` is always created.

In A11y + OTHER_APP (SCREEN_VIEWING context), this means ⊖ would render if the overlay capsule were interactive (blocked by C1 currently, but would become visible after C1 fix).

Design spec says: A11y should have no nav buttons (no ⊖, 📱, 👁).

**Suggestion**: Either:
1. Pass `hasIsland = statusIslandManager != null && platformMode == PlatformMode.VIRTUAL_DISPLAY`, or
2. Add `platformMode != PlatformMode.ACCESSIBILITY` guard to showMinimize in NavSpec

---

### M4: Glow Shows in A11y Even During Non-Active Terminal States

**File**: `app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:102`

**Problem**: A11y glow rule is `location != MAIN_APP && isActive`. `isActive` includes Done and Error. So glow shows during Done (teal) and Error (red) in A11y mode. The glow hides independently via its own auto-hide timer for Success state (2s) but has no auto-hide for Error.

This means in A11y Error mode, the red glow persists until the user dismisses the error (clicks Close) — but Close is unclickable due to C1.

**Suggestion**: After fixing C1, this becomes less problematic. But consider: A11y Error glow should have a timeout or be tied to the capsule lifecycle more tightly.

---

### M5: VD Glow Only in VD_VIEWER, Not OTHER_APP

**File**: `app/src/main/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicy.kt:118`

```kotlin
showGlow = location == OverlayUserLocation.VD_VIEWER && hasActiveTask,
```

**Observation**: VD mode only shows glow when user is on VD Viewer, not when on OTHER_APP with capsule visible. The design docs in round6 show glow for A11y OTHER_APP states but don't explicitly specify VD OTHER_APP glow policy.

This may be intentional (glow on other apps is intrusive in VD mode where agent is working on a separate display), but worth confirming.

---

## Low Priority

### L1: previousMode Is Single-Writer But Not Thread-Safe

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:70`

```kotlin
var previousMode: CapsuleMode = CapsuleMode.Hidden
    private set
```

This is a plain `var`, not a `MutableStateFlow` or atomic. It's written in `setMode()` which happens on the main dispatcher via coroutine scope, but `previousMode` is read from both `CapsuleOverlayHost` (Compose recomposition thread) and `SmartCapsuleSurface` (main thread). In practice this is fine because Compose reads on the main thread, but it violates the stated threading contract.

**Suggestion**: Wrap in `MutableStateFlow` or document the main-thread-only constraint more explicitly.

---

### L2: ChatViewModel.dismissError() Accesses Singleton Directly

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:232`

```kotlin
fun dismissError() {
    AgentService.instance?.capsuleStateHolder?.onDismissError()
}
```

This bypasses the established flow of going through `ServiceOverlayController`. All other operations (stop, takeover, resume, supplement, etc.) go through session Op → event → ServiceOverlayController → CapsuleStateHolder. But `dismissError` directly calls `CapsuleStateHolder.onDismissError()`, skipping `ServiceOverlayController`.

This means `applyVisibility()` is not called by the direct path (it relies on the mode observer on line 110 to catch Hidden/Done/Error transitions). It works, but it's inconsistent.

**Suggestion**: Route through `ServiceOverlayController` for consistency, perhaps adding an `onDismissError()` method that calls `stateHolder.onDismissError()` + explicit `applyVisibility()`.

---

### L3: Island Shows "Working..." Fallback When StateHolder is Null

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/IslandOverlayHost.kt:47-51`

```kotlin
if (holder == null) {
    StatusIslandCompose(
        text = "Working...",
        dotColor = Color(0xFF2563EB),
        onClick = onExpandCapsule,
    )
}
```

If the island is shown before `startObserving()` is called, it shows static "Working..." forever. This is a defensive edge case but could confuse users if the initialization order is wrong.

**Suggestion**: Log a warning and consider not showing the island until the state holder is connected.

---

### L4: CapsuleOverlayHost Debounce Is Global, Not Per-Action

**File**: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:281`

```kotlin
private var lastButtonClickTime = 0L
private fun debounced(action: () -> Unit) {
    val now = System.currentTimeMillis()
    if (now - lastButtonClickTime < DEBOUNCE_MS) return
    lastButtonClickTime = now
    action()
}
```

All button actions share one `lastButtonClickTime`. If user clicks Takeover (debounced), then quickly clicks Stop (within 300ms), the Stop click is dropped. This is generally fine for preventing double-clicks, but the design spec says Stop should give "immediate feedback" (§18.2).

**Suggestion**: Consider separate debounce timers for primary and stop actions, or reduce debounce to 100ms for stop.

---

### L5: OverlayComposeHost Tag Parameter Not Used for Window Identification

Minor: the `tag` parameter in `OverlayComposeHost` is used for logging but not set as `WindowManager.LayoutParams.accessibilityTitle`. This would help with debugging overlay windows via `dumpsys window`.

---

## Design vs Code Gaps Summary

| Design Spec | Code Reality | Gap |
|-------------|-------------|-----|
| Overlay capsule is interactive | FLAG_NOT_TOUCHABLE permanently set | **C1 — critical** |
| A11y never shows island | Code allows it via unguarded showPreference | **C2 — medium** |
| A11y has no nav buttons (⊖/📱/👁) | ⊖ can render due to hasIsland=true | **M3 — masked by C1** |
| Interaction lock blocks touches | Touch-eating View never receives touches | **M2 — blocked by C1** |
| Row1 tap disabled in A11y | Correctly implemented (`onRow1Click = null` when A11y) | ✅ |
| Supplement: no state change | Correctly implemented (no mode/pref/location change) | ✅ |
| TaskCompleted always writes chat history | Correctly implemented (`completionSummary` with fallback) | ✅ |
| SupplementReceived writes user message | Correctly implemented | ✅ |
| callId mismatch guard | Correctly implemented | ✅ |
| Stop immediate feedback (isStopPending) | Correctly implemented | ✅ |
| Done auto-hide 3s | Correctly implemented | ✅ |
| Island text derived from mode | Correctly implemented (`modeText()`) | ✅ |
| VD task completion: no app launch | No such code exists — correctly absent | ✅ |
| Capsule + Island mutual exclusion | `deriveOverlayVisibility` enforces this | ✅ |
| MAIN_APP: no system overlays | `deriveOverlayVisibility` enforces this | ✅ |
| VD Viewer island tap: no re-launch | Direct showPref toggle, correct | ✅ |
| Single Compose Capsule component | Correctly implemented (SmartCapsuleSurface) | ✅ |
| Force CAPSULE for WI/WA/Error | Correctly implemented in normalization | ✅ |
