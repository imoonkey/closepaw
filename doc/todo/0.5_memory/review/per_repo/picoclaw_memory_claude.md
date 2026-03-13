# PicoClaw Memory System Analysis

## 1. Product层面

### Memory分类
**两层分离的 memory 系统**:

1. **Conversation Memory** (Session 层): 对话历史 + 摘要
   - 存储在 `pkg/memory/` (JSONL Store)
   - 管理的是 message-level 的对话记录

2. **Agent Memory** (MemoryStore 层): 长期记忆 + 每日笔记
   - 存储在 `pkg/agent/memory.go` (Markdown 文件)
   - **MEMORY.md** -- 长期核心记忆
   - **YYYYMM/YYYYMMDD.md** -- 每日笔记（按月分目录）

### 每类结构
**Conversation Memory**: `providers.Message` 结构 (role, content, tool_calls 等), 以 JSONL 格式逐行存储。

**Agent Memory**: 纯 Markdown 文本文件，无结构化 schema。MEMORY.md 是一个自由格式文件，由 agent 自行组织内容。

## 2. System层面

### 架构
```
Agent Request
    ├── ContextBuilder.BuildMessages()
    │   ├── 静态部分 (缓存): identity + bootstrap + skills + MEMORY.md + 最近3天日记
    │   ├── 动态部分: 时间 + session info
    │   └── Summary (如果存在)
    ├── Session History (从 JSONL Store 加载)
    └── Current User Message
```

**极简设计**: 没有向量数据库、没有 embedding、没有混合检索。纯文件系统 + append-only JSONL。

### 存储/索引
**JSONL Store** (`pkg/memory/jsonl.go`):
- 每个 session = 2 个文件: `{key}.jsonl` (消息) + `{key}.meta.json` (元数据)
- **Append-only**: 消息永远不物理删除，只通过 meta.Skip 跳过
- **Sharded mutex**: 64 个分片锁, FNV hash 映射 session key -> 锁分片
- **fsync 保证**: 每次写入后 Sync, 原子写入 meta (temp+fsync+rename)

**MemoryStore** (`pkg/agent/memory.go`):
- `memory/MEMORY.md` -- 原子写入 (WriteFileAtomic)
- `memory/YYYYMM/YYYYMMDD.md` -- 原子写入, 按月分目录

**无索引**: 没有 FTS, 没有 embedding, 没有搜索能力。Memory 是全量注入系统提示。

### 写入方法
**Conversation Memory**:
- `AddMessage(sessionKey, role, content)` -- 追加文本消息
- `AddFullMessage(sessionKey, msg)` -- 追加完整消息（含 tool calls）
- `SetHistory(sessionKey, messages)` -- 原子替换整个历史
- `SetSummary(sessionKey, summary)` -- 写入摘要

**Agent Memory**:
- `WriteLongTerm(content)` -- 覆盖写入 MEMORY.md（原子写入，0o600权限）
- `AppendToday(content)` -- 追加到今日日记, 首次创建带日期头

### 检索方法
**无主动检索能力**: MEMORY.md 和最近3天日记在每次对话开始时全量注入系统提示。没有按需搜索。

**Session History**: `GetHistory(sessionKey)` 读取 JSONL 文件, 跳过 meta.Skip 行。
**Summary**: `GetSummary(sessionKey)` 从 meta.json 读取。

### 写入时机
- **Agent 自主写入**: LLM 通过 `write_file` 工具写 MEMORY.md（系统提示中指引"if something seems memorable, update MEMORY.md"）
- **Conversation 自动保存**: 每轮对话后 `AddMessage` + `Save`
- **摘要自动生成**: 当 history 超过阈值时触发（见 Lifecycle 部分）

## 3. Lifecycle层面

