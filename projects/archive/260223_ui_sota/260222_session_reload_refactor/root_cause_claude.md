# Root Cause Analysis: Follow-up Fails After Task Completion

Date: 2026-02-22
Status: Analysis complete

## 1. Observed Symptom

After a task completes successfully (e.g., "play a Fish Leong song on YouTube"), the user
sends a follow-up ("Which singer's song did I just ask you to play?"). The agent responds
saying it has no record of the previous conversation. (screenshot_session_continue_4.png)

## 2. Evidence from Debug Logs (run_20260222_181746)

```
18:18:22.581  AgentSession: Emitted event: TaskCompleted
18:18:23.106  CheckpointCoordinator: Flushed IDLE_READY checkpoint for d6c58765...
18:18:23.127  AgentSession: Emitted event: SessionCompleted
18:18:23.127  AgentSession: Task completed (reason=GOAL_ACHIEVED). Session completed and cleaned up.
18:18:23.448  AgentService: submitOp: Shutdown, session=null  ← session already gone
...
18:18:37.762  SessionServices: Created LLMClient: ChatCompletionClient  ← brand new LLM
18:18:37.762  SessionHistoryBootstrap: Created history stack with token budget=18000  ← EMPTY history
18:18:37.763  AgentSession: Received Op: UserInput(...) (current state: Created)  ← fresh session
```

The checkpoint IS saved to disk. But it's never loaded. A brand-new session is created instead.

## 3. Causal Chain

```
handleAgentComplete()
  ├─ checkpointCoordinator.flushIdleReady()  → writes context-*.json ✓
  ├─ _state.value = SessionState.Completed   → terminal state, rejects future UserInput
  ├─ services.cleanup()                      → destroys platform/LLM/tools
  └─ closeChannelWithDelay()                 → event stream ends

onTaskCompleted callback (ChatViewModel → MainActivity)
  └─ currentSession = null                   → session reference cleared

User types follow-up → ChatViewModel.sendMessage()
  └─ sessionProvider() returns null → onSessionNeeded → ensureSessionAndSend()

ensureSessionAndSend()
  └─ currentSession == null → enter session creation
     └─ selectedSessionForReload == null     ← NEVER SET for non-history sessions
        └─ createFreshSession()              → brand new HistoryManager, no context
```

## 4. Root Cause

`selectedSessionForReload` is only populated when the user manually selects a session
from the history drawer. For auto-started sessions (from debug-run, or from normal first
message), it's always null.

The only reload path requires an explicit user selection. There is no automatic path
to reload the just-completed session's checkpoint.

## 5. Why the Design Got Here

The original design (design.md) specified two independent concerns that weren't properly
composed:

1. **"Persist checkpoint on task completion"** — implemented ✓
2. **"Reload from checkpoint when no active session"** — partially implemented, gated
   behind `selectedSessionForReload` which is only set by history UI

The code review (code_review_codex.md) identified this in Finding #1 (reload overwritten
by first message) and Finding #3 (fresh session still reloads), but the fixes
over-corrected by removing the auto-reload and only keeping explicit-selection reload.
This created a gap: the common follow-up path has no reload mechanism at all.

## 6. Why This Is a State Machine Problem

The fundamental design error is treating task completion as a session-terminal event.

Current mental model:
```
Session = single task execution → complete → dead
Follow-up = new session (requires reload dance)
```

Correct mental model:
```
Session = ongoing conversation → task1 → idle → task2 → idle → ...
Session end = explicit user action (new session / app killed)
```

The CapsuleMode state machine already has this right: `Done` is transient (3s → Hidden),
not terminal. The agent is ready for the next task. But `SessionState.Completed` is
terminal and destroys everything.
