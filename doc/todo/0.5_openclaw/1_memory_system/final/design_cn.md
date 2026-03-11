status: draft

# Memory System V1

日期：2026-03-10

## 目标

增加一个小型长期记忆层，使其能跨任务、跨 session 保留。

V1 只需要两个能力：

1. 任务结束后保存可复用经验。
2. 在下一次任务中，把相关经验加载进 prompt。

这是增量设计。它不替代 session history、scratchpad、todos 或 app skills。

## 现有边界

当前代码库已经有几个很干净的接缝：

- `TurnPlanningPhaseRunner` 已经能根据当前 package 构建 per-turn 的 app context。
- `PromptBuilder` 已经会把非 history 的上下文以 user messages 注入。
- `SessionStorage` 体现了现有接受的持久化模型：`files/` 下的 app-private files。
- `app_skills/<package>/SKILL.md` 是静态、repo 所有的知识，必须与运行时 memory 保持分离。
- agent 已经通过 `ToolArbitrationResult.selectedToolCalls` 支持每个 turn 的多工具调用。

因此 V1 应该只是新增一层运行时能力：

- 静态知识：`app_skills`
- session 范围的 working memory：history + scratchpad + todos
- 跨任务持久 memory：新的 `files/memory/`

## 存储模型

把 markdown 文件存到：

```text
<app files>/memory/
├── apps/
│   ├── com.android.settings.md
│   └── net.gsantner.markor.md
├── user_prefs.md
└── device.md
```

作用域按实体，不按日期：

- app memory 以 package name 为粒度
- 用户偏好放到一个文件
- 设备事实放到一个文件

## 条目格式

用纯 markdown bullet，加时间戳。不引入 kind taxonomy。

例子：

```md
# App Memory: com.android.settings

- [2026-03-10] Use Settings search before browsing deep lists; direct scrolling is unreliable for deep options
- [2026-03-10] BACK dismisses the keyboard first on search screens; need a second BACK to navigate
- [2026-03-10] Verify the row text/state after a toggle change; do not trust highlight color alone
```

**为什么不加 `kind` 字段：**
- 当每个文件 ≤30 条时，LLM 在 recall 时会直接读完整个文件；基于 kind 的过滤 / 排序只会增加代码，不改变 LLM 实际看到的内容
- kind 分类要求 LLM（或 retain 逻辑）先做分类，额外引入一个可能出错的决策点
- 时间戳是唯一真正必要的元数据：它表达了“是否过时”，这是 LLM 无法从正文直接推断的
- 如果 V1 数据表明确实需要按 kind 过滤，以后可以在不改存储格式的前提下加（例如在正文前加 `[kind]`）

V1 不要加入 confidence、evidence graph、embeddings 或富元数据。

## Recall

### 加载什么

每个 planning turn 加载：

1. `user_prefs.md`（如果非空）
2. `device.md`（如果非空）
3. 当前前台 package 对应的 app memory（如果已知）

### 已知限制：turn 1 的空档

Turn 1 往往发生在 launcher / home / 上一个 app，因此目标 app 的 memory 通常要到 turn 2+ 才会被加载。对 V1 来说这是可接受的：

- Turn 1 通常只是 `open_app`，app-specific memory 影响不大
- 到 turn 2 时 agent 已进入目标 app，recall 就能生效
- 如果这个空档代价很大，Phase 2 可以复用 `open_app` 的匹配逻辑做 goal-based app resolution

### 不加载什么

不要做：

- 跨文件搜索
- embeddings
- SQLite / FTS
- 大范围跨 app recall

V1 的 recall 是一个很小、边界清晰的查找，不是检索系统。

### Prompt 放置位置

把 recalled memory 作为独立的 user-context block 注入：

`history -> working memory -> recalled memory -> app skill -> observation`

不要把它作为新的 system message。当前 `PromptBuilder` 已经把这些上下文段落建模成 user messages，V1 应该复用这个路径，而不是改请求结构。

### Budget

