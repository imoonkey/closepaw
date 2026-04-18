# Session / Task User Flows

> User-facing flows for session lifecycle, task execution, follow-up, and recovery.
> Last updated: 2026-04-09 (commit: 9ab9b5f)

## 1. Session vs Task

A **session** is a conversation thread. A **task** is one execution run within a session.

```
Session ─────────────────────────────────────────────────────────────►
  │                                                                   │
  Task 1 ──────────┤  (Idle)  ├── Task 2 ─────────┤  (Idle)  ├───── Shutdown
  "Open YouTube"      5min       "Play a song"       5min         (timeout or
                     timeout                        timeout        explicit)
```

The user does not manage sessions explicitly. Sessions start implicitly on first input and end via idle timeout or explicit stop.

## 2. First Task Flow

```
User types goal in capsule input → taps Send
  │
  ├─ MainActivity.ensureSessionAndSend()
  │   ├─ Validates API keys + overlay + a11y permissions
  │   ├─ Creates AgentSession (or reloads from checkpoint)
  │   ├─ Submits Op.UserInput
  │
  ├─ AgentSession: Created → Running
  │   ├─ platform.start() — acquires VirtualDisplay / a11y resources
  │   ├─ Emits SessionStarted + TaskStarted
  │   ├─ Starts agent loop
  │
  ├─ UI: ChatViewModel receives TaskStarted
  │   ├─ Adds user message bubble
  │   ├─ Adds agent message (Thinking indicator)
  │   ├─ Capsule → Running mode (blue dot)
  │
  ├─ Agent executes turns
  │   ├─ ThoughtUpdate → capsule shows current thought
  │   ├─ MessageDelta → agent bubble streams text
  │   ├─ ActionProposed / ActionExecuted → action cards in chat
  │
  └─ Agent completes
      ├─ AgentSession: Running → Idle
      │   ├─ Emits TaskCompleted
      │   ├─ Flushes IDLE_READY checkpoint
      │   ├─ agentRunner.clear() — platform stays alive for follow-up
      │   ├─ Schedules 5-min idle timeout
      │
      ├─ UI: TaskCompleted → completion text appended to chat
      │   ├─ Capsule → Done (3s) → Hidden
      │   ├─ Recording service finalizes agent message to disk
      │
      └─ Session alive in Hot Idle, awaiting follow-up
```

## 3. Follow-Up Task Flow (Hot Idle)

After task completion, the session stays alive in `Idle` state. The user can send follow-up input without starting a new session.

```
User types follow-up in capsule input → taps Send
  │
  ├─ MainActivity.ensureSessionAndSend()
  │   ├─ currentSession exists and state == Idle
  │   ├─ Submits Op.UserInput to existing session
  │
  ├─ AgentSession: Idle → Running
  │   ├─ Cancels idle timeout
  │   ├─ platform.start() — idempotent (no-op if already running)
  │   ├─ Emits TaskStarted (NOT SessionStarted — same session)
  │   ├─ Starts agent with full conversation history in memory
  │
  ├─ UI: same as first task (thought, streaming, actions)
  │
  └─ Agent completes → Idle again → cycle repeats
```

Key difference from first task: no `SessionStarted` event, no recording service init, conversation context preserved in memory (no checkpoint reload needed). Platform is **not** re-acquired — `platform.start()` is idempotent and the platform stayed alive across Hot Idle; release happens only on Shutdown via `services.cleanup()`.

## 4. Takeover / Resume Flow

```
User taps [Takeover] in capsule
  │
  ├─ Op.Takeover submitted
  ├─ Capsule → TakeoverPending (amber dot, "Handing over...")
  ├─ agentRunner.pause() — agent finishes current action
  ├─ Deferred completes → emit SessionTakeover
  ├─ Capsule → Takeover (amber dot, [Resume] button)
  │
  ├─ User interacts with phone directly
  │
  └─ User taps [Resume]
      ├─ Op.Resume submitted
      ├─ agentRunner.resume()
      ├─ Emit SessionResumed
      └─ Capsule → Running (blue dot)
```

Takeover is cooperative: the agent always finishes its current action before pausing. `TakeoverPending` reflects this real latency.

## 5. Supplement Flow

```
While agent is Running or Paused:
  │
  └─ User types in capsule Row 3 input → taps [Add note]
      ├─ Op.Supplement(text) submitted
      ├─ Text injected into HistoryManager (visible to agent on next turn)
      ├─ Emit SupplementReceived
      ├─ Chat: user message bubble added
      ├─ Recording: previous agent message finalized, user message recorded
      └─ Capsule flash: "Received" (between turns) or "Received, will apply next step" (mid-turn)
```

Supplements are passive — they don't interrupt the current turn.

## 6. Session Shutdown Flows

### 6.1 Idle timeout (automatic)

```
Task completes → Idle → 5 minutes pass with no input
  │
  └─ idleTimeoutJob fires
      ├─ handleShutdown()
      ├─ Flush CLOSED checkpoint
      ├─ services.cleanup() — all resources released
      ├─ Emit SessionCompleted(reason=IDLE_TIMEOUT)
      └─ Capsule: onSessionEnded → Hidden (immediate)
```

### 6.2 User stops agent (explicit)

```
User taps [Stop] in capsule
  │
  └─ Op.Shutdown submitted
      ├─ handleShutdown()
      ├─ Same cleanup as idle timeout
      └─ Emit SessionCompleted(reason=USER_STOPPED)
```

### 6.3 New session (from header or history drawer)

