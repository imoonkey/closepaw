# OpenClaw 借鉴点：对齐后的优先级与实施路线图

## 设计原则

两份分析都认同的两个元结论：

1. **声明式能力（Declarative capability）**：设备 / 工具声明自己能做什么，系统据此适配，而不是由中心层拍脑袋假设。
2. **状态外置化（State externalization）**：prompt、memory、session、tool policy 都是数据，而不是硬编码逻辑。它们应在运行时可配置、可替换。

---

## 当前代码库现实

在排优先级之前，先承认已经存在的基础：

| 领域 | 已有内容 | 缺失内容 |
|------|----------|----------|
| **风险等级** | `RiskLevel` enum（LOW/MEDIUM/HIGH）、带 SMART 模式的 `PolicyEngine`、`DEFAULT_RISK_LEVELS` 中的 per-tool 默认值、`MobileActionName.defaultRiskLevel` 提供的 per-action 风险 | 风险等级是写死在 companion object 里的，不是数据驱动；没有入口来源的信任维度 |
| **Session 持久化** | `SessionCheckpointCoordinator`、`SessionStorage`、`AgentSession.reload()`、hot-idle resume、`SessionHistoryManager` + `SessionRecordingService` | Session 还不是用户可感知的产品对象；没有按用户意愿浏览、恢复，也没有跨入口 identity |
| **Prompt 架构** | System prompt 位于 `AgentDef.systemPrompt`（`StandaloneAgentDef` / `PlannerAgentDef` / `ExecutorAgentDef` 中的硬编码字符串）；app skills 已经外置到 `assets/app_skills/<package>/SKILL.md` | 修改 prompt 仍需要改代码 + rebuild；identity / rules / tools guidance 还没有结构化分层 |
| **工具注册** | `ToolRegistry` 是运行时可变 map；`SessionToolingBootstrapper` 注册固定工具集；`ToolRegistry.createFilteredCopy()` 支持 per-session 过滤 | 没有运行时 availability check；无论平台 / 权限状态如何，LLM 看到的都是静态工具全集 |

---

## 统一优先级路线图

### P1：Session Capability Profile 与动态工具暴露

**问题：** LLM 能看到运行时可能不可用的工具，导致浪费 turn 和令人困惑的失败。

**现有基础：** `ToolRegistry` 已经可运行时变更。`createFilteredCopy(allowedNames, excludedNames)` 已经支持静态 session 级过滤。`AgentDef.allowedTools` 进一步按角色收紧。

**对齐方案：**

引入一个小型、session-scoped 的 `SessionCapabilityProfile`。

它只需要覆盖真正影响工具暴露和 prompt 宣告的运行时事实：
- platform mode
- 已授予权限 / service 健康状态
- 当前启用的工具名
- 对推理真正重要的关键 action constraints

这个对象刻意比“全能运行时合同”要小很多。Policy 继续独立存在。

为什么这个边界合理：
- 运行时真相由 session 层显式持有；
- 不会把 session / platform / config 逻辑推给每个 tool；
- 不会把 capability 和 policy 耦合成一个对象。

`ToolSpec.isAvailable(context)` 不是主设计。以后如果某些 truly tool-local checks 确实需要，可以加一层很薄的 hook，但 v1 不应该把每个工具都变成自己的 capability authority。

**具体变更：**
- 新增：`SessionCapabilityProfile`
- `SessionServices` 持有当前 capability profile
- `SessionToolingBootstrapper` 和 / 或 planning 过程按 profile 过滤可暴露工具
- `Turn` 在生成 schema 时只接收已启用工具
- `TurnPlanningPhaseRunner` 可在对推理有明显影响时注入简洁 capability summary

### P2：Policy Externalization

**问题：** 风险等级写死在 `PolicyEngine.DEFAULT_RISK_LEVELS` companion object 里。代码中的 TODO 也明确说明过未来要外置：*"Consider loading risk levels from configuration file for per-deployment customization."*

