# Current Click Implementation Map

## Layer Inventory

The current click pipeline flows through **7 layers and 20+ files** from LLM tool call to screen tap.

```
LLM Response
    |
    v
[L1] MobileActionTool.validate()          -- tool/impl/MobileActionTool.kt (324 lines)
    |
    v
[L2] ToolRouter.execute()                 -- tool/ToolRouter.kt (364 lines)
    |   - policy check, approval gate, state machine
    |
    v
[L3] MobileActionInvocation.execute()     -- tool/impl/MobileActionInvocation.kt (57 lines)
    |   - routes to ClickExecutor / LongPressExecutor / TypeExecutor / SwipeExecutor
    |
    v
[L4] TargetResolver.resolve()             -- tool/action/TargetResolver.kt (130 lines)
    |   - ElementIndex/Text/Coordinate -> Point(x,y)
    |   - occlusion detection (isPointBlockedBySmallerClickable)
    |   - 6 candidate points with margin logic
    |
    v
[L5] ClickExecutor.execute()              -- tool/action/ClickExecutor.kt (214 lines)
    |   - fallback chain: ACTION_CLICK -> gesture tap -> jitter -> re-resolve
    |   - up to 12 attempts per click
    |   - UI change detection after each attempt
    |
    v
[L6] AccessibilityPlatform.performAction() -- platform/AccessibilityPlatform.kt (403 lines)
    |   - dispatches UIAction to NodeActionPerformer or GestureInjector
    |
    v
[L7a] NodeActionPerformer                  -- platform/NodeActionPerformer.kt (173 lines)
    |     - finds clickable node at (x,y), calls node.performAction(ACTION_CLICK)
    |
    v
[L7b] AccessibilityGestureInjector        -- platform/AccessibilityGestureInjector.kt (137 lines)
          - builds GestureDescription, calls service.dispatchGesture()
```

**Supporting files:**
- `tool/action/Target.kt` (11 lines) - sealed interface for targeting
- `tool/action/UiChangeDetector.kt` (163 lines) - FNV hash + perceptual hash
- `tool/action/ActionOutcome.kt` (27 lines) - result types
- `tool/action/ObservationBuilder.kt` (33 lines) - post-action screen -> LLM
- `platform/UIAction.kt` (94 lines) - platform-agnostic action model
- `platform/ActionResult.kt` (22 lines) - atomic action result
- `platform/AndroidPlatform.kt` (123 lines) - platform interface

**Total: ~2000+ lines of code for click path alone.**

---

## Complexity Hot Spots

### 1. TargetResolver (130 lines) - Overly Complex Candidate Generation

```kotlin
// 6 candidate points for each element
val candidatePoints = listOf(
    Point((left + right) / 2, (top + bottom) / 2),    // center
    Point((left + right) / 2, quarterY),                // upper-middle
    Point((left + right) / 2, thirdY),                  // upper-third
    Point(left + ((right - left) / 3), quarterY),       // upper-left
    Point(right - ((right - left) / 3), quarterY),      // upper-right
    Point((left + right) / 2, top + 1)                  // near top edge
)
```

Problems:
- ALL 6 points bias toward center-right. When occlusion is on the right side, none finds the free zone on the left.
- Margin calculation (10% clamped to 2-32px) adds another layer of indirection.
- Returns null (fails) if ALL 6 are blocked, even when a free zone exists.
- The occlusion check compares area sizes, which is a heuristic that can misfire (parent-child relationships, sibling overlaps).

### 2. ClickExecutor (214 lines) - Deep Retry Nesting

The fallback chain:
```
1. ACTION_CLICK at resolved point
2. Gesture tap at same point
3. (if UI unchanged) Re-resolve target from fresh a11y tree
4. ACTION_CLICK at re-resolved point
5. Gesture tap at re-resolved point
6. (if still unchanged) Jitter: 4 attempts at ±12px offsets
7-10. Jitter taps at 4 offset positions
```

Each attempt involves:
- `platform.performAction(action)` dispatch
- 300ms delay
- `platform.captureScreen()` full a11y tree re-capture
- `UiChangeDetector.compare()` hash comparison
- attempt trail string building

Problems:
- Up to 12 dispatches + 12 screen captures per click = 12 * ~300ms = ~3.6s minimum
- All 12 attempts try variations of THE SAME fundamental approach (tap near the same point)
- Jitter offset is fixed at ±12px regardless of element size or situation
- Re-resolve is the only strategy change, but it only helps if the element moved

### 3. UiChangeDetector (163 lines) - "Did It Work?" Uncertainty

- Primary: FNV-1a hash of 11 a11y element fields
- Fallback: 8x8 perceptual hash of screenshot
- Returns: `Changed`, `Unchanged`, or `Unverifiable`

Problems:
- Many valid clicks DON'T change the a11y tree (focus changes, highlight toggles, popup positioning)
- Clock/battery text changes in a11y tree cause false "Changed" results
- `Unverifiable` (no a11y tree and no screenshot) silently succeeds
- The detector drives the retry loop, but its signal is noisy

### 4. Dual Dispatch (NodeActionPerformer + GestureInjector)

Two completely different click mechanisms:
1. **Node-based**: Find AccessibilityNodeInfo at (x,y), call `performAction(ACTION_CLICK)` on the node
2. **Gesture-based**: Inject a synthetic touch event at (x,y) via `dispatchGesture()`

They have different failure modes:
- Node-based fails when no clickable node exists at the exact coordinate
- Gesture-based fails when the touch event doesn't reach the target view (system UI interception, edge effects)

The executor blindly tries both in sequence without reasoning about WHY the first one failed.

---

## Prompt Layer

The MobileActionTool schema exposed to LLM includes:
- `action: "click"` as one of 4 action types (click, long_press, type, swipe)
- Three targeting methods (element_index, text+text_index, x+y)
- The schema validation enforces exactly one targeting method

The prompt doesn't explain:
- When to use element_index vs coordinates
- What "occluded" means or how to recover
- That `system_button` is a separate tool from `mobile_action`

---

## Architectural Problems

### P1: Too Many Layers for a Conceptually Simple Operation
A click is: "tap this point on screen." The current implementation routes through 7 layers, 20+ files, and 2000+ lines to do this. Each layer adds its own error handling, logging, and abstraction that compounds complexity.

### P2: TargetResolver Mixing Concerns
TargetResolver does TWO things:
1. Resolve a target spec to coordinates (simple lookup)
2. Find a safe tap point avoiding occlusion (complex heuristic)

These should be separate. The occlusion avoidance is where most bugs live, and it shouldn't block the resolution itself.

### P3: Retry at Wrong Level
The ClickExecutor retries with jitter and re-resolution, but the REAL failures are:
- Occlusion rejection (target never reaches executor)
- Gesture at wrong coordinates entirely (edge effects)
- Wrong dispatch method (node vs gesture vs time-based hold)

Retrying with ±12px offsets doesn't address any of these root causes.

### P4: UI Change Detection Drives Retry but Is Unreliable
The detector returns false positives (clock change) and false negatives (focus change). Basing the entire retry decision on this signal causes:
- Unnecessary retries on valid clicks
- Premature success on invalid clicks
- 300ms wait + full screen capture on every single attempt (performance)

### P5: No Information Flow Between Layers
When `NodeActionPerformer` reports "No clickable node at (540,200)", this information is logged in the attempt trail but not used to inform the next strategy. The executor doesn't adapt based on WHY the attempt failed.
