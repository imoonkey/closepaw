# Tool System 设计评审 — 最终版

**日期:** 2026-04-08
**评审人:** Claude, Codex（独立评审 + 交叉评审 + 对齐）
**范围:** `app/src/main/kotlin/com/moonkey/androidagent/tool/`（36 个文件）
**结论:** CHANGES_REQUESTED — 骨架良好，存在关键安全缺口和元数据漂移

---

## 架构

流水线: **ToolSpec -> ToolRegistry -> ToolRouter -> PolicyEngine -> ToolInvocation -> Executor**

五个阶段，各自单一职责。设计避免了对工具执行的过度抽象。`ToolSpec`（声明式的"是什么"）与 `ToolInvocation`（可执行的"怎么做"）正确分离，使审批流程成为可能。

**评级: 骨架良好，执行层面尚未完全落实。**

---

## 关键发现

### C1. Blocked 应用边界未端到端强制执行

安全模型声明 BLOCKED 应用应被遮罩并拒绝访问。该不变量仅在 turn 开始和一条 approval-refresh 路径上成立，并非在每个 observation 边界都得到保证。

**证据:**
- `PolicyEngine.check()` 在执行前评估当前前台 package
- `open_app` 在 invocation 执行内部解析目标 package，此时 policy 已经放行了该调用
- 原始 `platform.captureScreen()` 在多个 tool 层路径中被直接调用：
  - `UIActionInvocation`（action 后）
  - `PostActionAnalysis`（重试截屏）
  - `OpenAppTool`（启动后）
- `maskIfBlocked()` 仅在 `ToolRouter` 的 post-approval refresh 中被应用

**后果:** 从一个 NORMAL 应用出发，agent 可以导航进入 BLOCKED 应用并在 tool observation 中接收到未遮罩的内容。

### C2. ToolName 非规范化 — 遗漏会改变运行时行为

`ask_user` 和 `shell` 不在 `ToolName` 中。它们被解析为 `Unknown`，而 `Unknown` 默认 `isScreenChanging = true`。该元数据被以下组件消费：
- `PolicyEngine`（策略门控）
- `TurnToolPolicy`（turn 仲裁）
- `ActionSignature`（循环检测）

**后果:** `ask_user` 在 CAUTIOUS 应用上触发不必要的审批提示。两个工具均扭曲了 turn 仲裁和循环检测的行为。

**根因:** 能力元数据存在于一个并行的 enum（`ToolName`）中，与已注册的工具产生漂移。元数据应当存放在 `ToolSpec` 上。

---

## 高优先级发现

### H1. Shell 绕过了声明式 Tool 模型

`shell` 运行 `ProcessBuilder("sh", "-c", command)`。校验仅检查第一个 token 是否在 blocklist 中。Shell 元字符、管道和包装器可以绕过该检查。

该工具正在被使用（已在 `StandaloneAgentDef.allowedTools` 和 standalone prompt 中确认）。Android 沙箱限制了爆炸半径，但该工具破坏了原本声明式的、有类型的、可被 policy 检查的 tool 体系。

### H2. 指定目标的 Scroll 静默降级

`ScrollExecutor.resolveScrollArea()` 在目标解析失败时回退到全屏边界。带有明确 `element_index` 或 `text` 的 `scroll` 调用在解析失败时，语义会静默变为全屏滚动，而非报错。

---

## 中优先级发现

### M1. 各 Executor 之间取消语义不一致

- `PointActionExecutorCore` 和 `ScrollExecutor` 正确传播 `Cancelled`
- `SwipeExecutor` 将平台取消转换为 `Failed`
- `TypeExecutor` 将取消折叠为通用的失败轨迹
- Router 的 `cancel()`/`cancelAll()` 不持有执行中 invocation 的 per-call 取消令牌

### M2. ToolSpec 标准化了输入但未标准化能力和输出

- 能力判断委托给 `ToolName.isScreenChanging` 而非 tool spec
- 工具成功载荷为 `data: Any?`
- Post-action 截屏时序/常量散布在 `UIActionInvocation`、`OpenAppTool`、`PostActionAnalysis` 中

### M3. Point-Action 重定向是隐藏的 Policy

`refinePointActionTarget()` 可以从不可点击元素提升到其最近的可点击容器或附近的子元素。这处理了真实的 Android UI 模式，但无形中改变了目标语义。该行为应当在 attempt trail 中可观测。

### M4. Approval 后的 TOCTOU 检查不对称

Approval 之后，router 重新检查前台 package。如果 `packageName` 为 null 且当前应用为 CAUTIOUS，执行会在没有重新检查的情况下继续。Approval 上下文未被完全绑定。

---

## 低优先级发现

### L1. 死代码: Scroll-Boundary 相关

`UIActionInvocation.detectScrollBoundary()` 检查 `uiAction is UIAction.Swipe`，但 UIActionInvocation 仅被 SystemButton 和 Wait 工具使用 — 两者均不产生 Swipe。死代码。`UiChangeDetector.detectScrollBoundary()` 同样未被使用。

### L2. MobileActionName 残留成员

`PolicyEngine.isEscape()` 检查 `mobile_action(action=back/home)`，但 `MobileActionTool` 只接受 click/long_press/scroll/swipe/type。该分支永远不会匹配。

### L3. OpenAppTool 中的重复常量

`UI_SETTLE_DELAY_MS` 和 `SUGGESTION_LIMIT` 在 `OpenAppTool` 和 `OpenAppInvocation` 的 companion 中都有声明；实际只使用 invocation 中的。

### L4. SystemButtonTool 不可达的 Fallback

`else -> SystemButtonType.BACK` 永远不会执行，因为 `validate()` 已拒绝未知按钮。应改为 `error("unreachable")`。

### L5. DataQueryInvocation 似乎未被使用

通用 invocation handler，无生产调用者。

### L6. Shell 输出截断无提示

超过 4096 字符的输出被截断但没有任何指示。LLM 收到不完整数据。

### L7. Executor 逐次调用分配

`ClickExecutor`、`LongPressExecutor` 等无状态但在每次 `createInvocation()` 调用时被实例化。

### L8. Scheduled 状态是临时性的

Router 状态机中的 `Scheduled` 仅用于 UI 通知。没有任何逻辑查询它。

---

## 优势

1. **流水线真正做到了最小化。** 没有不必要的抽象或策略模式。
2. **安全模型是分层的。** AppClassifier -> PolicyEngine -> MemoryGate -> screen masking（执行缺口除外）。
3. **Action fallback 链设计良好。** 双通道、可配置优先级、带 attempt trail 用于调试。
4. **状态机是正确的。** 不可能状态无法被表示。
5. **工具在输入层面是一致的。** 每个 ToolSpec 都遵循 validate-then-create 模式，错误信息风格相似。
6. **TargetResolver 是纯函数且正确的。** 无状态，解析逻辑清晰。
7. **RememberExperienceTool 的 Layer 4 gate** 是纵深防御的正确实践。
