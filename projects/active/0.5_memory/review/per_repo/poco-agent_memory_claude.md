# Poco Agent Memory System Analysis

## 1. Product 层面

### Memory 分类

单一类型：**用户级记忆（user-level memory）**，基于 **Mem0** 开源库实现。

| 类型 | 存储 | 索引 | 用途 |
|------|------|------|------|
| **Mem0 Memory** | PostgreSQL (pgvector) + Neo4j/Memgraph (graph) | 向量 + 图 | 用户偏好、事实、对话中提取的记忆片段 |

没有类似 Hermes 的 MEMORY/USER 双分区，也没有 LettaBot 的多维 block 设计。记忆统一由 user_id + agent_id + run_id 三维 scope 管理。

### 每类结构

Mem0 自动从对话消息中提取记忆条目，每条记忆是一个独立的文本片段，带有：
- `memory_id`（UUID）
- `text`（记忆内容）
- `metadata`（可选）
- 向量 embedding（用于语义搜索）
- 图关系（Neo4j/Memgraph 中的实体-关系）
- 版本历史（history API）

## 2. System 层面

### 架构

四层服务架构，记忆流经多个组件：

```
Executor (Claude Agent SDK)
    ↓ MCP Tool calls
    ↓ (memory_create / memory_search / ...)
MemoryClient (HTTP)
    ↓
Executor Manager (proxy API)
    ↓
Backend (FastAPI)
    ↓ MemoryService → Mem0 SDK
    ↓
PostgreSQL (pgvector) + Neo4j/Memgraph (graph store)
```

**关键设计**：Executor 通过 **MCP Server** 暴露记忆工具给 Claude Agent SDK。`create_memory_mcp_server()` 注册 9 个工具（create, create_conversation, search, list, get, update, history, delete, delete_all）。

### 存储/索引

- **向量存储**：PostgreSQL + pgvector 扩展，可配置 collection name，embedding 维度 1024
- **图存储**：Neo4j（默认）或 Memgraph，存储实体间关系
- **History DB**：SQLite（`mem0_history_db_path`），存储记忆版本历史
- **Job 队列**：PostgreSQL `memory_create_jobs` 表，用于异步记忆创建

### 写入方法

两种写入路径：

1. **Agent 主动写入**（MCP tools）：
   - `memory_create`: 从纯文本创建记忆
   - `memory_create_conversation`: 从对话消息列表中提取记忆（Mem0 自动 extract）
   - `memory_update`: 按 ID 更新记忆文本

2. **异步批量创建**（Backend API）：
   - `POST /api/v1/memories` → 创建 MemoryCreateJob → 后台异步处理
   - Job 有 queued → running → success/failed 状态机
   - 支持进度跟踪（progress 字段）

### 检索方法

- **语义搜索**：`memory_search` — 向量相似度 + 图关系，支持 filters
- **全量列表**：`memory_list` — 按 scope（user_id + agent_id + run_id）获取所有记忆
- **单条获取**：`memory_get` — 按 ID
- **版本历史**：`memory_history` — 查看单条记忆的修改历史

### 写入时机

- **Agent 触发**：Claude Agent 在执行任务时通过 MCP tools 主动调用
- **Prompt 引导**：`PROMPT_APPEND_MEMORY_ENABLED` 注入提示：「Search relevant memory before re-asking the user, and store durable preferences/facts when useful」
- **Session 级别**：记忆创建请求带 session_id scope

## 3. Lifecycle 层面

### 淘汰/上限

- **无显式容量上限**：Mem0 不设记忆条目数上限
- **无自动淘汰**：记忆永久保留，除非手动删除
- **Delete API**：支持按 ID 删除单条 / 按 scope 删除全部 / 全局 reset
- **实际约束**：向量搜索性能随数据量增长可能降级

### 去重/合并

- **Mem0 内置去重**：Mem0 SDK 在 `add()` 时会自动检测语义相似的已有记忆，进行合并或更新（而非简单追加）
- **无客户端去重**：Poco Agent 代码层无额外去重逻辑

### 时间衰减

无时间衰减机制。Mem0 记忆是永久的。

## 4. Injection 层面

### Token 预算

- **无 always-on 注入**：记忆不自动注入系统提示
- **完全按需**：Agent 通过 `memory_search` tool call 检索相关记忆，结果作为 tool response 进入上下文
- **Prompt Appendix**：仅注入一行提示（「Search relevant memory before re-asking...」），约 30 tokens
- **实际预算**：取决于搜索返回的记忆条目数和长度

### 分级加载

单级按需加载：
- **无 L1（always-on）层**：没有记忆自动注入系统提示
- **L2（on-demand）**：所有记忆通过 MCP tools 按需检索

这与 Hermes（MEMORY.md always-on + session_search on-demand）和 LettaBot（Memory Blocks always-on + Archival on-demand）形成鲜明对比。

### 作用域隔离

三维 scope：
- **user_id**：用户级别隔离（必须）
- **agent_id**：固定为 `poco-agent`
- **run_id**：可选，允许 run 级别隔离
- **session_id**：Executor Manager 代理 API 使用 session_id，Backend 映射到 user_id

## 5. Abstraction 层面

### 反思/提炼

- **Mem0 自动提取**：`memory_create_conversation` 将对话消息传给 Mem0，由 Mem0 内部 LLM（`mem0_llm_model`，默认 OpenAI）自动提取记忆片段。这是唯一的「提炼」机制
- **Graph Store**：Neo4j/Memgraph 存储实体关系图，Mem0 自动从文本中抽取实体和关系。这提供了结构化的知识表示
- **无周期性反思**：没有 heartbeat 或定期回顾机制
- **版本历史**：`memory_history` API 保留记忆的修改历史，支持审计和回溯

### Working Memory vs Long-term Memory

| 维度 | Working Memory | Long-term Memory |
|------|---------------|-----------------|
| 实现 | Claude Agent SDK 的当前会话上下文 | Mem0（pgvector + Neo4j） |
| 容量 | Context window | 无限（数据库） |
| 持久性 | Session 级别 | 永久 |
| 更新 | 每 turn 自动 | Agent 通过 MCP tools 主动写入 |
| 访问延迟 | 即时 | 需要 HTTP 调用 + 向量搜索 |

**独特之处**：
- **纯按需架构**：唯一一个不做 always-on 记忆注入的系统。优点是不浪费 token 预算，缺点是 agent 必须主动搜索才能利用记忆
- **Mem0 集成**：利用成熟的第三方记忆库，获得免费的语义去重、实体提取、知识图谱能力。代价是对记忆质量和行为的控制力较弱
- **Graph Store**：三个系统中唯一使用知识图谱的，Neo4j/Memgraph 提供实体关系推理能力
- **异步 Job 模式**：记忆创建支持异步队列（queued → running → success/failed），适合长对话批量提取场景
- **多服务架构开销**：记忆操作需要穿越 4 层服务（Executor → Manager → Backend → Mem0），延迟较高
