status: draft

# Memory System V1 — Android Agent

日期：2026-03-10

---

## 目标

让 agent 能跨任务、跨 session 积累可复用经验。

V1 只做两件事：

1. **Retain** — 任务中学到可复用经验时，保存到设备本地文件。
2. **Recall** — 下一次任务开始时，根据当前前台 app 把相关经验加载进 prompt。

这是增量设计。不替代 session history、scratchpad、todos 或 app skills。

---

## 核心设计原则

1. **零额外 LLM 调用** — 写入靠工具调用，不靠后台 LLM。省电、省钱、省延迟。
2. **实体导向** — 文件按 app package name 组织，不按时间。核心实体是 app，不是日期。
3. **Markdown 存储** — 人类可读、可手改、可 adb pull、零依赖。
4. **有界增长** — 每个文件有 entry cap，recall 有 token budget。永远不会无限膨胀。
5. **与 working memory 正交** — scratchpad/todos 是"我这次任务在做什么"，memory 是"我以前学到了什么"。

---

## 三层知识体系（现有 + 新增）

```
静态知识      app_skills/<package>/SKILL.md   — repo 所有，发版时更新
session 工作  history + scratchpad + todos      — 每次 session 结束即清
跨任务持久    files/memory/                     — 新增，设备上长期保留
```

Memory 是中间层：比 app skills 更动态（运行时写入），比 session 工作区更持久（跨 session 存活）。

app_skills 是"教科书"，memory 是"使用心得"。两者不互相覆盖。

---

## 存储模型

### 目录结构

```text
<context.filesDir>/memory/
├── apps/
│   ├── com.android.settings.md
│   ├── net.gsantner.markor.md
│   └── org.tasks.md
├── user_prefs.md
└── device.md
```

三类文件：

| 文件 | 作用域 | Entry Cap |
|------|--------|-----------|
| `apps/<package>.md` | 单个 app 的操作经验 | 30 |
| `user_prefs.md` | 用户偏好（跨 app） | 20 |
| `device.md` | 设备特征 | 10 |

### 条目格式

纯 markdown bullet + 日期时间戳。不引入 kind taxonomy。

```md
# App Memory: com.android.settings

- [2026-03-10] 用 Settings 搜索比逐层浏览更可靠；深层选项靠滚动很容易漏掉
- [2026-03-10] BACK 在搜索页面先关闭键盘，需要按两次才能返回上级
- [2026-03-10] 切换 toggle 后要验证行文字/状态，不能只看高亮颜色
```

**为什么只有时间戳，没有 kind/confidence/evidence：**

- 每文件 ≤30 条，LLM recall 时全部读入。分类过滤在此规模下零增益。
- 时间戳是唯一必要元数据：表达"是否过时"，这是 LLM 无法从正文推断的。
- 格式向前兼容：将来需要 kind 可以在正文前加 `[kind]`，无需迁移。

### Entry Cap 执行

写入时检查条目数。超过上限 → 删除最旧条目（文件顶部）。简单的 FIFO 淘汰。

不做 dedup。在 ≤30 条规模下，LLM 调用 `remember_experience` 时能看到 recalled memory 中的已有条目，可以自行避免重复。

---

## Recall（读取路径）

### 每个 planning turn 加载什么

1. `user_prefs.md` — 如果非空
2. `device.md` — 如果非空
3. `apps/<当前前台 package>.md` — 如果已知且存在

### 不加载什么

- 不做跨文件搜索
- 不做 embeddings / FTS / SQLite
- 不做跨 app 广泛 recall

V1 的 recall 是一个小型、边界清晰的文件查找。不是检索系统。

### Prompt 注入位置

在 `PromptBuilder.buildInputItems()` 的组装序列中，在 working memory 之后、app skill 之前插入：

```
history → working memory → ★ recalled memory → app skill → observation
```

格式为独立的 user message：

