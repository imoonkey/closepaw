status: draft

# 1_memory_system 设计 (Codex)

Date: 2026-03-10  
Goal: 在现有 session history 之外，加一层跨任务、可持续积累的本地记忆系统。V1 先解决两件事：任务结束后把有价值的经验留下来；新任务开始时把相关经验自动带回 prompt。不要引入向量库、SQLite FTS、复杂 reflect pipeline。

## 1. 问题重述

当前代码里已经有三类“短期记忆”：

- `HistoryManager`：当前/历史 turn 的对话上下文
- `ScratchpadState`：当前 session 的结构化临时数据
- `TodoState`：当前 session 的任务计划

它们都只解决 **session 内** 的问题，不解决 **跨任务、跨 session** 的积累。  
同时，代码里已经有一条很好的“静态知识”路径：`app_skills/<package>/SKILL.md`。这适合通用、手工维护、可评审的 app 知识，但不适合运行时写入：

- 用户偏好
- 这台设备的特征
- 某个 app 在这台设备上的真实坑点
- 某次成功/失败后总结出的操作经验

所以这里真正要补的是一层 **runtime-learned durable memory**，而不是替换现有 history 或 app skill。

## 2. 代码库现状约束

从现有实现看，V1 应该贴着这几个 seam 做：

- `AgentSession.handleAgentComplete(...)` 已经是稳定的任务结束 hook，适合触发 retain。
- `TurnPlanningPhaseRunner` 每 turn 都会组 prompt，并且已经按 `currentPackageName` 注入 app skill，适合接 recall。
- `PromptBuilder` 已经有固定输入顺序：`history -> working memory -> app skill -> observation`，适合插入一段新的 recalled memory。
- `SessionStorage` / `SessionRecordingService` 已经证明“内部私有文件 + Kotlin 序列化/IO”是当前项目接受的持久化模式。
- `app_skills` 是 assets，运行时只读；动态记忆不能写到这里。

这意味着 V1 最小方案不是“新搞一套 agent 架构”，而是：

1. 在 session/task 生命周期上挂一个 retain
2. 在 prompt 装配路径上挂一个 recall
3. 用独立的本地 Markdown 文件做 durable storage

## 3. 设计结论

### 3.1 V1 只做 Retain + Recall，不做独立 Reflect

OpenClaw 的三段式里，Reflect 的作用是把事实进一步提炼成更稳定的知识库。  
对这个代码库，V1 没必要一次做全：

- 现有 `app_skills/<package>/SKILL.md` 已经承担了“静态、通用、人工评审”的 reflected knowledge 角色
- 我们当前缺的是“运行时学到的、用户/设备特有的、可自动写入的经验”

所以 V1 定位：

- `app_skills`：静态通用知识
- `memory/*.md`：动态 learned knowledge

Reflect 留到后续再做，届时再考虑“把反复验证过的动态记忆晋升为静态 skill”。

### 3.2 存储模型：本地 Markdown，按 scope 分文件

运行时目录：

```text
<app files>/memory/
├── apps/
│   ├── com.android.settings.md
│   ├── net.gsantner.markor.md
│   └── ...
├── user_prefs.md
└── device.md
```

核心原则：

- 按 **实体 scope** 组织，而不是按日期组织
- app 记忆用 package name，对齐现有 `app_skills/<package>` 习惯
- 不存原始操作流水，只存泛化后的经验
- 每条记忆保留最小来源信息，但不追求 OpenClaw 那种 line-level attribution

### 3.3 记忆类型：少而稳

V1 统一成 5 种 entry kind：

- `workflow`：某 app 里稳定有效的操作路径
- `pitfall`：稳定坑点/失败模式
- `verification`：这个 app 里应该如何确认结果
- `preference`：用户偏好
- `device`：设备特征/环境约束

这已经足够覆盖 brief 里的四类信息，不需要更细 taxonomy。

## 4. 文件格式

Markdown 保持人类可读，但格式受控，方便程序解析和 merge：

```md
# App Memory: com.android.settings

## Workflow
- [high][2026-03-10][success][session:abc task:task-1]
  Open Settings search before typing; direct list scrolling is less reliable for deep options.

## Pitfalls
- [medium][2026-03-10][failure][session:def task:task-2]
  BACK first dismisses the keyboard on search screens, so a second BACK may be needed to navigate.

## Verification
- [high][2026-03-10][success][session:abc task:task-1]
  Verify the toggle row text after change; do not trust highlight color alone.
```

为什么不用更复杂的 frontmatter / JSON-in-Markdown：

