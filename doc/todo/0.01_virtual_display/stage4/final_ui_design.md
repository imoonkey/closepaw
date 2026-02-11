# Virtual Display UI — Final Design

> "Simplicity is the ultimate sophistication." — Da Vinci

Date: 2026-02-11

---

## The Insight

The user gave us their phone and said *"do this for me."*

This is a trust transaction. The agent takes the phone into a back room — the **Virtual Display** — gets the job done, and brings the result back. The user's real screen stays pristine. They can peek anytime. When it's done, the result is just *there*.

Three states. Three transitions. Nothing more.

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   ① Background ──tap──▶ ② Watching ──swipe up──▶ ① Background  │
│        │                     │                                  │
│        │                     │ (task completes)                 │
│        │ (task completes)    ▼                                  │
│        └───────────────▶ ③ Handoff                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Scope

This design is about **when and where** the overlays appear — not **what they look like**.

| What changes | What does NOT change |
|---|---|
| Overlay routing: which overlays show on which screen in which mode | Smart Capsule appearance, layout, colors, buttons |
| New: Status Island (pill) on real screen in VD mode | Edge Glow appearance, colors, animations |
| New: Viewer Activity with embedded capsule + glow | Action Visualizer appearance |
| Swipe-up exit gesture in Viewer | Any overlay behavior in Accessibility mode |
| Task completion handoff | Capsule streaming text, pause/resume/stop UX |

The Smart Capsule, Edge Glow, and Action Visualizer retain their current design exactly as documented in [doc/main/ui/overlay.md](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/ui/overlay.md). Redesigning or optimizing their visual appearance is **out of scope** for this stage.

The only change is *where* they render: in the Viewer Activity (as Compose elements inside the Activity) instead of as system-window overlays — when in VD mode.

---

## State 1: Background — The Pill

**The user's phone is theirs. We show almost nothing.**

A single floating pill — the **Status Island** (conceptually the "Dynamic Island" / 灵动岛) — sits near the top of the screen. It is the *only* overlay. No edge glow. No smart capsule. No action visualizer. Nothing else touches the real screen.

### Content (left to right)

| Element | Spec |
|---|---|
| App icon | 16×16dp, the app the agent is currently operating |
| App name | 12sp, single line, max 8 chars, ellipsize |
| Divider | 1dp vertical bar, `#404040` |
| Status phrase | 12sp, single line, e.g. `Searching...` / `Typing...` |
| Status dot | 8dp circle, color matches agent state |

**Why app icon + name?** The user needs to know *which app* the agent is in — not just *that* it's working. This is the qi_note requirement: "在灵动岛显示virtual display当前ai agent在操作的app".

### Visual Spec

| Property | Value |
|---|---|
| Size | ~180×36dp (auto-width, capped at 220dp) |
| Position | Top center, 8dp below status bar safe area |
| Background | `#1A1A2E` at 70% opacity |
| Corner radius | 18dp |
| Text color | `#E0E0E0` |
| Shadow | Subtle drop shadow (2dp blur, 1dp y-offset, 20% black) |
| Entry animation | Slide down + fade in, 200ms |
| Exit animation | Slide up + fade out, 200ms |

### Interactions

| Gesture | Action |
|---|---|
| **Tap** | Open the Viewer (State 2) |
| **Long press** | Expand inline controls: **⏸ Pause** / **■ Stop** — the emergency brake |

**Why long-press?** If the agent is doing something wrong, the user needs to stop it *now* — without opening a full viewer, finding a button, and tapping it. Long-press → Stop is one gesture away.

### Status Dot Color Semantics

| State | Color | Behavior |
|---|---|---|
| Thinking / Active | Blue `#2563EB` | Gentle pulse (1s cycle) |
| Executing action | Blue `#2563EB` | Solid |
| Success | Teal `#0D9488` | Solid |
| Error | Red `#DC2626` | Solid |
| Paused | Amber `#F59E0B` | Solid |

---

## State 2: Watching — The Viewer

