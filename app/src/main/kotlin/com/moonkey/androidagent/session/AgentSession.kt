@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.orchestration.AgentOrchestration
import com.moonkey.androidagent.orchestration.CancellationReason
import com.moonkey.androidagent.orchestration.CancellationSignal
import com.moonkey.androidagent.orchestration.MobileV3OrchestrationFactory
import com.moonkey.androidagent.orchestration.OrchestrationConfig
import com.moonkey.androidagent.orchestration.OrchestrationFactory
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
 * Uses MobileV3Orchestration for multi-agent execution with
 * Manager, Executor, and Reflector agents.
 */
class AgentSession private constructor(
    val sessionId: SessionId,
    private val config: SessionConfig,
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val services: SessionServices,
    private val orchestrationFactory: OrchestrationFactory
) {
    companion object {
        private const val TAG = "AgentSession"
        
        /**
         * Create a new AgentSession with full SessionServices and orchestration support.
         * 
         * This is the primary way to create sessions.
         * 
         * @param config Session configuration
         * @param service AccessibilityService for platform access
         * @param scope CoroutineScope for async operations
         * @return AgentSession with SessionServices and orchestration initialized
         */
        suspend fun create(
            config: SessionConfig,
            service: AccessibilityService,
            scope: CoroutineScope
        ): AgentSession {
            val platform: AndroidPlatform = AccessibilityPlatform(service)
            val services = SessionServices.create(config, platform)
            val orchestrationFactory = MobileV3OrchestrationFactory()
            
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                service = service,
                scope = scope,
                services = services,
                orchestrationFactory = orchestrationFactory
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
            val orchestrationFactory = MobileV3OrchestrationFactory()
            
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                service = service,
                scope = scope,
                services = services,
                orchestrationFactory = orchestrationFactory
            )
        }
        
        /**
         * Create with custom orchestration factory.
         * 
         * For testing or when you want to inject a specific orchestration type.
         */
        fun createWithFactory(
            config: SessionConfig,
            service: AccessibilityService,
            scope: CoroutineScope,
            services: SessionServices,
            factory: OrchestrationFactory
        ): AgentSession {
            return AgentSession(
                sessionId = SessionId.generate(),
                config = config,
                service = service,
                scope = scope,
                services = services,
                orchestrationFactory = factory
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
    
    // ===== Orchestration =====
    
    private var orchestration: AgentOrchestration? = null
    private var orchestrationJob: Job? = null
    private var cancellationSignal: CancellationSignal? = null
    
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
        
        startOrchestration(op.goal)
        
        Log.i(TAG, "Session started: $sessionId, goal: ${op.goal}")
    }
    
    /**
     * Start the orchestration.
     */
    private fun startOrchestration(goal: String) {
        val signal = CompletableDeferred<CancellationReason>()
        cancellationSignal = signal
        
        val orchConfig = OrchestrationConfig(
            goal = goal,
            sessionId = sessionId,
            maxTurns = config.maxTurns,
            actionDelayMs = config.actionDelayMs,
            debugMode = config.debugMode
        )
        
        val orch = orchestrationFactory.create(
            config = orchConfig,
            services = services,
            eventEmitter = { event -> emit(event) },
            cancellationSignal = signal
        )
        orchestration = orch
        
        // Launch orchestration in a coroutine
        orchestrationJob = scope.launch {
            try {
                orch.run()
                
                // Check completion reason
                if (signal.isCompleted) {
                    val reason = signal.getCompleted()
                    handleOrchestrationComplete(reason)
                } else {
                    handleOrchestrationComplete(CancellationReason.GoalAchieved)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Orchestration cancelled")
                handleOrchestrationComplete(CancellationReason.UserRequested)
            } catch (e: Exception) {
                Log.e(TAG, "Orchestration error", e)
                handleOrchestrationComplete(CancellationReason.Error(e.message ?: "Unknown error"))
            }
        }
        
        Log.d(TAG, "Started orchestration")
    }
    
    /**
     * Handle orchestration completion.
     */
    private suspend fun handleOrchestrationComplete(reason: CancellationReason) {
        if (_state.value == SessionState.Shutdown) {
            return // Already shut down
        }
        
        val completionReason = when (reason) {
            CancellationReason.GoalAchieved -> CompletionReason.GOAL_ACHIEVED
            CancellationReason.UserRequested -> CompletionReason.USER_STOPPED
            CancellationReason.Timeout -> CompletionReason.TIMEOUT
            CancellationReason.MaxTurnsReached -> CompletionReason.MAX_TURNS
            is CancellationReason.Error -> CompletionReason.ERROR
        }
        
        _state.value = SessionState.Completed
        
        emit(AgentEvent.SessionCompleted(
            sessionId = sessionId,
            timestamp = now(),
            result = if (reason is CancellationReason.Error) reason.message else null,
            reason = completionReason
        ))
    }
    
    private suspend fun handlePause() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot pause: not running (state: ${_state.value})")
            return
        }
        
        _state.value = SessionState.Paused
        
        orchestration?.pause()
        
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
        
        orchestration?.resume()
        
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
        
        orchestration?.interrupt()
        Log.i(TAG, "Interrupt requested")
    }
    
    private suspend fun handleShutdown() {
        Log.i(TAG, "Shutting down session: $sessionId")
        
        val previousState = _state.value
        _state.value = SessionState.Shutdown
        
        // Stop orchestration
        orchestration?.stop()
        orchestration = null
        orchestrationJob?.cancel()
        orchestrationJob = null
        cancellationSignal?.complete(CancellationReason.UserRequested)
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
        // TODO: Forward to orchestration for handling
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
