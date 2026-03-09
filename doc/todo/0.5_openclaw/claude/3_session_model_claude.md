# 借鉴点 3: Session 作为产品核心对象

## OpenClaw 怎么做的

### Session Key 层级
```
main                          — 用户默认会话
session:<key>                 — 按主题隔离
agent:${agentId}:main         — agent 专属会话
direct:<channel>:<recipient>  — 按聊天渠道+对象
group:<channel>:<groupId>     — 群组会话
```

### Lane-Based 并发队列
不是全局并发限制，而是按 lane 做 FIFO 队列：
- 每个 session 独立一条 lane，保证单会话内串行执行
- 全局 lane 控制总并发上限
- `collect` 模式：多条消息合并成一个 followup turn（避免用户连发多条导致 agent 重复启动）

### 存储格式
- JSONL（append-only），crash-safe
- 支持 session pruning / compaction（长对话不爆 context）

## 为什么值得借鉴

Android Agent 目前的 session 概念比较弱：
- 任务 = 一次对话 = 一个 session，结束就消失
- 没有跨入口的会话连续性
- 没有并发任务隔离

当我们开始加 Web 控制台、消息入口等多入口时，session 必须成为一等对象。

## 可落地方案

### 近期：定义 Session 对象模型
```kotlin
data class AgentSession(
    val id: String,
    val label: String?,              // 用户可见名称
    val createdAt: Instant,
    val state: SessionState,         // Idle / Running / Error
    val history: List<Turn>,         // 对话历史
    val metadata: SessionMetadata    // 来源、关联 App 等
)
```

关键改动：
- session 有 ID，可以被恢复、查看、重试
- session 持久化到本地文件（JSONL 格式，OpenClaw 验证过这个方案可靠）
- 任务执行完不删除 session，保留供回溯

### 中期：多入口共享 session
- Web 控制台看到的和 App 里看到的是同一个 session 列表
- 从 Telegram 发来的任务也创建 session，可在 App 里查看进度

### Lane 队列（可选）
- 如果将来支持并发任务，需要 lane-based 串行化
- 单设备场景下，最简单的做法是全局单 lane —— 一次只跑一个任务，新任务排队

### 关键原则
- Session 是所有状态的挂载点：history、screenshots、actions、errors
- UI 是 session 的视图，不是反过来
- 先做好单 session 的持久化和可观测，再考虑多 session 并发