**The user tapped the pill. Show them the agent's screen.**

A full-screen `VirtualDisplayViewerActivity` opens, showing a live mirror of the virtual display.

### Layout

```
┌──────────────────────────────────┐
│          Edge Glow (top)         │
│ ┌──────────────────────────────┐ │
│ │                              │ │
│ │                              │ │
│ │    Virtual Display Content   │ │
│ │    (SurfaceView/TextureView) │ │
│ │                              │ │
│ │                              │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
│  ┌────────────────────────────┐  │
│  │      Smart Capsule         │  │
│  │  [⏸] status text [■ Stop] │  │
│  └────────────────────────────┘  │
│          Edge Glow (bottom)      │
│     ──── (no CTA shown but available: swipe up to exit) ────   │
└──────────────────────────────────┘
```

### Key Design Decisions

**Capsule and Glow are rendered inside the Activity** — not as `WindowManager` system-window overlays. This is the single most important architectural decision. It eliminates the entire class of "overlay leaking to wrong screen" bugs *by definition*. They physically cannot appear on the real screen because they don't exist as system windows.

**Their appearance is unchanged.** The Smart Capsule looks, behaves, and animates exactly as it does today (same streaming text, same pause/resume/stop buttons, same LLM streaming delta — see [overlay.md](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/ui/overlay.md)). The only difference is the rendering target: Compose elements inside the Activity layout instead of `WindowManager.addView()`. Same for Edge Glow — same colors, same pulse, same state-based transitions — just rendered as a view around the preview frame inside the Activity.

**Background is pure black** (`#000000`). The VD content fills the screen, letterboxed if aspect ratios differ.

### Interactions

| Gesture | Action |
|---|---|
| **Swipe up** from bottom edge | `finish()` the Activity. Agent continues. User returns to their phone. Status Island reappears. |
| **Capsule buttons** | Pause / Resume / Stop / Open Agent App — identical to current behavior |

**Exit hint**: A subtle "Swipe up to exit" label (`#808080`, 10sp) fades in at bottom center for 2 seconds on entry, then disappears. First-time users need it; repeat users won't notice it.

---

## State 3: Handoff — The Grand Finale

**The agent finished. Bring the result to the user's screen.**

This is the magic moment. The agent has been working in the back room and now presents the result.

### On `GOAL_ACHIEVED`

**Two scenarios:**

#### A) User is NOT watching (Status Island visible)

1. Relaunch the last foreground app on `display 0` via `startActivity(getLaunchIntentForPackage(lastAppPackage))`.
2. Status Island shows `✅ Done!` with a green dot for 3 seconds.
3. Status Island fades out. Virtual display released.

#### B) User IS watching (Viewer open)

1. Edge glow transitions to success color (teal).
2. Capsule shows `✅ Done` for 1.5 seconds.
3. Viewer auto-dismisses.
4. Last foreground app relaunched on real screen.
5. Status Island shows `✅ Done!` briefly, then fades out.

### On failure / user stop

- Skip the relaunch entirely.
- Status Island shows `❌ Stopped` or `⚠ Failed` for 2 seconds, then fades out.
- Virtual display released.

### What "relaunch" actually means

This is an honest relaunch — `startActivity()` with the package's launch intent. It is *not* a magical task-stack migration. For most apps, Android's own activity restore puts the user right where the agent left off. For some apps, it may open to the root screen. This is acceptable, predictable behavior. The user can always see the result.

> **Why not `setLaunchDisplayId(DEFAULT_DISPLAY)` for task reparenting?**
> Because `startActivity` with a different `launchDisplayId` typically creates a *new* task on the target display, not reparenting the existing one. The "honest relaunch" is more reliable across ROMs and apps.

---

## What the Real Screen Sees (Summary Table)