```
User taps [+] in header or [+ New Conversation] in drawer
  │
  ├─ coordinator.clearSession() — shutdown + teardown of old session
  ├─ ChatSessionHistoryController: clearConversation (UI reset only)
  │   └─ No recording session placeholder created — history entry
  │      is created when the actual AgentSession starts
  └─ UI ready for fresh input
```

## 7. Session Reload Flow (Process-Death Recovery)

When the app process dies and restarts, sessions can be restored from checkpoint:

```
User selects a session from history drawer
  │
  ├─ SessionHistoryManager.loadSessionByFileName()
  │   └─ Returns ResumedSessionData with session file content
  │
  ├─ viewModel.resumeSession() — shows historical messages in chat
  │
  ├─ User sends input
  │   ├─ MainActivity: selectedSessionForReload != null
  │   ├─ tryReloadSelectedSession()
  │   │   ├─ Read context-*.json from SessionStorage
  │   │   ├─ Validate: schemaVersion == 2, checkpoint isReloadable()
  │   │   ├─ AgentSession.reload(snapshot)
  │   │   │   ├─ Hydrate HistoryManager (replaceAll)
  │   │   │   ├─ Restore TodoState + ScratchpadState
  │   │   │   └─ Return session in Created state
  │   │   └─ Resume recording service with existing session file
  │   │
  │   └─ Submit Op.UserInput → Created → Running (normal first-task flow)
  │
  └─ If reload fails (no checkpoint, incompatible schema):
      ├─ Falls back to createFreshSession() silently
      └─ User gets a fresh session (no error toast)
```

Reload is a **recovery-only path**. Normal follow-ups use Hot Idle (in-memory, no disk I/O).

## 7.1 Cold-Idle Auto-Reload

When a session shuts down (idle timeout, explicit stop, or service destruction), the user may still send a follow-up expecting continuity. The system auto-reloads from checkpoint transparently:

```
Session is Shutdown (cold idle) — user is still on the session's chat page
  │
  User types follow-up → taps Send
  │
  ├─ MainActivity.ensureSessionAndSend()
  │   ├─ coordinator.submit() returns SESSION_DEAD
  │   ├─ No selectedSessionForReload set (not from sidebar)
  │   │
  │   ├─ Auto-reload path:
  │   │   ├─ coordinator.consumeDeadSessionFileName() → fileName captured at Shutdown
  │   │   ├─ sessionHistoryManager.getCurrentSessionId() → still set from before
  │   │   ├─ Construct SessionInfo(id, fileName, ...)
  │   │   ├─ Set coordinator.selectedSessionForReload
  │   │   ├─ autoReload = true
  │   │   │
  │   │   └─ createOrReloadSession(autoReload=true)
  │   │       ├─ tryReloadSelectedSession() — same path as section 7
  │   │       └─ Submit Op.UserInput → normal first-task flow
  │   │
  │   └─ If auto-reload fails (checkpoint missing/corrupted):
  │       ├─ Silent fallback to createFreshSession()
  │       └─ User gets a fresh session (no error toast)
  │
  └─ Contrast with explicit sidebar reload (section 7):
      └─ On failure: toast + abort (user chose specific session, should know it failed)
```

Key details:
- **fileName capture**: `SessionCoordinator` saves `recordingService.getCurrentFileName()` right before teardown in the Shutdown branch. One-shot via `consumeDeadSessionFileName()`.
- **FORCE_FRESH bypass**: `debug-run.sh` and eval runner send `fresh_session=true` → `FORCE_FRESH` policy skips auto-reload entirely, ensuring clean eval runs.
- **Transparent to user**: no UI indication of the reload — the follow-up just works as if the session never died.

## 8. Session History Sidebar

### 8.1 Active session tracking

`SessionHistoryManager` tracks the active session via `externalActiveSessionId`, set by `MainActivity` at session lifecycle boundaries:

| Lifecycle Event | Action |
|----------------|--------|
| Session created or reloaded | `setActiveSessionId(session.sessionId)` |
| Service session rebound | `setActiveSessionId(serviceSession.sessionId)` |
| Session cleared | `setActiveSessionId(null)` |
| History session selected for viewing | `setActiveSessionId(null)` |

The sidebar marks the active session to distinguish it from historical entries.

### 8.2 Recording coordination

Two `SessionRecordingService` instances exist:

| Instance | Location | Purpose |
|----------|----------|---------|
| Per-session RS | `SessionServices.recordingService` | Records events (messages, actions, screens) to session file |
| SHM RS | `SessionHistoryManager.recordingService` | Manages session file listing for sidebar |

The per-session RS is the one that captures `AgentEvent` data. On `TaskCompleted`, `completeAgentMessage()` finalizes the agent message buffer to ensure the on-disk session file is complete before Hot Idle.

## 9. debug-run.sh Compatibility

`debug-run.sh` monitors agent execution and stops after the first task:

```
# Watches for either event:
grep -qE "AgentSession: Emitted event: (SessionCompleted|TaskCompleted)"
```

With Hot Idle, only `TaskCompleted` fires on task completion. `SessionCompleted` fires on full shutdown (idle timeout / explicit stop). The script detects either event and sends `stop_agent` broadcast.

## Related Docs

- [State Machine](state_machine.md) -- formal state model, transition rules, resource ownership
- [Capsule State Machine](../capsule/state_machine.md) -- UI state machine (CapsuleMode)
- [Capsule User Flows](../capsule/user_flows.md) -- location x platform interaction matrix
- [User Interaction](../user_interaction.md) -- in-app UI, event mapping, page layout
- [Session Infrastructure](../../infra/session.md) -- SessionServices, AgentRunner, UserResponseChannel
