status: aligned

# Memory System V1 -- 对齐设计

日期：2026-03-11
基线：Claude 设计（骨架） + Codex 设计（选择性采纳）

---

## 目标

让 agent 跨 session 积累可复用经验。V1 只做两件事：

1. **Retain** -- 任务中学到可复用经验时，通过工具调用保存到设备本地文件
2. **Recall** -- 每个 planning turn 根据当前前台 app 把相关经验加载进 prompt

这是增量设计。不替代 session history、scratchpad、todos 或 app skills。

---

## 核心设计原则

1. **零额外 LLM 调用** -- 写入靠工具调用，不靠后台 LLM
2. **实体导向** -- 文件按 app package name 组织，不按时间
3. **Markdown 存储** -- 人类可读、可手改、可 adb pull、零依赖
4. **有界增长** -- 每个文件有 entry cap，recall 有 token budget
5. **与 working memory 正交** -- scratchpad/todos 是"当前任务草稿"，memory 是"跨任务经验"

---

## 三层知识体系

```
静态知识      app_skills/<package>/SKILL.md   -- repo 所有，发版时更新
跨任务持久    files/memory/                   -- 新增，设备上长期保留
session 工作  history + scratchpad + todos     -- 每次 session 结束即清
```

app_skills 是"教科书"，memory 是"使用心得"。两者互补不覆盖。

---

## 存储模型

### 目录结构

```text
<context.filesDir>/memory/
+-- apps/
|   +-- com.android.settings.md
|   +-- net.gsantner.markor.md
|   +-- org.tasks.md
+-- user_prefs.md
+-- device.md
```

### 文件分类

| 文件 | 作用域 | Entry Cap |
|------|--------|-----------|
| `apps/<package>.md` | 单个 app 的操作经验 | 30 |
| `user_prefs.md` | 用户偏好（跨 app） | 20 |
| `device.md` | 设备特征 | 10 |

### 条目格式

纯 markdown bullet + 日期时间戳 + **可选 `[kind]` 内联标签**。

```md
# App Memory: net.gsantner.markor

- [2026-03-10] [workflow] 新建文件时，dialog 有独立扩展名字段默认 .md，创建无扩展名文件需清除此字段
- [2026-03-10] [pitfall] 长文件列表不支持直接跳转，只能逐步滚动
- [2026-03-11] [verification] 创建文件后需返回列表确认文件名和扩展名完全匹配
```

#### 对齐决定：采纳 `[kind]` 标签

两份设计都同意这是零成本实验。`[kind]` 只存在于 LLM prompt 指令和写入的正文中，`MemoryStore` 完全不感知。

Kind 只有三个值：
- `[workflow]` -- 操作流程、导航技巧
- `[pitfall]` -- 陷阱、易错点
- `[verification]` -- 验证策略、如何确认操作结果

最坏情况没有效果，从 prompt 指令中删除即可。

### Entry Cap 执行

写入时检查条目数。超过上限 -> 删除最旧条目（文件顶部）。FIFO 淘汰。

不做 dedup。<=30 条规模下 LLM 可看到已有条目自行避免重复。

---

## Recall（读取路径）

### 每个 planning turn 加载什么

1. `user_prefs.md` -- 如果非空
2. `device.md` -- 如果非空
3. `apps/<当前前台 package>.md` -- 如果已知且存在

### 不加载什么

- 不做跨文件搜索、embeddings、FTS、SQLite
- 不做跨 app 广泛 recall

V1 的 recall 是一个小型、边界清晰的文件查找。

### Prompt 注入位置

在 `PromptBuilder.buildInputItems()` 中，working memory 之后、app skill 之前：

```
history -> working memory -> ** recalled memory ** -> app skill -> observation
```

格式为独立的 user message：