```md
## Recalled Memory

These are learnings from previous sessions. Use them to avoid repeating mistakes.

### Device
- [2026-03-10] 屏幕密度 420dpi，系统语言英文

### User Preferences
- [2026-03-10] 用户偏好支付宝付款

### App: com.android.settings
- [2026-03-10] Settings 搜索比滚动更可靠
- [2026-03-10] BACK 先收键盘，需要按两次
```

如果三个来源都为空，不注入任何内容（不加噪音）。

### Token Budget

| 来源 | 单文件截断 | 典型实际大小 |
|------|-----------|-------------|
| `device.md` | 2 KB | ~200 B |
| `user_prefs.md` | 2 KB | ~500 B |
| `apps/<pkg>.md` | 2 KB | ~1-3 KB |
| **总计** | **≤ 6 KB** | ~1-4 KB |

截断策略：保留最新条目（文件尾部），截掉最旧条目（文件头部）。

### Turn 1 空档

Turn 1 通常在 launcher / home / 上一个 app，目标 app 的 memory 要到 turn 2+ 才加载。

**为什么 V1 可以接受这个空档：**

- Turn 1 通常只是 `open_app`，app-specific memory 影响不大
- Turn 2 起 agent 已在目标 app 内，recall 生效
- `user_prefs.md` 和 `device.md` 每个 turn 都加载，不受影响
- Phase 2 可复用 `open_app` 的 app 匹配逻辑做 goal-based pre-loading（如果数据证明空档代价大）

---

## Retain（写入路径）

### 写入方式：`remember_experience` 工具

主要写入路径是一个 **工具调用**，不是任务结束后的后台 LLM 调用。

```
Tool: remember_experience
Parameters:
  agent_thought: string  (optional — 与其他工具一致)
  category:      "app" | "user_pref" | "device"
  content:       string  (1-2 sentences, generalizable)
  package_name:  string  (category = "app" 时必填)
```

### 为什么选工具，不选后台 LLM 调用

| 维度 | 工具 | 后台 LLM 调用 |
|------|------|-------------|
| 额外 LLM 调用 | 零 | 每个任务 ~1K tokens |
| 电池影响 | 零 | 额外网络请求 |
| 代码复杂度 | 一个 ToolSpec 注册 | 第二条 LLM 调用链路 |
| 与架构契合 | 和其他工具同路径 | `PromptBuilder → Turn → ToolRouter` 之外的首个例外 |
| LLM 上下文 | 完整（知道什么难、什么失败了） | 需要重新摘要整个 session |
| 可靠性 | 依赖 prompt 提示 | 自动 |

工具方案的核心优势：**LLM 在任务进行中有完整上下文，知道什么值得记住、如何概括。这是最高质量的"编辑控制"，后台调用无法复制。**

### 关键运行时约束：完成路径的缺口

当前 `Turn.kt`（第 204-208 行）允许纯文本完成：

```kotlin
val isComplete =
    completeTaskCall != null ||
    (toolCalls.isEmpty() && effectiveTextContent != null && !hasMalformedKnownToolMarker)
```

这意味着存在一个路径：LLM 不调用任何工具就结束任务，没有 `remember_experience` 的机会。

**V1 策略：接受这个缺口。** 理由：

1. 不应为 memory 系统改变全局 completion 语义 — 那是独立的架构决策。
2. 在良好 prompt 引导下，LLM 走 `complete_task` 路径的比例 >90%。
3. 纯文本退出的场景（"我做不到"、crash、用户手动停止）对 memory 的价值本就很低。
4. 如果 V1 数据证明缺口代价大，Phase 2 可加后台 retain hook 或收紧 `complete_task` 约束。

### 边界情况覆盖

| 场景 | 处理 |
|------|------|
| **正常完成** | LLM 同 turn 调用 `remember_experience` + `complete_task`（多工具已支持） |
| **MaxTurnsReached** | "FINAL TURN" 警告 → LLM 在最后 turn 保存经验 |
| **终态错误/crash** | 无有价值 learning，缺口可接受 |
| **用户手动停止** | 任务中断，经验不完整，缺口可接受 |

### 工具在 TurnToolPolicy 中的分类