**现有基础：** 风险体系已经齐全：`RiskLevel` enum、`PolicyEngine.evaluateRiskLocked()`、`MobileActionName.defaultRiskLevel`、`setRiskLevel()` 自定义覆盖、allow / deny lists。

**设计：** 把静态 `DEFAULT_RISK_LEVELS` map 与 `MobileActionName.defaultRiskLevel` 移到数据文件（assets 里的 YAML 或 JSON）。`PolicyEngine` 构造时加载。这个文件还能按 deployment 或 per-session config 覆盖。

**Stretch：** 增加 `entrySource` 维度（local / remote / voice），用它调制风险阈值。来自 remote source 的 HIGH-risk 工具必须始终要求确认。

**具体变更：**
- 新增：`assets/policy/risk_defaults.yaml`（tool → risk level mapping）
- 修改：`PolicyEngine` —— 从 asset 文件加载，不再依赖 companion object map
- 修改：`MobileActionName` —— 把 `defaultRiskLevel` 也外置到同一数据文件
- 可选：在 policy check context 中增加 `entrySource: EntrySource`

### P3：Persona 与 Prompt Asset Externalization

**问题：** System prompts 仍是 `StandaloneAgentDef.kt` 等文件里的硬编码字符串。想调整 agent 行为，必须改代码 + rebuild + reinstall。

**现有基础：** `AgentDef` 是一个抽象类，包含 `systemPrompt: String`、`allowedTools: Set<String>`、`requiresDelegationToolRegistration: Boolean`。`AgentDefRegistry` 按 role 解析 defs。App skills 已经是 asset-backed。

**对齐方案：**

分阶段做。

Phase 1：
- 先把 prompt 文本抽到 `assets/persona/<role>/system_prompt.md`
- tool allowlists 与 delegation config 暂时仍保留在代码中

Phase 2：
- 在 prompt 旁边增加一个轻量 manifest，用于 role metadata、allowed tools 与 delegation requirement

这样既保持 ownership layer 正确，也不会把 prompt 抽取变成一个远超需求的大重构。

**优先级说明：**

在架构路线图里，这项仍排在 P1 与 P2 之后。

如果评估 / 调 prompt 的速度已经成为当前瓶颈，可以战术性把 Phase 1 提前，但不改变总依赖关系。

**Phase 1 具体变更：**
- 新增：`assets/persona/standalone/system_prompt.md`、`planner/system_prompt.md`、`executor/system_prompt.md`
- 新增：`PersonaRepository`（接口 + asset 实现，模式可对标 `AppSkillRepository`）
- 修改：`AgentDef` 子类 —— 从 `PersonaRepository` 读取 `systemPrompt`，而不是内联字符串
- 修改：`SessionServices` —— 注入 `PersonaRepository`

### P4：跨 Session 经验 Memory

**问题：** Agent 的知识会随着 session 一起消失，无法从过去的 app 交互中学习。

**现有基础：** Session-scoped 的 `ScratchpadState`（20 条、3000 chars）和 `TodoState` 已存在，但会在 session 间清空。App skills 提供 per-package 的静态指导。

**设计：** 在 session-scoped scratchpad 旁边增加持久 memory 层。

**存储：** 按 app package 使用 markdown 文件：`data/memory/apps/<package>.md`。由 LLM 通过新的 `MemoryTool` 写入。在匹配 app 位于前台时，与 app skills 一起加载到 turn context。

**写入约束（来自 Codex review）：**
- 只存泛化经验，不存逐屏幕琐碎步骤
- 是否写入由 LLM 决定，但格式要结构化（fact + confidence + source session）
- 每个 app 限制最大文件大小（如 4KB），超出则驱逐最老条目
- 执行期间 memory 只读，写入在任务后发生

**检索：** 作为 “Relevant Experience” block 注入到 app skills 之后，并保持边界，避免 prompt 膨胀。

**为什么排在 P1-P3 之后：** 如果在不稳定的 tool / policy surface 上训练 memory，它会过拟合暂时性行为（Codex 的论点，已采纳）。

