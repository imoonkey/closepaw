# Design Review: Claude vs Codex Prompt Architecture

> 评价标准：设计思路的合理性、系统性、实操性。不评价写作风格。

---

## 1. 整体评价

| 维度 | Claude (3篇) | Codex (1篇) |
|------|-------------|-------------|
| **问题分析深度** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **设计原则清晰度** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **代码落地细节** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **风险/边界考量** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Token预算意识** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

## 2. Claude 设计优势

### 2.1 Sequential Narrative 哲学
Claude 提出的 "storytelling" 隐喻（who I am → what happened → what I know → what I see）非常有说服力。这不仅是一个组织原则，更是对 LLM token-by-token 处理机制的深刻理解：
- **警告放在屏幕 JSON 之前**：让 LLM 在 "看到" 屏幕之前已被 prime，影响后续解读
- **Memory 在 Observation 之前**：先读 "我记得什么"，再看 "我现在看到什么"

这种设计思路比 Codex 更具认知科学基础。

### 2.2 实现细节极其具体
Claude 给出了：
- 具体的代码改动 diff
- 每个文件的预估改动行数
- 5个阶段的渐进迁移计划
- Definition of Done checklist

这种粒度让开发者可以直接开始写代码，几乎不需要额外 design 决策。

### 2.3 "Surgery, Not Rewrite" 原则
明确表态**不是重写**，而是精确手术。这反映了对现有代码尊重和风险意识。

---

## 3. Codex 设计优势

### 3.1 更严格的 Token 实证
Codex 直接引用了 debug-output 的真实数据：
- 单轮 user_context: 15-21KB
- 全 run llm_input_items: 368KB
- `Screen after action:` 出现 104 次

这种**基于实际数据的分析**比 Claude 的估算更有说服力。Claude 的 token 预算是推算（80 elements × 30 tokens），而 Codex 是实测。

### 3.2 Task 绑定约束
Codex 明确指出：
> Task 3 与 Task 4 必须绑定上线，不能拆开

这是关键的**实施依赖分析**。如果去掉 tool output 中的 screen summary（Task 3），但没有同时实现 screen window（Task 4），LLM 会丢失短期视觉记忆。Claude 没有显式指出这个风险。

### 3.3 Token Guard 机制
Codex 提出了**弹性 token 预算**的概念：
- 永远保留 `screen_0`（当前屏幕）
- `screen_1/2` 按预算裁剪，超预算时降级为 compact form

这比 Claude 的 "last 3 full, rest compressed" 更灵活、更健壮。

### 3.4 显式 memory 结构拆分
Codex 的 prompt shape 中 `memory` 包含：
- todos
- scratchpad keys
- **current_subgoal**

Claude 没有提及 current_subgoal，这在 planner-executor 架构中是重要上下文。

---

## 4. 设计分歧点

### 4.1 Screen State 存储位置

| 问题 | Claude | Codex |
|------|--------|-------|
| Screen 存入 history？ | ✅ 是，作为 `isScreenObservation=true` 的 Message | ❌ 否，screen_window 是当前轮附加输入，不写回 history |

**评价**：Codex 更保守、更清晰。Screen state 放入 history 意味着 HistoryManager 需要理解 screen 语义，增加耦合。Codex 的 ring buffer 方案更干净。

### 4.2 Reminder 策略

| Claude | Codex |
|--------|-------|
| 保留 loop + turn budget + final turn | 只保留 CRITICAL（强 loop + 最终 turn） |

**评价**：Codex 更激进，去掉了常规 turn budget warning。这是一个有争议的决定——executor 可能需要知道自己还剩几轮。但从 "minimal intervention" 角度看，Codex 逻辑自洽。

### 4.3 文档结构

| Claude | Codex |
|--------|-------|
| 3篇文档，职责清晰（设计 → 决策 → 实现） | 1篇文档，从现状到方案一体化 |

**评价**：Claude 更适合复杂项目的团队协作（设计/实现分离）。Codex 更适合快速迭代的个人项目。

---

## 5. 关键缺失

### Claude 缺失
1. **没有引用真实 trace 数据**——token 估算是理论值
2. **没有分析 Task 依赖关系**——如果分阶段实施不当可能导致 regression
3. **没有考虑 current_subgoal** 的 prompt 位置

### Codex 缺失
1. **代码细节不足**——没有给出具体的 class/function 签名
2. **迁移测试策略太简略**——只说 "快照测试"，没有具体方案
3. **没有明确 Turn.kt 的改动范围**

---

## 6. 综合结论

**Claude 更适合作为 Implementation Reference**：如果你想立刻开始写代码，Claude 的 03_implementation_plan 是可以直接执行的。

**Codex 更适合作为 Design Sanity Check**：它的 token 实证、Task 依赖分析、Token Guard 弹性机制都是 Claude 没有覆盖的重要考量。

### 建议的融合方案
1. 采用 Claude 的 **Sequential Narrative** 作为 prompt 组织原则
2. 采用 Codex 的 **ring buffer + token guard** 作为 screen window 实现
3. 采用 Codex 的 **Task 绑定约束**（3 和 4 必须同时上线）
4. 采用 Claude 的 **渐进迁移阶段**，但在 Phase 2/3 合并
5. 补充 Codex 提到的 **current_subgoal** 到 memory section

---

## 7. 评分总结

| 维度 | Claude | Codex | 备注 |
|------|--------|-------|------|
| 设计哲学 | 9/10 | 7/10 | Claude 的 narrative 概念更有深度 |
| 实操可落地 | 9/10 | 6/10 | Claude 代码细节完整 |
| 风险预见 | 7/10 | 9/10 | Codex 的 Task 依赖和 token 实证更强 |
| 系统性 | 8/10 | 8/10 | 两者都覆盖全貌 |
| **综合** | **8.3/10** | **7.5/10** | Claude 略胜，但 Codex 的 token 分析值得采纳 |
