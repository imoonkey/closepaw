# 1. 一句话结论

NextClaw **有轻量的长期记忆**，但它不是独立的 memory backend；本体是 workspace 下的 `MEMORY.md` / `memory/MEMORY.md` / `memory/YYYY-MM-DD.md` 文件，通过 **prompt 注入** 和 **`memory_search` / `memory_get` 检索** 两条路径使用，**没有自动写回、结构化索引、也没有把 session history 自动沉淀成 memory**。

## 2. Product层面

- 用户侧暴露的 memory 能力很克制：PRD 里只有 `Memory 检索（search/get）`，而不是单独的 memory 产品面板或 memory workflow，见 `docs/prd/current-feature-list.md`、`docs/feature-universe.md`。
- `nextclaw init` 会给 workspace 初始化 memory 相关文件：根目录 `MEMORY.md`，以及 `memory/MEMORY.md` 和 `memory/` 目录，见 `packages/nextclaw/src/cli/workspace.ts`、`packages/nextclaw/templates/MEMORY.md`、`packages/nextclaw/templates/memory/MEMORY.md`。
- 产品里另有一整套 session history 能力，可查看/清空历史，但这是“会话转录”而不是“长期记忆”，见 `packages/nextclaw-core/src/session/manager.ts`、`packages/nextclaw-core/src/agent/tools/sessions.ts`、`packages/nextclaw-server/src/ui/config.ts`。

## 3. System层面

- 核心存储抽象在 `packages/nextclaw-core/src/agent/memory.ts` 的 `MemoryStore`。它实际拼三类内容：根目录 `MEMORY.md`（代码里叫 `Workspace Memory`）、`memory/MEMORY.md`（`Long-term Memory`）、以及当天的 `memory/YYYY-MM-DD.md`（`Today's Notes`）。
- 检索实现很简单：`packages/nextclaw-core/src/agent/tools/memory.ts` 的 `MemorySearchTool` 只对 `MEMORY.md` 和 `memory/` 目录下一层的 `.md` 文件做**大小写不敏感的逐行 substring 搜索**。没有 embedding、没有向量库、没有 rerank、没有递归目录扫描。
- 读取实现也很克制：`MemoryGetTool` 只允许读取 workspace 内的 `MEMORY.md` 或 `memory/*.md`，并按行号返回片段，防止把它当成任意文件读取器。
- 这些 memory 工具是 agent 默认工具的一部分，由 `packages/nextclaw-core/src/agent/loop.ts` 注册。

## 4. Lifecycle层面

- 生命周期起点是 workspace 模板：`nextclaw init` 负责把 memory 文件骨架放到工作区，后续 memory 是否有内容主要靠人或 agent 自己维护，见 `packages/nextclaw/src/cli/workspace.ts`。
- 每个 turn 构造系统提示时，`ContextBuilder` 会调用 `buildMemorySection()` 把 memory 内容直接拼进 prompt，见 `packages/nextclaw-core/src/agent/context.ts`。
- 运行中，system prompt 还会强制提示模型：遇到“prior work / decisions / preferences / todos”这类问题，先跑 `memory_search`，再用 `memory_get` 读精确片段，见 `packages/nextclaw-core/src/agent/context.ts`。
- **没有自动沉淀闭环**：`MemoryStore.appendToday()` 和 `writeLongTerm()` 虽然存在，但仓库里没有调用点；也没有 `memory_write` / `memory_append` 之类专用工具。这意味着“写记忆”主要依赖通用 `write_file` / `edit_file` 和模板里的行为约定，而不是系统机制。
- session history 会持久化到独立的 sessions 目录（`packages/nextclaw-core/src/utils/helpers.ts` + `packages/nextclaw-core/src/session/manager.ts`），但没有代码把这些历史自动提炼进 memory 文件。

## 5. Injection层面

- 它其实有两条 memory 注入链路：
- 第一条是**预注入**：`packages/nextclaw-core/src/agent/context.ts` 会把 `MemoryStore.getMemoryContext()` 的结果直接塞进 system prompt 的 `# Memory` 段落。
- 第二条是**按需检索**：同一个 system prompt 又要求模型在需要“回忆”时主动调用 `memory_search` / `memory_get`。
- 注入量受全局配置 `agents.context.memory.enabled` / `agents.context.memory.maxChars` 控制，默认 `enabled=true`、`maxChars=8000`，见 `packages/nextclaw-core/src/config/schema.ts`。
- 这段 memory 没有独立预算优先级；`packages/nextclaw-core/src/agent/input-budget-pruner.ts` 会先裁工具结果、再丢旧 history，最后必要时直接截 system prompt，所以 memory 注入虽然比 history 更“靠前”，但仍可能被截断。

## 6. 抽象层面

- 这里的 memory 抽象本质上是：**workspace-scoped 的 Markdown 文件集合 + 两个工具包装器**，不是数据库、不是检索服务、也不是事件驱动的记忆管线。
- 配置层只有 `agents.context.memory.enabled/maxChars` 这类全局开关，没有 per-agent 的 memory policy / memory backend / retrieval strategy，见 `packages/nextclaw-core/src/config/schema.ts`、`packages/nextclaw-core/src/config/schema.help.ts`。
- 多 agent 隔离更多是靠 `agents.list.*.workspace` 把不同 agent 指到不同 workspace，从而间接隔离 memory 语料，而不是在同一 workspace 里做一层 first-class memory namespace，见 `packages/nextclaw/src/cli/commands/agent-runtime-pool.ts`。
- 兼容层上，`packages/nextclaw-openclaw-compat/src/plugins/runtime.ts` 把 memory 暴露成 `createMemorySearchTool` / `createMemoryGetTool`，说明它的抽象优先级是“工具接口兼容”，不是“memory subsystem”。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：足够简单。用人可读的 Markdown 文件做长期记忆，调试成本低，迁移成本低，不需要额外基础设施。
- 值得借鉴：同时保留“默认注入”和“按需检索”两条路。小项目里这比一上来做 RAG backend 更务实。
- 值得借鉴：memory 与 workspace 强绑定，天然适合按项目/身份隔离；如果 agent workspace 分开，memory 也会自然分开。
- 明显局限：没有自动写回，没有 session->memory 提炼，没有 importance/TTL/merge 机制，所以它不会“自己长出长期记忆”。
- 明显局限：检索只是 literal substring 搜索；`minScore` 参数甚至被忽略，排序分数恒定，适合小规模笔记，不适合复杂召回。
- 明显局限：memory 文件约定本身有点混乱。代码同时读根目录 `MEMORY.md` 和 `memory/MEMORY.md`，但文档很多地方只强调 `memory/MEMORY.md`；这会让用户不清楚“长期记忆到底写哪一个”。
- 明显局限：写接口 (`appendToday` / `writeLongTerm`) 目前是未接线状态，且仓库里没有对应测试，说明这块还不是成熟的闭环系统。
