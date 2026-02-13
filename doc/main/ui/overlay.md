# Overlay System

> Smart Capsule, Edge Glow, Status Island, Action Visualizer, and mode-aware overlay branching.
> Last updated: 2026-02-13 (Smart Capsule V2 Round 3)

## Overview

The overlay system provides visual feedback and interaction when the agent is executing tasks. All system overlays use Android's `TYPE_ACCESSIBILITY_OVERLAY` for system-wide visibility. The main app uses a Compose capsule widget instead.

### Mode-Aware Branching

`ServiceOverlayController` selects overlays based on `PlatformMode` and `CapsuleContext`:

| Mode | Context | Overlays | Rationale |
|------|---------|----------|-----------|
| `ACCESSIBILITY` | `MAIN_APP` | None (Compose capsule in-app) | User in app, SmartCapsuleCompose handles UI |
| `ACCESSIBILITY` | `SCREEN_VIEWING` | EdgeGlow + SmartCapsule + ActionVisualizer | User sees agent on same screen |
| `VIRTUAL_DISPLAY` | `MAIN_APP` | None (Compose capsule in-app) | User in app |
| `VIRTUAL_DISPLAY` | `SCREEN_VIEWING` | SmartCapsule overlay | User watching VD viewer |
| `VIRTUAL_DISPLAY` | `BACKGROUND` | StatusIsland | Compact pill on real screen |

→ See: `app/ServiceOverlayController.kt`, `ui/overlay/model/CapsuleContext.kt`

---

## Smart Capsule (V2 Round 3)

The primary UI for user-agent collaboration. Exists in two forms:
- **System overlay** (`SmartCapsuleManager`) — View-based, shown outside the main app
- **Compose widget** (`SmartCapsuleCompose`) — Compose-based, embedded in the main app

Both are driven by `CapsuleMode` from a shared `CapsuleStateHolder`.

→ See: `ui/overlay/model/CapsuleMode.kt`, `ui/overlay/CapsuleStateHolder.kt`

### Architecture

**State management:**
- **CapsuleStateHolder** — single source of truth. Holds `CapsuleMode`, `CapsuleContext`, `PlatformMode`, `isAgentMidTurn` as `StateFlow`s. Processes events and broadcasts state changes.

**Overlay (View-based) separation of concerns:**
- **SmartCapsuleManager** — pure renderer. Receives mode via `renderMode()`, handles show/hide, keyboard, callbacks.
- **SmartCapsuleRenderer** — per-mode visual rendering (row visibility, text, colors, dot animation)
- **SmartCapsuleAnimator** — window-level height expand/collapse, Done exit slide+fade
- **SmartCapsuleLayoutBuilder** — 3-row View layout construction

**Compose widget:**
- **SmartCapsuleCompose** — Compose version for main app. Same 3-row layout, same callbacks.

```kotlin
sealed interface CapsuleMode {
    data class Running(val thought: String) : CapsuleMode
    data class TakeoverPending(val lastThought: String) : CapsuleMode
    data class Takeover(val lastThought: String) : CapsuleMode
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode
    data class Done(val message: String) : CapsuleMode
    data class Error(val message: String) : CapsuleMode
    data object Hidden : CapsuleMode
}
```

State flows: `ServiceOverlayController` receives events → updates `CapsuleStateHolder` → pushes to `SmartCapsuleManager.renderMode()`. `SmartCapsuleCompose` collects `stateHolder.mode` directly via `StateFlow`.

### Thought Pipeline

Agent thoughts flow through:

1. LLM returns tool call with `agent_thought` parameter
2. `AgentTurnRunner` extracts and sanitizes the thought (≤40 chars)
3. `AgentEvent.ThoughtUpdate` emitted
4. `ServiceOverlayController` → `CapsuleStateHolder.onThoughtUpdate()` → `Running(thought)`
5. Overlay: pushed via `capsuleManager.renderMode()`. Compose: collected via `stateHolder.mode`

### Modes

