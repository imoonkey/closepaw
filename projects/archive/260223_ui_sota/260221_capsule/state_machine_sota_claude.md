# State Machine SOTA — Code Reality

Date: 2026-02-20
Source: Actual code analysis, not design docs.

---

## 1. State Dimensions (4-tuple)

The UI at any moment is determined by four independent state dimensions:

| # | Dimension | Type | Location | Values |
|---|-----------|------|----------|--------|
| 1 | `PlatformMode` | enum | `protocol/SessionConfig.kt:62` | `ACCESSIBILITY`, `VIRTUAL_DISPLAY` |
| 2 | `OverlayUserLocation` | enum | `app/OverlayLocationPolicy.kt:8` | `MAIN_APP`, `VD_VIEWER`, `OTHER_APP` |
| 3 | `CapsuleMode` | sealed interface | `ui/overlay/model/CapsuleMode.kt:10` | Hidden, Running, TakeoverPending, Takeover, WaitingForInput, WaitingForAction, Done, Error |
| 4 | `ShowPreference` | enum | `app/OverlayLocationPolicy.kt:14` | `CAPSULE`, `ISLAND` |

### Ownership

- **CapsuleMode** + **TurnPhase** + **isStopPending**: owned by `CapsuleStateHolder` (`ui/overlay/CapsuleStateHolder.kt:35`)
- **PlatformMode** + **OverlayUserLocation** + **ShowPreference**: owned by `ServiceOverlayController` (`app/ServiceOverlayController.kt:34`) as private vars
- **CapsuleContext**: derived from PlatformMode + OverlayUserLocation via `resolveCapsuleContext()` (`app/OverlayLocationPolicy.kt:56`)

### Derived States (not independently stateful)

| Derived value | Source | Location |
|---------------|--------|----------|
| `CapsuleContext` | PlatformMode × OverlayUserLocation | `OverlayLocationPolicy.kt:56` |
| `GlowState` | CapsuleMode × TurnPhase | `ui/overlay/model/GlowState.kt:25` |
| `CapsuleRenderSpec` | CapsuleMode × previousMode × isStopPending | `ui/overlay/model/CapsuleRenderSpec.kt:47` |
| `NavSpec` | CapsuleContext × PlatformMode × hasIsland × CapsuleMode | `ui/overlay/model/CapsuleRenderSpec.kt:162` |
| `hasActiveTask` | CapsuleMode | `CapsuleStateHolder.kt:77` |
| `isActive` (visibility) | hasActiveTask ∨ Done ∨ Error | `OverlayLocationPolicy.kt:86` |

---

## 2. CapsuleMode State Machine

### 2.1 States

```
sealed interface CapsuleMode {
    data class Running(val thought: String)
    data class TakeoverPending(val lastThought: String)
    data class Takeover(val lastThought: String)
    data class WaitingForInput(val question: String, val callId: String)
    data class WaitingForAction(val instruction: String, val callId: String)
    data class Done(val message: String)
    data class Error(val message: String)
    data object Hidden
}
```

### 2.2 Transitions — Universal Events (any source mode)

These can fire from any mode. Designed to handle server race conditions.

| Event | Handler | Target Mode | Side Effects |
|-------|---------|-------------|--------------|
| `onTaskStarted(taskId, input)` | `CapsuleStateHolder.kt:104` | `Running(sanitize(input))` | Cancel auto-hide; clear isStopPending; clear turnPhase |
| `onError(message)` | `CapsuleStateHolder.kt:111` | `Error(sanitize(message))` | Cancel auto-hide; clear isStopPending |
| `onAskUser(QUESTION, msg, callId)` | `CapsuleStateHolder.kt:117` | `WaitingForInput(msg, callId)` | — |
| `onAskUser(ACTION, msg, callId)` | `CapsuleStateHolder.kt:117` | `WaitingForAction(msg, callId)` | — |

### 2.3 Transitions — Guarded Events (specific source modes)

Invalid source mode → silent ignore + debug log.

| Event | Valid Source | Target Mode | Guard Logic |
|-------|-------------|-------------|-------------|
| `onThoughtUpdate(t)` | Running | Running(t) | `CapsuleStateHolder.kt:132` |
| `onTakeoverRequested()` | Running | TakeoverPending(thought) | `CapsuleStateHolder.kt:137` |
| `onTakeoverConfirmed()` | Running, TakeoverPending | Takeover(thought) | `CapsuleStateHolder.kt:145` |
| `onResumed()` | Takeover, TakeoverPending | Running("Thinking...") | Also clears turnPhase + isAgentMidTurn. `CapsuleStateHolder.kt:157` |
| `onUserResponseSent(callId)` | WaitingForInput, WaitingForAction | Running("Processing response...") | **callId must match**. Mismatch → returns false, no transition. `CapsuleStateHolder.kt:168` |
| `onDismissError()` | Error | Hidden | `CapsuleStateHolder.kt:256` |

### 2.4 Task Completion (complex branching)