```md
## Recalled Memory

These are learnings from previous sessions. Use them to avoid repeating mistakes.

### Device
- [2026-03-10] ...

### User Preferences
- [2026-03-10] ...

### App: com.android.settings
- [2026-03-10] [pitfall] Settings 搜索比滚动更可靠
- [2026-03-10] [workflow] BACK 先收键盘，需要按两次
```

如果三个来源都为空，不注入任何内容。

### Token Budget

#### 对齐决定：采纳弹性分配（Codex 方案）

均分 2KB 会浪费空间（device.md 早期通常为空），app memory 通常价值最高。

| 来源 | 软上限 | 说明 |
|------|--------|------|
| `user_prefs.md` | 1.5 KB | 先分配 |
| `device.md` | 1.0 KB | 次分配 |
| `apps/<pkg>.md` | 剩余预算 | 最多 3.5 KB |
| **总计** | **<= 6 KB** | 硬上限 |

某部分为空时，剩余预算可让后续部分占用，但总量绝不超 6KB。

**截断方向：最新优先。** 保留文件尾部（最新条目），截掉文件头部（最旧条目）。

### Turn 1 空档

#### 对齐决定：V1 接受空档，Phase 2 解决

Claude 说 defer，Codex 说 implement。交叉评审后的结论：

**Codex 对问题的诊断正确** -- turn 1 确实是最需要记忆的时刻。但 **实现方案的覆盖率被高估**：

- `AppAliases.PACKAGE_MAP` 只有 25 条，且集中在 Google 原生应用
- 以下 eval 高频 app 完全缺失：OsmAnd (`net.osmand`)、Tasks.org (`org.tasks`)、OpenTracks (`de.dennisguse.opentracks`)、Retro Music、Broccoli、VLC、Simple Gallery
- `app_skills/` 目录有 16 个 package，但 PACKAGE_MAP 只覆盖其中约一半

要真正覆盖需要同时扫描 `app_skills/` 目录名做反向匹配，复杂度超过"~20 行"。

**V1 策略：**
- Turn 1 通常只是 `open_app`，app-specific memory 影响有限
- Turn 2 起 agent 已在目标 app 内，recall 生效
- `user_prefs.md` 和 `device.md` 每个 turn 都加载，不受影响
- Phase 2 补充 goal-aware recall 时，同步扩展 app alias 覆盖率

---

## Retain（写入路径）

### 主路径：`remember_experience` 工具

```
Tool: remember_experience
Parameters:
  agent_thought: string  (optional -- 与其他工具一致)
  category:      "app" | "user_pref" | "device"
  content:       string  (1-2 sentences, generalizable, 建议加 [kind] 前缀)
  package_name:  string  (category = "app" 时必填)
```

### 工具策略

auto-allow。写一行 markdown 风险极低且可逆。

### 工具在 TurnToolPolicy 中的分类

`remember_experience` 是 **非屏幕变更工具**（cognitive tool），和 scratchpad / write_todos 同类。

在 `TurnToolPolicy.arbitrateToolCalls()` 中：
- cognitive calls = `toolCalls.filter { !ToolName.from(call.name).isScreenChanging }`
- 所有 cognitive tool **始终保留**
- `complete_task` 仅在无 screen action 时保留

**关键正确性保证：** LLM 可以在一个 turn 里同时调用 `remember_experience` + `complete_task`，两者都会被选中执行。

### 完成路径的缺口

当前 `Turn.kt`（第 204-208 行）允许纯文本完成：

```kotlin
val isComplete =
    completeTaskCall != null ||
    (toolCalls.isEmpty() && effectiveTextContent != null && !hasMalformedKnownToolMarker)
```

#### 对齐决定：V1 不收紧完成契约

两份设计都识别了这个缺口。Codex 建议收紧为 `completeTaskCall != null`。Claude 的评审指出了关键风险：**收紧后如果 LLM 持续返回纯文本，会烧完所有 turn**。需要配套"连续纯文本 N 次则强制终止"的安全阀，不是"一行代码"能解决的。

这是独立于 memory 的架构决策，不应绑定在 memory V1 中。

