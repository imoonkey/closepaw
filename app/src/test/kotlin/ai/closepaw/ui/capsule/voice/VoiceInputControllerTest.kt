package ai.closepaw.ui.capsule.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests for [VoiceInputController]. Drives recognition transitions through
 * [FakeRecognizer] callbacks — no Android framework required.
 *
 * Each test asserts on BOTH [VoiceInputController.state] and the captured `onText` history so a
 * regression in either the state machine or the visible-text contract is caught.
 */
class VoiceInputControllerTest {

    private class CapturingOnText : (String) -> Unit {
        val calls = mutableListOf<String>()
        override fun invoke(s: String) { calls.add(s) }
    }

    private class CapturingOnToast : (String) -> Unit {
        val calls = mutableListOf<String>()
        override fun invoke(s: String) { calls.add(s) }
    }

    private fun newController(
        factory: FakeRecognizerFactory = FakeRecognizerFactory(),
        onText: CapturingOnText = CapturingOnText(),
        onToast: CapturingOnToast = CapturingOnToast(),
    ): Triple<VoiceInputController, CapturingOnText, CapturingOnToast> =
        Triple(
            VoiceInputController(factory, "en-US", onText, onToast),
            onText,
            onToast,
        )

    @Test
    fun `partial-after-cancel — late onPartial dropped, no onText`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Listening)

        factory.created.last().callbacks.onPartial("hello")
        assertThat(onText.calls).containsExactly("hello").inOrder()
        assertThat(c.state).isEqualTo(VoiceState.Listening)

        c.cancel()
        assertThat(c.state).isEqualTo(VoiceState.Idle)

        factory.created.last().callbacks.onPartial("hello world")
        assertThat(onText.calls).containsExactly("hello").inOrder()
    }

    @Test
    fun `natural-finish-then-late-callback — late onPartial dropped after Listening to Idle`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Listening)

        val cb = factory.created.last().callbacks
        cb.onPartial("hi")
        cb.onFinal("hi there")
        assertThat(c.state).isEqualTo(VoiceState.Idle)
        assertThat(onText.calls).containsExactly("hi", "hi there").inOrder()

        cb.onPartial("hi there friend")
        assertThat(c.state).isEqualTo(VoiceState.Idle)
        assertThat(onText.calls).containsExactly("hi", "hi there").inOrder()
    }

    @Test
    fun `stop-then-partial — partialAtStop frozen, late onPartial ignored`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("base")
        val cb = factory.created.last().callbacks
        cb.onPartial("first")
        assertThat(c.lastPartial).isEqualTo("first")
        assertThat(onText.calls).containsExactly("base first").inOrder()

        c.stop()
        assertThat(c.state).isEqualTo(VoiceState.Stopping)
        assertThat(c.partialAtStop).isEqualTo("first")

        val before = onText.calls.toList()
        cb.onPartial("second")
        assertThat(c.partialAtStop).isEqualTo("first")
        assertThat(c.lastPartial).isEqualTo("first")
        assertThat(onText.calls).isEqualTo(before)
    }

    @Test
    fun `stop-then-NO_MATCH — commits partialAtStop`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("base")
        val cb = factory.created.last().callbacks
        cb.onPartial("hello")
        c.stop()
        assertThat(c.partialAtStop).isEqualTo("hello")

        cb.onError(VoiceError.NoMatch)
        assertThat(c.state).isEqualTo(VoiceState.Idle)
        assertThat(onText.calls.last()).isEqualTo("base hello")
    }

    @Test
    fun `stop-then-final — final wins over partialAtStop`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("base")
        val cb = factory.created.last().callbacks
        cb.onPartial("hello")
        c.stop()

        cb.onFinal("hello world")
        assertThat(c.state).isEqualTo(VoiceState.Idle)
        assertThat(onText.calls.last()).isEqualTo("base hello world")
    }

    @Test
    fun `destroy-mid-listen — all subsequent callbacks dropped`() {
        val factory = FakeRecognizerFactory()
        val (c, onText, _) = newController(factory)

        c.start("")
        val fake = factory.created.last()
        val cb = fake.callbacks
        cb.onPartial("hi")
        assertThat(onText.calls).containsExactly("hi").inOrder()

        c.dispose()
        assertThat(fake.destroyCount).isEqualTo(1)

        cb.onPartial("hi there")
        cb.onFinal("hi there")
        assertThat(onText.calls).containsExactly("hi").inOrder()
    }

    @Test
    fun `double-start-while-stopping — second start is a no-op`() {
        val factory = FakeRecognizerFactory()
        val (c, _, _) = newController(factory)

        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Listening)
        assertThat(factory.created.size).isEqualTo(1)

        c.stop()
        assertThat(c.state).isEqualTo(VoiceState.Stopping)

        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Stopping)
        assertThat(factory.created.size).isEqualTo(1)
    }

    @Test
    fun `availability-flipped-on-start — transitions to Unavailable`() {
        val factory = FakeRecognizerFactory(available = true)
        val (c, _, _) = newController(factory)
        assertThat(c.state).isEqualTo(VoiceState.Idle)

        factory.available = false
        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Unavailable)
        assertThat(factory.created).isEmpty()
    }

    @Test
    fun `init when factory unavailable — starts in Unavailable`() {
        val factory = FakeRecognizerFactory(available = false)
        val (c, _, _) = newController(factory)
        assertThat(c.state).isEqualTo(VoiceState.Unavailable)

        c.start("")
        assertThat(c.state).isEqualTo(VoiceState.Unavailable)
        assertThat(factory.created).isEmpty()
    }
}

internal class FakeRecognizer : Recognizer {
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
        get() = startedWith?.second ?: error("not started")
}

internal class FakeRecognizerFactory(
    var available: Boolean = true,
    val makeRecognizer: () -> FakeRecognizer = ::FakeRecognizer,
) : RecognizerFactory {
    val created = mutableListOf<FakeRecognizer>()
    override fun isAvailable(): Boolean = available
    override fun create(): Recognizer? =
        if (!available) null else makeRecognizer().also { created.add(it) }
}
