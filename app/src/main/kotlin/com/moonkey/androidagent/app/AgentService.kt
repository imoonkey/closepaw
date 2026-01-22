package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.ui.overlay.EdgeGlowManager
import com.moonkey.androidagent.ui.overlay.SmartCapsuleManager
import com.moonkey.androidagent.ui.overlay.model.GlowState
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import android.view.accessibility.AccessibilityEvent
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.Op
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.session.AgentSession
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
 * **Phase 2**: Now uses AgentSession with Op/Event protocol.
 * Operations are submitted via Op sealed class, status is received via AgentEvent Flow.
 * 
 * Status updates are exposed via [statusFlow] for lifecycle-aware collection by MainActivity.
 */
class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"
        
        /** Broadcast action to stop the agent remotely (from dev.sh script) */
        const val ACTION_STOP_AGENT = "com.moonkey.androidagent.STOP_AGENT"
        
        /** Our package name for detecting when app is in foreground */
        private const val OUR_PACKAGE = "com.moonkey.androidagent"

        @Volatile
        var instance: AgentService? = null
            private set
        
        /** 
         * Status updates exposed as StateFlow for lifecycle-aware collection.
         * Static so MainActivity can collect even before service instance is available.
         */
        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var session: AgentSession? = null
    private var capsuleManager: SmartCapsuleManager? = null
    private var edgeGlowManager: EdgeGlowManager? = null
    private var actionVisualizer: ActionVisualizerManager? = null
    
    /**
     * Get the action visualizer for use in sessions created by MainActivity.
     * Returns null if service is not connected or visualizer not initialized.
     */
    fun getActionVisualizer(): ActionVisualizerManager? = actionVisualizer
    
    /** Job for the current session's event collector, cancelled before starting new session */
    private var eventCollectorJob: Job? = null
    
    /** Whether there's an active task that should show capsule when app is in background */
    private var hasActiveTask = false
    
    /** Whether our app is currently in the foreground (initialized to true since service is enabled from our app) */
    private var isOurAppInForeground = true
    
    /** Current task input for restoring capsule state when switching to background */
    private var currentTaskInput: String? = null
    
    /** Current glow state for restoring when switching to background */
    private var currentGlowState: GlowState = GlowState.Active
    
    /** Tracking last known foreground package for debug logging */
    private var lastKnownForegroundPackage: String? = null
    
    /**
     * Register an external session (created by MainActivity) for capsule observation.
     * This allows the SmartCapsule to display streaming updates from sessions created outside AgentService.
     */
    fun observeExternalSession(externalSession: AgentSession) {
        Log.i(TAG, "Observing external session: ${externalSession.sessionId}")
        session = externalSession
        // Don't show capsule here - it will be shown on TaskStarted if app is not in foreground
        observeSession(externalSession)
    }
    
    /** BroadcastReceiver to handle remote stop commands (from dev.sh script) */
    private val stopReceiver = object : BroadcastReceiver() {
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
        updateStatus("Accessibility Service connected")

        // NOTE: EdgeGlowManager is initialized before SmartCapsuleManager so that its
        // overlay is added to WindowManager first and *should* render below the capsule.
        // This relies on platform/OEM behavior that is not strongly documented and may
        // vary across Android versions and device manufacturers.
        //
        // If you change overlay window types/flags in EdgeGlowManager or SmartCapsuleManager,
        // or target new Android versions, verify the z-order (glow under capsule) on
        // representative devices.
        edgeGlowManager = EdgeGlowManager(context = this)
        
        // Initialize SmartCapsuleManager with Op-based callbacks
        capsuleManager = SmartCapsuleManager(
            context = this,
            onStop = { submitOp(Op.Shutdown) },
            onPause = { submitOp(Op.Pause) },
            onResume = { submitOp(Op.Resume) },
            onOpenApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        )
        
        // Initialize ActionVisualizerManager for touch action visualization
        actionVisualizer = ActionVisualizerManager(this)
        Log.i(TAG, "ActionVisualizerManager initialized")
        
        // Register broadcast receiver for remote stop commands (from adb/dev.sh)
        // Only in debug builds - security risk if exposed in production
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter(ACTION_STOP_AGENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(stopReceiver, filter)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detect when our app goes to foreground/background to show/hide capsule
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            
            // Log all window state changes for debugging
            Log.d(TAG, "TYPE_WINDOW_STATE_CHANGED: pkg=$packageName, class=$className, lastKnown=$lastKnownForegroundPackage")
            
            // IMPORTANT: Only consider Activity windows for foreground detection.
            // Popup windows, overlays, and dialogs (like FrameLayout, PopupWindow, etc.)
            // should not affect foreground state - they would cause our capsule overlay
            // to trigger a false "our app is in foreground" detection.
            val isActivityWindow = className != null && 
                (className.endsWith("Activity") || 
                 className.contains("Launcher") ||
                 className.contains(".app.") ||
                 // Some launchers use custom class names
                 className.contains("Home"))
            
            // Skip non-activity windows to avoid detecting our own overlay as "foreground"
            if (!isActivityWindow) {
                Log.d(TAG, "Ignoring non-activity window: $className")
                return
            }
            
            if (packageName != null && packageName != lastKnownForegroundPackage) {
                lastKnownForegroundPackage = packageName
                val wasInForeground = isOurAppInForeground
                isOurAppInForeground = packageName == OUR_PACKAGE
                
                Log.d(TAG, "Window changed (state update): pkg=$packageName, wasInForeground=$wasInForeground, isInForeground=$isOurAppInForeground, hasActiveTask=$hasActiveTask")
                
                // Only react if state changed and we have an active task
                if (wasInForeground != isOurAppInForeground && hasActiveTask) {
                    if (isOurAppInForeground) {
                        // Our app came to foreground - hide capsule and glow
                        Log.d(TAG, "Our app in foreground, hiding capsule and glow")
                        capsuleManager?.hide()
                        edgeGlowManager?.hideImmediately()
                    } else {
                        // Our app went to background - show capsule and glow with current state
                        Log.d(TAG, "Our app went to background with active task, showing capsule and glow")
                        Log.d(TAG, "  capsuleManager=${capsuleManager != null}, currentTaskInput=$currentTaskInput, glowState=$currentGlowState")
                        
                        // Show edge glow with current state
                        edgeGlowManager?.show(currentGlowState)
                        
                        // Show capsule
                        if (currentTaskInput != null) {
                            Log.d(TAG, "  calling onTaskStarted with input")
                            capsuleManager?.onTaskStarted("restore", currentTaskInput!!)
                        } else {
                            Log.d(TAG, "  calling show() directly (no input)")
                            capsuleManager?.show()
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        submitOp(Op.Shutdown)
        edgeGlowManager?.dispose()
        capsuleManager?.hide()
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

    /**
     * Submit an operation to the current session.
     */
    private fun submitOp(op: Op) {
        val currentSession = session
        Log.d(TAG, "submitOp: $op, session=${currentSession?.sessionId}")
        
        if (currentSession == null && op !is Op.Start) {
            Log.w(TAG, "No active session for op: $op")
            return
        }
        
        scope.launch {
            currentSession?.submit(op)
        }
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, status)
        _statusFlow.value = status
        capsuleManager?.updateStatus(status)
    }

    /**
     * Start observing events from the session.
     * Cancels any previous collector before starting new one.
     */
    private fun observeSession(agentSession: AgentSession) {
        // Cancel previous collector if still active
        eventCollectorJob?.cancel()
        
        eventCollectorJob = scope.launch {
            agentSession.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    /**
     * Handle events from the session.
     */
    private fun handleEvent(event: AgentEvent) {
        Log.d(TAG, "Received event: ${event::class.simpleName}")
        
        when (event) {
            is AgentEvent.StatusUpdate -> {
                val displayStatus = if (event.emoji != null) {
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
                Log.i(TAG, "Task started: ${event.taskId}, input: ${event.input}, isOurAppInForeground=$isOurAppInForeground")
                hasActiveTask = true
                currentTaskInput = event.input
                currentGlowState = GlowState.Active
                
                // Only show overlays if app is NOT in foreground
                // When app goes to background later, onAccessibilityEvent will show them
                if (!isOurAppInForeground) {
                    Log.d(TAG, "App not in foreground, showing capsule and glow for task")
                    edgeGlowManager?.show(GlowState.Active)
                    capsuleManager?.onTaskStarted(event.taskId, event.input)
                } else {
                    Log.d(TAG, "App in foreground, overlays will show when navigating away")
                }
            }
            
            is AgentEvent.MessageDelta -> {
                // Always forward streaming text to capsule
                capsuleManager?.onMessageDelta(event.turnId, event.delta)
            }
            
            is AgentEvent.TurnPhaseChanged -> {
                // Track glow state based on turn phase
                currentGlowState = when (event.phase) {
                    TurnPhase.EXECUTION -> GlowState.Executing
                    TurnPhase.PLANNING, TurnPhase.PERCEPTION, TurnPhase.REFLECTION -> GlowState.Active
                }
                // Only update if glow is showing (i.e., app not in foreground)
                edgeGlowManager?.updateState(currentGlowState)
            }
            
            is AgentEvent.ActionExecuted -> {
                // Track glow state based on action result
                currentGlowState = if (event.success) GlowState.Active else GlowState.Error
                // Only update if glow is showing (i.e., app not in foreground)
                edgeGlowManager?.updateState(currentGlowState)
                
                // Fallback: if we have an active task and not in foreground, ensure overlays visible
                // This handles race conditions where accessibility events might be missed
                if (hasActiveTask && !isOurAppInForeground) {
                    Log.d(TAG, "ActionExecuted: ensuring overlays are visible (fallback)")
                    if (edgeGlowManager?.isShowing() != true) {
                        edgeGlowManager?.show(currentGlowState)
                    }
                    if (capsuleManager?.isShowing() != true) {
                        currentTaskInput?.let { input ->
                            capsuleManager?.onTaskStarted("action-fallback", input)
                        }
                    }
                }
                // Always update capsule with action result
                capsuleManager?.onActionExecuted(event.toolName, event.success)
            }
            
            is AgentEvent.TaskCompleted -> {
                Log.i(TAG, "Task completed: ${event.taskId}")
                hasActiveTask = false
                currentTaskInput = null
                currentGlowState = GlowState.Success
                
                // Update glow to success (will auto-hide after delay) - only if showing
                edgeGlowManager?.updateState(GlowState.Success)
                
                capsuleManager?.onTaskCompleted()
            }
            
            // ===== Session Lifecycle Events =====
            
            is AgentEvent.SessionCompleted -> {
                Log.i(TAG, "Session completed: ${event.sessionId}, reason: ${event.reason}")
                hasActiveTask = false
                
                // Emit a terminal status so MainActivity can detect completion
                val statusMessage = when (event.reason) {
                    CompletionReason.GOAL_ACHIEVED -> "✅ Goal achieved!"
                    CompletionReason.USER_STOPPED -> "🛑 Agent stopped"
                    CompletionReason.MAX_TURNS -> "⚠️ Max turns reached"
                    CompletionReason.TASK_IMPOSSIBLE -> "❌ Task cannot be completed"
                    CompletionReason.ERROR -> "❌ Session ended with error"
                    CompletionReason.INTERRUPTED -> "🛑 Session interrupted"
                }
                updateStatus(statusMessage)
                
                // Update glow state based on completion reason (only if showing)
                when (event.reason) {
                    CompletionReason.GOAL_ACHIEVED, CompletionReason.MAX_TURNS -> {
                        currentGlowState = GlowState.Success
                        edgeGlowManager?.updateState(GlowState.Success)
                    }
                    CompletionReason.USER_STOPPED, CompletionReason.INTERRUPTED -> {
                        currentGlowState = GlowState.Active // Reset for next task
                        edgeGlowManager?.hideImmediately()
                    }
                    CompletionReason.ERROR, CompletionReason.TASK_IMPOSSIBLE -> {
                        currentGlowState = GlowState.Error
                        edgeGlowManager?.updateState(GlowState.Error)
                    }
                }
                
                // Don't hide capsule immediately - let TaskCompleted's 3-second delay handle it
                // Only hide immediately if session was stopped/interrupted
                if (event.reason == CompletionReason.USER_STOPPED || 
                    event.reason == CompletionReason.INTERRUPTED) {
                    capsuleManager?.hide()
                }
                session = null
            }
            
            is AgentEvent.SessionError -> {
                Log.e(TAG, "Session error: ${event.error.message}")
                updateStatus("❌ Error: ${event.error.message}")
                currentGlowState = GlowState.Error
                edgeGlowManager?.updateState(GlowState.Error)
                capsuleManager?.onError(event.error.message)
            }
            
            is AgentEvent.SessionPaused -> {
                Log.i(TAG, "Session paused: ${event.sessionId}")
                currentGlowState = GlowState.Paused
                edgeGlowManager?.updateState(GlowState.Paused)
                capsuleManager?.updatePauseState(paused = true)
            }
            
            is AgentEvent.SessionResumed -> {
                Log.i(TAG, "Session resumed: ${event.sessionId}")
                currentGlowState = GlowState.Active
                edgeGlowManager?.updateState(GlowState.Active)
                capsuleManager?.updatePauseState(paused = false)
            }
            
            // Handle other events as needed
            else -> {
                Log.d(TAG, "Unhandled event type: ${event::class.simpleName}")
            }
        }
    }

    /** Run the agent loop - called from MainActivity */
    fun runAgent(goal: String, apiKey: String, maxSteps: Int = 20) {
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
        
        // Show capsule overlay immediately
        capsuleManager?.show()
        // Note: Agent.kt emits the "Starting agent" status, don't duplicate here

        // Create and run session in coroutine
        scope.launch {
            try {
                val newSession = AgentSession.create(
                    config = SessionConfig(
                        maxTurns = maxSteps,
                        debugMode = true
                    ),
                    service = this@AgentService,
                    scope = scope,
                    apiKey = apiKey,
                    visualizer = actionVisualizer
                )
                
                session = newSession
                
                // Start observing events
                observeSession(newSession)
                
                // Submit start operation
                newSession.submit(Op.Start(goal = goal))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                updateStatus("❌ Failed to start: ${e.message}")
                capsuleManager?.hide()
            }
        }
    }

    fun stopAgent() {
        submitOp(Op.Shutdown)
        edgeGlowManager?.hideImmediately()
        capsuleManager?.hide()
        updateStatus("🛑 Agent stopped")
    }
}
