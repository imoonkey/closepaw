# P0: Click False Success 分析与建议（Codex）

## 结论（更新版）

这是执行层的“假成功”问题，但修复方式不能牺牲可解释性。核心原则：

- `node.performAction(ACTION_CLICK)=true` 只代表动作被接收，不代表业务生效。
- “页面无变化”不总是失败（有些点击本来就不改变页面）。
- **Agent 必须有 full visibility**：执行层做了什么尝试，必须完整返回给 Agent。

因此推荐方案是：
- **默认自动兜底（node -> gesture）**，减少无谓 turn；
- **绝不 silent**，将每次尝试和判定都写入 tool output；
- 同时保留 Agent 可显式指定 click 通道的能力（需要时可控）。

## 背景证据

在 `SimpleSmsSend` 中，同坐标点击出现“node 无效、gesture 生效”：
- `node_action_click` 报 success，但 UI 不推进；
- `gesture_tap` 立刻推进目标状态。

这说明当前“首个 success 立即返回”会吞掉关键 fallback 机会。

## 讨论方案对比

## 方案 1：仅暴露“无变化”，由 Agent 自己决定是否切 gesture

优点：
- 透明，Agent 知道发生了什么。
- 不会执行层自动做额外动作。

缺点：
- 依赖模型每轮自主判断，稳定性和效率受 prompt/上下文影响大。
- 常见场景会增加额外 turn（先失败一次再显式再试）。

## 方案 2：执行层自动 retry，但必须显式回传尝试链路（推荐）

优点：
- 执行效率高，减少重复 turn。
- 透明性保留：Agent 可见 node 尝试、无变化判定、gesture fallback 结果。
- 对“已知高风险 node 假成功”更稳健。

风险与控制：
- 风险：某些点击本来无页面变化，可能触发不必要 fallback。
- 控制：限定只做一次 `node -> gesture`，并在输出中标明 `no_observable_change`，不是硬判失败。

## 推荐设计（Hybrid）

### 1) 默认 click 策略：`auto`

- click 执行策略默认 `auto`：
  - 先 `node_action_click`
  - 若 `node` 成功但 `effect=no_observable_change`，再尝试一次 `gesture_tap`
- 最多一次 fallback，不做链式重试。

### 2) Tool output 必须结构化透明

输出至少包含：
- `attempts`（按顺序）
- 每个 attempt 的 `channel`、`dispatch_result`、`effect_result`
- 最终 `final_channel`、`final_status`

示例语义（示例字段名可调整）：
- `node_action_click: dispatch=accepted, effect=no_observable_change`
- `gesture_tap: dispatch=success, effect=changed`
- `final_status=success_verified`

### 3) 状态语义改造：不要把“无变化”直接等同失败

建议拆分：
- `success_verified`：有可观测推进
- `success_unverified`：动作被接收，但无显著变化（或无法确认）
- `failed`：动作执行失败

这样既避免误报，也保留 Agent 决策空间。

### 4) 给 Agent 保留显式通道控制

在 action 参数或 tool schema 中支持：
- `click_mode=auto|node|gesture`

默认 `auto`，但 Agent 在特殊场景可以强制模式，确保可控性。

## 建议改动点

- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`
  - success 分支新增 effect 检测与单次 fallback；
  - 返回完整 `attemptTrail`（不再 silent）。
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt`
  - 支持 `click_mode`（默认 auto，兼容 node/gesture 强制）。
- （可新增）`app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScreenChangeDetector.kt`
  - 统一 pre/post 变化判定，输出 `changed/no_observable_change/unknown`。
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt`
  - 保持优先级，补充注释：auto 模式下 node 可触发透明 fallback。

## 验收标准

1. `SimpleSmsSend`
- node 假成功场景自动进入 gesture fallback，turn 明显下降。
- tool output 中可见完整 attempts 链路。

2. “无变化但非失败”场景
- 不会被硬判失败；
- 返回 `success_unverified` 或等价状态，并让 Agent 可继续决策。

3. 可解释性
- 从 trace 可直接还原“为什么自动 fallback、做了几次、最终为何成功/失败”。

## 观测指标（建议新增）

- `node_dispatch_success_count`
- `node_no_observable_change_count`
- `node_to_gesture_fallback_count`
- `fallback_success_verified_count`
- `success_unverified_count`

重点关注：
- fallback 是否显著提升成功率；
- 是否带来可接受范围内的额外动作开销。

## 最小测试清单

- 单测：`PointActionExecutorCore`
  - `node accepted + no_change -> gesture fallback triggered`
  - `node accepted + changed -> no fallback`
  - `node failure -> gesture fallback`（现有行为不退化）
  - `no_change but semantic no-op -> success_unverified`（不硬失败）
- 单测：`ClickExecutor`
  - `click_mode=node|gesture|auto` 行为正确
- 集成：
  - 复跑 `SimpleSmsSend` 验证透明 fallback
  - 回归 `SystemWifiTurnOn/Off`、`ContactsAddContact`，确保无明显副作用
