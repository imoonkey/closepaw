package ai.closepaw.protocol

/** Session lifecycle domain events. */
sealed interface SessionLifecycleEvent : AgentEvent

/** Task lifecycle domain events. */
sealed interface TaskLifecycleEvent : AgentEvent

/** Sub-agent lifecycle/activity domain events. */
sealed interface SubAgentDomainEvent : AgentEvent

/** Turn lifecycle domain events. */
sealed interface TurnDomainEvent : AgentEvent

/** Streaming output domain events. */
sealed interface StreamingDomainEvent : AgentEvent

/** Action proposal/execution domain events. */
sealed interface ActionDomainEvent : AgentEvent

/** Perception/capture domain events. */
sealed interface PerceptionDomainEvent : AgentEvent

/** Approval workflow domain events. */
sealed interface ApprovalDomainEvent : AgentEvent

/** ask_user workflow domain events. */
sealed interface AskUserDomainEvent : AgentEvent

/** Agent-thought domain events. */
sealed interface ThoughtDomainEvent : AgentEvent

/** Generic status line domain events. */
sealed interface StatusDomainEvent : AgentEvent
