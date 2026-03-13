# TasksDueNextWeek — R43 Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 7/30 | **Reason**: GoalAchieved (false completion) | **Duration**: ~80s

**Agent answer**: 13 | **Correct**: 6

## Root Cause

**Category**: ScratchpadTruncation + IncompleteDedupe

Tasks.org skill with dedup guidance was injected from turn 3 onward. Agent partially followed it — wrote 8 unique task names to scratchpad in turn 5 with explicit dedup logic. But at answer time (turn 7), the scratchpad `next_week_unique_seen` field was truncated to ~200 chars (platform limit), making the deduplicated list unreadable. Agent fell back to counting raw visible rows (13).

## Key Findings

1. **Dedup guidance worked initially**: Turn 5 scratchpad contained 8 unique task names with dedup reasoning.
2. **Scratchpad truncation destroyed working memory**: At turn 7, the field showed `"...Send report to manager:Sat(202... // truncated, 330 chars"`. Agent couldn't consult its own notes.
3. **Even 8 is wrong (correct is 6)**: Some day-of-week labels deep in the list refer to the FOLLOWING week (Oct 23+), not target range (Oct 16-22). Agent included tasks positioned chronologically after "Oct 22" marker.
4. **No reconciliation after final scroll**: After turn 6's scroll revealed 5 new tasks, agent jumped directly to complete_task without updating scratchpad.

## Comparison: R41 → R42 → R43

| Dimension | R41 (13) | R42 (14) | R43 (13) |
|-----------|----------|----------|----------|
| Dedup attempted | No | No | **Yes** (8 unique in scratchpad) |
| Scratchpad truncated | No | No | **Yes** (platform bug) |
| Date range correct | Yes | Yes | Yes |
| Final answer | 13 | 14 | 13 |

Directional improvement: agent now attempts dedup. Blocked by platform constraint.

## Recommendations

1. **Fix scratchpad truncation** — Platform-level. If working_memory truncates values at ~200 chars, the dedup strategy can never work for lists with >6-8 entries.
2. **More compact scratchpad format** — If truncation can't be fixed, use shorter format: `"unique: FolUp|PlanFam|BookFlt|Research|SendRep|OrgFiles|CallGma|Brainstorm = 8"` instead of full JSON.
3. **Consider parking** — If platform fix is infeasible, this task is structurally blocked.
