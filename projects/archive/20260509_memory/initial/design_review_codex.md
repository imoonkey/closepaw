status: draft

# Claude 设计方案交叉评审 — Codex 视角

日期：2026-03-10

评审对象：`design_claude.md`
对比基准：`design_codex.md`（本方评审者的设计）

---

## 一、正确性验证

逐一核实 Claude 设计中对代码库的引用。

### 1.1 Turn.kt isComplete 逻辑 — 正确

Claude 文档引用：
```kotlin
val isComplete = completeTaskCall != null ||
    (toolCalls.isEmpty() && effectiveTextContent != null && !hasMalformedKnownToolMarker)
```

与 `Turn.kt:204-208` 完全一致。纯文本完成 fallback 确实存在。

### 1.2 PromptBuilder 注入顺序 — 正确

Claude 描述的 `history → working memory → [recalled memory] → app skill → observation` 顺序与 `PromptBuilder.buildInputItems()` 的实际结构吻合。当前代码是 `history → memory(working) → appSkill → observation`，在 working memory 和 appSkill 之间插入 recalled memory 是合理的。

### 1.3 SessionServices 接线方式 — 正确

Claude 建议将 `MemoryStore` 在 `SessionServices.create()` 中实例化，并在拿到 `toolRegistry` 后单独注册工具。这比通过 `SessionToolingBootstrapper` 注入更干净——因为 `SessionToolingBootstrapper.create()` 目前只接受 `approvalMode` 一个参数，不依赖外部状态。Claude 对这一点的判断是正确的。

### 1.4 ToolSpec 模式 — 正确

Claude 参考 `ScratchpadTool` 的模式设计 `RememberExperienceTool`，这是正确的参考。`ScratchpadTool` 接受 `ScratchpadState` 作为构造参数，`RememberExperienceTool` 接受 `MemoryStore` 完全同理。

### 1.5 AppAliases.PACKAGE_MAP 引用 — 未引用但相关

Claude 设计不涉及 goal-based recall，所以没有引用 `AppAliases`。但如果考虑将来扩展，需要注意 `PACKAGE_MAP` 目前只覆盖 13 个 app，而 `app_skills/` 目录有 16 个 app。关键 eval app 如 OsmAnd (`net.osmand`)、Tasks.org (`org.tasks`)、OpenTracks (`de.dennisguse.opentracks`)、Broccoli、RetroMusic、SimpleGallery、VLC 均不在 `PACKAGE_MAP` 中。

### 1.6 多工具同 turn — 正确

Claude 声称系统已支持多工具同 turn（`ToolArbitrationResult.selectedToolCalls` 是列表），并认为 LLM 可以在 `complete_task` 前同 turn 调用 `remember_experience`。经核实 `TurnToolPolicy` 确实支持这一点。

**小结：没有发现技术错误。Claude 对代码库的理解准确。**

---

## 二、缺陷分析

### 2.1 Turn-1 Gap — 最大遗漏

Claude 明确将 turn-1 gap 归为"V1 可接受"，计划 Phase 2 解决。

我不同意。turn-1 恰恰是最需要记忆的时刻。agent 上次在 OsmAnd 踩过"必须用 Address tab 搜索"的坑，如果 turn 1 不知道，可能又浪费 2-3 个 turn 去走错误路径。

**成本分析：** 从 goal 文本提取目标 app 只需 ~20 行代码，复用 `AppAliases.PACKAGE_MAP`（加上 `app_skills/` 目录的包名匹配作为兜底）。假阳性无害——多加载几百字 context 不会导致错误行为。

**但必须诚实指出我自己设计的一个问题：** `PACKAGE_MAP` 覆盖率不足。当前 map 没有 OsmAnd、Tasks.org、OpenTracks 等关键 eval app。如果要做 goal-aware bootstrap，需要同时扩展 `PACKAGE_MAP`，或者用 `app_skills/` 目录下已有的包名做反向匹配（从包名中提取短名称）。这让"~20 行"的估计偏乐观——实际需要 30-40 行加上 map 扩展。

### 2.2 Failure 自动保存 — 被遗漏的高价值场景

Claude 设计完全依赖 LLM 主动调用 `remember_experience`。但从 autotune 经验看，LLM 遵循 prompt 指令的比率约 70-80%。V1 恰恰是数据收集最宝贵的时期——如果 20-30% 的经验丢失，后续就没有足够数据评估 memory 系统的价值。

我的设计中提出了一个极轻量的 failure auto-retain hook：在 `complete_task(status="failure")` 后，如果本次 session 没调用过 `remember_experience`，自动从 failure answer 截取一条 `[pitfall]` 保存。不需要额外 LLM 调用，约 10 行代码。

Claude 把这个放到了 Phase 2，但 Phase 2 的问题是：没有 V1 的数据，你无法判断是否需要 Phase 2。

### 2.3 收紧完成契约 — 作为开放问题提出，但应明确推荐

Claude 在第十三节问了"是否收紧完成契约"，推荐收紧。我同意推荐，但认为应该直接纳入 V1 scope 而非作为开放问题。理由：

1. 纯文本完成是 agent 行为异常的信号，不应视为正常完成
2. 改动量极小——一行代码
3. 对 retain 可靠性有直接帮助
4. 对 eval/trace 分析也有独立价值

### 2.4 PlannerAgentDef / ExecutorAgentDef 覆盖 — 提出了但没给方案

Claude 在开放问题中问了"PlannerAgentDef / ExecutorAgentDef 是否也加 memory"。当前 eval 主要跑 Standalone 模式，这个问题 V1 可以不解。但 Claude 没有给出明确的"不做"决策，留了个尾巴。建议明确声明 V1 只覆盖 StandaloneAgentDef。

---

