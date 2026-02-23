# User Flow SOTA — Code Reality

Date: 2026-02-20
Source: Actual code analysis, not design docs.

---

## 1. Rendering Architecture

### 1.1 Three Render Hosts

| Host | Type | When Used | Container |
|------|------|-----------|-----------|
| **SmartCapsuleCompose** | In-app Compose | MAIN_APP (any platform) | `ChatScreen.kt:127` bottomBar |
| **CapsuleOverlayHost** | System overlay window | OTHER_APP, VD_VIEWER | `ServiceOverlayController.kt:62` |
| **IslandOverlayHost** | System overlay window | OTHER_APP, VD_VIEWER (when pref=ISLAND) | `ServiceOverlayController.kt:48` |

Supplementary:
- **GlowOverlayHost**: Edge glow effect, `FLAG_NOT_TOUCHABLE`, decorative only
- **ActionVisualizerManager**: Touch point visualizer, `FLAG_NOT_TOUCHABLE`

### 1.2 Shared Render Surface

Both SmartCapsuleCompose and CapsuleOverlayHost render via `SmartCapsuleSurface` (`ui/capsule/surface/SmartCapsuleSurface.kt`). Single composable, mode-driven layout:

```
Surface {
    Column {
        if (isTaskActive) {
            CapsuleRow1(spec, onClick)          // thought line + dot
            if (mode != Done) {
                [expandedBody if present]        // WaitingFor* question/instruction
                CapsuleRow2(spec, navSpec, ...)  // action buttons + nav buttons
            }
        }
        renderSpec.row3? {                       // input field + submit button
            CapsuleRow3(...)
        }
    }
}
```

### 1.3 Input Routing

`SmartCapsuleSurface.kt:142` — Row3 submit dispatches based on CapsuleMode:

| Mode | Submit Action | Button Label |
|------|--------------|-------------|
| Hidden | `onSend(text)` → starts new task | "Send →" |
| WaitingForInput | `onUserResponse(callId, text)` | "Send →" |
| Running/TakeoverPending/Takeover | `onSupplement(text)` | "Add note" |

---

## 2. State Catalog (CapsuleMode → Render)

### 2.1 CapsuleRenderSpec per Mode

All derived from `CapsuleRenderSpec.from()` at `CapsuleRenderSpec.kt:47`:

| Mode | Dot | Thought Text | ExpandedBody | Primary Btn | Stop/Close Btn | Row3 |
|------|-----|-------------|-------------|-------------|----------------|------|
| Running(t) | Blue, pulsing | t (or "Thinking...") | null | "Takeover" | "Stop" | "Got ideas? Add a note..." / "Add note" |
| TakeoverPending | Amber, static | "Handing over..." | null | "Handing over" (disabled) | "Stop" | "Got ideas? Add a note..." / "Add note" |
| Takeover(t) | Amber, static | t (alpha 0.6) | null | "Resume" | "Stop" | "Got ideas? Add a note..." / "Add note" |
| WaitingForInput(q) | null (no dot) | "💬 Awaiting response" | question body | null | "Stop" | "Type your response..." / "Send →" (auto-focus) |
| WaitingForAction(i) | null (no dot) | "✋ Action needed" | instruction body | "Done" | "Stop" | null (hidden) |
| Done(msg) | Teal, static | "✓ msg" | null | null | null | null (hidden) |
| Error(msg) | Red, static | "⚠ msg" | null | null | "Close" | null (hidden) |
| Hidden | null | "" | null | null | null | "What can I help you with?" / "Send →" |

### 2.2 isStopPending override

When `isStopPending = true`, the Stop button renders as "Stopping..." (disabled).
`CapsuleRenderSpec.kt:144`.

---

## 3. Per-Location User Flows

### 3.1 A11y + MAIN_APP

