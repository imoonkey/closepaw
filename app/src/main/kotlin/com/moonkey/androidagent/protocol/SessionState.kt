package com.moonkey.androidagent.protocol

/**
 * SessionState - Lifecycle states for an agent session.
 *
 * State machine transitions:
 *
 *   Created ──UserInput──► Running ──Pause──► Paused
 *      │                     │                  │
 *      │                     │ ◄──Resume────────┘
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

    /** Session is paused (cooperative) */
    data object Paused : SessionState

    /** Session shut down (terminal state) */
    data object Shutdown : SessionState
}
