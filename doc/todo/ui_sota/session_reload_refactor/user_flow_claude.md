# Session User Flow — Refactor Design

Date: 2026-02-22
Status: Draft by Claude

---

## 1. Core Principle

A session is a conversation. Tasks come and go within a session.
The session stays alive until the user explicitly ends it.

---

## 2. User Flows

### Flow 1: Single Task (Happy Path)

```
User opens app
  → Sees empty chat with input field
User types "Play Fish Leong on YouTube" → Send
  → SessionState: Created → Running
  → CapsuleMode: Hidden → Running
  → Agent executes task (multiple turns)
  → Task completes
  → SessionState: Running → Idle
  → CapsuleMode: Running → Done (3s) → Hidden
  → Chat shows completion message
  → Input field ready for next message
```

### Flow 2: Follow-up After Task (THE FIX)

```
(Continuing from Flow 1 — session is Idle, chat shows history)

User types "Which singer did I just ask you to play?" → Send
  → SessionState: Idle → Running
  → CapsuleMode: Hidden → Running
  → Platform re-acquired (a11y/VD start)
  → Agent runs with FULL conversation history from previous task
  → Agent answers correctly using context
  → Task completes
  → SessionState: Running → Idle
  → CapsuleMode: Running → Done (3s) → Hidden
```

**Key difference from current behavior:** No new session. No reload. History is
in memory. LLM sees the full conversation.

### Flow 3: Multiple Follow-ups

```
User: "Open Settings and find the About section"
  → Task 1 runs → completes → Idle

User: "What Android version is shown?"
  → Task 2 runs (with Task 1 history) → completes → Idle

User: "Now go back to home screen"
  → Task 3 runs (with Task 1+2 history) → completes → Idle
```

Each task builds on the previous. History accumulates naturally.
Auto-compress kicks in if token budget is exceeded.

### Flow 4: Explicit New Session

```
(Session is in Idle state with history)

User taps hamburger → "New Session"
  → viewModel.startNewSession()
  → Op.Shutdown sent to current session
  → SessionState: Idle → Shutdown
  → currentSession = null
  → Chat cleared
  → history recording finalized

User types new message → creates fresh session (Created state)
```

### Flow 5: Session Resume from History

```
User taps hamburger → selects a past session
  → viewModel.resumeSession(sessionInfo)
  → Chat shows historical messages (read-only viewing)
  → selectedSessionForReload = sessionInfo

User types follow-up message
  → ensureSessionAndSend(text)
  → selectedSessionForReload is set → try checkpoint reload
  → If reloadable: hydrate HistoryManager, create session from checkpoint
  → If not: show error, suggest starting new session
```

This is the ONLY path that involves checkpoint reload. Normal follow-up
(Flow 2) never touches disk.

### Flow 6: Process Death Recovery

```
User completes task → checkpoint saved (IDLE_READY)
  → Android kills process (memory pressure, user swipes away)

User reopens app
  → No currentSession in memory
  → Chat is empty

User taps hamburger → selects the session from history
  → Same as Flow 5: checkpoint reload
```

### Flow 7: Task Interruption

```
User types task → agent starts running
User taps "Stop"
  → Op.Interrupt → agentRunner.stop()
  → Agent stops → TaskCompleted(USER_STOPPED)
  → SessionState: Running → Idle
  → Session alive, can accept follow-up

User types "Try again but this time..."
  → Idle → Running (with partial history from interrupted attempt)
```

### Flow 8: debug-run.sh

```
./scripts/debug-run.sh "Play Fish Leong on YouTube"
  → fresh_session=true → clearCurrentSession() → new session
  → Task runs → completes → Idle
  → Session stays alive (session NOT destroyed)
  → debug-run.sh sends stop_agent signal → Op.Shutdown
  → Session → Shutdown

If debug-run wants follow-up support:
  → Don't send stop_agent after completion
  → User can type follow-up in UI
  → Session is in Idle state, ready
```

---

## 3. Input Routing (Simplified)

### From ChatViewModel

```
sendMessage(text) {
    val session = sessionProvider()
    if (session != null && session.state != Shutdown) {
        // Session exists and is alive → submit directly
        session.submit(Op.UserInput(text))
    } else {
        // No session → request creation
        onSessionNeeded(text)
    }
}
```

### From MainActivity

```
ensureSessionAndSend(text) {
    val session = currentSession
    if (session != null) {
        when (session.state) {
            Created, Idle → session.submit(Op.UserInput(text))
            Running, Paused → enqueuePendingInput(text)
            Shutdown → { currentSession = null; retry }
        }
        return
    }
    // No session at all → create fresh (or reload if selected)
}
```

---

## 4. Session Lifecycle Summary

```
                    User opens app
                         │
                         ▼
              ┌──── Empty state ◄──── New Session
              │          │
              │          │ (type message)
              │          ▼
              │     Create session ◄── OR ── Reload from checkpoint
              │          │                    (history selection only)
              │          ▼
              │      ┌─ Task N ─┐
              │      │  Running │
              │      │  Paused  │
              │      └────┬─────┘
              │           │ TaskCompleted
              │           ▼
              │        Idle
              │        (ready for follow-up)
              │           │
              │     ┌─────┴─────┐
              │     │           │
              │  UserInput   Shutdown
              │  (follow-up)  (explicit)
              │     │           │
              │     ▼           ▼
              │   Task N+1    Done
              │     │
              │     └──► Idle ──► ...
              │
              └── (process killed → checkpoint on disk → history → reload)
```

---

## 5. Event Emission Changes

| Event | When emitted | Change |
|-------|-------------|--------|
| `SessionStarted` | First UserInput (Created → Running) | No change |
| `TaskStarted` | Each UserInput (Created/Idle → Running) | No change |
| `TaskCompleted` | Agent finishes | No change |
| `SessionCompleted` | Only on Shutdown | **Changed:** no longer on task completion |
| `StatusUpdate` | Errors, rejections | No change |

### Impact on CapsuleMode

The `AgentServiceEventHandler` maps:
- `TaskCompleted` → `capsuleState.onTaskCompleted()` → Done → Hidden
- `SessionCompleted` → `capsuleState.onSessionEnded()` → Hidden

With this change, `SessionCompleted` only fires on Shutdown, which is correct:
the UI should hide immediately on explicit session end.

`TaskCompleted` continues to drive the Done → Hidden transition for normal
task completion, which is unchanged.

---

## 6. Recording Service Interaction

No API change to `SessionRecordingService`. The recording continues across
tasks within the same session:

```
Task 1:
  initializeNewSession() → recordUserMessage() → startAgentMessage() → ... → completeAgentMessage()

Task 2 (follow-up):
  recordUserMessage() → startAgentMessage() → ... → completeAgentMessage()
  (No initializeNewSession — same session continues)

Shutdown:
  completeSession() → clearSession()
```

`initializeNewSession()` is called once on first task (from Created state).
Subsequent tasks in the same session don't re-initialize — they just continue
appending to the same `SessionRecord`.
