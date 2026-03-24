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
│   └── TaskCompleted(taskId, result?, reason)
├── TurnDomainEvent
│   ├── TurnStarted(turnId, turnNumber, phase)
│   ├── TurnCompleted(turnId, turnNumber)
│   └── TurnPhaseChanged(turnId, phase)
├── StreamingDomainEvent
│   └── MessageDelta(turnId, delta)
├── ActionDomainEvent
│   ├── ActionProposed(actionId, toolName, description)
│   └── ActionExecuted(actionId, toolName, success, result?)
├── ApprovalDomainEvent
│   ├── ApprovalRequired(actionId, description, details)
│   └── ApprovalResolved(actionId, decision)
├── AskUserDomainEvent
│   └── AskUser(type: AskUserType, message, callId)
├── ThoughtDomainEvent
│   └── ThoughtUpdate(thought)
├── PlanningStateEvent
│   ├── TodosUpdated(todos)
│   └── ScratchpadUpdated(key, action)
├── SubAgentDomainEvent
│   ├── SubAgentStarted(agentName, query)
│   ├── SubAgentActivity(agentName, activity)
│   └── SubAgentCompleted(agentName, success, message)
├── PerceptionDomainEvent
│   └── ScreenCaptured(elementCount, packageName?, ...)
└── StatusDomainEvent
    └── StatusUpdate(status, emoji?)
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
| `TaskCompleted` | Task ends | `taskId`, `result`, `reason` |
| `SessionCompleted` | Session terminates | `result`, `reason` |

## Enums

**AskUserType**: `QUESTION` (user types text answer), `ACTION` (user performs physical action, taps "Done").

**TurnPhase**: `PERCEPTION` (capturing screen), `PLANNING` (LLM reasoning), `EXECUTION` (tool call).

**ScreenStatePhase**: `PRE_TURN` (before turn), `POST_ACTION` (after action execution).

**CompletionReason**: `GOAL_ACHIEVED`, `USER_STOPPED`, `MAX_TURNS`, `TASK_IMPOSSIBLE`, `ERROR`, `INTERRUPTED`, `IDLE_TIMEOUT`.

**TodoStatus**: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`.
