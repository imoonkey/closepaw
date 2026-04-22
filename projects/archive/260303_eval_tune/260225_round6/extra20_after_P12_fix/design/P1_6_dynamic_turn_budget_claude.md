# P1-6: Dynamic Turn Budget

## Problem

30 turns is tight for multi-step tasks (ExpenseAddMultiple: 2/3 done at turn 30). But increasing to 40+ for all tasks wastes tokens on simple tasks where the agent might loop on errors.

User's note: "现在这个max turn 30是写死的，这个要怎么改比较clean？如果都搞成40，我怕有一些agent在一些简单任务上，因为执行有误，重复无效操作，而造成token浪费"

## Current Architecture

- Eval config: `max_turns: 30` in `eval/config/default.yaml:37`
- Bridge passes to app via intent extra: `native_agent_bridge.py:197-198`
- App reads from intent and feeds to `AgentExecutionConfig`
- Agent loop checks: `if (turnCount >= config.maxTurns)` in `Agent.kt:82`
- UI options: `MAX_TURNS_OPTIONS = listOf(10, 20, 50)` in `SettingsModels.kt:43`

## Design: Per-Task Override in Eval Config

### Level 1: Global default + per-task override

Add `task_overrides` to eval config:

```yaml
# eval/config/default.yaml
bridge:
  max_turns: 30  # default

  # Per-task overrides (task name prefix match)
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

### Level 2: Loop detection as safety net

To address the user's concern about "重复无效操作" on simple tasks, add a **stall counter** to the agent loop. This is NOT a turn budget reduction — it's a failsafe:

In `Agent.kt`, after `turnRunner.executeTurn()`:

```kotlin
// Detect stalled loops: if last N consecutive turns had identical a11y tree and same action, stop early
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

## Why NOT a Global Increase to 40

The user is right — a flat increase risks:
- Token waste on simple 4-turn tasks where the agent loops due to misunderstanding
- Longer eval times (each turn = 1 LLM call + a11y capture)
- The per-task override + stall detection gives precise control

## Why NOT a Heuristic Budget (e.g., based on goal word count)

Tempting but wrong. Goal complexity doesn't correlate well with turn count. "Delete the duplicate expense" (simple goal) needs 14 turns. "Open Clock" needs 4.

## Files Changed

| File | Change |
|---|---|
| `eval/config/default.yaml` | Add `task_overrides` section |
| `eval/aw_bridge/runner.py` | Add `_resolve_task_config()`, apply before `bridge.run_task()` |
| `eval/aw_bridge/native_agent_bridge.py` | No change (already reads max_turns from config) |
| `app/.../agent/Agent.kt` | Add stall detection (optional, independent of per-task override) |

## Impact

- ExpenseAddMultiple with 45 turns: 3rd expense almost certainly completes (was at 90% on turn 30)
- Stall detection: saves tokens on pathologically looping tasks
- Clean per-task control for future eval tuning

## Risks

- Per-task overrides add maintenance burden (need to update when adding new tasks)
- Stall detection may false-positive on legitimate retry scenarios (mitigated by requiring 3 consecutive identical actions, not just 2)
