# 产品策略：Android Agent 与 OpenClaw

## 1. 战略决策

**选项 2.5：Android Agent 是一个独立的、Android 原生的个人 Agent。OpenClaw 只是一个可选的上游消费者，通过一层轻量、厂商中立的 Task API 接入。**

### 这意味着什么

- 我们**不会**成为 OpenClaw Node（不实现 Gateway 协议、不做 action 级 RPC、不共享 session/memory）。
- 我们**会**暴露一个 task 级 HTTP 接口，让任何外部 agent 都能提交手机任务并轮询结果。
- 规划、感知、执行、校验、session 状态和 memory 全部保留在 Android Agent 内部。

### 为什么

**反对 Option 1（OpenClaw Node）：**
Node 模式会把我们的整个 agent 缩减成一个由他们 Gateway 控制的远程 `screen.action` 调用。ReAct 循环、感知流水线、规划状态（todos/scratchpad）、多 agent 委派、app skills，全都会被压扁成一个可替换的执行后端。他们自己的 Android 团队也在持续迭代；一旦他们把 a11y 自动化补齐，我们就失去位置。

**反对 Option 2（完全独立）：**
完全没有集成路径，就拿不到 OpenClaw 用户基数带来的网络效应。分发必须从零开始。

**支持 Option 2.5：**
我们的护城河是基于 a11y tree 的界面理解 + 自主执行。OpenClaw 的 Android app 更像“传感器 + 聊天客户端”（camera、GPS、SMS、notifications）。它的 A2UI 是 Canvas 驱动的（agent 往手机推渲染后的 HTML），而不是“读 a11y tree → 理解 UI → 规划 → 执行 → 校验”。这个差异是结构性的，不是一个待办功能。Option 2.5 既保住这个护城河，又让它能被他们的生态消费。

## 2. 产品定位

### 主要身份

Android Agent 是一个**Android 原生个人 Agent**：
- 拥有手机端用户体验
- 拥有 agent loop（perceive → think → act → observe）
- 拥有 Android 特有的感知与动作能力（a11y tree、screen automation）
- 拥有本地 session 连续性、memory 和安全决策

### 集成身份

Android Agent 同时也是一个**agent 能力提供方**：
- 任何外部编排器都可以通过 Task API 提交任务
- OpenClaw 是第一个显而易见的消费者，但契约本身是通用的
- API 与厂商无关，不依赖 OpenClaw 私有协议

## 3. Task API 设计

### 契约

API 是**task 级、异步**的。外部调用方发送自然语言指令，由 Android Agent 决定如何执行。调用方**不能**指定工具、坐标或动作序列。

```text
POST /v1/tasks
{
  "instruction": "Open WeChat and send Zhang San: tomorrow 3pm works",
  "timeout_seconds": 120
}
→ 202 Accepted
{
  "task_id": "t_abc123",
  "status": "accepted"
}

GET /v1/tasks/{task_id}
→ 200
{
  "task_id": "t_abc123",
  "status": "completed",
  "result": "Message sent to Zhang San",
  "steps_taken": 7,
  "duration_ms": 14200
}

POST /v1/tasks/{task_id}/cancel
→ 200
{
  "task_id": "t_abc123",
  "status": "cancelled"
}
```

这里使用 `POST cancel` 而不是 `DELETE`，因为任务记录应该保留终态，供调用方查询。

### 任务状态机

```text
accepted → running → completed
                   → failed
                   → cancelled
                   → timed_out
                   → waiting_for_local_user → running
                                            → timed_out
```

**`waiting_for_local_user`** 是一个关键设计。它把多个内部中断类型折叠成一个统一的外部状态：
- `PolicyEngine` 需要工具审批
- `ask_user` 需要设备侧输入
- 需要设备权限或人工介入

这样就避免为每种内部中断发明不同的外部协议。对外部调用方来说只有一种语义：等待，或者超时放弃。

### 并发模型

**一次只跑一个任务。** Android 的 accessibility service 是单例，并发屏幕自动化在物理上不可行。当已经有任务在运行时：
- 新的 `POST /v1/tasks` 返回 `409 Conflict`，带 `Retry-After` header
- v1 不排队（排队会增加复杂度，收益不清晰，调用方自己重试即可）

### 超时契约

