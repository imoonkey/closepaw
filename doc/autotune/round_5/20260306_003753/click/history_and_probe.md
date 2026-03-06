# Click History And Probe

Date: 2026-03-06

## Goal

Provide a clean input document for the next click redesign round:

- what we changed before
- what kept regressing
- what the archive already tells us
- what the latest isolated Files probe actually proves

This document is intentionally narrow. It is evidence, not a proposed fix.

## Archive Pattern: We Keep Mixing Three Problems

Across the archive, click regressions repeatedly came from three different layers being mixed together:

1. target resolution
2. dispatch mechanism
3. success verification

### 2026-02-07 mobile_action redesign work

Relevant docs:

- [mobile_action_architecture_v2.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260208_click/mobile_action_architecture_v2.md)
- [analysis_claude_done.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260209_prompt/tool/260207_mobile_action/analysis_claude_done.md)
- [qi_note_done.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260209_prompt/tool/260207_mobile_action/qi_note_done.md)
- [qi_note_2.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260209_prompt/tool/260207_mobile_action/qi_note_2.md)

What those docs already got right:

- single-targeting is better than multi-selector retry soup
- platform primitives should stay atomic
- tool layer should not own Android fallback details
- bounds-based targeting was noise
- the `text` field should have one meaning

What they did not settle:

- how to prove that a reported click actually had an effect
- how to separate Android/widget limits from our own wrong-node/wrong-container bugs

### 2026-02-20 overlay touch gate work

Relevant doc:

- [impl_summary_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260223_ui_sota/260221_overlay_touch/impl_summary_claude.md)

This round matters because it proved a real past failure mode:

- our own overlay could intercept gesture injection
- gesture dispatch then reported success while the app never received the touch

This is important historical evidence, but it does not justify blaming every current Files failure on overlay. Overlay must be isolated, not assumed.

### 2026-01 platform/perception review

Relevant docs:

- [platform_perception_summary.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260119_codebase_review/summary/platform_perception_summary.md)
- [platform_perception_review.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/archive/260119_codebase_review/claude/platform_perception_review.md)

Still relevant today:

- never hold stale node references across UI changes
- re-query at action time
- type/focus fallbacks need fresh nodes and timing

This explains why any click plan that depends on old node identity is fragile.

## Round-5 Work Already Landed

Relevant docs:

- [click_alignment.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_5/20260306_003753/click/final/click_alignment.md)
- [implementation_summary.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/autotune/round_5/20260306_003753/click/implementation_summary.md)

Already implemented:

- post-action verification for point actions and scroll
- second delayed post-capture before concluding unchanged
- semantic target promotion from non-actionable child to actionable container
- point-action `success-but-unchanged` now treated as no-op fallback, then failure

This was necessary, but it did not fully answer the Files question.

## Fresh Isolated Files Probe

### Setup

Clean emulator setup on `emulator-5554`:

- enabled our own accessibility service: `com.moonkey.androidagent/.app.AgentService`
- `pointer_location=0`
- `show_touches=0`
- no visible `TYPE_ACCESSIBILITY_OVERLAY` window in `dumpsys accessibility` or `dumpsys window`
- created `/sdcard/Download/task.html`
- opened `com.google.android.documentsui/com.android.documentsui.files.FilesActivity`

Observed row structure from the live UI tree:

- row root: `com.google.android.documentsui:id/item_root`
- class: `LinearLayout`
- bounds: `[0,667][1080,859]`
- `clickable=true`
- child title `TextView`: `task.html`
- title bounds: `[189,706][364,763]`

Test coordinates:

- row center: `(540,763)`
- title center: `(276,734)`
- icon center: `(94,761)`

### Probe results

Artifacts:

- [files_node_row/result.json](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_node_row/result.json)
- [files_gesture_row/result.json](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_gesture_row/result.json)
- [files_node_title/result.json](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_node_title/result.json)
- [files_gesture_title/result.json](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_gesture_title/result.json)
- [files_longpress_row/result.json](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_longpress_row/result.json)
- [files_adb_row_check_activity.txt](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_adb_row_check_activity.txt)
- [files_adb_title_check_activity.txt](/Users/moonkey/workspace/android-agent-workspace/androidagent/debug-output/action-test/files_adb_title_check_activity.txt)

Result table:

| Mechanism | Coord | Transport | UI effect |
|---|---:|---|---|
| `adb input tap` | `(540,763)` | dispatched | unchanged |
| `node_action_click` | `(540,763)` | `success` | unchanged |
| `gesture_tap` | `(540,763)` | `success` | unchanged |
| `adb input tap` | `(276,734)` | dispatched | unchanged |
| `node_action_click` | `(276,734)` | `success` | unchanged |
| `gesture_tap` | `(276,734)` | `success` | unchanged |
| `adb input tap` | `(94,761)` | dispatched | unchanged |
| `node_action_click` | `(94,761)` | `success` | changed |
| `gesture_tap` | `(94,761)` | `success` | unchanged |
| `node_action_long_click` | `(540,763)` | `success` | changed |
| `node_action_long_click` | `(94,761)` | `success` | changed |

### What this proves

This cleanly rules out one tempting explanation:

- **not just our overlay**

In this setup there was no visible overlay window, and gesture injection still produced no effect.

It also proves the current failure is more specific than "click is broken in Files":

- **`node_action_click` still works on the icon hotspot**
- `gesture_tap` does not
- raw `adb tap` does not
- row-center and title-center node click do not

What remains plausible:

1. Files row open is hotspot-sensitive for node click in this state.
2. Our current semantic target center choice can land on a no-op point even when node click would work elsewhere on the same row.
3. Gesture tap and raw adb tap are weaker mechanisms for this Files row than node click.
4. There may still be hidden state differences between this direct FilesActivity setup and the eval path, but the current regression signal is already strong without that assumption.

### What this does not prove

It does not prove:

- all Files states reject synthetic taps
- all apps share this limitation
- our executor no longer has any click bugs elsewhere

It only proves that in the cleanest setup we tested:

- node click works at the icon hotspot
- node click fails at row/title center
- gesture and adb single taps do not open the file at the tested points
- long-press works

## Current First-Principles Boundary

The evidence today supports this boundary:

### Proven our-runtime responsibilities

- reject multi-selector ambiguity
- resolve the correct semantic target
- promote non-actionable child nodes to actionable containers
- separate transport success from observable effect
- keep overlay pass-through from corrupting gesture results
- record enough probe evidence to classify failures

### Still-open mechanism question

For Files row open, the clean probe now points to a mixed boundary:

- `gesture_tap` and `adb tap` look genuinely weak for this widget path
- `node_action_click` still works, but only at a specific hotspot we were not selecting

That makes current semantic point selection a likely regression contributor.

That means the next plan should not jump straight to a Files-specific workaround. It should first answer:

- what exact mechanism opened the file in the earlier "known-good" period
- what exact node/hotspot was clicked then
- whether the open path depends on activity mode, defaults, chooser state, or row subtype
- how to choose a stable actionable hotspot instead of blindly using container center
