# Agent Protocol Overview

> Op/Event communication, state machine, errors, and utilities.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Overview

Unidirectional flow with a task-based model:

```
┌────────────┐         Op           ┌──────────────┐
│     UI     │ ─────────────────►   │ AgentSession │
│            │ ◄─────────────────   │              │
└────────────┘      AgentEvent      └──────────────┘
```

- **Operations (Op)**: user intents sent via `session.submit(op)`
- **Events (AgentEvent)**: state/progress emitted via `session.events`
- **Task model**: Session (long-lived) → Task (work from `Op.UserInput`) → Turn (Perceive → Think → Act → Observe)

## Operations (Op)

> See: `protocol/Op.kt`

### State Transitions

```
                              ┌─────────┐
                              │ Created │
                              └────┬────┘
                                   │ Op.UserInput
                                   ▼
                    ┌─────────────────────────────┐
            ┌──────►│           Running           │◄──────┐
            │       └──────┬───────────────┬──────┘       │
            │  Op.Takeover │               │ TaskCompleted│
            │              ▼               │              │
   Op.Resume│       ┌──────────┐           │              │
            │       │  Paused  │           ▼              │
            │       └──────────┘     ┌──────────┐         │
            │    Op.UserInput        │   Idle   │─────────┘
            │◄───────────────────────└────┬─────┘
                                          │ Op.Shutdown
                                          ▼
                                   ┌───────────┐
                                   │  Shutdown │
                                   └───────────┘
```

| Operation | Valid States | Effect |
|-----------|--------------|--------|
| `UserInput(text)` | Created, Idle | Start a new task |
| `Takeover` | Running | Pause cooperatively |
| `Resume` | Paused | Resume execution |
| `Interrupt` | Running | Stop current task → Idle |
| `Shutdown` | Any | Graceful shutdown |
| `Approve(id, decision)` | Running | Resolve pending approval |
| `Supplement(text)` | Running | Inject context mid-task |
| `UserResponse(callId, response)` | Running | Respond to `ask_user` |

### Approval

`ApprovalDecision`: `APPROVED` (execute), `DENIED` (skip), `ABORT` (stop session).

`RiskLevel`: `LOW` (read-only, auto-approved), `MEDIUM` (may ask), `HIGH` (destructive, requires approval).

`ApprovalRequirement`: `None`, `Required(reason, riskLevel)`, `Forbidden(reason)`.

## Session State Machine

> See: `protocol/SessionState.kt`

| State | Description |
|-------|-------------|
| `Created` | Initialized, not started |
| `Running` | Actively executing a task |
| `Idle` | Between tasks (Hot Idle — lightweight state, expensive resources released) |
| `Paused` | Cooperative takeover |
| `Shutdown` | Terminal — all resources released |

-> See: [Session State Machine](../ui/session/state_machine.md) for formal transition rules and resource ownership.

## Error Handling

> See: `protocol/AgentError.kt`

`sealed class AgentError` with `message: String` and `isRecoverable: Boolean`.

| Error Type | Recoverable | Description |
|------------|-------------|-------------|
| `LLMError` | Depends | API failure (429/503/504 = yes) |
| `LLMParseError` | Yes | Response malformed |
| `PlatformError` | No | Android platform failed |
| `PermissionError` | No | Missing permission |
| `ValidationError` | Yes | Tool params invalid |
| `UnknownToolError` | Yes | Tool does not exist |
| `InvalidStateError` | No | Wrong state for operation |
| `SessionClosedError` | No | Already cancelled |
| `ApprovalDeniedError` | Yes | User denied action |
| `PolicyDeniedError` | No | Policy forbids action |
| `UnexpectedError` | No | Unclassified |

Factory: `AgentError.from(e: Throwable)` creates appropriate subtype.

## Utilities

**SessionId** (`protocol/SessionId.kt`): `@JvmInline value class SessionId(val value: String)` — UUID-based unique identifier.

**sanitizeThought** (`protocol/TextUtils.kt`): Trims whitespace, truncates to 40 chars with ellipsis.

## Subpages

| Page | Focus |
|------|-------|
| [events.md](events.md) | AgentEvent hierarchy, domain interfaces, key events |
| [config.md](config.md) | SessionConfig, PlatformMode, AgentMode, LLM config |

## File Structure

```
protocol/
├── Op.kt                     # UI→Agent commands
├── AgentEvent.kt             # Base event interface
├── AgentEventDomains.kt      # 12 domain marker interfaces
├── AgentError.kt             # Error hierarchy
├── SessionConfig.kt          # Session configuration
├── SessionState.kt           # 5-state machine
├── SessionId.kt              # Session identifier
├── TaskOutcome.kt            # Task-level outcome (GOAL_ACHIEVED / MAX_TURNS / TASK_IMPOSSIBLE / ERROR / USER_STOPPED)
├── SessionEndReason.kt       # Session-level shutdown reason (USER_STOPPED / IDLE_TIMEOUT / INTERRUPTED)
├── Session/Task/Turn/Streaming/Action/Approval/AskUser/
│   Thought/SubAgent/Perception/StatusEvents.kt
├── ApprovalTypes.kt          # ApprovalDecision, RiskLevel
├── TodoModels.kt             # Todo, TodoStatus
└── TextUtils.kt              # sanitizeThought
```

## Related Docs

- [Events](events.md) - Full event hierarchy
- [Config](config.md) - Session configuration
- [Session](../infra/session.md) - AgentSession lifecycle
- [Loop](../agent/loop.md) - Turn execution emitting events
- [Platform](../infra/platform.md) - Platform mode configuration
