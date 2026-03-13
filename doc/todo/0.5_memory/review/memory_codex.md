# AI Agent Memory 系统综述（Codex）

日期：2026-03-11

---

## TL;DR

24 个仓库看下来，我的结论很直接：

1. **大多数系统在做“通用助手记忆”**，主轴是 user profile / facts / past discussions / archival recall。
2. **我们做的不是这个问题。** `doc/main/agent/memory.md` 实际上在做的是 **Android 操作经验记忆**：按 app package 聚焦，记 workflow / pitfall / verification。
3. 所以我们和它们最大的差别，不是“文件 vs 数据库”，而是 **memory 的主索引维度不同**：
   - 它们：`user / agent / session / namespace / semantic query`
   - 我们：`device / user_pref / current_app(package)`
4. 如果目标是“让 Android agent 下次进同一个 app 少踩坑”，我们现在这版 **非常对题**；如果目标升级成“真正的通用长期个体记忆”，那现在这版还远不够。

---

## 一、24 个系统里真正收敛的东西

### 1. 记忆分层几乎是共识

不管名字怎么起，成熟系统最终都会落到这几层：

- **Always-on identity / core memory**
  角色、长期偏好、稳定事实。
- **Session / recent discussion**
  当前对话或最近几轮上下文。
- **Archival / long-term store**
  可检索但不常驻的长期记忆。
- **Daily / episodic log**
  按天或按 session 追加的流水。

我们现在也隐含是这个结构，只是更极端简化：

- `device.md` = 稳定环境事实
- `user_prefs.md` = 跨 app 偏好
- `apps/<pkg>.md` = app 级长期经验

### 2. 写入路径通常只有三类

- **Agent 主动写**
  Letta、Hermes、OpenClaw、ZeroClaw 都偏这类。
- **系统自动抽取**
  Leon、LobsterAI、MemOS、OpenViking、supermemory 偏这类。
- **压缩时顺手沉淀**
  OpenClaw、CoPaw、Nanobot 的味道最强。

我们属于 **“Agent 主动 + failure 兜底”**。这在成本和可控性上很合理，也比纯 prompt 依赖更稳。

### 3. 存储不是关键分歧，检索才是

同样是“memory”，底层跨度非常大：

- **纯文件**
  Hermes、Nanobot、PicoClaw、mimiclaw
- **文件为真相源 + 索引加速**
  OpenClaw、CoPaw、IronClaw、OpenViking
- **数据库 / 向量 / 图**
  mem0、Letta、Leon、MemOS、supermemory

但真正决定行为差异的不是存在哪里，而是 **怎么 recall**：

- 全量注入
- top-k semantic search
- BM25 + vector hybrid
- hierarchical / recursive retrieval
- graph-aware retrieval

我们现在的 recall 最不一样：**不是 search，而是 lookup**。  
当前前台包名一出来，就直接把对应 app memory 文件塞进 prompt。

### 4. 大多数系统在 lifecycle 上并不强

看起来很“智能”的 memory，很多其实生命周期管理很薄：

- 不做真 dedup
- 不做冲突合并
- 不做时间衰减
- 不做预算级裁剪
- 不做抽象层提炼

真正把 lifecycle 做到比较像样的，主要是：

- **mem0**：LLM 决定 add/update/delete
- **Leon**：层次和事实表都比较讲究
- **memU**：hierarchical retrieval + sufficiency
- **OpenViking**：层级化压缩与加载
- **MemOS**：scheduler + multi-plane + graph/feedback

我们现在的 lifecycle 很保守：

- FIFO cap
- 最新优先截断
- 不 dedup
- 不 decay
- 不 reflect

这不先进，但非常可控。

### 5. “working memory -> long-term memory” 是分水岭

真正把 memory 做出差异的，是有没有一套像样的“升格机制”：

- **没有升格，只有存取**：Hermes、ZeroClaw、PicoClaw、mimiclaw
- **对话压缩成摘要**：Nanobot、OpenClaw、CoPaw
- **从原始事实提炼 profile / abstraction**：Leon、OpenViking、memU、MemOS、Second-Me、supermemory

我们当前版本几乎没有这层。  
它本质上是 **经验条目缓存**，不是知识蒸馏系统。

---

## 二、每个系统最值得记住的一点

下面不是细节表，而是“设计判断时脑子里要留下的一个钉子”。

### Claw 系