每个文件截断到最近 2KB。memory 段总量 ≤ 6KB。

这比在 recall 层按条数限制更简单。store 层已经会限制条目数（见 Retain），所以 recall 只需要读取并截断。

## Retain

### 写入路径：`remember_experience` 工具

主要写入路径是任务中的一个**工具调用**，不是任务结束后再做一次后台 LLM 调用。

```text
Tool: remember_experience
Parameters:
  category: "app" | "user_pref" | "device"
  content:  string (1-2 sentences, generalizable)
  package_name: string (required when category = "app")
```

系统提示词应增加说明：“在调用 `complete_task` 之前，如果你学到了可复用的经验，请调用 `remember_experience` 保存下来。”

**为什么选工具，而不是后台 LLM 调用：**

| 维度 | Tool | Background LLM call |
|---|---|---|
| 复杂度 | 只需注册一个工具 | 需要新的 LLM 调用链路 + JSON schema + 错误处理 |
| 成本 | 零额外 LLM 调用 | 每个任务约多 1K tokens |
| 与代码库契合度 | 与其他工具同一路径 | 成为 `PromptBuilder → Turn → ToolRouter` 之外第一个例外 |
| 可靠性 | 取决于 LLM 是否遵循提示 | 自动 |

选择工具方案，是因为它避免创建**第二条 LLM 调用路径**。当前所有 LLM 调用都走 `PromptBuilder → Turn.runStreaming() → ToolRouter`。如果加后台 retain 调用，就得额外处理自己的 prompt 构造、模型选择、streaming 和错误处理。

### 关键运行时约束

这个选择只有在“任务完成通常会经过 `complete_task`”这个前提下才完全成立。

当前运行时在没有 tool calls 返回时，仍然可以通过**纯 assistant 文本且无工具调用**完成任务（`Turn.kt` 在这种情况下会把 `isComplete = true`）。这意味着纯工具 retain 路径存在真实缺口：

- 文本型完成时没有 `remember_experience` 的机会
- 终态的非工具退出不会被自动捕获

因此 V1 必须明确选择其一：

1. **收紧完成契约**，要求成功完成必须经由 `complete_task`。
2. **接受这个缺口**，V1 里只依赖 prompt 提示。
3. **后续补一个 retain fallback hook**，如果这个缺口代价过高。

当前建议：先保持 tool-first 方案，保持简单，但把这点文档化为主要已知限制。如果团队更重视正确性而不是最小实现，那么更干净的修复是强制 `complete_task`，而不是假装这个缺口不存在。

**边界情况覆盖：**

- **正常完成：** LLM 在 `complete_task` 前或同 turn 一起调用 `remember_experience`。系统已支持多工具同 turn（`ToolArbitrationResult.selectedToolCalls` 是列表）。
- **MaxTurnsReached：** `ExecutorStepPolicy` 会给 agent 一个 “FINAL TURN” 警告，此时应利用这个最后 turn 保存经验。
- **终态错误：** 网络失败、不可恢复崩溃等，不太会产生有价值的 app-specific learning，这个缺口可接受。
- **UserRequested（手动停止）：** 对 memory 的价值较低，因为任务中断于中途。

如果 V1 数据表明 LLM 对这个工具利用率太低，可以在 Phase 2 增加后台 retain hook 作为主路径，工具退为可选覆盖。

### retain 时的 store 行为

- `MemoryStore` 以 `- [YYYY-MM-DD] {content}` 格式追加写入
- 文件不存在时自动创建文件与 header
- 写入时做 entry cap：
  - 每个 app 文件：最多 30 条
  - `user_prefs.md`：最多 20 条
  - `device.md`：最多 10 条
- 超限时删除最旧条目（顶部）
- V1 不做 dedup（在 ≤30 条规模下，LLM 能看到已有内容并主动避免重复）

### 工具策略

自动允许，不需要用户审批。写一个 markdown bullet 风险很低，而且可逆。

## 组件

三个组件：

