# 1. 一句话结论
`ZeroClaw` 有真正的长期记忆系统：`src/memory/` 提供了可插拔的持久化与召回抽象，默认以 `SqliteMemory` 做本地长期记忆与混合检索；但不同运行入口对这套能力的接线并不完全一致，所以“系统是真的有，产品一致性还不够”。

## 2. Product层面
- 记忆是显式产品能力，不是藏在 prompt 里的技巧：onboarding 可选 `sqlite/lucid/markdown/none`（`src/onboard/wizard.rs`），CLI 有 `zeroclaw memory list/get/stats/clear`（`src/main.rs`、`src/memory/cli.rs`），Web 有 `/api/memory` 和 `web/src/pages/Memory.tsx`。
- Agent 侧把记忆也作为工具暴露：`memory_store` / `memory_recall` / `memory_forget`（`src/tools/memory_store.rs`、`src/tools/memory_recall.rs`、`src/tools/memory_forget.rs`），并且 `auto_save` 会自动保存用户输入（`src/agent/loop_.rs`）。
- 代码里还实现了 `postgres` 和 `qdrant` backend（`src/memory/postgres.rs`、`src/memory/qdrant.rs`），但 onboarding 的一等选项只暴露 `sqlite/lucid/markdown/none`（`src/memory/backend.rs`、`src/onboard/wizard.rs`）。
- Web 面板更像“记忆表管理器”，不是“记忆调试器”：前端类型里有 `score` 和 `session_id`，但页面不展示，也没有注入前后对照（`web/src/types/api.ts`、`web/src/pages/Memory.tsx`）。
- `/api/memory` 的“搜索 + category 筛选”并不真正组合：只要有 `query`，后端就直接走 `recall`，忽略 `category`（`src/gateway/api.rs`）。

## 3. System层面
- 核心 contract 很清楚：`Memory` trait 统一了 `store/recall/get/list/forget/count/health_check`，并把 `MemoryCategory` 与可选 `session_id` 放进协议层（`src/memory/traits.rs`）。
- 默认强实现是 `src/memory/sqlite.rs`：`workspace/memory/brain.db` + `memories` 表 + `FTS5` + `embedding_cache`；排序由 `src/memory/vector.rs` 的 hybrid merge 做向量/关键词融合。
- `LucidMemory` 不是独立存储，而是“本地 SQLite 为 authoritative，外部 `lucid` CLI 做补充召回/同步”的桥接层；它有 `local_hit_threshold`、超时和失败冷却，明显是 local-first 设计（`src/memory/lucid.rs`）。
- `PostgresMemory` 是远端耐久存储，但只做 SQL 关键词召回；`QdrantMemory` 是纯向量召回；它们都不等价于 SQLite 的混合检索（`src/memory/postgres.rs`、`src/memory/qdrant.rs`）。
- `MarkdownMemory` 更像可读审计日志：简单、零依赖，但不是等价替代品（`src/memory/markdown.rs`）。
- 这套 memory 不只承载“用户事实”，也承载 SOP 审计记录，`SopAuditLogger` 直接把运行状态写进 memory backend 的自定义 `sop` 类别（`src/sop/audit.rs`）。

## 4. Lifecycle层面
- 写入入口有三类：工具/API 手动写入、`auto_save` 自动写入用户消息、SOP 审计写入（`src/tools/memory_store.rs`、`src/gateway/api.rs`、`src/agent/loop_.rs`、`src/sop/audit.rs`）。
- 自动保存明确排除了 assistant 输出；旧的 `assistant_resp*` 还会在注入时被过滤，避免模型自己写的总结重新回流成“事实”（`src/memory/mod.rs`、`src/agent/memory_loader.rs`、`src/agent/tests.rs`）。
- 生命周期治理不是只靠 TTL：`src/memory/hygiene.rs` 每 12 小时 best-effort 跑一次，归档旧 markdown/session 文件，并裁剪 SQLite 中过期的 `conversation` 记录。
- 快照/恢复只覆盖 `Core` 记忆：`MEMORY_SNAPSHOT.md` 是“灵魂备份”，不是完整历史备份；`Daily/Conversation/Custom` 默认不会随快照恢复（`src/memory/snapshot.rs`、`src/config/schema.rs`）。
- 不同入口的 autosave 语义并不完全一致：主 loop 在 `src/agent/loop_.rs` 用 `user_msg_<uuid>` 保留多条消息，但库级 `src/agent/agent.rs` 仍用固定 `user_msg` key 覆盖写入。

