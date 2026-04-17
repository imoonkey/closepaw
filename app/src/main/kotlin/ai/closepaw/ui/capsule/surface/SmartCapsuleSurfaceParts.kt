package ai.closepaw.ui.capsule.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.overlay.model.NavSpec

@Composable
internal fun CapsuleRow2(
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spec.buttons.primary?.let { btn ->
                FilledTonalButton(
                    onClick = {
                        when (mode) {
                            is CapsuleMode.Running -> onTakeover()
                            is CapsuleMode.Takeover -> onResume()
                            is CapsuleMode.WaitingForAction -> onDone(mode.callId)
                            is CapsuleMode.WaitingForApproval -> onApprovalResponse(
                                mode.callId, ApprovalDecision.APPROVED, ApprovalScope.ONCE, mode.packageName
                            )
                            else -> {}
                        }
                    },
                    enabled = btn.enabled,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = primaryIconForMode(mode),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = btn.text, fontSize = 14.sp)
                }
            }

            spec.buttons.secondary?.let { btn ->
                if (mode is CapsuleMode.WaitingForApproval) {
                    ApprovalScopeButton(btn, mode, ApprovalScope.SESSION, onApprovalResponse)
                }
            }

            spec.buttons.tertiary?.let { btn ->
                if (mode is CapsuleMode.WaitingForApproval) {
                    ApprovalScopeButton(btn, mode, ApprovalScope.ALWAYS, onApprovalResponse)
                }
            }

            spec.buttons.stop?.let { btn ->
                OutlinedButton(
                    onClick = {
                        when (mode) {
                            is CapsuleMode.Error -> onDismissError()
                            is CapsuleMode.WaitingForApproval -> onApprovalResponse(
                                mode.callId, ApprovalDecision.DENIED, ApprovalScope.ONCE, mode.packageName
                            )
                            else -> onStop()
                        }
                    },
                    enabled = btn.enabled,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = stopIconForMode(mode),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = btn.text,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

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
}

@Composable
internal fun CapsuleRow3(
    row3Spec: CapsuleRenderSpec.Row3Spec,
    inputText: String,
    onInputChange: (String) -> Unit,
    inputEnabled: Boolean,
    autoFocusInput: Boolean,
    onInputFocusChanged: (Boolean) -> Unit,
    showOpenViewer: Boolean,
    onOpenViewer: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocusInput, inputEnabled) {
        if (autoFocusInput && inputEnabled) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            value = inputText,
            onValueChange = onInputChange,
            enabled = inputEnabled,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onInputFocusChanged(it.isFocused) }
                .testTag("qa-capsule-input"),
            placeholder = {
                Text(text = row3Spec.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            maxLines = 2,
            singleLine = false,
        )

        if (showOpenViewer) {
            Spacer(Modifier.width(6.dp))
            NavIconButton(
                icon = Icons.Rounded.Visibility,
                contentDescription = "Open viewer",
                onClick = onOpenViewer,
            )
        }

        Spacer(Modifier.width(6.dp))
        FilledIconButton(
            onClick = onSubmit,
            enabled = inputEnabled && inputText.isNotBlank(),
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = row3Spec.buttonText,
                modifier = Modifier.size(18.dp),
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
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = btn.text, fontSize = 14.sp)
    }
}
