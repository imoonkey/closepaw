package ai.closepaw.protocol

/**
 * SessionState - Lifecycle states for an agent session.
 *
 * State machine transitions:
 *
 *   Created ──UserInput──► Running ──Takeover──► TakeoverPending ──confirmed──► Paused
 *      │                     │                        │                           │
 *      │                     │                        │   ◄──Resume───────────────┘
 *      │                     │                        │
 *      │                     │ ◄──────────────────────┘ (via Paused → Resume)
 *      │                     │
 *      │                     └──TaskCompleted──► Idle ──UserInput──► Running
 *      │                                          │
 *      │                                          └──IdleTimeout──► Shutdown
 *      │                                                               ▲
 *      └──────────────────────Shutdown─────────────────────────────────┘
 *
 * Terminal state: Shutdown
 * - Shutdown: explicit user stop, idle timeout, or activity destruction
 *
 * TakeoverPending: Agent has been asked to pause but hasn't confirmed yet.
 * Resume is rejected in this state. Transitions to Paused once the agent
 * reaches a safe pause point (end of current turn).
 *
 * Hot Idle: After task completion, session enters Idle.
 * Expensive resources (platform, VD) are released. Lightweight state
 * (history, todos, LLM client) stays in memory for instant follow-up.
 * A 5-minute idle timeout auto-triggers Shutdown.
 */
sealed interface SessionState {
    /** Session created but not yet started */
    data object Created : SessionState

    /** Session is actively running a task */
    data object Running : SessionState

    /** Session is between tasks, awaiting follow-up (Hot Idle) */
    data object Idle : SessionState

    /** Agent asked to pause but hasn't confirmed yet — Resume rejected in this state */
    data object TakeoverPending : SessionState

    /** Session is paused (cooperative) — agent confirmed it reached pause point */
    data object Paused : SessionState

    /** Session shut down (terminal state) */
    data object Shutdown : SessionState
}
