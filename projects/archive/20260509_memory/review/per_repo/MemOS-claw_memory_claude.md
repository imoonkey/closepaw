# MemOS (Claw Version) Memory System Analysis

## 1. Product层面

### Memory分类
MemOS 拥有最复杂的记忆分类体系，分为**三大记忆维度**和**多种记忆类型**：

**三大记忆维度（MemCube 模型）:**
1. **Activation Memory**（激活记忆）— KV Cache，LLM 的注意力状态
2. **Parametric Memory**（参数记忆）— LoRA adapter，模型微调权重
3. **Textual Memory**（文本记忆）— 结构化文本知识

**Textual Memory 子类型:**
- `NaiveText` — 简单文本记忆
- `GeneralText` — 通用文本 + 向量索引
- `TreeText` / `SimpleTreeText` — 树状结构文本（最复杂的实现）
- `PreferenceText` — 用户偏好记忆

**TreeText 内的 Memory Scope:**
- `WorkingMemory` — 当前任务上下文（类似 session 级别）
- `LongTermMemory` — 跨会话的持久知识
- `UserMemory` — 用户个人信息
- `ToolSchemaMemory` — 工具 schema
- `ToolTrajectoryMemory` — 工具使用轨迹
- `RawFileMemory` — 原始文件内容
- `SkillMemory` — 技能/经验指南
- `PreferenceMemory` — 偏好记忆

**Memory 对象内部分类（mem_reader）:**
- `objective_memory` — 客观事实（nickname, gender, birth, occupation, residence 等 16 个预定义 key）
- `subjective_memory` — 主观偏好（current_mood, response_style, language_style 等 9 个预定义 key）
- `scene_memory` — 场景记忆（qa_pair 对话对 + document 文档）

### 每类结构
**Textual Memory Item:**
- `id`, `memory`（文本内容）
- `metadata`: `key`, `tags[]`, `memory_type`(scope), `relativity`(score), `status`
- 存储在 Neo4j 图数据库中

**Memory 对象（mem_reader 输出）:**
```python
{
  "objective_memory": { key: { "value": ..., "info": { timestamp, confidence_score, origin_data } } },
  "subjective_memory": { key: { "value": ..., "info": {...} } },
  "scene_memory": {
    "qa_pair": { "section": [...], "info": { summary, label[], user_id, session_id } },
    "document": { "section": [...], "info": { doc_type, doc_category, summary } }
  }
}
```

## 2. System层面

### 架构

**核心引擎（Python, src/memos/）:**
```
MemOS Core
├── mem_reader — 从对话中提取记忆
│   ├── process_preference_memory — 偏好提取
│   └── process_skill_memory — 技能提取
├── mem_scheduler — 记忆调度
│   ├── memory_manage_modules/
│   │   ├── activation_memory_manager — KV Cache 管理
│   │   └── memory_filter — LLM 驱动的记忆过滤
│   ├── task_schedule_modules/
│   │   └── memory_update_handler — 记忆更新处理
│   └── base_mixins/memory_ops — 记忆操作基类
├── memories/textual/
│   └── tree_text_memory/retrieve/recall — 图+向量混合检索
├── configs/memory.py — 记忆配置工厂
└── api/handlers/memory_handler — REST API
```

**OpenClaw 集成（TypeScript, apps/memos-local-openclaw/）:**
```
memos-local-openclaw
├── storage/sqlite.ts — SQLite + FTS5 存储
├── recall/engine.ts — 检索引擎（RRF + MMR + 时间衰减）
├── embedding/ — 多 provider 嵌入（OpenAI, Voyage, Cohere, Gemini, Mistral）
├── ingest/ — 对话摄入（chunking, dedup, task processing）
├── tools/ — memory_search, memory_get, memory_timeline
└── skill/ — 技能系统（generator, evaluator, installer）
```

### 存储/索引
**核心引擎:**
- **Neo4j 图数据库** — 存储 TextualMemoryItem，支持图遍历和向量搜索
- **向量嵌入** — Ollama/API 嵌入器
- **BM25 索引** — EnhancedBM25 全文搜索
- **LoRA adapter** — 参数记忆的模型权重文件
- **Pickle** — 激活记忆的 KV Cache 序列化

**OpenClaw 集成:**
- **SQLite + FTS5** — chunks 表 + 全文搜索虚拟表
- **嵌入向量** — BLOB 存储在 embeddings 表中
- **WAL 模式** + 外键约束

### 写入方法
**核心引擎:**
- `mem_reader` 用 LLM 从对话中提取结构化记忆
- `update_user_memory()` 更新 objective/subjective memory（同 key 值用 `|` 连接历史）
- `add_qa_batch()` 批量添加 QA 对到 scene memory
- `process_qa_pair_summaries()` / `process_document_summaries()` LLM 生成摘要

**OpenClaw 集成:**
- `ingest/worker` 异步处理对话 chunk
- `ingest/chunker` 对话分块
- `ingest/dedup` 内容哈希去重
- `task-processor` 任务级别的摘要生成
- `skill/generator` 从任务中自动生成技能指南

