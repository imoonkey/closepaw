# 1. 一句话结论

LobsterAI 有真实的长期用户记忆，但它本质上是一个“规则抽取 + SQLite 事实表 + 轻量注入/编辑工具”的实现，不是向量检索、摘要压缩或通用 episodic memory 系统。

# 2. Product层面

- 面向用户的能力是明确的：自动记住个人资料、偏好、稳定事实，也支持显式 `remember/forget` 指令和设置页手动增删改，见 `README.md`、`src/renderer/components/Settings.tsx`。
- 产品上把“长期记忆”和“聊天历史”分开了：长期记忆用于个性化，历史回溯走 `conversation_search` / `recent_chats`，不是把整段历史都塞进 memory，见 `src/main/libs/coworkRunner.ts`。
- 数据本地化是产品卖点之一，记忆存 SQLite，本地持久化，见 `README.md`、`src/main/sqliteStore.ts`。
- 但当前设置页真正暴露给用户的 memory 开关很少，只看到 `memoryEnabled`、`memoryLlmJudgeEnabled` 和 CRUD；后端支持的 `memoryImplicitUpdateEnabled`、`memoryGuardLevel`、`memoryUserMemoriesMaxItems` 没有被前端设置页实际接出来，见 `src/renderer/components/Settings.tsx:526-543`、`src/renderer/components/Settings.tsx:1210-1215`、`src/main/preload.ts:152-189`。

# 3. System层面

- 核心存储是两张表：`user_memories` 存 memory 实体，`user_memory_sources` 存来源链路（session/message/role），见 `src/main/sqliteStore.ts:120-161`。
- 运行时中心在 `src/main/coworkStore.ts`：负责 config、去重、合并、soft delete、stale 标记、统计、CRUD、turn 级自动更新。
- 抽取和判别是分层的：`src/main/libs/coworkMemoryExtractor.ts` 负责从对话中抽取候选；`src/main/libs/coworkMemoryJudge.ts` 负责 rule-first 判定，边界样本再走可选 LLM 二判。
- 历史检索不是 memory 表的一部分，而是直接查 `cowork_messages`，通过工具暴露给模型，见 `src/main/coworkStore.ts:1365-1395`、`src/main/libs/coworkRunner.ts:3129-3178`。
- 有迁移意识：旧的 `MEMORY.md` / `memory.md` 会迁移进 `user_memories`，说明它不是一次性 demo，而是经历过 memory 形态演进，见 `src/main/sqliteStore.ts:376-470`。

# 4. Lifecycle层面

- 记忆抽取发生在“一个 user turn + 一个 assistant turn 完成之后”，并且异步排队处理；session 完成时才触发 `applyTurnMemoryUpdatesForSession()`，见 `src/main/libs/coworkRunner.ts:545-585`、`src/main/libs/coworkRunner.ts:595-635`、`src/main/libs/coworkRunner.ts:3543-3550`。
- `extractTurnMemoryChanges()` 要求 `userText` 和 `assistantText` 都非空；但隐式抽取实际主要只看用户文本模式，assistant 文本更多只是作为“本轮已完成”的门槛，见 `src/main/libs/coworkMemoryExtractor.ts:108-180`、`src/main/libs/coworkMemoryExtractor.ts:182-200`。
- 写入时有三层状态：`created` / `stale` / `deleted`。隐式记忆会记录 source；删 session 时会把相关 source 置 inactive，失去 source 的隐式记忆会转 `stale`，见 `src/main/coworkStore.ts:345-380`、`src/main/coworkStore.ts:1233-1253`、`src/main/coworkStore.ts:634-649`。
- 重复记忆不是简单拒绝，而是 `createOrReviveUserMemory()` 里做 revive/merge；显式 delete 是 soft delete，不是物理删除，见 `src/main/coworkStore.ts:967-1055`、`src/main/coworkStore.ts:1153-1166`。
- 启动时还会做一次“非个人/过程性记忆”自动清理，避免脏记忆永久残留，见 `src/main/main.ts:535-543`、`src/main/coworkStore.ts:1202-1230`。

