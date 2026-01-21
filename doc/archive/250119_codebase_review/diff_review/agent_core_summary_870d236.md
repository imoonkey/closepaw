# Diff Review - Agent Core Fixes (870d236)

1) Summary (what does it do)
- Migrates tool calling to the OpenAI Responses API with structured tool calls.
- Adds `complete_task` as a first-class tool and updates completion guidance.
- Propagates post-action observations to avoid double captures and refresh snapshots between tools.
- Uses session-configured models and improves network error categorization.
- Adds thread-safe pause/resume controls and generates tool schemas dynamically.

2) High-risk issues (must-fix)
- Completion can trigger on any text-only response, even without `complete_task`.
  - Why it matters: A plain-text assistant reply (no tool calls) will end the session even if the goal is not achieved, reintroducing premature termination.
  - Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` (`processResponse`)
  - Fix: Require `complete_task` to mark completion, or gate “no tool calls” completion behind a stricter condition (e.g., explicit DONE marker at start of line or a config flag).

**Team Note**: This is intended. It could be a proper complete, or some other reasons to stop, e.g., “I need you to log in first,”. In these cases, stop the next turn are good chocies. We will go with this for now, and update if needed. Update doc/main to reflect this if needed.

3) Medium issues (should-fix)
- Tool call ID mismatch persists between LLM call_id and ToolRouter callId.
  - Why it matters: History records `call_id` from the model, but ActionExecuted/approval flows use ToolRouter’s generated ID. This makes event ↔ history correlation unreliable and can break UI debugging.
  - Location: `Agent.kt` uses `toolCall.id` for history but `result.callId` for events; `ToolRouter.kt` always generates a new callId.
  - Fix: Allow ToolRouter to accept a caller-provided callId (LLM call_id), or emit both IDs in events and store a mapping.

**Team Note**: fix, just use llm call's tool call id, no need to keep a separate id.


- Snapshot refresh is skipped when post-action observation capture fails.
  - Why it matters: If a tool returns success but `observation` is null (capture failed or non-BaseTool), `captureObservation()` runs but `currentSnapshot` is not updated, so subsequent tools may use stale indices.
  - Location: `Agent.kt` updates `currentSnapshot` only from `result.observation`.
  - Fix: Let `captureObservation()` return the snapshot or update `currentSnapshot` on fallback captures.

**Team Note**: fix.

- Recoverable error detection is too broad and can retry on non-network bugs.
  - Why it matters: Any non-DNS error without “internet” in the message becomes recoverable, which can lead to retry loops on logic errors.
  - Location: `Agent.kt` `TurnOutcome.Error(recoverable = ...)`
  - Fix: Make recoverable strictly depend on transient network classes (timeouts, connection reset/refused) or propagate explicit error types from `LLMClient`.

**Team Note**: add a TODO, but skip fix for now.

4) Low-risk suggestions (nice-to-have)
- [Skip] Store and use `ResponsesResult.responseId` as `previous_response_id` to reduce prompt size and improve conversation continuity.
- [Fix] Remove unused import `FunctionDefinition` from `ToolRegistry.kt`.
- [Fix] Skip screen capture when only `complete_task` was executed, to save latency.
