package ai.closepaw.qa

import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.capsule.surface.SmartCapsuleSurface
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.Composable

@Composable
fun TestCapsule(
    mode: CapsuleMode,
    isStopPending: Boolean = false,
    platformMode: PlatformMode = PlatformMode.ACCESSIBILITY,
    context: CapsuleContext = CapsuleContext.MAIN_APP,
    hasIsland: Boolean = true,
    previousMode: CapsuleMode? = null,
    onSend: (String) -> Unit = {},
    onSupplement: (String) -> Unit = {},
    onTakeover: () -> Unit = {},
    onResume: () -> Unit = {},
    onSupplementAndResume: (String) -> Unit = { text ->
        onSupplement(text)
        onResume()
    },
    onStop: () -> Unit = {},
    onUserResponse: (String, String) -> Unit = { _, _ -> },
    onApprovalResponse: (String, ApprovalDecision, ApprovalScope, String) -> Unit = { _, _, _, _ -> },
    onDismissError: () -> Unit = {},
    onNavigate: (NavAction) -> Unit = {},
) {
    ClosePawTheme {
        SmartCapsuleSurface(
            mode = mode,
            isStopPending = isStopPending,
            platformMode = platformMode,
            context = context,
            onSend = onSend,
            onSupplement = onSupplement,
            onTakeover = onTakeover,
            onResume = onResume,
            onSupplementAndResume = onSupplementAndResume,
            onStop = onStop,
            onUserResponse = onUserResponse,
            onApprovalResponse = onApprovalResponse,
            onDismissError = onDismissError,
            onNavigate = onNavigate,
            hasIsland = hasIsland,
            previousMode = previousMode,
        )
    }
}
