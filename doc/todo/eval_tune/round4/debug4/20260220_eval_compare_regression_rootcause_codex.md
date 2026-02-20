# Eval 对比与回归根因分析（Codex）

## Scope

- Baseline: `eval/results/20260219_185400`
- New run: `eval/results/20260220_000105`
- 目标：逐 task/turn 判断“动作是否真正生效”（而不是仅 tool 返回 success），并定位回归来源与修复方向。
- 备注：本分析主要基于 `per_task.jsonl`、`trace.jsonl`、`trace/artifacts/*`、`logcat.log` 和代码提交差异。

## 1) Run-level 对比

- `scripted_success_rate`: `0.8571 -> 0.5`
- `duration_p50_sec`: `57.70 -> 81.82`
- `duration_p90_sec`: `133.48 -> 195.20`
- `tool_failure_rate`: `0.0093 -> 0.0305`

结论：新 run 明显退化，但退化来源是混合型（执行层 + 认知层 + 基础设施）。

## 2) 20260220_000105 按 task/turn 审计（真实生效视角）

### 2.1 BrowserMultiply（失败，MaxTurnsReached）

- 关键 turn 链路：
  - `t02~t12`: 打开 Files/Chrome、处理弹窗，流程基本合理。
  - `t13~t26`: 连续 `scratchpad` 记录数字 + 点击按钮，工具都返回 success。
  - `t27~t30`: 开始无效 scroll/click，未进入“输入乘积并提交”的收尾路径。
- 实际执行判断：
  - 动作本身大多执行成功，但任务闭环没有完成（未形成 submit 成功证据）。
- 证据：
  - `.../aw_20260220_000105_BrowserMultiply_0_0/trace/artifacts/llm_history/*_turn_*_history.json`
- 判定：**Reasoning/Task policy 回归**，非纯执行层故障。

### 2.2 CameraTakePhoto（成功）

- `3 turns`，无 tool failure。
- 动作与目标一致，真实成功。

### 2.3 ClockTimerEntry（成功）

- `7 turns`，无 tool failure。
- 动作链路连续，真实成功。

### 2.4 ContactsAddContact（成功）

- `10 turns`，无 tool failure。
- 真实成功。

### 2.5 ExpenseAddSingle（成功）

- `11 turns`，无 tool failure。
- 真实成功。

### 2.6 FilesMoveFile（失败，MaxTurnsReached）

- 关键 turn 链路：
  - `t08`: `long_press element_index=16` -> success（`node_action_long_click`）。
  - `t14`: `click text="Move"` -> `Error: Text "Move" index 0 not found`.
  - `t16/t17/t20`: 模型调用 `mobile_action{"action":"system_button"}` -> schema 错误（应使用独立 `system_button` tool）。
  - `t27`: `long_press element_index=15` -> success + `Screen content unchanged ... [unverified]`。
- 实际执行判断：
  - 多个“success”未带来 UI 状态推进，存在假成功/弱验证问题；同时混入模型动作 schema 错误。
- 证据：
  - `.../tool_result/224_turn_13_...txt`
  - `.../tool_result/259_turn_15_...txt`
  - `.../tool_result/276_turn_16_...txt`
  - `.../tool_result/330_turn_19_...txt`
  - `.../tool_result/449_turn_26_...txt`
- 判定：**Execution + Reasoning 混合失败**（目标流推进失败）。

### 2.7 MarkorCreateNote（成功）

- 相比 baseline 从 fail 变 success（此项是改进，不是回归）。

### 2.8 RecipeAddSingleRecipe（成功）

- `17 turns`，无异常证据。

### 2.9 SimpleSmsSend（失败，MaxTurnsReached）

- 关键 turn 链路：
  - `t02`: `open_app("Simple SMS Messenger")` 失败（名称不匹配）。
  - `t03`: 使用建议名 `SMS Messenger` 打开成功。
  - `t04~t30`: 多轮 click/type/back 循环，未稳定进入“输入内容+发送”闭环。
