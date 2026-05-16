# CTN Device QA Notes

**Branch**: `task/codex-targeting-normalization`
**Date**: 2026-05-16
**Device**: Tailscale-attached Android (serial `100.64.43.95:5555`), model from `/sdcard/.../result.json` — Settings home (zh-CN locale) used as test surface.
**APK**: `app/build/outputs/apk/debug/app-debug.apk` — `./gradlew :app:assembleDebug` exit 0.

## How scenarios were executed

The agent runtime is non-deterministic — LLMs do not reliably emit the dual-target shapes we need to exercise the new rules (especially the failure cases like `element_index + xy outside bounds`). To run the scenarios deterministically against the real `MobileActionTool` → `TargetResolver` → executor → `AccessibilityPlatform` → real touch dispatch path, this QA pass added a debug-only entry point:

- `app/src/main/kotlin/ai/closepaw/debug/MobileActionDebugRunner.kt` — `BroadcastReceiver` (action `ai.closepaw.ACTION_DEBUG_MOBILE_ACTION`) gated by `BuildConfig.DEBUG`. Builds an `AccessibilityPlatform` from the live `AgentService`, captures a real `ScreenSnapshot`, then runs `MobileActionTool.validate → createInvocation → execute` with `args` JSON passed via intent extra. Writes `result.json` + `pre_tree.json` + `post_tree.json` to `/sdcard/Android/data/ai.closepaw/files/mobile-action-debug/latest/`.
- `app/src/main/kotlin/ai/closepaw/app/AgentServiceReceiverHelpers.kt` — `registerDebugMobileActionReceiverIfNeeded` / `unregisterDebugMobileActionReceiverIfNeeded` (parallel to existing `ACTION_DEBUG_EXEC` receiver).
- `app/src/main/kotlin/ai/closepaw/app/AgentService.kt` — registers / unregisters the receiver in `onServiceConnected` / `onUnbind`.
- `scripts/mobile-action-test.sh` — CLI wrapper that broadcasts `args`, polls for `.done`, captures before/after PNGs, pulls result.

This receiver is the same path the agent uses in production (same `MobileActionTool`, same `AccessibilityPlatform`, same `NodeActionPerformer` / `AccessibilityGestureInjector`); only the params source differs (intent extra vs LLM tool call).

All 6 scenarios were exercised via `projects/active/codex_targeting_normalization/qa-evidence/run-scenarios.sh`. Results live under `debug-output/mobile-action-test/ctn-*/`.

Reference targets observed on Settings home:
- Element `28` `更多连接`: bounds `[0,1743,1264,1946]`, center `(632,1844)` — clickable row.
- Element `3` (top-right search icon): center `(1151,238)` — clickable, opens Settings search.
- After clicking search: `EditText` index `1` `搜索设置`, center `(618,238)`.
- Element `0`: `RecyclerView`, scrollable, bounds `[0,0,1264,2800]`.

---

## Scenarios

### 1. In-bounds Codex shape — PASS

Args: `{"action":"click","element_index":28,"x":632,"y":1844}` (xy is exactly the element center, inside bounds).

Expected: executes via semantic node action; no coordinate-fallback warning.

Actual (`debug-output/mobile-action-test/ctn-1-inbounds/result.json`):
- `status=success`, `phase=execute`, `elapsed=338ms`
- `message`: `Clicked (632,1844) via node_action_click`
- No "coordinate fallback" warning in message.

The semantic node-action channel won — confirms `TargetResolver` returned `Resolved` (not the hint point) and `PointActionExecutorCore` dispatched `ClickNodeAt`.

### 2. Ambiguity (semantic + xy outside bounds) — PASS

Args: `{"action":"click","element_index":28,"x":99999,"y":99999}`.

Expected: `Ambiguous` failure, no UI tap dispatched, message mentions ambiguous + reason.

Actual (`ctn-2-ambiguous/result.json`):
- `status=failure`, `phase=execute`, `elapsed=0ms`
- `message`: `Ambiguous target: element_index 28 resolves to bounds Bounds(left=0, top=1743, right=1264, bottom=1946) but coordinate hint (99999, 99999) lies outside. Refusing to guess.`
- Zero elapsed ms before dispatch — confirms no touch was sent.

### 3. Coordinate fallback (semantic miss + valid xy) — PASS

