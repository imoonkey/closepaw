# Design: AgentEvent Domain Split

**Priority**: P2 — Organization
**Files affected**: `protocol/AgentEvent.kt`, all event consumers

---

## Problem

`AgentEvent.kt` (360 lines) is a single sealed interface containing 20+ event data classes spanning 6 domains:
- Session lifecycle (SessionStarted, SessionCompleted, SessionError, SessionTakeover, SessionResumed)
- Task lifecycle (TaskStarted, TaskCompleted)
- Turn lifecycle (TurnStarted, TurnCompleted, TurnPhaseChanged)
- Actions (ActionProposed, ActionExecuted, ApprovalRequired, ApprovalResolved)
- Streaming/Thought (MessageDelta, ThoughtUpdate, StatusUpdate)
- Planning state (TodosUpdated, ScratchpadUpdated, SupplementReceived)
- Sub-agent (SubAgentStarted, SubAgentActivity, SubAgentCompleted)
- User interaction (AskUser)

The file is well-organized with comment sections, but:
1. The flat structure makes it hard to see which events a consumer actually cares about
2. Adding new events to any domain requires editing this monolithic file
3. IDE navigation/autocomplete shows 20+ completions for `AgentEvent.`

## Solution

Keep `AgentEvent` as the root sealed interface but split into separate files grouped by domain. Each file contains the events for one domain.

### File structure

```
protocol/
├── AgentEvent.kt          # Root sealed interface + SessionId typealias (trimmed to ~20 lines)
├── SessionEvents.kt       # SessionStarted, SessionCompleted, SessionError, SessionTakeover, SessionResumed
├── TaskEvents.kt          # TaskStarted, TaskCompleted
├── TurnEvents.kt          # TurnStarted, TurnCompleted, TurnPhaseChanged, MessageDelta
├── ActionEvents.kt        # ActionProposed, ActionExecuted, ApprovalRequired, ApprovalResolved
├── StateEvents.kt         # TodosUpdated, ScratchpadUpdated, SupplementReceived, ThoughtUpdate, StatusUpdate
├── SubAgentEvents.kt      # SubAgentStarted, SubAgentActivity, SubAgentCompleted
└── UserInteractionEvents.kt # AskUser
```

### Implementation

Kotlin sealed interfaces allow subclasses in different files **within the same package**. Since all event classes are already in `protocol/`, this is a pure file-move refactoring:

```kotlin
// AgentEvent.kt — root only
sealed interface AgentEvent {
    val sessionId: SessionId
    val timestamp: Long
}

// SessionEvents.kt
data class SessionStarted(...) : AgentEvent
data class SessionCompleted(...) : AgentEvent
// etc.
```

All `when` exhaustiveness checks continue working because the sealed interface is in the same package.

## Steps

1. Create new event files in `protocol/`
2. Move event data classes to their respective files (no code changes, just file moves)
3. Trim `AgentEvent.kt` to only the sealed interface declaration
4. Move `TurnPhase`, `CompletionReason`, `AskUserType` enums to companion files or keep in place
5. Verify compilation — sealed interface members in same package is guaranteed

## Risks

- **None**: This is a pure refactoring with no behavioral change. Kotlin sealed interface rules guarantee compilation.
- **Minor**: Git blame history is disrupted for moved classes. Accept this tradeoff.
