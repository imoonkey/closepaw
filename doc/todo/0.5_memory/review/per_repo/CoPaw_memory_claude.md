# CoPaw Memory System Analysis

## 1. Product层面

### Memory分类
CoPaw 的记忆系统基于 ReMeLight（ReMe 框架），分两大类：

**上下文记忆（Context Management）:**
- 对话历史的压缩摘要（compressed summary）
- 目的是管理 context window 不溢出

**长期记忆（Long-term Memory）:**
- `MEMORY.md` — 持久事实、偏好、决策
- `memory/YYYY-MM-DD.md` — 每日日志，运行时上下文

### 每类结构
- **MEMORY.md**：agent 通过 file tools 直接操作的 Markdown 文件，结构自由
- **Daily logs**：按日期命名的 Markdown 文件，追加式写入
- **Compressed summary**：纯文本摘要，存储在内存对象中（ReMeInMemoryMemory）
- 底层索引：向量嵌入 + BM25 全文索引的混合数据库

## 2. System层面

### 架构
```
MemoryManager(ReMeLight)
├── Context Management (压缩摘要)
│   ├── compact_memory() — 对话压缩
│   └── compressed_summary — 内存中的摘要
├── Long-term Memory Management
│   ├── File Tools (read/write/edit) — agent 直接操作 .md 文件
│   ├── File Watcher (watchfile) — 异步监控文件变更
│   ├── Async Index Update — 更新向量索引和全文索引
│   └── Hybrid Search (memory_search tool)
```

### 存储/索引
- **文件存储**：plain Markdown 文件是 source of truth
- **向量数据库**：支持 chroma / local / sqlite 三种后端（auto 模式按平台选择）
- **BM25 全文搜索**：可选启用
- **嵌入服务**：支持 dashscope 等外部 API，可配置维度（默认1024）、缓存（默认2000条）

### 写入方法
**核心设计：Agent 通过 file tools 自己写文件**（而非专用 memory API）
- `write_file` / `edit_file` / `read_file` — 标准文件操作工具
- Agent 根据系统提示中的指导决定写入目标和内容
- 文件变更后由 watchfile 异步更新索引

**自动摘要写入**：
- context overflow 时 `summary_memory()` 自动写入 daily log
- 对话压缩时 `compact_memory()` 更新 compressed summary

### 检索方法
**Hybrid Search（memory_search tool）:**
1. 向量语义搜索 × 0.7 权重
2. BM25 全文搜索 × 0.3 权重
3. 候选池扩展：3× multiplier，上限200
4. 融合策略：按 `path + start_line + end_line` 去重，加权求和
5. 支持 `min_score` 过滤和 `max_results` 限制

**直接读取**：Agent 知道具体路径时可直接 `read_file`

### 写入时机
| 信息类型 | 写入目标 | 方式 |
|---------|---------|------|
| 持久事实/偏好 | MEMORY.md | agent 通过 write/edit tool |
| 每日笔记 | memory/YYYY-MM-DD.md | agent 通过 write/edit tool |
| Context overflow 摘要 | memory/YYYY-MM-DD.md | 自动触发 summary_memory |
| 用户说"记住这个" | 立即写文件 | agent 通过 write tool |

## 3. Lifecycle层面

### 淘汰/上限
- **Context 压缩**：通过 `memory_compact_threshold` 控制，超过阈值时压缩旧消息
- `memory_compact_ratio` 控制压缩比
- `memory_compact_reserve` 控制保留的近期消息
- `tool_result_compact_keep_n` 控制保留的工具输出数
- **文件层面**：无自动淘汰，文件永久保留
- 被压缩的消息标记为 `COMPRESSED`，不再参与后续压缩

### 去重/合并
- 无显式的记忆去重机制
- 依赖 agent 的智能判断来避免重复写入
- 向量索引在文件变更时重建，天然去重

### 时间衰减
- 无显式时间衰减
- 向量搜索按语义相关度排序，不考虑时间
- BM25 按词频统计排序

## 4. Injection层面

### Token预算
- `max_input_length` — 全局最大输入长度
- `memory_compact_threshold` — 触发压缩的阈值
- 无细粒度的 per-memory token 预算
- 依赖 compact_ratio 控制压缩后的大小

### 分级加载
- **系统提示**中注入 MEMORY.md 的指导说明
- **自动 recall**：memory_search 返回的片段注入上下文
- **On-demand**：agent 按需调用 read_file 加载具体文件
- Compressed summary 始终在上下文中

### 作用域隔离
- 工作目录（working_dir）级别隔离
- 不同项目/用户使用不同 working_dir
- 无更细粒度的权限控制

## 5. Abstraction层面

### 反思/提炼
- **Context 压缩**（compact_memory）：LLM 生成对话摘要，可迭代压缩（含 previous_summary）
- **Summary Memory**（summary_memory）：更全面的对话总结，写入文件
- 依赖 agent 自身能力做反思（通过 file tools 编辑 MEMORY.md 更新认知）

### Working Memory ↔ Long-term Memory
- **Working Memory** = 当前对话 messages + compressed_summary
- **Long-term Memory** = MEMORY.md + memory/*.md 文件
- 转换路径：
  - 对话 → compressed_summary（自动压缩）
  - 对话 → daily log（自动 summary 或 agent 主动写入）
  - Agent 从对话中提炼 → MEMORY.md（agent 自主决策）
- **特点**：agent 对长期记忆有完全的读写控制权，系统只负责索引和搜索
