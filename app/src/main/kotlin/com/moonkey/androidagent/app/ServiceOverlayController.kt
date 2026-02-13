package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.protocol.AskUserType
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.ui.overlay.CapsuleStateHolder
import com.moonkey.androidagent.ui.overlay.EdgeGlowManager
import com.moonkey.androidagent.ui.overlay.SmartCapsuleManager
import com.moonkey.androidagent.ui.overlay.StatusIslandManager
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.GlowState

/**
 * ServiceOverlayController — Mode-aware overlay management for AgentService.
 *
 * In ACCESSIBILITY mode: drives EdgeGlowManager + SmartCapsuleManager.
 * In VIRTUAL_DISPLAY mode: drives StatusIslandManager (+ capsule overlay for ask_user).
 *
 * State is managed by [CapsuleStateHolder] (single source of truth).
 * SmartCapsuleManager is a pure renderer — it displays what [stateHolder] tells it.
 * Each event handler updates the stateHolder, then dispatches to the appropriate renderer.
 */
class ServiceOverlayController(
    context: AccessibilityService,
    private val appPackage: String,
    private val logTag: String,
    private val onStop: () -> Unit,
    private val onTakeover: () -> Unit,
    private val onResume: () -> Unit,
    private val onSupplement: (String) -> Unit,
    private val onUserResponse: (String, String) -> Unit, // (callId, response)
    private val onOpenApp: () -> Unit,
    private val onOpenViewer: (() -> Unit)? = null,
    private val statusIslandManager: StatusIslandManager? = null
) {
    // ── Unified state ──

    val stateHolder = CapsuleStateHolder()

    // ── A11y overlays ──

    private val edgeGlowManager = EdgeGlowManager(context)
    private val capsuleManager = SmartCapsuleManager(service = context).apply {
        this.onStop = this@ServiceOverlayController.onStop
        this.onTakeover = {
            stateHolder.onTakeoverRequested()
            pushModeToOverlayCapsule()
            this@ServiceOverlayController.onTakeover()
        }
        this.onResume = this@ServiceOverlayController.onResume
        this.onSupplement = { text -> this@ServiceOverlayController.onSupplement(text) }
        this.onUserResponse = { callId, response ->
            stateHolder.onUserResponseSent(callId)
            pushModeToOverlayCapsule()
            this@ServiceOverlayController.onUserResponse(callId, response)
        }
        this.onOpenApp = this@ServiceOverlayController.onOpenApp
        this.onDismissError = {
            stateHolder.onDismissError()
            pushModeToOverlayCapsule()
        }
        this.onDoneAutoHide = {
            stateHolder.onDoneAutoHide()
            pushModeToOverlayCapsule()
        }
        // Navigation callbacks
        this.onMinimize = {
            hideCapsuleOverlay()
            showIsland()
        }
        this.onOpenViewer = { this@ServiceOverlayController.onOpenViewer?.invoke() }
    }

    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    private var hasActiveTask = false
    private var isAppInForeground = true
    private var currentTaskInput: String? = null
    private var currentGlowState: GlowState = GlowState.Active
    private var lastKnownForegroundPackage: String? = null

    /** Set platform mode. Call before any events are dispatched. */
    fun setPlatformMode(mode: PlatformMode) {
        platformMode = mode
        stateHolder.setPlatformMode(mode)
        capsuleManager.updateNavContext(
            stateHolder.context.value, mode, hasIsland = statusIslandManager != null
        )
    }

    // ── Capsule overlay + island management (for VD mode navigation) ──

    fun showCapsuleOverlay() {
        capsuleManager.show()
        capsuleManager.renderMode(stateHolder.mode.value, CapsuleMode.Hidden)
    }

    fun hideCapsuleOverlay() {
        capsuleManager.hide()
    }

    fun showIsland() {
        statusIslandManager?.show()
    }

    fun hideIsland() {
        statusIslandManager?.hide()
    }

    /** Called when status island is tapped — expand capsule overlay, hide island. */
    fun onIslandTapped() {
        stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
        // Set nav context BEFORE showing capsule so first render has correct buttons
        capsuleManager.updateNavContext(
            CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
        )
        showCapsuleOverlay()
        hideIsland()
    }

    /** Called when VD viewer activity becomes visible. */
    fun onViewerOpened() {
        stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
        // Set nav context BEFORE showing capsule so first render has correct buttons
        capsuleManager.updateNavContext(
            CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
        )
        showCapsuleOverlay()
        hideIsland()
    }

    /** Called when VD viewer activity becomes hidden. */
    fun onViewerClosed() {
        stateHolder.setContext(CapsuleContext.BACKGROUND)
        hideCapsuleOverlay()
        showIsland()
    }

    fun updateStatus(status: String) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus(status, glowStateColor(currentGlowState))
            }
            PlatformMode.ACCESSIBILITY -> {
                // Status updates don't change CapsuleMode, but may update thought text
                // if capsule is showing with placeholder thought
                maybeUpdatePlaceholderThought(status)
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
        capsuleManager.dispose()
        statusIslandManager?.dispose()
    }

    fun handleWindowStateChanged(packageName: String?, className: String?) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // In VD mode, window changes on the real screen are irrelevant.
            }
            PlatformMode.ACCESSIBILITY -> {
                handleWindowStateChangedA11y(packageName, className)
            }
        }
    }

    // ── Event handlers ──

    fun onTaskStarted(taskId: String, input: String) {
        hasActiveTask = true
        currentTaskInput = input
        currentGlowState = GlowState.Active

        stateHolder.onTaskStarted(taskId, input)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus(input.take(24), glowStateColor(GlowState.Active))
                statusIslandManager?.show()
            }
            PlatformMode.ACCESSIBILITY -> {
                Log.i(logTag, "Task started: $taskId, input: $input, isAppInForeground=$isAppInForeground")
                if (!isAppInForeground) {
                    edgeGlowManager.show(GlowState.Active)
                    pushModeToOverlayCapsule()
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
                maybeUpdatePlaceholderThought(delta.replace("\n", " ").trim())
            }
        }
    }

    fun onTurnPhaseChanged(phase: TurnPhase) {
        currentGlowState = when (phase) {
            TurnPhase.EXECUTION -> GlowState.Executing
            TurnPhase.PLANNING, TurnPhase.PERCEPTION -> GlowState.Active
        }

        stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)

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
                            stateHolder.onTaskStarted("action-fallback", input)
                            pushModeToOverlayCapsule()
                        }
                    }
                }
            }
        }
    }

    fun onTaskCompleted(reason: CompletionReason) {
        hasActiveTask = false
        currentTaskInput = null
        currentGlowState = GlowState.Success

        stateHolder.onTaskCompleted(reason)

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
                pushModeToOverlayCapsule()
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

        stateHolder.onError(message)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.showError(message.take(24))
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Error)
                pushModeToOverlayCapsule()
            }
        }
    }

    fun onThoughtUpdate(thought: String) {
        stateHolder.onThoughtUpdate(thought)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // If capsule is showing (from ask_user), hide it — interaction is done
                if (capsuleManager.isShowing()) capsuleManager.hide()
                statusIslandManager?.updateStatus(thought.take(24), glowStateColor(GlowState.Active))
            }
            PlatformMode.ACCESSIBILITY -> {
                pushModeToOverlayCapsule()
            }
        }
    }

    fun onSessionTakeover() {
        currentGlowState = GlowState.Paused

        stateHolder.onTakeoverConfirmed()

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updatePauseState(paused = true)
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Paused)
                pushModeToOverlayCapsule()
            }
        }
    }

    fun onSessionResumed() {
        currentGlowState = GlowState.Active

        stateHolder.onResumed()

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                if (capsuleManager.isShowing()) capsuleManager.hide()
                statusIslandManager?.updatePauseState(paused = false)
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(GlowState.Active)
                pushModeToOverlayCapsule()
            }
        }
    }

    fun onSupplementReceived(text: String) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.updateStatus("已收到: ${text.take(16)}", glowStateColor(currentGlowState))
            }
            PlatformMode.ACCESSIBILITY -> {
                capsuleManager.flashSupplementConfirmation(stateHolder.isAgentMidTurn.value)
            }
        }
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        stateHolder.onAskUser(type, message, callId)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // Show SmartCapsule overlay for ask_user (user needs input UI)
                pushModeToOverlayCapsule()
                statusIslandManager?.updateStatus("❓ ${message.take(20)}", glowStateColor(GlowState.Paused))
            }
            PlatformMode.ACCESSIBILITY -> {
                pushModeToOverlayCapsule()
            }
        }
    }

    // ── Private: Push state to overlay capsule ──

    /**
     * Push current CapsuleStateHolder mode to SmartCapsuleManager for rendering.
     * This is the bridge between the state holder and the View-based renderer.
     */
    private fun pushModeToOverlayCapsule() {
        capsuleManager.renderMode(stateHolder.mode.value, stateHolder.previousMode)
    }

    /**
     * Update thought text only if capsule is in Running mode with placeholder thought.
     * Used for status updates and message deltas that arrive before ThoughtUpdate events.
     */
    private fun maybeUpdatePlaceholderThought(text: String) {
        val current = stateHolder.mode.value as? CapsuleMode.Running ?: return
        if (current.thought != "思考中...") return
        val cleaned = text.replace(Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]"), "").trim()
        val display = cleaned.take(40).takeIf { it.isNotEmpty() } ?: return
        stateHolder.onThoughtUpdate(display)
        pushModeToOverlayCapsule()
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
                    stateHolder.setContext(CapsuleContext.MAIN_APP)
                    capsuleManager.hide()
                    edgeGlowManager.hideImmediately()
                } else {
                    Log.d(logTag, "Our app went to background with active task, showing capsule and glow")
                    stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
                    edgeGlowManager.show(currentGlowState)
                    capsuleManager.show()
                    capsuleManager.updateNavContext(
                        CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
                    )
                    pushModeToOverlayCapsule()
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