- 这里的读写主体是 Kotlin 本地代码，不是通用 Markdown tooling
- 受控 bullet 格式足够 parse，心智负担更小
- 用户肉眼打开文件时也容易理解

## 5. 组件设计

### 5.1 `memory/` 新模块

新增一组轻量组件：

- `MemoryEntry`
- `MemoryScope`
- `MemoryKind`
- `DurableMemoryRepository`
- `FileDurableMemoryRepository`
- `MarkdownMemoryCodec`
- `MemoryRecallService`
- `MemoryRetainService`
- `TaskMemoryCapture`

### 5.2 职责划分

`TaskMemoryCapture`

- task 级临时收集器
- 在任务执行期间记录：
  - `goal`
  - `taskId`
  - 任务中见过的 package 集合
  - 完成结果（success/failure + final answer）

`MemoryRetainService`

- 任务结束后，基于 task slice 做一次“是否值得记住”的抽取
- 输出结构化 `MemoryEntry`
- 调用 repository merge 到 Markdown 文件

`MemoryRecallService`

- 根据当前 task 和 app scope 选出相关 entry
- 做 prompt 级裁剪，生成一段短文本

`FileDurableMemoryRepository`

- 负责文件读写、解析、merge、去重、cap
- 不做 LLM 决策

这个拆分保持单一职责，也复用现有风格：prompt 只负责拼装，repository 只负责持久化，生命周期层只负责时机。

## 6. Retain 流程

### 6.1 触发时机

在 `AgentSession.handleAgentComplete(...)` 触发 retain，但 **不阻塞** 任务完成到 Idle 的主流程。

原因：

- retain 是增强项，不是 correctness path
- 如果把 LLM 抽取放在 completion critical path，会直接拖慢 UX
- 现有 Hot Idle 设计强调“任务完成后快速回到 idle 等 follow-up”

所以 retain 采用：

- `GoalAchieved` / `Error` / `MaxTurnsReached`：后台 best-effort retain
- `UserRequested`：默认跳过，不把人工打断/半成品直接写进 durable memory

### 6.2 retain 输入

不把整段 session history 扔给 retain，而是只取“当前 task slice”：

- 当前 task 的 `goal`
- `completionReason`
- `final answer`
- 当前 task 期间访问过的 package 列表
- `scratchpad` 最终快照
- `HistoryManager` 中从最后一条 `USER_INTENT` 到结尾的历史片段

这样有两个好处：

- 不污染前一个 task 的经验
- token 小很多，避免为了记忆系统再做一套历史压缩

### 6.3 retain 决策方式

brief 明确要求“由 LLM 判断什么值得记住，不做规则抽取”。  
但文件格式、merge 和 scope 校验仍由代码控制。

所以 retain 采用：

1. LLM 输出严格 JSON schema
2. 代码做校验和 merge

JSON 结构示意：

```json
{
  "entries": [
    {
      "scope": {"type": "app", "packageName": "com.android.settings"},
      "kind": "pitfall",
      "confidence": "medium",
      "summary": "BACK first dismisses the keyboard on search screens.",
      "appliesWhen": "searching in Settings",
      "source": {"sessionId": "abc", "taskId": "task-1", "outcome": "failure"}
    }
  ]
}
```

代码校验规则：

- app scope 只能落到本 task 访问过的 package 上
- `summary` 必须是泛化经验，不能只是一次性流水
- 长度受限，避免写进长篇自然语言
- `kind` / `confidence` / `scope` 必须在白名单内

### 6.4 merge 策略

V1 不做 embedding，也不做 fuzzy clustering，只做简单稳定的 merge：

- 同一 `scope + kind + normalized summary` 视为同条记忆
- 重复出现时更新：
  - `last_verified`
  - `source`
  - `confidence` 取较高值
- 每个文件保留最近且最可信的一小批条目

建议 cap：

- 每个 app 文件最多 40 条
- `user_prefs.md` 最多 20 条
- `device.md` 最多 20 条

超出后按 “低置信度 + 最久未验证” 优先淘汰。

## 7. Recall 流程

### 7.1 recall 候选 scope

每次 planning 前，按下面顺序收集 recall scope：

1. `user_prefs.md`
2. `device.md`
3. 当前前台 app 对应的 `apps/<package>.md`
4. 若是 task 的前几 turn，且 goal 明显指向某个 app，则额外加入该 app 的记忆

第 4 点不需要新发明 resolver，直接复用 `open_app` 现有的 app 名匹配思路：

- 已安装 app label
- alias map
- package name 直传

这样 first turn 还没切进目标 app 时，也能拿到目标 app 经验。

### 7.2 task mode 粗分类

