# mobile_action

> Current deep dive for the screen-interaction tool.
> Last updated: 2026-03-06

## Purpose

`mobile_action` is the single tool for screen-targeted interactions on the real device. It owns:

- `click`
- `long_press`
- `type`
- `scroll`
- `swipe`

It does not own app launch, system buttons, task completion, or user interaction policy.

The tool contract is defined in [MobileActionTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt) and the runtime glue lives in [MobileActionInvocation.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionInvocation.kt).

## Contract

### Targeting model

For `click`, `long_press`, and targeted `type`, the tool accepts exactly one targeting method:

- `element_index`
- `text` with optional `text_index`
- `x` and `y`

This is a hard validation rule, not a preference hint. Mixed selectors are rejected.

`scroll` optionally accepts `element_index` to scope the scroll area.

`swipe` does not use semantic targeting. It requires raw `start: [x, y]` and `end: [x, y]`.

### Validation guarantees

The tool rejects:

- unknown actions
- legacy bounds selectors (`x1/y1/x2/y2`)
- partial coordinates
- negative coordinates or indices
- `text_index` without `text`

This keeps targeting single-path. The executor does not run cross-selector fallback.

## Execution architecture

`mobile_action` has three runtime layers:

1. Tool contract
2. Action executors
3. Atomic platform primitives

### 1. Tool contract

[MobileActionTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt) does four things:

- defines the schema and prompt-facing description
- validates parameters
- parses the target into a typed `Target`
- routes to the executor for the selected action

This layer should stay dumb. It should not know about Android fallback quirks.

### 2. Action executors

The executor layer is where almost all real action semantics live:

- [ClickExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt)
- [LongPressExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutor.kt)
- [ScrollExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt)
- [TypeExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt)
- [SwipeExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt)
- shared helpers:
  - [PointActionExecutorCore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt)
  - [TargetResolver.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt)
  - [PostActionAnalysis.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt)
  - [UiChangeDetector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt)
  - [ActionPriorityOrder.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt)

This is the single smart layer.

### 3. Atomic platform primitives

The platform layer should be mechanism-only:

- [AccessibilityPlatform.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt)
- [NodeActionPerformer.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt)
- [AccessibilityGestureInjector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt)
- [UIAction.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt)

It exposes atomic calls like:

- node click at point
- gesture tap at point
- node long click
- gesture long press
- set text on node
- set text on focused field
- scroll node
- swipe gesture

The platform answers "was the Android API call accepted?" The executor layer answers "did this action path achieve an observable effect?"

## Action semantics

### click

`click` uses [PointActionExecutorCore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt) with this channel order:

1. `node_action_click`
2. `gesture_tap`

For coordinate targets, node click is skipped because there is no semantic target.

For semantic targets, the runtime first resolves the requested element, then may promote it to a containing actionable row if the resolved child is not itself clickable.

Current promotion rule:

- if the resolved semantic element is not clickable and not long-clickable
- find the smallest clickable or long-clickable container that contains its bounds
- within that container, search for actionable children materially smaller than the container (< 80% area)
- pick the child closest to the original resolved point; use its center as the click point
- if no qualifying child exists, fall back to the container center

This exists because many Android lists expose text nodes as children inside a clickable row. The child-hotspot selection avoids the failure mode where `container.center` lands on a dead zone for `ACTION_CLICK` (e.g., Files app row center).

### long_press

`long_press` is structurally the same as `click`:

1. `node_action_long_click`
2. `gesture_long_press`

It also benefits from semantic target promotion.

### type

`type` has two modes:

- targeted: resolve target, try `SetTextOnNodeAt`, then optionally `TapAt -> SetTextOnFocused`
- focused: directly call `SetTextOnFocused`

The tap-to-focus fallback is disabled in VD mode because the IME may land on the wrong display.

### scroll

`scroll` accepts a content direction, not finger direction:

- `down` means reveal lower content
- `up` means reveal upper content
- `left` means reveal leftward content
- `right` means reveal rightward content

Current channel order:

1. `a11y_scroll`
2. `gesture_swipe`

If a target element is supplied and it resolves to bounds, the gesture is computed inside that area. Otherwise the whole display is used.

### swipe

`swipe` is raw precision gesture. It does not try to infer direction or semantics.

This is the escape hatch for sliders, drag-like interactions, and custom surfaces.

## Resolution and verification pipeline

For `click` and `long_press`, the pipeline is:

1. Resolve target to a point and optional semantic hint.
2. Bounds-check the resolved point against display size.
3. For semantic targets, promote non-actionable child nodes to an actionable container, then select the nearest actionable child hotspot within that container (falling back to container center if no child qualifies).
4. Dispatch the first channel.
5. Capture post-action screen state after settle delay (300ms).
6. If unchanged, retry at +500ms; if still unchanged, final retry at +1000ms (1800ms total budget).
7. If still unchanged:
   - treat that channel as a no-op
   - continue to fallback