| Mode | Row 1 | Row 2 | Row 3 | User Action |
|------|-------|-------|-------|-------------|
| **Running** | Blue dot, thought | [接管] [停止] + nav | [补充] input | Takeover, supplement, stop |
| **TakeoverPending** | Amber dot, "正在交接..." | [交接中] [停止] + nav | [补充] input | Wait for handoff |
| **Takeover** | Amber dot, last thought | [继续] [停止] + nav | [补充] input | Resume, supplement, stop |
| **WaitingForInput** | "💬 等待答复", question | [停止] + nav | [发送 →] input | Type answer |
| **WaitingForAction** | "✋ 操作手机", instruction | [完成] [停止] + nav | Hidden | Tap "Done" after action |
| **Done** | Teal dot, completion | Hidden | Hidden | Auto-hides 3s |
| **Error** | Red dot, error | [关闭] | Hidden | Dismiss |
| **Hidden** | Hidden | Hidden | [发送 →] input (main app only) | Send new task |

### Status Dot Colors

| Mode | Color | Hex |
|------|-------|-----|
| Running | Blue | `#2563EB` |
| TakeoverPending | Amber | `#F59E0B` |
| Takeover | Amber | `#F59E0B` |
| WaitingForInput/Action | Purple | `#7C3AED` |
| Done | Teal | `#0D9488` |
| Error | Red | `#DC2626` |

### Layout

Three-row layout built by `SmartCapsuleLayoutBuilder`:

```
┌──────────────────────────────────────────┐
│ [●] Thought text...                      │  ← Row 1: status dot + thought
│──────────────────────────────────────────│
│ [✋ 接管] [⏹ 停止]          [⊖] [📱] [👁]│  ← Row 2: controls + nav icons
│──────────────────────────────────────────│
│ [有想法? 补充一下...          ] [💬 补充] │  ← Row 3: input + action button
└──────────────────────────────────────────┘
```

**Expanded body** (WaitingForInput, WaitingForAction) appears below Row 1 showing the question/instruction text.

**Navigation icons** (Row 2, right side):
- [⊖] Minimize to island (VD mode only, not in main app)
- [📱] Open main app (not shown when already in app)
- [👁] Open VD viewer (VD mode only, not when already viewing)

**Row 3 button text** adapts per mode:
- Hidden (idle): "发送 →" — sends new task
- Running/Takeover: "补充" — sends supplement
- WaitingForInput: "发送 →" — sends response

### ask_user Polish (Round 2)

- **4-minute nudge** — After 4 minutes in WaitingFor* states, appends "还在等待您的回复..." to body text
- **Context-aware supplement confirmation** — "✓ 已收到" (between turns) vs "✓ 已收到，下一步生效" (mid-turn)
- **VD mode fix** — In VIRTUAL_DISPLAY mode, `ask_user` shows full SmartCapsule overlay so user can type/tap; capsule hides after response

### State Transition Animations (Round 2)

| Transition | Animation | Duration |
|------------|-----------|----------|
| Running ↔ Takeover | Dot color crossfade (blue ↔ amber) | 200ms |
| Compact → WaitingFor* | Height expand + content fade-in | 250ms |
| WaitingFor* → Running | Height collapse | 200ms |
| Done → Hidden | Slide down 16dp + fade out | 300ms |

→ See: `ui/overlay/SmartCapsuleAnimator.kt`

### Callbacks

`SmartCapsuleManager` and `SmartCapsuleCompose` expose callbacks wired by `ServiceOverlayController` / `ChatScreen`:

