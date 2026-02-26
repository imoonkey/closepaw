@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.moonkey.androidagent.session

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.agent.AgentStopReason
import com.moonkey.androidagent.history.model.CheckpointState
import com.moonkey.androidagent.history.model.HistoryItemConverter
import com.moonkey.androidagent.history.model.SessionRuntimeSnapshot
import com.moonkey.androidagent.history.model.isReloadable
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.OverlayTouchGate
import com.moonkey.androidagent.platform.PlatformFactory
import com.moonkey.androidagent.protocol.*
import com.moonkey.androidagent.trace.TraceRecorderFactory
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

        /** Hot Idle timeout: auto-shutdown after 5 minutes of inactivity. */
        private const val IDLE_TIMEOUT_MS = 300_000L

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

        /**
         * Reload a session from a persisted [SessionRuntimeSnapshot].
         *
         * Hydrates HistoryManager, TodoState, ScratchpadState from the snapshot
         * and resumes the UI recording service. Returns a session in [SessionState.Created]
         * state — the first [Op.UserInput] triggers platform start as usual.
         *
         * @return the reloaded session, or null if the snapshot is invalid.
         */
        fun reload(
                snapshot: SessionRuntimeSnapshot,
                service: AccessibilityService,
                scope: CoroutineScope,
                apiKeys: Map<String, String> = emptyMap(),
                visualizer: ActionVisualizerManager? = null,
                overlayTouchGate: OverlayTouchGate? = null,
        ): AgentSession? {
            if (snapshot.schemaVersion != 1) {
                Log.w(TAG, "Unsupported schema version ${snapshot.schemaVersion}, cannot reload")
                return null
            }
            if (!snapshot.checkpointState.isReloadable()) {
                Log.w(
                    TAG,
                    "Snapshot state is ${snapshot.checkpointState}, expected IDLE_READY or CLOSED"
                )
                return null
            }

            val config = snapshot.config.toSessionConfig()
            val sessionId = SessionId(snapshot.sessionId)
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

            val historyItems = HistoryItemConverter.fromRecords(snapshot.historyItems)
            services.historyManager.replaceAll(historyItems)

            val restoredTodos =
                    snapshot.todos.map { todo ->
                        val status = try {
                            TodoStatus.valueOf(todo.status)
                        } catch (_: IllegalArgumentException) {
                            TodoStatus.PENDING
                        }
                        com.moonkey.androidagent.protocol.Todo(
                                description = todo.description,
                                status = status
                        )
                    }
            services.sessionState.todos.update(restoredTodos)

            snapshot.scratchpad.forEach { (key, value) ->
                services.sessionState.scratchpad.write(key, value)
            }

            Log.i(TAG, "Reloaded session $sessionId with ${historyItems.size} history items")

            return AgentSession(
                    sessionId = sessionId,
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

    private val checkpointCoordinator = SessionCheckpointCoordinator(
            sessionId = sessionId.value,
            config = config,
            historyManager = services.historyManager,
            sessionState = services.sessionState,
            recordingService = services.recordingService
    )

    init {
        services.historyManager.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }
        services.sessionState.todos.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }
        services.sessionState.scratchpad.setMutationListener { checkpointCoordinator.scheduleCheckpoint(_state.value) }
    }

    private var currentTaskId: String? = null

    /** Idle timeout job — auto-triggers Shutdown after [IDLE_TIMEOUT_MS] of inactivity. */
    private var idleTimeoutJob: Job? = null

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
        when (_state.value) {
            SessionState.Running, SessionState.Paused -> {
                Log.w(TAG, "Rejecting UserInput: Session is busy")
                emitStatus("⚠️ Agent is busy. Please wait.")
                return
            }
            SessionState.Shutdown -> {
                Log.w(TAG, "Rejecting UserInput: Session is shut down")
                return
            }
            SessionState.Created -> {
                if (!initializeForFirstTask()) return
                emit(SessionStarted(sessionId = sessionId, timestamp = now(), goal = op.text))
            }
            SessionState.Idle -> {
                if (!reacquirePlatform()) return
            }
        }
        startTask(op.text)
    }

    /** Initialize recording + platform on first [Op.UserInput]. Returns false on failure. */
    private suspend fun initializeForFirstTask(): Boolean {
        val recorderSessionId = services.recordingService.getCurrentSessionId()
        if (recorderSessionId == null || recorderSessionId != sessionId.value) {
            services.recordingService.initializeNewSession(
                    sessionId = sessionId.value,
                    model = config.mainModel
            )
        }
        try {
            services.platform.start()
        } catch (e: Exception) {
            Log.e(TAG, "Platform start failed", e)
            emitStatus("⚠️ Platform initialization failed: ${e.message}")
            return false
        }
        return true
    }

    /** Ensure platform is ready for Hot Idle follow-up. Returns false on failure. */
    private suspend fun reacquirePlatform(): Boolean {
        cancelIdleTimeout()
        try {
            services.platform.start() // Idempotent — no-op if already running
        } catch (e: Exception) {
            Log.e(TAG, "Platform start failed on follow-up", e)
            emitStatus("⚠️ Platform start failed: ${e.message}")
            scheduleIdleTimeout() // re-arm so session doesn't leak in Idle
            return false
        }
        Log.i(TAG, "Hot Idle follow-up: platform ready")
        return true
    }

    private suspend fun startTask(text: String) {
        val taskId = "task-${System.currentTimeMillis()}"
        currentTaskId = taskId
        _state.value = SessionState.Running

        emit(
                TaskStarted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        input = text
                )
        )

        agentRunner.start(text, taskId)

        Log.i(TAG, "Task started: $taskId, input: $text")
    }

    /**
     * Handle agent task completion — transition to Hot Idle.
     *
     * Releases agent runner but keeps platform alive (VD apps keep running).
     * Lightweight conversation state stays in memory for instant follow-up.
     * Schedules idle timeout for auto-shutdown.
     */
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

        // 1. Flush trace to disk BEFORE signaling completion — guarantees all trace
        //    events (including session_stopped + run_summary) are on disk before the
        //    runner detects TaskCompleted in logcat and force-stops the process.
        try {
            services.traceRecorder.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Trace flush failed (non-fatal): ${e.message}")
        }

        // 2. Emit TaskCompleted (per-task event, not session-level)
        emit(
                TaskCompleted(
                        sessionId = sessionId,
                        timestamp = now(),
                        taskId = taskId,
                        result = resultMessage,
                        reason = completionReason
                )
        )

        // 3. Flush checkpoint for process-death recovery
        val checkpointed = checkpointCoordinator.flushIdleReady()
        if (!checkpointed) {
            emitStatus("⚠️ Checkpoint save failed; session kept alive in memory.")
            Log.e(TAG, "Checkpoint save failed for task $taskId; session kept alive in memory")
        }

        // 4. Transition to Idle (Hot Idle)
        _state.value = SessionState.Idle
        currentTaskId = null

        // 5. Release agent runner only; platform stays alive for follow-up tasks
        agentRunner.clear()

        // 6. Schedule idle timeout (auto-shutdown after inactivity)
        scheduleIdleTimeout()

        Log.i(TAG, "Task $taskId completed (reason=$completionReason). Session idle, awaiting follow-up.")
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
                kind = com.moonkey.androidagent.history.MessageKind.USER_INTENT,
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
        // Idempotency guard — safe against double-call from timeout + explicit shutdown
        if (_state.value == SessionState.Shutdown) {
            Log.d(TAG, "Already shut down, ignoring duplicate Shutdown")
            return
        }

        Log.i(TAG, "Shutting down session: $sessionId")

        val previousState = _state.value
        _state.value = SessionState.Shutdown

        cancelIdleTimeout()

        val closedCheckpointSaved = checkpointCoordinator.flushClosed()
        if (!closedCheckpointSaved) {
            Log.w(TAG, "Failed to flush CLOSED checkpoint for $sessionId")
        }

        disableCheckpointMutationListeners()
        services.userResponseChannel.cancel()

        agentRunner.shutdown()

        services.cleanup()

        val reason = when (previousState) {
            SessionState.Running, SessionState.Paused -> CompletionReason.USER_STOPPED
            SessionState.Idle -> CompletionReason.IDLE_TIMEOUT
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

        Log.i(TAG, "Session shutdown complete: $sessionId")
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

    // ===== Idle Timeout =====

    private fun scheduleIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.i(TAG, "Idle timeout expired, auto-shutting down session $sessionId")
            handleShutdown()
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun disableCheckpointMutationListeners() {
        services.historyManager.setMutationListener(null)
        services.sessionState.todos.setMutationListener(null)
        services.sessionState.scratchpad.setMutationListener(null)
    }
}
