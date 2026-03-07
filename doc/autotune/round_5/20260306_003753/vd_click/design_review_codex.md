# Design Review: VD Action Priority

**Reviewer**: Codex  
**Date**: 2026-03-06  
**Reviewing**: `vd_action_priority_design.md`

## Verdict

The document identifies a real issue, but it over-claims the fix.

- I agree with the core premise that Virtual Display changes the meaning of the current `GESTURE_TAP` / `GESTURE_LONG_PRESS` channels.
- I agree that priority policy should stay centralized rather than be pushed down into every platform implementation.
- I do **not** think the current evidence is strong enough to justify a blanket VD flip to `tap -> node_click` for `click`, or `gesture_long_press -> node_long_click` for `long_press`.
- I do think `a11y_scroll -> gesture_swipe` should stay unchanged.
- I think the naming issue around `GESTURE_TAP` is **not cosmetic**. It is already muddying reasoning, logs, and eval interpretation.

## 1. Option A vs Option B

## My answer

Between the two choices presented, **Option A is the right direction**, but **not exactly as written**.

## Why Option A is better

- The priority table is policy, not platform behavior. Keeping it centralized in `ActionPriorityOrder` is the cleaner ownership model.
- `Option B` makes every platform implementation own fallback policy, which is the wrong abstraction boundary for this codebase. `AndroidPlatform.performAction()` is currently atomic transport only, not orchestration policy.
- The current code already reflects that split:
  - `ActionPriorityOrder` owns the sequence today in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt:15`
  - executors build channel attempts in `ClickExecutor.kt`, `LongPressExecutor.kt`, and `ScrollExecutor.kt`
  - platform dispatch stays downstream in `AccessibilityPlatform.performAction()` and `VirtualDisplayPlatform.dispatchAction()`

Pushing a full `ActionPriority` object onto `AndroidPlatform` would blur that separation and scatter one small table across multiple files for little gain.

## What I would change in Option A

I would **not** use concrete type checks like:

- `platform is VirtualDisplayPlatform`

inside every executor as proposed in `vd_action_priority_design.md:91-93`.

That is not fatal, but it is avoidable. The current `AndroidPlatform` interface already carries small capability flags like `allowTapToFocus()` in `app/src/main/kotlin/com/moonkey/androidagent/platform/AndroidPlatform.kt:78`. The same pattern fits here.

The clean version is:

- keep priority tables centralized in `ActionPriorityOrder`
- expose one small capability on `AndroidPlatform`, such as `pointActionTransportMode()` or `directTouchInjectionMode()`
- let `ActionPriorityOrder` branch on that capability

That preserves the good part of Option A without hard-coding executor knowledge of a specific implementation class.

## Bottom line on A vs B

- **Choose Option A**
- but implement it as **centralized policy + small platform capability**
- **do not** make `AndroidPlatform` own full per-action priority lists
- **do not** hard-code `VirtualDisplayPlatform` type checks in all three executors if a one-line capability can express the same thing

## 2. Missing Edge Cases and Risks

The doc understates the risk. This is not just a low-risk list reorder.

## A. The evidence is narrower than the recommendation

The document proves this:

- accessibility `dispatchGesture()` can false-succeed on some views
- VD `injectInputEvent()` is a different transport

It does **not** prove this:

- VD tap should beat `node_click` for semantic targets in general

That gap matters. The step from "`dispatchGesture` is weak" to "`VD tap should be primary everywhere`" is not evidence-complete.

There is also conflicting round-5 evidence in nearby Files probes:

- `doc/autotune/round_5/20260306_003753/click/fundamental_fix/final/click_fundamental_fix.md:24-27`
- `doc/autotune/round_5/20260306_003753/click/fundamental_fix/final/click_fundamental_fix.md:33`

Those notes say that, in at least one clean Files state, `gesture_tap` and raw `adb tap` were weaker than `node_action_click`, not stronger. Your VD note may still be right for a different state, but the design needs to acknowledge that the data is state-sensitive.

## B. Tap-first loses semantic target safety

This is the biggest design omission.

For semantic clicks, the node path carries target identity:

- `UIAction.ClickNodeAt` includes `semanticHint` in `app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt:34-38`
- `ClickExecutor` passes that hint on the node channel in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt:26-29`
- the tap channel drops it in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt:30-33`

So `node_click` is not just "another transport". It is also the only path that preserves semantic identity checking.

That means a blanket VD flip is trading:

- more physical fidelity

for:

- less target identity safety

The proposal does not discuss that tradeoff, and it should.

## C. Tap-first increases duplicate-side-effect risk when verification is weak

The point-action core retries the next channel if the first channel reports success but post-action analysis still says "unchanged":

- loop and fallback logic in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt:86-123`
- post-action change gating in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt:150-180`

That creates a real risk:

- first channel actually worked
- post-action capture lagged or missed the effect
- fallback channel fires too

This risk already exists, but tap-first makes it more concerning because injected touch is the most side-effectful path. A true physical tap followed by semantic fallback can produce duplicate opens, double activation, or state churn.

The design should call this out explicitly and treat it as a validation requirement, not "low risk".

## D. Long press is riskier than click

For `long_press`, physical injection is even more side-effectful than click:

- it can start drag behavior
- it can trigger text selection handles
- it can invoke press-and-hold behaviors that are not equivalent to `ACTION_LONG_CLICK`

The proposal flips long press on analogy alone. That is too weak. The document gives no device evidence that `node_long_click` is the weaker path in VD.

## E. The document says "tool layer knows the platform", but the sample derives it in the executor

This is a smaller issue, but it signals design looseness.

- `vd_action_priority_design.md:81` says executors receive mode from the tool layer
- `vd_action_priority_design.md:91-93` actually derives mode from `platform is VirtualDisplayPlatform`

Pick one design. Do not leave ownership of "what mode am I in?" ambiguous.

## F. Telemetry / eval continuity is not addressed

Changing priority without cleaning up channel names will contaminate comparisons across:

- attempt trails
- debug traces
- future eval summaries
- regression analysis docs

If the logs still say `gesture_tap` in VD mode, later reviewers will continue drawing the wrong causal conclusions.

## 3. Is the Proposed VD Priority Order Correct?

## Click: `tap -> node_click`

My answer: **not yet proven, and probably too broad as stated**.

Why I am pushing back:

- The evidence in the doc shows that VD tap is stronger than accessibility `dispatchGesture`, not that it is stronger than semantic `node_click`.
- In the current code, semantic `node_click` is the only channel that preserves `semanticHint`.
- Nearby round-5 Files evidence shows some states where raw tap / `adb tap` are weaker than `node_action_click`.

If you force me to pick today, I would **not** ship a blanket global VD `tap -> node_click` order.

I would choose one of these instead:

1. Keep `node_click -> tap` for semantic targets, keep tap-only for coordinate targets.
2. Add a narrowly scoped experiment flag for VD tap-first and validate it on a real task set before making it default.
3. If you insist on one VD-wide order, require evidence across multiple surfaces first, not just one Files scenario.

My strongest recommendation is option 1. It matches the actual asymmetry in the code:

- semantic targets benefit from node identity
- coordinate targets already skip node click

That is the simplest rule that respects both mechanisms.

## Long press: `gesture_long_press -> node_long_click`

My answer: **no, I would not flip this based on the current evidence**.

This proposal is even less justified than click.

- No concrete failure evidence is shown for `node_long_click`.
- Injected long press is more behaviorally dangerous than injected tap.
- Semantic long-press targets benefit from the same identity-preserving node path as click.

My default would stay:

- `node_long_click -> long_press`

for semantic targets, with physical long press as fallback.

## Scroll: keep `a11y_scroll -> gesture_swipe`

My answer: **yes, keep it**.

This is the most defensible part of the document.

- `ScrollNodeAt` is semantic and precise.
- It is less timing-sensitive than a physical swipe.
- It is already a good first attempt for a11y-visible scroll containers.
- Fallback to swipe is still necessary for custom surfaces and non-scrollable-node cases.

I would not change scroll ordering unless new evidence shows VD gesture swipe materially outperforms node scroll on real tasks.

## My net assessment of the proposed ordering

- `click`: not proven enough for blanket flip
- `long_press`: I disagree with the flip
- `scroll`: keep as-is

So I do **not** endorse the full VD order table as written.

## 4. GESTURE_TAP Naming Ambiguity

This is a real design problem, not cosmetic cleanup.

Today the naming is already misleading:

- `ActionPriorityOrder.ClickChannel.GESTURE_TAP` in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt:26`
- executor display name `"gesture_tap"` in `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt:30-42`
- `UIAction.TapAt` comment says "Perform a gesture tap" in `app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt:40-45`
- but Accessibility and VD dispatch completely different transports:
  - `AccessibilityPlatform.performAction()` routes `TapAt` to `gestureInjector.injectTap()` in `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:268-270`
  - `VirtualDisplayPlatform.dispatchAction()` routes `TapAt` to `inputInjector.injectTap()` in `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt:299`
  - VD injection is raw `MotionEvent` synthesis in `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayInputInjector.kt:29-54`

