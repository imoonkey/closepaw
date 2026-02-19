# Round2 Cog-Tune Audit (Codex)

## Scope

- Eval target: `eval/config/aw_subset_core.txt`
- Config: `eval/config/default.yaml`
- Analyzed run dir: `eval/results/20260218_145836` (14 `per_task` rows including one retry)
- Step-level evidence source: each run's `trace/derived/steps.jsonl` + `trace/artifacts/tool_result/*` (or `trace_dir=null` cases)

## Metrics Snapshot

From:

`eval/.venv/bin/python3 eval/analysis/summarize.py --run-dir eval/results/20260218_145836`

- `scripted_success_rate`: `0.5`
- `timeout_rate`: `0.0714`
- `infra_failure_rate`: `0.1429`
- `tool_failure_rate`: `0.0870`
- `duration_p50_sec`: `31.43`
- `duration_p90_sec`: `207.81`

## Run-by-Run Step Rationality + Tool Execution

| run_id | outcome | tools (fail) | step-level verdict | key issues |
|---|---|---:|---|---|
| `aw_20260218_145836_BrowserMultiply_0_0` | fail (`MaxTurnsReached`) | 20 (3) | Steps 1-5 reasonable setup; 6/16/17 click attempts failed; 7-15 and 18-20 repeat attempts without recovery | WebView click reliability, no adaptive fallback |
| `aw_20260218_145836_CameraTakePhoto_1_0` | success | 4 (0) | Steps 1-4 are reasonable and complete | none |
| `aw_20260218_145836_ClockTimerEntry_2_0` | fail (`timeout`) | 0 (0) | No agent turns (`trace_dir=null`) | harness/completion-reason contamination (`volume_controller`) |
| `aw_20260218_145836_ContactsAddContact_3_0` | success (likely false positive) | 3 (0) | Turns 1-3 reasonable; turn 4 intent to type but no structured tool call executed | inline tool-call not executed; possible cloud-sync false positive |
| `aw_20260218_145836_FilesMoveFile_4_0` | fail (`GoalAchieved` mismatch) | 4 (0) | Turns 1-2 reasonable; turn 3 selected wrong storage target; turn 4 planned action but emitted no runnable tool call | wrong storage reasoning + premature completion |
| `aw_20260218_145836_MarkorCreateNote_5_0` | fail (`GoalAchieved` mismatch) | 2 (0) | Turns 1-2 reasonable; turn 3 planned typing filename but no tool call executed | inline tool-call not executed |
| `aw_20260218_145836_RecipeAddSingleRecipe_6_0` | infra failure | 0 (0) | No steps (`trace_dir=null`) | Broccoli activity missing (`Error type 3`) |
| `aw_20260218_145836_RecipeAddSingleRecipe_6_1` | infra failure | 0 (0) | No steps (`trace_dir=null`) | same as attempt 0 |
| `aw_20260218_145836_SimpleSmsSend_7_0` | fail (`GoalAchieved` mismatch) | 3 (1) | Turn 1 open target app failed; turn 2 fallback opened wrong app; turn 3 recovered to home; turn 4 stopped early | missing required app + premature complete |
| `aw_20260218_145836_SystemBluetoothTurnOnVerify_8_0` | success (brittle) | 2 (0) | Turn 1-2 reasonable navigation; turn 3 intended next click but malformed inline call | malformed tool-call path, possible accidental success |
| `aw_20260218_145836_SystemBrightnessMaxVerify_9_0` | success (brittle) | 2 (0) | Turn 2 swipe had no UI change; turn 3 intended search click but malformed inline call | weak execution + accidental state success risk |
| `aw_20260218_145836_SystemBrightnessMinVerify_10_0` | success (brittle) | 2 (0) | Turn 1 unnecessary home; turn 2 opens shade; turn 3 intended follow-up malformed inline call | malformed tool-call path |
| `aw_20260218_145836_SystemWifiTurnOffVerify_11_0` | success | 2 (0) | Turn 2 click likely effective; turn 3 text shows tool-result echo pattern | observation/output mixing risk |
| `aw_20260218_145836_SystemWifiTurnOnVerify_12_0` | success (moderately brittle) | 2 (0) | Turn 2 likely effective; turn 3 intended follow-up malformed inline call | malformed tool-call path |

