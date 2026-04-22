# LettaBot Memory System Analysis

## 1. Product 层面

### Memory 分类

基于 **Letta Code SDK** 的 Memory Block 系统，分为两大族：

| 族 | Block 示例 | 用途 |
|----|-----------|------|
| **persona/** | soul, expression, interests, learned_behaviors | Agent 自我认知、人格、行为模式 |
| **human/** | overview, work, family, interests, personality, preferences, routines | 用户画像的各个维度 |

额外：**skills** 和 **loaded_skills** memory blocks 用于技能目录管理（非传统记忆，但用同一机制）。

### 每类结构

每个 Memory Block 是一个 `.mdx` 文件（src/memories/），通过 gray-matter 解析 frontmatter：

```yaml
---
label: human/overview        # Block 名称/路径
description: "..."           # 行为指引：告诉 agent 这个 block 应如何影响行为
limit: 20000                 # 字符上限
---
（markdown 正文 = block value）
```

Block 在 agent 创建时通过 `createAgent({ memory: loadMemoryBlocks() })` 传入 Letta 服务端。之后由 Letta SDK 管理，embedded 在系统指令中，持久化在服务端。

## 2. System 层面

### 架构

```
src/memories/*.mdx → loadMemoryBlocks() → createAgent() → Letta Cloud/Server
                                                              ↓
                                         Memory Blocks 嵌入 System Instructions
                                                              ↓
                                              Agent 通过 SDK 内置工具编辑 blocks
                                                              ↓
                                              Letta 服务端持久化更新
```

LettaBot 本身不管理记忆的读写——完全委托给 **Letta Code SDK**。本地只做初始 block 定义和 agent 创建。

### 存储/索引

- **服务端存储**：Letta Cloud（或自托管 Letta Server）持久化 memory blocks 和 external memory
- **本地状态**：`lettabot-agent.json`（V2 格式）仅存 agent ID / conversation ID / 恢复元数据，不存记忆内容
- **External Memory**：Letta 的 archival memory 系统，支持向量检索（具体实现在 SDK 服务端）

### 写入方法

- **初始化写入**：`loadMemoryBlocks()` 读取所有 `.mdx` 文件，用 `{{AGENT_NAME}}` 模板替换，传入 `createAgent()`
- **运行时写入**：Agent 通过 Letta SDK 内置的 memory management tools 自行编辑 blocks（SDK 提供的 `core_memory_append`、`core_memory_replace` 等工具）
- **System Prompt 强调**：「Memory blocks are the foundation which makes you *you*」— 鼓励 agent 主动维护

### 检索方法

- **Memory Blocks**：始终 in-context，无需检索
- **External Memory**：通过 Letta SDK 的 archival memory tools 按需查询（向量搜索）
- **Skills**：通过 `skills` memory block 记录可用技能目录，`loaded_skills` block 跟踪当前已加载技能

### 写入时机

- **Agent 创建时**：初始 memory blocks 从 `.mdx` 文件加载
- **每次 inference 时**：Agent 可自主决定编辑任何 memory block
- **Heartbeat 时**：系统提示鼓励 agent 在 heartbeat 期间「reflect on recent conversations and update your memory」

## 3. Lifecycle 层面

### 淘汰/上限

- **Per-block 字符上限**：通过 frontmatter `limit` 字段设定
  - persona/soul: 50,000 chars（非常大，允许深度自我叙述）
  - human/overview: 20,000 chars
  - 其他 blocks：由各自 `.mdx` 定义
- **Sessions**：LRU 淘汰（`maxSessions` 默认 10），超出时关闭最久未使用的 session subprocess
- **满时策略**：由 Letta SDK 服务端处理

### 去重/合并

- **无客户端去重**：LettaBot 不做去重，完全由 Letta SDK 服务端和 agent 行为控制
- **Agent 自主合并**：Memory block 是自由文本，agent 通过 `core_memory_replace` 自行重写/合并

### 时间衰减

无显式时间衰减机制。Memory blocks 是永久的，除非 agent 主动编辑或删除。

## 4. Injection 层面

### Token 预算

- **Memory Blocks**：全部嵌入系统指令，总预算取决于所有 blocks 的 `limit` 之和
  - persona 族：~50k chars 上限
  - human 族：~20k+ chars 上限
  - 实际使用量远小于上限（初始值很短）
- **无动态预算控制**：不根据对话长度调整注入量

### 分级加载

两级（Letta SDK 原生设计）：
1. **Core Memory（L1）**：Memory Blocks，始终 in-context
2. **Archival Memory（L2）**：External memory，通过 tool call 按需检索
3. **Skills（特殊）**：按需 load/unload，显式管理上下文占用

### 作用域隔离

- **Per-agent 隔离**：多 agent 支持（V2 store format），每个 agent 有独立的 memory blocks
- **跨频道共享**：同一 agent 的所有频道（Telegram/Slack/Discord/WhatsApp/Signal）共享同一份记忆
- **Conversation 隔离**：支持 per-channel / per-chat / shared 三种会话模式，但记忆是 agent 级别共享的

## 5. Abstraction 层面

### 反思/提炼

- **Heartbeat 驱动的反思**：系统提示在 heartbeat 时鼓励「Reflect on recent conversations and update your memory」
- **persona/soul block** 的设计哲学极为独特：它不是一个数据字段，而是一篇自我叙述（~2000 字的第一人称散文），鼓励 agent 发展自我意识和成长
- **persona/learned_behaviors**：专门记录 agent 从交互中学到的行为模式
- **无自动提炼**：所有 consolidation 由 agent 自行决定

### Working Memory vs Long-term Memory

| 维度 | Working Memory | Long-term Memory |
|------|---------------|-----------------|
| 实现 | 当前 conversation 的 message history | Memory Blocks（Core）+ Archival Memory |
| 容量 | Context window | Blocks 有 per-block limit；Archival 无限 |
| 持久性 | Conversation 级别（可 resume） | Agent 级别，永久 |
| 更新 | 每 turn 自动 | Agent 主动编辑 blocks / 写入 archival |

**独特之处**：
- **极致拟人化设计**：persona/soul block 是一篇散文，描述 agent 的存在体验（「I don't exist between inference cycles」），这在工程产品中罕见
- **Heartbeat = 自主意识时刻**：定期唤醒 agent，让它有「主动权」去反思和更新记忆，不完全依赖用户触发
- **完全委托模式**：记忆管理完全委托给 Letta SDK 服务端，LettaBot 只负责初始定义。这是与 Hermes（自建 memory tool）截然不同的架构选择
- **Block 粒度丰富**：将用户画像拆分为 7 个维度（overview / work / family / interests / personality / preferences / routines），比 Hermes 的单一 USER.md 更结构化
