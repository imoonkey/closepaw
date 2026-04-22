# AW Core Eval Cog Tune Report (Codex)

## Scope

- Eval config: `eval/config/default.yaml`
- Task list: `eval/config/aw_subset_core.txt`
- Run command:
  - `python3.11 eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_core.txt`
- Run dir: `eval/results/20260218_145836`
- Data sources:
  - `summary.json`
  - `per_task.jsonl`
  - each run `trace/derived/steps.jsonl` (if trace exists)
  - `runner.log` + per-run `logcat.log` (for no-trace/infra cases)

## High-level metrics

- `num_task_instances`: 13
- `num_attempts`: 14
- `num_results`: 13
- `scripted_success_rate`: 0.5385 (7/13)
- `timeout_rate`: 0.0769
- `infra_failure_rate`: 0.0769
- `tool_failure_rate`: 0.0870
- `goal_claim_precision`: 0.7

---

## Run-by-run step audit

Legend:
- reasonable? = `Y` / `Q`(questionable) / `N`
- execution_ok? = `Y` / `N` / `-` (no tool call)
- category = `Perception | Context | Reasoning | Execution | Observation | Orchestration | Evaluation gap`

### 1) `aw_20260218_145836_BrowserMultiply_0_0` (failure, MaxTurnsReached)

Per-task: turns=20, tool_calls=20, tool_failures=3, scripted_success=false.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | write_todos | Y | Y | - | 计划完整 |
| 2 | open_app | Y | Y | - | 打开 Files |
| 3 | mobile_action | Y | Y | - | 点击 task.html |
| 4 | mobile_action | Y | Y | - | 选择 Chrome |
| 5 | wait | Y | Y | - | 等待加载 |
| 6 | mobile_action | Y | N | Execution | 点击 "Use without an account" 失败 |
| 7 | mobile_action | Y | Y | - | 退而点 "Continue as I" |
| 8 | mobile_action | Y | Y | - | 点 "No thanks" |
| 9 | mobile_action | Y | Y | - | 回到 Files 再点 task.html |
| 10 | mobile_action | Y | Y | - | 再次选择 Chrome |
| 11 | mobile_action | Q | Y | Observation | 历史上下文出现错前台（Agent app vs Chrome） |
| 12 | mobile_action | Y | Y | - | 点 Click Me |
| 13 | scratchpad | Y | Y | - | 记录数字 |
| 14 | mobile_action | Y | Y | - | 点 Click Me |
| 15 | scratchpad | Y | Y | - | 更新数字 |
| 16 | mobile_action | Y | N | Execution | Click Me 点击无效 |
| 17 | mobile_action | N | N | Reasoning+Execution | 点击不可点击 WebView 容器 |
| 18 | write_todos | Q | Y | Orchestration | 进入重规划而非恢复动作 |
| 19 | scratchpad | Q | Y | Orchestration | 继续记笔记但无推进 |
| 20 | mobile_action | Y | Y | - | 最后一步仍未完成任务 |

结论:
- 核心问题是 WebView 点击可执行性差 + 失败恢复策略不足 + 局部观察上下文错位。

### 2) `aw_20260218_145836_CameraTakePhoto_1_0` (success)

Per-task: turns=4, tool_calls=4, scripted_success=true.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | open_app | Y | Y | - | 打开 Camera |
| 2 | mobile_action | Y | Y | Perception(轻) | 初始树稀疏，仅能先点 Options |
| 3 | mobile_action | Y | Y | - | 点击快门 |
| 4 | complete_task | Y | Y | - | 完成任务 |

### 3) `aw_20260218_145836_ClockTimerEntry_2_0` (timeout)

Per-task: turns=0, tool_calls=0, trace_dir=null, scripted_success=false.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| - | - | - | - | Orchestration | `AgentService.instance` 未就绪时跳到设置页，返回后未重试 session |
| - | - | - | - | Evaluation gap | `volume_controller` 从系统日志误匹配为 completion reason |

