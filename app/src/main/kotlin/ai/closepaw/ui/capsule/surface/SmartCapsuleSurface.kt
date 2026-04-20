package ai.closepaw.ui.capsule.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper

/**
 * SmartCapsuleSurface — orchestrator composable for the Smart Capsule.
 *
 * Renders, top-to-bottom: status line, optional detail body, control bar,
 * optional input bar (with optional startup-error banner above it).
 *
 * The orchestrator owns derivation (`CapsuleRenderSpec`, `NavSpec`) and submit-intent
 * routing, but not the input draft state — that lives in [CapsuleInputBar].
 */
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
    onStatusClick: (() -> Unit)? = null,
    onInputFocusChanged: (Boolean) -> Unit = {},
    onInputSubmitted: () -> Unit = {},
    autoFocusInput: Boolean = false,
    pendingInputText: String = "",
    onPendingInputConsumed: () -> Unit = {},
    startupError: String? = null,
    onDismissStartupError: () -> Unit = {},
    onStartupErrorClick: (() -> Unit)? = null,
) {
    val renderSpec = remember(mode, isStopPending, previousMode, transientThought) {
        val baseSpec = CapsuleRenderSpec.from(mode, previousMode, isStopPending)
        if (transientThought.isNullOrBlank()) baseSpec
        else baseSpec.copy(thought = CapsuleRenderSpec.ThoughtSpec(transientThought))
    }
    val navSpec = remember(context, platformMode, mode, hasIsland) {
        NavSpec.from(context, platformMode, hasIsland = hasIsland, mode = mode)
    }
    val isTaskActive = mode !is CapsuleMode.Hidden
    val shape = MaterialTheme.shapes.large
    val spacing = MaterialTheme.closePaw.spacing

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .foldedPaper(shape),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.md)
                .navigationBarsPadding()
                .padding(top = spacing.sm, bottom = 6.dp),
        ) {
            if (isTaskActive) {
                CapsuleStatusLine(spec = renderSpec, onClick = onStatusClick)
                if (mode !is CapsuleMode.Done) {
                    if (renderSpec.expandedBody != null) {
                        CapsuleDivider()
                        CapsuleDetailBody(text = renderSpec.expandedBody)
                    }
                    CapsuleDivider()
                    CapsuleControlBar(
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

            renderSpec.input?.let { input ->
                if (isTaskActive && mode !is CapsuleMode.Done) {
                    CapsuleDivider()
                }
                if (startupError != null) {
                    StartupErrorBanner(
                        message = startupError,
                        onDismiss = onDismissStartupError,
                        onClick = onStartupErrorClick,
                    )
                }
                CapsuleInputBar(
                    spec = input,
                    mode = mode,
                    platformMode = platformMode,
                    context = context,
                    pendingInputText = pendingInputText,
                    onPendingInputConsumed = onPendingInputConsumed,
                    autoFocusInput = autoFocusInput,
                    onInputFocusChanged = onInputFocusChanged,
                    onSubmit = { text ->
                        when (mode) {
                            is CapsuleMode.Hidden -> onSend(text)
                            is CapsuleMode.WaitingForInput -> onUserResponse(mode.callId, text)
                            else -> onSupplement(text)
                        }
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
private fun CapsuleDetailBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    )
}

@Composable
private fun StartupErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val surfaceModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .let { if (onClick != null) it.clickable { onClick() } else it }
    Surface(
        modifier = surfaceModifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
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
private fun CapsuleStatusLine(
    spec: CapsuleRenderSpec,
    onClick: (() -> Unit)?,
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onClick() }
            .padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spec.dot != null) {
            val dotColor by animateColorAsState(
                targetValue = spec.dot.status.toStatusColor(),
                animationSpec = tween(ClosePawMotion.StatusFlip, easing = ClosePawMotion.EaseInOutSine),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
        }

        Text(
            text = spec.thought.text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (spec.thought.dimmed) 0.6f else 1f,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
