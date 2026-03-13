# Hermes Agent Memory System Analysis

## 1. Product 层面

### Memory 分类

三层记忆体系：

| 类型 | 存储 | 容量 | 用途 |
|------|------|------|------|
| **MEMORY.md** | 文件 (`~/.hermes/memories/`) | 2,200 chars (~800 tokens) | Agent 个人笔记：环境、工具、经验教训 |
| **USER.md** | 文件 (`~/.hermes/memories/`) | 1,375 chars (~500 tokens) | 用户画像：偏好、沟通风格、角色 |
| **Session Search** | SQLite FTS5 (`~/.hermes/state.db`) | 无限 | 全历史对话搜索，按需召回 |

可选第四层：**Honcho Integration** — 第三方 AI-native 用户建模服务，提供跨会话 user representation。

### 每类结构

**MEMORY.md / USER.md**：纯文本条目，用 `\n§\n` 分隔，支持多行。每条是一个独立事实/观察。

**Session DB**：SQLite 表 `sessions`（元数据）+ `messages`（完整对话记录）+ FTS5 虚表（全文索引）。支持 parent_session_id 链（压缩后会话分裂）。

**Honcho**：peer-based 会话模型，通过 `context()` API 返回 user representation + peer card，语义搜索驱动。

## 2. System 层面

### 架构

```
System Prompt (frozen snapshot) ← MEMORY.md + USER.md + Honcho prefetch
      ↓
   Agent Loop
      ↓
Tool calls → memory tool (add/replace/remove) → 写入磁盘
             session_search tool → FTS5 查询 → Gemini Flash 摘要
             query_user_context → Honcho dialectic chat
```

核心设计原则：**Frozen Snapshot Pattern** — 系统提示中的记忆快照在会话开始时冻结，整个会话期间不变。这保护了 LLM 的 prefix cache，避免每次写入记忆后重新计算系统提示。

### 存储/索引

- **MEMORY.md / USER.md**：原子写入（temp file + `os.replace`），避免并发读取到半写文件
- **Session DB**：SQLite WAL 模式，FTS5 虚表做全文搜索
- **Honcho**：远程 API，语义向量索引（服务端管理）

### 写入方法

**Memory Tool**（`memory` tool）：
- `add`: 追加新条目，检查字符上限
- `replace`: 子串匹配定位 → 替换整条
- `remove`: 子串匹配定位 → 删除整条
- 写入前做安全扫描（prompt injection / exfiltration 模式检测 + 不可见 Unicode 检查）
- 拒绝精确重复

**Session Store**：自动存储，每条消息 append 到 SQLite + 遗留 JSONL

### 检索方法

- **MEMORY.md / USER.md**：不需要检索，直接注入系统提示
- **Session Search**：FTS5 全文搜索 → 按 session 分组取 top N → 加载对话 → 截断到 ~100k chars（以匹配词为中心） → Gemini Flash 生成摘要
- **Honcho**：`context()` 单次 API 调用返回 user representation + peer card，支持基于当前消息的语义搜索

### 写入时机

- **Memory**：Agent 主动调用 memory tool（proactive saving），schema 描述中明确要求：用户分享偏好/纠正错误/完成复杂任务后主动保存
- **Session**：每条消息自动追加
- **Honcho**：每次对话结束后批量同步新消息

## 3. Lifecycle 层面

### 淘汰/上限

- **硬上限**：MEMORY.md 2,200 chars，USER.md 1,375 chars
- **满时策略**：add 操作返回错误 + 当前条目列表，提示 agent consolidate/replace/remove
- **Session**：无上限，永久保存
- **Guidance**：当容量 >80%（系统提示头部可见）时，提示 agent 在添加前先合并条目

### 去重/合并

- **Load 时去重**：`load_from_disk()` 调用 `dict.fromkeys()` 去除精确重复（保留首次出现）
- **Add 时去重**：精确匹配检查，已存在则跳过
- **合并**：由 agent 自行决定，通过 replace 将多条合并为一条。Schema 描述中鼓励 consolidation

### 时间衰减

无自动时间衰减机制。Session 有 reset policy（idle timeout / daily reset），但这控制的是对话上下文轮换，不是记忆淘汰。Memory 条目无时间戳。

## 4. Injection 层面

### Token 预算

- **固定预算**：~1,300 tokens（MEMORY ~800 + USER ~500），不随对话长度变化
- **Session Search**：按需消耗，每次搜索摘要最多 10,000 tokens
- **Honcho**：可配置 `context_tokens` 参数

### 分级加载

两级加载策略：
1. **Always-on（L1）**：MEMORY.md + USER.md，每次会话自动注入系统提示
2. **On-demand（L2）**：session_search + Honcho query，仅当 agent 判断需要时通过 tool call 触发

### 作用域隔离

- **Memory**：全局共享，所有平台/频道共用同一份 MEMORY.md 和 USER.md
- **Session**：按 session_key 隔离（`agent:main:{platform}:{type}:{chat_id}`）
- **Honcho**：按 peer_id 隔离（channel + chat_id），支持跨会话用户建模

## 5. Abstraction 层面

### 反思/提炼

- **无自动反思**：没有定期回顾或自动摘要机制
- **Agent 驱动的 consolidation**：当空间不足时，schema 提示 agent 合并条目。这是一种被动的、由容量压力驱动的提炼
- **Trajectory Compressor**：独立工具，用于 RL 训练数据压缩，不直接影响运行时记忆。策略：保护首尾 turns，中间段用 LLM 生成摘要替换
- **Session Search 摘要**：检索时用 Gemini Flash 对历史对话做 focused summarization，算是一种「检索时提炼」

### Working Memory vs Long-term Memory

| 维度 | Working Memory | Long-term Memory |
|------|---------------|-----------------|
| 实现 | 当前会话上下文（messages array） | MEMORY.md + USER.md + Session DB |
| 容量 | 受 context window 限制 | MEMORY/USER 有字符上限，Session 无限 |
| 持久性 | 会话结束即失效（除非写入 memory tool） | 跨会话持久 |
| 更新 | 每 turn 自动追加 | 手动（memory tool）或自动（session append） |
| 访问延迟 | 即时（in-context） | MEMORY/USER 即时；Session Search 需要 FTS + LLM |

**独特之处**：
- Frozen snapshot 模式是非常务实的工程决策，在记忆新鲜度和 prefix cache 性能之间取了后者
- 安全扫描层（injection/exfil 检测）是其他系统少见的，体现了多平台（Telegram/WhatsApp/Discord）场景下的安全考量
- Session Search 的「检索时摘要」模式让无限历史变得可访问，同时不污染主模型上下文
