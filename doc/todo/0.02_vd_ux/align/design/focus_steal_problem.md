# VD Focus Steal: Accessibility Actions Redirect IME InputConnection

Date: 2026-02-23
Status: Open
Severity: Medium (UX degradation during concurrent main-screen usage)

## Problem

When the agent executes on a Virtual Display (VD) and the user is
simultaneously typing on the main screen (display 0), the user's
keystrokes silently stop reaching their text field. Input goes to the
VD instead. The user must re-tap their text field to restore input.

## Reproduction

1. Start an agent task in VD mode (e.g., "search YouTube for X").
2. While the agent is running, switch to Gmail on the main screen.
3. Start composing an email — keyboard is visible, typing works.
4. When the agent performs a click action on a focusable element in
   the VD (e.g., YouTube's search box), keystrokes stop appearing in
   Gmail.
5. Tap the Gmail compose field again — typing resumes.

## Root Cause

The agent uses `AccessibilityNodeInfo.performAction(ACTION_CLICK)` to
click UI elements on the VD. This call chain:

```
NodeActionPerformer.performNodeClickAt(x, y)
  → AccessibilityNodeInfo.performAction(ACTION_CLICK)
    → AccessibilityManagerService
      → AccessibilityInteractionController (in target app process)
        → View.performAccessibilityAction()
          → View.performClick()
            → requestFocus()          ← focus change happens here
              → ViewRootImpl
                → InputMethodManager  ← IME InputConnection redirected
```

Android's `InputMethodManager` is system-wide. When the VD app's view
calls `requestFocus()`, IMM redirects the active `InputConnection` from
the main display's focused field to the VD's focused field. The user's
keyboard is still visible on the main screen, but keystrokes now route
to the VD.

## Why existing display isolation doesn't help

Our VD is created with these focus-related flags:

| Flag | Value | What it isolates |
|---|---|---|
| `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` | `0x800` | Input event routing (touch/key) |
| `VIRTUAL_DISPLAY_FLAG_TRUSTED` | `0x400` | Window focus policy |
| `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` | `0x8` | Content rendering |

These flags isolate the **input event pipeline** — touch events and key
events dispatched via `InputManager` respect per-display-group boundaries.

However, `AccessibilityNodeInfo.performAction()` does NOT go through the
input event pipeline. It calls directly into the target app's View
hierarchy via IPC (`AccessibilityInteractionConnection`). The resulting
`requestFocus()` → `InputMethodManager` redirect is a View-framework-level
operation that does not check display group boundaries.

This is a fundamental Android framework limitation. Per-display focus
isolation was designed for input events, not for programmatic focus
changes triggered through the accessibility API.

## Impact

- **User experience**: Intermittent. Only affects users who type on the
  main screen while the agent is executing. Keystrokes silently disappear
  (they go to the VD). Re-tapping the text field fixes it immediately.
- **Agent behavior**: No impact. Agent actions on VD work correctly.
- **Frequency**: Proportional to how often the agent clicks focusable
  elements (search boxes, text fields, buttons with focusable=true).

## Potential Mitigations

### Option A: Coordinate-tap instead of accessibility click

Replace `ClickNodeAt` → `performAction(ACTION_CLICK)` with a
"find-then-tap" pattern: use the a11y tree to locate the node's bounds,
then inject a coordinate tap via `InputManager.injectInputEvent()` with
`setDisplayId()`. Input injection respects display group isolation, so
focus stays on the main display.

**Trade-offs**:
- (+) Eliminates focus steal completely.
- (-) Less reliable than ACTION_CLICK — coordinate taps can miss if
  layout shifts between tree capture and tap execution.
- (-) Significant agent behavior change, needs thorough testing.
- (-) Some nodes may not respond to coordinate taps the same way as
  accessibility clicks (e.g., custom views with click listeners on
  parent nodes).

### Option B: Post-action focus restore

After each accessibility action, check if IME focus moved away from
display 0 and attempt to restore it. Could use
`AccessibilityNodeInfo.performAction(ACTION_FOCUS)` on the previously
focused node on display 0.

**Trade-offs**:
- (+) No change to agent behavior.
- (-) Requires tracking "which node had focus on display 0" before
  each action — complex, fragile.
- (-) Restoring focus might cause visible flicker.
- (-) Race condition: user might have intentionally moved focus.

### Option C: Accept as known limitation

Document the behavior. Most users won't type on the main screen during
agent execution. The workaround (re-tap text field) is immediate.

**Trade-offs**:
- (+) No code change, no regression risk.
- (-) Imperfect UX for concurrent usage.

## Current Decision

Option C — accepted as known limitation. Revisit if user feedback
indicates this is a frequent pain point, or if Option A ("find-then-tap")
becomes feasible as part of a broader agent action reliability improvement.
