status: draft

# Memory System V1 — Codex 独立设计

日期：2026-03-10

---

## 核心洞察：从不同角度审视

阅读完已有设计（`design.md` / `design_cn.md`）后，我认同大方向（markdown 存储、tool-based retain、per-app recall），但在三个关键点上提出不同视角。

### 洞察 1：Turn-1 Gap 不是"可接受的限制"，而是设计缺陷

已有设计把 turn-1 gap 归为"V1 可接受，Phase 2 再解"。但实际场景中，turn 1 恰恰是**最需要记忆的时刻**。如果 agent 上次在 OsmAnd 里踩过"必须用 Address tab 搜索"的坑，turn 1 就该知道，而不是等到 turn 2 才想起来。

关键在于：**goal 文本本身就包含了目标 app 信息**。"在 Markor 中创建文件"——Markor 就在 goal 里。不需要等 foreground package 变化，只需在 session 启动时做一次轻量级 goal 解析。

成本：~20 行代码，复用已有的 `AppAliases.PACKAGE_MAP`。价值：覆盖几乎所有 autotune 任务的 turn-1 recall。

### 洞察 2：条目 Kind 不是为了过滤，而是给 LLM 认知锚点

已有设计反对 `kind` 字段，理由是"<=30 条 LLM 全读，kind 不改变 LLM 看到的内容"。推理在文件层面成立，但忽略了一点：**kind 不是给检索系统用的，是 prompt engineering**。

当 LLM 读到 `[pitfall]` 前缀，会自动提高警觉；读到 `[workflow]`，会尝试复用流程。成本为零——只是正文多几个字符，`MemoryStore` 完全不需要感知这些标签。如果 V1 数据表明没有价值，从 prompt 指令中删除即可。

### 洞察 3：Auto-Retain 不是"备份"，而是数据收集的基本保障

tool-based retain 的可靠性完全取决于 LLM 是否"记得"调用工具。从 autotune 经验看，LLM 遵循 prompt 指令的比率大约 70-80%，意味着 20-30% 的经验会丢失。V1 恰恰是数据收集最宝贵的时期。

一个极轻量的 auto-retain hook（不需要额外 LLM 调用）可以兜底**失败场景**——这是最有价值的经验来源。

---

## 目标

为 Android Agent 增加跨 session 的长期记忆能力。

V1 三个能力：
1. **Retain** — 任务中保存可复用经验（LLM 主动 + failure 自动兜底）
2. **Recall** — 每个 planning turn 加载相关记忆
3. **Goal-Aware Bootstrap** — session 启动时从 goal 推断目标 app，解决 turn-1 gap

---

## 与现有系统的关系

```
静态知识层    app_skills/<package>/SKILL.md     <- repo 所有，手工维护
运行时记忆层  files/memory/apps/<package>.md    <- 设备本地，agent 自动积累
会话工作层    history + scratchpad + todos       <- session scope
```

三层互不覆盖：
- app_skills = "操作手册"（如何做）
- memory = "经验笔记"（踩过什么坑、发现什么规律）
- working memory = "当前任务草稿"

---

## 存储模型

```text
<app files>/memory/
├── apps/
│   ├── com.android.settings.md
│   ├── net.gsantner.markor.md
│   └── ...
├── user_prefs.md
└── device.md
```

按实体组织，不按时间。根目录挂在 `context.filesDir/memory/`。

### 条目格式

```md
# App Memory: net.gsantner.markor

- [2026-03-10] [workflow] 新建文件时，dialog 有独立扩展名字段默认 .md，创建无扩展名文件需清除此字段
- [2026-03-10] [pitfall] 长文件列表不支持直接跳转，只能逐步滚动
- [2026-03-11] [verification] 创建文件后需返回列表确认文件名和扩展名完全匹配
```

**与已有设计的差异：增加了 `[kind]` 内联标签。**

Kind 只有三个值，写在正文开头，不是独立字段：
- `[workflow]` — 操作流程、导航技巧、快捷方式
- `[pitfall]` — 陷阱、易错点、不符合预期的行为
- `[verification]` — 验证策略、如何确认操作结果