| Component | Real Screen (VD Mode) | Viewer Activity |
|---|---|---|
| Status Island (Pill) | ✅ **Shown** — only overlay | ❌ Hidden |
| Edge Glow | ❌ **Hidden** | ✅ Shown (Compose in Activity) |
| Smart Capsule | ❌ **Hidden** | ✅ Shown (Compose in Activity) |
| Action Visualizer | ❌ **Hidden** | ❌ Not needed |

**This is the key contract.** One pill on the real screen. Everything else lives inside the Viewer Activity or doesn't exist.

---

## Bug Fixes

### Bug 1: Overlay Leaking to Real Screen

**Symptom**: Edge glow and smart capsule sometimes appear on the real screen during VD mode.

**Root Cause**: `ServiceOverlayController` doesn't know the current `PlatformMode`. It shows overlays whenever a task is active and the app is in background — regardless of which display the agent is operating on.

**Fix**: `ServiceOverlayController` receives `PlatformMode` at construction.
- `ACCESSIBILITY` mode: current behavior, unchanged. **No regression.**
- `VIRTUAL_DISPLAY` mode: suppress edge glow + capsule entirely on real screen. Show only the Status Island.

### Bug 2: Ghost Keyboard on Real Screen

**Symptom**: When the agent types on the VD, the soft keyboard pops up on the real screen.

**Root Cause**: `TypeExecutor` has a fallback path (Attempt 2: tap-to-focus → set text) that triggers the IME on the default display.

**Fix (two layers)**:

1. **Prevention** (primary): In VD mode, `TypeExecutor` uses `ACTION_SET_TEXT` only — no tap-to-focus. The agent writes directly to the edit field's memory. No keyboard involvement.

2. **Suppression** (safety net): After any `ACTION_SET_TEXT` on VD, proactively dismiss the keyboard on `display 0` via `InputMethodManager.hideSoftInputFromWindow()` or Shizuku shell `input keyevent 4`. Android IMEs are unpredictable across ROMs — the safety net catches edge cases.

---

## Accessibility Mode — No Regression

Everything above applies **only to `PlatformMode.VIRTUAL_DISPLAY`**.

When running in `PlatformMode.ACCESSIBILITY`:
- Edge glow on real screen: ✅ same as before
- Smart capsule on real screen: ✅ same as before
- Show when app in background, hide when app in foreground: ✅ same as before
- No Status Island (not needed — agent is on the real screen)

The mode split is a simple `when(platformMode)` at the top of `ServiceOverlayController`'s event handlers.

---

## Future Compatibility: Interactive Mode

> "在未来这个virtual display在user看它的时候, 它也可以选择接管" — qi_note

This design is **fully compatible** with a future "User Takeover" mode:

1. The Viewer Activity already contains a `SurfaceView`/`TextureView`. Attaching an `OnTouchListener` to it captures user input for injection into the VD.
2. The Hybrid Model (from `compatibility_faq.md`) — switching `VirtualDisplay.setSurface()` between `ImageReader` (background) and the Activity's `SurfaceView` (watching) — upgrades the viewer to 60fps hardware rendering when needed.
3. The capsule's button area can add a "Take Over" button in the future.

Nothing in this design blocks these enhancements.

---

## Acceptance Criteria

| # | Criterion | Measurement |
|---|---|---|
| 1 | VD running → real screen shows only the Status Island | Visual: no edge glow, no capsule on real screen |
| 2 | Status Island reflects current app (icon + name visible) | Functional |
| 3 | Tap Status Island → Viewer opens | Latency ≤ 300ms |
| 4 | Swipe up in Viewer → returns to real screen | Success rate > 99%, task not interrupted |
| 5 | Task succeeds → last app appears on real screen | Within 1 second of completion |
| 6 | Long-press Status Island → Stop available | Functional |
| 7 | A11y mode behavior unchanged | No regression in existing overlay flow |
| 8 | No keyboard appears on real screen during VD typing | Functional |
| 9 | User never sees "displayId" or any technical concept | UX cleanliness |

---

## One-liner

One small pill on your real screen. Tap to watch. Long-press to stop. Swipe to leave. Result appears when it's done. That's it.
