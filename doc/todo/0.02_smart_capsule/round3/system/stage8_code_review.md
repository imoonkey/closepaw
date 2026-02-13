# Stage 8 Code Review — SmartCapsuleCompose Integration

**Scope**: SmartCapsuleCompose implementation and integration into main app, replacing InputDock.

**Files reviewed**:
- `SmartCapsuleCompose.kt` (new)
- `ChatScreen.kt` (modified)
- `ChatViewModel.kt` (modified)
- `ChatMessage.kt` (modified)
- `AgentService.kt` (modified)
- `InputDock.kt` (modified)

---

## 1. Correctness — UX Design Alignment

### ✅ Row visibility (matches spec)

| Condition | Row 1+2 | Row 3 |
|-----------|---------|-------|
| `Hidden` (no task) | Hidden | Visible (InputDock replacement) |
| Task active (Running, Takeover, etc.) | Visible | Visible |
| `WaitingForAction` | Visible | Hidden |
| `Done` / `Error` | Visible | Hidden |

`isTaskActive = mode !is CapsuleMode.Hidden` and `shouldShowRow3(mode)` correctly implement the design.

### [HIGH] Nav button [1] ⊖ shown in MAIN_APP when it should be hidden

**File**: `SmartCapsuleCompose.kt`  
**Lines**: 294–296

**Problem**: In MAIN_APP with VD mode, the Minimize button [1] ⊖ is shown. The spec says: *"Nav buttons: in MAIN_APP context, [2] 📱 and [1] ⊖ should be hidden (already in app)"*.

**Current code**:
```kotlin
if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
    NavIconButton(text = "⊖", onClick = { onNavigate(NavAction.MINIMIZE) })
}
```

**Fix**: Add context check so [1] is hidden when already in the app:
```kotlin
if (platformMode == PlatformMode.VIRTUAL_DISPLAY && context != CapsuleContext.MAIN_APP) {
    NavIconButton(text = "⊖", onClick = { onNavigate(NavAction.MINIMIZE) })
}
```

### ✅ [2] 📱 and [3] 👁

- [2] App: correctly hidden when `context == MAIN_APP`
- [3] Watch: correctly hidden when `platformMode == ACCESSIBILITY` or `context == SCREEN_VIEWING`

---

## 2. Architecture

### [MEDIUM] AgentService singleton access from ChatScreen

**File**: `ChatScreen.kt`  
**Lines**: 62–68

**Problem**: ChatScreen reads `AgentService.instance?.capsuleStateHolder` directly. This couples the UI to a global singleton and complicates testing.

**Assessment**: Acceptable for this app because:
- AgentService is the single accessibility service
- `instance` is cleared in `onDestroy`
- No Activity context is stored

**Recommendation**: Document this as an intentional coupling. For future refactors, consider injecting `CapsuleStateHolder` (or a provider) via a ViewModel or composition local.

### ✅ Separation of concerns

- SmartCapsuleCompose: pure UI, driven by `CapsuleMode`
- ChatViewModel: user actions (send, supplement, takeover, etc.)
- CapsuleStateHolder: single source of truth for capsule state
- AgentService: service lifecycle and overlay controller

---

## 3. State Flow & Null Safety

### [MEDIUM] Fallback flow created on every composition when stateHolder is null

**File**: `ChatScreen.kt`  
**Lines**: 63–68

**Problem**: When `AgentService.instance` is null (e.g. service not connected), the fallback is:

```kotlin
stateHolder?.mode ?: kotlinx.coroutines.flow.MutableStateFlow<CapsuleMode>(CapsuleMode.Hidden)
```

A new `MutableStateFlow` is created on each composition. That can cause unnecessary recomposition and is wasteful.

**Fix**: Use a stable fallback:

```kotlin
val fallbackMode = remember { MutableStateFlow(CapsuleMode.Hidden) }
val fallbackPlatform = remember { MutableStateFlow(PlatformMode.ACCESSIBILITY) }
val capsuleMode by (stateHolder?.mode ?: fallbackMode).collectAsStateWithLifecycle()
val capsulePlatformMode by (stateHolder?.platformMode ?: fallbackPlatform).collectAsStateWithLifecycle()
```

### ✅ Null safety when stateHolder is null