关键证据:
- `runner.log` 显示 a11y wait 完成后长时间无有效回合，约 15 分钟后 timeout。
- logcat 显示 service 较晚才 connected，且 STOP_AGENT 后仍无 active session。

### 4) `aw_20260218_145836_ContactsAddContact_3_0` (success)

Per-task: turns=4, tool_calls=3, scripted_success=true.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | open_app | Y | Y | - | 打开 Contacts |
| 2 | mobile_action | Y | Y | - | 点 Create contact |
| 3 | mobile_action | Y | Y | - | 点 First name 输入框 |
| 4 | none (LLM文本) | N | N | Execution/Orchestration | 本应输入 Hugo，但输出为 inline pseudo-tool，未解析为 tool call 却直接结束 |

说明:
- 该 run scripted_success=true，但 trace 中确有"文本里工具调用未执行"的风险模式。
- **已确认为 false positive**: agent 从未真正创建联系人。`clear_app_data` 后 Google Contacts Sync 在 14 秒内从云端恢复了先前 run 创建的 Hugo Pereira，agent 启动时该联系人已存在。
- **Status: RESOLVED** — 新建 `AndroidWorldAvd` 模拟器（API 33, Pixel 6, 无 Google 账号），无云同步干扰，`clear_app_data` 能可靠清除联系人。修正后基线应为 6/13 (46%)。

### 5) `aw_20260218_145836_FilesMoveFile_4_0` (failure, GoalAchieved)

Per-task: turns=4, tool_calls=4, scripted_success=false.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | write_todos + open_app | Y | Y | - | 打开 Files 并规划 |
| 2 | mobile_action | Y | Y | - | Show roots |
| 3 | mobile_action | Y | Y | - | 进入 `sdk_gphone64_arm64` |
| 4 | write_todos only | N | N/A | Reasoning/Orchestration | 声称下一步进 Podcasts，但未发任何移动文件动作却 `is_complete=true` |

### 6) `aw_20260218_145836_MarkorCreateNote_5_0` (failure, GoalAchieved)

Per-task: turns=3, tool_calls=2, scripted_success=false.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | open_app | Y | Y | - | 打开 Markor |
| 2 | mobile_action | Y | Y | - | 点新建文件 |
| 3 | none (LLM文本) | N | N | Execution | 计划输入文件名但输出 `mobile_action{...}` inline 文本，未解析执行，直接结束 |

### 7) `aw_20260218_145836_RecipeAddSingleRecipe_6_0` (infra_failure)
### 8) `aw_20260218_145836_RecipeAddSingleRecipe_6_1` (infra_failure retry)

Per-task: 两次均 turns=0, tool_calls=0, trace_dir=null.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| - | - | - | - | Evaluation gap/Execution | `am start ... com.flauschcode.broccoli.MainActivity` Error type 3: activity does not exist |

关键证据:
- `runner.log` 两次 attempt 同样 ActivityNotFound，retry 未触发任何修复动作。

### 9) `aw_20260218_145836_SimpleSmsSend_7_0` (failure, GoalAchieved)

Per-task: turns=4, tool_calls=3, tool_failures=1, scripted_success=false.

| step | tool | reasonable? | execution_ok? | category | note |
|---|---|---:|---:|---|---|
| 1 | open_app(Simple SMS Messenger) | Y | N | Execution/Evaluation gap | 目标 app 名与实际安装不匹配，open_app 失败 |
| 2 | open_app(Messages) | Y | Y | - | 成功打开 Google Messages |
| 3 | system_button(home) | N | Y | Reasoning | 离开消息应用，未继续发送流程 |
| 4 | none | N | - | Reasoning/Observation | 在 launcher 界面直接宣布完成 |

### 10) `aw_20260218_145836_SystemBluetoothTurnOnVerify_8_0` (success)
### 11) `aw_20260218_145836_SystemBrightnessMaxVerify_9_0` (success)
### 12) `aw_20260218_145836_SystemBrightnessMinVerify_10_0` (success)
### 13) `aw_20260218_145836_SystemWifiTurnOffVerify_11_0` (success)
### 14) `aw_20260218_145836_SystemWifiTurnOnVerify_12_0` (success)

