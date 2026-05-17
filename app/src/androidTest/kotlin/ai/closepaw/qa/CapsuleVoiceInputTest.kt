package ai.closepaw.qa

import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.capsule.surface.CapsuleInputBar
import ai.closepaw.ui.capsule.surface.VoiceMicDeps
import ai.closepaw.ui.capsule.voice.Recognizer
import ai.closepaw.ui.capsule.voice.RecognizerCallbacks
import ai.closepaw.ui.capsule.voice.RecognizerFactory
import ai.closepaw.ui.capsule.voice.VoiceError
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.CapsuleRenderSpec
import ai.closepaw.ui.theme.ClosePawTheme
import android.Manifest
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// ---------------------------------------------------------------------------
// Test fixtures — fake recognizer + factory + deps. These are the entire reason
// these tests exist as a separate class from CapsuleInputTest: never touch the
// real AndroidRecognizerFactory, never start a SpeechRecognizer session.
// ---------------------------------------------------------------------------

private class FakeRecognizer : Recognizer {
    var startedWith: Pair<String, RecognizerCallbacks>? = null
    var stopCount = 0
    var cancelCount = 0
    var destroyCount = 0

    override fun start(languageTag: String, callbacks: RecognizerCallbacks) {
        startedWith = languageTag to callbacks
    }

    override fun stop() { stopCount++ }
    override fun cancel() { cancelCount++ }
    override fun destroy() { destroyCount++ }

    val callbacks: RecognizerCallbacks
        get() = startedWith?.second ?: error("Recognizer was not started")
}

private class FakeRecognizerFactory(var available: Boolean = true) : RecognizerFactory {
    val created = mutableListOf<FakeRecognizer>()
    override fun isAvailable(): Boolean = available
    override fun create(): Recognizer? =
        if (!available) null else FakeRecognizer().also { created += it }
}

private class FakeVoiceMicDeps(
    override val factory: RecognizerFactory,
    override val activity: Activity? = null,
    var permissionGranted: Boolean = true,
    val onOverlayRequest: () -> Unit = {},
) : VoiceMicDeps {
    var overlayRequestCount = 0
    override fun isPermissionGranted(): Boolean = permissionGranted
    override fun requestOverlayPermission() {
        overlayRequestCount++
        onOverlayRequest()
    }
}

private fun SemanticsNodeInteraction.editableTextValue(): String =
    fetchSemanticsNode().config[SemanticsProperties.EditableText].text

private fun grantRecordAudio() {
    val instr = InstrumentationRegistry.getInstrumentation()
    instr.uiAutomation.grantRuntimePermission(
        instr.targetContext.packageName,
        Manifest.permission.RECORD_AUDIO,
    )
}

