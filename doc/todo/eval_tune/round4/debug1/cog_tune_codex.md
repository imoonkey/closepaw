# Cog Tune Root Cause Classification (Codex)

## Scope
- Eval run: `eval/results/20260219_163232`
- Source file: `eval/results/20260219_163232/per_task.jsonl`
- Metrics (`eval/analysis/summarize.py`):
  - `scripted_success_rate=0.3333` (1/3)
  - `tool_failure_rate=0.0` (eval 汇总口径)

## Failed tasks and evidence

### 1) `aw_20260219_163232_ExpenseAddSingle_0_0`
- Primary root cause: **Reasoning / Tool schema misuse** (非执行层)
- Evidence:
  - turn 12: `action=system_button` 传给 `mobile_action`，被 schema 拒绝
    - args: `eval/results/20260219_163232/artifacts/aw_20260219_163232_ExpenseAddSingle_0_0/trace/artifacts/tool_call_args/204_turn_12_mobile_action_synthetic_mobile_action_text_8f6f3afa-1947-4daf-9ba8-57f80.txt`
    - result: `eval/results/20260219_163232/artifacts/aw_20260219_163232_ExpenseAddSingle_0_0/trace/artifacts/tool_result/209_turn_12_mobile_action_synthetic_mobile_action_text_8f6f3afa-1947-4daf-9ba8-57f80.txt`
  - turn 20: `click` 缺 `element_index/text/x/y`，参数校验失败
    - args: `eval/results/20260219_163232/artifacts/aw_20260219_163232_ExpenseAddSingle_0_0/trace/artifacts/tool_call_args/340_turn_20_mobile_action_synthetic_mobile_action_text_0e8d7c4a-da0e-4543-b39e-e79e6.txt`
    - result: `eval/results/20260219_163232/artifacts/aw_20260219_163232_ExpenseAddSingle_0_0/trace/artifacts/tool_result/345_turn_20_mobile_action_synthetic_mobile_action_text_0e8d7c4a-da0e-4543-b39e-e79e6.txt`

### 2) `aw_20260219_163232_FilesMoveFile_1_0`
- Primary root cause: **Execution**
- Secondary signal: perception/target choice mismatch（选中 `clickable=false` 的根节点）
- Evidence (false-success cluster):
  - turn 10/14/20 都对 `element_index=0` 点击，center 均为 `(540,1104)`，节点 `clickable=false`
  - tool_result 文本均显示“gesture_tap success”，但 pre/post tree 不变（`changed=False`）
  - 代表 action transport 成功表象，但 UI effect 不成立（false success）

## Decision
- 按流程：`/cog-tune` 分类为 **Execution**（针对 FilesMoveFile）
- 已切到 action-layer 验证，详见 `doc/todo/eval_tune/round4/debug1/action_debug_codex.md`
