package ai.closepaw.ui.overlay.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapsuleRenderSpecTest {

    @Test
    fun `running mode shows stopping disabled when stop pending`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.Running("thinking"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.text).isEqualTo("Stopping...")
        assertThat(stop?.enabled).isFalse()
    }

    @Test
    fun `error mode keeps close action even when stop pending`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.Error("oops"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.text).isEqualTo("Close")
        assertThat(stop?.enabled).isTrue()
    }

    @Test
    fun `entering WaitingForInput from a different mode sets clearInput true`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.WaitingForInput("question?", callId = "c1"),
            previousMode = CapsuleMode.Running("thinking"),
        )
        assertThat(spec.input?.clearDraft).isTrue()
    }

    @Test
    fun `re-entering WaitingForInput from itself leaves clearInput false`() {
        val spec = CapsuleRenderSpec.from(
            mode = CapsuleMode.WaitingForInput("another?", callId = "c2"),
            previousMode = CapsuleMode.WaitingForInput("first?", callId = "c1"),
        )
        assertThat(spec.input?.clearDraft).isFalse()
    }
}
