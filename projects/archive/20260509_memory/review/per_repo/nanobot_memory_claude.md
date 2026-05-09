# Nanobot Memory System Analysis

## 1. Product层面

### Memory分类
两层记忆 + 会话历史：

| 类型 | 文件 | 用途 |
|------|------|------|
| Long-term Memory | `memory/MEMORY.md` | 持久事实（偏好、项目上下文、关系） |
| History Log | `memory/HISTORY.md` | Append-only 事件日志，grep 可搜索 |
| Session | `sessions/<key>.jsonl` | 对话历史，JSONL 格式 |

### 每类结构
- **MEMORY.md**: 结构化 Markdown 模板，分为 User Information / Preferences / Project Context / Important Notes 四个区段
- **HISTORY.md**: 每条记录以 `[YYYY-MM-DD HH:MM]` 开头的段落（2-5句摘要），append-only
- **Session JSONL**: 元数据行 + 消息行，包含 `last_consolidated` 游标追踪合并进度

## 2. System层面

### 架构
单体式双层记忆系统，直接嵌入 agent loop：
- `MemoryStore` 类管理 `MEMORY.md` + `HISTORY.md`
- `SessionManager` 管理会话历史（JSONL 持久化 + 内存缓存）
- `ContextBuilder` 负责把 memory 注入 system prompt
- 无插件系统，无向量索引

### 存储/索引
- **纯文件存储**: Markdown (MEMORY.md, HISTORY.md) + JSONL (sessions)
- **无向量索引**: 检索完全依赖 grep/文本搜索
- **Session 持久化**: JSONL 文件 `workspace/sessions/<safe_key>.jsonl`
  - 第一行是 metadata（key, created_at, updated_at, last_consolidated）
  - 后续行是消息（role, content, timestamp, tool_calls 等）
- **Legacy 迁移**: 自动从 `~/.nanobot/sessions/` 迁移到 workspace 目录

### 写入方法
两种写入路径：
1. **Agent 主动写入**: agent 通过 `write_file`/`edit_file` 工具直接修改 `memory/MEMORY.md`
2. **LLM-driven consolidation**: 专用的 `save_memory` tool call
   - 系统构造一个合并 prompt，包含当前 MEMORY.md 内容 + 待合并的对话
   - 调用 LLM（独立的合并 agent），LLM 通过 `save_memory` tool 返回两个字段：
     - `history_entry`: 2-5 句摘要段落，写入 HISTORY.md（append）
     - `memory_update`: 完整更新后的 MEMORY.md 内容（全量覆写）

### 检索方法
- **MEMORY.md**: 每次构建 system prompt 时全量加载（`get_memory_context()`）
- **HISTORY.md**: 不自动加载到 context，通过以下方式搜索：
  - 小文件：`read_file` 后内存搜索
  - 大文件：`exec` 工具执行 `grep -i "keyword" memory/HISTORY.md`
- **无语义搜索**: 完全依赖关键词匹配

### 写入时机
- **Auto-consolidation（核心机制）**:
  - 触发条件: `unconsolidated_messages >= memory_window`（默认100条）
  - 异步执行: `asyncio.create_task`，不阻塞当前消息处理
  - 使用 `asyncio.Lock` 防止并发合并
  - `_consolidating` set 追踪进行中的合并，避免重复触发
- **`/new` 命令**: 手动触发 `archive_all=True` 合并，然后清空 session
- **Agent 主动**: 对话中 agent 判断需要更新 MEMORY.md 时

## 3. Lifecycle层面

### 淘汰/上限
- **Session 消息上限**: `memory_window`（默认100条）控制送入 LLM 的历史窗口
- **Consolidation 窗口**: 合并时保留后半（`memory_window // 2`）条消息，合并前半
- **Tool result 截断**: 保存到 session 时截断为 `_TOOL_RESULT_MAX_CHARS`（500字符）
- **MEMORY.md / HISTORY.md 无大小限制**: 随时间无限增长
- **Session JSONL 无自动清理**: 消息 append-only，合并不删除旧消息

