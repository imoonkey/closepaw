package ai.closepaw.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityEvent
import ai.closepaw.debug.ActionDebugReceiver
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.session.AgentSession
import ai.closepaw.ui.overlay.compose.IslandOverlayHost
import ai.closepaw.ui.overlay.compose.ServiceLifecycleOwner
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"

        const val ACTION_STOP_AGENT = "ai.closepaw.STOP_AGENT"

        private const val OUR_PACKAGE = "ai.closepaw"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        @Volatile
        var instance: AgentService? = null
            private set

        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val _effectivePlatformMode = MutableStateFlow<PlatformMode?>(null)
    val effectivePlatformMode: StateFlow<PlatformMode?> = _effectivePlatformMode.asStateFlow()

    /**
     * Signal that the VirtualDisplayViewerActivity should finish itself. Emitted by the
     * overlay controller when the agent reaches a terminal idle state with the viewer
     * still in front (see [shouldFinishViewerOnIdle]). The activity collects this in its
     * lifecycle scope and calls finish() so the user lands back on MainActivity instead
     * of being stranded on a frozen VD surface.
     *
     * SharedFlow with replay=0 + buffer=1 + DROP_OLDEST so a missed signal during
     * activity recreation doesn't leak — the next emit replaces it, and we only ever
     * care about the most recent finish request.
     */
    private val _viewerFinishSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val viewerFinishSignal: SharedFlow<Unit> = _viewerFinishSignal.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var session: AgentSession? = null
    private var overlayController: ServiceOverlayController? = null
    private var actionVisualizer: ActionVisualizerManager? = null
    private var currentPlatformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    @Volatile private var isServiceActive = false
    private val viewerBridge by lazy {
        AgentServiceViewerBridge(
            logTag = TAG,
            overlayControllerProvider = { overlayController },
            platformProvider = { session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform },
            openViewerActivity = { openViewerActivity() },
            finishViewerActivity = { _viewerFinishSignal.tryEmit(Unit) },
        )
    }
    private val eventHandler =
            AgentServiceEventHandler(
                    logTag = TAG,
                    updateStatus = ::updateStatus,
                    sessionCleared = {
                        session = null
                        _effectivePlatformMode.value = null
                    },
                    overlayController = { overlayController }
            )

    val capsuleStateHolder get() = overlayController?.stateHolder

    fun getActiveSession(): AgentSession? = session

    internal fun getActionVisualizer(): ActionVisualizerManager? = actionVisualizer

    internal fun getOverlayTouchGate(): ai.closepaw.platform.OverlayTouchGate? =
            overlayController?.overlayTouchGate

    internal fun dismissError() {
        overlayController?.dismissError()
    }

    private var eventCollectorJob: Job? = null

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
        _effectivePlatformMode.value = platformMode
        overlayController?.setPlatformMode(platformMode)
        observeSession(externalSession)
    }

    private val stopReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_STOP_AGENT) {
                        Log.i(TAG, "Received STOP_AGENT broadcast")
                        stopAgent()
                    }
                }
            }

    private val debugExecReceiver = ActionDebugReceiver()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceActive = true
        instance = this
        Log.i(TAG, "AgentService connected")

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
                        onApprovalResponse = { callId, decision, scope, packageName ->
                            submitOp(Op.Approve(callId, decision, scope, packageName))
                        },
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
                        onOpenViewer = { viewerBridge.openViewer() },
                        onFinishViewer = { viewerBridge.finishViewer() },
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

        actionVisualizer = ActionVisualizerManager(
                context = this,
                lifecycleOwner = serviceLifecycleOwner,
                savedStateRegistryOwner = serviceLifecycleOwner
        )
        Log.i(TAG, "ActionVisualizerManager initialized")

        registerDebugStopReceiverIfNeeded(this, stopReceiver)
        registerDebugExecReceiverIfNeeded(this, debugExecReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        isServiceActive = false
        instance = null

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        val currentSession = session
        if (currentSession != null) {
            // Detach shutdown onto a scope that outlives `scope`. The session handles
            // its own checkpoint persistence via NonCancellable, so the main goal here
            // is to avoid blocking the main thread (ANR risk).
            val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            shutdownScope.launch {
                try {
                    val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        currentSession.submit(Op.Shutdown)
                        true
                    }
                    if (completed != true) {
                        Log.w(TAG, "Timed out waiting for session shutdown")
                    }
                } finally {
                    shutdownScope.cancel()
                }
            }
        }
        session = null
        _effectivePlatformMode.value = null

        overlayController?.dispose()
        overlayController = null
        actionVisualizer?.dispose()
        actionVisualizer = null
        unregisterDebugStopReceiverIfNeeded(this, stopReceiver)
        unregisterDebugExecReceiverIfNeeded(this, debugExecReceiver)
        serviceLifecycleOwner.onDestroy()
        super.onDestroy()
        _statusFlow.value = ""
        scope.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
    }

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

    private fun observeSession(agentSession: AgentSession) {
        eventCollectorJob?.cancel()

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

    fun runAgent(
            goal: String,
            authStore: ai.closepaw.auth.AuthStore? = null,
            maxSteps: Int = 20,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring runAgent because service is not active")
            return
        }
        if (session != null) {
            Log.i(TAG, "Stopping existing session before starting new one")
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            val oldSession = session
            session = null
            scope.launch { oldSession?.submit(Op.Shutdown) }
        }

        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        scope.launch {
            try {
                val settings = AppSettingsStore(this@AgentService).load()
                val sessionConfig =
                        SessionConfig(
                                maxTurns = maxSteps,
                                debugMode = true,
                                traceEnabled = settings.traceEnabled,
                                platformMode = platformMode
                        )
                val visualizer = actionVisualizer
                val touchGate = overlayController?.overlayTouchGate
                val newSession =
                        withContext(Dispatchers.Default) {
                            AgentSession.create(
                                    config = sessionConfig,
                                    service = this@AgentService,
                                    scope = scope,
                                    authStore = authStore,
                                    visualizer = visualizer,
                                    overlayTouchGate = touchGate,
                            )
                        }

                session = newSession

                observeSession(newSession)

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

    fun onViewerOpened() {
        viewerBridge.onViewerOpened()
    }

    /**
     * Synchronous version of the [shouldFinishViewerOnIdle] rule. Used by
     * VirtualDisplayViewerActivity at onStart to race-proof the SharedFlow path: if the
     * agent is already idle when the user opens the viewer, the SharedFlow emit may
     * happen before the activity's collector subscribes, so we also poll directly here.
     */
    fun shouldFinishViewerNow(): Boolean = overlayController?.shouldFinishViewerNow() == true

    fun onViewerClosed() {
        viewerBridge.onViewerClosed()
    }

    fun onMainAppVisible() {
        viewerBridge.onMainAppVisible()
    }

    fun onMainAppHidden() {
        viewerBridge.onMainAppHidden()
    }

    private fun openViewerActivity() {
        try {
            val intent =
                    Intent().setClassName(
                                    this,
                                    "ai.closepaw.ui.viewer.VirtualDisplayViewerActivity"
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open viewer", e)
        }
    }

    fun notifyViewerVisible(surfaceView: SurfaceView) {
        viewerBridge.notifyViewerVisible(surfaceView)
    }

    fun notifyViewerHidden() {
        viewerBridge.notifyViewerHidden()
    }

    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerBridge.onViewerTouch(
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
