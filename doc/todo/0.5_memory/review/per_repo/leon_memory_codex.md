# 1. 一句话结论

Leon 有真实的长期记忆系统，不只是 skill 级 JSON 状态或聊天日志；它已经做成了 `persistent / daily / discussion + context` 分层存储与检索，但当前更像“工具驱动的可检索记忆库”，不是默认自动注入到主 agent 的全局记忆层。

## 2. Product层面

- 面向产品的主记忆能力，已经不是假的：`bridges/toolkits/structured_knowledge/tools/memory.tool.json` 明确暴露了 `read` / `write`，目标就是“persistent, daily, discussion” 三层记忆。
- 产品上，Leon 明确区分了两类知识源：`structured_knowledge.memory` 负责“用户事实/偏好/历史”，`structured_knowledge.context` 负责“环境/系统/runtime 文件”。这比把所有历史都塞进一个 history 好很多。
- 但当前用户可见的 memory 操作仍偏薄。主工具只暴露了 `read` / `write`，没有把 `forgetById` / `forgetByQuery` 这类删除能力暴露给 agent，因此“记住了如何忘掉”这个闭环不完整。
- repo 里大量 `memory.py` / `memory.ts` 不是主记忆系统，而是 skill 自带状态存储。例如：
  - `skills/leon/introduction/src/lib/memory.py` 只存 owner 的 `name/birth_date`
  - `skills/greeting_skill/src/actions/greet.py` 通过 `leon:introduction:owner` 读取这个 skill memory
  - `skills/utilities/timer/src/lib/memory.ts`、`skills/guess_the_number_skill/src/lib/memory.py` 只是计时器/游戏状态
- 结论上，Leon 的“产品级记忆”与“skill 状态持久化”是两套东西，不能混为一谈。

## 3. System层面

- 主实现集中在 `server/src/core/memory-manager/`：
  - `memory-manager.ts`：生命周期编排、turn 观察、长期候选抽取、summary、retention
  - `memory-repository.ts`：SQLite 读写
  - `qmd-backend.ts`：QMD 检索与索引刷新
  - `sql/schema.sql`：底层 schema
- 底层 schema 是认真设计过的，不只是 append log：
  - `memory_items`：主记忆项
  - `memory_facts`：结构化事实层
  - `context_documents`：上下文文件索引
  - FTS 表：`memory_chunks_fts`、`context_chunks_fts`
- 存储介质是双轨的：
  - source of truth 在 `core/memory/index.sqlite`
  - 同时写 markdown mirror 到 `core/memory/persistent`、`core/memory/daily`、`core/memory/discussion`
- 检索不是单纯关键词搜文件。`qmd-backend.ts` 把 `core/memory/*` 和 `core/context/*.md` 建成多 namespace collection，再做 query/search、补搜、second pass、rescue、backtrack。
- 一个明显的系统问题是“双实现”：
  - server 侧有 `server/src/core/memory-manager/*`
  - tool 侧又有 `bridges/nodejs/src/sdk/tools/memory/memory-tool.ts`
  - 两边都直接操作同一套 `core/memory/index.sqlite` 和同一份 schema。它不是薄代理，而是重复实现，后续很容易漂移。

## 4. Lifecycle层面

- 自动写入主链路接在 `server/src/core/nlp/nlu/nlu.ts` 的 ReAct 路径上：
  - 每轮回答后调用 `MEMORY_MANAGER.observeTurn(...)`
  - 同时在没有显式 `memory.write` 时调用 `savePersistentMemoryCandidatesFromTurn(...)`
- `observeTurn(...)` 的动作很清楚：
  - 当前轮写入 `daily`，作为 `conversation` event
  - 当前轮再写入 `discussion`，作为短期滚动记忆
  - 然后执行 `summarizeDay(dayKey)` 和 `pruneDiscussion(now)`
- 长期记忆不是每轮直接全存，而是 `savePersistentMemoryCandidatesFromTurn(...)` 再走一次 LLM 抽取：
  - 输入是本轮 `User + Leon`
  - 要求只抽“stable long-term user memory”
  - 最多保存 3 条候选
  - 还做了近重复检测，避免 persistent 区域越堆越脏
- retention 也是真做了：
  - `discussion` item TTL 是 5 天
  - storage maintenance 会把 30 天前 discussion soft delete
  - 180 天前 discussion 进冷归档并 gzip
  - `daily` 的非 summary 90 天后清理
  - soft-deleted 行保留 7 天后 purge
