package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.accessibility.AccessibilityEvent
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.history.model.ScreenStateRecord
import com.moonkey.androidagent.platform.virtualdisplay.VirtualDisplayPlatform
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.overlay.StatusIslandManager
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AgentService - The entry point for the Accessibility Service.
 *
 * **Phase 2**: Now uses AgentSession with Op/Event protocol. Operations are submitted via Op sealed
 * class, status is received via AgentEvent Flow.
 *
 * Status updates are exposed via [statusFlow] for lifecycle-aware collection by MainActivity.
 */
class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"

        /** Broadcast action to stop the agent remotely (from scripts) */
        const val ACTION_STOP_AGENT = "com.moonkey.androidagent.STOP_AGENT"

        /** Our package name for detecting when app is in foreground */
        private const val OUR_PACKAGE = "com.moonkey.androidagent"

        @Volatile
        var instance: AgentService? = null
            private set

        /**
         * Status updates exposed as StateFlow for lifecycle-aware collection. Static so
         * MainActivity can collect even before service instance is available.
         */
        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var session: AgentSession? = null
    private var overlayController: ServiceOverlayController? = null
    private var actionVisualizer: ActionVisualizerManager? = null
    private var currentPlatformMode: PlatformMode = PlatformMode.ACCESSIBILITY

    /**
     * Access the capsule state holder for in-app Compose UI.
     * Returns null if service not connected or overlay not initialized.
     */
    val capsuleStateHolder get() = overlayController?.stateHolder

    /** Returns the currently active session observed by the service, if any. */
    fun getActiveSession(): AgentSession? = session

    /**
     * Get the action visualizer for use in sessions created by MainActivity. Returns null if
     * service is not connected or visualizer not initialized.
     *
     * Note: Internal visibility - only for use within the app module. External code should not
     * depend on this method.
     */
    internal fun getActionVisualizer(): ActionVisualizerManager? = actionVisualizer

    /** Job for the current session's event collector, cancelled before starting new session */
    private var eventCollectorJob: Job? = null

    /**
     * Register an external session (created by MainActivity) for capsule observation. This allows
     * the SmartCapsule to display streaming updates from sessions created outside AgentService.
     *
     * @param platformMode The platform mode for this session — required to set up the correct
     * overlay strategy (StatusIsland for VD, capsule+glow for A11y).
     */
    fun observeExternalSession(
            externalSession: AgentSession,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        Log.i(TAG, "Observing external session: ${externalSession.sessionId}, mode=$platformMode")
        session = externalSession
        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)
        observeSession(externalSession)
    }

    /** BroadcastReceiver to handle remote stop commands (from debug-run.sh script) */
    private val stopReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_STOP_AGENT) {
                        Log.i(TAG, "Received STOP_AGENT broadcast")
                        stopAgent()
                    }
                }
            }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentService connected")

        // Ensure we retrieve interactive windows
        val info = serviceInfo
        if (info != null) {
            info.flags =
                    info.flags or
                            android.accessibilityservice.AccessibilityServiceInfo
                                    .FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
            Log.i(TAG, "Updated service info flags: ${info.flags}")
        } else {
            Log.w(TAG, "Service info was null!")
        }

        updateStatus("Accessibility Service connected")

        overlayController =
                ServiceOverlayController(
                        context = this,
                        scope = scope,
                        appPackage = OUR_PACKAGE,
                        logTag = TAG,
                        onStop = { submitOp(Op.Shutdown) },
                        onTakeover = { submitOp(Op.Takeover) },
                        onResume = { submitOp(Op.Resume) },
                        onSupplement = { text -> submitOp(Op.Supplement(text)) },
                        onUserResponse = { callId, response -> submitOp(Op.UserResponse(callId, response)) },
                        onOpenApp = {
                            val intent =
                                    Intent(this, MainActivity::class.java).apply {
                                        flags =
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                            startActivity(intent)
                        },
                        onOpenViewer = { openViewer() },
                        statusIslandManager =
                                StatusIslandManager(
                                        service = this,
                                        onExpandCapsule = {
                                            // Tap island → expand Smart Capsule overlay, hide island
                                            overlayController?.onIslandTapped()
                                        }
                                )
                )

        // Initialize ActionVisualizerManager for touch action visualization
        actionVisualizer = ActionVisualizerManager(this)
        Log.i(TAG, "ActionVisualizerManager initialized")

        // Register broadcast receiver for remote stop commands (from adb/debug-run.sh)
        // Only in debug builds - security risk if exposed in production
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter(ACTION_STOP_AGENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(stopReceiver, filter)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detect when our app goes to foreground/background to show/hide capsule
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlayController?.handleWindowStateChanged(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString()
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        submitOp(Op.Shutdown)
        overlayController?.dispose()
        overlayController = null
        actionVisualizer?.dispose()
        actionVisualizer = null
        if (BuildConfig.DEBUG) {
            try {
                unregisterReceiver(stopReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was never registered
                Log.w(TAG, "stopReceiver was not registered: ${e.message}")
            }
        }
        super.onDestroy()
        instance = null
        // Reset statusFlow to prevent stale values when service restarts
        _statusFlow.value = ""
        scope.cancel()
    }

    /** Submit an operation to the current session. */
    private fun submitOp(op: Op) {
        val currentSession = session
        Log.d(TAG, "submitOp: $op, session=${currentSession?.sessionId}")

        if (currentSession == null) {
            Log.w(TAG, "No active session for op: $op")
            return
        }

        scope.launch { currentSession.submit(op) }
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, status)
        _statusFlow.value = status
    }

    /**
     * Start observing events from the session. Cancels any previous collector before starting new
     * one.
     */
    private fun observeSession(agentSession: AgentSession) {
        // Cancel previous collector if still active
        eventCollectorJob?.cancel()

        // Cache the recording service to avoid repeated lookup in high-frequency event loop
        val recordingService = agentSession.getServices().recordingService

        eventCollectorJob =
                scope.launch {
                    agentSession.events.collect { event -> handleEvent(event, recordingService) }
                }
    }

    /** Handle events from the session. */
    private fun handleEvent(
            event: AgentEvent,
            recordingService: com.moonkey.androidagent.history.SessionRecordingService? = null
    ) {
        Log.d(TAG, "Received event: ${event::class.simpleName}")

        when (event) {
            is AgentEvent.StatusUpdate -> {
                val displayStatus =
                        if (event.emoji != null) {
                            "${event.emoji} ${event.status}"
                        } else {
                            event.status
                        }
                updateStatus(displayStatus)
            }
            is AgentEvent.SessionStarted -> {
                Log.i(TAG, "Session started: ${event.sessionId}, goal: ${event.goal}")
            }

            // ===== Task Events (for SmartCapsule streaming) =====

            is AgentEvent.TaskStarted -> {
                recordingService?.recordUserMessage(
                        UUID.randomUUID().toString(),
                        event.timestamp,
                        event.input
                )
                recordingService?.startAgentMessage(event.taskId, event.timestamp)
                overlayController?.onTaskStarted(event.taskId, event.input)
            }
            is AgentEvent.MessageDelta -> {
                recordingService?.appendTextDelta(event.delta)
            }
            is AgentEvent.ThoughtUpdate -> {
                overlayController?.onThoughtUpdate(event.thought)
            }
            is AgentEvent.TurnPhaseChanged -> {
                overlayController?.onTurnPhaseChanged(event.phase)
            }
            is AgentEvent.ActionExecuted -> {
                val state = if (event.success) "success" else "failed"
                recordingService?.updateActionState(event.actionId, state, event.result)
                overlayController?.onActionExecuted(event.toolName, event.success)
            }
            is AgentEvent.SubAgentStarted -> {
                updateStatus("🤖 Delegating to ${event.agentName}...")
            }
            is AgentEvent.SubAgentActivity -> {
                // Activity events can be very frequent; keep UI/log noise low.
            }
            is AgentEvent.SubAgentCompleted -> {
                val status = if (event.success) "completed" else "failed"
                updateStatus("🤖 ${event.agentName} $status")
            }
            is AgentEvent.TaskCompleted -> {
                recordingService?.completeAgentMessage()
                overlayController?.onTaskCompleted(event.reason, event.result)
            }
            is AgentEvent.ActionProposed -> {
                recordingService?.recordAction(
                        actionId = event.actionId,
                        toolName = event.toolName,
                        description = event.description,
                        state = "proposed"
                )
            }
            is AgentEvent.ScreenCaptured -> {
                recordingService?.recordScreenState(
                        ScreenStateRecord(
                                id = UUID.randomUUID().toString(),
                                timestamp = event.timestamp,
                                turnId = event.turnId,
                                turnNumber = event.turnNumber,
                                phase = event.phase,
                                elementCount = event.elementCount,
                                packageName = event.packageName,
                                activityName = event.activityName,
                                rawA11yTreePath = event.rawA11yTreePath,
                                sanitizedA11yTreePath = event.sanitizedA11yTreePath,
                                screenshotPath = event.screenshotPath,
                                traceRunId = event.traceRunId
                        )
                )
            }

            // ===== Session Lifecycle Events =====

            is AgentEvent.SessionCompleted -> {
                Log.i(TAG, "Session completed: ${event.sessionId}, reason: ${event.reason}")

                // Emit a terminal status so MainActivity can detect completion
                val statusMessage =
                        when (event.reason) {
                            CompletionReason.GOAL_ACHIEVED -> "✅ Goal achieved!"
                            CompletionReason.USER_STOPPED -> "🛑 Agent stopped"
                            CompletionReason.MAX_TURNS -> "⚠️ Max turns reached"
                            CompletionReason.TASK_IMPOSSIBLE -> "❌ Task cannot be completed"
                            CompletionReason.ERROR -> "❌ Session ended with error"
                            CompletionReason.INTERRUPTED -> "🛑 Session interrupted"
                        }
                updateStatus(statusMessage)
                overlayController?.onSessionCompleted(event.reason)
                session = null
            }
            is AgentEvent.SessionError -> {
                Log.e(TAG, "Session error: ${event.error.message}")
                updateStatus("❌ Error: ${event.error.message}")
                overlayController?.onSessionError(event.error.message)
            }
            is AgentEvent.SessionTakeover -> {
                Log.i(TAG, "Session takeover: ${event.sessionId}")
                overlayController?.onSessionTakeover()
            }
            is AgentEvent.SessionResumed -> {
                Log.i(TAG, "Session resumed: ${event.sessionId}")
                overlayController?.onSessionResumed()
            }
            is AgentEvent.SupplementReceived -> {
                Log.i(TAG, "Supplement received: ${event.text.take(30)}")
                overlayController?.onSupplementReceived(event.text)
            }
            is AgentEvent.AskUser -> {
                Log.i(TAG, "AskUser: type=${event.type}, callId=${event.callId}")
                overlayController?.onAskUser(event.type, event.message, event.callId)
            }

            // Handle other events as needed
            else -> {
                Log.d(TAG, "Unhandled event type: ${event::class.simpleName}")
            }
        }
    }

    /** Run the agent loop - called from MainActivity */
    fun runAgent(
            goal: String,
            apiKeys: Map<String, String> = emptyMap(),
            maxSteps: Int = 20,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        // Stop any existing session before starting new one (prevents concurrent sessions)
        // Cancel collector first to stop receiving events, then shutdown session
        if (session != null) {
            Log.i(TAG, "Stopping existing session before starting new one")
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            // Submit shutdown synchronously since we're about to replace the session
            // The old session will handle its own cleanup
            scope.launch { session?.submit(Op.Shutdown) }
            session = null
        }

        // Set platform mode on overlay controller
        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        // Overlay windows are now managed by applyVisibility() inside ServiceOverlayController.
        // They appear automatically when the first TaskStarted event arrives.

        // Create and run session in coroutine
        scope.launch {
            try {
                val newSession =
                        AgentSession.create(
                                config =
                                        SessionConfig(
                                                maxTurns = maxSteps,
                                                debugMode = true,
                                                traceEnabled = true,
                                                platformMode = platformMode
                                        ),
                                service = this@AgentService,
                                scope = scope,
                                apiKeys = apiKeys,
                                visualizer = actionVisualizer
                        )

                session = newSession

                // Start observing events
                observeSession(newSession)

                // Submit start operation
                newSession.submit(Op.UserInput(text = goal))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                updateStatus("❌ Failed to start: ${e.message}")
                overlayController?.hideAll()
            }
        }
    }

    fun stopAgent() {
        submitOp(Op.Shutdown)
        overlayController?.hideAll()
        updateStatus("🛑 Agent stopped")
    }

    // ── Virtual Display Viewer Support ──

    /**
     * Called by VirtualDisplayViewerActivity when it becomes visible.
     * Shows capsule overlay on real screen and hides status island.
     */
    fun onViewerOpened() {
        if (overlayController == null) {
            Log.w(TAG, "onViewerOpened: overlay controller not initialized (service not connected?)")
            return
        }
        overlayController?.onViewerOpened()
    }

    /**
     * Called by VirtualDisplayViewerActivity when it becomes hidden.
     * Hides capsule overlay and shows status island.
     */
    fun onViewerClosed() {
        overlayController?.onViewerClosed()
    }

    /** Open the VirtualDisplayViewerActivity. Called from StatusIsland tap or nav button. */
    private fun openViewer() {
        try {
            val intent =
                    Intent().setClassName(
                                    this,
                                    "com.moonkey.androidagent.ui.viewer.VirtualDisplayViewerActivity"
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open viewer", e)
        }
    }

    /**
     * Called by VirtualDisplayViewerActivity when its SurfaceView is ready. Switches the virtual
     * display output to the Viewer for live preview.
     */
    fun notifyViewerVisible(surfaceView: SurfaceView) {
        val platform = session?.getServices()?.platform as? VirtualDisplayPlatform ?: return
        platform.switchToLivePreview(surfaceView)
    }

    /**
     * Called by VirtualDisplayViewerActivity when it's hidden. Switches back to ImageReader for
     * headless capture.
     */
    fun notifyViewerHidden() {
        val platform = session?.getServices()?.platform as? VirtualDisplayPlatform ?: return
        platform.switchToImageReader()
    }

}
