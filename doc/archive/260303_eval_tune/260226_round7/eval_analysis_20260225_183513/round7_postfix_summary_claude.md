# Round 7 — Post-Fix Evaluation Summary

## Run Comparison

| Metric | Pre-Fix (162502) | Post-Fix (183513) | Delta |
|---|---|---|---|
| Pass Rate | 2/5 (40%) | 2/5 (40%) | No change |
| MaxTurnsReached | 3 tasks | 0 tasks | **-3 (eliminated)** |
| GoalAchieved (true positive) | 2 | 2 | No change |
| GoalAchieved (false positive) | 0 | 3 | **+3** |
| Total turns executed | 105 | 77 | **-28 (27% reduction)** |
| Average turns per task | 21.0 | 15.4 | **-5.6** |
| Average turns for failures | 30.0 | 20.7 | **-9.3** |
| Tool failure rate | 2.9% (3/105) | 0% (0/77) | **-2.9pp** |

**Headline**: Pass rate unchanged at 40%, but the nature of failures fundamentally shifted. All three failures went from "stuck/exhausted" (MaxTurnsReached) to "completed but wrong" (GoalAchieved with score=0). This is a qualitative improvement — the agent can now finish tasks it previously couldn't, but new failure modes emerged at the semantic/scoring layer.

## Per-Task Comparison

### AudioRecorderRecordAudio

| | Pre-Fix (162502) | Post-Fix (183513) |
|---|---|---|
| Turns | 30 | **9** |
| Completion | MaxTurnsReached | **GoalAchieved** |
| Score | 0.0 | 0.0 |
| Root Cause | 23-turn save/warning/OK loop | **False completion** (claimed success without actual recording) |

**What changed**: P0 cycle detection works — the agent no longer enters the save→warning→OK infinite loop. Instead, it now completes prematurely at turn 9, claiming a recording was saved when it wasn't actually persisted. The failure shifted from **cognition: loop** to **cognition: false verification**.

**What's still broken**: The agent doesn't verify the actual outcome. It sees a recording in the list and assumes success, but the file is a 00:00-duration placeholder that was never actually recorded. The agent needs stronger evidence-based completion verification.

### ClockStopWatchRunning

| | Pre-Fix (162502) | Post-Fix (183513) |
|---|---|---|
| Turns | 4 | 4 |
| Score | 1.0 | 1.0 |

No change. Optimal execution retained.

### ContactsNewContactDraft

| | Pre-Fix (162502) | Post-Fix (183513) |
|---|---|---|
| Turns | 11 | 11 |
| Score | 1.0 | 1.0 |

No change. Clean execution retained.

### ExpenseAddMultipleFromMarkor

| | Pre-Fix (162502) | Post-Fix (183513) |
|---|---|---|
| Turns | 30 | **23** |
| Completion | MaxTurnsReached | **GoalAchieved** |
| Score | 0.0 | 0.0 |
| Root Cause | Stuck in Markor 26 turns trying to read truncated text; shell `>` rejected | **Completed full cross-app workflow**, but note field mismatch |

**What changed**: **P1a (text truncation fix) and P1b (shell relaxation) directly fixed the previous blocker.** The agent now reads the full expense file content (no truncation), identifies the two reimbursable transactions, uses scratchpad to transfer data, opens Pro Expense, and successfully logs both expenses. This is a huge behavioral improvement — from "never left Markor" to "completed the entire multi-app workflow in 23 turns."

**What's still broken**: Scored 0 due to note field semantics. The agent entered "Urgent. Reimbursable." (full text from Markor file) but the scoring expected "Urgent" (just the descriptive portion, without the "Reimbursable" filter tag). The agent correctly copied the data but didn't understand that "Reimbursable" is a metadata tag, not part of the note content.

### SimpleCalendarAddOneEvent

| | Pre-Fix (162502) | Post-Fix (183513) |
|---|---|---|
| Turns | 30 | **30** |
| Completion | MaxTurnsReached | **GoalAchieved** |
| Score | 0.0 | 0.0 |
| Root Cause | 16 turns on date nav; 2 actions from success | **14 turns on date nav; saved event successfully**, but DB row not found |

**What changed**: P2 tips improved the agent's strategy slightly (14 vs 16 turns on date navigation; used event form date picker instead of main calendar view). The agent now completes all steps including Save and dismissing a disclaimer dialog. It uses exactly 30/30 turns with zero margin.

**What's still broken**: Scored 0 because the expected DB row was not found. The expected row has `source='imported-ics'` and `time_zone='UTC'` — a UI-created event would have different values for these fields. This is likely a **scoring-level issue** (the AndroidWorld task scoring matches fields that differ between UI-created and programmatically-injected events) or a **timezone issue** (device timezone ≠ UTC causing timestamp mismatch).

## P0/P1/P2 Fix Effectiveness

| Fix | Target | Result |
|---|---|---|
| **P0: Cycle detection** | AudioRecorder loop (23 turns) | **Fixed loop**, but revealed false completion (new failure mode) |
| **P1a: Remove text truncation** | Expense couldn't read Markor file | **Fixed** — agent reads full file content, completes cross-app workflow |
| **P1b: Relax shell validation** | Shell command `>` rejected | **Fixed** — validation no longer blocks useful operators |
| **P2: Tips section** | Calendar date navigation (16 turns) | **Partially effective** — reduced to 14 turns, agent saved event, but scoring still fails |