That means the same channel label currently refers to:

- accessibility gesture dispatch in one mode
- raw input injection in another mode

That is too ambiguous for a system where debugging depends on knowing the exact transport.

## My recommendation on naming

Do one of these before or together with the behavior change:

1. Rename the intent-level channel to something transport-neutral:
   - `GESTURE_TAP` -> `POINT_TAP`
   - `GESTURE_LONG_PRESS` -> `POINT_LONG_PRESS`
2. Or split transport-level names explicitly:
   - `A11Y_GESTURE_TAP`
   - `INPUT_TAP`

I prefer option 1 at the executor policy layer, plus explicit transport tagging in platform logs.

That gives a clean separation:

- executor policy talks about the intent: tap at a point
- platform logs talk about the actual transport: a11y gesture vs injected input

The current document calls this "cosmetic, not blocking". I disagree. For this workstream, it is operationally important.

## Recommended Changes To The Design Before Implementation

I would revise the design doc to say this:

1. Keep centralized priority policy. Do not push full action-priority tables into platform implementations.
2. Expose a small platform capability on `AndroidPlatform` instead of using executor `is VirtualDisplayPlatform` checks.
3. Do **not** blanket-flip semantic `click` and `long_press` in VD yet.
4. Keep `scroll` ordering unchanged.
5. Treat the naming cleanup as part of the change, not optional polish.
6. Expand risk language around duplicate side effects when post-action verification misses a real change.
7. Add validation criteria that compare:
   - semantic target correctness
   - false-success rate
   - duplicate-action rate
   - transport label clarity in logs

## Bottom Line

The design is strongest when it says:

- Virtual Display changes the transport behind point actions
- policy should stay centralized
- logs should stop pretending all taps are the same thing

The design is weakest when it jumps from that to:

- therefore VD should globally prefer physical tap and physical long press over semantic node actions

That conclusion is not fully earned by the evidence shown here.

My recommendation:

- **Option A over Option B**
- but implemented via a small platform capability, not concrete type checks
- **keep scroll as-is**
- **do not approve blanket VD `tap -> node_click` / `long_press -> node_long_click` yet**
- **fix the naming ambiguity now**
