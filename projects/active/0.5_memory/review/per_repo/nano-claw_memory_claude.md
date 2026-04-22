# nano-claw Memory System Analysis

## 1. Product层面

### Memory分类
单层记忆：纯会话历史

| 类型 | 文件 | 用途 |
|------|------|------|
| Session Memory | `~/.nano-claw/memory/<sessionId>.json` | 对话消息历史 |

没有 MEMORY.md、没有 HISTORY.md、没有长期事实存储。

### 每类结构
- **Session JSON**: `Message[]` 数组，每条消息包含 `role`, `content`, 可选 `tool_calls`, `tool_call_id`, `name`
- 按 sessionId 分文件存储

## 2. System层面

### 架构
极简实现：
- `Memory` 类: 消息列表 + JSON 文件持久化
- `ContextBuilder` 类: 构建 system prompt + 管理上下文截断
- `AgentLoop` 集成两者
- 无 memory skill、无外部索引、无合并机制

### 存储/索引
- **纯 JSON 文件**: `~/.nano-claw/memory/<sessionId>.json`
- **无索引**: 无向量、无全文检索
- **同步读写**: `readFileSync`/`writeFileSync`
- **每次 addMessage 后立即写盘**

### 写入方法
- 每次 agent loop 迭代自动写入:
  - 用户消息 → `addMessage(user)`
  - LLM 回复 → `addMessage(assistant)`
  - Tool call → `addMessage(assistant with tool_calls)`
  - Tool result → `addMessage(tool)`
- 所有写入都触发 `save()` → 全量序列化 JSON 写入磁盘

### 检索方法
- **`getMessages()`**: 返回全部消息（用于构建 LLM context）
- **`getRecentMessages(count)`**: 返回最近 N 条
- **无语义搜索、无关键词搜索**

### 写入时机
- **每条消息即时写入**: `addMessage()` → `save()`
- 无 batch、无 debounce、无异步

## 3. Lifecycle层面

### 淘汰/上限
- **消息数量上限**: `maxMessages`（默认100条）
- **淘汰策略**: 超出 maxMessages 时：
  1. 保留所有 `system` role 消息
  2. 对非 system 消息取最近 `maxMessages` 条
  3. 合并: `[...systemMessages, ...recentOtherMessages]`
- **Context 截断**: `ContextBuilder.truncateContext()` 按字符数截断
  - 预算: `maxTokens * 4 (chars/token) * 4 (context-to-response ratio)`
  - 始终保留 system message
  - 从最旧的非 system 消息开始丢弃

### 去重/合并
- **无去重**: 消息原样保存
- **无合并**: 无 consolidation、无摘要生成
- **仅有裁剪**: 超出上限时简单丢弃旧消息

### 时间衰减
- **无时间衰减**: 所有消息等权

## 4. Injection层面

### Token预算
- **粗略估算**: `maxTokens * 4 * 4` 字符数上限（约 65K 字符 for maxTokens=4096）
- **无精确 token 计数**: 用 char length 粗略近似
- **无 system prompt token 预算**: skills + tools 信息全量注入

### 分级加载
无分级机制：
- System prompt: 全量注入（base prompt + skills + tools + current time）
- 历史消息: 全量传入，超限时从尾部截断
- 无按需加载、无条件注入

### 作用域隔离
- **Per-session**: 每个 sessionId 独立 JSON 文件
- **无跨 session 记忆共享**: 各 session 完全隔离
- **无 long-term memory**: 不存在跨 session 的持久知识

## 5. Abstraction层面

### 反思/提炼
- **无反思机制**: 无 consolidation、无摘要、无知识提取
- **纯滑动窗口**: 旧消息被丢弃，信息不可恢复

### Working Memory <-> Long-term Memory
- **只有 Working Memory**: session 消息列表就是全部记忆
- **无 Long-term Memory**: 没有 MEMORY.md 或等价物
- **Session 间无记忆传递**: 每个 session 从零开始
- **消息生命周期**: 写入 → 保持 → 超出 maxMessages 时丢弃 → 永久丢失

**独特之处**:
- **极简设计**: 整个 Memory 类仅 116 行 TypeScript，是三个仓库中最简单的
- **这是 nanobot 的 TypeScript 移植**: nano-claw 定位为 nanobot 的轻量 TS 重写，但移植时丢弃了 nanobot 的双层记忆系统（MEMORY.md + HISTORY.md）和 LLM-driven consolidation
- **无任何智能**: 纯 FIFO 滑动窗口 + JSON 持久化，没有索引、搜索、合并、衰减等任何高级特性
- **同步 I/O**: `readFileSync`/`writeFileSync` 在 Node.js 中会阻塞事件循环，生产环境的潜在问题
- **字符级截断**: 用 char length 而非 token count 估算 context 大小，精度较低但实现简单

---

## 三仓库对比摘要

| 维度 | OpenClaw | Nanobot | nano-claw |
|------|----------|---------|-----------|
| 记忆层数 | 3层（MEMORY.md + daily log + session） | 3层（MEMORY.md + HISTORY.md + session） | 1层（session only） |
| 存储 | Markdown + SQLite 向量索引 | Markdown + JSONL | JSON |
| 检索 | 混合搜索（BM25 + vector + MMR） | grep 关键词 | 无 |
| 合并 | Pre-compaction flush（agent写） | LLM-driven consolidation | 无 |
| 时间衰减 | 可配置指数衰减 | 无 | 无 |
| Token 管理 | 精确 token 计数 + 预算体系 | 消息数量窗口 | 字符数估算 |
| 复杂度 | 高（插件化、多后端、高度可配置） | 中（简单但完整） | 低（最小可用） |
