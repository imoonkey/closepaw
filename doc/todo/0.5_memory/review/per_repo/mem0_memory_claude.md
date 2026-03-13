# mem0 Memory System Analysis

## 1. Product层面

### Memory分类
mem0 定位为"面向 AI 应用的记忆层"，记忆类型相对简单：

1. **Factual Memory (事实记忆)** — 从对话中提取的结构化事实，存于向量库
2. **Graph Memory (图记忆)** — 实体-关系三元组，存于 Neo4j（可选）
3. **Procedural Memory (过程记忆)** — Agent 执行历史的结构化摘要

提取维度按 actor 区分：
- **User Memory**: 从用户消息中提取事实（默认）
- **Agent Memory**: 从 assistant 消息中提取 agent 特征（当 agent_id 存在时）

### 每类结构

**MemoryItem:**
- `id`, `memory` (事实文本), `hash`, `metadata`, `score`, `created_at`, `updated_at`
- metadata 包含: user_id, agent_id, run_id, actor_id, role

**Graph Memory (三元组):**
- `source` → `relationship` → `destination`
- 节点属性: name, user_id, agent_id, run_id, embedding, created, mentions
- 关系属性: created, mentions

**Procedural Memory:**
- 结构化的 agent 执行历史（Task Objective, Progress Status, Sequential Agent Actions）
- 每步包含: Agent Action, Action Result, Key Findings, Navigation History, Errors, Context

## 2. System层面

### 架构
扁平两层架构：
- **Memory** 类 — 核心，协调向量库 + 图库 + SQLite
- **MemoryGraph** — 图记忆的独立实现

设计哲学：简洁优先。一个 Memory 实例 = 一个集合，所有操作通过 user_id/agent_id/run_id 过滤。

### 存储/索引
- **向量存储**: 支持 25+ 后端 (Qdrant, Pinecone, Milvus, Chroma, FAISS, pgvector, Redis, Weaviate, Elasticsearch, MongoDB 等)
- **图存储**: Neo4j（通过 langchain_neo4j），节点上的 embedding 用于向量相似度搜索
- **历史存储**: SQLite（本地 `~/.mem0/history.db`），记录每次记忆变更
- **Embedding**: 支持 OpenAI, Ollama 等多种 embedding 模型
- **Reranker**: 可选，支持 Cohere, HuggingFace, SentenceTransformer 等

### 写入方法
**向量存储写入 (核心流程):**
1. 消息 → LLM 事实提取 (`FACT_RETRIEVAL_PROMPT`) → facts 列表
2. 每个 fact → embedding → 向量库搜索 top-5 相似记忆 → 得到 old_memory
3. old_memory + new_facts → LLM 决策 (`DEFAULT_UPDATE_MEMORY_PROMPT`) → ADD/UPDATE/DELETE/NONE
4. 执行对应操作 → 写入向量库 + SQLite 历史

**UUID 防幻觉技巧**: 将 UUID 映射为整数 ID 传给 LLM，防止 LLM 生成虚假 ID

**图存储写入:**
1. LLM 提取实体 (`EXTRACT_ENTITIES_TOOL`) → entity_type_map
2. LLM 建立关系 (`RELATIONS_TOOL`) → 三元组列表
3. 搜索图中已有相似节点 (cosine similarity >= threshold)
4. LLM 决定删除过时关系 (`DELETE_MEMORY_TOOL_GRAPH`)
5. MERGE 写入节点和关系 (去重合并)

**非推理模式 (infer=False):**
- 跳过 LLM 提取，直接将 message content 作为记忆写入

### 检索方法
**向量检索:**
- embedding 相似度搜索 → 可选 reranker 重排

**图检索:**
1. LLM 从 query 中提取实体
2. 对每个实体 embedding → Neo4j 向量相似度搜索 (cosine >= threshold)
3. 获取匹配节点的所有入边和出边关系
4. BM25 对关系三元组重排
5. 返回 top-5 关系

### 写入时机
- **显式调用**: `memory.add(messages)` 由应用层触发
- **并行执行**: 向量存储和图存储写入通过 ThreadPoolExecutor 并行
- 无异步/后台/定时写入机制

## 3. Lifecycle层面

### 淘汰/上限
- **无自动淘汰机制**
- 无容量上限配置
- 仅提供手动 `delete` 和 `delete_all` API
- 图存储有 `limit` 参数限制查询返回数量（默认 100）

### 去重/合并
- **LLM 驱动的智能合并**: 核心机制
  - 新 fact 提取后，搜索 top-5 相似旧记忆
  - LLM 判断: 相同信息 → NONE; 新信息 → ADD; 过时/更详细 → UPDATE; 矛盾 → DELETE
  - UPDATE 示例: "likes cheese pizza" + "loves chicken pizza" → "loves cheese and chicken pizza"
- **图存储去重**: MERGE 语法天然去重，匹配已有节点时递增 mentions 计数
- **节点匹配阈值**: cosine similarity >= 0.7（可配置），超过阈值视为同一实体

### 时间衰减
- **无时间衰减机制**
- 有 `created_at` / `updated_at` 时间戳但未用于排序或衰减
- 图存储的 `mentions` 计数可间接反映重要性（频次而非时效性）

## 4. Injection层面

### Token预算
- **无 token budget 管理**
- 通过 `limit` / `top_k` 控制返回数量
- 完全依赖应用层管理注入量

### 分级加载
- **无分级加载**
- 单级检索: 向量搜索 → 可选 reranker → 返回结果
- 图和向量结果并列返回 (`results` + `relations`)

### 作用域隔离
- **三维 ID 过滤**: user_id / agent_id / run_id
- 至少需要提供一个 ID
- 所有 CRUD 操作都带 filters
- 图存储查询: WHERE 子句过滤 user_id + agent_id + run_id
- **actor_id**: 额外的 actor 级过滤（query-time only，不存入 metadata）

## 5. Abstraction层面

### 反思/提炼
- **无主动反思/提炼机制**
- LLM 在写入时做一次性的 fact 提取和合并决策
- 不会回顾已有记忆进行再组织
- Procedural Memory 是对 agent 历史的一次性摘要，不迭代

### Working Memory 和 Long-term Memory
- **无显式区分**
- 所有记忆都是同一级，直接写入向量库
- 无 working → long-term 迁移机制
- 无记忆层级或生命周期状态机
- SQLite history 表记录变更历史 (old_memory → new_memory, event)，但仅作审计用，不影响检索

**总结**: mem0 的设计哲学是"简洁实用"。核心亮点是 LLM-in-the-loop 的写入合并机制和广泛的向量库/图库后端支持。但在记忆生命周期管理（淘汰、衰减、层级化）方面几乎为空白，完全依赖应用层决策。
