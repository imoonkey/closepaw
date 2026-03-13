## 1. 一句话结论

`mem0` 确实有真实的长期记忆内核，但本质是“单向量库记忆 + LLM 抽取/合并 + 可选图谱/审计”的 memory infrastructure，不是带自动注入、自动分层编排的完整 agent memory OS。

## 2. Product层面

- 产品定位很清楚：把 memory 做成独立基础设施，而不是聊天框内部功能。仓库里实际有三种交付形态：OSS SDK（`mem0/memory/main.py`，经由 `mem0/__init__.py` 暴露）、托管 Platform client（`mem0/client/main.py`）、以及本地 OpenMemory/MCP（`openmemory/api` + `openmemory/ui`，见 `docs/openmemory/overview.mdx`）。
- `README.md` 的主路径不是“让 mem0 直接替你聊天”，而是业务代码先 `memory.search()`，把结果拼进 prompt，再在回答后 `memory.add()`。这说明它卖的是“记忆层”，不是 agent runtime。
- OSS 不是假把式 demo。文档和配置都显示它默认就能落在本地长期存储上：向量库默认本地 Qdrant `/tmp/qdrant`，历史默认 SQLite `~/.mem0/history.db`（`docs/open-source/overview.mdx`、`mem0/configs/vector_stores/qdrant.py`、`mem0/configs/base.py`）。
- OpenMemory 不是另一套记忆算法，而是在 core memory 上加“多应用共享、UI 可见、ACL、暂停/归档/删除状态”的产品外壳（`openmemory/api/app/models.py`、`openmemory/api/app/mcp_server.py`）。

## 3. System层面

- 核心 orchestrator 是 `mem0/memory/main.py` 里的 `Memory` / `AsyncMemory`。初始化时装配 embedder、vector store、LLM、reranker、graph store、SQLite history，Provider 选择统一走 `mem0/utils/factory.py`。
- 真正的主存储模型很朴素：一个 memory item 本质就是 `text + metadata + embedding`。向量库 payload 里保存 `data/hash/created_at/updated_at/user_id/agent_id/run_id/actor_id/...`；graph 和 history 都是旁路增强，不是主 source of truth。
- Graph memory 是并行支路，不是核心必需件。写入时 `add()` 同时跑 `_add_to_vector_store()` 和 `_add_to_graph()`；查询时返回 `results` + `relations`，但 graph 不负责重排向量结果（`mem0/memory/main.py`、`mem0/memory/graph_memory.py`、`docs/open-source/features/graph-memory.mdx`）。
- OpenMemory 再叠一层关系数据库，维护 `User/App/Memory/AccessControl/MemoryState/MemoryAccessLog` 等模型，但语义存储/检索仍委托给 `mem0.Memory`（`openmemory/api/app/utils/memory.py`、`openmemory/api/app/routers/memories.py`）。
- 这意味着 OpenMemory 是明显的双写架构：向量层存“真实记忆文本”，SQL 层存“状态/权限/分类/可见性”；一致性靠应用层同步，不是单事务系统。

## 4. Lifecycle层面

- `add()` 的智能摄入链路是真实存在的，不是 append-only：
  1. 校验 `user_id/agent_id/run_id`，规范化 message。
  2. `infer=True` 时先用 LLM 抽 facts（`mem0/memory/utils.py`、`mem0/configs/prompts.py`）。
  3. 每个 fact 先去向量库找 top-5 旧记忆。
  4. 再让 LLM 产出 `ADD/UPDATE/DELETE/NONE`。
  5. 按动作对向量库做增改删，并写 SQLite history（`mem0/memory/storage.py`）。
- `infer=False` 是原文直写，直接绕过去重和冲突解决；这更像 transcript store，而不是“智能长期记忆”。
- `search()` 的真实路径是：query embed -> vector search -> optional rerank -> optional graph search。它有 retrieval，但没有 consolidation、summary compaction、遗忘循环。
- `history()` 明确提供审计轨；OpenMemory 额外引入 `active / paused / archived / deleted` 状态和 access log（`openmemory/api/app/models.py`、`openmemory/api/app/routers/memories.py`）。
- 代码和产品叙事有一个关键落差：`docs/core-concepts/memory-types.mdx` 把 conversation/session/user/org 讲成分层记忆，但 `mem0/memory/main.py` 里的 `_build_filters_and_metadata()` 只是把 `user_id/agent_id/run_id` 写入 metadata/filter；如果同时传多个 ID，本质上是交集过滤，不是“短期 + 长期”联合召回。
- 另一个落差是 memory type。`mem0/configs/enums.py` 有 `semantic_memory / episodic_memory / procedural_memory`，但 `mem0/memory/main.py` 里只有 `procedural_memory` 走独立分支；semantic/episodic 没有单独实现。
- 自动归档/过期在 OSS core 里也不成立。OpenMemory 虽然有 `ArchivePolicy` 表模型（`openmemory/api/app/models.py`），但仓库里看不到实际执行器；platform docs 里的 expiration 更像托管版能力。

