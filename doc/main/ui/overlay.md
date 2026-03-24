# Overlay System

> Smart Capsule, Edge Glow, Status Island, Action Visualizer, and mode-aware overlay branching.
> Last updated: 2026-02-20 (commit: 2493be6)

## Overview

The overlay system provides visual feedback and interaction when the agent executes tasks outside the main app. All system overlays use `TYPE_ACCESSIBILITY_OVERLAY`. The main app embeds a Compose capsule widget instead. All overlay hosts are Compose-based via `OverlayComposeHost`.

### Mode-Aware Branching

`ServiceOverlayController` selects overlays based on `PlatformMode` and `CapsuleContext`:

| Mode | Context | Overlays |
|------|---------|----------|
| `ACCESSIBILITY` | `MAIN_APP` | None (Compose capsule in-app) |
| `ACCESSIBILITY` | `SCREEN_VIEWING` | EdgeGlow + SmartCapsule + ActionVisualizer |
| `VIRTUAL_DISPLAY` | `MAIN_APP` | None (Compose capsule in-app) |
| `VIRTUAL_DISPLAY` | `SCREEN_VIEWING` | SmartCapsule overlay |
| `VIRTUAL_DISPLAY` | `BACKGROUND` | StatusIsland |

---

## Smart Capsule

The primary UI for user-agent collaboration. Two forms: system overlay (`CapsuleOverlayHost`) and Compose widget (`SmartCapsuleCompose`). Both render `SmartCapsuleSurface` driven by `CapsuleMode` from `CapsuleStateHolder`.

-> See: [capsule/architecture.md](capsule/architecture.md) for modes, state transitions, rendering spec, and callbacks.
-> See: [capsule/state_machine.md](capsule/state_machine.md) for formal state vector and transition rules.
-> See: [capsule/user_flows.md](capsule/user_flows.md) for location x platform interaction matrix.

---

## Edge Glow

> See: `ui/overlay/compose/GlowOverlayHost.kt`, `ui/overlay/compose/EdgeGlowCompose.kt`

Ambient visual feedback showing the agent is actively controlling the device. Full-screen `Canvas`-based Compose overlay with gradient glow on all four edges.

- **Four-edge gradient glow** (40dp) with state-based colors and pulse animation (800ms, alpha 0.5→0.85)
- **Touch pass-through** (`FLAG_NOT_TOUCHABLE`)
- **Auto-hide** after Success (2000ms delay, 500ms fade-out)
- **Derived state** — `deriveGlowState(CapsuleMode, TurnPhase)` eliminates parallel state management

| State | Color | Derived When |
|-------|-------|-------------|
| Active | Blue `#2563EB` | Running (non-execution phase) |
| Executing | Light Blue `#3B82F6` | Running + `EXECUTION` turn phase |
| Success | Teal `#0D9488` | Done mode |
| Error | Red `#DC2626` | Error mode |
| Paused | Amber `#F59E0B` | TakeoverPending, Takeover, WaitingFor* |

Only visible in `ACCESSIBILITY` mode, `SCREEN_VIEWING` context.

---

## Status Island (VD Mode)

> See: `ui/overlay/compose/IslandOverlayHost.kt`, `ui/overlay/compose/StatusIslandCompose.kt`

Compact floating pill on the real screen during VD `BACKGROUND` context. Shows status dot + truncated text (24 chars). Tap expands to full Smart Capsule. Position: top-center, below status bar.

---

## Action Visualizer

> See: `ui/overlay/visualizer/ActionVisualizerManager.kt`

Visual feedback for agent touch actions. Canvas-based with automatic item lifetime management. Touch pass-through.

| Type | Properties |
|------|-----------|
| **Click ripple** | 8→48dp expansion, 500ms, blue/purple at 60% opacity |
| **Swipe trail** | 4dp line + dots, gesture duration + 400ms, light blue/indigo at 50% |

API: `showClick(x, y, longPress)`, `showSwipe(...)`, `showScrollAsSwipe(...)`. Called from `AccessibilityPlatform` before dispatching gestures.

---

## Overlay Compose Infrastructure

**OverlayComposeHost** (`ui/overlay/compose/OverlayComposeHost.kt`): `ComposeView` → `WindowManager` bridge. `show(layoutParams, content)`, `hide()`, `updateLayoutParams()`, `dispose()`.

**ServiceLifecycleOwner** (`ui/overlay/compose/ServiceLifecycleOwner.kt`): `LifecycleOwner` + `SavedStateRegistryOwner` for services. Enables Compose in non-Activity contexts.

---

## Z-Order

**Accessibility (SCREEN_VIEWING):** EdgeGlow (bottom) → SmartCapsule (middle) → ActionVisualizer (top)

**VD (BACKGROUND):** StatusIsland only

**VD (SCREEN_VIEWING):** SmartCapsule atop VD viewer

**Main App:** No system overlays. `SmartCapsuleCompose` in `Scaffold.bottomBar`.

---

## File Structure

```
ui/overlay/
├── CapsuleStateHolder.kt            # Mode/context/phase StateFlows
├── compose/
│   ├── OverlayComposeHost.kt        # ComposeView → WindowManager
│   ├── ServiceLifecycleOwner.kt     # LifecycleOwner for services
│   ├── CapsuleOverlayHost.kt        # Smart Capsule system overlay
│   ├── GlowOverlayHost.kt           # Edge glow system overlay
│   ├── IslandOverlayHost.kt         # Status island (VD mode)
│   ├── VisualizerOverlayHost.kt     # Action visualizer overlay
│   ├── EdgeGlowCompose.kt           # 4-edge glow rendering
│   ├── StatusIslandCompose.kt       # Status pill composable
│   └── ActionVisualizerCompose.kt   # Click/swipe rendering
├── model/
│   ├── CapsuleMode.kt               # 8 modes (sealed interface)
│   ├── CapsuleContext.kt            # MAIN_APP / SCREEN_VIEWING / BACKGROUND
│   ├── CapsuleColors.kt            # Status dot colors
│   ├── CapsuleRenderSpec.kt        # Mode → visual properties + NavSpec
│   └── GlowState.kt                # Glow state enum + deriveGlowState()
└── visualizer/
    └── ActionVisualizerManager.kt   # Visualization orchestrator

ui/capsule/
├── SmartCapsuleCompose.kt           # Compose capsule for main app
└── surface/
    ├── SmartCapsuleSurface.kt       # 3-row capsule layout
    ├── SmartCapsuleSurfaceParts.kt  # Row components
    └── SmartCapsuleHostLayout.kt    # Host-level padding

app/
├── ServiceOverlayController.kt     # Mode-aware overlay branching
└── AgentService.kt                 # CapsuleStateHolder, viewer hooks
```

---

## Related Docs

- [Capsule Architecture](capsule/architecture.md) - Modes, state transitions, callbacks
- [Capsule State Machine](capsule/state_machine.md) - Formal state vector, transition rules
- [Capsule User Flows](capsule/user_flows.md) - Location x platform interaction matrix
- [Style](style.md) - Color definitions
- [Platform](../infra/platform.md) - AccessibilityPlatform integration
