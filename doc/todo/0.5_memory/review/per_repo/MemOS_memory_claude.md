# MemOS Memory System Analysis

## 1. Product层面

### Memory分类
MemOS 采用"三体记忆"架构，通过 **MemCube** 容器统一管理：

1. **Textual Memory (文本记忆)** — 核心，两种实现：
   - `GeneralTextMemory`：纯向量存储，适合简单场景
   - `TreeTextMemory`：图数据库 + 向量混合，支持层级结构（主力实现）
2. **Activation Memory (激活记忆)** — KV Cache 形式，将文本记忆编译为 LLM 的 KV Cache 加速推理
3. **Parametric Memory (参数化记忆)** — LoRA 适配器形式（目前仅 placeholder）
4. **Preference Memory (偏好记忆)** — 作为 TextMemory 的特化，单独管理用户偏好

### 每类结构

**TextualMemoryItem:**
- `id` (UUID), `memory` (文本内容), `metadata` (丰富的元数据)
- Metadata 包含: `status` (activated/resolving/archived/deleted), `memory_type`, `key`, `confidence`, `source`, `tags`, `visibility`, `version`, `history` (归档版本列表)
- `sources` 追溯到原始消息 (SourceMessage)，支持 chat/doc/web/file/system

**TreeNodeTextualMemoryMetadata 扩展字段:**
- `memory_type`: WorkingMemory / LongTermMemory / UserMemory / OuterMemory / ToolSchemaMemory / ToolTrajectoryMemory / RawFileMemory / SkillMemory
- `embedding`, `usage`, `background`, `file_ids`

**ActivationMemoryItem (KVCacheItem):**
- 内含 PyTorch DynamicCache 对象，支持多 cache 合并(concat)
- 可从 TextualMemoryItem 转换

**PreferenceTextualMemoryMetadata:**
- `preference_type`: explicit/implicit
- 独立的 dialog_id, original_text, embedding, preference 字段

## 2. System层面

### 架构
三层架构：
- **MOSCore** — 操作系统核心，管理多个 MemCube，处理多用户
- **MemCube** — 容器层，封装 text_mem + act_mem + para_mem + pref_mem
- **MemScheduler** — 异步任务调度层，队列化 add/query/reorganize 等操作

组件间关系：MOSCore → UserManager → MemCube → Memory implementations

### 存储/索引
- **GeneralTextMemory**: Qdrant 向量数据库 + Embedding
- **TreeTextMemory**: Neo4j 图数据库 + Embedding + BM25 + 全文索引
  - 图结构支持多种边类型: PARENT, MERGED_TO, INFERS, FOLLOWS, AGGREGATE_TO, MATERIAL, SUMMARY, FOLLOWING, PRECEDING
- **KVCacheMemory**: 内存 dict + pickle 序列化持久化
- **Reranker**: 支持 cosine_local、LLM rerank 等多种重排策略

### 写入方法
1. **LLM 抽取**: 对话 → `SIMPLE_STRUCT_MEM_READER_PROMPT` → 提取 key/value/tags 结构化记忆
2. **向量嵌入**: Embedder 生成向量 → 写入 Qdrant 或 Neo4j
3. **图写入** (TreeTextMemory):
   - 新节点先写为 WorkingMemory
   - 同时写入对应 memory_type 节点 (LongTermMemory/UserMemory 等)
   - 异步触发 Reorganizer 进行关系检测和结构优化
4. **批量写入**: `_add_memories_batch` 支持批量 Neo4j 写入，多线程并行

### 检索方法
TreeTextMemory 的多阶段检索管线：
1. **TaskGoalParser** — 解析查询意图
2. **MemoryPathResolver** — 路径决策
3. **GraphMemoryRetriever** — 图搜索 (embedding 相似度 + fulltext)
4. **BM25** — 关键词检索
5. **Reranker** — 重排 (支持层级权重: topic/concept/fact)
6. **MemoryReasoner** — LLM 推理判断

搜索模式：`fast`(直接检索) vs `fine`(调用大模型精细搜索)

SchedulerRetriever 额外提供：
- `enhance_memories_with_query` — LLM 增强检索结果
- `recall_for_missing_memories` — 补充遗漏记忆
- `filter_unrelated_and_redundant_memories` — 过滤无关/冗余

