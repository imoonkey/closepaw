# 产品路线：Android Agent 与 OpenClaw 的关系

## 背景

OpenClaw 很火，用户量大。跟 Android Agent 在大 purpose 上都是 general personal agent，但路径有冲突。需要决定怎么 ride the wave。

## 两个极端选项及其问题

### Option 1: 做 OpenClaw Node（深度集成）

表面合理：他们有用户，我们补上他们缺的 Android 自动化能力。

问题：
- **变成他们的外包。** OpenClaw 的 Node 是被动的 — Gateway 发 `node.invoke(command, params)`，Node 执行完返回结果。Agent 大脑、session、memory、tool 选择全在 Gateway 侧。我们整个 agent 逻辑会被压缩成一个 `screen.action` tool call。
- **他们自己在做 Android app。** 我们在帮他们补能力的同时，他们自己的 Android 团队也在迭代。哪天他们把 a11y 做进去了，我们就没位置了。
- **用户归属不在我们。** 用户的账号、session、配置、付费关系全在 OpenClaw。我们只是可替换的执行后端。
- **架构依赖太重。** 需要实现他们的完整 Gateway protocol（WebSocket + 自定义 RPC + 双 session 连接 + TLS fingerprint pinning + device auth）。他们改协议我们就要跟着改。

### Option 2: 完全独立（只 borrow idea）

问题：
- **短期没问题，长期会孤立。** 如果 OpenClaw 成为 personal agent 的事实标准，完全不兼容意味着用户群不重叠，需要自己从零建渠道和用户心智。
- **重复造轮子。** Session 体系、memory、compaction、channel integration — OpenClaw 花了大量工程做到成熟，全部自己写时间成本高。

## 建议路线：Option 2.5

**核心定位：Android-native personal agent，独立产品，但暴露一个轻量的 agent-to-agent 协议。**

### 关键事实

OpenClaw 的 Android app 定位是"传感器 + 聊天客户端"（camera、GPS、SMS、notification 读取）。它没有做 accessibility service 级别的屏幕自动化。它的 A2UI 是 Canvas（agent 推 HTML 给手机渲染），不是我们的"读 a11y tree → 理解界面 → 执行动作"。

这个差异是护城河。

### 自己拥有的，全部自己做

- Agent 大脑（规划、观察、执行、校验循环）
- 屏幕自动化（a11y tree、click、scroll、input）
- 手机端 session、memory、tool 系统
- 独立的用户 onboarding 和 App 体验
- 语音输入/输出

### 给 OpenClaw 用户的集成点：一个极薄的 Task API

不做 OpenClaw Node，而是暴露一个**任务级别**的 HTTP/WS 接口：

```
POST /task
{
  "instruction": "打开微信，给张三发消息：明天下午3点见",
  "callback_url": "https://...",
  "timeout_seconds": 120
}

→ 202 Accepted { "task_id": "abc-123", "status": "running" }

GET /task/abc-123
→ { "status": "completed", "result": "消息已发送", "steps": [...] }
```

OpenClaw 用户只需写一个简单的 tool definition 即可调用：

```json
{
  "name": "android_agent",
  "description": "Execute tasks on Android phone via screen automation",
  "parameters": { "instruction": { "type": "string" } }
}
```

### API 设计的关键原则

1. **任务级别，不是动作级别。** 接收"给张三发微信"，不是"click(x=100, y=200)"。规划和执行智能在我们这边，不在 OpenClaw Gateway 那边。
2. **我们是大脑，不是手指。** OpenClaw 可以 dispatch 任务给我们，但我们自己决定怎么做。保护核心价值。
3. **协议极简。** HTTP POST + GET polling（或 WS 推送），不实现 OpenClaw 完整 Gateway protocol。任何编排系统都能调用，不绑定 OpenClaw。
4. **双向可选。** 我们的 agent 也可以调用 OpenClaw 的能力（"帮我在电脑上查个文件"）。两个 agent 对等，不是主从。

### 两个视角下的架构

```
OpenClaw 用户视角:
  OpenClaw Gateway (desktop/cloud)
    ├── Telegram channel
    ├── Discord channel
    ├── Browser automation
    ├── Shell/exec
    └── Android Agent ← 一个 tool，但它自己有大脑

独立用户视角:
  Android Agent (standalone app)
    ├── 屏幕自动化
    ├── 语音交互
    ├── 本地 session/memory
    └── [可选] 连接 OpenClaw 或其他编排器
```

### 为什么这个位置最好

- OpenClaw 用户得到他们缺的 Android 自动化能力 → ride the wave
- 我们保持独立产品身份 → 不依赖他们
- Agent 智能不被降级成"远程手指" → 护城河完整
- 协议通用 → 将来任何 agent 框架都能集成

## 优先级

1. **先把独立产品做到 demo-ready**（核心自动化 + 基本 session + 语音输入）
2. **加一个 HTTP Task API**（~3-5 天工作量，给外部调用的入口）
3. **写一个 OpenClaw tool definition 示例**（让 OpenClaw 用户 5 分钟接入）
4. **不花时间在深度 OpenClaw 协议集成上**
