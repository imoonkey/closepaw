# Group4 Round1 Design (Codex, Independent)

## Scope and Inputs

- Run: `eval/results/20260227_161048`
- Primary reference: `doc/todo/0.01_eval_tune/group4/eval_analysis_20260227_161048/group4_summary_claude.md`
- Deep dive references: `per_task/*.md` + selected traces under `eval/results/20260227_161048/artifacts/*/trace`
- Note: request中给了两次同一路径 `group4_summary_claude.md`，本设计按该单一 summary + trace 证据展开。

## Common Problems (Evidence-Based)

### 1) Anti-loop 误杀是第一大系统性问题

证据：
- 本次共有 `7` 个 `complete_task_forced_*`（loop escalation 强制失败）。
- `RetroPlayingQueue` 中，turn 6/7/8 的 `click element_index=10` 被连续 `POLICY_REJECTION`，但语义上是在处理不同歌曲（trace: `llm_tool_calls/95,105,115`）。
- `RecipeDeleteMultipleRecipesWithNoise` 在 turn 20 已完成三道菜删除，turn 21-22 做 verification，turn 23 被强制 failure（trace: `.../tool_call_args/379_turn_23_complete_task_forced...json`）。

根因（独立判断）：
- loop escalation 依赖过粗粒度 action signature（尤其是 `element_index`），对“同 UI 模板下不同语义目标”的区分不足。
- `NavigationState.toSignature()` 当前取前 32 个元素 token，容易漏掉真正变化的语义位点（如列表中部被选中项）。

### 2) Calendar 在 a11y-only 下存在结构性感知/执行错配

证据：
- `SimpleCalendarAnyEventsOnDate` 的月视图 a11y tree 中存在 `42` 个 `class=View` 且 `text=""`、`clickable=true` 的日期格（trace sanitized tree 统计）。
- `SimpleCalendarEventOnDateAtTime` 中，turn 6 执行 `type "27"` 到日期 EditText 后，观测仍是 `22`；turn 7 点 OK 后依旧在 `October 22 (Sun)`（trace: `llm_tool_calls/95` + `tool_observation_screen/102,119`）。

根因：
- NumberPicker 在该应用里“可 type 不等于值真正提交”，当前策略把它当通用可编辑框。
- 月历格无可读文本标签，模型无法从 a11y 树建立日期映射。

### 3) 目标验证不足导致 false success

证据：
- `MarkorDeleteNewestNote`、`MarkorMoveNote` 都是 `GoalAchieved` 但 `scripted_score=0`。
- `MarkorMoveNote` trace 中在可滚动列表里直接 long_press 第一个模糊匹配文件，未做 exact match。

根因：
- destructive 操作前的“目标唯一性/精确匹配”约束不够硬。
- `complete_task(success)` 前缺少面向任务约束的二次校验（不是动作完成就算目标完成）。

### 4) 能力路由缺失：vision-required 任务在 a11y-only 中消耗过多回合

证据：
- `RecipeAddMultipleRecipesFromImage`: 看到的是 `recipes.jpg` 文件名与查看器控件，几乎无图像语义文本。
- `MarkorTranscribeVideo`: a11y 主要是播放器控件（Play/Tracks/advanced options），无帧文本。

根因：
- 任务先验能力需求（image/video OCR）未与 perception_mode 对齐。
- fail-fast 触发不稳定，导致 10+ turn 无效探索。

### 5) Turn budget / strategy mismatch 仍明显

证据：
- `MaxTurnsReached`: `OsmAndMarker`, `OsmAndTrack`, `RetroPlaylistDuration`。
- 多 item 任务（添加/删除/核验）在 30 turn + loop policy 下容错太低。

根因：
- 缺少 upfront 回合预算估计与策略切换（single-item 循环 vs batched workflow）。

## Round1 Design

目标：优先“降误杀 + 降假成功 + 降无效回合”，不做大规模架构重写。

## P0: Anti-loop V2 (Progress-aware, Context-aware)