### 写入时机
- **同步模式 (sync)**: add 后立即 cleanup WorkingMemory + refresh size
- **异步模式 (async)**: add 后跳过 cleanup，由 MemScheduler 异步处理
- **MemScheduler**: RabbitMQ/Redis 消息驱动，支持 ADD/QUERY/ANSWER/FEEDBACK/MEM_READ/MEM_REORGANIZE/PREF_ADD 等任务类型

## 3. Lifecycle层面

### 淘汰/上限
- **按 memory_type 分别设上限**:
  - WorkingMemory: 20（FIFO，最旧的先删）
  - LongTermMemory: 1500
  - RawFileMemory: 1500
  - UserMemory: 480
- **清理策略**: 80% 填充率时触发清理，`remove_oldest_memory` 保留最新 N 条
- **数据库 drop**: 先备份到临时目录 (versioned)，保留最近 30 个备份，再删库

### 去重/合并
- **NodeHandler.resolve**: 新节点与现有节点做关系检测 (duplicate/conflict/related/unrelated)
  - `duplicate` → 合并为一个节点，旧节点 archived
  - `conflict` → LLM 决定保留哪个或合并
- **ArchivedTextualMemory**: 更新时保留历史版本，记录 version、update_type、archived_memory_id
- **阈值**: similarity > 0.92 触发合并检查，> 0.80 触发关系检测
- **边继承**: 合并时 `_inherit_edges` 将旧节点的边迁移到新节点

### 时间衰减
- 无显式时间衰减机制
- 通过 `updated_at` 排序间接实现新鲜度偏好
- WorkingMemory FIFO 淘汰本质上是时间驱动的

## 4. Injection层面

### Token预算
- 未发现显式 token budget 管理
- 通过 `top_k` 参数控制返回数量
- Reranker 的层级权重间接控制注入量

### 分级加载
- **三级记忆层级**: WorkingMemory → LongTermMemory → UserMemory
  - WorkingMemory: 最近对话的快照（20条上限）
  - LongTermMemory: 经过组织的持久知识
  - UserMemory: 用户画像信息
- 检索时可指定 `memory_type` 过滤: All / WorkingMemory / LongTermMemory / UserMemory
- `search_priority` 参数控制各类型检索优先级

### 作用域隔离
- **user_name** (cube_id) 贯穿所有操作，Neo4j 查询始终带 user_name 过滤
- **MemCube** 粒度隔离: 每个 cube 有独立的 text_mem/act_mem/para_mem/pref_mem
- **UserManager** + **UserRole**: 验证用户存在性和 cube 访问权限
- **visibility**: private / public / session 三级可见性
- **session_id**: 支持会话级隔离

## 5. Abstraction层面

### 反思/提炼
- **GraphStructureReorganizer** — 核心的反思机制:
  1. **消息驱动线程**: 新节点入图后触发关系检测 (detect → resolve)
  2. **定时结构优化** (每100秒):
     - KMeans 聚类 → LLM 生成摘要父节点 → 建立 PARENT 层级树
     - RelationAndReasoningDetector: 检测节点间关系、生成推理节点 (INFERS)、时序链接 (FOLLOWS)、聚合概念节点 (AGGREGATE_TO)
  3. **子聚类**: 大 cluster 用 LLM 进一步拆分为语义连贯的子组
- **Enhancement Pipeline** (Scheduler): 检索后 LLM 增强、补充遗漏、过滤冗余

### Working Memory 和 Long-term Memory
- **明确的双轨设计**:
  - 新记忆**同时写入** WorkingMemory 和 LongTermMemory/UserMemory
  - WorkingMemory 是临时快照 (FIFO 20条)，LongTermMemory 是持久存储
- **异步模式下的 Working → Long-term 迁移**:
  - 快速模式(fast): 原始输入直接写为 LongTermMemory 节点，标记 `mode:fast`
  - 后台 mem_reader 异步处理 fast 节点 → 生成精炼的 LongTermMemory
  - `working_binding` 记录 WorkingMemory 和 LongTermMemory 之间的对应关系
  - 精炼完成后清理对应的 WorkingMemory 节点
- **SkillMemory / ToolSchemaMemory / ToolTrajectoryMemory**: 特殊类型，不走 WorkingMemory 路径，直接入图
