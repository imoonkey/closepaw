# Session + Protocol Review

## Summary

Session lifecycle management, Op processing, and AgentEvent emission. This layer is the contract between UI and the agent loop.

## High-risk issues (must-fix)

None identified in this layer alone.

## Medium issues (should-fix)

### `Op.Start.config` is ignored
- Why it matters: UI-provided session settings (model, approval mode, delays) are silently discarded, contradicting the protocol contract.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleStart()` uses only `op.goal`, not `op.config`.
- Fix: either apply `op.config` (and rebuild `SessionServices` if required) or remove `config` from `Op.Start` to avoid misleading callers.

### Session event flow never completes on normal finish
- Why it matters: `events` collectors can leak across sessions, especially when running multiple sessions in one service lifetime.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleAgentComplete()` does not close `eventChannel`.
- Fix: close the channel after emitting `SessionCompleted`, or expose a session-scope job to cancel collectors.

### Interrupt does not cancel in-flight work
- Why it matters: `Op.Interrupt` promises to abort the current turn, but `agent.stop()` only halts future turns. LLM calls and tool execution continue.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleInterrupt()` calls `agent?.stop()`.
- Fix: plumb a cancellation token into `Turn`/`ToolRouter` and cancel in-flight work, or document Interrupt as “stop after current action.”

### Missing approval resolution events
- Why it matters: the protocol defines `ApprovalResolved`, but the session never emits it, so UIs can’t update approval UI state.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleApproval()` only calls `toolRouter.resolveApproval(...)`.
- Fix: emit `AgentEvent.ApprovalResolved` when a decision is applied.

### `Op.UserInput` is a no-op
- Why it matters: protocol advertises user guidance, but `UserInput` is not forwarded to the agent or history.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleUserInput()` TODO.
- Fix: append to history and re-run a turn, or explicitly mark the op unsupported in the protocol docs.

## Low-risk suggestions (nice-to-have)

### Duplicate completion events on shutdown
- Why it matters: `Op.Shutdown` emits `SessionCompleted` even after a normal completion, causing duplicate end signals.
- Location: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt` — `handleShutdown()` always emits `SessionCompleted`.
- Fix: guard against emitting completion if state is already `Completed`.
