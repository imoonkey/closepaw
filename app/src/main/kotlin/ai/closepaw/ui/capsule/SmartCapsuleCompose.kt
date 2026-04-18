package ai.closepaw.ui.capsule

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.surface.SmartCapsuleSurface
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode

/** Navigation action for capsule context switching. */
enum class NavAction {
    MINIMIZE,
    OPEN_APP,
    OPEN_VIEWER
}

/**
 * Main-app wrapper for [SmartCapsuleSurface].
 *
 * Rendering is shared with overlay capsule host so both paths stay visually identical.
 */
@Composable
fun SmartCapsuleCompose(
    mode: CapsuleMode,
    isStopPending: Boolean,
    platformMode: PlatformMode,
    context: CapsuleContext,
    onSend: (String) -> Unit,
    onSupplement: (String) -> Unit,
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onUserResponse: (String, String) -> Unit,
    onApprovalResponse: (String, ApprovalDecision, ApprovalScope, String?) -> Unit = { _, _, _, _ -> },
    onDismissError: () -> Unit,
    onNavigate: (NavAction) -> Unit,
    modifier: Modifier = Modifier,
    previousMode: CapsuleMode? = null,
    pendingInputText: String = "",
    onPendingInputConsumed: () -> Unit = {},
    startupError: String? = null,
    onDismissStartupError: () -> Unit = {},
    onStartupErrorClick: (() -> Unit)? = null,
) {
    SmartCapsuleSurface(
        mode = mode,
        isStopPending = isStopPending,
        platformMode = platformMode,
        context = context,
        onSend = onSend,
        onSupplement = onSupplement,
        onTakeover = onTakeover,
        onResume = onResume,
        onStop = onStop,
        onUserResponse = onUserResponse,
        onApprovalResponse = onApprovalResponse,
        onDismissError = onDismissError,
        onNavigate = onNavigate,
        modifier = modifier,
        previousMode = previousMode,
        pendingInputText = pendingInputText,
        onPendingInputConsumed = onPendingInputConsumed,
        startupError = startupError,
        onDismissStartupError = onDismissStartupError,
        onStartupErrorClick = onStartupErrorClick,
    )
}
