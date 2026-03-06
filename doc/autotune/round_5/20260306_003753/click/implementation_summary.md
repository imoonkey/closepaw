# Click Implementation Summary

Date: 2026-03-06

## Scope

This work started from the aligned click investigation in:

- `doc/autotune/round_5/20260306_003753/click/final/click_alignment.md`

The immediate goal was not to add more prompt wording. It was to tighten the action layer so a reported click success is closer to a real, observable interaction, then probe whether the Browser `Files -> task.html` path reflects a general click regression or a narrower target/mechanism issue.

## Implemented Changes

### 1. Post-action verification for point actions and scroll

Files:

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt`

Behavior changes:

- `click`, `long_press`, `type`, and `swipe` now capture the post-action screen and mark unchanged actions as `unverified` instead of silently treating them as fully trustworthy.
- `scroll` now requires observable effect. If all scroll channels are no-op, the action fails instead of reporting dispatch-only success.
- The post-action change check now uses a second delayed capture before concluding `unchanged`, which reduces false `unverified` results for slower transitions such as Chrome first-run/page updates.
- `UiChangeDetector` fingerprints now include more UI state (`keyboardVisible`, selection/checked state, range info, hint text) so real state changes are less likely to be missed.

### 2. Actionable-container promotion for semantic point targets

Files:

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`

Behavior changes:

- Semantic point actions no longer blindly trust a non-actionable child node such as a `TextView` label.
- If the resolved semantic target is a non-clickable/non-long-clickable child inside a clickable row/container, the executor promotes the target to the containing actionable element before dispatch.
- This now applies to both `text` targets and `element_index` targets.

This directly addresses the observed pattern where Browser/Files targeted the `task.html` text node, but the runtime actionable node was actually the row/container.

### 3. Point-action no-op success is now treated as channel failure

Files:

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`

Behavior changes:

- A point-action channel is no longer accepted just because Android returned `ACTION_CLICK=true` or a gesture dispatch completed.
- If the post-action verification still shows `unchanged`, that channel is treated as a no-op and the executor continues to the next fallback channel.
- If every click/long-press channel has no observable effect, the action returns failure instead of `success [unverified]`.

This is the executor-level version of the core requirement: a click should not count as successful unless it actually does something observable, or change verification is genuinely unavailable.

## Tests Added or Updated

Files:

- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/ClickExecutorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutorTest.kt`
- `app/src/test/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutorTest.kt`

Coverage added:

- unchanged point action -> `unverified`
- delayed post-action change -> verified success without false warning
- text target promoted to containing clickable row
- element index target promoted to containing clickable row
- point action channel success + unchanged -> fallback / final failure
- scroll with no observable effect -> failure

## Direct Probing Beyond Unit Tests

### Files row probing

I ran direct emulator-side probes against `Files -> Downloads -> task.html` instead of relying only on eval traces.

Setup:

- install latest APK on `emulator-5554`
- create `/sdcard/Download/task.html`
- open `com.google.android.documentsui/com.android.documentsui.files.FilesActivity`
- confirm row bounds with UI dump

Key findings:

1. The row structure in Files is a clickable `LinearLayout` / item root that contains a non-clickable `TextView` for `task.html`.
2. Single-target semantic clicks were often resolving to the child label, while the runtime actionable node was a larger container/card/row.
3. This produced real identity mismatches in runtime logs, not just scorer-side interpretation issues.

### Tap-matrix probing

I also probed multiple coordinates across the row with raw ADB taps after making the file visible:

- row center
- title center
- icon center
- metadata center
- left row
- right row

Observed result:

- none of the sampled single taps reliably opened the file
- long-press/context-menu remained the only consistently effective path seen in eval traces

This is important: the Browser `Files -> task.html` problem is not explained by the new verification layer. There is a real target/semantics issue in that Files flow.

## Intermediate Evaluation Signal

### Before actionable-container promotion

The first targeted Browser re-run after the initial verification patch exposed a remaining gap:

- Browser traces still showed semantic click attempts carrying the child `task.html` hint instead of the actionable row/container
- live runtime logs showed `NodeActionPerformer` mismatches against a larger `CardView`/row target

That is why the executor was updated again to promote `element_index` semantic targets in addition to `text` targets.

### Final targeted Browser validation

Final validation run:

- `eval/results/20260306_035338`

Result:

- `BrowserDraw`: `0.0`
- `BrowserMultiply`: `0.0`
- aggregate: `0/2`

What changed relative to the earlier failures:

- `BrowserDraw` no longer failed at the initial Files row click hypothesis. It reached the share sheet / chooser / Chrome path, but still failed before the drawing canvas became stably usable.
- `BrowserMultiply` progressed further than the earlier post-patch regression. It opened `task.html` in Chrome, clicked the page button 5 times, recorded the 5 displayed numbers, and computed the correct product `20250`. It still failed because it could not expose or reach the answer form before the turn limit.

Interpretation:

- The click-layer fixes improved the early execution path and removed the most misleading false-success mode around Files row targeting.
- These fixes were not enough to restore Browser task success end to end.
- The remaining Browser failures are no longer clean evidence of the original Files click bug. They now look like later-stage Chrome / page accessibility / form-reachability problems.

### Round-6 gate

Because `BrowserMultiply` regressed from the earlier successful smoke run and the targeted Browser validation is still `0/2`, I did **not** start the remaining `never_succeeded` tasks and did **not** write a round-6 eval package.

## Current Bottom Line

- The original `screen change detect` patch was necessary but insufficient.
- There was also a real executor-side semantic targeting bug: non-actionable child labels were being clicked as if they were the actionable element.
- Independent emulator probing still suggests a narrower Files-specific interaction problem remains even after fixing semantic-target promotion.
- Tightening point-action semantics from `success-but-unchanged` to fallback/failure materially improved the Browser entry path, but it did not recover end-to-end Browser success.
- The next iteration should target the later Browser failure mode, not re-open the original click-regression hypothesis as if nothing changed.
