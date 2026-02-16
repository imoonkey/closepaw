package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityEvent
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.platform.virtualdisplay.VirtualDisplayPlatform
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.session.AgentSession
import com.moonkey.androidagent.ui.overlay.compose.IslandOverlayHost
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.compose.ServiceLifecycleOwner
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

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
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var session: AgentSession? = null
    private var overlayController: ServiceOverlayController? = null
    private var actionVisualizer: ActionVisualizerManager? = null
    private var currentPlatformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    @Volatile private var isServiceActive = false
    private val eventHandler =
            AgentServiceEventHandler(
                    logTag = TAG,
                    updateStatus = ::updateStatus,
                    sessionCleared = { session = null },
                    overlayController = { overlayController }
            )

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
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring observeExternalSession while service is shutting down")
            return
        }
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
        isServiceActive = true
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
                        lifecycleOwner = serviceLifecycleOwner,
                        savedStateRegistryOwner = serviceLifecycleOwner,
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
                                IslandOverlayHost(
                                        service = this,
                                        lifecycleOwner = serviceLifecycleOwner,
                                        savedStateRegistryOwner = serviceLifecycleOwner,
                                        onExpandCapsule = {
                                            // Tap island → expand Smart Capsule overlay, hide island
                                            overlayController?.onIslandTapped()
                                        }
                                )
                )

        // Initialize ActionVisualizerManager for touch action visualization
        actionVisualizer = ActionVisualizerManager(
                context = this,
                lifecycleOwner = serviceLifecycleOwner,
                savedStateRegistryOwner = serviceLifecycleOwner
        )
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
            val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                event.displayId
            } else {
                Display.DEFAULT_DISPLAY
            }
            overlayController?.handleWindowStateChanged(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString(),
                    displayId = eventDisplayId,
            )
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        // Fence off new work immediately so callers don't grab a half-destroyed instance.
        isServiceActive = false
        instance = null

        // Cancel event collector first to avoid handling events during teardown.
        eventCollectorJob?.cancel()
        eventCollectorJob = null

        val currentSession = session
        if (currentSession != null) {
            runBlocking {
                val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    currentSession.submit(Op.Shutdown)
                    true
                }
                if (completed != true) {
                    Log.w(TAG, "Timed out waiting for session shutdown")
                }
            }
        }
        session = null

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
        serviceLifecycleOwner.onDestroy()
        super.onDestroy()
        // Reset statusFlow to prevent stale values when service restarts
        _statusFlow.value = ""
        scope.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
    }

    /** Submit an operation to the current session. */
    private fun submitOp(op: Op) {
        if (!isServiceActive) {
            Log.w(TAG, "Dropping op while service is not active: $op")
            return
        }
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
                    try {
                        agentSession.events.collect { event ->
                            try {
                                eventHandler.handleEvent(event, recordingService)
                            } catch (e: Exception) {
                                Log.e(
                                        TAG,
                                        "Failed to handle event: ${event::class.simpleName}",
                                        e
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Session event collector crashed", e)
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
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring runAgent because service is not active")
            return
        }
        // Stop any existing session before starting new one (prevents concurrent sessions)
        // Cancel collector first to stop receiving events, then shutdown session
        if (session != null) {
            Log.i(TAG, "Stopping existing session before starting new one")
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            val oldSession = session
            session = null
            // Submit shutdown synchronously since we're about to replace the session
            // The old session will handle its own cleanup
            scope.launch { oldSession?.submit(Op.Shutdown) }
        }

        // Set platform mode on overlay controller
        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        // Overlay windows are now managed by applyVisibility() inside ServiceOverlayController.
        // They appear automatically when the first TaskStarted event arrives.

        // Create and run session in coroutine
        scope.launch {
            try {
                val sessionConfig =
                        SessionConfig(
                                maxTurns = maxSteps,
                                debugMode = true,
                                traceEnabled = true,
                                platformMode = platformMode
                        )
                val visualizer = actionVisualizer
                val newSession =
                        withContext(Dispatchers.Default) {
                            AgentSession.create(
                                    config = sessionConfig,
                                    service = this@AgentService,
                                    scope = scope,
                                    apiKeys = apiKeys,
                                    visualizer = visualizer
                            )
                        }

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

    /** MainActivity foreground callback to enforce MAIN_APP overlay invariants. */
    fun onMainAppVisible() {
        overlayController?.onMainAppVisible()
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

    /**
     * Forward touch events from VirtualDisplayViewerActivity to VD input injection.
     */
    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        // Viewer interaction is only allowed after explicit takeover.
        // Returning true consumes the touch so it doesn't accidentally control VD while agent runs.
        val currentMode = overlayController?.stateHolder?.mode?.value
        if (currentMode !is CapsuleMode.Takeover) return true

        val platform = session?.getServices()?.platform as? VirtualDisplayPlatform ?: return false
        return platform.onViewerTouch(
                action = action,
                x = x,
                y = y,
                downTime = downTime,
                eventTime = eventTime,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
        )
    }

}
