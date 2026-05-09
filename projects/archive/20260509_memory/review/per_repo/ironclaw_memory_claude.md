# IronClaw Memory System Analysis

## 1. Product层面

### Memory分类
**Workspace 文件系统 + Job Context Memory**, 两层独立系统:

1. **Workspace Memory** (持久化, 跨会话):
   - `MEMORY.md` -- 长期策展记忆
   - `daily/YYYY-MM-DD.md` -- 每日日志 (带时间戳)
   - `HEARTBEAT.md` -- 周期性检查清单
   - `IDENTITY.md`, `SOUL.md`, `AGENTS.md`, `USER.md` -- 身份文件 (注入系统提示)
   - `BOOTSTRAP.md` -- 首次运行仪式
   - `TOOLS.md` -- 环境工具说明
   - 任意自定义路径 (如 `projects/alpha/notes.md`)

2. **Job Context Memory** (会话级, 内存中):
   - `ConversationMemory` -- 对话消息历史 (有上限)
   - `ActionRecord` -- 工具执行记录 (含 input/output/cost/duration)

### 每类结构
**Workspace**: `MemoryDocument` -- id(UUID), path, content, word_count, created_at, updated_at。文档被分块为 `MemoryChunk` (800 words, 15% overlap)，每个 chunk 可带 embedding 向量。

**Job Context**: `ActionRecord` -- id(UUID), sequence, tool_name, input(JSON), output_raw, output_sanitized, sanitization_warnings, cost(Decimal), duration, success, error, executed_at。

## 2. System层面

### 架构
```
Workspace (PostgreSQL/libSQL)
├── Documents (文件系统抽象)
│   ├── CRUD: read/write/append/delete/list
│   └── 虚拟目录结构
├── Chunks (文档分块)
│   ├── 800 words, 15% overlap
│   └── 可选 embedding 向量
├── Search Engine
│   ├── FTS (PostgreSQL ts_rank_cd / libSQL FTS5)
│   ├── Vector (pgvector cosine / libSQL BLOB)
│   └── RRF 融合
└── Identity Files -> System Prompt
```

**双数据库后端**: PostgreSQL (生产) + libSQL/Turso (本地/边缘)。所有新功能必须同时支持两种后端。

### 存储/索引
**文档存储**: PostgreSQL 的 `workspace_documents` 表 / libSQL 等效表。

**分块索引**:
1. `workspace_chunks` 表: document_id, chunk_index, content, embedding
2. **PostgreSQL**: `ts_rank_cd` FTS + `pgvector` 余弦相似度
3. **libSQL**: FTS5 + BLOB 存储向量

**Embedding 提供者** (可选):
- OpenAI (`text-embedding-3-small/large`)
- NEAR AI
- Ollama (本地)
- 支持 backfill: `backfill_embeddings()` 补充缺失的 embedding

### 写入方法
**四个 memory 工具**:
1. `memory_write` -- 写入/追加到指定 target:
   - `memory` -> MEMORY.md
   - `daily_log` -> `daily/YYYY-MM-DD.md` (默认, 自动添加 `[HH:MM:SS]` 时间戳)
   - `heartbeat` -> HEARTBEAT.md
   - `bootstrap` -> 清空 BOOTSTRAP.md (首次运行仪式完成)
   - 自定义路径 -> 任意文件
2. `memory_read` -- 读取文件
3. `memory_search` -- 混合搜索
4. `memory_tree` -- 查看目录树

**写入后自动 reindex**: 写入/追加后自动重新分块 + 生成 embedding + 更新索引。

**安全保护**: IDENTITY.md, SOUL.md, AGENTS.md, USER.md 是 protected files, 工具不可覆盖 (防 prompt injection)。Rate limit: 20次/分钟, 200次/小时。

### 检索方法
**Hybrid Search (RRF)**: Reciprocal Rank Fusion
1. FTS 检索: PostgreSQL `ts_rank_cd` / libSQL FTS5, 返回 ranked results
2. Vector 检索: embedding cosine similarity, 返回 ranked results
3. RRF 融合: `score(d) = sum(1 / (k + rank(d)))`, k=60 (默认)
4. 归一化到 [0,1], 可设 min_score 阈值
5. `pre_fusion_limit=50`: 每种方法最多取50条再融合

**与 ZeroClaw 的区别**: ZeroClaw 用加权分数融合 (weighted score merge), IronClaw 用 RRF (rank-based fusion)。RRF 对不同方法的分数尺度更鲁棒。

### 写入时机
- **Agent 主动写入**: LLM 通过 `memory_write` 工具
- **Daily log 自动时间戳**: `append_daily_log` 自动添加 `[HH:MM:SS]` 前缀
- **Heartbeat 周期写入**: 心跳系统 (默认30分钟) 执行后可写入 HEARTBEAT.md
- **Bootstrap 首次运行**: 引导 agent 主动了解用户并写入 MEMORY.md + TOOLS.md
- **CLI 写入**: `ironclaw memory write` 命令行

