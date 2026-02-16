package com.moonkey.androidagent.ui.capsule.surface

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.capsule.NavAction
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleRenderSpec
import com.moonkey.androidagent.ui.overlay.model.NavSpec
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
) {
    var inputText by remember { mutableStateOf("") }
    val isTaskActive = mode !is CapsuleMode.Hidden
    val previousModeState = remember { mutableStateOf<CapsuleMode?>(null) }
    val resolvedPreviousMode = previousMode ?: previousModeState.value
    val renderSpec = remember(mode, isStopPending, resolvedPreviousMode, transientThought) {
        val baseSpec = CapsuleRenderSpec.from(mode, resolvedPreviousMode, isStopPending)
        previousModeState.value = mode
        if (transientThought.isNullOrBlank()) {
            baseSpec
        } else {
            baseSpec.copy(thought = CapsuleRenderSpec.ThoughtSpec(transientThought))
        }
    }
    val navSpec = remember(context, platformMode, mode, hasIsland) {
        NavSpec.from(context, platformMode, hasIsland = hasIsland, mode = mode)
    }

    if (renderSpec.row3?.clearInput == true && inputText.isNotEmpty()) {
        inputText = ""
    }
    val inputEnabled = when {
        context == CapsuleContext.MAIN_APP -> true
        mode is CapsuleMode.Running && platformMode == PlatformMode.ACCESSIBILITY -> false
        mode is CapsuleMode.TakeoverPending && platformMode == PlatformMode.ACCESSIBILITY -> false
        else -> true
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
            if (isTaskActive) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    CapsuleRow1(spec = renderSpec, onClick = onRow1Click)
                    if (mode !is CapsuleMode.Done) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        if (renderSpec.expandedBody != null) {
                            Text(
                                text = renderSpec.expandedBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                        }
                        CapsuleRow2(
                            spec = renderSpec,
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
            }
            renderSpec.row3?.let { row3 ->
                val hintText = if (inputEnabled) row3.hint else "Take over to type note"
                CapsuleRow3(
                    row3Spec = row3.copy(hint = hintText),
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    inputEnabled = inputEnabled,
                    autoFocusInput = autoFocusInput && mode is CapsuleMode.WaitingForInput,
                    onInputFocusChanged = onInputFocusChanged,
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
                        onInputSubmitted()
                    }
                )
                Spacer(Modifier.height(8.dp))
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
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
        }

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
                    },
                    enabled = btn.enabled
                )
            }
        }
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

@Composable
private fun CapsuleRow3(
    row3Spec: CapsuleRenderSpec.Row3Spec,
    inputText: String,
    onInputChange: (String) -> Unit,
    inputEnabled: Boolean,
    autoFocusInput: Boolean,
    onInputFocusChanged: (Boolean) -> Unit,
    showOpenViewer: Boolean,
    onOpenViewer: () -> Unit,
    onSubmit: () -> Unit
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            enabled = inputEnabled,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onInputFocusChanged(it.isFocused) },
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
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
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
            enabled = inputEnabled && inputText.isNotBlank()
        ) {
            Text(
                text = row3Spec.buttonText,
                fontSize = 14.sp
            )
        }
    }
}

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
