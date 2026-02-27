# P0: Click False Success — `node_action_click` Silently Fails

## Problem

When the agent clicks an element by index, `node_action_click` is tried first. Android's `AccessibilityNodeInfo.performAction(ACTION_CLICK)` returns `true`, so the tool reports **"Success"** — but the UI does not change. The click didn't actually work. The agent (and the platform) have no way to know the click was ineffective.

The same coordinates succeed immediately when dispatched via `gesture_tap` (`dispatchGesture` with a touch event).

## Evidence (SimpleSmsSend, round6)

| Turn | Target | Method | Tool Result | UI Changed? |
|------|--------|--------|-------------|-------------|
| 5 | element_index=3 (checkmark) | `node_action_click` | `Success: Clicked (1017,359)` | **No** |
| 7 | element_index=4 (LinearLayout) | `node_action_click` | `Success: Clicked (1038,359)` | **No** |
| 11 | element_index=3 | `node_action_click` | `Success: Clicked (1017,359)` | **No** |
| 12 | element_index=4 | `node_action_click` | `Success: Clicked (1038,359)` | **No** |
| **14** | **coordinate (1017,359)** | **`gesture_tap`** | **`Success: Tapped (1017,359)`** | **Yes** |

Turns 5 and 14 target the **exact same point (1017,359)** with opposite outcomes. The only difference is the dispatch method.

## Why This Happens

`node.performAction(ACTION_CLICK)` delivers the click via the accessibility framework — the node's `onPerformClick` handler runs. But some widgets (e.g., Simple SMS Messenger's phone number confirmation button) don't respond to `ACTION_CLICK`. They only respond to real touch events dispatched through the input pipeline.

`performAction` returns `true` because the node _accepted_ the action (it's marked `clickable`), even though the widget's actual click handler didn't fire.

## Current Code Path

```
ClickExecutor.execute()
  → PointActionExecutorCore.executePointAction()            # PointActionExecutorCore.kt:75-99
    → channels = [NODE_CLICK, GESTURE_TAP]                  # ActionPriorityOrder.kt:18
    → for channel in channels:
        → platform.performAction(channel.createAction(pt))
        → if Success → return immediately                   # line 83: returns, never tries GESTURE_TAP
        → if Failure → try next channel
```

1. `NodeActionPerformer.performNodeActionAt()` (line 203): calls `node.performAction(action)`, returns `ActionResult.Success` if `true`
2. `PointActionExecutorCore` (line 81-90): on `ActionResult.Success`, immediately returns. **Never falls through to `gesture_tap`.**
3. Post-action screen capture (line 126-132): captures the screen AFTER success is decided, but **does not compare pre vs post** to detect "no change"

## Key Files

| File | Role |
|------|------|
| `tool/action/PointActionExecutorCore.kt:75-99` | Channel fallback loop — exits on first Success |
| `tool/action/ClickExecutor.kt` | Thin wrapper, defines NODE_CLICK → GESTURE_TAP order |
| `tool/action/ActionPriorityOrder.kt:18` | Priority config: `[NODE_CLICK, GESTURE_TAP]` |
| `platform/NodeActionPerformer.kt:192-214` | Calls `node.performAction()`, returns Success if `true` |
| `platform/AccessibilityGestureInjector.kt:37-47` | `gesture_tap` via `dispatchGesture()` |

## Impact

- Agent wastes turns on clicks that report success but do nothing
- Triggers "stuck" spirals where agent retries the same element or tries adjacent elements
- SimpleSmsSend: 9 wasted turns (T5-T13) before agent accidentally discovered coordinate-based click
- Any app with widgets that don't respond to `ACTION_CLICK` will hit this

## Fix Direction

After `node_action_click` returns Success, compare the pre-action and post-action a11y tree (or a fast hash/fingerprint). If the screen did not change, treat it as a **silent failure** and fall through to `gesture_tap`.

The post-action capture already exists at `PointActionExecutorCore.kt:126-132` — it captures the screen but doesn't use it for change detection. The pre-action snapshot is available as the `snapshot` parameter passed into `executePointAction`.

Pseudocode:
```
for channel in channels:
    result = platform.performAction(channel.createAction(pt))
    if result is Success:
        postScreen = platform.captureScreen()
        if screenChanged(preSnapshot, postScreen):
            return Success(...)          // genuine success
        else:
            attemptTrail += "${channel}: success but no screen change, retrying"
            continue                     // fall through to next channel
```
