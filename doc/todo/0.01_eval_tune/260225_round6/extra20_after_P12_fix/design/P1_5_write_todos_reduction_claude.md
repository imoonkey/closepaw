# P1-5: Reduce write_todos Overhead

## Problem

`write_todos` consumed 2-5 turns per task with zero contribution to task completion. In turn-budget-constrained tasks (ExpenseAddMultiple, SimpleCalendarAddOneEvent), this overhead directly caused failure.

## Current Architecture

- `WriteTodosTool` defined in `WriteTodosTool.kt`, registered in `SessionToolingBootstrapper.kt:49`
- StandaloneAgentDef: includes `write_todos` in `allowedTools` + prompt guidance (lines 33-37)
- PlannerAgentDef: includes `write_todos`
- ExecutorAgentDef: already excludes `write_todos`

## Design: Comment Out, Don't Delete

User's note: "可以先去掉。但是是comment掉tool enablement和相关的system prompt instruction，等以后如果需要再加回来。"

### Changes

1. **SessionToolingBootstrapper.kt:49** — comment out registration:

```kotlin
// TODO: Re-enable write_todos when turn budget is less constrained
// register(WriteTodosTool(sessionState.todos))
```

2. **StandaloneAgentDef.kt** — remove from allowedTools and comment out prompt section:

```kotlin
override val allowedTools: Set<String> =
    setOf(
        "mobile_action",
        "system_button",
        "wait",
        "open_app",
        "scratchpad",
        // "write_todos",  // Disabled: consumes turns with no eval benefit
        "complete_task",
        "ask_user"
    )
```

And comment out the prompt line:
```kotlin
// - Use `write_todos` for multi-step goals to keep progress explicit.
```

3. **PlannerAgentDef.kt** — same pattern: comment out from allowedTools and prompt.

### Why Comment Out Instead of Delete

- The tool implementation (`WriteTodosTool.kt`) stays in the codebase untouched
- Easy to re-enable by uncommenting 3 locations
- No code deletion = easy `git diff` to see what was disabled
- If future models benefit from structured planning (e.g., with larger turn budgets), uncomment

## Alternative Considered: Reduce Frequency Instead of Disable

We could keep write_todos but add prompt guidance like "Only use write_todos once at the beginning, not mid-task." But this is model-dependent — qwen3.5 may still over-use it. Disabling is cleaner and deterministic.

## Files Changed

| File | Change |
|---|---|
| `app/.../session/SessionToolingBootstrapper.kt` | Comment out line 49 |
| `app/.../agent/definition/StandaloneAgentDef.kt` | Comment out from allowedTools + prompt |
| `app/.../agent/definition/PlannerAgentDef.kt` | Comment out from allowedTools + prompt |

## Impact

- Saves 2-5 turns per task across all tasks
- ExpenseAddMultiple (saved 4 turns → likely pushes 3rd expense to completion)
- SimpleCalendarAddOneEvent (saved 2 turns → gets closer to October in date picker)

## Risks

- Loss of structured planning visibility in traces (acceptable — scratchpad still available)
- If re-enabled later, models may not batch properly without re-tuning prompt