brief 提到 recall 应该看“任务类型”。  
V1 不做专门 classifier，只做二分：

- `ACTION`：创建、编辑、删除、导航、发送、切换
- `QUERY`：读取、查找、确认、统计、判断

这个分类只影响排序，不影响存储。

排序优先级：

- `ACTION`：`workflow > pitfall > verification`
- `QUERY`：`verification > workflow > pitfall`

`preference` 和 `device` 永远可参与，但总量受全局 cap。

### 7.3 prompt 注入位置

在 `PromptBuilder.buildInputItems(...)` 新增一段：

```text
## Recalled Memory

### User Preferences
- ...

### Device
- ...

### App Experience: com.android.settings
- ...
```

放置顺序：

`history -> working memory -> recalled memory -> app skill -> observation`

原因：

- working memory 仍然是当前 session 的最高优先级
- recalled memory 是本次 task 的动态历史经验
- app skill 仍然是更静态、更通用的规则块
- observation 始终放最后，保证最新屏幕证据压轴

### 7.4 recall 预算

为了不把 durable memory 变成新的 context 膨胀源，V1 做硬裁剪：

- 总 entry 数最多 8 条
- 总字符数最多约 1,200
- 同一 app 最多 4 条

Recall 是摘要式提示，不是全文回放。

## 8. 运行时交互

### 8.1 Task 生命周期

```text
TaskStarted
  -> reset TaskMemoryCapture
  -> record goal

Each turn
  -> capture currentPackageName
  -> add to visited package set
  -> recall relevant memory for prompt

TaskCompleted
  -> slice current task history
  -> launch async retain
  -> session immediately enters Idle
```

### 8.2 与现有系统的关系

- 不替换 `HistoryManager`
  - history 继续负责当前 session 的对话上下文和压缩
- 不替换 `ScratchpadState`
  - scratchpad 继续负责 task 内显式工作记忆
- 不替换 `app_skills`
  - app skill 继续负责静态、通用、人工维护的知识

新 memory layer 只补“跨任务 learned context”这一个缺口。

## 9. 为什么这个方案最适合当前代码库

### 9.1 不写入 `app_skills`

拒绝把动态记忆直接写进 `app_skills/<package>/SKILL.md`：

- assets 是只读的
- app skills 是 repo 内通用知识，不该混入用户/设备特有经验
- 自动写 skill 会破坏 prompt ownership 边界

### 9.2 不引入 SQLite / FTS / 向量库

拒绝在 V1 上 SQLite/FTS：

- 当前 recall 范围极小：全局 2 个文件 + 最多 1-2 个 app 文件
- 文件数和数据量都远不到需要检索引擎的级别
- 会显著提高实现、调试、迁移复杂度

Markdown 顺序扫描完全够用。

### 9.3 不让 LLM 直接重写整份 Markdown

拒绝“让 LLM 输出整份文件内容再覆盖”：

- diff 不稳定
- 容易覆盖已有高质量条目
- 失败时难以做约束和校验

所以用 “LLM 抽结构化 entry，代码 merge” 更稳。

### 9.4 不把 retain 放进 completion blocking path

拒绝同步 retain：

- completion 路径应该保持轻
- memory 写失败不该影响任务完成语义
- Hot Idle 的速度不能被记忆系统拖慢

best-effort async 更符合当前 session 设计。

## 10. 实施范围建议

### Phase 1

必须实现：

- `memory/` 文件存储与 codec
- app/user/device 三类 scope
- task completion retain
- planning-time recall
- prompt 注入
- 去重与 cap

### Phase 2

可以后续再做：

- 多次验证后提升 confidence
- 记忆“晋升”为人工可评审 skill 的 workflow
- 更精细的 task type 分类
- 更强的去重/相似合并

### 明确非目标

- 不做 embeddings
- 不做跨文件全文检索
- 不做自动修改 assets 下的 `app_skills`
- 不做独立 reflect bank/entities 系统

## 11. 成功标准

V1 做完后，应该满足：

1. 某 app 上一次任务总结出的经验，能在下一次相关任务 prompt 中自动出现。
2. 用户偏好和设备特征能跨 session 留存。
3. memory 文件可直接打开阅读，内容不是原始日志，而是泛化经验。
4. 即使 retain 失败，主任务完成与 session idle 流程也不受影响。
5. prompt token 增量可控，不引入新的 context 膨胀问题。

## 12. 一句话总结

这个 memory system 的最小正确形态不是“再造一个知识库产品”，而是：  
**在现有 app skill 与 session history 之间，补一层 runtime-learned、Markdown-backed、best-effort 的 durable memory。**
