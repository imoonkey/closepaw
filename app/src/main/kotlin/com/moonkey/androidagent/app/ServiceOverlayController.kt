package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.ui.overlay.EdgeGlowManager
import com.moonkey.androidagent.ui.overlay.SmartCapsuleManager
import com.moonkey.androidagent.ui.overlay.StatusIslandManager
import com.moonkey.androidagent.ui.overlay.model.GlowState

/**
 * ServiceOverlayController — Mode-aware overlay management for AgentService.
 *
 * In ACCESSIBILITY mode: drives EdgeGlowManager + SmartCapsuleManager (unchanged).
 * In VIRTUAL_DISPLAY mode: drives StatusIslandManager only (no glow, no capsule on real screen).
 *
 * Each event handler is a flat `when(platformMode)` branch. The ACCESSIBILITY branch
 * is the exact code that ran before mode branching was added. No regression possible.
 */
class ServiceOverlayController(
    context: AccessibilityService,
    private val appPackage: String,
    private val logTag: String,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val statusIslandManager: StatusIslandManager? = null
) {
    // A11y overlays — only used in ACCESSIBILITY mode
    private val edgeGlowManager = EdgeGlowManager(context)
    private val capsuleManager = SmartCapsuleManager(
        context = context,
        onStop = onStop,
        onPause = onPause,
        onResume = onResume,
        onOpenApp = onOpenApp
    )

    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    private var hasActiveTask = false
    private var isAppInForeground = true
    private var currentTaskInput: String? = null
    private var currentGlowState: GlowState = GlowState.Active
    private var lastKnownForegroundPackage: String? = null

    /** Set platform mode. Call before any events are dispatched. */
    fun setPlatformMode(mode: PlatformMode) {
        platformMode = mode
    }

    fun updateStatus(status: String) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // Status island shows abbreviated status via updateStatus()
                statusIslandManager?.updateStatus(status, glowStateColor(currentGlowState))
            }
            PlatformMode.ACCESSIBILITY -> {
                capsuleManager.updateStatus(status)
            }
        }
    }

    fun showCapsule() {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.show()
            }
            PlatformMode.ACCESSIBILITY -> {
                capsuleManager.show()
            }
        }
    }

    fun hideAll() {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.hide()
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.hideImmediately()
                capsuleManager.hide()
            }
        }
    }

    fun dispose() {
        edgeGlowManager.dispose()
        capsuleManager.hide()
        statusIslandManager?.dispose()
    }

    fun handleWindowStateChanged(packageName: String?, className: String?) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // In VD mode, window changes on the real screen are irrelevant.
                // The agent's windows are on the virtual display. Status island stays visible.
            }
            PlatformMode.ACCESSIBILITY -> {
                handleWindowStateChangedA11y(packageName, className)
            }
        }
    }

    fun onTaskStarted(taskId: String, input: String) {
        hasActiveTask = true
        currentTaskInput = input
        currentGlowState = GlowState.Active

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus(input.take(24), glowStateColor(GlowState.Active))
                statusIslandManager?.show()
            }
            PlatformMode.ACCESSIBILITY -> {
                Log.i(logTag, "Task started: $taskId, input: $input, isAppInForeground=$isAppInForeground")
                if (!isAppInForeground) {
                    edgeGlowManager.show(GlowState.Active)
                    capsuleManager.onTaskStarted(taskId, input)
                }
            }
        }
    }

    fun onMessageDelta(turnId: String, delta: String) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // Status island doesn't show streaming text — too small.
            }
            PlatformMode.ACCESSIBILITY -> {
                capsuleManager.onMessageDelta(turnId, delta)
            }
        }
    }

    fun onTurnPhaseChanged(phase: TurnPhase) {
        currentGlowState = when (phase) {
            TurnPhase.EXECUTION -> GlowState.Executing
            TurnPhase.PLANNING, TurnPhase.PERCEPTION -> GlowState.Active
        }

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus(
                    currentTaskInput?.take(24) ?: "Working...",
                    glowStateColor(currentGlowState)
                )
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(currentGlowState)
            }
        }
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        currentGlowState = if (success) GlowState.Active else GlowState.Error

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus(
                    "$toolName ${if (success) "✓" else "✗"}",
                    glowStateColor(currentGlowState)
                )
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(currentGlowState)
                if (hasActiveTask && !isAppInForeground) {
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
        }
    }

    fun onTaskCompleted(reason: CompletionReason) {
        hasActiveTask = false
        currentTaskInput = null
        currentGlowState = GlowState.Success

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                when (reason) {
                    CompletionReason.GOAL_ACHIEVED -> statusIslandManager?.showSuccess("✓ Done!")
                    CompletionReason.ERROR, CompletionReason.TASK_IMPOSSIBLE ->
                        statusIslandManager?.showError("✗ Failed")
                    else -> statusIslandManager?.showSuccess("✓ Complete")
                }
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Success)
                capsuleManager.onTaskCompleted()
            }
        }
    }

    fun onSessionCompleted(reason: CompletionReason) {
        hasActiveTask = false

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.hide()
            }
            PlatformMode.ACCESSIBILITY -> {
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
        }
    }

    fun onSessionError(message: String) {
        currentGlowState = GlowState.Error

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.showError(message.take(24))
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Error)
                capsuleManager.onError(message)
            }
        }
    }

    fun onSessionPaused() {
        currentGlowState = GlowState.Paused

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updatePauseState(paused = true)
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Paused)
                capsuleManager.updatePauseState(paused = true)
            }
        }
    }

    fun onSessionResumed() {
        currentGlowState = GlowState.Active

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updatePauseState(paused = false)
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Active)
                capsuleManager.updatePauseState(paused = false)
            }
        }
    }

    // ── Private: A11y mode window tracking ──

    private fun handleWindowStateChangedA11y(packageName: String?, className: String?) {
        Log.d(logTag, "TYPE_WINDOW_STATE_CHANGED: pkg=$packageName, class=$className, lastKnown=$lastKnownForegroundPackage")

        val normalizedClassName = className?.substringBefore('$')
        val isActivityWindow = normalizedClassName != null &&
            (normalizedClassName.endsWith("Activity") ||
                normalizedClassName.contains("Activity") ||
                normalizedClassName.contains("Launcher") ||
                normalizedClassName.contains(".app.") ||
                normalizedClassName.contains("Home"))

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
                    edgeGlowManager.show(currentGlowState)
                    if (currentTaskInput != null) {
                        capsuleManager.onTaskStarted("restore", currentTaskInput!!)
                    } else {
                        capsuleManager.show()
                    }
                }
            }
        }
    }

    // ── Private: Color mapping ──

    private fun glowStateColor(state: GlowState): Int = when (state) {
        GlowState.Active -> 0xFF2563EB.toInt()    // Blue
        GlowState.Executing -> 0xFF7C3AED.toInt()  // Purple
        GlowState.Success -> 0xFF0D9488.toInt()     // Teal
        GlowState.Error -> 0xFFDC2626.toInt()       // Red
        GlowState.Paused -> 0xFFF59E0B.toInt()      // Amber
    }
}
