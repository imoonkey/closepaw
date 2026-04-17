package ai.closepaw.ui.capsule.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.NavAction
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.overlay.model.NavSpec

@Composable
fun SmartCapsuleSurface(
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
    hasIsland: Boolean = true,
    previousMode: CapsuleMode? = null,
    transientThought: String? = null,
    onRow1Click: (() -> Unit)? = null,
    onInputFocusChanged: (Boolean) -> Unit = {},
    onInputSubmitted: () -> Unit = {},
    autoFocusInput: Boolean = false,
    pendingInputText: String = "",
    onPendingInputConsumed: () -> Unit = {},
    startupError: String? = null,
    onDismissStartupError: () -> Unit = {},
) {
    var inputText by remember { mutableStateOf("") }

    // Restore preserved input after a session bootstrap failure. Seeds once per
    // non-empty pendingInputText value, then tells the VM to clear it.
    LaunchedEffect(pendingInputText) {
        if (pendingInputText.isNotEmpty()) {
            inputText = pendingInputText
            onPendingInputConsumed()
        }
    }
    val isTaskActive = mode !is CapsuleMode.Hidden
    val renderSpec = remember(mode, isStopPending, previousMode, transientThought) {
        val baseSpec = CapsuleRenderSpec.from(mode, previousMode, isStopPending)
        if (transientThought.isNullOrBlank()) baseSpec
        else baseSpec.copy(thought = CapsuleRenderSpec.ThoughtSpec(transientThought))
    }
    val navSpec = remember(context, platformMode, mode, hasIsland) {
        NavSpec.from(context, platformMode, hasIsland = hasIsland, mode = mode)
    }

    LaunchedEffect(renderSpec.row3?.clearInput) {
        if (renderSpec.row3?.clearInput == true && inputText.isNotEmpty()) {
            inputText = ""
        }
    }

    val inputEnabled = when {
        context == CapsuleContext.MAIN_APP -> true
        mode is CapsuleMode.Running && platformMode == PlatformMode.ACCESSIBILITY -> false
        mode is CapsuleMode.TakeoverPending && platformMode == PlatformMode.ACCESSIBILITY -> false
        else -> true
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 6.dp)
        ) {
            if (isTaskActive) {
                CapsuleRow1(spec = renderSpec, onClick = onRow1Click)
                if (mode !is CapsuleMode.Done) {
                    if (renderSpec.expandedBody != null) {
                        CapsuleDivider()
                        Text(
                            text = renderSpec.expandedBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp)
                        )
                    }
                    CapsuleDivider()
                    CapsuleRow2(
                        spec = renderSpec,
                        navSpec = navSpec,
                        mode = mode,
                        onTakeover = onTakeover,
                        onResume = onResume,
                        onStop = onStop,
                        onDone = { callId -> onUserResponse(callId, "done") },
                        onApprovalResponse = onApprovalResponse,
                        onDismissError = onDismissError,
                        onNavigate = onNavigate,
                    )
                }
            }

            renderSpec.row3?.let { row3 ->
                val hintText = if (inputEnabled) row3.hint else "Take over to type note"
                if (isTaskActive && mode !is CapsuleMode.Done) {
                    CapsuleDivider()
                }
                if (startupError != null) {
                    StartupErrorBanner(
                        message = startupError,
                        onDismiss = onDismissStartupError,
                    )
                }
                CapsuleRow3(
                    row3Spec = row3.copy(hint = hintText),
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    inputEnabled = inputEnabled,
                    autoFocusInput = autoFocusInput && mode is CapsuleMode.WaitingForInput,
                    onInputFocusChanged = onInputFocusChanged,
                    showOpenViewer = false, // §1.4: VD viewer reachable via Row1 nav / island, not idle Row3
                    onOpenViewer = { onNavigate(NavAction.OPEN_VIEWER) },
                    onSubmit = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@CapsuleRow3
                        when (mode) {
                            is CapsuleMode.Hidden -> onSend(text)
                            is CapsuleMode.WaitingForInput -> onUserResponse(mode.callId, text)
                            else -> onSupplement(text)
                        }
                        inputText = ""
                        onInputSubmitted()
                    },
                )
            }
        }
    }
}

@Composable
private fun CapsuleDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
        thickness = 1.dp,
    )
}

@Composable
private fun StartupErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun CapsuleRow1(
    spec: CapsuleRenderSpec,
    onClick: (() -> Unit)?
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spec.dot != null) {
            val dotColor by animateColorAsState(
                targetValue = Color(spec.dot.color),
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
        }

        Text(
            text = spec.thought.text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = spec.thought.alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
