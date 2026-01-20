@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.agent.Agent
import com.moonkey.androidagent.agent.AgentConfig
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.platform.AccessibilityPlatform
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * AgentSession - Manages the lifecycle of an agent execution.
 * 
 * This is the main entry point for controlling the agent. It:
 * - Receives Operations (Op) via submit()
 * - Emits Events (AgentEvent) via the events Flow
 * - Maintains session state via the state StateFlow
 * 
 * V2: Uses single ReAct Agent instead of multi-agent orchestration.
 */
class AgentSession private constructor(
    val sessionId: SessionId,
    private val config: SessionConfig,
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val services: SessionServices
) {
    companion object {
        private const val TAG = "AgentSession"
        
        /**
         * Create a new AgentSession with full SessionServices.
         * 
         * This is the primary way to create sessions.
         * 
         * @param config Session configuration
         * @param service AccessibilityService for platform access
         * @param scope CoroutineScope for async operations
         * @param apiKey OpenAI API key for LLM client
         * @return AgentSession with SessionServices initialized
         */
        suspend fun create(
            config: SessionConfig,
            service: AccessibilityService,
            scope: CoroutineScope,
            apiKey: String
        ): AgentSession {
            val platform: AndroidPlatform = AccessibilityPlatform(service)
            val services = SessionServices.create(config, platform, apiKey)
            
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                service = service,
                scope = scope,
                services = services
            )
        }
        
        /**
         * Create a new AgentSession with pre-created SessionServices.
         * 
         * Useful for testing or custom service configuration.
         */
        fun createWithServices(
            config: SessionConfig,
            service: AccessibilityService,
            scope: CoroutineScope,
            services: SessionServices
        ): AgentSession {
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                service = service,
                scope = scope,
                services = services
            )
        }
    }
    
    /**
     * Get the SessionServices.
     */
    fun getServices(): SessionServices = services
    
    // ===== State =====
    
    private val _state = MutableStateFlow<SessionState>(SessionState.Created)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    
    // ===== Events =====
    
    private val eventChannel = Channel<AgentEvent>(Channel.BUFFERED)
    val events: Flow<AgentEvent> = eventChannel.receiveAsFlow()
    
    // ===== Agent (V2) =====
    
    private var agent: Agent? = null
    private var agentJob: Job? = null
    private var cancellationSignal: CompletableDeferred<AgentStopReason>? = null
    
    private var currentGoal: String = ""
    
    /**
     * Submit an operation to the session.
     * 
     * This is thread-safe and can be called from any thread.
     */
    suspend fun submit(op: Op) {
        Log.d(TAG, "Received Op: $op (current state: ${_state.value})")
        
        when (op) {
            is Op.Start -> handleStart(op)
            is Op.Pause -> handlePause()
            is Op.Resume -> handleResume()
            is Op.Interrupt -> handleInterrupt()
            is Op.Shutdown -> handleShutdown()
            is Op.UserInput -> handleUserInput(op)
            is Op.Approve -> handleApproval(op)
        }
    }
    
    /**
     * Emit a status update as an event.
     */
    internal fun emitStatus(status: String, emoji: String? = null) {
        scope.launch {
            emit(AgentEvent.StatusUpdate(
                sessionId = sessionId,
                timestamp = now(),
                status = status,
                emoji = emoji
            ))
        }
    }
    
    // ===== Operation Handlers =====
    
    private suspend fun handleStart(op: Op.Start) {
        if (_state.value != SessionState.Created) {
            emit(AgentEvent.SessionError(
                sessionId = sessionId,
                timestamp = now(),
                error = AgentError.InvalidStateError(
                    message = "Cannot start: session already started",
                    currentState = _state.value.toString(),
                    attemptedOperation = "Start"
                )
            ))
            return
        }
        
        currentGoal = op.goal
        _state.value = SessionState.Running
        
        emit(AgentEvent.SessionStarted(
            sessionId = sessionId,
            timestamp = now(),
            goal = op.goal
        ))
        
        startAgent(op.goal)
        
        Log.i(TAG, "Session started: $sessionId, goal: ${op.goal}")
    }
    
    /**
     * Start the agent execution (V2).
     */
    private fun startAgent(goal: String) {
        val signal = CompletableDeferred<AgentStopReason>()
        cancellationSignal = signal
        
        val agentConfig = AgentConfig(
            goal = goal,
            sessionId = sessionId,
            maxTurns = config.maxTurns,
            uiSettleDelayMs = config.actionDelayMs,
            debugMode = config.debugMode
        )
        
        val newAgent = Agent(
            config = agentConfig,
            services = services,
            eventEmitter = { event -> emit(event) },
            cancellationSignal = signal
        )
        agent = newAgent
        
        // Launch agent in a coroutine
        agentJob = scope.launch {
            try {
                val result = newAgent.run()
                handleAgentComplete(result)
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent cancelled")
                handleAgentComplete(AgentStopReason.UserRequested)
            } catch (e: Exception) {
                Log.e(TAG, "Agent error", e)
                handleAgentComplete(AgentStopReason.Error(e.message ?: "Unknown error"))
            }
        }
        
        Log.d(TAG, "Started agent")
    }
    
    /**
     * Handle agent completion (V2).
     */
    private suspend fun handleAgentComplete(reason: AgentStopReason) {
        if (_state.value == SessionState.Shutdown) {
            return // Already shut down
        }
        
        val completionReason = when (reason) {
            AgentStopReason.GoalAchieved -> CompletionReason.GOAL_ACHIEVED
            AgentStopReason.UserRequested -> CompletionReason.USER_STOPPED
            AgentStopReason.MaxTurnsReached -> CompletionReason.MAX_TURNS
            is AgentStopReason.Error -> CompletionReason.ERROR
        }
        
        _state.value = SessionState.Completed
        
        emit(AgentEvent.SessionCompleted(
            sessionId = sessionId,
            timestamp = now(),
            result = if (reason is AgentStopReason.Error) reason.message else null,
            reason = completionReason
        ))
    }
    
    private suspend fun handlePause() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot pause: not running (state: ${_state.value})")
            return
        }
        
        _state.value = SessionState.Paused
        
        agent?.pause()
        
        emit(AgentEvent.SessionPaused(
            sessionId = sessionId,
            timestamp = now()
        ))
        
        Log.i(TAG, "Session paused: $sessionId")
    }
    
    private suspend fun handleResume() {
        if (_state.value != SessionState.Paused) {
            Log.w(TAG, "Cannot resume: not paused (state: ${_state.value})")
            return
        }
        
        _state.value = SessionState.Running
        
        agent?.resume()
        
        emit(AgentEvent.SessionResumed(
            sessionId = sessionId,
            timestamp = now()
        ))
        
        Log.i(TAG, "Session resumed: $sessionId")
    }
    
    private suspend fun handleInterrupt() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
            return
        }
        
        agent?.stop()
        Log.i(TAG, "Interrupt requested")
    }
    
    private suspend fun handleShutdown() {
        Log.i(TAG, "Shutting down session: $sessionId")
        
        val previousState = _state.value
        _state.value = SessionState.Shutdown
        
        // Stop agent
        agent?.stop()
        agent = null
        agentJob?.cancel()
        agentJob = null
        cancellationSignal?.complete(AgentStopReason.UserRequested)
        cancellationSignal = null
        
        // Cleanup SessionServices
        services.cleanup()
        
        emit(AgentEvent.SessionCompleted(
            sessionId = sessionId,
            timestamp = now(),
            result = null,
            reason = if (previousState == SessionState.Running || previousState == SessionState.Paused) {
                CompletionReason.USER_STOPPED
            } else {
                CompletionReason.INTERRUPTED
            }
        ))
        
        // Close event channel
        eventChannel.close()
    }
    
    private suspend fun handleUserInput(op: Op.UserInput) {
        // TODO: Forward to agent for handling
        Log.w(TAG, "UserInput not yet supported: ${op.text}")
        emitStatus("User input not yet supported")
    }
    
    private suspend fun handleApproval(op: Op.Approve) {
        services.toolRouter.resolveApproval(op.actionId, op.decision)
        Log.d(TAG, "Resolved approval: ${op.actionId} -> ${op.decision}")
    }
    
    // ===== Helpers =====
    
    private suspend fun emit(event: AgentEvent) {
        try {
            eventChannel.send(event)
            Log.d(TAG, "Emitted event: ${event::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit event: $event", e)
        }
    }
    
    private fun now(): Long = System.currentTimeMillis()
}