**Rendering**: Only SmartCapsuleCompose (in ChatScreen bottomBar).
**System overlays**: Always hidden (enforced by `deriveOverlayVisibility`: MAIN_APP → all false).
**Nav buttons**: None (NavSpec: MAIN_APP → no ⊖/📱/👁).
**Row1 tap**: null (A11y → `onRow1Click` is null in CapsuleOverlayHost, but in ChatScreen it's not passed).
**Input**: Always enabled.

| Mode | Visible Rows | Input |
|------|-------------|-------|
| Hidden | Row3 only | Enabled, "What can I help you with?" |
| Running | Row1 + Row2 + Row3 | Enabled |
| TakeoverPending | Row1 + Row2 + Row3 | Enabled |
| Takeover | Row1 + Row2 + Row3 | Enabled |
| WaitingForInput | Row1 + body + Row2 + Row3 | Enabled, auto-focus |
| WaitingForAction | Row1 + body + Row2 | N/A |
| Done | Row1 only (3s → Hidden) | N/A |
| Error | Row1 + Row2([Close]) | N/A |

### 3.2 A11y + OTHER_APP

**Rendering**: CapsuleOverlayHost (system overlay) + GlowOverlayHost.
**Island**: Never (A11y could have island based on current code, but design intent is no island).
**Nav buttons**: None (`PlatformMode.ACCESSIBILITY` → showApp=false, showWatch=false; `SCREEN_VIEWING` context → showMinimize depends on hasIsland which is true for VD).
**Row1 tap**: Disabled (null) — `CapsuleOverlayHost.kt:120`: `if (platform != PlatformMode.ACCESSIBILITY)`.
**Input**: Disabled during Running/TakeoverPending (`SmartCapsuleSurface.kt:82-83`). Enabled during Takeover/WaitingForInput.
**Interaction lock**: Active during Running/TakeoverPending/WaitingForInput/WaitingForAction (not Takeover). Full-screen touch-eating overlay.

| Mode | Overlay Visible | Glow | Input | Interaction Lock |
|------|----------------|------|-------|-----------------|
| Hidden | No | No | N/A | No |
| Running | Yes | Blue pulsing | **Disabled** ("Take over to type note") | **Yes** |
| TakeoverPending | Yes | Amber | **Disabled** | **Yes** |
| Takeover | Yes | Amber | **Enabled** | No |
| WaitingForInput | Yes | Amber | **Enabled + auto-focus** | **Yes** |
| WaitingForAction | Yes | Amber | N/A | **Yes** |
| Done | Yes (3s) | Teal (2s) | N/A | No |
| Error | Yes | Red | N/A | No |

### 3.3 VD + MAIN_APP

**Rendering**: Only SmartCapsuleCompose.
**System overlays**: Always hidden.
**Nav buttons**: 👁 visible (NavSpec: VD + MAIN_APP → showWatch=true). In Hidden mode, 👁 appears next to Row3 via `showOpenViewer = mode is CapsuleMode.Hidden && navSpec.showWatch` (`SmartCapsuleSurface.kt:140`).
**Row1 tap**: Not connected in main app (ChatScreen doesn't pass onRow1Click).
**Input**: Always enabled.

| Mode | Visible Rows | Nav Buttons | Input |
|------|-------------|-------------|-------|
| Hidden | Row3 + 👁 | 👁 (in Row3 area) | Enabled |
| Running | Row1 + Row2 + Row3 | 👁 | Enabled |
| TakeoverPending | Row1 + Row2 + Row3 | 👁 | Enabled |
| Takeover | Row1 + Row2 + Row3 | 👁 | Enabled |
| WaitingForInput | Row1 + body + Row2 + Row3 | 👁 | Enabled, auto-focus |
| WaitingForAction | Row1 + body + Row2 | 👁 | N/A |
| Done | Row1 only | None (Row2 hidden) | N/A |
| Error | Row1 + Row2 | 👁 | N/A |

### 3.4 VD + VD_VIEWER + ShowPreference=CAPSULE

**Rendering**: CapsuleOverlayHost over VirtualDisplayViewerActivity.
**Nav buttons**: ⊖ + 📱 (no 👁, context=SCREEN_VIEWING). ⊖ hidden in WI/WA/Error modes.
**Row1 tap**: Opens Main App (`onOpenApp`).
**Input**: Always enabled (VD mode, no focus conflict).
**Glow**: Active during hasActiveTask.

| Mode | Overlay | Nav Buttons | Input | Glow |
|------|---------|-------------|-------|------|
| Hidden | No | N/A | N/A | No |
| Running | Yes | ⊖ 📱 | Enabled | Blue |
| TakeoverPending | Yes | ⊖ 📱 | Enabled | Amber |
| Takeover | Yes | ⊖ 📱 | Enabled | Amber |
| WaitingForInput | Yes | 📱 | Enabled, auto-focus | Amber |
| WaitingForAction | Yes | 📱 | N/A | Amber |
| Done | Yes (3s) | None | N/A | No |
| Error | Yes | 📱 | N/A | No |

### 3.5 VD + VD_VIEWER + ShowPreference=ISLAND

**Rendering**: IslandOverlayHost (compact pill at top).
**Force-CAPSULE**: WaitingForInput, WaitingForAction, Error → forced to CAPSULE → becomes 3.4.

| Mode | Island Visible | Island Text | Force to CAPSULE? |
|------|---------------|-------------|-------------------|
| Hidden | No | — | No |
| Running | Yes | thought (max 24 chars) | No |
| TakeoverPending | Yes | "Handing over..." | No |
| Takeover | Yes | "Paused" | No |
| WaitingForInput | — | — | **Yes → 3.4** |
| WaitingForAction | — | — | **Yes → 3.4** |
| Done | Yes | "Done: msg..." (max 18) | No |
| Error | — | — | **Yes → 3.4** |

**Island tap**: Directly toggles `ShowPreference = CAPSULE` + `applyVisibility()` (no viewer re-launch since already on viewer). `ServiceOverlayController.kt:184`.

### 3.6 VD + OTHER_APP + ShowPreference=CAPSULE

**Rendering**: CapsuleOverlayHost on user's current screen.
**Nav buttons**: ⊖ + 📱 + 👁. ⊖ hidden in WI/WA/Error.
**Row1 tap**: Opens Main App.
**Input**: Always enabled (VD, no focus conflict with agent).
**Glow**: No (only VD_VIEWER gets glow in VD mode).

| Mode | Overlay | Nav | Input |
|------|---------|-----|-------|
| Hidden | No | N/A | N/A |
| Running | Yes | ⊖ 📱 👁 | Enabled |
| TakeoverPending | Yes | ⊖ 📱 👁 | Enabled |
| Takeover | Yes | ⊖ 📱 👁 | Enabled |
| WaitingForInput | Yes | 📱 👁 | Enabled, auto-focus |
| WaitingForAction | Yes | 📱 👁 | N/A |
| Done | Yes (3s) | None | N/A |
| Error | Yes | 📱 👁 | N/A |

### 3.7 VD + OTHER_APP + ShowPreference=ISLAND

Same as 3.5 but with different island tap behavior:
- Active task: `onOpenViewer()` → launches VirtualDisplayViewerActivity → transitions to 3.4.
- No active task: `onOpenApp()` → launches MainActivity → transitions to 3.3.

---

## 4. Critical User Action Flows

### F1: Send Message (new task)

```
MAIN_APP + Hidden
  → User types text + clicks Send
  → ChatScreen: viewModel.sendMessage(text)
  → ChatViewModel: session.submit(Op.UserInput(text))
  → Session processes → emits TaskStarted
  → AgentServiceEventHandler: overlay.onTaskStarted()
    → CapsuleStateHolder: mode → Running
    → ServiceOverlayController: showPref → CAPSULE, applyVisibility()
  → ChatEventReducer: adds User message + Agent placeholder to chat
  → Capsule: Row1+Row2+Row3 expand, "Thinking..." shown
```

### F2: Takeover

```
Running state
  → User clicks [Takeover]
  → CapsuleOverlayHost: debounced → onTakeover callback
  → ServiceOverlayController:
    → CapsuleStateHolder.onTakeoverRequested() → mode = TakeoverPending (immediate UI feedback)
    → session.submit(Op.Takeover)
  → UI: amber dot, "Handing over...", button disabled
  → Server confirms → SessionTakeover event
  → ServiceOverlayController.onSessionTakeover() → CapsuleStateHolder.onTakeoverConfirmed()
  → mode = Takeover
  → UI: amber dot, thought (alpha 0.6), [Resume] [Stop], Row3 enabled
```

### F3: Resume

```
Takeover state
  → User clicks [Resume]
  → session.submit(Op.Resume)
  → Server confirms → SessionResumed event
  → CapsuleStateHolder.onResumed() → mode = Running("Thinking...")
  → UI: blue dot, "Thinking...", [Takeover] [Stop]
```

### F4: Stop

```
Any active mode with [Stop]
  → User clicks [Stop]
  → CapsuleStateHolder.onStopRequested() → isStopPending = true
  → UI immediately: "Stopping..." (disabled) — no mode change
  → session.submit(Op.Shutdown)
  → TaskCompleted/SessionCompleted event arrives
  → Mode transitions to Done/Hidden
  → isStopPending cleared
```

### F5: Supplement (Add note)

```
Running/TakeoverPending/Takeover
  → User types text + clicks [Add note]
  → SmartCapsuleSurface: onSupplement(text)
  → session.submit(Op.Supplement(text))
  → Server echoes SupplementReceived event
  → AgentServiceEventHandler:
    → overlay.onSupplementReceived() → flash "✓ Received" on thought line
  → ChatEventReducer: adds User message to chat history
  → **No mode change, no visibility change, no ShowPreference change**
```

### F6: WaitingForInput Response

```
WaitingForInput(question, callId)
  → User types response + clicks [Send]
  → SmartCapsuleSurface: onUserResponse(callId, text)
  → CapsuleOverlayHost: debounced → onUserResponse callback
  → ServiceOverlayController:
    → CapsuleStateHolder.onUserResponseSent(callId)
      → Guard: mode is WaitingForInput/WaitingForAction + callId matches
      → mode → Running("Processing response...")
    → session.submit(Op.UserResponse(callId, text))
```

### F7: Task Completion

```
Any active mode
  → Server emits TaskCompleted(reason, result)
  → AgentServiceEventHandler: overlay.onTaskCompleted(reason, result)
  → CapsuleStateHolder.onTaskCompleted():
    → mode → Done(message) or Error(message)
    → scheduleAutoHide() for Done (3s)
  → applyVisibility() (triggered by mode observer for Done/Error)
  → ChatEventReducer: appends completion text to agent message
  → 3s later: mode → Hidden, applyVisibility() hides everything
```

### F8: VD Viewer ↔ Island Toggle

```
VD_VIEWER + CAPSULE + Running
  → User clicks ⊖ (minimize)
  → showPref → ISLAND
  → applyVisibility(): capsule hidden, island shown
  → State: island visible with Running info

  → User taps island
  → onIslandTapped(): location == VD_VIEWER
  → showPref → CAPSULE (NO viewer re-launch)
  → applyVisibility(): island hidden, capsule shown
  → Reversible, repeatable indefinitely
```

### F9: VD Background → Island → Tap → Viewer

```
VD + OTHER_APP + ISLAND + Running
  → User taps island
  → onIslandTapped(): location != VD_VIEWER, hasActiveTask
  → onOpenViewer() → launches VirtualDisplayViewerActivity
  → VDViewerActivity.onStart() → AgentService.onViewerOpened()
  → ServiceOverlayController.onViewerOpened():
    → location = VD_VIEWER
    → showPref = CAPSULE
    → updateContext() → CapsuleContext.SCREEN_VIEWING
    → applyVisibility(): island hidden, capsule shown on VD viewer
```

### F10: VD Viewer 📱 → Main App

```
VD_VIEWER + CAPSULE + Running
  → User clicks 📱
  → onOpenApp → launches MainActivity
  → handleWindowStateChanged: MAIN_APP detected
  → location = MAIN_APP
  → applyVisibility(): all system overlays hidden
  → ChatScreen renders SmartCapsuleCompose with Running state
```

### F11: Dismiss Error

```
Error state
  → User clicks [Close]
  → CapsuleStateHolder.onDismissError() → mode = Hidden
  → applyVisibility(): everything hidden
```

---

## 5. Location Transition Matrix

### A11y

| From | To | Trigger |
|------|----|---------|
| MAIN_APP | OTHER_APP | handleWindowStateChanged (different package) |
| OTHER_APP | MAIN_APP | handleWindowStateChanged (our package, not VDViewer) or onMainAppVisible() |

### VD

| From | To | Trigger | ShowPref Effect |
|------|----|---------|-----------------|
| MAIN_APP | OTHER_APP | handleWindowStateChanged | unchanged |
| MAIN_APP | VD_VIEWER | 👁 click → onOpenViewer → VDViewer lifecycle | → CAPSULE |
| VD_VIEWER | OTHER_APP | Home/back → onViewerClosed | → ISLAND |
| VD_VIEWER | MAIN_APP | 📱 click / Row1 tap → onOpenApp + handleWindowStateChanged | → ISLAND (via onViewerClosed) |
| OTHER_APP | MAIN_APP | handleWindowStateChanged or onMainAppVisible | unchanged |
| OTHER_APP | VD_VIEWER | Island tap → onOpenViewer → VDViewer lifecycle | → CAPSULE |

---

## 6. Chat History Side Effects

### Guaranteed writes:

| Event | Chat History Entry | Implementation |
|-------|-------------------|----------------|
| TaskStarted | User message (input) + Agent placeholder | `ChatEventReducer.kt:55` |
| TaskCompleted | Completion text (or "Task completed" default) | `ChatEventReducer.kt:151` → `appendCompletionToMessages()` |
| SupplementReceived | User message (supplement text) | `ChatEventReducer.kt:163` |
| MessageDelta | Streaming text to agent message | `ChatEventReducer.kt:83` |
| ActionProposed | Action card in agent message | `ChatEventReducer.kt:103` |
| ActionExecuted | Action state update | `ChatEventReducer.kt:120` |
| SessionError | Agent message marked Complete | `ChatEventReducer.kt:159` |

### completionSummary (`ChatViewModel.kt:25`)
```kotlin
result?.takeIf { it.isNotBlank() } ?: "Task completed"
```
Ensures non-null, non-blank completion text always.

---

## 7. Overlay Window Configuration

### CapsuleOverlayHost (`CapsuleOverlayHost.kt:261`)

```
Type: TYPE_ACCESSIBILITY_OVERLAY
Flags: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
Size: MATCH_PARENT × WRAP_CONTENT (or MATCH_PARENT for locked interaction)
Gravity: BOTTOM CENTER (or TOP START for locked)
```

**⚠ FLAG_NOT_TOUCHABLE is set permanently** — see Suggestions doc.

Focus toggling: `setOverlayFocusable()` toggles `FLAG_NOT_FOCUSABLE` at runtime.

### IslandOverlayHost (`IslandOverlayHost.kt:93`)

```
Type: TYPE_ACCESSIBILITY_OVERLAY
Flags: FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN
Size: WRAP_CONTENT × WRAP_CONTENT
Gravity: TOP CENTER (offset by status bar height + 4dp)
```

### GlowOverlayHost (`GlowOverlayHost.kt:145`)

```
Type: TYPE_ACCESSIBILITY_OVERLAY
Flags: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS
Size: MATCH_PARENT × MATCH_PARENT
```

---

## 8. Supplement Confirmation Flash

`CapsuleOverlayHost.flashSupplementConfirmation()` at `CapsuleOverlayHost.kt:210`:

- During mid-turn: "✓ Received, will apply next step" (2s)
- Otherwise: "✓ Received" (1.5s)
- Overrides thought line via `transientThought` StateFlow
- Only fires if capsule overlay is currently showing

---

## 9. VD Viewer Touch Passthrough

`AgentServiceViewerBridge.onViewerTouch()` at `AgentServiceViewerBridge.kt:43`:

- Only passes touch to VD when `mode is Takeover`
- Otherwise returns `true` (consumed, no VD interaction)
- Touch coordinates are translated from viewer view to VD coordinates via platform