为什么只要三个：
- LLM 写入时无需纠结分类——从上下文自然可判断
- 阅读时提供认知锚点——`[pitfall]` 意味着"小心"，`[workflow]` 意味着"可复用"
- 不增加任何代码复杂度——它只是正文的一部分

### 条目上限

| 文件 | 上限 |
|---|---|
| 每个 app 文件 | 30 条 |
| `user_prefs.md` | 20 条 |
| `device.md` | 10 条 |

超限时淘汰最旧条目（列表顶部）。V1 不做语义去重——LLM 可以看到已有条目并主动避免重复。

---

## Recall

### 加载策略

每个 planning turn 加载：

1. `user_prefs.md`（非空时）
2. `device.md`（非空时）
3. 一个 app memory 文件（基于下文的选择逻辑）

### Goal-Aware Bootstrap：解决 Turn-1 Gap

**这是本设计与已有方案的主要差异。**

核心思路：session 启动时，从 goal 文本提取目标 app 名称，映射到 package name，预加载对应 memory。

```kotlin
// MemoryRecaller 内部
fun recallForGoal(goalText: String): String? {
    val packageName = resolveTargetPackage(goalText) ?: return null
    return loadAppMemory(packageName)
}

private fun resolveTargetPackage(goalText: String): String? {
    val lowerGoal = goalText.lowercase()
    // 复用 OpenAppTool 的 AppAliases.PACKAGE_MAP
    for ((alias, pkg) in AppAliases.PACKAGE_MAP) {
        if (lowerGoal.contains(alias)) return pkg
    }
    return null
}
```

**调用逻辑（在 TurnPlanningPhaseRunner 中）：**

```kotlin
val recalledMemory = if (turnNumber == 1) {
    // Turn 1: goal 优先，foreground 兜底
    memoryRecaller.recallForGoal(config.goalText)
        ?: memoryRecaller.recall(currentPackageName)
} else {
    // Turn 2+: foreground 优先
    memoryRecaller.recall(currentPackageName)
}
```

**降级逻辑：**
- goal 中找不到 app 名 -> 不加载 app memory，只加载 user_prefs + device
- 找到的 app 有多个匹配 -> 取第一个（与 `open_app` 行为一致）
- 对应 memory 文件不存在 -> 静默跳过

**为什么假阳性无害：** 多加载一些 memory 条目不会造成错误行为，只是多几百字 context。

### Prompt 注入位置

```
history -> working memory -> recalled memory -> app skill -> observation
```

作为 user message 注入，复用 `PromptBuilder` 现有路径。不引入新的 system message。

### 注入格式

```md
## Recalled Memory

Past experience on this device (read-only context):

### App: net.gsantner.markor
Source: target app predicted from goal

- [2026-03-10] [workflow] 新建文件时...
- [2026-03-10] [pitfall] 长文件列表...
```

`Source` 行帮助 LLM 理解 turn 1 为什么能看到目标 app 的 memory。可选值：
- `foreground app`
- `target app predicted from goal`

### 预算

总 memory 段 <= 6KB。

打包策略（弹性分配）：
1. `user_prefs.md` 最近内容，软上限 1.5KB
2. `device.md` 最近内容，软上限 1KB
3. 剩余预算给 app memory，最多 3.5KB

某部分为空时，剩余预算可让后续部分占用，但总量绝不超 6KB。这比"每文件固定 2KB"更适合移动端——app memory 通常价值最高。

**截断方向：从后往前保留（最新优先）。** 最新条目在文件底部（append 模式），截断时优先保留尾部。

---

## Retain

### 主路径：`remember_experience` 工具

```text
Tool: remember_experience
Parameters:
  category: "app" | "user_pref" | "device"
  content:  string (1-2 sentences, generalizable, prefixed with [kind])
  package_name: string (required when category = "app")
  agent_thought: string (optional, consistent with existing tool style)
```

工具策略：auto-allow，不需用户审批。

**系统提示词增加说明：**

```
## Long-Term Memory

You have persistent memory on this device. Relevant memories are loaded
automatically based on the current app.

Before calling complete_task, if you learned something reusable, call
remember_experience to save it. Prefix content with a kind tag:
- [workflow] operation patterns, navigation sequences, useful shortcuts
- [pitfall] traps, gotchas, things that don't work as expected
- [verification] how to verify a result in this app

Only store generalizable knowledge, not task-specific steps.
Keep entries to 1-2 sentences.
```

