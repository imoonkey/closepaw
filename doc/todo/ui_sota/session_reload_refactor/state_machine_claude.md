# Session State Machine — Refactor Design

Date: 2026-02-22
Status: Draft by Claude

---

## 1. State Vector (Single Dimension)

Session behavior is determined by one state:

`SessionState`: `Created | Running | Paused | Idle | Shutdown`

Ownership: `AgentSession._state` (MutableStateFlow)

### Derived States (not independently stateful)

| Derived | Source | Meaning |
|---------|--------|---------|
| `canAcceptInput` | Created, Idle | Session will accept Op.UserInput |
| `isActive` | Running, Paused | Agent is engaged in a task |
| `isTerminal` | Shutdown | Session is dead |
| `needsPlatformStart` | Created, Idle | Platform.start() required before task |

---

## 2. States

```kotlin
sealed interface SessionState {
    /** Session initialized, platform not started. First UserInput triggers start. */
    data object Created : SessionState

    /** Agent is actively executing a task. */
    data object Running : SessionState

    /** Agent paused at user's request (takeover). */
    data object Paused : SessionState

    /** Task completed. Session ready for follow-up input. */
    data object Idle : SessionState

    /** Session ended. Terminal state. All resources released. */
    data object Shutdown : SessionState
}
```

---

## 3. Transitions

### 3.1 From Created

| Event | Target | Guard | Side Effects |
|-------|--------|-------|--------------|
| `Op.UserInput` | Running | — | initRecording, platform.start(), emit SessionStarted + TaskStarted, start agent |
| `Op.Shutdown` | Shutdown | — | flushClosed, cleanup, emit SessionCompleted |

### 3.2 From Running

| Event | Target | Guard | Side Effects |
|-------|--------|-------|--------------|
| TaskCompleted | Idle | — | emit TaskCompleted, flushIdleReady, platform.stop(), agentRunner.clear() |
| `Op.Pause` (Takeover) | Paused | — | agentRunner.pause(), emit SessionTakeover |
| `Op.Interrupt` | Running→(agent stops)→Idle | — | agentRunner.stop() (leads to TaskCompleted with USER_STOPPED) |
| `Op.Shutdown` | Shutdown | — | agentRunner.shutdown(), flushClosed, cleanup, emit SessionCompleted |
| `Op.UserInput` | (rejected) | — | emitStatus("Agent is busy") |
| `Op.Supplement` | Running | — | addItem to history, emit SupplementReceived |

### 3.3 From Paused

| Event | Target | Guard | Side Effects |
|-------|--------|-------|--------------|
| `Op.Resume` | Running | — | agentRunner.resume(), emit SessionResumed |
| `Op.Shutdown` | Shutdown | — | agentRunner.shutdown(), flushClosed, cleanup |
| `Op.Supplement` | Paused | — | addItem to history, emit SupplementReceived |
| `Op.UserInput` | (rejected) | — | emitStatus("Agent is paused") |

### 3.4 From Idle

| Event | Target | Guard | Side Effects |
|-------|--------|-------|--------------|
| `Op.UserInput` | Running | — | platform.start(), emit TaskStarted, start agent |
| `Op.Shutdown` | Shutdown | — | flushClosed, cleanup, emit SessionCompleted |

Key difference from Created: no SessionStarted emission, no recording init (already done).

### 3.5 From Shutdown

| Event | Target | Guard | Side Effects |
|-------|--------|-------|--------------|
| (any) | (rejected) | — | Log warning, no-op |

---

## 4. Transition Diagram

```
                    ┌──────────────────────────────────────────────────┐
                    │                                                  │
                    ▼                Op.Shutdown                       │
                 Created ──────────────────────────────► Shutdown      │
                    │                                     ▲  ▲        │
                    │ Op.UserInput                        │  │        │
                    │ (first task)                        │  │        │
                    ▼                Op.Shutdown          │  │        │
                 Running ────────────────────────────────┘  │        │
                    │  ▲                                     │        │
                    │  │ Op.Resume                           │        │
                    │  │                Op.Shutdown           │        │
          Op.Pause  │  └─── Paused ─────────────────────────┘        │
                    │                                                  │
                    │ TaskCompleted                                    │
                    │                                                  │
                    ▼                                                  │
                  Idle ────────────── Op.Shutdown ─────────────────────┘
                    │
                    │ Op.UserInput
                    │ (follow-up task)
                    │
                    └───────────────► Running
```

---

## 5. Session vs Task Lifecycle

```
Session lifecycle:
  Created ──► [Task 1] ──► Idle ──► [Task 2] ──► Idle ──► ... ──► Shutdown

Task lifecycle (within session):
  TaskStarted ──► Running ──► TaskCompleted
                    │
                    ├──► Paused ──► Running (resume)
                    │
                    └──► TaskCompleted (USER_STOPPED via Interrupt)
```

A session can contain 0..N tasks. Each task has its own `taskId`.

---

## 6. State × Op Matrix

| State \ Op | UserInput | Supplement | Takeover | Resume | Interrupt | Shutdown | UserResponse | Approve |
|------------|-----------|------------|----------|--------|-----------|----------|--------------|---------|
| Created | → Running | reject | reject | reject | reject | → Shutdown | reject | reject |
| Running | reject (busy) | accept | → Paused | reject | → (agent stops) | → Shutdown | accept | accept |
| Paused | reject (paused) | accept | reject | → Running | reject | → Shutdown | reject | reject |
| Idle | → Running | reject | reject | reject | reject | → Shutdown | reject | reject |
| Shutdown | reject | reject | reject | reject | reject | no-op | reject | reject |

---

## 7. Comparison: CapsuleMode ↔ SessionState

| Session event | SessionState transition | CapsuleMode transition |
|---------------|------------------------|----------------------|
| First UserInput | Created → Running | Hidden → Running |
| TaskCompleted | Running → Idle | Running → Done (3s → Hidden) |
| Follow-up UserInput | Idle → Running | Hidden → Running |
| Op.Takeover | Running → Paused | Running → TakeoverPending → Takeover |
| Op.Resume | Paused → Running | Takeover → Running |
| Op.Shutdown | * → Shutdown | * → Hidden |

The state machines are now isomorphic at the task boundary level.

---

## 8. What Was Removed

### `SessionState.Completed` — removed

Previously: terminal state after task completion. Blocked all input. Required
complex checkpoint-reload dance for follow-up.

Now: `Idle` serves this role. Non-terminal. Accepts input. No reload needed.

The `CompletionReason` that was attached to `SessionCompleted` events is preserved
in `TaskCompleted` events. Task-level completion reporting is unaffected.

`SessionCompleted` events are now only emitted on `Shutdown` transitions,
which is the true session end.

---

## 9. Invariants

| # | Invariant | Enforcement |
|---|-----------|-------------|
| 1 | Only `Shutdown` is terminal | `handleUserInput` accepts Created + Idle |
| 2 | One task at a time | Running/Paused reject new UserInput |
| 3 | History preserved across tasks | HistoryManager not cleared between tasks |
| 4 | Checkpoint on every task boundary | flushIdleReady() on TaskCompleted |
| 5 | Platform re-acquired per task | platform.start() on each Created/Idle → Running |
| 6 | Event stream lives with session | closeChannelWithDelay() only on Shutdown |

---

## 10. Transient Flags (not state)

| Flag | Purpose | Lifecycle |
|------|---------|-----------|
| `currentTaskId` | Tracks active task | Set on task start, cleared on task complete |
| `completionEmitted` | Guards double SessionCompleted | **Remove** — only one Shutdown path now |
| `channelCloseScheduled` | Guards double close | Keep, but only triggered on Shutdown |