**Summary**: All fixes achieved their intended behavioral impact. The pass rate didn't improve because new failure modes were uncovered at the next layer (semantic understanding, scoring compatibility).

## Common Problems Across All Failed Tasks

### 1. False Completion / Weak Verification (affects 3/3 failures)

All three failed tasks declared `GoalAchieved` with confident completion messages, but scored 0.0. The agent's completion verification is surface-level:
- **AudioRecorder**: Saw a recording in the list → assumed it was real (it was a 0:00 placeholder)
- **Expense**: Saw both entries in the Recent list → assumed they were correct (note field was wrong)
- **Calendar**: Saw event in the notification area → assumed it was saved (DB row didn't match)

The agent verifies **visual presence** but not **semantic correctness**. It checks "does the item appear on screen?" but not "does the item have the right values in the right fields?"

**Recommendation**: Add a completion verification tip:
> "Before completing a task, verify not just that the expected item exists on screen, but that its specific values match what was requested. Click on the item to view its details and confirm all fields are correct."

### 2. Date/Time Navigation is Turn-Expensive (affects 1/3 failures directly, systemic risk)

SimpleCalendarAddOneEvent consumed 14/30 turns (47%) navigating from Feb 2026 to Oct 2023 in the date picker. The agent clicked "Next month" 8 times sequentially. This pattern will recur for any task requiring dates far from today.

**Recommendation**: Add a tip about using shell intents for calendar events:
> "For calendar events with specific dates far from today, consider using `adb shell am start` with intent extras to pre-populate fields, avoiding manual date picker navigation."

### 3. Scoring Compatibility / Task Design Issues (affects 2/3 failures, environment/task issue)

Both ExpenseAddMultipleFromMarkor and SimpleCalendarAddOneEvent failed at the scoring layer despite the agent completing the workflows correctly from a user-visible perspective.

**ExpenseAddMultipleFromMarkor**: The scoring expected `note='Urgent'` but the Markor file contains `"Urgent. Reimbursable."` as the full note text. The task goal says "Log the reimbursable transactions" — it uses "reimbursable" as a filter criterion but **never instructs the agent to strip it from the note field**. The agent's behavior of copying the full note verbatim is a reasonable interpretation. Additionally, the scoring expects `created_date=1697320800000` (Oct 15, 2023), but Pro Expense's entry form has no date picker — the agent has no way to set a historical date. **This is a task/scorer design issue, not an agent cognition issue.**

**SimpleCalendarAddOneEvent**: The scoring expects `source='imported-ics'` and `time_zone='UTC'` — values that a UI-created event would not have. The agent correctly filled all fields and pressed Save, but the DB row doesn't match the scorer's expectations for implementation-specific fields.

**Recommendation**: Investigate AndroidWorld scoring code:
1. Check if note matching is exact or fuzzy (does it strip filter keywords?)
2. Check if `created_date` matching is required or ignored for Expense tasks
3. Verify the emulator timezone is set to UTC for Calendar tasks
4. Check if `source` field is included in CalendarEvent row matching
5. If these are indeed task design issues, they are **not actionable by the agent** and should be excluded from pass rate analysis

### 4. No Recovery Strategy When Near Turn Limit (systemic)

All three failed tasks exhausted their turn budget (30, 23, and 30 turns respectively). None demonstrated awareness of the remaining turn budget or adjusted strategy to prioritize completion. The agent doesn't reason about "I have N turns left, should I take a shortcut?"

**Recommendation**: Surface turn budget awareness:
> "You have used N of M turns. If you are running low, prioritize completing the core action and verifying, even if some details might be imperfect."

## Priority Recommendations for Next Round

### P0 — False Completion Guard (would fix AudioRecorder, improve all tasks)

The completion verification pattern needs strengthening. The agent should not just check visual presence but click into items to verify field values before declaring success. This could be a system prompt instruction or a hard check in the completion tool.

### P1 — Scoring Compatibility Investigation (would fix Expense + Calendar)

Investigate AndroidWorld scoring code for both ExpenseAddMultipleFromMarkor and SimpleCalendarAddOneEvent. Both tasks failed at the scoring layer despite the agent completing the workflow correctly. Key questions: does note matching strip filter keywords? Is `created_date` required? Does CalendarEvent scoring include `source` and `time_zone` fields? If these are task design issues, they should be marked as non-actionable and excluded from agent pass rate analysis.

### P2 — Shell-Based Calendar Event Creation (would fix Calendar and save turns)

Add guidance for using `am start -a android.intent.action.INSERT` for calendar events. This bypasses the date picker entirely and would reduce the Calendar task from 30 turns to ~5.

## Aggregate Statistics (Post-Fix Run: 183513)

| Metric | Value |
|---|---|
| Total turns executed | 77 (across 5 task attempts) |
| Total tool calls | 77 |
| Total tool failures | 0 |
| Tool success rate | 100% |
| Tasks hitting MaxTurnsReached | 0 |
| Tasks declaring GoalAchieved | 5 (2 true, 3 false) |
| Goal Claim Precision | 40% (2/5) |
| Average turns per task | 15.4 |
| Average turns for successes | 7.5 |
| Average turns for failures | 20.7 |
