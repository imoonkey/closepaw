# 1. 一句话结论

nanobot 有“真实但极简”的长期记忆系统：它用 workspace 级 `memory/MEMORY.md` 保存长期事实、用 `memory/HISTORY.md` 保存可 grep 的事件摘要，再配合 session tail 和 LLM consolidate 做上下文压缩；它不是向量检索或自动召回系统，更像文件式记忆层。

# 2. Product层面

- 对外定位是 personal knowledge assistant / persistent memory，但记忆粒度默认是 workspace 级，不是 user 级；`README.md` 还明确写了要用不同 workspace 才能隔离 memory、sessions、skills。
- `nanobot/templates/AGENTS.md` 明确说“不要只把 reminder 写进 `MEMORY.md`”，说明这个记忆系统只负责保存事实，不负责主动提醒或任务触发。
- `README.md` 一边把 `nanobot/agent/memory.py` 标成 persistent memory，一边 roadmap 仍保留 “Long-term memory”，可理解为：项目已经有基础版记忆，但作者自己也不把它当成熟能力。

# 3. System层面

- 核心实现是 `nanobot/agent/memory.py` 的 `MemoryStore`，只有两份持久文件：`workspace/memory/MEMORY.md` 和 `workspace/memory/HISTORY.md`。
- `MEMORY.md` 存长期事实，`HISTORY.md` 存 append-only 事件摘要；仓库里没有 embedding、vector DB、专门 retriever、相似度召回或事实冲突解决层。
- 主 agent 没有专门的 memory write API。平时写记忆主要靠 `nanobot/skills/memory/SKILL.md` 指导 agent 直接用 `edit_file` / `write_file` 改文件；只有自动归档时，`MemoryStore.consolidate()` 才调用内部的 `save_memory` tool schema。
- `nanobot/session/manager.py` 用 `last_consolidated` 把“仍在 prompt 内的会话尾部”与“已归档的旧消息”分开，但原始消息列表依然 append-only 保留在 session JSONL 中。
- 记忆模板初始化非常轻：`nanobot/utils/helpers.py` 的 `sync_workspace_templates()` 直接创建 `memory/MEMORY.md` 和 `memory/HISTORY.md`，`nanobot/cli/commands.py` 的 `onboard()` 会调用它。

# 4. Lifecycle层面

- 新对话先进入 `Session.messages`；真正喂给模型的是 `Session.get_history()` 返回的未归档 tail，而不是整段历史。
- 当 `unconsolidated >= memory_window` 时，`nanobot/agent/loop.py` 会异步触发 consolidate；默认窗口是 100，配置在 `nanobot/config/schema.py`。
- consolidate 的策略很简单：保留最近一半窗口 `keep_count = memory_window // 2`，把 `session.messages[last_consolidated:-keep_count]` 交给模型，总结出一条 `history_entry` 追加进 `HISTORY.md`，并用 `memory_update` 覆写整个 `MEMORY.md`。
- 成功后系统只推进 `session.last_consolidated`，不会删除旧 session 消息；`/new` 会先 archive 尚未归档的尾部，再清空 session。
- `tests/test_consolidate_offset.py` 覆盖了归档触发、`last_consolidated` 持久化、并发去重、`/new` 与归档串行化这些关键边界，说明这套机制的主要复杂度在 lifecycle/并发，而不在检索算法。

# 5. Injection层面

- `nanobot/agent/context.py` 每轮都会把 `MEMORY.md` 直接拼进 system prompt，所以长期事实是“常驻注入”的。
- `nanobot/skills/memory/SKILL.md` 带 `always: true`，会经由 `nanobot/agent/skills.py` 自动注入；它明确告诉模型：`HISTORY.md` 不会自动进入上下文，需要自己用 `read_file` 或 `exec` 去搜。
- 因此实际注入结构是三层：`MEMORY.md` 常驻、memory skill 常驻、session 未归档 tail 常驻；`HISTORY.md` 只在模型主动检索时才进入上下文。
- `nanobot/agent/loop.py::_save_turn()` 会把 runtime metadata 从持久历史里剥掉，把图片降成 `[image]`，把超长 tool result 截断到 500 字符。这个设计减少了 prompt 污染，但也降低了后续记忆保真度。
- `nanobot/agent/subagent.py` 的 `_build_subagent_prompt()` 没有像主 agent 一样自动注入 `MEMORY.md`；子 agent 默认只拿到 workspace 和 skills summary，所以长期记忆不会自然传到子 agent。

# 6. 抽象层面

- 它的抽象非常朴素：`MEMORY.md` 是稳定事实层，`HISTORY.md` 是可搜索事件层，session tail 是工作记忆层。
- consolidate 的真实语义不是“抽取增量事实”，而是“让 LLM 读旧对话后，重写一份完整长期记忆，再追加一条事件摘要”。这使实现很短，但把一致性压力全部交给 LLM。
- 这套抽象几乎没有强 schema。虽然 `save_memory` 描述要求 markdown string，`nanobot/agent/memory.py` 和 `tests/test_memory_consolidation_types.py` 实际上接受 dict/list 异常返回，并把非字符串直接序列化成 JSON 文本写盘。
- `history_entry` 依赖模型自己遵守 “[YYYY-MM-DD HH:MM] + grep-friendly 摘要” 约定，代码本身不做格式校验。
- consolidate prompt 不带 session key / channel 身份；如果多个会话共用一个 workspace，全局 `HISTORY.md` 的来源归属会很弱。

# 7. 值得借鉴 / 明显局限

- 值得借鉴：`MEMORY.md` 常驻注入、`HISTORY.md` 只做按需搜索，这个切分对 prompt 预算很友好，复杂度也低。
- 值得借鉴：`last_consolidated` + 保留半窗 tail 的做法很实用，既避免反复总结同一批消息，也保住最近上下文连续性。
- 值得借鉴：把“如何使用记忆”放进 always skill，而不是堆一个更重的 memory service，整体很 KISS。
- 明显局限：长期记忆是 workspace 全局共享的，不是 per-user / per-session；多 channel 或多租户共用 workspace 时有串味和泄漏风险。
- 明显局限：历史召回完全依赖模型主动去搜 `HISTORY.md`；如果模型没想到 grep，旧信息就等于不存在。
- 明显局限：`MEMORY.md` 每次由 LLM 全量覆写，没有 provenance、冲突检测或 merge 保护，长期运行后容易静默丢事实、改写事实或引入幻觉。
- 明显局限：子 agent 默认不继承主记忆，这会让“主 agent 记得、子 agent 不记得”成为实际行为差异。
