# P0-2: Block ask_user in Eval Mode

## Problem

6/20 tasks (30%) hit ASK_USER_BLOCKED — the agent calls `ask_user` for date clarification, which is blocked in eval. Zero productive turns executed.

Current eval detection: `completion_monitor.py` detects `"Executing tool: ask_user"` in logcat and flags as ASK_USER_BLOCKED error. This is a **reactive** catch — the agent still wastes turns before triggering it.

## Current Architecture

- `AskUserTool` registered in `SessionAgentRunner.kt:119-131` via `ensureAskUserToolRegistered()`
- StandaloneAgentDef includes `"ask_user"` in `allowedTools` (line 17)
- PlannerAgentDef already excludes ask_user (line 8-14)
- Tool filtering happens via `allowedToolNames` in the agent definition

## Design: Two-Layer Fix

### Layer 1: System Prompt Instruction (Primary)

Add to StandaloneAgentDef system prompt (and ExecutorAgentDef):

```
## ask_user
- NEVER call ask_user to clarify dates, times, or quantities.
- Use the device's current date/time for relative references ("tomorrow", "next week").
- If information seems ambiguous, make the most reasonable assumption and proceed.
- Only use ask_user when the task is genuinely impossible without physical user intervention
  (e.g., CAPTCHA, biometric authentication, physical camera positioning).
```

This is the clean fix — it teaches the model when ask_user is appropriate without removing the tool entirely. The model needs ask_user for genuine cases (physical actions during eval won't happen, but this instruction is also used in production).

### Layer 2: Eval Bridge Config (Belt-and-Suspenders)

Add an `excluded_tools` field to the eval bridge config that gets passed to the agent intent:

```yaml
# eval/config/default.yaml
bridge:
  max_turns: 30
  excluded_tools: []  # e.g., ["ask_user"] to fully disable in eval
```

The bridge passes this via intent extra to the app, which filters the tool from the agent definition's `allowedTools` before session start.

In `SessionAgentRunner.kt`, apply the filter:

```kotlin
// Before building agent config:
val effectiveTools = agentDef.allowedTools - excludedToolNames
```

This keeps the tool code intact (not deleted/commented) and makes the exclusion configurable per eval config.

## Why Not Just Remove the Tool?

User's note says "不要太hacky". Removing `ask_user` entirely would break production flows. The system prompt fix addresses the root cause (model over-applies ask_user for dates), and the config-based exclusion is a clean safety net for eval.

## Edge Case: SimpleCalendarAddRepeatingEvent

This task had an **absolute** date ("October 29, 2023") but still triggered ask_user. The system prompt fix explicitly addresses this: "NEVER call ask_user to clarify dates." If the model still does it despite the prompt, Layer 2 catches it.

## Files Changed

| File | Change |
|---|---|
| `app/.../agent/definition/StandaloneAgentDef.kt` | Add `## ask_user` guidance to system prompt |
| `app/.../agent/definition/ExecutorAgentDef.kt` | Add same guidance |
| `eval/config/default.yaml` | Add `excluded_tools: []` field |
| `eval/aw_bridge/native_agent_bridge.py` | Pass `excluded_tools` via intent extra |
| `app/.../session/SessionAgentRunner.kt` | Apply tool exclusion filter from intent |

## Impact

- Unblocks 6 ASK_USER_BLOCKED tasks
- System prompt fix also improves production behavior (fewer unnecessary user interruptions)

## Risks

- Model may ignore the system prompt instruction (mitigated by Layer 2)
- Excluding ask_user in eval may cause the agent to get stuck on genuinely interactive tasks (acceptable for eval — those tasks shouldn't be in the automated eval set)
