# Per-File Code Summary

This document provides a summary of each Kotlin source file in `app/src/main/kotlin/`.

---

## Entry Points & Service Layer

### `AgentService.kt`
**AccessibilityService 入口点**

Android Accessibility Service 的主入口。负责：
- 管理 `AgentSession` 的生命周期
- 通过 `Op` sealed class 接收操作指令（Start/Pause/Resume/Shutdown）
- 通过 `AgentEvent` Flow 向 UI 层发送状态更新
- 管理浮动控制条 `OverlayManager`
- 提供静态 `statusFlow` 供 MainActivity 收集状态

### `MainActivity.kt`
**Compose UI 主界面**

应用的主 Activity，使用 Jetpack Compose 构建 UI。负责：
- 显示 API Key 输入、Goal 输入、状态日志
- 通过 `lifecycleScope` 收集 `AgentService.statusFlow` 更新 UI
- 处理 Intent extras（支持从命令行传入 API key 和 goal）
- 检查权限（Overlay、Accessibility Service）
- 调用 `AgentService.runAgent()` 启动 agent

---

## Agent Core (核心逻辑)

### `agent/Agent.kt`
**ReAct Agent 主循环**

单一 ReAct agent，执行 Perceive → Think → Act → Observe 循环。负责：
- 管理 turn 计数和 pause/stop 状态
- 每轮：捕获屏幕 → 调用 LLM → 执行工具 → 记录观察结果
- 通过 `eventEmitter` 发送各类事件（TurnStarted, ActionExecuted 等）
- 处理错误恢复（区分可恢复/不可恢复错误）
- 支持 `complete_task` 工具标记任务完成

### `agent/Turn.kt`
**单轮 LLM 交互**

封装一次 LLM 调用的完整流程：
- 从 `HistoryManager` 构建 `ResponseInputItem` 列表
- 调用 `LLMClient.chatWithTools()` 获取响应
- 解析 tool calls 和文本内容
- 检测任务是否完成（`complete_task` 被调用）

### `agent/AgentConfig.kt`
**Agent 配置**

Agent 执行的配置数据类，包含：
- `goal`: 用户目标
- `sessionId`: 会话 ID
- `maxTurns`: 最大轮数
- `uiSettleDelayMs`: 动作后等待时间
- `debugMode`: 调试模式开关

---

## Session Management (会话管理)

### `session/AgentSession.kt`
**会话生命周期管理**

Agent 执行的主控制器，实现 Op/Event 协议：
- 接收 `Op` 操作（Start, Pause, Resume, Shutdown, Approve 等）
- 通过 `events` Flow 发送 `AgentEvent`
- 管理 `SessionState` 状态机（Created → Running → Paused → Completed/Shutdown）
- 创建并运行 `Agent` 实例
- 处理 approval 请求的转发

### `session/SessionServices.kt`
**依赖注入容器**

会话级别的服务容器（类似 DI container）：
- 创建并持有所有会话服务：`ToolRegistry`, `ToolRouter`, `HistoryManager`, `PolicyEngine`, `LLMClient`
- 注册内置工具（click, type, scroll, swipe, back, home, wait, complete_task）
- 提供 `cleanup()` 方法清理资源

---

## Protocol (协议层)

### `protocol/Op.kt`
**操作指令定义**

从 UI 层发送到 Agent 的操作指令：
- `Start(goal)`: 启动 agent
- `Pause` / `Resume`: 暂停/恢复
- `Interrupt`: 中断当前轮
- `Shutdown`: 关闭会话
- `UserInput(text)`: 用户输入（预留）
- `Approve(actionId, decision)`: 审批响应

还包含 `SessionConfig` 配置类和 `ApprovalMode` 枚举。

### `protocol/AgentEvent.kt`
**事件定义**

从 Agent 发送到 UI 的事件：
- 会话生命周期：`SessionStarted`, `SessionCompleted`, `SessionError`, `SessionPaused`, `SessionResumed`
- Turn 事件：`TurnStarted`, `TurnCompleted`, `TurnPhaseChanged`
- 动作事件：`ActionProposed`, `ActionExecuted`, `ActionSkipped`
- 其他：`ScreenCaptured`, `ApprovalRequired`, `ApprovalResolved`, `StatusUpdate`

### `protocol/SessionState.kt`
**会话状态机**

会话的生命周期状态：
- `Created`: 已创建未启动
- `Running`: 运行中
- `Paused`: 已暂停
- `Completed`: 已完成
- `Shutdown`: 已关闭

### `protocol/SessionId.kt`
**会话 ID**

使用 `@JvmInline value class` 实现的类型安全会话标识符。