`onTaskCompleted(reason, message)` — `CapsuleStateHolder.kt:205`

Guard: ignores if already Hidden/Done/Error.

| CompletionReason | Target Mode | Auto-hide? |
|-----|-----|-----|
| GOAL_ACHIEVED | Done(message ?: "Task completed") | 3s → Hidden |
| MAX_TURNS | Done("Max steps reached") | 3s → Hidden |
| TASK_IMPOSSIBLE | Done("Task impossible") | 3s → Hidden |
| USER_STOPPED | Done("Stopped") | 3s → Hidden |
| INTERRUPTED | Done("Interrupted") | 3s → Hidden |
| ERROR | Error("Error occurred") | No auto-hide |

### 2.5 Session End (distinct from task completion)

`onSessionEnded(reason)` — `CapsuleStateHolder.kt:227`

| CompletionReason | Target Mode | Notes |
|-----|-----|-----|
| GOAL_ACHIEVED | Done(preserve current message) | 3s auto-hide |
| MAX_TURNS | Done("Max steps reached") | 3s auto-hide |
| TASK_IMPOSSIBLE | Done("Task impossible") | 3s auto-hide |
| USER_STOPPED | Hidden | Immediate hide |
| INTERRUPTED | Hidden | Immediate hide |
| ERROR | Error("Error occurred") | Only if not already Error |

### 2.6 Transient Flags (not CapsuleMode states)

**isStopPending** (`CapsuleStateHolder.kt:62`):
- Set: `onStopRequested()` — validates mode has Stop action, returns false if already pending
- Cleared: any terminal event (onTaskStarted, onError, onTaskCompleted, onSessionEnded, onDismissError)
- Effect: `CapsuleRenderSpec.stopButtonSpec()` renders "Stopping..." disabled

**isAgentMidTurn** (`CapsuleStateHolder.kt:57`):
- Set/cleared by `setAgentMidTurn()` via TurnPhaseChanged events
- Effect: supplement flash text ("Received, will apply next step" vs "Received")

**previousMode** (`CapsuleStateHolder.kt:70`):
- Updated on every `setMode()` call
- Effect: `CapsuleRenderSpec.from()` uses it to decide `clearInput` on WaitingForInput transition

---

## 3. ShowPreference State Machine

Owned by `ServiceOverlayController` (private var), initial value: `ISLAND`.

| Event | New ShowPreference | Location |
|---|---|---|
| `onViewerOpened()` | CAPSULE | `ServiceOverlayController.kt:196` |
| `onViewerClosed()` | ISLAND | `ServiceOverlayController.kt:203` |
| `onMinimize (⊖ click)` | ISLAND | `ServiceOverlayController.kt:91` |
| `onIslandTapped()` (VD_VIEWER) | CAPSULE | `ServiceOverlayController.kt:184` |
| `onIslandTapped()` (OTHER_APP, VD) | Opens viewer → CAPSULE (via `onViewerOpened`) | `ServiceOverlayController.kt:188` |
| `onIslandTapped()` (A11y) | CAPSULE | `ServiceOverlayController.kt:180` |
| `onTaskStarted()` | CAPSULE | `ServiceOverlayController.kt:245` |
| `onSessionError()` | CAPSULE | `ServiceOverlayController.kt:275` |
| `onAskUser()` | CAPSULE | `ServiceOverlayController.kt:304` |

**Force-CAPSULE normalization** in `deriveOverlayVisibility()` (`OverlayLocationPolicy.kt:87`):
- WaitingForInput → force CAPSULE
- WaitingForAction → force CAPSULE
- Error → force CAPSULE

---

## 4. OverlayUserLocation State Machine

Owned by `ServiceOverlayController` (private var), initial: `MAIN_APP`.

| Trigger | New Location | Logic |
|---------|-------------|-------|
| `handleWindowStateChanged(pkg, cls, displayId)` | Resolved by `resolveUserLocation()` | `OverlayLocationPolicy.kt:35` |
| `onViewerOpened()` | VD_VIEWER | `ServiceOverlayController.kt:196` |
| `onViewerClosed()` | OTHER_APP (if was VD_VIEWER) | `ServiceOverlayController.kt:203` |
| `onMainAppVisible()` | MAIN_APP | `ServiceOverlayController.kt:233` |

### resolveUserLocation logic (`OverlayLocationPolicy.kt:35`)

```
if className is not an activity window → null (ignore)
if displayId != DEFAULT_DISPLAY → null (ignore VD app windows)
if packageName != ourPackage → OTHER_APP
if className contains "VirtualDisplayViewerActivity" → VD_VIEWER
else → MAIN_APP
```

---

## 5. Visibility Decision Engine

### 5.1 `deriveOverlayVisibility()` (`OverlayLocationPolicy.kt:79`)

Pure function. Inputs: platformMode, location, mode, hasActiveTask, showPreference.

**A11y mode:**
- `isOverlayContext = location != MAIN_APP && isActive`
- Capsule: `isOverlayContext && normalizedShowPreference == CAPSULE`
- Island: `isOverlayContext && normalizedShowPreference == ISLAND`
- Glow: `location != MAIN_APP && isActive`

