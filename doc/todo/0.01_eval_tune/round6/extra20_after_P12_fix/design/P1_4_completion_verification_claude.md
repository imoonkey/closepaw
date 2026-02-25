# P1-4: Pre-Completion Verification

## Problem

3 tasks declared GoalAchieved but scored 0.0. The user's note asks: "agent都尝试去verify了... 这个是Task Initialization该跑没跑吗？还是说这个是verification的原因？"

## Investigation Findings

| Task | Verified? | What happened |
|---|---|---|
| CameraTakeVideo | Yes, but misidentified | Agent saw "Photo taken on..." in a11y labels → declared "Successfully recorded one video." Misread Photo as Video. |
| ClockStopWatchPausedVerify | Yes, but wrong logic | Agent saw stopwatch at 00:00 with Start button → declared "already stopped, nothing to pause." |
| AudioRecorderRecordAudioWithFileName | No | Agent recorded audio, attempted rename via long-press (Turn 13), rename dialog didn't appear. Turn 14: declared success without confirming filename was changed. |

### Deep Dive: ClockStopWatchPausedVerify — Task Init Issue?

The user asks: "这个是Task Initialization该跑没跑吗？"

This is a real possibility. The goal is "Pause the stopwatch." AndroidWorld tasks typically set up initial state via `task.initialize_task(env)`. If the task was supposed to start the stopwatch running (so the agent just needs to pause), but initialization failed or the stopwatch stopped between init and agent start, then:

- The agent seeing 00:00 with Start button is accurate — the stopwatch IS reset
- The agent's conclusion ("nothing to pause") is logically correct given what it sees
- The root cause is task setup, not agent reasoning

**We can't determine this without checking the AndroidWorld task definition for `ClockStopWatchPausedVerify`**. If the task expected the stopwatch to already be running, this is an eval environment issue, not a cognitive error.

**Recommendation**: Check `android_world/task_evals/single/clock.py` for the `ClockStopWatchPausedVerify` task's `initialize_task()` method to see if it's supposed to start the stopwatch before handing control to the agent.

## Design: Two Approaches

### Approach A: System Prompt Checklist (Addresses CameraTakeVideo, AudioRecorder)

Add to StandaloneAgentDef system prompt, after the "Execution Quality" section:

```
## Completion Verification
- Before calling complete_task, re-read the current screen state and verify EACH
  requirement from the original goal against what you actually see on screen.
- Match specific labels: if the goal says "video", confirm the a11y tree shows "video" not "photo".
- If you performed an action (rename, save, delete) that should have changed the UI, confirm the
  change is reflected before declaring success.
- Never assume success from tool-call results alone — always verify the on-screen outcome.
```

This is lightweight (~4 lines), doesn't add a new tool or turn overhead, and addresses the two clear verification failures:
- CameraTakeVideo: would prompt the agent to check if a11y labels say "video" vs "photo"
- AudioRecorderRecordAudioWithFileName: would prompt the agent to verify the filename changed

### Approach B: No Change for ClockStopWatchPausedVerify

Until we confirm whether the task initialization is at fault, don't add prompt guidance for "if stopwatch is at 00, start it first" — that would be encoding task-specific knowledge into the general prompt. If it's a task init issue, the fix belongs in the eval environment, not the agent prompt.

## Decision

**Use Approach A only.** Add the completion verification guidance. Don't try to fix ClockStopWatchPausedVerify at the prompt level until the task init question is resolved.

## Action Item: Investigate Task Init

```bash
# Check if ClockStopWatchPausedVerify initializes the stopwatch to running:
grep -A 30 "class.*PausedVerify\|class.*StopwatchPaused" \
  .reference/eval/android_world/android_world/task_evals/single/clock.py
```

If `initialize_task()` starts the stopwatch, the agent is correct and the issue is a race condition between task init and agent perception. If it doesn't, the goal statement is misleading ("Pause the stopwatch" when it's not running).

## Files Changed

| File | Change |
|---|---|
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `## Completion Verification` section to system prompt (~4 lines) |

## Impact

- Addresses CameraTakeVideo and AudioRecorderRecordAudioWithFileName (2 tasks)
- ClockStopWatchPausedVerify: deferred pending task init investigation

## Risks

- Extra prompt text adds ~40 tokens per turn (negligible)
- May cause the agent to spend 1 extra turn verifying when it could have completed immediately (acceptable tradeoff vs false success)
