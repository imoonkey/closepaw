# Leon Memory System Analysis

## 1. Product层面

### Memory分类
Leon 把记忆分为三个 **scope** 和多种 **kind**:

**Scope（作用域）:**
- `persistent` — 长期记忆，跨会话持久化（用户偏好、事实、知识）
- `daily` — 每日记忆，按天聚合的对话日志和摘要
- `discussion` — 讨论记忆，当前会话上下文（短期）

**Kind（记忆类型）:**
- `fact` / `preference` / `event` / `note` / `summary` / `knowledge` / `task`

**Source Type（来源类型）:**
- `explicit_user` / `inferred` / `tool_output` / `conversation` / `system`

此外还有一个独立的 **Facts 表**（`memory_facts`），从 persistent 记忆中提炼出的 key-value 结构化事实。

### 每类结构
每条记忆项的 schema：
- `id`, `scope`, `kind`, `title`, `content_md`, `content_text`
- `importance`（0-1）, `confidence`（0-1）
- `day_key`（YYYY-MM-DD）, `expires_at`（可选过期时间）
- `is_pinned`（置顶标记）, `dedupe_hash`（SHA256 去重哈希）
- `metadata_json`（扩展元数据）

Facts 表：`fact_key` → `fact_value_json` + `canonical_text` + `priority` + `last_seen_at`

## 2. System层面

### 架构
双层存储架构：
1. **SQLite** — 主存储，`memory_items` + `memory_facts` + `context_documents` 三张表
2. **QMD 索引** — 外部嵌入引擎（`qmd` CLI），维护 `leon-memory` 索引，支持语义搜索
3. **Markdown 镜像** — 每条记忆同时写入文件系统的 `.md` 文件，按 scope 分目录

目录结构：
```
core/memory/
├── persistent/YYYY/MM/DD/{id}.md
├── daily/{day_key}.md
├── discussion/{day_key}.md
└── index.sqlite
```

### 存储/索引
- SQLite 用 better-sqlite3，WAL 模式
- QMD 索引维护四个 collection：`context`、`memory-persistent`、`memory-daily`、`memory-discussion`
- 每个 collection 监控对应目录的 `**/*.md` 文件
- 嵌入刷新有最小间隔（30秒），索引更新有最小间隔（10秒）

### 写入方法
两种路径：
1. **Tool 主动写入**：LLM 通过 `memory.write()` tool 写入，指定 content/scope/kind 等
2. **自动提取**：`extractPersistentMemory()` 在每轮对话后用 LLM 判断是否包含值得持久化的信息，自动提取并写入 persistent scope

写入流程：
1. 计算 `dedupe_hash = SHA256(scope|kind|content_lowercase)`
2. 若已存在相同哈希，更新（upsert）；否则插入新记录
3. 若 scope=persistent 且 kind=fact/preference，额外 upsert 到 Facts 表
4. 写入 Markdown 镜像文件
5. 触发 QMD 索引更新和嵌入刷新

### 检索方法
**Recall 流程**（非常精细的多阶段检索）：
1. **初始搜索**：用 QMD 的 `query` 模式（语义）搜索，若无结果 fallback 到 `search` 模式（词法）
2. **Enrich 搜索**：若初始结果不够好（缺少 persistent 命中或排名质量差），追加词法搜索
3. **命名空间补全**：对缺失命中的 namespace，单独搜索补全
4. **Second Pass**：构建判别性二次查询，补充初始遗漏
5. **Rescue Pass**：构建桥接 token 进行挽救搜索
6. **Backtrack**：从已有结果中回溯补充候选
7. **排名**：多因素排名 = QMD score + query token overlap + namespace 权重（persistent=1.35, daily=0.85, discussion=0.65, context=0.8）
8. **Token 预算裁剪**：按预算选择 top-K 结果，每个 hit 按剩余预算裁剪内容
9. **Facts 注入**：从 Facts 表读取 top-8 高优先级事实

### 写入时机
- **对话结束后**：自动提取 persistent 记忆（若 user >= 4词 或 assistant >= 8词）
- **每日结束**：生成 daily summary
- **每轮对话**：记录 discussion 和 daily conversation logs
- **用户显式请求**：通过 memory tool 写入

## 3. Lifecycle层面

### 淘汰/上限
完善的生命周期管理：
- **Discussion TTL**：5天过期，30天软删除
- **Daily 原始日志**：90天后软删除非 summary 项
- **Discussion 归档**：180天冷归档（gzip 压缩）
- **软删除保留**：7天后物理删除
- **维护周期**：每6小时运行一次 `runStorageMaintenance()`
- **SQLite 优化**：定期 `PRAGMA optimize` + WAL checkpoint

### 去重/合并
- **写入时去重**：通过 `dedupe_hash`（SHA256 of scope|kind|content）实现 upsert
- **Persistent 近似去重**：写入前检查最近300条 persistent 记忆
  - 精确匹配：normalized 文本相同
  - 包含匹配：短文本完全包含在长文本中（min 40 chars）
  - Jaccard 相似度：token 集合相似度 >= 0.84 则跳过
- **Facts 合并**：相同 `fact_key` 的事实会被更新（upsert）

### 时间衰减
- **检索权重**中无显式时间衰减函数
- 但 `updatedAt DESC` 排序本身带有隐式新鲜度偏好
- Discussion 和 Daily 的 TTL 机制起到了间接的时间衰减效果

## 4. Injection层面

### Token预算
精确的预算控制：
- **Planning 阶段**：220 tokens（记忆）+ 1,200 tokens（context 文件）
- **Execution 阶段**：480 tokens
- **Persistent 提取**：max 220 tokens
- 每个 hit 有最低预算 48 tokens
- 首个 hit 可占用预算的 60%
- 后续 hit 按剩余/剩余slots 均分

### 分级加载
- **Recall 结果**按 namespace 优先级确保覆盖（先为每个 namespace 选一个代表）
- **Context 文件**单独注入（最多3个，按相关性排序）
- Facts 独立注入（top 8）
- 内容过长时 clip 并加 `...`

### 作用域隔离
- 5个 namespace 独立索引：`memory_persistent`, `memory_daily`, `memory_discussion`, `conversation_daily`, `context`
- Context namespace 可通过 `contextFilenames` 限制访问范围
- Skill 级别的隔离：每个 skill 的 Memory SDK 只能访问自己的 `memory/{name}.json`，跨 skill 只读

## 5. Abstraction层面

### 反思/提炼
- **Persistent 提取**：每轮对话后 LLM 自动提取值得长期保存的信息（JSON schema 约束输出）
- **Daily Summary**：每日对话自动汇总为 Markdown 摘要
- **Facts 结构化**：从 persistent 记忆中提取 key-value 事实，带 priority 排序
- 提取有超时（45秒）和重试（最多1次）保护

### Working Memory ↔ Long-term Memory
- **Working Memory** = discussion scope（5天 TTL，当前对话上下文）
- **Long-term Memory** = persistent scope（永久，仅通过提取或显式写入）
- **中间层** = daily scope（90天，对话日志 + 每日摘要）
- 转换路径：discussion → daily（自动日志）→ persistent（LLM 提取）
- 单向流动：短期记忆中的重要信息自动沉淀到长期记忆