- 但生命周期有一个很关键的缺口：从仓库搜索看，自动 memory capture 只挂在 `nlu.ts` 的 `route: 'react'` 上；`server/src/core/brain/brain.ts` 的 workflow 路径只更新 `SELF_MODEL_MANAGER`，不更新 `MEMORY_MANAGER`。也就是说，并不是所有交互都会进入主记忆系统。
- 另外，`summarizer.ts` 的 daily summary 不是语义摘要，而是把最近对话截成 bullet。它很便宜，但抽象层级不高。

## 5. Injection层面

- Leon 当前的 memory 注入方式，核心是“显式工具调用”，不是“默认背景注入”。
- `server/src/core/llm-manager/llm-duties/react-llm-duty/constants.ts` 明确把规则写进 prompt：
  - owner 历史/偏好/过去讨论，优先用 `structured_knowledge.memory.read`
  - 显式“记住这个”，用 `structured_knowledge.memory.write`
  - 环境/runtime 问题优先用 `structured_knowledge.context`
- `server/src/core/llm-manager/llm-duties/react-llm-duty/execution.ts` 更直接：`buildExecutionMemorySection(...)` 明确返回 `Execution Memory: none`，并写死日志“use structured_knowledge.memory.read when memory is needed”。
- `planning.ts` 实际注入的是：
  - tool catalog
  - self-model snapshot
  - context manifest
  - 没有把 memory pack 直接塞进 planning prompt
- 虽然 `memory-manager.ts` 里实现了 `buildPlanningMemoryPack()` / `buildExecutionMemoryPack()`，但按仓库全文搜索，目前只有定义，没有实际调用点。说明这套“自动 memory pack 注入”还没有真正接进主 ReAct 流程。
- 所以 Leon 的 memory 注入策略，本质上是“让 agent 学会何时主动查 memory”，而不是“每次推理都先被 memory 污染一遍”。

## 6. 抽象层面

- Leon 在抽象上做了一个值得肯定的拆分：
  - `memory`：用户长期/中期历史
  - `context`：环境与系统状态
  - `self-model`：agent 自身行为反思
  - skill `Memory`：功能局部状态
- 主记忆内部再分层：
  - `persistent`：长期
  - `daily`：按天汇总
  - `discussion`：短期会话滚动层
- 这个抽象基本是对的，因为它把“可长期复用的 owner knowledge”和“当下环境事实”拆开了，避免所有 grounding 都混在 chat history 里。
- 但实现上有几处抽象泄漏：
  - `conversation_daily` 和 `memory_daily` 最终指到同一个 QMD collection
  - server `MemoryManager` 与 bridge `MemoryTool` 各自实现一遍 recall/write/embedding refresh
  - `memory_facts` 这个结构化层虽然存在，但只有 `bridges/nodejs/src/sdk/tools/memory/memory-tool.ts` 在 `scope='persistent' && kind in ('fact','preference')` 时才会 `upsertFact(...)`
- 这带来一个重要后果：`memory-manager.ts` 的自动长期候选抽取，最终保存的是 `kind: 'note'`，不是 `fact/preference`。所以“自动学到的长期记忆”大多只是可检索 note，不会自动升级成结构化 fact 层。

## 7. 值得借鉴 / 明显局限

### 值得借鉴

- 分层生命周期是对的。`persistent / daily / discussion` 比单一 conversation history 更接近真实 agent memory。
- 存储与检索分离也值得抄：SQLite 做真实数据层，markdown mirror 给检索与人工可读，QMD 做 retrieval。
- `memory` 与 `context` 分开，且在 prompt policy 层明确“什么时候查 memory，什么时候查 context”，这个设计非常实用。
- maintenance 做得够工程化：去重、TTL、warm/cold archive、gzip、monthly report，不是 demo 级实现。

### 明显局限

- 记忆不是 always-on 注入；如果 planner / executor 没主动调 `structured_knowledge.memory.read`，当前轮就吃不到记忆。
- 自动记忆捕获只覆盖 ReAct route，不覆盖 workflow route，导致全系统的一致性不足。
- `memory_facts` 结构层没有被自动抽取链路充分利用，当前长期记忆更像“高质量 note 库”，而不是稳定的 user profile / fact graph。
- `server/src/core/memory-manager/*` 和 `bridges/nodejs/src/sdk/tools/memory/memory-tool.ts` 的双实现会带来维护风险。
- `summarizer.ts` 的 daily summary 只是 recency bullet 化，不是真正的语义压缩。
- 删除/遗忘能力没有进入 agent 可调用接口，产品层 memory 管理闭环还没完成。