**VD mode:**
- `MAIN_APP || !isActive`: all hidden
- Otherwise: `showPreference` decides capsule vs island (mutually exclusive)
- Glow: only `VD_VIEWER && hasActiveTask`

### 5.2 Force CAPSULE normalization (`OverlayLocationPolicy.kt:87`)

```
WaitingForInput || WaitingForAction || Error → normalizedShowPreference = CAPSULE
```

Written back to `ServiceOverlayController.showPreference` via `decision.normalizedShowPreference`.

### 5.3 `applyVisibility()` (`ServiceOverlayController.kt:132`)

Called after every state change. Executes the decision:
1. Derives `OverlayVisibilityDecision` from `deriveOverlayVisibility()`
2. Derives `shouldLockUserInteraction()` — decides if touch passthrough is blocked
3. Shows/hides capsule, island, glow accordingly
4. **Mutual exclusion enforced**: capsule and island are never both visible

### 5.4 `shouldLockUserInteraction()` (`OverlayLocationPolicy.kt:132`)

Returns true when the agent owns the screen and user should not interfere:
- A11y + OTHER_APP + active (not Takeover, not terminal)
- VD + VD_VIEWER + active (not Takeover, not terminal)

Effect: `CapsuleOverlayHost` expands to MATCH_PARENT and adds a touch-eating View.

---

## 6. CapsuleContext Derivation (`OverlayLocationPolicy.kt:56`)

| PlatformMode | OverlayUserLocation | CapsuleContext |
|---|---|---|
| A11y | MAIN_APP | MAIN_APP |
| A11y | OTHER_APP | SCREEN_VIEWING |
| A11y | VD_VIEWER | (never occurs) |
| VD | MAIN_APP | MAIN_APP |
| VD | VD_VIEWER | SCREEN_VIEWING |
| VD | OTHER_APP | BACKGROUND |

---

## 7. NavSpec Derivation (`CapsuleRenderSpec.kt:162`)

| Button | Visible When |
|--------|-------------|
| ⊖ Minimize | hasIsland && context != MAIN_APP && mode not in {WaitingForInput, WaitingForAction, Error, Done} |
| 📱 Open App | context != MAIN_APP && platformMode != A11y && mode != Done |
| 👁 Watch | platformMode != A11y && context != SCREEN_VIEWING && mode != Done |

---

## 8. Input Focus Policy

### 8.1 Overlay Focus (CapsuleOverlayHost)

`setOverlayFocusable()` at `CapsuleOverlayHost.kt:245` toggles `FLAG_NOT_FOCUSABLE`.

Focus observer (`CapsuleOverlayHost.kt:225`):
```
shouldBeFocusable = when (mode) {
    WaitingForInput → true (always focusable for keyboard)
    Takeover → inputFocused (only when user explicitly taps input)
    else → false
}
```

### 8.2 Input enablement (SmartCapsuleSurface)

`SmartCapsuleSurface.kt:80`:
```
inputEnabled = when {
    context == MAIN_APP → true (always)
    Running + A11y → false (agent owns real screen)
    TakeoverPending + A11y → false
    else → true (VD modes, Takeover, WaitingForInput)
}
```

---

## 9. Event Flow Architecture

```
User action / Server event
    ↓
AgentSession.events (SharedFlow)
    ↓
AgentService.observeSession() → AgentServiceEventHandler.handleEvent()
    ↓
ServiceOverlayController.on*() methods
    ↓
CapsuleStateHolder (mode transitions)  +  ShowPreference mutations
    ↓
applyVisibility() → CapsuleOverlayHost / IslandOverlayHost / GlowOverlayHost
    ↓
mode StateFlow → Compose recomposition → CapsuleRenderSpec.from() → SmartCapsuleSurface render
```

Parallel path for chat UI:
```
AgentSession.events
    ↓
ChatViewModel.startEventCollection() → ChatEventReducer.handle()
    ↓
SnapshotStateList<ChatMessage> → ChatScreen recomposition
```

---

## 10. Key Invariants (enforced in code)

| # | Invariant | Enforcement |
|---|-----------|-------------|
| 1 | Capsule + Island never both visible | `deriveOverlayVisibility()` returns mutually exclusive flags |
| 2 | MAIN_APP: no system overlays | `deriveOverlayVisibility()`: MAIN_APP → all false |
| 3 | A11y: never shows island | `deriveOverlayVisibility()`: A11y shows island only when `isOverlayContext && pref==ISLAND`, but A11y init pref is ISLAND with toggle possible |
| 4 | callId mismatch guard | `CapsuleStateHolder.onUserResponseSent()` checks callId match |
| 5 | Island text derived from mode | `IslandOverlayHost.modeText()` — no separate state |
| 6 | Auto-hide Done after 3s | `CapsuleStateHolder.scheduleAutoHide()` |
| 7 | Supplement: no state change | Only flash UI + chat message append |
