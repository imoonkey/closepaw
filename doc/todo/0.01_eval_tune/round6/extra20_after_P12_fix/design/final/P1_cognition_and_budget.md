# P1 Cognition and Turn Budget: Completion Gate, Write Todos, Dynamic Turns

These fixes address turn waste and false-success completions. Together they save 2-5 turns per task, fix the implicit completion bug, and give multi-step tasks more room to complete.

---

## P1-4: Completion Gate Hardening

### Problem

3 tasks declared GoalAchieved but scored 0.0:

| Task | What Happened |
|---|---|
| CameraTakeVideo | Agent saw "Photo taken on..." labels, declared "Successfully recorded one video." Misread photo as video. |
| AudioRecorderRecordAudioWithFileName | Agent output plain text that looked like an action; Turn.processResponse treated it as implicit completion. No `complete_task` was called. |
| ClockStopWatchPausedVerify | Agent saw stopwatch at 00:00 with Start button, declared "already stopped." Possible task init issue — see Open Question below. |

### Root Cause Analysis

The AudioRecorder failure reveals a runtime bug in `Turn.kt:processResponse()` (lines 174-213):

```kotlin
val isComplete =
    completeTaskCall != null ||
    (toolCalls.isEmpty() &&
     effectiveTextContent != null &&
     !hasMalformedKnownToolMarker)
```