外部的 `timeout_seconds` 映射到**墙钟时间 deadline watchdog**，而不是 `SessionConfig.maxTurns`。这个 watchdog：
- 作为协程计时器与 agent session 并行运行
- 超时后向 session 提交 `Op.Interrupt`
- 短暂等待清理完成（让当前动作跑完）
- 将任务状态切到 `timed_out`

这很必要，因为 turn 时长波动很大（2-30 秒），而 `maxTurns` 只是 turn 预算，不是时间限制。

### Session 映射

一个外部 task = 一次内部 task run。Task API gateway：
1. 如果当前没有 session，则通过 `SessionCoordinator` 创建一个
2. 如果当前有一个空闲 session，则复用它执行新任务
3. 如果 session 已经在运行或暂停，则用 `409 Conflict` 拒绝新任务
4. 通过 `Op.UserInput` 提交指令（经 `SessionCoordinator.submit(text)` 这个已有的字符串接口）
5. 观察 `AgentEvent` 流来更新外部任务状态
6. 在完成 / 失败 / 超时后记录终态

外部 task **不拥有**持久化的对话 memory。agent 可以使用自己的内部 session 机制（Hot Idle、scratchpad、todos），但外部调用方看到的只有 task 级结果。

### API 不暴露什么

- 原始 accessibility tree 数据
- 内部 tool calls 或动作序列（除了粗粒度 step count）
- agent reasoning / thought 内容
- session history 或 scratchpad 内容
- 任何指定工具、坐标或动作计划的方式

## 4. 安全模型

### 认证

**必须使用 Bearer token，即使只监听 localhost。** 在 Android 上，localhost 不是安全边界，设备上的其他 app 也能访问 loopback 端口。

- 第一次启用 API 时生成 token（高强度随机，32 bytes，base64）
- 在 Settings UI 中展示给用户复制
- 支持轮换：用户可以在 Settings 中重新生成
- 所有未带合法 `Authorization: Bearer <token>` 的请求都返回 `401 Unauthorized`

### 网络绑定

- **默认仅 localhost**（`127.0.0.1:8741`）
- **LAN 绑定需要显式 opt-in**，并给出醒目的网络暴露警告
- 不提供直接面向互联网的绑定（需要时用 tunnel）

### 安全边界

外部 task 遵守与一方任务相同的本地安全边界：
- 不绕过 `PolicyEngine` 的审批策略
- 不通过隐藏通道绕开 `ToolRouter`
- 外部系统不能覆盖本地用户的审批决定
- 如果任务离不开本地用户输入，就进入 `waiting_for_local_user`，并在超时后失败

## 5. 架构

### 边界

Task API 是 `SessionCoordinator` **之上的新层**，不是放进 `tool/` 或 `agent/` 内部。核心 agent 栈保持不变。

```text
External orchestrator (OpenClaw, other)
        |
        | HTTP request
        v
TaskApiGateway (new)
        |
        | submit(text) / interrupt / observe events
        v
SessionCoordinator → AgentSession → SessionServices → ToolRouter → mobile_action / tools
```

### 新组件

**1. `TaskApiGateway`**
- 内嵌 HTTP server（具体库实现阶段再定，NanoHTTPd 和 Ktor 都可行）
- 绑定到配置的地址/端口
- 用 bearer token 做认证
- 把 HTTP 请求映射成 `SessionCoordinator` 调用
- 维护内存中的 `Map<String, TaskRecord>` 供状态查询
- 观察 `AgentEvent` SharedFlow 来更新 task records
- 管理每个任务的墙钟 deadline watchdog
- 它位于 app/service 层，不在 `AgentSession` 内部；随长生命周期 service 启动，独立于单个任务执行

**2. `TaskApiConfig`**（app 级设置，不放进 `SessionConfig`）
```kotlin
data class TaskApiConfig(
    val enabled: Boolean = false,
    val port: Int = 8741,
    val authToken: String,
    val bindLan: Boolean = false      // false = localhost only
)
```

这是 app/service 级配置，和不可变的 per-session `SessionConfig` 分离。

**3. `TaskRecord`**（内存态，v1 明确接受其易失性）
```kotlin
data class TaskRecord(
    val id: String,
    val instruction: String,
    val status: TaskStatus,
    val result: String? = null,
    val stepsCount: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)
```

进程死亡会丢失 task records。v1 可以接受，因为任务本身很短（秒到分钟级）。调用方应当优雅处理连接丢失。

**4. Settings UI**：启用 / 禁用 Task API 的开关、端口和 auth token 展示、带警告的 LAN 绑定 opt-in。