## 3. Lifecycle层面

### 淘汰/上限
**ConversationMemory (Job 级)**: 硬上限 `max_messages=100`, 超出时 FIFO 淘汰, 但保留 system message。

**Workspace Hygiene** (`workspace/hygiene.rs`):
- **定时执行**: 基于 cadence 节奏, AtomicBool 防并发
- **Daily log 清理**: 超过 retention period 的 `daily/*.md` 删除
- **Conversation 文档清理**: 超过 retention period 的 `conversations/*.md` 删除
- **Identity 保护**: MEMORY.md, IDENTITY.md, SOUL.md 等永远不删
- **状态持久化**: `~/.ironclaw/hygiene_state.json` 记录最后运行时间

### 去重/合并
**无自动去重/合并**。MEMORY.md 靠 agent 自行管理。

Heartbeat 系统提示中暗示 agent 应该"Organize and curate MEMORY.md (remove stale, consolidate dupes)"，但这依赖 agent 的自主行为。

### 时间衰减
**无时间衰减**。搜索评分纯基于 RRF (文本相关性 + 语义相似度), 不考虑时间因素。Hygiene 按固定阈值清理, 不做渐进衰减。

## 4. Injection层面

### Token预算
- **Skills 系统**: `SKILLS_MAX_TOKENS=4000` -- skills prompt 注入的 token 上限
- **Search limit**: 默认5条, 最大20条 (`memory_search` 工具参数)
- **无 MEMORY.md 的 token 限制**: 全量注入系统提示, 没有截断
- **Heartbeat**: "effectively empty" 检测 -- 纯注释内容跳过 LLM 调用

### 分级加载
**System Prompt 注入层级** (`system_prompt_for_context`):
1. **Bootstrap** (仅首次): BOOTSTRAP.md
2. **Identity files**: AGENTS.md, SOUL.md, USER.md, IDENTITY.md (总是注入)
3. **Tool notes**: TOOLS.md
4. **Long-term memory**: MEMORY.md (非 group chat 时注入)
5. **Recent context**: 今天+昨天的 daily log

**Tool 主动检索**:
- `memory_search` -- 按需混合搜索 ("MUST be called before answering questions about prior work")
- `memory_read` -- 精确读取文件
- `memory_tree` -- 浏览结构

**Group Chat 隔离**: `is_group_chat=true` 时不注入 MEMORY.md, 防止隐私泄露。

### 作用域隔离
- **User ID 隔离**: Workspace 按 user_id 分区
- **Agent ID 隔离**: 可选的 agent_id 进一步隔离 (多 agent 场景)
- **Group Chat 隔离**: MEMORY.md 不注入 group chat
- **Protected files**: 4个身份文件不可通过工具覆盖 (case-insensitive 匹配)
- **Tool sanitization**: memory 工具标记为 `requires_sanitization=false` (内部可信内容)

## 5. Abstraction层面

### 反思/提炼
**无自动反思/提炼机制**。

Heartbeat 系统的 seed 模板中建议 agent:
- "Organize and curate MEMORY.md (remove stale, consolidate dupes)"
- "Update daily logs with session summaries"
- "Clean up context/ documents that are outdated"

但这完全依赖 agent 的自主判断和执行, 不是系统自动化的。

**AGENTS.md 中的指引**:
- "Write things down. Mental notes do not survive restarts."
- "Always search memory before answering questions about prior conversations"

### Working Memory <-> Long-term Memory
- **Working Memory**: `ConversationMemory` (内存中 Vec<ChatMessage>, max 100 条) + `ActionRecord` (工具执行历史)
- **Long-term Memory**: Workspace 系统 (数据库支持, 带索引和搜索)
- **注入**: long-term -> system prompt (身份文件+MEMORY.md+最近日记) 每轮自动注入
- **写入**: agent 通过 `memory_write` / `memory_search` 工具显式操作 long-term memory
- **无自动迁移**: 对话结束后不自动从 working memory 提取写入 long-term

**独特设计**:
1. **文件系统抽象**: 数据库支持的虚拟文件系统, 而非简单 KV store。支持目录、listing、tree 操作。
2. **RRF 混合搜索**: 比 ZeroClaw 的加权融合更鲁棒, 对分数尺度差异不敏感。
3. **Document Chunking**: 800 words + 15% overlap, 确保跨边界内容可搜索。
4. **身份文件安全**: protected files 防 prompt injection 攻击, case-insensitive 匹配。
5. **Bootstrap 仪式**: 首次运行引导 agent 主动了解用户, 完成后删除 BOOTSTRAP.md, 不会重复触发。
6. **Heartbeat 前瞻性执行**: 定期执行 (30分钟), 读取 HEARTBEAT.md 清单, 有发现时主动通知用户。
7. **Group Chat 隐私**: 自动排除个人 MEMORY.md, 防止隐私泄露到群聊。
