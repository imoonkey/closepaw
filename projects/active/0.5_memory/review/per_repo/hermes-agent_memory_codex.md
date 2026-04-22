# 1. 一句话结论

Hermes Agent 有真实的长期记忆，但核心不是向量库，而是一个刻意收敛的“小而硬”方案：`tools/memory_tool.py` 维护 `MEMORY.md` / `USER.md` 两个持久化记忆文件，`hermes_state.py` + `tools/session_search_tool.py` 提供历史会话检索，另外可选叠加 `honcho_integration/` 做更强的用户建模。

# 2. Product层面

- 产品上把 memory 明确拆成三层：
  - 常驻上下文记忆：`MEMORY.md`（环境、项目、经验）和 `USER.md`（用户画像），见 `website/docs/user-guide/features/memory.md`。
  - 按需召回：`session_search`，用于“之前聊过什么”的历史找回，而不是始终注入。
  - 可选增强：Honcho 用户建模，作为附加层，不替代 `USER.md`。
- 这套 memory 默认就是开着的，不是实验开关：`hermes_cli/config.py` 与 `cli-config.yaml.example` 默认 `memory_enabled: true`、`user_profile_enabled: true`。
- 产品策略非常克制：memory 总容量只有 `2200 + 1375 chars`，文档明确定位为“始终值得放进上下文的关键事实”，不是把全部历史都长期保存。
- Agent 被明确要求“主动记忆”，不是等用户说“请记住”：`agent/prompt_builder.py` 的 `MEMORY_GUIDANCE` 和 `tools/memory_tool.py` 的 schema 描述都在强化这个行为。
- README 的产品叙事也把它放在“closed learning loop”里，但这里的 factual memory、session recall、skills 是分开的，不混成一个黑盒记忆体。

# 3. System层面

- 核心存储在 `tools/memory_tool.py`：
  - 落盘位置是 `~/.hermes/memories/`。
  - 两个文件：`MEMORY.md`、`USER.md`。
  - 条目分隔符是 `§`，允许多行条目。
  - 读取时去重，写入时用临时文件 + `os.replace` 做原子替换，避免并发读到空文件。
- 这个模块最关键的设计不是 CRUD，而是“双状态”：
  - `_system_prompt_snapshot`：会话开始时冻结，专供 prompt 注入。
  - `memory_entries` / `user_entries`：实时可变，工具调用后立刻写盘。
  - 这使它既是“真持久化”，又能维持同一会话内的 prompt cache 稳定。
- 安全上并不是裸注入：`tools/memory_tool.py` 在写入前做 regex 级别的 prompt injection / exfiltration / invisible unicode 检查；`agent/prompt_builder.py` 对上下文文件也做了类似扫描。
- 历史召回不是同一个存储层，而是 `hermes_state.py` 的 SQLite `state.db`：
  - 开 WAL。
  - `messages` 表 + FTS5 虚表。
  - 同时把 `system_prompt` 快照也存进 session 记录。
- `tools/session_search_tool.py` 的实现是“FTS5 找候选 session，再用便宜模型总结”，所以它更像 archive recall，不是核心记忆库本身。
- Honcho 也不是替代 core memory：`honcho_integration/session.py` 明说它是“runs alongside existing SQLite state and file-based memory”，`tools/honcho_tools.py` 只是额外暴露 `query_user_context`。

# 4. Lifecycle层面

- `run_agent.py` 初始化 `AIAgent` 时，会读取 config、构造 `MemoryStore`、执行 `load_from_disk()`，因此 memory 是 session start 时装载，不是按需 lazy read。
- `_build_system_prompt()` 会把冻结后的 memory block 拼进系统提示；但 mid-session 写入只会更新磁盘，不会更新当前 prompt。
- 这套“延迟生效”不是偶然，而是明确设计：
  - CLI session 内，prompt 缓存保持稳定。
  - Gateway 场景更进一步：虽然“每条消息新建一个 AIAgent”，但继续会话时会直接从 SQLite 取回旧的 `system_prompt`，而不是重新拼 prompt，所以同一 session 内的新记忆仍然不会自动进 prompt，见 `run_agent.py` 的 `stored_prompt` 复用逻辑。