## 5. Injection层面

- OSS core 没有自动注入。`mem0/memory/main.py` 的 `chat()` 直接 `NotImplemented`，而 `README.md` 明确要求业务方自己 `search()` 后把 memories 拼进 system prompt。
- 所以 mem0 更像 retrieval-ready memory service，而不是会自动把记忆塞回 agent context 的 runtime middleware。
- 仓库里的注入渠道主要有三种：
- 代码显式调用 SDK：`Memory` / `AsyncMemory`。
- HTTP 薄封装：`server/main.py` 暴露 `/memories`、`/search` 等 REST。
- MCP 工具注入：`openmemory/api/app/mcp_server.py` 定义 `add_memories`、`search_memory`、`list_memories` 等 tools，而且 tool 描述直接要求“每次用户提问都调用搜索”。
- OpenMemory 还做了一种“摄入期注入”：把配置里的 `custom_instructions` 映射成 `custom_fact_extraction_prompt`，影响记忆抽取规则，而不是回答时上下文注入（`openmemory/api/app/utils/memory.py`、`openmemory/api/app/routers/config.py`）。
- Platform 的 `custom_instructions / custom_categories / retrieval_criteria / memory_depth` 只在 `mem0/client/main.py`、`mem0/client/project.py` 里作为远端 API wrapper 出现；这个 repo 里看不到托管端的真实检索编排实现。

## 6. 抽象层面

- 好的一面是 Provider 抽象比较干净：LLM、embedder、vector store、graph store、reranker 都能替换，核心编排几乎不关心底层供应商（`mem0/utils/factory.py`）。
- `MemoryBase` 只保留极小 CRUD 契约，`MemoryItem` 统一了外部返回形状（`mem0/memory/base.py`、`mem0/configs/base.py`）。
- 但 memory 的真实抽象其实很薄：核心仍然是“文本事实 + metadata + embedding”；`user_id/agent_id/run_id` 只是 scope tag，不是第一类 memory tier。
- “统一过滤语法”也有明显抽象泄漏。`Memory.search()` 支持高级 operators，但不同 vector store 适配程度不一致：例如 Qdrant 只做很窄的 range/equality，Chroma 会把 `contains` 退化成 `eq`，Supabase 基本只保留 `$eq/$and`（`mem0/memory/main.py`、`mem0/vector_stores/qdrant.py`、`mem0/vector_stores/chroma.py`、`mem0/vector_stores/supabase.py`）。
- 维护性上，`mem0/memory/main.py` 同时塞了 sync + async 两整套实现，文件已经 2300+ 行；抽象边界不够健康。
- OpenMemory 的抽象也比较“产品态”而不是“系统态”：它把向量层 UUID 直接复用到 SQL `Memory.id`，从而把 ACL/状态层和 semantic layer 松耦合地绑在一起。

## 7. 值得借鉴 / 明显局限

**值得借鉴**

- 把 memory 独立成基础设施层，而不是继续堆聊天历史。
- 用“两阶段 LLM”做摄入：先抽 fact，再决策 add/update/delete，比 naive append 更接近长期记忆。
- 把 history、graph、ACL/UI 设计成外挂层，核心 vector memory 保持简单。
- 同一套 core 同时支撑 SDK、REST、MCP、OpenMemory UI，这个产品面组织方式很强。

**明显局限**

- “多层记忆 / session vs user / semantic vs episodic”更多是产品叙事，不是代码里的真实编排。
- 回答期注入完全靠调用方自觉；agent 如果忘了先 search，系统本身不会补救。
- OpenMemory 的 SQL 元数据层和向量层是双写同步，天然有漂移风险。`openmemory/api/app/routers/memories.py` 里能看到“先写向量再镜像到 SQL”，`openmemory/api/app/mcp_server.py` 里能看到“直接搜向量再按 SQL ACL 过滤”的模式。
- 托管 Platform 的很多高级能力在这个 repo 里只是 docs 或 client surface，不是可复用的本地实现。
- 核心代码过于集中，sync/async 重复明显，后续深改成本会高。
