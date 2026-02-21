@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.OverlayTouchGate
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

        private const val EVENT_DELIVERY_GRACE_PERIOD_MS = 100L

        fun create(
                config: SessionConfig,
                service: AccessibilityService,
                scope: CoroutineScope,
                apiKeys: Map<String, String> = emptyMap(),
                visualizer: ActionVisualizerManager? = null,
                overlayTouchGate: OverlayTouchGate? = null,
        ): AgentSession {
            val sessionId = SessionId.generate()
            val traceRecorder = TraceRecorderFactory.create(service, config, sessionId)
            val platform: AndroidPlatform =
                    PlatformFactory.create(
                            config = config,
                            service = service,
                            visualizer = visualizer,
                            traceRecorder = traceRecorder,
                            overlayTouchGate = overlayTouchGate,
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

    fun getServices(): SessionServices = services

    private val _state = MutableStateFlow<SessionState>(SessionState.Created)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 8, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

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

    private val completionEmitted = AtomicBoolean(false)

    private val channelCloseScheduled = AtomicBoolean(false)
    suspend fun submit(op: Op) {
        Log.d(TAG, "Received Op: $op (current state: ${_state.value})")

        when (op) {
            is Op.Takeover -> handleTakeover()
            is Op.Resume -> handleResume()
            is Op.Interrupt -> handleInterrupt()
            is Op.Shutdown -> handleShutdown()
            is Op.UserInput -> handleUserInput(op)
            is Op.Supplement -> handleSupplement(op.text)
            is Op.UserResponse -> handleUserResponse(op.callId, op.response)
            is Op.Approve -> handleApproval(op)
        }
    }

    internal fun emitStatus(status: String, emoji: String? = null) {
        scope.launch {
            emit(
                    StatusUpdate(
                            sessionId = sessionId,
                            timestamp = now(),
                            status = status,
                            emoji = emoji
                    )
            )
        }
    }

    private suspend fun handleUserInput(op: Op.UserInput) {
        if (_state.value == SessionState.Running || _state.value == SessionState.Paused) {
            Log.w(TAG, "Rejecting UserInput: Session is busy")
            emitStatus("⚠️ Agent is busy. Please wait.")
            return
        }

        if (_state.value == SessionState.Created) {
            services.recordingService.initializeNewSession(
                    sessionId = sessionId.value,
                    model = config.mainModel
            )

            try {
                services.platform.start()
            } catch (e: Exception) {
                Log.e(TAG, "Platform start failed", e)
                emitStatus("⚠️ Platform initialization failed: ${e.message}")
                return
            }

            emit(
                    SessionStarted(
                            sessionId = sessionId,
                            timestamp = now(),
                            goal = op.text
                    )
            )
        }

        val taskId = "task-${System.currentTimeMillis()}"
        currentTaskId = taskId
        _state.value = SessionState.Running

        emit(
                TaskStarted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        input = op.text
                )
        )

        agentRunner.start(op.text, taskId)

        Log.i(TAG, "Task started: $taskId, input: ${op.text}")
    }

    private suspend fun handleAgentComplete(reason: AgentStopReason) {
        if (_state.value == SessionState.Shutdown) {
            return
        }

        val taskId = currentTaskId ?: "unknown"
        val completionReason = reason.toCompletionReason()
        val resultMessage =
                when (reason) {
                    is AgentStopReason.Error -> reason.message
                    is AgentStopReason.GoalAchieved -> reason.message
                    else -> null
                }

        emit(
                TaskCompleted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        result = resultMessage,
                        reason = completionReason
                )
        )

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

    private suspend fun handleTakeover() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot takeover: not running (state: ${_state.value})")
            return
        }

        val confirmed = agentRunner.pause()
        _state.value = SessionState.Paused
        confirmed.await()

        emit(SessionTakeover(sessionId = sessionId, timestamp = now()))

        Log.i(TAG, "Session takeover (paused): $sessionId")
    }

    private suspend fun handleResume() {
        if (_state.value != SessionState.Paused) {
            Log.w(TAG, "Cannot resume: not paused (state: ${_state.value})")
            return
        }

        _state.value = SessionState.Running

        agentRunner.resume()

        emit(SessionResumed(sessionId = sessionId, timestamp = now()))

        Log.i(TAG, "Session resumed: $sessionId")
    }

    private suspend fun handleSupplement(text: String) {
        val currentState = _state.value
        if (currentState != SessionState.Running && currentState != SessionState.Paused) {
            Log.w(TAG, "Cannot supplement: not running or paused (state: $currentState)")
            return
        }

        services.historyManager.addItem(
            com.moonkey.androidagent.history.ResponseItem.Message(
                role = "user",
                content = text
            )
        )

        emit(SupplementReceived(sessionId = sessionId, timestamp = now(), text = text))

        Log.i(TAG, "Supplement received: ${text.take(50)}")
    }

    private suspend fun handleUserResponse(callId: String, response: String) {
        val delivered = services.userResponseChannel.deliver(callId, response)
        if (delivered) {
            Log.i(TAG, "UserResponse delivered: callId=$callId")
        } else {
            Log.w(TAG, "UserResponse not delivered (no matching pending request): callId=$callId")
        }
    }

    private suspend fun handleInterrupt() {
        if (_state.value != SessionState.Running) {
            Log.w(TAG, "Cannot interrupt: not running (state: ${_state.value})")
            return
        }

        services.userResponseChannel.cancel()

        agentRunner.stop()
        Log.i(TAG, "Interrupt requested")
    }

    private suspend fun handleShutdown() {
        Log.i(TAG, "Shutting down session: $sessionId")

        val previousState = _state.value
        _state.value = SessionState.Shutdown

        services.userResponseChannel.cancel()

        agentRunner.shutdown()

        services.cleanup()

        if (completionEmitted.compareAndSet(false, true)) {
            val reason = when (previousState) {
                SessionState.Running, SessionState.Paused -> CompletionReason.USER_STOPPED
                else -> CompletionReason.INTERRUPTED
            }
            emit(
                    SessionCompleted(
                            sessionId = sessionId,
                            timestamp = now(),
                            result = null,
                            reason = reason
                    )
            )
        }

        closeChannelWithDelay()
    }

    private suspend fun handleApproval(op: Op.Approve) {
        services.toolRouter.resolveApproval(op.actionId, op.decision)

        emit(
                ApprovalResolved(
                        sessionId = sessionId,
                        timestamp = now(),
                        actionId = op.actionId,
                        decision = op.decision
                )
        )

        Log.d(TAG, "Resolved approval: ${op.actionId} -> ${op.decision}")
    }

    private suspend fun emit(event: AgentEvent) {
        try {
            _events.emit(event)
            Log.d(TAG, "Emitted event: ${event::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit event: $event", e)
        }
    }

    private fun closeChannelWithDelay() {
        if (!channelCloseScheduled.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            delay(EVENT_DELIVERY_GRACE_PERIOD_MS)
            Log.d(TAG, "Session event stream ended")
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
