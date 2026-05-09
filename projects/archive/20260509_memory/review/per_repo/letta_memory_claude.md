# Letta Memory System Analysis

## 1. Product层面

### Memory分类
Letta (前身 MemGPT) 定义了三层 memory 体系:
- **Core Memory (Block)**: 存在于 LLM context window 内的可编辑结构化块，如 `persona`、`human`。agent 通过 `core_memory_append` / `core_memory_replace` 工具直接修改
- **Archival Memory (Archive)**: 外部向量存储，无限容量。agent 通过 `archival_memory_insert` / `archival_memory_search` 工具读写
- **Recall Memory**: 完整对话历史的持久化存储，支持回溯检索

### 每类结构
- **Block**: `label` + `value`(纯文本) + `limit`(字符上限) + `description` + `read_only` 标志。可通过 `tags` 关联分组
- **FileBlock**: Block 的子类，关联文件 ID/源 ID，有 `is_open`/`last_accessed_at` 状态追踪
- **Archive**: 命名的 passage 集合，关联 embedding config，可在多 agent 间共享
- **Passage**: 向量化的文本片段，属于某个 Archive

## 2. System层面

### 架构
- Block 系统: Pydantic schema → SQLAlchemy ORM → PostgreSQL
- Archival 系统: 文本 → embedding → 向量数据库 (native 或 TurboPuffer)
- Recall 系统: 消息持久化到 PostgreSQL
- 新增 **git-backed memory**: 将 blocks 以 markdown 文件存储在 git 仓库中 (`system/human.md`)，支持版本追踪

### 存储/索引
- Core Memory: PostgreSQL `blocks` 表，支持 `block_history` 变更追踪
- Archival: 向量数据库 (native/TurboPuffer)，embedding 可选配置
- Recall: PostgreSQL `messages` 表，消息带 `agent_id`/`step_id`/`run_id` 关联

### 写入方法
- Core Memory: agent 在对话中通过 tool call 调用 `core_memory_append(label, content)` 或 `core_memory_replace(label, old_content, new_content)`
- Archival: agent 调用 `archival_memory_insert(text, tags?)`
- Git Memory: 通过 `BlockManagerGit` 将 block 变更同步到 git repo

### 检索方法
- Core Memory: 直接嵌入 system prompt (全量注入)
- Archival: 向量相似度搜索 (`archival_memory_search(query)`)
- Recall: 数据库查询历史消息
- 外部 memory summary: 在 context window 中注入 archival/recall 的元数据摘要 (条目数量等)

### 写入时机
- Core Memory: agent 在对话过程中主动决定写入 (tool call)
- Archival: agent 主动写入 (tool call)
- Summary: 当消息数超过 buffer limit 时自动触发

## 3. Lifecycle层面

### 淘汰/上限
- **Core Memory**: 每个 block 有硬性字符上限 (`CORE_MEMORY_BLOCK_CHAR_LIMIT`)，写入时校验
- **Context Window 管理**: 两种 summarization 策略:
  - `STATIC_MESSAGE_BUFFER`: 保持固定条数消息窗口，超出时裁剪旧消息
  - `PARTIAL_EVICT_MESSAGE_BUFFER`: 按比例淘汰旧消息 (默认 30%)，生成递归摘要替换
- **Archival**: 无上限

### 去重/合并
- Core Memory 的 `core_memory_replace` 是精确字符串匹配替换，不做自动去重
- Block 层面有 `validate_file_blocks_no_duplicates` 校验
- 无自动合并逻辑 — 由 agent 自主决策

### 时间衰减
- 无显式时间衰减机制
- 通过 summarization 间接实现: 旧消息被摘要替代后，细节信息自然丢失

## 4. Injection层面

### Token预算
- `ContextWindowOverview` 精确追踪每个部分的 token 占用: system prompt / core memory / summary memory / functions / messages 等
- Summarizer 有 context window overflow fallback: 先截断 tool return → 再 middle-truncate transcript

### 分级加载
- Core Memory: 全量注入 context window
- FileBlocks: 有 open/closed 状态，可限制同时打开文件数 (`max_files_open`)
- Archival/Recall: 按需搜索，仅注入摘要元数据
- Git memory: 通过 `_render_memory_filesystem()` 渲染文件树视图 + 仅展开 `system/` 前缀的 blocks

### 作用域隔离
- Block 通过 `label` 命名空间隔离 (如 `system/human`, `skills/xxx`)
- Archive 关联 `organization_id`，可通过 `ArchivesAgents` 在多 agent 间共享
- FileBlock 关联 `source_id` / `folder_id`

## 5. Abstraction层面

### 反思/提炼
- **EphemeralSummaryAgent**: 独立的摘要 agent，将淘汰消息写入目标 block (target_block_label)
- **Sleeptime Agent**: 异步的"后台思考"agent，在主 agent 空闲时处理 memory 提炼
- 摘要采用 fire-and-forget 模式异步执行，不阻塞主对话流

### Working Memory <-> Long-term Memory
- **Working Memory** = Core Memory blocks (in-context) + 当前消息窗口
- **Long-term Memory** = Archival Memory (向量存储) + Recall Memory (完整历史) + Summary Memory (递归摘要)
- 转换机制: 消息从 working memory 淘汰后 → 由 summarizer 生成摘要 → 写入 summary block 或持久化；agent 可主动将重要信息写入 archival
- 独特设计: agent 完全自主控制 memory 的读写决策 (通过 tool call)，这是 Letta 与其他系统的核心差异
