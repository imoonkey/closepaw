package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.app.AgentService
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.capsule.surface.SmartCapsuleSurface
import ai.closepaw.ui.capsule.surface.VoiceMicDeps
import ai.closepaw.ui.capsule.surface.smartCapsuleHostPadding
import ai.closepaw.ui.capsule.voice.AndroidRecognizerFactory
import ai.closepaw.app.shouldCapsuleOverlayBeTouchable
import ai.closepaw.platform.OverlayTouchGate
import ai.closepaw.ui.overlay.CapsuleStateHolder
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CapsuleOverlayHost(
    private val service: AccessibilityService,
    private val stateHolder: CapsuleStateHolder,
    private val scope: CoroutineScope,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    companion object {
        private const val TAG = "CapsuleOverlayHost"
        private const val DEBOUNCE_MS = 300L
    }

    var onTakeover: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onSupplement: ((String) -> Unit)? = null
    var onSupplementAndResume: ((String) -> Unit)? = null
    var onUserResponse: ((String, String) -> Unit)? = null
    var onApprovalResponse: ((String, ApprovalDecision, ApprovalScope, String) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onDismissError: (() -> Unit)? = null
    var onMinimize: (() -> Unit)? = null
    var onOpenViewer: (() -> Unit)? = null
    var onSend: ((String) -> Unit)? = null

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private val transientThought = MutableStateFlow<String?>(null)
    private val inputFocused = MutableStateFlow(false)
    private val interactionLocked = MutableStateFlow(false)

    private var focusJob: Job? = null
    private var touchabilityJob: Job? = null
    private var transientThoughtJob: Job? = null
    private var lastButtonClickTime = 0L
    private var isFocusable = false
    private var passThroughDepth = 0

    /** Gate for [AccessibilityGestureInjector] to temporarily pass touches through. */
    val touchGate: OverlayTouchGate = object : OverlayTouchGate {
        override fun beginGesturePassThrough(): AutoCloseable {
            passThroughDepth++
            Log.d(TAG, "beginGesturePassThrough: depth=$passThroughDepth, isShowing=${composeHost.isShowing()}")
            applyNotTouchableFlag(forceNotTouchable = true)
            var closed = false
            return AutoCloseable {
                if (closed) return@AutoCloseable
                closed = true
                passThroughDepth = (passThroughDepth - 1).coerceAtLeast(0)
                Log.d(TAG, "endGesturePassThrough: depth=$passThroughDepth")
                if (passThroughDepth == 0) {
                    applyBaselineTouchability(stateHolder.mode.value)
                }
            }
        }
    }

    fun isShowing(): Boolean = composeHost.isShowing()

    fun show() {
        if (composeHost.isShowing()) return
        composeHost.show(createLayoutParams(interactionLocked.value)) {
            val mode by stateHolder.mode.collectAsState(initial = CapsuleMode.Hidden)
            val stopPending by stateHolder.isStopPending.collectAsState(initial = false)
            val ctx by stateHolder.context.collectAsState()
            val platform by stateHolder.platformMode.collectAsState()
            val islandEnabled by stateHolder.hasIsland.collectAsState()
            val flashThought by transientThought.collectAsState(initial = null)
            val lockTouches by interactionLocked.collectAsState(initial = false)

            val capsuleContent: @androidx.compose.runtime.Composable () -> Unit = {
                val voiceDeps = remember {
                    val appCtx = service.applicationContext
                    object : VoiceMicDeps {
                        override val factory = AndroidRecognizerFactory(appCtx)
                        override val activity: android.app.Activity? = null
                        override fun isPermissionGranted(): Boolean =
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                appCtx,
                                android.Manifest.permission.RECORD_AUDIO,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        override fun requestOverlayPermission() {
                            AgentService.instance?.requestVoicePermissionViaMainActivity()
                        }
                    }
                }
                SmartCapsuleSurface(
                    mode = mode,
                    previousMode = stateHolder.previousMode,
                    isStopPending = stopPending,
                    platformMode = platform,
                    context = ctx,
                    transientThought = flashThought,
                    onSend = { text -> debounced { onSend?.invoke(text) } },
                    onSupplement = { text -> debounced { onSupplement?.invoke(text) } },
                    onTakeover = { debounced { onTakeover?.invoke() } },
                    onResume = { debounced { onResume?.invoke() } },
                    onSupplementAndResume = { text ->
                        debounced {
                            val submit = onSupplementAndResume
                            if (submit != null) {
                                submit(text)
                            } else {
                                onSupplement?.invoke(text)
                                onResume?.invoke()
                            }
                        }
                    },
                    onStop = { debounced { onStop?.invoke() } },
                    onUserResponse = { callId, response ->
                        debounced { onUserResponse?.invoke(callId, response) }
                    },
                    onApprovalResponse = { callId, decision, scope, packageName ->
                        debounced { onApprovalResponse?.invoke(callId, decision, scope, packageName) }
                    },
                    onDismissError = { debounced { onDismissError?.invoke() } },
                    onNavigate = { action ->
                        debounced {
                            when (action) {
                                NavAction.MINIMIZE -> onMinimize?.invoke()
                                NavAction.OPEN_APP -> onOpenApp?.invoke()
                                NavAction.OPEN_VIEWER -> onOpenViewer?.invoke()
                            }
                        }
                    },
                    hasIsland = islandEnabled,
                    onStatusClick = if (platform != PlatformMode.ACCESSIBILITY) {
                        { debounced { onOpenApp?.invoke() } }
                    } else {
                        null
                    },
                    onInputFocusChanged = { focused -> inputFocused.value = focused },
                    onInputSubmitted = {
                        inputFocused.value = false
                        setOverlayFocusable(false)
                    },
                    autoFocusInput = mode is CapsuleMode.WaitingForInput,
                    voice = voiceDeps,
                )
            }

            if (lockTouches) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            View(ctx).apply {
                                setOnTouchListener { _, _ -> true }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .smartCapsuleHostPadding()
                    ) {
                        capsuleContent()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .smartCapsuleHostPadding()
                ) {
                    capsuleContent()
                }
            }
        }
        startFocusObserver()
        startTouchabilityObserver()
        Log.i(TAG, "Capsule overlay shown")
    }

    fun hide() {
        stopFocusObserver()
        stopTouchabilityObserver()
        inputFocused.value = false
        setOverlayFocusable(false)
        composeHost.hide()
    }

    fun dispose() {
        transientThoughtJob?.cancel()
        transientThoughtJob = null
        hide()
        composeHost.dispose()
    }

    fun setInteractionLocked(locked: Boolean) {
        if (interactionLocked.value == locked) return
        interactionLocked.value = locked
        if (!composeHost.isShowing()) return
        composeHost.updateLayoutParams { params ->
            params.height = if (locked) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            params.gravity = if (locked) {
                Gravity.TOP or Gravity.START
            } else {
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }
    }

    fun flashSupplementConfirmation(isAgentMidTurn: Boolean) {
        val message = if (isAgentMidTurn) {
            "✓ Received, will apply next step"
        } else {
            "✓ Received"
        }
        val durationMs = if (isAgentMidTurn) 2000L else 1500L
        transientThoughtJob?.cancel()
        transientThoughtJob = scope.launch {
            transientThought.value = message
            delay(durationMs)
            transientThought.value = null
        }
    }

    private fun startFocusObserver() {
        focusJob?.cancel()
        focusJob = scope.launch {
            combine(stateHolder.mode, inputFocused) { mode, focused ->
                when (mode) {
                    is CapsuleMode.WaitingForInput -> true
                    is CapsuleMode.Takeover -> focused
                    else -> false
                }
            }.collect { shouldBeFocusable ->
                setOverlayFocusable(shouldBeFocusable)
            }
        }
    }

    private fun stopFocusObserver() {
        focusJob?.cancel()
        focusJob = null
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        if (isFocusable == focusable || !composeHost.isShowing()) return
        isFocusable = focusable
        composeHost.updateLayoutParams { params ->
            params.flags = if (focusable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
        }
        if (!focusable) {
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(composeHost.getWindowToken(), 0)
        }
    }

    private fun createLayoutParams(locked: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (locked) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (locked) Gravity.TOP or Gravity.START
            else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

    private fun startTouchabilityObserver() {
        touchabilityJob?.cancel()
        touchabilityJob = scope.launch {
            stateHolder.mode.collect { mode ->
                if (passThroughDepth == 0) {
                    applyBaselineTouchability(mode)
                }
            }
        }
    }

    private fun stopTouchabilityObserver() {
        touchabilityJob?.cancel()
        touchabilityJob = null
        passThroughDepth = 0
    }

    private fun applyBaselineTouchability(mode: CapsuleMode) {
        val touchable = shouldCapsuleOverlayBeTouchable(mode)
        applyNotTouchableFlag(forceNotTouchable = !touchable)
    }

    private fun applyNotTouchableFlag(forceNotTouchable: Boolean) {
        if (!composeHost.isShowing()) return
        composeHost.updateLayoutParams { params ->
            params.flags = if (forceNotTouchable) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
        }
    }

    private fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastButtonClickTime < DEBOUNCE_MS) return
        lastButtonClickTime = now
        action()
    }
}