共同模式（每 run 3 turns, 2 tools）:

| run | step1 | step2 | step3 | judgement |
|---|---|---|---|---|
| Bluetooth On | open_app(Settings) | click Connected devices | done | 合理，执行成功 |
| Brightness Max | open_app(Settings) | swipe/导航 | done | 合理，执行成功（含一次“屏幕未变化”警告） |
| Brightness Min | HOME | 下拉打开快捷设置 | done | 合理，执行成功 |
| Wifi Off | open_app(Settings) | click Network & internet | done | 合理，执行成功 |
| Wifi On | open_app(Settings) | click Network & internet | done | 合理，执行成功 |

---

## Problem classification summary

### A) Execution (tool call可执行性/解析)

Affected runs:
- BrowserMultiply (点击失败与WebView不可点击目标)
- MarkorCreateNote (inline pseudo-tool 未解析)
- SimpleSmsSend (open_app 找不到目标 app)

Pattern:
- 工具本身可用，但“目标选择”或“工具调用格式”导致动作未落地。

### B) Reasoning / Orchestration (过早完成、恢复策略不足)

Affected runs:
- BrowserMultiply (失败后恢复不足，后期无效回合)
- FilesMoveFile (中间状态误判完成)
- MarkorCreateNote (未执行关键动作仍完成)
- SimpleSmsSend (离开正确上下文后宣布完成)

Pattern:
- `is_complete` 偏乐观，缺少“任务完成前置条件”约束。

### C) Observation / Context

Affected runs:
- BrowserMultiply (局部回合出现前台 app/历史上下文错位迹象)
- CameraTakePhoto (初始树稀疏但被较好处理，属可控风险)

Pattern:
- 个别场景中，观测到的 UI 与模型叙述可能短暂不一致。

### D) Evaluation gap / Infra

Affected runs:
- ClockTimerEntry timeout: a11y/session ready 竞态 + completion reason 误匹配系统日志
- RecipeAddSingleRecipe 两次 infra_failure: Broccoli activity 不存在（安装/映射缺口）
- SimpleSmsSend: task 文案 app 名与设备已安装 app 存在偏差
- ContactsAddContact: Google Cloud Sync 在 `clear_app_data` 后恢复联系人，导致 false positive（**已通过无 Google 账号模拟器解决**）

Pattern:
- 环境/任务映射与 harness 前置检查不足，导致"未进 agent 回合"的失败或无效成功。

---

## Recommended follow-up changes (generalizable)

1) **Completion guardrail**
- 当 `tool_calls=[]` 且文本中疑似工具调用（如 `mobile_action{`）时，不允许直接完成，先触发解析修复或重试。
- 对多步任务增加最小完成条件（如 file move 必须出现 move/confirm 动作链）。

2) **Tool-call recovery hardening**
- 强化从文本恢复工具调用的解析器，支持从自然语言包裹中提取 `tool{...}`。

3) **Session/a11y readiness hardening**
- bridge 不用固定 sleep，改为轮询验证 accessibility service 真正 ready。
- 从设置页返回后若有 pending goal 且 service 就绪，自动重试启动 session。

4) **Reason extraction hardening**
- completion reason 仅从 agent 自身结构化日志提取，避免误吃系统日志（如 `volume_controller`）。

5) **Task->App dependency mapping**
- 为 Recipe/SMS 等任务补齐 required packages / app alias 映射。
- 对 `ActivityNotFound` 类错误改为“安装后重试”或快速标注 infra，不做无效重复 retry。

6) **WebView interaction strategy**
- 针对 WebView 按钮点击失败，加入坐标偏移重试、可点击性校验、动作后 UI 变化验证与 fallback。

---

## Notes

- `aw_subset_core.txt` 共 14 行任务名；本次 run 因 `skip_unavailable_tasks=true` 与 infra retry，最终形成 13 task instances、14 attempts。
- 本报告已覆盖每个 attempt；其中无 trace 的 infra/timeout run 使用 `runner.log` + `logcat` 进行证据化分析。