### 边界情况覆盖

| 场景 | 处理 |
|------|------|
| **正常完成** | LLM 同 turn 调用 `remember_experience` + `complete_task`（多工具已支持） |
| **MaxTurnsReached** | "FINAL TURN" 警告 -> LLM 在最后 turn 保存经验 |
| **终态错误/crash** | 无有价值 learning，缺口可接受 |
| **用户手动停止** | 任务中断，经验不完整，缺口可接受 |
| **纯文本完成** | 缺口存在但概率低（<10%），V1 接受 |

### Failure Auto-Retain Hook

#### 对齐决定：V1 采纳，修正实现细节

Codex 提出的 failure auto-retain 方向正确：LLM prompt 遵循率约 70-80%，failure 场景下的经验最有价值。

**但需修正三个实现问题：**

1. **放置位置**：Codex 写的 `AgentExecutor` 不存在。正确位置是 `Agent.run()` 循环结束后、`trace.sessionStopped()` 之前。具体在 `Agent.kt` 的 `TurnOutcome.Complete` 分支中，当 `result.success == false` 时触发。

2. **状态跟踪**：不在 `AgentSessionState`（纯 data class，只有 todos 和 scratchpad）加字段。改为 `MemoryStore` 内部维护一个 `hasWrittenThisSession: AtomicBoolean`。

3. **内容模板**：除了 answer，还应包含 goal 摘要，避免 answer 含糊时条目无用：
   `[pitfall] Failed on "${goal.take(60)}": ${answer.take(80)}`

**实现代码（在 Agent.kt 中）：**

```kotlin
// Agent.run() 中，TurnOutcome.Complete 分支
is TurnOutcome.Complete -> {
    if (!result.success) {
        // Failure auto-retain: 如果本 session 没有主动调用过 remember_experience，
        // 自动保存一条 pitfall 记录
        if (!services.memoryStore.hasWrittenThisSession()) {
            val currentPkg = services.platform.getCurrentPackageName()
            if (currentPkg != null) {
                val entry = "[pitfall] Failed on \"${config.goal.take(60)}\": ${result.message.take(80)}"
                services.memoryStore.appendAppMemory(currentPkg, entry)
            }
        }
    }
    // ... 原有逻辑
}
```

### Eval/Debug 隔离

当 agent 运行在 eval/debug-run 模式时，`remember_experience` 的写入被跳过。

实现：`MemoryStore` 构造时接受 `readOnly: Boolean` 参数。eval 模式下设为 true，retain 返回成功但不实际写入。recall 正常工作（可测试 recall 效果）。

---

## 组件设计

### 总览

三个新组件，零新抽象类型：

```
MemoryStore          -- 文件读写 + entry cap + 线程安全 + session 写入跟踪
MemoryRecaller       -- 根据 package 选文件 -> 弹性预算截断 -> 格式化 prompt block
RememberExperienceTool -- ToolSpec + ToolInvocation，委托给 MemoryStore
```

条目是字符串。文件是字符串。不引入 MemoryEntry data class、MemoryKind enum 等。

### MemoryStore

