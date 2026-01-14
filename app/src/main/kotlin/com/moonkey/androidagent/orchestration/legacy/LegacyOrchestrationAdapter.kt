package com.moonkey.androidagent.orchestration.legacy

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.orchestration.AgentOrchestration
import com.moonkey.androidagent.orchestration.EventEmitter
import com.moonkey.androidagent.protocol.AgentEvent
import com.moonkey.androidagent.protocol.SessionId
import com.moonkey.androidagent.service.AgentOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LegacyOrchestrationAdapter - Wraps the existing AgentOrchestrator as an AgentOrchestration.
 * 
 * This adapter allows the existing (working) orchestrator to be used with the new
 * AgentSession interface, enabling:
 * - Gradual migration from legacy to new orchestration
 * - A/B testing between orchestration strategies
 * - Fallback option if new orchestration has issues
 * 
 * The adapter translates between:
 * - Legacy callback-based status updates → Event emission
 * - AgentOrchestration interface → AgentOrchestrator methods
 */
class LegacyOrchestrationAdapter(
    private val goal: String,
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val eventEmitter: EventEmitter,
    private val sessionId: SessionId
) : AgentOrchestration {
    
    companion object {
        private const val TAG = "LegacyOrchAdapter"
    }
    
    // The wrapped legacy orchestrator
    private var legacyOrchestrator: AgentOrchestrator? = null
    
    // Completion signal for the run() method
    private val runCompletion = CompletableDeferred<Unit>()
    
    // Track if stopped
    private var isStopped = false
    
    override suspend fun run() {
        Log.i(TAG, "Starting legacy orchestrator adapter for: $goal")
        
        // Create the legacy orchestrator with event-emitting status listener
        legacyOrchestrator = AgentOrchestrator(
            service = service,
            scope = scope,
            statusListener = { status ->
                // Convert legacy status callbacks to events
                launchAndEmit {
                    eventEmitter(AgentEvent.StatusUpdate(
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis(),
                        status = status
                    ))
                }
            }
        )
        
        // Start the legacy orchestrator
        legacyOrchestrator?.start(goal)
        
        // Wait until stopped
        // The legacy orchestrator runs its own loop, so we just wait
        // until stop() is called or the orchestrator finishes
        try {
            runCompletion.await()
        } catch (e: Exception) {
            Log.d(TAG, "Run completion interrupted: ${e.message}")
        }
        
        Log.i(TAG, "Legacy orchestrator adapter finished")
    }
    
    override suspend fun pause() {
        Log.d(TAG, "Pause requested (delegating to legacy)")
        legacyOrchestrator?.pause()
        emitStatus("⏸️ Paused")
    }
    
    override suspend fun resume() {
        Log.d(TAG, "Resume requested (delegating to legacy)")
        legacyOrchestrator?.resume()
        emitStatus("▶️ Resuming...")
    }
    
    override suspend fun interrupt() {
        Log.d(TAG, "Interrupt requested (legacy doesn't support interrupt, treating as stop)")
        // Legacy orchestrator doesn't support interrupt, so we stop
        stop()
    }
    
    override suspend fun stop() {
        if (isStopped) return
        isStopped = true
        
        Log.d(TAG, "Stop requested")
        legacyOrchestrator?.stop()
        legacyOrchestrator = null
        
        // Signal run() to return
        runCompletion.complete(Unit)
    }
    
    // ===== Helpers =====
    
    private suspend fun emitStatus(status: String) {
        eventEmitter(AgentEvent.StatusUpdate(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            status = status
        ))
    }
    
    /**
     * Launch a coroutine that emits an event.
     * Handles the non-suspend context of the status listener.
     */
    private fun launchAndEmit(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to emit event", e)
            }
        }
    }
}