- 为了降低“上下文丢失前来不及记忆”的风险，repo 做了三层补救：
  - 周期性 nudge：默认每 10 个 user turn 提醒一次，见 `run_agent.py`。
  - 压缩前 flush：`flush_memories()` 会在 context compression 前先给模型一次只带 `memory` 工具的保存机会。
  - 会话过期/重置前 flush：`gateway/run.py` 有后台 watcher，session 过期时会异步触发 memory flush。
- `tests/test_flush_memories_codex.py` 和 `tests/gateway/test_async_memory_flush.py` 说明这不是文档设想，而是被专门回归测试过的生命周期机制。
- 还有一个边界很值得注意：`tools/delegate_tool.py` 明确禁止 subagent 调 `memory`，避免多个子代理并发污染共享长期记忆。

# 5. Injection层面

- Prompt 拼装顺序在 `run_agent.py` 写得很清楚：identity -> tool guidance -> 外部 system message -> persistent memory -> skills -> context files -> date/time -> platform hint。
- `tools/memory_tool.py` 会把 memory 渲染成带标题和容量百分比的块，例如 `MEMORY (your personal notes) [67% — ...]`；这相当于把“容量管理”也暴露给模型自己判断。
- 这里的读取方式是“只通过 prompt 注入读取”，而不是再给一个 `read` tool：
  - 文档 `website/docs/user-guide/features/memory.md` 明确说没有 `read` action。
  - 实际 schema / dispatcher 也只有 `add`、`replace`、`remove`。
- `session_search` 是另一条注入链：不是把全历史塞进 prompt，而是用户/模型显式调用后，把总结结果作为普通 tool output 注入当前轮。
- Honcho 也是单独注入：
  - 首轮前 `prefetch` 拉上下文。
  - 然后把结果烘焙进当前 session 的 cached system prompt。
  - 会话结束后再同步新的 user/assistant 消息回 Honcho。

# 6. 抽象层面

- 这个 repo 对“memory”其实做了很清晰的分层抽象：
  - `memory`：面向 agent 自己的环境/项目/经验事实，始终小容量常驻。
  - `user`：面向用户画像的常驻资料，和 agent note 分开。
  - `session_search`：面向历史 transcript 的检索式回忆。
  - `Honcho`：面向跨 session 的推断式用户模型。
- 也就是说，它不是“一个大记忆系统”，而是“常驻事实层 + 检索回忆层 + 可选推断层”的组合。
- 最底层抽象非常朴素：文本条目 CRUD，更新/删除靠 substring match，不用 entry id、不用 embedding、不用图结构。这让实现非常透明，但也天然限制了精度和自动化空间。
- 另外，它把“程序性记忆”从 factual memory 里剥离出去，交给 skills 系统；这个边界在产品和实现上都比较干净。

# 7. 值得借鉴 / 明显局限

## 值得借鉴

- `MemoryStore` 的“冻结快照 + 实时落盘”二分法很值得借鉴，既保留长期记忆，又不破坏 prefix cache。
- “超小常驻记忆 + 按需 session_search”的二层结构很务实，比把所有历史都做成长上下文更可控。
- 在压缩、退出、session reset 之前主动 flush memory，这个 lifecycle 设计比单纯依赖模型自觉更可靠。
- 因为 memory 会被直接注入 prompt，所以先做安全扫描、再做注入，这个防线是必要的。
- 禁止 subagent 直接写共享 memory，也是很好的工程边界。

## 明显局限

- core memory 仍然是“全量注入的小文本块”，不是可检索、可排序、可衰减的知识层；一旦规模变大就只能继续压缩或人工替换。
- `replace/remove` 依赖 substring match，容易出现歧义；没有 entry id、时间戳、来源、置信度。
- 记忆质量高度依赖主模型自己写得好不好、会不会合并条目；系统没有单独的 consolidation / scoring / decay 机制。
- 同一 session 内新写入的 memory 故意不回流到 prompt，这对缓存友好，但也意味着“刚记住的东西”在当前 session 里不一定真的可被再次利用。
- `session_search` 需要额外 FTS + LLM 总结调用，成本和时延都高于 core memory。
- Honcho 作为增强层能力更强，但引入外部服务依赖，也把“用户记忆”拆成了本地 `USER.md` 与外部模型两条路径。
