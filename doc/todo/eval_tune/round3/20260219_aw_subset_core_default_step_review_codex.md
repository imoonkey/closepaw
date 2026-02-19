# Round3 AW Core Default 逐步审查（Codex）

## 范围与运行信息

- 本文只基于**重新执行**的新结果，不复用历史分析。
- 运行命令：
  - `eval/.venv/bin/python eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_core.txt`
- 新 run 目录：`eval/results/20260219_124436`
- 逐步审查输入：
  - `eval/results/20260219_124436/per_task.jsonl`
  - `eval/results/20260219_124436/artifacts/<run_id>/trace/derived/steps.jsonl`
- 逐步审查方法：
  - 先对全部 14 个 trace 执行 `inspection_tool/replay_compiler.py` 生成 `derived/steps.jsonl`
  - 再由 subagent 对每个 run 的每一步进行合理性判断（World/Mind/Act 交叉），并核对 tool execution 成败

## 本轮指标（summary）

- `num_results`: 14
- `scripted_success_rate`: 0.6429 (9/14)
- `goal_claim_precision`: 0.8889
- `tool_failure_rate`: 0.0186
- `duration_p50_sec`: 69.65
- `duration_p90_sec`: 146.78

## 每个 run 的逐步合理性与 tool execution

说明：下面的“逐 turn 判定”覆盖每个 run 的所有 turn。  
标记定义：
- `合理`: 动作方向与当前 UI/任务目标一致
- `存疑`: 动作可执行但路径低效、偏航或目标语义不稳
- `不合理`: 参数/动作类型错误，或明显不可达成任务
- `N/A`: 本 turn 未形成有效 tool 执行（例如 LLM 错误中断）

### 1) `aw_20260219_124436_BrowserMultiply_0_0` (failure, MaxTurnsReached)

- Tool execution：20 次结果，0 次失败
- 逐 turn 判定：`1-7 合理, 8 存疑, 9 存疑, 10-19 合理, 20 存疑`
- 备注：中后段存在 Chrome 与其他界面切换扰动，最终未完成“5 次点击后计算并输入乘积”。

### 2) `aw_20260219_124436_CameraTakePhoto_1_0` (success)

- Tool execution：3 次结果，0 次失败
- 逐 turn 判定：`1-3 合理`

### 3) `aw_20260219_124436_ClockTimerEntry_2_0` (success)

- Tool execution：7 次结果，0 次失败
- 逐 turn 判定：`1-7 合理`

### 4) `aw_20260219_124436_ContactsAddContact_3_0` (success)

- Tool execution：10 次结果，0 次失败
- 逐 turn 判定：`1-10 合理`

### 5) `aw_20260219_124436_ExpenseAddSingle_4_0` (failure, MaxTurnsReached)

- Tool execution：22 次结果，1 次失败
- 逐 turn 判定：`1-17 合理, 18 不合理, 19-20 合理`
- 备注：turn 18 出现 click 参数不完整导致 validation 失败；后续未在 turn budget 内完成提交。

### 6) `aw_20260219_124436_FilesMoveFile_5_0` (failure, Error)

- Tool execution：5 次结果，0 次失败（但流程中断）
- 逐 turn 判定：`1-5 合理, 6 N/A`
- 备注：turn 6 为 LLM 401（Unauthorized）导致无有效 tool 行动，任务中断。

### 7) `aw_20260219_124436_MarkorCreateNote_6_0` (success)

- Tool execution：9 次结果，1 次失败
- 逐 turn 判定：`1-7 合理, 8 不合理, 9 合理`
- 备注：turn 8 使用了 `mobile_action` 不支持的动作语义（system button 风格），但任务最终已完成。

### 8) `aw_20260219_124436_RecipeAddSingleRecipe_7_0` (failure, MaxTurnsReached)

- Tool execution：20 次结果，0 次失败
- 逐 turn 判定：`1-19 合理, 20 存疑`
- 备注：长表单填写基本可执行，但未在回合耗尽前完成保存。

### 9) `aw_20260219_124436_SimpleSmsSend_8_0` (success)

