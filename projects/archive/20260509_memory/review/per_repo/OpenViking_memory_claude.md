# OpenViking Memory System Analysis

## 1. Product层面

### Memory分类
OpenViking 将所有上下文统一到文件系统范式下，分为三大类:
- **Resources**: 项目文档、代码仓库、网页等外部知识
- **User Memory**: 用户偏好、习惯、实体、事件等个人信息
- **Agent Memory**: agent 积累的案例、模式、工具使用经验、技能执行记忆

Memory 细分为 8 个类别 (`MemoryCategory`):
- User 侧: profile / preferences / entities / events
- Agent 侧: cases / patterns / tools / skills

### 每类结构
统一的 `Context` 对象:
- `uri`: 唯一路径标识 (`viking://user/xxx/memories/preferences/...`)
- `abstract`: 一句话摘要 (L0)
- `level`: L0/L1/L2 三级详细度
- `context_type`: skill / memory / resource
- `category`: 细分类别
- `active_count`: 访问频次
- `updated_at`: 最后更新时间
- `vector`: embedding 向量
- `meta`: 扩展元数据 (包含 overview)

## 2. System层面

### 架构
- **VikingFS**: 虚拟文件系统层，提供 `viking://` URI 统一寻址
- **VikingDBManager**: 向量数据库管理层，支持多种 backend (本地/HTTP/VikingDB/Volcengine)
- **QueueFS**: 异步处理队列 (embedding queue + semantic processing DAG)
- **Session 管理**: compressor → extractor → deduplicator 流水线

### 存储/索引
- 文件系统: 本地文件存储 (`local_fs.py`) + LevelDB (第三方依赖)
- 向量索引: 多适配器 (local/HTTP/VikingDB/Volcengine)，支持 dense embedding
- 元数据: 目录结构本身就是索引

### 写入方法
- **资源写入**: `ov add-resource <url/path>`，自动解析 (支持 markdown/html/epub/excel/PDF/代码等)
- **Memory 写入**: SessionCompressor 在会话结束时自动提取
  - `MemoryExtractor`: LLM 从对话中抽取 6 类 memory，每条包含 L0/L1/L2 三级内容
  - 写入后自动入队 embedding 和语义处理

### 检索方法
- **目录递归检索 (HierarchicalRetriever)**:
  1. IntentAnalyzer 生成多个检索条件
  2. 向量检索定位高分目录
  3. 目录内二次检索
  4. 子目录递归钻取
  5. 结果聚合
- 支持 `ov find`(语义搜索) / `ov grep`(文本搜索) / `ov ls`(目录浏览) / `ov tree`(树形展示)

### 写入时机
- 资源: 用户主动添加
- Memory: 会话结束时由 `SessionCompressor.compress()` 自动触发
- 语义处理 (L0/L1 生成): 写入后异步通过 semantic DAG 自动生成

## 3. Lifecycle层面

### 淘汰/上限
- 无显式硬性上限
- **冷热管理 (memory_lifecycle.py)**: `hotness_score` 函数计算 0.0-1.0 热度分

### 去重/合并
- **MemoryDeduplicator**: LLM 辅助去重
  1. 向量预过滤: 找到同类别下相似 memory (cosine similarity)
  2. LLM 决策: SKIP(跳过重复) / CREATE(创建新条目) / NONE(仅处理已有条目)
  3. 对已有条目: MERGE(合并候选到已有) / DELETE(删除冲突旧条目)
- Profile 类别: 始终合并 (ALWAYS_MERGE)
- Preferences/Entities/Patterns: 支持 MERGE 决策
- Events/Cases: 仅 skip 或 create

### 时间衰减
- `hotness_score = sigmoid(log1p(active_count)) * exp(-decay * age_days)`
- 默认半衰期 7 天
- 热度分可与语义相似度混合，用于提升检索排序中频繁访问/近期更新条目的权重

## 4. Injection层面

### Token预算
- 文档中未见显式 token budget 管理
- 通过 L0/L1/L2 分层本身控制粒度

### 分级加载
**L0/L1/L2 三层架构** — 这是 OpenViking 的核心创新:
- **L0 (Abstract)**: ~100 tokens，一句话摘要，用于快速判断相关性
- **L1 (Overview)**: ~2k tokens，核心信息和使用场景，供 agent 规划阶段决策
- **L2 (Detail)**: 完整原始内容，仅在深度阅读时按需加载
- 每个目录节点都有对应的 `.abstract` 和 `.overview` 文件

### 作用域隔离
- **URI 命名空间**: `viking://user/` vs `viking://agent/` vs `viking://resources/`
- **Space 隔离**: `UserIdentifier` 的 `user_space_name()` / `agent_space_name()` 决定存储空间
- **Account 隔离**: `account_id` 字段
- Memory 类别自动路由到对应 space:
  - User 类别 (profile/preferences/entities/events) → `viking://user/{space}/memories/`
  - Agent 类别 (cases/patterns/tools/skills) → `viking://agent/{space}/memories/`

## 5. Abstraction层面

### 反思/提炼
- Session 结束时的 memory 提取本身就是一种反思:
  - LLM 分析对话内容 → 抽取有价值信息 → 分类存储
- 语义处理管道 (semantic DAG) 自动为新内容生成父目录的 L0/L1 摘要
- 合并操作 (`_merge_memory_bundle`) 也涉及内容提炼

### Working Memory <-> Long-term Memory
- **Working Memory** = 当前 session 的对话上下文
- **Long-term Memory** = VikingFS 中的所有持久化 context (memories + resources + skills)
- 转换机制: SessionCompressor 在会话结束时将 working memory 中值得保留的信息提取并持久化
- 独特设计: 文件系统范式使得 memory 的组织和检索完全可视化、可调试
