# 1. 一句话结论

`nano-claw` 没有真实的长期记忆系统；它实现的是基于 `sessionId` 的对话日志持久化与最近窗口回放，更接近“持久化上下文缓存”而不是“可提炼、可检索、可跨任务复用的 memory”。

# 2. Product层面

- 面向用户的 memory 概念基本就是“每个 session 保留聊天历史”。CLI 用 `--session` 切分上下文，默认是 `default`，对应实现见 `src/cli/commands/agent.ts`。
- 多渠道场景下，channel adapter 直接把 `sessionId` 绑定到用户/渠道，例如 Telegram 用 `telegram-${userId}`（`src/channels/telegram.ts`），因此产品表现是“同一用户在同一渠道里能续聊”。
- 文档把这套能力宣传成 `Persistent conversation memory`（`README.md`、`examples/advanced-features.md`），在“记住上一段对话”这个层面成立。
- 但产品上没有用户画像、偏好记忆、事实抽取、任务记忆、跨 session 迁移，也没有“只保留重要信息而不是整段历史”的能力。
- 有一个明显的文档/产品错位：文档建议用 `nano-claw agent --session work -m "clear"` 清空上下文（`examples/advanced-features.md`），但代码里只有交互模式把 `clear` 当命令；单消息 `-m` 模式不会清空，而是把 `"clear"` 当普通输入发给模型（`src/cli/commands/agent.ts`）。

# 3. System层面

- 真正的 “memory” 实现只有 `src/agent/memory.ts`：按 `sessionId` 把 `Message[]` 直接读写到 `~/.nano-claw/memory/<sessionId>.json`，路径来自 `src/utils/helpers.ts`。
- `AgentLoop` 会把用户消息、assistant 回复、tool 调用结果全部 append 到这份数组，并在每次 `addMessage()` 时同步落盘（`src/agent/loop.ts`、`src/agent/memory.ts`）。
- `ContextBuilder` 不做检索，只是把 system prompt 放在最前，再把整段历史原样拼进 prompt（`src/agent/context.ts`）。
- 超长时也不是总结或压缩，而是按字符数保留最近消息、丢弃更早消息；现有测试也只覆盖这个 recency truncation 行为（`src/agent/context.ts`、`src/agent/context.test.ts`）。
- `SessionManager`（`src/session/manager.ts`）是另一套机制：它保存 `userId/channelType/lastActivity/metadata` 到 `~/.nano-claw/sessions/`，不保存对话内容，也不参与 prompt 注入。
- 全仓没有 embedding、vector store、summary、retrieval、salience、preference profile 之类模块；`src/config/schema.ts` 里也没有任何 memory 策略参数。可以明确判定：没有真实 long-term memory。

# 4. Lifecycle层面

- `onboard` 只负责创建 `~/.nano-claw/memory/` 目录（`src/cli/commands/onboard.ts`）。
- 每次新建 `AgentLoop(sessionId)` 都会先从对应 JSON 文件 `load()` 历史，再在处理过程中不断 `save()`（`src/agent/memory.ts`）。
- 清空历史的生命周期只有交互式 `clear -> agent.clearHistory() -> Memory.clear()`；结果是把文件写成空数组，不是删除文件（`src/cli/commands/agent.ts`、`src/agent/memory.ts`）。
- `SessionManager.cleanupOldSessions()` 只删除 `sessions/` 里的 metadata 文件，而且仓库里没有调用方；memory 文件本身没有 TTL、归档或 GC。旧 transcript 会长期残留。
- `cron` 每次执行都生成 `cron-${job.id}-${Date.now()}` 作为新 session（`src/cron/index.ts`），因此定时任务默认不继承上一次记忆。
- gateway/channel 路径里会先 `getOrCreateSession()` 再创建 `AgentLoop(message.sessionId)`（`src/gateway/server.ts`），所以 “session metadata 生命周期” 和 “conversation transcript 生命周期” 是并行但松耦合的两条线。

# 5. Injection层面

- 注入顺序非常直接：`system prompt + current time + skills + tools` 组成一个 `system` message，然后把历史 `Message[]` 原样追加（`src/agent/context.ts`）。
- 被注入的 memory 不是“提炼后的记忆单元”，而是原始对话 transcript；tool 输出也以 `role: 'tool'` 的形式直接回灌进上下文（`src/agent/loop.ts`）。
- 截断策略只有“保留 system message + 尽量保留最新消息”，没有按重要性、类型、任务阶段做选择（`src/agent/context.ts`）。
- 因为没有 summary 层，tool 大输出和长对话会直接挤占上下文窗口，导致老信息被机械地按 recency 淘汰。

# 6. 抽象层面

- `Memory` 这个名字抽象得比实际能力更强；从实现看，它本质上是 `sessionId -> Message[]` 的 transcript repository，而不是“记忆系统”。
- 领域对象只有通用 `Message` 和 `Session`（`src/types.ts`）；没有 `Fact`、`Preference`、`Episode`、`Summary`、`MemoryItem`、`Retriever` 等更高层抽象。
- working memory 与 long-term memory 没有分层：同一份 transcript 既承担持久化，又直接承担 prompt 注入。
- `SessionManager` 和 `Memory` 分离是个好雏形，但两者之间没有统一的 memory lifecycle 或 retention contract，所以 abstraction 停在“会话元数据 vs 对话日志”这一级。

# 7. 值得借鉴 / 明显局限

- 值得借鉴：`flat file + session key` 的极简方案很适合作为 memory baseline，零外部依赖、可直接人工查看 `~/.nano-claw/memory/*.json`、调试成本低。
- 值得借鉴：`SessionManager` 与 transcript store 分开，至少把“路由/身份隔离”和“对话内容”拆开了，后续扩展真实 memory 时不必从零重构。
- 值得借鉴：`ContextBuilder.truncateContext()` 职责单一、测试清晰，适合先作为 working-memory 层的最小实现。
- 明显局限：它不是长期记忆，只是持久化聊天历史；没有抽取、检索、总结、跨 session 复用。
- 明显局限：文档与实现有偏差。示例把 memory 文件描述成带 `messages` / `metadata` 的对象，但代码实际写的是裸 `Message[]`（`examples/advanced-features.md` vs `src/agent/memory.ts`）；`-m "clear"` 示例也和实现不符。
- 明显局限：每次 `addMessage()` 都同步 `writeFileSync()`，tool-heavy 对话会频繁阻塞 I/O；同时 tool 输出原样入库/入 prompt，容易放大上下文成本。
- 明显局限：没有真正 GC。session metadata 可删，但 transcript 无自动删除；`cleanupOldSessions()` 甚至没有被接线使用。