### Adapter 的现实复杂度

这个 gateway 不是简单的 1:1 transport wrapper。它必须：
- 切回主线程访问 `SessionCoordinator`（它受主线程约束）
- 在 API 提交与 UI 提交之间仲裁（UI 任务运行时拒绝 API 输入，反之亦然）
- 让 server 生命周期与 session 生命周期解耦（server 在任务完成后继续存活；session 不需要）
- 把 `AgentEvent` 流投影成外部 `TaskStatus`（“状态投影器”逻辑，大约 30 行 `when(event)`，不值得单独抽类）
- 管理 deadline watchdog 协程

这是真正的 orchestration layer，预估 300-500 LOC，不是薄薄一层透传。

## 6. OpenClaw 集成

OpenClaw 用户可以在他们的 Gateway 里加这样一个工具定义：

```json
{
  "name": "android_agent",
  "description": "Execute tasks on Android phone via screen automation. Send natural language instructions.",
  "parameters": {
    "instruction": { "type": "string" }
  },
  "endpoint": {
    "method": "POST",
    "url": "http://<phone-ip>:8741/v1/tasks",
    "headers": { "Authorization": "Bearer <token>" },
    "body_template": { "instruction": "{{instruction}}", "timeout_seconds": 120 }
  },
  "poll": {
    "url": "http://<phone-ip>:8741/v1/tasks/{{task_id}}",
    "interval_seconds": 3,
    "complete_when": "status in ['completed', 'failed', 'timed_out', 'cancelled']"
  }
}
```

5 分钟即可接上。不需要 SDK，不需要实现协议，不需要 Node 注册。

## 7. 推进阶段

### Phase 0：独立产品（当前优先级）
- 核心自动化质量（autotune、app skills）
- Session 稳定性（Hot Idle、checkpoint recovery）
- 设备端 UX
- **不做任何外部 API 工作。**

### Phase 1：Task API
- 带 HTTP server 的 `TaskApiGateway`
- Bearer token 认证，默认仅 localhost
- `POST /v1/tasks`、`GET /v1/tasks/{id}`、`POST /v1/tasks/{id}/cancel`
- 墙钟 deadline watchdog
- Settings UI 开关
- 集成测试：curl → task → completion

### Phase 2：OpenClaw bridge
- OpenClaw 工具定义模板（repo 中存 JSON）
- 带安全警告的 LAN 绑定 opt-in
- 使用文档

### Phase 3：双向（未来）
- 用于向桌面 / 云 agent 外发委托的 `remote_agent` 工具
- 使用同一个 Task API 契约做 agent-to-agent 协议
- 双方是 peer，不是 master-slave

### 明确延后
- 完整的 OpenClaw Gateway 协议
- action 级 RPC
- 共享远程 memory / session 模型
- 流式进度通道（task 级粒度下轮询已足够）
- Webhook callback（真实使用场景出现后再加）
- 持久化 task records（如果进程死亡恢复变重要，再补）
- 自动发现（mDNS / Bonjour，属于 nice-to-have，不是 v1）

## 8. 非目标

- 不实现 OpenClaw Gateway 协议的任何部分
- 不允许外部系统直接驱动 `mobile_action` 或其他内部工具
- 不向外部调用方共享 session history、scratchpad 或原始 accessibility tree
- 在独立产品价值尚未验证前，不为深度 OpenClaw 耦合做优化
- 不向外部调用方流出 agent 的 reasoning / thought

## 9. 取舍

| 选择 | 我们获得什么 | 我们放弃什么 |
|---|---|---|
| Task 级 API（不是 action 级） | 护城河保留，我们的大脑仍在本地 | 高级 orchestrator 的可组合性更弱 |
| 默认仅 localhost | 安全性 | 跨设备使用时需要手动开启 LAN |
| 不做 OpenClaw Node | 产品独立性 | 最快的 OpenClaw 原生接入路径 |
| 串行执行（一次一个任务） | 简单，符合硬件现实 | 外部调用方忙时必须自己重试 |
| `waiting_for_local_user` 状态 | 契约诚实，不会静默挂死 | 某些远程流程无法完全自治 |
| 内存态 task records | 实现简单 | 进程死亡会丢失外部任务状态 |

这些都可以接受，因为它们守住了唯一值得守的东西：Android Agent 是一个产品，而不是外包执行器。
