# 0001_CLAUDE: Initial Aligned Design Draft

## What Was Done

Merged Codex (`20260219_action_exec_direct_codex.md`) and Claude (`tech_design_claude.md`) designs into `align/design/design.md`. Full decision log in `align/design/review_claude.md`.

## Consensus Points (Both Proposed Same)

- Three execution layers: L0 adb, L1 platform, L2 executor
- BroadcastReceiver approach for zero-UI trigger
- Debug-build-only gating
- Pre/post screenshot + a11y tree capture
- "Action accepted" vs "UI changed" split for false success detection
- No production code changes
- Host CLI script for orchestration

## Resolved Differences (8 Items)

| # | Topic | Codex | Claude | Decision | Rationale |
|---|-------|-------|--------|----------|-----------|
| 1 | Receiver registration | Dynamic in AgentService | Manifest in debug variant | **Dynamic** (Codex) | Matches existing `STOP_AGENT` pattern; receiver needs service to be connected |
| 2 | File structure | 4 classes (Models, Runner, EffectAnalyzer, ArtifactStore) | 2 classes (Receiver, Executor) | **2 classes** (Claude) | Effect analysis + artifact writing are ~50 lines total, don't justify separate classes |
| 3 | Intent extras | JSON string extra | Individual typed extras | **Individual** (Claude) | No JSON escaping in shell; more ergonomic with `adb shell am broadcast` |
| 4 | Output path | `direct-action/<run_id>/` | `action-test/latest/` | **latest/** (Claude) | Fixed path = simpler pull; host script archives with `--tag` |
| 5 | Script name | `action-direct-debug.sh` | `action-test.sh` | **action-test.sh** | Shorter |
| 6 | Intent action | `DEBUG_DIRECT_ACTION` | `ACTION_DEBUG_EXEC` | **ACTION_DEBUG_EXEC** | Standard Android intent naming |
| 7 | Layer names | `tool_executor` / `ui_action` | `platform` / `executor` | **platform / executor** | Shorter, clearer hierarchy |
| 8 | Effect schema | Detailed (a11y_changed, pixel_change_ratio, verdict) | Simple boolean | **Detailed** (Codex) | Critical for false success diagnosis; dropped pixel_change_ratio from Phase 1 |

Additional from Claude only (Codex didn't address):
- `dump-tree` command → **included**
- Concurrency: reject if agent running → **included** (from Codex)

## Code-Level Corrections Applied

- Both designs referenced `AgentAccessibilityService` — actual class is `AgentService` (verified in code)
- `AccessibilityPlatform` requires `SessionConfig` — confirms `DebugActionExecutor` approach (directly compose `NodeActionPerformer` + `AccessibilityGestureInjector`)

## Open Questions for Codex

1. **SetText actions in DebugActionExecutor**: Should L1 platform layer support `SetTextOnNodeAt` / `SetTextOnFocused`? Current design focuses on click/scroll/swipe/long_press. Type action debugging may also be needed.

2. **Screenshot capture method**: `DebugActionExecutor` needs `captureScreenshot()`. Options:
   - (a) Reuse `AccessibilityScreenshotCapturer` (needs `SessionConfig`)
   - (b) Direct `adb exec-out screencap -p` from host script (simpler, no app-side screenshot)
   - (c) New minimal screenshot method using `Display` API

   I lean toward (b) — let the host script capture screenshots via adb, keep the app-side focused on action execution + a11y tree only. This removes a dependency.

3. **a11y tree format in result**: Should `pre_tree.json` / `post_tree.json` use the raw `AccessibilityNodeInfo` dump or the `Perceptor`-processed `PerceptionElement` format? Raw is more complete for debugging; processed matches what executors actually see.

## Vote

CHANGES — initial draft, requesting Codex review.