`remember_experience` 是 **非屏幕变更工具**（cognitive tool），和 scratchpad / write_todos 同类。

在 `TurnToolPolicy.arbitrateToolCalls()`（当前实现，第 43-85 行）中：
- cognitive calls = `toolCalls.filter { !ToolName.from(call.name).isScreenChanging }`
- 所有 cognitive tool **始终保留**
- `complete_task` 仅在无 screen action 时保留

**关键正确性保证：** LLM 可以在一个 turn 里同时调用 `remember_experience` + `complete_task`，两者都会被选中执行。`remember_experience` 作为 cognitive call 始终保留，`complete_task` 在无 screen action 时也保留。

### MemoryStore 写入行为

1. 格式化为 `- [YYYY-MM-DD] {content}` 并追加到文件末尾
2. 文件不存在时自动创建目录 + 文件 + header
3. 写入后检查 entry cap，超限则删除最旧条目
4. 线程安全：用 `@Synchronized` 保护（和 `ScratchpadState`、`TodoState` 一致）

### 工具策略

auto-allow。写一行 markdown 风险极低且可逆。

### Eval/Debug 隔离

当 agent 运行在 eval/debug-run 模式时，`remember_experience` 的写入应该被跳过。

**理由：** eval 任务是合成的，其"学到的经验"会污染真实用户 memory。

**V1 实现方式：** `MemoryStore` 构造时接受 `readOnly: Boolean` 参数。eval 模式下设为 true，retain 返回成功但不实际写入。recall 正常工作（可以测试 recall 效果）。

---

## 组件设计

### 总览

三个新组件，零新抽象类型：

```
MemoryStore          — 文件读写 + entry cap + 线程安全
MemoryRecaller       — 根据 package 选文件 → 格式化 prompt block
RememberExperienceTool — ToolSpec + ToolInvocation，委托给 MemoryStore
```

条目是字符串。文件是字符串。不引入 MemoryEntry data class、MemoryKind enum、MemoryScope sealed class。保持扁平。

### MemoryStore

```kotlin
// app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryStore.kt  (~80 行)

class MemoryStore(
    private val memoryDir: File,
    private val readOnly: Boolean = false
) {
    companion object {
        const val APP_ENTRY_CAP = 30
        const val USER_PREFS_ENTRY_CAP = 20
        const val DEVICE_ENTRY_CAP = 10
        private const val APPS_DIR = "apps"
        private const val USER_PREFS_FILE = "user_prefs.md"
        private const val DEVICE_FILE = "device.md"
    }

    @Synchronized
    fun appendAppMemory(packageName: String, content: String) {
        if (readOnly) return
        val file = File(File(memoryDir, APPS_DIR), "$packageName.md")
        appendEntry(file, "# App Memory: $packageName", content, APP_ENTRY_CAP)
    }

    @Synchronized
    fun appendUserPref(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, USER_PREFS_FILE), "# User Preferences", content, USER_PREFS_ENTRY_CAP)
    }

    @Synchronized
    fun appendDeviceMemory(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, DEVICE_FILE), "# Device", content, DEVICE_ENTRY_CAP)
    }

    @Synchronized
    fun readAppMemory(packageName: String): String? =
        readFileIfExists(File(File(memoryDir, APPS_DIR), "$packageName.md"))

    @Synchronized
    fun readUserPrefs(): String? =
        readFileIfExists(File(memoryDir, USER_PREFS_FILE))

    @Synchronized
    fun readDevice(): String? =
        readFileIfExists(File(memoryDir, DEVICE_FILE))

    // ── 内部 ──

    private fun appendEntry(file: File, header: String, content: String, cap: Int) {
        file.parentFile?.mkdirs()
        val date = java.time.LocalDate.now().toString()
        val entry = "- [$date] $content"

        if (!file.exists()) {
            file.writeText("$header\n\n$entry\n")
            return
        }
        file.appendText("$entry\n")
        enforceEntryCap(file, header, cap)
    }

    private fun enforceEntryCap(file: File, header: String, cap: Int) {
        val lines = file.readLines()
        val entries = lines.filter { it.trimStart().startsWith("- [") }
        if (entries.size <= cap) return
        val kept = entries.takeLast(cap)
        file.writeText("$header\n\n${kept.joinToString("\n")}\n")
    }

    private fun readFileIfExists(file: File): String? {
        if (!file.exists()) return null
        val content = file.readText().trim()
        return content.takeIf { it.isNotEmpty() }
    }
}
```

