# Agent Events

> AgentEvent hierarchy, domain interfaces, and key event types.
> -> See: [overview](overview.md) for protocol architecture and operations.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Event Domain Hierarchy

All events implement `AgentEvent` with `sessionId: SessionId` and `timestamp: Long`. Organized into 12 domain marker interfaces for type-safe filtering.

```
AgentEvent (sealed interface)
├── SessionLifecycleEvent
│   ├── SessionStarted(goal)
│   ├── SessionCompleted(result?, reason)
│   ├── SessionError(error: AgentError)
│   ├── SessionTakeover
│   ├── SessionResumed
│   └── SupplementReceived(text)
├── TaskLifecycleEvent
│   ├── TaskStarted(taskId, input)
│   └── TaskCompleted(taskId, result?, outcome: TaskOutcome, handoff: CompletionHandoff?)
├── TurnDomainEvent
│   ├── TurnStarted(turnId, turnNumber)
│   ├── TurnCompleted(turnId, turnNumber)
│   └── TurnPhaseChanged(turnId, phase)
├── StreamingDomainEvent
│   └── MessageDelta(turnId, delta)
├── ActionDomainEvent
│   ├── ActionProposed(actionId, toolName, description)
│   └── ActionExecuted(actionId, toolName, success, result?)
├── ApprovalDomainEvent
│   └── ApprovalRequired(actionId, description, details)
├── AskUserDomainEvent
│   └── AskUser(type: AskUserType, message, callId)
├── ThoughtDomainEvent
│   └── ThoughtUpdate(thought)
├── SubAgentDomainEvent
│   ├── SubAgentStarted(agentName, query)
│   ├── SubAgentActivity(agentName, activity)
│   └── SubAgentCompleted(agentName, success, message)
├── PerceptionDomainEvent
│   └── ScreenCaptured(elementCount, packageName?, ...)
└── StatusDomainEvent
    └── StatusUpdate(status)
```

## Key Events

| Event | When Emitted | Key Fields |
|-------|--------------|------------|
| `SessionStarted` | First Created → Running | `goal` |
| `TaskStarted` | New task begins | `taskId`, `input` |
| `MessageDelta` | Streaming text chunk | `turnId`, `delta` |
| `ThoughtUpdate` | Agent selects tool with `agent_thought` | `thought` |
| `ActionExecuted` | Tool completes | `actionId`, `toolName`, `success` |
| `AskUser` | Agent needs user help | `type`, `message`, `callId` |
| `SupplementReceived` | User sent mid-task supplement | `text` |
| `SessionTakeover` | User takes over (after agent pauses) | — |
| `TaskCompleted` | Task ends | `taskId`, `result`, `outcome`, `handoff?` (VD only; carries foreground `appPackage` + `appLabel` so chat can render an `Open <App>` CTA) |
| `SessionCompleted` | Session terminates | `reason` |

## Enums

**AskUserType**: `QUESTION` (user types text answer), `ACTION` (user performs physical action, taps "Done").

**TurnPhase**: `PERCEPTION` (capturing screen), `PLANNING` (LLM reasoning), `EXECUTION` (tool call).

**ScreenStatePhase**: `PRE_TURN` (before turn), `POST_ACTION` (after action execution).

**TaskOutcome** (task-level): `GOAL_ACHIEVED`, `TASK_IMPOSSIBLE`, `ERROR`, `USER_STOPPED`. (`MAX_TURNS` was removed when production turn caps were replaced with context-window auto-compaction — see [agent/loop.md](../agent/loop.md#auto-compaction).)

**SessionEndReason** (session-level): `USER_STOPPED`, `IDLE_TIMEOUT`, `INTERRUPTED`. Kept distinct from `TaskOutcome` to eliminate impossible states (`SessionCompleted` carrying task-only values).

**TodoStatus**: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.