8. If all channels are no-op or fail, return failure.

This is the critical current behavior: dispatch acceptance alone is not enough for success.

## What counts as success now

The runtime distinguishes three layers:

1. `action accepted`
2. `observable screen change`
3. `semantic task correctness`

`mobile_action` only owns the first two.

### Accepted and changed

This is the strongest action success:

- Android accepted the primitive call
- post-action capture shows the UI changed

### Accepted but unchanged

For point actions and scroll, this is now treated as channel failure, not final success.

The old failure mode was:

- `ACTION_CLICK=true`
- tool reports success
- UI did nothing

The current runtime instead falls through to the next channel, and if every channel is unchanged it returns failure.

### Unverifiable

If post-action capture fails entirely, the action may still return success with `verified=false`.

This is reserved for cases where the action probably executed but the tool could not capture enough evidence afterward.

That is a tooling limitation, not a semantic proof of success.

## What `UiChangeDetector` actually sees

[UiChangeDetector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt) computes a fingerprint from:

- element identity and order
- resource id
- class name
- text
- description
- hint text
- bounds
- enabled/selected/checked/checkable state (NOT isFocused — excluded to prevent false positives from transient focus shifts on list item click)
- range info
- keyboard visibility
- screenshot perceptual hash if the a11y tree is empty

This improves detection, but it still measures observable change, not task truth.

## Ownership boundaries

The click stack fails for three fundamentally different reasons. They must not be conflated.

### Our logic bug

This is ours if any of these are true:

- target resolution picks the wrong node
- coordinate translation is wrong
- fallback order is wrong
- clickable-ancestor promotion picks the wrong container
- overlay pass-through is broken for our own gesture path
- we declare success after a no-op

These are runtime bugs and should be fixed in code.

### Android accessibility limitation

This is platform-limited when:

- node action returns accepted but the widget ignores it
- gesture injection is accepted but the app does not respond
- a widget only responds to real user touch sequences or a different gesture shape

Even then, we still own good detection and fallback inside the reachable platform envelope.

### App-specific behavior

Some apps wire open/select/drag behavior in unusual ways. That is not automatically our bug.

But if we can detect the pattern cleanly and route to a more faithful mechanism without app-specific hacks everywhere, that is still worth implementing.

## Known hard case: Files row open (partially addressed)

As of 2026-03-06, the Files `task.html` row open regression was traced to two separate issues:

**1. Container-center dead zone** — `refinePointActionTarget()` rewriting the click point to `container.center`. Fixed (`04618f3`) by selecting the nearest actionable child hotspot within the promoted container.

**2. UiChangeDetector false positive** — `ACTION_CLICK` on RecyclerView items triggers `isFocused` state change without actually opening the file. The detector incorrectly reported "Changed". Fixed (`5ee310a`) by excluding `isFocused` from the fingerprint and extending the verify window to 1800ms.

**3. Remaining platform limitation** — `gesture_tap` (via `AccessibilityService.dispatchGesture()`) is a false success on Files RecyclerView items: accepted by the framework but produces no UI change. `node_action_click` (via `performAction(ACTION_CLICK)`) is the only reliable channel for this surface, working in both A11Y and VD modes.

Transport reliability on Files RecyclerView (API 34 emulator, `edb4acd`):

| Transport | Display 0 | VD Secondary Display |
|-----------|-----------|---------------------|
| `node_action_click` | ✅ 8/8 | ✅ (via a11y, display-agnostic) |
| `adb shell input tap` | ✅ | ❌ (secondary display routing) |
| `dispatchGesture` | ❌ false success | N/A |
| Shizuku `injectInputEvent` | ❌ false success* | ❌* |

\*Shizuku results may reflect test setup issues (ad-hoc initialization outside normal VD lifecycle) rather than a transport-level limitation. On most other app surfaces, all four transports work correctly. This is scoped to Files RecyclerView.

-> Full experiment with setup details and raw data: `doc/main/infra/tool/click_transport_experiment.md`

## Why this documentation matters

`mobile_action` is not just a schema. It is the foundation of the agent's physical reliability.

If the tool cannot clearly distinguish:

- target identity
- primitive dispatch
- observable effect

then the agent will compensate with prompt hacks and task-specific workarounds. That is the wrong layer.

The correct direction is:

- keep targeting single-path
- keep platform primitives atomic
- keep executor semantics explicit
- isolate Android/app limits with clean probes
- only add app-specific tactics after the mechanism boundary is proven