### MemoryRecaller

```kotlin
// app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryRecaller.kt  (~50 行)

class MemoryRecaller(private val store: MemoryStore) {

    companion object {
        const val MAX_SECTION_BYTES = 2048
    }

    fun recall(currentPackageName: String?): String? {
        val device = store.readDevice()?.truncateToRecent(MAX_SECTION_BYTES)
        val userPrefs = store.readUserPrefs()?.truncateToRecent(MAX_SECTION_BYTES)
        val appMemory = currentPackageName?.let {
            store.readAppMemory(it)?.truncateToRecent(MAX_SECTION_BYTES)
        }

        if (device == null && userPrefs == null && appMemory == null) return null

        return buildString {
            appendLine("## Recalled Memory")
            appendLine()
            appendLine("These are learnings from previous sessions. Use them to avoid repeating mistakes.")
            device?.let {
                appendLine()
                appendLine("### Device")
                appendLine(extractEntries(it))
            }
            userPrefs?.let {
                appendLine()
                appendLine("### User Preferences")
                appendLine(extractEntries(it))
            }
            appMemory?.let {
                appendLine()
                appendLine("### App: $currentPackageName")
                appendLine(extractEntries(it))
            }
        }.trim()
    }

    private fun extractEntries(content: String): String =
        content.lines().filter { it.trimStart().startsWith("- [") }.joinToString("\n")

    private fun String.truncateToRecent(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        val lines = lines()
        val result = mutableListOf<String>()
        var size = 0
        for (line in lines.reversed()) {
            val lineSize = line.toByteArray().size + 1
            if (size + lineSize > maxBytes) break
            result.add(0, line)
            size += lineSize
        }
        return result.joinToString("\n")
    }
}
```

### RememberExperienceTool

```kotlin
// app/src/main/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceTool.kt  (~80 行)

class RememberExperienceTool(
    private val store: MemoryStore
) : ToolSpec {
    override val name = "remember_experience"

    override val description = """
        Save a reusable learning to long-term memory.

        Call this before complete_task when you learned something that would help
        in future tasks. You can call this alongside complete_task in the same turn.

        What to save:
        - App quirks (button locations, navigation gotchas, unreliable UI patterns)
        - User preferences (payment methods, notification habits, language)
        - Device characteristics (screen density, OS version quirks)

        What NOT to save:
        - Task-specific steps (use scratchpad instead)
        - Temporary data that won't help future tasks
        - Information already in Recalled Memory or App Skills

        Keep entries to 1-2 sentences. Generalize — don't reference specific task details.
    """.trimIndent()

    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for saving this experience")
            })
            put("category", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("app", "user_pref", "device")))
                put("description", "Where to store: app = app-specific quirk, user_pref = user preference, device = device fact")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The learning to save (1-2 sentences, generalizable)")
            })
            put("package_name", JSONObject().apply {
                put("type", "string")
                put("description", "Target app package name (required when category = app)")
            })
        })
        put("required", JSONArray(listOf("category", "content")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        val category = params.optString("category", "")
        if (category !in listOf("app", "user_pref", "device")) {
            return ValidationResult.Invalid("category must be: app, user_pref, or device")
        }
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) return ValidationResult.Invalid("content must not be empty")
        if (content.length > 300) {
            return ValidationResult.Invalid("content too long (max 300 chars). Keep to 1-2 sentences.")
        }
        if (category == "app" && params.optString("package_name", "").trim().isEmpty()) {
            return ValidationResult.Invalid("package_name required when category = app")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val agentThought = params.optString("agent_thought", "").trim()
        val category = params.getString("category")
        val content = params.getString("content").trim()
        return RememberExperienceInvocation(
            store, params, category, content,
            appendReason("Save experience ($category)", agentThought)
        )
    }
}

private class RememberExperienceInvocation(
    private val store: MemoryStore,
    override val params: JSONObject,
    private val category: String,
    private val content: String,
    private val description: String
) : ToolInvocation {
    override val toolName = "remember_experience"
    override fun getDescription() = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        return try {
            when (category) {
                "app" -> store.appendAppMemory(params.getString("package_name").trim(), content)
                "user_pref" -> store.appendUserPref(content)
                "device" -> store.appendDeviceMemory(content)
            }
            textToolSuccess(output = "Saved to long-term memory ($category).")
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Failed to save memory: ${e.message}", e)
        }
    }
}
```