```kotlin
// app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryStore.kt  (~90 行)

class MemoryStore(
    private val memoryDir: File,
    private val readOnly: Boolean = false,
    val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH
) {
    companion object {
        const val APP_ENTRY_CAP = 30
        const val USER_PREFS_ENTRY_CAP = 20
        const val DEVICE_ENTRY_CAP = 10
        const val DEFAULT_MAX_CONTENT_LENGTH = 2000
        private const val APPS_DIR = "apps"
        private const val USER_PREFS_FILE = "user_prefs.md"
        private const val DEVICE_FILE = "device.md"
    }

    private val writtenThisSession = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasWrittenThisSession(): Boolean = writtenThisSession.get()

    @Synchronized
    fun appendAppMemory(packageName: String, content: String) {
        if (readOnly) return
        val file = File(File(memoryDir, APPS_DIR), "$packageName.md")
        appendEntry(file, "# App Memory: $packageName", content, APP_ENTRY_CAP)
        writtenThisSession.set(true)
    }

    @Synchronized
    fun appendUserPref(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, USER_PREFS_FILE), "# User Preferences", content, USER_PREFS_ENTRY_CAP)
        writtenThisSession.set(true)
    }

    @Synchronized
    fun appendDeviceMemory(content: String) {
        if (readOnly) return
        appendEntry(File(memoryDir, DEVICE_FILE), "# Device", content, DEVICE_ENTRY_CAP)
        writtenThisSession.set(true)
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

    // -- 内部 --

    private fun appendEntry(file: File, header: String, content: String, cap: Int) {
        file.parentFile?.mkdirs()
        val date = java.time.LocalDate.now().toString()
        val truncated = content.take(maxContentLength)
        val entry = "- [$date] $truncated"

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
// app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryRecaller.kt  (~70 行)

class MemoryRecaller(private val store: MemoryStore) {

    companion object {
        private const val USER_PREFS_BUDGET = 1536  // 1.5KB
        private const val DEVICE_BUDGET = 1024      // 1KB
        private const val TOTAL_BUDGET = 6144       // 6KB
    }

    fun recall(currentPackageName: String?): String? {
        val sections = mutableListOf<String>()
        var remaining = TOTAL_BUDGET

        // 1. Device
        store.readDevice()?.let { raw ->
            val entries = extractEntries(raw).truncateToRecent(minOf(DEVICE_BUDGET, remaining))
            if (entries != null) {
                sections.add("### Device\n$entries")
                remaining -= entries.toByteArray().size
            }
        }

        // 2. User Preferences
        if (remaining > 0) {
            store.readUserPrefs()?.let { raw ->
                val entries = extractEntries(raw).truncateToRecent(minOf(USER_PREFS_BUDGET, remaining))
                if (entries != null) {
                    sections.add("### User Preferences\n$entries")
                    remaining -= entries.toByteArray().size
                }
            }
        }

        // 3. App memory (gets remaining budget)
        if (currentPackageName != null && remaining > 0) {
            store.readAppMemory(currentPackageName)?.let { raw ->
                val entries = extractEntries(raw).truncateToRecent(remaining)
                if (entries != null) {
                    sections.add("### App: $currentPackageName\n$entries")
                }
            }
        }

        if (sections.isEmpty()) return null

        return buildString {
            appendLine("## Recalled Memory")
            appendLine()
            appendLine("These are learnings from previous sessions. Use them to avoid repeating mistakes.")
            for (section in sections) {
                appendLine()
                appendLine(section)
            }
        }.trim()
    }

    private fun extractEntries(content: String): String =
        content.lines().filter { it.trimStart().startsWith("- [") }.joinToString("\n")

    /** 从后往前保留（最新优先），按行截断 */
    private fun String.truncateToRecent(maxBytes: Int): String? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.toByteArray().size <= maxBytes) return trimmed
        val lines = trimmed.lines()
        val result = mutableListOf<String>()
        var size = 0
        for (line in lines.reversed()) {
            val lineSize = line.toByteArray().size + 1
            if (size + lineSize > maxBytes) break
            result.add(0, line)
            size += lineSize
        }
        return result.joinToString("\n").trim().ifEmpty { null }
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

        Prefix content with a kind tag:
        - [workflow] operation patterns, navigation sequences, useful shortcuts
        - [pitfall] traps, gotchas, things that don't work as expected
        - [verification] how to verify a result in this app

        What NOT to save:
        - Task-specific steps (use scratchpad instead)
        - Temporary data that won't help future tasks
        - Information already in Recalled Memory or App Skills

        Keep entries to 1-2 sentences. Generalize -- don't reference specific task details.
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
                put("description", "The learning to save (1-2 sentences, prefixed with [workflow], [pitfall], or [verification])")
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
        if (content.length > store.maxContentLength) {
            return ValidationResult.Invalid("content too long (max ${store.maxContentLength} chars). Keep to 1-2 sentences.")
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

### 1. SessionServices -- 新增字段 + 实例化

```kotlin
// SessionServices 新增字段：
internal val memoryStore: MemoryStore,
internal val memoryRecaller: MemoryRecaller,