| Callback | Triggered By | Dispatches |
|----------|--------------|------------|
| `onTakeover` | User requests takeover | `stateHolder.onTakeoverRequested()` → `Op.Takeover` |
| `onResume` | User taps "继续" in Takeover | `Op.Resume` |
| `onSupplement` | User sends supplement text | `Op.Supplement(text)` |
| `onUserResponse` | User answers ask_user | `stateHolder.onUserResponseSent()` → `Op.UserResponse(callId, response)` |
| `onStop` | User taps Stop | `Op.Shutdown` |
| `onOpenApp` | User taps app icon | Opens main activity |
| `onDismissError` | User dismisses error | `stateHolder.onDismissError()` |
| `onDoneAutoHide` | Done state auto-hides after 3s | `stateHolder.onDoneAutoHide()` |
| `onMinimize` | Nav [⊖] tapped | Hides capsule, shows island |
| `onOpenViewer` | Nav [👁] tapped | Launches VD viewer activity |

### Integration

→ See: `app/ServiceOverlayController.kt`, `app/AgentService.kt`

**Overlay flow:** `AgentSession` → `AgentEvent` → `AgentService.handleEvent()` → `ServiceOverlayController` → `CapsuleStateHolder` → `capsuleManager.renderMode()`

**Compose flow:** `CapsuleStateHolder.mode` collected via `StateFlow` in `ChatScreen` → `SmartCapsuleCompose` renders directly

---

## Edge Glow

Ambient visual feedback showing the agent is actively controlling the device.

### Features

- **Full-screen edge glow** with gradient fade from edges
- **State-based colors** matching agent execution phases
- **Pulse animation** when active or executing
- **Touch pass-through** (doesn't block interaction)
- **Display cutout handling** for notched devices
- **Auto-hide** after success state (2 seconds)

### Glow States

| State | Color | Hex | Behavior |
|-------|-------|-----|----------|
| **Active** | Primary Blue | `#2563EB` | Pulsing animation |
| **Executing** | Light Blue | `#3B82F6` | Pulsing animation |
| **Success** | Teal | `#0D9488` | Static, auto-hides after 2s |
| **Error** | Red | `#DC2626` | Static |
| **Paused** | Amber | `#F59E0B` | Static |

### Visibility Control

The edge glow is only visible when the main app is **not** in the foreground:

```kotlin
if (!isAppInForeground && shouldShowGlow) {
    edgeGlowManager?.show(currentGlowState)
} else {
    edgeGlowManager?.hide()
}
```

### Integration

→ See: `ui/overlay/EdgeGlowManager.kt`

```kotlin
edgeGlowManager?.show(GlowState.Active)
edgeGlowManager?.updateState(GlowState.Executing)
edgeGlowManager?.updateState(GlowState.Success)  // Auto-hides
edgeGlowManager?.hide()
```

---

## Status Island (VD Mode)

→ See: `ui/overlay/StatusIslandManager.kt`

Compact floating pill overlay displayed on the **real screen** during virtual display mode when the user is in `BACKGROUND` context (not viewing VD viewer or main app).

### Features

- **Status dot**: Color-coded (thinking, acting, success, error, paused)
- **Tap**: Expands to full Smart Capsule overlay (calls `onExpandCapsule`)
- **Compact**: Small floating pill that doesn't interfere with real-screen usage

### States

| State | Color | Hex |
|-------|-------|-----|
| Thinking | Blue | `#2563EB` |
| Acting | Light Blue | `#3B82F6` |
| Success | Teal | `#0D9488` |
| Error | Red | `#DC2626` |
| Paused | Amber | `#F59E0B` |

### Integration

Driven by `ServiceOverlayController` in `VIRTUAL_DISPLAY` mode. When the island is tapped, `ServiceOverlayController.onIslandTapped()` hides the island, sets context to `SCREEN_VIEWING`, updates nav buttons, and shows the full Smart Capsule overlay.

---

## Action Visualizer

Visual feedback when the agent performs touch actions.

### Features

- **Ripple effect** for tap/click actions
- **Trail animation** for swipe/scroll actions
- **Non-intrusive** - passes all touch events through
- **Automatic cleanup** after animation completes
- **Color-coded** actions

### Visualization Types

#### Click Ripple

→ See: `ui/overlay/visualizer/ClickRippleView.kt`

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | EaseOut |
| Click color | Blue (`#2563EB`) at 60% opacity |
| Long press color | Purple (`#7C3AED`) at 60% opacity |

#### Swipe Trail

→ See: `ui/overlay/visualizer/SwipeTrailView.kt`

| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot radius | 8dp |
| End dot radius | 6dp |
| Swipe color | Light Blue (`#3B82F6`) at 50% opacity |
| Scroll color | Indigo (`#6366F1`) at 50% opacity |

### Integration

→ See: `ui/overlay/visualizer/ActionVisualizerManager.kt`

Called from `AccessibilityPlatform`:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }
    
    private suspend fun performSwipe(...): ActionResult {
        visualizer?.showSwipe(startX, startY, endX, endY, durationMs)
        // ... dispatch gesture
    }
}
```

### API

```kotlin
class ActionVisualizerManager(context: AccessibilityService) {
    var enabled: Boolean
    fun showClick(x: Float, y: Float, longPress: Boolean = false)
    fun showSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    fun showScrollAsSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    fun clearAll()
    fun dispose()
}
```

---

## Z-Order

### Accessibility Mode (SCREEN_VIEWING)

Edge glow added **before** SmartCapsule so it renders below:

```
Screen
  └── EdgeGlowView (bottom)
      └── SmartCapsule (top)
          └── ActionVisualizer (topmost)
