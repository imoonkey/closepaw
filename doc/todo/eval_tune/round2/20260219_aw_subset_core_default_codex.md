# Round2 Cog-Tune Audit (Codex, fresh rerun)

## Scope

- Eval target: `eval/config/aw_subset_core.txt`
- Config: `eval/config/default.yaml`
- Fresh rerun analyzed: `eval/results/20260218_235445`
- Analysis method:
  - Recompiled all traces to `trace/derived/steps.jsonl` with `inspection_tool/replay_compiler.py`
  - 3 subagents performed per-run, step-level audit (reasonableness + tool execution success)
  - Final consolidation cross-checked with `per_task.jsonl` and `runner.log`

## Metrics Snapshot

From `eval/results/20260218_235445/summary.json` (14 task instances):

- `scripted_success_rate`: `0.4286` (6/14)
- `timeout_rate`: `0.0000`
- `infra_failure_rate`: `0.0714` (1 task instance: FilesMoveFile)
- `tool_failure_rate`: `0.0329`
- `goal_claim_precision`: `0.5000`
- `duration_p50_sec`: `149.64`
- `duration_p90_sec`: `234.22`

Recomputed from all attempts (`summarize.py`, includes retry attempts = 15 rows):

- `scripted_success_rate`: `0.4000`
- `infra_failure_rate`: `0.1333`

Compare vs previous baseline `eval/results/20260218_203057`:

- `scripted_success_rate`: `-0.1429`
- `timeout_rate`: `-0.1429` (improved)
- `infra_failure_rate`: `+0.0714` (regressed)
- `goal_claim_precision`: `-0.3571` (regressed)
- `tool_failure_rate`: `-0.1242` (improved)
- `duration_p90_sec`: `-689.51` (much faster tail)

## Run-by-Run Step Rationality + Tool Execution

Notes:

- `steps/calls/failed_results` are from `trace/derived/steps.jsonl`.
- FilesMoveFile had infra failures before agent started, so no step trace exists.

| run_id | outcome | steps / calls / failed | step-level verdict |
|---|---|---:|---|
| `aw_20260218_235445_BrowserMultiply_0_0` | fail (`MaxTurnsReached`) | `20 / 20 / 0` | Initial flow is reasonable, but context drifts `DocumentsUI -> Chrome -> DocumentsUI`; never reaches stable "click 5 times + compute product" completion. |
| `aw_20260218_235445_CameraTakePhoto_1_0` | fail (scripted false, agent claimed achieved) | `4 / 4 / 0` | Steps are reasonable and concise (open camera, wait, click shutter, complete); mismatch likely benchmark verification gap. |
| `aw_20260218_235445_ClockTimerEntry_2_0` | fail (scripted false, agent claimed achieved) | `14 / 14 / 0` | Execution is coherent, but answer indicates timer was started; violates goal constraint "Do not start". |
| `aw_20260218_235445_ContactsAddContact_3_0` | fail (`MaxTurnsReached`) | `20 / 20 / 0` | Mid-run app drift (`Contacts -> Dialer -> Settings -> Launcher -> Contacts`) suggests weak recovery planning despite successful tool dispatch. |
| `aw_20260218_235445_ExpenseAddSingle_4_0` | fail (`MaxTurnsReached`) | `20 / 21 / 1` | One hard failure from invalid tool usage (`mobile_action` with `action=system_button`), then continued but did not converge. |
| `aw_20260218_235445_FilesMoveFile_5_0` | infra_failure | `0 / 0 / 0` | Failed in task init (`adb content delete ...` timeout), agent never started. |
| `aw_20260218_235445_FilesMoveFile_5_1` | infra_failure (retry) | `0 / 0 / 0` | Retry failed at benchmark lifecycle (`initialize_task() is already called`). |
| `aw_20260218_235445_MarkorCreateNote_6_0` | success | `10 / 12 / 0` | Overall reasonable and successful; one early low-signal step with empty a11y tree recovered by wait. |
| `aw_20260218_235445_RecipeAddSingleRecipe_7_0` | fail (`MaxTurnsReached`) | `20 / 22 / 0` | Agent stays in Broccoli and keeps acting, but fails to finish persistence/submit path within turn budget. |
| `aw_20260218_235445_SimpleSmsSend_8_0` | success | `17 / 17 / 2` | Recovered from early app-name mismatch and one invalid action; eventually reached successful scripted state. |
| `aw_20260218_235445_SystemBluetoothTurnOnVerify_9_0` | success | `10 / 10 / 0` | Steps are coherent; successful end-to-end toggle verification. |
| `aw_20260218_235445_SystemBrightnessMaxVerify_10_0` | success | `16 / 16 / 1` | One typing misuse on non-editable node; later steps recovered and task passed. |
| `aw_20260218_235445_SystemBrightnessMinVerify_11_0` | fail (`MaxTurnsReached`) | `20 / 20 / 2` | Repeated invalid `system_button` usage inside `mobile_action` and package drift (`SystemUI <-> Agent <-> Settings`) prevented convergence. |
| `aw_20260218_235445_SystemWifiTurnOffVerify_12_0` | success | `1 / 1 / 0` | Minimal and rational. |
| `aw_20260218_235445_SystemWifiTurnOnVerify_13_0` | success | `1 / 1 / 0` | Minimal and rational. |

