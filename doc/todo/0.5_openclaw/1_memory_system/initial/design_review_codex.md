# Review: 1_memory_system Claude Design

## Summary

The design is directionally good on KISS: local markdown, no DB, and reuse of the existing prompt path. The main problems are around runtime correctness in this codebase: recall is keyed too narrowly, retention relies on an unreliable tool-call moment, and one integration detail does not match the current prompt builder.

## High

1. Recall keyed only by `currentPackage` misses the most important planning moment.  
   In the design, recall loads app memory only from the current foreground package ([design_claude.md:84-92](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L84)). In the current loop, `TurnPlanningPhaseRunner` gets only the pre-turn foreground package and uses that for app-skill injection ([TurnPlanningPhaseRunner.kt:42-80](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt#L42)). On turn 1 the agent is often on the launcher, home screen, or a previous app, so the target app's memory would not be recalled before `open_app`. That weakens exactly the first-turn planning where app memory is most valuable. The design needs a second recall signal from the task goal / intended app, not just the current package.

2. Tool-only retention is not reliable under the current completion/error flow.  
   The design explicitly avoids any post-task hook and depends on the model calling `remember_experience` itself ([design_claude.md:116-118](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L116), [design_claude.md:202](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L202)). In the current runtime, once the task completes, `AgentSession.handleAgentComplete()` immediately transitions to idle and clears the runner ([AgentSession.kt:337-388](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt#L337)). There is no guaranteed post-completion turn. The only way this works is if the model remembers to emit `remember_experience` in the same turn as `complete_task`, and that still does not cover terminal `Error` / `MaxTurnsReached` cases well. For this feature, a best-effort task-end retain hook is the safer primary path; a tool can still exist as an optional secondary path.

## Medium

1. The proposed "system message block" does not match the current prompt assembly API.  
   The design says long-term memory should be injected as a system message block ([design_claude.md:134-148](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L134)). But `PromptBuilder` currently emits only user/assistant input items, and all non-history context sections are added as user messages ([PromptBuilder.kt:43-55](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt#L43), [PromptBuilder.kt:179-215](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt#L179)). If implemented literally, this would require changing the request model, not just adding a section. The simpler fit is: inject recalled memory the same way app skill and working memory are injected today.

2. "No parsing, no schema" is too loose for an append-only store that is also budgeted and truncated.  
   The design makes `MemoryStore` a pure append/read wrapper and lets the LLM write free-form markdown bullets ([design_claude.md:39-66](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L39), [design_claude.md:64](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L64)). At the same time, recall truncates files to 2KB ([design_claude.md:92](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L92)) and the design is explicitly additive with no dedup ([design_claude.md:13](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L13), [design_claude.md:198-199](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.5_openclaw/1_memory_system/initial/design_claude.md#L198)). That combination will degrade recall quality quickly: duplicate/stale entries accumulate, newest entries may be outside the truncated window, and there is no safe way to do future compaction. V1 does not need a complex index, but it does need a minimal structured entry format and deterministic merge/truncation rules.

## Open Questions

1. Should app memory be recalled from both `currentPackage` and a goal-derived target package, using the existing `open_app` name-resolution logic as the fallback?
2. Is `remember_experience` intended as the main write path, or just a manual/explicit override on top of an automatic task-end retain pass?

## Recommendation

CHANGES_REQUESTED
