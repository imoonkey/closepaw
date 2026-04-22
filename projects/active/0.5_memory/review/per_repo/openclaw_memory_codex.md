## 1. 一句话结论

`openclaw` 有真实的长期记忆系统，但主线实现不是“统一的自动 memory DB”，而是“workspace Markdown（`MEMORY.md` / `memory.md` / `memory/*.md`）作为真相源 + per-agent 语义索引/检索 + compaction 前静默落盘”；`extensions/memory-lancedb` 是可替换的实验型分支，不是默认主路径。

## 2. Product层面

- 默认 memory 产品面来自 `extensions/memory-core/index.ts`：给 agent 暴露 `memory_search` / `memory_get`，给运维暴露 `openclaw memory ...` CLI（`src/cli/memory-cli.ts`，`docs/cli/memory.md`）。
- 面向用户的“长期记忆”本质上是工作区文件，不是隐藏状态；`docs/concepts/memory.md` 明确把“写入磁盘”定义为记忆成立的前提。
- 另有 `extensions/memory-lancedb/index.ts`，提供 `memory_recall` / `memory_store` / `memory_forget`，并支持 autoRecall/autoCapture，但这是替换型 memory plugin，不是默认体验。
- 默认失败语义也比较产品化：memory 不可用时，`memory_search` 返回 `disabled/unavailable` 结果而不是直接抛错（`src/agents/tools/memory-tool.ts`）。

## 3. System层面

- 真相源是 Markdown；索引层是 `src/memory/*`。`src/agents/memory-search.ts` 解析 per-agent 配置，默认索引源只有 `"memory"`，索引文件默认落在 `~/.openclaw/memory/<agentId>.sqlite`。
- `src/memory/manager.ts` + `src/memory/manager-sync-ops.ts` + `src/memory/manager-search.ts` 负责 chunking、embeddings、watcher、异步 sync、BM25 + vector 混合检索、可选 `sqlite-vec`、embedding cache、MMR、temporal decay。
- `src/memory/index.ts` 把 backend 再抽象成 `MemorySearchManager`，可在 builtin SQLite 和 QMD 间切换，并在 QMD 失败时回退 builtin。
- Session transcript memory 不是默认能力：builtin 需要 `experimental.sessionMemory: true` 且 `sources` 包含 `"sessions"`；QMD 需要 `memory.qmd.sessions.enabled = true`。

## 4. Lifecycle层面

- 每次 agent run 会把 `MEMORY.md` / `memory.md` 当 bootstrap file 注入（`src/agents/workspace.ts`，`src/agents/bootstrap-files.ts`）。
- Memory index 在 session start、search、watch、interval 上异步维护；`MemoryIndexManager.warmSession()` 和 `manager-sync-ops.ts` 的 watcher / delta sync 是主链路。
- 接近 compaction 时，`src/auto-reply/reply/agent-runner-memory.ts` 会触发一次隐藏的 `trigger: "memory"` run；`src/auto-reply/reply/memory-flush.ts` 会提示模型把 durable info 写入 `memory/YYYY-MM-DD.md`，并把 `memoryFlushAt` / `memoryFlushCompactionCount` 写回 `sessions.json`。
- `/new` / `/reset` 时还有一个可选 bundled hook `src/hooks/bundled/session-memory/handler.ts`，会把上一段会话摘成 `memory/YYYY-MM-DD-slug.md`。它更像“会话归档进记忆目录”，不是核心 retrieval pipeline。
- `memory-lancedb` 自己还有一套生命周期：`before_agent_start` 做 auto-recall，`agent_end` 做 auto-capture（只看 user message，带去重和长度阈值）。

## 5. Injection层面

- 注入分三层：1) bootstrap 常驻注入 `MEMORY.md` / `memory.md`；2) system prompt 明确要求“涉及历史事实先跑 `memory_search` 再 `memory_get`”（`src/agents/system-prompt.ts`）；3) plugin hook 可用 `before_prompt_build` / `before_agent_start` 直接 prepend context（`src/plugins/types.ts`，`src/plugins/hooks.ts`）。
- 默认 `memory-core` 不做自动 recall，只提供工具；真正 recall 主要依赖模型遵循 prompt 主动调用工具。
- `memory-lancedb` 才是 hook 注入式 recall：`before_agent_start` 把召回结果包装成 `<relevant-memories>` 塞进 prompt，`agent_end` 再自动 capture。
- `memory/*.md` 日志文件不会自动进上下文；它们只有在 agent 主动用 tool 取回时才消耗 token（`docs/concepts/system-prompt.md`）。

## 6. 抽象层面

- 设计上把“memory plugin slot”（`src/plugins/slots.ts`）、“retrieval backend”（builtin/QMD）、“truth layer”（Markdown files）、“prompt/hook injection”分开了，替换点很清晰。
- 但仓库里其实并存两套 memory 哲学：主线是 file-backed memory；`memory-lancedb` 是 DB-backed memory with dedicated store/forget tool。两者没有统一成一个一致的读写模型。
- 安全抽象也很直接：`MEMORY.md` / `memory/*.md` 被视为 trusted local operator state，不是隔离边界（`SECURITY.md`）。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：用 Markdown 做 source of truth、索引做派生层，调试/备份/重建都简单；这比“只有向量库、没有原始记忆文件”更实用。
- 值得借鉴：pre-compaction silent memory flush 很好，它把短期上下文压力和长期记忆写入明确接上了。
- 值得借鉴：`MemorySearchManager` + builtin/QMD fallback + graceful unavailable payload，让 memory 能力失败时不至于把整个 agent 打挂。
- 明显局限：默认主线没有强制 auto-recall，更多是“靠 prompt 提醒模型先查记忆”；如果模型没按规约调用 `memory_search`，记忆就不会生效。
- 明显局限：文档多处说 `MEMORY.md` 只应在 main private session 加载（`docs/concepts/memory.md`，`docs/concepts/agent-workspace.md`），但代码里的 `filterBootstrapFilesForSession()` 只排除了 `subagent/cron`，看不到对 group/channel 的同级约束；文档与实现有偏差。
- 明显局限：核心 docs 推 `memory/YYYY-MM-DD.md` 作为 canonical daily file，但 `session-memory` hook 实际写的是 `memory/YYYY-MM-DD-slug.md`，memory 目录内的写入形态并不完全统一。