- **OpenClaw**：文件就是记忆，压缩前会触发 memory flush；最像“工程上能落地的 agent memory”。
- **ZeroClaw**：多 backend memory 抽象，`memory_store / memory_recall / memory_forget` 很清晰，还带 snapshot/hydration 思路。
- **PicoClaw**：极简文件记忆，`MEMORY.md + daily notes`，更像 OpenClaw 的轻量化实现。
- **Nanobot**：把长期记忆更新交给一次单独的 consolidation LLM 调用，产物是 `MEMORY.md + HISTORY.md`。
- **nano-claw**：本质只是 session JSON 滑动窗口，不是真正长期记忆。
- **mimiclaw**：嵌入式版本的 `MEMORY.md + daily file`，思路朴素但很适合极小设备。
- **LettaBot**：更多是把 Letta 的 memory blocks 载入 SDK，不是自己重新发明 memory。
- **Hermes Agent**：`MEMORY.md + USER.md` 双文件、字符上限、冻结快照、不允许 mid-session 改 prompt，是很成熟的 cache-friendly 设计。
- **IronClaw**：workspace 文档分块后做 FTS + vector hybrid search，长期记忆和 workspace search 基本合并成一套系统。
- **Leon**：分 persistent / daily / discussion，并额外维护 facts；是“结构化个人助手记忆”的代表。
- **LobsterAI**：自动从对话里抽用户记忆，规则优先、LLM 兜底，还有 guard level，产品味很强。
- **CoPaw**：ReMeLight 驱动的文件记忆，支持 context compaction、异步 watcher、hybrid search，和 OpenClaw 一脉相承但更平台化。
- **NextClaw**：有 OpenClaw 风格的 `MEMORY.md` 和 memory tools，但整体仍是轻量注入，不是重型 memory backend。
- **Poco-Agent**：本质是把 mem0 做成产品能力，memory 是 feature flag 驱动的外接服务。
- **ClawX**：桌面壳，不是 memory 系统本体。
- **MemOS-claw**：把 MemOS 当作 OpenClaw 的 memory plugin，用更重的 memory runtime 给 agent 补脑。

### Memory 专项仓库

- **mem0**：最像“memory platform API”，强项是统一抽取、更新、检索、图扩展。
- **memU**：核心不是存储，而是 hierarchical retrieval pipeline 和“检索是否够用”的判断。
- **Letta**：把 memory 变成 agent 可编辑状态，core vs archival 切得很清楚。
- **OpenViking**：filesystem paradigm + L0/L1/L2 progressive loading，是层级化 context 管理的代表。
- **PageIndex**：不是传统 agent memory，更像“结构化长文检索引擎”；对 memory 的启发在于 vectorless tree retrieval。
- **Second-Me**：把个人记忆一路提炼到更高抽象层，甚至走向参数化，是“人格连续性”路线。
- **supermemory**：更像给产品团队用的 memory infrastructure，记忆、内容块、profile 一体化。
- **MemOS**：把 memory 当 OS 层，multi-plane、multi-cube、scheduler、feedback，野心最大也最重。

---

## 三、我们的 memory 跟它们到底哪里不同

这里直接对照 [memory.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/agent/memory.md) 说。

### 1. 我们不是在做“记住这个人”，而是在做“记住这个 app 怎么搞”

这是最大区别。

多数系统的长期记忆主要围绕：

- user facts
- user preferences
- agent persona
- past discussions
- project knowledge

而我们存的是：

- `[workflow]`
- `[pitfall]`
- `[verification]`

并且按 **app package** 组织。

这意味着我们本质上更像：

- **per-app procedural memory**
- **device-grounded operational memory**

而不是通用 personal memory。

这是一个非常好的 domain-specific 选择，因为 Android agent 的失败大头，本来就不是“忘了用户叫什么”，而是“忘了这个 app 上次怎么点才对”。

### 2. 我们的 recall 是 deterministic，不是 semantic

别家大多是：

`query -> search -> rerank -> inject`

我们是：

`currentPackageName -> load apps/<pkg>.md -> inject`

优点很明确：

- 命中精度高
- 行为可解释
- 无额外 embedding / DB / 索引
- on-device 成本极低
- debug 非常容易

缺点也明确：

- 不支持跨 app 迁移
- 不支持语义近似召回
- turn-1 如果还没进 app，就有空档
- 当条目数量变大时，没有 search 层就会开始吃力

所以我们现在不是“比他们更高级”，而是 **做了一个对 Android 自动化更合适的窄解**。

### 3. 我们几乎不依赖额外 LLM 管线

很多系统的写入质量来自额外 LLM：

- session 结束后抽取
- 压缩时总结
- 背景 worker 重写 memory
- graph reorganizer / profile synthesizer

而我们当前设计故意避开这些：

- 正常写入靠 `remember_experience`
- 失败时自动补一条 pitfall
- recall 不做 semantic retrieval

这让我们的 memory 具有三个现实优势：

- **便宜**
- **稳定**
- **容易上线**