### 备用路径：Failure Auto-Retain Hook

**这是本设计与已有方案的第二个差异。**

在 `complete_task` 执行完成后的处理链中（非 tool 内部），检查本次 session 是否已调用过 `remember_experience`。如果没有，且任务状态为 **failure**，则自动保存一条经验。

```kotlin
// AgentExecutor 层面（非 CompleteTaskTool 内部）
if (status == "failure" && !sessionState.hasCalledRememberExperience) {
    val currentPkg = platform.getCurrentPackageName()
    if (currentPkg != null) {
        val failureEntry = "[pitfall] Task failed: ${answer.take(120)}"
        memoryStore.append("app", currentPkg, failureEntry)
    }
}
```

**为什么只在 failure 时自动保存：**
- 成功任务的经验多样化，自动提取质量低
- 失败任务的信息高度集中——"什么不行"比"什么可以"更容易自动总结
- failure 的 answer 字段本身就包含了失败原因的结构化描述
- 覆盖最有价值的场景——防止 agent 反复在同一个坑上失败

**为什么不做完整的后台 LLM 调用：**
- 不创建第二条 LLM 调用路径
- 从 failure answer 直接截取，质量已足够
- 零额外成本

### 运行时约束：收紧完成契约

**本设计的选择：V1 收紧完成契约。**

修改 `Turn.kt` 的完成判定：

当前逻辑：
```kotlin
val isComplete = completeTaskCall != null ||
    (toolCalls.isEmpty() && effectiveTextContent != null && !hasMalformedKnownToolMarker)
```

目标逻辑：
```kotlin
val isComplete = completeTaskCall != null
```

理由：
1. 纯文本完成本身就是 agent 行为异常的信号——正常 agent 应通过 `complete_task` 明确结束
2. 收紧后，tool-based retain 和 auto-retain hook 都能可靠触发
3. 独立有价值——结构化 completion 数据对 eval 和 trace 分析更好
4. 改动量极小——一行代码

### Store 行为

- `MemoryStore` 以 `- [YYYY-MM-DD] {content}` 格式追加写入
- 文件不存在时自动创建文件 + header
- 写入时执行 entry cap，超限删除最旧条目
- V1 不做语义去重（规模小，LLM 可自行避免）
- 单条内容最大 200 字符

---

## 组件

### 新增文件

| 文件 | 职责 | 估计行数 |
|---|---|---|
| `memory/MemoryStore.kt` | 文件读写、追加、entry cap | ~90 行 |
| `memory/MemoryRecaller.kt` | 按 package/goal 选文件，格式化 prompt block | ~80 行 |
| `tool/impl/RememberExperienceTool.kt` | 工具定义、调用委托 | ~80 行 |

### 修改文件

| 文件 | 变更 |
|---|---|
| `PromptBuilder.kt` | `buildInputItems()` 增加 `recalledMemory: String?` 参数，注入为 user message |
| `TurnPlanningPhaseRunner.kt` | 调用 `memoryRecaller`，传递 goal text 和 turnNumber |
| `SessionServices.kt` | 注入 `MemoryStore` + `MemoryRecaller` |
| `SessionToolingBootstrapper.kt` | 注册 `RememberExperienceTool` |
| `StandaloneAgentDef.kt` | system prompt 增加 memory 说明 + allowedTools 增加 `remember_experience` |
| `AgentSessionState.kt` | 增加 `hasCalledRememberExperience` 标记 |
| `Turn.kt` | 收紧 `isComplete` 判定（可选，推荐 V1 做） |

### Wiring

```kotlin
// SessionServices.create()
val memoryDir = File(context.filesDir, "memory")
val memoryStore = MemoryStore(memoryDir)
val memoryRecaller = MemoryRecaller(memoryStore)

// SessionToolingBootstrapper
register(RememberExperienceTool(memoryStore, sessionState))

// TurnPlanningPhaseRunner.runPlanningPhase()
val recalledMemory = if (turnNumber == 1) {
    memoryRecaller.recallForGoal(goalText)
        ?: memoryRecaller.recall(currentPackageName)
} else {
    memoryRecaller.recall(currentPackageName)
}
val inputItems = promptBuilder.buildInputItems(
    snapshot = snapshot,
    image = snapshot.image,
    warnings = warnings,
    turnNumber = turnNumber,
    maxTurns = config.maxTurns,
    appSkill = appSkill,
    recalledMemory = recalledMemory  // 新参数
)
```

