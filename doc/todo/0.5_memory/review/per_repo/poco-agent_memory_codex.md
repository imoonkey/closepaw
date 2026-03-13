## 1. 一句话结论

`poco-agent` 有真实的长期记忆系统，但本质上是 `mem0` 的薄封装 + 用户级 memory tools 注入，不是系统自动回忆并注入上下文；能存、能查、能手动管理，但主任务链路接入还不完整。

## 2. Product层面

- README 把它明确当成 `Smart Memory` 能力卖点，前端也有独立 `Memories` 页面和侧边栏入口，且由 `mem0_enabled` feature flag 控制（`README.md`、`frontend/hooks/use-memory-feature-enabled.ts`、`frontend/components/shell/sidebar/sidebar-header.tsx`）。
- 用户能做 `list/search/create/edit/delete`，创建走异步 job 并显示进度（`frontend/features/memories/hooks/use-memories-store.ts`、`backend/app/api/v1/memories.py`）。
- 手动新增记忆不是直接写原文，而是先包装成 `"User preference: ..."` 再交给后端抽取（`frontend/features/memories/components/memories-page-client.tsx`）。产品默认它更偏“偏好记忆”，不是通用知识条目录入。
- 当前产品面是“可见、可编辑的记忆库”，不是黑盒自动记忆；但 `history` 和单条详情虽然有 API，页面层并没有把它们做成强体验，实际主要还是平铺列表 + 搜索 + 改删。
- 会话侧虽然有 `memory_enabled` 配置，但当前仓库里主任务输入流没有真正把它露出来：`task-composer.tsx` 有 `memoryEnabled` state，`composer-toolbar.tsx` 没有对应 toggle，默认值是 `false`。所以普通聊天/任务发起路径大概率不会打开记忆工具。

## 3. System层面

- 核心实现是 `backend/app/services/memory_service.py` 对 `mem0.Memory.from_config(...)` 的一层包装；依赖直接来自 `backend/pyproject.toml` 的 `mem0ai`。
- 默认存储拓扑是 `pgvector + neo4j/memgraph + history_db_path`（`backend/app/core/settings.py`、`docker-compose.yml`、`docker-compose.r2.yml`）。应用自己的 Postgres 只存 `memory_create_jobs` 这类任务元数据，不存 canonical memory record（`backend/app/models/memory_create_job.py`）。
- 作用域模型是 `user_id + agent_id("poco-agent") + optional run_id`（`MemoryService._build_scope`）。没有一等公民的 `project_id` 维度；`run_id` 只在部分 public API 可传。
- 执行链路里的 internal/manager memory API 虽然表面写着 “session scope” （`executor_manager/app/schemas/memory.py`），但 backend 实际是 `session_id -> user_id` 再做 user-level memory（`backend/app/core/deps.py`、`backend/app/api/v1/internal_memories.py`）。也就是说，executor 侧记忆本质上是跨会话共享的用户级记忆。
- 记忆抽取和 embedding 在包装层被固定为 OpenAI 提供者；即使主代理模型跑 Claude，开记忆也仍然要求 `OPENAI_API_KEY`（`backend/app/services/memory_service.py`）。

## 4. Lifecycle层面

- 创建链路是：`POST /memories` -> `memory_create_jobs` 入库 -> FastAPI `BackgroundTasks` -> `MemoryCreateJobService.process_create_job()` -> `mem0.add(messages=...)`（`backend/app/api/v1/memories.py`、`backend/app/services/memory_create_job_service.py`）。
- 读取、搜索、更新、删除、历史查询都是同步直连 mem0（`backend/app/services/memory_service.py`）。
- 这套系统没有看到“自动扫完整聊天记录并沉淀长期记忆”的后台流程。全仓里真正触发创建的，只有 Memories 页面/API 和 executor 暴露出的 memory tools；所以它更像“agent 可调用的 memory system”，不是系统级自动沉淀。
- job 虽然有数据库表，但没有独立 worker 或恢复机制；处理完全依赖 FastAPI 进程内的 `BackgroundTasks`。backend 重启后，`queued/running` job 很可能悬空。
- `MEM0_HISTORY_DB_PATH` 默认是 backend 容器内的 `/tmp/poco/memory/history.db`（`backend/app/core/settings.py`、`docker-compose.yml`），compose 里也没看到为 backend 挂这个路径的持久卷，所以“记忆变更历史”本身不如向量/图存储稳定。

