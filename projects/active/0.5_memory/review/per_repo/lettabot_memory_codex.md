## 1. 一句话结论

`lettabot` 确实有“长期记忆产品能力”，但 memory kernel 不在这个仓库里：本仓库主要负责用 `src/memories/*.mdx` 初始化 Letta memory blocks、按 channel/chat 路由 conversation，并把 `agentId` / `conversationId` 持久化；真正的长期记忆存储、检索、编辑和反思主要依赖 Letta agent / Letta Code SDK。

## 2. Product层面

- 它把“跨渠道持续记忆”做成了产品主卖点：`README.md` 和 `docs/README.md` 都强调 single agent / unified memory，可在 Telegram、Slack、Discord、WhatsApp、Signal 之间连续对话。
- 它明确区分了两种东西：`docs/configuration.md` 里写得很清楚，**agent memory blocks 始终共享**，真正按 `shared` / `per-channel` / `per-chat` 隔离的是 **message history**。
- `listen` 模式是一个很实用的产品设计：组消息仍然送进 agent 处理“用于 context/memory”，但默认不回复；对应代码在 `src/core/bot.ts`，只是 suppress delivery，不是跳过 agent 运行。
- OpenAI-compatible 接口也延续这个思路：`docs/openai-compat.md` 明确只提取最后一条 user message，多轮上下文交给 Letta 的 conversation history 和 memory，而不是让客户端重复上传历史。

## 3. System层面

- 这个仓库里的 memory 系统可以拆成 3 层：
- `src/core/memory.ts` + `src/memories/*.mdx`：初始化 seed memory blocks。
- `src/core/session-manager.ts` + `src/tools/letta-api.ts`：把 session、agent、conversation 接到 Letta 平台，真实 memory 在这里的外部系统里。
- `src/core/store.ts`：本地薄状态层，只保存 `agentId`、`conversationId`、per-key conversation map、少量恢复状态，不保存记忆正文。
- 没看到仓库内自建的向量库、embedding pipeline、semantic recall、memory summarizer 或 memory compaction。若有检索/编辑能力，也是 Letta/SDK 自带，不是 `lettabot` 自己实现。
- `src/main.ts` 只是把 `memfs` 和 `sleeptime` 透传给 SDK。`docs/configuration.md` 也说明 `memfs` 对应的是 Letta 的 context repository，同步位置在 `~/.letta/agents/<agent-id>/memory/`，不是 repo 内部自定义存储。
- `src/todo/store.ts` 确实有一个本地 JSON 持久化待办系统，但它更像 task memory，不是这个产品的统一长期语义记忆层。

## 4. Lifecycle层面

- 创建期：首次没有 agent 时，`src/core/session-manager.ts` 会 `createAgent({ systemPrompt, memory: loadMemoryBlocks(...) })`；`src/onboard.ts` 的 eager create 也走同一路径。
- 运行期：后续大多数时候只是 `resumeSession(...)` / `createSession(...)` 到既有 agent 或 conversation；本地 store 记的是句柄，不是记忆本身。
- 隔离期：conversation routing 决定“哪段历史被复用”。`docs/configuration.md` 定义了 `disabled` / `shared` / `per-channel` / `per-chat`；但不管怎么切，agent memory blocks 仍然是共享的。
- 重置期：`/reset` 只清 conversation，不清 agent memory。`src/core/bot.ts` 和 `src/cli.ts` 都明确提示 “Agent memory is preserved”。
- 后台更新期：heartbeat / cron / polling 都能在 silent mode 驱动 agent；`docs/configuration.md` 里的 `sleeptime` 进一步允许后台反思更新 memory，但只有 `memfs: true` 时才生效，`src/main.ts` 里也专门做了这层保护。
- 回收期：`per-chat` 模式下 subprocess session 会被 LRU 驱逐，但 `conversationId` 继续保存在 `src/core/store.ts`，所以历史不会因进程回收而丢失。

## 5. Injection层面

- 唯一明确的本地 memory injection 入口是 `src/core/memory.ts`：它读取 `src/memories/*.mdx`，解析 frontmatter 的 `label` / `description` / `limit`，再把正文作为 block value 传给 Letta SDK。
- 这些 blocks 基本分两类：`persona/*` 用于 agent 自我与表达风格，`human/*` 用于用户画像与长期了解。它们更像“高层语义槽位”，不是严格 schema。
- `{{AGENT_NAME}}` 会在加载时替换，说明这批文件是 bootstrap prompt-memory，而不是按用户动态分表的长期数据库。
- `src/core/system-prompt.ts` 明确告诉 agent：你有 memory blocks 和 external memory。但这里仍然只是“使用说明”，不是本仓库自己提供 memory tool。
- 一个很重要的边界：`loadMemoryBlocks(...)` 只在 `createAgent(...)` 时使用。也就是说，`src/memories/*.mdx` 是**初始化种子**，不是 runtime source of truth；你改 repo 里的 MDX，并不会自动更新一个已经存在的 agent memory。

## 6. 抽象层面

- 它的抽象不是“在应用里自己做 memory engine”，而是“把 Letta 当成 memory OS，然后本仓库做 messaging + routing + session orchestration”。
- 从状态分层看，至少有四层：
- `persona memory`：agent 是谁。
- `human memory`：它认识了谁。
- `conversation history`：最近在哪个上下文里说过什么。
- `local operational state`：agentId / conversationId / recovery attempts 这类运行态元数据。
- 这个分层的好处是产品语义很稳定：你可以共享“人格”和“用户认知”，同时按 channel/chat 切历史，避免上下文串线。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：把“共享 memory、隔离 history”拆开设计，这比把所有长期状态混在一个会话日志里清晰很多，尤其适合多渠道 agent。
- 值得借鉴：`listen` + heartbeat/sleeptime 让记忆更新不依赖显式回复，产品上能做“被动吸收上下文”。
- 值得借鉴：把初始 memory 直接写成 `src/memories/*.mdx`，运营/人格调优门槛很低，也便于人为审阅。
- 值得借鉴：本地只存薄句柄而不复制整套 memory，实现简单，session 回收也便宜。
- 明显局限：这个 repo 没有把长期记忆的核心算法实现在本地；如果你想借的是“memory engine”，这里几乎借不到，只能借它的分层和接线方式。
- 明显局限：repo 内的 MDX memory 不是运行期真源，更多是 agent 首次创建时的 seed；后续真实记忆演化发生在 Letta 一侧。
- 明显局限：`memfs` 默认关闭，而且 `docs/configuration.md` 明说 headless conflict resolution 仍有限，所以“本地可审计、可版本化的记忆”不是开箱默认能力。
- 明显局限：没有看到仓库内显式的用户级记忆 schema、冲突合并策略、遗忘/压缩策略、召回评估；这些能力若存在，也在 Letta 平台内部而非本仓库代码里。