## 三、设计权衡分析

### 3.1 条目格式：纯时间戳 vs kind 标签

**Claude 选择：** 只有时间戳，理由是"<=30 条 LLM 全读，kind 不改变实际阅读量"。

**我的看法：** 推理在文件层面成立，但忽略了 kind 作为 prompt engineering 手段的价值。当 LLM 读到 `[pitfall]` 前缀会自动提高警觉，读到 `[workflow]` 会尝试复用流程。成本为零——只是正文多几个字符，`MemoryStore` 完全不需要感知这些标签。

不过 Claude 的反论也有道理："如果后续需要 kind，可以直接在正文前加 `[kind]` 前缀，无需改格式"。这意味着 kind 标签不是架构决策，只是 prompt 指令决策——后续随时可加。

**结论：** 这是一个低风险的设计选择。Claude 的方案可以工作，但我仍然建议 V1 就加 kind 标签——成本为零，收益为正。

### 3.2 预算分配：均分 vs 弹性

**Claude 选择：** 每文件 2KB 截断，总量 <= 6KB。

**我的选择：** user_prefs 1.5KB + device 1KB + app memory 拿剩余（最多 3.5KB），弹性分配。

弹性分配的优势：app memory 通常价值最高（直接影响当前操作），给它更多预算合理。均分会导致 app memory 和 device info 拿到同样的空间，但 device info 很少有 10 条以上。

**不过 Claude 方案的简洁性更好：** 不需要按优先级排列截断逻辑。对 V1 来说两者差异不大——因为大多数文件不会接近 2KB。

### 3.3 截断方向

Claude 没有明确声明截断方向。我的设计明确为"从后往前保留，最新优先"。这是一个需要明确的实现细节——不声明的话实现者可能从前截断（保留最旧条目），这与"最新经验最有价值"的直觉相悖。

### 3.4 内容长度限制

Claude 限制 500 字符，我限制 200 字符。

我的 200 字符限制偏紧——1-2 句英文可以工作，但中文或复杂技术描述可能不够。Claude 的 500 字符更安全。但 500 字符 x 30 条 = 15KB 理论上限，超过了 2KB 的 recall 截断预算。这意味着大量条目会被截断掉——虽然 entry cap 和 recall 截断是两层独立机制，但不匹配的数字可能让人困惑。

**建议：** 300 字符是合理的折中。

---

## 四、Claude 设计的优势（相对于 Codex 设计）

### 4.1 更详细的 wiring 方案

Claude 对每个修改点（PromptBuilder、TurnPlanningPhaseRunner、SessionServices、StandaloneAgentDef）都给出了具体的代码级方案。特别是对"为什么在 SessionServices 层注册工具而非 SessionToolingBootstrapper"的分析很精准——它正确识别了 `SessionToolingBootstrapper` 目前不依赖外部状态的设计意图。

### 4.2 更清晰的边界情况分析

第九节的表格覆盖了 8 个场景，每个都有明确的"行为"描述。特别是"磁盘空间不足 → 静默失败不阻塞任务"和"多 session 并发写入 → V1 不处理"这两个，我的设计虽然在风险表中提到了文件 I/O 阻塞，但没有像 Claude 这样系统地列举。

### 4.3 开放问题的价值

第十三节的三个开放问题虽然我批评它们"没给方案"，但作为设计文档的沟通手段，它们是有效的——明确标注了需要决策者输入的点，而不是自行假设。

### 4.4 "纯增量设计"的定位

Claude 在文档开头就明确声明"不替代 session history、scratchpad、todos、app skills 中的任何一个"，并用简洁的三层比喻（说明书 vs 使用心得 vs 当前草稿）阐述了定位。这个 framing 比我的"与现有系统的关系"更锐利。

---

## 五、综合评价

| 维度 | Claude 设计 | 评价 |
|---|---|---|
| 代码库理解 | 准确，无技术错误 | 优 |
| 架构简洁性 | 3 新文件 ~250 行，改动面小 | 优 |
| Turn-1 Gap | 显式推迟到 Phase 2 | 缺——成本低收益高，应 V1 做 |
| Failure 自动保存 | 不做 | 缺——V1 数据收集期不应放弃 |
| 完成契约收紧 | 推荐但未纳入 scope | 中——应明确纳入 |
| 条目 kind | 不加 | 可接受——后续可无痛添加 |
| 预算分配 | 均分 2KB | 可接受——V1 差异不大 |
| 截断方向 | 未明确 | 缺——实现时需补充 |
| 文档清晰度 | 非常好 | 优 |

**总结：** Claude 设计是一个正确且可实现的方案。核心架构（MemoryStore + MemoryRecaller + RememberExperienceTool，三文件扁平结构）与我的设计一致。主要分歧在三个"做不做"的决策上：turn-1 goal-aware recall、failure auto-retain、收紧完成契约。我认为这三个应纳入 V1，因为它们的实现成本都很低（合计约 50 行代码），而推迟到 Phase 2 会丢失 V1 数据收集期的宝贵信息。

---

## 六、合并建议

如果合并两个设计，推荐以 Claude 设计为骨架（因为 wiring 细节更完整），增加以下来自 Codex 设计的内容：

1. **Turn-1 goal-aware recall** — 但需同时扩展 `AppAliases.PACKAGE_MAP` 以覆盖 eval app（OsmAnd、Tasks.org、OpenTracks 等）
2. **Failure auto-retain hook** — 在 `complete_task(status="failure")` 后自动保存
3. **收紧完成契约** — 从开放问题升级为 V1 scope
4. **kind 标签** — 作为 prompt 指令加入，不改变 MemoryStore 代码
5. **明确截断方向** — 最新优先
6. **弹性预算分配** — 可选，V1 差异不大
