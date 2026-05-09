status: draft

# Codex Memory 设计交叉评审 (Claude)

日期：2026-03-10

---

## 总体评价

Codex 设计在核心架构上与我的方案高度一致（markdown 存储、tool-based retain、per-app recall、3 个新文件），这说明独立推导收敛到了相似结论。差异集中在三个"升级提案"：Goal-Aware Bootstrap、`[kind]` 标签、Failure Auto-Retain。下面逐一审视。

---

## 一、正确性问题

### 1.1 Goal 解析复用 `AppAliases.PACKAGE_MAP` 的覆盖率被高估

Codex 声称 "复用已有的 `AppAliases.PACKAGE_MAP`"，成本 "~20 行代码"，能 "覆盖几乎所有 autotune 任务的 turn-1 recall"。

**实际验证结果：** `PACKAGE_MAP` 只有 25 个条目，全部是 Google 原生应用和少量第三方（Markor、Simple Calendar 等）。以下 eval 常用 app 完全缺失：

- `net.osmand` (OsmAnd)
- `org.tasks` (Tasks.org)
- `de.dennisguse.opentracks` (OpenTracks)
- `code.name.monkey.retromusic` (Retro Music)
- `com.flauschcode.broccoli` (Broccoli)
- `org.videolan.vlc` (VLC)
- `com.simplemobiletools.gallery.pro` (Simple Gallery)

这些 app 都有 app_skills 文件，说明是 eval 高频目标。**"几乎所有"的说法不成立。**

修正方案：如果要做 goal 解析，应该同时扫描 `app_skills/` 目录名（即已安装且有 skill 的 package）和设备已安装应用列表的 label，而不是只依赖硬编码 alias map。但这会把 "~20 行" 变成更复杂的实现。

### 1.2 Auto-Retain Hook 放在 "AgentExecutor 层面" -- 该类不存在

Codex 代码示例写的是：

```kotlin
// AgentExecutor 层面（非 CompleteTaskTool 内部）
if (status == "failure" && !sessionState.hasCalledRememberExperience) {
```

**实际代码库中不存在 `AgentExecutor` 类。** 主循环在 `Agent.kt`，turn 执行在 `AgentTurnRunner`，completion 结果在 `Agent.run()` 的 `TurnOutcome.Complete` 分支处理。hook 的正确放置点是 `Agent.run()` 循环结束后、`trace.sessionStopped()` 之前。

这不影响设计可行性，但说明 Codex 没有实际阅读主循环代码就写了接线方案。

### 1.3 `AgentSessionState` 加字段的侵入性被低估

Codex 提议在 `AgentSessionState` 加 `hasCalledRememberExperience` 标记。当前 `AgentSessionState` 是一个纯粹的 data class（6 行），只有 `todos` 和 `scratchpad`。加一个 memory 相关的 mutable 标记会破坏其不可变语义，也违反了"session state 只管 working memory"的职责边界。

更好的做法：让 `MemoryStore` 自己跟踪本 session 是否有过写入（一个内部 `AtomicBoolean`），不污染 `AgentSessionState`。

### 1.4 收紧 `isComplete` 的影响分析不够

Codex 建议删除纯文本完成路径，将 `isComplete` 改为 `completeTaskCall != null`。但当前代码中纯文本完成路径还承担了一个重要职责：**当 LLM 返回纯文本且没有 malformed tool marker 时，视为任务完成**。这是一个降级保护——如果 LLM 因某种原因无法生成 tool call（模型退化、格式问题），系统仍能优雅终止而非无限循环。

收紧后，这种情况会变成 `isComplete = false`，agent 继续下一个 turn。如果 LLM 持续返回纯文本，会烧完所有 turn。需要配套一个 "连续纯文本 N 次则强制终止" 的安全阀。Codex 的 "一行代码" 评估忽略了这个尾部风险。

---

## 二、遗漏（Gaps）

### 2.1 线程安全

Codex 的 `MemoryStore` 实现直接用 `File.readLines()` / `File.writeText()` / `File.appendText()`，没有任何同步机制。虽然 V1 是单 session，但 `remember_experience` 工具执行和 recall 读取可能在不同协程上并发。至少需要一个 `Mutex` 或者保证所有操作在同一 dispatcher 上串行化。

### 2.2 PromptBuilder 接口改动方案有遗漏

Codex 提议给 `buildInputItems()` 加 `recalledMemory: String?` 参数。但这意味着所有现有调用点都要改。当前 `PromptBuilder` 只在 `TurnPlanningPhaseRunner` 中使用（已确认），但还有测试文件 `PromptBuilderTest.kt` 也要更新。这不算大问题，但清单中没提。

我的方案也有同样的改动，此处只是指出 Codex 的文件改动清单不完整。

### 2.3 `Dispatchers.IO` 的实际使用位置未指明

