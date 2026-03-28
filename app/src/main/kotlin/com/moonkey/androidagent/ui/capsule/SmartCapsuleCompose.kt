package com.moonkey.androidagent.ui.capsule

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moonkey.androidagent.protocol.ApprovalDecision
import com.moonkey.androidagent.protocol.ApprovalScope
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.capsule.surface.SmartCapsuleSurface
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode

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
    modifier: Modifier = Modifier
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
    )
}
