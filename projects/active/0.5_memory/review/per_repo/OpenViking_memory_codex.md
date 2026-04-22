# OpenViking Memory Review

## 1. 一句话结论

OpenViking 有真实的长期记忆系统，但它不是独立的“记忆引擎”，而是统一 `Context Database` 里的 `memory` 子域：会话在 `commit()` 后被归档、经 LLM 抽取/去重后写入 `user/agent memories`，再进入同一套检索栈；不过“归档摘要注入”和“文档/实现一致性”有明显缺口。

## 2. Product层面

- 产品定义里的 memory 不是简单聊天历史，而是 Agent 主动沉淀的长期上下文，见 `README.md`、`docs/en/concepts/02-context-types.md`、`docs/en/concepts/08-session.md`。
- 面向用户的价值主要有两类：一类是用户侧长期记忆，如 `profile / preferences / entities / events`；另一类是 agent 侧经验记忆，如 `cases / patterns`。
- 文档主讲 6 类 memory，但 `openviking/session/memory_extractor.py` 实际已经扩展到 `tools` 和 `skills` 两类，说明它也在把“工具/技能使用经验”视为长期记忆的一部分。
- `bot/workspace/memory/MEMORY.md` 更像 bot workspace 里的记忆文件模板，不是核心 memory pipeline 本体。

## 3. System层面

- 核心链路很清晰：`openviking/session/session.py` 管会话与 `commit()`，`openviking/session/compressor.py` 管抽取/去重/索引，`openviking/session/memory_extractor.py` 负责把消息转成 candidate memories，`openviking/session/memory_deduplicator.py` 负责向量预筛 + LLM 决策。
- 存储是双层的：AGFS 存真实内容，Vector Index 存 `uri / context_type / category / level / active_count / updated_at / owner_space` 等索引信息，见 `docs/en/concepts/05-storage.md` 和 `openviking/core/context.py`。
- memory 没有单独的一套存储技术；它完全复用统一 `Context` 抽象和 `L0/L1/L2` 分层。物理位置主要是 `viking://user/{user_space}/memories/*` 和 `viking://agent/{agent_space}/memories/*`。
- `SessionCompressor._index_memory()` 在写入 memory 后既入 embedding 队列，也触发父目录语义生成，所以 memory 目录本身会变成可递归检索的对象。
- 检索侧没有“memory-only engine”；它走 `openviking/retrieve/hierarchical_retriever.py`，只是根目录换成 user/agent memories。

## 4. Lifecycle层面

- 生命周期是显式的：`Session.add_message()` 先持续写 `messages.jsonl`；`Session.commit()` 再把当前消息归档到 `history/archive_NNN/{messages.jsonl,.abstract.md,.overview.md}`。
- `commit()` 之后进入长期记忆沉淀：`SessionCompressor.extract_long_term_memories()` 会执行 `提取 -> 去重 -> create/merge/delete -> 向量化 -> 父目录语义更新`。
- 类别策略是具体实现出来的，不只是概念：`profile` 合并到单文件 `profile.md`；`preferences / entities / events / cases / patterns` 通常各自落成 `.md`；`tools / skills` 则按名字聚合到固定文件。
- 反馈闭环也存在，但不是自动的：只有调用方显式记录 `session.used()` 的 URI，`commit()` 时才会通过 `increment_active_count()` 把使用反馈写回索引。
- 严格说它没有完整的“遗忘/冷热迁移”生命周期。`openviking/retrieve/memory_lifecycle.py` 目前只是一个排名时用的 `hotness_score()` 纯函数，不是真正的 memory aging / compaction 机制。

## 5. Injection层面

- memory 的注入方式是“先检索，再由上层决定读哪一层”，不是自动把全部长期记忆塞进系统提示词。
- 入口链路是 `SearchService.search()` -> `Session.get_context_for_search()` -> `VikingFS.search()` -> `IntentAnalyzer.analyze()` -> `TypedQuery(memory/resource/skill)` -> `HierarchicalRetriever.retrieve()`。
- `IntentAnalyzer` 设计上会吃 `session compression summary + recent messages + current query`；检索结果返回的是 `MatchedContext`，默认先给 L0/L1 级别的 URI 和 abstract，而不是直接灌入完整 L2。
- 一个很实际的缺口在这里：`Session.get_context_for_search()` 在 `openviking/session/session.py` 返回的是 `{"summaries": ..., "recent_messages": ...}`，但 `openviking/storage/viking_fs.py` 读取的是 `session_info.get("summary")`。这意味着归档摘要大概率没有真正注入 intent analysis。
- 即使先不看这个 bug，archive recall 也只是对 archive `.overview.md` 做简单关键词计数，不是语义检索。

## 6. 抽象层面

- 它的抽象很统一，但不算“厚”：memory 本质上就是 `Context(context_type="memory") + category + URI 约定`，不是独立的语义记忆模型。
- 真正稳定的抽象边界是两层：一层是 `memory / resource / skill` 三大 context type；另一层是 `L0 / L1 / L2` 三层信息表示。memory 完整复用这两层抽象。
- 更新规则主要靠代码约定和 prompt 约定，不是强 schema：`ALWAYS_MERGE_CATEGORIES`、`MERGE_SUPPORTED_CATEGORIES`、`DedupDecision(skip/create/none)`、`ExistingMemoryAction(merge/delete)` 都定义在 session/compressor/dedup 这层。
- 这让系统很容易统一实现，但记忆质量高度依赖 prompt，例如 `compression.memory_extraction`、`compression.memory_merge_bundle`、`compression.dedup_decision`。
- 文档与实现并不完全对齐：文档仍以 6 类为主，而代码已经是 8 类；某些路径和可合并策略在不同文档/代码之间也有漂移。

## 7. 值得借鉴 / 明显局限

- 值得借鉴：把 memory 做成可浏览文件树 + 可检索索引，而不是黑盒向量条目；调试、人工修正、可观察性都更强。
- 值得借鉴：`commit()` 时同时保留 raw archive 和 distilled memory，原始会话与长期记忆并存，不会只剩一个压缩摘要。
- 值得借鉴：memory、resource、skill 共用同一套检索与分层加载基座，系统复杂度比“另起一套 memory 子系统”低。
- 值得借鉴：`active_count + updated_at` 的热度权重虽然简单，但至少把“真正被使用过的记忆”反馈回排序。
- 明显局限：归档摘要注入目前有键名错位，session-level memory context 设计上存在，实装上可能没真正生效。
- 明显局限：强依赖 LLM。`MemoryExtractor.extract()` 在 `vlm` 不可用时直接返回空，没有 rule-based fallback。
- 明显局限：hotness 反馈依赖调用方显式 `session.used()`；如果 agent 没做这层埋点，记忆排序不会自我进化。
- 明显局限：没有更强的 episodic/semantic graph、conflict resolution 状态机、长期衰减/淘汰机制；本质仍是“LLM 生成的 markdown 文件 + 向量索引”。
