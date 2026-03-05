# Round7 Postfix Summary (Codex)

## 范围与证据

- 已分析：`doc/todo/0.01_eval_tune/round7/eval_analysis_20260225_183513/` 下 5 个任务分析文件（按你的要求，未读取 `round7_postfix_summary_claude.md`）。
- 对照基线：`doc/todo/0.01_eval_tune/round7/eval_analysis_20260225_162502/round7_summary_claude.md`。
- 补充证据：
  - `eval/results/20260225_162502/{summary.json,per_task.jsonl,runner.log}`
  - `eval/results/20260225_183513/{summary.json,per_task.jsonl,runner.log}`
  - `eval/analysis/compare_runs.py --base 162502 --new 183513`
  - 关键 trace/a11y 片段（Expense 的截断/非截断证据）

## 与 162502 的核心对比

| 指标 | 162502 | 183513 | 变化 |
|---|---:|---:|---:|
| scripted_success_rate | 0.4 | 0.4 | 0 |
| goal_claim_precision | 1.0 | 0.4 | -0.6 |
| tool_failure_rate | 0.0286 | 0.0 | -0.0286 |
| duration_p50_sec | 198.98 | 102.42 | -96.56 |
| duration_p90_sec | 226.93 | 230.09 | +3.16 |

结论：总通过率没有提升，但失败形态发生了显著变化：从“到不了完成态/MaxTurns”为主，变成“能 `GoalAchieved` 但评分仍 0”的语义/评测契约失配。

## 逐任务对比：之前的问题是否解决

| Task | 162502 主要问题 | 183513 现状 | 结论 |
|---|---|---|---|
| AudioRecorderRecordAudio | 23-turn 循环，MaxTurnsReached | 9 turns 即 `GoalAchieved`，但实际仍在录音中，score=0 | **未解决（失败模式转移）**：从循环变为误判完成 |
| ClockStopWatchRunning | 成功 | 成功 | **已稳定** |
| ContactsNewContactDraft | 成功 | 成功 | **已稳定** |
| ExpenseAddMultipleFromMarkor | Markor 文本截断、一直卡在读取阶段，未进入 Pro Expense | 能完整读文件并完成录入，但 note 写成 `Urgent. Reimbursable.`（期望 `Urgent`），且可能有 created_date 不匹配，score=0 | **部分解决**：感知瓶颈明显缓解，但语义映射/评测对齐未解决 |
| SimpleCalendarAddOneEvent | 30 turns 但差 2 步，MaxTurnsReached | 30 turns 内完成保存并 `GoalAchieved`，仍 score=0（expected row 含 UTC/source='imported-ics'） | **部分解决**：流程完成能力提升，但评测契约不对齐仍失败 |

## Common Problems（跨任务）

1. **完成判定与真实成功脱钩（False Completion）**
   - 在 183513 中，3 个失败任务全部是 `GoalAchieved + scripted_score=0`。
   - 说明当前 completion gate 对“任务真的完成且满足评测约束”的校验不够。

2. **任务语义抽取/规范化不足（尤其跨 App 数据搬运）**
   - Expense 中“Reimbursable”应被识别为筛选标签，而非 note 实体内容。
   - 这类“源文本包含业务标签”的结构化抽取没有被系统化处理。

3. **评测契约（DB 字段）未显式纳入执行闭环**
   - Calendar 期望行包含 UTC 时间戳与 `source='imported-ics'`；UI 正常操作并不保证这些字段匹配。
   - Agent 当前面向 UI 成功，不面向 scorer schema 成功。

4. **高成本子流程仍缺乏预算与替代策略**
   - Calendar 仍吃满 30 turns，虽然这次勉强完成动作，但没有恢复余量，鲁棒性差。

5. **多模态证据利用仍是空位**
   - 多个 trace 中 `screenshot_attached=false` 仍持续存在。
   - 本轮不再是 Expense 的主阻塞，但对 Audio 这类状态歧义场景依然损失判别信号。

## 与上版总结（162502）的对照结论

- 上版提到的 **Expense a11y 文本截断**：**大概率已改善**（183513 a11y 中出现了完整 `my_expenses` 大文本，且任务可进入录入阶段）。
- 上版提到的 **Calendar 导航低效**：**部分改善**（从“差2步失败”到“勉强完成”），但仍高风险并未转化为通过。
- 上版提到的 **Loop/卡死问题**：**表象减轻但根因未闭环**（Audio 不再长循环，但变成提前成功误判）。
- 新增更突出的系统性问题：**GoalAchieved 精度显著下滑（1.0 -> 0.4）**。

## 优先级建议（面向下一轮）

### P0
- 增强 `complete_task` 前置校验：按任务类型做“成功必要条件”检查（例如 Audio 必须处于停止态、对象必须是新产物而非历史残留）。
- 建立“UI成功 vs scorer成功”双层完成门控，避免 `GoalAchieved` 过早触发。

### P1
- 增加结构化文本规范化规则：把 `Reimbursable` 这类标签从 note 内容中剥离。
- 对日期/时区/source 这类 scorer 关键字段提供任务提示或执行策略（不是仅凭 UI 可见状态判断完成）。

### P2
- 对高 turn 成本控件（日期选择器/NumberPicker）引入预算感知与替代路径策略，保留 recovery 余量。
- 评估启用 screenshot 输入作为歧义场景回退（至少在关键任务类别开启）。
