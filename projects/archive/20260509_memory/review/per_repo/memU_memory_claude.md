# memU Memory System Analysis

## 1. Product层面

### Memory分类
memU 采用三层结构：Resource → MemoryItem → MemoryCategory

**MemoryItem 类型 (6种):**
1. **profile** — 用户基本信息、持久特征（职业、年龄、偏好）
2. **event** — 事件记忆（时间相关的经历）
3. **knowledge** — 知识性事实
4. **behavior** — 行为模式、习惯
5. **skill** — 技能相关记忆
6. **tool** — 工具调用记忆（含 ToolCallResult 历史）

**MemoryCategory (自动/手动分类):**
- 默认 10 个: personal_info, preferences, relationships, activities, goals, experiences, knowledge, opinions, habits, work_life
- 每个 category 维护一个 LLM 生成的 `summary`（自然语言摘要）
- item 与 category 是多对多关系 (CategoryItem 关联表)

**Resource (原始资源):**
- 支持多模态: text, image, video, audio, document, conversation
- 记录 url, modality, local_path, caption, embedding

### 每类结构

**MemoryItem:**
- `id`, `resource_id`, `memory_type`, `summary` (文本), `embedding`, `happened_at`
- `extra` dict 存储扩展信息:
  - 强化追踪: `content_hash`, `reinforcement_count`, `last_reinforced_at`
  - 引用追踪: `ref_id`
  - Tool 专用: `when_to_use`, `metadata`, `tool_calls`

**ToolCallResult (嵌入 extra.tool_calls):**
- tool_name, input, output, success, time_cost, token_cost, score, call_hash

### 每类结构的独特之处
- **Category Summary**: 每个分类维护一个 LLM 生成的自然语言摘要，在 item 增删改时通过 **patch prompt** 增量更新（不重算全量），非常高效
- **多模态预处理**: 图片/视频/音频先经 LLM 转写再提取记忆

## 2. System层面

### 架构
Workflow-based 异步管线：
- **MemoryService** — 核心服务，组合 MemorizeMixin + RetrieveMixin + PatchMixin
- **WorkflowStep** — 步骤化处理，每步声明 requires/produces/capabilities
- **LLM Profile** — 不同步骤可用不同 LLM（preprocess、extract、categorize、summary 分别配置）

### 存储/索引
- **InMemory**: dict + cosine brute-force（开发/测试）
- **PostgreSQL + pgvector**: 生产环境
- **SQLite**: 轻量持久化
- 所有存储都实现统一的 Repository 接口 (ResourceRepo, MemoryCategoryRepo, MemoryItemRepo, CategoryItemRepo)
- **向量索引**: bruteforce / pgvector / none 三种
- **Rust 核心** (`_core.pyi`): 可能有 Rust 加速层

### 写入方法
**Memorize Workflow (7步):**
1. `ingest_resource` — 获取原始资源
2. `preprocess_multimodal` — 多模态预处理（图片/视频/音频 → 文本）
3. `extract_items` — LLM 按 memory_type 提取结构化记忆，同时分配 category
4. `dedupe_merge` — 去重合并（目前 placeholder）
5. `categorize_items` — embedding + 持久化 item/resource/relation
6. `persist_index` — 更新 category summary + 可选 item reference
7. `build_response` — 构建响应

**提取 prompt 设计 (以 profile 为例):**
- 模块化 prompt: objective / workflow / rules / category / output / examples / input
- 严格限制: 只提取用户明确陈述的事实，不提取临时信息、assistant 内容
- 输出格式: XML (`<item><memory><content>...</content><categories>...</categories></memory></item>`)

### 检索方法
**RAG Retrieve Workflow (多级渐进):**
1. `route_intention` — LLM 判断是否需要检索 + 改写查询
2. `route_category` — embedding 搜索匹配 category (top_k)
3. `sufficiency_after_category` — LLM 判断 category summary 是否已足够
4. `recall_items` — 如不够，embedding 搜索 item (支持 similarity/salience 排序)
5. `sufficiency_after_items` — LLM 再判断是否足够
6. `recall_resources` — 如还不够，搜索原始 resource caption
7. `build_context` — 组装结果

**LLM Retrieve Workflow (替代方案):**
- 不用向量搜索，直接把 category/item/resource 信息交给 LLM 排序