### `protocol/AgentError.kt`
**错误类型定义**

分类的错误类型，包含 `isRecoverable` 属性：
- LLM 错误：`LLMError`, `LLMParseError`
- 平台错误：`PlatformError`, `PermissionError`
- 验证错误：`ValidationError`, `UnknownToolError`
- 状态错误：`InvalidStateError`, `SessionClosedError`
- 审批错误：`ApprovalDeniedError`, `PolicyDeniedError`

### `protocol/ApprovalTypes.kt`
**审批相关类型**

- `ApprovalDecision`: APPROVED / DENIED / ABORT
- `RiskLevel`: LOW / MEDIUM / HIGH
- `ApprovalRequirement`: None / Required / Forbidden
- `ApprovalDetails`: 审批请求的详细信息

---

## Infrastructure (基础设施)

### `infra/history/HistoryManager.kt`
**对话历史管理**

管理 LLM 对话历史：
- 存储 `ResponseItem`（Message, FunctionCall, FunctionCallOutput）
- 支持截断策略（NONE, CONSERVATIVE, AGGRESSIVE, MINIMAL）
- Token 估算和上下文窗口管理
- 历史压缩和规范化（确保 call/output 配对）
- `dropLastNUserTurns()` 支持回滚

### `infra/registry/ToolRegistry.kt`
**工具注册表**

管理工具的注册和查找：
- 注册/注销/查找 `ToolSpec`
- 生成 OpenAI Responses API 格式的工具定义
- JSON 转换辅助方法

### `infra/tools/ToolRouter.kt`
**工具执行路由**

工具调用的状态机执行器：
- 状态流程：VALIDATING → POLICY CHECK → (AWAITING_APPROVAL) → EXECUTING → SUCCESS/ERROR
- 集成 `PolicyEngine` 进行审批决策
- 支持审批超时（60秒）
- 跟踪活跃的工具调用状态

### `infra/tools/ToolSpec.kt`
**工具规范接口**

定义工具的接口和相关类型：
- `ToolSpec`: 工具规范（name, description, parameterSchema, validate, createInvocation）
- `ValidationResult`: 验证结果
- `ToolInvocation`: 可执行的工具调用
- `ToolExecutionContext`: 执行上下文
- `ToolExecutionResult`: 执行结果（Success/Failure/Cancelled）
- `ToolObservation`: 执行后的观察结果（ScreenState/TextOutput）

### `infra/tools/ToolCallResult.kt`
**工具调用最终结果**

经过 ToolRouter 完整生命周期后的结果：
- `Success`: 成功，包含 output 和可选的 observation
- `Error`: 失败，包含错误信息
- `Cancelled`: 被取消

### `infra/tools/ToolCallState.kt`
**工具调用状态机**

跟踪工具调用的各个状态：
- `Validating`: 验证中
- `Scheduled`: 已调度
- `AwaitingApproval`: 等待审批
- `Executing`: 执行中
- `Success` / `Error` / `Cancelled`: 终态

### `infra/policy/PolicyEngine.kt`
**策略引擎**

决定工具调用是否需要审批：
- 支持三种模式：`ALWAYS_ASK`, `AUTO_APPROVE`, `SMART`
- 基于风险级别的决策（LOW/MEDIUM/HIGH）
- 支持 allow/deny 列表
- 可配置每个工具的风险级别

---

## Platform Layer (平台层)

### `platform/AndroidPlatform.kt`
**平台抽象接口**

Android 平台操作的抽象：
- `captureScreen()`: 捕获屏幕
- `performAction()`: 执行 UI 动作
- `hasRequiredPermissions()`: 检查权限
- `getCurrentPackageName()`: 获取前台应用包名
- `getDisplayInfo()`: 获取显示信息

### `platform/AccessibilityPlatform.kt`
**AccessibilityService 实现**

`AndroidPlatform` 的真实实现：
- 使用 `Perceptor` 捕获屏幕
- 实现各种 UI 动作：点击、输入、滚动、滑动、系统按钮
- 手势通过 `GestureDescription` API 实现
- 文本输入通过重新查询 accessibility tree 找到目标节点

### `platform/UIAction.kt`
**UI 动作定义**

平台无关的 UI 动作：
- `Click(elementIndex)`: 点击元素
- `ClickAt(x, y)`: 点击坐标
- `Type(elementIndex, text)`: 输入文本
- `Scroll(direction)`: 滚动
- `Swipe(startX, startY, endX, endY, durationMs)`: 滑动
- `SystemButton(button)`: 系统按钮
- `Wait(durationMs)`: 等待