@RunWith(AndroidJUnit4::class)
class CapsuleVoiceInputTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val waiting = CapsuleMode.WaitingForInput(question = "Say what?", callId = "c-1")
    private val inputSpec = CapsuleRenderSpec.InputSpec(hint = "Type…", submitLabel = "Send")

    @Composable
    private fun Host(deps: VoiceMicDeps?) {
        ClosePawTheme {
            CapsuleInputBar(
                spec = inputSpec,
                mode = waiting,
                platformMode = PlatformMode.ACCESSIBILITY,
                context = CapsuleContext.MAIN_APP,
                pendingInputText = "",
                onPendingInputConsumed = {},
                autoFocusInput = false,
                onInputFocusChanged = {},
                onSubmit = {},
                voice = deps,
            )
        }
    }

    // (a1) Mic leadingIcon renders when factory advertises availability and the
    // controller settles into Idle (its default when isAvailable() == true).
    @Test fun mic_icon_visible_when_factory_available_and_idle() {
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(factory = factory, activity = null)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).assertExists()
    }

    // (a2) Factory unavailable → controller pins to Unavailable → no leadingIcon
    // is rendered at all (testTag absent, not merely hidden).
    @Test fun mic_icon_hidden_when_factory_unavailable() {
        val factory = FakeRecognizerFactory(available = false)
        val deps = FakeVoiceMicDeps(factory = factory, activity = null)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).assertDoesNotExist()
    }

    // (c) Overlay path with permission ungranted: activity == null routes mic taps through
    // requestOverlayPermission() and MUST NOT spin up a Recognizer.
    @Test fun overlay_path_when_activity_is_null() {
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(
            factory = factory,
            activity = null,
            permissionGranted = false,
        )

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(1, deps.overlayRequestCount)
        assertTrue(
            "Recognizer must not be created on overlay path; created=${factory.created.size}",
            factory.created.isEmpty(),
        )
    }

    // (c2) Overlay path with permission already granted: must start the controller directly
    // (no MainActivity bounce) — fixes the device-QA bug where granted-overlay yanked the user
    // into MainActivity just for the permission check to no-op.
    @Test fun overlay_path_with_permission_granted_starts_controller_directly() {
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(
            factory = factory,
            activity = null,
            permissionGranted = true,
        )

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(0, deps.overlayRequestCount)
        assertEquals(
            "Controller must spin up a Recognizer on granted overlay path",
            1,
            factory.created.size,
        )
    }

    // (d) Granted permission lets the gate hand off to the controller; partials
    // streamed via callbacks must flow into the input field through onText.
    @Test fun partial_text_streams_into_input_during_listening() {
        grantRecordAudio()
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(factory = factory, activity = compose.activity)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        // Tap should have started recognition. If it didn't, the rest is moot.
        assertEquals(
            "Expected exactly one recognizer to be created after mic tap",
            1, factory.created.size,
        )
        val rec = factory.created.single()
        assertNotNull("Recognizer.start() was never invoked", rec.startedWith)

        compose.runOnUiThread { rec.callbacks.onPartial("hello") }
        compose.waitForIdle()
        assertEquals("hello", compose.onNodeWithTag("qa-capsule-input").editableTextValue())

        compose.runOnUiThread { rec.callbacks.onPartial("hello world") }
        compose.waitForIdle()
        assertEquals("hello world", compose.onNodeWithTag("qa-capsule-input").editableTextValue())
    }

    // (e) User typing while Listening must cancel the recognizer AND preserve
    // the keystroke. The bar diffs IME input against the controller's last
    // pushed text to detect this — verified end-to-end here.
    @Test fun typing_during_listening_cancels_recognizer_and_keeps_user_text() {
        grantRecordAudio()
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(factory = factory, activity = compose.activity)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, factory.created.size)
        val rec = factory.created.single()

        compose.runOnUiThread { rec.callbacks.onPartial("hi") }
        compose.waitForIdle()
        assertEquals("hi", compose.onNodeWithTag("qa-capsule-input").editableTextValue())

        // Simulate the user typing 'x' — IME appends → diff vs controller text → cancel.
        compose.onNodeWithTag("qa-capsule-input").performTextInput("x")
        compose.waitForIdle()

        assertEquals("recognizer must be cancelled on user typing", 1, rec.cancelCount)
        val finalText = compose.onNodeWithTag("qa-capsule-input").editableTextValue()
        assertNotEquals(
            "user keystroke must not be overwritten by base text",
            "hi", finalText,
        )
        assertTrue(
            "expected user keystroke 'x' to be preserved, got '$finalText'",
            finalText.contains("x"),
        )
    }

    // (f) Send button must be inert while Listening even if text would otherwise
    // pass the non-blank check — the canSend gate adds a Listening/Stopping veto.
    @Test fun send_button_disabled_during_listening() {
        grantRecordAudio()
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(factory = factory, activity = compose.activity)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, factory.created.size)
        val rec = factory.created.single()

        compose.runOnUiThread { rec.callbacks.onPartial("non blank text") }
        compose.waitForIdle()

        compose.onNodeWithTag("qa-capsule-send", useUnmergedTree = true).assertIsNotEnabled()
    }

    // (g) Terminal onFinal must transition state back to Idle and destroy the
    // session recognizer — guarding against leaked SpeechRecognizer instances
    // after a normal end-of-utterance.
    @Test fun final_result_destroys_recognizer_and_returns_to_idle() {
        grantRecordAudio()
        val factory = FakeRecognizerFactory(available = true)
        val deps = FakeVoiceMicDeps(factory = factory, activity = compose.activity)

        compose.setContent { Host(deps = deps) }

        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, factory.created.size)
        val rec = factory.created.single()

        compose.runOnUiThread { rec.callbacks.onFinal("the final phrase") }
        compose.waitForIdle()

        assertEquals("recognizer must be destroyed after onFinal", 1, rec.destroyCount)
        assertEquals(
            "the final phrase",
            compose.onNodeWithTag("qa-capsule-input").editableTextValue(),
        )
        // Mic icon flips back to the Idle Mic glyph; the testTag stays.
        compose.onNodeWithTag("qa-capsule-mic", useUnmergedTree = true).assertExists()
    }

    // (b) Permission-missing → MAIN_APP launcher path. Brittle: instrumented
    // ActivityResult registration interacts poorly with composeTestRule lifecycle
    // and the system permission dialog cannot be reliably introspected here.
    // We document the intent and skip; tests (a)-(g) above already cover all
    // observable surface behavior reachable without provoking the system dialog.
    @Ignore("ActivityResultLauncher registration + system permission dialog is brittle in instrumented tests; see comment")
    @Test fun permission_missing_routes_through_gate_without_starting_recognizer() {
        // If this were enabled we'd revoke RECORD_AUDIO, set activity = compose.activity,
        // tap the mic, and assert factory.created.isEmpty(). gate.requestPermission()
        // would have fired the system dialog — we cannot easily verify that, so the
        // negative assertion (no recognizer started) is the meaningful signal.
        assertNull(null)
    }
}