- Tool execution：9 次结果，1 次失败
- 逐 turn 判定：`1 存疑, 2-9 合理`
- 备注：turn 1 `open_app` 命名不匹配导致失败；turn 2 起改用正确 app 名后恢复并完成任务。

### 10) `aw_20260219_124436_SystemBluetoothTurnOnVerify_9_0` (success)

- Tool execution：11 次结果，0 次失败
- 逐 turn 判定：`1-5 合理, 6-9 存疑, 10-11 合理`
- 备注：中段多次切换/点击蓝牙相关控件，路径偏绕，最终通过替代路径达成目标。

### 11) `aw_20260219_124436_SystemBrightnessMaxVerify_10_0` (success)

- Tool execution：4 次结果，0 次失败
- 逐 turn 判定：`1-4 合理`

### 12) `aw_20260219_124436_SystemBrightnessMinVerify_11_0` (scripted success, MaxTurnsReached)

- Tool execution：20 次结果，0 次失败
- 逐 turn 判定：`1-7 合理, 8-9 存疑, 10-13 合理, 14 存疑, 15-20 合理`
- 备注：存在 SystemUI/Launcher 绕行；虽被 scripted 判定成功，但策略效率偏低。

### 13) `aw_20260219_124436_SystemWifiTurnOffVerify_12_0` (failure, GoalAchieved)

- Tool execution：13 次结果，0 次失败
- 逐 turn 判定：`1-12 合理, 13 存疑`
- 备注：agent 语义上把“断开当前连接”当成“关闭 Wi-Fi”，与 scripted 标准不一致（claim 与评分分离）。

### 14) `aw_20260219_124436_SystemWifiTurnOnVerify_13_0` (success)

- Tool execution：8 次结果（7 turn），0 次失败
- 逐 turn 判定：`1-7 合理`

## 问题总结与归类（Round3）

### A. Reasoning（任务语义/收敛策略）

- `BrowserMultiply`: 未稳定完成“5 次采样 + 乘积输入”闭环。
- `RecipeAddSingleRecipe`: 长表单阶段未收敛到保存动作。
- `SystemBrightnessMinVerify`: 中段决策绕行，造成高 turn 消耗。
- `SystemWifiTurnOffVerify`: “disconnect”与“wifi off”语义混淆。

### B. Execution（工具调用参数或动作语义）

- `ExpenseAddSingle`: click 参数缺失导致 validation 失败（turn 18）。
- `MarkorCreateNote`: 使用了 `mobile_action` 不支持动作语义（turn 8）。
- `SimpleSmsSend`: 首轮 app 名匹配失败，造成 1 次可恢复失败。

### C. Orchestration（流程中断/路径组织）

- `FilesMoveFile`: LLM 401 导致 turn 6 无有效执行并中断任务。
- `BrowserMultiply`, `SystemBrightnessMinVerify`, `SystemBluetoothTurnOnVerify`: 存在可执行但低效的路径迂回。

### D. Perception / Context（UI名词映射与控件可见性）

- `SimpleSmsSend`: app 名映射（Simple SMS Messenger vs SMS Messenger）不稳。
- `SystemBluetoothTurnOnVerify`: 蓝牙开关交互可见性/可操作性信号不稳定，触发多轮试探。

### E. Evaluation gap（任务判定口径差异）

- `SystemWifiTurnOffVerify`: agent claim 成功但 scripted 失败，显示“任务语义口径”和“评分口径”存在偏差。

## 可直接跟进的 tuning 优先级

1. 明确系统任务语义（尤其 `Turn off` vs `Disconnect`）并在完成前校验。
2. 对 `mobile_action` 做更强参数前置校验与修复建议，减少可避免的 validation fail。
3. 建立 app 名映射/模糊匹配回退（`open_app`），降低首轮命名失败。
4. 长表单任务增加“完成态优先”策略（保存按钮优先探测，避免回合耗尽）。
5. 对重复无效交互增加早停与换路由策略，减少 20-turn 失败型拖延。
