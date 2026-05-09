# Supermemory Memory System Analysis

## 1. Product层面

### Memory分类
Supermemory 是一个"memory + RAG + user profiles"一体化的 SaaS 服务:
- **Memories**: 从对话/内容中自动提取的事实性知识单元
- **Documents**: 用户上传的原始文档 (PDF/图片/视频/代码/网页)
- **User Profiles**: 从 memory graph 自动构建的用户画像
  - Static Profile: 长期稳定事实 ("Sarah 是 TechCorp 高级工程师")
  - Dynamic Profile: 近期上下文 ("Sarah 正在迁移支付服务")

Memory 自动分类为:
| 类型 | 示例 | 行为 |
|------|------|------|
| Facts | "Alex 在 Stripe 做 PM" | 持续有效直到被更新 |
| Preferences | "Alex 喜欢晨会" | 重复提及时强化 |
| Episodes | "周二和 Alex 喝咖啡" | 衰减，除非有重要意义 |

### 每类结构
- Memory: content(文本事实) + embedding(向量) + isLatest(是否最新) + relationships(图关系) + containerTag(作用域) + 时间戳
- Document: 原始内容 + 多种 extractor 处理后的 chunks + metadata
- User Profile: static facts 列表 + dynamic context 列表

## 2. System层面

### 架构
- **Turbo 单体仓库**: Next.js Web + Hono API + MCP server
- **存储**: Cloudflare 生态 (Hyperdrive/PostgreSQL + KV + AI Workers)
- **Embedding**: Cloudflare AI
- **搜索**: 向量相似度 + 混合搜索
- 开源但核心 API 后端是托管服务

### 存储/索引
- PostgreSQL (via Drizzle ORM): 文档元数据、用户、组织
- Cloudflare AI: embedding 生成
- 向量索引: Cloudflare Vectorize (推测)
- 知识图谱: 内存中的 fact-to-fact 关系图

### 写入方法
- `client.add({ content, containerTag })`: 添加文本/URL/HTML
- `client.documents.uploadFile()`: 上传文件
- Connectors 自动同步: Google Drive / Gmail / Notion / OneDrive / GitHub (cron 每 4 小时 + webhook)
- Memory 提取: `IngestContentWorkflow` 自动处理
  - 内容类型检测 → 提取 → AI 摘要 + 自动标签 → embedding → chunking → space 关系管理

### 检索方法
三种搜索模式:
- **hybrid** (默认): RAG + Memory 合并查询 — 返回文档 chunks + 个性化 memory
- **memories**: 仅搜索提取的 memory facts
- **documents**: 仅搜索文档 chunks (带 metadata filter)

`client.profile()`: 一次调用返回 user profile + 可选搜索结果 (~50ms)

### 写入时机
- 用户主动添加内容时
- Connectors 定时同步时
- 对话过程中 AI 通过 MCP 工具自动保存 (memory tool)

## 3. Lifecycle层面

### 淘汰/上限
- **自动遗忘**: Supermemory 的核心特性
  - 时间型遗忘: "我明天有考试" → 日期过后自动遗忘
  - 矛盾解决: 新事实通过 Update 关系自动替代旧事实
  - 噪音过滤: 无意义的闲聊不会成为永久记忆
- Content hashing 防止重复处理

### 去重/合并
- **知识图谱关系** 取代传统去重:
  - **Update**: 新信息与旧信息矛盾 → 标记 `isLatest`，旧 memory 保留但搜索时降权
  - **Extend**: 新信息补充旧信息 → 两者都保留，提供更丰富上下文
  - **Derive**: 从多个 memory 推断新知识 → 自动创建衍生 memory
- 这不是简单的去重，而是基于语义关系的知识演化

### 时间衰减
- Episodes 类型: 自然衰减 (除非有重大意义)
- 时间型事实: 过期后自动遗忘
- Preferences: 重复提及时反而增强 (正向强化)
- 具体实现细节在闭源 API 后端

## 4. Injection层面

### Token预算
- User Profile 设计本身就是为了节省 token: 一次调用 ~50ms 获取压缩的用户画像
- 相比反复搜索 3-5 次节省大量 token

### 分级加载
- **Level 1 — Profile**: static + dynamic 快照，直接注入 system prompt (~固定低 token)
- **Level 2 — Search**: 按查询按需检索相关 memories/documents
- **Level 3 — Full context**: Connectors + 完整文档内容 (按需)

MCP 工具也体现了分级:
- `context`: 注入完整 profile (system prompt 级)
- `recall`: 搜索特定 memory (查询级)
- `memory`: 保存/遗忘信息 (写入级)

### 作用域隔离
- **containerTag**: 核心隔离机制 — 类似"项目/空间"标签
  - `containerTag: "user_123"` → 用户级隔离
  - 可按工作/个人/客户/仓库灵活组织
- **Organization**: Better Auth + RBAC 组织级隔离
- **API Key**: 外部访问的认证隔离

## 5. Abstraction层面

### 反思/提炼
- **Memory Graph 关系推断**: 从已有 facts 自动 derive 新知识 (如推断公司方向)
- **User Profile 构建**: 从所有 memories 自动提炼 static (长期) + dynamic (近期) 画像
- **AI-powered 摘要**: IngestContentWorkflow 中的自动摘要和标签生成

### Working Memory <-> Long-term Memory
- **Working Memory**: 当前对话上下文 + 注入的 User Profile
- **Long-term Memory**: Memory Graph (fact 网络) + Document Store (RAG 知识库)
- 转换机制:
  - 对话 → memory tool → 自动提取 facts → 入图
  - Documents → IngestContentWorkflow → chunks + embeddings → 可检索
  - Memory Graph → User Profile 自动更新 → 下次对话自动注入
- **独特价值**: 将 memory 视为"事实的演化图"而非"文本的存储"。关系型知识管理 (update/extend/derive) 使系统能理解信息的时间性和关联性，这是与简单向量存储的本质区别