Args: `{"action":"click","element_index":9999,"x":632,"y":1844}` (index 9999 never resolves; xy is a real tappable location).

Expected: coordinate fallback; result message contains coordinateFallback warning; tap dispatched at (cx,cy).

Actual (`ctn-3-coord-fallback/result.json`):
- `status=success`, `phase=execute`, `elapsed=499ms`
- `message`: `Tapped (632,1844) via gesture_tap` + `Warning: Used coordinate fallback after semantic target failed: Element not found: index 9999. Available: 0, 1, 2, 3, ...`
- Channel is `gesture_tap` (not `node_action_click`) — confirms `PointActionExecutorCore` skipped semantic channels when no semantic hint was resolved, as designed.

### 4. Type coordinate fallback (semantic miss + xy on focusable input) — PASS

Pre-step: click element `3` (search icon) to open the search EditText.
Args: `{"action":"type","element_index":9999,"x":618,"y":238,"input_text":"qa"}`.

Expected: `TapAt` then `SetTextOnFocused`; field receives "qa"; resolver warning surfaces.

Actual (`ctn-4-type-fallback/result.json`):
- `status=success`, `phase=execute`, `elapsed=636ms`
- `message`: `Typed via tap-to-focus at (618,238)` + `Warning: Used coordinate fallback after semantic target failed: Element not found: index 9999. Available: 0, 1, 2, 3, ...`
- "tap-to-focus" path confirms the new coordinate-fallback branch in `TypeExecutor` (commit `5c265fd2`): skip direct `SetTextOnNodeAt`, tap to focus, then `SetTextOnFocused`.

### 5. Scroll guard (element_index + xy where element does not resolve) — PASS

Args: `{"action":"scroll","element_index":9999,"x":632,"y":1095,"direction":"down"}`.

Expected: fails as unresolved scroll target; never falls back to xy as scroll area.

Actual (`ctn-5-scroll-guard/result.json`):
- `status=failure`, `phase=execute`, `elapsed=1ms`
- `message`: `Scroll target not found: element_index=9999` + `Warning: Used coordinate fallback after semantic target failed: Element not found: index 9999. ...`
- The 1ms elapsed and `failure` status confirm `ScrollExecutor` rejected the coordinate-fallback result (per the new guard, commit `1021410d`) — no swipe was dispatched.

### 6. Regression (single-target click / type / scroll / swipe) — PASS

| Sub-case | Args | Outcome | Evidence |
| --- | --- | --- | --- |
| 6a click | `{"action":"click","element_index":28}` | `Clicked (632,1844) via node_action_click` | `ctn-6a-click-element/result.json` |
| 6b type | `{"action":"type","text":"搜索设置","input_text":"hello"}` (after opening search) | `Typed into element at (618,238)` | `ctn-6b-type-text/result.json` |
| 6c scroll | `{"action":"scroll","direction":"down"}` | `Scrolled down via a11y_scroll` | `ctn-6c-scroll-down/result.json` |
| 6d swipe | `{"action":"swipe","start":[632,2200],"end":[632,800],"duration_ms":300}` | `Swiped (632,2200)→(632,800) over 300ms` | `ctn-6d-swipe/result.json` |

All four pre-existing single-target shapes still behave as before.

> Note on initial 6d run: an earlier draft of `run-scenarios.sh` used `start_x/start_y/end_x/end_y`. The swipe schema requires `start:[x,y]` and `end:[x,y]` arrays — the runner script was corrected (no code change needed); a re-run with the correct schema passed.

---

## Summary

| # | Scenario | Result |
| --- | --- | --- |
| 1 | in-bounds dual target | PASS |
| 2 | ambiguous (semantic + outside xy) | PASS |
| 3 | coordinate fallback after semantic miss | PASS |
| 4 | type coordinate fallback | PASS |
| 5 | scroll guard rejects coordinate fallback | PASS |
| 6 | regression (click / type / scroll / swipe) | PASS |

`./gradlew :app:assembleDebug` exits 0.

## Reproducing

```
./gradlew :app:assembleDebug
adb -s 100.64.43.95:5555 install -r app/build/outputs/apk/debug/app-debug.apk
./projects/active/codex_targeting_normalization/qa-evidence/run-scenarios.sh
ls debug-output/mobile-action-test/
```

Individual scenarios can be re-run with `./scripts/mobile-action-test.sh --tag <name> '<args-json>'`.