### P0.1 Enrich Action Signature

设计：
- `type` 签名加入 `input_text` 片段哈希（避免“同输入框不同内容”被当重复）。
- `click/type` 的 `element_index` 签名加入目标语义（从 snapshot 取 `text/desc/hintText/class`），而不仅是 index。

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/ActionSignature.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt`（签名生成传入 snapshot）

### P0.2 Strengthen Progress Signal

设计：
- `NavigationState` 新增“无进展连续计数”与“语义进展计数”（例如不同 query/item 名称被处理）。
- 屏幕签名采样从“前 32 元素”升级为“分层采样 + 状态位优先（selected/focused/checked）”。

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/NavigationState.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt`

### P0.3 Safe Escalation Gate

设计：
- FORCE_COMPLETE 触发需同时满足：
  - 连续 no-progress
  - 且最近 N turn 无语义新项
  - 且存在连续 policy rejection 或同类失败证据
- 对“验证阶段”降级为 BLOCK/ADVISORY，不直接 forced failure。

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`

## P1: Calendar & Picker Reliability

### P1.1 Prompt Correction (remove wrong heuristic)

设计：
- 删除/替换当前 prompt 中“NumberPicker 直接 type”的指导。
- 改为：优先对 NumberPicker 容器执行 scroll（up/down）并做 post-action value verification。
- Calendar QA 默认先 search，再日期导航。

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`

### P1.2 Picker-specific Execution Guard

设计：
- `TypeExecutor` 对 NumberPicker 场景加“输入后值未变化 -> 显式失败”判定，促使模型切换滚动策略。

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt`

## P1: False Success Guard (Destructive Ops)

### P1.3 Exact Match & Completion Guard

设计：
- 在 prompt 增加硬约束：文件名/对象名必须 exact match，不允许 substring 替代。
- `complete_task(success)` 前对 destructive 任务注入检查提示：
  - 目标名是否精确一致
  - 是否在最终界面或 shell 证据中可验证

改动点：
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`

## P2: Capability Routing & Eval Config

### P2.1 Hybrid overrides for vision-required tasks

设计：
- 给已知 image/video 阅读任务加 `perception_mode: hybrid` override。
- 至少覆盖：`MarkorTranscribeVideo`, `RecipeAddMultipleRecipesFromImage`（若任务名与基准一致）。

改动点：
- `eval/config/default.yaml`

### P2.2 Stable fail-fast policy

设计：
- 在 a11y-only 且检测到“目标依赖图像/视频文本”时，<=2 次尝试后明确失败，避免 10+ turn 消耗。

改动点：
- Prompt层先落地：`StandaloneAgentDef` capability rule

## Out of Scope (Round1)

- 不尝试在 round1 内彻底解决 OsmAnd 的 OpenGL a11y blindspot。
- 不引入新的 heavy OCR/tooling 管线（保留为 round2+）。

## Verification Plan

1. 执行同配置复跑 group4：
   - `eval/.venv/bin/python eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_group_4.txt`
2. 指标门槛：
   - `scripted_success_rate`: 0.35 -> 目标 >= 0.50
   - `goal_claim_precision`: 0.75 -> 目标 >= 0.90
   - forced completion 数：7 -> 目标 <= 3
3. 定向回归样例：
   - `RetroPlayingQueue`（验证 anti-loop 误杀修复）
   - `RecipeDeleteMultipleRecipesWithNoise`（验证 verification phase 不再被 forced fail）
   - `SimpleCalendarEventOnDateAtTime`（验证 NumberPicker 交互策略）
   - `MarkorMoveNote`（验证 exact match 防假成功）

## Expected Impact (Round1)

- Anti-loop 误杀显著下降，至少回收 `RetroPlayingQueue` + `RecipeDeleteMultipleRecipesWithNoise` 两个稳定增益点。
- Calendar 两题中至少 1 题从“错误策略循环”转为“可完成或快速失败”。
- false success 从 2 题降到 0-1 题。

