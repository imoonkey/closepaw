# Stage 7 Code Review: 3-Row Layout + Navigation Buttons [1][2][3]

**Scope**: Smart Capsule Round 3, Stage 7  
**Files reviewed**: SmartCapsuleLayoutBuilder.kt, SmartCapsuleRenderer.kt, SmartCapsuleManager.kt, ServiceOverlayController.kt

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 0 |
| HIGH | 2 |
| MEDIUM | 3 |
| LOW | 2 |
| INFO | 2 |

**Recommendation: CHANGES_REQUESTED** — Fix the 2 High issues before merge.

---

## 1. Correctness vs UX Design

### 1.1 Row Layout Match

The 3-row layout matches `ux_design_round3.md` Section 2:

| Row | Expected | Implemented |
|-----|----------|-------------|
| Row 1 | Status dot + thought text | ✓ `row1`, `statusDot`, `thoughtText` |
| Row 2 | [接管/继续] [停止] + [⊖] [📱] [👁] | ✓ `primaryButton`, `stopButton`, `navMinimize`, `navApp`, `navWatch` |
| Row 3 | EditText + [补充/发送] | ✓ `inputEditText`, `inputButton` |

### 1.2 Row Visibility by Mode

| Mode | Row 1 | Row 2 | Row 3 | UX Doc | Match |
|------|-------|-------|-------|--------|-------|
| Running | ✓ | ✓ | ✓ (supplement) | ✓ | ✓ |
| TakeoverPending | ✓ | ✓ | ✓ (supplement) | ✓ | ✓ |
| Takeover | ✓ | ✓ | ✓ (supplement) | ✓ | ✓ |
| WaitingForInput | ✓ | ✓ | ✓ (answer) | ✓ | ✓ |
| WaitingForAction | ✓ | ✓ | Hidden | ✓ | ✓ |
| Done | ✓ | Hidden | Hidden | ✓ | ✓ |
| Error | ✓ | ✓ ([关闭] only) | Hidden | ✓ | ✓ |
| Hidden | — | — | — | handled by manager | ✓ |

### 1.3 Row 3 Button Text

| State | UX Doc | Code |
|-------|--------|------|
| Supplement mode | [💬 补充] | ✓ "补充" |
| Answer mode | [发送 →] | "发送" (arrow omitted — acceptable) |

---

## 2. Architecture

### 2.1 Callback Routing

| Callback | Source | Destination | Status |
|----------|--------|--------------|--------|
| `onPrimary` | LayoutBuilder → Manager | `handlePrimaryClick` → `onTakeover`/`onResume`/`onUserResponse` | ✓ |
| `onStop` | LayoutBuilder → Manager | `handleStopClick` → `onStop`/`onDismissError` | ✓ |
| `onRow1Tap` | LayoutBuilder → Manager | `onOpenApp` | ✓ |
| `onRow3Submit` | LayoutBuilder → Manager | `handleRow3Submit` → `onSupplement`/`onUserResponse`/`onSend` | ✓ |
| `onMinimize` | LayoutBuilder → Manager | `onMinimize` → Controller `hideCapsuleOverlay` + `showIsland` | ✓ |
| `onNavApp` | LayoutBuilder → Manager | `onOpenApp` | ✓ |
| `onNavWatch` | LayoutBuilder → Manager | `onOpenViewer` | ✓ |

### 2.2 Separation of Concerns

- **LayoutBuilder**: Stateless view hierarchy construction. ✓
- **Renderer**: Pure visual rendering (visibility, text, colors). ✓
- **Manager**: Orchestrates show/hide, callbacks, keyboard, timers. ✓
- **Controller**: Event routing, state holder, platform mode. ✓

---

## HIGH

### [HIGH] CapsuleContext never updated — nav buttons always wrong in overlay

**File**: `ServiceOverlayController.kt`, `CapsuleStateHolder.kt`  
**Lines**: `updateNavContext` uses `stateHolder.context.value`; `setContext` never called

**Problem**: `stateHolder.setContext()` is never invoked. Context remains `MAIN_APP` always. When the overlay is shown (user in another app, viewing agent screen), context should be `SCREEN_VIEWING`. As a result:
- `showApp = context != MAIN_APP` → always false → [2] 📱 never shown
- `showWatch = context == SCREEN_VIEWING → false` branch never triggers
- In A11y overlay (user in other app), [2] 📱 should be visible so user can open the app. It is hidden.

**Fix**: Sync context when overlay visibility changes. In `handleWindowStateChangedA11y`:
```kotlin
if (isAppInForeground) {
    stateHolder.setContext(CapsuleContext.MAIN_APP)
    capsuleManager.hide()
    ...
} else {
    stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
    ...
    pushModeToOverlayCapsule()
}
```
Also call `capsuleManager.updateNavContext(...)` after `setContext` so nav buttons re-render.

---

### [HIGH] configureRow3Answer clears input on every render — loses user typing

**File**: `SmartCapsuleRenderer.kt`  
**Line**: 310

**Problem**: `configureRow3Answer` calls `v.inputEditText.text?.clear()` unconditionally. It is invoked every time `renderWaitingForInput` runs. If `pushModeToOverlayCapsule` is called again while the user is typing (e.g. from a thought update or other event), the input is cleared and the user loses their text.

**Fix**: Only clear when transitioning *into* WaitingForInput from a different mode:
```kotlin
private fun configureRow3Answer(v: CapsuleViews, previousMode: CapsuleMode?) {
    if (previousMode !is CapsuleMode.WaitingForInput) {
        v.inputEditText.text?.clear()
    }
    v.inputEditText.hint = "输入你的答复..."
    v.inputButtonText.text = "发送"
}
```
Pass `previousMode` from `renderWaitingForInput`.