### 检索方法
**核心引擎（GraphMemoryRetriever.retrieve）:**
1. **任务/目标解析**：LLM 从 query 中提取 `keys`（精确匹配）和 `tags`（标签重叠>=2）
2. **并行三路检索**：
   - Graph recall：Neo4j 元数据过滤（key + tag）
   - Vector recall：嵌入相似度搜索
   - BM25 recall：全文检索（可选）
   - Fulltext recall：Neo4j 全文索引（可选，fast_graph 模式）
3. **合并去重**：by ID

**OpenClaw RecallEngine.search:**
1. FTS5 全文搜索 — candidatePool = maxResults × 5
2. 向量搜索 — 嵌入查询
3. **RRF 融合**（Reciprocal Rank Fusion）— 合并两路结果
4. **MMR 重排**（Maximal Marginal Relevance）— 多样性优化，maxResults × 2
5. **时间衰减** — `decay(t) = 0.5^(age/halfLife)`，alpha=0.3 保底
6. 阈值过滤 + 归一化 + role 过滤
7. 构建 SearchHit（summary + excerpt + ref + taskId + skillId）

**MemoryFilter（LLM 驱动的后过滤）:**
- `filter_unrelated_memories` — LLM 判断记忆与 query 的相关性
- `filter_redundant_memories` — LLM 去除冗余记忆
- `filter_unrelated_and_redundant_memories` — 一步完成两种过滤

### 写入时机
- **对话结束后**：mem_reader 自动提取
- **异步后台**：ingest worker 处理新的对话 chunk
- **任务完成时**：task-processor 生成任务摘要
- **技能学习时**：skill generator 从任务中提炼技能指南

## 3. Lifecycle层面

### 淘汰/上限
**TreeText Memory:**
- `memory_size` 配置：`{"WorkingMemory": 20, "LongTermMemory": 10000, "UserMemory": 10000}`
- 按 scope 独立控制上限
- `status` 字段："activated" / 其他，支持软状态管理

**OpenClaw:**
- 无显式淘汰（SQLite 持续增长）
- 重复查询检测（最近20条）防止无效搜索

### 去重/合并
**核心引擎:**
- `update_user_memory()` 同 key 值合并（用 `|` 连接）
- LLM 驱动的冗余过滤（`filter_redundant_memories`）

**OpenClaw:**
- `ingest/dedup` 内容哈希去重
- RRF + MMR 天然去重（by chunkId）

### 时间衰减
**OpenClaw 的显式时间衰减（最完善的实现）:**
```
decay(t) = 0.5 ^ (age_days / half_life)
final_score = base_score × (0.3 + 0.7 × decay)
```
- 默认 half_life = 14天
- alpha = 0.3 保底（确保旧但高相关的结果不被清零）

**核心引擎:**
- 无显式时间衰减
- relativity score 不含时间因子

## 4. Injection层面

### Token预算
**OpenClaw:**
- `maxResultsDefault` / `maxResultsMax` 控制返回数量
- `excerptMaxChars` 控制单个 hit 的摘要长度
- `getMaxCharsDefault` / `getMaxCharsMax` 控制全文获取长度

**核心引擎:**
- 无显式 token 预算
- top_k 参数间接控制

### 分级加载
**OpenClaw 的工具链分级加载:**
1. **自动 recall（hook）**：每轮对话自动注入相关记忆
2. **memory_search**：agent 主动搜索（返回 summary + excerpt）
3. **task_summary**：按 taskId 获取完整任务摘要
4. **skill_get**：获取技能指南内容
5. **memory_timeline**：展开某个 hit 的前后上下文（±N turns）
6. **memory_get**：获取单个 chunk 的完整内容

层层递进：概览 → 详情 → 完整内容

### 作用域隔离
- **Owner 过滤**：每个 chunk/skill 有 owner 字段
- `resolveOwnerFilter(owner)` — 返回 `[owner, "public"]`，可访问自己的和公共的
- **User 级别**：core 引擎按 `user_name` 过滤
- **Cube 级别**：MemCube 是记忆的逻辑容器，可跨 Cube 检索

## 5. Abstraction层面

### 反思/提炼
**多层次提炼体系:**
1. **mem_reader**：LLM 从对话中提取 objective/subjective/scene memory
2. **QA batch summary**：多轮对话批量摘要
3. **Document summary**：文档级别摘要
4. **Task summary**（OpenClaw）：任务级别叙述性摘要
5. **Skill generation**（OpenClaw）：从任务中自动生成"怎么做"的技能指南
6. **Skill evolution**（OpenClaw）：技能随使用迭代升级
7. **LLM Memory Filter**：用 LLM 过滤不相关和冗余记忆
8. **Tree reorganization**：可选的记忆树重组（`reorganize` 配置）

### Working Memory ↔ Long-term Memory
- **Working Memory** = `WorkingMemory` scope（上限20条，当前任务上下文，status=activated）
- **Long-term Memory** = `LongTermMemory` scope（上限10000条，跨会话知识）
- **User Memory** = `UserMemory` scope（上限10000条，用户个人信息）
- 还有 `ToolSchemaMemory`、`ToolTrajectoryMemory`、`SkillMemory` 等特殊 scope
- 转换路径：
  - 对话 → mem_reader 提取 → 写入对应 scope
  - WorkingMemory 中的重要信息可升级到 LongTermMemory
  - 任务完成后自动生成 SkillMemory
- **特点**：业界最完整的记忆分类体系，三维度（激活/参数/文本）× 多 scope × 多类型
