# AI Agent Memory 系统综述

> 基于 16 个 Claw 类项目 + 8 个 Memory 专项库的代码分析
> 分析时间：2026-03-11

---

## 一、共性模式

### 1. Product 层面：记忆分类趋同

几乎所有成熟系统都收敛到 **2-3 层记忆** 的基本结构：

| 层级 | 典型命名 | 持久性 | 写入方式 |
|------|----------|--------|----------|
| **Identity/Core** | MEMORY.md / Core Block / persona | 永久 | Agent 或用户主动编辑 |
| **Episodic/Daily** | HISTORY.md / daily log / session transcript | 长期 | 自动 append |
| **Session/Working** | 当前对话上下文 | 会话级 | 自动 |

- **User memory** 和 **Agent memory** 的分离是常见模式（OpenViking、Hermes、LettaBot、MemOS）
- **App/Tool memory**（工具使用经验、技能指南）是少数系统的独特维度（MemOS、OpenViking）
- 大部分系统的 Identity 层是 **一个整体文件**（MEMORY.md），少数用结构化 schema（MemOS 的 key-value、Leon 的 Facts 表）

### 2. System 层面：存储方案光谱

```
纯文件 ←──────────────────────────────────────────→ 全DB
Nanobot    Hermes    OpenClaw    Leon    Letta    MemOS    mem0
(MD+JSONL) (MD+SQLite) (MD+SQLite+Vec) (SQLite+QMD) (PG+Vec) (Neo4j+Qdrant) (25+后端)
```

**写入方法**普遍分三类：
1. **Agent 主动 tool call**（最常见）— Letta, Hermes, OpenClaw, Nanobot
2. **对话后自动提取**（LLM 抽取）— Leon, MemOS, OpenViking
3. **Pre-compaction flush**（context 压缩前触发）— OpenClaw 独有

**检索方法**的复杂度差异极大：
- 最简单：全量注入 + grep（Nanobot, Hermes, PicoClaw）
- 中间：BM25 + 向量混合搜索（OpenClaw, ZeroClaw, IronClaw）
- 最复杂：图遍历 + 向量 + BM25 + LLM rerank + LLM 后过滤（MemOS）

### 3. Lifecycle 层面：淘汰策略普遍薄弱

| 策略 | 采用项目 | 机制 |
|------|----------|------|
| **硬上限 + FIFO** | Hermes (2200 chars), MemOS (WM:20/LTM:1500) | 最旧条目淘汰 |
| **TTL/过期** | Leon (discussion 5d, daily 90d) | 定期维护 |
| **无淘汰** | mem0, memU, Nanobot, Letta archival | 无限增长 |
| **LRU** | LobsterAI (max 12) | 最少使用淘汰 |

**去重/合并**的主流方案：
- **LLM 决策**（mem0 的 ADD/UPDATE/DELETE 模型）— 最智能但最昂贵
- **哈希去重**（Leon 的 SHA256, memU 的 content_hash）— 精确但无语义
- **相似度阈值**（Leon Jaccard≥0.84, MemOS cosine≥0.92）— 折中
- **Agent 自管理**（Hermes 容量压力 → 提示 agent consolidate）— 最简单

**时间衰减**只有少数系统实现：
- OpenClaw: `e^(-λt)`, 半衰期 30 天，可选开启
- MemOS-Claw (OpenClaw 集成): `0.5^(age/14)`, α=0.3 保底
- OpenViking: `sigmoid(log1p(count)) × e^(-decay × age)`, 半衰期 7 天
- memU: salience = similarity × reinforcement × recency_decay (半衰期 30 天)

### 4. Injection 层面：Token 预算管理参差不齐

| 项目 | 预算方式 | 具体数值 |
|------|----------|----------|
| **Leon** | 精确 token 预算 | Planning 220t + Execution 480t |
| **Hermes** | 固定字符上限 | ~1300 tokens (800+500) |
| **OpenClaw** | snippet 级控制 | ~6 条 × 700 chars |
| **我们** | 弹性预算 | ≤6 KB (device 1KB + user 1.5KB + app 余量) |
| **多数系统** | 仅 top_k | 无 token 级控制 |

**分级加载**的典型模式：
- **L0/L1/L2 三层**：OpenViking（abstract→overview→detail）、memU（category summary→item→resource）
- **Always-on + On-demand 双层**：Hermes（MEMORY.md 常驻 + session_search 按需）、Nanobot 同
- **全量 Core + 按需 Archival**：Letta（core block in-context + archival search）
- **无分级**：mem0、大部分轻量 Claw

### 5. Abstraction 层面：反思/提炼能力两极分化

**有反思/提炼的系统：**
- **MemOS**：最复杂 — GraphStructureReorganizer 每 100s KMeans 聚类 + LLM 生成摘要父节点 + 推理节点
- **OpenViking**：Session 结束时 LLM 抽取 8 类 memory + 语义 DAG 自动生成 L0/L1 摘要
- **memU**：Category Summary 增量更新 — 每次写入触发 patch prompt 更新分类摘要
- **Second-Me**：L0→L1→L2 知识蒸馏，最终烤入模型参数
- **Nanobot**：LLM consolidation agent 全量重写 MEMORY.md
- **Leon**：每轮对话后 LLM 自动提取 persistent memory + Facts
- **Supermemory**：知识图谱关系推断 (update/extend/derive)

**无反思的系统：** mem0, Hermes, IronClaw, ZeroClaw, PicoClaw 等

**Working Memory ↔ Long-term Memory 流转模型：**