- **`MemoryStore`**：负责 `files/memory/` 的文件读写 / 追加，以及 entry cap
- **`MemoryRecaller`**：根据当前 package 选出相关文件，并格式化 prompt block
- **`RememberExperienceTool`**：工具定义与调用层，内部委托给 `MemoryStore`

不要再加额外类型（不需要 `MemoryEntry` data class，不需要 `MemoryKind` enum，不需要 `MemoryScope` sealed class）。条目就是字符串，文件就是字符串，保持扁平。

### Wiring

- 在 `SessionServices.create()` 中实例化 `MemoryStore` 和 `MemoryRecaller`：
  ```kotlin
  val memoryDir = File(context.filesDir, "memory")
  val memoryStore = MemoryStore(memoryDir)
  val memoryRecaller = MemoryRecaller(memoryStore)
  ```
- 在 `SessionToolingBootstrapper` 里注册 `RememberExperienceTool(memoryStore)`
- `PromptBuilder.buildInputItems()` 增加 `recalledMemory: String?` 参数，并把它作为 user message 注入到 working memory 和 app skill 之间
- `TurnPlanningPhaseRunner` 调用 `memoryRecaller.recall(currentPackageName)` 并把结果传给 builder

### System Prompt 补充

```text
## Long-Term Memory

You have persistent memory on this device. Relevant memories are loaded
automatically based on the current app.

Before calling complete_task, if you learned something reusable, call
remember_experience to save it:
- App quirks (button locations, navigation patterns, gotchas)
- User preferences (payment methods, notification habits)
- Device characteristics (screen density, OS version quirks)

Only store generalizable knowledge, not task-specific steps.
Keep entries to 1-2 sentences.
```

### 文件改动

| 文件 | 变更 |
|---|---|
| `app/.../memory/MemoryStore.kt` | **新增** — 约 80 行 |
| `app/.../memory/MemoryRecaller.kt` | **新增** — 约 50 行 |
| `app/.../tool/impl/RememberExperienceTool.kt` | **新增** — 约 80 行 |
| `app/.../agent/cognition/prompt/PromptBuilder.kt` | **修改** — 增加 `recalledMemory` 参数 |
| `app/.../agent/TurnPlanningPhaseRunner.kt` | **修改** — 调用 recaller |
| `app/.../session/SessionServices.kt` | **修改** — 注入 store + recaller |
| `app/.../session/SessionToolingBootstrapper.kt` | **修改** — 注册工具 |
| system prompt | **修改** — 增加 memory 说明 |

大约 250 行新增代码，3 个新文件，4-5 处小改动。

## 明确的非目标

V1 不应包含：

- embeddings / vector search
- SQLite / FTS
- reflect / synthesis bank
- 自动把内容写进 `app_skills`
- LLM 全文件重写
- 条目 kind taxonomy（workflow / pitfall / verification）
- confidence level 或 evidence metadata
- 后台 LLM retain 调用（如果工具方案不足，Phase 2 再看）
- turn 1 的目标 app recall（如果缺口代价大，Phase 2 再做）
- dedup / merge 逻辑（V1 规模下，LLM + 人工清理已足够）

## 给主用户的开放问题

V1 是否也应该收紧任务完成语义，要求必须通过 `complete_task` 完成（删掉 `Turn.kt` 里的纯文本完成 fallback）？

- **如果是：** 基于工具的 retention 在所有正常完成路径上都会可靠。这本身也有独立价值，因为结构化 completion data 比从纯文本中推断完成状态更好。推荐。
- **如果否：** 工具 retain 依旧是有意为之的 best-effort 路径；纯文本完成缺口作为 V1 限制接受。

## 成功标准

1. 某个 app 的使用经验能在下一次同 app 前台出现时进入 prompt
2. 用户偏好和设备特征能跨 session 保留
3. Memory 文件是纯 markdown，人类可读、可手改
4. Memory 操作不能阻塞或拖慢任务完成 / session 生命周期
5. Memory 带来的总 prompt 增量 ≤ 6KB