---

## 接入点（Wiring）

### 1. SessionServices — 新增字段 + 实例化

```kotlin
// SessionServices 新增字段：
internal val memoryStore: MemoryStore,
internal val memoryRecaller: MemoryRecaller,

// SessionServices.create() 中：
val memoryStore = MemoryStore(File(context.filesDir, "memory"))
val memoryRecaller = MemoryRecaller(memoryStore)

// 在 tooling 创建之后注册 memory tool：
tooling.toolRegistry.register(RememberExperienceTool(memoryStore))
```

**为什么不修改 `SessionToolingBootstrapper`：** 当前 bootstrapper 不依赖外部状态（只依赖 `sessionState`）。`MemoryStore` 依赖 `context.filesDir`，属于 `SessionServices` 层的关注点。在 `SessionServices.create()` 中注册更干净。

### 2. PromptBuilder — 增加 recalledMemory 参数

```kotlin
fun buildInputItems(
    snapshot: ScreenSnapshot,
    image: ScreenImage?,
    warnings: List<String> = emptyList(),
    turnNumber: Int = 0,
    maxTurns: Int = 0,
    appSkill: String? = null,
    recalledMemory: String? = null   // ← 新增
): List<ResponseInputItem> = buildList {
    addAll(buildHistorySection())
    buildMemorySection()?.let { add(it) }
    recalledMemory?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }  // ← 新增
    appSkill?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }
    add(buildObservationSection(snapshot, image, warnings, turnNumber, maxTurns))
}
```

注入位置：working memory 之后、app skill 之前。LLM 读到的顺序：
1. 历史对话
2. 当前 session 的工作记忆（scratchpad + todos）
3. 跨 session 的持久记忆 ← 新增
4. 静态 app skill 指导
5. 当前屏幕观察

### 3. TurnPlanningPhaseRunner — 调用 recaller

在 `runPlanningPhase()` 中（第 71-79 行区域），在构造 inputItems 前调用 recall：

```kotlin
val appSkill = buildAppSkillMessage(currentPackageName)
val recalledMemory = services.memoryRecaller.recall(currentPackageName)  // ← 新增
val inputItems = promptBuilder.buildInputItems(
    snapshot = snapshot,
    image = snapshot.image,
    warnings = warnings,
    turnNumber = turnNumber,
    maxTurns = config.maxTurns,
    appSkill = appSkill,
    recalledMemory = recalledMemory   // ← 新增
)
```

### 4. ToolName — 新增 RememberExperience

```kotlin
data object RememberExperience : ToolName(
    raw = "remember_experience",
    canonical = "remember_experience",
    displayName = "Remember experience"
)
```

`isScreenChanging` 返回 `false`（cognitive tool）。在 `from()` 和 `isScreenChanging` 的 when 分支中添加。

### 5. StandaloneAgentDef — 允许列表 + 系统提示

在 `allowedTools` 中加入 `"remember_experience"`。

在 systemPrompt 的 `## Working Memory` 段之后添加：

```
## Long-Term Memory

You have persistent memory on this device. Relevant memories are loaded
automatically based on the current app (shown under "Recalled Memory").

Before calling complete_task, if you learned something reusable, call
remember_experience to save it:
- App quirks (button locations, navigation patterns, gotchas)
- User preferences (payment methods, notification habits)
- Device characteristics (screen density, OS version quirks)

Only store generalizable knowledge, not task-specific steps.
Keep entries to 1-2 sentences.
Do not store information already shown in Recalled Memory.
```