| 模型 | 代表 | 特点 |
|------|------|------|
| **Agent 自主写入** | Letta, Hermes | Agent 通过 tool call 决定什么值得记住 |
| **LLM 自动抽取** | Leon, MemOS, OpenViking | 对话后 LLM 自动从 session 中提取 |
| **Pre-compaction flush** | OpenClaw | Context 压缩前给 agent 一次机会写入 |
| **LLM consolidation** | Nanobot | 定期用独立 LLM 调用重写整个记忆 |
| **参数化蒸馏** | Second-Me | 知识最终编码进 LoRA 参数 |

---

## 二、各系统独特亮点

### Tier 1：成熟且有独特创新

| 系统 | 核心亮点 |
|------|----------|
| **OpenClaw** | Pre-compaction memory flush（context 压缩前的自动记忆保存触发）；"文件即记忆"哲学；高度可配置的混合搜索 |
| **Leon** | 业界最精细的 recall 管线（6+ 轮多阶段检索）；精确 token 预算（220/480t）；三维记忆 scope（persistent/daily/discussion）+ 独立 Facts 表 |
| **MemOS** | 三体记忆（文本/激活/参数）；Neo4j 图+向量混合；GraphStructureReorganizer 自动聚类和推理；业界最完整的 memory scope 分类 |
| **Letta** | Agent 完全自主控制记忆读写（核心设计哲学）；Core/Archival/Recall 三层分明；EphemeralSummaryAgent + Sleeptime Agent 异步提炼 |
| **OpenViking** | L0/L1/L2 分级加载（核心创新）；文件系统范式（viking:// URI）；hotness score（7 天半衰期）；8 类 memory 自动抽取 |
| **Hermes** | Frozen snapshot pattern（保护 prefix cache）；安全扫描（prompt injection/exfil 检测）；Session Search + Gemini Flash 摘要的"检索时提炼" |

### Tier 2：特定维度的创新

| 系统 | 核心亮点 |
|------|----------|
| **mem0** | LLM-in-the-loop 写入合并（ADD/UPDATE/DELETE）；25+ 向量后端支持；UUID→整数 ID 的防幻觉技巧 |
| **memU** | 三级渐进检索（category summary→item→resource）+ sufficiency check；salience 排序（similarity × reinforcement × recency）；content_hash 强化去重 |
| **Nanobot** | LLM-as-memory-consolidator（独立 LLM 全量重写 MEMORY.md）；事实(可变)/事件(append-only) 双层分离 |
| **Second-Me** | L0→L1→L2 知识蒸馏到 LoRA 参数（参数化记忆，零检索延迟）；人格"侧面"聚类 |
| **Supermemory** | 知识图谱的演化关系（update/extend/derive）；自动遗忘（时间型事实过期）；auto-built user profile（static+dynamic） |
| **LobsterAI** | 规则+LLM 双重记忆提取（regex 先行，LLM judge 兜底）；三级守卫（strict/standard/relaxed）|
| **IronClaw** | RRF 混合搜索（比加权融合更稳健）；文档分块（800 词 15% 重叠）；bootstrap ritual 首次引导 |

### Tier 3：极简但有参考价值

| 系统 | 特点 |
|------|------|
| **PicoClaw** | Go 实现，64 分片 mutex 并发控制；Anthropic cache_control 优化 LLM 侧 KV 缓存 |
| **ZeroClaw** | 6 种后端实现可插拔；snapshot/hydration "灵魂备份" |
| **CoPaw** | Agent 全权控制文件记忆写入 + 文件 watcher 异步索引更新 |

### 不含记忆系统

- **ClawX**：纯 GUI 客户端，无记忆
- **nano-claw**：仅 JSON 滑动窗口，无长期记忆
- **mimiclaw**：嵌入式 C，仅 buffer-size 约束的最近 N 天

---

## 三、关键设计取舍总结

### 1. Agent 自主 vs 系统自动
- **Agent 自主**（Letta, Hermes）：灵活，但依赖 LLM 质量和 prompt 工程
- **系统自动**（Leon, MemOS）：可靠，但可能提取噪音或遗漏重要信息
- **混合**（OpenClaw, 我们的系统）：Agent 主动 + 失败兜底

### 2. 文件存储 vs 数据库
- **文件**（OpenClaw, Hermes, Nanobot）：可读、可调试、git 可追踪，但检索能力弱
- **数据库**（Leon, MemOS, Letta）：检索强大，但不透明、调试困难
- 趋势：**文件为真相源 + 索引为加速层**（OpenClaw 模式）是最佳平衡点

### 3. 精确预算 vs 粗放注入
- 只有 Leon（220/480t）和 Hermes（1300t 固定）做到了精确 token 预算
- 大部分系统仅靠 top_k 控制，实际 token 开销不可预测
- 我们的弹性预算（6KB 分级分配）是少数采用的方案之一

### 4. 被动衰减 vs 主动重组
- **被动**：时间衰减函数降低旧记忆权重（OpenClaw, OpenViking, memU）
- **主动**：定期聚类、LLM 重组（MemOS 每 100s，memU patch summary）
- **无管理**：mem0 等依赖应用层 — 实际等于永不衰减

### 5. 通用记忆 vs 领域特化
- 大部分系统做通用对话记忆
- **MemOS** 的 SkillMemory/ToolTrajectoryMemory 和 **OpenViking** 的 tools/skills 类别是少有的领域特化
- 我们的系统（per-app workflow/pitfall/verification）是**最强的领域特化**实现
