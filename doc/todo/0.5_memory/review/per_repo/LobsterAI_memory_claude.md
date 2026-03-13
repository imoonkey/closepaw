# LobsterAI Memory System Analysis

## 1. Product层面

### Memory分类
LobsterAI 的记忆系统专注于 **用户个人记忆**（User Memories），只有一种类型：

- **用户记忆条目**（User Memory Items）— 关于用户的持久化事实片段
  - 个人档案：姓名、职业、住所等
  - 偏好：喜好、习惯
  - 助手偏好：回复风格、语言、格式等
  - 所有权信息：家人、宠物等

不支持每日日志、任务记忆、知识库等类型。

### 每类结构
每条用户记忆：
```typescript
{
  id: string,           // UUID
  text: string,         // 记忆文本内容
  matchKey: string,     // 归一化匹配键（用于去重）
  createdAt: number,    // 创建时间戳
  lastUsedAt: number,   // 最近使用时间戳
  sources: [{           // 来源记录
    sessionId, messageId, role, isActive, createdAt
  }]
}
```

存储在 SQLite 的 `cowork_user_memories` + `cowork_user_memory_sources` 表中。

## 2. System层面

### 架构
```
CoworkStore (SQLite)
├── Memory Extraction Pipeline
│   ├── coworkMemoryExtractor.ts — 从对话中提取候选记忆
│   └── coworkMemoryJudge.ts — 验证候选记忆质量
├── Memory Storage
│   ├── cowork_user_memories 表
│   └── cowork_user_memory_sources 表
└── Memory Injection
    └── 系统提示中注入 user memories 列表
```

### 存储/索引
- **SQLite**（sql.js）：KV 配置表 + 记忆表 + 来源关联表
- **无向量索引**：纯文本匹配
- **无全文搜索**：依赖归一化 matchKey 进行近似重复检测

### 写入方法
**提取流程（extractTurnMemoryChanges）:**

1. **显式提取**：正则匹配用户的"记住/remember"、"忘记/forget"指令
   - `记住：我叫小明` → add
   - `忘掉：我不喜欢Java` → delete
2. **隐式提取**：从用户消息中检测个人信息信号
   - 个人档案信号（我叫/我是/I am）→ confidence 0.93
   - 所有权信号（我有/我养了/I have）→ confidence 0.9
   - 偏好信号（我喜欢/I prefer）→ confidence 0.88
   - 助手偏好信号（请用中文回复）→ confidence 0.86
3. **过滤**：排除问句、闲聊、代码块、临时信息、过程性指令

**判断流程（judgeMemoryCandidate）:**
- 规则评分（scoreMemoryText）：基础 0.5 + 各种信号加减分
- Guard Level 阈值：strict(0.8)/standard(0.72)/relaxed(0.62)（隐式）; strict(0.7)/standard(0.6)/relaxed(0.52)（显式）
- **LLM 二次判断**：仅在边界情况下（score 与 threshold 差距 <= 0.08）调用 LLM
  - LLM 结果缓存：256条，10分钟 TTL
  - 超时 5秒，最少置信度 0.55

### 检索方法
- 无语义搜索
- 直接从数据库读取全部活跃记忆，注入系统提示
- `getActiveUserMemoryItems()` — 按 lastUsedAt DESC 排序

### 写入时机
- **每轮对话结束后**：`applyTurnMemoryUpdates()` 自动提取并存储
- 仅在 `memoryEnabled` 且有 user+assistant 文本时触发
- 隐式提取可通过 `memoryImplicitUpdateEnabled` 开关控制

## 3. Lifecycle层面

### 淘汰/上限
- **硬上限**：`memoryUserMemoriesMaxItems`（默认12，范围1-60）
- 超出上限时淘汰策略：按 `lastUsedAt ASC` 删除最久未使用的
- 无过期时间，只有 LRU 式淘汰

### 去重/合并
**近似重复检测（Near-Duplicate）:**
- 对新候选计算 `matchKey`（归一化、小写、去标点）
- 与现有记忆的 matchKey 计算 **bigram Jaccard 相似度**
- 阈值 >= 0.82 视为重复
- 重复时**更新**现有记忆的文本和来源（而非新增）
- 同时排除过程性文本（命令、脚本）和助手风格文本（使用XX技能）

### 时间衰减
- 无显式衰减
- 但 `lastUsedAt` 追踪使用时间，LRU 淘汰间接实现"用进废退"

## 4. Injection层面

### Token预算
- 无显式 token 预算
- 通过 `memoryUserMemoriesMaxItems` 间接控制（默认12条 × 每条几十字 ≈ 几百 token）
- 所有活跃记忆全部注入系统提示

### 分级加载
- 无分级，所有记忆一次性全量注入
- 按 lastUsedAt 排序（最近使用的优先）

### 作用域隔离
- 记忆是**全局的**，跨所有 session 共享
- 与 workingDirectory（项目目录）无关
- 无 user/project/session 级别的隔离

## 5. Abstraction层面

### 反思/提炼
- **无自动反思/提炼**
- 记忆直接从原始对话文本中提取，不做摘要
- 更新时用新文本覆盖旧文本（而非合并）

### Working Memory ↔ Long-term Memory
- **Working Memory** = 当前 session 的对话历史（cowork_messages 表）
- **Long-term Memory** = user_memories 表中的记忆条目
- 转换路径：
  - 每轮对话 → 提取器分析 → 判断器过滤 → 存入 user_memories
  - 单向流动，无反向加载到工作记忆
- **特点**：极简设计，只关注"用户是谁"这一类信息，不处理任务/知识