- 实际执行判断：
  - 动作本身大多成功，但任务策略反复 reset，最终未发送成功。
- 证据：
  - `.../aw_20260220_000105_SimpleSmsSend_8_0/trace/artifacts/llm_history/*`
  - baseline 对照在 `t09` 即发送完成（`20260219_185400`）。
- 判定：**Reasoning 回归为主**，执行层不是主因。

### 2.10 SystemBluetoothTurnOn（成功）

- 真实成功。

### 2.11 SystemBrightnessMax（失败，agent claim GoalAchieved 但 scripted fail）

- 关键 turn 链路：
  - baseline：`t06` 一次 `swipe (42,212)->(1038,212)` 后 `range_percent=100`。
  - new run：同样坐标多次 swipe/click/long_press 后，`range_percent` 始终 `0`。
  - `t15` 还出现 `mobile_action{"action":"system_button"}` schema 错误。
- 执行层关键证据（最关键）：
  - 新 run `logcat` 出现 swipe 触摸 `ACTION_CANCEL`（例如首次 400ms swipe：DOWN 后很快 CANCEL）。
  - baseline 同类 swipe 记录为 `ACTION_DOWN -> ACTION_UP`，并成功拉到 100。
- 证据：
  - baseline: `...20260219_185400...SystemBrightnessMax.../llm_history/58_turn_4_history.json` 后 `range_percent=100`（实际在 `t06`）
  - new: `...20260220_000105...SystemBrightnessMax.../llm_history/*` 多轮 `range_percent=0`
  - new: `...SystemBrightnessMax.../logcat.log`（`AccessibilityGestureInjector` + `TaplEvents`）
  - new: `.../tool_result/239_turn_14_...txt`（Unknown action: system_button）
- 判定：**Execution 回归成立（swipe 注入后未生效）**，并伴随少量 reasoning/schema 错误。

### 2.12 SystemBrightnessMin（失败，Error）

- `t21` 发生 `LLM error: PermissionDeniedException - 403: Key limit exceeded`。
- 判定：**Infrastructure**（非动作执行回归）。

### 2.13 SystemWifiTurnOff（失败，Error）

- `t1` 即 `403 key limit exceeded`。
- 判定：**Infrastructure**。

### 2.14 SystemWifiTurnOn（失败，Error）

- `t1` 即 `403 key limit exceeded`。
- 判定：**Infrastructure**。

## 3) 回归分类（相对 baseline）

- 明确回归：
  - `BrowserMultiply`（success -> fail，reasoning）
  - `SimpleSmsSend`（success -> fail，reasoning）
  - `SystemBrightnessMax`（success -> fail，execution + small reasoning）
- 持续失败（非新回归）：
  - `FilesMoveFile`（两次都 fail，但失败形态变化）
- 明确改进：
  - `MarkorCreateNote`（fail -> success）
- 基础设施噪音：
  - `SystemBrightnessMin`、`SystemWifiTurnOff`、`SystemWifiTurnOn`（403 key limit）

## 4) 代码层根因定位（重点：swipe / long_press / dispatchGesture）

两次 run 之间与动作层最相关的提交：

- `17ffc344` `fix: stabilize node long-press path and simplify target resolution`
  - `LongPressExecutor` 从 `swipe_to_self` 改为 `LongClickNodeAt` 优先 + gesture fallback。
  - `TargetResolver` 去掉了 occlusion offset 逻辑，直接中心点。
- `e7456640` `fix: harden dispatchGesture tap path and add diagnostics`
  - `AccessibilityGestureInjector` 新增 `buildGesture + setDisplayId`、主线程 handler、更详细日志。

当前代码与风险点：

1. `AccessibilityGestureInjector` 的 tap/long_press 仍是零长度 path（`moveTo` 无 `lineTo`）。
   - 文件：`app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`
   - 风险：某些场景“dispatch completed 但交互不生效”。