### MemoryRecaller 核心逻辑

```kotlin
class MemoryRecaller(private val store: MemoryStore) {

    companion object {
        private const val USER_PREFS_BUDGET = 1536  // 1.5KB
        private const val DEVICE_BUDGET = 1024      // 1KB
        private const val TOTAL_BUDGET = 6144       // 6KB
    }

    fun recall(currentPackageName: String?): String? {
        return buildRecallBlock(appPackage = currentPackageName, source = "foreground app")
    }

    fun recallForGoal(goalText: String): String? {
        val pkg = resolveTargetPackage(goalText) ?: return null
        return buildRecallBlock(appPackage = pkg, source = "target app predicted from goal")
    }

    private fun buildRecallBlock(appPackage: String?, source: String): String? {
        val sections = mutableListOf<String>()
        var remaining = TOTAL_BUDGET

        // 1. User prefs
        store.read("user_prefs")?.truncateFromEnd(minOf(USER_PREFS_BUDGET, remaining))?.let {
            sections.add("### User Preferences\n$it")
            remaining -= it.length
        }

        // 2. Device
        if (remaining > 0) {
            store.read("device")?.truncateFromEnd(minOf(DEVICE_BUDGET, remaining))?.let {
                sections.add("### Device\n$it")
                remaining -= it.length
            }
        }

        // 3. App memory (gets whatever budget remains)
        if (appPackage != null && remaining > 0) {
            store.readApp(appPackage)?.truncateFromEnd(remaining)?.let {
                sections.add("### App: $appPackage\nSource: $source\n\n$it")
            }
        }

        if (sections.isEmpty()) return null

        return buildString {
            appendLine("## Recalled Memory")
            appendLine()
            appendLine("Past experience on this device (read-only context):")
            appendLine()
            sections.forEach { section ->
                appendLine(section)
                appendLine()
            }
        }.trim()
    }

    private fun resolveTargetPackage(goalText: String): String? {
        val lower = goalText.lowercase()
        for ((alias, pkg) in AppAliases.PACKAGE_MAP) {
            if (lower.contains(alias)) return pkg
        }
        return null
    }

    /** 从后往前保留（最新优先），按行截断 */
    private fun String.truncateFromEnd(maxLen: Int): String? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= maxLen) return trimmed
        val lines = trimmed.lines()
        val result = StringBuilder()
        for (line in lines.asReversed()) {
            if (result.length + line.length + 1 > maxLen) break
            result.insert(0, line + "\n")
        }
        return result.toString().trim().ifEmpty { null }
    }
}
```

### MemoryStore 核心逻辑

```kotlin
class MemoryStore(private val baseDir: File) {

    companion object {
        private const val APPS_DIR = "apps"
        private const val MAX_CONTENT_LENGTH = 200
        private val ENTRY_CAPS = mapOf(
            "app" to 30,
            "user_pref" to 20,
            "device" to 10
        )
    }

    fun append(category: String, packageOrNull: String?, content: String) {
        val file = resolveFile(category, packageOrNull)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }

        val date = java.time.LocalDate.now().toString()
        val truncatedContent = content.take(MAX_CONTENT_LENGTH)
        val entry = "- [$date] $truncatedContent"

        if (!file.exists()) {
            val header = buildHeader(category, packageOrNull)
            file.writeText("$header\n\n$entry\n")
        } else {
            file.appendText("$entry\n")
        }
        enforceEntryCap(file, category)
    }

    fun read(category: String): String? {
        val file = resolveFile(category, null)
        return file.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    }

    fun readApp(packageName: String): String? {
        val file = File(File(baseDir, APPS_DIR), "$packageName.md")
        return file.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    }

    private fun resolveFile(category: String, packageName: String?): File = when (category) {
        "app" -> File(File(baseDir, APPS_DIR), "${packageName!!}.md")
        "user_pref" -> File(baseDir, "user_prefs.md")
        "device" -> File(baseDir, "device.md")
        else -> throw IllegalArgumentException("Unknown category: $category")
    }

    private fun buildHeader(category: String, packageName: String?) = when (category) {
        "app" -> "# App Memory: $packageName"
        "user_pref" -> "# User Preferences"
        "device" -> "# Device Info"
        else -> "# Memory"
    }

    private fun enforceEntryCap(file: File, category: String) {
        val cap = ENTRY_CAPS[category] ?: ENTRY_CAPS["app"]!!
        val lines = file.readLines()
        val entryLines = lines.filter { it.trimStart().startsWith("- [") }
        if (entryLines.size <= cap) return
        val headerLines = lines.takeWhile { !it.trimStart().startsWith("- [") }
        val keptEntries = entryLines.takeLast(cap)
        file.writeText((headerLines + keptEntries).joinToString("\n") + "\n")
    }
}
```

