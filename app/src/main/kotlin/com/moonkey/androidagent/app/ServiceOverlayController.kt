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
import kotlinx.coroutines.CoroutineScope

/**
 * ServiceOverlayController — Thin coordinator between state and overlay windows.
 *
 * Single source of truth: [CapsuleStateHolder].
 * Shadow state: NONE. All state is derived from stateHolder.
 *
 * In ACCESSIBILITY mode: drives EdgeGlowManager + SmartCapsuleManager.
 * In VIRTUAL_DISPLAY mode: drives StatusIslandManager (+ capsule overlay for ask_user).
 *
 * SmartCapsuleManager observes [CapsuleStateHolder.mode] via StateFlow — auto-renders.
 * This controller only decides WHEN to show/hide overlay windows.
 * It does NOT push rendering updates (no pushModeToOverlayCapsule).
 */
class ServiceOverlayController(
    context: AccessibilityService,
    private val scope: CoroutineScope,
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
    // ── Unified state (single source of truth) ──

    val stateHolder = CapsuleStateHolder(scope)

    // ── Overlay managers ──

    private val edgeGlowManager = EdgeGlowManager(context)
    private val capsuleManager = SmartCapsuleManager(
        service = context,
        stateHolder = stateHolder,
        scope = scope,
    ).apply {
        this.onStop = this@ServiceOverlayController.onStop
        this.onTakeover = {
            this@ServiceOverlayController.onTakeover()
        }
        this.onResume = this@ServiceOverlayController.onResume
        this.onSupplement = { text -> this@ServiceOverlayController.onSupplement(text) }
        this.onUserResponse = { callId, response ->
            this@ServiceOverlayController.onUserResponse(callId, response)
        }
        this.onOpenApp = this@ServiceOverlayController.onOpenApp
        this.onDismissError = {
            stateHolder.onDismissError()
        }
        // Navigation callbacks
        this.onMinimize = {
            hideCapsuleOverlay()
            showIsland()
        }
        this.onOpenViewer = { this@ServiceOverlayController.onOpenViewer?.invoke() }
    }

    // ── Window-level state (NOT capsule state) ──

    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    private var isAppInForeground = true
    private var lastKnownForegroundPackage: String? = null

    init {
        statusIslandManager?.startObserving(stateHolder, scope)
    }

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
        // No need to push mode — manager observes StateFlow
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
        capsuleManager.updateNavContext(
            CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
        )
        showCapsuleOverlay()
        hideIsland()
    }

    /** Called when VD viewer activity becomes visible. */
    fun onViewerOpened() {
        stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
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
                // No-op: StatusIsland observes state holder reactively.
            }
            PlatformMode.ACCESSIBILITY -> {
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
        stateHolder.onTaskStarted(taskId, input)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                statusIslandManager?.show()
            }
            PlatformMode.ACCESSIBILITY -> {
                Log.i(logTag, "Task started: $taskId, input: $input, isAppInForeground=$isAppInForeground")
                if (!isAppInForeground) {
                    edgeGlowManager.show(stateHolder.derivedGlowState)
                    capsuleManager.show()
                    // Manager auto-renders via observer — no push needed
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
        stateHolder.setTurnPhase(phase)
        stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
            }
        }
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
                if (stateHolder.hasActiveTask && !isAppInForeground) {
                    if (!edgeGlowManager.isShowing()) {
                        edgeGlowManager.show(stateHolder.derivedGlowState)
                    }
                    if (!capsuleManager.isShowing()) {
                        capsuleManager.show()
                        // Manager auto-renders via observer
                    }
                }
            }
        }
    }

    fun onTaskCompleted(reason: CompletionReason, message: String?) {
        stateHolder.onTaskCompleted(reason, message)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
                // Manager auto-renders via observer
            }
        }
    }

    fun onSessionCompleted(reason: CompletionReason) {
        stateHolder.onSessionEnded(reason)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                if (!stateHolder.hasActiveTask || isAppInForeground) {
                    edgeGlowManager.hide()
                } else {
                    edgeGlowManager.updateState(stateHolder.derivedGlowState)
                }
            }
        }
    }

    fun onSessionError(message: String) {
        stateHolder.onError(message)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
                // Manager auto-renders via observer
            }
        }
    }

    fun onThoughtUpdate(thought: String) {
        stateHolder.onThoughtUpdate(thought)

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                // Manager auto-renders via observer
            }
        }
    }

    fun onSessionTakeover() {
        stateHolder.onTakeoverConfirmed()

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
                // Manager auto-renders via observer
            }
        }
    }

    fun onSessionResumed() {
        stateHolder.onResumed()

        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
            }
            PlatformMode.ACCESSIBILITY -> {
                edgeGlowManager.updateState(stateHolder.derivedGlowState)
                // Manager auto-renders via observer
            }
        }
    }

    fun onSupplementReceived(text: String) {
        when (platformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> {
                // No-op: island updates via StateFlow observer.
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
                capsuleManager.show()
                // Manager auto-renders via observer
            }
            PlatformMode.ACCESSIBILITY -> {
                // Manager auto-renders via observer
            }
        }
    }

    // ── Private: thought update ──

    /**
     * Update thought text only if capsule is in Running mode with placeholder thought.
     * Used for status updates and message deltas that arrive before ThoughtUpdate events.
     */
    private fun maybeUpdatePlaceholderThought(text: String) {
        val current = stateHolder.mode.value as? CapsuleMode.Running ?: return
        if (current.thought != "Thinking...") return
        val cleaned = text.replace(Regex("[🚀👀🧠💡✅⏸️❌⚠️✓]"), "").trim()
        val display = cleaned.take(40).takeIf { it.isNotEmpty() } ?: return
        stateHolder.onThoughtUpdate(display)
        // Manager auto-renders via observer — no push needed
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
                    "wasInForeground=$wasInForeground, isInForeground=$isAppInForeground, " +
                    "hasActiveTask=${stateHolder.hasActiveTask}"
            )

            if (wasInForeground != isAppInForeground && stateHolder.hasActiveTask) {
                if (isAppInForeground) {
                    Log.d(logTag, "Our app in foreground, hiding capsule and glow")
                    stateHolder.setContext(CapsuleContext.MAIN_APP)
                    capsuleManager.hide()
                    edgeGlowManager.hideImmediately()
                } else {
                    Log.d(logTag, "Our app went to background with active task, showing capsule and glow")
                    stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
                    edgeGlowManager.show(stateHolder.derivedGlowState)
                    capsuleManager.updateNavContext(
                        CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
                    )
                    capsuleManager.show()
                    // Manager auto-renders via observer
                }
            }
        }
    }
}
