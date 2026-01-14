package com.moonkey.androidagent.orchestration

import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.SessionId
import kotlinx.coroutines.CompletableDeferred

/**
 * AgentOrchestration - Interface for agent coordination strategies.
 * 
 * This defines HOW agents work together to accomplish goals.
 * The interface is stable, but implementations can vary:
 * 
 * - SingleAgentOrchestration: Simple loop with one agent
 * - MobileV3Orchestration: Multi-agent (Manager, Executor, Reflector)
 * - TreeOfThoughtsOrchestration: Future exploration strategies
 * 
 * Key design principles:
 * - Orchestration is CANCELLATION-AWARE (checks signal, yields periodically)
 * - Orchestration is PAUSE-AWARE (respects pause state)
 * - Orchestration emits events for UI updates
 * - Orchestration doesn't manage its own lifecycle (Session does that)
 */
interface AgentOrchestration {
    
    /**
     * Run the main agent loop.
     * 
     * This is the core method that executes the orchestration strategy.
     * It should:
     * - Run until goal is achieved, cancelled, or max turns reached
     * - Check cancellation signal periodically
     * - Respect pause state
     * - Emit events for UI updates
     * 
     * @throws kotlinx.coroutines.CancellationException if cancelled
     */
    suspend fun run()
    
    /**
     * Cooperative pause - complete current action, then wait.
     * 
     * The orchestration should finish its current atomic operation
     * (e.g., complete the current tool call) then enter a waiting state
     * until resume() is called.
     */
    suspend fun pause()
    
    /**
     * Resume from pause.
     * 
     * Signals the orchestration to continue from where it paused.
     */
    suspend fun resume()
    
    /**
     * Interrupt the current turn.
     * 
     * This aborts the current turn's processing but keeps the session alive.
     * The next iteration should start fresh (new perception, new planning).
     * 
     * Use cases:
     * - User wants to redirect the agent
     * - Current action is taking too long
     * - Error recovery
     */
    suspend fun interrupt()
    
    /**
     * Stop completely and cleanup.
     * 
     * This signals the orchestration to exit its run loop.
     * Called when the session is shutting down.
     */
    suspend fun stop()
}

/**
 * CancellationReason - Why the orchestration was cancelled.
 */
sealed interface CancellationReason {
    /** User explicitly requested stop */
    data object UserRequested : CancellationReason
    
    /** Timeout limit reached */
    data object Timeout : CancellationReason
    
    /** Maximum turns reached */
    data object MaxTurnsReached : CancellationReason
    
    /** Error during execution */
    data class Error(val message: String) : CancellationReason
    
    /** Goal was achieved */
    data object GoalAchieved : CancellationReason
}

/**
 * OrchestrationConfig - Configuration for orchestration behavior.
 */
data class OrchestrationConfig(
    /** The user's goal */
    val goal: String,
    
    /** Session ID for event emission */
    val sessionId: SessionId,
    
    /** Maximum number of turns before stopping */
    val maxTurns: Int = 50,
    
    /** Delay between actions in milliseconds */
    val actionDelayMs: Long = 3000,
    
    /** Whether to enable debug logging */
    val debugMode: Boolean = false
)

/**
 * EventEmitter - Function type for emitting events from orchestration.
 */
typealias EventEmitter = suspend (AgentEvent) -> Unit

/**
 * CancellationSignal - Deferred that completes when cancellation is requested.
 */
typealias CancellationSignal = CompletableDeferred<CancellationReason>


