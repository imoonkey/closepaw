# R22-R30 Unpark Loop — File Changes Summary

**Commit range**: `9fa9448..f83be3b` (7 commits) + uncommitted summary docs
**Date**: 2026-03-09

## Committed Changes (9fa9448..HEAD)

### App Skills (代码变更)
| File | Change |
|------|--------|
| `app_skills/com.android.chrome/SKILL.md` | 更新 Chrome 技能，增加 hybrid perception + canvas 导航指引 |
| `app_skills/de.dennisguse.opentracks/SKILL.md` | OpenTracks 滚动策略从双向改为"只向下滚5-6次" |
| `app_skills/com.flauschcode.broccoli/SKILL.md` | Broccoli 增加字段逐一比较指引（解决重复食谱识别） |
| `app_skills/com.arduia.expense/SKILL.md` | 新增 Pro Expense 技能（类别横向滚动 + 全列表滚动） |
| `app_skills/com.dimowner.audiorecorder/SKILL.md` | 新增 Audio Recorder 技能（录音 + 重命名流程） |
| `app_skills/code.name.monkey.retromusic/SKILL.md` | 新增 Retro Music 技能（hybrid perception + 时长策略） |
| `app_skills/net.osmand/SKILL.md` | 新增 OsmAnd 技能（标记/路线搜索指引） |

### Eval Configs
| File | Change |
|------|--------|
| `eval/config/autotune_round_{24..30}.txt` | 每轮的任务配置（各 2-4 个任务） |
| `eval/config/cannot_handle_group.txt` | +9 行（从 1 项增至 10 项） |
| `eval/config/unpark_active.txt` | 活跃任务列表随 re-park 逐步缩减 13→0 |
| `eval/config/default.yaml` | 增加 hybrid perception override + max_turns 调整 |

### Meta / Loop State
| File | Change |
|------|--------|
| `doc/autotune/meta/loop_state.json` | 循环状态从 running → stop_success |
| `doc/autotune/meta/scoreboard.md` | +7 行（新增 R22-R30 列） |
| `doc/autotune/meta/unpark_triage.md` | 新文件：13 个 unpark 任务的分诊记录 |

### Other
| File | Change |
|------|--------|
| `sop/adhoc/tune_loop.md` | 调优循环 SOP 更新 |
| `doc/todo/remote_emulator/remote_emulator_eval_codex.md` | 远程模拟器方案文档（独立于 autotune） |

**合计**: 23 files changed, +745 / −47

## Uncommitted Changes (HEAD → working tree)

### 新增：Round 分析文档（untracked, 42 files）
- `doc/autotune/round_{22..30}/` — 每轮的 per-task 分析和 common_problems 报告

### 修改：Meta 文档（17 files, +577 / −199）
| File | Change |
|------|--------|
| `doc/autotune/meta/changelog.md` | +108 行：R22-R30 每轮的修复、结果、发现 |
| `doc/autotune/meta/issues.md` | 重写 parked 列表：4 项标记 RESOLVED，9 项更新根因描述 |
| `doc/autotune/meta/per_task/*.md` (×11) | 每个 unpark 任务新增 R22-R30 轮次记录 |
| `doc/autotune/meta/scoreboard.json` | +230 行：所有任务的 R22-R30 分数数据 |
| `doc/autotune/meta/scoreboard.md` | 重排全表，填入 R22-R30 列数据 |
| `doc/autotune/meta/loop_state.json` | 最终状态微调 |

### 删除
| File | Change |
|------|--------|
| `.ai-dev/ralph-loop.local.md` | −51 行：Ralph Loop 临时文件清理 |

## 变更类型分布

```
App Skills (代码)     7 files   — 新增/修改 app 技能指引
Eval Config           9 files   — 轮次配置 + cannot_handle + default.yaml
Round Analysis       42 files   — 每轮每任务的详细分析报告 (untracked)
Meta Docs            17 files   — changelog, issues, scoreboard, per_task
SOP/Other             2 files   — tune_loop SOP + remote emulator doc
```
