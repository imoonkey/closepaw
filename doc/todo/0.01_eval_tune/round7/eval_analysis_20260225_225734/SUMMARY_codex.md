# SUMMARY - Cog Tune (Codex)

## Scope
- eval run: `eval/results/20260225_225734`
- source tasks: `20` unique tasks, `24` total records (含 retry attempts)
- output per-task docs: `doc/todo/0.01_eval_tune/round7/eval_analysis_20260225_225734/per_task`
- 独立性: 本分析仅使用 run 证据（`per_task.jsonl`/`runner.log`/`trace`），未读取同目录 `*_claude.md`。

## Headline Metrics
- final scripted_success (by unique task): `6/20` = `30.0%`
- failures (by unique task): `14`
- run-level metrics (from summarize.py): scripted_success_rate=0.25, infra_failure_rate=0.25, goal_claim_precision=0.6

## Common Problems (From Single-Task Analyses)
- Evaluation gap: 9 failing tasks
- Reasoning: 6 failing tasks
- Observation: 6 failing tasks
- Execution: 3 failing tasks
- Context: 1 failing tasks
- Perception: 1 failing tasks

### Observed Patterns
- Pattern A: Trace 完整性问题频繁（无 trace、只到 llm_request、只到 tool_call）。这会掩盖真实 cognition 问题并降低可调优性。
- Pattern B: Calendar/Expense 中存在 `GoalAchieved` 但 scripted 失败，说明完成判定与 scorer 契约未对齐。
- Pattern C: 多个 Calendar task 在初始化阶段就 infra_failure（DB 不存在/初始化重复调用），属于 harness 侧阻塞。
- Pattern D: 长任务（30 turns）常见“策略循环”，典型表现为高频点击/滑动/shell 探测但未进入收敛路径。

## Prioritized Recommendations
### P0 (先修评测可用性)
- 修复 eval/harness 的 Calendar 初始化幂等与 DB reset 逻辑（`no such table`, `initialize_task() already called`）。
- 把 “0 turn / missing trace / trace 未闭环” 标记为 infra_failure 或 instrumentation_failure，并自动重试。
- 在 bridge 结束前强制 trace flush，避免 scoring 有结果但无完整 turn 证据。

### P1 (修 cognition 主链路)
- 加强 `complete_task` 前自检：针对 Calendar/Expense 任务校验关键字段是否满足 scorer 契约。
- 对图片/文件抽取任务加“模板化执行路径 + 回合预算门控”，减少 shell/滑动循环。
- 对文本归一化加规则：将 `Reimbursable` 等标签词与 note 字段分离。

### P2 (提升鲁棒性)
- Provider 错误（如 SSE provider error）加入模型回退与单任务重试。
- 对复杂控件（Calendar recurrence）添加子目标检查点，防止盲点按耗尽 30 turns。

## Next Verification Plan
- 先跑一轮仅修 harness 的 smoke（含 Calendar 相关任务）确认 infra_failure 显著下降。
- 再跑 `aw_subset_group_1` 对比：关注 `scripted_success_rate`、`goal_claim_precision`、`missing-trace ratio`。
- 最后挑选 2~3 个长任务做 step-by-step 回放，验证“预算门控 + 完成前自检”是否减少 MaxTurnsReached/False GoalAchieved。