---

## 数据流

### Retain 路径

```
LLM 返回 tool call: remember_experience(category="app", content="...", package_name="...")
    ↓
TurnToolPolicy.arbitrateToolCalls()
    → cognitive tool，始终保留（不受 screen action 影响）
    ↓
ToolRouter.execute() → RememberExperienceTool.createInvocation()
    ↓
RememberExperienceInvocation.execute()
    ↓
MemoryStore.appendAppMemory(packageName, content)
    → 写入 files/memory/apps/<package>.md
    → 追加 "- [YYYY-MM-DD] content"
    → 检查 entry cap，超限则删最旧
    ↓
返回 Success("Saved to long-term memory (app).")
```

### Recall 路径

```
TurnPlanningPhaseRunner.runPlanningPhase()
    ↓
services.memoryRecaller.recall(currentPackageName)
    → 读取 device.md + user_prefs.md + apps/<pkg>.md
    → 每文件截断到 2KB（保留最新条目）
    → 拼接为 "## Recalled Memory" 文本块
    ↓
PromptBuilder.buildInputItems(..., recalledMemory = 文本块)
    → 注入为 user message（working memory 和 app skill 之间）
    ↓
LLM 在 context 中看到跨 session 的持久经验
```

---

## 与现有系统的交互

### vs. Scratchpad

| | Scratchpad | Memory |
|---|---|---|
| 生命周期 | session 内 | 跨 session |
| 存储内容 | 当前任务的临时数据 | 跨任务的可复用经验 |
| 写入触发 | 任务执行中任意时刻 | 任务即将完成时 |
| 显示位置 | Working Memory 段 | Recalled Memory 段 |

Prompt 中两者有明确的段落分隔。System prompt 也明确说明："Only store generalizable knowledge, not task-specific steps."

### vs. App Skills

| | App Skills | Memory |
|---|---|---|
| 来源 | repo 中的静态文件 | 运行时 LLM 写入 |
| 更新方式 | 发版时更新 | 每次任务动态积累 |
| 可信度 | 高（人工审核） | 中（可能有误） |

两者互补。App skills 是"教科书"，memory 是"笔记本"。

---

## 文件改动清单

| 文件 | 变更 | 改动量 |
|------|------|-------|
| `app/.../memory/MemoryStore.kt` | **新增** | ~80 行 |
| `app/.../memory/MemoryRecaller.kt` | **新增** | ~50 行 |
| `app/.../tool/impl/RememberExperienceTool.kt` | **新增** | ~80 行 |
| `app/.../agent/cognition/prompt/PromptBuilder.kt` | **修改** — 加 `recalledMemory` 参数 | +3 行 |
| `app/.../agent/TurnPlanningPhaseRunner.kt` | **修改** — 调用 recaller | +2 行 |
| `app/.../session/SessionServices.kt` | **修改** — 加字段 + 实例化 + 注册工具 | +8 行 |
| `app/.../agent/definition/StandaloneAgentDef.kt` | **修改** — allowedTools + system prompt | +15 行 |
| `app/.../tool/ToolName.kt` | **修改** — 加 RememberExperience variant | +6 行 |

**总计：~210 行新增代码，3 个新文件，5 处小编辑。**

---

## 边界情况

| 场景 | 行为 |
|------|------|
| 正常完成 | LLM 同 turn 调用 `remember_experience` + `complete_task`，两者都执行 |
| MaxTurnsReached | "FINAL TURN" 警告，LLM 在最后 turn 保存 |
| 终态错误/crash | 无有价值 learning，缺口可接受 |
| 用户手动停止 | 任务中断，经验不完整，缺口可接受 |
| 纯文本完成（无 tool call） | 缺口存在但概率低（<10%），V1 接受 |
| Memory 文件被手动编辑 | 完全支持，markdown 就是为此设计的 |
| 多 session 并发 | V1 不处理（Android Agent 当前是单 session 模型） |
| 磁盘空间不足 | MemoryStore 捕获 IOException，静默失败，不阻塞任务 |
| Eval/debug-run | `readOnly = true`，retain 返回成功但不写入 |

