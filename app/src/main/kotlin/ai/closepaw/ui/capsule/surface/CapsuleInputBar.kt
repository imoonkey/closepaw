package ai.closepaw.ui.capsule.surface

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import ai.closepaw.ui.capsule.voice.RecognizerFactory
import ai.closepaw.ui.capsule.voice.VoicePermissionDisposition
import ai.closepaw.ui.capsule.voice.VoiceState
import ai.closepaw.ui.capsule.voice.rememberVoiceInputController
import ai.closepaw.ui.capsule.voice.rememberVoicePermissionGate
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.launch

/**
 * Dependencies needed for the mic leadingIcon. Callers in MAIN_APP supply a real [activity];
 * overlay callers pass [activity] = null and implement [requestOverlayPermission] to route the
 * RECORD_AUDIO prompt through MainActivity (since overlays cannot host an ActivityResultLauncher).
 *
 * [isPermissionGranted] lets the overlay branch skip the MainActivity hop when RECORD_AUDIO is
 * already granted — otherwise tapping mic with permission would yank the user into MainActivity
 * just for the permission check to no-op.
 */
interface VoiceMicDeps {
    val factory: RecognizerFactory
    val activity: Activity?
    fun isPermissionGranted(): Boolean
    fun requestOverlayPermission()
}

internal val LocalVoiceFeedback = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * CapsuleInputBar — text field with the send button as a trailing icon and an optional
 * voice-mic leadingIcon when [voice] is wired by the caller.
 *
 * The send button lives inside `TextField.trailingIcon` so the field reads as
 * one object. Material3 TextField merges its trailingIcon subtree into the
 * field's own semantics root, so tests must address the send button by its
 * `qa-capsule-send` testTag rather than by contentDescription.
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
    voice: VoiceMicDeps? = null,
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

    // Track the last text the recognizer pushed via onText so typing-during-Listening can be
    // detected as a delta from controller output.
    val controllerLastText = remember { mutableStateOf("") }
    val reportVoiceFeedback by rememberUpdatedState(LocalVoiceFeedback.current)
    val voiceController = if (voice != null) {
        rememberVoiceInputController(
            factory = voice.factory,
            onText = { newText ->
                controllerLastText.value = newText
                inputText = newText
            },
            onToast = { msg ->
                reportVoiceFeedback(msg)
            },
        )
    } else {
        null
    }

    // VoiceInputController.state is backed by mutableStateOf, so a direct read here makes the
    // mic icon (and the Send/IME gating below) recompose automatically on every state change.
    val voiceState = voiceController?.state ?: VoiceState.Unavailable

    val gate = if (voice?.activity != null) {
        rememberVoicePermissionGate(activity = voice.activity!!) { granted ->
            if (granted) {
                voiceController?.start(baseText = inputText)
            } else {
                reportVoiceFeedback("Microphone permission denied")
            }
        }
    } else {
        null
    }

    val hint = when {
        voiceState == VoiceState.Listening -> "Listening…"
        inputEnabled -> spec.hint
        else -> "Take over to type note"
    }
    val autoFocus = autoFocusInput && mode is CapsuleMode.WaitingForInput

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus, inputEnabled) {
        if (autoFocus && inputEnabled) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val submit: () -> Unit = submit@{
        if (voiceState == VoiceState.Listening || voiceState == VoiceState.Stopping) {
            return@submit
        }
        val text = inputText.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            inputText = ""
        }
    }

    val reducedMotion = ClosePawMotion.reducedMotion()
    val scope = rememberCoroutineScope()
    val sendScale = remember { Animatable(1f) }
    val canSend = inputEnabled && inputText.isNotBlank() &&
        voiceState != VoiceState.Listening && voiceState != VoiceState.Stopping
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

    val onMicTap: () -> Unit = mic@{
        val v = voice ?: return@mic
        val act = v.activity
        if (act != null && gate != null) {
            when (gate.disposition()) {
                VoicePermissionDisposition.Granted ->
                    voiceController?.start(baseText = inputText)
                VoicePermissionDisposition.Request ->
                    gate.requestPermission()
                VoicePermissionDisposition.OpenAppSettings -> {
                    reportVoiceFeedback("Microphone permission is disabled. Re-enable it in Android Settings.")
                    gate.openAppSettings()
                }
            }
        } else {
            if (v.isPermissionGranted()) {
                voiceController?.start(baseText = inputText)
            } else {
                reportVoiceFeedback("Microphone permission is required")
                v.requestOverlayPermission()
            }
        }
    }

    val leadingIcon: (@Composable () -> Unit)? = when (voiceState) {
        VoiceState.Unavailable -> null
        VoiceState.Idle -> {
            {
                IconButton(
                    onClick = onMicTap,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("qa-capsule-mic"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Start voice input",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        VoiceState.Listening -> {
            {
                IconButton(
                    onClick = { voiceController?.stop() },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("qa-capsule-mic"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.StopCircle,
                        contentDescription = "Stop voice input",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        VoiceState.Stopping -> {
            {
                IconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("qa-capsule-mic"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.StopCircle,
                        contentDescription = "Stopping voice input",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    TextField(
        value = inputText,
        onValueChange = { newText ->
            // Typing (or IME edits) during Listening cancels the recognizer but keeps the user's
            // text. Detect this by diffing against the last value the controller pushed via onText.
            if (voiceState == VoiceState.Listening &&
                voiceController != null &&
                newText != controllerLastText.value
            ) {
                voiceController.cancel()
            }
            inputText = newText
        },
        enabled = inputEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp)
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onInputFocusChanged(it.isFocused) }
            .testTag("qa-capsule-input"),
        placeholder = {
            Text(text = hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = leadingIcon,
        trailingIcon = {
            Box(modifier = Modifier.padding(end = 4.dp)) {
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = canSend,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("qa-capsule-send")
                        .graphicsLayer {
                            scaleX = sendScale.value
                            scaleY = sendScale.value
                        },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.closePaw.inkFaint.copy(alpha = 0.30f),
                        disabledContentColor = MaterialTheme.closePaw.inkFaint,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = spec.submitLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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
        maxLines = 4,
        singleLine = false,
    )
}
