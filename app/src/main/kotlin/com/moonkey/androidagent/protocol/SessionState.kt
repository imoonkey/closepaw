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
 *      │                  ├──Complete──► Completed
 *      │                  │
 *      │                  ├──Error──► Error
 *      │                  │
 *      └──────────────────┴──Shutdown──► Shutdown
 */
sealed interface SessionState {
    /** Session created but not yet started */
    data object Created : SessionState
    
    /** Session is actively running */
    data object Running : SessionState
    
    /** Session is paused (cooperative) */
    data object Paused : SessionState
    
    /** Session completed successfully */
    data object Completed : SessionState
    
    /** Session was cancelled by user */
    data object Cancelled : SessionState
    
    /** Session shut down gracefully */
    data object Shutdown : SessionState
    
    /** Session encountered an error */
    data class Error(val exception: Throwable) : SessionState
}

/**
 * CancellationReason - Why a session or turn was cancelled.
 */
sealed interface CancellationReason {
    /** User requested cancellation */
    data object UserRequested : CancellationReason
    
    /** Operation timed out */
    data class Timeout(val durationMs: Long) : CancellationReason
    
    /** Cancelled due to error */
    data class ErrorCancellation(val message: String) : CancellationReason
}

