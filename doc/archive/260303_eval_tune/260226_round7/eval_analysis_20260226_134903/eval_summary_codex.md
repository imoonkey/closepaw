# Eval Summary - Cog Tune (Codex)

## Scope
- eval run: `eval/results/20260226_134903`
- source tasks: `20` unique tasks, `20` total records
- output per-task docs: `doc/todo/0.01_eval_tune/round7/eval_analysis_20260226_134903/per_task`
- 独立性: 本分析仅使用 run 证据（`per_task.jsonl`/`summary.json`/`trace`），未读取同目录 `*_claude.md`。

## Headline Metrics
- final scripted_success: `16/20` = `80.0%`
- failures: `4`
- run-level metrics: scripted_success_rate=0.8, goal_claim_precision=0.9333333333333333, tool_failure_rate=0.007614213197969543, duration_p90_sec=303.5106887910515

## Common Problems (From Single-Task Analyses)
- Reasoning: 6 task-attempts
- Observation: 6 task-attempts
- Execution: 2 task-attempts
- Context: 1 task-attempts
- Evaluation gap: 1 task-attempts

### Observed Patterns
- Pattern A: `MaxTurnsReached` 出现于 5 / 20 tasks，主因是策略循环与收敛判定偏弱。
- Pattern B: `GoalAchieved` 但 scripted 失败出现于 1 task，说明完成判定与 scorer 契约存在错位。
- Pattern C: 高频 `scratchpad`（>=6 次/任务）出现在 1 tasks，挤占动作预算。
- Pattern D: `open_app` 找不到应用/别名问题出现在 1 tasks，影响启动阶段稳定性。

## Prioritized Recommendations
### P0 (先修成功率和判定一致性)
- 在 `complete_task` 前加入 task-family 自检（Calendar/Expense/Recorder 的关键字段与数量断言），避免 false GoalAchieved。
- 对 `MaxTurnsReached` 任务引入循环熔断：连续 N 次同类动作后必须切换策略或回退上一步。
- 对关键入口工具（`open_app`、高风险 `mobile_action`）增加重试+别名映射，降低启动期失败。

### P1 (优化 cognition 与上下文效率)
- 收紧 `scratchpad` 写入策略：仅在阶段切换写 checkpoint，减少每回合写入。
- 为多步任务提供模板化子目标（读取源数据 -> 切换目标 app -> 逐条录入 -> 复核），减少漫游式探索。
- 在 observation 层加入后置验证（包名、标题文本、列表计数变化），未达预期时立即纠偏。

### P2 (验证闭环)
- 先对失败四类任务重跑 smoke：Audio filename、BrowserDraw、ExpenseFromGallery、ExpenseFromMarkor。
- 再重跑 `aw_subset_group_1`，比较 scripted_success_rate、goal_claim_precision、MaxTurnsReached 占比。
- 抽样复盘 2 个边界成功任务（`MaxTurnsReached` 但 scripted success）验证完成判定是否提前收敛。

## Task Outcome Snapshot
| Task | scripted_success | completion_reason | turns | tool_failures |
|---|---:|---|---:|---:|
| AudioRecorderRecordAudio | 1 | GoalAchieved | 7 | 0 |
| AudioRecorderRecordAudioWithFileName | 0 | MaxTurnsReached | 30 | 0 |
| BrowserDraw | 0 | MaxTurnsReached | 30 | 0 |
| BrowserMaze | 1 | GoalAchieved | 17 | 0 |
| CameraTakeVideo | 1 | GoalAchieved | 7 | 0 |
| ClockStopWatchPausedVerify | 1 | GoalAchieved | 3 | 0 |
| ClockStopWatchRunning | 1 | GoalAchieved | 4 | 0 |
| ContactsNewContactDraft | 1 | GoalAchieved | 11 | 0 |
| ExpenseAddMultiple | 1 | GoalAchieved | 24 | 0 |
| ExpenseAddMultipleFromGallery | 0 | MaxTurnsReached | 30 | 2 |
| ExpenseAddMultipleFromMarkor | 0 | GoalAchieved | 17 | 0 |
| ExpenseDeleteDuplicates | 1 | GoalAchieved | 21 | 0 |
| SimpleCalendarAddOneEvent | 1 | GoalAchieved | 21 | 0 |
| SimpleCalendarAddOneEventInTwoWeeks | 1 | GoalAchieved | 22 | 0 |
| SimpleCalendarAddOneEventRelativeDay | 1 | MaxTurnsReached | 30 | 0 |
| SimpleCalendarAddOneEventTomorrow | 1 | GoalAchieved | 29 | 0 |
| SimpleCalendarAddRepeatingEvent | 1 | GoalAchieved | 20 | 0 |
| SimpleCalendarDeleteEvents | 1 | GoalAchieved | 13 | 0 |
| SimpleCalendarDeleteEventsOnRelativeDay | 1 | MaxTurnsReached | 30 | 1 |
| SimpleCalendarDeleteOneEvent | 1 | GoalAchieved | 18 | 0 |