// SessionServices.create() 中：
val memoryDir = File(context.filesDir, "memory")
val isEvalMode = config.traceRunId != null  // eval/debug-run 模式检测
val memoryStore = MemoryStore(memoryDir, readOnly = isEvalMode, maxContentLength = 2000)
val memoryRecaller = MemoryRecaller(memoryStore)

// 在 tooling 创建之后注册 memory tool：
tooling.toolRegistry.register(RememberExperienceTool(memoryStore))
```

**为什么不修改 `SessionToolingBootstrapper`：** 当前 bootstrapper（`SessionToolingBootstrapper.create()`）只接受 `approvalMode` 参数，不依赖外部状态。`MemoryStore` 依赖 `context.filesDir`，属于 `SessionServices` 层的关注点。在 `SessionServices.create()` 中注册更干净。（两份评审都确认了这个判断。）

### 2. PromptBuilder -- 增加 recalledMemory 参数

```kotlin
fun buildInputItems(
    snapshot: ScreenSnapshot,
    image: ScreenImage?,
    warnings: List<String> = emptyList(),
    turnNumber: Int = 0,
    maxTurns: Int = 0,
    appSkill: String? = null,
    recalledMemory: String? = null   // <-- 新增
): List<ResponseInputItem> = buildList {
    addAll(buildHistorySection())
    buildMemorySection()?.let { add(it) }
    recalledMemory?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }  // <-- 新增
    appSkill?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textUserMessage(it)) }
    add(buildObservationSection(snapshot, image, warnings, turnNumber, maxTurns))
}
```

注入顺序：history -> working memory -> **recalled memory** -> app skill -> observation

注意：`PromptBuilderTest.kt` 也需要更新以适配新参数。

### 3. TurnPlanningPhaseRunner -- 调用 recaller

在 `runPlanningPhase()` 中（第 71-79 行区域）：

```kotlin
val appSkill = buildAppSkillMessage(currentPackageName)
val recalledMemory = services.memoryRecaller.recall(currentPackageName)  // <-- 新增
val inputItems = promptBuilder.buildInputItems(
    snapshot = snapshot,
    image = snapshot.image,
    warnings = warnings,
    turnNumber = turnNumber,
    maxTurns = config.maxTurns,
    appSkill = appSkill,
    recalledMemory = recalledMemory   // <-- 新增
)
```

### 4. ToolName -- 新增 RememberExperience

```kotlin
data object RememberExperience : ToolName(
    raw = "remember_experience",
    canonical = "remember_experience",
    displayName = "Remember experience"
)
```

在 `isScreenChanging` 的 when 分支中添加（返回 `false`，cognitive tool）。
在 `from()` 的 when 分支中添加。

### 5. StandaloneAgentDef -- 允许列表 + 系统提示

在 `allowedTools` 中加入 `"remember_experience"`。

在 systemPrompt 的 `## Working Memory` 段之后添加：

```
## Long-Term Memory

You have persistent memory on this device. Relevant memories are loaded
automatically based on the current app (shown under "Recalled Memory").

Before calling complete_task, if you learned something reusable, call
remember_experience to save it. Prefix content with a kind tag:
- [workflow] operation patterns, navigation sequences, useful shortcuts
- [pitfall] traps, gotchas, things that don't work as expected
- [verification] how to verify a result in this app

Only store generalizable knowledge, not task-specific steps.
Keep entries to 1-2 sentences.
Do not store information already shown in Recalled Memory or App Skills.
```

### 6. Agent.kt -- Failure Auto-Retain Hook

