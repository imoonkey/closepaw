package ai.closepaw.ui.capsule

import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Capsule-side bridge between the agent runtime and a UI host (e.g. `ChatScreen`).
 *
 * Lifts the runtime dependency out of the composable layer: callers (an activity)
 * decide where the flows come from — typically `AgentService.overlayController.stateHolder`
 * — and the UI just reads them.
 *
 * Use [InertCapsuleBinding] when the runtime isn't bound yet so the UI can still
 * render its idle state (input bar with placeholder) instead of crashing.
 */
data class CapsuleBinding(
    val mode: StateFlow<CapsuleMode>,
    val platformMode: StateFlow<PlatformMode>,
    val isStopPending: StateFlow<Boolean>,
    val previousMode: () -> CapsuleMode?,
    val onStopRequested: () -> Boolean,
    val onApprovalResolved: (String) -> Boolean,
)

/**
 * No-op binding used when the agent runtime is unbound.
 *
 * `mode = Hidden`, `platformMode = ACCESSIBILITY`, `isStopPending = false`,
 * `previousMode = null`, and the two callbacks return `true` so callers fall
 * back to their default branch (e.g. ViewModel-side stop / approval forwarding).
 */
val InertCapsuleBinding: CapsuleBinding = CapsuleBinding(
    mode = MutableStateFlow(CapsuleMode.Hidden),
    platformMode = MutableStateFlow(PlatformMode.ACCESSIBILITY),
    isStopPending = MutableStateFlow(false),
    previousMode = { null },
    onStopRequested = { true },
    onApprovalResolved = { true },
)
