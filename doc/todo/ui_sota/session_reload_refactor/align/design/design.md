# Session Lifecycle Refactor — Aligned Design

Date: 2026-02-22
Status: Aligned (Claude + Codex APPROVE). Updated with idle timeout strategy.

---

## 1. Consensus

1. **Session = conversation thread; task = one execution run.**  
2. Follow-up failure is caused by session-level semantics, not tool behavior.  
3. `SessionState.Completed` as terminal is wrong for multi-task conversation.  
4. `TaskCompleted` should not imply conversation ended.  

---

## 2. Root Cause (Top-Down)

The current implementation mixes three different concerns:

1. **Thread binding**: which conversation thread current UI input belongs to  
2. **Runtime lease**: whether a live `AgentSession` runtime object is held  
3. **Task execution**: running/paused/idle task state

Today these are spread across:
- `MainActivity.currentSession`
- `MainActivity.selectedSessionForReload`
- `SessionRecordingService` current record pointer
- `AgentSession.state`
- checkpoint state on disk

Result: same user intent ("continue this chat") is inferred from different variables,
so behavior diverges (hard failure toast vs silent fresh session).

---

## 3. State Model (Session-Level)

### 3.1 Canonical axes

1. `ThreadBinding`
- `None`
- `Bound(sessionRef, mode=Reloadable|ViewOnly)`

2. `RuntimeLease`
- `Released`
- `Acquired(sessionId)`

3. `TaskState`
- `Idle`
- `Running(taskId)`
- `Paused(taskId)`

### 3.2 Minimal operational states

With Hot Idle (see §5), `READY_COLD` only occurs on process-death recovery
(via history drawer selection). The normal lifecycle is EMPTY → READY → RUNNING.

- `EMPTY` = `None + Released + Idle` — no session
- `READY_COLD` = `Bound + Released + Idle` — history selected, needs reload
- `READY` = `Bound + Acquired(partial) + Idle` — between tasks, platform released,
   conversation state in memory (Hot Idle)
- `RUNNING` = `Bound + Acquired + Running` — task executing
- `PAUSED` = `Bound + Acquired + Paused` — takeover

`Completed` is removed from session-level state and kept as a task event only.

Note: `READY_COLD` → `READY` transition happens transparently on first UserInput
(reload checkpoint → hydrate → acquire platform). For Hot Idle, the normal
TaskCompleted destination is `READY` (not `READY_COLD`).

---

## 4. Target Transition Rules

1. `NewSession` -> bind new thread -> `EMPTY` → first UserInput creates session → `RUNNING`
2. `SelectHistory` -> bind selected thread -> `READY_COLD` (`ViewOnly` if no valid snapshot)
3. `UserInput` in `READY_COLD`
   - `Reloadable`: reload from checkpoint → acquire platform → `RUNNING`
   - `ViewOnly`: reject with explicit UI message (no silent fallback)
4. `UserInput` in `READY` (Hot Idle follow-up)
   - Cancel idle timeout → re-acquire platform → `RUNNING` (no reload, history in memory)
5. `TaskCompleted` -> checkpoint flush -> platform.stop() -> `READY` (Hot Idle) -> start idle timeout
6. `Takeover` / `Resume`: `RUNNING <-> PAUSED`
7. `CloseThread` / explicit session end -> cancel idle timeout -> `EMPTY`
8. `IdleTimeout` expires in `READY` -> auto-Shutdown -> `EMPTY`

---

## 5. Key Design Choice: Hot Idle vs Cold Idle

Two valid implementations exist:

1. **Hot Idle** (Claude proposal, recommended)
Release platform resources (VD/a11y), keep lightweight conversation state
(HistoryManager, TodoState, ScratchpadState, LLM client) in memory.

2. **Cold Idle** (Codex proposal)
Release all runtime; keep only thread binding + checkpointed state on disk.

### RESOLVED: Hot Idle (with platform release)

**Evidence supporting Hot Idle:**

