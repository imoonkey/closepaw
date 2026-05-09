# ZeroClaw Memory System Analysis

## 1. Product层面

### Memory分类
四种 `MemoryCategory`:
- **Core** -- 长期事实、偏好、决策（永久性）
- **Daily** -- 每日会话日志
- **Conversation** -- 对话上下文
- **Custom(String)** -- 用户自定义分类（如 "project"）

### 每类结构
统一的 `MemoryEntry` 结构体:
- `id` (UUID), `key` (唯一标识), `content` (文本), `category`, `timestamp`, `session_id` (可选), `score` (检索时填充)

key 是用户显式命名的语义标识符（如 `user_lang`, `project_stack`），不是自动生成的。

## 2. System层面

### 架构
**多后端 trait-driven 架构**，Memory trait 定义了 `store/recall/get/list/forget/count/health_check` 7个方法。

实现后端（6种）:
| 后端 | 存储 | 检索方式 | 适用场景 |
|------|------|---------|---------|
| **SqliteMemory** | SQLite `brain.db` | 混合检索 (向量+FTS5) | 默认主力 |
| **LucidMemory** | SQLite + 外部 Lucid CLI | 本地+远程合并 | 增强检索 |
| **MarkdownMemory** | 本地 `.md` 文件 | 文本匹配 | 轻量级 |
| **PostgresMemory** | PostgreSQL | SQL 查询 | 服务器部署 |
| **QdrantMemory** | Qdrant 向量数据库 | 向量检索 | 语义搜索为主 |
| **NoneMemory** | 无 | 无 | 禁用内存 |

工厂函数 `create_memory()` 根据配置自动选择后端。

### 存储/索引
**SqliteMemory** (核心后端) 使用三层索引:
1. **主表 `memories`**: id, key, content, category, embedding(BLOB), created_at, updated_at
2. **FTS5 虚拟表 `memories_fts`**: BM25 全文检索
3. **向量搜索**: embedding 存为 BLOB, 使用 cosine similarity

**embedding 缓存表 `embedding_cache`**: content_hash -> embedding, 避免重复 API 调用，LRU 淘汰。

### 写入方法
- **Tool 驱动**: `memory_store` 工具，agent 主动调用
- **参数**: key (唯一标识) + content (内容) + category (可选, 默认 core)
- **幂等写入**: 相同 key 会覆盖（`INSERT OR REPLACE`）
- **写入时自动生成 embedding**（如果 embedder 可用）并同步更新 FTS5
- **安全策略**: 受 SecurityPolicy 控制，read-only 模式或 rate limit 可阻止写入

### 检索方法
**混合检索** (Hybrid Merge):
1. 向量检索: query -> embedding -> cosine similarity 扫描所有 entries
2. 关键词检索: FTS5 BM25 评分
3. 加权融合: `final_score = vector_weight * vs + keyword_weight * ks`（默认 0.7/0.3）
4. BM25 分数归一化到 [0,1]，向量分数天然在 [0,1]

**MemoryLoader** 负责将检索结果注入上下文:
- 默认 limit=5, min_relevance_score=0.4
- 过滤掉 `assistant_resp_*` 旧自动保存条目（防止幻觉回注）
- 格式化为 `[Memory context]\n- key: content`

**LucidMemory 级联检索**: 先查本地 SQLite, 如果命中数 < threshold (默认3)，再查 Lucid CLI，去重合并结果。

### 写入时机
- **Agent 主动写入**: LLM 判断需要记住时调用 `memory_store` tool
- **自动保存**: agent loop 中对较长的用户消息 (>20 chars) 做 autosave（key 为 UUID 前缀）
- **CLI 写入**: 用户通过 `zeroclaw memory store` 命令行写入

## 3. Lifecycle层面

### 淘汰/上限
**Hygiene 系统** (`hygiene.rs`): 定时清理，12小时执行一次
- **Daily memory 文件归档**: 超过 `archive_after_days` (默认7天) 移入 `memory/archive/`
- **Session 文件归档**: 同上
- **归档文件清除**: 超过 `purge_after_days` (默认30天) 物理删除
- **Conversation 行清理**: SQLite 中 category=conversation 且 updated_at 超过 `conversation_retention_days` 的行直接 DELETE
- **Core 类不受影响**: 只清 conversation 和 daily

**Response Cache**: 独立 SQLite 数据库, TTL 可配 (默认60分钟), max_entries 上限, LRU 淘汰。

### 去重/合并
- **Key 唯一性**: 相同 key 的 store 操作会覆盖旧值（`INSERT OR REPLACE`）
- **LucidMemory 合并去重**: merge_results 按 `key+content` 的 lowercase 签名去重
- **Legacy autosave 过滤**: `is_assistant_autosave_key()` 识别并跳过旧的 `assistant_resp_*` 条目

### 时间衰减
**无显式时间衰减**。检索评分纯基于语义/关键词相关性，不考虑时间因素。Hygiene 系统按固定阈值清理，不做渐进衰减。

## 4. Injection层面

### Token预算
- **MemoryLoader**: limit=5 条目, min_relevance=0.4 过滤
- **LucidMemory**: `token_budget` 参数 (默认200), 传给 Lucid CLI 的 `--budget` 参数
- **无全局 token 预算管理**: memory context 直接拼接到 prompt, 长度不可控

### 分级加载
**两级加载**:
1. **System prompt 注入**: 无条件加载（通过 MemoryLoader）
2. **Tool 主动检索**: agent 调用 `memory_recall` tool 做按需查询

没有"先加载摘要、再按需加载全文"的分级策略。

### 作用域隔离
- **session_id 可选隔离**: store/recall 支持 session_id 参数，可按会话隔离
- **Category 过滤**: list 操作可按 category 过滤
- **无用户级/项目级隔离**: 所有 memory 共享同一个 brain.db

## 5. Abstraction层面

### 反思/提炼
**Snapshot 系统** (`snapshot.rs`):
- **导出**: Core 类 memory -> `MEMORY_SNAPSHOT.md` (Markdown 格式, Git 可见)
- **水合**: brain.db 丢失时, 从 MEMORY_SNAPSHOT.md 自动恢复（冷启动保护）
- 这是"灵魂备份", 不是真正的反思/提炼

**无 LLM 驱动的反思**: 没有定期让 LLM 总结/合并/提炼 memory 的机制。Memory 的质量完全依赖 agent 写入时的判断。

### Working Memory <-> Long-term Memory
- **Working Memory**: 对话历史 (message history), 不在 Memory 系统中管理
- **Long-term Memory**: Memory trait 管理的所有条目 (brain.db)
- **桥梁**: MemoryLoader 在每轮对话时从 long-term 检索相关条目注入 working context
- **单向流动**: working -> long-term 仅通过 agent 显式调用 `memory_store`
- **无自动提炼**: 对话结束时不会自动从对话历史中提取关键信息写入 long-term

**独特设计**: LucidMemory 作为 SQLite 的"增强层"，本地 SQLite 是权威数据源，Lucid CLI 是补充检索源，失败时优雅降级到纯本地。