### 写入时机
- **显式调用**: `service.memorize(resource_url, modality)`
- **Patch API**: `create_memory_item`, `update_memory_item`, `delete_memory_item` 手动 CRUD
- **OpenAI Wrapper**: 拦截 OpenAI chat 调用，自动在对话后触发 memorize + 在请求前 retrieve

## 3. Lifecycle层面

### 淘汰/上限
- **无自动淘汰机制**
- 无容量上限配置
- 提供 `clear_items(where)` 按条件批量清理
- 依赖应用层管理

### 去重/合并
- **content_hash 去重**: SHA256(normalize(summary) + memory_type) 的前 16 位
  - 写入时先算 hash → 查找同 scope 同 hash 的已有 item
  - 命中 → 走强化路径而非创建新 item
  - 归一化处理: lowercase + 合并空白字符
- **Reinforcement (强化)**:
  - 重复记忆不创建新条目，而是递增 `reinforcement_count` + 更新 `last_reinforced_at`
  - 可通过 `enable_item_reinforcement` 配置开关
- **Workflow dedupe_merge 步骤**: 目前是 placeholder，预留了未来扩展位

### 时间衰减
- **Salience 排序** (检索时，非存储时衰减):
  - 公式: `salience = similarity x reinforcement_factor x recency_factor`
  - `recency_decay_days` 控制半衰期（默认 30 天）
  - 强化次数越多 + 越近期 → 排序越高
- 通过 `ranking: "similarity" | "salience"` 配置，默认 similarity（纯向量）

## 4. Injection层面

### Token预算
- **无显式 token budget 管理**
- 通过各级 `top_k` 控制数量:
  - category top_k = 5
  - item top_k = 5
  - resource top_k = 5

### 分级加载
- **三级渐进检索** (最大亮点):
  1. **Category Summary** — 先搜 category 的自然语言摘要（最粗粒度，信息密度最高）
  2. **Memory Item** — category 不够时再搜 item（中等粒度）
  3. **Resource** — 还不够时搜原始资源 caption（最细粒度）
- 每级之间有 **sufficiency check** (LLM 判断已检索内容是否足够回答查询)
- 如果 category summary 就能回答，后续检索全部跳过 — 极大节省资源

**Category Summary 作为缓存层:**
- 本质上是对一组 item 的 LLM 压缩摘要
- item 增删改时通过 patch prompt 增量更新（不重新遍历所有 item）
- 支持 `[ref:ITEM_ID]` 引用，允许从 summary 追溯到原始 item

### 作用域隔离
- **可扩展 User Model**: 通过 `DefaultUserModel` 定义 scope 字段（默认 user_id）
- **`build_scoped_models`**: 动态生成带 scope 字段的 Pydantic model
- **`where` 过滤**: 所有 CRUD 和检索操作都支持 where 条件过滤
- **Repository 层统一 filter**: `matches_where` 在所有仓库查询中应用
- 支持扩展为 multi-agent (agent_id) / multi-session (session_id)

## 5. Abstraction层面

### 反思/提炼
- **Category Summary 增量合成** — 最接近反思的机制:
  - 新 item 写入 → 触发 category summary 更新
  - Patch prompt 方式: 告诉 LLM "原 summary" + "新增/修改/删除的内容" → 输出新 summary
  - 相当于每次写入时对该分类做一次局部反思
- **无全局反思**: 不会跨 category 整合信息
- **无定时重组**: category summary 仅在写入触发时更新

### Working Memory 和 Long-term Memory
- **无显式 Working/Long-term 区分**
- 所有 item 都是同级持久存储
- **Salience 机制部分替代了这种区分**:
  - `reinforcement_count` 高 + `last_reinforced_at` 近 = 类似 working memory 的"活跃"状态
  - 久未被强化的 item 自然下沉 (recency decay) = 类似 long-term memory
- **Category Summary 充当"压缩的 long-term memory"**:
  - 个别 item 可能被遗忘（排名低），但它们的信息已被 category summary 吸收

**总结**: memU 的独特优势在于三级渐进检索 + category summary 压缩层 + salience 排序。这三者组合使得检索效率极高（category summary 可能就够用），同时自然实现了记忆重要性的动态排序。强化去重机制简洁有效。但缺乏主动的记忆重组/整合能力和容量管理。