还定义了 `ScrollDirection` 和 `SystemButtonType` 枚举。

### `platform/ActionResult.kt`
**动作执行结果**

UI 动作的执行结果：
- `Success`: 成功
- `Failure`: 失败
- `ElementNotFound`: 元素未找到
- `Cancelled`: 被取消

---

## Data Layer (数据层)

### `data/perception/Perceptor.kt`
**屏幕感知引擎**

将 AccessibilityNodeInfo 树转换为语义化的 `ScreenSnapshot`：
- 遍历 accessibility tree，提取有意义的元素
- 限制最大元素数（80）和字符串长度（60）
- 正确回收 AccessibilityNodeInfo 节点
- 生成 JSON 格式供 LLM 使用

### `data/llm/LLMClient.kt`
**LLM 客户端**

OpenAI Responses API 的封装：
- 支持 tool/function calling
- 自动重试（指数退避，最多 5 次）
- 区分可重试错误（rate limit, 5xx）和不可重试错误
- 实例化设计（非单例），支持不同 API key

---

## Domain Models (领域模型)

### `domain/models/Models.kt`
**核心数据模型**

- `Bounds`: 矩形边界
- `Point`: 2D 坐标点
- `ScreenSnapshot`: 屏幕快照（时间戳 + 元素列表）
- `PerceptionElement`: UI 元素（index, text, resourceId, className, 交互属性, 位置等）

---

## Tools (工具实现)

### `tools/base/BaseTool.kt`
**工具基类**

UI 工具的抽象基类：
- 提供参数验证辅助方法
- 提供 JSON Schema 构建辅助方法
- 实现 `BaseToolInvocation`：执行 UIAction 并捕获执行后的屏幕观察

### `tools/impl/ClickTool.kt`
**点击工具**

点击指定 index 的 UI 元素。参数：`element_index`（必填）

### `tools/impl/TypeTool.kt`
**输入工具**

向指定元素输入文本。参数：`element_index`（必填）, `text`（必填）

### `tools/impl/ScrollTool.kt`
**滚动工具**

向指定方向滚动屏幕。参数：`direction`（必填，up/down/left/right）

### `tools/impl/SwipeTool.kt`
**滑动工具**

从一点滑动到另一点。参数：`start_x`, `start_y`, `end_x`, `end_y`（必填），`duration_ms`（可选）

### `tools/impl/BackTool.kt`
**返回键工具**

按下系统返回键。无参数。

同一文件还包含 `HomeTool`：按下系统 Home 键。

### `tools/impl/WaitTool.kt`
**等待工具**

等待指定时间。参数：`duration_ms`（可选，默认 1000，最大 30000）

### `tools/impl/CompleteTaskTool.kt`
**任务完成工具**

标记任务已完成。参数：`summary`（必填，完成摘要）

---

## UI Layer (UI 层)

### `ui/screen/AgentScreen.kt`
**主界面 Compose UI**

应用主界面的 Compose 实现：
- Header：标题和副标题
- ConfigSection：API Key 输入（带显示/隐藏）、Goal 输入
- ActionButtons：Start Agent 按钮、Accessibility Settings 按钮
- StatusLog：状态日志显示区域
- 使用 `StatusUtils` 统一处理状态类型和颜色

### `ui/theme/Theme.kt`
**主题定义**

Compose Material3 主题：
- 使用 Notion 风格的浅色配色
- 配置系统栏颜色
- 组合 colorScheme 和 typography

### `ui/theme/Color.kt`
**颜色定义**

Notion 风格的优雅浅色主题色值：
- 背景/表面色：暖白色调
- Primary：蓝灰色
- Accent：珊瑚/赤陶色
- Secondary：柔和青色
- 状态色：Success/Warning/Error/Info

### `ui/theme/Type.kt`
**字体定义**

Material3 Typography 配置：Display, Headline, Title, Body, Label 各级别的字体样式。

---

## Utilities (工具类)

### `util/StatusUtils.kt`
**状态处理工具**

集中的状态消息处理：
- `cleanStatusText()`: 移除 emoji
- `getStatusType()`: 检测状态类型（SUCCESS/ERROR/WARNING/THINKING/TOOL/RUNNING/NEUTRAL）
- `isTerminalStatus()`: 判断是否为终态

---

## Service Layer (服务层)

### `service/OverlayManager.kt`
**浮动控制条**

Agent 运行时的浮动 UI 控制条：
- 显示在屏幕底部
- 包含状态指示点、状态文本、暂停/恢复按钮、停止按钮
- 使用 WindowManager 作为 overlay 显示
- 根据状态类型更新指示点颜色