## 5. Injection层面

- 全局门控是 `MEM0_ENABLED`：它决定后端默认是否允许 memory，也决定前端是否显示记忆入口（`backend/app/core/settings.py`、`backend/app/api/v1/models.py`）。
- 会话门控是 `config.memory_enabled`：backend 在建任务时会规范化它；如果全局没开 mem0，就强制关掉（`backend/app/services/task_service.py`）。
- executor 只有在 `config.memory_enabled=true` 时才创建 `MemoryClient`，并把内置 MCP server `__poco_memory` 注入 Claude SDK，同时在 prompt appendix 里明确要求“先查 memory，再少问用户；必要时存 durable facts”（`executor/app/api/v1/task.py`、`executor/app/core/engine.py`、`executor/app/prompts/prompt_append.py`、`executor/app/core/memory.py`）。
- 这里的注入方式是“工具注入”，不是“系统自动把相关记忆检索后塞进当前上下文”。
- 一个明显的架构边界问题是：`backend/app/api/v1/memories.py` 和 `backend/app/api/v1/internal_memories.py` 各自实例化了自己的 `MemoryService()`。因此 `POST /memories/configure` 这类运行时配置，并不天然等于 executor/internal 路径正在使用的那份配置。

## 6. 抽象层面

- 这层抽象非常薄：上层不自己定义 memory schema，只把 `messages`、`query`、`memory_id` 转给 mem0，返回值基本也都是 `Any`（`backend/app/schemas/memory.py`、`backend/app/services/memory_service.py`）。
- “写入记忆”的核心抽象是对话片段，不是结构化 fact。`memory_create` 会把文本当成 user message，`memory_create_conversation` 直接提交一组消息；手动 UI 也是在造 synthetic conversation（`executor/app/core/memory.py`、`frontend/features/memories/components/memories-page-client.tsx`）。
- 这种设计接入很轻，但也意味着“偏好”“项目上下文”“长期事实”没有产品内统一 schema，更多依赖 mem0 自己从文本里抽取。
- `update` 只接受 `text`；schema 虽然带了 `metadata`，但更新路径没真正用它。`search` 虽支持 `filters`，当前产品/UI 也基本没把结构化 metadata 用起来。
- 单条 memory 的 `get/update/history/delete` 都只按 `memory_id` 调用 mem0，没有再附带 `user_id` scope（`backend/app/api/v1/memories.py`、`backend/app/services/memory_service.py`）。从这个仓库本身看，不存在显式的应用层 ownership 校验。

## 7. 值得借鉴 / 明显局限

### 值得借鉴

- 把长期记忆做成独立可见能力：有专门页面、可搜索、可编辑、可删除，而不是完全黑盒。
- 不把 memory 粗暴塞进每轮 prompt，而是做成内置 MCP tools，让 agent 按需 recall / write；这比“每轮自动拼一坨历史”更可控。
- 创建走异步 job，应用数据库只存 job 元数据，真正记忆落外部 store，主业务库负担小。

### 明显局限

- 主聊天入口当前没有真正把 `memory_enabled` 打通，导致“agent 自动用记忆”这条最关键的产品路径还是半成品。
- 没有 durable worker / resume，memory create job 的可靠性依赖 web 进程不重启。
- 记忆作用域基本是 user-level，全局 `agent_id` 固定；`project_id` 不是一等隔离维度，README 里说的 project context 在实现上不够硬。
- 单条 memory CRUD 的应用层权限边界偏弱；再叠加 `get_current_user_id()` 里的 `default` 用户回退，这套长期记忆更像单用户/弱权限部署方案。
- 开记忆额外依赖 OpenAI 作为 mem0 的 LLM/embedder，即使主代理模型是 Claude；运维和成本链路被拉长。
- `history.db` 放在 backend 本地 `/tmp` 路径且未见持久化卷，历史审计能力不够稳。
