package com.moonkey.androidagent.protocol

/**
 * SessionState - Lifecycle states for an agent session.
 * 
 * State machine transitions:
 * 
 *   Created ──Start──► Running ──Pause──► Paused
 *      │                  │                  │
 *      │                  │ ◄──Resume────────┘
 *      │                  │
 *      │                  ├──TaskCompleted──► Idle ──UserInput──► Running
 *      │                  │
 *      │                  └──Complete──► Completed
 *      │                                     │
 *      └─────────────Shutdown────────────────┴──► Shutdown
 * 
 * Terminal states: Completed, Shutdown
 * - Completed: Session finished naturally (e.g. fatal error, or explicit end)
 * - Shutdown: User requested stop via Op.Shutdown
 * 
 * Note: CompletionReason in SessionCompleted event distinguishes
 * between GOAL_ACHIEVED, MAX_TURNS, ERROR, USER_STOPPED, etc.
 */
sealed interface SessionState {
    /** Session created but not yet started */
    data object Created : SessionState
    
    /** Session is actively running a task */
    data object Running : SessionState
    
    /** Session is active but waiting for user input (no task running) */
    data object Idle : SessionState
    
    /** Session is paused (cooperative) */
    data object Paused : SessionState
    
    /** Session completed (terminal state) */
    data object Completed : SessionState
    
    /** Session shut down via Op.Shutdown (terminal state) */
    data object Shutdown : SessionState
}
