package ai.closepaw.ui.capsule.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.launch

/**
 * CapsuleInputBar — text field + send button.
 *
 * Owns its own draft state (`inputText`) plus the lifecycle effects that mutate it:
 *  - seed from `pendingInputText` after a session bootstrap failure (then signal consume),
 *  - clear on transitions into `WaitingForInput` (per `InputSpec.clearDraft`).
 *
 * Submit-intent routing (Hidden → onSend / WaitingForInput → onUserResponse / else → onSupplement)
 * lives at the surface level, not here — the bar exposes a single `onSubmit(text)` callback.
 */
@Composable
internal fun CapsuleInputBar(
    spec: CapsuleRenderSpec.InputSpec,
    mode: CapsuleMode,
    platformMode: PlatformMode,
    context: CapsuleContext,
    pendingInputText: String,
    onPendingInputConsumed: () -> Unit,
    autoFocusInput: Boolean,
    onInputFocusChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
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

    LaunchedEffect(spec.clearDraft) {
        if (spec.clearDraft && inputText.isNotEmpty()) {
            inputText = ""
        }
    }

    val inputEnabled = when {
        context == CapsuleContext.MAIN_APP -> true
        mode is CapsuleMode.Running && platformMode == PlatformMode.ACCESSIBILITY -> false
        mode is CapsuleMode.TakeoverPending && platformMode == PlatformMode.ACCESSIBILITY -> false
        else -> true
    }
    val hint = if (inputEnabled) spec.hint else "Take over to type note"
    val autoFocus = autoFocusInput && mode is CapsuleMode.WaitingForInput

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus, inputEnabled) {
        if (autoFocus && inputEnabled) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val submit: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            inputText = ""
        }
    }

    val reducedMotion = ClosePawMotion.reducedMotion()
    val scope = rememberCoroutineScope()
    val sendScale = remember { Animatable(1f) }
    val onSendClick: () -> Unit = {
        if (!reducedMotion) {
            scope.launch {
                sendScale.animateTo(
                    1.04f,
                    tween(ClosePawMotion.Standard / 2, easing = ClosePawMotion.EaseOutCubic),
                )
                sendScale.animateTo(
                    1.0f,
                    tween(ClosePawMotion.Standard / 2, easing = ClosePawMotion.EaseOutCubic),
                )
            }
        }
        submit()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            enabled = inputEnabled,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onInputFocusChanged(it.isFocused) }
                .testTag("qa-capsule-input"),
            placeholder = {
                Text(text = hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            maxLines = 2,
            singleLine = false,
        )

        FilledIconButton(
            onClick = onSendClick,
            enabled = inputEnabled && inputText.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer {
                    scaleX = sendScale.value
                    scaleY = sendScale.value
                },
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = spec.submitLabel,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
