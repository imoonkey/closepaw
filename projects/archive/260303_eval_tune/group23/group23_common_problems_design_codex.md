# Group2 + Group3 Common Problems & Design (Codex)

## 0. Scope / Evidence

- 分析对象：
  - `eval/results/20260226_215731`（group2）
  - `eval/results/20260227_002312`（group3）
- 证据来源（独立于 `*_claude.md`）：
  - `per_task.jsonl`、`summary.json`
  - 失败任务 trace：`trace/derived/steps.jsonl` + `tool_call_args/*`
  - 判分链路代码：`eval/aw_bridge/runner_execution.py`、`eval/aw_bridge/trace_parser.py`
  - AndroidWorld IR 判分：`.reference/eval/android_world/android_world/task_evals/information_retrieval/*`

关键指标：
- group2 scripted success rate = `0.70`（14/20）
- group3 scripted success rate = `0.619`（13/21，含 1 个 infra 任务）
- 共 12 个 scripted failure（不含 infra），其中 10 个是 `MaxTurnsReached`

---

## 1. Common Problems（Group2 + Group3）

### P1. Repeated action loop / 无进展循环（主问题）

表现：
- 10/12 失败是 `MaxTurnsReached`，大量任务“工具都 success，但目标不完成”。
- 典型：
  - `MarkorCreateFolder`：`mobile_action` 27 次，最长同签名连发 11 次。
  - `RetroSavePlaylist`：`mobile_action` 28 次，最长同签名连发 15 次。
  - `TasksHighPriorityTasks`：`mobile_action` + `system_button(back)` 反复切换 30 turns。

根因：
- 当前 loop 检测只提供 warning，不强制策略切换。
- 模型在长历史下继续沿用同一路径（即使 warning 已注入）。

---

### P2. “模型宣告成功”与 scripted success 不一致

表现：
- `GoalAchieved` 但 scripted fail（group2/group3 都出现）：
  - `MarkorAddNoteHeader`：agent 回答成功，但 scripted=0。
  - `SportsTrackerActivitiesOnDate`：agent 回答了结果，但 scripted=0。

根因（两类）：
- 非 Q&A 任务：判分看最终设备状态（文件名/内容精确匹配），不是看 `answer` 文案。
- Q&A 任务：判分字段可能和模型读取字段不同（例：SportsTracker 期望 `category`，模型答了活动 `name`）。

---

### P3. shell 使用越界（尤其 Markor 相关任务）

表现：
- 高失败样本里 shell 占比很高：
  - `MarkorMergeNotes`：shell 17 次
  - `MarkorTranscribeReceipt`：shell 10 次
  - `MarkorEditNote`：shell 9 次
- 出现大量“文件系统探测式”命令，偏离 UI 主路径。

根因：
- prompt 中虽然写了“UI 优先”，但 shell 仍被描述为常规可用通道。
- 缺少 runtime 级 shell budget/去重/熔断约束。

---

### P4. 视觉任务在 accessibility-only 跑，导致信息缺失

表现：
- `MarkorTranscribeReceipt` 的 trace meta 显示：`screenshot_input=false`。
- 该任务需要读 `receipt.png` 内容；accessibility tree 本身不包含图片文字。

根因：
- eval 默认 `perception_mode: accessibility_only`。
- `task_overrides` 未覆盖 `MarkorTranscribeReceipt`。

---

### P5. 历史上下文膨胀，强化“惯性策略”

表现：
- loop 后期 `llm_request` 的 `history_items/input_items` 很高（如 `TasksHighPriorityTasks` 到 80+）。
- 模型持续重复既有失败路径，难以“重置思路”。

根因：
- 当前 history 压缩策略对“失败动作序列”保留过多，缺少“失败摘要替换”机制。

---

## 2. 回答你明确提出的 4 个点

### 2.1 Q&A 的 `complete_task.answer` 是否和 scripted judgment 对齐？

结论：**链路本身是对齐的，但不保证答了就得分。**

- `runner_execution.py`：如果 trace 里有 `complete_task.answer`，会写入 `env.interaction_cache` 后再 `task.is_successful(env)`。
- IR 任务（Q&A）在 `information_retrieval.py` 里通过 `proto_utils.check_agent_answer` 判分。
- 所以：
  - `answer` 会传到 evaluator；
  - 但若答案字段错/格式错/数量错，仍会 0 分。

额外观察：
- 也存在 `MaxTurnsReached` 但 scripted 成功（如 `ExpenseDeleteMultiple2`），说明 scripted 判分最终看任务状态，不依赖模型“宣告完成”。

---

### 2.2 shell 乱用问题

结论：**属实，且是共性失败放大器。**

建议从“仅提示”升级为“提示 + runtime 硬约束”。

---

### 2.3 需要 vision 的任务改 Hybrid override

结论：**应做，且应至少覆盖 `MarkorTranscribeReceipt`。**

建议在 `eval/config/default.yaml` 的 `task_overrides` 增加：
- `MarkorTranscribeReceipt: { perception_mode: hybrid }`

可进一步考虑前缀覆盖：
- `MarkorTranscribe: { perception_mode: hybrid }`

---

### 2.4 repeated action loop 借鉴 reference mobile agents