Codex 在风险表中提到 "MemoryStore 读写应在 `Dispatchers.IO` 上执行"，但代码示例中没有体现 `withContext(Dispatchers.IO)`。具体是在 `MemoryStore` 内部加，还是由调用方负责？这需要明确。推荐在 `MemoryStore` 内部处理，让调用方无感知。

---

## 三、设计取舍评判

### 3.1 Goal-Aware Bootstrap：方向正确，但 V1 的价值/成本比不如描述的那么好

**赞同点：** Turn 1 确实是最需要记忆的时刻，Codex 对 "可接受的限制" 的挑战是有道理的。

**问题：** 实际覆盖率受限于 `PACKAGE_MAP` 的不完整（见 1.1）。要真正覆盖 eval 场景，需要额外的 app label 扫描逻辑，复杂度会超过 "~20 行"。而且 Turn 1 通常是 `open_app`，此时 app-specific memory 的价值确实有限——agent 还没进入 app 内部。

**结论：** 值得做，但不是 V1 必需。可以作为 V1.1 的快速跟进，此时已有真实 memory 数据可以评估 turn-1 recall 的实际价值。

### 3.2 `[kind]` 内联标签：务实的零成本实验

Codex 的论点有说服力："kind 不是给检索系统用的，是 prompt engineering"。`[pitfall]` 确实比裸文本多一个认知信号，而且不增加代码复杂度——它只存在于 LLM 的 prompt 指令和写入的文本中，`MemoryStore` 完全不感知。

**风险很低：** 最坏情况是没有效果，从 prompt 指令中删除即可。

**结论：** 赞同采纳。

### 3.3 Failure Auto-Retain：有价值，但实现细节需要修正

**赞同点：** LLM 70-80% 的 prompt 遵循率是真实观察，failure 场景下遗漏最可惜。从 `complete_task` 的 answer 字段截取失败原因是一个零成本的高价值兜底。

**问题：**

1. hook 放置点错误（见 1.2），需要重新定位到 `Agent.run()` 的 completion 路径
2. `hasCalledRememberExperience` 标记的放置方案不理想（见 1.3）
3. 截取 `answer.take(120)` 作为 pitfall 内容，质量依赖于 LLM 在 `complete_task` 中写的 answer 质量。如果 answer 是 "I was unable to complete the task"，保存的 memory 毫无价值

**改进建议：** 除了 answer，还应该包含 goal 的关键词摘要，例如 `[pitfall] Failed on "${goal.take(60)}": ${answer.take(80)}`。这样即使 answer 含糊，至少保留了失败的任务上下文。

**结论：** 赞同采纳，但需修正实现位置和内容模板。

### 3.4 弹性预算分配 vs 均分

Codex 的弹性分配（user_prefs 1.5KB、device 1KB、app memory 拿剩余）比我的均分 2KB 更合理。app memory 确实是价值最高的部分，不应该被固定的 2KB 上限卡住，特别是 user_prefs 和 device 早期很可能是空的。

**结论：** 赞同采纳。

### 3.5 截断方向明确化

Codex 明确了 "从后往前保留（最新优先）"，我的方案说了 "从文件末尾取" 但没给出实现。两者意图一致，Codex 更具体。

**结论：** 赞同。

---

## 四、值得采纳的 Novel Ideas

| 编号 | Idea | 采纳建议 |
|------|------|----------|
| N1 | `[kind]` 内联标签（workflow/pitfall/verification） | V1 采纳。零代码成本，可逆实验 |
| N2 | Failure Auto-Retain hook | V1 采纳，修正放置位置（`Agent.run()` completion 路径），用 `MemoryStore` 内部跟踪代替 `AgentSessionState` 标记 |
| N3 | 弹性预算分配 | V1 采纳，替代均分方案 |
| N4 | Goal-Aware Bootstrap | 推迟到 V1.1。先补全 app alias 覆盖率，或改用 app label 扫描 |
| N5 | 收紧 `isComplete` | 独立于 memory 系统评估。需配套连续纯文本安全阀 |

---

## 五、综合建议：合并方案

基于两份设计的交叉评审，推荐的 V1 方案：

1. **存储模型** — 两者一致，无争议
2. **Recall** — 用 Codex 的弹性预算分配，其余沿用我的方案（foreground-based recall）
3. **Retain** — `remember_experience` 工具（两者一致）+ Failure Auto-Retain hook（Codex 提案，修正实现位置）
4. **Prompt 指令** — 加入 `[kind]` 标签引导（Codex 提案）
5. **Goal-Aware Bootstrap** — 推迟到 V1.1
6. **收紧 `isComplete`** — 不绑定在 memory V1 中，独立评估

总新增代码量估计：~280 行，3 个新文件，5-6 处小改动。与两份设计的估计一致。
