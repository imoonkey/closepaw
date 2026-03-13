# 1. 一句话结论

PicoClaw 没有真正的“长期记忆系统”或语义检索层；它的 memory 本质上是 `workspace/memory/MEMORY.md` 这类文件式笔记，加上 `sessions/` 里的会话历史与摘要压缩，前者靠 Agent 自己写文件，后者才是工程化持久层。

# 2. Product层面

- 产品对外把 memory 当成工作区核心能力之一来讲：README 的工作区布局里明确有 `memory/` 和 `sessions/`，并把它展示为 “Schedule • Automate • Memory”（`.reference/claws/picoclaw/README.md`，`.reference/claws/picoclaw/README.zh.md`）。
- 真正面向用户的长期记忆载体是模板文件 `.reference/claws/picoclaw/workspace/memory/MEMORY.md`，只有 `User Information`、`Preferences`、`Important Notes`、`Configuration` 这类栏目，定位更像“跨会话记事本”，不是知识库或用户画像引擎。
- 仓库里没有独立的 memory UI 或 memory API。Web 侧暴露的是 session history（`.reference/claws/picoclaw/web/backend/api/session.go`、`.reference/claws/picoclaw/web/frontend/src/hooks/use-session-history.ts`），不是长期记忆管理。

# 3. System层面

- 长期记忆由 `.reference/claws/picoclaw/pkg/agent/memory.go` 的 `MemoryStore` 管理，只处理两个物理形态：`memory/MEMORY.md` 和 `memory/YYYYMM/YYYYMMDD.md`。
- `GetMemoryContext()` 的做法非常直接：读取 `MEMORY.md` 全文，再拼接最近 3 天 daily notes，整体塞进 prompt；没有 chunk、索引、检索、ranking、去重或冲突解决。
- 另一套名为 `pkg/memory` 的模块其实不是“长期记忆”，而是 session persistence：`.reference/claws/picoclaw/pkg/memory/store.go` 定义接口，`.reference/claws/picoclaw/pkg/memory/jsonl.go` 用 append-only `.jsonl` + `.meta.json` 保存消息、摘要、skip offset。
- `.reference/claws/picoclaw/pkg/session/jsonl_backend.go` 把 `memory.Store` 适配成 `session.SessionStore`，所以 Agent 主流程里真正稳定接入的是“会话存储”，不是“长期知识存储”。
- 结论上，这个仓库的 memory 架构是“两层但不对称”的：会话记忆是完整子系统，长期记忆只是工作区文件约定。

# 4. Lifecycle层面

- Agent 启动时，`.reference/claws/picoclaw/pkg/agent/instance.go` 的 `initSessionStore()` 默认初始化 JSONL session store；如果发现旧的 `sessions/*.json`，会通过 `.reference/claws/picoclaw/pkg/memory/migration.go` 迁移过去。
- 每次请求进入 `.reference/claws/picoclaw/pkg/agent/loop.go` 时，会先读取 session `history` 和 `summary`，然后把用户消息、assistant 回复、tool call / tool result 逐条落盘。
- 当会话过长时，`maybeSummarize()` 会异步触发 `summarizeSession()`；默认阈值是 20 条消息或上下文窗口的 75%（`.reference/claws/picoclaw/pkg/config/defaults.go`、`.reference/claws/picoclaw/config/config.example.json`）。
- `summarizeSession()` 只摘要 `user` / `assistant` 消息，写回 `summary`，再把历史截断到最近 4 条并执行 compaction（`.reference/claws/picoclaw/pkg/agent/loop.go`）。
- 相比之下，daily notes 虽然在 `.reference/claws/picoclaw/pkg/agent/memory.go` 里有 `AppendToday()` / `ReadToday()`，但仓库里没有主流程调用点；也就是说它存在文件结构，但没有自动写入生命周期。

# 5. Injection层面

- `.reference/claws/picoclaw/pkg/agent/context.go` 在 system prompt 里直接写死规则：如果有值得记住的事，就去更新 `memory/MEMORY.md`。
- `BuildSystemPrompt()` 会把 identity/bootstrap files/skills/memory 合成静态 prompt；`BuildMessages()` 再把静态 prompt、动态上下文、可选的 `CONTEXT_SUMMARY` 合并成一个单独的 `system` message。
- 之后 provider 层直接消费这一个 system message：`.reference/claws/picoclaw/pkg/providers/codex_provider.go` 把它映射到 `instructions`，`.reference/claws/picoclaw/pkg/providers/anthropic/provider.go` 把 `SystemParts` 映射到 top-level `system`。
- 这说明记忆注入方式是“system 前缀注入”，不是“按 query 触发的 memory retrieval”。
- 长期记忆写入也不是专用 API，而是依赖通用文件工具 `read_file` / `write_file` / `append_file` 是否启用（`.reference/claws/picoclaw/pkg/agent/instance.go`、`.reference/claws/picoclaw/config/config.example.json`）。

# 6. 抽象层面

- 会话记忆的抽象比较完整：`session.SessionStore` <- `session.JSONLBackend` <- `memory.Store` <- `memory.JSONLStore`。这层抽象让 JSON 后端和 JSONL 后端可以平滑切换。
- 长期记忆没有类似分层；只有一个具体实现 `agent.MemoryStore`，而且主流程里几乎只用到 `GetMemoryContext()`。
- `WriteLongTerm()`、`AppendToday()` 这些写接口虽然存在，但在仓库里没有被 Agent 主流程直接调用，说明长期记忆的写路径并没有成为一等系统接口。
- 所以从抽象设计上看，PicoClaw 把“session memory”当成基础设施，把“long-term memory”当成 prompt 习惯和工作区约定。

# 7. 值得借鉴 / 明显局限

- 值得借鉴：把“长期笔记”和“会话历史”拆开是对的。`.reference/claws/picoclaw/pkg/agent/memory.go` 负责用户可编辑的稳定事实，`.reference/claws/picoclaw/pkg/memory/jsonl.go` 负责高频追加的对话流，职责边界很清楚。
- 值得借鉴：session store 用 append-only JSONL + meta skip + compaction，简单、便宜、容错也不错，适合边缘设备或轻量 Agent。
- 值得借鉴：把静态 memory/skills/bootstrap 合成单 system message，并给静态块做 cache，是很实用的 prompt 注入工程化方案（`.reference/claws/picoclaw/pkg/agent/context.go`）。
- 明显局限：它没有真正的长期记忆能力。没有抽取策略、没有 embedding/vector index、没有检索排序、没有事实冲突处理、没有重要性/新鲜度管理。
- 明显局限：长期记忆更新依赖模型自己遵守提示词去改文件，稳定性取决于模型行为和文件工具可用性，不是受控的 memory pipeline。
- 明显局限：`.reference/claws/picoclaw/pkg/agent/context.go` 的 cache invalidation 只跟踪 `memory/MEMORY.md`，不跟踪 `memory/YYYYMM/YYYYMMDD.md`；但 `GetMemoryContext()` 又会读取最近 3 天 daily notes。这意味着 daily notes 更新后，system prompt cache 可能不会立刻失效，最近笔记可能注入陈旧内容。
- 明显局限：Web session API 仍按旧 `.json` 文件读取（`.reference/claws/picoclaw/web/backend/api/session.go`），而运行时默认 session backend 已经是 `.jsonl + .meta.json`（`.reference/claws/picoclaw/pkg/agent/instance.go`、`.reference/claws/picoclaw/pkg/memory/jsonl.go`）；这说明外部可见的“记忆”与真实存储层之间存在实现漂移。