在 `Agent.run()` 的 `TurnOutcome.Complete` 分支中，`trace.sessionStopped()` 之前：

```kotlin
is TurnOutcome.Complete -> {
    if (!result.success && !services.memoryStore.hasWrittenThisSession()) {
        val currentPkg = services.platform.getCurrentPackageName()
        if (currentPkg != null) {
            val entry = "[pitfall] Failed on \"${config.goal.take(60)}\": ${result.message.take(80)}"
            services.memoryStore.appendAppMemory(currentPkg, entry)
        }
    }
    // ... 原有完成逻辑 ...
}
```

---

## 数据流

### Retain 路径（主路径）

```
LLM 返回 tool call: remember_experience(category="app", content="[pitfall] ...", package_name="...")
    |
TurnToolPolicy.arbitrateToolCalls()
    -> cognitive tool，始终保留
    |
ToolRouter.execute() -> RememberExperienceTool.createInvocation()
    |
RememberExperienceInvocation.execute()
    |
MemoryStore.appendAppMemory(packageName, content)
    -> 写入 files/memory/apps/<package>.md
    -> 追加 "- [YYYY-MM-DD] content"
    -> 检查 entry cap，超限则删最旧
    -> writtenThisSession = true
    |
返回 Success("Saved to long-term memory (app).")
```

### Retain 路径（failure 兜底）

```
Agent.run() 循环结束
    |
TurnOutcome.Complete(success = false)
    |
检查 memoryStore.hasWrittenThisSession() == false
    |
自动写入 "[pitfall] Failed on '${goal}': ${message}"
    -> 同样走 MemoryStore.appendAppMemory()
```

### Recall 路径

```
TurnPlanningPhaseRunner.runPlanningPhase()
    |
services.memoryRecaller.recall(currentPackageName)
    -> 读取 device.md + user_prefs.md + apps/<pkg>.md
    -> 弹性预算截断（device 1KB, user_prefs 1.5KB, app 剩余, 总计 <= 6KB）
    -> 拼接为 "## Recalled Memory" 文本块
    |
PromptBuilder.buildInputItems(..., recalledMemory = 文本块)
    -> 注入为 user message（working memory 和 app skill 之间）
```

---

## 文件改动清单

| 文件 | 变更 | 改动量 |
|------|------|-------|
| `app/.../memory/MemoryStore.kt` | **新增** | ~90 行 |
| `app/.../memory/MemoryRecaller.kt` | **新增** | ~70 行 |
| `app/.../tool/impl/RememberExperienceTool.kt` | **新增** | ~80 行 |
| `app/.../agent/cognition/prompt/PromptBuilder.kt` | **修改** -- 加 `recalledMemory` 参数 | +3 行 |
| `app/.../agent/TurnPlanningPhaseRunner.kt` | **修改** -- 调用 recaller | +2 行 |
| `app/.../session/SessionServices.kt` | **修改** -- 加字段 + 实例化 + 注册工具 | +10 行 |
| `app/.../agent/definition/StandaloneAgentDef.kt` | **修改** -- allowedTools + system prompt | +15 行 |
| `app/.../tool/ToolName.kt` | **修改** -- 加 RememberExperience variant | +6 行 |
| `app/.../agent/Agent.kt` | **修改** -- failure auto-retain hook | +8 行 |
| `app/.../test/.../PromptBuilderTest.kt` | **修改** -- 适配新参数 | ~5 行 |

**总计：~240 行新增代码，3 个新文件，7 处小编辑。**

---

## 六项对齐决定总结