When the model outputs plain text without any tool calls (and the text doesn't look like a malformed tool marker), `isComplete` is set to `true`. This means the task auto-completes on conversational text, even if the agent hasn't called `complete_task`.

The CameraTakeVideo failure is a cognitive verification failure — the agent checked the screen but mismatched "photo" for "video".

### Design: Two-Part Fix

#### Part A: Fix Implicit Completion in Turn.processResponse

Require `complete_task` for completion in all modes. In `Turn.kt:processResponse()`, change:

```kotlin
// BEFORE (implicit completion on plain text):
val isComplete =
    completeTaskCall != null ||
    (toolCalls.isEmpty() &&
     effectiveTextContent != null &&
     !hasMalformedKnownToolMarker)

// AFTER (explicit completion only):
val isComplete = completeTaskCall != null
```

The implicit-completion path existed as a convenience for models with weak tool-calling, but it directly causes false success. If the model does not call `complete_task`, it should continue until it does or reaches max turns.

#### Part B: System Prompt Verification Checklist

Add to `StandaloneAgentDef` system prompt:

```
## Completion Verification
- Before calling complete_task, re-read the current screen state and verify EACH
  requirement from the original goal against what you actually see on screen.
- Match specific labels: if the goal says "video", confirm the a11y tree shows "video" not "photo".
- If you performed an action (rename, save, delete) that should have changed the UI, confirm the
  change is reflected before declaring success.
- Never assume success from tool-call results alone — always verify the on-screen outcome.
```

This addresses CameraTakeVideo (would prompt checking "video" vs "photo") and AudioRecorder (would prompt verifying filename changed).

### Why NOT Goal-Slot Coverage Check

A runtime check that extracts required literals from the goal and validates them against the a11y tree before allowing `complete_task(status=success)` was considered. This is over-engineered:
- Fragile: quoted strings in goals don't always appear literally on screen (e.g., "Draft" contact may show as "Unsaved contact")
- Complex: requires NLP extraction of "required literals" which is model-dependent
- The prompt-based verification is lighter and leverages the LLM's reasoning capability

### Open Question: ClockStopWatchPausedVerify Task Init

The goal is "Pause the stopwatch." If `initialize_task()` was supposed to start the stopwatch running (so the agent just needs to pause it), but initialization failed or the stopwatch stopped between init and agent start, then the agent's conclusion ("nothing to pause") is correct.

**Action needed**: Check `android_world/task_evals/single/clock.py` for the `ClockStopWatchPausedVerify` task's `initialize_task()` to determine whether this is a task setup issue or cognitive error. This affects whether additional prompt guidance is needed.

### Files Changed

| File | Change |
|---|---|
| `app/.../agent/Turn.kt` | Remove implicit text-only completion path; completion requires `complete_task` |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `## Completion Verification` section to system prompt |

### Impact

- Part A fixes AudioRecorder false success (and any future implicit completion bugs)
- Part B addresses CameraTakeVideo and AudioRecorder verification failures

### Risks

- Part A: if the model does not call `complete_task`, it runs to max turns. This is acceptable — timeout is better than false success.
- Part B adds ~40 tokens per turn. Negligible overhead.

---

## P1-5: Disable write_todos in Eval

### Problem

`write_todos` consumed 2-5 turns per task with zero contribution to task completion. In turn-budget-constrained tasks, this overhead directly caused failure.

### Design: Exclude via eval config

Using the `excluded_tools` mechanism from P0-2:

```yaml
# eval/config/default.yaml
bridge:
  excluded_tools: ["ask_user", "write_todos"]
```

This disables `write_todos` in eval without deleting code. The tool implementation (`WriteTodosTool.kt`) and registration (`SessionToolingBootstrapper.kt:49`) stay untouched.

Additionally, comment out the prompt guidance line in `StandaloneAgentDef`:

```kotlin
// - Use `write_todos` for multi-step goals to keep progress explicit.
```

And in `PlannerAgentDef`, same treatment.

### Why excluded_tools Instead of Comment-Out

The `excluded_tools` config approach (from P0-2) is cleaner than commenting out registration code:
- One mechanism for all tool exclusions (ask_user, write_todos, future tools)
- No code changes needed in the app to toggle tools on/off
- Eval config is the single source of truth for eval-specific behavior

However, the prompt guidance line should still be commented out — no point telling the model about a tool it can't use.

### Files Changed

| File | Change |
|---|---|
| `eval/config/default.yaml` | Add `"write_todos"` to `excluded_tools` list |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Comment out write_todos prompt guidance |
| `app/.../agent/definition/PlannerAgentDef.kt` | Comment out write_todos prompt guidance |

### Impact

- Saves 2-5 turns per task across all tasks
- ExpenseAddMultiple: saved 4 turns toward completing 3rd expense
- SimpleCalendarAddOneEvent: saved 2 turns toward reaching October in date picker

### Risks

- Loss of structured planning visibility in traces (scratchpad still available)
- If re-enabled later, models may not batch properly without re-tuning prompt

---

## P1-6: Dynamic Turn Budget

### Problem

30 turns is tight for multi-step tasks (ExpenseAddMultiple: 2/3 done at turn 30). But increasing to 40+ for all tasks wastes tokens on simple tasks where the agent loops on errors.

### Design: Per-Task Override + Stall Detection

#### Part A: Per-Task Override in Eval Config

```yaml
# eval/config/default.yaml
bridge:
  max_turns: 30  # default

  task_overrides:
    ExpenseAddMultiple: { max_turns: 45 }
    # SimpleCalendarAddOneEvent: { max_turns: 45 }  # uncomment if needed
```

In `runner.py`, resolve per-task config before calling the bridge:

```python
def _resolve_task_config(self, task_name: str, base_config: dict) -> dict:
    """Merge per-task overrides into base bridge config."""
    config = dict(base_config)
    overrides = config.pop("task_overrides", {})
    for prefix, override in overrides.items():
        if task_name.startswith(prefix):
            config.update(override)
            break
    return config
```

#### Part B: Stall Detection Safety Net

In `Agent.kt`, detect looping behavior:

```kotlin
// After turnRunner.executeTurn():
if (turnExecution.outcome is TurnOutcome.Continue) {
    if (turnExecution.isStall) {
        stallCount++
        if (stallCount >= MAX_STALL_TURNS) {
            stopReason = AgentStopReason.Error("Agent stalled: $stallCount identical turns")
            break
        }
    } else {
        stallCount = 0
    }
}
```

Where `MAX_STALL_TURNS = 3` and `isStall` is determined by comparing the current action + screen hash to the previous turn.

This catches the pathological case (agent clicking the same button 10 times) without penalizing productive multi-step tasks.

### Why NOT Complexity Rules or Goal Heuristics

A more complex resolver with `complexity_rules` (threshold mapping) and `goal_bonus_rules` (regex matching goal text for keywords like "three", "multiple") was considered. This adds unnecessary complexity:
- Goal complexity doesn't correlate well with turn count ("Delete the duplicate expense" = 14 turns, "Open Clock" = 4 turns)
- Regex-based goal analysis is fragile and requires maintenance
- Per-task overrides give precise control for known complex tasks
- Stall detection handles the token waste concern without predicting turn needs

### Files Changed

| File | Change |
|---|---|
| `eval/config/default.yaml` | Add `task_overrides` section |
| `eval/aw_bridge/runner.py` | Add `_resolve_task_config()`, apply before `bridge.run_task()` |
| `app/.../agent/Agent.kt` | Add stall detection (independent of per-task override) |

### Impact

- ExpenseAddMultiple with 45 turns: 3rd expense completes (was at 90% on turn 30)
- Stall detection: saves tokens on pathologically looping tasks
- Clean per-task control for future eval tuning

### Risks

- Per-task overrides add maintenance burden (need to update when adding new tasks)
- Stall detection may false-positive on legitimate retry scenarios (mitigated by requiring 3 consecutive identical actions)