1. **Cost of kept resources is negligible:**
   - HistoryManager: ~100KB-1MB (token-budgeted, auto-compressed)
   - TodoState + ScratchpadState: ~2KB
   - LLM client (cloud): stateless HTTP client wrapper, no connection held
   - Total: well under 2MB — trivial on Android

2. **Cost of Cold Idle follow-up is high:**
   - `SessionServices.create()`: creates LLMClient, ToolRouter, HistoryManager, TraceRecorder
   - Disk I/O: read + deserialize `context-*.json`
   - For local LLM: `llmClient.cleanup()` releases model weights → re-load on follow-up
   - All of this on every follow-up, even for quick "what did you just do?" questions

3. **Expensive resources ARE released (both approaches agree):**
   - `AndroidPlatform.stop()`: releases VirtualDisplay + ImageReader (the heavy resources)
   - `AccessibilityPlatform.stop()`: no-op (already lightweight)
   - `agentRunner.clear()`: releases agent loop state

4. **Hot Idle eliminates the class of bugs that caused this issue:**
   - The entire follow-up bug exists because the reload path didn't activate
   - Cold Idle keeps reload on the critical path for every follow-up
   - Hot Idle makes reload a recovery-only path (process death)
   - Fewer critical paths = fewer failure modes

5. **Checkpoint still serves process-death recovery:**
   - `flushIdleReady()` persists on every task completion
   - If process is killed, history-drawer selection triggers reload
   - Reload path (`replaceAll()`) is verified byte-fidelity safe

**What "release runtime resources" means in practice:**
The original requirement is satisfied by releasing the expensive resources
(platform, VD, agent loop). The lightweight conversation state is not a
"runtime resource" in the resource-management sense — it's conversation data.

### 5.1 Resource Release Timeline

Hot Idle keeps lightweight state in memory. The question is: when does it get released?

```
TaskCompleted
  ├─ immediate: platform.stop()        ← VirtualDisplay, ImageReader (expensive)
  ├─ immediate: agentRunner.clear()    ← agent loop references
  ├─ kept: HistoryManager, TodoState, ScratchpadState, LLM client (~2MB total)
  │
  ├─ if follow-up within timeout → Idle → Running (instant, no reload)
  │
  └─ if no follow-up within timeout → auto-Shutdown → services.cleanup()
                                       (full release: history, LLM, tools, trace)

Explicit triggers also release everything:
  - User taps "New Session" → Op.Shutdown → services.cleanup()
  - Activity.onDestroy()    → sessionScope.cancel()
```

### 5.2 Idle Timeout

After task completion, session enters `Idle` and starts a timeout timer.

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `IDLE_TIMEOUT_MS` | 5 minutes (300,000ms) | Long enough for natural follow-up, short enough to not hog memory indefinitely |

**Behavior:**
- `TaskCompleted → Idle`: start timer
- `Idle + UserInput → Running`: cancel timer (user is still engaged)
- `Idle + timeout expires`: auto-`Shutdown` (full cleanup, emit `SessionCompleted`)
- `Idle + explicit Shutdown`: cancel timer, immediate cleanup

**Implementation:** Single `Job` in `AgentSession`, launched on Idle entry,
cancelled on any state exit from Idle.

```kotlin
private var idleTimeoutJob: Job? = null

private fun scheduleIdleTimeout() {
    idleTimeoutJob?.cancel()
    idleTimeoutJob = scope.launch {
        delay(IDLE_TIMEOUT_MS)
        handleShutdown()  // reuses existing shutdown path
    }
}

private fun cancelIdleTimeout() {
    idleTimeoutJob?.cancel()
    idleTimeoutJob = null
}
```

### 5.3 Resource Ownership Summary