2. `SwipeExecutor` 固定 `verified=true`，没有做 pre/post change 验证。
   - 文件：`app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt`
   - 风险：工具 success 被误当作 UI success（尤其 slider 场景）。

3. Brightness 场景中同坐标 swipe 从 baseline 有效变为无效，且 logcat 有 `ACTION_CANCEL`。
   - 现象与 `dispatchGesture` 路径高度相关，优先怀疑 gesture 注入链路/边缘手势冲突。

4. `mobile_action` 与 `system_button` tool 的边界不清晰（模型在多个任务中混用）。
   - 文件：`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
   - 结果：多次无效 turn 被 schema error 消耗。

## 5) 修复建议（短期）

### P0: 先去噪再评估

- 先修复/切换 eval key，排除 `403 key limit` 干扰，再做回归判断。

### P0: 修复 swipe “false success”

1. 在 `SwipeExecutor` 加入 pre/post 对比并设置 `verified`：
   - unchanged 时返回 warning（与 `LongPressExecutor` 对齐）。
2. 对“贴边坐标”做安全内缩（例如 left/right/top/bottom inset 若干 px）：
   - 降低系统边缘手势拦截导致的 `ACTION_CANCEL`。
3. 在 slider-like 场景中，unchanged 后触发一次“安全重试路径”（中间点起始，不从边缘起手）。

### P1: gesture tap/long_press 稳定性

- 将 tap/long_press path 改为 epsilon 短线段（非零长度 stroke），避免零长度手势兼容性问题。

### P1: tool schema 抗错

- 对 `mobile_action.action=system_button` 提供兼容回退或更强纠错提示（避免连续浪费 turns）。

### P2: open_app 名称容错

- 给 `open_app` 增加 alias/fuzzy（`Simple SMS Messenger` -> `SMS Messenger`）。

## 6) 重构策略（解决 click/swipe/long_click/targeting spaghetti）

### 目标

- 把“动作执行是否真正生效”从分散逻辑提升为统一能力，减少每个 executor 各自为政。

### 分层重构建议

1. **统一执行骨架（ActionExecutionPipeline）**
   - 阶段固定：`resolve target -> execute primary -> fallback -> observe -> verify -> return`.
   - 所有 executor（click/long_press/swipe/scroll/type）复用同一骨架。

2. **统一验证器（PostActionVerifier）**
   - 输入 `preSnapshot + postSnapshot + actionType`，输出 `verified/unchanged/warnings`。
   - 所有动作统一走这个验证器，不允许 `verified=true` 硬编码。

3. **统一手势构建器（GesturePathBuilder）**
   - 集中处理 epsilon、edge inset、duration policy、display routing。
   - `AccessibilityGestureInjector` 仅负责 dispatch，不再散落策略。

4. **targeting 统一策略**
   - `TargetResolver` 恢复可选 occlusion 规避（可配置）。
   - 将“中心点/偏移点/坐标合法化”做成可测试纯函数。

5. **tool 层职责清晰化**
   - 明确 `mobile_action` 与 `system_button` 的边界；必要时在 tool arbitration 做自动纠偏。

### 验证与发布策略

- 先落地最小修复（P0/P1），跑 `aw_subset_core` 和针对性 task（BrightnessMax、FilesMoveFile、SimpleSmsSend）。
- 回归稳定后再做结构重构，避免一次性大改引入新回归。

## 7) 建议的下一步实验（最小闭环）

1. 仅针对 `SystemBrightnessMax` 做 A/B：
   - A: 现状
   - B: `SwipeExecutor` 加 verify + edge inset
   - 对比 `range_percent` 到达率与 `ACTION_CANCEL` 频率。
2. 对 `FilesMoveFile` 增加“long_press 成功但 unchanged”后的分支策略验证。
3. 对 `SimpleSmsSend` 增加 app alias 后复跑，验证是否消除早期分叉。

