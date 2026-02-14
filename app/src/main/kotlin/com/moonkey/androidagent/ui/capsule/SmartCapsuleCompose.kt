package com.moonkey.androidagent.ui.capsule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleRenderSpec
import com.moonkey.androidagent.ui.overlay.model.NavSpec

/**
 * Navigation action for capsule context switching.
 */
enum class NavAction {
    MINIMIZE,   // Minimize to status island (VD mode)
    OPEN_APP,   // Open the main app
    OPEN_VIEWER // Open VD viewer
}

/**
 * SmartCapsuleCompose — Compose version of the Smart Capsule for embedding in the main app.
 *
 * Uses [CapsuleRenderSpec] as single source of truth for visual properties,
 * ensuring pixel-perfect consistency with the View-based overlay capsule.
 *
 * Three-row layout:
 *   Row 1: Status dot + thought text (visible when task active)
 *   Row 2: Control buttons + nav icons (visible when task active)
 *   Row 3: Input field + action button (visible per spec)
 *
 * In the main app (CapsuleContext.MAIN_APP):
 *   - When no task active (Hidden mode): only Row 3 is shown (acts as InputDock)
 *   - When task active: all applicable rows are shown
 */
@Composable
fun SmartCapsuleCompose(
    mode: CapsuleMode,
    platformMode: PlatformMode,
    context: CapsuleContext,
    onSend: (String) -> Unit,
    onSupplement: (String) -> Unit,
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onUserResponse: (String, String) -> Unit,
    onDismissError: () -> Unit,
    onNavigate: (NavAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val isTaskActive = mode !is CapsuleMode.Hidden

    // Track previous mode locally for transition-aware rendering (e.g. clearInput)
    val previousModeState = remember { mutableStateOf<CapsuleMode?>(null) }
    val spec = remember(mode) {
        CapsuleRenderSpec.from(mode, previousModeState.value).also {
            previousModeState.value = mode
        }
    }
    val navSpec = remember(context, platformMode, mode) {
        NavSpec.from(context, platformMode, hasIsland = true, mode = mode)
    }

    // Clear input when spec says so (e.g. transition into WaitingForInput)
    if (spec.row3?.clearInput == true && inputText.isNotEmpty()) {
        inputText = ""
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            // Row 1 + Row 2: only when task is active
            AnimatedVisibility(
                visible = isTaskActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    CapsuleRow1(spec = spec)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // Expanded body
                    if (spec.expandedBody != null) {
                        Text(
                            text = spec.expandedBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }
                    CapsuleRow2(
                        spec = spec,
                        navSpec = navSpec,
                        mode = mode,
                        onTakeover = onTakeover,
                        onResume = onResume,
                        onStop = onStop,
                        onDone = { callId -> onUserResponse(callId, "done") },
                        onDismissError = onDismissError,
                        onNavigate = onNavigate
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Row 3: shown when spec defines it
            if (spec.row3 != null) {
                CapsuleRow3(
                    row3Spec = spec.row3,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    showOpenViewer = mode is CapsuleMode.Hidden && navSpec.showWatch,
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
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Row 1: Status dot + thought text ──

@Composable
private fun CapsuleRow1(spec: CapsuleRenderSpec) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        if (spec.dot != null) {
            val dotColor by animateColorAsState(
                targetValue = Color(spec.dot.color),
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
        }

        // Thought text
        Text(
            text = spec.thought.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = spec.thought.alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Row 2: Control buttons + Navigation icons ──

@Composable
private fun CapsuleRow2(
    spec: CapsuleRenderSpec,
    navSpec: NavSpec,
    mode: CapsuleMode,
    onTakeover: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDone: (String) -> Unit,
    onDismissError: () -> Unit,
    onNavigate: (NavAction) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Control buttons — derive from spec
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spec.buttons.primary?.let { btn ->
                CapsuleTextButton(
                    text = "${btn.icon} ${btn.text}",
                    onClick = {
                        when (mode) {
                            is CapsuleMode.Running -> onTakeover()
                            is CapsuleMode.Takeover -> onResume()
                            is CapsuleMode.WaitingForAction -> onDone(mode.callId)
                            else -> {}
                        }
                    },
                    enabled = btn.enabled
                )
            }
            spec.buttons.stop?.let { btn ->
                CapsuleTextButton(
                    text = "${btn.icon} ${btn.text}",
                    onClick = {
                        when (mode) {
                            is CapsuleMode.Error -> onDismissError()
                            else -> onStop()
                        }
                    }
                )
            }
        }

        // Right: Navigation icons — derive from navSpec
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (navSpec.showMinimize) {
                NavIconButton(text = "⊖", onClick = { onNavigate(NavAction.MINIMIZE) })
            }
            if (navSpec.showApp) {
                NavIconButton(text = "📱", onClick = { onNavigate(NavAction.OPEN_APP) })
            }
            if (navSpec.showWatch) {
                NavIconButton(text = "👁", onClick = { onNavigate(NavAction.OPEN_VIEWER) })
            }
        }
    }
}

// ── Row 3: Input field + Action button ──

@Composable
private fun CapsuleRow3(
    row3Spec: CapsuleRenderSpec.Row3Spec,
    inputText: String,
    onInputChange: (String) -> Unit,
    showOpenViewer: Boolean,
    onOpenViewer: () -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = row3Spec.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            maxLines = 3,
            singleLine = false
        )
        if (showOpenViewer) {
            Spacer(Modifier.width(8.dp))
            NavIconButton(text = "👁", onClick = onOpenViewer)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onSubmit,
            enabled = inputText.isNotBlank()
        ) {
            Text(
                text = row3Spec.buttonText,
                fontSize = 14.sp
            )
        }
    }
}

// ── Helpers ──

@Composable
private fun CapsuleTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            }
        )
    }
}

@Composable
private fun NavIconButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 16.sp)
    }
}