---

## 明确的非目标

V1 不做：

- Embeddings / 向量搜索
- SQLite / FTS
- Reflect / synthesis bank
- 自动写入 app_skills
- LLM 全文件重写
- 条目 kind taxonomy
- Confidence 或 evidence metadata
- 后台 LLM retain 调用
- Turn 1 的 goal-based target app recall
- Dedup / merge 逻辑
- Memory 管理 UI

---

## 已知限制

1. **纯文本完成缺口** — LLM 不调用 `complete_task` 直接结束时，没有 `remember_experience` 机会。
2. **Turn 1 空档** — 目标 app memory 在 turn 2+ 才加载。
3. **无 dedup** — LLM 可能写入重复条目（≤30 条规模下，LLM 可自行避免）。
4. **无 stale entry 检测** — 老条目可能因 app 更新而过时（时间戳提供 staleness 信号）。
5. **Eval 隔离需手动** — eval runner 需传入 `readOnly = true`。

---

## Phase 2 候选

1. **Goal-based turn-1 recall** — 从 task goal 提取目标 app，turn 1 就加载 memory
2. **后台 retain hook** — 如果工具利用率不够，在 complete_task 后自动触发
3. **收紧完成契约** — 要求成功完成必须经由 complete_task（独立于 memory 但有助于 retain 可靠性）
4. **Memory 可视化 UI** — 在 app 设置中展示/编辑 memory
5. **Autotune memory seeding** — 将 autotune 积累的经验自动导入为初始 memory
6. **跨 app recall** — 基于 task goal 关键词加载多个 app 的 memory

---

## 成功标准

1. 某个 app 的使用经验能在下一次同 app 前台时进入 prompt
2. 用户偏好和设备特征能跨 session 保留
3. Memory 文件是纯 markdown — 人类可读、可 adb pull、可手改
4. Memory 操作不阻塞或拖慢任务完成 / session 生命周期
5. 总 prompt 增量 ≤ 6KB
6. Eval 模式不污染生产 memory

---

## 给主用户的开放问题

1. **是否收紧完成契约？** V1 是否也应要求必须通过 `complete_task` 完成（去掉 `Turn.kt` 的纯文本完成 fallback）？推荐收紧，但这是独立于 memory 的架构决策。

2. **PlannerAgentDef / ExecutorAgentDef 是否也加 memory？** 当前设计只覆盖 `StandaloneAgentDef`。如果 planner-executor 模式也需要，executor 是最合适的注入点。

3. **SubAgent 是否共享主 session 的 MemoryStore？** 当前 `SubAgentRunner` 创建独立 services。V1 建议先只在主 agent 启用。

---

## Self-Review 检查

| 检查项 | 状态 |
|--------|------|
| 覆盖目标？retain + recall 都有 | OK |
| 与 SessionServices 集成？create() 中实例化 | OK |
| 与 PromptBuilder 集成？新参数 recalledMemory | OK |
| 与 TurnPlanningPhaseRunner 集成？调用 recaller | OK |
| 工具注册？SessionServices.create() 中注册 | OK |
| 工具策略（arbitration）？cognitive，始终保留 | OK |
| 与 complete_task 同 turn？可以，TurnToolPolicy 已验证 | OK |
| 有界增长？entry cap + recall truncation | OK |
| 6KB budget？每文件 2KB × 3 = 6KB | OK |
| Eval 隔离？readOnly 模式 | OK |
| Turn 1 gap？文档化为已知限制 | OK |
| 无不必要复杂度？零新类型，纯字符串 | OK |
| 向前兼容？markdown 格式可扩展 | OK |
| ToolName 更新？新增 RememberExperience | OK |
| StandaloneAgentDef 更新？allowedTools + prompt | OK |