但也直接牺牲了三个上限：

- 抽象能力
- 自动提炼能力
- 通用个体画像能力

### 4. 我们的作用域比他们更“物理”

它们常见的 scope 是：

- `user_id`
- `agent_id`
- `run_id`
- `namespace`
- `tenant`
- `session`

我们当前的 scope 是：

- `device`
- `user_prefs`
- `current_app(package)`

这是很少见的。  
从 agent memory 角度看，这几乎是在把 **UI 环境本身** 当作记忆主索引。

对 Android agent 来说，这反而是最自然的：

- app 决定可操作性
- 同一目标在不同 app 的 workflow 完全不同
- “包名”比“语义相似”更稳定

### 5. 我们没有真正的 reflection / synthesis 层

OpenViking、memU、MemOS、Second-Me、Leon、supermemory 这些系统，都会尝试把原始交互再提炼一层：

- category summary
- user profile
- facts
- abstractions
- graph relations
- L0/L1/L2

我们现在没有这个层。

所以当前条目虽然实用，但知识密度不高：

- 它会记住“Developer Options 在 System 下面”
- 但不会自己抽象成“Settings 类 app 优先用搜索而不是滚动”

这让我们在 **相同 app 重复任务** 上会很强，
但在 **跨 app / 跨任务泛化** 上会明显弱于那些更重的系统。

### 6. 我们把 memory 放在 prompt anatomy 的一个固定槽位

这也很重要。

很多系统会让 memory 和 history、archival search、tool retrieval 混在一起，最后变成“大杂烩 context”。

我们在 `memory.md` 里把顺序定得很清楚：

`History -> Working Memory -> Recalled Memory -> App Skill -> Observation`

这意味着 memory 的职责非常单纯：

- 不是 scratchpad
- 不是 session transcript
- 不是 static skill
- 只是“过往经验提示”

这个边界感，在参考系统里其实不多见。

---

## 四、如果站在设计评审角度，我会怎么评价当前方案

### 我认为它做对了的地方

- **问题定义非常准**
  Android agent 的 memory 应该优先服务“减少 UI 探索成本”，不是先做通用人格系统。
- **存储方案对**
  Markdown 文件足够；V1 没必要上 SQLite/向量库。
- **scoping 对**
  按 package 存经验，是最贴近失败模式的索引方式。
- **预算策略对**
  用固定上限和弹性 recall，比“top_k 但 token 不可控”更工程化。
- **和 app_skill 正交**
  skill 是静态教材，memory 是使用心得，这个切分非常健康。

### 我认为它当前明显弱的地方

- **turn-1 app recall 空档**
  这是现实问题，不只是理论问题。
- **没有 dedup / merge**
  条目多了之后会开始重复。
- **没有轻量抽象层**
  只能记经验点，不能长出“模式”。
- **没有跨 app 泛化**
  如果两个 app 有类似结构，我们现在不会复用经验。
- **用户偏好层偏弱**
  `user_prefs.md` 有入口，但当前系统重心其实不在这里。

### 如果只做 V1/V1.5，我建议优先补的不是“向量搜索”

而是这几个便宜但收益高的东西：

1. **Goal-aware turn-1 bootstrap**
   从 goal 里猜 app，补齐首轮 recall。
2. **轻量 dedup**
   至少做 normalize 后的近似去重。
3. **小型抽象层**
   比如 app memory 顶部自动维护 3-5 条“current best-known patterns”。
4. **failure learning 更强**
   失败自动写已经有了，但可以加上更好的模板和去重。
5. **verification memory 提权**
   Android 自动化里 verification 的价值很高，应该比一般 workflow 更珍贵。

---

## 五、一个更直白的结论

如果把参考系统分成两类：

- **想成为“会记住你是谁”的通用助手**
- **想成为“下次别再点错”的操作型 agent**

那我们现在明显属于第二类。

这不是能力弱，而是目标更窄、更实用。

所以我的判断是：

- **和 mem0 / Letta / MemOS / supermemory 的差异**：我们没在做通用 memory platform。
- **和 OpenViking / Leon / memU 的差异**：我们没在做高抽象层 context database。
- **和 OpenClaw / CoPaw / PicoClaw / ZeroClaw 的亲缘性最高**：文件优先、可读可改、记忆服务 agent 执行，而不是服务人格建模。
- **而我们真正独特的一点**：把长期记忆锚定到 **Android app package**，并把内容限定为 **workflow / pitfall / verification**。这一点在参考项目里几乎没人这么做。

换句话说：

**别人的 memory 更像“大脑”；我们的 memory 更像“这个 app 的作战笔记”。**

对于 Android agent，这个选择是成立的。
