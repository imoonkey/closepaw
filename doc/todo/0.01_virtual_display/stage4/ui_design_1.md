# Virtual Display UI Design — Stage 4

Date: 2026-02-11

---

## The Core Insight

The user gave us their phone. They said *"do this for me."* Now we
need to disappear — work silently in the background — and only
reappear when it matters.

The agent should feel like a personal assistant who takes your phone
into the back room, gets the job done, and brings it back with
everything ready. You don't need to watch. You can if you want to.
And when it's done, the result is just *there*.

Three states. Three UX modes. Nothing more.

---

## State 1: Agent Working (Background)

**What the user sees: Almost nothing.**

A tiny floating pill — the *Status Island* — sits near the top of
the screen. It's small, non-intrusive, and alive:

- **Shape**: Small rounded pill (similar to Dynamic Island), ~160×36dp
- **Content**: Pulsing dot (color = agent state) + one-line status
  like `Opening YouTube...` or `Searching...`
- **Position**: Top center, below the status bar, inside the safe area
- **Behavior**:
  - Touch-through for everything except the pill itself
  - Tap → opens the Live View (State 2)
  - No edge glow on the real screen. None. Zero.
  - No smart capsule on the real screen. Zero.

Why no glow? Because the user's phone is *theirs* right now. The
agent is working on a *different screen*. A glowing border on a
screen the agent isn't touching is confusing, not helpful.

The Status Island replaces the full capsule+glow system entirely
when in VD mode. It is the *only* overlay on the real screen.

---

## State 2: Live View (User is Watching)

**The user tapped the Status Island. Show them what's happening.**

A full-screen activity opens showing a live mirror of the virtual
display content:

- **Content**: Real-time frames from `ImageReader` rendered into a
  `TextureView` or `SurfaceView`
- **Smart Capsule**: Rendered *inside this activity* as a Compose
  overlay, at the bottom. Shows full status, pause/resume/stop
  buttons, streaming LLM text — everything the capsule does today.
- **Edge Glow**: Rendered *inside this activity* around the preview
  frame. Color reflects agent state (active/executing/error/paused).
- **Exit gesture**: Swipe up from the bottom edge → `finish()` the
  activity. Agent keeps running. User returns to their phone.
  A subtle bottom-bar hint ("Swipe up to exit") fades in for 2s on
  entry, then disappears.

Why a separate Activity and not an overlay? Because overlays are
system windows that float *above* everything. An Activity is the
natural container for "a screen you're looking at." It participates
in the normal back-stack. It has proper lifecycle. And critically, it
doesn't pollute the user's real screen with overlay windows.

### Frame rendering

The VD already has an `ImageReader` producing frames. We add a
callback that writes frames to a `SurfaceTexture` (via `TextureView`)
in the Activity. When the Activity is not visible, no rendering
happens (the callback checks `isActive`). Zero overhead when not
watching.

### Capsule + Glow in Activity

The capsule and glow do NOT use `WindowManager.addView()` here.
They are Compose elements laid over the preview inside the Activity.
This eliminates the entire class of "overlay leaking to wrong screen"
bugs.

---

## State 3: Task Complete (Handoff)

**The agent finished. Bring the result to the user.**

When the task succeeds (`GOAL_ACHIEVED`):

1. The app that was last in foreground on the VD gets **relaunched on
   the real screen** via a normal `startActivity()` with the same
   package name. This is the "bring-to-front" moment.

2. The Status Island shows a brief success message with a check mark
   (✅ Done!) for 3 seconds, then fades out.

3. The virtual display is released.

This isn't a magical "move the app state." It's a simple, honest
relaunch. The app opens to wherever its own state puts it (which for
most apps, is exactly where the agent left it — Android's activity
restore handles this). The user sees the result.

If the task fails or is stopped by the user: skip the relaunch.
Just dismiss the Status Island and release the VD.

---

## What We're Removing

In VD mode, the following are **suppressed entirely on the real screen**:

| Component | Real Screen (VD Mode) | Live View Activity |
|---|---|---|
| Edge Glow | **Hidden** | Shown inside Activity |
| Smart Capsule | **Hidden** | Shown inside Activity |
| Action Visualizer | **Hidden** | Not needed (VD is not real screen) |
| Status Island | **Shown** (only overlay) | Hidden (Activity has capsule) |

This is the key design decision. One overlay on the real screen (the
Status Island). Everything else lives inside the Live View Activity
or doesn't exist.

---

## Interactions Summary

```
User sends task
  └→ Agent starts on VD
       └→ Real screen: Status Island appears (small pill, top center)
            ├→ User taps pill → Live View Activity opens
            │    ├→ Shows VD content live
            │    ├→ Smart Capsule at bottom (pause/resume/stop)
            │    ├→ Edge Glow around preview
            │    └→ Swipe up → back to real screen, agent continues
            │
            └→ Task completes
                 ├→ Success: relaunch last app on real screen, show ✅
                 ├→ Failure: show ❌ briefly, dismiss
                 └→ Status Island fades out after 3s
```

---

## Keyboard Bug Fix

The keyboard-on-main-screen bug has a simple cause: when the agent
focuses an editable field on the VD, the system's IME lifecycle
triggers on the default display too.

The existing mitigation (`clearInputFocusAfterSetText` in
`NodeActionPerformer`) addresses one path. But we also need:

- After any `ACTION_SET_TEXT`, explicitly dismiss the keyboard on the
  *real display* by calling `service.softInput` API or injecting
  `KEYCODE_BACK` if a soft input is detected.

The cleaner long-term fix: key-event injection for typing instead of
`ACTION_SET_TEXT`. But that's a separate scope item. The focus-clear
+ dismiss combination is the pragmatic fix for now.

---

## Visual Language

### Status Island
- Background: `#1A1A2E` with 70% opacity, rounded corners (18dp radius)
- Status dot: 8dp circle, color matches `GlowState`
- Text: 12sp, `#E0E0E0`, single line, ellipsize end
- Max width: 200dp
- Shadow: subtle drop shadow for visibility over any background
- Entry: slide down + fade in (200ms)
- Exit: slide up + fade out (200ms)

### Live View Activity
- Background: `#000000` (black, the preview fills the screen)
- Preview: scales to fit, letterboxed if aspect ratios differ
- Capsule: same design as current SmartCapsule, positioned at bottom
- Glow: same EdgeGlowView, rendered as a view around the preview
- Exit hint: "Swipe up to exit" in `#808080`, 10sp, bottom center

---

## One-liner

One small pill on your real screen. Tap to watch. Swipe to leave.
Result appears when it's done. That's it.