- `stateHolder?.onDismissError()` is a safe no-op when null
- Fallback flows yield `CapsuleMode.Hidden` and `PlatformMode.ACCESSIBILITY`, which are valid defaults

### [LOW] Service lifecycle vs. UI

If the service is destroyed while the app is in the foreground, `instance` becomes null and the UI switches to the fallback. No crash, but the transition could be smoother if the fallback flows were stable (see above).

---

## 4. Code Quality

### ✅ Compose usage

- `AnimatedVisibility` for Row 1+2
- `animateColorAsState` for status dot
- `remember` for `inputText`
- `collectAsStateWithLifecycle` for flows

### [LOW] Magic numbers

**File**: `SmartCapsuleCompose.kt`  
**Lines**: 169–176

Dot colors are hardcoded (`0xFF2563EB`, `0xFFF59E0B`, etc.). Consider theme-based colors or named constants for consistency.

### ✅ CapsuleMode handling

All `CapsuleMode` variants are handled in `CapsuleRow1`, `CapsuleRow2`, `CapsuleRow3`, `ExpandedBody`, and `shouldShowRow3`.

### [INFO] `animateItem()` in MessageList

**File**: `ChatScreen.kt`  
**Line**: 195

`Modifier.animateItem()` is used for list items. Ensure the correct import (e.g. `androidx.compose.foundation.animateItem`) is present and the project builds.

---

## 5. Missing Items & Edge Cases

### ✅ Action callbacks

ChatViewModel exposes and ChatScreen wires:

- `sendMessage`, `sendSupplement`, `requestTakeover`, `requestResume`, `stopTask`, `sendUserResponse`
- `onDismissError` delegates to `stateHolder?.onDismissError()`
- `onNavigate` is a no-op in MAIN_APP (correct, since nav buttons are hidden)

### ✅ Input behavior by mode

- `Hidden` → `onSend` (new message)
- `WaitingForInput` → `onUserResponse(callId, text)`
- Other active modes → `onSupplement`

### [LOW] `onNavigate` no-op

**File**: `ChatScreen.kt`  
**Line**: 129

`onNavigate = { /* In-app nav: handled at Activity level if needed */ }` is correct for MAIN_APP because nav buttons are hidden. Consider a short comment explaining that nav is only used in overlay contexts.

### ✅ Deprecation

- `InputState` and `InputDock` are correctly deprecated with clear replacement references.

---

## 6. ChatViewModel & ChatMessage Changes

### ✅ InputState removal

- `InputState` removed from `ChatUiState`
- `ChatUiState` simplified to `showEmptyState` only
- Capsule mode comes from `CapsuleStateHolder`, not ViewModel

### ✅ New methods

- `sendSupplement`, `requestTakeover`, `requestResume`, `sendUserResponse` implemented with `session.submit(Op.*)`
- `stopTask` uses `Op.Interrupt`

### ✅ Event handling

- `handleTaskStarted`, `handleTaskCompleted`, `handleError` no longer drive input state; capsule state is handled by `CapsuleStateHolder`.

---

## 7. AgentService Changes

### ✅ `capsuleStateHolder` accessor

```kotlin
val capsuleStateHolder get() = overlayController?.stateHolder
```

- Returns `null` when service not connected or overlay not initialized
- Callers handle null via `?.` and fallbacks

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 0 |
| HIGH | 1 |
| MEDIUM | 2 |
| LOW | 2 |
| INFO | 1 |

### Recommendation: **CHANGES_REQUESTED**

**Must fix before merge**:
1. **[HIGH]** Hide [1] ⊖ in MAIN_APP: add `context != CapsuleContext.MAIN_APP` to the Minimize button condition.

**Should fix**:
2. **[MEDIUM]** Use `remember` for fallback flows when `stateHolder` is null.
3. **[MEDIUM]** Document or consider injecting `CapsuleStateHolder` for testability.

**Nice to have**:
4. **[LOW]** Replace hardcoded dot colors with theme or constants.
5. **[LOW]** Add a brief comment for the `onNavigate` no-op in ChatScreen.

---

## Approval Criteria

- **Approve**: No Critical/High issues
- **Request Changes**: Any Critical or 2+ High issues

**Verdict**: 1 HIGH issue → **CHANGES_REQUESTED**. Fix the nav button visibility, then re-review.