| Resource | Released on TaskCompleted? | Released on idle timeout? | Released on Shutdown? |
|----------|--------------------------|--------------------------|----------------------|
| VirtualDisplay + ImageReader | **Yes** (platform.stop()) | Yes | Yes |
| AgentRunner state | **Yes** (clear()) | Yes | Yes |
| HistoryManager | No (needed for follow-up) | **Yes** | **Yes** |
| LLM client | No (cheap, reused) | **Yes** | **Yes** |
| TodoState / ScratchpadState | No (conversation state) | **Yes** | **Yes** |
| ToolRouter | No (cheap) | **Yes** | **Yes** |
| TraceRecorder | No (may append) | **Yes** | **Yes** |
| Event stream (SharedFlow) | No (open for follow-up events) | **Yes** (closeChannelWithDelay) | **Yes** |

---

## 6. MainActivity/Session Orchestration Changes

Introduce single owner: `SessionThreadCoordinator` (name can vary).

Responsibilities:
1. Own one session-level state machine
2. Accept events (`onSend`, `onSelectSession`, `onNewSession`, `onTaskCompleted`, `onShutdown`)
3. Decide create/reload/release operations
4. Expose derived UI capability (`canContinue`, `isViewOnly`)

`MainActivity` should stop directly combining `currentSession` + `selectedSessionForReload`
branch logic.

---

## 7. Failure Policy

On reload failure for a bound thread:
1. transition to `Bound(..., ViewOnly)`
2. block execution from that thread
3. show explicit action choices: `Start New Session` or `Select Another Session`

Never silently create a fresh thread while user believes they are continuing history.

---

## 8. Event Semantics

- `TaskCompleted`: emitted per task end (keep)
- `SessionCompleted`: only when explicit thread close/shutdown (not each task end)

Capsule behavior stays consistent:
- task done -> `Done -> Hidden`
- session close -> hidden + cleared binding

---

## 9. Implementation Plan

### Stage 1: State machine fix (fixes the follow-up bug)

| File | Change |
|------|--------|
| `protocol/SessionState.kt` | Remove `Completed` variant; update doc comment |
| `session/AgentSession.kt` | `handleAgentComplete()`: transition to `Idle` (not `Completed`); call `platform.stop()` + `agentRunner.clear()`; do NOT call `services.cleanup()` / `closeChannelWithDelay()` / emit `SessionCompleted`. Add `scheduleIdleTimeout()`. `handleUserInput()`: accept `Idle` state (re-acquire platform, start new task). Remove `Completed` rejection. Cancel idle timeout on Idle→Running. `handleShutdown()`: cancel idle timeout. Remove `completionEmitted` field (only one Shutdown path now). |
| `app/MainActivity.kt` | `onTaskCompleted` callback: remove `currentSession = null`. `ensureSessionAndSend()`: remove `Completed` from terminal check. |
| `ui/chat/ChatViewModel.kt` | `sendMessage()`: remove `SessionState.Completed` check. |

### Stage 2: SessionThreadCoordinator extraction (optional, structure improvement)

1. Extract state machine from `MainActivity.ensureSessionAndSend()` into coordinator
2. Move `selectedSessionForReload` logic into coordinator
3. Expose derived capabilities (`canContinue`, `isViewOnly`)

### Stage 3: Error/UX hardening (optional)

1. Add `ViewOnly` explicit mode and messaging
2. Remove all silent fresh fallback paths

---

## 10. Open Questions

1. **RESOLVED: Hot vs Cold Idle** → Hot Idle with platform release (see §5)
2. **RESOLVED: Resource release** → Idle timeout (5min) auto-Shutdown (see §5.2)
3. **SessionThreadCoordinator scope**: Deferred to Stage 2. Not needed for bug fix.
4. **`debug-run.sh` resume policy**: Current stop_agent-after-completion still works
   (sends Op.Shutdown → full cleanup). Follow-up testing: skip stop_agent, session
   stays in Idle until timeout.
5. **Mapping to code**: `SessionState.Idle` is the single "between tasks" state.
   `READY_COLD` is handled at the `MainActivity` level (checkpoint reload before
   creating `AgentSession`), not as a separate `SessionState` variant.