建议借鉴两类“硬机制”而非只借鉴提示语：
- MobileAgent-v3：post-action reflector（A=成功 / B=跑偏 / C=无变化），失败进入修正路径。
- minitap：subgoal failure -> 强制 replan gate，不继续原路径硬刷。

---

## 3. Design Proposal

## 3.1 Design Goals

- 降低 MaxTurnsReached 失败率（优先）。
- 降低 shell 误用导致的偏航。
- 让视觉任务配置自动正确。
- 保持通用性，避免只修单任务。

---

## 3.2 Proposal A: Eval-Alignment Contract（Q&A + Completion）

1. 在 agent prompt 增加 Q&A 任务的硬性 completion 规则：
- 若目标是“问答型”（问 What/How many/quantity/title only），必须在拿到答案后 1-2 turn 内 `complete_task`。
- `answer` 必须严格贴合 goal 格式（例如 `titles only`、`comma separated`、`single number`）。

2. 在 prompt 中加入“字段对齐提醒”：
- SportsTracker 类任务强调回答 `activity type/category`，不是活动名称。

3. 在后处理策略中增加轻量 guard（可选）：
- 当 `complete_task(status=success)` 的 answer 明显违背格式约束时，降级为继续执行并提示修正。

---

## 3.3 Proposal B: Shell Governance（Prompt + Policy 双层）

1. Prompt 约束升级（`StandaloneAgentDef.kt`）：
- shell 默认禁用，只有满足以下任一条件才允许：
  - 任务显式是文件系统/系统状态查询；
  - UI 无法读取且已做过至少一次 UI 尝试（要在 thought 中说明原因）。
- 禁止“目录探测式连发”（`find/ls` 扫描型命令）超过 2 次。

2. Runtime 约束（`TurnToolPolicy.kt` 或新 policy）：
- shell budget：每任务上限（例如 3 次），超限后直接 drop shell tool call 并注入 warning。
- shell 去重：同命令或等价命令重复两次且无新信息 -> 阻断。
- shell 域白名单：仅允许读命令（`cat`, `ls`, `grep`, `find`），默认禁写。

---

## 3.4 Proposal C: Vision Override for OCR-like Tasks

在 `eval/config/default.yaml` 增加：

```yaml
bridge:
  task_overrides:
    MarkorTranscribeReceipt: { perception_mode: hybrid }
```

可扩展策略（推荐）：
- 对任务名包含 `Transcribe` / `FromGallery` / `Receipt` 的任务统一 hybrid（通过前缀或显式列表）。

---

## 3.5 Proposal D: Anti-Loop Hard Breaker（借鉴 MobileAgent/minitap）

从“warning-only”升级为“状态机 + 强制分流”：

1. 新增 action outcome 分类（轻量版 reflector）：
- `PROGRESS`：页面/目标状态有推进
- `NO_CHANGE`：动作成功但无可见推进
- `WRONG_PAGE`：进入与子目标无关页面

2. 失败计数触发器：
- 连续 2 次 `NO_CHANGE/WRONG_PAGE`：禁止重复最近 action signature，强制尝试替代策略。
- 连续 4 次：触发 `REPLAN_REQUIRED`（插入强警告并清理近历史为失败摘要）。
- 连续 6 次：允许 `complete_task(status="failure")` 早停，避免刷满 30 turns。

3. 失败动作黑名单（短窗）：
- 最近 N 步失败 action signature 不得重复（除非屏幕签名变化超过阈值）。

4. history 收敛：
- 进入循环后，将最近失败链压缩成 3-5 行“失败摘要”，减少 80+ history items 的惯性污染。

---

## 3.6 Proposal E: Better Eval/Trace Observability

1. 在 trace 里显式记录：
- 当前 perception mode（accessibility/hybrid）
- loop detector 命中类型与计数
- shell budget 状态

2. 在 per-task 分析脚本补充字段：
- `max_same_action_streak`
- `shell_calls`
- `loop_warnings_count`

这样 group 复盘会更快定位“为什么 MaxTurns”。

---

## 4. Implementation Plan（建议顺序）

1. 配置先行（低风险高收益）
- `eval/config/default.yaml`：加 `MarkorTranscribeReceipt -> hybrid`

2. Prompt 收敛
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`

3. Policy 硬约束
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt`
- 新增 `ShellUsagePolicy` / `ProgressStallPolicy`（或并入现有 loop policy）

4. Loop breaker
- `LoopDetectionPolicy.kt` + `AgentTurnRunner.kt` + `PromptBuilder.kt`（注入强制性恢复指令）

5. 验证
- 先跑 group2/group3 子集，再跑 smoke/core。

---

## 5. Verification Plan

最小验证集（直接对应本次共性问题）：
- `MarkorTranscribeReceipt`（vision）
- `MarkorMergeNotes`（shell overuse + loop）
- `TasksHighPriorityTasks`（repeated loop）
- `SimpleCalendarEventsInNextWeek`（scroll loop）
- `SportsTrackerActivitiesOnDate`（Q&A 字段对齐）

验收指标：
- Group2+3 scripted success rate 提升（目标 +10pp 起步）。
- MaxTurnsReached 失败数显著下降。
- shell 调用次数在失败任务中下降（尤其 Markor 系列）。

