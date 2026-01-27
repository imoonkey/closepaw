package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.ui.overlay.EdgeGlowManager
import com.moonkey.androidagent.ui.overlay.SmartCapsuleManager
import com.moonkey.androidagent.ui.overlay.model.GlowState

/**
 * ServiceOverlayController - Owns overlay state and foreground tracking for AgentService.
 */
class ServiceOverlayController(
    context: AccessibilityService,
    private val appPackage: String,
    private val logTag: String,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: () -> Unit
) {
    // NOTE: EdgeGlowManager is initialized before SmartCapsuleManager so that its
    // overlay is added to WindowManager first and *should* render below the capsule.
    private val edgeGlowManager = EdgeGlowManager(context)
    private val capsuleManager = SmartCapsuleManager(
        context = context,
        onStop = onStop,
        onPause = onPause,
        onResume = onResume,
        onOpenApp = onOpenApp
    )

    private var hasActiveTask = false
    private var isAppInForeground = true
    private var currentTaskInput: String? = null
    private var currentGlowState: GlowState = GlowState.Active
    private var lastKnownForegroundPackage: String? = null

    fun updateStatus(status: String) {
        capsuleManager.updateStatus(status)
    }

    fun showCapsule() {
        capsuleManager.show()
    }

    fun hideAll() {
        edgeGlowManager.hideImmediately()
        capsuleManager.hide()
    }

    fun dispose() {
        edgeGlowManager.dispose()
        capsuleManager.hide()
    }

    fun handleWindowStateChanged(packageName: String?, className: String?) {
        Log.d(logTag, "TYPE_WINDOW_STATE_CHANGED: pkg=$packageName, class=$className, lastKnown=$lastKnownForegroundPackage")

        val isActivityWindow = className != null &&
            (className.endsWith("Activity") ||
                className.contains("Launcher") ||
                className.contains(".app.") ||
                className.contains("Home"))

        if (!isActivityWindow) {
            Log.d(logTag, "Ignoring non-activity window: $className")
            return
        }

        if (packageName != null && packageName != lastKnownForegroundPackage) {
            lastKnownForegroundPackage = packageName
            val wasInForeground = isAppInForeground
            isAppInForeground = packageName == appPackage

            Log.d(
                logTag,
                "Window changed (state update): pkg=$packageName, " +
                    "wasInForeground=$wasInForeground, isInForeground=$isAppInForeground, hasActiveTask=$hasActiveTask"
            )

            if (wasInForeground != isAppInForeground && hasActiveTask) {
                if (isAppInForeground) {
                    Log.d(logTag, "Our app in foreground, hiding capsule and glow")
                    capsuleManager.hide()
                    edgeGlowManager.hideImmediately()
                } else {
                    Log.d(logTag, "Our app went to background with active task, showing capsule and glow")
                    Log.d(logTag, "  capsuleManager=${capsuleManager.isShowing()}, currentTaskInput=$currentTaskInput, glowState=$currentGlowState")

                    edgeGlowManager.show(currentGlowState)

                    if (currentTaskInput != null) {
                        Log.d(logTag, "  calling onTaskStarted with input")
                        capsuleManager.onTaskStarted("restore", currentTaskInput!!)
                    } else {
                        Log.d(logTag, "  calling show() directly (no input)")
                        capsuleManager.show()
                    }
                }
            }
        }
    }

    fun onTaskStarted(taskId: String, input: String) {
        Log.i(logTag, "Task started: $taskId, input: $input, isAppInForeground=$isAppInForeground")
        hasActiveTask = true
        currentTaskInput = input
        currentGlowState = GlowState.Active

        if (!isAppInForeground) {
            Log.d(logTag, "App not in foreground, showing capsule and glow for task")
            edgeGlowManager.show(GlowState.Active)
            capsuleManager.onTaskStarted(taskId, input)
        } else {
            Log.d(logTag, "App in foreground, overlays will show when navigating away")
        }
    }

    fun onMessageDelta(turnId: String, delta: String) {
        capsuleManager.onMessageDelta(turnId, delta)
    }

    fun onTurnPhaseChanged(phase: TurnPhase) {
        currentGlowState = when (phase) {
            TurnPhase.EXECUTION -> GlowState.Executing
            TurnPhase.PLANNING, TurnPhase.PERCEPTION -> GlowState.Active
        }
        edgeGlowManager.updateState(currentGlowState)
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        currentGlowState = if (success) GlowState.Active else GlowState.Error
        edgeGlowManager.updateState(currentGlowState)

        if (hasActiveTask && !isAppInForeground) {
            Log.d(logTag, "ActionExecuted: ensuring overlays are visible (fallback)")
            if (!edgeGlowManager.isShowing()) {
                edgeGlowManager.show(currentGlowState)
            }
            if (!capsuleManager.isShowing()) {
                currentTaskInput?.let { input ->
                    capsuleManager.onTaskStarted("action-fallback", input)
                }
            }
        }
        capsuleManager.onActionExecuted(toolName, success)
    }

    fun onTaskCompleted() {
        Log.i(logTag, "Task completed")
        hasActiveTask = false
        currentTaskInput = null
        currentGlowState = GlowState.Success

        edgeGlowManager.updateState(GlowState.Success)
        capsuleManager.onTaskCompleted()
    }

    fun onSessionCompleted(reason: CompletionReason) {
        hasActiveTask = false

        when (reason) {
            CompletionReason.GOAL_ACHIEVED, CompletionReason.MAX_TURNS -> {
                currentGlowState = GlowState.Success
                edgeGlowManager.updateState(GlowState.Success)
            }
            CompletionReason.USER_STOPPED, CompletionReason.INTERRUPTED -> {
                currentGlowState = GlowState.Active
                edgeGlowManager.hideImmediately()
            }
            CompletionReason.ERROR, CompletionReason.TASK_IMPOSSIBLE -> {
                currentGlowState = GlowState.Error
                edgeGlowManager.updateState(GlowState.Error)
            }
        }

        if (reason == CompletionReason.USER_STOPPED || reason == CompletionReason.INTERRUPTED) {
            capsuleManager.hide()
        }
    }

    fun onSessionError(message: String) {
        currentGlowState = GlowState.Error
        edgeGlowManager.updateState(GlowState.Error)
        capsuleManager.onError(message)
    }

    fun onSessionPaused() {
        currentGlowState = GlowState.Paused
        edgeGlowManager.updateState(GlowState.Paused)
        capsuleManager.updatePauseState(paused = true)
    }

    fun onSessionResumed() {
        currentGlowState = GlowState.Active
        edgeGlowManager.updateState(GlowState.Active)
        capsuleManager.updatePauseState(paused = false)
    }
}