## Categorized Problems (Cog-Tune Taxonomy)

### Perception

- BrowserMultiply: visible target not reliably represented as clickable node in some turns (for example turn 17 "No clickable node").

### Context

- FilesMoveFile: agent did not enforce strict matching between required storage (`sdk_gphone_x86_64`) and selected storage label.

### Reasoning

- Repeated premature `GoalAchieved` after non-executed planned actions.
- Weak recovery strategy after repeated click-no-op in BrowserMultiply.

### Execution

- BrowserMultiply: multiple click attempts returned no UI change.
- SimpleSmsSend: initial `open_app` failed due app missing; fallback launched different app.

### Observation

- SystemWifiTurnOffVerify: assistant text appears to include raw tool-result style content, indicating observation/response mixing.

### Orchestration

- Recurrent malformed inline tool-call emissions (`mobile_action{...}` inside plain text) that are not parsed as executable structured calls.

### Evaluation Gap

- ClockTimerEntry: no trace, timeout with misleading completion reason text.
- RecipeAddSingleRecipe attempts: required app/activity missing (infra setup).
- ContactsAddContact: potential benchmark false positive from synced pre-existing contact state.

## Evidence Pointers (High-Signal)

- BrowserMultiply failed clicks and no-op loops:
  - `eval/results/20260218_145836/artifacts/aw_20260218_145836_BrowserMultiply_0_0/trace/artifacts/tool_result/*turn_6*`
  - `eval/results/20260218_145836/artifacts/aw_20260218_145836_BrowserMultiply_0_0/trace/artifacts/tool_result/*turn_16*`
  - `eval/results/20260218_145836/artifacts/aw_20260218_145836_BrowserMultiply_0_0/trace/artifacts/tool_result/*turn_17*`
- Inline-call-not-executed pattern:
  - Contacts: `...ContactsAddContact_3_0/trace/artifacts/llm_response_text/64_turn_4_assistant.txt`
  - Contacts: `...ContactsAddContact_3_0/trace/artifacts/llm_tool_calls/65_turn_4_tool_calls.json`
  - Markor: `...MarkorCreateNote_5_0/trace/artifacts/llm_response_text/46_turn_3_assistant.txt`
  - Markor: `...MarkorCreateNote_5_0/trace/artifacts/llm_tool_calls/47_turn_3_tool_calls.json`
- FilesMoveFile storage mismatch and missing action execution:
  - `...FilesMoveFile_4_0/trace/artifacts/sanitized_a11y_tree/*`
  - `...FilesMoveFile_4_0/trace/artifacts/llm_tool_calls/72_turn_4_tool_calls.json`
- Infra setup gaps:
  - `eval/results/20260218_145836/per_task.jsonl` (Recipe attempts exceptions)
  - `eval/results/20260218_145836/runner.log` (Broccoli launch error and retry)

## Recommended Fixes (Generalizable, Prioritized)

1. Harden tool-call extraction and recovery in turn parsing: robustly convert inline `toolName{...}` text into structured calls, or force a retry-turn instead of silent completion.
2. Add completion guardrail: if assistant indicates an intended next tool action but no executable call is parsed, block `GoalAchieved`.
3. Add adaptive failure policy for click no-op: after N no-op clicks, switch strategy (alternate target, scroll, or app-specific fallback).
4. Tighten app/task preflight: for task-required packages/activities (Broccoli, Simple SMS), fail fast or skip with explicit status before task start.
5. Add task-specific verification hints in prompt/context for strict entities (for example exact storage root matching in file tasks).
6. Improve harness completion-reason parsing to ignore unrelated system logcat lines.

## Verification Plan

1. Re-run core subset after parser/guardrail fixes:
   - `eval/.venv/bin/python3 eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_core.txt`
2. Recompute metrics and compare:
   - `eval/.venv/bin/python3 eval/analysis/summarize.py --run-dir eval/results/<new_run>`
   - `eval/.venv/bin/python3 eval/analysis/compare_runs.py --base eval/results/20260218_145836 --new eval/results/<new_run>`
3. Spot-check traces for previously failing patterns:
   - Inline tool call not executed
   - BrowserMultiply click no-op loops
   - Premature `GoalAchieved` without terminal action