## High-Signal Problems (Categorized)

### Perception

- Occasional low-signal starts (`elements=0`) after app launch (e.g., Markor early step), requiring wait/retry to stabilize.

### Context

- BrowserMultiply loses sub-goal continuity after switching app contexts; task state is not tightly preserved across navigation jumps.

### Reasoning

- Constraint violation in ClockTimerEntry: result narrative implies "started timer" although goal explicitly forbids it.
- Repeated wrong action schema selection (`system_button` stuffed into `mobile_action`) across multiple runs.

### Execution

- Deterministic tool-level failures caused by invalid action schema:
  - Expense turn 12
  - SimpleSmsSend turn 8
  - BrightnessMin turn 6/9
- BrightnessMax turn 12 typing attempted on non-editable target.

### Observation

- For several failing runs, tool dispatch succeeds but state-based completion is not reached; observation usage is insufficient to trigger strategy shift.

### Orchestration

- Multi-step form/navigation tasks (BrowserMultiply, ContactsAddContact, RecipeAddSingleRecipe, BrightnessMin) loop or drift instead of escalating strategy.

### Evaluation gap

- CameraTakePhoto: trace behavior looks correct, but scripted checker returns failure.
- FilesMoveFile: infra init failures dominate outcome, not cognition quality.

## Evidence Pointers

- Run-level data:
  - `eval/results/20260218_235445/summary.json`
  - `eval/results/20260218_235445/per_task.jsonl`
  - `eval/results/20260218_235445/runner.log`

- Key execution failures:
  - Expense invalid action:
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_ExpenseAddSingle_4_0/trace/artifacts/tool_result/206_turn_12_mobile_action_synthetic_mobile_action_text_77143826-b535-4e2c-a863-9d1b1.txt`
  - SMS app-name mismatch:
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_SimpleSmsSend_8_0/trace/artifacts/tool_result/16_turn_1_open_app_synthetic_open_app_0_b98ab799-dcf0-487a-8fc6-764bdc8a1e4f_result.txt`
  - SMS invalid action:
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_SimpleSmsSend_8_0/trace/artifacts/tool_result/135_turn_8_mobile_action_synthetic_mobile_action_text_160ef07c-6143-4ab0-8104-01f022.txt`
  - BrightnessMax type misuse:
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_SystemBrightnessMaxVerify_10_0/trace/artifacts/tool_result/203_turn_12_mobile_action_synthetic_mobile_action_text_4ee23f49-b04f-49dc-8887-25c6a.txt`
  - BrightnessMin invalid actions:
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_SystemBrightnessMinVerify_11_0/trace/artifacts/tool_result/104_turn_6_mobile_action_synthetic_mobile_action_text_ca891c7c-643d-472a-af2b-96e6cf.txt`
    - `eval/results/20260218_235445/artifacts/aw_20260218_235445_SystemBrightnessMinVerify_11_0/trace/artifacts/tool_result/155_turn_9_mobile_action_synthetic_mobile_action_text_ab9f124e-9dbf-446a-aed2-d746b5.txt`

- Infra failure evidence (FilesMoveFile):
  - `eval/results/20260218_235445/per_task.jsonl` lines for `FilesMoveFile_5_0` and `FilesMoveFile_5_1`
  - `eval/results/20260218_235445/runner.log` entries around:
    - timeout at `content delete --uri content://media/external/downloads`
    - retry failure `FilesMoveFile.initialize_task() is already called.`

## Minimal, Generalizable Next Fixes

1. Add stronger prompt/tool guidance to prevent invalid `mobile_action.action=system_button`; enforce direct `system_button` tool use.
2. Add completion guard for negative constraints (e.g., "Do not start") before `complete_task`.
3. Add loop-break policy after repeated successful-but-no-progress actions (mandatory strategy shift after N turns).
4. Improve BrowserMultiply-style multi-stage task memory (explicit sub-goal tracking across app switches).
5. For eval harness reliability, isolate FilesMoveFile infra issues (download cleanup timeout + retry initialization state) so cognition regressions are not masked by setup failures.

