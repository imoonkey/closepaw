# OpenClaw Memory System Analysis

## 1. Product层面

### Memory分类
两层记忆 + 可选向量搜索 + 可选会话转录索引：

| 类型 | 文件 | 用途 |
|------|------|------|
| Long-term Memory | `MEMORY.md` | 人工策展的持久事实（偏好、决策、关键信息） |
| Daily Log | `memory/YYYY-MM-DD.md` | 每日追加日志，运行上下文 |
| Session Transcript | `sessions/*.jsonl` | 完整对话记录（实验性，可索引） |

### 每类结构
- **MEMORY.md**: 自由格式 Markdown，由用户/agent 手动编写维护
- **Daily Log**: 按日期命名的 Markdown 文件，append-only，由 agent 写入或 memory flush 自动触发写入
- **Session Transcript**: JSONL 格式，每行一个消息/事件，由系统自动记录

## 2. System层面

### 架构
插件化设计，memory 系统通过 plugin slot 挂载：
- 默认后端 `memory-core`（内置 SQLite indexer）
- 可选后端 `qmd`（外部 sidecar：BM25 + vector + reranking）
- 可通过 `plugins.slots.memory = "none"` 完全禁用

### 存储/索引
- **源文件**: 纯 Markdown（文件即真相源）
- **向量索引**: per-agent SQLite 数据库 `~/.openclaw/memory/<agentId>.sqlite`
  - `files` 表：path, hash, mtime, size
  - `chunks` 表：id, path, start_line, end_line, hash, model, text, embedding
  - `embedding_cache` 表：缓存 chunk 嵌入向量，避免重复 embed
  - 可选 FTS5 全文索引表（BM25 关键词搜索）
  - 可选 `sqlite-vec` 扩展加速向量查询（vec0 虚拟表）
- **QMD 后端**: 独立 SQLite + 本地 GGUF 模型，BM25 + 向量 + reranking

### 写入方法
Agent 通过文件系统工具直接写 Markdown 文件：
- 用户说"remember this" → agent 调用文件工具写入 `MEMORY.md`
- 日常笔记 → agent 写入 `memory/YYYY-MM-DD.md`
- **Pre-compaction memory flush**: 系统自动触发的隐式写入（见下方写入时机）

### 检索方法
两个 agent-facing 工具：
1. **`memory_search`**: 语义搜索，返回 snippet + file path + line range + score
   - 混合搜索：`vectorWeight * vectorScore + textWeight * textScore`（默认 0.7/0.3）
   - 可选 MMR re-ranking（去重复，lambda=0.7）
   - 可选 temporal decay（指数衰减，半衰期30天）
   - 候选池放大 `candidateMultiplier`（默认4x）
2. **`memory_get`**: 按路径 + 行范围精确读取 Markdown 文件内容

### 写入时机
- **用户主动**: 对话中要求 agent 记住某些内容
- **Agent 主动**: agent 判断需要记录决策/事实时
- **Pre-compaction memory flush（核心机制）**:
  - 触发条件1: token 用量接近 `contextWindow - reserveTokensFloor - softThresholdTokens`
  - 触发条件2: 会话 transcript 字节数超过 `forceFlushTranscriptBytes`（默认 2MB）
  - 机制: 系统注入一个 silent agentic turn，提醒 model 把持久记忆写到 `memory/YYYY-MM-DD.md`
  - 通常以 `NO_REPLY` 结束（用户不可见）
  - 每个 compaction 周期只触发一次

## 3. Lifecycle层面

### 淘汰/上限
- **搜索结果上限**: `maxResults`（默认6条），`maxSnippetChars`（约700字符）
- **QMD 注入上限**: `maxInjectedChars` 按字符预算裁剪结果
- **Embedding cache**: `maxEntries`（默认50000条），超出时可能淘汰旧条目
- **Session transcript**: 可配置 `retentionDays`
- **Markdown 文件本身无上限**: 依赖用户策展

### 去重/合并
- **MMR (Maximal Marginal Relevance)**: 用 Jaccard 文本相似度检测近似重复 snippet，降低冗余
  - 公式: `lambda * relevance - (1-lambda) * max_similarity_to_selected`
- **Chunk 去重**: 索引按 chunk hash 判断是否需要重新 embed
- **无自动合并机制**: MEMORY.md 的合并完全依赖 agent/用户手动维护

### 时间衰减
- **Temporal decay（可选，默认关闭）**:
  - `decayedScore = score * e^(-lambda * ageInDays)`，lambda = ln(2)/halfLifeDays
  - 默认半衰期30天：今天100%，7天84%，30天50%，90天12.5%，180天1.6%
- **Evergreen 文件不衰减**: `MEMORY.md` 和 `memory/` 下非日期命名文件永不衰减
- **日期来源**: 从文件名解析 `YYYY-MM-DD`；其他来源用文件 mtime

## 4. Injection层面

### Token预算
- **搜索侧**: snippet 约700字符，最多6条结果
- **QMD 模式**: `maxInjectedChars` 硬上限
- **Memory flush 预算**: `softThresholdTokens`（默认4000 tokens）作为 flush 提前量
- **Context window guard**: 最低16K tokens 硬下限，32K tokens 警告线
- **Compaction reserve**: `reserveTokensFloor`（默认20000 tokens）

### 分级加载
- **Session 启动时**: 加载今天 + 昨天的 daily log
- **MEMORY.md**: 仅在 main/private session 中加载，group context 中不加载
- **memory_search**: 按需调用，agent 决定何时搜索
- **Session transcript 索引**: 异步后台索引，delta 阈值触发（100KB / 50条消息）

### 作用域隔离
- **Per-agent**: 每个 agent 独立的 SQLite 索引和 workspace
- **Session scope**: QMD 搜索可配置 `scope` 规则（deny all + allow direct chats 为默认）
  - 支持按 `chatType`（direct/group/channel）过滤
  - 支持按 session key prefix 匹配
- **MEMORY.md 隔离**: 只在 private session 加载，防止 group 聊天泄露个人记忆
- **Citations 模式**: `auto` 在 direct chat 显示来源，group/channel 中隐藏

## 5. Abstraction层面

### 反思/提炼
- **无自动反思/提炼**: 系统不主动总结或重组记忆内容
- **Pre-compaction flush 是最接近的机制**: 在 compaction 前提醒 agent "存储持久记忆"，但内容选择完全由 agent 决定
- **用户策展**: MEMORY.md 的质量完全依赖 agent 和用户手动维护
- **Context pruning**: 仅影响当前请求的 in-memory context（不修改磁盘历史），是一种微压缩优化

### Working Memory <-> Long-term Memory
- **Working Memory**: 当前 session 的对话历史（session JSONL），加上 compaction 后的压缩上下文
- **Long-term Memory**: `MEMORY.md` + `memory/*.md` 文件
- **转化路径**: Working Memory -> (pre-compaction flush) -> Daily Log -> (用户/agent 手动策展) -> MEMORY.md
- **回溯路径**: Long-term Memory -> (memory_search 语义搜索) -> 注入到 Working Memory 的 tool result

**独特之处**:
- "文件即记忆"哲学：Markdown 文件是唯一真相源，索引只是加速检索
- 混合搜索（BM25 + vector）解决了纯语义搜索在精确标识符查找上的弱点
- Pre-compaction flush 是一个巧妙的 bridge：在 context 被压缩前，给 agent 一次机会把重要信息持久化
- 高度可配置：从 embedding provider 到搜索算法到衰减参数，几乎所有行为都可调节
