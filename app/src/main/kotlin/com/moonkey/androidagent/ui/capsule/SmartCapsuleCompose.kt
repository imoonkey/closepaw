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
 * Three-row layout:
 *   Row 1: Status dot + thought text (visible when task active)
 *   Row 2: Control buttons + nav icons (visible when task active)
 *   Row 3: Input field + action button (always visible unless WaitingForAction/Done/Error)
 *
 * In the main app (CapsuleContext.MAIN_APP):
 *   - When no task active (Hidden mode): only Row 3 is shown (acts as InputDock replacement)
 *   - When task active: all 3 rows are shown
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
                    CapsuleRow1(mode = mode)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // Expanded body for WaitingFor* modes
                    ExpandedBody(mode = mode)
                    CapsuleRow2(
                        mode = mode,
                        platformMode = platformMode,
                        context = context,
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

            // Row 3: always visible except WaitingForAction, Done, Error
            if (shouldShowRow3(mode)) {
                CapsuleRow3(
                    mode = mode,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSubmit = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@CapsuleRow3
                        when (mode) {
                            is CapsuleMode.Hidden -> {
                                onSend(text)
                                inputText = ""
                            }
                            is CapsuleMode.WaitingForInput -> {
                                onUserResponse(mode.callId, text)
                                inputText = ""
                            }
                            else -> {
                                onSupplement(text)
                                inputText = ""
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ── Row 1: Status dot + thought text ──

@Composable
private fun CapsuleRow1(mode: CapsuleMode) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        val dotColor by animateColorAsState(
            targetValue = when (mode) {
                is CapsuleMode.Running -> Color(0xFF2563EB)       // Blue
                is CapsuleMode.TakeoverPending -> Color(0xFFF59E0B) // Amber
                is CapsuleMode.Takeover -> Color(0xFFF59E0B)       // Amber
                is CapsuleMode.Done -> Color(0xFF0D9488)           // Teal
                is CapsuleMode.Error -> Color(0xFFEF4444)          // Red
                else -> Color.Transparent
            },
            label = "dotColor"
        )

        val showDot = mode !is CapsuleMode.WaitingForInput &&
            mode !is CapsuleMode.WaitingForAction &&
            mode !is CapsuleMode.Hidden

        if (showDot) {
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
            text = when (mode) {
                is CapsuleMode.Running -> mode.thought.ifEmpty { "思考中..." }
                is CapsuleMode.TakeoverPending -> "正在交接..."
                is CapsuleMode.Takeover -> mode.lastThought.ifEmpty { "已暂停" }
                is CapsuleMode.Done -> "✓ ${mode.message}"
                is CapsuleMode.Error -> "⚠ ${mode.message}"
                is CapsuleMode.WaitingForInput -> "💬 等待答复"
                is CapsuleMode.WaitingForAction -> "✋ 操作手机"
                is CapsuleMode.Hidden -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (mode) {
                is CapsuleMode.Takeover -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Expanded Body (for WaitingFor* modes) ──

@Composable
private fun ExpandedBody(mode: CapsuleMode) {
    val bodyText = when (mode) {
        is CapsuleMode.WaitingForInput -> mode.question
        is CapsuleMode.WaitingForAction -> mode.instruction
        else -> null
    }

    if (bodyText != null) {
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )
    }
}

// ── Row 2: Control buttons + Navigation icons ──

@Composable
private fun CapsuleRow2(
    mode: CapsuleMode,
    platformMode: PlatformMode,
    context: CapsuleContext,
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
        // Left: Control buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (mode) {
                is CapsuleMode.Running -> {
                    CapsuleTextButton(text = "✋ 接管", onClick = onTakeover)
                    CapsuleTextButton(text = "⏹ 停止", onClick = onStop)
                }
                is CapsuleMode.TakeoverPending -> {
                    CapsuleTextButton(text = "✋ 交接中", onClick = {}, enabled = false)
                    CapsuleTextButton(text = "⏹ 停止", onClick = onStop)
                }
                is CapsuleMode.Takeover -> {
                    CapsuleTextButton(text = "▶ 继续", onClick = onResume)
                    CapsuleTextButton(text = "⏹ 停止", onClick = onStop)
                }
                is CapsuleMode.WaitingForInput -> {
                    CapsuleTextButton(text = "⏹ 停止", onClick = onStop)
                }
                is CapsuleMode.WaitingForAction -> {
                    CapsuleTextButton(
                        text = "✅ 完成",
                        onClick = { onDone(mode.callId) }
                    )
                    CapsuleTextButton(text = "⏹ 停止", onClick = onStop)
                }
                is CapsuleMode.Error -> {
                    CapsuleTextButton(text = "✕ 关闭", onClick = onDismissError)
                }
                else -> {}
            }
        }

        // Right: Navigation icons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // [1] Minimize: only in VD mode AND not when already in main app
            if (platformMode == PlatformMode.VIRTUAL_DISPLAY && context != CapsuleContext.MAIN_APP) {
                NavIconButton(text = "⊖", onClick = { onNavigate(NavAction.MINIMIZE) })
            }
            // [2] App: never when already in app
            if (context != CapsuleContext.MAIN_APP) {
                NavIconButton(text = "📱", onClick = { onNavigate(NavAction.OPEN_APP) })
            }
            // [3] Watch: never in A11y; never when already viewing
            if (platformMode != PlatformMode.ACCESSIBILITY && context != CapsuleContext.SCREEN_VIEWING) {
                NavIconButton(text = "👁", onClick = { onNavigate(NavAction.OPEN_VIEWER) })
            }
        }
    }
}

// ── Row 3: Input field + Action button ──

@Composable
private fun CapsuleRow3(
    mode: CapsuleMode,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val (placeholder, buttonText) = when (mode) {
        is CapsuleMode.Hidden -> "有什么可以帮你?" to "发送 →"
        is CapsuleMode.WaitingForInput -> "输入你的答复..." to "发送 →"
        else -> "有想法? 补充一下..." to "补充"
    }

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
                    text = placeholder,
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
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onSubmit,
            enabled = inputText.isNotBlank()
        ) {
            Text(
                text = buttonText,
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

private fun shouldShowRow3(mode: CapsuleMode): Boolean = when (mode) {
    is CapsuleMode.WaitingForAction -> false
    is CapsuleMode.Done -> false
    is CapsuleMode.Error -> false
    else -> true
}
