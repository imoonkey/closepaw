package ai.closepaw.ui.capsule.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.Locale

/**
 * UI-facing state for the voice-input session. The state machine is:
 *
 *   Idle ──start()──▶ Listening ──stop()──▶ Stopping ──onFinal/onError──▶ Idle
 *                       │                                     │
 *                       └──cancel()──▶ Idle                   └──LangUnavail──▶ Unavailable
 *
 * [Unavailable] is terminal for the session — the user has to restart it to retry.
 */
enum class VoiceState { Idle, Listening, Stopping, Unavailable }

/**
 * Drives a [Recognizer] session and surfaces a small state machine plus the partial/final
 * text-edit semantics specified in `projects/active/voice_input/design_claude.md`.
 *
 * Plain (non-Compose, non-Android) class so it can be exercised by JVM unit tests with a fake
 * recognizer. The companion @Composable [rememberVoiceInputController] wires it into Compose.
 *
 * Threading: assumes single-threaded use from the main thread — same constraint as [Recognizer].
 *
 * Generation counter: every terminal callback, [cancel], and [dispose] bumps [generation] so any
 * late callbacks captured in the previous session's closure are dropped. This is how we keep a
 * delayed `onPartial` from a cancelled or finished session from overwriting freshly-typed text.
 */
class VoiceInputController(
    private val factory: RecognizerFactory,
    private val languageTag: String,
    private val onText: (String) -> Unit,
    private val onToast: (String) -> Unit = {},
) {
    var state: VoiceState =
        if (factory.isAvailable()) VoiceState.Idle else VoiceState.Unavailable
        private set

    var lastPartial: String = ""
        private set

    var partialAtStop: String = ""
        private set

    private var generation: Int = 0
    private var disposed: Boolean = false
    private var recognizer: Recognizer? = null
    private var baseText: String = ""

    fun start(baseText: String) {
        if (disposed || state == VoiceState.Stopping) return
        if (state == VoiceState.Listening) return
        if (!factory.isAvailable()) {
            state = VoiceState.Unavailable
            return
        }
        this.baseText = baseText
        lastPartial = ""
        partialAtStop = ""
        val r = factory.create() ?: run {
            state = VoiceState.Unavailable
            return
        }
        recognizer = r
        val myGen = generation
        r.start(languageTag, object : RecognizerCallbacks {
            override fun onPartial(text: String) {
                if (myGen != generation || disposed) return
                // During Stopping the partialAtStop snapshot is frozen — incoming partials are
                // noise from audio captured before stop() and must not mutate visible text.
                if (state == VoiceState.Stopping) return
                lastPartial = text
                onText(joinWithSpace(this@VoiceInputController.baseText, text))
            }

            override fun onFinal(text: String) {
                if (myGen != generation || disposed) return
                if (text.isNotEmpty()) {
                    onText(joinWithSpace(this@VoiceInputController.baseText, text))
                } else if (state == VoiceState.Stopping && partialAtStop.isNotEmpty()) {
                    onText(joinWithSpace(this@VoiceInputController.baseText, partialAtStop))
                }
                cleanupAfterTerminal(VoiceState.Idle)
            }

            override fun onError(error: VoiceError) {
                if (myGen != generation || disposed) return
                val next = handleError(error)
                cleanupAfterTerminal(next)
            }
        })
        state = VoiceState.Listening
    }

    fun stop() {
        if (state != VoiceState.Listening) return
        partialAtStop = lastPartial
        state = VoiceState.Stopping
        recognizer?.stop()
    }

    fun cancel() {
        if (state == VoiceState.Idle || state == VoiceState.Unavailable) {
            // Even from Idle, bump generation so any late stale callback from a previous
            // session is dropped.
            generation++
            recognizer?.cancel()
            return
        }
        generation++
        recognizer?.cancel()
        state = VoiceState.Idle
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        recognizer?.destroy()
        recognizer = null
    }

    private fun cleanupAfterTerminal(nextState: VoiceState) {
        state = nextState
        generation++
        recognizer?.destroy()
        recognizer = null
    }

    private fun handleError(error: VoiceError): VoiceState = when (error) {
        VoiceError.NoMatch, VoiceError.SpeechTimeout -> {
            if (state == VoiceState.Stopping && partialAtStop.isNotEmpty()) {
                onText(joinWithSpace(baseText, partialAtStop))
            } else {
                onText(baseText)
            }
            VoiceState.Idle
        }
        VoiceError.InsufficientPermissions -> {
            onText(baseText)
            onToast("Microphone permission revoked — re-enable in Settings")
            VoiceState.Idle
        }
        VoiceError.LanguageUnavailable -> {
            onText(baseText)
            onToast("Voice not available for this language")
            VoiceState.Unavailable
        }
        VoiceError.Network, VoiceError.NetworkTimeout -> {
            onText(baseText)
            onToast("Voice needs network for this language")
            VoiceState.Idle
        }
        VoiceError.Busy, VoiceError.ServiceDied, VoiceError.Unknown -> {
            onText(baseText)
            onToast("Voice unavailable")
            VoiceState.Idle
        }
    }

    private fun joinWithSpace(base: String, more: String): String = when {
        base.isEmpty() -> more
        more.isEmpty() -> base
        base.endsWith(' ') -> base + more
        else -> "$base $more"
    }
}

@Composable
fun rememberVoiceInputController(
    factory: RecognizerFactory,
    languageTag: String = Locale.getDefault().toLanguageTag(),
    onText: (String) -> Unit,
    onToast: (String) -> Unit = {},
): VoiceInputController {
    val controller = remember(factory, languageTag) {
        VoiceInputController(factory, languageTag, onText, onToast)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}