| # | 议题 | 决定 | 理由 |
|---|------|------|------|
| 1 | Turn-1 recall gap | **V1 接受空档，Phase 2 解决** | PACKAGE_MAP 覆盖率不足（25 条 vs 16 个 app_skills package），实现复杂度被低估 |
| 2 | Failure auto-retain hook | **V1 采纳，修正实现** | 修正放置位置到 Agent.kt，用 MemoryStore 内部 AtomicBoolean 跟踪，不污染 AgentSessionState |
| 3 | [kind] 内联标签 | **V1 采纳** | 零代码成本，只存在于 prompt 指令和正文中，MemoryStore 不感知，可逆 |
| 4 | 弹性 vs 固定预算 | **采纳弹性分配** | app memory 价值最高，device/user_prefs 早期通常为空，弹性分配更合理 |
| 5 | 收紧完成契约 | **V1 不做，独立评估** | 需要配套安全阀防止无限循环，不是"一行代码"，不绑定 memory V1 |
| 6 | AgentSessionState 突变 | **不修改 AgentSessionState** | 保持 data class 纯粹性，改用 MemoryStore 内部 AtomicBoolean |

---

## 边界情况

| 场景 | 行为 |
|------|------|
| 正常完成 | LLM 同 turn 调用 `remember_experience` + `complete_task`，两者都执行 |
| MaxTurnsReached | "FINAL TURN" 警告，LLM 在最后 turn 保存 |
| 失败完成 | failure auto-retain hook 自动保存 pitfall |
| 终态错误/crash | 无有价值 learning，缺口可接受 |
| 用户手动停止 | 任务中断，经验不完整，缺口可接受 |
| 纯文本完成 | 缺口存在但概率低（<10%），V1 接受 |
| Memory 文件被手动编辑 | 完全支持，markdown 就是为此设计的 |
| 多 session 并发 | V1 不处理（Android Agent 当前是单 session 模型） |
| 磁盘空间不足 | MemoryStore 的 @Synchronized 方法捕获 IOException，静默失败，不阻塞任务 |
| Eval/debug-run | `readOnly = true`，retain 返回成功但不写入 |

---

## 明确的非目标

V1 不做：

- Embeddings / 向量搜索 / SQLite / FTS
- Reflect / synthesis bank / 自动归纳
- 自动写入 app_skills
- LLM 全文件重写
- Confidence 或 evidence metadata
- 后台 LLM retain 调用（failure auto-retain 不算 -- 不调用 LLM）
- Turn 1 的 goal-based target app recall
- Dedup / merge 逻辑
- Memory 管理 UI
- 收紧 `Turn.kt` 完成契约
- PlannerAgentDef / ExecutorAgentDef 的 memory 支持（V1 只覆盖 StandaloneAgentDef）
- SubAgent 共享主 session 的 MemoryStore

---

## Phase 2 候选

| 优先级 | 项目 | 前置条件 |
|--------|------|----------|
| 高 | Goal-aware turn-1 recall | 扩展 app alias 覆盖率或改用 app label 扫描 |
| 高 | 收紧完成契约 + 安全阀 | 需要配套连续纯文本 N 次强制终止机制 |
| 中 | Memory 可视化 UI | V1 数据积累到足够量 |
| 中 | Autotune memory seeding | 将 autotune 积累的经验导入为初始 memory |
| 低 | 跨 app recall | 基于 task goal 关键词加载多个 app memory |
| 低 | PlannerAgentDef / ExecutorAgentDef 覆盖 | 确认 planner-executor 模式需要 memory |

---

## 成功标准

1. 某个 app 的使用经验能在下一次同 app 前台时进入 prompt
2. 用户偏好和设备特征能跨 session 保留
3. Memory 文件是纯 markdown -- 人类可读、可 adb pull、可手改
4. Memory 操作不阻塞或拖慢任务完成
5. 总 prompt 增量 <= 6KB
6. Eval 模式不污染生产 memory
7. 失败任务的原因被自动保存到对应 app 的 memory 文件（failure auto-retain）

---

## 给主用户的开放问题

1. **Eval 模式检测方式：** `config.traceRunId != null` 已确认足够准确 ✅

2. **内容长度上限：** 已改为 2000 字符，通过构造参数可配置 ✅

3. **Failure auto-retain：** 默认开启，V1 不提供关闭开关（行为透明且可逆——手动删除条目即可）