### 淘汰/上限
**Conversation Memory 摘要+截断**:
- **触发条件**: `len(history) > SummarizeMessageThreshold` 或 `estimateTokens(history) > ContextWindow * SummarizeTokenPercent / 100`
- **Token 估算**: `totalChars * 2 / 5` (2.5 chars/token, 考虑 CJK)
- **流程**:
  1. 保留最后4条消息
  2. 对之前的消息用 LLM 生成摘要（大批量会分两半分别摘要再合并）
  3. `TruncateHistory(sessionKey, keepLast=4)` -- 逻辑截断 (meta.Skip 增加, 不物理删除)
  4. `SetSummary(sessionKey, finalSummary)` -- 保存摘要
- **摘要注入**: 下次对话时在系统提示中以 `CONTEXT_SUMMARY:` 前缀注入，并标注"approximate references only"

**Compact 机制**: `Compact(sessionKey)` 可选调用，物理重写 JSONL 文件删除已跳过的行。

**Agent Memory**: 无自动淘汰。MEMORY.md 完全靠 agent/用户手动维护。

### 去重/合并
**无自动去重/合并机制**。MEMORY.md 是纯文本, 完全依赖 agent 的判断力来避免重复写入。

摘要系统有一定的信息合并效果: 多轮对话 -> 单段摘要。

### 时间衰减
**无时间衰减**。摘要后截断是硬淘汰, 不是渐进衰减。每日日记无自动清理。

## 4. Injection层面

### Token预算
- **ContextWindow**: 可配置 (per-agent), 用于摘要触发阈值计算
- **MaxTokens**: 控制 LLM 响应长度
- **SummarizeTokenPercent**: 历史占上下文窗口的百分比阈值
- **无 memory 注入的 token 限制**: MEMORY.md 和日记全量注入, 没有截断/限制

### 分级加载
**三级注入**:
1. **系统提示 (静态缓存)**: identity + bootstrap files + skills summary + MEMORY.md + 最近3天日记
2. **摘要**: `CONTEXT_SUMMARY:` 前缀注入 (被标记为"approximate")
3. **对话历史**: 截断后的最近消息

**系统提示缓存** (`BuildSystemPromptWithCache`): 基于 mtime 的文件变更检测, 避免每次请求重建。静态部分使用 Anthropic `cache_control: ephemeral` 启用 KV 缓存复用。

### 作用域隔离
- **Agent Instance 隔离**: 每个 agent 有独立的 workspace, session manager, memory store
- **Session Key 隔离**: 不同 channel/chat 的对话历史独立
- **Group Chat**: 无特殊处理 (对比 IronClaw)

## 5. Abstraction层面

### 反思/提炼
**LLM 驱动的摘要** (唯一的自动提炼机制):
- 对话历史过长时, LLM 生成摘要
- 大批量分割: >50 条消息分两批分别摘要, 再 LLM 合并
- 摘要 prompt: "Provide a concise summary...preserving core context and key points"
- 包含 oversized message 的注解: "[Note: Some oversized messages were omitted...]"
- **异步执行**: `go func()` 后台运行, `sync.Map` 防止同一 session 重复摘要

**无 MEMORY.md 的自动反思**: 没有定期让 LLM 审查/合并/提炼 MEMORY.md 内容的机制。

### Working Memory <-> Long-term Memory
- **Working Memory**: 对话历史 (JSONL) + 摘要 -- 会话级别, 有自动管理
- **Long-term Memory**: MEMORY.md + 每日日记 -- 跨会话持久, 手动管理
- **注入方向**: long-term -> system prompt (全量), working -> message history (截断+摘要)
- **写入方向**: agent 通过 tool 调用写 MEMORY.md; 对话自动保存到 JSONL
- **摘要是桥梁**: 对话历史截断后, 精华以 summary 形式保留, 但不自动写入 MEMORY.md

**独特设计**:
1. **极简**: 没有向量数据库, 没有 embedding, 纯文件系统。适合嵌入式/IoT 场景。
2. **Append-only JSONL**: 写入永远不修改已有数据, crash-safe。
3. **Sharded locking**: 64 分片锁, O(1) 内存开销, 适合长期运行的 daemon。
4. **Provider-aware 缓存**: 静态系统提示用 Anthropic cache_control 标记, 利用 LLM 侧 KV 缓存。
