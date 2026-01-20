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
 *      │                  └──Complete──► Completed
 *      │                                     │
 *      └─────────────Shutdown────────────────┴──► Shutdown
 * 
 * Terminal states: Completed, Shutdown
 * - Completed: Agent finished (goal achieved, max turns, or error)
 * - Shutdown: User requested stop via Op.Shutdown
 * 
 * Note: CompletionReason in SessionCompleted event distinguishes
 * between GOAL_ACHIEVED, MAX_TURNS, ERROR, USER_STOPPED, etc.
 */
sealed interface SessionState {
    /** Session created but not yet started */
    data object Created : SessionState
    
    /** Session is actively running */
    data object Running : SessionState
    
    /** Session is paused (cooperative) */
    data object Paused : SessionState
    
    /** Session completed (see CompletionReason for details) */
    data object Completed : SessionState
    
    /** Session shut down via Op.Shutdown */
    data object Shutdown : SessionState
}

