package ai.closepaw.ui.capsule.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.overlay.model.NavSpec

/**
 * CapsuleControlBar — the row beneath the status line / detail body.
 *
 * Two clusters share one `Row(SpaceBetween)`:
 *  - [ActionButtonCluster] (left): mode-driven action buttons (Takeover, Resume, Done,
 *    Allow / Session / Always / Deny, Stop, Close).
 *  - [NavButtonCluster] (right): nav icons (Minimize, OpenApp, OpenViewer) gated by [NavSpec].
 *
 * Both clusters hide together when mode is `Done`; that gate is enforced by
 * [SmartCapsuleSurface] (skips the entire bar) and by [NavSpec.from] (zeroes
 * every nav flag).
 */
@Composable
internal fun CapsuleControlBar(
    spec: CapsuleRenderSpec,
    navSpec: NavSpec,
    mode: CapsuleMode,
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDone: (String) -> Unit,
    onApprovalResponse: (String, ApprovalDecision, ApprovalScope, String?) -> Unit,
    onDismissError: () -> Unit,
    onNavigate: (NavAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButtonCluster(
            buttons = spec.buttons,
            mode = mode,
            onTakeover = onTakeover,
            onResume = onResume,
            onStop = onStop,
            onDone = onDone,
            onApprovalResponse = onApprovalResponse,
            onDismissError = onDismissError,
        )
        NavButtonCluster(navSpec = navSpec, onNavigate = onNavigate)
    }
}

@Composable
private fun ActionButtonCluster(
    buttons: CapsuleRenderSpec.ButtonsSpec,
    mode: CapsuleMode,
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDone: (String) -> Unit,
    onApprovalResponse: (String, ApprovalDecision, ApprovalScope, String?) -> Unit,
    onDismissError: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.primary?.let { btn ->
            FilledTonalButton(
                onClick = {
                    when (mode) {
                        is CapsuleMode.Running -> onTakeover()
                        is CapsuleMode.Takeover -> onResume()
                        is CapsuleMode.WaitingForAction -> onDone(mode.callId)
                        is CapsuleMode.WaitingForApproval -> onApprovalResponse(
                            mode.callId, ApprovalDecision.APPROVED, ApprovalScope.ONCE, mode.packageName,
                        )
                        else -> {}
                    }
                },
                enabled = btn.enabled,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = primaryIconForMode(mode),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = btn.text, fontSize = 14.sp)
            }
        }

        buttons.secondary?.let { btn ->
            if (mode is CapsuleMode.WaitingForApproval) {
                ApprovalScopeButton(btn, mode, ApprovalScope.SESSION, onApprovalResponse)
            }
        }

        buttons.tertiary?.let { btn ->
            if (mode is CapsuleMode.WaitingForApproval) {
                ApprovalScopeButton(btn, mode, ApprovalScope.ALWAYS, onApprovalResponse)
            }
        }

        buttons.stop?.let { btn ->
            OutlinedButton(
                onClick = {
                    when (mode) {
                        is CapsuleMode.Error -> onDismissError()
                        is CapsuleMode.WaitingForApproval -> onApprovalResponse(
                            mode.callId, ApprovalDecision.DENIED, ApprovalScope.ONCE, mode.packageName,
                        )
                        else -> onStop()
                    }
                },
                enabled = btn.enabled,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = stopIconForMode(mode),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = btn.text,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NavButtonCluster(
    navSpec: NavSpec,
    onNavigate: (NavAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (navSpec.showMinimize) {
            NavIconButton(
                icon = Icons.Rounded.RemoveCircleOutline,
                contentDescription = "Minimize",
                onClick = { onNavigate(NavAction.MINIMIZE) },
            )
        }
        if (navSpec.showApp) {
            NavIconButton(
                icon = Icons.Rounded.PhoneAndroid,
                contentDescription = "Open app",
                onClick = { onNavigate(NavAction.OPEN_APP) },
            )
        }
        if (navSpec.showWatch) {
            NavIconButton(
                icon = Icons.Rounded.Visibility,
                contentDescription = "Open viewer",
                onClick = { onNavigate(NavAction.OPEN_VIEWER) },
            )
        }
    }
}

@Composable
private fun NavIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

private fun primaryIconForMode(mode: CapsuleMode): ImageVector = when (mode) {
    is CapsuleMode.Running -> Icons.Rounded.PanTool
    is CapsuleMode.Takeover -> Icons.Rounded.PlayArrow
    is CapsuleMode.WaitingForAction -> Icons.Rounded.CheckCircle
    is CapsuleMode.WaitingForApproval -> Icons.Rounded.Check
    else -> Icons.Rounded.PanTool
}

private fun stopIconForMode(mode: CapsuleMode): ImageVector = when (mode) {
    is CapsuleMode.Error -> Icons.Rounded.Close
    is CapsuleMode.WaitingForApproval -> Icons.Rounded.Close
    else -> Icons.Rounded.StopCircle
}

@Composable
private fun ApprovalScopeButton(
    btn: CapsuleRenderSpec.ButtonSpec,
    mode: CapsuleMode.WaitingForApproval,
    scope: ApprovalScope,
    onApprovalResponse: (String, ApprovalDecision, ApprovalScope, String?) -> Unit,
) {
    FilledTonalButton(
        onClick = { onApprovalResponse(mode.callId, ApprovalDecision.APPROVED, scope, mode.packageName) },
        enabled = btn.enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = btn.text, fontSize = 14.sp)
    }
}
