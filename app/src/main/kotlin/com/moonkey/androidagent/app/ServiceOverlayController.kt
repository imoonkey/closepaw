package com.moonkey.androidagent.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.moonkey.androidagent.protocol.AskUserType
import com.moonkey.androidagent.protocol.CompletionReason
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.protocol.TurnPhase
import com.moonkey.androidagent.ui.overlay.CapsuleStateHolder
import com.moonkey.androidagent.ui.overlay.compose.CapsuleOverlayHost
import com.moonkey.androidagent.ui.overlay.compose.GlowOverlayHost
import com.moonkey.androidagent.ui.overlay.compose.IslandOverlayHost
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.platform.OverlayTouchGate
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
 *   C. In overlay context + active task, ShowPreference decides capsule vs island.
 *   D. WaitingFor* / Error force capsule visibility regardless preference.
 */
class ServiceOverlayController(
    context: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
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
    private val statusIslandManager: IslandOverlayHost? = null
) {
    // ── Unified state (single source of truth) ──

    val stateHolder = CapsuleStateHolder(scope)

    /** Touch gate for gesture injection pass-through. */
    val overlayTouchGate: OverlayTouchGate
        get() = capsuleManager.touchGate

    // ── Overlay managers ──

    private val edgeGlowManager = GlowOverlayHost(
        service = context,
        scope = scope,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
    )
    private val capsuleManager = CapsuleOverlayHost(
        service = context,
        stateHolder = stateHolder,
        scope = scope,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
    ).apply {
        this.onStop = {
            if (stateHolder.onStopRequested()) {
                this@ServiceOverlayController.onStop()
            }
        }
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
        this.onOpenApp = { openMainAppAndHideOverlays() }
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
    private var userLocation = OverlayUserLocation.MAIN_APP

    /** User preference: capsule or island while overlays are visible (A11y/VD). */
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
        val decision = deriveOverlayVisibility(
            platformMode = platformMode,
            location = userLocation,
            mode = mode,
            hasActiveTask = stateHolder.hasActiveTask,
            showPreference = showPreference,
        )
        val lockInteraction = shouldLockUserInteraction(
            platformMode = platformMode,
            location = userLocation,
            mode = mode,
        )
        showPreference = decision.normalizedShowPreference
        capsuleManager.setInteractionLocked(lockInteraction)

        if (decision.showCapsule) {
            if (!capsuleManager.isShowing()) capsuleManager.show()
        } else {
            capsuleManager.hide()
        }

        if (decision.showIsland) {
            if (statusIslandManager?.isShowing() != true) statusIslandManager?.show()
        } else {
            statusIslandManager?.hide()
        }

        if (decision.showGlow) {
            if (!edgeGlowManager.isShowing()) {
                edgeGlowManager.show(stateHolder.derivedGlowState)
            }
        } else {
            edgeGlowManager.hideImmediately()
        }
    }

    // ── Public: viewer lifecycle ──

    fun onIslandTapped() {
        val mode = stateHolder.mode.value
        if (shouldOpenAppWhenIslandTapped(stateHolder.hasActiveTask, mode)) {
            openMainAppAndHideOverlays()
            return
        }
        when (platformMode) {
            PlatformMode.ACCESSIBILITY -> {
                showPreference = ShowPreference.CAPSULE
                applyVisibility()
            }
            PlatformMode.VIRTUAL_DISPLAY -> {
                if (userLocation == OverlayUserLocation.VD_VIEWER) {
                    showPreference = ShowPreference.CAPSULE
                    applyVisibility()
                } else {
                    // Open VD viewer — onViewerOpened() handles capsule + island swap
                    onOpenViewer?.invoke() ?: openMainAppAndHideOverlays()
                }
            }
        }
    }

    fun onViewerOpened() {
        userLocation = OverlayUserLocation.VD_VIEWER
        showPreference = ShowPreference.CAPSULE
        updateContext()
        applyVisibility()
    }

    fun onViewerClosed() {
        if (userLocation == OverlayUserLocation.VD_VIEWER) {
            userLocation = OverlayUserLocation.OTHER_APP
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

    fun handleWindowStateChanged(packageName: String?, className: String?, displayId: Int?) {
        handleWindowStateChangedInternal(packageName, className, displayId)
    }

    /**
     * MainActivity foreground callback.
     *
     * Accessibility window events can be delayed/missed on some devices. This explicit signal
     * guarantees MAIN_APP invariants: no system capsule/island/glow on top of in-app Compose UI.
     */
    fun onMainAppVisible() {
        if (userLocation != OverlayUserLocation.MAIN_APP) {
            userLocation = OverlayUserLocation.MAIN_APP
            updateContext()
        }
        applyVisibility()
    }

    // ── Event handlers ──

    fun onTaskStarted(taskId: String, input: String) {
        stateHolder.onTaskStarted(taskId, input)
        showPreference = ShowPreference.CAPSULE
        applyVisibility()
    }

    fun onTurnPhaseChanged(phase: TurnPhase) {
        stateHolder.setTurnPhase(phase)
        stateHolder.setAgentMidTurn(phase == TurnPhase.EXECUTION || phase == TurnPhase.PLANNING)
        refreshGlowState()
    }

    fun onActionExecuted(toolName: String, success: Boolean) {
        refreshGlowState()
        // applyVisibility not needed: active task state hasn't changed
    }

    fun onTaskCompleted(reason: CompletionReason, message: String?) {
        stateHolder.onTaskCompleted(reason, message)
        refreshGlowState()
        // applyVisibility triggered by mode observer (Done/Error)
    }

    fun onSessionCompleted(reason: CompletionReason) {
        stateHolder.onSessionEnded(reason)
        refreshGlowState()
        applyVisibility()
    }

    fun onSessionError(message: String) {
        stateHolder.onError(message)
        refreshGlowState()
        showPreference = ShowPreference.CAPSULE
        applyVisibility()
    }

    fun onThoughtUpdate(thought: String) {
        stateHolder.onThoughtUpdate(thought)
        // Capsule and island auto-render via observer
    }

    fun onSessionTakeover() {
        stateHolder.onTakeoverConfirmed()
        refreshGlowState()
        applyVisibility()
    }

    fun onSessionResumed() {
        stateHolder.onResumed()
        refreshGlowState()
        applyVisibility()
    }

    fun onSupplementReceived(@Suppress("UNUSED_PARAMETER") text: String) {
        if (capsuleManager.isShowing()) {
            capsuleManager.flashSupplementConfirmation(stateHolder.isAgentMidTurn.value)
        }
    }

    fun onAskUser(type: AskUserType, message: String, callId: String) {
        stateHolder.onAskUser(type, message, callId)
        showPreference = ShowPreference.CAPSULE
        applyVisibility()
    }

    // ── Private: window tracking (shared between A11y and VD) ──

    private fun handleWindowStateChangedInternal(
        packageName: String?,
        className: String?,
        displayId: Int?,
    ) {
        val nextLocation = resolveUserLocation(
            appPackage = appPackage,
            packageName = packageName,
            className = className,
            displayId = displayId,
        ) ?: return

        if (nextLocation != userLocation) {
            Log.d(
                logTag,
                "Window changed: pkg=$packageName, class=$className, displayId=$displayId, " +
                    "from=$userLocation, to=$nextLocation, hasActiveTask=${stateHolder.hasActiveTask}"
            )
            userLocation = nextLocation
            updateContext()
            applyVisibility()
        }
    }

    private fun updateContext() {
        val ctx = resolveCapsuleContext(platformMode, userLocation)
        stateHolder.setContext(ctx)
        capsuleManager.updateNavContext(
            ctx,
            platformMode,
            hasIsland = statusIslandManager != null
        )
    }

    private fun refreshGlowState() {
        if (!edgeGlowManager.isShowing()) return
        edgeGlowManager.updateState(stateHolder.derivedGlowState)
    }

    private fun openMainAppAndHideOverlays() {
        onMainAppVisible()
        onOpenApp()
    }
}
