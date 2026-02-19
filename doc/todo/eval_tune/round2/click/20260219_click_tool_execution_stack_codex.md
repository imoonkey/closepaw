# Click Tool + Execution 代码链路总结（Codex）

## 目标

梳理当前项目里 click 的完整路径：`prompt -> tool definition -> invocation/executor -> platform`，并指出“层多且易错”的具体位置。

## 1) Prompt 层（模型被如何约束）

### `agent/definition/ExecutorAgentDef.kt`

- 指令强调：优先 `element_index` / `text`，坐标最后手段。
- 明确 `mobile_action(action="click", element_index=N)` 的示例路径。
- 同时要求快速完成、少循环，但没有 click 专属失败策略模板。

### `agent/definition/StandaloneAgentDef.kt`

- 同样强调语义 selector 优先，避免盲点坐标。
- 有“失败后切换策略”原则，但没有结构化的 click 失败分类（occluded/no-op/edge）。

## 2) Tool Definition 层（schema 与参数校验）

### `tool/impl/MobileActionTool.kt`

- `mobile_action` 将 `click/long_press/type/swipe` 合并到一个工具。
- click 校验规则：
  - 必须且仅能使用一种 target（`element_index` / `text` / `x,y`）。
  - 禁用 legacy bounds（`x1/y1/x2/y2`）。
- 创建 invocation 时直接路由到 `ClickExecutor().execute(...)`。

**观察**：一个大工具承载多个手势类型，导致 click 语义和 swipe/type 共享同一入口，认知和维护成本偏高。

## 3) Invocation + Executor 层（点击核心逻辑）

### `tool/impl/MobileActionInvocation.kt`

- 是 click 的薄封装层：`ActionOutcome -> ToolExecutionResult` 映射。
- 会把 `attemptTrail` 拼接进输出文本，便于 trace 观察。

### `tool/action/ClickExecutor.kt`

- 当前 click 主流程（简化）：
  1. `TargetResolver` 解目标到像素点；
  2. 先 `UIAction.ClickNodeAt`（a11y ACTION_CLICK）；
  3. 再 `UIAction.TapAt`（gesture tap）；
  4. 每步后 `captureScreen + UiChangeDetector.compare`；
  5. 若 unchanged：可 `re_resolve`，并追加 jitter taps；
  6. 最多 `MAX_TOTAL_ATTEMPTS = 12`。

### `tool/action/TargetResolver.kt`

- 负责 `element_index/text/coordinate -> Point`。
- 含较多启发式：
  - 候选点（center、upper-third 等）；
  - “smaller clickable blocks point” 的遮挡判断。

### `tool/action/UiChangeDetector.kt`

- 用 a11y 元素指纹（FNV-1a）+ screenshot pHash 识别变化。
- 结果三态：`Changed / Unchanged / Unverifiable`。

**观察**：click 实际行为由多个局部策略共同决定（resolver + fallback + verifier），且每层都在“猜测下一步”，组合复杂。

## 4) Platform 层（原子动作执行）

### 抽象接口

- `platform/AndroidPlatform.kt`: `performAction(UIAction)` 只承诺原子执行，不做重试/回退。

### Accessibility 路径

- `platform/AccessibilityPlatform.kt`
  - `ClickNodeAt` -> `NodeActionPerformer.performNodeClickAt(...)`
  - `TapAt` -> `AccessibilityGestureInjector.injectTap(...)`
- `platform/NodeActionPerformer.kt`
  - `performNodeClickAt` 调 `AccessibilityNodeFinder.findClickableNodeAtLocation` 后 `ACTION_CLICK`。
- `platform/AccessibilityNodeFinder.kt`
  - reverse child DFS 找“top-most clickable visible node”。

### VirtualDisplay 路径

- `platform/virtualdisplay/VirtualDisplayPlatform.kt`
  - 同样支持 `ClickNodeAt` 与 `TapAt`，但 tap 走 `VirtualDisplayInputInjector`（Shizuku 注入）。

## 5) 现状复杂度画像（为什么“套太多层”）

当前 click 一次调用隐含以下决策链：

1. Prompt 约束 target 选择；
2. Tool schema 再做 one-of 校验；
3. Resolver 做点位启发式 + 遮挡判定；
4. Executor 在 node-click / tap / re-resolve / jitter 间编排；
5. Verifier 决定是否认为成功；
6. Platform 再映射到不同执行后端（A11y / Gesture / VirtualDisplay）。

这条链路可扩展性高，但可读性和可预测性差，尤其在 no-op 场景下难以定位“哪层判断错了”。

## 6) 最容易引入 bug 的点

- 同一 click 目标在 resolver 与 node finder 的“可点击定义”不一致。
- “执行成功”与“任务推进成功”混淆（dispatch 成功但无业务进展）。
- 大重试预算掩盖上游策略问题，造成长循环。
- `mobile_action` 合并过多动作，导致 click 的问题被泛化逻辑稀释。