---

## 与已有设计的对照

| 维度 | 已有设计 (design.md) | 本设计 | 差异理由 |
|---|---|---|---|
| Turn-1 gap | 接受为限制，Phase 2 再解 | Goal-aware bootstrap，V1 解 | 成本极低（~20 行），价值高 |
| 条目 kind | 不加 | 加内联 `[kind]` 标签 | 零代码成本，给 LLM 认知锚点 |
| Auto-retain | 不做，Phase 2 if needed | failure 时自动保存 | 覆盖最有价值场景，无额外 LLM 调用 |
| 完成契约 | 开放问题 | 收紧（推荐 V1 做） | 独立有价值，让 retain 可靠 |
| 截断方向 | 未明确 | 明确为最新优先 | 实现细节需明确 |
| 预算分配 | 每文件 2KB 均分 | 弹性分配，app memory 优先 | 移动端 app memory 价值最高 |
| 组件数 | 3 新文件 | 3 新文件 | 一致 |
| 总代码量 | ~250 行 | ~300 行 | 多了 goal 解析和 auto-retain |

---

## 实现顺序

1. **MemoryStore** — 文件 CRUD + entry cap（独立可测）
2. **MemoryRecaller** — recall + goal-aware bootstrap（依赖 MemoryStore）
3. **RememberExperienceTool** — 工具注册（依赖 MemoryStore）
4. **Wiring** — SessionServices / ToolingBootstrapper / PromptBuilder / TurnPlanningPhaseRunner
5. **System prompt** — StandaloneAgentDef 增加 memory 说明 + allowedTools
6. **Auto-retain hook** — Executor 层 failure 路径
7. **（推荐同步）收紧完成契约** — Turn.kt isComplete 调整

每步可独立验证。步骤 1-3 可并行开发。

---

## 成功标准

1. 同一 app 的使用经验在下一次 session 的 prompt 中出现
2. Turn 1 时，如果 goal 明确提到目标 app，该 app 的 memory 被加载
3. 失败任务的原因被自动保存到对应 app 的 memory 文件
4. Memory 文件是纯 markdown，人类可读、可手动编辑
5. Memory 操作不阻塞或拖慢任务执行
6. 总 prompt 增量 <= 6KB

---

## 明确的非目标

V1 不包含：

- embeddings / vector search / SQLite / FTS
- reflect / synthesis bank / 自动归纳
- 自动写入 `app_skills`
- LLM 全文件重写
- confidence level 或 evidence metadata
- 后台 LLM retain 调用（failure auto-retain 不算——它不调用 LLM）
- 跨 app 搜索 / 广义检索
- dedup / merge 逻辑
- memory 的 UI 展示或编辑界面

---

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| LLM 不调用 `remember_experience` | auto-retain hook 兜底 failure；prompt 指令强调；V2 可加后台 retain |
| goal 解析误匹配 app | 假阳性只是多加载一些 memory，不造成错误行为 |
| memory 文件膨胀 | entry cap 硬限制；每文件最多 30 条 |
| 低质量条目积累 | kind 标签帮助人工审阅；V2 可加 LLM-based 清理 |
| 文件 I/O 阻塞主线程 | MemoryStore 读写应在 `Dispatchers.IO` 上执行 |
| 收紧完成契约导致回归 | 纯文本完成路径保留为"继续执行"而非"任务完成"，不丢弃 LLM 输出 |
