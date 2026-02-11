@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.PlatformFactory
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.trace.TraceRecorderFactory
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AgentSession - Manages the lifecycle of an agent execution.
 *
 * This is the main entry point for controlling the agent. It:
 * - Receives Operations (Op) via submit()
 * - Emits Events (AgentEvent) via the events Flow
 * - Maintains session state via the state StateFlow
 *
 * V2: Uses single ReAct Agent instead of multi-agent orchestration. Supports multi-round tasks via
 * Op.UserInput.
 */
class AgentSession
private constructor(
        val sessionId: SessionId,
        private val config: SessionConfig,
        private val service: AccessibilityService,
        private val scope: CoroutineScope,
        private val services: SessionServices
) {
    companion object {
        private const val TAG = "AgentSession"

        /** Grace period to allow event collectors to process final event before channel close */
        private const val EVENT_DELIVERY_GRACE_PERIOD_MS = 100L

        /**
         * Create a new AgentSession with full SessionServices.
         *
         * @param config Session configuration
         * @param service AccessibilityService for platform access
         * @param scope CoroutineScope for async operations
         * @param apiKeys Per-provider API keys, keyed by env var name
         * @param visualizer Optional ActionVisualizerManager for touch action visualization
         */
        fun create(
                config: SessionConfig,
                service: AccessibilityService,
                scope: CoroutineScope,
                apiKeys: Map<String, String> = emptyMap(),
                visualizer: ActionVisualizerManager? = null
        ): AgentSession {
            val sessionId = SessionId.generate()
            val traceRecorder = TraceRecorderFactory.create(service, config, sessionId)
            val platform: AndroidPlatform =
                    PlatformFactory.create(
                            config = config,
                            service = service,
                            visualizer = visualizer,
                            traceRecorder = traceRecorder
                    )
            val services =
                    SessionServices.create(
                            config = config,
                            platform = platform,
                            apiKeys = apiKeys,
                            context = service,
                            scope = scope,
                            traceRecorder = traceRecorder
                    )

            return AgentSession(
                    sessionId = sessionId,
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

    /** Get the SessionServices. */
    fun getServices(): SessionServices = services

    // ===== State =====

    private val _state = MutableStateFlow<SessionState>(SessionState.Created)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // ===== Events =====

    // Use SharedFlow to allow multiple collectors (ChatViewModel + AgentService)
    // replay=8: replay recent events for late collectors (esp. TaskStarted)
    // extraBufferCapacity: buffer events to avoid slow collectors blocking emitter
    private val _events = MutableSharedFlow<AgentEvent>(replay = 8, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    // ===== Agent (V2) =====
    private val agentRunner =
            SessionAgentRunner(
                    scope = scope,
                    services = services,
                    sessionId = sessionId,
                    config = config,
                    emitEvent = { event -> emit(event) },
                    onComplete = { reason -> handleAgentComplete(reason) }
            )

    private var currentTaskId: String? = null

    // Guard against emitting SessionCompleted twice (Issue 2: double completion)
    private val completionEmitted = AtomicBoolean(false)

    // Guard against scheduling multiple channel close operations (PR feedback)
    private val channelCloseScheduled = AtomicBoolean(false)

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

    /** Emit a status update as an event. */
    internal fun emitStatus(status: String, emoji: String? = null) {
        scope.launch {
            emit(
                    AgentEvent.StatusUpdate(
                            sessionId = sessionId,
                            timestamp = now(),
                            status = status,
                            emoji = emoji
                    )
            )
        }
    }

    // ===== Operation Handlers =====

    private suspend fun handleStart(op: Op.Start) {
        // Backward compatibility: Map Start to UserInput
        handleUserInput(Op.UserInput(op.goal))
    }

    private suspend fun handleUserInput(op: Op.UserInput) {
        // Only Running or Paused states indicate an active task that prevents new input.
        // Created and Idle states allow starting new tasks.
        if (_state.value == SessionState.Running || _state.value == SessionState.Paused) {
            Log.w(TAG, "Rejecting UserInput: Session is busy")
            emitStatus("⚠️ Agent is busy. Please wait.")
            return
        }

        // SessionStarted is only emitted once when the session first moves from Created
        // to Running. Subsequent tasks (from Idle state) do not re-emit SessionStarted.
        if (_state.value == SessionState.Created) {
            // Start session recording
            services.recordingService.initializeNewSession(sessionId.value, config.mainModel)

            // Initialize platform resources (VirtualDisplayPlatform creates display here).
            try {
                services.platform.start()
            } catch (e: Exception) {
                Log.e(TAG, "Platform start failed", e)
                emitStatus("⚠️ Platform initialization failed: ${e.message}")
                return
            }

            emit(
                    AgentEvent.SessionStarted(
                            sessionId = sessionId,
                            timestamp = now(),
                            goal = op.text // Treat first input as "goal" for compatibility
                    )
            )
        }

        // Start new task
        val taskId = "task-${System.currentTimeMillis()}"
        currentTaskId = taskId
        _state.value = SessionState.Running

        emit(
                AgentEvent.TaskStarted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        input = op.text
                )
        )

        agentRunner.start(op.text, taskId)

        Log.i(TAG, "Task started: $taskId, input: ${op.text}")
    }

    /**
     * Handle agent completion (V2).
     *
     * Cleanup of agent references happens here after task completion. Note: If handleShutdown() is
     * called during a task, it sets state to Shutdown and performs its own cleanup (agent.stop(),
     * agentJob.cancel(), etc.). The early return below ensures we don't double-cleanup or emit
     * spurious events.
     */
    private suspend fun handleAgentComplete(reason: AgentStopReason) {
        // If we are shutting down, handleShutdown() already handled cleanup.
        // Skip task completion logic to avoid double events or stale reference issues.
        if (_state.value == SessionState.Shutdown) {
            return
        }

        val taskId = currentTaskId ?: "unknown"
        val completionReason = reason.toCompletionReason()
        val resultMessage = if (reason is AgentStopReason.Error) reason.message else null

        emit(
                AgentEvent.TaskCompleted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        result = resultMessage,
                        reason = completionReason
                )
        )

        // Transition to Idle (ready for next task)
        _state.value = SessionState.Idle
        currentTaskId = null
        agentRunner.clear()

        Log.i(TAG, "Task $taskId completed (reason=$completionReason). Session Idle.")
    }

    private fun AgentStopReason.toCompletionReason(): CompletionReason =
            when (this) {
                is AgentStopReason.GoalAchieved -> CompletionReason.GOAL_ACHIEVED
                is AgentStopReason.MaxTurnsReached -> CompletionReason.MAX_TURNS
                is AgentStopReason.UserRequested -> CompletionReason.USER_STOPPED
                is AgentStopReason.Error -> CompletionReason.ERROR
            }

    private suspend fun handlePause() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot pause: not running (state: ${_state.value})")
            return
        }

        _state.value = SessionState.Paused

        agentRunner.pause()

        emit(AgentEvent.SessionPaused(sessionId = sessionId, timestamp = now()))

        Log.i(TAG, "Session paused: $sessionId")
    }

    private suspend fun handleResume() {
        if (_state.value != SessionState.Paused) {
            Log.w(TAG, "Cannot resume: not paused (state: ${_state.value})")
            return
        }

        _state.value = SessionState.Running

        agentRunner.resume()

        emit(AgentEvent.SessionResumed(sessionId = sessionId, timestamp = now()))

        Log.i(TAG, "Session resumed: $sessionId")
    }

    private suspend fun handleInterrupt() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
            return
        }

        // Interrupt is cooperative - signals agent to stop after current action.
        // This will eventually trigger handleAgentComplete via AgentStopReason.UserRequested
        agentRunner.stop()
        Log.i(TAG, "Interrupt requested")
    }

    private suspend fun handleShutdown() {
        Log.i(TAG, "Shutting down session: $sessionId")

        val previousState = _state.value
        _state.value = SessionState.Shutdown

        // Stop agent
        agentRunner.shutdown()

        // Cleanup SessionServices
        services.cleanup()

        // Only emit completion if not already emitted
        if (completionEmitted.compareAndSet(false, true)) {
            emit(
                    AgentEvent.SessionCompleted(
                            sessionId = sessionId,
                            timestamp = now(),
                            result = null,
                            reason =
                                    if (previousState == SessionState.Running ||
                                                    previousState == SessionState.Paused
                                    ) {
                                        CompletionReason.USER_STOPPED
                                    } else {
                                        CompletionReason.INTERRUPTED
                                    }
                    )
            )
        }

        // Close channel with delay
        closeChannelWithDelay()
    }

    private suspend fun handleApproval(op: Op.Approve) {
        services.toolRouter.resolveApproval(op.actionId, op.decision)

        // Emit ApprovalResolved event
        emit(
                AgentEvent.ApprovalResolved(
                        sessionId = sessionId,
                        timestamp = now(),
                        actionId = op.actionId,
                        decision = op.decision
                )
        )

        Log.d(TAG, "Resolved approval: ${op.actionId} -> ${op.decision}")
    }

    // ===== Helpers =====

    private suspend fun emit(event: AgentEvent) {
        try {
            _events.emit(event)
            Log.d(TAG, "Emitted event: ${event::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit event: $event", e)
        }
    }

    /**
     * Mark the session as closed after a delay. SharedFlow doesn't need explicit closing, but this
     * signals end of session.
     */
    private fun closeChannelWithDelay() {
        if (!channelCloseScheduled.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            delay(EVENT_DELIVERY_GRACE_PERIOD_MS)
            // SharedFlow doesn't have close(), session lifecycle is tracked via state
            Log.d(TAG, "Session event stream ended")
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
