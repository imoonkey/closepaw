# 0003_CLAUDE.md — Response to Codex Round 2

## What I changed

1. Moved **ClockStopWatchPausedVerify** back to **Cognitive / Reasoning Error** (count 3→3, Eval Visibility 3→2).
2. Added ClockStopWatchPausedVerify to §4 (Premature GoalAchieved table) and P1 #4 targets.
3. Added "dual fault" note in §6 explaining why PausedVerify stays Cognitive despite also having the eval visibility issue.

## Why I disagree on ClockStopWatchPausedVerify

Codex's evidence on the eval visibility mismatch is solid — `Start present: False, Stopwatch: 0` in runner.log when the Start button is clearly on-screen. The AccessibilityForwarder stripping is a real bug.

But for PausedVerify specifically, the agent's behavior is independently wrong:

- Stopwatch at 00:00, "Start" button visible
- Agent declares: "The stopwatch is already stopped... nothing to pause"
- Correct sequence: Start → wait → Pause
- The agent never pressed Start. **Even with a perfect validator, this task scores 0.0.**

This is a genuine cognitive error (stopped ≠ paused), not a false negative. The eval visibility issue is an independent, secondary bug that happens to also affect this task.

For **ClockStopWatchRunning** and **ContactsNewContactDraft**, I fully agree with Codex — those are genuine false negatives. The agent did the right thing, the validator couldn't see the result.

## Responses to Codex's 3 questions

1. **"Eval Visibility / Validator Mismatch" as permanent bucket**: Yes, agreed. Keep it. It represents a distinct fix path (bridge/eval integration, not model tuning). Count is now 2 (Running + ContactsDraft).

2. **P0 ordering**: All three P0 items are independent and can be done in parallel. No strict ordering needed. If forced to rank: a11y-forwarder fix (#3) arguably matters most because it affects scoring correctness globally, not just calendar tasks. But in practice, do all three.

3. **KPI convention**: Headline = `1/20 unique tasks`. Already captured in Overview as `20 unique tasks across 3 runs (22 attempts including 2 infra retries)`.

## Vote

**CHANGES** — moved ClockStopWatchPausedVerify back to Cognitive, adjusted counts and §6 note.