# 5. Injection层面

- 注入方式很克制：把记忆渲染成 `<userMemories>` XML 块，作为 prompt prefix 拼到用户消息前，不放进 system prompt，以便 system prompt 保持稳定并利用 prompt cache，见 `src/main/libs/coworkRunner.ts:649-680`、`src/main/libs/coworkRunner.ts:2240-2249`。
- 注入量有硬上限：默认最多 12 条，单条截断 200 字，总长约 2000 字符，排序依据是 `updated_at DESC`，见 `src/main/coworkStore.ts:32-38`、`src/main/coworkStore.ts:1058-1096`、`src/main/libs/coworkRunner.ts:655-679`。
- 模型侧被明确告知：涉及“之前/上次/还记得”应优先调用 `conversation_search` / `recent_chats`；只有用户显式要求记住、修改、删除时才调用 `memory_user_edits`，见 `src/main/libs/coworkRunner.ts:2220-2233`。
- 同一套 memory/history 工具既在本地 Claude SDK 模式下挂载，也在 sandbox agent-runner 里通过 host MCP 转发，所以注入与编辑在 local/sandbox 两条执行链路上都成立，见 `src/main/libs/coworkRunner.ts:3129-3236`、`sandbox/agent-runner/index.js:1289-1372`。

# 6. 抽象层面

- 它抽象的不是“任意知识”，而是“稳定的用户事实与偏好”。候选类型基本限于个人资料、拥有关系、偏好、对 assistant 输出风格的长期要求，见 `src/main/libs/coworkMemoryExtractor.ts:5-16`、`src/main/libs/coworkMemoryExtractor.ts:149-160`。
- 去重/合并不是 embedding 检索，而是 `fingerprint + token overlap + char bigram + 文本质量偏好` 的启发式方案，见 `src/main/coworkStore.ts:180-231`、`src/main/coworkStore.ts:270-273`。
- 判别也是轻量抽象：rule score 为主，只在阈值附近启用可选 LLM 二判，并带 10 分钟 TTL cache；这更像“精修 gate”，不是让模型全面管理记忆，见 `src/main/libs/coworkMemoryJudge.ts:10-15`、`src/main/libs/coworkMemoryJudge.ts:58-63`、`src/main/libs/coworkMemoryJudge.ts:199-299`。
- 因此它更接近“profile memory / preference memory”，而不是 semantic memory、episodic memory、task memory 三者统一的 memory substrate。

# 7. 值得借鉴 / 明显局限

**值得借鉴**

- 把“长期记忆”与“历史检索”硬分层，这个产品/系统边界很清楚，能显著降低把 history 噪声写进 memory 的风险。
- `user_memory_sources` + orphan stale 的设计很好，隐式抽到的记忆不是永久 immortal，而是和会话来源绑定。
- 注入放在 user prefix 而非 system prompt，这个实现细节很实用，兼顾了个性化和 prompt cache 稳定性。
- 整体实现非常轻：SQLite + 规则 + 少量 LLM gate，就能形成可用 memory MVP。

**明显局限**

- 没有真正的 semantic retrieval / ranking：没有向量库、没有 usage reinforcement，`last_used_at` 字段存在但当前代码里没有被更新使用，注入更多依赖 `updated_at`，见 `src/main/sqliteStore.ts:130`、`src/main/coworkStore.ts:939-949`、`src/main/coworkStore.ts:1088-1094`。
- 隐式抽取基本是 regex/rule 驱动，语言和表达覆盖面有限；`assistantText` 虽在接口里存在，但当前并未参与实质语义判断。
- 生命周期是“turn 完成后异步写入”，因此中断、报错、未出 assistant 回复的轮次不会形成 memory。
- 后端有更细粒度 memory 配置，但前端没有完整暴露，说明产品闭环还停留在“能用”而不是“可精调”阶段。