---

## MEDIUM

### [MEDIUM] Minimize button shown when island may not exist

**File**: `SmartCapsuleRenderer.kt`  
**Line**: 67

**Problem**: `showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY` assumes the island always exists in VD mode. If `statusIslandManager` is null (e.g. VD mode without island), tapping [1] ⊖ would hide the capsule but `showIsland()` would no-op — user loses the capsule with nothing to tap.

**Fix**: Pass an `hasIsland: Boolean` (or equivalent) into `updateNavContext` / `configureNavButtons` so `showMinimize = platformMode == VIRTUAL_DISPLAY && hasIsland`. Controller can derive `hasIsland = statusIslandManager != null`.

---

### [MEDIUM] onSend never wired — Hidden-mode Row 3 send is dead

**File**: `SmartCapsuleManager.kt`, `ServiceOverlayController.kt`  
**Lines**: 56, 256–257

**Problem**: `onSend` callback exists for `CapsuleMode.Hidden` (idle send mode) but is never set in `ServiceOverlayController`. The overlay hides when `Hidden`, so this path is currently unreachable. If Row-3-only idle overlay is added later (e.g. VD viewer with no task), `onSend` would need to be wired.

**Fix**: Either wire `onSend` in the controller (if VD idle overlay is in scope) or add a comment: `// onSend: for future Row-3-only idle overlay; not used when overlay hides on Hidden`.

---

### [MEDIUM] Duplicate divider/row visibility logic

**File**: `SmartCapsuleRenderer.kt`  
**Lines**: 96–118, 132–152, etc.

**Problem**: Each mode's render method repeats similar patterns for `row1`, `divider1`, `row2`, `divider2`, `row3` visibility. Some extraction could reduce duplication (e.g. `setRowVisibility(v, row1=true, row2=true, row3=true)`).

**Fix**: Optional refactor — extract a helper to reduce repetition. Low priority; current code is readable.

---

## LOW

### [LOW] UX doc specifies "发送 →" but code uses "发送"

**File**: `SmartCapsuleRenderer.kt`  
**Line**: 312

**Problem**: UX doc 2.6 says button label `[发送 →]` for answer mode. Code uses `"发送"`. Arrow omitted.

**Fix**: Use `"发送 →"` if space allows, or document the truncation.

---

### [LOW] Idle placeholder "有什么可以帮你?" not used in overlay

**File**: `SmartCapsuleLayoutBuilder.kt`  
**Line**: 229

**Problem**: UX doc 2.2 Idle Layout uses placeholder "有什么可以帮你?" for Row-3-only. LayoutBuilder uses "有想法? 补充一下..." as the default hint. The overlay never shows idle (Row 3 only) — it hides on Hidden. So this is only relevant for future main-app Compose widget. No change needed for overlay.

**Fix**: None for Stage 7. Note for Compose widget implementation.

---

## INFO

### [INFO] Nav button visibility rules implemented correctly (when context is fixed)

**File**: `SmartCapsuleRenderer.kt`  
**Lines**: 66–77

Once `setContext` is wired:
- [1] ⊖: Only in VD mode ✓
- [2] 📱: Never when in MAIN_APP ✓
- [3] 👁: Never in A11y; never when SCREEN_VIEWING ✓

---

### [INFO] Controller capsule/island methods are clear

**File**: `ServiceOverlayController.kt`  
**Lines**: 93–108

`showCapsuleOverlay`, `hideCapsuleOverlay`, `showIsland`, `hideIsland` provide a clean API for VD mode navigation. `onMinimize` correctly chains `hideCapsuleOverlay()` + `showIsland()`.

---

## 4. Missing Items / Edge Cases

| Item | Status |
|------|--------|
| CapsuleContext.setContext wiring | Missing — HIGH |
| Row 3 input clear on re-render | Bug — HIGH |
| VD mode setContext(SCREEN_VIEWING) / setContext(BACKGROUND) | Not in scope for overlay-only Stage 7; needed when VD viewer/island lifecycle is wired |
| Main app Row-3-only (Compose) | Out of scope — separate implementation |
| Island tap → expand (Context C) | Out of scope — StatusIslandManager |

---

## 5. Code Quality

- **Readability**: Good. Mode-specific render methods are clear.
- **Duplication**: Moderate — row visibility patterns repeated; acceptable.
- **File size**: LayoutBuilder 396 lines, Renderer 371 lines, Manager 378 lines — within 400-line guideline.
- **Null safety**: Proper use of `?.` for optional callbacks.

---

## 6. Recommendation

**CHANGES_REQUESTED**

1. **Must fix**: Wire `setContext(SCREEN_VIEWING)` / `setContext(MAIN_APP)` when overlay show/hide changes in A11y mode, and ensure `updateNavContext` is called after context changes.
2. **Must fix**: Only clear Row 3 input when transitioning into `WaitingForInput`, not on every render.
3. **Consider**: Pass `hasIsland` for minimize button; wire or document `onSend`.

---

## Checklist vs UX Design

| UX Requirement | Implemented |
|----------------|-------------|
| Row 1: dot + thought | ✓ |
| Row 2: controls + [⊖][📱][👁] | ✓ |
| Row 3: input + [补充/发送] | ✓ |
| [1] only in VD mode | ✓ (needs hasIsland for edge case) |
| [2] never in app | ✓ (needs setContext) |
| [3] never when watching; never in A11y | ✓ (needs setContext) |
| onMinimize → hide overlay + show island | ✓ |
| onNavApp → onOpenApp | ✓ |
| onNavWatch → onOpenViewer | ✓ |
