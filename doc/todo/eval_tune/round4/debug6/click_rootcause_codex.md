# Eval 20260220_162433 Click Root Cause（Codex）

## Scope

- 输入：
  - `eval/results/20260220_162433/summary.json`
  - `eval/results/20260220_162433/per_task.jsonl`
  - 全部 task 的 `trace/trace.jsonl` 与关联 `tool_call_args` / `tool_result` / `tool_observation_screen`
- 方法：
  - 按 `/cog-tune` 流程做逐 task 逐 turn 审计
  - 并行 subagent 全覆盖 14 个 task
  - 额外用脚本二次校验 click/long_press 的“执行成功 vs 状态变化”

## TL;DR

- 这轮不是 click 执行链路整体坏掉；**大多数 click 是有效的**。
- 问题主要集中在两个文件类任务：
  - `BrowserMultiply`
  - `FilesMoveFile`
- 这些任务里大量出现：`tool_result=Success: Tapped...`，但 UI 没有推进到目标状态（假成功/弱成功）。
- 另有少量非 click 问题（如 `SystemBrightnessMin` 的提前结束）会被误感知为 click 问题。

## Quant Snapshot

- Run 总体：14 个任务，`scripted_success_rate=0.642857`
- click-like（`click + long_press`）动作总量：93
- click-like 执行成功：93/93（不含 `system_button` 这种非 click-like）
- click-like 成功但前后 sanitized tree 完全相同：21/93（22.58%）
- 这 21 次里，**19 次集中在两项失败任务**：
  - `BrowserMultiply`: 9
  - `FilesMoveFile`: 10

## Why “有些 click 没问题，有些有问题”

### 1) Click transport 本身大体可用（“没问题”的来源）

- 系统设置类任务中 click 基本有效并能带来明确状态变化：
  - `SystemWifiTurnOff`：turn3 后出现 `"Wi-Fi is off"`
    - 证据：`artifacts/aw_20260220_162433_SystemWifiTurnOff_12_0/trace/artifacts/tool_observation_screen/51_turn_3_mobile_action_synthetic_mobile_action_text_a819822e-5194-463d-bc5a-6cba96.txt`
  - `SystemWifiTurnOn`：turn4 后 Wi-Fi switch 为 `checked: true`
    - 证据：`artifacts/aw_20260220_162433_SystemWifiTurnOn_13_0/trace/artifacts/tool_observation_screen/70_turn_4_mobile_action_synthetic_mobile_action_text_8277b101-6adc-4491-ae10-7472c9.txt`
- 也没有复现之前常见的 `dispatchGesture ... len=0.0 / NaN` 迹象（本 run 检索不到）。

### 2) 文件管理场景出现“success 但任务状态没推进”（“有问题”的来源）

#### BrowserMultiply

- 30 turn 中多数 click/long_press 都返回 success，但几乎一直停留在 `com.google.android.documentsui`。
  - 证据：`trace/trace.jsonl` 中多 turn 的 `screen_captured.package` 持续为 `com.google.android.documentsui`
  - 例子：`artifacts/aw_20260220_162433_BrowserMultiply_0_0/trace/trace.jsonl`
- 多次 observation 仍包含 `task.html` 项，说明“点了但没有进入目标网页流程”：
  - 证据：`.../tool_observation_screen/36_turn_2_...txt`
  - 证据：`.../tool_observation_screen/513_turn_30_...txt`
- 还出现了两次无效 action：
  - `Unknown action: 'system_button'`
  - 证据：
    - `.../tool_result/104_turn_6_mobile_action_...txt`
    - `.../tool_result/138_turn_8_mobile_action_...txt`

#### FilesMoveFile

- 30 turn 内 click/long_press 全部 execution success，但目标“移动文件”未完成（MaxTurnsReached）。
- `click_like` 24 次中有 10 次前后 tree 相同，说明大量交互没有实质推进。
- 根因更像“策略/观测闭环不足”，不是 click 注入挂掉。

## Non-click Confounders（容易误判成 click 问题）

### SystemBrightnessMin

- turn2 只做了 scroll；
- turn3 没有真正 tool call（`llm_tool_calls` 为空），但文本里写了伪调用并直接结束为 GoalAchieved。
  - 证据：`.../llm_tool_calls/48_turn_3_tool_calls.json`（为空）
  - 证据：`.../llm_response_text/47_turn_3_assistant.txt`

### SystemBrightnessMax / ClockTimerEntry

- 两者 click 执行都成功、且有状态变化证据，但 scripted 判定仍失败；
- 更偏向任务完成判据/收尾策略问题（Evaluation gap + Reasoning），不是 click transport 主故障。

## Root Cause Buckets

- `Execution`（局部）：
  - BrowserMultiply 使用了不被 `mobile_action` 支持的 `system_button`
- `Observation`：
  - 文件场景中“点了但没推进”后，缺少强制状态校验，继续重复近似动作
- `Reasoning`：
  - 在未达关键里程碑时重复同类 click，缺乏策略切换
- `Evaluation gap`：
  - `SystemBrightnessMin` 出现“无 tool call 也结束”
  - `ClockTimerEntry`/`SystemBrightnessMax` 可能存在任务完成判据错位

## Actionable Suggestions

1. 对 `click/long_press` 增加 effect-level guard（至少在文件管理高风险场景）：
   - 连续 N 次 click 后 tree/关键文本不变 -> 强制切换策略（node fallback / 其他路径）
2. 消除 action schema 误用：
   - 提示词层明确 `mobile_action` 不支持 `system_button`
   - 或补齐 schema + executor 支持
3. 在 `complete_task` 前增加任务关键状态断言：
   - Brightness 类必须看到明确亮度状态变化再允许结束

