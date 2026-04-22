# nextclaw Memory System Analysis

## 1. Product层面

### Memory分类
nextclaw 的记忆系统采用 **Markdown 文件 + 本地搜索** 的轻量方案：

**长期记忆（MEMORY.md）:**
- 用户信息、偏好、决策、笔记
- 模板中预设了 `User Information`、`Preferences`、`Decisions and Notes` 三个分区

**每日记忆（memory/YYYY-MM-DD.md）:**
- 当日笔记和运行时上下文

**工作区记忆（workspace root MEMORY.md）:**
- 工作区级别的共享记忆

### 每类结构
纯 Markdown 文件，无结构化 schema：
- `{workspace}/MEMORY.md` — 长期记忆
- `{workspace}/memory/MEMORY.md` — 长期记忆（备选路径）
- `{workspace}/memory/YYYY-MM-DD.md` — 每日记忆

## 2. System层面

### 架构
```
MemoryStore (memory.ts)
├── readLongTerm() / writeLongTerm() — MEMORY.md 读写
├── readToday() / appendToday() — 当日文件追加
├── getRecentMemories(days) — 最近 N 天聚合
├── getMemoryContext() — 组合全部记忆上下文
└── listMemoryFiles() — 列出所有记忆文件

MemorySearchTool / MemoryGetTool (tools/memory.ts)
├── memory_search — 文本搜索工具
└── memory_get — 片段读取工具
```

### 存储/索引
- **纯文件存储**：Markdown 文件是唯一存储
- **无向量索引**：无嵌入、无向量数据库
- **无数据库**：不使用 SQLite 或其他 DB
- 搜索完全依赖运行时文本扫描

### 写入方法
- Agent 通过 file tools（或 MemoryStore API）直接写文件
- `writeLongTerm(content)` — 覆盖写入 MEMORY.md
- `appendToday(content)` — 追加写入当日文件（自动创建带日期标题）

### 检索方法
**memory_search tool:**
- 纯 `toLowerCase().includes(query)` 子串匹配
- 遍历所有 memory 文件的每一行
- 支持 `contextLines` 参数返回上下文行
- 所有命中的 score 均为 1（无排名）
- 返回 path + line number + snippet

**memory_get tool:**
- 按 path + from + lines 精确读取
- 安全限制：只能读取 MEMORY.md 或 memory/*.md

**MemoryStore.getMemoryContext():**
- 直接拼接 workspace memory + long-term memory + today's notes
- 无搜索，全量注入

### 写入时机
- Agent 自主决定何时写入（系统提示中的指导）
- 无自动提取或自动摘要
- Today 文件在首次写入时自动创建标题

## 3. Lifecycle层面

### 淘汰/上限
- **无自动淘汰**
- **无大小限制**
- 文件永久保留
- 依赖 agent 自行管理（编辑、删除）

### 去重/合并
- **无去重机制**
- 依赖 agent 智能判断避免重复

### 时间衰减
- **无时间衰减**
- `getRecentMemories(days)` 默认只读最近7天，但这是读取范围限制而非衰减

## 4. Injection层面

### Token预算
- **无 token 预算管理**
- `getMemoryContext()` 全量注入所有记忆
- 文件过大时会直接消耗大量 context window

### 分级加载
三层拼接注入：
1. `## Workspace Memory` — 工作区 MEMORY.md
2. `## Long-term Memory` — memory/MEMORY.md
3. `## Today's Notes` — 当日文件

无按需加载，无裁剪。

### 作用域隔离
- **工作区级别隔离**：每个 workspace 独立的 memory 目录
- memory_search 和 memory_get 有路径安全检查（isWithin）
- 无用户级别隔离

## 5. Abstraction层面

### 反思/提炼
- **无自动反思/提炼**
- 无摘要生成
- Agent 可通过编辑 MEMORY.md 手动整理

### Working Memory ↔ Long-term Memory
- **Working Memory** = 当前对话上下文（无特殊管理）
- **Long-term Memory** = MEMORY.md + memory/*.md 文件
- 无自动转换机制
- Agent 手动决定何时将对话信息写入文件
- **特点**：极简设计，"记忆就是文件"，agent 全权管理内容