### 去重/合并
- **LLM-driven consolidation**: 合并 prompt 明确要求"include all existing facts plus new ones"
  - `memory_update` 是全量覆写，由 LLM 判断是否有新内容（`if update != current_memory`）
  - 理论上 LLM 会去重，但实际效果取决于模型质量
- **Session 消息不删除**: `last_consolidated` 是游标，标记已合并位置，但旧消息保留在 JSONL 中
- **`get_history()` 只返回未合并消息**: `session.messages[last_consolidated:][-max_messages:]`

### 时间衰减
- **无时间衰减机制**: HISTORY.md 中的条目权重相同
- **隐式衰减**: HISTORY.md 不加载到 context，旧内容只有被 grep 搜到才会被引用

## 4. Injection层面

### Token预算
- **无显式 token 预算**: MEMORY.md 全量注入 system prompt，无截断
- **隐式限制**: `memory_window` 控制历史消息数量（默认100条）
- **对话截断**: 无基于 token 的截断，只有基于消息数量的窗口

### 分级加载
两级：
1. **Always loaded**: MEMORY.md 全量注入 system prompt（`# Memory\n## Long-term Memory\n{content}`）
2. **On-demand**: HISTORY.md 需要 agent 主动用工具搜索
3. **Session history**: `get_history()` 返回 `memory_window` 条未合并消息

注意：Memory skill 标记为 `always: true`，说明相关指导总是注入 system prompt。

### 作用域隔离
- **Per-session**: 每个 `channel:chat_id` 独立 session
- **Shared memory**: MEMORY.md 和 HISTORY.md 被所有 session 共享（同一 workspace）
- **Workspace 级隔离**: 不同 workspace 有独立的 memory 目录

## 5. Abstraction层面

### 反思/提炼
- **LLM-driven consolidation 是核心反思机制**:
  - 系统用独立的 LLM 调用来"反思"对话内容
  - Prompt: "Process this conversation and call the save_memory tool with your consolidation"
  - System prompt: "You are a memory consolidation agent"
  - 输出两个维度：事件摘要（HISTORY.md）+ 事实更新（MEMORY.md）
- **全量覆写策略**: `memory_update` 返回完整的更新后 MEMORY.md，而非增量 diff
  - 优点：LLM 可以重新组织和去重
  - 风险：如果 LLM 遗漏旧事实，会导致信息丢失

### Working Memory <-> Long-term Memory
- **Working Memory**: 当前 session 的消息列表（`session.messages`），窗口为 `memory_window` 条
- **Long-term Memory**: `MEMORY.md`（事实）+ `HISTORY.md`（事件日志）
- **转化路径 (Working -> Long-term)**:
  1. 消息累积达到 `memory_window` 阈值
  2. 异步触发 consolidation
  3. LLM 阅读旧消息 + 当前 MEMORY.md
  4. LLM 调用 `save_memory` tool: 事件 -> HISTORY.md (append), 事实 -> MEMORY.md (overwrite)
  5. `last_consolidated` 游标前进
- **回溯路径 (Long-term -> Working)**:
  - MEMORY.md 自动注入每个 system prompt
  - HISTORY.md 需要 agent 用 grep 主动搜索

**独特之处**:
- **LLM-as-memory-consolidator**: 用独立 LLM 调用做记忆合并，而非规则/程序化处理。这是对 MEMORY.md 质量的最大赌注
- **双层分离设计**: 事实（MEMORY.md，可变，全量覆写）vs 事件（HISTORY.md，不可变，append-only），语义清晰
- **Grep-first 检索**: 完全放弃向量搜索，依赖精确关键词匹配。对于结构化日志（如 `[YYYY-MM-DD HH:MM]` 前缀），这种设计实用且可预测
- **消息 append-only**: 合并不删除旧消息，`last_consolidated` 游标决定 `get_history()` 返回范围。保留了完整历史但 JSONL 文件会持续增长
- **简单直接**: 整个 memory 系统约 160 行 Python，无数据库、无嵌入、无外部依赖