### P5：Session Workspace 提升为产品对象

**问题：** Session 目前只是基础设施，还不是用户可见的产品对象。

**现有基础：** 完整的 checkpoint / reload / hot-idle 基础设施已经存在，history 层也有 `SessionRecord`。

**还缺什么：** Session 需要成为一个可浏览、可恢复、跨入口复用 identity 的产品能力。这本质上是产品 / UI 项目，不是存储项目。在 web、voice 等多入口更接近落地前，先不做细设计。

### P6：Voice Input

**方向：** 使用 Android `SpeechRecognizer` API。在 Smart Capsule overlay 中做 push-to-talk。STT 结果通过 `Op.UserInput` 喂给 `SessionCoordinator`。零外部依赖。它不直接提高任务成功率，但能明显提升无障碍性与 hands-free UX。

### P7：Onboarding Wizard

**方向：** 做成一步步的 funnel：accessibility permission → overlay permission → battery optimization → LLM API key validation → demo task。不是简单的设置页，而是顺序式引导。对首轮成功率很有价值。

### P8：Remote Entry Points 的安全配对

**方向：** 建立在 P2 policy work 和 P5 session / workspace identity 之上。它主要在 remote 或多入口控制面出现后才重要。来自 remote source 的 HIGH-risk actions 必须始终要求设备端确认。

### P9：Rich Message / Canvas Host

**方向：** 用原生 Compose 扩展 chat message types，支持 `Choice`、`Confirmation`、`Summary`。只有当 rich messages 被证明确实不够用时，才再考虑 WebView canvas。明确放在最后，先把 agent core 做强，再改展示层。

---

## 依赖图

```text
P1 (capability profile) ──→ P2 (policy extern) ──→ P5 (session workspace) ──→ P8 (security pairing)
          │                           │                    │
          └──────────────→ P3 (persona assets)            └────────────→ remote/web surfaces
                                   │
                                   └──────────────→ P4 (memory)

P6 (voice)     P7 (onboarding)     P9 (rich messages)
   [independent]  [independent]       [independent]
```

P1、P6 和 P7 可以独立启动。P2 依赖 P1 更受益。P3 可在 P1 之后开始；如果 prompt 调优速度非常急迫，也可稍作提前。P4 则明显受益于 P1 + P2 带来的稳定面。

---

## Trade-offs 与理由

| 决策 | 选中的方案 | 备选方案 | 原因 |
|----------|--------|-------------|-----|
| Availability 边界 | 小型 `SessionCapabilityProfile` | `ToolSpec.isAvailable(context)` 或完整 runtime contract | 既保持 runtime truth 显式，又避免把 session 逻辑复制进工具里 |
| Prompt 抽取范围 | Phase 1 只抽 prompt 文本，Phase 2 再加 manifest | 立刻把整个 `AgentDef` 都资产化 | 增量推进。先拿到 prompt 迭代速度，再处理 persona metadata |
| 风险等级工作 | 外置现有基础设施 | 从零重建 | 风险体系已经有了，缺的是数据 ownership，不是 enum / 逻辑 |
| Memory 时机 | 放到 P1-P3 之后 | 作为 Tier 2 并行推进 | 不稳定的工具面会让 memory 过拟合 |
| Session 持久化 | 优先级下调（因为基础已存在） | 提升为 Tier 2 | 基础设施基本完成了，剩下的是产品 / UI 工作 |

---

## 开放问题

1. **Memory 写入时机：** memory 应该在任务执行中写（风险是保存了不完整经验），还是只在任务后写（更安全，但会漏掉中途 insight）？需要实验。
2. **Prompt 时机：战术 vs 架构：** 如果在 P1 完成前，prompt 迭代速度就已经成为瓶颈，是否值得把 Phase 1 prompt extraction 提前？当前答案是：可以，但它仍然应视为 P3 的一部分，而不是改变整个路线排序。