## 5. Injection层面
- 注入方式很朴素：先 `recall` top-5，再把结果拼成 `[Memory context]` 文本前缀塞到当前 user message 前面（`src/agent/memory_loader.rs`、`src/agent/loop_.rs`）。
- 注入前只做两层过滤：`min_relevance_score` 分数阈值 + `assistant_resp*` 黑名单；没有更细的信任等级、来源权重或冲突解决。
- `session_id` 虽然进入了 `Memory` 抽象和后端 schema（`src/memory/traits.rs`、`src/memory/sqlite.rs`），但多数真实入口都传 `None`，所以运行时基本是全局记忆池，而不是会话隔离池（`src/agent/loop_.rs`、`src/tools/memory_store.rs`、`src/tools/memory_recall.rs`、`src/gateway/api.rs`）。
- 一个很关键的不一致：文档声明支持 `[[embedding_routes]]`，而且 `Agent::from_config` 确实走 `create_memory_with_storage_and_routes`（`src/agent/agent.rs`）；但主运行入口 `src/agent/loop_.rs`、`src/channels/mod.rs`、`src/gateway/mod.rs` 走的是 `create_memory_with_storage`，没有把 `embedding_routes` 传进去。
- `/webhook` 简单聊天路径还存在“只写不读”的情况：`handle_webhook` 会 auto-save，但 `run_gateway_chat_simple` 不做 memory recall/injection（`src/gateway/mod.rs`）。

## 6. 抽象层面
- 这个 repo 最值得看的不是“向量搜索”本身，而是把 memory 当成一等基础设施：可替换 backend、统一工具入口、统一 API、统一 agent 注入点（`src/memory/mod.rs`、`src/tools/mod.rs`、`src/gateway/api.rs`）。
- `NoneMemory` 这种显式 no-op backend 设计很好，能让上层保持稳定 wiring，而不是到处 `Option<Memory>`（`src/memory/none.rs`）。
- 但抽象也明显泄漏：同一个 `Memory` trait 背后，SQLite 是混合召回，Postgres 是关键词，Qdrant 是纯向量，Markdown 是 append-only 文本；接口统一，不代表语义统一。
- 它把“用户事实”“聊天残留”“日记”“SOP 审计”都塞进同一个 memory substrate（`src/memory/traits.rs`、`src/sop/audit.rs`），工程上很省事，但语义边界比较混。

## 7. 值得借鉴 / 明显局限
- 值得借鉴：`LucidMemory` 的 local-first 方案很实用，`SQLite authoritative + external augmentation` 比“完全依赖远端记忆服务”稳得多（`src/memory/lucid.rs`）。
- 值得借鉴：把 `assistant_resp*` 当作不可信历史、明确禁止再注入，是很少见但非常对路的 memory hygiene 规则（`src/memory/mod.rs`、`src/agent/memory_loader.rs`）。
- 值得借鉴：只把 `Core` 记忆快照成 `MEMORY_SNAPSHOT.md`，让“长期身份/偏好”变成 Git 可见资产，而不是纯数据库黑箱（`src/memory/snapshot.rs`）。
- 明显局限：`MarkdownMemory` 会丢失原始 key 和非 core 类别，而且 `forget()` 永远失败；前端删除按钮却仍按“已删”处理，产品体验会误导用户（`src/memory/markdown.rs`、`web/src/lib/api.ts`、`web/src/pages/Memory.tsx`）。
- 明显局限：记忆接线存在多套实现，主路径对 `embedding_routes` 的支持不完整，简单 webhook 也不读记忆；这说明 memory 还没有真正成为全产品面一致生效的底层能力（`src/agent/agent.rs`、`src/agent/loop_.rs`、`src/channels/mod.rs`、`src/gateway/mod.rs`）。
- 明显局限：我推断 `snapshot` 恢复后的 `embedding_cache` schema 与 `SqliteMemory` 正常路径不完全一致，`src/memory/snapshot.rs` 创建的表缺少 `accessed_at`，而 `src/memory/sqlite.rs` 的后续缓存写入会假定该列存在；如果冷启动恢复后继续启用 embedding cache，这里有潜在风险。
