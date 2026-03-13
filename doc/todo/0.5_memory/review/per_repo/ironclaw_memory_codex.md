# 1. 一句话结论

IronClaw 有一套真实的长期记忆系统：它把 memory 做成数据库里的 workspace 文件系统，再用系统提示常驻文件 + `memory_search/read/write/tree` 工具完成读写；但 recall 主要靠 agent 主动用工具，而不是系统强制检索，且当前产品接线基本是单个全局 workspace。

# 2. Product层面

- 面向用户的记忆形态不是 KV 或单条 summary，而是一个可浏览、可搜索、可编辑的 workspace：`MEMORY.md`、`IDENTITY.md`、`SOUL.md`、`AGENTS.md`、`USER.md`、`TOOLS.md`、`HEARTBEAT.md`、`daily/`、`context/` 以及任意自定义路径，见 `src/workspace/README.md` 和 `src/workspace/document.rs`。
- 同一套 memory 暴露给三条产品面：LLM 工具 `memory_search/read/write/tree`（`src/tools/builtin/memory.rs`）、CLI `ironclaw memory ...`（`src/cli/memory.rs`）、Web Gateway 的 `/api/memory/*`（`src/channels/web/server.rs`, `src/channels/web/handlers/memory.rs`）。
- 产品体验上分成两层：`MEMORY.md`/身份文件/近两天 daily logs 是“常驻热记忆”，其他任意文档是“按需搜索的冷记忆”。

# 3. System层面

- 底层存储是真数据库，不是进程内状态：`memory_documents` 存路径化文档，`memory_chunks` 存切块、FTS 向量和 embedding，见 `migrations/V1__initial.sql`。
- 检索链路是 `Workspace.search()` -> 后端 `hybrid_search()` -> RRF 融合 FTS 与向量结果，核心在 `src/workspace/search.rs`、`src/workspace/repository.rs`、`src/db/libsql/workspace.rs`。
- 抽象上支持 `user_id + agent_id` 隔离，也支持 Postgres / libSQL 双后端，见 `src/workspace/mod.rs` 和 `src/db/mod.rs`；但应用装配时实际只创建了 `Workspace::new_with_db("default", db)`，所以当前产品更像单 workspace / 单用户记忆，见 `src/app.rs`。

# 4. Lifecycle层面

- 启动时会先可选导入磁盘模板，再 `seed_if_empty()` 补齐核心文档，若启用 embedding provider 再异步 `backfill_embeddings()`，见 `src/app.rs` 和 `src/workspace/mod.rs`。
- 每次 `write/append` 都会触发 reindex：删旧 chunks -> 重新 chunk（默认 800 词、15% overlap）-> 逐 chunk 生成 embedding，见 `src/workspace/mod.rs` 和 `src/workspace/chunker.rs`。
- 长对话压缩不会直接沉淀到 `MEMORY.md`，而是写进 `daily/YYYY-MM-DD.md`；heartbeat 会读取 `HEARTBEAT.md`，并顺手触发 memory hygiene 清理旧的 `daily/` / `conversations/` 文档，见 `src/agent/compaction.rs`、`src/agent/heartbeat.rs`、`src/workspace/hygiene.rs`。

# 5. Injection层面

- 注入顺序很明确：`BOOTSTRAP.md`（若存在）-> `AGENTS.md` -> `SOUL.md` -> `USER.md` -> `IDENTITY.md` -> `TOOLS.md` -> `MEMORY.md`（群聊时排除）-> 今天/昨天的 daily log，核心在 `src/workspace/mod.rs`。
- 这不是纯 `search-then-answer` 架构，而是“固定热记忆 + 按需检索”架构；`dispatcher` 只负责把 system prompt 和工具列表送进 LLM，不会在回答前自动跑 recall，见 `src/agent/dispatcher.rs`。
- 安全边界主要靠写保护而不是内容净化：`memory_write` 禁止覆盖 `IDENTITY/SOUL/AGENTS/USER`，`write_file` 也会拒绝写 workspace 路径；但 memory 工具本身把 workspace 内容视为 trusted，不做 sanitization，见 `src/tools/builtin/memory.rs` 和 `src/tools/builtin/file.rs`。
- 对安装型 skill 的权限衰减也考虑到了 memory：`Installed` skill 只能保留 `memory_search/read/tree`，拿不到 `memory_write`，见 `src/skills/attenuation.rs`。

# 6. 抽象层面

- `Workspace` 是真正的核心抽象：上层只看 `read/write/list/search/system_prompt` 这套 filesystem-like API，下层再决定是 Postgres 还是 libSQL，见 `src/workspace/mod.rs`。
- “长期记忆”与“运行时短记忆”是分开的：`src/context/memory.rs` 里的 `ConversationMemory/ActionRecord` 只是 job 内存，不是跨 session 的 workspace memory。
- 同一抽象同时服务 agent tool、CLI、Web API，这减少了三套记忆系统分叉的风险；路径 convention（`MEMORY.md`, `daily/`, `context/` 等）本身也是抽象的一部分，见 `src/workspace/document.rs`。

# 7. 值得借鉴 / 明显局限

**值得借鉴**

- 把 memory 做成“可编辑文件系统 + 搜索索引”而不是 opaque store，用户、CLI、Web、Agent 共用一份真数据。
- “热记忆注入 + 冷记忆搜索”分层很实用：人格与近期上下文常驻，长尾知识走 `memory_search`。
- 对 LLM 写路径做了硬隔离：身份文件保护、filesystem tool 与 workspace tool 分离、installed skill 无法写 memory。

**明显局限**

- recall 不是强制链路，更多靠 prompt 约束和 tool description；模型没主动调 `memory_search` 就会漏召回，见 `src/tools/builtin/memory.rs` 和 `src/workspace/mod.rs` 里默认 seed 的 `AGENTS.md`。
- “支持多租户”更多停留在 schema / API 层，产品 wiring 仍是全局 `"default"` workspace，见 `src/app.rs` 和 `src/cli/memory.rs`。
- 语义检索不是总能落地：embeddings 默认关闭（`src/config/embeddings.rs`），libSQL 在 `V9` 之后也可能退化为 FTS-only，见 `src/db/libsql/workspace.rs` 和 `migrations/V9__flexible_embedding_dimension.sql`。
- prompt 注入面仍偏大：`MEMORY.md` 与近两天 daily logs 是全文直注；群聊只排除了 `MEMORY.md`，没有排除 `USER.md` 和 daily logs，见 `src/workspace/mod.rs`。
- `seed_if_empty()` 的 fresh-workspace 判断在补齐 `AGENTS/SOUL/USER` 之后才执行，`BOOTSTRAP.md` 的首次引导大概率不会真的被 seed，见 `src/workspace/mod.rs`。
