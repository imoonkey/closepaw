package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.moonkey.androidagent.BuildConfig
import com.moonkey.androidagent.ui.overlay.OverlayManager
import android.view.accessibility.AccessibilityEvent
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.Op
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
    private var overlayManager: OverlayManager? = null
    
    /** Job for the current session's event collector, cancelled before starting new session */
    private var eventCollectorJob: Job? = null
    
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

        // Initialize OverlayManager with Op-based callbacks
        overlayManager = OverlayManager(
            context = this,
            onStop = { submitOp(Op.Shutdown) },
            onPause = { submitOp(Op.Pause) },
            onResume = { submitOp(Op.Resume) }
        )
        
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
        // We poll the screen, no reactive event handling needed for MVP
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        submitOp(Op.Shutdown)
        overlayManager?.hide()
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
        overlayManager?.updateStatus(status)
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
            
            is AgentEvent.SessionCompleted -> {
                Log.i(TAG, "Session completed: ${event.sessionId}, reason: ${event.reason}")
                
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
                
                overlayManager?.hide()
                session = null
            }
            
            is AgentEvent.SessionError -> {
                Log.e(TAG, "Session error: ${event.error.message}")
                updateStatus("❌ Error: ${event.error.message}")
            }
            
            is AgentEvent.SessionPaused -> {
                Log.i(TAG, "Session paused: ${event.sessionId}")
                overlayManager?.updatePauseState(paused = true)
            }
            
            is AgentEvent.SessionResumed -> {
                Log.i(TAG, "Session resumed: ${event.sessionId}")
                overlayManager?.updatePauseState(paused = false)
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
        
        // Show overlay immediately
        overlayManager?.show()
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
                    apiKey = apiKey
                )
                
                session = newSession
                
                // Start observing events
                observeSession(newSession)
                
                // Submit start operation
                newSession.submit(Op.Start(goal = goal))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                updateStatus("❌ Failed to start: ${e.message}")
                overlayManager?.hide()
            }
        }
    }

    fun stopAgent() {
        submitOp(Op.Shutdown)
        overlayManager?.hide()
        updateStatus("🛑 Agent stopped")
    }
}
