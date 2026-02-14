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
import kotlinx.coroutines.launch

/**
 * ServiceOverlayController — Coordinator between state and overlay windows.
 *
 * Single source of truth: [CapsuleStateHolder].
 *
 * Window visibility is determined by ONE function: [applyVisibility].
 * It reads (platformMode, userLocation, mode, showPreference)
 * and sets exactly which overlay windows are visible.
 *
 * Invariants enforced by applyVisibility:
 *   A. Capsule and Island are never simultaneously visible.
 *   B. In MAIN_APP, no system overlays — Compose capsule handles it.
 *   C. In A11y mode, island is never shown.
 *   D. In VD + background + active task, ShowPreference decides capsule vs island.
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
            stateHolder.onTakeoverRequested()   // immediate visual feedback
            this@ServiceOverlayController.onTakeover()
        }
        this.onResume = this@ServiceOverlayController.onResume
        this.onSupplement = { text -> this@ServiceOverlayController.onSupplement(text) }
        this.onUserResponse = { callId, response ->
            if (stateHolder.onUserResponseSent(callId)) {
                this@ServiceOverlayController.onUserResponse(callId, response)
            }
        }
        this.onOpenApp = this@ServiceOverlayController.onOpenApp
        this.onDismissError = {
            stateHolder.onDismissError()
        }
        // Navigation callbacks
        this.onMinimize = {
            showPreference = ShowPreference.ISLAND
            applyVisibility()
        }
        this.onOpenViewer = { this@ServiceOverlayController.onOpenViewer?.invoke() }
    }

    // ── Window-level state ──

    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    private var userLocation = UserLocation.MAIN_APP

    private enum class UserLocation {
        MAIN_APP,
        VD_VIEWER,
        OTHER_APP
    }

    /** User preference: capsule or island in VD background. */
    enum class ShowPreference { CAPSULE, ISLAND }
    private var showPreference = ShowPreference.ISLAND

    init {
        statusIslandManager?.startObserving(stateHolder, scope)

        // Observe mode for terminal transitions (Done→Hidden auto-hide) that affect visibility
        scope.launch {
            stateHolder.mode.collect { mode ->
                if (mode is CapsuleMode.Hidden || mode is CapsuleMode.Done || mode is CapsuleMode.Error) {
                    applyVisibility()
                }
            }
        }
    }

    /** Set platform mode. Call before any events are dispatched. */
    fun setPlatformMode(mode: PlatformMode) {
        platformMode = mode
        stateHolder.setPlatformMode(mode)
        updateContext()
        applyVisibility()
    }

    // ── Visibility system (single authority) ──

    /**
     * The ONE function that decides which overlay windows are visible.
     * Called after every state change and context change.
     */
    private fun applyVisibility() {
        val mode = stateHolder.mode.value
        val isActive = stateHolder.hasActiveTask
            || mode is CapsuleMode.Done
            || mode is CapsuleMode.Error
        val isMainApp = userLocation == UserLocation.MAIN_APP

        when (platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                // A11y: no island ever. Capsule + glow only when not in our app and task active.
                if (isMainApp || !isActive) {
                    capsuleManager.hide()
                    edgeGlowManager.hideImmediately()
                } else {
                    if (!capsuleManager.isShowing()) capsuleManager.show()
                    if (!edgeGlowManager.isShowing()) {
                        edgeGlowManager.show(stateHolder.derivedGlowState)
                    }
                }
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                edgeGlowManager.hideImmediately()

                if (isMainApp || !isActive) {
                    // In our app or no task: Compose capsule handles everything.
                    capsuleManager.hide()
                    statusIslandManager?.hide()
                } else {
                    if ((mode is CapsuleMode.WaitingForInput ||
                            mode is CapsuleMode.WaitingForAction ||
                            mode is CapsuleMode.Error) &&
                        showPreference == ShowPreference.ISLAND
                    ) {
                        showPreference = ShowPreference.CAPSULE
                    }

                    // Background or viewer: show one of capsule/island per preference.
                    when (showPreference) {
                        ShowPreference.CAPSULE -> {
                            if (!capsuleManager.isShowing()) capsuleManager.show()
                            statusIslandManager?.hide()
                        }
                        ShowPreference.ISLAND -> {
                            capsuleManager.hide()
                            if (statusIslandManager?.isShowing() != true) {
                                statusIslandManager?.show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Public: viewer lifecycle ──

    fun onIslandTapped() {
        val mode = stateHolder.mode.value
        if (!stateHolder.hasActiveTask && mode !is CapsuleMode.Done && mode !is CapsuleMode.Error) {
            onOpenApp()
            return
        }
        when (platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                // Defensive no-op: A11y should never show island.
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                if (userLocation == UserLocation.VD_VIEWER) {
                    showPreference = ShowPreference.CAPSULE
                    applyVisibility()
                } else {
                    // Open VD viewer — onViewerOpened() handles capsule + island swap
                    onOpenViewer?.invoke() ?: onOpenApp()
                }
            }
        }
    }

    fun onViewerOpened() {
        userLocation = UserLocation.VD_VIEWER
        showPreference = ShowPreference.CAPSULE
        updateContext()
        applyVisibility()
    }

    fun onViewerClosed() {
        if (userLocation == UserLocation.VD_VIEWER) {
            userLocation = UserLocation.OTHER_APP
        }
        showPreference = ShowPreference.ISLAND
        updateContext()
        applyVisibility()
    }

    fun hideAll() {
        capsuleManager.hide()
        edgeGlowManager.hideImmediately()
        statusIslandManager?.hide()
    }

    fun dispose() {
        edgeGlowManager.dispose()
        capsuleManager.dispose()
        statusIslandManager?.dispose()
    }

    fun handleWindowStateChanged(packageName: String?, className: String?) {
        handleWindowStateChangedInternal(packageName, className)
    }

    // ── Event handlers ──

    fun onTaskStarted(taskId: String, input: String) {
        stateHolder.onTaskStarted(taskId, input)
        applyVisibility()
    }

    fun onTurnPhaseChanged(phase: TurnPhase) {
        stateHolder.setTurnPhase(phase)
        stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
        // applyVisibility not needed: active task state hasn't changed
    }

    fun onTaskCompleted(reason: CompletionReason, message: String?) {
        stateHolder.onTaskCompleted(reason, message)
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
        // applyVisibility triggered by mode observer (Done/Error)
    }

    fun onSessionCompleted(reason: CompletionReason) {
        stateHolder.onSessionEnded(reason)
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
        applyVisibility()
    }

    fun onSessionError(message: String) {
        stateHolder.onError(message)
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
        if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
            showPreference = ShowPreference.CAPSULE
        }
        applyVisibility()
    }

    fun onThoughtUpdate(thought: String) {
        stateHolder.onThoughtUpdate(thought)
        // Capsule and island auto-render via observer
    }

    fun onSessionTakeover() {
        stateHolder.onTakeoverConfirmed()
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
    }

    fun onSessionResumed() {
        stateHolder.onResumed()
        if (platformMode == PlatformMode.ACCESSIBILITY) {
            edgeGlowManager.updateState(stateHolder.derivedGlowState)
        }
    }

    fun onSupplementReceived(@Suppress("UNUSED_PARAMETER") text: String) {
        if (capsuleManager.isShowing()) {
            capsuleManager.flashSupplementConfirmation(stateHolder.isAgentMidTurn.value)
        }
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        stateHolder.onAskUser(type, message, callId)
        // In VD mode, WaitingFor* needs capsule shown for user input
        if (platformMode == PlatformMode.VIRTUAL_DISPLAY) {
            showPreference = ShowPreference.CAPSULE
            applyVisibility()
        }
    }

    // ── Private: window tracking (shared between A11y and VD) ──

    private fun handleWindowStateChangedInternal(packageName: String?, className: String?) {
        val normalizedClassName = className?.substringBefore('$')
        val isActivityWindow = normalizedClassName != null &&
            (normalizedClassName.endsWith("Activity") ||
                normalizedClassName.contains("Activity") ||
                normalizedClassName.contains("Launcher") ||
                normalizedClassName.contains(".app.") ||
                normalizedClassName.contains("Home"))

        if (!isActivityWindow) return

        val nextLocation = when {
            packageName == null -> return
            packageName != appPackage -> UserLocation.OTHER_APP
            isViewerActivityClass(normalizedClassName) -> UserLocation.VD_VIEWER
            else -> UserLocation.MAIN_APP
        }

        if (nextLocation != userLocation) {
            Log.d(
                logTag,
                "Window changed: pkg=$packageName, class=$normalizedClassName, " +
                    "from=$userLocation, to=$nextLocation, hasActiveTask=${stateHolder.hasActiveTask}"
            )
            userLocation = nextLocation
            updateContext()
            applyVisibility()
        }
    }

    private fun isViewerActivityClass(className: String?): Boolean {
        val name = className ?: return false
        return name.contains("VirtualDisplayViewerActivity")
    }

    private fun updateContext() {
        val ctx = when (platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                if (userLocation == UserLocation.MAIN_APP) CapsuleContext.MAIN_APP
                else CapsuleContext.SCREEN_VIEWING
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                when (userLocation) {
                    UserLocation.MAIN_APP -> CapsuleContext.MAIN_APP
                    UserLocation.VD_VIEWER -> CapsuleContext.SCREEN_VIEWING
                    UserLocation.OTHER_APP -> CapsuleContext.BACKGROUND
                }
            }
        }
        stateHolder.setContext(ctx)
        capsuleManager.updateNavContext(
            ctx,
            platformMode,
            hasIsland = statusIslandManager != null
        )
    }
}