```

### Virtual Display Mode (BACKGROUND)

Only the Status Island is shown on the real screen:

```
Screen
  └── StatusIsland (single overlay)
```

### Virtual Display Mode (SCREEN_VIEWING)

SmartCapsule overlay shown atop VD viewer:

```
Screen
  └── VirtualDisplayViewerActivity
      └── SmartCapsule (overlay atop viewer)
```

### Main App (MAIN_APP)

No system overlays. SmartCapsuleCompose is embedded in the Compose hierarchy:

```
ChatScreen
  └── SmartCapsuleCompose (Scaffold.bottomBar)
```

---

## File Structure

```
ui/overlay/
├── CapsuleStateHolder.kt         # Single source of truth: CapsuleMode + CapsuleContext StateFlows
├── SmartCapsuleManager.kt        # Pure renderer for overlay (View-based), receives renderMode()
├── SmartCapsuleRenderer.kt       # Per-mode visual rendering (3-row layout)
├── SmartCapsuleAnimator.kt       # Window-level height, dot crossfade, Done exit
├── SmartCapsuleLayoutBuilder.kt  # 3-row View layout construction + nav icons
├── StatusIslandManager.kt        # VD-mode compact pill (tap → expand to capsule)
├── EdgeGlowManager.kt            # Edge glow lifecycle (A11y mode)
├── EdgeGlowView.kt               # Custom glow rendering
├── model/
│   ├── CapsuleMode.kt            # Sealed interface + isExpanded() extension
│   ├── CapsuleContext.kt          # MAIN_APP / SCREEN_VIEWING / BACKGROUND enum
│   └── GlowState.kt              # State enum with colors
└── visualizer/
    ├── ActionVisualizerManager.kt  # Visualization orchestrator
    ├── ClickRippleView.kt          # Ripple effect view
    └── SwipeTrailView.kt           # Swipe trail view

ui/capsule/
└── SmartCapsuleCompose.kt         # Compose version for main app embedding

ui/chat/
├── ChatScreen.kt                  # Hosts SmartCapsuleCompose in Scaffold.bottomBar
├── ChatViewModel.kt               # Delegates capsule actions to AgentSession
└── components/
    └── InputDock.kt               # @Deprecated, replaced by SmartCapsuleCompose

app/
├── ServiceOverlayController.kt    # Mode-aware overlay branching, owns CapsuleStateHolder
└── AgentService.kt                # Exposes capsuleStateHolder, viewer lifecycle hooks
```

---

## Related Docs

- [Style](style.md) - Color definitions
- [Tech Design](tech_design.md) - Integration details
- [Platform](../infra/platform.md) - AccessibilityPlatform integration
